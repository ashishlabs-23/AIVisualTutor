package calibration

import context.ApplicationContext
import context.ContextProcessor
import context.TesseractOcrService
import context.WindowsUiAutomationPerceptionEngine
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking

/**
 * Manual research harness for 4-GAP-2 calibration.
 * Uses production UIA + OCR + evaluator only; does not change those implementations.
 * Ground-truth labels must be supplied by a human — this tool never invents them.
 *
 * Usage:
 *   CalibrationHarnessKt <manifest.tsv> <outDir>
 */
fun main(args: Array<String>) = runBlocking {
    require(args.size >= 2) { "Usage: CalibrationHarnessKt <manifest.tsv> <outDir>" }
    val manifest = File(args[0])
    val outDir = File(args[1]).also { it.mkdirs() }
    val processor = ContextProcessor(
        ocr = TesseractOcrService(),
        perceptionEngine = WindowsUiAutomationPerceptionEngine()
    )
    val robot = Robot()
    val lines = manifest.readLines().filter { it.isNotBlank() && !it.startsWith("caseId") }
    val summary = StringBuilder()
    summary.appendLine("caseId\tcategory\tdecision\treason\tuiaStatus\tocrStatus\tinstructionFlag\tgroundTruth")
    for (line in lines) {
        val cols = line.split('\t')
        require(cols.size >= 9) { "Bad manifest row: $line" }
        val caseId = cols[0]
        val category = cols[1]
        val targetDescription = cols[2].takeIf { it.isNotBlank() && it != "-" }
        val region = Rectangle(cols[3].toInt(), cols[4].toInt(), cols[5].toInt(), cols[6].toInt())
        val processHint = cols[7].takeIf { it.isNotBlank() && it != "-" }
        val instructionFlag = cols[8]
        println("CASE $caseId ...")
        val image = robot.createScreenCapture(region)
        val cropPath = File(outDir, "${caseId}_crop.png")
        check(ImageIO.write(image, "png", cropPath)) { "Failed to write $cropPath" }
        val app = processHint?.let { hint ->
            val parts = hint.split(':', limit = 2)
            findWindowByProcessName(parts[0], parts.getOrNull(1))
        }
        val visual = processor.process(
            image = image,
            x = region.x,
            y = region.y,
            app = app,
            selectedRegion = region,
            targetDescription = targetDescription,
            requiresVisualGrounding = false
        )
        val ev = visual.evidenceEvaluation
        File(outDir, "${caseId}_result.txt").writeText(
            buildString {
                appendLine("caseId=$caseId")
                appendLine("category=$category")
                appendLine("targetDescription=${targetDescription ?: ""}")
                appendLine("instructionFlag=$instructionFlag")
                appendLine("region=${region.x},${region.y},${region.width},${region.height}")
                appendLine("cropPath=${cropPath.absolutePath}")
                appendLine("appProcess=${app?.processName}")
                appendLine("appTitle=${app?.windowTitle}")
                appendLine("appHwnd=${app?.windowHandle}")
                appendLine("decision=${ev?.decision}")
                appendLine("reason=${ev?.reason}")
                appendLine("uiaStatus=${ev?.uiAutomationStatus}")
                appendLine("ocrStatus=${ev?.ocrStatus}")
                appendLine("visualAvailability=${ev?.visualGroundingAvailability}")
                appendLine("visualInvocation=${ev?.visualGroundingInvocation}")
                appendLine("uiaObject=${visual.perceptionResult?.selectedObject}")
                appendLine("uiaText=${visual.perceptionResult?.visibleText}")
                appendLine("uiaBounds=${visual.perceptionResult?.boundingRectangle}")
                appendLine("uiaConfidence=${visual.perceptionResult?.confidence}")
                appendLine("ocrText=${visual.ocrResult?.text?.replace('\n', ' ')?.take(240)}")
                appendLine("ocrConfidence=${visual.ocrResult?.confidence}")
                appendLine("ocrWordCount=${visual.ocrResult?.words?.size}")
                appendLine("ocrMillis=${visual.metadata["ocrMillis"]}")
                appendLine("perceptionMillis=${visual.metadata["perceptionMillis"]}")
                appendLine("processingMillis=${visual.metadata["processingMillis"]}")
                appendLine("groundTruthLabel=PENDING_LABEL")
                appendLine("groundTruthMatch=")
            }
        )
        summary.appendLine(
            "$caseId\t$category\t${ev?.decision}\t${ev?.reason}\t${ev?.uiAutomationStatus}\t${ev?.ocrStatus}\t$instructionFlag\tPENDING_LABEL"
        )
        println("  decision=${ev?.decision} uia=${ev?.uiAutomationStatus} ocr=${ev?.ocrStatus}")
    }
    File(outDir, "SUMMARY.tsv").writeText(summary.toString())
    println("WROTE ${File(outDir, "SUMMARY.tsv").absolutePath}")
}

