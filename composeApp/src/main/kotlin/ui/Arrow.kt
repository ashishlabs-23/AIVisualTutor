package ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import models.HighlightRegion

/**
 * Draws a simple arrow from a small callout label down to the top-left
 * corner of [region], plus the label bubble itself. This is intentionally
 * simple geometry (a line + triangular arrowhead) rather than a fancy
 * curved callout, to keep the drawing code easy to follow.
 */
@Composable
fun Arrow(
    region: HighlightRegion,
    label: String,
    color: Color = Color(0xFF2196F3)
) {
    // Anchor the label a fixed distance up and to the left of the region,
    // clamped so it never goes off the top-left of the screen.
    val labelX = (region.x - 40f).coerceAtLeast(8f)
    val labelY = (region.y - 60f).coerceAtLeast(8f)
    val targetX = region.x
    val targetY = region.y

    Canvas(modifier = Modifier.fillMaxSize()) {
        val start = Offset(labelX + 30f, labelY + 30f)
        val end = Offset(targetX, targetY)

        drawLine(color = color, start = start, end = end, strokeWidth = 3f)

        // Arrowhead: two short lines angled back from the line's direction.
        val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
        val arrowLength = 14f
        val arrowAngle = Math.toRadians(28.0)

        val leftWing = Offset(
            x = (end.x - arrowLength * cos(angle - arrowAngle)).toFloat(),
            y = (end.y - arrowLength * sin(angle - arrowAngle)).toFloat()
        )
        val rightWing = Offset(
            x = (end.x - arrowLength * cos(angle + arrowAngle)).toFloat(),
            y = (end.y - arrowLength * sin(angle + arrowAngle)).toFloat()
        )

        drawLine(color = color, start = end, end = leftWing, strokeWidth = 3f)
        drawLine(color = color, start = end, end = rightWing, strokeWidth = 3f)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(labelX.roundToInt(), labelY.roundToInt()) }
            .background(color = color, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
