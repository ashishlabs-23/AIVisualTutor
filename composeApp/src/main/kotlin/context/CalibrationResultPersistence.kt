package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.imageio.ImageIO

enum class CalibrationRunKind { LIVE_FLOW, CROP_REPLAY, LEGACY_IMPORT }

data class CalibrationAttempt(
    val caseId: String,
    val runKind: CalibrationRunKind,
    val capturedAt: Instant?,
    val targetDescription: String?,
    val targetDescriptionSource: String?,
    val sourceCropPath: Path? = null,
    val captureMetadata: Map<String, String> = emptyMap(),
    val groundTruthLabel: String? = "PENDING"
)

data class CalibrationRunResult(
    val image: BufferedImage,
    val attempt: CalibrationAttempt,
    val selectedRegion: Rectangle?,
    val applicationContext: ApplicationContext?,
    val visualContext: VisualContext?,
    val processingStartedNanos: Long,
    val providerSnapshot: CalibrationProviderSnapshot? = null,
    val failureType: String? = null,
    val failureDiagnostic: String? = null,
    val legacyFields: Map<String, String> = emptyMap(),
    val legacyCropPath: Path? = null
)

data class CalibrationProviderSnapshot(
    val uiAutomationEvidence: PerceptionResult? = null,
    val uiAutomationStatus: EvidenceStatus? = null,
    val uiAutomationLatencyMillis: Long? = null,
    val ocrEvidence: OcrResult? = null,
    val ocrStatus: EvidenceStatus? = null,
    val ocrLatencyMillis: Long? = null,
    val evaluation: EvidenceEvaluationResult? = null,
    val visualGroundingStatus: GroundingProviderAvailability? = null,
    val visualGroundingInvocation: VisualGroundingInvocationStatus? = null,
    val visualGroundingPreflight: VisualGroundingPreflight? = null,
    val visualGroundingInvocationRequested: Boolean = false,
    val visualGroundingInvocationAttempted: Boolean = false
)

fun interface CalibrationResultSink {
    fun persist(result: CalibrationRunResult)
}

object NoOpCalibrationResultSink : CalibrationResultSink {
    override fun persist(result: CalibrationRunResult) = Unit
}

