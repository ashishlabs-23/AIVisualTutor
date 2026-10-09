package context

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking

data class GroundTruthCase(
    val annotation: GroundTruthAnnotation,
    val screenshotPath: Path,
    val applicationContext: ApplicationContext? = null,
    val selectedRegionOnDesktop: java.awt.Rectangle? = null
)

/** Reads an RFC-4180-style CSV with one independently annotated case per row. */
object GroundTruthCsv {
    private val requiredColumns = setOf(
        "caseId", "screenshotPath", "targetDescription", "expectedTargetIdentity",
        "expectedGeometry", "x", "y", "width", "height", "coordinateSpace", "expectedAbsent", "notes",
        "windowHandle", "pid", "applicationName", "processName",
        "windowX", "windowY", "windowWidth", "windowHeight",
        "selectedX", "selectedY", "selectedWidth", "selectedHeight"
    )

    fun read(path: Path): List<GroundTruthCase> {
        val csv = path.toAbsolutePath().normalize()
        require(Files.isRegularFile(csv)) { "Ground-truth CSV does not exist: $csv" }
        val base = csv.parent.toRealPath()
        val rows = parse(Files.readString(csv, StandardCharsets.UTF_8).removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "Ground-truth CSV is empty." }
        val headers = rows.first().map(String::trim)
        require(headers.toSet().size == headers.size) { "Ground-truth CSV has duplicate columns." }
        require(headers.containsAll(requiredColumns)) {
            "Ground-truth CSV is missing columns: ${(requiredColumns - headers.toSet()).sorted().joinToString()}"
        }
        return rows.drop(1).mapIndexed { index, row ->
            val line = index + 2
            require(row.size == headers.size) { "Ground-truth CSV row $line has ${row.size} fields; expected ${headers.size}." }
            val fields = headers.zip(row).toMap()
            fun value(key: String) = fields.getValue(key).trim()
            fun optionalInt(key: String) = value(key).takeIf(String::isNotEmpty)?.toIntOrNull()
                ?: if (value(key).isEmpty()) null else throw IllegalArgumentException("Invalid $key on CSV row $line.")
            fun optionalLong(key: String) = value(key).takeIf(String::isNotEmpty)?.toLongOrNull()
                ?: if (value(key).isEmpty()) null else throw IllegalArgumentException("Invalid $key on CSV row $line.")
            fun optionalDouble(key: String) = value(key).takeIf(String::isNotEmpty)?.toDoubleOrNull()
                ?: if (value(key).isEmpty()) null else throw IllegalArgumentException("Invalid $key on CSV row $line.")

            val screenshot = base.resolve(value("screenshotPath")).normalize()
            require(screenshot.startsWith(base) && Files.isRegularFile(screenshot)) {
                "Screenshot path on CSV row $line must be a file within the annotation directory."
            }
            val realScreenshot = screenshot.toRealPath()
            require(realScreenshot.startsWith(base)) { "Screenshot symlink on CSV row $line escapes the annotation directory." }
            val absent = value("expectedAbsent").toBooleanStrictOrNull()
                ?: throw IllegalArgumentException("expectedAbsent must be true or false on CSV row $line.")
            val coordinateSpace = runCatching { GroundingBoxSpace.valueOf(value("coordinateSpace")) }
                .getOrElse { throw IllegalArgumentException("Invalid coordinateSpace on CSV row $line.") }
            val expectedBox: GroundingBox?
            val expectedPoint: Pair<Double, Double>?
            when (value("expectedGeometry")) {
                "BOX" -> {
                    expectedBox = GroundingBox(
                        optionalDouble("x") ?: error("Missing x on CSV row $line."),
                        optionalDouble("y") ?: error("Missing y on CSV row $line."),
                        optionalDouble("width") ?: error("Missing width on CSV row $line."),
                        optionalDouble("height") ?: error("Missing height on CSV row $line.")
                    )
                    expectedPoint = null
                }
                "POINT" -> {
                    expectedPoint = (optionalDouble("x") ?: error("Missing x on CSV row $line.")) to
                        (optionalDouble("y") ?: error("Missing y on CSV row $line."))
                    require(value("width").isEmpty() && value("height").isEmpty()) { "Point annotations cannot include width/height (CSV row $line)." }
                    expectedBox = null
                }
                "ABSENT" -> {
                    require(absent && listOf("x", "y", "width", "height").all { value(it).isEmpty() }) {
                        "ABSENT geometry requires expectedAbsent=true and blank coordinates (CSV row $line)."
                    }
                    expectedBox = null
                    expectedPoint = null
                }
                else -> throw IllegalArgumentException("expectedGeometry must be BOX, POINT, or ABSENT on CSV row $line.")
            }
            val textGeometryColumns = listOf("textX", "textY", "textWidth", "textHeight")
            val textGeometryValues = textGeometryColumns.map { fields[it]?.trim().orEmpty() }
            require(textGeometryValues.all(String::isEmpty) || textGeometryValues.all(String::isNotEmpty)) {
                "Text-region geometry must provide all four coordinates or none (CSV row $line)."
            }
            val expectedTextBox = if (textGeometryValues.all(String::isNotEmpty)) {
                GroundingBox(
                    textGeometryValues[0].toDoubleOrNull() ?: error("Invalid textX on CSV row $line."),
                    textGeometryValues[1].toDoubleOrNull() ?: error("Invalid textY on CSV row $line."),
                    textGeometryValues[2].toDoubleOrNull() ?: error("Invalid textWidth on CSV row $line."),
                    textGeometryValues[3].toDoubleOrNull() ?: error("Invalid textHeight on CSV row $line.")
                )
            } else null
            require(absent == (expectedBox == null && expectedPoint == null)) {
                "expectedAbsent must agree with expectedGeometry on CSV row $line."
            }
            val annotation = GroundTruthAnnotation(
                caseId = value("caseId").also { require(it.isNotBlank()) { "Blank caseId on CSV row $line." } },
                screenshotReference = value("screenshotPath"),
                targetDescription = value("targetDescription").also { require(it.isNotBlank()) { "Blank targetDescription on CSV row $line." } },
                expectedTargetIdentity = value("expectedTargetIdentity").takeIf(String::isNotBlank),
                expectedBox = expectedBox,
                expectedPoint = expectedPoint,
                coordinateSpace = coordinateSpace,
                expectedAbsent = absent,
                notes = value("notes").takeIf(String::isNotBlank),
                expectedTextBox = expectedTextBox
            )
            val hwnd = optionalLong("windowHandle")
            val selectedParts = listOf("selectedX", "selectedY", "selectedWidth", "selectedHeight").map(::optionalInt)
            val windowParts = listOf("windowX", "windowY", "windowWidth", "windowHeight").map(::optionalInt)
            require(selectedParts.all { it == null } || selectedParts.all { it != null }) {
                "Selected desktop rectangle must provide all four coordinates (CSV row $line)."
            }
            require(windowParts.all { it == null } || windowParts.all { it != null }) {
                "Window rectangle must provide all four coordinates (CSV row $line)."
            }
            require(hwnd == null || selectedParts.all { it != null }) { "windowHandle requires a selected desktop rectangle (CSV row $line)." }
            val selected = if (selectedParts.all { it != null }) java.awt.Rectangle(
                selectedParts[0]!!, selectedParts[1]!!, selectedParts[2]!!, selectedParts[3]!!
            ).also { require(it.width > 0 && it.height > 0) { "Selected desktop rectangle must be positive (CSV row $line)." } } else null
            val windowBounds = if (windowParts.all { it != null }) java.awt.Rectangle(
                windowParts[0]!!, windowParts[1]!!, windowParts[2]!!, windowParts[3]!!
            ) else null
            val appContext = if (hwnd != null || value("applicationName").isNotBlank() || value("processName").isNotBlank()) {
                ApplicationContext(
                    applicationName = value("applicationName").ifBlank { "Annotated application" },
                    processName = value("processName").ifBlank { "unknown" },
                    pid = optionalLong("pid"),
                    windowBounds = windowBounds,
                    windowHandle = hwnd
                )
            } else null
            GroundTruthCase(annotation, realScreenshot, appContext, selected)
        }
    }

