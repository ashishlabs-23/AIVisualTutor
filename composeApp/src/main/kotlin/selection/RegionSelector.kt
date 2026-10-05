package selection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.graphics.toComposeImageBitmap
import models.HighlightRegion
import org.jetbrains.skia.Image
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

data class FrozenScreenSnapshot(
    val image: BufferedImage,
    val monitorBounds: Rectangle,
    val scaleX: Double,
    val scaleY: Double,
    val monitors: List<FrozenMonitorSnapshot> = emptyList()
) {
    fun flush() {
        image.flush()
        monitors.forEach { it.image.flush() }
    }
}

data class FrozenMonitorSnapshot(val desktopBounds: Rectangle, val image: BufferedImage, val scaleX: Double, val scaleY: Double)

fun captureFrozenScreenSnapshot(): FrozenScreenSnapshot {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val capturedMonitors = mutableListOf<FrozenMonitorSnapshot>()
    try {
        environment.screenDevices.forEach { device ->
            val bounds = Rectangle(device.defaultConfiguration.bounds)
            require(bounds.width > 0 && bounds.height > 0) { "A monitor has no usable area." }
            // JDK 17 Robot returns a multi-resolution variant per GraphicsDevice. Keeping each
            // monitor separate avoids applying one monitor's scale to a mixed-DPI desktop.
            val capture = Robot(device).createMultiResolutionScreenCapture(bounds)
            val image = capture.resolutionVariants.filterIsInstance<BufferedImage>()
                .maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: error("Windows did not provide a readable screen snapshot.")
            capturedMonitors += FrozenMonitorSnapshot(bounds, image, image.width.toDouble() / bounds.width, image.height.toDouble() / bounds.height)
        }
    } catch (e: Exception) {
        capturedMonitors.forEach { it.image.flush() }
        throw e
    }
    val monitors = capturedMonitors.toList()
    val bounds = monitors.map { it.desktopBounds }.reduce { a, b -> a.union(b) }
    val image = BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = java.awt.Color.BLACK
        graphics.fillRect(0, 0, bounds.width, bounds.height)
        monitors.forEach { monitor ->
            graphics.drawImage(monitor.image, monitor.desktopBounds.x - bounds.x, monitor.desktopBounds.y - bounds.y,
                monitor.desktopBounds.width, monitor.desktopBounds.height, null)
        }
    } finally { graphics.dispose() }
    require(image.width > 0 && image.height > 0) { "The screen snapshot is empty." }
    return FrozenScreenSnapshot(
        image = image,
        monitorBounds = bounds,
        scaleX = 1.0,
        scaleY = 1.0,
        monitors = monitors
    )
}

@Composable
fun RegionSelectorWindow(
    snapshot: FrozenScreenSnapshot,
    onRegionSelected: (Rectangle) -> Unit,
    onCancel: () -> Unit,
    onFailure: (String) -> Unit,
    onEscConsumed: () -> Unit = {}
) {
    val bounds = snapshot.monitorBounds
    val windowState = rememberWindowState(
        position = WindowPosition(
            // GraphicsConfiguration.bounds and WindowPosition use the desktop's
            // user-space coordinates. Converting through the caller's Density
            // applies the primary window's DPI a second time on scaled displays.
            x = bounds.x.dp,
            y = bounds.y.dp
        ),
        size = androidx.compose.ui.unit.DpSize(bounds.width.dp, bounds.height.dp)
    )

    Window(
        onCloseRequest = onCancel,
        state = windowState,
        undecorated = true,
        transparent = false,
        resizable = false,
        alwaysOnTop = true,
        focusable = true,
        title = "AI Tutor - Select Region"
    ) {
        RegionSelectorContent(
            snapshot = snapshot,
            onRegionSelected = onRegionSelected,
            onCancel = onCancel,
            onFailure = onFailure,
            onEscConsumed = onEscConsumed
        )
    }
}

