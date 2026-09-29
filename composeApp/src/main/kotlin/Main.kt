import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import bridge.CaptureBridge
import bridge.GlobalCaptureHotkey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import overlay.OverlayManager
import overlay.OverlayWindow
import selection.FrozenScreenSnapshot
import selection.RegionSelectorWindow
import selection.captureFrozenScreenSnapshot
import tutor.TutorController
import ui.ScreenshotPreviewWindow
import ui.TutorDock
import ui.TutorPanel
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Application entry point.
 *
 * This wires together three independent windows:
 *  1. The main tutor panel, which can be collapsed to a small dock.
 *  2. The transparent overlay window (highlight + arrow), shown on demand.
 *  3. The manual region-selection window, shown on demand.
 *
 * All actual state lives in [TutorController] and [OverlayManager] - this
 * file only decides which windows are visible and how big the main
 * window should be.
 */
fun main() = application {
    val controller = remember { TutorController() }
    val overlayManager = remember { OverlayManager() }
    val previewScope = rememberCoroutineScope()
    val globalCaptureHotkey = remember { GlobalCaptureHotkey() }

    // Start with the full panel so the first-run UI is immediately visible.
    var isPanelExpanded by remember { mutableStateOf(true) }
    var isRegionSelectorOpen by remember { mutableStateOf(false) }
    var isTutorWindowsVisible by remember { mutableStateOf(true) }
    var selectorSnapshot by remember { mutableStateOf<FrozenScreenSnapshot?>(null) }
    var restoreTutorWindowsVisible by remember { mutableStateOf(true) }
    var previewPngPath by remember { mutableStateOf<String?>(null) }
    var captureStatus by remember { mutableStateOf<String?>(null) }
    var isCaptureInProgress by remember { mutableStateOf(false) }

    suspend fun openRegionSelector() {
        if (isRegionSelectorOpen || selectorSnapshot != null) return

        val previousVisibility = isTutorWindowsVisible
        restoreTutorWindowsVisible = previousVisibility
        isTutorWindowsVisible = false
        try {
            delay(200)
            val snapshot = withContext(Dispatchers.IO) {
                captureFrozenScreenSnapshot()
            }
            selectorSnapshot = snapshot
            isRegionSelectorOpen = true
        } catch (exception: Exception) {
            selectorSnapshot = null
            isRegionSelectorOpen = false
            isTutorWindowsVisible = previousVisibility
            captureStatus = "Could not snapshot the screen for region selection: ${exception.message}"
        }
    }

    fun restoreAfterRegionSelection() {
        selectorSnapshot?.image?.flush()
        selectorSnapshot = null
        isRegionSelectorOpen = false
        isTutorWindowsVisible = restoreTutorWindowsVisible
    }

    suspend fun capturePreviousWindow() {
        if (previewPngPath != null) {
            captureStatus = "Close the current screenshot preview before capturing again."
            return
        }
        if (isCaptureInProgress) {
            captureStatus = "A capture is already in progress."
            return
        }

        isCaptureInProgress = true
        captureStatus = "Capturing previous external window..."
        try {
            when (val result = CaptureBridge.capturePreviousWindow()) {
                is CaptureBridge.Result.Success -> {
                    previewPngPath = result.pngPath
                    captureStatus = "Screenshot captured."
                }
                is CaptureBridge.Result.Failure -> {
                    captureStatus = "Capture failed: ${result.diagnostic}"
                }
            }
        } catch (exception: Exception) {
            captureStatus = "Capture failed: ${exception.message ?: "Unexpected error."}"
        } finally {
            isCaptureInProgress = false
        }
    }

    LaunchedEffect(globalCaptureHotkey) {
        val registrationFailure = globalCaptureHotkey.start {
            previewScope.launch {
                capturePreviousWindow()
            }
        }
        captureStatus = registrationFailure
            ?: "Global capture shortcut registered: Ctrl+Alt+F12"
    }
    DisposableEffect(globalCaptureHotkey) {
        onDispose { globalCaptureHotkey.close() }
    }

    val dockSize = DpSize(140.dp, 56.dp)
    val panelSize = DpSize(420.dp, 560.dp)

    // rememberWindowState only uses `size` for the *first* composition, so
    // toggling isPanelExpanded later needs an explicit side effect that
    // writes to the already-created WindowState to actually resize the
    // window each time the dock/panel is expanded or collapsed.
    val mainWindowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = panelSize
    )
    LaunchedEffect(isPanelExpanded) {
        mainWindowState.size = if (isPanelExpanded) panelSize else dockSize
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = mainWindowState,
        title = "AI Visual Tutor",
        visible = isTutorWindowsVisible,
        undecorated = false,
        transparent = false,
        resizable = false,
        alwaysOnTop = false
    ) {
        if (isPanelExpanded) {
            TutorPanel(
                controller = controller,
                overlayManager = overlayManager,
                onClose = { isPanelExpanded = false },
                onOpenRegionSelector = {
                    if (previewPngPath != null) {
                        captureStatus = "Close the current screenshot preview before selecting a region."
                    } else if (isCaptureInProgress) {
                        captureStatus = "A capture is already in progress."
                    } else {
                        captureStatus = null
                        previewScope.launch { openRegionSelector() }
                    }
                },
                onCapturePreviousWindow = {
                    previewScope.launch { capturePreviousWindow() }
                },
                captureStatus = captureStatus
            )
        } else {
            TutorDock(onClick = { isPanelExpanded = true })
        }
    }

    if (previewPngPath != null) {
        ScreenshotPreviewWindow(
            pngPath = previewPngPath!!,
            onCloseRequest = {
                val capturedPngPath = previewPngPath
                if (capturedPngPath != null) {
                    previewPngPath = null
                    previewScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val capturedPng = File(capturedPngPath)
                                if (capturedPng.isFile && capturedPng.extension.equals("png", ignoreCase = true)) {
                                    Files.deleteIfExists(Paths.get(capturedPngPath))
                                }
                            }
                        } catch (exception: Exception) {
                            System.err.println("Could not delete captured PNG '$capturedPngPath': ${exception.message}")
                        }
                    }
                }
            }
        )
    }

    if (overlayManager.isOverlayVisible) {
        OverlayWindow(
            tutorState = controller.state,
            visible = isTutorWindowsVisible,
            onCloseRequest = { overlayManager.hideOverlay() }
        )
    }

    val activeSelectorSnapshot = selectorSnapshot
    if (isRegionSelectorOpen && activeSelectorSnapshot != null) {
        var selectorCompleted by remember(activeSelectorSnapshot) { mutableStateOf(false) }
        DisposableEffect(activeSelectorSnapshot) {
            onDispose {
                if (!selectorCompleted) {
                    activeSelectorSnapshot.image.flush()
                    selectorSnapshot = null
                    isRegionSelectorOpen = false
                    isTutorWindowsVisible = restoreTutorWindowsVisible
                    captureStatus = "Region selection was interrupted."
                }
            }
        }
        RegionSelectorWindow(
            snapshot = activeSelectorSnapshot,
            onRegionSelected = { pngPath, region ->
                selectorCompleted = true
                controller.setHighlight(region)
                previewPngPath = pngPath
                captureStatus = "Region screenshot captured."
                restoreAfterRegionSelection()
            },
            onCancel = {
                selectorCompleted = true
                restoreAfterRegionSelection()
                captureStatus = null
            },
            onFailure = { diagnostic ->
                selectorCompleted = true
                restoreAfterRegionSelection()
                captureStatus = "Region capture failed: $diagnostic"
            }
        )
    }
}
