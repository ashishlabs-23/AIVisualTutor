package context

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

data class GroundTruthAnnotation(
    val caseId: String,
    val screenshotReference: String,
    val targetDescription: String,
    val expectedTargetIdentity: String? = null,
    val expectedBox: GroundingBox? = null,
    val expectedPoint: Pair<Double, Double>? = null,
    val coordinateSpace: GroundingBoxSpace = GroundingBoxSpace.SCREENSHOT_PIXELS,
    val expectedAbsent: Boolean = false,
    val notes: String? = null,
    val expectedTextBox: GroundingBox? = null
) {
    init {
        require(expectedBox != null || expectedPoint != null || expectedAbsent) { "Ground truth needs a box, point, or expectedAbsent=true." }
        require(!expectedAbsent || (expectedBox == null && expectedPoint == null)) { "Absent targets cannot have expected geometry." }
        require(!expectedAbsent || expectedTextBox == null) { "Absent targets cannot have expected text geometry." }
        expectedBox?.let { require(it.validWithin(Double.MAX_VALUE, Double.MAX_VALUE)) { "Ground-truth box is invalid." } }
        expectedTextBox?.let { require(it.validWithin(Double.MAX_VALUE, Double.MAX_VALUE)) { "Ground-truth text box is invalid." } }
        expectedPoint?.let { require(it.first.isFinite() && it.second.isFinite() && it.first >= 0 && it.second >= 0) { "Ground-truth point is invalid." } }
    }
}

data class GroundingMetrics(
    val cases: Int,
    val accepted: Int,
    val coverage: Double?,
    val selectiveAccuracy: Double?,
    val falseAcceptanceRate: Double?,
    val abstentionRate: Double?,
    val meanIou: Double?,
    val meanCenterErrorPixels: Double?,
    val validPredictionRate: Double?,
    val providerFailureRate: Double?,
    val adaptiveEscalationRate: Double?,
    val targetIdentificationAccuracy: Double? = null,
    val meanEndToEndLatencyMillis: Double? = null,
    val evaluatedCases: Int = cases,
    val unavailableCases: Int = 0,
    val fullControlLocalizationAccuracy: Double? = null,
    val textRegionCases: Int = 0,
    val textRegionLocalizationAccuracy: Double? = null,
    val meanTextRegionIou: Double? = null,
    val meanTextCenterErrorPixels: Double? = null,
    val pointLocalizationCases: Int = 0,
    val pointLocalizationAccuracy: Double? = null,
    val meanPointErrorPixels: Double? = null
)

