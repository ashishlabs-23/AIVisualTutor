package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope

/**
 * Modern floating frosted-glass HUD dock pill shown when the tutor panel is collapsed.
 */
@Composable
fun WindowScope.TutorDock(onClick: () -> Unit) {
    WindowDraggableArea {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
                .background(
                    color = Color(0xF0101424),
                    shape = RoundedCornerShape(18.dp)
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(Color(0x888B5CF6), Color(0x4438BDF8), Color(0x22FFFFFF))
                    ),
                    shape = RoundedCornerShape(18.dp)
                )
                .clickable { onClick() }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = "✨", fontSize = 13.sp)
                Text(
                    text = "AI Visual Tutor",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}
