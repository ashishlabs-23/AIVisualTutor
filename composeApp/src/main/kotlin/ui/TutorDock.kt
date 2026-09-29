package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope

/**
 * The small floating "AI TUTOR" pill shown when the panel is collapsed.
 * The whole thing sits inside a [WindowDraggableArea] so the user can drag
 * the window it lives in around the screen, and clicking it
 * opens the full [TutorPanel].
 *
 * Declared as an extension of [WindowScope] because that is what
 * [WindowDraggableArea] requires - it needs access to the real window to
 * move it. This function is meant to be called directly inside a
 * `Window { ... }` content block (see Main.kt), which provides that scope.
 */
@Composable
fun WindowScope.TutorDock(onClick: () -> Unit) {
    WindowDraggableArea {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(color = Color(0xFF1E88E5), shape = RoundedCornerShape(16.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "AI TUTOR",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
