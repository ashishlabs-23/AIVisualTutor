package bridge

import java.awt.Rectangle
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureBridgeTest {
    @Test
    fun parsesStructuredTargetMetadataWithoutRelyingOnUnstructuredCaptureLogs() {
        val processName = Base64.getEncoder().encodeToString("fixture|process".toByteArray(StandardCharsets.UTF_8))
        val metadata = CaptureBridge.parseCapturedWindow(
            "Capturing previous eligible window\nAIVT_CAPTURE_TARGET|123456|77|650|180|520|320|$processName"
        )

        assertEquals(123456L, metadata?.windowHandle)
        assertEquals(77L, metadata?.processId)
        assertEquals("fixture|process", metadata?.processName)
        assertEquals(Rectangle(650, 180, 520, 320), metadata?.bounds)
    }

    @Test
    fun malformedOrInvalidTargetMetadataIsUnavailable() {
        assertNull(CaptureBridge.parseCapturedWindow("Capturing previous eligible window"))
        assertNull(CaptureBridge.parseCapturedWindow("AIVT_CAPTURE_TARGET|0|77|0|0|520|320|Zm9v"))
        assertNull(CaptureBridge.parseCapturedWindow("AIVT_CAPTURE_TARGET|12|77|0|0|0|320|Zm9v"))
        assertNull(CaptureBridge.parseCapturedWindow("AIVT_CAPTURE_TARGET|12|77|0|0|520|320|%%%"))
    }
}
