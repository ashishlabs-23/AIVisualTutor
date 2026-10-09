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
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import context.ContextProcessor
import context.CalibrationAttempt
import context.CalibrationRunKind
import context.StderrContextLogger
import context.RegionSelectionController
import context.VisualContextAcquisition
import context.WindowsApplicationContextProvider
import context.WindowsWindowFocusManager
import context.SelectionSession
import context.GroundingExperimentRunner
import context.GroundingMode
import context.GroundingRequest
import context.ScreenToScreenshotTransform
import context.ApplicationContext
import models.HighlightRegion
import bridge.WgcScreenCaptureService
import bridge.FrozenSnapshotRegionCaptureService
import java.awt.Rectangle

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
    val lifecycleLogger = remember { StderrContextLogger() }
    val globalCaptureHotkey = remember { GlobalCaptureHotkey(logger = lifecycleLogger) }
    val contextProcessor = remember { ContextProcessor(logger = lifecycleLogger) }
    val groundingExperimentRunner = remember { GroundingExperimentRunner() }
    val regionCapture = remember { WgcScreenCaptureService(lifecycleLogger) }
    val selectionController = remember { RegionSelectionController(lifecycleLogger) }
    val acquisition = remember { VisualContextAcquisition(selectionController, WindowsApplicationContextProvider(), regionCapture, contextProcessor, WindowsWindowFocusManager()) }

    // Start with the full panel so the first-run UI is immediately visible.
    var isPanelExpanded by remember { mutableStateOf(true) }
    var isRegionSelectorOpen by remember { mutableStateOf(false) }
    var isTutorWindowsVisible by remember { mutableStateOf(true) }
    var selectorSnapshot by remember { mutableStateOf<FrozenScreenSnapshot?>(null) }
    var restoreTutorWindowsVisible by remember { mutableStateOf(true) }
    var activeSelectionSession by remember { mutableStateOf<SelectionSession?>(null) }
    var previewPngPath by remember { mutableStateOf<String?>(null) }
    var previewEvidence by remember { mutableStateOf(ui.ScreenshotPreviewEvidence.pending(null)) }
    var captureStatus by remember { mutableStateOf<String?>(null) }
    var isCaptureInProgress by remember { mutableStateOf(false) }
    var groundingApplicationContext by remember { mutableStateOf<ApplicationContext?>(null) }
    var groundingDesktopRegion by remember { mutableStateOf<Rectangle?>(null) }
    var previewExperimentCaseId by remember { mutableStateOf("PREVIEW_${UUID.randomUUID()}") }

    suspend fun openRegionSelector() {
        if (isRegionSelectorOpen || selectorSnapshot != null) return
        if (previewPngPath != null || isCaptureInProgress) {
            captureStatus = "Close the current preview or wait for capture to finish before selecting a region."
            return
        }

        val session = acquisition.begin()
        if (session == null) {
            captureStatus = "A region selection is already active."
            return
        }
        activeSelectionSession = session
        val previousVisibility = isTutorWindowsVisible
        restoreTutorWindowsVisible = previousVisibility
        try {
            // begin() queries the foreground application before the tutor windows are hidden.
            isTutorWindowsVisible = false
            delay(200)
            val snapshot = withContext(Dispatchers.IO) {
                captureFrozenScreenSnapshot()
            }
            selectorSnapshot = snapshot
            isRegionSelectorOpen = true
            lifecycleLogger.event(context.LifecycleEvent.OVERLAY_SHOWN, mapOf("regionId" to session.regionId, "state" to "SELECTING"))
        } catch (exception: Exception) {
            acquisition.fail(session)
            activeSelectionSession = null
            selectorSnapshot = null
            isRegionSelectorOpen = false
            isTutorWindowsVisible = previousVisibility
            captureStatus = "Could not snapshot the screen for region selection: ${exception.message}"
        }
    }

    fun restoreAfterRegionSelection() {
        selectorSnapshot?.flush()
        selectorSnapshot = null
        isRegionSelectorOpen = false
        isTutorWindowsVisible = restoreTutorWindowsVisible
        lifecycleLogger.event(context.LifecycleEvent.OVERLAY_CLOSED, mapOf("state" to "CLOSED"))
        lifecycleLogger.event(context.LifecycleEvent.WINDOW_RESTORED, mapOf("state" to if (restoreTutorWindowsVisible) "VISIBLE" else "HIDDEN"))
    }

    fun restorePreviousFocusLater(session: SelectionSession?) {
        if (session == null) return
        previewScope.launch {
            delay(180)
            val restored = acquisition.restorePreviousFocus(session)
            lifecycleLogger.event(context.LifecycleEvent.FOCUS_RESTORED, mapOf("regionId" to session.regionId, "state" to if (restored) "RESTORED" else "NOT_RESTORED"))
        }
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
        val targetDescription = controller.currentTargetDescription
        groundingApplicationContext = null
        groundingDesktopRegion = null
        previewExperimentCaseId = "PREVIEW_${UUID.randomUUID()}"
        try {
            when (val result = CaptureBridge.capturePreviousWindow()) {
                is CaptureBridge.Result.Success -> {
                    val capturedWindow = result.capturedWindow
                    groundingApplicationContext = capturedWindow?.let {
                        ApplicationContext(
                            applicationName = it.processName,
                            processName = it.processName,
                            pid = it.processId,
                            windowBounds = it.bounds,
                            windowHandle = it.windowHandle
                        )
                    }
                    groundingDesktopRegion = capturedWindow?.bounds
                    val capturedAt = Instant.now()
                    previewPngPath = result.pngPath
                    previewEvidence = ui.ScreenshotPreviewEvidence.processingFailure(targetDescription)
                    try {
                        val image = withContext(Dispatchers.IO) {
                            ImageIO.read(File(result.pngPath)) ?: error("Captured PNG could not be decoded.")
                        }
                        val visualContext = contextProcessor.process(
                            image = image,
                            x = 0,
                            y = 0,
                            app = null,
                            regionId = UUID.randomUUID(),
                            selectedRegion = null,
                            targetDescription = targetDescription,
                            calibrationAttempt = CalibrationAttempt(
                                caseId = "LIVE_${UUID.randomUUID()}",
                                runKind = CalibrationRunKind.LIVE_FLOW,
                                capturedAt = capturedAt,
                                targetDescription = targetDescription,
                                targetDescriptionSource = targetDescription
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { "active_tutor_step_instruction" },
                                captureMetadata = mapOf(
                                    "captureSourceMode" to "CAPTURE_BRIDGE_WINDOW",
                                    "selectedRegionCoordinateSpace" to "NOT_RECORDED"
                                )
                            )
                        )
                        previewEvidence = ui.ScreenshotPreviewEvidence.from(targetDescription, visualContext)
                        captureStatus = "Screenshot captured and evidence processed."
                    } catch (exception: kotlinx.coroutines.CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        previewEvidence = ui.ScreenshotPreviewEvidence.pending(targetDescription)
                        captureStatus = "Screenshot captured; evidence processing failed: ${exception.message ?: "Unexpected error."}"
                    }
                }
                is CaptureBridge.Result.Failure -> {
                    captureStatus = "Capture failed: ${result.diagnostic}"
                }
            }
        } catch (exception: kotlinx.coroutines.CancellationException) {
            throw exception
        } catch (exception: Exception) {
            captureStatus = "Capture failed: ${exception.message ?: "Unexpected error."}"
        } finally {
            isCaptureInProgress = false
        }
    }

    LaunchedEffect(globalCaptureHotkey) {
        val registrationFailure = globalCaptureHotkey.start(onHotkeyPressed = {
            previewScope.launch {
                capturePreviousWindow()
            }
        }, onRegionHotkeyPressed = {
            previewScope.launch { openRegionSelector() }
        })
        captureStatus = registrationFailure
            ?: "Global shortcuts active: Ctrl+Alt+F12 (window), Ctrl+Shift+Space (region)"
    }
    DisposableEffect(globalCaptureHotkey) {
        onDispose { globalCaptureHotkey.close() }
    }
    DisposableEffect(controller) {
        onDispose { controller.sessionContext.lastVisualContext?.image?.flush() }
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
        DisposableEffect(previewPngPath) {
            val pathToClean = previewPngPath
            onDispose {
                if (pathToClean != null) {
                    runCatching { Files.deleteIfExists(Paths.get(pathToClean)) }
                }
            }
        }
        ScreenshotPreviewWindow(
            pngPath = previewPngPath!!,
            evidence = previewEvidence,
            onRunGrounding = { mode, targetDescription ->
                val image = withContext(Dispatchers.IO) {
                    ImageIO.read(File(previewPngPath!!)) ?: error("Preview screenshot could not be decoded.")
                }
                val region = groundingDesktopRegion
                val physicalDisplayCoordinates = runCatching {
                    val devices = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
                    devices.isNotEmpty() && devices.all { device ->
                        val transform = device.defaultConfiguration.defaultTransform
                        transform.scaleX == 1.0 && transform.scaleY == 1.0 && transform.shearX == 0.0 && transform.shearY == 0.0
                    }
                }.getOrDefault(false)
                val mapping = region?.takeIf { physicalDisplayCoordinates && it.width > 0 && it.height > 0 }?.let {
                    ScreenToScreenshotTransform(it.x.toDouble(), it.y.toDouble(), image.width.toDouble() / it.width, image.height.toDouble() / it.height)
                }
                try {
                    groundingExperimentRunner.run(GroundingRequest(
                        screenshot = image, targetDescription = targetDescription, mode = mode,
                        applicationContext = groundingApplicationContext, selectedRegionOnDesktop = region,
                        screenToScreenshot = mapping, screenshotReference = "current-preview",
                        experimentId = "PHASE5_PREVIEW", caseId = previewExperimentCaseId
                    ))
                } finally { image.flush() }
            },
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
                    activeSelectionSession?.let { acquisition.cancel(it) }
                    activeSelectionSession = null
                    activeSelectorSnapshot.flush()
                    selectorSnapshot = null
                    isRegionSelectorOpen = false
                    isTutorWindowsVisible = restoreTutorWindowsVisible
                    captureStatus = "Region selection was interrupted."
                }
            }
        }
        RegionSelectorWindow(
            snapshot = activeSelectorSnapshot,
            onRegionSelected = { desktopBounds ->
                selectorCompleted = true
                // Close the fullscreen selector immediately; PROCESSING owns the session now.
                // The underlying app is visible while the frozen snapshot is cropped/analyzed.
                isRegionSelectorOpen = false
                captureStatus = "Region captured; processing visual context..."
                val targetDescription = controller.currentTargetDescription
                groundingApplicationContext = activeSelectionSession?.applicationContext
                groundingDesktopRegion = desktopBounds
                previewExperimentCaseId = "PREVIEW_${activeSelectionSession?.regionId ?: UUID.randomUUID()}"
                previewScope.launch {
                    val session = activeSelectionSession
                    val frozenSnapshot = selectorSnapshot
                    try {
                        checkNotNull(session) { "Selection session expired." }
                        val result = acquisition.process(
                            session, checkNotNull(frozenSnapshot), desktopBounds, targetDescription
                        )
                        controller.setHighlight(HighlightRegion(desktopBounds.x.toFloat(), desktopBounds.y.toFloat(), desktopBounds.width.toFloat(), desktopBounds.height.toFloat()))
                        val oldImage = controller.sessionContext.lastVisualContext?.image
                        if (oldImage !== result.image) oldImage?.flush()
                        controller.recordVisualContext(result.visualContext)
                        previewEvidence = ui.ScreenshotPreviewEvidence.from(targetDescription, result.visualContext)
                        previewPngPath = withContext(Dispatchers.IO) { FrozenSnapshotRegionCaptureService(lifecycleLogger).persistPreview(result.image).toAbsolutePath().toString() }
                        val appName = session.applicationContext?.applicationName ?: "Unknown application"
                        captureStatus = "Visual context: ${result.visualContext.width} x ${result.visualContext.height}, ${result.visualContext.contentType}, ${result.visualContext.extractedText?.length ?: 0} OCR characters, $appName."
                        kotlinx.coroutines.delay(250)
                        acquisition.reset(session)
                    } catch (exception: context.SelectionTooSmallException) {
                        session?.let { acquisition.cancel(it) }
                        captureStatus = "Selection is too small; choose a region larger than 8 physical pixels."
                        restorePreviousFocusLater(session)
                    } catch (exception: Exception) {
                        session?.let { acquisition.fail(it) }
                        captureStatus = "Region captured; context processing failed: ${exception.message}"
                        restorePreviousFocusLater(session)
                    } finally {
                        session?.let { acquisition.reset(it) }
                        restoreAfterRegionSelection()
                        activeSelectionSession = null
                    }
                }
            },
            onCancel = {
                if (!selectorCompleted) {
                    selectorCompleted = true
                    val session = activeSelectionSession
                    session?.let { acquisition.cancel(it) }
                    activeSelectionSession = null
                    restoreAfterRegionSelection()
                    captureStatus = "Region selection cancelled."
                    lifecycleLogger.event(context.LifecycleEvent.SELECTION_STATE_IDLE, mapOf("regionId" to session?.regionId, "state" to "IDLE"))
                    // Wait for Compose to remove the topmost selector and restore
                    // tutor visibility before returning focus to the pre-hotkey HWND.
                    restorePreviousFocusLater(session)
                }
            },
            onFailure = { diagnostic ->
                if (!selectorCompleted) {
                    selectorCompleted = true
                    val session = activeSelectionSession
                    session?.let { acquisition.fail(it) }
                    activeSelectionSession = null
                    restoreAfterRegionSelection()
                    captureStatus = "Region capture failed: $diagnostic"
                    restorePreviousFocusLater(session)
                }
            },
            onEscConsumed = {
                lifecycleLogger.event(context.LifecycleEvent.ESC_RECEIVED, mapOf("regionId" to activeSelectionSession?.regionId, "state" to "ESC"))
                lifecycleLogger.event(context.LifecycleEvent.ESC_CONSUMED, mapOf("regionId" to activeSelectionSession?.regionId, "state" to "CONSUMED"))
            }
        )
    }
}
