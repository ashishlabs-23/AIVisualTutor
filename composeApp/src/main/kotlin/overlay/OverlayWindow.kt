package overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

    LaunchedEffect(isBlenderNotRunning) {
        if (isBlenderNotRunning) {
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
        // Guarantee the native AWT/Swing surface has transparent alpha channel from the first frame
        LaunchedEffect(window) {
            try {
                window.background = java.awt.Color(0, 0, 0, 0)
                window.contentPane.background = java.awt.Color(0, 0, 0, 0)
                (window.contentPane as? javax.swing.JComponent)?.isOpaque = false
                window.rootPane.isOpaque = false
                window.rootPane.background = java.awt.Color(0, 0, 0, 0)
            } catch (_: Exception) {}
        }

        LaunchedEffect(visible) {
            if (!visible) return@LaunchedEffect
            try {
                // Allow Compose/Skiko one initial frame to present its clear buffer
                delay(150)
                val success = withContext(Dispatchers.IO) {
                    enableWindowsClickThrough()
                }
                if (!success) {
                    System.err.println("Notice: Overlay active without native click-through.")
                }
            } catch (exception: Exception) {
                System.err.println("Notice: Overlay initialization fallback: ${exception.message}")
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
            screenOffsetX = screenBounds.x.toFloat(),
            screenOffsetY = screenBounds.y.toFloat()
        )
    }

    val closeControlSize = DpSize(170.dp, 72.dp)
    val closeControlState = rememberWindowState(
        position = WindowPosition(
            x = (screenBounds.x + screenBounds.width).dp - closeControlSize.width - 8.dp,
            y = screenBounds.y.dp + 8.dp
        ),
        size = closeControlSize
    )
    Window(
        onCloseRequest = onCloseRequest,
        state = closeControlState,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        visible = visible && !isBlenderNotRunning,
        focusable = false,
        title = "AI Tutor Overlay Close"
    ) {
        LaunchedEffect(window) {
            try {
                window.background = java.awt.Color(0, 0, 0, 0)
                window.contentPane.background = java.awt.Color(0, 0, 0, 0)
                (window.contentPane as? javax.swing.JComponent)?.isOpaque = false
                window.rootPane.isOpaque = false
                window.rootPane.background = java.awt.Color(0, 0, 0, 0)
            } catch (_: Exception) {}
        }
        OverlayCloseControl(onCloseRequest)
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
    screenOffsetX: Float = 0f,
    screenOffsetY: Float = 0f
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
        // APPLICATION AWARENESS HUD BANNERS
        // =====================================================================
        if (isBlenderTarget && !isBlenderRunning) {
            // Case 1: Blender is not running
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
                        text = "⚠️ Blender is Not Running",
                        color = Color(0xFFF87171),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "• Launch Blender to display visual step guidance over your 3D workspace",
                        color = Color(0xFFFCA5A5),
                        fontSize = 11.sp
                    )
                }
            }
            return@Box
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
            return@Box
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
        val rawRegion = activeHighlightRegion
            ?: tutorState.currentHighlight
            ?: tutorState.adaptiveInstruction?.targetRegion

        if (tutorState.screenAssistanceEnabled && !tutorState.isPaused && rawRegion != null) {
            // If Blender window bounds are known and the region coordinates are window-relative,
            // offset the region by Blender's actual window location on the desktop.
            val windowOffset = blenderInfo?.bounds
            val positionedRegion = if (windowOffset != null && rawRegion.x < windowOffset.width && rawRegion.y < windowOffset.height) {
                HighlightRegion(
                    x = windowOffset.x.toFloat() + rawRegion.x,
                    y = windowOffset.y.toFloat() + rawRegion.y,
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
                ?: currentStep?.title
                ?: "Step ${tutorState.currentStepIndex + 1}"
            val calloutInstruction = stepInstruction
                ?: tutorState.adaptiveInstruction?.instructionText
                ?: currentStep?.instruction

            val calloutColor = Color(0xFFFACC15)
            Highlight(region = adjustedRegion, color = calloutColor)
            Arrow(
                region = adjustedRegion,
                label = calloutTitle,
                instruction = calloutInstruction,
                color = calloutColor
            )
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
    }
}

@Composable
private fun OverlayCloseControl(onCloseRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Text(
            text = "✕ Close highlight",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color(0xE61E2333), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0x66FACC15), RoundedCornerShape(8.dp))
                .clickable(onClick = onCloseRequest)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/**
 * Applies WS_EX_TRANSPARENT on the overlay HWND so mouse events pass through to Blender.
 *
 * CRITICAL FIX: We deliberately do NOT set WS_EX_LAYERED (0x80000).
 * Compose Desktop uses a modern DirectComposition DirectX swapchain for transparency.
 * Forcing WS_EX_LAYERED breaks DirectComposition and causes Windows DWM to paint
 * the uninitialized layered buffer as solid black!
 * Applying WS_EX_TRANSPARENT (0x20) alone preserves hardware-accelerated transparency
 * while allowing mouse clicks to fall through directly to Blender.
 */
fun enableWindowsClickThrough(): Boolean {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        return false
    }

    val nativeSource = """
        using System;
        using System.Runtime.InteropServices;
        using System.Text;
        public static class TutorOverlayNativeStyle {
            public delegate bool EnumWindowsProc(IntPtr window, IntPtr parameter);
            [DllImport("user32.dll")]
            public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);
            [DllImport("user32.dll", CharSet = CharSet.Unicode)]
            public static extern int GetWindowText(IntPtr window, StringBuilder text, int maxCount);
            [DllImport("user32.dll")]
            public static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
            [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
            public static extern IntPtr GetWindowLongPtr(IntPtr window, int index);
            [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
            public static extern IntPtr SetWindowLongPtr(IntPtr window, int index, IntPtr value);
            [DllImport("user32.dll", SetLastError = true)]
            public static extern bool SetWindowPos(IntPtr window, IntPtr insertAfter, int x, int y, int width, int height, uint flags);
            public static bool EnableClickThrough(uint targetProcessId) {
                IntPtr overlayWindow = IntPtr.Zero;
                IntPtr closeControlWindow = IntPtr.Zero;
                // Ensure both WS_EX_LAYERED (0x80000) and WS_EX_TRANSPARENT (0x20) are set.
                // In Windows DWM, WS_EX_TRANSPARENT requires WS_EX_LAYERED to pass clicks through to background apps.
                const long WsExLayered = 0x80000L;
                const long WsExTransparent = 0x20L;
                EnumWindows((window, _) => {
                    uint processId;
                    GetWindowThreadProcessId(window, out processId);
                    if (processId == targetProcessId) {
                        var title = new StringBuilder(256);
                        GetWindowText(window, title, title.Capacity);
                        if (title.ToString() == "AI Tutor Overlay") {
                            long style = GetWindowLongPtr(window, -20).ToInt64();
                            SetWindowLongPtr(window, -20, new IntPtr(style | WsExLayered | WsExTransparent));
                            long applied = GetWindowLongPtr(window, -20).ToInt64();
                            bool clickThrough = (applied & WsExTransparent) == WsExTransparent;
                            // Refresh z-order and frame without changing size/position.
                            bool refreshed = SetWindowPos(window, new IntPtr(-1), 0, 0, 0, 0, 0x0033);
                            if (clickThrough && refreshed) overlayWindow = window;
                        } else if (title.ToString() == "AI Tutor Overlay Close") {
                            closeControlWindow = window;
                        }
                    }
                    return true;
                }, IntPtr.Zero);
                if (overlayWindow == IntPtr.Zero || closeControlWindow == IntPtr.Zero) return false;
                // Keep the close-control always-on-top; no click-through needed.
                return SetWindowPos(
                    closeControlWindow,
                    new IntPtr(-1),
                    0,
                    0,
                    0,
                    0,
                    0x0013);
            }
        }
    """.trimIndent()
    val encodedSource = Base64.getEncoder().encodeToString(
        nativeSource.toByteArray(StandardCharsets.UTF_8)
    )
    val script = """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}source = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encodedSource'))
        Add-Type -TypeDefinition ${'$'}source
        ${'$'}processId = [uint32]${ProcessHandle.current().pid()}
        if (-not [TutorOverlayNativeStyle]::EnableClickThrough(${'$'}processId)) {
            exit 1
        }
    """.trimIndent()
    return try {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            script
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        exitCode == 0
    } catch (ex: Exception) {
        System.err.println("Warning: Native overlay click-through execution failed: ${ex.message}")
        false
    }
}
