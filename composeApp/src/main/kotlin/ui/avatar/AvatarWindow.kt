package ui.avatar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
 * - Target-aware dynamic gaze and articulated pointing gestures via [TargetGuidanceCalculator].
 * - Contextual explanations via [SmartChatbotProvider].
 * - Fluid drag-to-reposition across multi-monitor setups with dynamic coordinate tracking.
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
        size = DpSize(440.dp, 260.dp)
    )
) {
    val coroutineScope = rememberCoroutineScope()
    val coordinator = controller.conversationCoordinator

    var isCompact by remember { mutableStateOf(false) }
    var isHighlightBusy by remember { mutableStateOf(false) }
    var highlightFeedback by remember { mutableStateOf<String?>(null) }
    val isExplainBusy = coordinator.isThinking
    val explanationText = coordinator.latestExplanationText
    var isRecoveryActive by remember { mutableStateOf(false) }

    // Dynamic window resizing based on presentation mode and explanation expansion
    val isExplainingActive = isExplainBusy || !explanationText.isNullOrBlank()
    val expandedHeight = if (isExplainingActive) 360.dp else 260.dp
    val expandedSize = DpSize(440.dp, expandedHeight)
    val compactSize = DpSize(190.dp, 60.dp)

    LaunchedEffect(isCompact, isExplainingActive) {
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
        isRecoveryActive = false
    }

    // Determine target presence for pointing
    val activeTargetRegion = overlayManager.activeHighlightRegion
        ?: state.currentHighlight
        ?: step?.targetRegion

    val hasTarget = activeTargetRegion != null &&
        activeTargetRegion.width > 0f &&
        activeTargetRegion.height > 0f

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

    // Dynamic Window Screen Position tracking for multi-monitor / drag coordinate math
    var windowScreenX by remember { mutableStateOf(windowState.position.x.value) }
    var windowScreenY by remember { mutableStateOf(windowState.position.y.value) }
    var avatarVisorLocalOffset by remember { mutableStateOf(Offset(54f, 46f)) }

    // Pillar 2: Procedural Kinematics (Drag inertia tilt tracking)
    var prevWindowX by remember { mutableStateOf(windowState.position.x.value) }
    var rawTiltDegrees by remember { mutableStateOf(0f) }

    LaunchedEffect(windowState.position.x.value) {
        val currentX = windowState.position.x.value
        val deltaX = currentX - prevWindowX
        if (kotlin.math.abs(deltaX) > 1.5f) {
            rawTiltDegrees = (-deltaX * 0.35f).coerceIn(-14f, 14f)
        } else {
            rawTiltDegrees = 0f
        }
        prevWindowX = currentX
        windowScreenX = currentX
    }

    val animatedTiltDegrees by animateFloatAsState(
        targetValue = rawTiltDegrees,
        animationSpec = spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
            stiffness = 240f
        )
    )

    // Desktop display dimensions for docking and screen-edge intelligence
    val screenWidth = remember {
        try {
            java.awt.Toolkit.getDefaultToolkit().screenSize.width.toFloat()
        } catch (_: Exception) {
            1920f
        }
    }
    val screenHeight = remember {
        try {
            java.awt.Toolkit.getDefaultToolkit().screenSize.height.toFloat()
        } catch (_: Exception) {
            1080f
        }
    }

    // Dynamic target docking position calculation
    val currentDockingResult = remember(activeTargetRegion, windowState.size, screenWidth, screenHeight) {
        if (activeTargetRegion != null && activeTargetRegion.width > 0f && activeTargetRegion.height > 0f) {
            TargetDockPositionCalculator.calculateDockPosition(
                target = activeTargetRegion.toScreenRect(),
                screenSize = ScreenPoint(screenWidth, screenHeight),
                windowSize = ScreenPoint(windowState.size.width.value, windowState.size.height.value)
            )
        } else {
            null
        }
    }

    val isAvatarOnRight = currentDockingResult?.isAvatarOnRight ?: ((windowScreenX + 440f) > (screenWidth - 80f))

    // Dynamic flight kinematics across screen to dock beside highlighted target
    var flightTargetPosition by remember { mutableStateOf<ScreenPoint?>(null) }

    // Automatically trigger flight whenever an active target region is updated
    LaunchedEffect(currentDockingResult?.position) {
        val dest = currentDockingResult?.position ?: return@LaunchedEffect
        flightTargetPosition = dest
    }

    LaunchedEffect(flightTargetPosition) {
        val dest = flightTargetPosition ?: return@LaunchedEffect
        val startX = windowState.position.x.value
        val startY = windowState.position.y.value
        val endX = dest.x
        val endY = dest.y

        val dx = endX - startX
        val dy = endY - startY
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        if (dist < 4f) return@LaunchedEffect

        val durationMs = (dist * 0.42f).coerceIn(300f, 650f).toLong()
        val startTime = System.currentTimeMillis()

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            val progress = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
            // Cubic Ease-Out flight profile
            val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            val curX = startX + (endX - startX) * eased
            val curY = startY + (endY - startY) * eased
            windowState.position = WindowPosition(curX.dp, curY.dp)
            windowScreenX = curX
            windowScreenY = curY
            if (progress >= 1f) break
            delay(16)
        }
    }

    // Calculate dynamic anchor point of the robot's visor on the desktop
    val robotVisorScreenPoint = ScreenPoint(
        x = windowScreenX + avatarVisorLocalOffset.x,
        y = windowScreenY + avatarVisorLocalOffset.y
    )

    // Compute target-aware guidance geometry (Phase 3A)
    val guidanceGeometry = remember(robotVisorScreenPoint, activeTargetRegion) {
        if (activeTargetRegion != null && activeTargetRegion.width > 0f && activeTargetRegion.height > 0f) {
            TargetGuidanceCalculator.calculateGuidance(
                anchor = robotVisorScreenPoint,
                targetBounds = activeTargetRegion.toScreenRect()
            )
        } else {
            TargetGuidanceGeometry.neutral(robotVisorScreenPoint)
        }
    }

    // Directional guidance activation guard (Phase 3D)
    val isDirectionalGuidancePermitted = (presentationState == AvatarPresentationState.POINTING ||
        presentationState == AvatarPresentationState.GUIDING ||
        presentationState == AvatarPresentationState.WAITING_FOR_USER) &&
        isBlenderConnected &&
        !isVerificationBusy

    val guidanceTarget = if (guidanceGeometry.isGuidanceUsable && isDirectionalGuidancePermitted) {
        AvatarGuidanceTarget(
            gazeOffsetX = guidanceGeometry.gazeOffset.offsetX,
            gazeOffsetY = guidanceGeometry.gazeOffset.offsetY,
            pointingAngleRadians = guidanceGeometry.pointingGesture?.directionAngleRadians,
            isPointingActive = presentationState == AvatarPresentationState.POINTING || (hasTarget && presentationState == AvatarPresentationState.GUIDING),
            isPointingRight = guidanceGeometry.pointingGesture?.isPointingRight ?: true,
            distance = guidanceGeometry.distance
        )
    } else {
        null
    }

    // Dynamic global desktop coordinates for the robot emitter
    val isPointingRight = guidanceTarget?.isPointingRight ?: (guidanceGeometry.pointingGesture?.isPointingRight ?: true)
    val isPointingActive = guidanceTarget?.isPointingActive == true

    val handOffsetX = if (isPointingRight) 38f else -38f
    val handOffsetY = 10f

    val emitterScreenPoint = if (isPointingActive) {
        ScreenPoint(
            x = windowScreenX + avatarVisorLocalOffset.x + handOffsetX,
            y = windowScreenY + avatarVisorLocalOffset.y + handOffsetY
        )
    } else {
        ScreenPoint(
            x = windowScreenX + avatarVisorLocalOffset.x,
            y = windowScreenY + avatarVisorLocalOffset.y
        )
    }

    val effectivePointingActive = isPointingActive && visible

    LaunchedEffect(emitterScreenPoint, effectivePointingActive, presentationState, visible) {
        overlayManager.updateAvatarGuidance(
            emitterPoint = if (visible) emitterScreenPoint else null,
            isPointing = effectivePointingActive,
            state = presentationState
        )
    }

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
        // Track native window screen position for dynamic guidance calculations
        LaunchedEffect(window) {
            val listener = object : java.awt.event.ComponentAdapter() {
                override fun componentMoved(e: java.awt.event.ComponentEvent) {
                    windowScreenX = window.x.toFloat()
                    windowScreenY = window.y.toFloat()
                }
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    windowScreenX = window.x.toFloat()
                    windowScreenY = window.y.toFloat()
                }
            }
            window.addComponentListener(listener)
            windowScreenX = window.x.toFloat()
            windowScreenY = window.y.toFloat()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            if (isCompact) {
                // ==========================================
                // COMPACT DOCK PRESENTATION (Reference Matched)
                // ==========================================
                WindowDraggableArea {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(3.dp)
                            .background(
                                color = Color(0xF21E2127),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = Color(0xFF2D323B),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable { isCompact = false }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AvatarView(
                                    state = AvatarPresentationState.COMPACT,
                                    size = 32.dp,
                                    guidanceTarget = guidanceTarget,
                                    modifier = Modifier.onGloballyPositioned { layoutCoordinates ->
                                        val pos = layoutCoordinates.positionInWindow()
                                        val sz = layoutCoordinates.size
                                        avatarVisorLocalOffset = Offset(
                                            pos.x + sz.width / 2f,
                                            pos.y + sz.height * 0.42f
                                        )
                                    }
                                )
                                Column {
                                    Text(
                                        text = "AI Visual Tutor",
                                        color = Color(0xFFF1F5F9),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = if (isBlenderConnected) "● Connected" else "● Disconnected",
                                        color = if (isBlenderConnected) Color(0xFF22C55E) else Color(0xFFEF4444),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            // Reopen affordance
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(Color(0xFF282F38), CircleShape)
                                    .clickable { isCompact = false },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "▲",
                                    color = Color(0xFF8A94A6),
                                    fontSize = 9.sp
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
                        val avatarBlock: @Composable () -> Unit = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AvatarView(
                                    state = presentationState,
                                    size = 96.dp,
                                    guidanceTarget = guidanceTarget,
                                    tiltDegrees = animatedTiltDegrees,
                                    modifier = Modifier.onGloballyPositioned { layoutCoordinates ->
                                        val pos = layoutCoordinates.positionInWindow()
                                        val sz = layoutCoordinates.size
                                        avatarVisorLocalOffset = Offset(
                                            pos.x + sz.width / 2f,
                                            pos.y + sz.height * 0.42f
                                        )
                                    }
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
                        }

                        val bubbleBlock: @Composable () -> Unit = {
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
                                    isTailOnRight = isAvatarOnRight,
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
                                                val targetRegion = result.region ?: step?.targetRegion
                                                if (targetRegion != null) {
                                                    controller.setHighlight(targetRegion)
                                                    overlayManager.showOverlay(targetRegion)
                                                    val dock = TargetDockPositionCalculator.calculateDockPosition(
                                                        target = targetRegion.toScreenRect(),
                                                        screenSize = ScreenPoint(screenWidth, screenHeight),
                                                        windowSize = ScreenPoint(windowState.size.width.value, windowState.size.height.value)
                                                    )
                                                    flightTargetPosition = dock.position
                                                    highlightFeedback = "✓ Target highlighted on screen"
                                                } else {
                                                    highlightFeedback = "Notice: Target location approximate"
                                                    val fallbackRegion = step?.targetRegion
                                                    if (fallbackRegion != null) {
                                                        controller.setHighlight(fallbackRegion)
                                                        overlayManager.showOverlay(fallbackRegion)
                                                        val dock = TargetDockPositionCalculator.calculateDockPosition(
                                                            target = fallbackRegion.toScreenRect(),
                                                            screenSize = ScreenPoint(screenWidth, screenHeight),
                                                            windowSize = ScreenPoint(windowState.size.width.value, windowState.size.height.value)
                                                        )
                                                        flightTargetPosition = dock.position
                                                    }
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
                                        coordinator.explainCurrentStep()
                                    },
                                    onToggleWorkspace = onToggleWorkspace,
                                    isWorkspaceOpen = isWorkspaceOpen,
                                    onDismissExplanation = { coordinator.clearActiveExplanation() },
                                    onStartTutorialClick = {
                                        controller.goToStep(0)
                                    }
                                )
                            }
                        }

                        if (isAvatarOnRight) {
                            bubbleBlock()
                            avatarBlock()
                        } else {
                            avatarBlock()
                            bubbleBlock()
                        }
                    }
                }
            }
        }
    }
}
