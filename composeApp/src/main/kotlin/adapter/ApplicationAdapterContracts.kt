package adapter

import context.EvidenceObservation
import context.EvidenceSource
import context.ObjectObservation
import java.time.Instant
import java.util.UUID

/**
 * Unique identification for an application adapter.
 */
data class AdapterIdentity(
    val id: String,
    val applicationName: String,
    val adapterVersion: String = "1.0.0",
    val supportedSchemaVersion: Int = 1
)

/**
 * Representation of an active application session.
 */
data class ApplicationSession(
    val sessionId: String,
    val applicationName: String,
    val applicationVersion: String,
    val startedAt: Instant = Instant.now(),
    val activeSceneId: String? = null,
    val activeSceneName: String? = null,
    val isAlive: Boolean = true
)

/**
 * Health status of an application adapter.
 */
enum class AdapterHealthStatus {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    TERMINATED
}

/**
 * Detailed health report of an application adapter.
 */
data class AdapterHealth(
    val status: AdapterHealthStatus,
    val adapterId: String,
    val applicationName: String,
    val sessionId: String? = null,
    val lastSnapshotVersion: Long = 0L,
    val diagnostic: String? = null,
    val checkedAt: Instant = Instant.now(),
    val details: Map<String, String> = emptyMap()
)

/**
 * Freshness status of an observation.
 */
enum class ObservationFreshness {
    FRESH,
    STALE,
    EXPIRED,
    UNKNOWN
}

/**
 * Distinct evidence categories across the multi-modal evidence pipeline.
 */
enum class EvidenceCategory {
    /** Ground truth application state retrieved directly from the application adapter */
    DIRECT_APPLICATION_STATE,
    /** Semantic UI observations from Windows UI Automation */
    SEMANTIC_UI_AUTOMATION,
    /** Optical character recognition from on-screen pixels */
    OCR_TEXT,
    /** Machine learning visual model / VLM predictions */
    VISUAL_MODEL_PREDICTION,
    /** Higher-level derived conclusions or synthesized evaluations */
    DERIVED_CONCLUSION
}

/**
 * Rigorous provenance information attached to an evidence observation.
 */
data class EvidenceProvenance(
    val observationId: String = UUID.randomUUID().toString(),
    val category: EvidenceCategory,
    val source: EvidenceSource,
    val capturedAt: Instant = Instant.now(),
    val snapshotVersion: Long? = null,
    val sessionId: String? = null,
    val taskId: String? = null,
    val stepId: String? = null,
    val isDirectFact: Boolean = (category == EvidenceCategory.DIRECT_APPLICATION_STATE),
    val freshness: ObservationFreshness = ObservationFreshness.FRESH,
    val confidence: Double? = if (isDirectFact) 1.0 else null,
    val diagnostic: String? = null,
    val error: String? = null
)

/**
 * Enhanced evidence observation unifying Phase 6/7 evidence models with Phase 8 provenance.
 */
data class ProvenanceEvidenceObservation(
    val provenance: EvidenceProvenance,
    val objectObservation: ObjectObservation? = null,
    val observedValue: Any? = null,
    val available: Boolean = true,
    val details: Map<String, String> = emptyMap()
) {
    fun toLegacyEvidenceObservation(): EvidenceObservation = EvidenceObservation(
        source = provenance.source,
        capturedAt = provenance.capturedAt,
        available = available,
        objectObservation = objectObservation,
        details = details + listOfNotNull(
            "observationId" to provenance.observationId,
            "category" to provenance.category.name,
            "freshness" to provenance.freshness.name,
            "isDirectFact" to provenance.isDirectFact.toString(),
            provenance.snapshotVersion?.let { "snapshotVersion" to it.toString() },
            provenance.sessionId?.let { "sessionId" to it },
            provenance.confidence?.let { "confidence" to "%.3f".format(it) }
        ).toMap(),
        diagnostic = provenance.diagnostic ?: provenance.error
    )
}

/**
 * Strongly typed representation of an object in an adapter snapshot.
 */
data class AdapterObjectObservation(
    val identity: String,
    val name: String,
    val type: String,
    val sceneName: String,
    val selected: Boolean? = null,
    val location: List<Double>? = null,
    val rotation: List<Double>? = null,
    val scale: List<Double>? = null,
    val visible: Boolean? = true,
    val properties: Map<String, String> = emptyMap()
) {
    fun toObjectObservation(): ObjectObservation = ObjectObservation(
        id = identity,
        name = name,
        type = type,
        sceneName = sceneName,
        selected = selected,
        metadata = properties + listOfNotNull(
            location?.let { "location" to it.joinToString(",") },
            rotation?.let { "rotation" to it.joinToString(",") },
            scale?.let { "scale" to it.joinToString(",") },
            visible?.let { "visible" to it.toString() }
        ).toMap()
    )
}

/**
 * Representation of a scene in an adapter snapshot.
 */
data class AdapterSceneIdentity(
    val identity: String,
    val name: String
)

/**
 * Versioned, immutable snapshot of the application state.
 */
data class AdapterSnapshot(
    val schemaVersion: Int = 1,
    val applicationName: String,
    val applicationVersion: String,
    val sessionId: String,
    val snapshotVersion: Long,
    val observedAt: Instant = Instant.now(),
    val scene: AdapterSceneIdentity,
    val activeObject: AdapterObjectObservation? = null,
    val selectedObjects: Set<String> = emptySet(),
    val activeMode: String? = null,
    val workspace: String? = null,
    val objects: List<AdapterObjectObservation> = emptyList(),
    val isComplete: Boolean = true,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Result of an adapter snapshot request.
 */
sealed class AdapterSnapshotResult {
    data class Success(val snapshot: AdapterSnapshot) : AdapterSnapshotResult()
    data class Stale(val snapshotVersion: Long, val currentVersion: Long, val reason: String) : AdapterSnapshotResult()
    data class Unavailable(val diagnostic: String, val error: Throwable? = null) : AdapterSnapshotResult()
    data class Error(val diagnostic: String, val errorCode: String? = null, val error: Throwable? = null) : AdapterSnapshotResult()
}

/**
 * Declares the specific observability and verification capabilities supported by an adapter.
 */
enum class AdapterCapability {
    /** Can observe internal 3D/canvas objects, names, types, and spatial transforms */
    OBJECT_STATE,
    /** Can observe active and selected items */
    SELECTION_STATE,
    /** Can observe active application/interaction modes (e.g. OBJECT vs EDIT) */
    MODE_STATE,
    /** Can observe document structural elements (e.g. worksheets, slides, pages) */
    DOCUMENT_STRUCTURE,
    /** Can observe cell values and calculation formulas */
    CELL_FORMULAS,
    /** Can detect visual pixel changes */
    VISUAL_CHANGE,
    /** Streams or notifies of live application events without manual polling */
    LIVE_CHANGE_EVENTS
}

/**
 * Generic contract for application adapters.
 */
interface ApplicationAdapter {
    val identity: AdapterIdentity
    val capabilities: Set<AdapterCapability>
        get() = emptySet()

    /** Returns whether this adapter supports the specified capability */
    fun supports(capability: AdapterCapability): Boolean = capabilities.contains(capability)

    /** Reports the connection and health status of the adapter */
    suspend fun checkHealth(): AdapterHealth

    /** Returns the active session if currently connected */
    suspend fun currentSession(): ApplicationSession?

    /** Retrieves an application-state snapshot */
    suspend fun takeSnapshot(): AdapterSnapshotResult

    /** Closes the adapter connection cleanly */
    suspend fun close()
}