/** Writes every completed calibration attempt under a local, git-ignored research-data directory. */
class CalibrationResultStore(
    root: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val beforeResultMove: () -> Unit = {}
) : CalibrationResultSink {
    private val root = root.toAbsolutePath().normalize()
    override fun persist(result: CalibrationRunResult) {
        val runId = UUID.randomUUID()
        Files.createDirectories(root)
        reportStaleTemporaryFiles()
        ensureManifestExists()
        val runDirectory = root.resolve(runId.toString())
        Files.createDirectory(runDirectory)
        val cropPath = runDirectory.resolve("crop.png")
        val cropBytes = result.attempt.sourceCropPath?.let(Files::readAllBytes)
            ?: result.legacyCropPath?.let(Files::readAllBytes)
            ?: encodePng(result.image)
        writeAtomic(cropPath, cropBytes)
        val cropHash = sha256(cropBytes)
        val now = clock.instant()
        val record = recordMap(result, runId, cropHash, now)
        val json = Json.encode(record).toByteArray(StandardCharsets.UTF_8)
        writeAtomic(runDirectory.resolve("result.json"), json, beforeResultMove)
        appendManifest(
            mapOf(
                "runId" to runId.toString(),
                "caseId" to result.attempt.caseId,
                "runKind" to result.attempt.runKind.name,
                "capturedAtUtc" to result.attempt.capturedAt?.toString().orStatus("NOT_RECORDED"),
                "cropSha256" to cropHash,
                "resultPath" to root.relativize(runDirectory.resolve("result.json")).toString().replace('\\', '/'),
                "processingStatus" to processingStatus(result)
            )
        )
    }

    private fun recordMap(
        result: CalibrationRunResult,
        runId: UUID,
        cropHash: String,
        processedAt: Instant
    ): Map<String, Any?> {
        val visual = result.visualContext
        val app = result.applicationContext
        val snapshot = result.providerSnapshot
        val uia = visual?.perceptionResult ?: snapshot?.uiAutomationEvidence
        val ocr = visual?.ocrResult ?: snapshot?.ocrEvidence
        val ev = visual?.evidenceEvaluation ?: snapshot?.evaluation
        val visualGrounding = ev?.visualGroundingEvidence
        val visualPreflight = ev?.visualGroundingPreflight ?: snapshot?.visualGroundingPreflight
        val uiaStatus = if (result.attempt.runKind == CalibrationRunKind.CROP_REPLAY) {
            "NOT_RUN"
        } else {
            ev?.uiAutomationStatus?.name ?: snapshot?.uiAutomationStatus?.name ?: result.legacyFields["uiaStatus"] ?: "NOT_RECORDED"
        }
        val ocrStatus = ev?.ocrStatus?.name ?: snapshot?.ocrStatus?.name ?: result.legacyFields["ocrStatus"] ?: "NOT_RECORDED"
        val uiaMetadata: Any? = uia?.metadata
            ?: result.legacyFields.filterKeys { it.startsWith("uia") }.takeIf { it.isNotEmpty() }
            ?: "NOT_RECORDED"
        val uiaCoordinateSpace = uia?.metadata?.get("coordinateSpace") ?: when {
            uiaStatus == "NOT_RUN" -> "NOT_RUN"
            result.attempt.runKind == CalibrationRunKind.LEGACY_IMPORT -> "NOT_RECORDED"
            uia?.source == PerceptionSource.UI_AUTOMATION && uia.boundingRectangle != null -> "PHYSICAL_DESKTOP_SCREEN"
            else -> "NOT_RECORDED"
        }
        val legacyValue: (String) -> Any? = { key ->
            if (!result.legacyFields.containsKey(key)) "NOT_RECORDED"
            else result.legacyFields[key]?.takeUnless { it == "null" }
        }
        val fileModifiedAtUtc = result.legacyCropPath?.let { path ->
            runCatching { Files.getLastModifiedTime(path).toInstant().toString() }.getOrNull()
        }.orStatus("NOT_RECORDED")
        val uiaFields = mapOf<String, Any?>(
            "status" to uiaStatus,
            "selectedObject" to (uia?.selectedObject ?: legacyValue("uiaObject")),
            "visibleText" to (uia?.visibleText ?: legacyValue("uiaText")),
            "uiType" to (uia?.takeIf { uiaStatus != "NOT_RUN" }?.uiType?.name ?: "NOT_RECORDED"),
            "controlType" to (uia?.takeIf { uiaStatus != "NOT_RUN" }?.uiType?.name ?: "NOT_RECORDED"),
            "automationId" to (uia?.metadata?.get("automationId") ?: "NOT_RECORDED"),
            "bounds" to (uia?.boundingRectangle?.toMap() ?: "NOT_RECORDED"),
            "coordinateSpace" to uiaCoordinateSpace,
            "confidence" to (if (uiaStatus == "NOT_RUN") "NOT_RECORDED" else uia?.confidence ?: "NOT_RECORDED"),
            "candidateCount" to (if (uiaStatus == "NOT_RUN") "NOT_RECORDED" else uia?.metadata?.get("candidateCount")?.toIntOrNull() ?: "NOT_RECORDED"),
            "metadata" to uiaMetadata,
            "latencyMs" to (if (uiaStatus == "NOT_RUN") "NOT_RUN"
                else visual?.metadata?.get("perceptionMillis")?.toLongOrNull()
                    ?: snapshot?.uiAutomationLatencyMillis
                    ?: result.legacyFields["perceptionMillis"]?.toLongOrNull()
                    ?: "NOT_RECORDED")
        )
        val ocrFields = mapOf<String, Any?>(
            "status" to ocrStatus,
            "text" to (ocr?.text ?: legacyValue("ocrText")),
            "confidence" to (ocr?.confidence ?: result.legacyFields["ocrConfidence"]?.toFloatOrNull() ?: "NOT_RECORDED"),
            "wordCount" to (ocr?.words?.size ?: result.legacyFields["ocrWordCount"]?.toIntOrNull() ?: "NOT_RECORDED"),
            "wordBoxes" to (ocr?.words?.map { word ->
                mapOf(
                    "text" to word.text,
                    "confidence" to word.confidence,
                    "bounds" to word.bounds.toMap(),
                    "language" to word.language.orStatus("NOT_RECORDED"),
                    "lineIndex" to word.lineIndex,
                    "wordIndex" to word.wordIndex
                )
            } ?: "NOT_RECORDED"),
            "language" to ocr?.language.orStatus("NOT_RECORDED"),
            "coordinateSpace" to ocr?.coordinateSpace?.name.orStatus("NOT_RECORDED"),
            "metadata" to (ocr?.metadata ?: "NOT_RECORDED"),
            "latencyMs" to (ocr?.metadata?.get("totalLatencyMillis")?.toLongOrNull()
                ?: visual?.metadata?.get("ocrMillis")?.toLongOrNull()
                ?: snapshot?.ocrLatencyMillis
                ?: result.legacyFields["ocrMillis"]?.toLongOrNull()
                ?: "NOT_RECORDED")
        )
        val visualFields = mapOf<String, Any?>(
            "status" to (visualGrounding?.availability?.name ?: snapshot?.visualGroundingStatus?.name ?: when (snapshot?.visualGroundingInvocation ?: ev?.visualGroundingInvocation) {
                VisualGroundingInvocationStatus.NOT_CONFIGURED -> "NOT_CONFIGURED"
                VisualGroundingInvocationStatus.UNAVAILABLE -> "UNAVAILABLE"
                VisualGroundingInvocationStatus.HOST_BLOCKED -> "HOST_BLOCKED"
                VisualGroundingInvocationStatus.FAILED -> "FAILURE"
                VisualGroundingInvocationStatus.CANCELLED -> "CANCELLED"
                VisualGroundingInvocationStatus.NOT_INVOKED_MISSING_TARGET,
                VisualGroundingInvocationStatus.NOT_NEEDED,
                VisualGroundingInvocationStatus.PENDING -> "NOT_RUN"
                VisualGroundingInvocationStatus.INVOKED -> "NOT_RECORDED"
                VisualGroundingInvocationStatus.RETURNED_EVIDENCE -> "AVAILABLE"
                null -> "NOT_RECORDED"
            }),
            "providerId" to (visualGrounding?.providerId
                ?: visualPreflight?.metadata?.get("provider")
                ?: ev?.visualGroundingProviderId.orStatus("NOT_RECORDED")),
            "model" to visualGrounding?.metadata?.get("model").orStatus("NOT_RECORDED"),
            "semantic" to (visualGrounding?.semantic?.let {
                mapOf("target" to it.target, "targetType" to it.targetType, "confidence" to it.confidence)
            } ?: "NOT_RECORDED"),
            "point" to (visualGrounding?.geometry?.point?.let {
                mapOf("x" to it.x, "y" to it.y)
            } ?: "NOT_RECORDED"),
            "parsedPoint" to (visualGrounding?.geometry?.point?.let {
                mapOf("x" to it.x, "y" to it.y)
            } ?: "NOT_RECORDED"),
            "bounds" to (visualGrounding?.geometry?.bounds?.let {
                mapOf("left" to it.left, "top" to it.top, "right" to it.right, "bottom" to it.bottom)
            } ?: "NOT_RECORDED"),
            "coordinateSpace" to (visualGrounding?.geometry?.coordinateSpace?.name ?: "NOT_RECORDED"),
            "confidence" to (visualGrounding?.geometry?.confidence ?: "NOT_RECORDED"),
            "executionLocation" to (visualGrounding?.executionLocation?.name ?: "NOT_RECORDED"),
            "latencyMs" to (visualGrounding?.latencyMillis ?: "NOT_RECORDED"),
            "rawOutput" to (visualGrounding?.metadata?.get("rawModelOutput").orStatus("NOT_RECORDED")),
            "metadata" to (visualGrounding?.metadata ?: "NOT_RECORDED")
        )
        val visualStatus = when {
            visualGrounding?.geometry != null -> "SUCCESS"
            visualGrounding?.metadata?.get("status") == "HOST_BLOCKED" -> "HOST_BLOCKED"
            visualGrounding != null -> visualGrounding.availability.name
            snapshot?.visualGroundingStatus != null -> snapshot.visualGroundingStatus.name
            snapshot?.visualGroundingInvocation == VisualGroundingInvocationStatus.CANCELLED -> "CANCELLED"
            visualPreflight != null -> visualPreflight.availability.name
            else -> when (snapshot?.visualGroundingInvocation ?: ev?.visualGroundingInvocation) {
                VisualGroundingInvocationStatus.NOT_CONFIGURED -> "NOT_CONFIGURED"
                VisualGroundingInvocationStatus.UNAVAILABLE -> "UNAVAILABLE"
                VisualGroundingInvocationStatus.HOST_BLOCKED -> "HOST_BLOCKED"
                VisualGroundingInvocationStatus.FAILED -> "FAILURE"
                VisualGroundingInvocationStatus.CANCELLED -> "CANCELLED"
                VisualGroundingInvocationStatus.NOT_INVOKED_MISSING_TARGET,
                VisualGroundingInvocationStatus.NOT_NEEDED,
                VisualGroundingInvocationStatus.PENDING -> "NOT_RUN"
                VisualGroundingInvocationStatus.INVOKED -> "FAILURE"
                VisualGroundingInvocationStatus.RETURNED_EVIDENCE -> "AVAILABLE"
                null -> "NOT_RUN"
            }
        }
        val visualMetadata = visualPreflight?.metadata.orEmpty() + visualGrounding?.metadata.orEmpty()
        val visualPoint = visualGrounding?.geometry?.point
        val visualPixelPoint = visualMetadata["pixelPoint"]?.split(',')?.takeIf { it.size == 2 }?.let {
            mapOf("x" to it[0].toDoubleOrNull(), "y" to it[1].toDoubleOrNull())
        }
        val visualGroundingBlock = mapOf<String, Any?>(
            "status" to visualStatus,
            "availability" to (visualGrounding?.availability?.name ?: visualPreflight?.availability?.name.orStatus("NOT_RECORDED")),
            "provider" to (visualGrounding?.providerId ?: visualPreflight?.metadata?.get("provider")
                ?: ev?.visualGroundingProviderId.orStatus("NOT_RECORDED")),
            "model" to visualMetadata["model"].orStatus("NOT_RECORDED"),
            "modelFile" to visualMetadata["modelFile"].orStatus("NOT_RECORDED"),
            "modelSource" to visualMetadata["modelSource"].orStatus("NOT_RECORDED"),
            "quantizationSource" to visualMetadata["quantizationSource"].orStatus("NOT_RECORDED"),
            "runtime" to visualMetadata["runtime"].orStatus("NOT_RECORDED"),
            "configured" to visualMetadata["configured"].orStatus("NOT_RECORDED"),
            "executable" to visualMetadata["executable"].orStatus("NOT_RECORDED"),
            "invocationRequested" to (ev?.visualGroundingInvocationRequested
                ?: snapshot?.visualGroundingInvocationRequested ?: false),
            "invocationAttempted" to (ev?.visualGroundingInvocationAttempted
                ?: (snapshot?.visualGroundingInvocationAttempted == true ||
                    snapshot?.visualGroundingInvocation == VisualGroundingInvocationStatus.CANCELLED)),
            "memoryGuard" to mapOf(
                "availableMemoryBytes" to visualMetadata["availableMemoryBytes"].orStatus("NOT_RECORDED"),
                "requiredSafetyMemoryBytes" to visualMetadata["requiredSafetyMemoryBytes"].orStatus("NOT_RECORDED"),
                "guardDecision" to visualMetadata["guardDecision"].orStatus("NOT_RECORDED")
            ),
            "rawOutput" to visualGrounding?.metadata?.get("rawModelOutput").orStatus("NOT_RECORDED"),
            "normalizedPoint" to (visualPoint?.let { mapOf("x" to it.x, "y" to it.y) } ?: "NONE"),
            "pixelPoint" to (visualPixelPoint ?: "NONE"),
            "coordinateSpace" to (visualGrounding?.geometry?.coordinateSpace?.name ?: "NONE"),
            "latencyMs" to (visualGrounding?.latencyMillis ?: "NOT_RECORDED"),
            "diagnostic" to (visualGrounding?.metadata?.get("diagnostic")
                ?: visualPreflight?.diagnostic ?: ev?.visualGroundingDiagnostic).orStatus("NOT_RECORDED")
        )
        val evaluatorFields = mapOf<String, Any?>(
            "decision" to (ev?.decision?.name ?: result.legacyFields["decision"].orStatus("NOT_RECORDED")),
            "reason" to (ev?.reason ?: result.legacyFields["reason"].orStatus("NOT_RECORDED")),
            "providerInvocationStatus" to (ev?.visualGroundingInvocation?.name
                ?: snapshot?.visualGroundingInvocation?.name
                ?: result.legacyFields["visualInvocation"].orStatus("NOT_RECORDED")),
            "thresholdsSnapshot" to if (result.attempt.runKind == CalibrationRunKind.LEGACY_IMPORT) {
                "NOT_RECORDED"
            } else {
                mapOf(
                    "uiaMatch" to EvidenceEvaluator.UIA_MATCH_THRESHOLD,
                    "ocrMatch" to EvidenceEvaluator.OCR_MATCH_THRESHOLD,
                    "visualSemantic" to EvidenceEvaluator.VISUAL_SEMANTIC_THRESHOLD,
                    "visualGeometry" to EvidenceEvaluator.VISUAL_GEOMETRY_THRESHOLD
                )
            }
        )
        val region = result.selectedRegion
        val captureTime = result.attempt.capturedAt
        val zone = ZoneId.systemDefault()
        val captureLocal = captureTime?.atZone(zone)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val runRoot = root.toAbsolutePath().normalize()
        val runDir = runRoot.resolve(runId.toString())
        val totalLatency: Any = if (result.attempt.runKind == CalibrationRunKind.LEGACY_IMPORT) {
            "NOT_RECORDED"
        } else {
            (System.nanoTime() - result.processingStartedNanos) / 1_000_000
        }
        val displayMetadata: Any = result.attempt.captureMetadata.takeIf { it.isNotEmpty() } ?: "NOT_RECORDED"
        return mapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "runId" to runId.toString(),
            "caseId" to result.attempt.caseId,
            "runKind" to result.attempt.runKind.name,
            "capturedAtUtc" to captureTime?.toString().orStatus("NOT_RECORDED"),
            "timestamp" to captureTime?.toString().orStatus("NOT_RECORDED"),
            "capturedAtLocal" to captureLocal.orStatus("NOT_RECORDED"),
            "timeZoneId" to if (captureTime == null) "NOT_RECORDED" else zone.id,
            "processedAtUtc" to processedAt.toString(),
            "crop" to mapOf(
                "path" to root.relativize(runDir.resolve("crop.png")).toString().replace('\\', '/'),
                "sha256" to cropHash,
                "widthPx" to result.image.width,
                "heightPx" to result.image.height,
                "selectedRegionDesktop" to (region?.toMap() ?: "NOT_RECORDED"),
                "targetHwnd" to (app?.windowHandle ?: result.legacyFields["appHwnd"]?.toLongOrNull() ?: "NOT_RECORDED"),
                "applicationName" to (app?.applicationName ?: result.legacyFields["appProcess"].orStatus("NOT_RECORDED"))
            ),
            "application" to (app?.applicationName ?: result.legacyFields["appProcess"].orStatus("NOT_RECORDED")),
            "environment" to mapOf(
                "appVersion" to "NOT_RECORDED",
                "commit" to "NOT_RECORDED",
                "worktreeDirty" to "NOT_RECORDED",
                "jvmVersion" to System.getProperty("java.runtime.version").orStatus("NOT_RECORDED"),
                "osBuild" to "NOT_RECORDED",
                "osVersion" to System.getProperty("os.version").orStatus("NOT_RECORDED"),
                "tesseractVersion" to ocr?.metadata?.get("tesseractVersion").orStatus("NOT_RECORDED"),
                "tessdataVersion" to ocr?.metadata?.get("tessdataVersion").orStatus("NOT_RECORDED"),
                "tesseractLanguage" to ocr?.language.orStatus("NOT_RECORDED"),
                "displayScaleMonitorLayout" to displayMetadata
            ),
            "inputs" to mapOf(
                "targetDescription" to (visual?.evidenceEvaluation?.targetDescription
                    ?: result.attempt.targetDescription
                    ?: legacyValue("targetDescription")),
                "targetDescriptionSource" to result.attempt.targetDescriptionSource.orStatus("NOT_RECORDED")
            ),
            "targetDescription" to (visual?.evidenceEvaluation?.targetDescription
                ?: result.attempt.targetDescription
                ?: legacyValue("targetDescription")),
            "providers" to mapOf("UIA" to uiaFields, "OCR" to ocrFields, "visualGrounding" to visualFields),
            "visualGrounding" to visualGroundingBlock,
            "evaluator" to evaluatorFields,
            "groundTruthLabel" to result.attempt.groundTruthLabel,
            "geometry" to mapOf(
                "uiaCoordinateSpace" to uiaCoordinateSpace,
                "ocrCoordinateSpace" to ocr?.coordinateSpace?.name.orStatus("NOT_RECORDED"),
                "visualCoordinateSpace" to visualGrounding?.geometry?.coordinateSpace?.name.orStatus("NOT_RECORDED"),
                "geometryComparisonStatus" to if (uia == null || ocr == null) "NOT_COMPUTABLE" else "NOT_COMPUTABLE"
            ),
            "overall" to mapOf(
                "processingStatus" to processingStatus(result),
                "failureType" to result.failureType.orStatus("NOT_RECORDED"),
                "failureDiagnostic" to result.failureDiagnostic.orStatus("NOT_RECORDED"),
                "totalLatencyMs" to totalLatency,
                "fileModifiedAtUtc" to fileModifiedAtUtc
            )
        )
    }

    private fun processingStatus(result: CalibrationRunResult): String =
        when {
            result.failureType?.contains("Cancellation") == true -> "CANCELLED"
            result.failureType != null -> "FAILURE"
            result.visualContext == null && result.attempt.runKind == CalibrationRunKind.LEGACY_IMPORT -> "NOT_RECORDED"
            result.visualContext != null -> "AVAILABLE"
            else -> "NOT_RECORDED"
        }

    private fun appendManifest(fields: Map<String, Any?>): Long {
        Files.createDirectories(root)
        ensureManifestExists()
        return synchronized(manifestMutex) {
            FileChannel.open(root.resolve(MANIFEST), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val current = ByteBuffer.allocate(channel.size().toInt())
                    channel.position(0)
                    while (current.hasRemaining()) channel.read(current)
                    current.flip()
                    val lastSequence = StandardCharsets.UTF_8.decode(current).toString()
                        .lineSequence().filter(String::isNotBlank).mapNotNull {
                            Regex("\"sequence\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull()
                        }.maxOrNullCompat() ?: 0L
                    val sequence = lastSequence + 1
                    val row = Json.encode(fields + ("sequence" to sequence)) + "\n"
                    channel.position(channel.size())
                    val bytes = ByteBuffer.wrap(row.toByteArray(StandardCharsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                    sequence
                }
            }
        }
    }

    private fun ensureManifestExists() {
        synchronized(manifestMutex) {
            val manifest = root.resolve(MANIFEST)
            if (Files.exists(manifest)) return
            FileChannel.open(root.resolve(".manifest.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
                lockChannel.lock().use {
                    if (Files.exists(manifest)) return
                    val recovered = Files.list(root).use { entries ->
                        entries.filter { Files.isDirectory(it) }.map { directory ->
                            val recordPath = directory.resolve("result.json")
                            if (!Files.isRegularFile(recordPath)) null else {
                                val contents = Files.readString(recordPath)
                                fun value(name: String) = Regex("\"$name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                                    .find(contents)?.groupValues?.get(1)
                                val caseId = value("caseId")
                                val runId = value("runId")
                                val runKind = value("runKind")
                                val hash = Regex("\"sha256\"\\s*:\\s*\"([a-f0-9]{64})\"").find(contents)?.groupValues?.get(1)
                                val captured = value("capturedAtUtc")
                                val processing = value("processingStatus")
                                if (caseId == null || runId == null || runKind == null || hash == null || processing == null) null
                                else mapOf(
                                    "caseId" to caseId,
                                    "runId" to runId,
                                    "runKind" to runKind,
                                    "cropSha256" to hash,
                                    "capturedAtUtc" to (captured ?: "NOT_RECORDED"),
                                    "processingStatus" to processing,
                                    "processedAtUtc" to (value("processedAtUtc") ?: "NOT_RECORDED"),
                                    "resultPath" to root.relativize(recordPath).toString().replace('\\', '/')
                                )
                            }
                        }.toList().filterNotNull().sortedWith(
                            compareBy<Map<String, String>> { it["processedAtUtc"] }.thenBy { it["runId"] }
                        )
                    }
                    val rows = recovered.mapIndexed { index, entry ->
                        Json.encode(entry + ("sequence" to (index + 1)))
                    }.joinToString(separator = "\n", postfix = if (recovered.isEmpty()) "" else "\n")
                        .toByteArray(StandardCharsets.UTF_8)
                    writeAtomic(manifest, rows)
                }
            }
        }
    }

    private fun reportStaleTemporaryFiles() {
        val staleBefore = clock.instant().minusSeconds(STALE_TEMP_AGE_SECONDS)
        val stale = Files.walk(root).use { paths ->
            paths.filter {
                Files.isRegularFile(it) &&
                    it.fileName.toString().endsWith(".tmp") &&
                    Files.getLastModifiedTime(it).toInstant().isBefore(staleBefore)
            }.toList()
        }
        if (stale.isEmpty()) return
        val report = root.resolve("stale-tmp-report.jsonl")
        FileChannel.open(report, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val rows = stale.joinToString(separator = "") { path ->
                Json.encode(
                    mapOf(
                        "observedAtUtc" to clock.instant().toString(),
                        "relativePath" to root.relativize(path).toString().replace('\\', '/'),
                        "status" to "IGNORED_NOT_READ_AS_RESULT"
                    )
                ) + "\n"
            }.toByteArray(StandardCharsets.UTF_8)
            val buffer = ByteBuffer.wrap(rows)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    private fun writeAtomic(destination: Path, bytes: ByteArray, beforeMove: () -> Unit = {}) {
        val temporary = destination.resolveSibling("${destination.fileName}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            beforeMove()
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                throw IllegalStateException("atomic_move_unsupported", e)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun encodePng(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output)) { "png_encoder_unavailable" }
        output.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun Rectangle.toMap() = mapOf("x" to x, "y" to y, "width" to width, "height" to height)
    private fun String?.orStatus(status: String) = this?.takeIf(String::isNotBlank) ?: status

    companion object {
        const val SCHEMA_VERSION = 3
        const val MANIFEST = "manifest.jsonl"
        const val ENABLE_PROPERTY = "aivt.calibration.enabled"
        const val OUTPUT_PROPERTY = "aivt.calibration.outputDir"
        private const val STALE_TEMP_AGE_SECONDS = 600L
        private val manifestMutex = Any()

        fun configuredSink(): CalibrationResultSink {
            val configuredRoot = System.getProperty(OUTPUT_PROPERTY)?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: defaultOutputDirectory()
            return CalibrationResultStore(configuredRoot)
        }

        private fun defaultOutputDirectory(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()
            return Path.of(localAppData, "AIVisualTutor", "calibration-runs")
        }

        fun isoUtc(value: String): Boolean = runCatching {
            OffsetDateTime.parse(value).offset.totalSeconds == 0
        }.getOrDefault(false) && runCatching { Instant.parse(value) }.isSuccess
    }
}

private object Json {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escape(value)}\""
        is Number -> if (value.toDouble().isFinite()) value.toString() else "null"
        is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") {
            "${encode(it.key.toString())}:${encode(it.value)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { encode(it) }
        else -> encode(value.toString())
    }

    private fun escape(value: String) = buildString {
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }
}

private fun <T : Comparable<T>> Sequence<T>.maxOrNullCompat(): T? {
    var max: T? = null
    for (item in this) if (max == null || item > max) max = item
    return max
}