    private fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var afterQuote = false
        var i = 0
        fun endField() { row += field.toString(); field.setLength(0); afterQuote = false }
        fun endRow() { endField(); if (row.any(String::isNotEmpty)) rows += row.toList(); row.clear() }
        while (i < text.length) {
            val c = text[i++]
            if (quoted) {
                if (c == '"') {
                    if (i < text.length && text[i] == '"') { field.append('"'); i++ }
                    else { quoted = false; afterQuote = true }
                } else field.append(c)
            } else when {
                afterQuote && c == ',' -> endField()
                afterQuote && c == '\r' -> { if (i < text.length && text[i] == '\n') i++; endRow() }
                afterQuote && c == '\n' -> endRow()
                afterQuote && c.isWhitespace() -> Unit
                afterQuote -> throw IllegalArgumentException("Unexpected character after a quoted CSV field.")
                c == ',' -> endField()
                c == '\r' -> { if (i < text.length && text[i] == '\n') i++; endRow() }
                c == '\n' -> endRow()
                c == '"' && field.isEmpty() -> quoted = true
                c == '"' -> throw IllegalArgumentException("Unexpected quote in unquoted CSV field.")
                else -> field.append(c)
            }
        }
        require(!quoted) { "Unterminated quoted CSV field." }
        if (field.isNotEmpty() || row.isNotEmpty() || afterQuote) endRow()
        return rows
    }
}

