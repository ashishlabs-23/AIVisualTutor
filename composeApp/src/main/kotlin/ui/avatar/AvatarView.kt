package ui.avatar

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural Compose Desktop rendering of the AI Visual Tutor robot avatar.
 *
 * Visual Characteristics:
 * - Rounded, friendly silhouette (capsule chassis).
 * - Glossy near-black faceplate.
 * - Warm amber/orange illuminated LED eyes.
 * - White, pearl, and graphite body materials.
 * - Subtle state-reactive aura glow.
 * - No leaf or plant motif.
 * - 100% resolution-independent Skia vector graphics.
 */
@Composable
fun AvatarView(
    state: AvatarPresentationState,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    isReducedMotion: Boolean = false,
    pointingAngleRadians: Float? = null
) {
    val infiniteTransition = rememberInfiniteTransition()

    // 1. Subtle breathing pulse (disabled in reduced motion or offline)
    val pulse by if (!isReducedMotion && state != AvatarPresentationState.OFFLINE) {
        infiniteTransition.animateFloat(
            initialValue = 0.55f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.85f) }
    }

    // 2. Periodic subtle eye blink (~4 second cycle)
    val blinkScale by if (!isReducedMotion && (state == AvatarPresentationState.IDLE || state == AvatarPresentationState.GUIDING || state == AvatarPresentationState.WAITING_FOR_USER)) {
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 0.1f,
            animationSpec = infiniteRepeatable(
                animation = androidx.compose.animation.core.keyframes {
                    durationMillis = 4200
                    1.0f at 0
                    1.0f at 3950
                    0.15f at 4075
                    1.0f at 4200
                },
                repeatMode = RepeatMode.Restart
            )
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(1.0f) }
    }

    // 3. Orbital LED animation for Thinking / Verifying
    val orbitAngle by if (!isReducedMotion && (state == AvatarPresentationState.THINKING || state == AvatarPresentationState.VERIFYING)) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val canvasW = this.size.width
            val canvasH = this.size.height
            val centerX = canvasW / 2f
            val centerY = canvasH / 2f

            // Determine aura glow color by state
            val auraColor = when (state) {
                AvatarPresentationState.SUCCESS -> Color(0xFF22C55E)
                AvatarPresentationState.THINKING, AvatarPresentationState.VERIFYING -> Color(0xFF38BDF8)
                AvatarPresentationState.RECOVERY -> Color(0xFFF97316)
                AvatarPresentationState.OFFLINE -> Color(0xFF64748B)
                else -> Color(0xFFF59E0B) // Warm amber gold
            }

            // 1. Outer ambient aura glow
            if (state != AvatarPresentationState.COMPACT) {
                val glowRadius = (canvasW * 0.46f) + (pulse * 3f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            auraColor.copy(alpha = 0.32f * pulse),
                            auraColor.copy(alpha = 0.08f * pulse),
                            Color.Transparent
                        ),
                        center = Offset(centerX, centerY),
                        radius = glowRadius
                    ),
                    radius = glowRadius,
                    center = Offset(centerX, centerY)
                )
            }

            // 2. White & Pearl outer chassis (capsule helmet shell)
            val headW = if (state == AvatarPresentationState.COMPACT) canvasW * 0.88f else canvasW * 0.74f
            val headH = if (state == AvatarPresentationState.COMPACT) canvasH * 0.82f else canvasH * 0.68f
            val headTop = centerY - headH / 2f
            val headLeft = centerX - headW / 2f

            val chassisBrush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFFFFFFF),
                    Color(0xFFF8FAFC),
                    Color(0xFFE2E8F0),
                    Color(0xFFCBD5E1)
                ),
                startY = headTop,
                endY = headTop + headH
            )

            drawRoundRect(
                brush = chassisBrush,
                topLeft = Offset(headLeft, headTop),
                size = Size(headW, headH),
                cornerRadius = CornerRadius(headW * 0.38f, headH * 0.44f)
            )

            // Chassis border highlight rim
            drawRoundRect(
                color = Color(0xFF94A3B8).copy(alpha = 0.45f),
                topLeft = Offset(headLeft, headTop),
                size = Size(headW, headH),
                cornerRadius = CornerRadius(headW * 0.38f, headH * 0.44f),
                style = Stroke(width = 1.5f)
            )

            // 3. Graphite ear pods / joints
            val earW = canvasW * 0.06f
            val earH = headH * 0.35f
            val earY = centerY - earH / 2f
            drawRoundRect(
                color = Color(0xFF334155),
                topLeft = Offset(headLeft - earW * 0.55f, earY),
                size = Size(earW, earH),
                cornerRadius = CornerRadius(earW * 0.4f, earW * 0.4f)
            )
            drawRoundRect(
                color = Color(0xFF334155),
                topLeft = Offset(headLeft + headW - earW * 0.45f, earY),
                size = Size(earW, earH),
                cornerRadius = CornerRadius(earW * 0.4f, earW * 0.4f)
            )

            // 4. Glossy near-black visor faceplate
            val visorW = headW * 0.82f
            val visorH = headH * 0.62f
            val visorLeft = centerX - visorW / 2f
            val visorTop = centerY - visorH / 2f + 2f

            val visorBrush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF0F172A),
                    Color(0xFF020617),
                    Color(0xFF0B0F17)
                ),
                startY = visorTop,
                endY = visorTop + visorH
            )

            drawRoundRect(
                brush = visorBrush,
                topLeft = Offset(visorLeft, visorTop),
                size = Size(visorW, visorH),
                cornerRadius = CornerRadius(visorW * 0.32f, visorH * 0.38f)
            )

            // Visor subtle glass specular reflection
            val reflectionPath = Path().apply {
                moveTo(visorLeft + 8f, visorTop + 4f)
                lineTo(visorLeft + visorW - 8f, visorTop + 4f)
                lineTo(visorLeft + visorW * 0.65f, visorTop + visorH * 0.42f)
                lineTo(visorLeft + visorW * 0.25f, visorTop + visorH * 0.42f)
                close()
            }
            drawPath(
                path = reflectionPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    startY = visorTop,
                    endY = visorTop + visorH * 0.5f
                )
            )

            // 5. State-dependent eyes & face expression
            drawEyesForState(
                state = state,
                centerX = centerX,
                centerY = centerY + 2f,
                visorW = visorW,
                visorH = visorH,
                pulse = pulse,
                blinkScale = blinkScale,
                orbitAngle = orbitAngle
            )

            // 6. Pointing arm gesture (when POINTING or explicit angle)
            if (state == AvatarPresentationState.POINTING || pointingAngleRadians != null) {
                drawPointingGesture(
                    centerX = centerX,
                    centerY = centerY,
                    radius = headW * 0.46f,
                    angleRad = pointingAngleRadians ?: (-0.35f) // Default pointing up-right toward workspace
                )
            }
        }
    }
}

