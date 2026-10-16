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

    @Test
    fun avatarGuidanceCoordinationTracksEmitterAndStateSimultaneously() {
        val manager = OverlayManager()
        val emitter = ui.avatar.ScreenPoint(1200f, 450f)

        // Initially inactive
        kotlin.test.assertNull(manager.avatarEmitterScreenPoint)
        assertFalse(manager.isAvatarPointingActive)
        kotlin.test.assertEquals(ui.avatar.AvatarPresentationState.IDLE, manager.avatarPresentationState)

        // Update when pointing at Sphere in Blender
        manager.updateAvatarGuidance(
            emitterPoint = emitter,
            isPointing = true,
            state = ui.avatar.AvatarPresentationState.POINTING
        )

        kotlin.test.assertEquals(emitter, manager.avatarEmitterScreenPoint)
        assertTrue(manager.isAvatarPointingActive)
        kotlin.test.assertEquals(ui.avatar.AvatarPresentationState.POINTING, manager.avatarPresentationState)

        // Verify state change to SUCCESS updates presentation state simultaneously
        manager.updateAvatarGuidance(
            emitterPoint = emitter,
            isPointing = false,
            state = ui.avatar.AvatarPresentationState.SUCCESS
        )
        assertFalse(manager.isAvatarPointingActive)
        kotlin.test.assertEquals(ui.avatar.AvatarPresentationState.SUCCESS, manager.avatarPresentationState)
    }
}


