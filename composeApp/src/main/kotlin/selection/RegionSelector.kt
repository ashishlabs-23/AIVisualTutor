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
import androidx.compose.ui.platform.LocalDensity
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

data class FrozenScreenSnapshot(
    val image: BufferedImage,
    val monitorBounds: Rectangle,
    val scaleX: Double,
    val scaleY: Double
)

fun captureFrozenScreenSnapshot(): FrozenScreenSnapshot {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val pointer = MouseInfo.getPointerInfo()?.location
    val device = pointer?.let { position ->
        environment.screenDevices.firstOrNull { screen ->
            screen.defaultConfiguration.bounds.contains(position)
        }
    } ?: environment.defaultScreenDevice
    val configuration = device.defaultConfiguration
    val bounds = Rectangle(configuration.bounds)
    require(bounds.width > 0 && bounds.height > 0) { "The selected monitor has no usable area." }

    val robot = Robot(device)
    val capture = robot.createMultiResolutionScreenCapture(bounds)
    val image = capture.resolutionVariants
        .filterIsInstance<BufferedImage>()
        .maxByOrNull { it.width.toLong() * it.height.toLong() }
        ?: error("Windows did not provide a readable screen snapshot.")

    require(image.width > 0 && image.height > 0) { "The screen snapshot is empty." }
    return FrozenScreenSnapshot(
        image = image,
        monitorBounds = bounds,
        scaleX = image.width.toDouble() / bounds.width,
        scaleY = image.height.toDouble() / bounds.height
    )
}

@Composable
fun RegionSelectorWindow(
    snapshot: FrozenScreenSnapshot,
    onRegionSelected: (String, HighlightRegion) -> Unit,
    onCancel: () -> Unit,
    onFailure: (String) -> Unit
) {
    val density = LocalDensity.current
    val bounds = snapshot.monitorBounds
    val windowState = rememberWindowState(
        position = WindowPosition(
            x = with(density) { bounds.x.toDp() },
            y = with(density) { bounds.y.toDp() }
        ),
        size = with(density) {
            androidx.compose.ui.unit.DpSize(
                bounds.width.toDp(),
                bounds.height.toDp()
            )
        }
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
            onFailure = onFailure
        )
    }
}

@Composable
private fun RegionSelectorContent(
    snapshot: FrozenScreenSnapshot,
    onRegionSelected: (String, HighlightRegion) -> Unit,
    onCancel: () -> Unit,
    onFailure: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val image = remember(snapshot.image) {
        ByteArrayOutputStream().use { output ->
            check(ImageIO.write(snapshot.image, "png", output)) { "No PNG writer is available." }
            // Phase 3 can reuse this frozen frame for visual context analysis.
            Image.makeFromEncoded(output.toByteArray()).toComposeImageBitmap()
        }
    }
    var dragStart by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                    onCancel()
                    true
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

                        if (crop.width <= MINIMUM_SELECTION_PIXELS ||
                            crop.height <= MINIMUM_SELECTION_PIXELS
                        ) {
                            onCancel()
                        } else {
                            val region = HighlightRegion(
                                x = (crop.x / snapshot.scaleX).toFloat(),
                                y = (crop.y / snapshot.scaleY).toFloat(),
                                width = (crop.width / snapshot.scaleX).toFloat(),
                                height = (crop.height / snapshot.scaleY).toFloat()
                            )
                            try {
                                onRegionSelected(saveCrop(snapshot.image, crop), region)
                            } catch (exception: Exception) {
                                onFailure(exception.message ?: "Could not save the selected screenshot region.")
                            }
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

internal data class PixelCrop(val x: Int, val y: Int, val width: Int, val height: Int)

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

private fun saveCrop(image: BufferedImage, crop: PixelCrop): String {
    require(crop.width > MINIMUM_SELECTION_PIXELS && crop.height > MINIMUM_SELECTION_PIXELS) {
        "Selected region must be larger than $MINIMUM_SELECTION_PIXELS pixels in both dimensions."
    }
    val path = Paths.get(
        System.getProperty("java.io.tmpdir"),
        "AIVisualTutor_capture_${UUID.randomUUID().toString().replace("-", "")}.png"
    )
    try {
        val selectedImage = image.getSubimage(crop.x, crop.y, crop.width, crop.height)
        check(ImageIO.write(selectedImage, "png", path.toFile())) { "No PNG writer is available." }
        check(Files.isRegularFile(path) && Files.size(path) > 0) { "The selected PNG was not written." }
        return path.toAbsolutePath().toString()
    } catch (exception: Exception) {
        Files.deleteIfExists(path)
        throw exception
    }
}

private const val MINIMUM_SELECTION_PIXELS = 4
private const val OUTSIDE_SELECTION_DIM_ALPHA = 0.35f
