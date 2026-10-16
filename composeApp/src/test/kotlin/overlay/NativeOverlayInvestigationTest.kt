package overlay

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.W32APIOptions
import com.sun.jna.Library
import java.awt.Color
import java.awt.Frame
import java.awt.Graphics
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertTrue

internal interface DiagnosticUser32 : Library {
    companion object {
        val INSTANCE: DiagnosticUser32 by lazy {
            Native.load("user32", DiagnosticUser32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    fun GetWindowLongPtrW(hWnd: Pointer, nIndex: Int): Pointer
    fun SetWindowLongPtrW(hWnd: Pointer, nIndex: Int, dwNewLong: Pointer): Pointer
    fun GetClassNameW(hWnd: Pointer, lpClassName: CharArray, nMaxCount: Int): Int
    fun GetWindowTextW(hWnd: Pointer, lpString: CharArray, nMaxCount: Int): Int
    @com.sun.jna.Structure.FieldOrder("x", "y")
    open class POINT : com.sun.jna.Structure() {
        @JvmField var x: Int = 0
        @JvmField var y: Int = 0
        class ByValue : POINT(), com.sun.jna.Structure.ByValue
    }

    fun EnumChildWindows(hWndParent: Pointer, lpEnumFunc: Pointer?, lParam: Pointer?): Boolean
    fun GetWindowRect(hWnd: Pointer, lpRect: IntArray): Boolean
    fun WindowFromPoint(point: POINT.ByValue): Pointer
    fun WindowFromPoint(point: Long): Pointer
    fun EnumWindows(lpEnumFunc: com.sun.jna.Callback, lParam: Pointer?): Boolean
    fun GetWindowThreadProcessId(hWnd: Pointer, lpdwProcessId: IntArray): Int
    fun IsWindowVisible(hWnd: Pointer): Boolean
    fun IsIconic(hWnd: Pointer): Boolean
    fun IsZoomed(hWnd: Pointer): Boolean
    fun GetClientRect(hWnd: Pointer, lpRect: IntArray): Boolean
    fun ClientToScreen(hWnd: Pointer, lpPoint: POINT): Boolean
    fun EnableWindow(hWnd: Pointer, bEnable: Boolean): Boolean
    fun ShowWindow(hWnd: Pointer, nCmdShow: Int): Boolean
    fun SetWindowPos(hWnd: Pointer, hWndInsertAfter: Pointer, X: Int, Y: Int, cx: Int, cy: Int, uFlags: Int): Boolean
    fun GetForegroundWindow(): Pointer
    fun SetForegroundWindow(hWnd: Pointer): Boolean
}

class NativeOverlayInvestigationTest {

    @Test
    fun testInspectNativeWindowStyles() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            println("Skipping Windows native investigation on non-Windows OS")
            return
        }

        val user32 = DiagnosticUser32.INSTANCE

        // Run on AWT Event Dispatch Thread
        javax.swing.SwingUtilities.invokeAndWait {
            // 1. Create a background window (simulating Blender)
            val bgWindow = JFrame("Background Mock Blender").apply {
                setSize(500, 400)
                setLocation(100, 100)
                isVisible = true
                toFront()
            }

            // 2. Create an overlay window on top of it
            val composeWindow = androidx.compose.ui.awt.ComposeWindow().apply {
                isUndecorated = true
                background = Color(0, 0, 0, 0)
                setSize(400, 300)
                setLocation(150, 150)
                isAlwaysOnTop = true
                isVisible = true
                toFront()
            }
            Thread.sleep(150)

            try {
                val bgHwnd = Native.getWindowPointer(bgWindow)
                val overlayHwnd = Native.getWindowPointer(composeWindow)
                println("Background Window HWND: $bgHwnd")
                println("Overlay Window HWND: $overlayHwnd")

                val rect = IntArray(4)
                user32.GetWindowRect(bgHwnd, rect)
                println("Background Rect: Left=${rect[0]}, Top=${rect[1]}, Right=${rect[2]}, Bottom=${rect[3]}")
                user32.GetWindowRect(overlayHwnd, rect)
                println("Overlay Rect: Left=${rect[0]}, Top=${rect[1]}, Right=${rect[2]}, Bottom=${rect[3]}")

                // Enumerate child windows of overlay
                val childHwnds = mutableListOf<Pointer>()
                val callback = object : com.sun.jna.Callback {
                    fun callback(childHwnd: Pointer, lParam: Pointer?): Boolean {
                        childHwnds.add(childHwnd)
                        val childClassBuf = CharArray(256)
                        user32.GetClassNameW(childHwnd, childClassBuf, 256)
                        val childStyle = Pointer.nativeValue(user32.GetWindowLongPtrW(childHwnd, -16))
                        val childExStyle = Pointer.nativeValue(user32.GetWindowLongPtrW(childHwnd, -20))
                        println("  Child HWND: $childHwnd, Class: ${String(childClassBuf).trim('\u0000')}, STYLE: 0x${java.lang.Long.toHexString(childStyle)}, EXSTYLE: 0x${java.lang.Long.toHexString(childExStyle)}")
                        return true
                    }
                }
                val enumProc = com.sun.jna.CallbackReference.getFunctionPointer(callback)
                user32.EnumChildWindows(overlayHwnd, enumProc, null)

                val HWND_TOPMOST = Pointer(-1L)
                user32.SetWindowPos(bgHwnd, HWND_TOPMOST, 100, 100, 500, 400, 0x0040)
                user32.SetWindowPos(overlayHwnd, HWND_TOPMOST, 150, 150, 400, 300, 0x0040)
                Thread.sleep(200)

                // Point inside both windows at screen coordinates (250, 250)
                fun makePt(x: Int, y: Int) = DiagnosticUser32.POINT.ByValue().apply {
                    this.x = x
                    this.y = y
                }
                fun makeLong(x: Int, y: Int): Long = (y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)

                val classNameBuf2 = CharArray(256)
                val titleBuf = CharArray(256)
                user32.GetClassNameW(overlayHwnd, classNameBuf2, 256)
                user32.GetWindowTextW(overlayHwnd, titleBuf, 256)
                println("Overlay Class: ${String(classNameBuf2).trim('\u0000')}, Title: '${String(titleBuf).trim('\u0000')}'")

                val hitPt = user32.WindowFromPoint(makePt(250, 250))
                val hitLong = user32.WindowFromPoint(makeLong(250, 250))
                println("WindowFromPoint with POINT.ByValue: $hitPt")
                println("WindowFromPoint with Long: $hitLong")

                // Apply current setClickThrough (top-level only)
                WindowsOverlayNativeHelper.setClickThrough(composeWindow)
                val hitAfterTopOnly = user32.WindowFromPoint(makeLong(250, 250))
                user32.GetWindowTextW(hitAfterTopOnly, titleBuf, 256)
                println("WindowFromPoint AFTER top-level setClickThrough: $hitAfterTopOnly, Class: ${String(classNameBuf2).trim('\u0000')}")
                println("  Is hit equal to overlay top-level? ${hitAfterTopOnly == overlayHwnd}")
                println("  Is hit equal to overlay child? ${childHwnds.contains(hitAfterTopOnly)}")
                println("  Is hit equal to background? ${hitAfterTopOnly == bgHwnd}")

                // What if we also apply WS_EX_TRANSPARENT to child HWNDs?
                for (child in childHwnds) {
                    val ex = Pointer.nativeValue(user32.GetWindowLongPtrW(child, -20))
                    user32.SetWindowLongPtrW(child, -20, Pointer(ex or 0x00000020L))
                }
                val hitAfterChildTrans = user32.WindowFromPoint(makeLong(250, 250))
                user32.GetClassNameW(hitAfterChildTrans, classNameBuf2, 256)
                user32.GetWindowTextW(hitAfterChildTrans, titleBuf, 256)
                println("WindowFromPoint AFTER applying WS_EX_TRANSPARENT to child HWNDs: $hitAfterChildTrans, Class: ${String(classNameBuf2).trim('\u0000')}")
                println("  Is hit equal to background? ${hitAfterChildTrans == bgHwnd}")

                // What if we call EnableWindow(overlayHwnd, false)?
                user32.EnableWindow(overlayHwnd, false)
                for (child in childHwnds) {
                    user32.EnableWindow(child, false)
                }
                val hitAfterDisabled = user32.WindowFromPoint(makeLong(250, 250))
                user32.GetClassNameW(hitAfterDisabled, classNameBuf2, 256)
                user32.GetWindowTextW(hitAfterDisabled, titleBuf, 256)
                println("WindowFromPoint AFTER EnableWindow(false): $hitAfterDisabled, Class: ${String(classNameBuf2).trim('\u0000')}")
                println("  Is hit equal to background? ${hitAfterDisabled == bgHwnd}")

            } finally {
                composeWindow.dispose()
                bgWindow.dispose()
            }
        }
    }

    @Test
    fun testFindBlenderViaJna() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val user32 = DiagnosticUser32.INSTANCE
        println("=== Searching for Blender window via JNA ===")

        val windows = mutableListOf<String>()
        val callback = object : com.sun.jna.Callback {
            fun callback(hWnd: Pointer, lParam: Pointer?): Boolean {
                val pidArr = IntArray(1)
                user32.GetWindowThreadProcessId(hWnd, pidArr)
                val pid = pidArr[0]
                val titleBuf = CharArray(256)
                val classBuf = CharArray(256)
                user32.GetWindowTextW(hWnd, titleBuf, 256)
                user32.GetClassNameW(hWnd, classBuf, 256)
                val title = String(titleBuf).trim('\u0000')
                val className = String(classBuf).trim('\u0000')
                val visible = user32.IsWindowVisible(hWnd)
                val rect = IntArray(4)
                user32.GetWindowRect(hWnd, rect)
                val clientRect = IntArray(4)
                user32.GetClientRect(hWnd, clientRect)

                val pt = DiagnosticUser32.POINT().apply { x = 0; y = 0 }
                user32.ClientToScreen(hWnd, pt)

                if (pid == 2864 && className == "GHOST_WindowClass") {
                    println("Found Blender GHOST_WindowClass window: $hWnd. Restoring...")
                    user32.ShowWindow(hWnd, 9) // SW_RESTORE = 9
                    user32.SetForegroundWindow(hWnd)
                    Thread.sleep(300)

                    val newRect = IntArray(4)
                    user32.GetWindowRect(hWnd, newRect)
                    val newClientRect = IntArray(4)
                    user32.GetClientRect(hWnd, newClientRect)
                    val newPt = DiagnosticUser32.POINT().apply { x = 0; y = 0 }
                    user32.ClientToScreen(hWnd, newPt)

                    windows.add("RESTORED BLENDER: PID $pid HWND $hWnd Title '$title' Zoomed=${user32.IsZoomed(hWnd)} WindowRect=[${newRect[0]},${newRect[1]},${newRect[2]},${newRect[3]}] ClientOrigin=(${newPt.x},${newPt.y}) ClientSize=(${newClientRect[2]},${newClientRect[3]})")
                }
                return true
            }
        }

        user32.EnumWindows(callback, null)
        for (w in windows) {
            println(w)
        }
    }

