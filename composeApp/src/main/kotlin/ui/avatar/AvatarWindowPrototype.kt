package ui.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState

/**
 * Phase 1 Native Window Proof:
 * Displays a transparent, undecorated native window hosting the robot avatar
 * and minimal instruction bubble beside Blender.
 *
 * Verifies:
 * 1. Windows DWM transparent composition with zero black background artifacts.
 * 2. Drag-to-reposition across virtual desktop displays via [WindowDraggableArea].
 * 3. Interactive controls inside transparent window without click interference.
 * 4. Safe close/hide lifecycle.
 */
@Composable
fun AvatarWindowPrototype(
    onClose: () -> Unit = {},
    initialState: AvatarPresentationState = AvatarPresentationState.GUIDING
) {
    val windowSize = DpSize(380.dp, 220.dp)
    val windowState = rememberWindowState(
        size = windowSize,
        position = WindowPosition(x = 100.dp, y = 100.dp)
    )

    var presentationState by remember { mutableStateOf(initialState) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var stepIndex by remember { mutableStateOf(1) }

    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "AI Visual Tutor Avatar Proof",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true
    ) {
        // Enforce transparent alpha background on the AWT frame
        LaunchedEffect(window) {
            try {
                window.background = java.awt.Color(0, 0, 0, 0)
                window.contentPane.background = java.awt.Color(0, 0, 0, 0)
                (window.contentPane as? javax.swing.JComponent)?.isOpaque = false
                window.rootPane.isOpaque = false
                window.rootPane.background = java.awt.Color(0, 0, 0, 0)
            } catch (_: Exception) {}
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            // Entire composite is enclosed in a draggable area so user can reposition anywhere
            WindowDraggableArea {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Left Column: Avatar & State Cycle Controls
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AvatarView(
                            state = presentationState,
                            size = 105.dp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        // Mini state cycler chip for manual testing during proof
                        Box(
                            modifier = Modifier
                                .background(Color(0xD01E293B), RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0x60475569), RoundedCornerShape(10.dp))
                                .clickable {
                                    val allStates = AvatarPresentationState.values()
                                    val nextIdx = (presentationState.ordinal + 1) % allStates.size
                                    presentationState = allStates[nextIdx]
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "State: ${presentationState.name}",
                                color = Color(0xFFCBD5E1),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Right Column: Instruction Bubble & Drag Bar & Close
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        // Title bar with drag handle and close control
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⋮⋮ Drag to reposition",
                                color = Color(0x88CBD5E1),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )

                            // Close Button
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color(0x40EF4444), CircleShape)
                                    .clickable { onClose() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✕",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Interactive Instruction Bubble
                        val instruction = when (presentationState) {
                            AvatarPresentationState.SUCCESS -> "Action verified in Blender! Ready for next step."
                            AvatarPresentationState.THINKING -> "Analyzing the 3D scene and computing next guidance..."
                            AvatarPresentationState.OFFLINE -> "Blender adapter is offline. Start the state server in Blender."
                            AvatarPresentationState.RECOVERY -> "We noticed an issue. Try selecting the object first."
                            else -> "Press Shift+A and select Mesh -> UV Sphere to add the 3D sphere."
                        }

                        InstructionBubble(
                            stepNumber = stepIndex,
                            totalSteps = 4,
                            instructionText = instruction,
                            targetControlName = if (presentationState == AvatarPresentationState.POINTING) "3D Viewport -> Add Menu" else null,
                            statusLabel = presentationState.defaultStatusLabel,
                            statusColor = when (presentationState) {
                                AvatarPresentationState.SUCCESS -> Color(0xFF22C55E)
                                AvatarPresentationState.THINKING -> Color(0xFF38BDF8)
                                AvatarPresentationState.RECOVERY -> Color(0xFFF97316)
                                AvatarPresentationState.OFFLINE -> Color(0xFF64748B)
                                else -> Color(0xFFF59E0B)
                            },
                            highlightFeedback = actionFeedback,
                            onHighlightClick = {
                                actionFeedback = "✓ Target Highlighted!"
                                presentationState = AvatarPresentationState.POINTING
                            },
                            onExplainClick = {
                                actionFeedback = "💡 Concept: UV spheres provide uniform radial vertex topology."
                            }
                        )
                    }
                }
            }
        }
    }
}
