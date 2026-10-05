package bridge

import context.ContextLogger
import context.LifecycleEvent
import context.StderrContextLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class HotkeyAction { PREVIOUS_WINDOW, SELECT_REGION }
data class HotkeyRegistrationResult(val captureRegistered: Boolean, val regionRegistered: Boolean, val diagnostic: String? = null) {
    val anyRegistered get() = captureRegistered || regionRegistered
}

/** Injectable platform seam so registration lifecycle is unit-testable without Windows. */
interface HotkeyPlatform {
    suspend fun register(onAction: (HotkeyAction) -> Unit): HotkeyRegistrationResult
    fun unregister()
}

interface GlobalHotkeyManager : AutoCloseable {
    suspend fun start(onHotkeyPressed: () -> Unit, onRegionHotkeyPressed: () -> Unit = {}): String?
}

class GlobalCaptureHotkey(
    private val platform: HotkeyPlatform = PowerShellHotkeyPlatform(),
    private val logger: ContextLogger = StderrContextLogger()
) : GlobalHotkeyManager {
    private val lock = Mutex()
    private val callbacks = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var registered = false
    private var startAttempted = false
    @Volatile private var lastStatus: String? = null

    override suspend fun start(onHotkeyPressed: () -> Unit, onRegionHotkeyPressed: () -> Unit): String? = lock.withLock {
        if (startAttempted) return@withLock lastStatus
        startAttempted = true
        val outcome = try {
            withContext(Dispatchers.IO) {
                platform.register { action ->
                    callbacks.launch {
                        logger.event(LifecycleEvent.HOTKEY_TRIGGERED, mapOf("state" to action.name))
                        when (action) {
                            HotkeyAction.PREVIOUS_WINDOW -> onHotkeyPressed()
                            HotkeyAction.SELECT_REGION -> onRegionHotkeyPressed()
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            startAttempted = false
            throw e
        } catch (e: Exception) {
            HotkeyRegistrationResult(false, false, e.message ?: "Hotkey registration failed.")
        }
        registered = outcome.anyRegistered
        val ids = when {
            outcome.captureRegistered && outcome.regionRegistered -> "both"
            outcome.captureRegistered -> "capture"
            outcome.regionRegistered -> "region"
            else -> "none"
        }
        logger.event(LifecycleEvent.HOTKEY_REGISTERED, mapOf("registeredIds" to ids))
        lastStatus = when {
            outcome.captureRegistered && outcome.regionRegistered -> null
            outcome.captureRegistered -> "Ctrl+Shift+Space could not be registered; Ctrl+Alt+F12 remains available."
            outcome.regionRegistered -> "Ctrl+Alt+F12 could not be registered; Ctrl+Shift+Space remains available."
            else -> "Could not register global shortcuts. ${outcome.diagnostic.orEmpty()}".trim()
        }
        lastStatus
    }

    override fun close() {
        runBlocking {
            lock.withLock {
                if (registered) withContext(Dispatchers.IO) { platform.unregister() }
                registered = false
                lastStatus = null
                callbacks.cancel()
            }
        }
    }
}

/** Win32 RegisterHotKey lives in an isolated PowerShell message-pump process. */
class PowerShellHotkeyPlatform : HotkeyPlatform {
    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null

    override suspend fun register(onAction: (HotkeyAction) -> Unit): HotkeyRegistrationResult = withContext(Dispatchers.IO) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return@withContext HotkeyRegistrationResult(false, false, "Global shortcuts are supported only on Windows.")
        }
        val source64 = Base64.getEncoder().encodeToString(HOST_SOURCE.toByteArray(StandardCharsets.UTF_8))
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}source = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$source64'))
            Add-Type -TypeDefinition ${'$'}source
            [TutorGlobalHotkeyHost]::Run()
        """.trimIndent()
        val child = try {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return@withContext HotkeyRegistrationResult(false, false, e.message ?: "Could not start Win32 hotkey host.")
        }
        val reader = BufferedReader(InputStreamReader(child.inputStream, StandardCharsets.UTF_8))
        val ready = try { reader.readLine() } catch (e: Exception) { child.destroyForcibly(); return@withContext HotkeyRegistrationResult(false, false, e.message) }
        if (ready == null || !ready.startsWith("READY:")) {
            val diag = generateSequence { if (reader.ready()) reader.readLine() else null }.joinToString(" ")
            child.destroyForcibly()
            return@withContext HotkeyRegistrationResult(false, false, diag.ifBlank { "Windows rejected both shortcuts." })
        }
        val capture = ready.getOrNull(6) == '1'
        val region = ready.getOrNull(7) == '1'
        if (!capture && !region) {
            child.destroyForcibly()
            return@withContext HotkeyRegistrationResult(false, false, "Windows rejected Ctrl+Alt+F12 and Ctrl+Shift+Space.")
        }
        process = child
        writer = BufferedWriter(OutputStreamWriter(child.outputStream, StandardCharsets.UTF_8))
        thread(name = "aivt-global-hotkey-events", isDaemon = true) {
            try {
                reader.useLines { lines -> lines.forEach { line ->
                    when (line) {
                        "HOTKEY_CAPTURE" -> onAction(HotkeyAction.PREVIOUS_WINDOW)
                        "HOTKEY_REGION" -> onAction(HotkeyAction.SELECT_REGION)
                    }
                } }
            } catch (_: Exception) { /* close() shuts down the message-pump process */ }
        }
        HotkeyRegistrationResult(capture, region)
    }

    override fun unregister() {
        val child = process ?: return
        try {
            writer?.apply { write("STOP"); newLine(); flush(); close() }
            if (!child.waitFor(2, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor() }
        } catch (_: Exception) { child.destroyForcibly() }
        finally { writer = null; process = null }
    }

    private companion object {
        val HOST_SOURCE = """
            using System;
            using System.Runtime.InteropServices;
            using System.Threading.Tasks;
            public static class TutorGlobalHotkeyHost {
                private const int CaptureId = 1, RegionId = 2;
                private const uint ModAlt=0x0001, ModControl=0x0002, ModShift=0x0004, ModNoRepeat=0x4000;
                private const uint VkF12=0x7B, VkSpace=0x20, WmHotkey=0x0312, WmQuit=0x0012;
                [StructLayout(LayoutKind.Sequential)] private struct Message { public IntPtr Window; public uint Id; public UIntPtr WParam; public IntPtr LParam; public uint Time; public int X; public int Y; }
                [DllImport("user32.dll", SetLastError=true)] private static extern bool RegisterHotKey(IntPtr h,int id,uint mod,uint key);
                [DllImport("user32.dll", SetLastError=true)] private static extern bool UnregisterHotKey(IntPtr h,int id);
                [DllImport("user32.dll", SetLastError=true)] private static extern int GetMessage(out Message m,IntPtr h,uint min,uint max);
                [DllImport("user32.dll", SetLastError=true)] private static extern bool PostThreadMessage(uint id,uint msg,UIntPtr w,IntPtr l);
                [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
                public static int Run() {
                    bool capture=RegisterHotKey(IntPtr.Zero,CaptureId,ModControl|ModAlt|ModNoRepeat,VkF12);
                    bool region=RegisterHotKey(IntPtr.Zero,RegionId,ModControl|ModShift|ModNoRepeat,VkSpace);
                    if(!capture && !region) { Console.WriteLine("FAIL:both"); Console.Out.Flush(); return Marshal.GetLastWin32Error(); }
                    uint threadId=GetCurrentThreadId();
                    Console.WriteLine("READY:"+(capture?"1":"0")+(region?"1":"0")); Console.Out.Flush();
                    Task.Run(()=>{ Console.ReadLine(); PostThreadMessage(threadId,WmQuit,UIntPtr.Zero,IntPtr.Zero); });
                    try { Message m; while(GetMessage(out m,IntPtr.Zero,0,0)>0) { if(m.Id==WmHotkey) { if(m.WParam.ToUInt64()==CaptureId) Console.WriteLine("HOTKEY_CAPTURE"); else if(m.WParam.ToUInt64()==RegionId) Console.WriteLine("HOTKEY_REGION"); Console.Out.Flush(); } } }
                    finally { if(capture) UnregisterHotKey(IntPtr.Zero,CaptureId); if(region) UnregisterHotKey(IntPtr.Zero,RegionId); }
                    return 0;
                }
            }
        """.trimIndent()
    }
}
