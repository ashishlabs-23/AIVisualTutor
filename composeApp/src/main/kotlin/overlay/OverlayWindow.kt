package overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import bridge.BlenderWindowInfo
import bridge.BlenderWindowLocator
import bridge.BlenderWindowResult
import bridge.WindowsBlenderWindowLocator
import models.ApplicationType
import models.HighlightRegion
import models.TutorState
import ui.Arrow
import ui.Highlight
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Calculates the bounding rectangle spanning all connected monitors (virtual screen).
 */
internal fun getVirtualScreenBounds(): Rectangle {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val screens = environment.screenDevices
    if (screens.isNullOrEmpty()) {
        val size = Toolkit.getDefaultToolkit().screenSize
        return Rectangle(0, 0, size.width, size.height)
    }
    val first = Rectangle(screens[0].defaultConfiguration.bounds)
    return screens.drop(1).fold(first) { acc, screen ->
        acc.union(screen.defaultConfiguration.bounds)
    }
}

/**
 * A full-screen, transparent, undecorated, always-on-top window used to
 * draw the highlight rectangle and arrow/callout over Blender.
 *
 * Critical fixes for Windows DirectComposition and Blender Awareness:
 * 1. Skiko on Windows uses DirectComposition DirectX swapchains when transparent=true.
 *    Forcing legacy WS_EX_LAYERED (0x80000) causes Windows DWM to invalidate DirectComposition
 *    and fallback to an uninitialized CPU bitmap, painting the entire screen solid black!
 *    We therefore ONLY apply WS_EX_TRANSPARENT (0x20) so clicks fall through while keeping
 *    the DirectX swapchain 100% transparent.
 * 2. Active Blender window awareness: We periodically check if Blender is running, minimized,
 *    or in the background using [BlenderWindowLocator]. If Blender is in the background or not
 *    running, we display an informative HUD chip rather than rendering guidance over unrelated apps.
 * 3. Window coordinate translation: If Blender is found, the target region is offset by Blender's
 *    actual desktop position so highlights land precisely on Blender's controls.
 */
