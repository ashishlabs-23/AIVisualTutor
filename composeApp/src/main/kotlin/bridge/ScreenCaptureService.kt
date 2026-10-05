package bridge

import models.CapturedFrame
import models.CaptureSourceMode
import models.FrameMetadata
import models.HighlightRegion
import context.ContextLogger
import context.LifecycleEvent
import context.StderrContextLogger
import selection.FrozenScreenSnapshot
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Contract defining screen and window capture operations.
 */
interface ScreenCaptureService {
    suspend fun capturePreviousWindow(): CaptureResult
    suspend fun captureRegion(region: HighlightRegion): CaptureResult
    /** Captures only a selection from the immutable frame taken when selection began. */
    suspend fun captureFrozenRegion(snapshot: FrozenScreenSnapshot, desktopBounds: Rectangle, regionId: UUID): BufferedImage
}

/** Crops the immutable selection-start frame in memory; coordinates describe its desktop pixel space. */
class FrozenSnapshotRegionCaptureService(private val logger: ContextLogger = StderrContextLogger()) {
    fun crop(snapshot: FrozenScreenSnapshot, desktopSelection: Rectangle, regionId: UUID? = null): BufferedImage {
        val clipped = desktopSelection.intersection(snapshot.monitorBounds)
        require(!clipped.isEmpty) { "Selection does not intersect a connected monitor." }
        val parts = snapshot.monitors.mapNotNull { monitor ->
            val logical = clipped.intersection(monitor.desktopBounds)
            if (logical.isEmpty) null else {
                val left = floor((logical.x - monitor.desktopBounds.x) * monitor.scaleX).toInt().coerceIn(0, monitor.image.width)
                val top = floor((logical.y - monitor.desktopBounds.y) * monitor.scaleY).toInt().coerceIn(0, monitor.image.height)
                val right = ceil((logical.x + logical.width - monitor.desktopBounds.x) * monitor.scaleX).toInt().coerceIn(left, monitor.image.width)
                val bottom = ceil((logical.y + logical.height - monitor.desktopBounds.y) * monitor.scaleY).toInt().coerceIn(top, monitor.image.height)
                RegionTile(logical, Rectangle(left, top, right - left, bottom - top), monitor.image, monitor.scaleX, monitor.scaleY)
            }
        }
        require(parts.isNotEmpty()) { "Selection does not intersect a connected monitor." }
        // At mixed DPI, use the highest source scale among intersecting monitors as the output grid.
        // This preserves every source pixel (lower-DPI tiles may be interpolated upward, never downsampled).
        val outputScaleX = parts.maxOf { it.scaleX }
        val outputScaleY = parts.maxOf { it.scaleY }
        val outputWidth = ceil(clipped.width * outputScaleX).toInt()
        val outputHeight = ceil(clipped.height * outputScaleY).toInt()
        if (outputWidth <= selection.MINIMUM_SELECTION_PIXELS || outputHeight <= selection.MINIMUM_SELECTION_PIXELS) {
            throw context.SelectionTooSmallException("Selected region must be larger than ${selection.MINIMUM_SELECTION_PIXELS} physical pixels in both dimensions.")
        }
        val output = BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            graphics.color = java.awt.Color.BLACK
            graphics.fillRect(0, 0, output.width, output.height)
            parts.forEach { part ->
                val targetX = floor((part.desktop.x - clipped.x) * outputScaleX).toInt()
                val targetY = floor((part.desktop.y - clipped.y) * outputScaleY).toInt()
                val targetRight = ceil((part.desktop.x + part.desktop.width - clipped.x) * outputScaleX).toInt()
                val targetBottom = ceil((part.desktop.y + part.desktop.height - clipped.y) * outputScaleY).toInt()
                graphics.drawImage(part.source,
                    targetX, targetY, targetRight, targetBottom,
                    part.sourcePixels.x, part.sourcePixels.y, part.sourcePixels.x + part.sourcePixels.width, part.sourcePixels.y + part.sourcePixels.height, null)
            }
        } finally { graphics.dispose() }
        logger.event(LifecycleEvent.CAPTURE_COMPLETED, mapOf("regionId" to regionId, "width" to output.width, "height" to output.height, "state" to "CAPTURE_COMPLETED"))
        return output
    }

    fun persistPreview(image: BufferedImage): Path {
        val path = Files.createTempFile("AIVT_${UUID.randomUUID().toString().replace("-", "")}_", ".png")
        try {
            check(ImageIO.write(image, "png", path.toFile())) { "No PNG writer is available." }
            return path
        } catch (e: Exception) {
            Files.deleteIfExists(path)
            throw e
        }
    }

    private data class RegionTile(val desktop: Rectangle, val sourcePixels: Rectangle, val source: BufferedImage, val scaleX: Double, val scaleY: Double)
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
class WgcScreenCaptureService(private val logger: ContextLogger = StderrContextLogger()) : ScreenCaptureService {
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

    override suspend fun captureFrozenRegion(snapshot: FrozenScreenSnapshot, desktopBounds: Rectangle, regionId: UUID): BufferedImage =
        FrozenSnapshotRegionCaptureService(logger).crop(snapshot, desktopBounds, regionId)
}
