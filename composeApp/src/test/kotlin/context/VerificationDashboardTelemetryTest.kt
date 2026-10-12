package context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VerificationDashboardTelemetryTest {

    @Test
    fun `empty telemetry produces zeroed summary without error`() {
        val telemetry = VerificationDashboardTelemetry()
        val summary = telemetry.getSummary()

        assertEquals(0, summary.totalVerifications)
        assertEquals(0.0, summary.verificationSuccessRate)
        assertEquals(0.0, summary.abstentionRate)
        assertEquals(0.0, summary.medianLatencyMs)
        assertTrue(summary.perApplicationStats.isEmpty())
    }

    @Test
    fun `aggregates events correctly and calculates rates and latencies`() {
        val telemetry = VerificationDashboardTelemetry()

        telemetry.recordEvent(
            VerificationEvent(
                eventId = "e1",
                application = "Blender",
                taskId = "t1",
                stepId = "s1",
                outcome = VerificationOutcome.VERIFIED_SUCCESS,
                groundTruthLabel = true,
                latencyMs = 50,
                vlmEscalated = false
            )
        )

        telemetry.recordEvent(
            VerificationEvent(
                eventId = "e2",
                application = "Blender",
                taskId = "t1",
                stepId = "s2",
                outcome = VerificationOutcome.ABSTAINED,
                latencyMs = 150,
                vlmEscalated = true,
                vlmTokenUsage = 240
            )
        )

        telemetry.recordEvent(
            VerificationEvent(
                eventId = "e3",
                application = "Excel",
                taskId = "t2",
                stepId = "s1",
                outcome = VerificationOutcome.VERIFIED_FAILURE,
                groundTruthLabel = false,
                latencyMs = 80,
                recoveryOffered = true,
                recoverySucceeded = true
            )
        )

        val summary = telemetry.getSummary()
        assertEquals(3, summary.totalVerifications)
        assertEquals(1, summary.verifiedCount)
        assertEquals(1, summary.failureCount)
        assertEquals(1, summary.abstentionCount)

        assertEquals(1.0 / 3.0, summary.verificationSuccessRate, 0.001)
        assertEquals(1.0 / 3.0, summary.abstentionRate, 0.001)
        assertEquals(1.0 / 3.0, summary.vlmEscalationFrequency, 0.001)
        assertEquals(240, summary.totalVlmTokens)

        // Latencies sorted: 50, 80, 150 -> median is 80
        assertEquals(80.0, summary.medianLatencyMs, 0.001)
        assertEquals(1.0, summary.recoverySuccessRate, 0.001)

        // Per-application breakdown
        assertEquals(2, summary.perApplicationStats.size)
        val blenderStats = summary.perApplicationStats["Blender"]
        assertNotNull(blenderStats)
        assertEquals(2, blenderStats!!.totalEvents)
        assertEquals(0.5, blenderStats.successRate, 0.001)
        assertEquals(0.5, blenderStats.abstentionRate, 0.001)

        val markdown = telemetry.exportReportMarkdown()
        assertTrue(markdown.contains("# Verification Telemetry Dashboard Report"))
        assertTrue(markdown.contains("Blender"))
        assertTrue(markdown.contains("Excel"))
    }
}
