package ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import models.HighlightRegion

/**
 * Draws a prominent golden HUD arrow pointing directly at the target [region],
 * matching the visual indicator in the Blender overlay mockup.
 */
@Composable
fun Arrow(
    region: HighlightRegion,
    label: String? = null,
    color: Color = Color(0xFFFACC15)
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val targetX = region.x + region.width + 4f
        val targetY = region.y + region.height / 2f
        val startX = targetX + 38f
        val startY = targetY

        // Arrow shaft
        drawLine(
            color = color,
            start = Offset(startX, startY),
            end = Offset(targetX, targetY),
            strokeWidth = 4f
        )

        // Triangular solid arrowhead pointing left toward target
        val headPath = Path().apply {
            moveTo(targetX, targetY)
            lineTo(targetX + 16f, targetY - 9f)
            lineTo(targetX + 12f, targetY)
            lineTo(targetX + 16f, targetY + 9f)
            close()
        }
        drawPath(path = headPath, color = color)
    }
}
