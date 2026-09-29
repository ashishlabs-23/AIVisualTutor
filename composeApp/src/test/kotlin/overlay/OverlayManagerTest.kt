package overlay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayManagerTest {

    @Test
    fun overlayVisibilityCanBeShownAndHidden() {
        val manager = OverlayManager()

        assertFalse(manager.isOverlayVisible)
        manager.showOverlay()
        assertTrue(manager.isOverlayVisible)
        manager.hideOverlay()
        assertFalse(manager.isOverlayVisible)
    }

    @Test
    fun virtualScreenBoundsReturnsValidDimensions() {
        val bounds = getVirtualScreenBounds()
        assertTrue(bounds.width > 0)
        assertTrue(bounds.height > 0)
    }
}


