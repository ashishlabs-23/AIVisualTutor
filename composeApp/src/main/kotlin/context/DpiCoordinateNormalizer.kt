package context

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Coordinate space taxonomy to ensure zero ambiguity across perception,
 * screen capture, visual grounding, and UI overlay rendering.
 */
enum class CoordinateSpace {
    /** Raw physical pixels on the display hardware (Win32 API, raw screenshot rasters, UIA BoundingRectangle). */
    PHYSICAL_SCREEN,
    /** Java AWT / Compose Desktop layout coordinates (DIPs / Device Independent Pixels). */
    LOGICAL_DESKTOP,
    /** Pixel coordinates relative to a cropped sub-image (e.g. RegionFocus crops). */
    CROP_PIXELS,
    /** Unit-normalized coordinates (0.0 to 1.0) relative to an image or bounding box. */
    NORMALIZED
}

/**
 * Geometric profile of a connected display monitor.
 */
data class MonitorGeometry(
    val id: String,
    val logicalBounds: Rectangle,
    val scaleX: Double,
    val scaleY: Double,
    val physicalBounds: Rectangle = Rectangle(
        (logicalBounds.x * scaleX).roundToInt(),
        (logicalBounds.y * scaleY).roundToInt(),
        (logicalBounds.width * scaleX).roundToInt(),
        (logicalBounds.height * scaleY).roundToInt()
    )
)

/**
 * Robust coordinate conversion and normalization service supporting:
 * - Mixed-DPI display configurations (100%, 125%, 150%, 200%).
 * - Negative monitor origins (secondary displays left or top of primary display).
 * - Multi-monitor virtual desktop bounds clamping.
 * - Sub-crop bidirectional coordinate mapping.
 */
class DpiCoordinateNormalizer(
    private val monitorsProvider: () -> List<MonitorGeometry> = ::detectConnectedMonitors
) {

    /**
     * Converts a rectangle from physical screen coordinates to logical AWT/Compose desktop coordinates.
     */
    fun physicalToLogical(physicalRect: Rectangle): Rectangle {
        val monitor = findMonitorForPhysicalRect(physicalRect)
            ?: monitorsProvider().firstOrNull()
            ?: return physicalRect

        val scaleX = if (monitor.scaleX > 0.0) monitor.scaleX else 1.0
        val scaleY = if (monitor.scaleY > 0.0) monitor.scaleY else 1.0

        // Calculate offset relative to the monitor's physical origin
        val relPhysX = physicalRect.x - monitor.physicalBounds.x
        val relPhysY = physicalRect.y - monitor.physicalBounds.y

        val logX = monitor.logicalBounds.x + (relPhysX / scaleX).roundToInt()
        val logY = monitor.logicalBounds.y + (relPhysY / scaleY).roundToInt()
        val logW = (physicalRect.width / scaleX).roundToInt().coerceAtLeast(1)
        val logH = (physicalRect.height / scaleY).roundToInt().coerceAtLeast(1)

        return Rectangle(logX, logY, logW, logH)
    }

    /**
     * Converts a rectangle from logical AWT/Compose desktop coordinates to physical screen coordinates.
     */
    fun logicalToPhysical(logicalRect: Rectangle): Rectangle {
        val monitor = findMonitorForLogicalRect(logicalRect)
            ?: monitorsProvider().firstOrNull()
            ?: return logicalRect

        val scaleX = if (monitor.scaleX > 0.0) monitor.scaleX else 1.0
        val scaleY = if (monitor.scaleY > 0.0) monitor.scaleY else 1.0

        val relLogX = logicalRect.x - monitor.logicalBounds.x
        val relLogY = logicalRect.y - monitor.logicalBounds.y

        val physX = monitor.physicalBounds.x + (relLogX * scaleX).roundToInt()
        val physY = monitor.physicalBounds.y + (relLogY * scaleY).roundToInt()
        val physW = (logicalRect.width * scaleX).roundToInt().coerceAtLeast(1)
        val physH = (logicalRect.height * scaleY).roundToInt().coerceAtLeast(1)

        return Rectangle(physX, physY, physW, physH)
    }

    /**
     * Clamps a rectangle within the combined virtual desktop bounds.
     */
    fun clampToVirtualDesktop(rect: Rectangle, isPhysical: Boolean = false): Rectangle {
        val monitors = monitorsProvider()
        if (monitors.isEmpty()) return rect

        val unionBounds = monitors.map { if (isPhysical) it.physicalBounds else it.logicalBounds }
            .reduce { a, b -> a.union(b) }

        val clampedX = max(unionBounds.x, min(unionBounds.x + unionBounds.width - 1, rect.x))
        val clampedY = max(unionBounds.y, min(unionBounds.y + unionBounds.height - 1, rect.y))
        val clampedW = max(1, min(unionBounds.x + unionBounds.width - clampedX, rect.width))
        val clampedH = max(1, min(unionBounds.y + unionBounds.height - clampedY, rect.height))

        return Rectangle(clampedX, clampedY, clampedW, clampedH)
    }

    fun findMonitorForPhysicalRect(rect: Rectangle): MonitorGeometry? {
        val monitors = monitorsProvider()
        return monitors.firstOrNull { it.physicalBounds.intersects(rect) }
            ?: monitors.minByOrNull { distanceSquared(it.physicalBounds, rect) }
    }

    fun findMonitorForLogicalRect(rect: Rectangle): MonitorGeometry? {
        val monitors = monitorsProvider()
        return monitors.firstOrNull { it.logicalBounds.intersects(rect) }
            ?: monitors.minByOrNull { distanceSquared(it.logicalBounds, rect) }
    }

    private fun distanceSquared(container: Rectangle, target: Rectangle): Double {
        val cx1 = container.centerX
        val cy1 = container.centerY
        val cx2 = target.centerX
        val cy2 = target.centerY
        val dx = cx1 - cx2
        val dy = cy1 - cy2
        return dx * dx + dy * dy
    }

    companion object {
        fun detectConnectedMonitors(): List<MonitorGeometry> = runCatching {
            val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
            env.screenDevices.mapIndexed { idx, device ->
                val bounds = device.defaultConfiguration.bounds
                val transform = device.defaultConfiguration.defaultTransform
                val sx = if (transform.scaleX > 0.0) transform.scaleX else 1.0
                val sy = if (transform.scaleY > 0.0) transform.scaleY else 1.0
                MonitorGeometry(
                    id = "MONITOR_$idx (${device.iDstring})",
                    logicalBounds = bounds,
                    scaleX = sx,
                    scaleY = sy
                )
            }
        }.getOrElse { emptyList() }
    }
}
