package ui.avatar

import androidx.compose.ui.window.application

/**
 * Standalone executable entry point for validating the Phase 1 Native Window Proof
 * without launching the full tutoring application.
 */
fun main() {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        if (System.getProperty("skiko.renderApi") == null) {
            System.setProperty("skiko.renderApi", "SOFTWARE_FAST")
        }
    }

    application {
        AvatarWindowPrototype(
            onClose = ::exitApplication,
            initialState = AvatarPresentationState.GUIDING
        )
    }
}
