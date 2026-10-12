package context

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

enum class VerificationOutcome {
    VERIFIED_SUCCESS,
    VERIFIED_FAILURE,
    ABSTAINED,
    FALSE_ACCEPT,
    FALSE_REJECT
}

data class VerificationEvent(
    val eventId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val application: String,
    val taskId: String,
    val stepId: String,
    val outcome: VerificationOutcome,
    val groundTruthLabel: Boolean? = null,
    val latencyMs: Long,
    val vlmEscalated: Boolean = false,
    val vlmTokenUsage: Int = 0,
    val localEvidenceUsed: List<String> = emptyList(),
    val recoveryOffered: Boolean = false,
    val recoverySucceeded: Boolean? = null
)

data class ApplicationSummary(
    val totalEvents: Int,
    val successRate: Double,
    val abstentionRate: Double,
    val medianLatencyMs: Double
)

data class DashboardSummary(
    val totalVerifications: Int,
    val verifiedCount: Int,
    val failureCount: Int,
    val abstentionCount: Int,
    val falseAcceptCount: Int,
    val falseRejectCount: Int,
    val verificationSuccessRate: Double,
    val abstentionRate: Double,
    val falseAcceptRate: Double,
    val falseRejectRate: Double,
    val vlmEscalationFrequency: Double,
    val medianLatencyMs: Double,
    val p95LatencyMs: Double,
    val totalVlmTokens: Int,
    val recoverySuccessRate: Double,
    val perApplicationStats: Map<String, ApplicationSummary>
)

/**
 * Developer and research telemetry accumulator for tracking verification efficacy,
 * latency, abstention coverage, VLM escalation frequency, and recovery success.
 */
class VerificationDashboardTelemetry {
    private val events = CopyOnWriteArrayList<VerificationEvent>()

    fun recordEvent(event: VerificationEvent) {
        events.add(event)
    }

    fun getAllEvents(): List<VerificationEvent> = events.toList()

    fun clear() {
        events.clear()
    }

    fun getSummary(): DashboardSummary {
        val total = events.size
        if (total == 0) {
            return DashboardSummary(
                totalVerifications = 0,
                verifiedCount = 0,
                failureCount = 0,
                abstentionCount = 0,
                falseAcceptCount = 0,
                falseRejectCount = 0,
                verificationSuccessRate = 0.0,
                abstentionRate = 0.0,
                falseAcceptRate = 0.0,
                falseRejectRate = 0.0,
                vlmEscalationFrequency = 0.0,
                medianLatencyMs = 0.0,
                p95LatencyMs = 0.0,
                totalVlmTokens = 0,
                recoverySuccessRate = 0.0,
                perApplicationStats = emptyMap()
            )
        }

        var verified = 0
        var failure = 0
        var abstained = 0
        var falseAccept = 0
        var falseReject = 0
        var vlmEscalations = 0
        var totalTokens = 0
        var recoveryOfferedCount = 0
        var recoverySucceededCount = 0

        val latencies = mutableListOf<Long>()

        for (e in events) {
            latencies.add(e.latencyMs)
            if (e.vlmEscalated) vlmEscalations++
            totalTokens += e.vlmTokenUsage

            if (e.recoveryOffered) {
                recoveryOfferedCount++
                if (e.recoverySucceeded == true) recoverySucceededCount++
            }

            when (e.outcome) {
                VerificationOutcome.VERIFIED_SUCCESS -> verified++
                VerificationOutcome.VERIFIED_FAILURE -> failure++
                VerificationOutcome.ABSTAINED -> abstained++
                VerificationOutcome.FALSE_ACCEPT -> falseAccept++
                VerificationOutcome.FALSE_REJECT -> falseReject++
            }
        }

        latencies.sort()
        val medianLat = percentile(latencies, 50.0)
        val p95Lat = percentile(latencies, 95.0)

        // Per-application stats
        val appGroups = events.groupBy { it.application }
        val appSummaries = appGroups.mapValues { (_, appEvents) ->
            val appTotal = appEvents.size
            val appVerified = appEvents.count { it.outcome == VerificationOutcome.VERIFIED_SUCCESS }
            val appAbstained = appEvents.count { it.outcome == VerificationOutcome.ABSTAINED }
            val appLats = appEvents.map { it.latencyMs }.sorted()
            ApplicationSummary(
                totalEvents = appTotal,
                successRate = if (appTotal > 0) appVerified.toDouble() / appTotal else 0.0,
                abstentionRate = if (appTotal > 0) appAbstained.toDouble() / appTotal else 0.0,
                medianLatencyMs = percentile(appLats, 50.0)
            )
        }

        val totalEvaluatedGroundTruth = events.count { it.groundTruthLabel != null }

        return DashboardSummary(
            totalVerifications = total,
            verifiedCount = verified,
            failureCount = failure,
            abstentionCount = abstained,
            falseAcceptCount = falseAccept,
            falseRejectCount = falseReject,
            verificationSuccessRate = verified.toDouble() / total,
            abstentionRate = abstained.toDouble() / total,
            falseAcceptRate = if (totalEvaluatedGroundTruth > 0) falseAccept.toDouble() / totalEvaluatedGroundTruth else 0.0,
            falseRejectRate = if (totalEvaluatedGroundTruth > 0) falseReject.toDouble() / totalEvaluatedGroundTruth else 0.0,
            vlmEscalationFrequency = vlmEscalations.toDouble() / total,
            medianLatencyMs = medianLat,
            p95LatencyMs = p95Lat,
            totalVlmTokens = totalTokens,
            recoverySuccessRate = if (recoveryOfferedCount > 0) recoverySucceededCount.toDouble() / recoveryOfferedCount else 0.0,
            perApplicationStats = appSummaries
        )
    }