/** Provider failures are reported separately and excluded from grounding-accuracy denominators. */
object GroundingResearchMetrics {
    fun evaluate(results: List<GroundingResult>, annotations: Map<String, GroundTruthAnnotation>, iouThreshold: Double = 0.5, pointTolerancePixels: Double = 10.0): GroundingMetrics {
        require(iouThreshold.isFinite() && iouThreshold in 0.0..1.0)
        require(pointTolerancePixels.isFinite() && pointTolerancePixels >= 0.0)
        val cases = results.mapNotNull { result -> annotations[result.caseId]?.let { result to it } }
            .filter { it.second.expectedAbsent || it.second.coordinateSpace == GroundingBoxSpace.SCREENSHOT_PIXELS }
        val evaluated = cases.filter { (run, _) -> run.providerRuns.any { it.status in USABLE_PROVIDER_STATUSES } }
        val accepted = evaluated.filter { it.first.decision == EvidenceDecision.ACCEPT }
        val acceptedPositive = accepted.filterNot { it.second.expectedAbsent }
        val fullControlMeasurements = acceptedPositive.mapNotNull { (run, truth) ->
            val expected = truth.expectedBox ?: return@mapNotNull null
            val prediction = run.acceptedBox ?: return@mapNotNull null
            val overlap = iou(prediction, expected) ?: return@mapNotNull null
            overlap to kotlin.math.hypot(prediction.centerX - expected.centerX, prediction.centerY - expected.centerY)
        }
        val pointMeasurements = acceptedPositive.mapNotNull { (run, truth) ->
            val expected = truth.expectedPoint ?: return@mapNotNull null
            val prediction = run.acceptedPoint ?: return@mapNotNull null
            kotlin.math.hypot(prediction.first - expected.first, prediction.second - expected.second)
        }
        val textRegionCases = evaluated.filter { (run, truth) ->
            truth.expectedTextBox != null && run.providerRuns.any {
                it.provider == GroundingProviderId.OCR && it.status in USABLE_PROVIDER_STATUSES
            }
        }
        val textRegionMeasurements = textRegionCases.mapNotNull { (run, truth) ->
            val expected = truth.expectedTextBox ?: return@mapNotNull null
            if (run.decision != EvidenceDecision.ACCEPT) return@mapNotNull null
            val observation = run.selectedCandidate?.observations.orEmpty()
                .filter { it.source == GroundingProviderId.OCR && it.textSimilarity >= run.configuration.minimumTextSimilarity }
                .maxByOrNull { it.textSimilarity }
                ?: return@mapNotNull null
            val prediction = observation.box ?: return@mapNotNull null
            val overlap = iou(prediction, expected) ?: return@mapNotNull null
            overlap to kotlin.math.hypot(prediction.centerX - expected.centerX, prediction.centerY - expected.centerY)
        }
        val falseAccepts = accepted.count { (run, truth) ->
            truth.expectedAbsent || !identifiesExpectedTarget(run, truth)
        }
        val correctIdentifications = evaluated.count { (run, truth) -> identifiesExpectedTarget(run, truth) }
        val providerRuns = cases.flatMap { it.first.providerRuns }
        val adaptive = evaluated.filter { it.first.mode == GroundingMode.ADAPTIVE }
        val validPredictions = accepted.count { (run, _) ->
            run.acceptedBox?.validWithin(run.screenshotWidth.toDouble(), run.screenshotHeight.toDouble()) == true ||
                run.acceptedPoint?.let { (x, y) ->
                    x.isFinite() && y.isFinite() && x >= 0 && y >= 0 &&
                        x < run.screenshotWidth && y < run.screenshotHeight
                } == true
        }
        val acceptedIdentityCorrect = accepted.count { (run, truth) -> !truth.expectedAbsent && identifiesExpectedTarget(run, truth) }
        return GroundingMetrics(
            cases = cases.size,
            accepted = accepted.size,
            coverage = evaluated.ratioOrNull(accepted.size),
            selectiveAccuracy = if (accepted.isEmpty()) null else acceptedIdentityCorrect.toDouble() / accepted.size,
            falseAcceptanceRate = evaluated.ratioOrNull(falseAccepts),
            abstentionRate = evaluated.ratioOrNull(evaluated.count { it.first.decision == EvidenceDecision.ABSTAIN }),
            meanIou = fullControlMeasurements.map { it.first }.takeIf { it.isNotEmpty() }?.average(),
            meanCenterErrorPixels = (fullControlMeasurements.map { it.second } + pointMeasurements)
                .takeIf { it.isNotEmpty() }?.average(),
            validPredictionRate = if (accepted.isEmpty()) null else validPredictions.toDouble() / accepted.size,
            providerFailureRate = providerRuns.takeIf { it.isNotEmpty() }?.count {
                it.status in setOf(
                    GroundingProviderStatus.UNAVAILABLE, GroundingProviderStatus.NOT_CONFIGURED,
                    GroundingProviderStatus.EXECUTION_FAILED, GroundingProviderStatus.INVALID_OUTPUT,
                    GroundingProviderStatus.TIMED_OUT, GroundingProviderStatus.INCOMPATIBLE_COORDINATES
                )
            }?.toDouble()?.div(providerRuns.size),
            adaptiveEscalationRate = adaptive.ratioOrNull(adaptive.count { GroundingProviderId.VISION in it.first.executedProviders }),
            targetIdentificationAccuracy = evaluated.ratioOrNull(correctIdentifications),
            meanEndToEndLatencyMillis = cases.map { it.first.totalLatencyMillis.toDouble() }.average()
                .takeIf { cases.isNotEmpty() },
            evaluatedCases = evaluated.size,
            unavailableCases = cases.size - evaluated.size,
            fullControlLocalizationAccuracy = if (acceptedPositive.none { it.second.expectedBox != null }) null else {
                fullControlMeasurements.count { it.first >= iouThreshold }.toDouble() /
                    acceptedPositive.count { it.second.expectedBox != null }
            },
            textRegionCases = textRegionCases.size,
            textRegionLocalizationAccuracy = if (textRegionCases.isEmpty()) null else
                textRegionMeasurements.count { it.first >= iouThreshold }.toDouble() / textRegionCases.size,
            meanTextRegionIou = textRegionMeasurements.map { it.first }.takeIf { it.isNotEmpty() }?.average(),
            meanTextCenterErrorPixels = textRegionMeasurements.map { it.second }.takeIf { it.isNotEmpty() }?.average(),
            pointLocalizationCases = evaluated.count { it.second.expectedPoint != null },
            pointLocalizationAccuracy = evaluated.filter { it.second.expectedPoint != null }
                .takeIf { it.isNotEmpty() }
                ?.let { pointCases ->
                    pointCases.count { (run, truth) ->
                        run.decision == EvidenceDecision.ACCEPT && truth.expectedPoint?.let { expected ->
                            run.acceptedPoint?.let { point ->
                                kotlin.math.hypot(point.first - expected.first, point.second - expected.second) <= pointTolerancePixels
                            } == true
                        } == true
                    }.toDouble() / pointCases.size
                },
            meanPointErrorPixels = pointMeasurements.takeIf { it.isNotEmpty() }?.average()
        )
    }

