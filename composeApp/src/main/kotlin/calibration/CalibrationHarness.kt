package calibration

import context.ApplicationContext
import context.CalibrationAttempt
import context.CalibrationResultSink
import context.CalibrationResultStore
import context.CalibrationRunKind
import context.CalibrationRunResult
import context.ContextProcessor
import context.CalibrationProviderSnapshot
import context.GroundingProviderAvailability
import context.LlamaCppGroundingConfiguration
import context.LlamaCppVisualGroundingProvider
import context.NoOpCalibrationResultSink
import context.PerceptionEngine
import context.PerceptionResult
import context.PerceptionSource
import context.TesseractOcrService
import context.UiType
import context.VisualGroundingInvocationStatus
import context.WindowsUiAutomationPerceptionEngine
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.time.Instant
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
    if (args.firstOrNull() == "--visual-preflight") {
        require(args.size == 4) { "Usage: --visual-preflight <caseId> <targetDescription> <crop.png>" }
        require(System.getProperty(CalibrationResultStore.ENABLE_PROPERTY)?.equals("true", true) == true) {
            "Visual preflight persistence requires explicit calibration mode."
        }
        val config = LlamaCppGroundingConfiguration.fromSystemProperties()
        require(config.enabled && config.executablePath != null && config.modelPath != null && config.mmprojPath != null) {
            "Visual preflight requires explicit executable, model, and mmproj paths."
        }
        val provider = LlamaCppVisualGroundingProvider(configuration = config)
        val preflight = provider.preflight()
        val cropPath = File(args[3]).toPath().toAbsolutePath().normalize()
        val image = ImageIO.read(cropPath.toFile()) ?: error("Preflight crop is not a readable image.")
        val invocation = when (preflight.availability) {
            GroundingProviderAvailability.NOT_CONFIGURED -> VisualGroundingInvocationStatus.NOT_CONFIGURED
            GroundingProviderAvailability.UNAVAILABLE -> VisualGroundingInvocationStatus.UNAVAILABLE
            GroundingProviderAvailability.HOST_BLOCKED -> VisualGroundingInvocationStatus.HOST_BLOCKED
            GroundingProviderAvailability.FAILURE -> VisualGroundingInvocationStatus.FAILED
            GroundingProviderAvailability.CANCELLED -> VisualGroundingInvocationStatus.CANCELLED
            GroundingProviderAvailability.AVAILABLE -> VisualGroundingInvocationStatus.PENDING
        }
        val sink = CalibrationResultStore.configuredSink()
        require(sink is CalibrationResultStore) { "Visual preflight requires the file result store." }
        sink.persist(
            CalibrationRunResult(
                image = image,
                attempt = CalibrationAttempt(
                    caseId = args[1],
                    runKind = CalibrationRunKind.CROP_REPLAY,
                    capturedAt = null,
                    targetDescription = args[2],
                    targetDescriptionSource = "explicit_visual_preflight",
                    sourceCropPath = cropPath,
                    captureMetadata = mapOf("visualPreflightOnly" to "true")
                ),
                selectedRegion = Rectangle(0, 0, image.width, image.height),
                applicationContext = null,
                visualContext = null,
                processingStartedNanos = System.nanoTime(),
                providerSnapshot = CalibrationProviderSnapshot(
                    visualGroundingStatus = preflight.availability,
                    visualGroundingInvocation = invocation,
                    visualGroundingPreflight = preflight,
                    visualGroundingInvocationRequested = true,
                    visualGroundingInvocationAttempted = false
                )
            )
        )
        println("Visual preflight persisted: provider=${provider.providerId} status=${preflight.availability} outputRoot=${System.getProperty(CalibrationResultStore.OUTPUT_PROPERTY) ?: "DEFAULT_LOCAL_APP_DATA"}")
        return@runBlocking
    }
    if (args.firstOrNull() == "--replay-saved") {
        require(args.size == 4) { "Usage: --replay-saved <manifest.tsv> <crop-dir> <existing-results-dir>" }
        require(System.getProperty(CalibrationResultStore.ENABLE_PROPERTY)?.equals("true", true) == true) {
            "Saved-crop replay requires explicit calibration mode."
        }
        replaySavedCrops(File(args[1]), File(args[2]), File(args[3]), CalibrationResultStore.configuredSink())
        return@runBlocking
    }
    if (args.firstOrNull() == "--legacy-import") {
        require(args.size == 2) { "Usage: --legacy-import <legacy-results-dir>" }
        require(System.getProperty(CalibrationResultStore.ENABLE_PROPERTY)?.equals("true", true) == true) {
            "Legacy import requires explicit calibration mode."
        }
        importLegacyResults(File(args[1]), CalibrationResultStore.configuredSink())
        return@runBlocking
    }
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
        val capturedAt = Instant.now()
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
            requiresVisualGrounding = false,
            calibrationAttempt = CalibrationAttempt(
                caseId = caseId,
                runKind = CalibrationRunKind.LIVE_FLOW,
                capturedAt = capturedAt,
                targetDescription = targetDescription,
                targetDescriptionSource = "manifest.targetDescription"
            )
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

