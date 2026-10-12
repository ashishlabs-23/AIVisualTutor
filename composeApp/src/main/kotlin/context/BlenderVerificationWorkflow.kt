package context

import adapter.ApplicationAdapter
import adapter.EvidenceCategory
import adapter.EvidenceProvenance
import adapter.EvidenceReconciler
import adapter.ObservationFreshness
import adapter.ProvenanceEvidenceObservation
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BlenderVerificationWorkflow(
    private val provider: BlenderStateProvider = BlenderTcpStateProvider(),
    private val verifier: StateVerifier = StateVerifier(),
    private val resultStore: VerificationResultStore = VerificationResultStore.default(),
    private val runWindow: Duration = Duration.ofMinutes(30),
    private val reconciler: EvidenceReconciler = EvidenceReconciler(stateVerifier = verifier)
) {
    constructor(
        applicationAdapter: ApplicationAdapter,
        verifier: StateVerifier = StateVerifier(),
        resultStore: VerificationResultStore = VerificationResultStore.default(),
        runWindow: Duration = Duration.ofMinutes(30)
    ) : this(
        provider = if (applicationAdapter is BlenderStateProvider) applicationAdapter else BlenderTcpStateProvider(),
        verifier = verifier,
        resultStore = resultStore,
        runWindow = runWindow,
        reconciler = EvidenceReconciler(stateVerifier = verifier)
    )

    init {
        require(!runWindow.isNegative && !runWindow.isZero) { "Verification run window must be positive." }
    }

    suspend fun captureBaseline(expected: ExpectedState): VerificationRun {
        val validationErrors = verifier.validate(expected)
        require(validationErrors.isEmpty()) {
            "Expected state is invalid: ${validationErrors.joinToString("; ")}"
        }
        val observation = provider.observe()
        require(observation.status == BlenderObservationStatus.AVAILABLE) {
            observation.diagnostic ?: "Blender state is unavailable."
        }
        val baseline = observation.toBaselineState(runId = java.util.UUID.randomUUID().toString())
        require(baseline.isComplete) { "Blender returned an incomplete baseline; verification cannot start." }
        val startedAt = observation.capturedAt
        return VerificationRun(
            expected = expected,
            startedAt = startedAt,
            deadline = startedAt.plus(runWindow)
        ).withBaseline(observation)
    }

    suspend fun verifyAction(run: VerificationRun): VerificationResult {
        val baseline = requireNotNull(run.baseline) { "Verification run has no baseline." }
        val observation = try {
            provider.observe()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BlenderStateObservation(
                status = BlenderObservationStatus.FAILED,
                capturedAt = Instant.now(),
                sessionId = run.applicationSessionId,
                metadata = mapOf("complete" to "false", "provider" to "blender-loopback-tcp"),
                diagnostic = "Blender state request failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }
        val observed = run.observedState(observation)
        val snapshotVer = observation.metadata["snapshotVersion"]?.toLongOrNull()
        val directProv = EvidenceProvenance(
            category = EvidenceCategory.DIRECT_APPLICATION_STATE,
            source = EvidenceSource.BLENDER,
            capturedAt = observation.capturedAt,
            snapshotVersion = snapshotVer,
            sessionId = observation.sessionId,
            isDirectFact = true,
            freshness = if (observation.status == BlenderObservationStatus.AVAILABLE) ObservationFreshness.FRESH else ObservationFreshness.UNKNOWN,
            confidence = if (observation.status == BlenderObservationStatus.AVAILABLE) 1.0 else 0.0,
            diagnostic = observation.diagnostic
        )
        val directEvidence = ProvenanceEvidenceObservation(
            provenance = directProv,
            available = observed != null,
            details = observation.metadata + mapOf(
                "status" to observation.status.name,
                "sessionId" to (observation.sessionId ?: "unknown"),
                "sceneId" to (observation.sceneId ?: "unknown"),
                "complete" to (observation.metadata["complete"] ?: "false"),
                "mode" to (observation.mode ?: "unknown")
            )
        )

        val result = reconciler.reconcileAndVerify(
            expected = run.expected,
            baseline = baseline,
            observed = observed,
            rawEvidence = listOf(directEvidence)
        )
        resultStore.persist(result)
        return result
    }

    /**
     * Automatically monitors for state change events or deltas,
     * then executes verification without requiring the user to manually click 'Verify Action'.
     */
    suspend fun autoVerifyAction(
        run: VerificationRun,
        pollIntervalMillis: Long = 300,
        maxWaitMillis: Long = 10000
    ): VerificationResult {
        val baseline = requireNotNull(run.baseline) { "Verification run has no baseline." }
        val startBaselineVersion = baseline.metadata["snapshotVersion"]?.toLongOrNull()
        val deadline = System.currentTimeMillis() + maxWaitMillis

        while (System.currentTimeMillis() < deadline) {
            val observation = try {
                provider.observe()
            } catch (_: Exception) {
                null
            }

            if (observation != null && observation.status == BlenderObservationStatus.AVAILABLE) {
                val currentVersion = observation.metadata["snapshotVersion"]?.toLongOrNull()
                val hasChanged = (currentVersion != null && startBaselineVersion != null && currentVersion > startBaselineVersion) ||
                    observation.objects.size != baseline.objects.size ||
                    observation.mode != baseline.mode

                if (hasChanged) {
                    break
                }
            }
            kotlinx.coroutines.delay(pollIntervalMillis)
        }

        return verifyAction(run)
    }
}

class VerificationResultStore(private val root: Path) {
    suspend fun persist(result: VerificationResult) = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        val output = root.resolve("${result.runId}.json")
        val temporary = root.resolve(".${result.runId}.tmp")
        try {
            Files.writeString(temporary, encode(result), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun encode(result: VerificationResult): String = buildString {
        append("{\n")
        append("  \"runId\": ").append(json(result.runId)).append(",\n")
        append("  \"operation\": ").append(json(result.operation.name)).append(",\n")
        append("  \"status\": ").append(json(result.status.name)).append(",\n")
        append("  \"explanation\": ").append(json(result.explanation)).append(",\n")
        append("  \"evidence\": [")
        append(result.evidence.joinToString(",") { evidence ->
            "{\"source\":${json(evidence.source.name)},\"capturedAt\":${json(evidence.capturedAt.toString())}," +
                "\"available\":${evidence.available},\"details\":${jsonObject(evidence.details)}," +
                "\"diagnostic\":${json(evidence.diagnostic)}}"
        })
        append("],\n")
        append("  \"diffs\": [")
        append(result.diffs.joinToString(",") { diff ->
            "{\"type\":${json(diff.type.name)},\"subject\":${json(diff.subject)},\"details\":${jsonObject(diff.details)}}"
        })
        append("],\n")
        append("  \"satisfiedConditions\": ").append(jsonConditions(result.satisfiedConditions)).append(",\n")
        append("  \"unsatisfiedConditions\": ").append(jsonConditions(result.unsatisfiedConditions)).append(",\n")
        append("  \"indeterminateConditions\": ").append(jsonConditions(result.indeterminateConditions)).append(",\n")
        append("  \"validationErrors\": ").append(result.validationErrors.joinToString(prefix = "[", postfix = "]") { json(it) }).append("\n")
        append("}\n")
    }

    private fun jsonConditions(conditions: List<ExpectedCondition>) = conditions.joinToString(prefix = "[", postfix = "]") {
        "{\"field\":${json(it.field.name)},\"objectName\":${json(it.objectName)},\"expectedValue\":${json(it.expectedValue)}," +
            "\"expectedBoolean\":${it.expectedBoolean ?: "null"},\"propertyName\":${json(it.propertyName)},\"required\":${it.required}}"
    }

    private fun jsonObject(values: Map<String, String>) = values.entries.joinToString(prefix = "{", postfix = "}") {
        "${json(it.key)}:${json(it.value)}"
    }

    private fun json(value: String?): String = value?.let {
        buildString {
            append('"')
            it.forEach { char ->
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
            append('"')
        }
    } ?: "null"

    companion object {
        fun default(): VerificationResultStore {
            val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()
            return VerificationResultStore(Path.of(localAppData, "AIVisualTutor", "verification-runs"))
        }
    }
}
