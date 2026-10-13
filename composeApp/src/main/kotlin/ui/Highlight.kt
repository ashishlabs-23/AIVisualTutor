package ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import models.HighlightRegion

/**
 * Draws an animated glowing rounded rectangle around [region] with dynamic
 * breathing pulse and HUD corner brackets.
 */
@Composable
fun Highlight(
    region: HighlightRegion,
    color: Color = Color(0xFFFACC15),
    strokeWidthPx: Float = 3.5f,
    cornerRadiusPx: Float = 6f
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val corner = CornerRadius(cornerRadiusPx, cornerRadiusPx)

        // Outer soft glow bloom (pulsing alpha & radius)
        val bloomExpansion = 4f + pulse * 3f
        drawRoundRect(
            color = color.copy(alpha = 0.20f * pulse),
            topLeft = Offset(region.x - bloomExpansion, region.y - bloomExpansion),
            size = Size(region.width + bloomExpansion * 2f, region.height + bloomExpansion * 2f),
            cornerRadius = CornerRadius(cornerRadiusPx + 3f, cornerRadiusPx + 3f),
            style = Stroke(width = strokeWidthPx + 6f * pulse)
        )

        // Secondary mid glow ring
        drawRoundRect(
            color = color.copy(alpha = 0.35f * pulse),
            topLeft = Offset(region.x - 2f, region.y - 2f),
            size = Size(region.width + 4f, region.height + 4f),
            cornerRadius = CornerRadius(cornerRadiusPx + 1f, cornerRadiusPx + 1f),
            style = Stroke(width = strokeWidthPx + 2f)
        )

        // Inner translucent tint
        drawRoundRect(
            color = color.copy(alpha = 0.12f * pulse),
            topLeft = Offset(region.x, region.y),
            size = Size(region.width, region.height),
            cornerRadius = corner
        )

        // Primary crisp golden border
        drawRoundRect(
            color = color,
            topLeft = Offset(region.x, region.y),
            size = Size(region.width, region.height),
            cornerRadius = corner,
            style = Stroke(width = strokeWidthPx)
        )

        // HUD Corner targeting reticles
        val bracketLen = (region.width.coerceAtMost(region.height) * 0.22f).coerceIn(8f, 18f)
        val bracketOffset = 4f
        val bracketStroke = 2.5f

        // Top-Left corner bracket
        drawLine(
            color = color,
            start = Offset(region.x - bracketOffset, region.y - bracketOffset + bracketLen),
            end = Offset(region.x - bracketOffset, region.y - bracketOffset),
            strokeWidth = bracketStroke
        )
        drawLine(
            color = color,
            start = Offset(region.x - bracketOffset, region.y - bracketOffset),
            end = Offset(region.x - bracketOffset + bracketLen, region.y - bracketOffset),
            strokeWidth = bracketStroke
        )

        // Top-Right corner bracket
        drawLine(
            color = color,
            start = Offset(region.x + region.width + bracketOffset - bracketLen, region.y - bracketOffset),
            end = Offset(region.x + region.width + bracketOffset, region.y - bracketOffset),
            strokeWidth = bracketStroke
        )
        drawLine(
            color = color,
            start = Offset(region.x + region.width + bracketOffset, region.y - bracketOffset),
            end = Offset(region.x + region.width + bracketOffset, region.y - bracketOffset + bracketLen),
            strokeWidth = bracketStroke
        )

        // Bottom-Left corner bracket
        drawLine(
            color = color,
            start = Offset(region.x - bracketOffset, region.y + region.height + bracketOffset - bracketLen),
            end = Offset(region.x - bracketOffset, region.y + region.height + bracketOffset),
            strokeWidth = bracketStroke
        )
        drawLine(
            color = color,
            start = Offset(region.x - bracketOffset, region.y + region.height + bracketOffset),
            end = Offset(region.x - bracketOffset + bracketLen, region.y + region.height + bracketOffset),
            strokeWidth = bracketStroke
        )

        // Bottom-Right corner bracket
        drawLine(
            color = color,
            start = Offset(region.x + region.width + bracketOffset - bracketLen, region.y + region.height + bracketOffset),
            end = Offset(region.x + region.width + bracketOffset, region.y + region.height + bracketOffset),
            strokeWidth = bracketStroke
        )
        drawLine(
            color = color,
            start = Offset(region.x + region.width + bracketOffset, region.y + region.height + bracketOffset - bracketLen),
            end = Offset(region.x + region.width + bracketOffset, region.y + region.height + bracketOffset),
            strokeWidth = bracketStroke
        )
    }
}
