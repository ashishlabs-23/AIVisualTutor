package overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Controls visibility of the transparent overlay window.
 *
 * The overlay window applies native WS_EX_TRANSPARENT click-through; this
 * class owns visibility only. The separate close control remains interactive.
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