private suspend fun replaySavedCrops(
    manifest: File,
    cropDirectory: File,
    existingResultsDirectory: File,
    sink: CalibrationResultSink
) {
    require(sink !== NoOpCalibrationResultSink) { "Calibration sink is disabled." }
    val lines = manifest.readLines().filter { it.isNotBlank() && !it.startsWith("caseId") }
    val processor = ContextProcessor(
        ocr = TesseractOcrService(),
        perceptionEngine = PerceptionEngine { request ->
            PerceptionResult(
                applicationName = request.applicationContext?.applicationName,
                selectedObject = null,
                visibleText = null,
                uiType = UiType.UNKNOWN,
                boundingRectangle = null,
                confidence = null,
                source = PerceptionSource.UNKNOWN,
                metadata = mapOf("status" to "not_run")
            )
        },
        calibrationResultSink = sink
    )
    for (line in lines) {
        val cols = line.split('\t')
        require(cols.size >= 9) { "Bad manifest row." }
        val caseId = cols[0]
        val target = cols[2].takeIf { it.isNotBlank() && it != "-" }
        val region = Rectangle(cols[3].toInt(), cols[4].toInt(), cols[5].toInt(), cols[6].toInt())
        val priorResult = File(existingResultsDirectory, "${caseId}_result.txt")
        val priorProbe = File(existingResultsDirectory, "${caseId}_provider_probe.txt")
        val savedFields = when {
            priorProbe.isFile -> readFields(priorProbe)
            priorResult.isFile -> readFields(priorResult)
            else -> emptyMap()
        }
        val crop = sequenceOf(
            File(cropDirectory, "${caseId}_crop.png"),
            File(cropDirectory, "crops/${caseId}_crop.png")
        ).firstOrNull(File::isFile) ?: error("Saved crop missing for $caseId.")
        val image = ImageIO.read(crop) ?: error("Saved crop is not a readable PNG for $caseId.")
        val applicationName = savedFields["appProcess"] ?: savedFields["source_application"]
        val app = applicationName?.let {
            ApplicationContext(
                applicationName = it,
                processName = it,
                windowTitle = savedFields["appTitle"] ?: savedFields["window_title"],
                pid = savedFields["appPid"]?.toLongOrNull() ?: savedFields["pid"]?.toLongOrNull(),
                windowHandle = savedFields["appHwnd"]?.toLongOrNull() ?: savedFields["hwnd"]?.toLongOrNull()
            )
        }
        processor.process(
            image = image,
            x = region.x,
            y = region.y,
            app = app,
            selectedRegion = region,
            targetDescription = target,
            calibrationAttempt = CalibrationAttempt(
                caseId = caseId,
                runKind = CalibrationRunKind.CROP_REPLAY,
                capturedAt = null,
                targetDescription = target,
                targetDescriptionSource = "manifest.targetDescription",
                sourceCropPath = crop.toPath()
            )
        )
    }
}

private fun importLegacyResults(directory: File, sink: CalibrationResultSink) {
    val store = sink as? CalibrationResultStore ?: error("Legacy import requires the file result store.")
    for (index in 1..18) {
        val caseId = "C%02d".format(index)
        val crop = File(directory, "${caseId}_crop.png")
        val resultFile = File(directory, "${caseId}_result.txt")
        require(crop.isFile && resultFile.isFile) { "Legacy artifacts missing for $caseId." }
        val fields = readFields(resultFile).filterKeys { !it.startsWith("groundTruth", ignoreCase = true) }
        val image = ImageIO.read(crop) ?: error("Legacy crop is not a readable PNG for $caseId.")
        val region = fields["region"]?.split(',')?.mapNotNull(String::toIntOrNull)
            ?.takeIf { it.size == 4 }?.let { Rectangle(it[0], it[1], it[2], it[3]) }
        val appName = fields["appProcess"]
        val app = appName?.let {
            ApplicationContext(
                it, it, fields["appTitle"], fields["appPid"]?.toLongOrNull(),
                windowHandle = fields["appHwnd"]?.toLongOrNull()
            )
        }
        store.persist(
            CalibrationRunResult(
                image = image,
                attempt = CalibrationAttempt(
                    caseId = caseId,
                    runKind = CalibrationRunKind.LEGACY_IMPORT,
                    capturedAt = null,
                    targetDescription = fields["targetDescription"],
                    targetDescriptionSource = if (fields.containsKey("targetDescription")) "legacy_result_file" else null
                ),
                selectedRegion = region,
                applicationContext = app,
                visualContext = null,
                processingStartedNanos = 0,
                legacyFields = fields,
                legacyCropPath = crop.toPath()
            )
        )
    }
}

private fun readFields(file: File): Map<String, String> = file.readLines().mapNotNull { line ->
    val split = line.indexOf('=')
    if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
}.toMap()

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
