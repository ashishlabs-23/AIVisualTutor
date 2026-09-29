package ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import models.HighlightRegion

/**
 * Draws a highlighted rectangle border around [region] on a full-size
 * transparent canvas. Meant to be layered inside [overlay.OverlayWindowContent].
 */
@Composable
fun Highlight(
    region: HighlightRegion,
    color: Color = Color(0xFFFFC107),
    strokeWidthPx: Float = 4f
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            color = color,
            topLeft = Offset(region.x, region.y),
            size = Size(region.width, region.height),
            style = Stroke(width = strokeWidthPx)
        )
        // A soft translucent fill so the region reads clearly even on
        // busy backgrounds, without fully obscuring what's underneath.
        drawRect(
            color = color.copy(alpha = 0.12f),
            topLeft = Offset(region.x, region.y),
            size = Size(region.width, region.height)
        )
    }
}