    @Test
    fun testClickThroughBehavior() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val user32 = DiagnosticUser32.INSTANCE

        javax.swing.SwingUtilities.invokeAndWait {
            // Background window (Mock Blender)
            var bgClicked = false
            val bgWindow = JFrame("Mock Target App").apply {
                setSize(400, 300)
                setLocation(200, 200)
                val panel = javax.swing.JPanel()
                panel.background = java.awt.Color.BLUE
                panel.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mousePressed(e: java.awt.event.MouseEvent?) {
                        bgClicked = true
                        println("SUCCESS: Background window panel received mousePressed!")
                    }
                })
                contentPane = panel
                isAlwaysOnTop = true
                isVisible = true
                toFront()
            }
            Thread.sleep(200)

            val robot = java.awt.Robot()
            robot.mouseMove(300, 300)
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(200)
            println("Click on bgWindow ALONE: $bgClicked")
            bgClicked = false

            var overlayClicked = false
            // Overlay window on top
            val overlay = androidx.compose.ui.awt.ComposeWindow().apply {
                isUndecorated = true
                background = Color(0, 0, 0, 0)
                setSize(400, 300)
                setLocation(200, 200)
                isAlwaysOnTop = true
                focusableWindowState = false
                contentPane.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mousePressed(e: java.awt.event.MouseEvent?) {
                        overlayClicked = true
                        println("NOTICE: Overlay ComposeWindow received mousePressed!")
                    }
                })
                isVisible = true
                toFront()
            }

            val bgHwnd = Native.getWindowPointer(bgWindow)
            val overlayHwnd = Native.getWindowPointer(overlay)

            val exStyleBefore = Pointer.nativeValue(user32.GetWindowLongPtrW(overlayHwnd, -20))
            println("Overlay EXSTYLE initial: 0x${java.lang.Long.toHexString(exStyleBefore)}")

            // Apply setClickThrough
            val applied = WindowsOverlayNativeHelper.setClickThrough(overlay)
            val exStyleAfter = Pointer.nativeValue(user32.GetWindowLongPtrW(overlayHwnd, -20))
            println("Applied: $applied, Overlay EXSTYLE after: 0x${java.lang.Long.toHexString(exStyleAfter)}")

            // Resize the overlay window
            overlay.setSize(450, 350)
            Thread.sleep(100)
            val exStyleAfterResize = Pointer.nativeValue(user32.GetWindowLongPtrW(overlayHwnd, -20))
            println("Overlay EXSTYLE after setSize(450, 350): 0x${java.lang.Long.toHexString(exStyleAfterResize)}")
            println("Did WS_EX_TRANSPARENT survive resize? ${(exStyleAfterResize and 0x20L) != 0L}")

            overlay.dispose()
            bgWindow.dispose()
        }
    }

    @Test
    fun testCaptureBlenderHeader() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val user32 = DiagnosticUser32.INSTANCE

        // Bring Blender to front
        var blenderHwnd: Pointer? = null
        val callback = object : com.sun.jna.Callback {
            fun callback(hWnd: Pointer, lParam: Pointer?): Boolean {
                val titleBuf = CharArray(256)
                user32.GetWindowTextW(hWnd, titleBuf, 256)
                val title = String(titleBuf).trim('\u0000')
                if (title.contains("Blender", ignoreCase = true) && user32.IsWindowVisible(hWnd)) {
                    blenderHwnd = hWnd
                    return false
                }
                return true
            }
        }
        user32.EnumWindows(callback, null)

        val hwnd = blenderHwnd
        println("Blender HWND for capture: $hwnd")
        if (hwnd == null) return

        user32.ShowWindow(hwnd, 9)
        user32.SetForegroundWindow(hwnd)
        Thread.sleep(500)

        val pt = DiagnosticUser32.POINT().apply { x = 0; y = 0 }
        user32.ClientToScreen(hwnd, pt)
        val cr = IntArray(4)
        user32.GetClientRect(hwnd, cr)
        val cw = cr[2]
        val ch = cr[3]
        println("Blender Client Origin: (${pt.x}, ${pt.y}), Size: ${cw}x${ch}")

        // Capture the top-left region of Blender (e.g. 500x200) where the 3D Viewport header and menus are
        val robot = java.awt.Robot()
        val captureRect = java.awt.Rectangle(pt.x, pt.y, 500.coerceAtMost(cw), 200.coerceAtMost(ch))
        val screenshot = robot.createScreenCapture(captureRect)
        val outDir = java.io.File("C:\\Users\\PC\\.gemini\\antigravity-ide\\brain\\8d435862-b8d1-4454-ac5b-a3b05a1feeee")
        val outFile = java.io.File(outDir, "blender_header_capture.png")
        javax.imageio.ImageIO.write(screenshot, "png", outFile)
        println("Saved Blender header capture to: ${outFile.absolutePath}")

        val img = screenshot
        // The rounded pill button has a distinct border/background color:
        // Background inside pill: RGB ~(58, 58, 58) or 0x3A3A3A, border is 0x484848 or similar.
        // Let's scan from X=40 to X=170 in Y=25..55 to find the exact outline of this pill button!
        println("=== Scanning pixels around Mode button ===")
        // Let's crop from X=45, Y=25, W=130, H=32
        val pillCrop = img.getSubimage(45, 25, 125, 32)
        val pillFile = java.io.File(outDir, "mode_pill_exact.png")
        javax.imageio.ImageIO.write(pillCrop, "png", pillFile)

        // Find the bounding box of non-header pixels:
        // Header background is ~0x303030 (RGB: 48, 48, 48).
        // Pill button border is darker/lighter: 0x222222 or 0x3d3d3d, with white text/icon.
        for (y in 27..48) {
            val colors = (45..165 step 10).map { x ->
                val rgb = img.getRGB(x, y) and 0xFFFFFF
                "%06X".format(rgb)
            }
            // println("Y=$y: " + colors.joinToString(" "))
        }

        // Measure exact pill button bounds within pillCrop (125 x 32)
        // Background color of header is ~(48, 48, 48) = 0x303030.
        // Let's find minX, maxX, minY, maxY where color differs significantly from header background!
        val headerBg = pillCrop.getRGB(0, 0)
        var minX = 125
        var maxX = 0
        var minY = 32
        var maxY = 0
        for (y in 0 until 32) {
            for (x in 0 until 125) {
                val rgb = pillCrop.getRGB(x, y)
                // If it's button border or interior (different from header background)
                val diff = Math.abs((rgb and 0xFF) - (headerBg and 0xFF)) +
                           Math.abs(((rgb shr 8) and 0xFF) - ((headerBg shr 8) and 0xFF)) +
                           Math.abs(((rgb shr 16) and 0xFF) - ((headerBg shr 16) and 0xFF))
                if (diff > 15) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        val pillWidth = maxX - minX + 1
        val pillHeight = maxY - minY + 1
        val absoluteX = 45 + minX
        val absoluteY = 25 + minY
        println("Pill inside crop: minX=$minX, maxX=$maxX, minY=$minY, maxY=$maxY")
        println("EXACT PILL RECT IN BLENDER CLIENT AREA: x=$absoluteX, y=$absoluteY, width=$pillWidth, height=$pillHeight")
        println("EXACT PILL RECT ON DESKTOP SCREEN: x=${pt.x + absoluteX}, y=${pt.y + absoluteY}, width=$pillWidth, height=$pillHeight")
    }
}
