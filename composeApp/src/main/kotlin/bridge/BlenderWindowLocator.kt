package bridge

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.win32.W32APIOptions
import java.awt.Rectangle
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Metadata describing a discovered Blender desktop window.
 */
data class BlenderWindowInfo(
    val hwnd: Long,
    val processId: Long,
    val title: String,
    val bounds: Rectangle,
    val isVisible: Boolean,
    val isMinimized: Boolean,
    val isMaximized: Boolean,
    val isForeground: Boolean,
    val clientOrigin: java.awt.Point = java.awt.Point(bounds.x, bounds.y),
    val clientSize: java.awt.Dimension = java.awt.Dimension(bounds.width, bounds.height)
)

/**
 * Outcome of attempting to locate the Blender window.
 */
sealed interface BlenderWindowResult {
    data class Found(val window: BlenderWindowInfo) : BlenderWindowResult
    object NotRunning : BlenderWindowResult
    data class Minimized(val window: BlenderWindowInfo) : BlenderWindowResult
    data class Error(val message: String) : BlenderWindowResult
}

/**
 * Contract for locating and managing the Blender application window.
 * Placed behind an interface for headless, deterministic unit testing.
 */
interface BlenderWindowLocator {
    suspend fun locateBlenderWindow(): BlenderWindowResult
    suspend fun bringBlenderToForeground(hwnd: Long): Boolean
    suspend fun isWindowValid(hwnd: Long): Boolean = hwnd != 0L
}