    private fun identifiesExpectedTarget(run: GroundingResult, truth: GroundTruthAnnotation): Boolean =
        if (truth.expectedAbsent) run.decision == EvidenceDecision.ABSTAIN && run.reason == GroundingDecisionReason.NO_MATCH
        else run.decision == EvidenceDecision.ACCEPT && (
            truth.expectedTargetIdentity?.let { candidateMatchesIdentity(run.selectedCandidate, it) }
                ?: ((run.selectedCandidate?.targetSimilarity ?: 0.0) >= run.configuration.minimumTextSimilarity)
            )

    private fun candidateMatchesIdentity(candidate: GroundingCandidate?, expected: String): Boolean =
        candidate?.observations.orEmpty().any {
            normalizeGroundingText(it.observedText.orEmpty()) == normalizeGroundingText(expected)
        }

    private fun List<Pair<GroundingResult, GroundTruthAnnotation>>.ratioOrNull(numerator: Int): Double? =
        if (isEmpty()) null else numerator.toDouble() / size

    private val USABLE_PROVIDER_STATUSES = setOf(
        GroundingProviderStatus.SUCCESS,
        GroundingProviderStatus.SUCCESS_NO_MATCH,
        GroundingProviderStatus.EMPTY_RESULT
    )
}

/** Additive experiment records beside the existing calibration store; identical crops share one content-addressed asset. */
class GroundingExperimentStore(root: Path) : GroundingResultSink {
    private val root = root.toAbsolutePath().normalize().resolve("phase5-experiments")
    override fun persist(result: GroundingResult) = persistRecord(result)
    override fun persist(result: GroundingResult, screenshot: java.awt.image.BufferedImage) {
        val bytes = ByteArrayOutputStream().use { output ->
            check(ImageIO.write(screenshot, "png", output)) { "screenshot_png_encoding_failed" }
            output.toByteArray()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val assetDirectory = root.resolve("screenshots")
        Files.createDirectories(assetDirectory)
        val asset = assetDirectory.resolve("$hash.png")
        if (!Files.exists(asset)) atomicWrite(asset, bytes)
        persistRecord(result.copy(screenshotReference = "screenshots/$hash.png"))
    }
    fun loadScreenshot(runId: String): java.awt.image.BufferedImage? {
        val recordPath = root.resolve(runId.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(100)).resolve("result.json")
        if (!Files.isRegularFile(recordPath)) return null
        val json = Files.readString(recordPath)
        val reference = Regex("\\\"screenshotReference\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1) ?: return null
        val asset = root.resolve(reference).normalize()
        if (!asset.startsWith(root.resolve("screenshots")) || !Files.isRegularFile(asset)) return null
        return ImageIO.read(asset.toFile())
    }
    fun readRecord(runId: String): String? {
        val recordPath = root.resolve(runId.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(100)).resolve("result.json")
        return if (Files.isRegularFile(recordPath)) Files.readString(recordPath) else null
    }
    fun loadReplayRequest(runId: String, modeOverride: GroundingMode? = null): GroundingRequest? {
        val json = readRecord(runId) ?: return null
        val screenshot = loadScreenshot(runId) ?: return null
        fun string(name: String): String? {
            val raw = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(null|\\\"(?:\\\\.|[^\\\"\\\\])*\\\")").find(json)?.groupValues?.get(1) ?: return null
            if (raw == "null") return null
            val body = raw.substring(1, raw.length - 1)
            return buildString {
                var i = 0
                while (i < body.length) {
                    val c = body[i++]
                    if (c != '\\' || i >= body.length) append(c)
                    else when (val escaped = body[i++]) {
                        '"' -> append('"'); '\\' -> append('\\'); '/' -> append('/')
                        'b' -> append('\b'); 'f' -> append('\u000c'); 'n' -> append('\n'); 'r' -> append('\r'); 't' -> append('\t')
                        'u' -> if (i + 4 <= body.length) { append(body.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: '\uFFFD'); i += 4 }
                        else -> { append('\\'); append(escaped) }
                    }
                }
            }
        }
        fun number(name: String, fallback: Double) = Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull() ?: fallback
        val originalMode = string("mode")?.let { runCatching { GroundingMode.valueOf(it) }.getOrNull() } ?: return null
        val reference = string("screenshotReference") ?: return null
        return GroundingRequest(
            screenshot = screenshot, targetDescription = string("targetDescription"), mode = modeOverride ?: originalMode,
            screenshotReference = reference, experimentId = string("experimentId") ?: "PHASE5_REPLAY",
            caseId = string("caseId"), groundTruthReference = string("groundTruthReference"),
            thresholds = GroundingThresholds(number("minimumTextSimilarity", .72), number("minimumAcceptedScore", .78),
                number("minimumWinnerMargin", .12), number("minimumIouForAssociation", .20))
        )
    }
    private fun persistRecord(result: GroundingResult) {
        Files.createDirectories(root)
        val directory = root.resolve(result.runId.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(100))
        Files.createDirectories(directory)
        require(!Files.exists(directory.resolve("result.json"))) { "duplicate_run_id" }
        val record = encode(result).toByteArray(StandardCharsets.UTF_8)
        atomicWrite(directory.resolve("result.json"), record)
        val row = "{\"runId\":${q(result.runId)},\"caseId\":${q(result.caseId.orEmpty())},\"mode\":${q(result.mode.name)},\"decision\":${q(result.decision.name)},\"path\":${q(root.relativize(directory.resolve("result.json")).toString().replace('\\', '/'))}}\n"
        FileChannel.open(root.resolve("manifest.jsonl"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            channel.lock().use {
                val bytes = ByteBuffer.wrap(row.toByteArray(StandardCharsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
        }
    }

    private fun encode(r: GroundingResult): String = buildString {
        append("{\"schemaVersion\":1,\"experimentId\":${q(r.experimentId)},\"caseId\":${nullable(r.caseId)},\"runId\":${q(r.runId)},\"timestamp\":${q(r.timestamp)},")
        append("\"mode\":${q(r.mode.name)},\"targetDescription\":${nullable(r.targetDescription)},\"screenshotReference\":${nullable(r.screenshotReference)},\"groundTruthReference\":${nullable(r.groundTruthReference)},\"screenshotDimensions\":{\"width\":${r.screenshotWidth},\"height\":${r.screenshotHeight}},")
        append("\"requestedProviders\":${array(r.requestedProviders.map { it.name })},\"executedProviders\":${array(r.executedProviders.map { it.name })},\"successfulProviders\":${array(r.successfulProviders.map { it.name })},\"failedProviders\":${array(r.failedProviders.map { it.name })},")
        append("\"providerRuns\":[${r.providerRuns.joinToString(",") { p -> "{\"provider\":${q(p.provider.name)},\"status\":${q(p.status.name)},\"latencyMillis\":${p.latencyMillis},\"diagnostic\":${nullable(p.diagnostic)},\"metadata\":${map(p.metadata)}}" }}],")
        append("\"skippedReasons\":${map(r.skippedReasons.mapKeys { it.key.name })},")
        append("\"candidates\":[${r.candidates.joinToString(",") { c -> "{\"candidateId\":${q(c.candidateId)},\"score\":${c.score},\"targetSimilarity\":${c.targetSimilarity},\"crossProviderAgreement\":${c.crossProviderAgreement ?: "null"},\"box\":${box(c.box)},\"observations\":[${c.observations.joinToString(",") { o -> "{\"source\":${q(o.source.name)},\"candidateId\":${q(o.candidateId)},\"observedText\":${nullable(o.observedText)},\"textSimilarity\":${o.textSimilarity},\"providerConfidence\":${o.providerConfidence ?: "null"},\"coordinateSpace\":${nullable(o.coordinateSpace?.name)},\"box\":${box(o.box)},\"originalBox\":${box(o.originalBox)},\"metadata\":${map(o.sourceMetadata)}}" }}]}" }}],")
        append("\"selectedCandidateId\":${nullable(r.selectedCandidate?.candidateId)},\"proposedCandidateId\":${nullable(r.proposedCandidate?.candidateId)},\"predictedCoordinates\":${box(r.proposedBox)},\"acceptedCoordinates\":${box(r.acceptedBox)},\"acceptedPoint\":${r.acceptedPoint?.let { "{\"x\":${it.first},\"y\":${it.second},\"coordinateSpace\":\"SCREENSHOT_PIXELS\"}" } ?: "null"},\"decision\":${q(r.decision.name)},\"reason\":${q(r.reason.name)},\"decisionReason\":${q(r.decisionReason)},\"totalLatencyMillis\":${r.totalLatencyMillis},\"modelId\":${nullable(r.modelId)},")
        append("\"thresholds\":{\"minimumTextSimilarity\":${r.configuration.minimumTextSimilarity},\"minimumAcceptedScore\":${r.configuration.minimumAcceptedScore},\"minimumWinnerMargin\":${r.configuration.minimumWinnerMargin},\"minimumIouForAssociation\":${r.configuration.minimumIouForAssociation}}}")
    }
    private fun box(b: GroundingBox?) = b?.let { "{\"x\":${it.x},\"y\":${it.y},\"width\":${it.width},\"height\":${it.height},\"coordinateSpace\":\"SCREENSHOT_PIXELS\"}" } ?: "null"
    private fun map(values: Map<String, String>) = "{" + values.entries.joinToString(",") { "${q(it.key)}:${q(it.value)}" } + "}"
    private fun array(values: List<String>) = "[" + values.joinToString(",") { q(it) } + "]"
    private fun nullable(value: String?) = value?.let(::q) ?: "null"
    private fun q(value: String) = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> { append('\\'); append('"') }
                '\\' -> { append('\\'); append('\\') }
                '\b' -> { append('\\'); append('b') }
                '\u000c' -> { append('\\'); append('f') }
                '\n' -> { append('\\'); append('n') }
                '\r' -> { append('\\'); append('r') }
                '\t' -> { append('\\'); append('t') }
                else -> if (c.code < 0x20) { append('\\'); append('u'); append("%04x".format(c.code)) } else append(c)
            }
        }
        append('"')
    }
    private fun atomicWrite(path: Path, bytes: ByteArray) {
        val temp = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE) }
            catch (e: AtomicMoveNotSupportedException) { throw IllegalStateException("atomic_move_unsupported", e) }
        } finally { Files.deleteIfExists(temp) }
    }
}

