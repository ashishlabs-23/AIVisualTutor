package context

import java.awt.Rectangle
import kotlin.math.roundToInt
import models.HighlightRegion

/**
 * Coordinate transform engine bridging Physical Desktop pixels, Logical Compose Desktop DIPs,
 * Window-Relative coordinates, and Screenshot-Relative raster coordinates.
 *
 * Supports:
 * - Multi-monitor virtual desktop coordinates including negative origins.
 * - Mixed DPI scaling per monitor (100%, 125%, 150%, 200%).
 * - Bidirectional transformation with mathematical round-trip consistency.
 * - Safe clamping and boundary guards against invalid/collapsed dimensions.
 */
class DesktopCoordinateTransform(
    private val dpiNormalizer: DpiCoordinateNormalizer = DpiCoordinateNormalizer()
) {

    /**
     * Converts a window-relative rectangle into physical desktop screen coordinates.
     */
    fun windowRelativeToPhysical(relativeRect: Rectangle, windowBounds: Rectangle): Rectangle {
        require(windowBounds.width > 0 && windowBounds.height > 0) {
            "Window bounds must have positive dimensions: ${windowBounds.width}x${windowBounds.height}"
        }
        val physX = windowBounds.x + relativeRect.x
        val physY = windowBounds.y + relativeRect.y
        val physW = relativeRect.width.coerceAtLeast(1)
        val physH = relativeRect.height.coerceAtLeast(1)
        return Rectangle(physX, physY, physW, physH)
    }

    /**
     * Converts physical desktop screen coordinates to coordinates relative to a target window.
     */
    fun physicalToWindowRelative(physicalRect: Rectangle, windowBounds: Rectangle): Rectangle {
        require(windowBounds.width > 0 && windowBounds.height > 0) {
            "Window bounds must have positive dimensions: ${windowBounds.width}x${windowBounds.height}"
        }
        val relX = physicalRect.x - windowBounds.x
        val relY = physicalRect.y - windowBounds.y
        val relW = physicalRect.width.coerceAtLeast(1)
        val relH = physicalRect.height.coerceAtLeast(1)
        return Rectangle(relX, relY, relW, relH)
    }

    /**
     * Converts physical desktop screen coordinates to logical Compose Desktop layout DIPs.
     */
    fun physicalToLogical(physicalRect: Rectangle): Rectangle {
        return dpiNormalizer.physicalToLogical(physicalRect)
    }

    /**
     * Converts logical Compose Desktop layout DIPs to physical desktop screen coordinates.
     */
    fun logicalToPhysical(logicalRect: Rectangle): Rectangle {
        return dpiNormalizer.logicalToPhysical(logicalRect)
    }

    /**
     * Converts physical desktop screen coordinates to screenshot image raster pixel coordinates.
     * [captureOrigin]: Physical screen origin where the screenshot began (top-left of captured window/region).
     * [rasterScale]: Ratio of image raster width to physical capture width (default 1.0).
     */
    fun physicalToScreenshot(
        physicalRect: Rectangle,
        captureOrigin: Rectangle,
        rasterScale: Double = 1.0
    ): Rectangle {
        require(rasterScale > 0.0) { "Raster scale must be positive: $rasterScale" }
        val relX = ((physicalRect.x - captureOrigin.x) * rasterScale).roundToInt()
        val relY = ((physicalRect.y - captureOrigin.y) * rasterScale).roundToInt()
        val relW = (physicalRect.width * rasterScale).roundToInt().coerceAtLeast(1)
        val relH = (physicalRect.height * rasterScale).roundToInt().coerceAtLeast(1)
        return Rectangle(relX, relY, relW, relH)
    }

    /**
     * Converts screenshot image raster pixel coordinates back to physical desktop screen coordinates.
     */
    fun screenshotToPhysical(
        screenshotRect: Rectangle,
        captureOrigin: Rectangle,
        rasterScale: Double = 1.0
    ): Rectangle {
        require(rasterScale > 0.0) { "Raster scale must be positive: $rasterScale" }
        val physX = captureOrigin.x + (screenshotRect.x / rasterScale).roundToInt()
        val physY = captureOrigin.y + (screenshotRect.y / rasterScale).roundToInt()
        val physW = (screenshotRect.width / rasterScale).roundToInt().coerceAtLeast(1)
        val physH = (screenshotRect.height / rasterScale).roundToInt().coerceAtLeast(1)
        return Rectangle(physX, physY, physW, physH)
    }

    /**
     * Converts a logical Compose rectangle to a [HighlightRegion] data model.
     */
    fun toHighlightRegion(logicalRect: Rectangle): HighlightRegion {
        return HighlightRegion(
            x = logicalRect.x.toFloat(),
            y = logicalRect.y.toFloat(),
            width = logicalRect.width.toFloat().coerceAtLeast(1f),
            height = logicalRect.height.toFloat().coerceAtLeast(1f)
        )
    }

    /**
     * Converts a [HighlightRegion] data model to an AWT [Rectangle].
     */
    fun fromHighlightRegion(region: HighlightRegion): Rectangle {
        return Rectangle(
            region.x.roundToInt(),
            region.y.roundToInt(),
            region.width.roundToInt().coerceAtLeast(1),
            region.height.roundToInt().coerceAtLeast(1)
        )
    }

    /**
     * Adjusts a highlight region relative to an active window offset and virtual screen offset.
     */
    fun adjustRegionForOverlay(
        region: HighlightRegion,
        windowOffset: Rectangle?,
        virtualScreenOrigin: Rectangle
    ): HighlightRegion {
        val baseRect = fromHighlightRegion(region)
        // If window offset is provided (window-relative target), translate to screen coordinates first
        val screenRect = if (windowOffset != null) {
            windowRelativeToPhysical(baseRect, windowOffset)
        } else {
            baseRect
        }
        // Offset by virtual screen origin so it renders accurately in the overlay canvas
        val overlayX = screenRect.x - virtualScreenOrigin.x
        val overlayY = screenRect.y - virtualScreenOrigin.y
        return HighlightRegion(
            x = overlayX.toFloat(),
            y = overlayY.toFloat(),
            width = screenRect.width.toFloat(),
            height = screenRect.height.toFloat()
        )
    }
}
