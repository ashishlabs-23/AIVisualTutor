package ui.avatar

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural Compose Desktop rendering of the AI Visual Tutor robot avatar
 * matching the official "AI Visual Tutor — Avatar States & PRD" design specification.
 *
 * Visual Characteristics:
 * - Rounded, friendly pearl-white floating helmet chassis (#FFFFFF / #F8FAFC).
 * - Glossy obsidian faceplate (#0E1117) with subtle glass specular reflection.
 * - Earcups with glowing Blender-orange concentric rings (#F5792A / #FFA757).
 * - Floating white body collar with warm under-glow.
 * - Floating expressive robot hands/paws (Idle, Thinking, Explaining, Pointing, Success, Error, Searching).
 * - Large, expressive amber/orange illuminated LED eyes with dynamic gaze tracking.
 * - Floating state badges (thinking dots '...', success cheer, error exclamation '!', lightbulb, Zzz).
 * - 100% resolution-independent Skia vector graphics.
 */
@Composable
fun AvatarView(
    state: AvatarPresentationState,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    isReducedMotion: Boolean = false,
    guidanceTarget: AvatarGuidanceTarget? = null,
    pointingAngleRadians: Float? = null
) {
    val infiniteTransition = rememberInfiniteTransition()

    // 1. Subtle floating breathing pulse (disabled in reduced motion or offline)
    val floatPulse by if (!isReducedMotion && state != AvatarPresentationState.OFFLINE) {
        infiniteTransition.animateFloat(
            initialValue = -2.5f,
            targetValue = 2.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val glowPulse by if (!isReducedMotion && state != AvatarPresentationState.OFFLINE) {
        infiniteTransition.animateFloat(
            initialValue = 0.70f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    } else {
        remember { mutableStateOf(0.85f) }
    }

    // 2. Periodic subtle eye blink (~4 second cycle)
    val blinkScale by if (!isReducedMotion && (state == AvatarPresentationState.IDLE || state == AvatarPresentationState.GUIDING || state == AvatarPresentationState.WAITING_FOR_USER)) {
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 0.1f,
            animationSpec = infiniteRepeatable(
                animation = androidx.compose.animation.core.keyframes {
                    durationMillis = 4000
                    1.0f at 0
                    1.0f at 3800
                    0.15f at 3900
                    1.0f at 4000
                },
                repeatMode = RepeatMode.Restart
            )
        )
    } else {
        remember { mutableStateOf(1.0f) }
    }

    // 3. Floating thinking dots / searching pulse animation
    val auxiliaryPulse by if (!isReducedMotion && (state == AvatarPresentationState.THINKING || state == AvatarPresentationState.VERIFYING)) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    } else {
        remember { mutableStateOf(0.5f) }
    }

    // 4. Target-Aware Dynamic Gaze Transitions
    val isGazePermitted = (state == AvatarPresentationState.POINTING ||
        state == AvatarPresentationState.GUIDING ||
        state == AvatarPresentationState.WAITING_FOR_USER) &&
        state != AvatarPresentationState.COMPACT

    val targetGazeX = if (isGazePermitted && guidanceTarget != null) {
        guidanceTarget.gazeOffsetX.coerceIn(-1.0f, 1.0f)
    } else 0f

    val targetGazeY = if (isGazePermitted && guidanceTarget != null) {
        guidanceTarget.gazeOffsetY.coerceIn(-1.0f, 1.0f)
    } else 0f

    val animatedGazeNormX by if (!isReducedMotion) {
        animateFloatAsState(
            targetValue = targetGazeX,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f)
        )
    } else {
        remember(targetGazeX) { mutableStateOf(targetGazeX) }
    }

    val animatedGazeNormY by if (!isReducedMotion) {
        animateFloatAsState(
            targetValue = targetGazeY,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f)
        )
    } else {
        remember(targetGazeY) { mutableStateOf(targetGazeY) }
    }

    // 5. Directional Pointing Gesture Articulation
    val isPointingPermitted = (state == AvatarPresentationState.POINTING ||
        (guidanceTarget?.isPointingActive == true) ||
        pointingAngleRadians != null) &&
        state != AvatarPresentationState.OFFLINE &&
        state != AvatarPresentationState.THINKING &&
        state != AvatarPresentationState.VERIFYING &&
        state != AvatarPresentationState.SUCCESS &&
        state != AvatarPresentationState.RECOVERY &&
        state != AvatarPresentationState.COMPACT

    val targetArmExtension = if (isPointingPermitted) 1.0f else 0f
    val armExtension by if (!isReducedMotion) {
        animateFloatAsState(
            targetValue = targetArmExtension,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
        )
    } else {
        remember(targetArmExtension) { mutableStateOf(targetArmExtension) }
    }

    val activePointingAngle = guidanceTarget?.pointingAngleRadians
        ?: pointingAngleRadians
        ?: (-0.35f) // Default upward-right angle

    val isPointingRight = guidanceTarget?.isPointingRight ?: (activePointingAngle in -1.57f..1.57f)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val canvasW = this.size.width
            val canvasH = this.size.height
            val centerX = canvasW / 2f
            val centerY = (canvasH / 2f) + floatPulse

            // Primary Blender Orange Palette
            val primaryOrange = Color(0xFFF5792A)
            val accentOrangeGlow = Color(0xFFFFA757)
            val darkGraphiteChassis = Color(0xFF334155)

            // State-specific aura color
            val auraColor = when (state) {
                AvatarPresentationState.SUCCESS -> Color(0xFF22C55E) // Vibrant green
                AvatarPresentationState.THINKING, AvatarPresentationState.VERIFYING -> Color(0xFF38BDF8) // Focused cyan
                AvatarPresentationState.RECOVERY -> Color(0xFFEF4444) // Error/recovery red
                AvatarPresentationState.OFFLINE -> Color(0xFF64748B) // Dim slate
                else -> primaryOrange // Signature warm Blender orange
            }

            // 1. Soft Warm Ambient Glow
            if (state != AvatarPresentationState.COMPACT) {
                val glowRadius = (canvasW * 0.44f) * glowPulse
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            auraColor.copy(alpha = 0.28f * glowPulse),
                            auraColor.copy(alpha = 0.08f * glowPulse),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY),
                        radius = glowRadius
                    ),
                    radius = glowRadius,
                    center = Offset(centerX, centerY)
                )
            }

            // 2. Floating Body Collar (Underneath the Head)
            if (state != AvatarPresentationState.COMPACT) {
                val collarW = canvasW * 0.36f
                val collarH = canvasH * 0.14f
                val collarTop = centerY + canvasH * 0.28f
                val collarLeft = centerX - collarW / 2f

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFFFFFFFF), Color(0xFFCBD5E1), Color(0xFF94A3B8)),
                        startY = collarTop,
                        endY = collarTop + collarH
                    ),
                    topLeft = Offset(collarLeft, collarTop),
                    size = Size(collarW, collarH),
                    cornerRadius = CornerRadius(collarW * 0.4f, collarH * 0.5f)
                )
                // Collar orange under-glow accent
                drawRoundRect(
                    color = primaryOrange.copy(alpha = 0.6f * glowPulse),
                    topLeft = Offset(collarLeft + 2f, collarTop + collarH - 3f),
                    size = Size(collarW - 4f, 3f),
                    cornerRadius = CornerRadius(1.5f, 1.5f)
                )
            }

            // 3. Spherical Pearl-White Head Chassis
            val headW = if (state == AvatarPresentationState.COMPACT) canvasW * 0.86f else canvasW * 0.68f
            val headH = if (state == AvatarPresentationState.COMPACT) canvasH * 0.80f else canvasH * 0.64f
            val headTop = centerY - headH / 2f
            val headLeft = centerX - headW / 2f

            val headBrush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFF8FAFC),
                    Color(0xFFE2E8F0),
                    Color(0xFFCBD5E1)
                ),
                center = Offset(centerX - headW * 0.15f, headTop + headH * 0.25f),
                radius = headW * 0.70f
            )

            drawRoundRect(
                brush = headBrush,
                topLeft = Offset(headLeft, headTop),
                size = Size(headW, headH),
                cornerRadius = CornerRadius(headW * 0.46f, headH * 0.48f)
            )

            // Outer chassis highlight rim
            drawRoundRect(
                color = Color.White.copy(alpha = 0.75f),
                topLeft = Offset(headLeft, headTop),
                size = Size(headW, headH),
                cornerRadius = CornerRadius(headW * 0.46f, headH * 0.48f),
                style = Stroke(width = 1.6f)
            )

            // 4. Cylindrical Earcups with Glowing Orange Accent Rings
            val earW = headW * 0.10f
            val earH = headH * 0.28f
            val earY = centerY - earH / 2f

            // Left Earcup
            val leftEarX = headLeft - earW * 0.55f
            drawRoundRect(
                color = darkGraphiteChassis,
                topLeft = Offset(leftEarX, earY),
                size = Size(earW, earH),
                cornerRadius = CornerRadius(earW * 0.4f, earW * 0.4f)
            )
            // Left glowing orange accent ring
            drawRoundRect(
                color = primaryOrange,
                topLeft = Offset(leftEarX + 1.5f, earY + earH * 0.2f),
                size = Size(2.5f, earH * 0.6f),
                cornerRadius = CornerRadius(1.2f, 1.2f)
            )

            // Right Earcup
            val rightEarX = headLeft + headW - earW * 0.45f
            drawRoundRect(
                color = darkGraphiteChassis,
                topLeft = Offset(rightEarX, earY),
                size = Size(earW, earH),
                cornerRadius = CornerRadius(earW * 0.4f, earW * 0.4f)
            )
            // Right glowing orange accent ring
            drawRoundRect(
                color = primaryOrange,
                topLeft = Offset(rightEarX + earW - 4f, earY + earH * 0.2f),
                size = Size(2.5f, earH * 0.6f),
                cornerRadius = CornerRadius(1.2f, 1.2f)
            )

            // 5. Glossy Obsidian Visor (Faceplate)
            val visorW = headW * 0.74f
            val visorH = headH * 0.60f
            val visorLeft = centerX - visorW / 2f
            val visorTop = centerY - visorH * 0.48f

            val visorBrush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF161B26),
                    Color(0xFF0E1117),
                    Color(0xFF07090D)
                ),
                startY = visorTop,
                endY = visorTop + visorH
            )

            drawRoundRect(
                brush = visorBrush,
                topLeft = Offset(visorLeft, visorTop),
                size = Size(visorW, visorH),
                cornerRadius = CornerRadius(visorW * 0.38f, visorH * 0.42f)
            )

            // Visor Curved Glass Specular Highlight Arc
            val glassPath = Path().apply {
                moveTo(visorLeft + visorW * 0.15f, visorTop + 4f)
                cubicTo(
                    visorLeft + visorW * 0.35f, visorTop + 3f,
                    visorLeft + visorW * 0.65f, visorTop + 3f,
                    visorLeft + visorW * 0.85f, visorTop + 4f
                )
                lineTo(visorLeft + visorW * 0.75f, visorTop + visorH * 0.38f)
                cubicTo(
                    visorLeft + visorW * 0.60f, visorTop + visorH * 0.28f,
                    visorLeft + visorW * 0.40f, visorTop + visorH * 0.28f,
                    visorLeft + visorW * 0.25f, visorTop + visorH * 0.38f
                )
                close()
            }
            drawPath(
                path = glassPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.24f),
                        Color.White.copy(alpha = 0.04f)
                    ),
                    startY = visorTop,
                    endY = visorTop + visorH * 0.4f
                )
            )

            // 6. Expressive Illuminated Amber Eyes
            drawReferenceEyes(
                state = state,
                centerX = centerX,
                centerY = centerY + 1f,
                visorW = visorW,
                visorH = visorH,
                blinkScale = blinkScale,
                primaryOrange = primaryOrange,
                accentOrange = accentOrangeGlow,
                gazeNormX = animatedGazeNormX,
                gazeNormY = animatedGazeNormY
            )

            // 7. Floating Hands / Paws & Gestures (Exact Reference Match)
            if (state != AvatarPresentationState.COMPACT) {
                drawFloatingHands(
                    state = state,
                    centerX = centerX,
                    centerY = centerY,
                    headW = headW,
                    headH = headH,
                    armExtension = armExtension,
                    pointingAngleRad = activePointingAngle,
                    isPointingRight = isPointingRight,
                    primaryOrange = primaryOrange,
                    auxiliaryPulse = auxiliaryPulse
                )
            }

            // 8. Overhead Floating State Badges (..., !, 💡, ?, Zzz)
            if (state != AvatarPresentationState.COMPACT) {
                drawStateBadges(
                    state = state,
                    centerX = centerX,
                    headTop = headTop,
                    primaryOrange = primaryOrange,
                    auxiliaryPulse = auxiliaryPulse
                )
            }
        }
    }
}

