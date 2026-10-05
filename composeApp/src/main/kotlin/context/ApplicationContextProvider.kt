package context

import java.awt.Rectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface ApplicationContextProvider { suspend fun current(): ApplicationContext? }
fun interface ForegroundWindowApi { fun foreground(excludedPid: Long): ApplicationContext? }

class WindowsApplicationContextProvider(private val native: ForegroundWindowApi = PowerShellForegroundWindowApi()) : ApplicationContextProvider {
    override suspend fun current(): ApplicationContext? = withContext(Dispatchers.IO) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) null
        else runCatching { native.foreground(ProcessHandle.current().pid()) }.getOrNull()
    }
}

/** Isolated native boundary: powershell/.NET is already the repository's Win32 interop mechanism. */
class PowerShellForegroundWindowApi : ForegroundWindowApi {
    override fun foreground(excludedPid: Long): ApplicationContext? {
        val source = """
            using System; using System.Diagnostics; using System.Runtime.InteropServices; using System.Text;
            public static class AivtForeground {
              [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
              [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
              [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder t, int n);
              [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
              [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
              public static string Read(uint excluded) {
                IntPtr h=GetForegroundWindow(); uint pid; GetWindowThreadProcessId(h,out pid);
                if(h==IntPtr.Zero || pid==0 || pid==excluded) return "";
                try { var p=Process.GetProcessById((int)pid); var t=new StringBuilder(2048); GetWindowText(h,t,t.Capacity); RECT r; GetWindowRect(h,out r);
                  return Convert.ToBase64String(Encoding.UTF8.GetBytes(p.ProcessName))+"|"+pid+"|"+r.Left+"|"+r.Top+"|"+(r.Right-r.Left)+"|"+(r.Bottom-r.Top)+"|"+Convert.ToBase64String(Encoding.UTF8.GetBytes(t.ToString()));
                } catch { return ""; }
              }
            }
        """.trimIndent()
        val source64 = java.util.Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8))
        val script = "Add-Type -TypeDefinition ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$source64'))); [AivtForeground]::Read([uint32]$excludedPid)"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); return null }
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.exitValue() != 0 || output.isBlank()) return null
        val parts = output.lineSequence().last().split('|')
        if (parts.size != 7) return null
        val processName = decode(parts[0])
        val pid = parts[1].toLongOrNull() ?: return null
        val bounds = runCatching { Rectangle(parts[2].toInt(), parts[3].toInt(), parts[4].toInt(), parts[5].toInt()) }.getOrNull()
        val title = decode(parts[6])
        return ApplicationContext(applicationName = processName, processName = processName, windowTitle = title, pid = pid, windowBounds = bounds)
    }

    private fun decode(value: String) = runCatching { String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrDefault("")
}
