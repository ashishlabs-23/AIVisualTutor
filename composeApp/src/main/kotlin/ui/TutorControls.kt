package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** "Previous" / "Next" step buttons with visual disabled state at boundaries. */
@Composable
fun StepNavigationButtons(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    isPreviousEnabled: Boolean = true,
    isNextEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TutorButton(text = "Previous", onClick = onPrevious, enabled = isPreviousEnabled)
        TutorButton(text = "Next", onClick = onNext, enabled = isNextEnabled)
    }
}

/** Small pill-shaped dot + label showing whether screen assistance is ON or OFF. */
@Composable
fun ScreenAssistanceIndicator(
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.clickable { onToggle() },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (enabled) Color(0xFF4CAF50) else Color(0xFF757575),
                    shape = CircleShape
                )
        )
        Text(
            text = if (enabled) "Screen Assistance ON" else "Screen Assistance OFF",
            color = Color(0xFFCFD3DA)
        )
    }
}

/** Pause / Resume toggle button. Label swaps depending on [isPaused]. */
@Composable
fun PauseResumeButton(
    isPaused: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    TutorButton(text = if (isPaused) "Resume" else "Pause", onClick = onToggle, modifier = modifier)
}

/** Shared button styling used across the tutor panel with enabled/disabled visual support. */
@Composable
fun TutorButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .background(
                color = if (enabled) Color(0xFF3A3F4B) else Color(0xFF282C35),
                shape = RoundedCornerShape(8.dp)
            )
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color(0xFF6B7280)
        )
    }
}