/**
 * Renders large, expressive amber/orange eyes matching the reference states.
 */
private fun DrawScope.drawReferenceEyes(
    state: AvatarPresentationState,
    centerX: Float,
    centerY: Float,
    visorW: Float,
    visorH: Float,
    blinkScale: Float,
    primaryOrange: Color,
    accentOrange: Color,
    gazeNormX: Float,
    gazeNormY: Float
) {
    val eyeSpacing = visorW * 0.25f
    val leftEyeX = centerX - eyeSpacing
    val rightEyeX = centerX + eyeSpacing
    val eyeY = centerY

    val eyeColor = when (state) {
        AvatarPresentationState.OFFLINE -> Color(0xFF64748B) // Dim slate
        AvatarPresentationState.SUCCESS -> Color(0xFF22C55E) // Cheerful green
        AvatarPresentationState.RECOVERY -> Color(0xFFEF4444) // Error/retry red
        AvatarPresentationState.THINKING, AvatarPresentationState.VERIFYING -> Color(0xFF38BDF8) // Cyan
        else -> primaryOrange // Signature warm amber/orange
    }

    // Dynamic gaze pixel displacement
    val maxShiftX = visorW * 0.09f
    val maxShiftY = visorH * 0.12f
    val shiftX = gazeNormX * maxShiftX
    val shiftY = gazeNormY * maxShiftY

    when (state) {
        AvatarPresentationState.SUCCESS -> {
            // Happy upward curved eyes (^ ^)
            val arcW = visorW * 0.19f
            val arcH = visorH * 0.24f
            drawArc(
                color = eyeColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(leftEyeX - arcW / 2f, eyeY - arcH / 2f),
                size = Size(arcW, arcH),
                style = Stroke(width = 4.2f, cap = StrokeCap.Round)
            )
            drawArc(
                color = eyeColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(rightEyeX - arcW / 2f, eyeY - arcH / 2f),
                size = Size(arcW, arcH),
                style = Stroke(width = 4.2f, cap = StrokeCap.Round)
            )
        }

        AvatarPresentationState.THINKING -> {
            // Contemplative eyes looking up-right
            val eyeW = visorW * 0.16f
            val eyeH = visorH * 0.28f
            val lookX = 3.5f
            val lookY = -3.0f

            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(leftEyeX - eyeW / 2f + lookX, eyeY - eyeH / 2f + lookY),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(rightEyeX - eyeW / 2f + lookX, eyeY - eyeH / 2f + lookY),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )
            // Glints
            drawCircle(Color.White.copy(alpha = 0.9f), radius = 2f, center = Offset(leftEyeX + lookX + 1.5f, eyeY + lookY - 2f))
            drawCircle(Color.White.copy(alpha = 0.9f), radius = 2f, center = Offset(rightEyeX + lookX + 1.5f, eyeY + lookY - 2f))
        }

        AvatarPresentationState.OFFLINE -> {
            // Sleeping / No Blender: peaceful curved resting lines (- -)
            val lineW = visorW * 0.18f
            drawArc(
                color = eyeColor,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(leftEyeX - lineW / 2f, eyeY - 4f),
                size = Size(lineW, 10f),
                style = Stroke(width = 3.2f, cap = StrokeCap.Round)
            )
            drawArc(
                color = eyeColor,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(rightEyeX - lineW / 2f, eyeY - 4f),
                size = Size(lineW, 10f),
                style = Stroke(width = 3.2f, cap = StrokeCap.Round)
            )
        }

        AvatarPresentationState.RECOVERY -> {
            // Worried / sad drooping eyes (• ︵ •)
            val eyeW = visorW * 0.15f
            val eyeH = visorH * 0.24f
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(leftEyeX - eyeW / 2f, eyeY - eyeH / 2f),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.4f, eyeW * 0.4f)
            )
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(rightEyeX - eyeW / 2f, eyeY - eyeH / 2f),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.4f, eyeW * 0.4f)
            )
            // Concerned brow slant
            drawLine(
                color = eyeColor.copy(alpha = 0.8f),
                start = Offset(leftEyeX - eyeW * 0.6f, eyeY - eyeH * 0.85f),
                end = Offset(leftEyeX + eyeW * 0.6f, eyeY - eyeH * 0.60f),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = eyeColor.copy(alpha = 0.8f),
                start = Offset(rightEyeX - eyeW * 0.6f, eyeY - eyeH * 0.60f),
                end = Offset(rightEyeX + eyeW * 0.6f, eyeY - eyeH * 0.85f),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round
            )
        }

        else -> {
            // Standard Friendly Amber Capsule Eyes with dynamic gaze tracking
            val eyeW = visorW * 0.16f
            val eyeH = visorH * 0.32f * blinkScale
            val finalShiftX = if (shiftX != 0f) shiftX else 0f
            val finalShiftY = if (shiftY != 0f) shiftY else 0f

            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(leftEyeX - eyeW / 2f + finalShiftX, eyeY - eyeH / 2f + finalShiftY),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(rightEyeX - eyeW / 2f + finalShiftX, eyeY - eyeH / 2f + finalShiftY),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )

            // Warm inner smile glint
            if (blinkScale > 0.4f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.88f),
                    radius = 2.4f,
                    center = Offset(leftEyeX + finalShiftX + 1.8f, eyeY + finalShiftY - 2.8f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.88f),
                    radius = 2.4f,
                    center = Offset(rightEyeX + finalShiftX + 1.8f, eyeY + finalShiftY - 2.8f)
                )
            }
        }
    }
}

