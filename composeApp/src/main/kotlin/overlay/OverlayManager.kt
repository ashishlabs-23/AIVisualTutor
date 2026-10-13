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

    var isClickThroughEnabled by mutableStateOf(false)
        private set

    var activeHighlightRegion by mutableStateOf<models.HighlightRegion?>(null)
        private set

    fun showOverlay(region: models.HighlightRegion? = null) {
        if (region != null) {
            activeHighlightRegion = region
        }
        isOverlayVisible = true
        isClickThroughEnabled = true
    }

    fun hideOverlay() {
        isOverlayVisible = false
        isClickThroughEnabled = false
        activeHighlightRegion = null
    }

    fun updateClickThrough(enabled: Boolean) {
        isClickThroughEnabled = enabled
    }
}
