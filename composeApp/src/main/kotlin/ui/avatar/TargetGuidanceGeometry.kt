package ui.avatar

import models.HighlightRegion
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 2D point on desktop / screen coordinate space.
 */
data class ScreenPoint(
    val x: Float,
    val y: Float
) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()

    fun scale(factor: Float): ScreenPoint = ScreenPoint(x * factor, y * factor)
}

/**
 * 2D rectangular bounds on desktop / screen coordinate space.
 */
data class ScreenRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()
    val isValid: Boolean get() = isFinite && width > 0f && height > 0f
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
    val centerPoint: ScreenPoint get() = ScreenPoint(centerX, centerY)

    fun scale(factor: Float): ScreenRect = ScreenRect(x * factor, y * factor, width * factor, height * factor)
}

/**
 * Convenience extension to bridge existing [HighlightRegion] to [ScreenRect].
 */
fun HighlightRegion.toScreenRect(): ScreenRect = ScreenRect(x, y, width, height)

/**
 * Evaluation of geometry validity for directional guidance.
 */
enum class GuidanceValidity {
    /** Target and anchor coordinates are valid and usable for directional guidance. */
    VALID,
    /** Target rectangle has zero or negative dimensions, or is missing. */
    INVALID_TARGET_BOUNDS,
    /** Anchor point coordinates are invalid. */
    INVALID_ANCHOR,
    /** Target center coincides with the anchor within the zero-distance threshold. */
    ZERO_DISTANCE,
    /** One or more coordinates are NaN or Infinite. */
    NON_FINITE_COORDINATES
}

/**
 * Normalized 2D direction vector with angle representations.
 */
data class DirectionVector(
    val dx: Float,
    val dy: Float
) {
    val length: Float = sqrt(dx * dx + dy * dy)
    val isFinite: Boolean = dx.isFinite() && dy.isFinite() && length.isFinite()

    val normalizedX: Float = if (length > 0.0001f) dx / length else 0f
    val normalizedY: Float = if (length > 0.0001f) dy / length else 0f

    val angleRadians: Float = if (length > 0.0001f) atan2(dy, dx) else 0f
    val angleDegrees: Float = (Math.toDegrees(angleRadians.toDouble())).toFloat()
}

/**
 * Eye gaze offset normalized between [-1.0f .. 1.0f] for X and Y.
 */
data class GazeOffset(
    val offsetX: Float,
    val offsetY: Float
) {
    companion object {
        val NEUTRAL = GazeOffset(0f, 0f)
    }
}

/**
 * Directional pointing arm/hand gesture geometry.
 */
data class PointingGesture(
    val origin: ScreenPoint,
    val directionAngleRadians: Float,
    val directionAngleDegrees: Float,
    val normalizedDirection: DirectionVector,
    val distance: Float,
    val isPointingRight: Boolean,
    val isPointingDown: Boolean
)

/**
 * Complete immutable guidance geometry result.
 */
data class TargetGuidanceGeometry(
    val validity: GuidanceValidity,
    val targetCenter: ScreenPoint?,
    val anchorPoint: ScreenPoint,
    val displacement: DirectionVector?,
    val distance: Float,
    val gazeOffset: GazeOffset,
    val pointingGesture: PointingGesture?,
    val isGuidanceUsable: Boolean
) {
    companion object {
        fun neutral(
            anchor: ScreenPoint = ScreenPoint(0f, 0f),
            validity: GuidanceValidity = GuidanceValidity.INVALID_TARGET_BOUNDS
        ): TargetGuidanceGeometry {
            return TargetGuidanceGeometry(
                validity = validity,
                targetCenter = null,
                anchorPoint = anchor,
                displacement = null,
                distance = 0f,
                gazeOffset = GazeOffset.NEUTRAL,
                pointingGesture = null,
                isGuidanceUsable = false
            )
        }
    }
}

/**
 * Target-aware guidance parameters passed to [AvatarView] to coordinate
 * directional gaze and pointing gestures with resolved UI targets.
 */