private fun findWindowByProcessName(processName: String, titleContains: String? = null): ApplicationContext? {
    val titleNeedle = titleContains.orEmpty()
    val source = """
        using System; using System.Diagnostics; using System.Runtime.InteropServices; using System.Text;
        public static class AivtFindWin {
          [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
          [DllImport("user32.dll")] static extern bool EnumWindows(EnumWindowsProc lp, IntPtr l);
          [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
          [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder t, int n);
          [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint p);
          [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
          delegate bool EnumWindowsProc(IntPtr h, IntPtr l);
          public static string Find(string want, string titleNeedle) {
            string found = "";
            EnumWindows((h, l) => {
              if (!IsWindowVisible(h)) return true;
              uint pid; GetWindowThreadProcessId(h, out pid);
              try {
                var p = Process.GetProcessById((int)pid);
                if (!string.Equals(p.ProcessName, want, StringComparison.OrdinalIgnoreCase)) return true;
                var t = new StringBuilder(2048); GetWindowText(h, t, t.Capacity);
                if (t.Length == 0) return true;
                if (!string.IsNullOrEmpty(titleNeedle) && t.ToString().IndexOf(titleNeedle, StringComparison.OrdinalIgnoreCase) < 0) return true;
                RECT r; GetWindowRect(h, out r);
                int w = r.Right - r.Left, hh = r.Bottom - r.Top;
                if (w < 80 || hh < 80) return true;
                found = Convert.ToBase64String(Encoding.UTF8.GetBytes(p.ProcessName))+"|"+pid+"|"+r.Left+"|"+r.Top+"|"+w+"|"+hh+"|"+Convert.ToBase64String(Encoding.UTF8.GetBytes(t.ToString()))+"|"+h.ToInt64();
                return false;
              } catch { return true; }
            }, IntPtr.Zero);
            return found;
          }
        }
    """.trimIndent()
    val source64 = Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8))
    val script =
        "Add-Type -TypeDefinition ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$source64'))); [AivtFindWin]::Find('${processName.replace("'", "''")}','${titleNeedle.replace("'", "''")}')"
    val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
        .redirectErrorStream(true).start()
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    if (process.exitValue() != 0 || output.isBlank()) return null
    val parts = output.lineSequence().last().split('|')
    if (parts.size != 8) return null
    fun decode(v: String) = runCatching { String(Base64.getDecoder().decode(v), Charsets.UTF_8) }.getOrDefault("")
    val bounds = runCatching {
        Rectangle(parts[2].toInt(), parts[3].toInt(), parts[4].toInt(), parts[5].toInt())
    }.getOrNull()
    return ApplicationContext(
        applicationName = decode(parts[0]),
        processName = decode(parts[0]),
        windowTitle = decode(parts[6]),
        pid = parts[1].toLongOrNull(),
        windowBounds = bounds,
        windowHandle = parts[7].toLongOrNull()
    )
}