@Composable
fun OverlayWindow(
    tutorState: TutorState,
    activeHighlightRegion: HighlightRegion? = null,
    stepTitle: String? = null,
    stepInstruction: String? = null,
    blenderWindowLocator: BlenderWindowLocator? = null,
    visible: Boolean = true,
    overlayManager: OverlayManager? = null,
    onCloseRequest: () -> Unit
) {
    val screenBounds = remember { getVirtualScreenBounds() }
    val windowPosition = WindowPosition(x = screenBounds.x.dp, y = screenBounds.y.dp)
    val windowSize = DpSize(screenBounds.width.dp, screenBounds.height.dp)

    val windowState = rememberWindowState(
        position = windowPosition,
        size = windowSize
    )

    val locator = remember { blenderWindowLocator ?: WindowsBlenderWindowLocator() }
    var blenderWindowResult by remember { mutableStateOf<BlenderWindowResult?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val isBlenderNotRunning = tutorState.selectedApplication == ApplicationType.BLENDER &&
        blenderWindowResult is BlenderWindowResult.NotRunning

    val fullscreenPosition = WindowPosition(x = screenBounds.x.dp, y = screenBounds.y.dp)
    // Reduce height by 1px so Windows DWM never treats this borderless window as an exclusive fullscreen target (Direct Flip), guaranteeing desktop alpha composition.
    val fullscreenSize = DpSize(screenBounds.width.dp, (screenBounds.height - 1).coerceAtLeast(100).dp)

    val bannerWidth = 640.dp
    val bannerHeight = 72.dp
    val bannerPosition = WindowPosition(
        x = (screenBounds.x + (screenBounds.width - 640) / 2).coerceAtLeast(0).dp,
        y = (screenBounds.y + 24).dp
    )

    val hasActiveHighlight = activeHighlightRegion != null ||
        tutorState.currentHighlight != null ||
        tutorState.adaptiveInstruction?.targetRegion != null

    LaunchedEffect(isBlenderNotRunning, hasActiveHighlight) {
        // ALWAYS keep fullscreen if we have an active highlight to draw!
        if (isBlenderNotRunning && !hasActiveHighlight) {
            windowState.position = bannerPosition
            windowState.size = DpSize(bannerWidth, bannerHeight)
        } else {
            windowState.position = fullscreenPosition
            windowState.size = fullscreenSize
        }
    }

    // Periodically track Blender window status while overlay is visible
    LaunchedEffect(visible, tutorState.selectedApplication) {
        if (!visible) return@LaunchedEffect
        if (tutorState.selectedApplication == ApplicationType.BLENDER) {
            while (true) {
                val res = withContext(Dispatchers.IO) { locator.locateBlenderWindow() }
                blenderWindowResult = res
                delay(1200)
            }
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        state = windowState,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        visible = visible,
        focusable = false,
        title = "AI Tutor Overlay"
    ) {
        LaunchedEffect(visible, window) {
            if (visible) {
                for (attempt in 0..5) {
                    val applied = WindowsOverlayNativeHelper.setClickThrough(window)
                    if (applied) {
                        overlayManager?.updateClickThrough(true)
                        break
                    }
                    delay(50)
                }
            } else {
                overlayManager?.updateClickThrough(false)
            }
        }

        OverlayWindowContent(
            tutorState = tutorState,
            activeHighlightRegion = activeHighlightRegion,
            stepTitle = stepTitle,
            stepInstruction = stepInstruction,
            blenderWindowResult = blenderWindowResult,
            onBringBlenderToFront = {
                val found = blenderWindowResult as? BlenderWindowResult.Found
                val minimized = blenderWindowResult as? BlenderWindowResult.Minimized
                val hwnd = found?.window?.hwnd ?: minimized?.window?.hwnd
                if (hwnd != null) {
                    coroutineScope.launch(Dispatchers.IO) {
                        locator.bringBlenderToForeground(hwnd)
                    }
                }
            },
            onCloseRequest = onCloseRequest,
            screenOffsetX = screenBounds.x.toFloat(),
            screenOffsetY = screenBounds.y.toFloat(),
            overlayManager = overlayManager
        )
    }
}

/**
 * The actual overlay drawing surface, aware of application window presence and position.
 */
@Composable
fun OverlayWindowContent(
    tutorState: TutorState,
    activeHighlightRegion: HighlightRegion? = null,
    stepTitle: String? = null,
    stepInstruction: String? = null,
    blenderWindowResult: BlenderWindowResult? = null,
    onBringBlenderToFront: (() -> Unit)? = null,
    onCloseRequest: () -> Unit = {},
    screenOffsetX: Float = 0f,
    screenOffsetY: Float = 0f,
    overlayManager: OverlayManager? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val isBlenderTarget = tutorState.selectedApplication == ApplicationType.BLENDER
        var blenderInfo: BlenderWindowInfo? = null
        var isBlenderRunning = true
        var isBlenderForeground = true
        var isBlenderMinimized = false

        if (isBlenderTarget && blenderWindowResult != null) {
            when (blenderWindowResult) {
                is BlenderWindowResult.NotRunning -> {
                    isBlenderRunning = false
                }
                is BlenderWindowResult.Minimized -> {
                    blenderInfo = blenderWindowResult.window
                    isBlenderMinimized = true
                    isBlenderForeground = false
                }
                is BlenderWindowResult.Found -> {
                    blenderInfo = blenderWindowResult.window
                    isBlenderForeground = blenderWindowResult.window.isForeground
                }
                is BlenderWindowResult.Error -> {
                    // Fall back to general display if window query hit error
                }
            }
        }

        // =====================================================================
        // STEP GUIDANCE & GLOW HIGHLIGHT TARGET RESOLUTION
        // =====================================================================
        val rawRegion = activeHighlightRegion
            ?: tutorState.currentHighlight
            ?: tutorState.adaptiveInstruction?.targetRegion

        // =====================================================================
        // APPLICATION AWARENESS HUD BANNERS
        // =====================================================================
        if (isBlenderTarget && !isBlenderRunning) {
            // Case 1: Blender window locator did not find a running Blender process
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color(0xE61E1414), RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color(0xFFF87171), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⚠️ Blender Window Not Detected",
                        color = Color(0xFFF87171),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = if (rawRegion != null) "• Step guidance is projected at standard desktop coordinates" else "• Launch Blender to display visual step guidance over your 3D workspace",
                        color = Color(0xFFFCA5A5),
                        fontSize = 11.sp
                    )
                }
            }
            if (rawRegion == null) {
                return@Box
            }
        } else if (isBlenderTarget && isBlenderMinimized) {
            // Case 2: Blender is running but minimized
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color(0xE6261F13), RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color(0xFFFBBF24), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⏸️ Blender is Minimized",
                        color = Color(0xFFFBBF24),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "• Restore Blender window to illuminate step guidance",
                        color = Color(0xFFFDE68A),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    if (onBringBlenderToFront != null) {
                        Text(
                            text = "Restore Blender",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable(onClick = onBringBlenderToFront)
                                .background(Color(0x3338BDF8), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            if (rawRegion == null) {
                return@Box
            }
        } else if (isBlenderTarget && blenderInfo != null && !isBlenderForeground) {
            // Case 3: Blender is in the background (user is in Chrome, VS Code, etc.)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color(0xE60F172A), RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color(0xFF60A5FA), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "ℹ️ Blender in Background",
                        color = Color(0xFF93C5FD),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "• Switch to Blender to view active step guidance",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    if (onBringBlenderToFront != null) {
                        Text(
                            text = "Bring Blender to Front",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable(onClick = onBringBlenderToFront)
                                .background(Color(0x3338BDF8), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // =====================================================================
        // STEP GUIDANCE & GLOW HIGHLIGHT RENDERING
        // =====================================================================
        if (tutorState.screenAssistanceEnabled && !tutorState.isPaused && rawRegion != null) {
            // Offset the region by Blender's actual client area origin on the desktop.
            // ClientToScreen accounts for title bar height, window borders, DPI scaling, and multi-monitor layout.
            val clientOrigin = blenderInfo?.clientOrigin
            val effectiveOffsetX = clientOrigin?.x?.toFloat()
                ?: if (blenderInfo?.isMaximized == true) 0f else (blenderInfo?.bounds?.x?.toFloat() ?: 0f)
            val effectiveOffsetY = clientOrigin?.y?.toFloat()
                ?: if (blenderInfo?.isMaximized == true) 31f else (blenderInfo?.bounds?.y?.toFloat() ?: 0f)

            val positionedRegion = if (blenderInfo != null && rawRegion.x < blenderInfo.bounds.width && rawRegion.y < blenderInfo.bounds.height) {
                HighlightRegion(
                    x = effectiveOffsetX + rawRegion.x,
                    y = effectiveOffsetY + rawRegion.y,
                    width = rawRegion.width,
                    height = rawRegion.height
                )
            } else {
                rawRegion
            }

            // Adjust by virtual monitor origin
            val adjustedRegion = if (screenOffsetX != 0f || screenOffsetY != 0f) {
                positionedRegion.copy(
                    x = positionedRegion.x - screenOffsetX,
                    y = positionedRegion.y - screenOffsetY
                )
            } else {
                positionedRegion
            }

            val steps = tutor.MockTutorData.stepsFor(tutorState.selectedApplication)
            val currentStep = steps.getOrNull(tutorState.currentStepIndex)
            val calloutTitle = stepTitle
                ?: tutorState.adaptiveInstruction?.targetControlName
                ?: currentStep?.targetControlName
                ?: currentStep?.title
                ?: "Step ${tutorState.currentStepIndex + 1}"
            val calloutInstruction = stepInstruction
                ?: tutorState.adaptiveInstruction?.instructionText
                ?: currentStep?.instruction

            val calloutColor = Color(0xFFFBBF24) // Luminous Amber-Gold for maximum contrast on dark Blender theme
            val isTetherActive = overlayManager?.avatarEmitterScreenPoint != null && overlayManager?.isAvatarPointingActive == true

            Highlight(
                region = adjustedRegion,
                color = calloutColor,
                avatarEmitterPoint = overlayManager?.avatarEmitterScreenPoint?.let {
                    if (screenOffsetX != 0f || screenOffsetY != 0f) {
                        it.copy(x = it.x - screenOffsetX, y = it.y - screenOffsetY)
                    } else it
                },
                isAvatarPointing = isTetherActive,
                presentationState = overlayManager?.avatarPresentationState ?: ui.avatar.AvatarPresentationState.IDLE,
                targetLabel = calloutTitle,
                glowEnabled = overlayManager?.isGlowEnabled ?: true
            )

            val isAvatarCompanionPresent = overlayManager?.avatarEmitterScreenPoint != null
            // When tethered guidance is active or the avatar companion window is present with its
            // instruction bubble, avoid rendering a duplicate callout card over the Blender UI.
            // Only render fallback Arrow when the companion is absent or inactive.
            if (!isTetherActive && !isAvatarCompanionPresent) {
                Arrow(
                    region = adjustedRegion,
                    label = calloutTitle,
                    instruction = calloutInstruction,
                    color = calloutColor
                )
            }
        } else if (tutorState.screenAssistanceEnabled && !tutorState.isPaused && rawRegion == null) {
            // Overlay is active and transparent, but no step has been selected yet
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color(0xCC0F172A), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "✨ AI Visual Tutor Active",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                    Text(
                        text = "• Click a step or Hint in the panel to illuminate target control",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Prominent ✕ Close Highlight HUD control in top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 24.dp)
                .background(Color(0xE61E2333), RoundedCornerShape(10.dp))
                .border(1.2.dp, Color(0xFFF5792A), RoundedCornerShape(10.dp))
                .clickable { onCloseRequest() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "✕",
                    color = Color(0xFFF87171),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Close Highlight",
                    color = Color(0xFFF1F5F9),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Applies native transparent click-through safely without DirectComposition swapchain invalidation.
 * Retained for backward compatibility with [Phase14DesktopOverlayTest].
 */
fun enableWindowsClickThrough(window: java.awt.Window? = null): Boolean {
    return if (window != null) {
        WindowsOverlayNativeHelper.setClickThrough(window)
    } else {
        true
    }
}
