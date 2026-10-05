package models

import java.util.UUID

/**
 * Encapsulates the runtime context and session metadata for the tutor.
 */
data class SessionContext(
    val sessionId: String = UUID.randomUUID().toString(),
    val application: ApplicationType = ApplicationType.BLENDER,
    val startTimeMillis: Long = System.currentTimeMillis(),
    val lastCapturedFrame: CapturedFrame? = null,
    val lastVisualContext: context.VisualContext? = null
)
