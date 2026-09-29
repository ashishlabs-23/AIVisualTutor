package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import models.ApplicationType
import overlay.OverlayManager
import tutor.TutorController

/**
 * The full tutor panel: application selector, current step, navigation,
 * pause/resume and the screen-assistance / overlay / region-selection
 * controls. This is the composable shown once the user clicks the
 * floating dock.
 *
 * Declared as an extension of [WindowScope] for the same reason as
 * [TutorDock] - its header uses [WindowDraggableArea] to let the user
 * drag the panel window around by its title bar area.
 */
@Composable
fun WindowScope.TutorPanel(
    controller: TutorController,
    overlayManager: OverlayManager,
    onClose: () -> Unit,
    onOpenRegionSelector: () -> Unit,
    onCapturePreviousWindow: () -> Unit,
    captureStatus: String?
) {
    val state = controller.state
    val step = controller.currentStep
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color(0xFF23262E), shape = RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        // Header - draggable so the user can move the panel, with a close button.
        WindowDraggableArea {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "AI VISUAL TUTOR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "X",
                    color = Color(0xFFAAB0BD),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onClose() }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Divider(color = Color(0xFF3A3F4B))
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Text(text = "Application", color = Color(0xFFAAB0BD), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(6.dp))
            ApplicationSelector(
                selected = state.selectedApplication,
                onSelect = { app: ApplicationType -> controller.selectApplication(app) }
            )

            Spacer(modifier = Modifier.height(14.dp))
            InstructionCard(step = step)

            Spacer(modifier = Modifier.height(14.dp))
            StepNavigationButtons(
                onPrevious = { controller.previousStep() },
                onNext = { controller.nextStep() },
                isPreviousEnabled = state.currentStepIndex > 0,
                isNextEnabled = state.currentStepIndex < step.totalSteps - 1
            )

            Spacer(modifier = Modifier.height(16.dp))
            Divider(color = Color(0xFF3A3F4B))
            Spacer(modifier = Modifier.height(12.dp))

            ScreenAssistanceIndicator(
                enabled = state.screenAssistanceEnabled,
                onToggle = { controller.toggleScreenAssistance() }
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PauseResumeButton(
                    isPaused = state.isPaused,
                    onToggle = { controller.togglePause() }
                )
                TutorButton(
                    text = if (overlayManager.isOverlayVisible) "Hide Overlay" else "Show Overlay",
                    onClick = {
                        if (overlayManager.isOverlayVisible) overlayManager.hideOverlay() else overlayManager.showOverlay()
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            TutorButton(
                text = "Capture Screen",
                onClick = {
                    onCapturePreviousWindow()
                }
            )

            if (captureStatus != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = captureStatus,
                    color = Color(0xFFCFD3DA),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            TutorButton(text = "Select Region Manually", onClick = onOpenRegionSelector)
        }
    }
}
