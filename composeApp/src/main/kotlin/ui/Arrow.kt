package ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import models.HighlightRegion

/**
 * Draws a prominent golden HUD arrow and contextual guidance callout card
 * pointing directly at the target [region].
 */
@Composable
fun Arrow(
    region: HighlightRegion,
    label: String? = null,
    instruction: String? = null,
    color: Color = Color(0xFFFACC15)
) {
    val density = LocalDensity.current
    val calloutWidthDp = 270.dp
    val calloutWidthPx = with(density) { calloutWidthDp.toPx() }
    val arrowLengthPx = 38f
    val gapPx = 8f

    // Smart positioning: if target is close to the right side, place arrow & callout on the left
    val placeOnLeft = (region.x + region.width + arrowLengthPx + calloutWidthPx + 24f) > 1850f
    val targetY = region.y + region.height / 2f

    val targetX = if (placeOnLeft) {
        region.x - 4f
    } else {
        region.x + region.width + 4f
    }

    val startX = if (placeOnLeft) {
        targetX - arrowLengthPx
    } else {
        targetX + arrowLengthPx
    }

    // Callout box coordinates
    val calloutXPx = if (placeOnLeft) {
        (startX - gapPx - calloutWidthPx).coerceAtLeast(16f)
    } else {
        startX + gapPx
    }
    val calloutYPx = (targetY - 32f).coerceAtLeast(16f)

    val isZUp = region.directionalIndicator?.axis == models.DirectionalAxis.UP_POSITIVE_Z

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Directional Pointer Arrow
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (isZUp) {
                // Vertical upward directional indicator pointing in positive Z direction
                val arrowTipY = (region.y - 52f).coerceAtLeast(16f)
                val arrowBaseY = (region.y - 8f).coerceAtLeast(arrowTipY + 20f)
                val shaftX = region.centerX

                // Upward glowing shaft
                drawLine(
                    color = color,
                    start = Offset(shaftX, arrowBaseY),
                    end = Offset(shaftX, arrowTipY),
                    strokeWidth = 4.0f
                )

                // Arrowhead pointing UP
                val headPath = Path().apply {
                    moveTo(shaftX, arrowTipY)
                    lineTo(shaftX - 10f, arrowTipY + 14f)
                    lineTo(shaftX, arrowTipY + 10f)
                    lineTo(shaftX + 10f, arrowTipY + 14f)
                    close()
                }
                drawPath(path = headPath, color = color)
            } else {
                // Standard horizontal pointer arrow
                drawLine(
                    color = color,
                    start = Offset(startX, targetY),
                    end = Offset(targetX, targetY),
                    strokeWidth = 3.5f
                )

                val headPath = Path().apply {
                    if (placeOnLeft) {
                        moveTo(targetX, targetY)
                        lineTo(targetX - 16f, targetY - 8f)
                        lineTo(targetX - 11f, targetY)
                        lineTo(targetX - 16f, targetY + 8f)
                    } else {
                        moveTo(targetX, targetY)
                        lineTo(targetX + 16f, targetY - 8f)
                        lineTo(targetX + 11f, targetY)
                        lineTo(targetX + 16f, targetY + 8f)
                    }
                    close()
                }
                drawPath(path = headPath, color = color)
            }
        }

        // 1b. Directional Badge for +Z Up
        if (isZUp) {
            val badgeTipY = (region.y - 84f).coerceAtLeast(8f)
            val badgeX = (region.centerX - 42f).coerceAtLeast(16f)
            val badgeXDp = with(density) { badgeX.toDp() }
            val badgeYDp = with(density) { badgeTipY.toDp() }

            Box(
                modifier = Modifier
                    .offset(x = badgeXDp, y = badgeYDp)
                    .background(Color(0xE60F172A), RoundedCornerShape(6.dp))
                    .border(1.dp, color, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = region.directionalIndicator?.label ?: "▲ +Z Up",
                    color = color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // 2. Guidance Callout Card
        if (!label.isNullOrBlank() || !instruction.isNullOrBlank()) {
            val calloutXDp = with(density) { calloutXPx.toDp() }
            val calloutYDp = with(density) { calloutYPx.toDp() }

            Column(
                modifier = Modifier
                    .offset(x = calloutXDp, y = calloutYDp)
                    .widthIn(max = calloutWidthDp)
                    .background(Color(0xE6131826), RoundedCornerShape(10.dp))
                    .border(1.5.dp, color.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    text = "STEP GUIDANCE",
                    color = color,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                )
                if (!label.isNullOrBlank()) {
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (!instruction.isNullOrBlank() && instruction != label) {
                    Text(
                        text = instruction,
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}
