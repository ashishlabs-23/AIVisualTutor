package ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import models.HighlightRegion

/**
 * Draws a glowing highlighted rounded rectangle around [region] on a full-size
 * transparent canvas matching the modern HUD overlay aesthetic.
 */
@Composable
fun Highlight(
    region: HighlightRegion,
    color: Color = Color(0xFFFACC15),
    strokeWidthPx: Float = 3.5f,
    cornerRadiusPx: Float = 6f
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val corner = CornerRadius(cornerRadiusPx, cornerRadiusPx)

        // Outer soft glow bloom
        drawRoundRect(
            color = color.copy(alpha = 0.25f),
            topLeft = Offset(region.x - 3f, region.y - 3f),
            size = Size(region.width + 6f, region.height + 6f),
            cornerRadius = CornerRadius(cornerRadiusPx + 2f, cornerRadiusPx + 2f),
            style = Stroke(width = strokeWidthPx + 5f)
        )

        // Inner translucent tint
        drawRoundRect(
            color = color.copy(alpha = 0.14f),
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
    }
}
