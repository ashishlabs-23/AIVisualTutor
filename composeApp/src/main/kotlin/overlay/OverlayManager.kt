package overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Controls visibility of the transparent overlay window.
 *
 * True mouse click-through is not implemented: a transparent top-level
 * window can still receive mouse input in its transparent areas. Phase 1
 * provides an explicit close control on the overlay so it can always be
 * dismissed without depending on the tutor window being reachable.
 */
class OverlayManager {

    var isOverlayVisible by mutableStateOf(false)
        private set

    fun showOverlay() {
        isOverlayVisible = true
    }

    fun hideOverlay() {
        isOverlayVisible = false
    }
}