@Composable
private fun RegionSelectorContent(
    snapshot: FrozenScreenSnapshot,
    onRegionSelected: (Rectangle) -> Unit,
    onCancel: () -> Unit,
    onFailure: (String) -> Unit,
    onEscConsumed: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val escapeKeySequence = remember { EscapeKeySequence() }
    val image = remember(snapshot.image) {
        ByteArrayOutputStream().use { output ->
            check(ImageIO.write(snapshot.image, "png", output)) { "No PNG writer is available." }
            // Phase 3 can reuse this frozen frame for visual context analysis.
            Image.makeFromEncoded(output.toByteArray()).toComposeImageBitmap()
        }
    }
    var dragStart by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    androidx.compose.runtime.LaunchedEffect(focusRequester) {
        // The Compose content can be composed before the native top-level
        // window is shown/activated. Retry after peer creation so the selector
        // receives ESC immediately, including when it was opened from a global
        // hotkey while another application had focus.
        repeat(10) {
            delay(50)
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    escapeKeySequence.handle(event.type, onEscConsumed, onCancel)
                } else if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                    escapeKeySequence.handle(event.type, onEscConsumed, onCancel)
                } else {
                    false
                }
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(snapshot) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startLocal = down.position
                        var endLocal = startLocal
                        dragStart = startLocal
                        dragCurrent = startLocal
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            endLocal = change.position
                            dragCurrent = endLocal
                            change.consume()
                            if (!change.pressed) break
                        }

                        val display = calculateImageDisplayRect(
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            imageWidth = image.width,
                            imageHeight = image.height
                        )
                        val crop = selectionToImageCrop(
                            startLocal,
                            endLocal,
                            display,
                            image.width,
                            image.height
                        )
                        dragStart = null
                        dragCurrent = null

                        if (crop.width <= 0 || crop.height <= 0) {
                            onCancel()
                        } else {
                            onRegionSelected(Rectangle(snapshot.monitorBounds.x + crop.x, snapshot.monitorBounds.y + crop.y, crop.width, crop.height))
                        }
                    }
                }
        ) {
            val display = calculateImageDisplayRect(
                width = size.width,
                height = size.height,
                imageWidth = image.width,
                imageHeight = image.height
            )
            val left = display.left.toInt()
            val top = display.top.toInt()
            val right = (display.left + display.width).toInt()
            val bottom = (display.top + display.height).toInt()
            val displayWidth = right - left
            val displayHeight = bottom - top

            drawImage(
                image = image,
                dstOffset = IntOffset(left, top),
                dstSize = IntSize(displayWidth, displayHeight)
            )

            val start = dragStart
            val current = dragCurrent
            if (start == null || current == null) {
                drawRect(
                    color = Color.Black.copy(alpha = OUTSIDE_SELECTION_DIM_ALPHA),
                    topLeft = androidx.compose.ui.geometry.Offset(left.toFloat(), top.toFloat()),
                    size = androidx.compose.ui.geometry.Size(displayWidth.toFloat(), displayHeight.toFloat())
                )
            } else {
                val selection = selectionToDisplayRect(start, current, display)
                val selectionLeft = selection.left
                val selectionTop = selection.top
                val selectionRight = selection.left + selection.width
                val selectionBottom = selection.top + selection.height
                val shade = Color.Black.copy(alpha = OUTSIDE_SELECTION_DIM_ALPHA)
                drawRect(shade, androidx.compose.ui.geometry.Offset(left.toFloat(), top.toFloat()),
                    androidx.compose.ui.geometry.Size(displayWidth.toFloat(), max(0f, selectionTop - top)))
                drawRect(shade, androidx.compose.ui.geometry.Offset(left.toFloat(), selectionBottom),
                    androidx.compose.ui.geometry.Size(displayWidth.toFloat(), max(0f, bottom - selectionBottom)))
                drawRect(shade, androidx.compose.ui.geometry.Offset(left.toFloat(), selectionTop),
                    androidx.compose.ui.geometry.Size(max(0f, selectionLeft - left), max(0f, selectionBottom - selectionTop)))
                drawRect(shade, androidx.compose.ui.geometry.Offset(selectionRight, selectionTop),
                    androidx.compose.ui.geometry.Size(max(0f, right - selectionRight), max(0f, selectionBottom - selectionTop)))
                drawRect(
                    color = Color(0xFF4CAF50),
                    topLeft = androidx.compose.ui.geometry.Offset(selectionLeft, selectionTop),
                    size = androidx.compose.ui.geometry.Size(selection.width, selection.height),
                    style = Stroke(width = 2f)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
                .background(Color(0xE623262E))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Drag to select a region.", color = Color.White)
            Text(
                text = "Cancel",
                color = Color(0xFFFF8A80),
                modifier = Modifier.clickable { onCancel() }
            )
        }
    }
}

internal data class DisplayRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

data class PixelCrop(val x: Int, val y: Int, val width: Int, val height: Int)
const val MINIMUM_SELECTION_PIXELS = 8