private fun DrawScope.drawEyesForState(
    state: AvatarPresentationState,
    centerX: Float,
    centerY: Float,
    visorW: Float,
    visorH: Float,
    pulse: Float,
    blinkScale: Float,
    orbitAngle: Float
) {
    val eyeSpacing = visorW * 0.24f
    val leftEyeX = centerX - eyeSpacing
    val rightEyeX = centerX + eyeSpacing
    val eyeY = centerY

    val eyeColor = when (state) {
        AvatarPresentationState.OFFLINE -> Color(0xFF475569) // Dim slate
        AvatarPresentationState.SUCCESS -> Color(0xFF4ADE80) // Cheerful green
        AvatarPresentationState.RECOVERY -> Color(0xFFFB923C) // Warm coral
        AvatarPresentationState.THINKING, AvatarPresentationState.VERIFYING -> Color(0xFF38BDF8) // Focused cyan
        else -> Color(0xFFF59E0B) // Amber gold
    }

    when (state) {
        AvatarPresentationState.SUCCESS -> {
            // Happy upward arcs (^ ^)
            val arcW = visorW * 0.18f
            val arcH = visorH * 0.22f
            drawArc(
                color = eyeColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(leftEyeX - arcW / 2f, eyeY - arcH / 2f),
                size = Size(arcW, arcH),
                style = Stroke(width = 3.5f)
            )
            drawArc(
                color = eyeColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(rightEyeX - arcW / 2f, eyeY - arcH / 2f),
                size = Size(arcW, arcH),
                style = Stroke(width = 3.5f)
            )
        }

        AvatarPresentationState.THINKING, AvatarPresentationState.VERIFYING -> {
            // Orbiting amber/cyan dots indicating calculation
            val rad = Math.toRadians(orbitAngle.toDouble())
            val offsetDist = 5.5f
            val ox = (cos(rad) * offsetDist).toFloat()
            val oy = (sin(rad) * offsetDist).toFloat()

            drawCircle(
                color = eyeColor.copy(alpha = 0.90f),
                radius = 5.5f,
                center = Offset(leftEyeX + ox, eyeY + oy)
            )
            drawCircle(
                color = eyeColor.copy(alpha = 0.90f),
                radius = 5.5f,
                center = Offset(rightEyeX - ox, eyeY - oy)
            )
        }

        AvatarPresentationState.OFFLINE -> {
            // Dim horizontal resting lines (- -) indicating disconnected state
            val lineW = visorW * 0.15f
            drawLine(
                color = eyeColor,
                start = Offset(leftEyeX - lineW / 2f, eyeY),
                end = Offset(leftEyeX + lineW / 2f, eyeY),
                strokeWidth = 3f
            )
            drawLine(
                color = eyeColor,
                start = Offset(rightEyeX - lineW / 2f, eyeY),
                end = Offset(rightEyeX + lineW / 2f, eyeY),
                strokeWidth = 3f
            )
        }

        AvatarPresentationState.RECOVERY -> {
            // Empathetic, supportive brow line & warm eyes
            val eyeW = visorW * 0.14f
            val eyeH = visorH * 0.22f
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
            // Soft supportive brow line above eyes
            drawLine(
                color = eyeColor.copy(alpha = 0.7f),
                start = Offset(leftEyeX - eyeW * 0.6f, eyeY - eyeH * 0.85f),
                end = Offset(leftEyeX + eyeW * 0.6f, eyeY - eyeH * 0.65f),
                strokeWidth = 2f
            )
            drawLine(
                color = eyeColor.copy(alpha = 0.7f),
                start = Offset(rightEyeX - eyeW * 0.6f, eyeY - eyeH * 0.65f),
                end = Offset(rightEyeX + eyeW * 0.6f, eyeY - eyeH * 0.85f),
                strokeWidth = 2f
            )
        }

        AvatarPresentationState.POINTING -> {
            // Directional gaze slightly shifted towards pointing direction (up-right)
            val eyeW = visorW * 0.15f
            val eyeH = visorH * 0.28f * blinkScale
            val glanceX = 2.5f
            val glanceY = -1.5f
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(leftEyeX - eyeW / 2f + glanceX, eyeY - eyeH / 2f + glanceY),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(rightEyeX - eyeW / 2f + glanceX, eyeY - eyeH / 2f + glanceY),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )
            // Inner glint
            if (blinkScale > 0.4f) {
                drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 2f, center = Offset(leftEyeX + glanceX + 2f, eyeY + glanceY - 2f))
                drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 2f, center = Offset(rightEyeX + glanceX + 2f, eyeY + glanceY - 2f))
            }
        }

        AvatarPresentationState.WAITING_FOR_USER -> {
            // Attentive, slightly wider expectant round LED eyes
            val eyeW = visorW * 0.16f
            val eyeH = visorH * 0.29f * blinkScale
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(leftEyeX - eyeW / 2f, eyeY - eyeH / 2f),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(rightEyeX - eyeW / 2f, eyeY - eyeH / 2f),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )
            if (blinkScale > 0.4f) {
                drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 2.2f, center = Offset(leftEyeX + 1.5f, eyeY - 2.5f))
                drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 2.2f, center = Offset(rightEyeX + 1.5f, eyeY - 2.5f))
            }
        }

        AvatarPresentationState.COMPACT -> {
            // Clean mini LED pill eyes for compact dock
            val eyeW = visorW * 0.18f
            val eyeH = visorH * 0.32f
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(leftEyeX - eyeW / 2f, eyeY - eyeH / 2f),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(rightEyeX - eyeW / 2f, eyeY - eyeH / 2f),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )
        }

        else -> {
            // Standard friendly round LED pill eyes with blink and glint
            val eyeW = visorW * 0.15f
            val eyeH = visorH * 0.28f * blinkScale
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(leftEyeX - eyeW / 2f, eyeY - eyeH / 2f),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )
            drawRoundRect(
                color = eyeColor,
                topLeft = Offset(rightEyeX - eyeW / 2f, eyeY - eyeH / 2f),
                size = Size(eyeW, eyeH),
                cornerRadius = CornerRadius(eyeW * 0.5f, eyeW * 0.5f)
            )

            // Inner warm glint
            if (blinkScale > 0.4f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = 2f,
                    center = Offset(leftEyeX + 2f, eyeY - 2f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = 2f,
                    center = Offset(rightEyeX + 2f, eyeY - 2f)
                )
            }
        }
    }
}

private fun DrawScope.drawPointingGesture(
    centerX: Float,
    centerY: Float,
    radius: Float,
    angleRad: Float
) {
    val startX = centerX + (cos(angleRad) * radius)
    val startY = centerY + (sin(angleRad) * radius)
    val pointerLength = 26f
    val tipX = startX + (cos(angleRad) * pointerLength)
    val tipY = startY + (sin(angleRad) * pointerLength)

    // Glowing beam pointer arm
    drawLine(
        color = Color(0xFFF59E0B),
        start = Offset(startX, startY),
        end = Offset(tipX, tipY),
        strokeWidth = 4.5f
    )
    drawCircle(
        color = Color(0xFFFDE68A),
        radius = 5.5f,
        center = Offset(tipX, tipY)
    )
}