fun defaultGroundingExperimentStore(): GroundingExperimentStore {
    val local = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
        ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()
    return GroundingExperimentStore(Path.of(local, "AIVisualTutor", "calibration-runs"))
}

class GroundingExperimentRunner(
    private val router: EvidenceAdaptiveGroundingRouter = defaultGroundingRouter(),
    private val store: GroundingResultSink = defaultGroundingExperimentStore()
) {
    suspend fun run(request: GroundingRequest): GroundingResult =
        EvidenceAdaptiveGroundingRouterProxy.run(router, request).also { store.persist(it, request.screenshot) }

    suspend fun compareAllModes(request: GroundingRequest): List<GroundingResult> =
        GroundingMode.values().map { mode ->
            val run = EvidenceAdaptiveGroundingRouterProxy.run(router, request.copy(mode = mode, executionId = UUID.randomUUID().toString()))
            store.persist(run, request.screenshot)
            run
        }
}

/** Keeps the router's sink independent so UI callers can select a store per experiment. */
private object EvidenceAdaptiveGroundingRouterProxy {
    suspend fun run(router: EvidenceAdaptiveGroundingRouter, request: GroundingRequest) = router.run(request)
}

fun defaultGroundingRouter(): EvidenceAdaptiveGroundingRouter {
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val uia = WindowsUiAutomationPerceptionEngine()
    val ocr: OCRService = if (windows) TesseractOcrService() else PlaceholderOcrService()
    return EvidenceAdaptiveGroundingRouter(
        ExistingUiaGroundingProvider(uia),
        ExistingOcrGroundingProvider(ocr),
        ExistingVisionGroundingProvider(defaultVisualGroundingProvider())
    )
}