/**
 * Renders cute floating hands/paws and state gestures (Exact reference match).
 */
private fun DrawScope.drawFloatingHands(
    state: AvatarPresentationState,
    centerX: Float,
    centerY: Float,
    headW: Float,
    headH: Float,
    armExtension: Float,
    pointingAngleRad: Float,
    isPointingRight: Boolean,
    primaryOrange: Color,
    auxiliaryPulse: Float
) {
    val handRadius = headW * 0.075f
    val handBrush = Brush.radialGradient(
        colors = listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F9), Color(0xFFCBD5E1)),
        radius = handRadius * 1.2f
    )

    when (state) {
        AvatarPresentationState.POINTING -> {
            // Directional Pointing Gesture: Hand extends pointing towards Blender target
            val shoulderX = if (isPointingRight) centerX + headW * 0.38f else centerX - headW * 0.38f
            val shoulderY = centerY + headH * 0.18f

            val pointerLength = headW * 0.44f * armExtension
            val dirX = cos(pointingAngleRad)
            val dirY = sin(pointingAngleRad)

            val handX = shoulderX + dirX * pointerLength
            val handY = shoulderY + dirY * pointerLength

            // Glowing Guidance Laser Beam from Hand towards Target in Blender
            val laserLength = 70f * armExtension
            val laserTipX = handX + dirX * laserLength
            val laserTipY = handY + dirY * laserLength

            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(primaryOrange, primaryOrange.copy(alpha = 0.2f)),
                    start = Offset(handX, handY),
                    end = Offset(laserTipX, laserTipY)
                ),
                start = Offset(handX, handY),
                end = Offset(laserTipX, laserTipY),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round
            )

            // Extended Pointing Hand
            drawCircle(brush = handBrush, radius = handRadius, center = Offset(handX, handY))
            drawCircle(color = Color.White.copy(alpha = 0.6f), radius = handRadius, center = Offset(handX, handY), style = Stroke(1f))

            // Orange illuminated pointer fingertip emitter
            val emitterX = handX + dirX * (handRadius * 0.9f)
            val emitterY = handY + dirY * (handRadius * 0.9f)
            drawCircle(color = primaryOrange, radius = 4f, center = Offset(emitterX, emitterY))
            drawCircle(color = Color(0xFFFFA757), radius = 2f, center = Offset(emitterX, emitterY))

            // Other hand floating relaxed
            val otherHandX = if (isPointingRight) centerX - headW * 0.45f else centerX + headW * 0.45f
            val otherHandY = centerY + headH * 0.30f
            drawCircle(brush = handBrush, radius = handRadius, center = Offset(otherHandX, otherHandY))
        }

        AvatarPresentationState.THINKING -> {
            // Hand brought up to chin in thoughtful pose
            val leftHandX = centerX - headW * 0.45f
            val leftHandY = centerY + headH * 0.32f
            drawCircle(brush = handBrush, radius = handRadius, center = Offset(leftHandX, leftHandY))

            // Right hand touching chin
            val rightHandX = centerX + headW * 0.24f
            val rightHandY = centerY + headH * 0.26f
            drawCircle(brush = handBrush, radius = handRadius * 1.05f, center = Offset(rightHandX, rightHandY))
            drawCircle(color = Color.White.copy(alpha = 0.6f), radius = handRadius * 1.05f, center = Offset(rightHandX, rightHandY), style = Stroke(1f))
        }

        AvatarPresentationState.SUCCESS -> {
            // Celebratory cheer: both hands raised high \ (^ ^) /
            val leftHandX = centerX - headW * 0.44f
            val leftHandY = centerY - headH * 0.05f
            val rightHandX = centerX + headW * 0.44f
            val rightHandY = centerY - headH * 0.05f

            drawCircle(brush = handBrush, radius = handRadius * 1.1f, center = Offset(leftHandX, leftHandY))
            drawCircle(brush = handBrush, radius = handRadius * 1.1f, center = Offset(rightHandX, rightHandY))
        }

        AvatarPresentationState.EXPLAINING -> {
            // One hand raised open in friendly explanation gesture
            val leftHandX = centerX - headW * 0.45f
            val leftHandY = centerY + headH * 0.30f
            drawCircle(brush = handBrush, radius = handRadius, center = Offset(leftHandX, leftHandY))

            val rightHandX = centerX + headW * 0.42f
            val rightHandY = centerY + headH * 0.12f
            drawCircle(brush = handBrush, radius = handRadius * 1.05f, center = Offset(rightHandX, rightHandY))
        }

        else -> {
            // Standard Idle: Two cute floating spheres hovering gently at the sides
            val leftHandX = centerX - headW * 0.46f
            val leftHandY = centerY + headH * 0.30f
            val rightHandX = centerX + headW * 0.46f
            val rightHandY = centerY + headH * 0.30f

            drawCircle(brush = handBrush, radius = handRadius, center = Offset(leftHandX, leftHandY))
            drawCircle(color = Color.White.copy(alpha = 0.5f), radius = handRadius, center = Offset(leftHandX, leftHandY), style = Stroke(1f))

            drawCircle(brush = handBrush, radius = handRadius, center = Offset(rightHandX, rightHandY))
            drawCircle(color = Color.White.copy(alpha = 0.5f), radius = handRadius, center = Offset(rightHandX, rightHandY), style = Stroke(1f))
        }
    }
}

