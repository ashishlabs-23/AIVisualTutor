package bridge

import models.CapturedFrame
import models.CaptureSourceMode
import models.FrameMetadata
import models.HighlightRegion

/**
 * Contract defining screen and window capture operations.
 */
interface ScreenCaptureService {
    suspend fun capturePreviousWindow(): CaptureResult
    suspend fun captureRegion(region: HighlightRegion): CaptureResult
}

/**
 * Result abstraction for capture operations.
 */
sealed interface CaptureResult {
    data class Success(val frame: CapturedFrame) : CaptureResult
    data class Failure(val diagnostic: String, val exitCode: Int? = null) : CaptureResult
}

/**
 * Default implementation backed by [CaptureBridge].
 */
class WgcScreenCaptureService : ScreenCaptureService {
    override suspend fun capturePreviousWindow(): CaptureResult {
        return when (val result = CaptureBridge.capturePreviousWindow()) {
            is CaptureBridge.Result.Success -> CaptureResult.Success(
                CapturedFrame(
                    filePath = result.pngPath,
                    metadata = FrameMetadata(sourceMode = CaptureSourceMode.FULL_WINDOW_WGC)
                )
            )
            is CaptureBridge.Result.Failure -> CaptureResult.Failure(
                diagnostic = result.diagnostic,
                exitCode = result.exitCode
            )
        }
    }

    override suspend fun captureRegion(region: HighlightRegion): CaptureResult {
        return when (val result = CaptureBridge.captureRegion(region)) {
            is CaptureBridge.Result.Success -> CaptureResult.Success(
                CapturedFrame(
                    filePath = result.pngPath,
                    metadata = FrameMetadata(
                        sourceMode = CaptureSourceMode.CUSTOM_REGION_WGC,
                        bounds = region
                    )
                )
            )
            is CaptureBridge.Result.Failure -> CaptureResult.Failure(
                diagnostic = result.diagnostic,
                exitCode = result.exitCode
            )
        }
    }
}
