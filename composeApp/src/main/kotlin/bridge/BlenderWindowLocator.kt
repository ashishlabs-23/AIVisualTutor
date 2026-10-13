package bridge

import java.awt.Rectangle
import java.util.Base64
import java.util.concurrent.TimeUnit
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
    val isForeground: Boolean
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
}

/**
 * Windows implementation that uses lightweight Win32 API queries
 * executed via PowerShell C# P/Invoke without external binary or .NET dependencies.
 */
class WindowsBlenderWindowLocator : BlenderWindowLocator {

    override suspend fun locateBlenderWindow(): BlenderWindowResult = withContext(Dispatchers.IO) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return@withContext BlenderWindowResult.Error("Desktop window capture is only supported on Windows.")
        }

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
            ?: return@withContext BlenderWindowResult.Error("Could not inspect Windows desktop for Blender window.")

        val lastLine = output.lineSequence().lastOrNull()?.trim() ?: ""
        when {
            lastLine == "NOT_RUNNING" -> BlenderWindowResult.NotRunning
            lastLine == "NOT_FOUND" -> BlenderWindowResult.Error("Blender is running, but no active Blender workspace window was identified.")
            lastLine.startsWith("FOUND|") -> {
                val parts = lastLine.split('|')
                if (parts.size < 12) {
                    return@withContext BlenderWindowResult.Error("Malformed window query response from Windows: $lastLine")
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