data class AvatarGuidanceTarget(
    /** Horizontal gaze shift normalized in [-1.0f .. 1.0f]. Positive looks right, negative looks left. */
    val gazeOffsetX: Float = 0f,
    /** Vertical gaze shift normalized in [-1.0f .. 1.0f]. Positive looks down, negative looks up. */
    val gazeOffsetY: Float = 0f,
    /** Direction angle in radians pointing from the robot toward the target. */
    val pointingAngleRadians: Float? = null,
    /** Whether directional pointing gesture is active. */
    val isPointingActive: Boolean = false,
    /** True if target is to the right of the avatar, false if to the left. */
    val isPointingRight: Boolean = true,
    /** Approximate Euclidean distance in pixels to the target. */
    val distance: Float = 0f
)

/**
 * Configuration parameters for guidance calculation.
 */
data class GuidanceGeometryConfig(
    val minTargetDistancePx: Float = 1.0f,
    val maxGazeDisplacementPx: Float = 450.0f
)

/**
 * Mathematical guidance calculator establishing the coordinate transformation boundary.
 */
object TargetGuidanceCalculator {

    fun calculateGuidance(
        anchor: ScreenPoint,
        targetBounds: ScreenRect?,
        config: GuidanceGeometryConfig = GuidanceGeometryConfig()
    ): TargetGuidanceGeometry {
        if (!anchor.isFinite) {
            return TargetGuidanceGeometry.neutral(anchor, GuidanceValidity.NON_FINITE_COORDINATES)
        }
        if (targetBounds == null || !targetBounds.isValid) {
            val validity = if (targetBounds != null && !targetBounds.isFinite) {
                GuidanceValidity.NON_FINITE_COORDINATES
            } else {
                GuidanceValidity.INVALID_TARGET_BOUNDS
            }
            return TargetGuidanceGeometry.neutral(anchor, validity)
        }

        val targetCenter = targetBounds.centerPoint
        if (!targetCenter.isFinite) {
            return TargetGuidanceGeometry.neutral(anchor, GuidanceValidity.NON_FINITE_COORDINATES)
        }

        val dx = targetCenter.x - anchor.x
        val dy = targetCenter.y - anchor.y

        val displacement = DirectionVector(dx, dy)
        val distance = displacement.length

        if (distance < config.minTargetDistancePx) {
            return TargetGuidanceGeometry(
                validity = GuidanceValidity.ZERO_DISTANCE,
                targetCenter = targetCenter,
                anchorPoint = anchor,
                displacement = displacement,
                distance = distance,
                gazeOffset = GazeOffset.NEUTRAL,
                pointingGesture = null,
                isGuidanceUsable = false
            )
        }

        // Clamped gaze offset in [-1.0f .. 1.0f]
        val gazeMagnitude = (distance / config.maxGazeDisplacementPx).coerceIn(0f, 1.0f)
        val gazeX = (displacement.normalizedX * gazeMagnitude).coerceIn(-1.0f, 1.0f)
        val gazeY = (displacement.normalizedY * gazeMagnitude).coerceIn(-1.0f, 1.0f)
        val gazeOffset = GazeOffset(gazeX, gazeY)

        val isRight = displacement.normalizedX >= 0f
        val isDown = displacement.normalizedY >= 0f

        val gesture = PointingGesture(
            origin = anchor,
            directionAngleRadians = displacement.angleRadians,
            directionAngleDegrees = displacement.angleDegrees,
            normalizedDirection = displacement,
            distance = distance,
            isPointingRight = isRight,
            isPointingDown = isDown
        )

        return TargetGuidanceGeometry(
            validity = GuidanceValidity.VALID,
            targetCenter = targetCenter,
            anchorPoint = anchor,
            displacement = displacement,
            distance = distance,
            gazeOffset = gazeOffset,
            pointingGesture = gesture,
            isGuidanceUsable = true
        )
    }
}