/**
 * Renders overhead floating state badges (..., !, 💡, ?, Zzz).
 */
private fun DrawScope.drawStateBadges(
    state: AvatarPresentationState,
    centerX: Float,
    headTop: Float,
    primaryOrange: Color,
    auxiliaryPulse: Float
) {
    val badgeY = headTop - 12f

    when (state) {
        AvatarPresentationState.THINKING -> {
            // Three floating glowing amber dots '...'
            val dotSpacing = 7f
            for (i in -1..1) {
                val dotX = centerX + i * dotSpacing
                val alpha = (0.4f + ((auxiliaryPulse + i * 0.3f) % 1f) * 0.6f).coerceIn(0.2f, 1f)
                drawCircle(color = primaryOrange.copy(alpha = alpha), radius = 2.5f, center = Offset(dotX, badgeY))
            }
        }

        AvatarPresentationState.RECOVERY -> {
            // Floating Red Exclamation Badge (!)
            drawCircle(color = Color(0xFFEF4444), radius = 8f, center = Offset(centerX + 26f, badgeY + 2f))
            drawCircle(color = Color.White, radius = 8f, center = Offset(centerX + 26f, badgeY + 2f), style = Stroke(1.2f))
            // Exclamation mark
            drawLine(
                color = Color.White,
                start = Offset(centerX + 26f, badgeY - 2f),
                end = Offset(centerX + 26f, badgeY + 2.5f),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
            drawCircle(color = Color.White, radius = 1f, center = Offset(centerX + 26f, badgeY + 5.5f))
        }

        AvatarPresentationState.OFFLINE -> {
            // Floating 'Zzz' indicating sleeping / waiting for Blender
            val zColor = Color(0xFF94A3B8).copy(alpha = 0.85f)
            drawCircle(color = zColor, radius = 1.5f, center = Offset(centerX + 24f, badgeY + 4f))
            drawCircle(color = zColor, radius = 2.2f, center = Offset(centerX + 29f, badgeY))
            drawCircle(color = zColor, radius = 3.0f, center = Offset(centerX + 35f, badgeY - 5f))
        }

        else -> {}
    }
}
