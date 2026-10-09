package context

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase5EvaluationTest {
    @Test
    fun csvAcceptsUtf8BomFromCommonSpreadsheetExports() {
        val root = Files.createTempDirectory("phase5-evaluation-bom-test")
        val screenshot = root.resolve("fixture.png")
        val image = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
        try {
            ImageIO.write(image, "png", screenshot.toFile())
        } finally {
            image.flush()
        }
        val header = "caseId,screenshotPath,targetDescription,expectedTargetIdentity,expectedGeometry,x,y,width,height,coordinateSpace,expectedAbsent,notes,windowHandle,pid,applicationName,processName,windowX,windowY,windowWidth,windowHeight,selectedX,selectedY,selectedWidth,selectedHeight"
        val row = "case,fixture.png,Save,Save,POINT,10,10,,,SCREENSHOT_PIXELS,false,,,,,,,,,,,,,"
        val csv = root.resolve("cases.csv")
        Files.writeString(csv, "\uFEFF$header\n$row\n")

        assertEquals(1, GroundTruthCsv.read(csv).size)
    }

    @Test
    fun csvDatasetRunsAllFiveModesOnSameImageAndReportsAbsentTargetAndLocalizationMetrics() = runBlocking {
        val root = Files.createTempDirectory("phase5-evaluation-test")
        val screenshotPath = root.resolve("fixture.png")
        val screenshot = BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB)
        try {
            assertTrue(ImageIO.write(screenshot, "png", screenshotPath.toFile()))
        } finally {
            screenshot.flush()
        }
        val csv = root.resolve("cases.csv")
        val headers = listOf(
            "caseId", "screenshotPath", "targetDescription", "expectedTargetIdentity", "expectedGeometry",
            "x", "y", "width", "height", "coordinateSpace", "expectedAbsent", "notes", "windowHandle",
            "pid", "applicationName", "processName", "windowX", "windowY", "windowWidth", "windowHeight",
            "selectedX", "selectedY", "selectedWidth", "selectedHeight", "textX", "textY", "textWidth", "textHeight"
        )
        fun csvRow(values: List<String>) = values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
        val visibleCase = listOf(
            "visible", "fixture.png", "Save", "Save", "BOX", "10", "10", "20", "12",
            "SCREENSHOT_PIXELS", "false", "independent, reviewed"
        ) + List(12) { "" } + listOf("12", "12", "10", "6")
        val absentCase = listOf(
            "missing", "fixture.png", "Archive", "", "ABSENT", "", "", "", "",
            "SCREENSHOT_PIXELS", "true", "same image; target absent"
        ) + List(16) { "" }
        Files.writeString(csv, listOf(csvRow(headers), csvRow(visibleCase), csvRow(absentCase)).joinToString("\n"))
        val cases = GroundTruthCsv.read(csv)
        assertEquals(2, cases.size)
        assertEquals("independent, reviewed", cases.first().annotation.notes)
        assertEquals(GroundingBox(12.0, 12.0, 10.0, 6.0), cases.first().annotation.expectedTextBox)
        assertTrue(cases.last().annotation.expectedAbsent)

        val box = GroundingBox(10.0, 10.0, 20.0, 12.0)
        val textBox = GroundingBox(12.0, 12.0, 10.0, 6.0)
        val router = EvidenceAdaptiveGroundingRouter(
            uia = GroundingUiaProvider { request ->
                val text = "Save"
                val observation = GroundingObservation(
                    GroundingProviderId.UIA, "uia-save", text, request.targetDescription.orEmpty(),
                    box, box, GroundingBoxSpace.SCREENSHOT_PIXELS, GroundingBoxSpace.SCREENSHOT_PIXELS,
                    null, textSimilarity(request.targetDescription.orEmpty(), text),
                    executionId = request.executionId
                )
                GroundingProviderRun(GroundingProviderId.UIA, GroundingProviderStatus.SUCCESS, 1, listOf(observation))
            },
            ocr = GroundingOcrProvider { request ->
                val text = "Save"
                val observation = GroundingObservation(
                    GroundingProviderId.OCR, "ocr-save", text, request.targetDescription.orEmpty(),
                    textBox, textBox, GroundingBoxSpace.SCREENSHOT_PIXELS, GroundingBoxSpace.SCREENSHOT_PIXELS,
                    null, textSimilarity(request.targetDescription.orEmpty(), text),
                    executionId = request.executionId
                )
                GroundingProviderRun(
                    GroundingProviderId.OCR,
                    if (observation.textSimilarity > 0.0) GroundingProviderStatus.SUCCESS else GroundingProviderStatus.SUCCESS_NO_MATCH,
                    1, listOf(observation)
                )
            },
            vision = GroundingVisionProvider { _, _ ->
                GroundingProviderRun(GroundingProviderId.VISION, GroundingProviderStatus.NOT_CONFIGURED, 0, diagnostic = "fixture_not_configured")
            }
        )
        val store = GroundingExperimentStore(root.resolve("results"))
        val report = Phase5EvaluationRunner(GroundingExperimentRunner(router, store))
            .evaluate(cases, "TEST_SWEEP")

        assertEquals(2, report.caseCount)
        assertEquals(10, report.runs.size)
        assertEquals(GroundingMode.values().toSet(), report.metricsByMode.keys)
        assertEquals(1.0, report.metricsByMode.getValue(GroundingMode.UIA_ONLY).meanIou)
        assertEquals(0.25, report.metricsByMode.getValue(GroundingMode.OCR_ONLY).meanIou)
        assertEquals(
            0.5, report.metricsByMode.getValue(GroundingMode.UIA_ONLY).coverage,
            report.runs.filter { it.mode == GroundingMode.UIA_ONLY }.toString()
        )
        assertEquals(0.0, report.metricsByMode.getValue(GroundingMode.UIA_ONLY).falseAcceptanceRate)
        assertEquals(1.0, report.metricsByMode.getValue(GroundingMode.UIA_ONLY).targetIdentificationAccuracy)
        assertEquals(1.0, report.metricsByMode.getValue(GroundingMode.OCR_ONLY).targetIdentificationAccuracy)
        assertEquals(0.0, report.metricsByMode.getValue(GroundingMode.OCR_ONLY).fullControlLocalizationAccuracy)
        assertEquals(1.0, report.metricsByMode.getValue(GroundingMode.OCR_ONLY).textRegionLocalizationAccuracy)
        assertEquals(1.0, report.metricsByMode.getValue(GroundingMode.VISION_ONLY).providerFailureRate)
        assertEquals(0.5, report.metricsByMode.getValue(GroundingMode.ADAPTIVE).adaptiveEscalationRate)
        assertEquals(0.2, report.metricsByMode.getValue(GroundingMode.ADAPTIVE).providerFailureRate)
        assertEquals(GroundingProviderStatus.NOT_CONFIGURED,
            report.runs.first { it.mode == GroundingMode.VISION_ONLY }.providerStatuses[GroundingProviderId.VISION])
        assertEquals(1, report.screenshotHashes.size)
        assertTrue(report.toJson().contains("\"VISION_ONLY\""))
        assertTrue(report.toJson().contains("\"targetIdentificationAccuracy\""))
        assertFalse(report.toJson().contains("NaN"))
        assertEquals(10L, Files.list(root.resolve("results/phase5-experiments")).use { paths ->
            paths.filter { Files.isDirectory(it) && it.fileName.toString() != "screenshots" }.count()
        })
    }

    @Test
    fun unavailableProviderIsReportedButExcludedFromAccuracyDenominators() = runBlocking {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
        try {
            val result = EvidenceAdaptiveGroundingRouter(
                GroundingUiaProvider {
                    GroundingProviderRun(
                        GroundingProviderId.UIA,
                        GroundingProviderStatus.UNAVAILABLE,
                        1,
                        diagnostic = "missing_window_handle"
                    )
                },
                GroundingOcrProvider { error("OCR must not run in UIA_ONLY") },
                GroundingVisionProvider { _, _ -> error("Vision must not run in UIA_ONLY") }
            ).run(GroundingRequest(image, "Continue", GroundingMode.UIA_ONLY, caseId = "live-case"))
            val metrics = GroundingResearchMetrics.evaluate(
                listOf(result),
                mapOf("live-case" to GroundTruthAnnotation(
                    "live-case",
                    "fixture.png",
                    "Continue",
                    "Continue",
                    expectedBox = GroundingBox(5.0, 5.0, 20.0, 10.0)
                ))
            )

            assertEquals(1, metrics.cases)
            assertEquals(0, metrics.evaluatedCases)
            assertEquals(1, metrics.unavailableCases)
            assertEquals(1.0, metrics.providerFailureRate)
            assertEquals(null, metrics.coverage)
            assertEquals(null, metrics.targetIdentificationAccuracy)
            assertEquals(null, metrics.fullControlLocalizationAccuracy)
        } finally {
            image.flush()
        }
    }

    @Test
    fun csvRejectsScreenshotPathOutsideDatasetDirectory() {
        val root = Files.createTempDirectory("phase5-evaluation-path-test")
        val outside = root.resolveSibling("outside.png")
        Files.write(outside, byteArrayOf(1))
        val csv = root.resolve("cases.csv")
        val headers = listOf(
            "caseId", "screenshotPath", "targetDescription", "expectedTargetIdentity", "expectedGeometry",
            "x", "y", "width", "height", "coordinateSpace", "expectedAbsent", "notes", "windowHandle",
            "pid", "applicationName", "processName", "windowX", "windowY", "windowWidth", "windowHeight",
            "selectedX", "selectedY", "selectedWidth", "selectedHeight"
        )
        Files.writeString(csv, headers.joinToString(",") + "\n" +
            (listOf("c", "../outside.png", "Save", "", "BOX", "1", "1", "2", "2", "SCREENSHOT_PIXELS", "false", "") +
                List(12) { "" }).joinToString(","))
        try {
            GroundTruthCsv.read(csv)
            error("Path traversal was accepted.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("within the annotation directory"))
        } finally {
            Files.deleteIfExists(outside)
        }
    }
}
