package ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import models.HighlightRegion
import ui.avatar.AvatarPresentationState
import ui.avatar.ScreenPoint
import kotlin.math.PI
import kotlin.math.sin

/**
 * Draws an animated glowing rounded rectangle around [region] with dynamic
 * breathing pulse, HUD corner brackets, and synchronized guidance tethering
 * to the companion robot avatar.
 */
@Composable
fun Highlight(
    region: HighlightRegion,
    color: Color = Color(0xFFFBBF24),
    strokeWidthPx: Float = 3.5f,
    cornerRadiusPx: Float = 6f,
    avatarEmitterPoint: ScreenPoint? = null,
    isAvatarPointing: Boolean = false,
    presentationState: AvatarPresentationState = AvatarPresentationState.IDLE,
    targetLabel: String? = null,
    glowEnabled: Boolean = true
) {
    val guidanceClock = ui.avatar.rememberSynchronizedGuidanceClock()
    val pulse = guidanceClock.breathingPulse
    val photonPhase = guidanceClock.photonPhase
    val impactPhase = guidanceClock.impactPhase

    // Synchronized pedagogical state color resolution
    val (primaryThemeColor, accentThemeGlow) = when (presentationState) {
        AvatarPresentationState.SUCCESS -> Pair(Color(0xFF22C55E), Color(0xFF86EFAC)) // Emerald Green
        AvatarPresentationState.THINKING, AvatarPresentationState.VERIFYING -> Pair(Color(0xFF38BDF8), Color(0xFFBAE6FD)) // Electric Cyan
        AvatarPresentationState.RECOVERY -> Pair(Color(0xFFEF4444), Color(0xFFFCA5A5)) // Warning Crimson
        else -> Pair(color, Color(0xFFF5792A)) // Signature Amber-Gold & Radiant Orange
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val corner = CornerRadius(cornerRadiusPx, cornerRadiusPx)

            // =====================================================================
            // 1. SYNCHRONIZED GUIDANCE BEAM (Avatar Emitter -> Target Impact)
            // =====================================================================
            if (glowEnabled && avatarEmitterPoint != null && isAvatarPointing && avatarEmitterPoint.isFinite) {
                val sx = avatarEmitterPoint.x
                val sy = avatarEmitterPoint.y
                val rx = region.x
                val ry = region.y
                val rx2 = region.x + region.width
                val ry2 = region.y + region.height

                // Calculate closest point on bounding box perimeter to the emitter
                val impactX = when {
                    sx < rx -> rx
                    sx > rx2 -> rx2
                    else -> sx
                }
                val impactY = when {
                    sy < ry -> ry
                    sy > ry2 -> ry2
                    else -> sy
                }

                val emitterOffset = Offset(sx, sy)
                val impactOffset = Offset(impactX, impactY)

                // Outer plasma glow beam
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            accentThemeGlow.copy(alpha = 0.35f * pulse),
                            primaryThemeColor.copy(alpha = 0.18f * pulse)
                        ),
                        start = emitterOffset,
                        end = impactOffset
                    ),
                    start = emitterOffset,
                    end = impactOffset,
                    strokeWidth = 8.5f + 3f * pulse,
                    cap = StrokeCap.Round
                )

                // Inner high-energy laser core
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f),
                            primaryThemeColor.copy(alpha = 0.85f)
                        ),
                        start = emitterOffset,
                        end = impactOffset
                    ),
                    start = emitterOffset,
                    end = impactOffset,
                    strokeWidth = 2.6f,
                    cap = StrokeCap.Round
                )

                // Traveling photon packets propagating from avatar to target
                for (i in 0..2) {
                    val nodeT = (photonPhase + i * 0.333f) % 1.0f
                    val nx = sx + (impactX - sx) * nodeT
                    val ny = sy + (impactY - sy) * nodeT
                    val nodeAlpha = (sin(nodeT * PI.toFloat())).coerceIn(0f, 1f)
                    drawCircle(
                        color = accentThemeGlow.copy(alpha = 0.5f * nodeAlpha),
                        radius = 5.5f,
                        center = Offset(nx, ny)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.95f * nodeAlpha),
                        radius = 2.4f,
                        center = Offset(nx, ny)
                    )
                }

                // Target impact flare and concentric spark ring
                val rippleRadius = 4f + 14f * impactPhase
                val rippleAlpha = (1f - impactPhase).coerceIn(0f, 1f)
                drawCircle(
                    color = primaryThemeColor.copy(alpha = rippleAlpha * 0.75f),
                    radius = rippleRadius,
                    center = impactOffset,
                    style = Stroke(width = 1.6f)
                )
                drawCircle(
                    color = primaryThemeColor,
                    radius = 4.5f,
                    center = impactOffset
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.2f,
                    center = impactOffset
                )
            }

            // =====================================================================
            // 2. HIGHLIGHT BOUNDING BOX & GLOW
            // =====================================================================
            drawHighlightEffect(
                region = region,
                primaryColor = primaryThemeColor,
                accentColor = accentThemeGlow,
                glowEnabled = glowEnabled,
                pulse = pulse,
                strokeWidthPx = strokeWidthPx,
                cornerRadiusPx = cornerRadiusPx
            )

            // =====================================================================
            // 3. STATE-SPECIFIC FX (Scanning Laser / Celebration Rings)
            // =====================================================================
            if (glowEnabled && (presentationState == AvatarPresentationState.THINKING || presentationState == AvatarPresentationState.VERIFYING)) {
                val scanY = region.y + region.height * photonPhase
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            primaryThemeColor.copy(alpha = 0.75f),
                            Color.White,
                            primaryThemeColor.copy(alpha = 0.75f),
                            Color.Transparent
                        ),
                        startX = region.x,
                        endX = region.x + region.width
                    ),
                    start = Offset(region.x, scanY),
                    end = Offset(region.x + region.width, scanY),
                    strokeWidth = 2.4f
                )
            }

            if (glowEnabled && presentationState == AvatarPresentationState.SUCCESS) {
                val successExpansion = 24f * photonPhase
                val successAlpha = (1f - photonPhase).coerceIn(0f, 1f)
                drawRoundRect(
                    color = primaryThemeColor.copy(alpha = 0.45f * successAlpha),
                    topLeft = Offset(region.x - successExpansion, region.y - successExpansion),
                    size = Size(region.width + successExpansion * 2f, region.height + successExpansion * 2f),
                    cornerRadius = CornerRadius(cornerRadiusPx + 4f, cornerRadiusPx + 4f),
                    style = Stroke(width = 2.4f)
                )
            }
        }

    // =====================================================================
    // 5. UNMISTAKABLE HUD TARGET BADGE PILL
    // =====================================================================
    if (!targetLabel.isNullOrBlank()) {
        val badgeX = (region.x).coerceAtLeast(8f)
        val badgeY = if (region.y > 34f) {
            region.y - 28f
        } else {
            region.y + region.height + 6f
        }

        Box(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(badgeX.toInt(), badgeY.toInt())
                    }
                }
                .background(
                    color = Color(0xF20F172A),
                    shape = RoundedCornerShape(6.dp)
                )
                .border(
                    width = 1.2.dp,
                    color = primaryThemeColor,
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("🎯", fontSize = 10.sp)
                Text(
                    text = targetLabel,
                    color = Color(0xFFF8FAFC),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
    }
}

