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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import chat.ChatContextBuilder
import chat.SmartChatbotProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import overlay.OverlayManager
import pedagogy.PedagogicalOutcome
import tutor.TutorController

/**
 * Production Integrated Avatar Window for AI Visual Tutor.
 *
 * Coordinates:
 * - Transparent native window beside Blender.
 * - Authoritative state reflection from [TutorController].
 * - Real target grounding via [OverlayManager] and [tutor.DynamicTargetPositioningService].
 * - Contextual explanations via [SmartChatbotProvider].
 * - Fluid drag-to-reposition across multi-monitor setups.
 * - Smooth toggling between Compact dock pill and Expanded bubble.
 */
@Composable
fun AvatarWindow(
    controller: TutorController,
    overlayManager: OverlayManager,
    isBlenderConnected: Boolean,
    isVerificationBusy: Boolean = false,
    visible: Boolean = true,
    isWorkspaceOpen: Boolean = false,
    onToggleWorkspace: () -> Unit = {},
    onCloseRequest: () -> Unit = {},
    windowState: WindowState = rememberWindowState(
        position = WindowPosition(x = 100.dp, y = 100.dp),
        size = DpSize(420.dp, 230.dp)
    )
) {
    val coroutineScope = rememberCoroutineScope()
    val chatbotProvider = remember { SmartChatbotProvider() }
    val contextBuilder = remember { ChatContextBuilder() }

    var isCompact by remember { mutableStateOf(false) }
    var isHighlightBusy by remember { mutableStateOf(false) }
    var highlightFeedback by remember { mutableStateOf<String?>(null) }
    var isExplainBusy by remember { mutableStateOf(false) }
    var explanationText by remember { mutableStateOf<String?>(null) }
    var isRecoveryActive by remember { mutableStateOf(false) }

    // Dynamic window resizing based on presentation mode
    val expandedSize = DpSize(420.dp, 240.dp)
    val compactSize = DpSize(180.dp, 58.dp)

    LaunchedEffect(isCompact) {
        windowState.size = if (isCompact) compactSize else expandedSize
    }

    // Observe authoritative outcome changes from TutorController
    val state = controller.state
    val step = controller.currentStep
    val lastOutcome = state.lastPedagogicalOutcome

    LaunchedEffect(lastOutcome) {
        when (lastOutcome) {
            PedagogicalOutcome.VERIFIED_FAILURE, PedagogicalOutcome.UNCERTAIN -> {
                isRecoveryActive = true
            }
            PedagogicalOutcome.VERIFIED_SUCCESS -> {
                isRecoveryActive = false
            }
            else -> {}
        }
    }

    // Clear feedback when step advances
    LaunchedEffect(state.currentStepIndex) {
        highlightFeedback = null
        explanationText = null
        isRecoveryActive = false
    }

    // Determine target presence for pointing
    val hasTarget = step?.targetControlName != null ||
        step?.targetRegion != null ||
        state.currentHighlight != null ||
        overlayManager.activeHighlightRegion != null

    // Determine transient verified success (showing for 3.5s after verified success)
    var isShowingSuccess by remember { mutableStateOf(false) }
    LaunchedEffect(lastOutcome) {
        if (lastOutcome == PedagogicalOutcome.VERIFIED_SUCCESS) {
            isShowingSuccess = true
            delay(3500)
            isShowingSuccess = false
        }
    }

    // Authoritative presentation state resolution enforcing precedence
    val presentationState = AvatarStateMachine.resolvePresentationState(
        isBlenderConnected = isBlenderConnected,
        isCompact = isCompact,
        isVerificationBusy = isVerificationBusy,
        isThinking = isHighlightBusy || isExplainBusy,
        isExplaining = !isExplainBusy && explanationText != null,
        isVerifiedSuccess = isShowingSuccess,
        isRecoveryActive = isRecoveryActive,
        activeStep = step,
        hasTarget = hasTarget
    )

    Window(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = "AI Visual Tutor Avatar",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        visible = visible
    ) {
        // Enforce transparent alpha background on native AWT container
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
            if (isCompact) {
                // ==========================================
                // COMPACT DOCK PRESENTATION
                // ==========================================
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
                            .clickable { isCompact = false }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AvatarView(
                                state = AvatarPresentationState.COMPACT,
                                size = 32.dp
                            )
                            Column {
                                Text(
                                    text = "AI Visual Tutor",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = presentationState.defaultStatusLabel,
                                    color = if (isBlenderConnected) Color(0xFF38BDF8) else Color(0xFFEF4444),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            } else {
                // ==========================================
                // EXPANDED AVATAR + INSTRUCTION BUBBLE
                // ==========================================
                WindowDraggableArea {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Left: Robot Avatar & Minimize affordance
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AvatarView(
                                state = presentationState,
                                size = 96.dp
                            )

                            // Minimize to Compact Dock chip
                            Box(
                                modifier = Modifier
                                    .background(Color(0x331E293B), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0x33475569), RoundedCornerShape(8.dp))
                                    .clickable { isCompact = true }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "━ Dock",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Right: Floating Instruction Bubble
                        Column(modifier = Modifier.weight(1f)) {
                            // Top Drag Handle & Close Control
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⋮⋮ Drag to move",
                                    color = Color(0x66CBD5E1),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )

                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(Color(0x28EF4444), CircleShape)
                                        .clickable { isCompact = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✕",
                                        color = Color(0xFFF87171),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            val allSteps = controller.stepsForCurrentApp()
                            val totalStepsCount = if (allSteps.isNotEmpty()) allSteps.size else 0
                            val stepNumber = if (step != null) step.stepNumber else null

                            val statusColor = when (presentationState) {
                                AvatarPresentationState.SUCCESS -> Color(0xFF22C55E)
                                AvatarPresentationState.THINKING, AvatarPresentationState.VERIFYING -> Color(0xFF38BDF8)
                                AvatarPresentationState.RECOVERY -> Color(0xFFF97316)
                                AvatarPresentationState.OFFLINE -> Color(0xFFEF4444)
                                else -> Color(0xFFF59E0B)
                            }

                            InstructionBubble(
                                stepNumber = stepNumber,
                                totalSteps = totalStepsCount,
                                instructionText = step?.instruction,
                                targetControlName = step?.targetControlName,
                                statusLabel = presentationState.defaultStatusLabel,
                                statusColor = statusColor,
                                isHighlightBusy = isHighlightBusy,
                                highlightFeedback = highlightFeedback,
                                onHighlightClick = {
                                    coroutineScope.launch {
                                        if (isHighlightBusy) return@launch
                                        isHighlightBusy = true
                                        highlightFeedback = "Resolving target in Blender..."
                                        try {
                                            val targetName = step?.targetControlName
                                            val result = if (!targetName.isNullOrBlank()) {
                                                controller.resolveTargetForName(targetName)
                                            } else {
                                                controller.resolveDynamicTarget()
                                            }
                                            if (result.region != null) {
                                                controller.setHighlight(result.region)
                                                overlayManager.showOverlay(result.region)
                                                highlightFeedback = "✓ Target highlighted on screen"
                                            } else {
                                                highlightFeedback = "Notice: Target location approximate"
                                                overlayManager.showOverlay(step?.targetRegion)
                                            }
                                        } catch (ex: Exception) {
                                            highlightFeedback = "Highlight unavailable: ${ex.message ?: "Check Blender"}"
                                        } finally {
                                            isHighlightBusy = false
                                        }
                                    }
                                },
                                isExplainBusy = isExplainBusy,
                                explanationText = explanationText,
                                onExplainClick = {
                                    coroutineScope.launch {
                                        if (isExplainBusy) return@launch
                                        if (step == null) {
                                            explanationText = "Start a goal or load a tutorial step to see in-depth concept guidance."
                                            return@launch
                                        }
                                        isExplainBusy = true
                                        try {
                                            val hint = controller.requestHint()
                                            if (!hint.isNullOrBlank()) {
                                                explanationText = hint
                                            } else {
                                                val context = contextBuilder.buildContext(
                                                    question = "Explain how to perform '${step.instruction}' and why it is important in Blender.",
                                                    controller = controller
                                                )
                                                val response = chatbotProvider.askTutor(context)
                                                explanationText = response.replyText
                                            }
                                        } catch (ex: Exception) {
                                            explanationText = "Concept: ${step.description}\nHint: ${step.conceptualHint ?: "Follow the indicated action in Blender."}"
                                        } finally {
                                            isExplainBusy = false
                                        }
                                    }
                                },
                                onDismissExplanation = {
                                    explanationText = null
                                },
                                onToggleWorkspace = onToggleWorkspace,
                                isWorkspaceOpen = isWorkspaceOpen
                            )
                        }
                    }
                }
            }
        }
    }
}
