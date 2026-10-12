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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowScope
import context.ExpectedState
import kotlinx.coroutines.launch
import models.ApplicationType
import overlay.OverlayManager
import tutor.TutorController

/**
 * Modern AI Visual Tutor unified panel matching the desktop design specification:
 * - Header with application name, tutorial title, beginner badge, and step progress bar
 * - Step visual preview card with title, description, and [Previous] / [Next] navigation
 * - Verification status card: "Waiting for your action... The tutor will automatically verify"
 * - Need Help? section: [I'm Stuck on this Step] and [Give me a Hint]
 * - Integrated [Chat] / [Explanation] tabs
 * - Current Progress checklist with active indicators
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
    onAutoVerifyBlenderAction: (ExpectedState) -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val state = controller.state
    val step = controller.currentStep
    val scrollState = rememberScrollState()
    var activeTab by remember { mutableStateOf(0) } // 0: Chat, 1: Explanation
    var showAdvancedTools by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color(0xFF131722), shape = RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF232936), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        // Draggable Title Bar
        WindowDraggableArea {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🎓", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "AI Visual Tutor",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "—",
                        color = Color(0xFFAAB0BD),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { /* minimize */ }
                    )
                    Text(
                        text = "▢",
                        color = Color(0xFFAAB0BD),
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { /* maximize */ }
                    )
                    Text(
                        text = "✕",
                        color = Color(0xFFAAB0BD),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onClose() }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Main scrollable area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            // Tutorial Header (Application / Title / Badge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color(0xFF1E2638), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "🧭", fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when (state.selectedApplication) {
                                ApplicationType.BLENDER -> "Blender Tutorial"
                                ApplicationType.PDF -> "PDF Manual Tutorial"
                                ApplicationType.EXCEL -> "Excel Tutorial"
                            },
                            color = Color(0xFFAAB0BD),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when (state.selectedApplication) {
                                ApplicationType.BLENDER -> "Create a Sphere"
                                ApplicationType.PDF -> "Document Analysis"
                                ApplicationType.EXCEL -> "Spreadsheet Entry"
                            },
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Beginner Badge
                Box(
                    modifier = Modifier
                        .background(Color(0xFF064E3B), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF10B981), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "🌱 Beginner",
                        color = Color(0xFF34D399),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Step counter & Segmented Progress Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Step ${step.stepNumber} of ${step.totalSteps}",
                    color = Color(0xFF34D399),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 5-segment Progress Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val total = step.totalSteps.coerceAtLeast(1)
                for (i in 1..total) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(
                                color = if (i <= step.stepNumber) Color(0xFF10B981) else Color(0xFF2A3142),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Preview Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181C26), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF2B3345), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                // Visual Mock Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(Color(0xFF0F1117), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF262C3A), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    // Visual Menu representation matching screenshot
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Left sub-menu
                        Column(
                            modifier = Modifier
                                .width(75.dp)
                                .background(Color(0xFF1A1D24), RoundedCornerShape(4.dp))
                                .padding(4.dp)
                        ) {
                            Text("Add", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(3.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2563EB), RoundedCornerShape(2.dp))
                                    .padding(2.dp)
                            ) {
                                Text("▶ Mesh", color = Color.White, fontSize = 8.sp)
                            }
                            Text("  Curve", color = Color(0xFFAAB0BD), fontSize = 8.sp)
                            Text("  Surface", color = Color(0xFFAAB0BD), fontSize = 8.sp)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Submenu with UV Sphere highlighted
                        Column(
                            modifier = Modifier
                                .width(95.dp)
                                .background(Color(0xFF1A1D24), RoundedCornerShape(4.dp))
                                .padding(4.dp)
                        ) {
                            Text("Plane", color = Color(0xFFAAB0BD), fontSize = 8.sp)
                            Text("Cube", color = Color(0xFFAAB0BD), fontSize = 8.sp)
                            Text("Circle", color = Color(0xFFAAB0BD), fontSize = 8.sp)
                            Spacer(modifier = Modifier.height(1.dp))
                            // Highlighted UV Sphere item
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF064E3B), RoundedCornerShape(3.dp))
                                    .border(1.5.dp, Color(0xFF10B981), RoundedCornerShape(3.dp))
                                    .padding(2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🌐 ", fontSize = 8.sp)
                                    Text(
                                        text = if (step.targetControlName != null) step.targetControlName!! else "UV Sphere",
                                        color = Color.White,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text("Ico Sphere", color = Color(0xFFAAB0BD), fontSize = 8.sp)
                            Text("Cylinder", color = Color(0xFFAAB0BD), fontSize = 8.sp)
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Tooltip callout bubble
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .background(Color(0xFF065F46), RoundedCornerShape(6.dp))
                                .border(1.dp, Color(0xFF34D399), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Click on\n${step.targetControlName ?: "UV Sphere"}",
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Step Title & Description
                Text(
                    text = step.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = step.description,
                    color = Color(0xFFCFD3DA),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Navigation row: [Previous] [Next]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (state.currentStepIndex > 0) Color(0xFF262C3A) else Color(0xFF1C202B),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable(enabled = state.currentStepIndex > 0) {
                                controller.previousStep()
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "← Previous",
                            color = if (state.currentStepIndex > 0) Color.White else Color(0xFF6B7280),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                if (state.currentStepIndex < step.totalSteps - 1) Color(0xFF2563EB) else Color(0xFF1C202B),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable(enabled = state.currentStepIndex < step.totalSteps - 1) {
                                controller.nextStep()
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Next →",
                            color = if (state.currentStepIndex < step.totalSteps - 1) Color.White else Color(0xFF6B7280),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Verification Status Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF042F2E), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF0D9488), RoundedCornerShape(8.dp))
                    .clickable {
                        val exp = step.expectedState
                        if (exp != null) onAutoVerifyBlenderAction(exp)
                    }
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color(0xFF059669), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✔", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Waiting for your action...",
                            color = Color(0xFF34D399),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "The tutor will automatically verify once action is completed in ${state.selectedApplication.displayName}.",
                            color = Color(0xFFA7F3D0),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Need Help? Section
            Text(
                text = "Need Help?",
                color = Color(0xFFAAB0BD),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // I'm Stuck on this Step
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .background(Color(0xFF1E3A8A), RoundedCornerShape(6.dp))
                        .clickable {
                            coroutineScope.launch {
                                controller.resolveDynamicTarget()
                                overlayManager.showOverlay()
                                val diag = controller.diagnoseCurrentStep()
                                controller.requestStuckHelp()
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🆘 I'm Stuck on this Step",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Give me a Hint
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1E2638), RoundedCornerShape(6.dp))
                        .clickable {
                            coroutineScope.launch {
                                controller.resolveDynamicTarget()
                                overlayManager.showOverlay()
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "💡 Give me a Hint",
                        color = Color(0xFFFDE047),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Navigation Tabs: [Chat] | [Explanation]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            color = if (activeTab == 0) Color(0xFF2563EB) else Color(0xFF1E2430),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable { activeTab = 0 }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Chat",
                        color = if (activeTab == 0) Color.White else Color(0xFFAAB0BD),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            color = if (activeTab == 1) Color(0xFF2563EB) else Color(0xFF1E2430),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable { activeTab = 1 }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Explanation",
                        color = if (activeTab == 1) Color.White else Color(0xFFAAB0BD),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Tab Content
            if (activeTab == 0) {
                Box(modifier = Modifier.height(240.dp)) {
                    ChatbotPanel(
                        controller = controller,
                        overlayManager = overlayManager,
                        onAutoVerifyAction = onAutoVerifyBlenderAction,
                        onCaptureScreen = onCapturePreviousWindow
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF181C26), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Step Explanation",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = step.description,
                        color = Color(0xFFCFD3DA),
                        fontSize = 11.sp
                    )
                    if (!step.conceptualHint.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "💡 Conceptual Hint: ${step.conceptualHint}",
                            color = Color(0xFFFDE047),
                            fontSize = 10.sp
                        )
                    }
                    if (!step.reflectionQuestion.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "❓ Reflection: ${step.reflectionQuestion}",
                            color = Color(0xFFA78BFA),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Current Progress Checklist
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181C26), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = "Current Progress",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                val steps = controller.stepsForCurrentApp()
                for (idx in steps.indices) {
                    val st = steps[idx]
                    val isPast = idx < state.currentStepIndex
                    val isCurrent = idx == state.currentStepIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Jump directly to this step
                                while (controller.state.currentStepIndex > idx) controller.previousStep()
                                while (controller.state.currentStepIndex < idx) controller.nextStep()
                            }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(
                                    when {
                                        isPast -> Color(0xFF059669)
                                        isCurrent -> Color(0xFF2563EB)
                                        else -> Color(0xFF282F3E)
                                    },
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isPast || isCurrent) "✔" else "○",
                                color = if (isPast || isCurrent) Color.White else Color(0xFF6B7280),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${idx + 1}. ${st.title}",
                            color = when {
                                isCurrent -> Color.White
                                isPast -> Color(0xFF34D399)
                                else -> Color(0xFF6B7280)
                            },
                            fontSize = 11.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Advanced Overlay & Window Tools Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvancedTools = !showAdvancedTools },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showAdvancedTools) "▾ Hide Overlay & Window Tools" else "▸ Advanced Overlay & Tools",
                    color = Color(0xFFAAB0BD),
                    fontSize = 10.sp
                )
            }

            if (showAdvancedTools) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TutorButton(
                        text = if (overlayManager.isOverlayVisible) "Hide Glow" else "Show Glow",
                        onClick = {
                            if (overlayManager.isOverlayVisible) overlayManager.hideOverlay() else overlayManager.showOverlay()
                        }
                    )
                    TutorButton(
                        text = "Capture Window",
                        onClick = onCapturePreviousWindow
                    )
                    TutorButton(
                        text = "Region",
                        onClick = onOpenRegionSelector
                    )
                }
            }
        }
    }
}
