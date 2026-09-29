package bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.concurrent.thread
import java.util.concurrent.TimeUnit

class GlobalCaptureHotkey {
    private var process: Process? = null
    private var controlWriter: BufferedWriter? = null

    suspend fun start(onHotkeyPressed: () -> Unit): String? = withContext(Dispatchers.IO) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return@withContext "Global capture shortcut is only supported on Windows."
        }

        val source64 = Base64.getEncoder().encodeToString(HOST_SOURCE.toByteArray(StandardCharsets.UTF_8))
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}source = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$source64'))
            Add-Type -TypeDefinition ${'$'}source
            [TutorGlobalHotkeyHost]::Run()
        """.trimIndent()

        val startedProcess = try {
            ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                script
            ).redirectErrorStream(false).start()
        } catch (exception: Exception) {
            return@withContext "Could not start global shortcut registration: ${exception.message}"
        }

        val errors = StringBuffer()
        thread(name = "capture-hotkey-stderr", isDaemon = true) {
            try {
                startedProcess.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        synchronized(errors) {
                            if (errors.isNotEmpty()) errors.appendLine()
                            errors.append(line)
                        }
                    }
                }
            } catch (_: Exception) {
                // Process shutdown can close the diagnostic stream.
            }
        }

        val stdout = BufferedReader(InputStreamReader(startedProcess.inputStream, StandardCharsets.UTF_8))
        val firstLine = try {
            stdout.readLine()
        } catch (exception: Exception) {
            startedProcess.destroyForcibly()
            return@withContext "Could not register global capture shortcut: ${exception.message}"
        }

        if (firstLine != "READY") {
            val exitCode = if (startedProcess.isAlive) {
                startedProcess.destroyForcibly()
                null
            } else {
                startedProcess.waitFor()
            }
            val diagnostic = synchronized(errors) { errors.toString().trim() }
            return@withContext buildString {
                append("Could not register Ctrl+Alt+F12.")
                if (exitCode != null) append(" Registration process exited with code $exitCode.")
                if (diagnostic.isNotEmpty()) append(" $diagnostic")
            }
        }

        process = startedProcess
        controlWriter = BufferedWriter(OutputStreamWriter(startedProcess.outputStream, StandardCharsets.UTF_8))
        thread(name = "capture-hotkey-events", isDaemon = true) {
            try {
                stdout.use { reader ->
                    while (true) {
                        when (reader.readLine() ?: break) {
                            "HOTKEY" -> onHotkeyPressed()
                        }
                    }
                }
            } catch (_: Exception) {
                // The process is stopped when the application is disposed.
            }
        }

        null
    }

    fun close() {
        val activeProcess = process ?: return
        try {
            controlWriter?.apply {
                write("STOP")
                newLine()
                flush()
                close()
            }
            if (!activeProcess.waitFor(2, TimeUnit.SECONDS)) {
                activeProcess.destroyForcibly()
                activeProcess.waitFor()
            }
        } catch (_: Exception) {
            activeProcess.destroyForcibly()
        } finally {
            controlWriter = null
            process = null
        }
    }

    private companion object {
        val HOST_SOURCE = """
            using System;
            using System.Runtime.InteropServices;
            using System.Threading.Tasks;

            public static class TutorGlobalHotkeyHost {
                private const int HotkeyId = 1;
                private const uint ModAlt = 0x0001;
                private const uint ModControl = 0x0002;
                private const uint ModNoRepeat = 0x4000;
                private const uint VirtualKeyF12 = 0x7B;
                private const uint WmHotkey = 0x0312;
                private const uint WmQuit = 0x0012;

                [StructLayout(LayoutKind.Sequential)]
                private struct Message {
                    public IntPtr Window;
                    public uint Id;
                    public UIntPtr WParam;
                    public IntPtr LParam;
                    public uint Time;
                    public int X;
                    public int Y;
                }

                [DllImport("user32.dll", SetLastError = true)]
                private static extern bool RegisterHotKey(IntPtr window, int id, uint modifiers, uint key);

                [DllImport("user32.dll", SetLastError = true)]
                private static extern bool UnregisterHotKey(IntPtr window, int id);

                [DllImport("user32.dll", SetLastError = true)]
                private static extern int GetMessage(out Message message, IntPtr window, uint min, uint max);

                [DllImport("user32.dll", SetLastError = true)]
                private static extern bool PostThreadMessage(uint threadId, uint message, UIntPtr wParam, IntPtr lParam);

                [DllImport("kernel32.dll")]
                private static extern uint GetCurrentThreadId();

                public static int Run() {
                    if (!RegisterHotKey(IntPtr.Zero, HotkeyId, ModControl | ModAlt | ModNoRepeat, VirtualKeyF12)) {
                        Console.Error.WriteLine("Windows rejected Ctrl+Alt+F12 (it may already be registered).");
                        return Marshal.GetLastWin32Error();
                    }

                    uint threadId = GetCurrentThreadId();
                    Console.Out.WriteLine("READY");
                    Console.Out.Flush();

                    Task.Run(() => {
                        Console.ReadLine();
                        PostThreadMessage(threadId, WmQuit, UIntPtr.Zero, IntPtr.Zero);
                    });

                    try {
                        Message message;
                        while (GetMessage(out message, IntPtr.Zero, 0, 0) > 0) {
                            if (message.Id == WmHotkey && message.WParam.ToUInt64() == HotkeyId) {
                                Console.Out.WriteLine("HOTKEY");
                                Console.Out.Flush();
                            }
                        }
                    }
                    finally {
                        UnregisterHotKey(IntPtr.Zero, HotkeyId);
                    }

                    return 0;
                }
            }
        """.trimIndent()
    }
}
