package ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import context.ExpectedState
import kotlinx.coroutines.launch
import models.ApplicationType
import overlay.OverlayManager
import pedagogy.PedagogicalOutcome
import tutor.TutorController

/**
 * Transparent floating frosted-glass HUD panel designed to overlay directly
 * on top of Blender, matching the AI Visual Tutor design mockup.
 */
@Composable
fun WindowScope.TutorPanel(
    controller: TutorController,
    overlayManager: OverlayManager,
    onClose: () -> Unit,
    onOpenRegionSelector: () -> Unit,
    onCapturePreviousWindow: () -> Unit,
    captureStatus: String?,
    blenderVerificationBaselineExpected: ExpectedState? = null,
    isBlenderVerificationBusy: Boolean = false,
    blenderVerificationFeedback: String? = null,
    onCaptureBlenderBaseline: (ExpectedState) -> Unit = {},
    onVerifyBlenderAction: (ExpectedState) -> Unit = {},
    onAutoVerifyBlenderAction: (ExpectedState) -> Unit = {},
    onRequestScreenshotHelp: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val state = controller.state
    val allSteps = controller.stepsForCurrentApp()
    val step = controller.currentStep
    val isTutorialActive = (controller.activePlan != null || controller.hasLoadedMockTutorial) && allSteps.isNotEmpty() && step != null
    val scrollState = rememberScrollState()

    var userGoalInput by remember { mutableStateOf("") }
    var goalValidationError by remember { mutableStateOf<String?>(null) }
    var inputQuery by remember { mutableStateOf("") }
    var showFullChat by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(2.dp)
            .background(
                color = Color(0xEB0F1422), // Frosted dark glass with alpha
                shape = RoundedCornerShape(24.dp)
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color(0x668B5CF6),
                        Color(0x3338BDF8),
                        Color(0x22FFFFFF),
                        Color(0x228B5CF6)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Bar (Draggable across the top)
            WindowDraggableArea {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF6366F1))),
                                    RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "✨", fontSize = 16.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "AI Visual Tutor",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Color(0xFF22C55E), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (state.selectedApplication == ApplicationType.BLENDER) {
                                        "Blender state connected"
                                    } else {
                                        "${state.selectedApplication.displayName} connected"
                                    },
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "—",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onClose() }
                                .padding(4.dp)
                        )
                        Text(
                            text = "✕",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onClose() }
                                .padding(4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (!isTutorialActive) {
                // ==========================================
                // INITIAL EMPTY GOAL-ENTRY HUD STATE
                // ==========================================
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Introduction Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x29181D2D), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0x2B475569), RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Goal-Driven 3D Tutoring",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Enter any 3D task or object. AI Visual Tutor will generate a structured plan, illuminate tools on screen, and verify your actions closed-loop in Blender.",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }

                    // Goal Input Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x38191E2E), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0x406366F1), RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "WHAT DO YOU WANT TO BUILD?",
                            color = Color(0xFFC084FC),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )

                        BasicTextField(
                            value = userGoalInput,
                            onValueChange = {
                                userGoalInput = it
                                if (goalValidationError != null) goalValidationError = null
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                            cursorBrush = SolidColor(Color(0xFFA78BFA)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0x4D0F1422), RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0x3364748B), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            decorationBox = { innerTextField ->
                                if (userGoalInput.isEmpty()) {
                                    Text(
                                        text = "What do you want to build in Blender?",
                                        color = Color(0xFF64748B),
                                        fontSize = 13.sp
                                    )
                                }
                                innerTextField()
                            }
                        )

                        if (goalValidationError != null) {
                            Text(
                                text = goalValidationError!!,
                                color = Color(0xFFF87171),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Prominent "Start Tutoring" Action Button
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFF6366F1))),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    val trimmed = userGoalInput.trim()
                                    if (trimmed.isEmpty()) {
                                        goalValidationError = "Please enter what you want to build before starting."
                                    } else {
                                        goalValidationError = null
                                        val plan = controller.startUserGoal(trimmed)
                                        if (plan != null) {
                                            overlayManager.showOverlay()
                                        }
                                    }
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Start Tutoring",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    // Quick Suggestion Chips
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Suggestions:",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        val suggestions = listOf(
                            "Create a UV sphere",
                            "Make a cube and move it up",
                            "Delete active object",
                            "Switch to Edit Mode"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            suggestions.take(2).forEach { s ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(Color(0x2E1E2436), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0x33475569), RoundedCornerShape(8.dp))
                                        .clickable {
                                            userGoalInput = s
                                            goalValidationError = null
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = s,
                                        color = Color(0xFFCBD5E1),
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            suggestions.drop(2).forEach { s ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(Color(0x2E1E2436), RoundedCornerShape(8.dp))
                                        .border(1.dp, Color(0x33475569), RoundedCornerShape(8.dp))
                                        .clickable {
                                            userGoalInput = s
                                            goalValidationError = null
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = s,
                                        color = Color(0xFFCBD5E1),
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Optional Demo / Test Data
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Or load ",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Demo Tutorial",
                            color = Color(0xFFA78BFA),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable {
                                    controller.loadMockTutorial()
                                    overlayManager.showOverlay()
                                }
                                .padding(2.dp)
                        )
                    }
                }
            } else {
                // ==========================================
                // ACTIVE GENERATED PLAN / TUTORIAL HUD STATE
                // ==========================================
                val activeStep = step!!
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Goal Card (Capsule styling matching mockup)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x38191E2E), RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0x33475569), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Goal:  ",
                                    color = Color(0xFFC084FC),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = when {
                                        controller.activePlan != null -> controller.activePlan!!.goalText
                                        else -> activeStep.title
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1
                                )
                            }
                            Text(
                                text = "Reset",
                                color = Color(0xFFA78BFA),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable { controller.clearUserGoal() }
                                    .padding(start = 6.dp)
                            )
                        }
                    }

                    // Generated Plan Card (Matching mockup)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x29181D2D), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0x2B475569), RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (controller.activePlan != null) "Generated Plan" else "Tutorial Plan",
                                color = Color(0xFFE2E8F0),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Step ${activeStep.stepNumber} of ${activeStep.totalSteps}",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        allSteps.forEachIndexed { idx, st ->
                            val isPast = idx < state.currentStepIndex
                            val isCurrent = idx == state.currentStepIndex

                            if (isCurrent) {
                                // Highlighted active violet card
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0x3D7C3AED), RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0x668B5CF6), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .background(Color(0xFF8B5CF6), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "${idx + 1}. ${st.title}",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            while (controller.state.currentStepIndex > idx) controller.previousStep()
                                            while (controller.state.currentStepIndex < idx) controller.nextStep()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                if (isPast) Color(0xFF6D28D9) else Color(0x26475569),
                                                CircleShape
                                            )
                                            .border(
                                                1.dp,
                                                if (isPast) Color(0xFF8B5CF6) else Color(0xFF475569),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (isPast) "✔" else "${idx + 1}",
                                            color = if (isPast) Color.White else Color(0xFF94A3B8),
                                            fontSize = if (isPast) 10.sp else 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "${idx + 1}. ${st.title}",
                                        color = if (isPast) Color(0xFFCBD5E1) else Color(0xFF94A3B8),
                                        fontSize = 12.sp,
                                        fontWeight = if (isPast) FontWeight.Medium else FontWeight.Normal
                                    )
                                }
                            }

                            if (idx < allSteps.lastIndex) {
                                Box(
                                    modifier = Modifier
                                        .padding(start = 21.dp)
                                        .width(2.dp)
                                        .height(6.dp)
                                        .background(Color(0x33475569))
                                )
                            }
                        }
                    }

                    // Assistant / Verification Speech Bubble (Matching mockup)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(Color(0x3D1E2436), CircleShape)
                                .border(1.5.dp, Color(0xFF818CF8), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🤖", fontSize = 20.sp)
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0x3D191F2E), RoundedCornerShape(14.dp))
                                .border(1.dp, Color(0x33475569), RoundedCornerShape(14.dp))
                                .clickable {
                                    val exp = activeStep.expectedState
                                    if (exp != null) onAutoVerifyBlenderAction(exp)
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Column {
                                Text(
                                    text = when {
                                        state.lastPedagogicalOutcome == PedagogicalOutcome.VERIFIED_SUCCESS -> "Action Verified! ✔"
                                        state.lastPedagogicalOutcome == PedagogicalOutcome.VERIFIED_FAILURE -> "State Check: Mismatch"
                                        state.lastPedagogicalOutcome == PedagogicalOutcome.UNCERTAIN -> "Verification Notice"
                                        activeStep.expectedState != null -> "Waiting for your action"
                                        else -> "Step Guidance"
                                    },
                                    color = when {
                                        state.lastPedagogicalOutcome == PedagogicalOutcome.VERIFIED_SUCCESS -> Color(0xFF34D399)
                                        state.lastPedagogicalOutcome == PedagogicalOutcome.VERIFIED_FAILURE -> Color(0xFFF87171)
                                        else -> Color(0xFFA78BFA)
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = state.adaptationReason?.takeIf { state.lastPedagogicalOutcome != null }
                                        ?: "Next: ${activeStep.instruction} Then I will verify it.",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    // Quick Action Bar (Hint, Stuck, Glow, Auto-Track)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0x2E1E2436), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x33475569), RoundedCornerShape(8.dp))
                                .clickable {
                                    coroutineScope.launch {
                                        controller.resolveDynamicTarget()
                                        overlayManager.showOverlay()
                                    }
                                }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("💡 Hint", color = Color(0xFFFDE047), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0x2E1E2436), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x33475569), RoundedCornerShape(8.dp))
                                .clickable {
                                    coroutineScope.launch {
                                        controller.resolveDynamicTarget()
                                        overlayManager.showOverlay()
                                        if (onRequestScreenshotHelp != null) {
                                            onRequestScreenshotHelp()
                                        } else {
                                            controller.requestScreenshotHelp()
                                        }
                                    }
                                }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🆘 Stuck", color = Color(0xFFF87171), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1.1f)
                                .background(Color(0x333B82F6), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x6660A5FA), RoundedCornerShape(8.dp))
                                .clickable {
                                    coroutineScope.launch {
                                        if (onRequestScreenshotHelp != null) {
                                            onRequestScreenshotHelp()
                                        } else {
                                            controller.requestScreenshotHelp()
                                        }
                                    }
                                }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📸 Analyze", color = Color(0xFF93C5FD), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0x2E1E2436), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0x33475569), RoundedCornerShape(8.dp))
                                .clickable {
                                    if (overlayManager.isOverlayVisible) overlayManager.hideOverlay() else overlayManager.showOverlay()
                                }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (overlayManager.isOverlayVisible) "✨ Glow On" else "✨ Glow Off",
                                color = Color(0xFFA78BFA),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (activeStep.expectedState != null) {
                            Box(
                                modifier = Modifier
                                    .weight(1.1f)
                                    .background(Color(0x33065F46), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0x6610B981), RoundedCornerShape(8.dp))
                                    .clickable {
                                        activeStep.expectedState?.let { onAutoVerifyBlenderAction(it) }
                                    }
                                    .padding(vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🎯 Track", color = Color(0xFF34D399), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Step Navigation Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "← Previous",
                            color = if (state.currentStepIndex > 0) Color(0xFFAAB0BD) else Color(0xFF475569),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable(enabled = state.currentStepIndex > 0) { controller.previousStep() }
                                .padding(4.dp)
                        )
                        Text(
                            text = "Next →",
                            color = if (state.currentStepIndex < allSteps.lastIndex) Color(0xFF818CF8) else Color(0xFF475569),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable(enabled = state.currentStepIndex < allSteps.lastIndex) { controller.nextStep() }
                                .padding(4.dp)
                        )
                    }

                    // Expandable Full Chat Drawer
                    if (showFullChat) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(Color(0x2E1E2436), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0x33475569), RoundedCornerShape(12.dp))
                                .padding(6.dp)
                        ) {
                            ChatbotPanel(
                                controller = controller,
                                overlayManager = overlayManager,
                                onAutoVerifyAction = onAutoVerifyBlenderAction,
                                onCaptureScreen = onCapturePreviousWindow,
                                onRequestScreenshotHelp = onRequestScreenshotHelp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Input Pill Bar (Matching mockup)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x3D181D2D), RoundedCornerShape(22.dp))
                        .border(1.dp, Color(0x33475569), RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "💬",
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { showFullChat = !showFullChat }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = inputQuery,
                            onValueChange = { inputQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                            cursorBrush = SolidColor(Color(0xFFA78BFA)),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                if (inputQuery.isEmpty()) {
                                    Text(
                                        text = "Ask a question...",
                                        color = Color(0xFF64748B),
                                        fontSize = 12.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "➤",
                            color = if (inputQuery.isNotBlank()) Color(0xFFA78BFA) else Color(0xFF475569),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable(enabled = inputQuery.isNotBlank()) {
                                    val trimmed = inputQuery.trim()
                                    val lower = trimmed.lowercase()
                                    val isGoal = lower.startsWith("goal:") || lower.startsWith("i want to") ||
                                        (lower.startsWith("create ") && (lower.contains("sphere") || lower.contains("cube"))) ||
                                        (lower.startsWith("make ") && (lower.contains("sphere") || lower.contains("cube"))) ||
                                        lower.contains("move") || lower.contains("delete") || lower.contains("rename")

                                    if (isGoal) {
                                        val cleanGoal = trimmed.replace(Regex("""^(goal:|i want to)\s*""", RegexOption.IGNORE_CASE), "").trim()
                                        controller.startUserGoal(if (cleanGoal.isNotBlank()) cleanGoal else trimmed)
                                    } else {
                                        showFullChat = true
                                    }
                                    inputQuery = ""
                                }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
    }
}