data class Phase5EvaluationRun(
    val caseId: String,
    val mode: GroundingMode,
    val runId: String,
    val decision: EvidenceDecision,
    val reason: GroundingDecisionReason,
    val totalLatencyMillis: Long,
    val requestedProviders: Set<GroundingProviderId>,
    val executedProviders: Set<GroundingProviderId>,
    val providerStatuses: Map<GroundingProviderId, GroundingProviderStatus>,
    val skippedReasons: Map<GroundingProviderId, String>,
    val screenshotSha256: String
)

class Phase5EvaluationRunner(
    private val runner: GroundingExperimentRunner = GroundingExperimentRunner()
) {
    suspend fun evaluate(
        cases: List<GroundTruthCase>,
        experimentId: String,
        iouThreshold: Double = 0.5,
        pointTolerancePixels: Double = 10.0
    ): Phase5EvaluationReport {
        require(cases.isNotEmpty()) { "No ground-truth cases were supplied." }
        require(cases.map { it.annotation.caseId }.toSet().size == cases.size) { "Ground-truth caseId values must be unique." }
        val allResults = mutableListOf<GroundingResult>()
        val runSummaries = mutableListOf<Phase5EvaluationRun>()
        for (case in cases) {
            val screenshot = ImageIO.read(case.screenshotPath.toFile())
                ?: error("Could not decode annotated screenshot for case ${case.annotation.caseId}.")
            try {
                val transform = case.selectedRegionOnDesktop?.let { desktop ->
                    ScreenToScreenshotTransform(
                        desktop.x.toDouble(), desktop.y.toDouble(),
                        screenshot.width.toDouble() / desktop.width, screenshot.height.toDouble() / desktop.height
                    )
                }
                val request = GroundingRequest(
                    screenshot = screenshot,
                    targetDescription = case.annotation.targetDescription,
                    mode = GroundingMode.ADAPTIVE,
                    applicationContext = case.applicationContext,
                    selectedRegionOnDesktop = case.selectedRegionOnDesktop,
                    screenToScreenshot = transform,
                    screenshotReference = case.annotation.screenshotReference,
                    experimentId = experimentId,
                    caseId = case.annotation.caseId,
                    groundTruthReference = case.annotation.caseId
                )
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(case.screenshotPath))
                    .joinToString("") { "%02x".format(it) }
                runner.compareAllModes(request).forEach { result ->
                    allResults += result
                    runSummaries += Phase5EvaluationRun(
                        case.annotation.caseId, result.mode, result.runId, result.decision, result.reason,
                        result.totalLatencyMillis, result.requestedProviders, result.executedProviders,
                        result.providerRuns.associate { it.provider to it.status }, result.skippedReasons, digest
                    )
                }
            } finally {
                screenshot.flush()
            }
        }
        val annotations = cases.associate { it.annotation.caseId to it.annotation }
        val byMode = GroundingMode.values().associateWith { mode ->
            GroundingResearchMetrics.evaluate(
                allResults.filter { it.mode == mode }, annotations, iouThreshold, pointTolerancePixels
            )
        }
        return Phase5EvaluationReport(
            schemaVersion = 1,
            experimentId = experimentId,
            generatedAt = Instant.now().toString(),
            caseCount = cases.size,
            screenshotHashes = runSummaries.map { it.screenshotSha256 }.distinct(),
            metricsByMode = byMode,
            runs = runSummaries
        )
    }
}

