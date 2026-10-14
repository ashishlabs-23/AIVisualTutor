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
import pedagogy.DetailPreference
import pedagogy.PedagogicalOutcome
import pedagogy.SkillMasteryLevel
import tutor.OutcomeRequirementLevel
import tutor.OutcomeSatisfactionStatus
import tutor.TutorController
import tutor.VerificationStrategy

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
                            val lastObs = controller.recoveryCoordinator.workflow.lastObservation
                            val isConnected = lastObs?.status == context.BlenderObservationStatus.AVAILABLE
                            val statusColor = when {
                                state.selectedApplication != ApplicationType.BLENDER -> Color(0xFF22C55E)
                                isConnected -> Color(0xFF22C55E)
                                lastObs?.status == context.BlenderObservationStatus.FAILED -> Color(0xFFEF4444)
                                lastObs?.status == context.BlenderObservationStatus.UNAVAILABLE -> Color(0xFFEF4444)
                                else -> Color(0xFFFBBF24)
                            }
                            val statusText = when {
                                state.selectedApplication != ApplicationType.BLENDER -> "${state.selectedApplication.displayName} connected"
                                isConnected -> "Blender connected (port 47629)"
                                lastObs?.status == context.BlenderObservationStatus.FAILED -> "Blender error: ${lastObs.diagnostic ?: "check console"}"
                                lastObs?.status == context.BlenderObservationStatus.UNAVAILABLE -> "Blender not connected (port 47629)"
                                else -> "Blender adapter (port 47629)"
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(statusColor, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = statusText,
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

                    // Ambiguity Warning if detected
                    if (controller.activePlan?.isAmbiguous == true) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0x3DF59E0B), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0x66F59E0B), RoundedCornerShape(12.dp))
                                .padding(10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "⚠️ Clarification Needed",
                                    color = Color(0xFFFBBF24),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = controller.activePlan?.clarificationPrompt ?: "Please clarify your goal.",
                                    color = Color(0xFFFDE68A),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Goal Coverage & Outcome Status Section
                    val plan = controller.activePlan
                    val progress = controller.goalProgressState
                    if (plan != null && plan.requiredOutcomes.isNotEmpty()) {
                        val totalOutcomes = plan.requiredOutcomes.size
                        val totalMandatory = plan.requiredOutcomes.count { it.importance == OutcomeRequirementLevel.MANDATORY }
                        val coveredOutcomes = plan.validationResult?.coveredOutcomeIds?.size ?: totalOutcomes
                        val satisfiedMandatory = plan.requiredOutcomes.count {
                            progress.outcomeProgress[it.id] == OutcomeSatisfactionStatus.SATISFIED
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0x29141B2D), RoundedCornerShape(14.dp))
                                .border(1.dp, Color(0x3338BDF8), RoundedCornerShape(14.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🎯 Goal Coverage",
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "$coveredOutcomes of $totalOutcomes covered • $satisfiedMandatory of $totalMandatory verified",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }

                            // Outcomes list with status indicators
                            plan.requiredOutcomes.forEach { outcome ->
                                val status = progress.outcomeProgress[outcome.id] ?: OutcomeSatisfactionStatus.NOT_STARTED
                                val (statusIcon, statusColor) = when (status) {
                                    OutcomeSatisfactionStatus.SATISFIED -> Pair("✓", Color(0xFF22C55E))
                                    OutcomeSatisfactionStatus.IN_PROGRESS -> Pair("↻", Color(0xFF38BDF8))
                                    OutcomeSatisfactionStatus.FAILED -> Pair("✗", Color(0xFFEF4444))
                                    else -> Pair("○", Color(0xFF64748B))
                                }
                                val stratLabel = when (outcome.verificationStrategy) {
                                    VerificationStrategy.AUTHORITATIVE_STATE -> "State"
                                    VerificationStrategy.VISUAL_INSPECTION -> "Visual"
                                    VerificationStrategy.MANUAL_CHECK -> "Manual"
                                    VerificationStrategy.UNSUPPORTED -> "Unsupported"
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = statusIcon,
                                            color = statusColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = outcome.description,
                                            color = if (status == OutcomeSatisfactionStatus.SATISFIED) Color(0xFF94A3B8) else Color(0xFFE2E8F0),
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0x33475569), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = stratLabel,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Prerequisite step execution guard banner
                    if (!controller.canExecuteCurrentStep()) {
                        val plan = controller.activePlan
                        val currentStepDef = plan?.steps?.getOrNull(state.currentStepIndex)
                        val unmetPrereqIds = currentStepDef?.prerequisiteStepIds?.filterNot {
                            controller.goalProgressState.completedStepIds.contains(it)
                        } ?: emptyList()
                        val unmetTitles = unmetPrereqIds.mapNotNull { id ->
                            plan?.steps?.find { it.stepId == id }?.title
                        }
                        val prereqWarning = if (unmetTitles.isNotEmpty()) {
                            "⚠️ Prerequisite step${if (unmetTitles.size > 1) "s" else ""} '${unmetTitles.joinToString("', '")}' must be satisfied before this step can be verified."
                        } else {
                            "⚠️ Prerequisite steps must be satisfied before this step can be verified."
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0x3DF59E0B), RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0x66F59E0B), RoundedCornerShape(10.dp))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = prereqWarning,
                                color = Color(0xFFFDE68A),
                                fontSize = 11.sp
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
                            val isCompleted = controller.isStepIndexCompleted(idx)
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
                                // ...
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .background(Color(0xFF8B5CF6), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (isCompleted) "✔" else "${idx + 1}",
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
                                                if (isCompleted) Color(0xFF6D28D9) else Color(0x26475569),
                                                CircleShape
                                            )
                                            .border(
                                                1.dp,
                                                if (isCompleted) Color(0xFF8B5CF6) else Color(0xFF475569),
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (isCompleted) "✔" else "${idx + 1}",
                                            color = if (isCompleted) Color.White else Color(0xFF94A3B8),
                                            fontSize = if (isCompleted) 10.sp else 11.sp,
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

                    // Adaptive Tutoring & Demonstrated Progress (Phase 13)
                    val demonstratedSkills = controller.pedagogicalEngine.state.demonstratedSkills.values.filter {
                        it.masteryLevel != SkillMasteryLevel.NOT_OBSERVED
                    }
                    val currentPref = controller.pedagogicalEngine.state.preferences.detailPreference
                    val adaptiveRationale = controller.state.adaptiveInstruction?.adaptationRationale

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x29141B2D), RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0x33A78BFA), RoundedCornerShape(14.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🎓 Learner Progress", color = Color(0xFFA78BFA), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (demonstratedSkills.isEmpty()) "Initial Beginner" else "${demonstratedSkills.size} Demonstrated Skill${if (demonstratedSkills.size > 1) "s" else ""}",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Detail: ", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                DetailPreference.values().forEach { pref ->
                                    val isSelected = pref == currentPref
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (isSelected) Color(0xFF7C3AED) else Color(0x26475569),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .clickable { controller.setDetailPreference(pref) }
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = pref.name.lowercase().replaceFirstChar { it.uppercase() },
                                            color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                            fontSize = 9.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }

                        if (demonstratedSkills.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                demonstratedSkills.take(3).forEach { skill ->
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0x3334D399), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "✔ ${skill.displayName}",
                                            color = Color(0xFF34D399),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        if (!adaptiveRationale.isNullOrBlank()) {
                            Text(
                                text = "💡 $adaptiveRationale",
                                color = Color(0xFFFDE68A),
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )
                        }
                    }

                    // Assistant / Verification Speech Bubble (Matching mockup with Phase 11 Closed-Loop Recovery)
                    val recoveryState = controller.recoveryCoordinator.state
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
                                    coroutineScope.launch {
                                        if (recoveryState is tutor.ClosedLoopRecoveryState.RetryLimitReached) {
                                            controller.recoveryCoordinator.resetStepAttempts(state.currentStepIndex)
                                        }
                                        controller.recoveryCoordinator.verifyActiveStep()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Column {
                                val isCurrentCompleted = controller.isStepIndexCompleted(state.currentStepIndex)
                                val bubbleTitle = when (recoveryState) {
                                    is tutor.ClosedLoopRecoveryState.Verifying -> "Checking Blender state..."
                                    is tutor.ClosedLoopRecoveryState.VerifiedSuccess -> "Action Verified! ✔"
                                    is tutor.ClosedLoopRecoveryState.RecoveryRecommended -> "Not Verified (Attempt ${recoveryState.attemptNumber} of ${controller.recoveryCoordinator.policy.maxVerifiedFailuresPerStep})"
                                    is tutor.ClosedLoopRecoveryState.RetryLimitReached -> "Retry Limit Reached"
                                    is tutor.ClosedLoopRecoveryState.NeedsMoreEvidence -> "Verification Notice"
                                    is tutor.ClosedLoopRecoveryState.AwaitingRetry -> "Ready to Retry"
                                    is tutor.ClosedLoopRecoveryState.AwaitingLearnerAction -> "Ready for Action"
                                    is tutor.ClosedLoopRecoveryState.Error -> "Verification Error"
                                    is tutor.ClosedLoopRecoveryState.Cancelled -> "Verification Cancelled"
                                    else -> when {
                                        isCurrentCompleted -> "Action Verified! ✔"
                                        state.lastPedagogicalOutcome == PedagogicalOutcome.VERIFIED_FAILURE -> "State Check: Mismatch"
                                        state.lastPedagogicalOutcome == PedagogicalOutcome.UNCERTAIN -> "Verification Notice"
                                        activeStep.expectedState != null -> "Waiting for your action"
                                        else -> "Step Guidance"
                                    }
                                }

                                val bubbleColor = when (recoveryState) {
                                    is tutor.ClosedLoopRecoveryState.VerifiedSuccess -> Color(0xFF34D399)
                                    is tutor.ClosedLoopRecoveryState.RecoveryRecommended,
                                    is tutor.ClosedLoopRecoveryState.RetryLimitReached,
                                    is tutor.ClosedLoopRecoveryState.Error -> Color(0xFFF87171)
                                    is tutor.ClosedLoopRecoveryState.NeedsMoreEvidence -> Color(0xFFFBBF24)
                                    is tutor.ClosedLoopRecoveryState.Verifying -> Color(0xFF60A5FA)
                                    is tutor.ClosedLoopRecoveryState.Cancelled -> Color(0xFF94A3B8)
                                    is tutor.ClosedLoopRecoveryState.AwaitingLearnerAction -> Color(0xFFA78BFA)
                                    else -> when {
                                        isCurrentCompleted -> Color(0xFF34D399)
                                        state.lastPedagogicalOutcome == PedagogicalOutcome.VERIFIED_FAILURE -> Color(0xFFF87171)
                                        else -> Color(0xFFA78BFA)
                                    }
                                }

                                val bubbleText = when (recoveryState) {
                                    is tutor.ClosedLoopRecoveryState.Verifying -> "Observing live Blender scene telemetry and checking expected state..."
                                    is tutor.ClosedLoopRecoveryState.VerifiedSuccess -> recoveryState.explanation
                                    is tutor.ClosedLoopRecoveryState.RecoveryRecommended -> {
                                        val diagExp = recoveryState.diagnosticReport?.primaryMismatch?.explanation
                                        if (diagExp != null && diagExp.startsWith("Not verified", ignoreCase = true)) {
                                            diagExp
                                        } else {
                                            "${recoveryState.recommendation.title}: ${recoveryState.recommendation.actionSteps.firstOrNull() ?: recoveryState.recommendation.explanation}"
                                        }
                                    }
                                    is tutor.ClosedLoopRecoveryState.RetryLimitReached -> recoveryState.message
                                    is tutor.ClosedLoopRecoveryState.NeedsMoreEvidence -> recoveryState.message
                                    is tutor.ClosedLoopRecoveryState.AwaitingRetry -> "Perform the action in Blender, then click Verify to check."
                                    is tutor.ClosedLoopRecoveryState.AwaitingLearnerAction -> "Perform the action in Blender, then click Verify to check."
                                    is tutor.ClosedLoopRecoveryState.Error -> recoveryState.message + (recoveryState.cause?.message?.let { ": $it" } ?: "")
                                    is tutor.ClosedLoopRecoveryState.Cancelled -> recoveryState.reason
                                    else -> if (isCurrentCompleted) {
                                        "This step is completed! You can proceed to the next step or click Verify to re-check."
                                    } else {
                                        state.adaptationReason?.takeIf { state.lastPedagogicalOutcome != null }
                                            ?: "Next: ${activeStep.instruction} Then I will verify it."
                                    }
                                }

                                Text(
                                    text = bubbleTitle,
                                    color = bubbleColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = bubbleText,
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )

                                if (recoveryState is tutor.ClosedLoopRecoveryState.RecoveryRecommended) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0x338B5CF6), RoundedCornerShape(6.dp))
                                                .border(1.dp, Color(0x668B5CF6), RoundedCornerShape(6.dp))
                                                .clickable {
                                                    coroutineScope.launch {
                                                        controller.recoveryCoordinator.verifyActiveStep()
                                                    }
                                                }
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text("Verify Again", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else if (recoveryState is tutor.ClosedLoopRecoveryState.RetryLimitReached) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0x33EF4444), RoundedCornerShape(6.dp))
                                            .border(1.dp, Color(0x66EF4444), RoundedCornerShape(6.dp))
                                            .clickable {
                                                controller.recoveryCoordinator.resetStepAttempts(state.currentStepIndex)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text("Reset Attempts & Try Again", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else if (recoveryState is tutor.ClosedLoopRecoveryState.Error) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0x33EF4444), RoundedCornerShape(6.dp))
                                            .border(1.dp, Color(0x66EF4444), RoundedCornerShape(6.dp))
                                            .clickable {
                                                coroutineScope.launch {
                                                    controller.recoveryCoordinator.verifyActiveStep()
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text("Retry Verification", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
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
                                        controller.requestHint()
                                        val targetRegion = controller.state.currentHighlight
                                            ?: activeStep.targetRegion
                                            ?: controller.state.adaptiveInstruction?.targetRegion
                                        if (targetRegion != null && controller.state.currentHighlight == null) {
                                            controller.setHighlight(targetRegion)
                                        }
                                        overlayManager.showOverlay(targetRegion)
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
                                    if (overlayManager.isOverlayVisible) {
                                        overlayManager.hideOverlay()
                                    } else {
                                        if (!controller.state.screenAssistanceEnabled) {
                                            controller.toggleScreenAssistance()
                                        }
                                        val targetRegion = controller.state.currentHighlight
                                            ?: activeStep.targetRegion
                                            ?: controller.state.adaptiveInstruction?.targetRegion
                                        if (targetRegion != null && controller.state.currentHighlight == null) {
                                            controller.setHighlight(targetRegion)
                                        }
                                        overlayManager.showOverlay(targetRegion)
                                    }
                                }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (overlayManager.isOverlayVisible) "✨ Glow Off" else "✨ Glow On",
                                color = Color(0xFFA78BFA),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val isVerifying = recoveryState is tutor.ClosedLoopRecoveryState.Verifying
                        Box(
                            modifier = Modifier
                                .weight(1.1f)
                                .background(
                                    if (isVerifying) Color(0x333B82F6) else Color(0x33065F46),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isVerifying) Color(0x6660A5FA) else Color(0x6610B981),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = !isVerifying) {
                                    coroutineScope.launch {
                                        if (recoveryState is tutor.ClosedLoopRecoveryState.RetryLimitReached) {
                                            controller.recoveryCoordinator.resetStepAttempts(state.currentStepIndex)
                                        }
                                        controller.recoveryCoordinator.verifyActiveStep()
                                    }
                                }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isVerifying) "⏳ Verifying..." else "🎯 Verify",
                                color = if (isVerifying) Color(0xFF93C5FD) else Color(0xFF34D399),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
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