internal interface JnaBlenderUser32 : Library {
    companion object {
        val INSTANCE: JnaBlenderUser32 by lazy {
            Native.load("user32", JnaBlenderUser32::class.java, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    @Structure.FieldOrder("x", "y")
    open class POINT : Structure() {
        @JvmField var x: Int = 0
        @JvmField var y: Int = 0
    }

    fun EnumWindows(lpEnumFunc: com.sun.jna.Callback, lParam: Pointer?): Boolean
    fun GetWindowThreadProcessId(hWnd: Pointer, lpdwProcessId: IntArray): Int
    fun IsWindowVisible(hWnd: Pointer): Boolean
    fun IsIconic(hWnd: Pointer): Boolean
    fun IsZoomed(hWnd: Pointer): Boolean
    fun GetWindowRect(hWnd: Pointer, lpRect: IntArray): Boolean
    fun GetClientRect(hWnd: Pointer, lpRect: IntArray): Boolean
    fun ClientToScreen(hWnd: Pointer, lpPoint: POINT): Boolean
    fun GetWindowTextW(hWnd: Pointer, lpString: CharArray, nMaxCount: Int): Int
    fun GetClassNameW(hWnd: Pointer, lpClassName: CharArray, nMaxCount: Int): Int
    fun GetForegroundWindow(): Pointer
    fun ShowWindow(hWnd: Pointer, nCmdShow: Int): Boolean
    fun SetForegroundWindow(hWnd: Pointer): Boolean
    fun BringWindowToTop(hWnd: Pointer): Boolean
    fun IsWindow(hWnd: Pointer): Boolean
}

/**
 * Windows implementation that locates the Blender window using ultra-low latency in-process
 * Win32 User32 queries via JNA, with transparent fallback to PowerShell P/Invoke.
 */
class WindowsBlenderWindowLocator : BlenderWindowLocator {

    override suspend fun locateBlenderWindow(): BlenderWindowResult = withContext(Dispatchers.IO) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return@withContext BlenderWindowResult.Error("Desktop window capture is only supported on Windows.")
        }

        // Fast in-process JNA query first (< 1 millisecond, zero process spawn overhead)
        try {
            val jnaResult = locateViaJna()
            if (jnaResult != null && jnaResult !is BlenderWindowResult.NotRunning) {
                return@withContext jnaResult
            }
            if (jnaResult is BlenderWindowResult.NotRunning) {
                return@withContext BlenderWindowResult.NotRunning
            }
        } catch (_: Throwable) {
            // Fall through to PowerShell fallback if JNA native call encounters an issue
        }

        locateViaPowerShell()
    }

    private fun locateViaJna(): BlenderWindowResult? {
        val user32 = JnaBlenderUser32.INSTANCE
        val fgHwnd = user32.GetForegroundWindow()

        var bestInfo: BlenderWindowInfo? = null
        var bestArea = -1L
        var foundMinimized: BlenderWindowInfo? = null
        var isProcessRunning = false

        // Check if blender.exe process is alive
        val isBlenderAlive = runCatching {
            ProcessHandle.allProcesses().asSequence().any { proc ->
                proc.info().command().orElse("").contains("blender", ignoreCase = true)
            }
        }.getOrDefault(false)

        val callback = object : com.sun.jna.Callback {
            fun callback(hWnd: Pointer, lParam: Pointer?): Boolean {
                val pidArr = IntArray(1)
                user32.GetWindowThreadProcessId(hWnd, pidArr)
                val pid = pidArr[0]
                if (pid <= 0) return true

                val titleBuf = CharArray(512)
                user32.GetWindowTextW(hWnd, titleBuf, 512)
                val title = String(titleBuf).trim('\u0000')

                val classBuf = CharArray(256)
                user32.GetClassNameW(hWnd, classBuf, 256)
                val className = String(classBuf).trim('\u0000')

                val isBlenderClass = className.equals("GHOST_WindowClass", ignoreCase = true)
                val isBlenderTitle = title.contains("blender", ignoreCase = true)

                val isBlenderWindow = if (isBlenderClass) {
                    true
                } else if (isBlenderTitle) {
                    val proc = ProcessHandle.of(pid.toLong()).orElse(null)
                    val cmd = proc?.info()?.command()?.orElse("") ?: ""
                    cmd.contains("blender", ignoreCase = true)
                } else {
                    false
                }

                if (!isBlenderWindow) {
                    return true
                }
                isProcessRunning = true

                val visible = user32.IsWindowVisible(hWnd)
                val rect = IntArray(4)
                user32.GetWindowRect(hWnd, rect)
                val width = rect[2] - rect[0]
                val height = rect[3] - rect[1]
                val area = width.toLong() * height.toLong()

                val clientRect = IntArray(4)
                user32.GetClientRect(hWnd, clientRect)
                val clientW = clientRect[2] - clientRect[0]
                val clientH = clientRect[3] - clientRect[1]

                val pt = JnaBlenderUser32.POINT().apply { x = 0; y = 0 }
                user32.ClientToScreen(hWnd, pt)

                val isIconic = user32.IsIconic(hWnd) || rect[0] <= -30000
                val isZoomed = user32.IsZoomed(hWnd)
                val isFg = (hWnd == fgHwnd)
                val hwndLong = Pointer.nativeValue(hWnd)

                val info = BlenderWindowInfo(
                    hwnd = hwndLong,
                    processId = pid.toLong(),
                    title = title,
                    bounds = Rectangle(rect[0], rect[1], width, height),
                    isVisible = visible,
                    isMinimized = isIconic,
                    isMaximized = isZoomed,
                    isForeground = isFg,
                    clientOrigin = java.awt.Point(pt.x, pt.y),
                    clientSize = java.awt.Dimension(clientW, clientH)
                )

                if (isIconic && foundMinimized == null) {
                    foundMinimized = info
                } else if (visible && area > bestArea && width > 100 && height > 100) {
                    bestArea = area
                    bestInfo = info
                }
                return true
            }
        }

        user32.EnumWindows(callback, null)

        return when {
            bestInfo != null -> BlenderWindowResult.Found(bestInfo!!)
            foundMinimized != null -> BlenderWindowResult.Minimized(foundMinimized!!)
            !isProcessRunning && !isBlenderAlive -> BlenderWindowResult.NotRunning
            else -> null // Request fallback to PowerShell
        }
    }

    private fun locateViaPowerShell(): BlenderWindowResult {
        val csharpSource = """
            using System;
            using System.Diagnostics;
            using System.Runtime.InteropServices;
            using System.Text;

            public static class AivtBlenderFinder {
                [StructLayout(LayoutKind.Sequential)]
                public struct RECT { public int Left, Top, Right, Bottom; }

                public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

                [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
                [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
                [DllImport("user32.dll")] static extern bool IsIconic(IntPtr hWnd);
                [DllImport("user32.dll")] static extern bool IsZoomed(IntPtr hWnd);
                [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
                [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
                [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
                [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();

                public static string Find() {
                    var blenderProcesses = Process.GetProcessesByName("blender");
                    if (blenderProcesses.Length == 0) {
                        return "NOT_RUNNING";
                    }

                    IntPtr bestHwnd = IntPtr.Zero;
                    uint bestPid = 0;
                    string bestTitle = "";
                    RECT bestRect = new RECT();
                    bool bestVisible = false;
                    bool bestMinimized = false;
                    bool bestMaximized = false;
                    long maxArea = -1;

                    IntPtr fg = GetForegroundWindow();

                    EnumWindows((hWnd, lParam) => {
                        uint pid;
                        GetWindowThreadProcessId(hWnd, out pid);
                        bool isBlenderProcess = false;
                        foreach (var p in blenderProcesses) {
                            if (p.Id == (int)pid) { isBlenderProcess = true; break; }
                        }

                        if (!isBlenderProcess) return true;

                        var sb = new StringBuilder(512);
                        GetWindowText(hWnd, sb, 512);
                        string title = sb.ToString();

                        bool visible = IsWindowVisible(hWnd);
                        bool minimized = IsIconic(hWnd);
                        bool maximized = IsZoomed(hWnd);
                        RECT r;
                        GetWindowRect(hWnd, out r);
                        int width = r.Right - r.Left;
                        int height = r.Bottom - r.Top;
                        long area = (long)width * height;

                        // Prefer visible non-zero windows, or minimized main window
                        if (minimized && bestHwnd == IntPtr.Zero) {
                            bestHwnd = hWnd;
                            bestPid = pid;
                            bestTitle = title;
                            bestRect = r;
                            bestVisible = visible;
                            bestMinimized = true;
                            bestMaximized = false;
                        } else if (visible && area > maxArea && (width > 100 && height > 100)) {
                            maxArea = area;
                            bestHwnd = hWnd;
                            bestPid = pid;
                            bestTitle = title;
                            bestRect = r;
                            bestVisible = visible;
                            bestMinimized = false;
                            bestMaximized = maximized;
                        }

                        return true;
                    }, IntPtr.Zero);

                    if (bestHwnd == IntPtr.Zero) {
                        return "NOT_FOUND";
                    }

                    int w = bestRect.Right - bestRect.Left;
                    int h = bestRect.Bottom - bestRect.Top;
                    bool isFg = (bestHwnd == fg);
                    string b64Title = Convert.ToBase64String(Encoding.UTF8.GetBytes(bestTitle));

                    return "FOUND|" + bestHwnd.ToInt64() + "|" + bestPid + "|" + b64Title + "|" +
                           bestRect.Left + "|" + bestRect.Top + "|" + w + "|" + h + "|" +
                           bestVisible + "|" + bestMinimized + "|" + bestMaximized + "|" + isFg;
                }
            }
        """.trimIndent()

        val output = runPowerShell(csharpSource, "[AivtBlenderFinder]::Find()")
            ?: return BlenderWindowResult.Error("Could not inspect Windows desktop for Blender window.")

        val lastLine = output.lineSequence().lastOrNull()?.trim() ?: ""
        return when {
            lastLine == "NOT_RUNNING" -> BlenderWindowResult.NotRunning
            lastLine == "NOT_FOUND" -> BlenderWindowResult.Error("Blender is running, but no active Blender workspace window was identified.")
            lastLine.startsWith("FOUND|") -> {
                val parts = lastLine.split('|')
                if (parts.size < 12) {
                    return BlenderWindowResult.Error("Malformed window query response from Windows: $lastLine")
                }
                val hwnd = parts[1].toLongOrNull() ?: 0L
                val pid = parts[2].toLongOrNull() ?: 0L
                val title = runCatching {
                    String(Base64.getDecoder().decode(parts[3]), Charsets.UTF_8)
                }.getOrDefault("Blender")
                val x = parts[4].toIntOrNull() ?: 0
                val y = parts[5].toIntOrNull() ?: 0
                val w = parts[6].toIntOrNull() ?: 0
                val h = parts[7].toIntOrNull() ?: 0
                val visible = parts[8].toBoolean()
                val minimized = parts[9].toBoolean()
                val maximized = parts[10].toBoolean()
                val isFg = parts[11].toBoolean()

                val info = BlenderWindowInfo(
                    hwnd = hwnd,
                    processId = pid,
                    title = title,
                    bounds = Rectangle(x, y, w, h),
                    isVisible = visible,
                    isMinimized = minimized,
                    isMaximized = maximized,
                    isForeground = isFg
                )

                if (minimized) {
                    BlenderWindowResult.Minimized(info)
                } else if (w <= 0 || h <= 0) {
                    BlenderWindowResult.Error("Blender workspace has invalid window dimensions (${w}x${h}).")
                } else {
                    BlenderWindowResult.Found(info)
                }
            }
            else -> BlenderWindowResult.Error("Unexpected response while querying Blender window: $lastLine")
        }
    }

    override suspend fun bringBlenderToForeground(hwnd: Long): Boolean = withContext(Dispatchers.IO) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true) || hwnd == 0L) {
            return@withContext false
        }

        // Fast in-process JNA activation
        try {
            val user32 = JnaBlenderUser32.INSTANCE
            val ptr = Pointer(hwnd)
            if (user32.IsIconic(ptr)) {
                user32.ShowWindow(ptr, 9) // SW_RESTORE only when minimized
            }
            user32.BringWindowToTop(ptr)
            user32.SetForegroundWindow(ptr)
            if (user32.GetForegroundWindow() == ptr) {
                return@withContext true
            }
        } catch (_: Throwable) {
        }

        val csharpSource = """
            using System;
            using System.Runtime.InteropServices;

            public static class AivtActivateWindow {
                [DllImport("user32.dll")] static extern bool IsWindow(IntPtr hWnd);
                [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
                [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr hWnd);
                [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
                [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr lpdwProcessId);
                [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
                [DllImport("user32.dll")] static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
                [DllImport("user32.dll")] static extern IntPtr SetFocus(IntPtr hWnd);

                public static bool Activate(long rawHwnd) {
                    IntPtr h = new IntPtr(rawHwnd);
                    if (!IsWindow(h)) return false;

                    uint own = GetCurrentThreadId();
                    uint target = GetWindowThreadProcessId(h, IntPtr.Zero);
                    bool attached = target != 0 && target != own && AttachThreadInput(own, target, true);

                    try {
                        BringWindowToTop(h);
                        SetForegroundWindow(h);
                        if (attached) SetFocus(h);
                        return GetForegroundWindow() == h;
                    } finally {
                        if (attached) AttachThreadInput(own, target, false);
                    }
                }
            }
        """.trimIndent()

        val output = runPowerShell(csharpSource, "[AivtActivateWindow]::Activate($hwnd)")
        output?.lineSequence()?.lastOrNull()?.trim() == "True"
    }

    override suspend fun isWindowValid(hwnd: Long): Boolean = withContext(Dispatchers.IO) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true) || hwnd == 0L) {
            return@withContext false
        }
        try {
            if (JnaBlenderUser32.INSTANCE.IsWindow(Pointer(hwnd))) {
                return@withContext true
            }
        } catch (_: Throwable) {
        }
        val csharpSource = """
            using System;
            using System.Runtime.InteropServices;
            public static class AivtValidateWindow {
                [DllImport("user32.dll")] static extern bool IsWindow(IntPtr hWnd);
                public static bool Check(long raw) { return IsWindow(new IntPtr(raw)); }
            }
        """.trimIndent()
        val output = runPowerShell(csharpSource, "[AivtValidateWindow]::Check($hwnd)")
        output?.lineSequence()?.lastOrNull()?.trim() == "True"
    }

    private fun runPowerShell(source: String, expression: String): String? {
        val encoded = Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8))
        val script = "Add-Type -TypeDefinition ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encoded'))); $expression"
        val process = try {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            return null
        }

        if (!process.waitFor(4, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }

        if (process.exitValue() != 0) return null
        return process.inputStream.bufferedReader().use { it.readText().trim() }.takeIf { it.isNotBlank() }
    }
}

/**
 * Resolves the installed Blender executable on the host machine.
 */
object BlenderExecutableLocator {
    fun findBlenderExecutable(): java.io.File? {
        val env = System.getenv("AIVT_BLENDER_EXECUTABLE")?.takeIf { it.isNotBlank() }
        val candidates = listOfNotNull(
            env,
            "C:\\Program Files\\Blender Foundation\\Blender 5.2\\blender.exe",
            "C:\\Program Files\\Blender Foundation\\Blender 4.2\\blender.exe",
            "C:\\Program Files\\Blender Foundation\\Blender 4.1\\blender.exe",
            "C:\\Program Files\\Blender Foundation\\Blender 4.0\\blender.exe",
            "D:\\blender.exe"
        ).map { java.io.File(it) }
        return candidates.firstOrNull { it.isFile }
    }
}

