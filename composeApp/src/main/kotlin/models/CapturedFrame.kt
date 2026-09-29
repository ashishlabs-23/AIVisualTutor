package models

/**
 * Represents a single captured image frame acquired during Phase 2 operations.
 */
data class CapturedFrame(
    val filePath: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val metadata: FrameMetadata = FrameMetadata()
)

/**
 * Metadata describing how and from where a frame was acquired.
 */
data class FrameMetadata(
    val sourceMode: CaptureSourceMode = CaptureSourceMode.FULL_WINDOW_WGC,
    val bounds: HighlightRegion? = null,
    val scaleFactor: Float = 1.0f
)

/**
 * The mechanism used to acquire a visual frame.
 */
enum class CaptureSourceMode {
    FULL_WINDOW_WGC,
    MANUAL_REGION_ROBOT,
    CUSTOM_REGION_WGC
}