    private fun percentile(sortedList: List<Long>, percentile: Double): Double {
        if (sortedList.isEmpty()) return 0.0
        val index = ((percentile / 100.0) * (sortedList.size - 1)).roundToInt()
        return sortedList[index.coerceIn(0, sortedList.size - 1)].toDouble()
    }

    fun exportReportMarkdown(): String {
        val summary = getSummary()
        return buildString {
            appendLine("# Verification Telemetry Dashboard Report")
            appendLine()
            appendLine("## Overall Metrics")
            appendLine("- **Total Verifications Recorded:** ${summary.totalVerifications}")
            appendLine("- **Verified Success Rate:** ${(summary.verificationSuccessRate * 100).format(1)}% (${summary.verifiedCount})")
            appendLine("- **Abstention Rate (Coverage Filter):** ${(summary.abstentionRate * 100).format(1)}% (${summary.abstentionCount})")
            appendLine("- **False Accept Rate:** ${(summary.falseAcceptRate * 100).format(1)}% (${summary.falseAcceptCount})")
            appendLine("- **False Reject Rate:** ${(summary.falseRejectRate * 100).format(1)}% (${summary.falseRejectCount})")
            appendLine("- **VLM Escalation Frequency:** ${(summary.vlmEscalationFrequency * 100).format(1)}%")
            appendLine("- **Total VLM Tokens:** ${summary.totalVlmTokens}")
            appendLine("- **Latency (Median / p95):** ${summary.medianLatencyMs.format(1)} ms / ${summary.p95LatencyMs.format(1)} ms")
            appendLine("- **Recovery Action Success Rate:** ${(summary.recoverySuccessRate * 100).format(1)}%")
            appendLine()
            appendLine("## Per-Application Breakdown")
            appendLine("| Application | Events | Success Rate | Abstention Rate | Median Latency |")
            appendLine("|---|---|---|---|---|")
            for ((app, stats) in summary.perApplicationStats) {
                appendLine("| $app | ${stats.totalEvents} | ${(stats.successRate * 100).format(1)}% | ${(stats.abstentionRate * 100).format(1)}% | ${stats.medianLatencyMs.format(1)} ms |")
            }
        }
    }

    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}
