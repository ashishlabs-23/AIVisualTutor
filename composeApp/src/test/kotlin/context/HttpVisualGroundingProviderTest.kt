package context

import java.awt.image.BufferedImage
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class HttpVisualGroundingProviderTest {

    @Test
    fun successfulHttpGroundingReturnsNormalizedPointAndMetadata() = runBlocking {
        var capturedUri: String? = null
        var capturedJson: String? = null
        var capturedApiKey: String? = null

        val transport = HttpTransport { uri, requestJson, _, apiKey ->
            capturedUri = uri.toString()
            capturedJson = requestJson
            capturedApiKey = apiKey
            HttpResponsePayload(
                statusCode = 200,
                body = """{"choices":[{"message":{"content":"(450, 600)"}}]}"""
            )
        }

        val config = HttpVisualGroundingConfiguration(
            enabled = true,
            endpoint = "http://127.0.0.1:8000/v1/chat/completions",
            modelId = "UGround-V1-2B",
            apiKey = "test-key-123"
        )
        val provider = HttpVisualGroundingProvider(config, transport)

        val image = BufferedImage(100, 200, BufferedImage.TYPE_INT_RGB)
        val request = VisualGroundingRequest(
            screenshot = image,
            applicationContext = null,
            targetDescription = "Find the Save button",
            ocrEvidence = null,
            uiAutomationEvidence = null
        )

        val result = provider.ground(request)

        assertEquals(GroundingProviderAvailability.AVAILABLE, result.availability)
        assertEquals("http-uground-v1-2b", result.providerId)
        assertEquals(GroundingExecutionLocation.REMOTE, result.executionLocation)
        assertEquals(VisualGroundingCoordinateSpace.NORMALIZED_CROP, result.geometry?.coordinateSpace)
        assertEquals(GroundingPoint(0.45, 0.60), result.geometry?.point)
        assertEquals("45.0,120.0", result.metadata["pixelPoint"])
        assertEquals("SUCCESS", result.metadata["failureCategory"])
        assertEquals("point_returned", result.metadata["status"])

        // Verify request payload
        assertEquals("http://127.0.0.1:8000/v1/chat/completions", capturedUri)
        assertEquals("test-key-123", capturedApiKey)
        assertTrue(capturedJson!!.contains("\"model\":\"UGround-V1-2B\""))
        assertTrue(capturedJson!!.contains("data:image/png;base64,"))
        assertTrue(capturedJson!!.contains("Description: Find the Save button"))
    }

    @Test
    fun disabledProviderReportsNotConfigured() = runBlocking {
        var calls = 0
        val provider = HttpVisualGroundingProvider(
            HttpVisualGroundingConfiguration(enabled = false, endpoint = "http://localhost:8000"),
            HttpTransport { _, _, _, _ -> calls++; HttpResponsePayload(200, "ok") }
        )
        val result = provider.ground(request("Find Box"))
        assertEquals(GroundingProviderAvailability.NOT_CONFIGURED, result.availability)
        assertEquals(0, calls)
    }

    @Test
    fun missingOrInvalidEndpointReportsAppropriateStatus() = runBlocking {
        val missingProvider = HttpVisualGroundingProvider(
            HttpVisualGroundingConfiguration(enabled = true, endpoint = null)
        )
        assertEquals(GroundingProviderAvailability.NOT_CONFIGURED, missingProvider.preflight().availability)

        val invalidSchemeProvider = HttpVisualGroundingProvider(
            HttpVisualGroundingConfiguration(enabled = true, endpoint = "ftp://invalid-scheme/v1")
        )
        assertEquals(GroundingProviderAvailability.UNAVAILABLE, invalidSchemeProvider.preflight().availability)
    }

    @Test
    fun httpErrorStatusReturnsProcessExitNonzeroCategory() = runBlocking {
        val transport = HttpTransport { _, _, _, _ ->
            HttpResponsePayload(statusCode = 500, body = "Internal Server Error")
        }
        val provider = HttpVisualGroundingProvider(
            HttpVisualGroundingConfiguration(enabled = true, endpoint = "http://localhost:8000/v1"),
            transport
        )
        val result = provider.ground(request("Find Target"))
        assertEquals(GroundingProviderAvailability.FAILURE, result.availability)
        assertEquals("PROCESS_EXIT_NONZERO", result.metadata["failureCategory"])
        assertEquals("http_error_500", result.metadata["status"])
    }

    @Test
    fun emptyOrUnparsableOutputDistinguished() = runBlocking {
        val emptyTransport = HttpTransport { _, _, _, _ ->
            HttpResponsePayload(statusCode = 200, body = """{"choices":[{"message":{"content":"   "}}]}""")
        }
        val emptyProvider = HttpVisualGroundingProvider(
            HttpVisualGroundingConfiguration(enabled = true, endpoint = "http://localhost:8000/v1"),
            emptyTransport
        )
        val emptyResult = emptyProvider.ground(request("Find Target"))
        assertEquals(GroundingProviderAvailability.FAILURE, emptyResult.availability)
        assertEquals("EMPTY_MODEL_OUTPUT", emptyResult.metadata["failureCategory"])

        val unparseTransport = HttpTransport { _, _, _, _ ->
            HttpResponsePayload(statusCode = 200, body = """{"choices":[{"message":{"content":"I cannot see the target"}}]}""")
        }
        val unparseProvider = HttpVisualGroundingProvider(
            HttpVisualGroundingConfiguration(enabled = true, endpoint = "http://localhost:8000/v1"),
            unparseTransport
        )
        val unparseResult = unparseProvider.ground(request("Find Target"))
        assertEquals(GroundingProviderAvailability.FAILURE, unparseResult.availability)
        assertEquals("OUTPUT_PARSE_FAILED", unparseResult.metadata["failureCategory"])
    }

    @Test
    fun timeoutAndConnectionFailureHandledSafely() = runBlocking {
        val timeoutTransport = HttpTransport { _, _, _, _ ->
            throw HttpTimeoutException("Request timed out")
        }
        val timeoutProvider = HttpVisualGroundingProvider(
            HttpVisualGroundingConfiguration(enabled = true, endpoint = "http://localhost:8000/v1"),
            timeoutTransport
        )
        val timeoutResult = timeoutProvider.ground(request("Find Target"))
        assertEquals(GroundingProviderAvailability.FAILURE, timeoutResult.availability)
        assertEquals("INFERENCE_TIMEOUT", timeoutResult.metadata["failureCategory"])

        val connTransport = HttpTransport { _, _, _, _ ->
            throw IOException("Connection refused")
        }
        val connProvider = HttpVisualGroundingProvider(
            HttpVisualGroundingConfiguration(enabled = true, endpoint = "http://localhost:8000/v1"),
            connTransport
        )
        val connResult = connProvider.ground(request("Find Target"))
        assertEquals(GroundingProviderAvailability.FAILURE, connResult.availability)
        assertEquals("PROVIDER_UNAVAILABLE", connResult.metadata["failureCategory"])
    }

    @Test
    fun extractContentFromResponseHandlesRawAndJsonOutputs() {
        val jsonOutput = """{"id":"chat-1","choices":[{"message":{"role":"assistant","content":"Answer: (350, 720)"}}]}"""
        assertEquals("Answer: (350, 720)", HttpVisualGroundingProvider.extractContentFromResponse(jsonOutput))

        val escapedJsonOutput = """{"choices":[{"message":{"content":"Coordinates: (100, 200)\nTarget found"}}]}"""
        assertEquals("Coordinates: (100, 200)\nTarget found", HttpVisualGroundingProvider.extractContentFromResponse(escapedJsonOutput))

        val textOutput = """{"choices":[{"text":"(500, 300)"}]}"""
        assertEquals("(500, 300)", HttpVisualGroundingProvider.extractContentFromResponse(textOutput))

        val responseOutput = """{"response":"(200, 700)"}"""
        assertEquals("(200, 700)", HttpVisualGroundingProvider.extractContentFromResponse(responseOutput))

        val plainOutput = "(500, 500)"
        assertEquals("(500, 500)", HttpVisualGroundingProvider.extractContentFromResponse(plainOutput))
    }

    @Test
    fun normalizeEndpointResolvesVariousUrlPatterns() {
        assertEquals(
            "http://localhost:8000/v1/chat/completions",
            HttpVisualGroundingConfiguration.normalizeEndpoint("http://localhost:8000")
        )
        assertEquals(
            "http://localhost:8000/v1/chat/completions",
            HttpVisualGroundingConfiguration.normalizeEndpoint("http://localhost:8000/")
        )
        assertEquals(
            "http://localhost:8000/v1/chat/completions",
            HttpVisualGroundingConfiguration.normalizeEndpoint("http://localhost:8000/v1")
        )
        assertEquals(
            "http://localhost:8000/v1/chat/completions",
            HttpVisualGroundingConfiguration.normalizeEndpoint("http://localhost:8000/v1/")
        )
        assertEquals(
            "http://192.168.1.100:8000/v1/chat/completions",
            HttpVisualGroundingConfiguration.normalizeEndpoint("http://192.168.1.100:8000/v1/chat/completions")
        )
    }

    @Test
    fun coordinateConversionCorrectlyScalesNonSquareScreensAndHandlesEdges() = runBlocking {
        val transport = HttpTransport { _, _, _, _ ->
            HttpResponsePayload(statusCode = 200, body = """{"choices":[{"message":{"content":"(250, 750)"}}]}""")
        }
        val provider = HttpVisualGroundingProvider(
            HttpVisualGroundingConfiguration(enabled = true, endpoint = "http://localhost:8000/v1"),
            transport
        )
        // 1920x1080 screenshot
        val image = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
        val result = provider.ground(VisualGroundingRequest(
            screenshot = image,
            applicationContext = null,
            targetDescription = "Menu bar",
            ocrEvidence = null,
            uiAutomationEvidence = null
        ))

        assertEquals(GroundingProviderAvailability.AVAILABLE, result.availability)
        assertEquals(GroundingPoint(0.25, 0.75), result.geometry?.point)
        // 0.25 * 1920 = 480.0, 0.75 * 1080 = 810.0
        assertEquals("480.0,810.0", result.metadata["pixelPoint"])
        assertEquals("1920", result.metadata["cropWidthPx"])
        assertEquals("1080", result.metadata["cropHeightPx"])
    }

    private fun request(target: String) = VisualGroundingRequest(
        screenshot = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB),
        applicationContext = null,
        targetDescription = target,
        ocrEvidence = null,
        uiAutomationEvidence = null
    )
}
