package overlay

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.W32APIOptions
import java.awt.Window

/**
 * Direct Win32 User32 interface loaded via JNA.
 *
 * Exposes window style manipulation functions needed to apply native
 * WS_EX_TRANSPARENT click-through behavior on Windows without invoking
 * external processes or breaking DirectComposition alpha transparency.
 */
internal interface WindowsOverlayUser32 : Library {
    companion object {
        val INSTANCE: WindowsOverlayUser32 by lazy {
            Native.load("user32", WindowsOverlayUser32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    fun GetWindowLongW(hWnd: Pointer, nIndex: Int): Int
    fun SetWindowLongW(hWnd: Pointer, nIndex: Int, dwNewLong: Int): Int
    fun GetWindowLongPtrW(hWnd: Pointer, nIndex: Int): Pointer
    fun SetWindowLongPtrW(hWnd: Pointer, nIndex: Int, dwNewLong: Pointer): Pointer
    fun SetWindowPos(hWnd: Pointer, hWndInsertAfter: Pointer?, X: Int, Y: Int, cx: Int, cy: Int, uFlags: Int): Boolean
    fun EnumChildWindows(hWndParent: Pointer, lpEnumFunc: com.sun.jna.Callback, lParam: Pointer?): Boolean
    fun EnableWindow(hWnd: Pointer, bEnable: Boolean): Boolean
}

/**
 * Manages native transparent click-through window styles for Compose Desktop overlays on Windows.
 *
 * Applying [WS_EX_TRANSPARENT] (0x00000020) causes Windows DWM and DefWindowProc to return
 * HTTRANSPARENT during WM_NCHITTEST hit-testing. This routes all mouse input (clicks, drags,
 * scrolls, hover) directly through to the application beneath (Blender) while keeping the
 * visual graphics (glowing borders, reticles, labels) fully rendered and visible.
 *
 * Also applies [WS_EX_NOACTIVATE] (0x08000000) so that pointer interactions never steal foreground
 * focus from Blender.
 */
object WindowsOverlayNativeHelper {
    const val GWL_EXSTYLE = -20
    const val WS_EX_TRANSPARENT = 0x00000020
    const val WS_EX_LAYERED = 0x00080000
    const val WS_EX_NOACTIVATE = 0x08000000

    // SWP_NOSIZE (0x1) | SWP_NOMOVE (0x2) | SWP_NOZORDER (0x4) | SWP_NOACTIVATE (0x10) | SWP_FRAMECHANGED (0x20)
    const val SWP_REFRESH_FRAME = 0x0037

    /**
     * Applies WS_EX_TRANSPARENT and WS_EX_NOACTIVATE to the native HWND backing the given AWT/Compose [window]
     * and its child rendering surfaces (SunAwtCanvas).
     *
     * Returns true if successfully applied, or false if not on Windows or if HWND was unavailable.
     */
    fun setClickThrough(window: Window): Boolean {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            return false
        }

        return try {
            val hwnd = Native.getWindowPointer(window) ?: return false
            val user32 = WindowsOverlayUser32.INSTANCE
            val is64 = Native.POINTER_SIZE == 8

            val currentExStyle = if (is64) {
                Pointer.nativeValue(user32.GetWindowLongPtrW(hwnd, GWL_EXSTYLE))
            } else {
                user32.GetWindowLongW(hwnd, GWL_EXSTYLE).toLong()
            }

            val targetExStyle = currentExStyle or WS_EX_TRANSPARENT.toLong() or WS_EX_NOACTIVATE.toLong()

            if (currentExStyle != targetExStyle) {
                if (is64) {
                    user32.SetWindowLongPtrW(hwnd, GWL_EXSTYLE, Pointer(targetExStyle))
                } else {
                    user32.SetWindowLongW(hwnd, GWL_EXSTYLE, targetExStyle.toInt())
                }
                user32.SetWindowPos(hwnd, null, 0, 0, 0, 0, SWP_REFRESH_FRAME)
            }

            // Also propagate WS_EX_TRANSPARENT to any child HWNDs (e.g. SunAwtCanvas where Skiko paints)
            val childCallback = object : com.sun.jna.Callback {
                fun callback(childHwnd: Pointer, lParam: Pointer?): Boolean {
                    val childEx = if (is64) {
                        Pointer.nativeValue(user32.GetWindowLongPtrW(childHwnd, GWL_EXSTYLE))
                    } else {
                        user32.GetWindowLongW(childHwnd, GWL_EXSTYLE).toLong()
                    }
                    val childTarget = childEx or WS_EX_TRANSPARENT.toLong()
                    if (childEx != childTarget) {
                        if (is64) {
                            user32.SetWindowLongPtrW(childHwnd, GWL_EXSTYLE, Pointer(childTarget))
                        } else {
                            user32.SetWindowLongW(childHwnd, GWL_EXSTYLE, childTarget.toInt())
                        }
                    }
                    return true
                }
            }
            user32.EnumChildWindows(hwnd, childCallback, null)

            true
        } catch (ex: Throwable) {
            System.err.println("Notice: Could not set native Windows overlay click-through: ${ex.message}")
            false
        }
    }

    /**
     * Reads the current native extended window style bits (GWL_EXSTYLE) of the given [window].
     */
    fun getExtendedStyle(window: Window): Long? {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return null
        return try {
            val hwnd = Native.getWindowPointer(window) ?: return null
            val user32 = WindowsOverlayUser32.INSTANCE
            if (Native.POINTER_SIZE == 8) {
                Pointer.nativeValue(user32.GetWindowLongPtrW(hwnd, GWL_EXSTYLE))
            } else {
                user32.GetWindowLongW(hwnd, GWL_EXSTYLE).toLong()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns true if WS_EX_TRANSPARENT is currently active on the native window.
     */
    fun isClickThrough(window: Window): Boolean {
        val exStyle = getExtendedStyle(window) ?: return false
        return (exStyle and WS_EX_TRANSPARENT.toLong()) != 0L
    }
}