/**
 * Draws the high-contrast target outline, multi-layer glow bloom, and HUD reticle brackets
 * directly onto any [androidx.compose.ui.graphics.drawscope.DrawScope].
 *
 * Exposing this as a pure DrawScope function enables direct off-screen headless rendering tests
 * with exact pixel-level verification, decoupled from the live desktop environment.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHighlightEffect(
    region: HighlightRegion,
    primaryColor: Color = Color(0xFFFBBF24),
    accentColor: Color = Color(0xFFF5792A),
    glowEnabled: Boolean = true,
    pulse: Float = 1.0f,
    strokeWidthPx: Float = 3.5f,
    cornerRadiusPx: Float = 6f
) {
    if (region.width <= 0f || region.height <= 0f) return

    val corner = CornerRadius(cornerRadiusPx, cornerRadiusPx)

    if (glowEnabled) {
        // Outer soft glow bloom (pulsing alpha & radius)
        val bloomExpansion = 4f + pulse * 4f
        drawRoundRect(
            color = accentColor.copy(alpha = 0.35f * pulse),
            topLeft = Offset(region.x - bloomExpansion, region.y - bloomExpansion),
            size = Size(region.width + bloomExpansion * 2f, region.height + bloomExpansion * 2f),
            cornerRadius = CornerRadius(cornerRadiusPx + 3f, cornerRadiusPx + 3f),
            style = Stroke(width = strokeWidthPx + 6f * pulse)
        )

        // Secondary mid intense glow ring
        drawRoundRect(
            color = primaryColor.copy(alpha = 0.60f * pulse),
            topLeft = Offset(region.x - 2f, region.y - 2f),
            size = Size(region.width + 4f, region.height + 4f),
            cornerRadius = CornerRadius(cornerRadiusPx + 1f, cornerRadiusPx + 1f),
            style = Stroke(width = strokeWidthPx + 2f)
        )

        // Inner translucent tint - subtle backing
        drawRoundRect(
            color = primaryColor.copy(alpha = 0.12f * pulse),
            topLeft = Offset(region.x, region.y),
            size = Size(region.width, region.height),
            cornerRadius = corner
        )

        // Primary solid theme border
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(region.x, region.y),
            size = Size(region.width, region.height),
            cornerRadius = corner,
            style = Stroke(width = strokeWidthPx)
        )

        // High-contrast luminous inner filament: distinguishes the tutor highlight
        // from Blender's native orange mesh selection outline (e.g. selected cube)
        val innerInset = 1.6f
        if (region.width > innerInset * 4f && region.height > innerInset * 4f) {
            drawRoundRect(
                color = Color.White.copy(alpha = (0.75f + 0.25f * pulse).coerceIn(0f, 1f)),
                topLeft = Offset(region.x + innerInset, region.y + innerInset),
                size = Size(region.width - innerInset * 2f, region.height - innerInset * 2f),
                cornerRadius = CornerRadius((cornerRadiusPx - 1f).coerceAtLeast(1f), (cornerRadiusPx - 1f).coerceAtLeast(1f)),
                style = Stroke(width = 1.5f)
            )
        }

        // HUD CORNER TARGETING RETICLES
        val bracketLen = (region.width.coerceAtMost(region.height) * 0.25f).coerceIn(8f, 20f)
        val bracketOffset = 4f
        val bracketStroke = 2.5f

        // Top-Left corner bracket
        drawLine(
            color = primaryColor,
            start = Offset(region.x - bracketOffset, region.y - bracketOffset + bracketLen),
            end = Offset(region.x - bracketOffset, region.y - bracketOffset),
            strokeWidth = bracketStroke
        )
        drawLine(
            color = primaryColor,
            start = Offset(region.x - bracketOffset, region.y - bracketOffset),
            end = Offset(region.x - bracketOffset + bracketLen, region.y - bracketOffset),
            strokeWidth = bracketStroke
        )

        // Top-Right corner bracket
        drawLine(
            color = primaryColor,
            start = Offset(region.x + region.width + bracketOffset - bracketLen, region.y - bracketOffset),
            end = Offset(region.x + region.width + bracketOffset, region.y - bracketOffset),
            strokeWidth = bracketStroke
        )
        drawLine(
            color = primaryColor,
            start = Offset(region.x + region.width + bracketOffset, region.y - bracketOffset),
            end = Offset(region.x + region.width + bracketOffset, region.y - bracketOffset + bracketLen),
            strokeWidth = bracketStroke
        )

        // Bottom-Left corner bracket
        drawLine(
            color = primaryColor,
            start = Offset(region.x - bracketOffset, region.y + region.height + bracketOffset - bracketLen),
            end = Offset(region.x - bracketOffset, region.y + region.height + bracketOffset),
            strokeWidth = bracketStroke
        )
        drawLine(
            color = primaryColor,
            start = Offset(region.x - bracketOffset, region.y + region.height + bracketOffset),
            end = Offset(region.x - bracketOffset + bracketLen, region.y + region.height + bracketOffset),
            strokeWidth = bracketStroke
        )

        // Bottom-Right corner bracket
        drawLine(
            color = primaryColor,
            start = Offset(region.x + region.width + bracketOffset - bracketLen, region.y + region.height + bracketOffset),
            end = Offset(region.x + region.width + bracketOffset, region.y + region.height + bracketOffset),
            strokeWidth = bracketStroke
        )
        drawLine(
            color = primaryColor,
            start = Offset(region.x + region.width + bracketOffset, region.y + region.height + bracketOffset - bracketLen),
            end = Offset(region.x + region.width + bracketOffset, region.y + region.height + bracketOffset),
            strokeWidth = bracketStroke
        )
    } else {
        // Glow Off: render clean, high-contrast, non-blooming outline without ambient bloom
        drawRoundRect(
            color = primaryColor.copy(alpha = 0.90f),
            topLeft = Offset(region.x, region.y),
            size = Size(region.width, region.height),
            cornerRadius = corner,
            style = Stroke(width = strokeWidthPx.coerceAtMost(3.0f))
        )
        val innerInset = 1.4f
        if (region.width > innerInset * 4f && region.height > innerInset * 4f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.70f),
                topLeft = Offset(region.x + innerInset, region.y + innerInset),
                size = Size(region.width - innerInset * 2f, region.height - innerInset * 2f),
                cornerRadius = CornerRadius((cornerRadiusPx - 1f).coerceAtLeast(1f), (cornerRadiusPx - 1f).coerceAtLeast(1f)),
                style = Stroke(width = 1.2f)
            )
        }
    }
}

