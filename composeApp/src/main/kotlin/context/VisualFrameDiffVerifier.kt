package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Status outcome of local visual frame differencing.
 */
enum class VisualDiffStatus {
    /** Images are visually identical or differences are below noise threshold */
    NO_CHANGE,
    /** Measurable, bounded visual change detected */
    MEASURABLE_CHANGE,
    /** Baseline and post-action images have differing dimensions (e.g. window resize/scale) */
    DIMENSIONS_CHANGED,
    /** One or both images were null, corrupt, or had non-positive dimensions */
    INVALID_IMAGES
}

/**
 * Structured result of local visual frame comparison.
 */
data class VisualDiffResult(
    val status: VisualDiffStatus,
    val changedRatio: Double,
    val totalPixelsAnalyzed: Int,
    val changedPixelCount: Int,
    val changedBoundingBox: Rectangle?,
    val imageDimensions: Rectangle,
    val regionOfInterest: Rectangle?,
    val processingDurationMs: Long,
    val warnings: List<String> = emptyList()
) {
    /** Returns true if a genuine visual change occurred */
    val hasVisualChange: Boolean get() = status == VisualDiffStatus.MEASURABLE_CHANGE
}

/**
 * Local visual frame-difference verifier.
 * Computes deterministic perceptual pixel differences, extracts bounding clusters of change,
 * and quantifies change ratios with zero external dependencies and zero VLM token cost.
 */
class VisualFrameDiffVerifier(
    /** Per-channel Euclidean color distance threshold (0.0 to 441.67). Default 25.0 filters rendering jitter */
    val colorDistanceThreshold: Double = 25.0,
    /** Minimum ratio of changed pixels in ROI to qualify as MEASURABLE_CHANGE (default 0.0001 = 0.01%) */
    val minimumChangeRatio: Double = 0.0001,
    /** Maximum ratio before flagging as potentially full-screen disruption/window switch (default 0.90) */
    val maximumChangeRatio: Double = 0.90,
    /** Minimum contiguous pixels to reject single-pixel anti-aliasing / caret flickers */
    val minPixelNoiseCluster: Int = 16
) {

    /**
     * Compares [baseline] and [postAction] frames within an optional [regionOfInterest].
     */
    fun compare(
        baseline: BufferedImage?,
        postAction: BufferedImage?,
        regionOfInterest: Rectangle? = null
    ): VisualDiffResult {
        val startTime = System.currentTimeMillis()

        if (baseline == null || postAction == null) {
            return VisualDiffResult(
                status = VisualDiffStatus.INVALID_IMAGES,
                changedRatio = 0.0,
                totalPixelsAnalyzed = 0,
                changedPixelCount = 0,
                changedBoundingBox = null,
                imageDimensions = Rectangle(0, 0, 0, 0),
                regionOfInterest = regionOfInterest,
                processingDurationMs = System.currentTimeMillis() - startTime,
                warnings = listOf("One or both input images were null")
            )
        }

        val w1 = baseline.width
        val h1 = baseline.height
        val w2 = postAction.width
        val h2 = postAction.height

        if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) {
            return VisualDiffResult(
                status = VisualDiffStatus.INVALID_IMAGES,
                changedRatio = 0.0,
                totalPixelsAnalyzed = 0,
                changedPixelCount = 0,
                changedBoundingBox = null,
                imageDimensions = Rectangle(0, 0, max(w1, w2), max(h1, h2)),
                regionOfInterest = regionOfInterest,
                processingDurationMs = System.currentTimeMillis() - startTime,
                warnings = listOf("Non-positive image dimensions: baseline=(${w1}x${h1}), postAction=(${w2}x${h2})")
            )
        }

        val warnings = mutableListOf<String>()
        if (w1 != w2 || h1 != h2) {
            warnings.add("Dimension mismatch: baseline (${w1}x${h1}) vs postAction (${w2}x${h2}). Comparing overlapping viewport.")
        }

        val intersectWidth = min(w1, w2)
        val intersectHeight = min(h1, h2)
        val fullImageRect = Rectangle(0, 0, intersectWidth, intersectHeight)

        // Clip ROI to the available image intersection
        val targetRoi = if (regionOfInterest != null) {
            val clipped = regionOfInterest.intersection(fullImageRect)
            if (clipped.isEmpty) {
                return VisualDiffResult(
                    status = VisualDiffStatus.NO_CHANGE,
                    changedRatio = 0.0,
                    totalPixelsAnalyzed = 0,
                    changedPixelCount = 0,
                    changedBoundingBox = null,
                    imageDimensions = fullImageRect,
                    regionOfInterest = regionOfInterest,
                    processingDurationMs = System.currentTimeMillis() - startTime,
                    warnings = listOf("Specified ROI ($regionOfInterest) does not intersect image bounds ($fullImageRect)")
                )
            }
            clipped
        } else {
            fullImageRect
        }

        val totalPixels = targetRoi.width * targetRoi.height
        var changedPixels = 0

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        val startX = targetRoi.x
        val startY = targetRoi.y
        val endX = targetRoi.x + targetRoi.width
        val endY = targetRoi.y + targetRoi.height

        for (y in startY until endY) {
            for (x in startX until endX) {
                val rgb1 = baseline.getRGB(x, y)
                val rgb2 = postAction.getRGB(x, y)

                if (rgb1 != rgb2) {
                    val r1 = (rgb1 shr 16) and 0xFF
                    val g1 = (rgb1 shr 8) and 0xFF
                    val b1 = rgb1 and 0xFF

                    val r2 = (rgb2 shr 16) and 0xFF
                    val g2 = (rgb2 shr 8) and 0xFF
                    val b2 = rgb2 and 0xFF

                    val dr = r1 - r2
                    val dg = g1 - g2
                    val db = b1 - b2
                    val dist = sqrt((dr * dr + dg * dg + db * db).toDouble())

                    if (dist >= colorDistanceThreshold) {
                        changedPixels++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
        }

        val changedRatio = if (totalPixels > 0) changedPixels.toDouble() / totalPixels.toDouble() else 0.0
        val changedBox = if (changedPixels >= minPixelNoiseCluster) {
            Rectangle(minX, minY, max(1, maxX - minX + 1), max(1, maxY - minY + 1))
        } else {
            null
        }

        val status = when {
            w1 != w2 || h1 != h2 -> VisualDiffStatus.DIMENSIONS_CHANGED
            changedPixels >= minPixelNoiseCluster && changedRatio >= minimumChangeRatio -> VisualDiffStatus.MEASURABLE_CHANGE
            else -> VisualDiffStatus.NO_CHANGE
        }

        if (changedRatio > maximumChangeRatio) {
            warnings.add("Large visual disruption: ${"%.1f".format(changedRatio * 100)}% of ROI changed (possible window minimize or tab switch)")
        }

        return VisualDiffResult(
            status = status,
            changedRatio = changedRatio,
            totalPixelsAnalyzed = totalPixels,
            changedPixelCount = changedPixels,
            changedBoundingBox = changedBox,
            imageDimensions = fullImageRect,
            regionOfInterest = targetRoi,
            processingDurationMs = System.currentTimeMillis() - startTime,
            warnings = warnings
        )
    }
}