/** Keeps the selector alive until both halves of ESC have been consumed. */
internal class EscapeKeySequence {
    private var keyDownConsumed = false

    fun handle(type: KeyEventType, onEscConsumed: () -> Unit, onCancel: () -> Unit): Boolean = when (type) {
        KeyEventType.KeyDown -> {
            if (!keyDownConsumed) onEscConsumed()
            keyDownConsumed = true
            true
        }
        KeyEventType.KeyUp -> {
            if (!keyDownConsumed) onEscConsumed()
            keyDownConsumed = false
            onCancel()
            true
        }
        else -> true
    }
}

data class SelectionRect(val left: Int, val top: Int, val width: Int, val height: Int)
fun normalizeSelectionRect(x1: Int, y1: Int, x2: Int, y2: Int) = SelectionRect(min(x1,x2), min(y1,y2), kotlin.math.abs(x2-x1), kotlin.math.abs(y2-y1))
fun clampSelectionRect(rect: SelectionRect, bounds: Rectangle): SelectionRect {
    val left=max(rect.left,bounds.x); val top=max(rect.top,bounds.y)
    val right=min(rect.left+rect.width,bounds.x+bounds.width); val bottom=min(rect.top+rect.height,bounds.y+bounds.height)
    return SelectionRect(left,top,max(0,right-left),max(0,bottom-top))
}
fun isSelectionLargeEnough(rect: SelectionRect) = rect.width > MINIMUM_SELECTION_PIXELS && rect.height > MINIMUM_SELECTION_PIXELS

internal fun calculateImageDisplayRect(
    width: Float,
    height: Float,
    imageWidth: Int,
    imageHeight: Int
): DisplayRect {
    val scale = min(width / imageWidth, height / imageHeight)
    val displayWidth = (imageWidth * scale).roundToInt().toFloat()
    val displayHeight = (imageHeight * scale).roundToInt().toFloat()
    return DisplayRect(
        left = ((width - displayWidth) / 2f).roundToInt().toFloat(),
        top = ((height - displayHeight) / 2f).roundToInt().toFloat(),
        width = displayWidth,
        height = displayHeight
    )
}

internal fun selectionToImageCrop(
    start: androidx.compose.ui.geometry.Offset,
    end: androidx.compose.ui.geometry.Offset,
    display: DisplayRect,
    imageWidth: Int,
    imageHeight: Int
): PixelCrop {
    if (display.width <= 0f || display.height <= 0f) return PixelCrop(0, 0, 0, 0)

    val cropBounds = normalizedSelectionBounds(start, end, display)
    if (cropBounds.width <= 0f || cropBounds.height <= 0f) {
        return PixelCrop(0, 0, 0, 0)
    }

    val scaleX = imageWidth / display.width
    val scaleY = imageHeight / display.height
    val localLeft = max(0f, cropBounds.left - display.left)
    val localTop = max(0f, cropBounds.top - display.top)
    val localRight = max(localLeft, min(display.width, cropBounds.left + cropBounds.width - display.left))
    val localBottom = max(localTop, min(display.height, cropBounds.top + cropBounds.height - display.top))

    val cropLeft = floor(localLeft * scaleX).toInt().coerceIn(0, imageWidth)
    val cropTop = floor(localTop * scaleY).toInt().coerceIn(0, imageHeight)
    val cropRight = ceil(localRight * scaleX).toInt().coerceIn(cropLeft, imageWidth)
    val cropBottom = ceil(localBottom * scaleY).toInt().coerceIn(cropTop, imageHeight)
    return PixelCrop(cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop)
}

private fun selectionToDisplayRect(
    start: androidx.compose.ui.geometry.Offset,
    end: androidx.compose.ui.geometry.Offset,
    display: DisplayRect
): DisplayRect = normalizedSelectionBounds(start, end, display)

private fun normalizedSelectionBounds(
    start: androidx.compose.ui.geometry.Offset,
    end: androidx.compose.ui.geometry.Offset,
    display: DisplayRect
): DisplayRect {
    val minX = min(start.x, end.x)
    val maxX = max(start.x, end.x)
    val minY = min(start.y, end.y)
    val maxY = max(start.y, end.y)

    val left = max(display.left, minX)
    val top = max(display.top, minY)
    val right = min(display.left + display.width, maxX)
    val bottom = min(display.top + display.height, maxY)

    return DisplayRect(left, top, max(0f, right - left), max(0f, bottom - top))
}

private const val OUTSIDE_SELECTION_DIM_ALPHA = 0.35f
