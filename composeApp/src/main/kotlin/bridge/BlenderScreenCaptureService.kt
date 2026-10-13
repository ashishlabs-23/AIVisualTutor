package bridge

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Result representing the outcome of attempting to capture the Blender workspace.
 */
sealed interface BlenderCaptureResult {
    data class Success(
        val image: BufferedImage,
        val pngPath: String,
        val windowInfo: BlenderWindowInfo,
        val capturedAt: Instant = Instant.now()
    ) : BlenderCaptureResult

    data class BlenderNotRunning(
        val message: String = "Blender is not running. Please launch Blender and open your project."
    ) : BlenderCaptureResult

    data class BlenderMinimized(
        val windowInfo: BlenderWindowInfo,
        val message: String = "Blender is minimized. Please restore and unminimize the Blender window to capture your workspace."
    ) : BlenderCaptureResult

    data class WindowNotFound(
        val message: String = "Blender workspace window could not be identified."
    ) : BlenderCaptureResult

    data class CaptureFailed(
        val diagnostic: String,
        val cause: Throwable? = null
    ) : BlenderCaptureResult

    data class InvalidImage(
        val diagnostic: String
    ) : BlenderCaptureResult
}

/**
 * Service contract for capturing the real Blender application screen.
 */
interface BlenderScreenCaptureService {
    suspend fun captureBlenderWorkspace(
        excludeOverlayAction: (suspend () -> Unit)? = null,
        restoreOverlayAction: (suspend () -> Unit)? = null
    ): BlenderCaptureResult
}

/**
 * Java Robot-based capture service that targets the Blender window.
 * Operates purely on Java/Compose JVM without external .NET binaries or PowerShell dependencies for capture.
 */
class RobotBlenderScreenCaptureService(
    private val windowLocator: BlenderWindowLocator = WindowsBlenderWindowLocator()
) : BlenderScreenCaptureService {

    override suspend fun captureBlenderWorkspace(
        excludeOverlayAction: (suspend () -> Unit)?,
        restoreOverlayAction: (suspend () -> Unit)?
    ): BlenderCaptureResult = withContext(Dispatchers.IO) {
        // Step 1: Locate the Blender application window
        val locateResult = windowLocator.locateBlenderWindow()
        val windowInfo = when (locateResult) {
            is BlenderWindowResult.NotRunning -> return@withContext BlenderCaptureResult.BlenderNotRunning()
            is BlenderWindowResult.Minimized -> return@withContext BlenderCaptureResult.BlenderMinimized(locateResult.window)
            is BlenderWindowResult.Error -> return@withContext BlenderCaptureResult.CaptureFailed(locateResult.message)
            is BlenderWindowResult.Found -> locateResult.window
        }

        val bounds = windowInfo.bounds
        if (bounds.width <= 10 || bounds.height <= 10) {
            return@withContext BlenderCaptureResult.InvalidImage(
                "Blender window dimensions are too small or invalid: ${bounds.width}x${bounds.height}"
            )
        }

        var overlayWasHidden = false
        try {
            // Step 2: Ensure Blender is the foreground window if necessary
            if (!windowInfo.isForeground && windowInfo.hwnd != 0L) {
                windowLocator.bringBlenderToForeground(windowInfo.hwnd)
                delay(120)
            }

            // Step 3: Overlay exclusion
            if (excludeOverlayAction != null) {
                excludeOverlayAction()
                overlayWasHidden = true
                delay(150) // Allow OS compositing to remove the overlay surface
            }

            // Step 4: Capture using java.awt.Robot with multi-monitor / high-DPI awareness
            val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val screenDevices = env.screenDevices
            if (screenDevices.isNullOrEmpty()) {
                return@withContext BlenderCaptureResult.CaptureFailed("No active graphics screen devices detected.")
            }

            // Find intersecting screen device or fallback to primary device
            val targetDevice = screenDevices.firstOrNull { device ->
                device.defaultConfiguration.bounds.intersects(bounds)
            } ?: env.defaultScreenDevice

            val deviceBounds = targetDevice.defaultConfiguration.bounds
            val captureRect = bounds.intersection(deviceBounds)
            if (captureRect.isEmpty || captureRect.width <= 0 || captureRect.height <= 0) {
                return@withContext BlenderCaptureResult.InvalidImage(
                    "Blender window does not intersect a connected monitor."
                )
            }

            val robot = Robot(targetDevice)
            val multiResCapture = runCatching {
                robot.createMultiResolutionScreenCapture(captureRect)
            }.getOrNull()

            val rawImage: BufferedImage = multiResCapture?.resolutionVariants
                ?.filterIsInstance<BufferedImage>()
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: robot.createScreenCapture(captureRect)

            if (rawImage.width <= 0 || rawImage.height <= 0) {
                return@withContext BlenderCaptureResult.InvalidImage("Captured screenshot has empty dimensions.")
            }

            // Step 5: Persist real image to a temporary file
            val tempPath = persistTemporaryPng(rawImage)

            // Step 6: Validate PNG format
            if (!hasPngMagicBytes(tempPath)) {
                Files.deleteIfExists(tempPath)
                return@withContext BlenderCaptureResult.InvalidImage("Persisted capture is not a valid PNG image.")
            }

            BlenderCaptureResult.Success(
                image = rawImage,
                pngPath = tempPath.toAbsolutePath().toString(),
                windowInfo = windowInfo
            )
        } catch (exception: Exception) {
            BlenderCaptureResult.CaptureFailed(
                diagnostic = "Failed to capture Blender screen: ${exception.message ?: "Unknown error"}",
                cause = exception
            )
        } finally {
            if (overlayWasHidden && restoreOverlayAction != null) {
                runCatching { restoreOverlayAction() }
            }
        }
    }

    private fun persistTemporaryPng(image: BufferedImage): Path {
        val tempFile = Files.createTempFile("AIVT_BlenderCapture_${UUID.randomUUID().toString().replace("-", "")}_", ".png")
        val written = ImageIO.write(image, "png", tempFile.toFile())
        if (!written) {
            Files.deleteIfExists(tempFile)
            error("No PNG image writer is available in the Java runtime.")
        }
        return tempFile
    }

    private fun hasPngMagicBytes(path: Path): Boolean = runCatching {
        val file = path.toFile()
        if (!file.isFile || file.length() < 8) return false
        file.inputStream().use { stream ->
            val header = ByteArray(8)
            val read = stream.read(header)
            read == 8 && header.contentEquals(PNG_HEADER_SIGNATURE)
        }
    }.getOrDefault(false)

    companion object {
        private val PNG_HEADER_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
