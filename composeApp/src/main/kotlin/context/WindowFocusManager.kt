package context

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** State captured before the selector changes foreground focus. HWND is a native window handle. */
data class PreviousForegroundWindow(
    val hwnd: Long,
    val processId: Long,
    val visible: Boolean,
    val minimized: Boolean,
    val maximized: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val tutorProcess: Boolean
)

interface WindowFocusManager {
    suspend fun capture(): PreviousForegroundWindow?
    suspend fun restore(previous: PreviousForegroundWindow?): Boolean
}

/** Native calls remain behind this seam so the selection lifecycle can be tested headlessly. */
class WindowsWindowFocusManager : WindowFocusManager {
    override suspend fun capture(): PreviousForegroundWindow? = withContext(Dispatchers.IO) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return@withContext null
        val source = """
            using System; using System.Runtime.InteropServices;
            public static class AivtWindowState {
              [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L,T,R,B; }
              [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
              [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h,out uint pid);
              [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
              [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
              [DllImport("user32.dll")] static extern bool IsZoomed(IntPtr h);
              [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h,out RECT r);
              public static string Read() {
                IntPtr h=GetForegroundWindow(); uint pid; GetWindowThreadProcessId(h,out pid);
                if(h==IntPtr.Zero || pid==0) return "";
                RECT r; if(!GetWindowRect(h,out r)) r=new RECT();
                return h.ToInt64()+"|"+pid+"|"+IsWindowVisible(h)+"|"+IsIconic(h)+"|"+IsZoomed(h)+"|"+r.L+"|"+r.T+"|"+(r.R-r.L)+"|"+(r.B-r.T);
              }
            }
        """.trimIndent()
        val output = runPowerShell(source, "[AivtWindowState]::Read()") ?: return@withContext null
        val fields = output.lineSequence().lastOrNull()?.split('|') ?: return@withContext null
        if (fields.size != 9) return@withContext null
        runCatching {
            val pid = fields[1].toLong()
            PreviousForegroundWindow(
                hwnd = fields[0].toLong(), processId = pid,
                visible = fields[2].toBoolean(), minimized = fields[3].toBoolean(), maximized = fields[4].toBoolean(),
                x = fields[5].toInt(), y = fields[6].toInt(), width = fields[7].toInt(), height = fields[8].toInt(),
                tutorProcess = pid == ProcessHandle.current().pid()
            )
        }.getOrNull()
    }

    override suspend fun restore(previous: PreviousForegroundWindow?): Boolean = withContext(Dispatchers.IO) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true) || previous == null) return@withContext false
        // Don't activate a window that was hidden/minimized before selection. The
        // tutor's Compose visibility is restored separately by Main.kt.
        if (!previous.visible || previous.minimized) return@withContext false
        val source = """
            using System; using System.Runtime.InteropServices;
            public static class AivtRestoreWindow {
              [DllImport("user32.dll")] static extern bool IsWindow(IntPtr h);
              [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr h);
              [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr h);
              [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
              [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h,IntPtr pid);
              [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
              [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a,uint b,bool attach);
              [DllImport("user32.dll")] static extern IntPtr SetFocus(IntPtr h);
              public static bool Restore(long raw) {
                var h=new IntPtr(raw); if(!IsWindow(h)) return false;
                uint own=GetCurrentThreadId(), target=GetWindowThreadProcessId(h,IntPtr.Zero);
                bool attached=target!=0 && target!=own && AttachThreadInput(own,target,true);
                try { BringWindowToTop(h); SetForegroundWindow(h); if(attached) SetFocus(h); return GetForegroundWindow()==h; }
                finally { if(attached) AttachThreadInput(own,target,false); }
              }
            }
        """.trimIndent()
        runPowerShell(source, "[AivtRestoreWindow]::Restore(${previous.hwnd})")?.lineSequence()?.lastOrNull()?.trim() == "True"
    }

    private fun runPowerShell(source: String, expression: String): String? {
        val encoded = java.util.Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8))
        val script = "Add-Type -TypeDefinition ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encoded'))); $expression"
        val process = try {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                .redirectErrorStream(true).start()
        } catch (_: Exception) { return null }
        if (!process.waitFor(4, TimeUnit.SECONDS)) { process.destroyForcibly(); return null }
        if (process.exitValue() != 0) return null
        return process.inputStream.bufferedReader().use { it.readText().trim() }.takeIf { it.isNotBlank() }
    }
}

object NoopWindowFocusManager : WindowFocusManager {
    override suspend fun capture(): PreviousForegroundWindow? = null
    override suspend fun restore(previous: PreviousForegroundWindow?) = false
}
