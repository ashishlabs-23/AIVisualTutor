package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min

/**
 * Source evidence used to determine the localized RegionFocus crop.
 */
enum class RegionFocusSource {
    /** Focused on the neighborhood surrounding a candidate target or OCR match */
    TARGET_NEIGHBORHOOD,
    /** Focused on the active application window bounds */
    APPLICATION_WINDOW,
    /** Focused on a known UI panel quadrant (e.g. toolbar, inspector) */
    PANEL_QUADRANT,
    /** Full-screen fallback when no localized metadata is available */
    FULL_SCREEN_FALLBACK
}

/**
 * Bidirectional coordinate transform between crop pixels and physical screen coordinates.
 */
data class CropCoordinateTransform(
    val screenOffsetX: Int,
    val screenOffsetY: Int,
    val scaleFactorX: Double = 1.0,
    val scaleFactorY: Double = 1.0
) {
    /**
     * Converts a point from crop pixel coordinates to physical desktop screen coordinates.
     */
    fun cropToScreen(cropX: Double, cropY: Double): Pair<Double, Double> {
        val screenX = (cropX * scaleFactorX) + screenOffsetX
        val screenY = (cropY * scaleFactorY) + screenOffsetY
        return Pair(screenX, screenY)
    }

    /**
     * Converts a point from physical desktop screen coordinates to crop pixel coordinates.
     */
    fun screenToCrop(screenX: Double, screenY: Double): Pair<Double, Double> {
        val cropX = (screenX - screenOffsetX) / scaleFactorX
        val cropY = (screenY - screenOffsetY) / scaleFactorY
        return Pair(cropX, cropY)
    }
}

/**
 * Result of RegionFocus sub-cropping.
 */
data class RegionFocusCrop(
    val croppedImage: BufferedImage,
    val cropRectOnScreen: Rectangle,
    val source: RegionFocusSource,
    val originalDimensions: Rectangle,
    val cropDimensions: Rectangle,
    val transform: CropCoordinateTransform,
    val estimatedPixelReductionRatio: Double,
    val diagnostic: String? = null
)

/**
 * Service that extracts task-relevant sub-crops before dispatching to multimodal VLMs.
 * Reduces token consumption, preserves native visual sharpness, and guarantees
 * bidirectional coordinate conversion back to physical screen space.
 */
