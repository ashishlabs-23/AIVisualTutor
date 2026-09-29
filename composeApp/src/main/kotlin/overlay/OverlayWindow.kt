package overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import models.TutorState
import ui.Arrow
import ui.Highlight
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * draw the highlight rectangle and arrow/callout over whatever the user
 * is working in (Blender / Excel / a PDF viewer).
 *
 * Transparency mechanism:
 *   Compose Desktop on Windows creates a native layered window when
 *   [transparent] = true.  Skiko renders with per-pixel alpha (alpha=0
 *   where nothing is drawn, alpha>0 for tutor graphics).  Windows then
 *   composites this surface over whatever is beneath it, so the underlying
 *   application shows through all un-drawn pixels naturally.
 *
 *   NO color-key / SetLayeredWindowAttributes is used.  The window already
 *   has WS_EX_LAYERED set by Compose; we only add WS_EX_TRANSPARENT so that
 *   mouse events fall through to the window below.
 *
 * The separate close-control window remains interactive (no WS_EX_TRANSPARENT).
 */
@Composable
fun OverlayWindow(
    tutorState: TutorState,
    visible: Boolean = true,
    onCloseRequest: () -> Unit
) {
    val density = LocalDensity.current
    val screenBounds = remember { getVirtualScreenBounds() }
    val windowPosition = with(density) {
        WindowPosition(x = screenBounds.x.toDp(), y = screenBounds.y.toDp())
    }
    val windowSize = with(density) {
        DpSize(screenBounds.width.toDp(), screenBounds.height.toDp())
    }

    val windowState = rememberWindowState(
        position = windowPosition,
        size = windowSize
    )

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
        LaunchedEffect(visible) {
            if (!visible) return@LaunchedEffect
            try {
                // Allow Compose/Skiko one frame to paint before we touch the
                // native window styles, so the HWND already exists.
                delay(100)
                withContext(Dispatchers.IO) {
                    enableWindowsClickThrough()
                }
            } catch (exception: Exception) {
                onCloseRequest()
                throw IllegalStateException(
                    "Could not enable click-through for the tutor overlay; overlay was closed.",
                    exception
                )
            }
        }
        OverlayWindowContent(
            tutorState = tutorState,
            screenOffsetX = screenBounds.x.toFloat(),
            screenOffsetY = screenBounds.y.toFloat()
        )
    }

    val closeControlSize = DpSize(170.dp, 72.dp)
    val closeControlState = rememberWindowState(
        position = WindowPosition(
            x = with(density) { (screenBounds.x + screenBounds.width).toDp() } - closeControlSize.width - 8.dp,
            y = with(density) { screenBounds.y.toDp() } + 8.dp
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
        visible = visible,
        focusable = false,
        title = "AI Tutor Overlay Close"
    ) {
        OverlayCloseControl(onCloseRequest)
    }
}

/**
 * The actual overlay drawing surface, kept separate from [OverlayWindow]
 * so it can be previewed/tested without needing a real OS window.
 *
 * There is intentionally NO fullscreen background color here.  The Compose
 * window is already [transparent]=true, so every pixel that is not painted
 * by a child composable has alpha=0 and the desktop/application beneath
 * shows through it directly.
 */
@Composable
fun OverlayWindowContent(
    tutorState: TutorState,
    screenOffsetX: Float = 0f,
    screenOffsetY: Float = 0f
) {
    Box(modifier = Modifier.fillMaxSize()) {
        val region = tutorState.currentHighlight
        if (tutorState.screenAssistanceEnabled && !tutorState.isPaused && region != null) {
            val adjustedRegion = if (screenOffsetX != 0f || screenOffsetY != 0f) {
                region.copy(
                    x = region.x - screenOffsetX,
                    y = region.y - screenOffsetY
                )
            } else {
                region
            }
            Highlight(region = adjustedRegion)
            Arrow(region = adjustedRegion, label = "Step ${tutorState.currentStepIndex + 1}")
        }
    }
}

@Composable
private fun OverlayCloseControl(onCloseRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Close highlight",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color(0xE623262E), RoundedCornerShape(8.dp))
                .clickable(onClick = onCloseRequest)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/**
 * Sets WS_EX_TRANSPARENT | WS_EX_LAYERED on the overlay HWND so that
 * ordinary mouse events fall through to the window beneath.
 *
 * Compose Desktop already sets WS_EX_LAYERED when [transparent]=true, so
 * this function only ADDS WS_EX_TRANSPARENT on top of the existing styles.
 * It deliberately does NOT call SetLayeredWindowAttributes, which would
 * switch the window from per-pixel-alpha mode (used by Skiko) to color-key
 * mode and destroy the Skiko-managed per-pixel transparency.
 *
 * The close-control window is intentionally excluded (no WS_EX_TRANSPARENT)
 * so that clicks on the "Close highlight" button are still received.
 */
private fun enableWindowsClickThrough() {
    check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "Native overlay click-through is only available on Windows."
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
                // WS_EX_LAYERED (0x80000) is already set by Compose Desktop for transparent=true.
                // We add WS_EX_TRANSPARENT (0x20) so mouse input falls through to the app below.
                // We deliberately do NOT call SetLayeredWindowAttributes because that would
                // switch the window from per-pixel-alpha mode (Skiko) to color-key mode,
                // which would destroy the Compose/Skiko per-pixel transparency.
                const long WsExLayered     = 0x80000L;
                const long WsExTransparent = 0x20L;
                const long clickThroughStyles = WsExLayered | WsExTransparent;
                EnumWindows((window, _) => {
                    uint processId;
                    GetWindowThreadProcessId(window, out processId);
                    if (processId == targetProcessId) {
                        var title = new StringBuilder(256);
                        GetWindowText(window, title, title.Capacity);
                        if (title.ToString() == "AI Tutor Overlay") {
                            long style = GetWindowLongPtr(window, -20).ToInt64();
                            SetWindowLongPtr(window, -20, new IntPtr(style | clickThroughStyles));
                            long applied = GetWindowLongPtr(window, -20).ToInt64();
                            bool clickThrough = (applied & clickThroughStyles) == clickThroughStyles;
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
            throw 'Could not locate the native AI Tutor Overlay window or initialize transparent layered click-through.'
        }
    """.trimIndent()
    val process = ProcessBuilder(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        script
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    check(exitCode == 0) {
        "Windows did not apply the overlay click-through style (exit $exitCode): $output"
    }
}
