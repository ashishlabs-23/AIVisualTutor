package context

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Diagnostic record of an individual expected condition evaluation.
 */
data class ConditionEvaluationDiagnostic(
    val field: String,
    val objectName: String?,
    val propertyName: String?,
    val expectedValue: String?,
    val satisfied: Boolean?
)

/**
 * Diagnostic record for a complete verification attempt.
 * Captures all relevant telemetry, observation freshness, condition evaluations,
 * and the final verification decision.
 * All fields refer to the exact same observation and verification attempt.
 */
data class VerificationAttemptDiagnostic(
    val attemptId: String,
    val taskId: String,
    val taskDescription: String,
    val expectedZCondition: String?,
    val expectedTarget: String?,
    val rawBlenderResponseSnippet: String?,
    val parsedCoordinates: Map<String, String>?,
    val objectIdentity: String?,
    val observationTimestamp: String?,
    val freshness: String?,
    val conditionResults: List<ConditionEvaluationDiagnostic>,
    val finalDecision: VerificationStatus,
    val failureReason: String?,
    val loggedAt: Instant = Instant.now()
)

/**
 * Safe, targeted diagnostic logger for verification path operations.
 * Excludes sensitive information and secrets, logging only state properties,
 * coordinates, conditions, and decisions.
 */
object VerificationDiagnosticLogger {
    private val history = CopyOnWriteArrayList<VerificationAttemptDiagnostic>()

    var logToStderr: Boolean = true

    fun record(diagnostic: VerificationAttemptDiagnostic) {
        history.add(diagnostic)
        if (logToStderr) {
            val safeCoords = diagnostic.parsedCoordinates?.let {
                "X=${it["x"] ?: it["location.x"] ?: "?"}, Y=${it["y"] ?: it["location.y"] ?: "?"}, Z=${it["z"] ?: it["location.z"] ?: "?"}"
            } ?: "none"
            val condSummary = diagnostic.conditionResults.joinToString("; ") {
                "${it.field}(${it.propertyName ?: it.objectName})=${it.expectedValue} -> ${it.satisfied}"
            }
            System.err.println(
                "[VERIFY-DIAGNOSTIC] attemptId=${diagnostic.attemptId} | task=${diagnostic.taskId} (${diagnostic.taskDescription}) | " +
                "expectedZ=${diagnostic.expectedZCondition} | targetObj=${diagnostic.expectedTarget} (id=${diagnostic.objectIdentity}) | " +
                "coords=[$safeCoords] | timestamp=${diagnostic.observationTimestamp} | freshness=${diagnostic.freshness} | " +
                "conditions=[$condSummary] | decision=${diagnostic.finalDecision} | reason=${diagnostic.failureReason ?: "none"}"
            )
        }
    }

    fun getLatestDiagnostic(): VerificationAttemptDiagnostic? = history.lastOrNull()

    fun getDiagnosticsForTask(taskId: String): List<VerificationAttemptDiagnostic> =
        history.filter { it.taskId == taskId }

    fun clear() {
        history.clear()
    }
}