class RegionFocusService(
    val contextualMarginPx: Int = 180,
    val minimumDimensionPx: Int = 48
) {

    /**
     * Generates an intelligent localized sub-crop from [screenshot].
     */
    fun createFocusedCrop(
        screenshot: BufferedImage,
        targetBoundsOnScreen: Rectangle? = null,
        appContext: ApplicationContext? = null,
        targetAreaDescription: String? = null
    ): RegionFocusCrop {
        val screenW = screenshot.width
        val screenH = screenshot.height
        val fullRect = Rectangle(0, 0, screenW, screenH)

        val windowBounds = appContext?.windowBounds

        // 1. Target Neighborhood (if target/candidate bounds are provided)
        if (targetBoundsOnScreen != null && targetBoundsOnScreen.width > 0 && targetBoundsOnScreen.height > 0) {
            // Translate desktop coordinates to screenshot coordinates
            val localX = if (windowBounds != null) targetBoundsOnScreen.x - windowBounds.x else targetBoundsOnScreen.x
            val localY = if (windowBounds != null) targetBoundsOnScreen.y - windowBounds.y else targetBoundsOnScreen.y

            val expandedX = max(0, localX - contextualMarginPx)
            val expandedY = max(0, localY - contextualMarginPx)
            val expandedW = min(screenW - expandedX, targetBoundsOnScreen.width + (contextualMarginPx * 2))
            val expandedH = min(screenH - expandedY, targetBoundsOnScreen.height + (contextualMarginPx * 2))

            val cropRect = clampCrop(expandedX, expandedY, expandedW, expandedH, screenW, screenH)
            if (isValidCrop(cropRect)) {
                return extractSubImage(
                    screenshot = screenshot,
                    cropRect = cropRect,
                    screenOriginX = (windowBounds?.x ?: 0) + cropRect.x,
                    screenOriginY = (windowBounds?.y ?: 0) + cropRect.y,
                    source = RegionFocusSource.TARGET_NEIGHBORHOOD,
                    fullRect = fullRect,
                    diagnostic = "Cropped around target bounds (${targetBoundsOnScreen.width}x${targetBoundsOnScreen.height}) with ${contextualMarginPx}px margin"
                )
            }
        }

        // 2. Panel Quadrant Heuristic (if targetAreaDescription hints at a specific panel)
        val quadrantCrop = resolveQuadrantCrop(targetAreaDescription, screenW, screenH)
        if (quadrantCrop != null && isValidCrop(quadrantCrop)) {
            val originX = windowBounds?.x ?: 0
            val originY = windowBounds?.y ?: 0
            return extractSubImage(
                screenshot = screenshot,
                cropRect = quadrantCrop,
                screenOriginX = originX + quadrantCrop.x,
                screenOriginY = originY + quadrantCrop.y,
                source = RegionFocusSource.PANEL_QUADRANT,
                fullRect = fullRect,
                diagnostic = "Cropped to UI quadrant based on area hint: '$targetAreaDescription'"
            )
        }

        // 3. Application Window Focus (if active application window bounds are available)
        if (windowBounds != null && windowBounds.width > 0 && windowBounds.height > 0) {
            val clampedX = max(0, min(screenW - 1, windowBounds.x))
            val clampedY = max(0, min(screenH - 1, windowBounds.y))
            val clampedW = min(screenW - clampedX, windowBounds.width)
            val clampedH = min(screenH - clampedY, windowBounds.height)

            val cropRect = clampCrop(clampedX, clampedY, clampedW, clampedH, screenW, screenH)
            if (isValidCrop(cropRect)) {
                return extractSubImage(
                    screenshot = screenshot,
                    cropRect = cropRect,
                    screenOriginX = windowBounds.x,
                    screenOriginY = windowBounds.y,
                    source = RegionFocusSource.APPLICATION_WINDOW,
                    fullRect = fullRect,
                    diagnostic = "Cropped to active application window (${appContext.applicationName ?: "app"}) bounds"
                )
            }
        }

        // 4. Fullscreen Fallback
        return RegionFocusCrop(
            croppedImage = screenshot,
            cropRectOnScreen = Rectangle(windowBounds?.x ?: 0, windowBounds?.y ?: 0, screenW, screenH),
            source = RegionFocusSource.FULL_SCREEN_FALLBACK,
            originalDimensions = fullRect,
            cropDimensions = fullRect,
            transform = CropCoordinateTransform(
                screenOffsetX = windowBounds?.x ?: 0,
                screenOffsetY = windowBounds?.y ?: 0
            ),
            estimatedPixelReductionRatio = 0.0,
            diagnostic = "Full screenshot fallback (no localized bounds available)"
        )
    }

    private fun resolveQuadrantCrop(areaDesc: String?, w: Int, h: Int): Rectangle? {
        val desc = areaDesc?.lowercase().orEmpty()
        return when {
            desc.contains("left") || desc.contains("toolbar") -> Rectangle(0, 0, (w * 0.35).toInt(), h)
            desc.contains("right") || desc.contains("properties") || desc.contains("inspector") -> Rectangle((w * 0.65).toInt(), 0, (w * 0.35).toInt(), h)
            desc.contains("top") || desc.contains("header") || desc.contains("menu") -> Rectangle(0, 0, w, (h * 0.30).toInt())
            desc.contains("bottom") || desc.contains("status") -> Rectangle(0, (h * 0.70).toInt(), w, (h * 0.30).toInt())
            else -> null
        }
    }

    private fun clampCrop(x: Int, y: Int, w: Int, h: Int, maxW: Int, maxH: Int): Rectangle {
        val cx = max(0, min(maxW - minimumDimensionPx, x))
        val cy = max(0, min(maxH - minimumDimensionPx, y))
        val cw = max(minimumDimensionPx, min(maxW - cx, w))
        val ch = max(minimumDimensionPx, min(maxH - cy, h))
        return Rectangle(cx, cy, cw, ch)
    }

    private fun isValidCrop(rect: Rectangle): Boolean {
        return rect.width >= minimumDimensionPx && rect.height >= minimumDimensionPx
    }

    private fun extractSubImage(
        screenshot: BufferedImage,
        cropRect: Rectangle,
        screenOriginX: Int,
        screenOriginY: Int,
        source: RegionFocusSource,
        fullRect: Rectangle,
        diagnostic: String
    ): RegionFocusCrop {
        val sub = screenshot.getSubimage(cropRect.x, cropRect.y, cropRect.width, cropRect.height)
        val fullPixels = fullRect.width * fullRect.height
        val cropPixels = cropRect.width * cropRect.height
        val reduction = if (fullPixels > 0) 1.0 - (cropPixels.toDouble() / fullPixels.toDouble()) else 0.0

        val transform = CropCoordinateTransform(
            screenOffsetX = screenOriginX,
            screenOffsetY = screenOriginY
        )

        return RegionFocusCrop(
            croppedImage = sub,
            cropRectOnScreen = Rectangle(screenOriginX, screenOriginY, cropRect.width, cropRect.height),
            source = source,
            originalDimensions = fullRect,
            cropDimensions = Rectangle(0, 0, cropRect.width, cropRect.height),
            transform = transform,
            estimatedPixelReductionRatio = max(0.0, reduction),
            diagnostic = diagnostic
        )
    }
}
