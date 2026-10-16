package overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ui.avatar.AvatarPresentationState
import ui.avatar.ScreenPoint

/**
 * Controls visibility and synchronization of the transparent overlay window.
 *
 * The overlay window applies native WS_EX_TRANSPARENT click-through; this
 * class owns visibility and real-time avatar guidance coordination.
 */
class OverlayManager {

    var isOverlayVisible by mutableStateOf(false)
        private set

    var isClickThroughEnabled by mutableStateOf(false)
        private set

    var activeHighlightRegion by mutableStateOf<models.HighlightRegion?>(null)
        private set

    var avatarEmitterScreenPoint by mutableStateOf<ScreenPoint?>(null)
        private set

    var isAvatarPointingActive by mutableStateOf(false)
        private set

    var avatarPresentationState by mutableStateOf(AvatarPresentationState.IDLE)
        private set

    var isGlowEnabled by mutableStateOf(true)
        private set

    fun updateGlowEnabled(enabled: Boolean) {
        isGlowEnabled = enabled
    }

    fun toggleGlow() {
        isGlowEnabled = !isGlowEnabled
    }

    fun updateAvatarGuidance(
        emitterPoint: ScreenPoint?,
        isPointing: Boolean,
        state: AvatarPresentationState = AvatarPresentationState.IDLE
    ) {
        avatarEmitterScreenPoint = emitterPoint
        isAvatarPointingActive = isPointing
        avatarPresentationState = state
    }

    fun showOverlay(region: models.HighlightRegion? = null) {
        if (region != null) {
            activeHighlightRegion = region
        }
        isGlowEnabled = true
        isOverlayVisible = true
        isClickThroughEnabled = true
    }

    fun hideOverlay() {
        isOverlayVisible = false
        isClickThroughEnabled = false
        activeHighlightRegion = null
    }

    /**
     * Clears any step-specific active highlight region so that when tutorial steps
     * transition, stale target coordinates from previous steps are not retained.
     */
    fun clearActiveRegion() {
        activeHighlightRegion = null
    }

    fun setHighlightRegion(region: models.HighlightRegion?) {
        activeHighlightRegion = region
    }

    fun updateClickThrough(enabled: Boolean) {
        isClickThroughEnabled = enabled
    }
}