data class Phase5EvaluationReport(
    val schemaVersion: Int,
    val experimentId: String,
    val generatedAt: String,
    val caseCount: Int,
    val screenshotHashes: List<String>,
    val metricsByMode: Map<GroundingMode, GroundingMetrics>,
    val runs: List<Phase5EvaluationRun>
) {
    fun toJson(): String = buildString {
        append("{\"schemaVersion\":$schemaVersion,\"experimentId\":${quote(experimentId)},\"generatedAt\":${quote(generatedAt)},")
        append("\"caseCount\":$caseCount,\"screenshotSha256\":${strings(screenshotHashes)},\"metricsByMode\":{")
        append(metricsByMode.entries.joinToString(",") { (mode, metrics) -> "${quote(mode.name)}:${metricsJson(metrics)}" })
        append("},\"runs\":[")
        append(runs.joinToString(",") { run ->
            "{\"caseId\":${quote(run.caseId)},\"mode\":${quote(run.mode.name)},\"runId\":${quote(run.runId)}," +
                "\"decision\":${quote(run.decision.name)},\"reason\":${quote(run.reason.name)}," +
                "\"totalLatencyMillis\":${run.totalLatencyMillis},\"requestedProviders\":${strings(run.requestedProviders.map { it.name }.sorted())}," +
                "\"executedProviders\":${strings(run.executedProviders.map { it.name }.sorted())}," +
                "\"providerStatuses\":{${run.providerStatuses.entries.joinToString(",") { (provider, status) -> "${quote(provider.name)}:${quote(status.name)}" }}}, " +
                "\"skippedReasons\":{${run.skippedReasons.entries.joinToString(",") { (provider, reason) -> "${quote(provider.name)}:${quote(reason)}" }}},\"screenshotSha256\":${quote(run.screenshotSha256)}}"
        })
        append("]}")
    }

    private fun metricsJson(m: GroundingMetrics) =
        "{\"cases\":${m.cases},\"evaluatedCases\":${m.evaluatedCases},\"unavailableCases\":${m.unavailableCases}," +
            "\"accepted\":${m.accepted},\"coverage\":${number(m.coverage)}," +
            "\"selectiveAccuracy\":${number(m.selectiveAccuracy)},\"falseAcceptanceRate\":${number(m.falseAcceptanceRate)}," +
            "\"abstentionRate\":${number(m.abstentionRate)},\"targetIdentificationAccuracy\":${number(m.targetIdentificationAccuracy)}," +
            "\"meanIoU\":${number(m.meanIou)},\"meanCenterErrorPixels\":${number(m.meanCenterErrorPixels)}," +
            "\"validPredictionRate\":${number(m.validPredictionRate)},\"providerFailureRate\":${number(m.providerFailureRate)}," +
            "\"fullControlLocalizationAccuracy\":${number(m.fullControlLocalizationAccuracy)}," +
            "\"textRegionCases\":${m.textRegionCases},\"textRegionLocalizationAccuracy\":${number(m.textRegionLocalizationAccuracy)}," +
            "\"meanTextRegionIoU\":${number(m.meanTextRegionIou)},\"meanTextCenterErrorPixels\":${number(m.meanTextCenterErrorPixels)}," +
            "\"pointLocalizationCases\":${m.pointLocalizationCases},\"pointLocalizationAccuracy\":${number(m.pointLocalizationAccuracy)}," +
            "\"meanPointErrorPixels\":${number(m.meanPointErrorPixels)},\"adaptiveEscalationRate\":${number(m.adaptiveEscalationRate)}," +
            "\"meanEndToEndLatencyMillis\":${number(m.meanEndToEndLatencyMillis)}}"

    private fun strings(values: List<String>) = values.joinToString(prefix = "[", postfix = "]", transform = ::quote)
    private fun number(value: Double?) = value?.takeIf(Double::isFinite)?.toString() ?: "null"
    private fun quote(value: String) = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

fun main(args: Array<String>) = runBlocking {
    require(args.size == 3 && args[0] == "--annotations" && args[1].isNotBlank() && args[2].isNotBlank()) {
        "Usage: Phase5EvaluationKt --annotations <dataset.csv> <report.json>"
    }
    val csv = Path.of(args[1]).toAbsolutePath().normalize()
    val output = Path.of(args[2]).toAbsolutePath().normalize()
    val cases = GroundTruthCsv.read(csv)
    val report = Phase5EvaluationRunner().evaluate(cases, "PHASE5_${Instant.now().toEpochMilli()}")
    Files.createDirectories(output.parent)
    Files.writeString(output, report.toJson() + System.lineSeparator(), StandardCharsets.UTF_8)
    println("PHASE5_EVALUATION cases=${report.caseCount} runs=${report.runs.size} report=$output")
    report.metricsByMode.forEach { (mode, metrics) ->
        println("$mode identificationAccuracy=${metrics.targetIdentificationAccuracy} coverage=${metrics.coverage} " +
            "falseAcceptance=${metrics.falseAcceptanceRate} meanLatencyMs=${metrics.meanEndToEndLatencyMillis}")
    }
}
