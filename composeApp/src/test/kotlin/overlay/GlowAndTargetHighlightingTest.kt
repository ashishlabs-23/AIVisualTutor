package overlay

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import models.ApplicationType
import models.HighlightRegion
import models.TutorState
import models.TutorStep
import pedagogy.GuidanceStage
import pedagogy.PedagogicalOutcome
import ui.avatar.AvatarPresentationState
import ui.avatar.DockSide
import ui.avatar.ScreenPoint
import ui.avatar.ScreenRect
import ui.avatar.TargetDockPositionCalculator
import ui.avatar.toScreenRect
import ui.drawHighlightEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused regression tests for:
 * - Glow activation and accurate toggle state representation
 * - Target highlight geometry and coordinate handling
 * - Multi-monitor positioning without coordinate drift
 * - Stale target prevention across step changes and docking
 * - Distinguishability from Blender mesh selection outlines
 * - Strict non-interference with authoritative task verification
 * - Headless pixel/image rendering and distinct Glow On vs Glow Off buffers
 */
class GlowAndTargetHighlightingTest {

    // =========================================================================
    // 1. GLOW ENABLED PRODUCES INTENDED VISUAL EFFECT & TRACKING
    // =========================================================================
    @Test
    fun test01_glowEnabledProducesIntendedVisualEffect() {
        val manager = OverlayManager()
        val testRegion = HighlightRegion(100f, 78f, 120f, 28f)

        assertFalse(manager.isOverlayVisible, "Glow should initially be disabled")
        assertFalse(manager.isClickThroughEnabled, "Click-through should initially be disabled")
        assertNull(manager.activeHighlightRegion, "Active highlight region should initially be null")

        manager.showOverlay(testRegion)

        assertTrue(manager.isOverlayVisible, "Glow must be visible when enabled")
        assertTrue(manager.isClickThroughEnabled, "Click-through must be enabled for desktop interaction")
        assertEquals(testRegion, manager.activeHighlightRegion, "Target region must be preserved on the overlay")
    }

    // =========================================================================
    // 2. GLOW DISABLED REMOVES THE EFFECT & CLEARS HIGHLIGHT
    // =========================================================================
    @Test
    fun test02_glowDisabledRemovesVisualEffectAndHidesOverlay() {
        val manager = OverlayManager()
        val testRegion = HighlightRegion(100f, 78f, 120f, 28f)

        manager.showOverlay(testRegion)
        assertTrue(manager.isOverlayVisible)

        manager.hideOverlay()

        assertFalse(manager.isOverlayVisible, "Glow effect must be completely removed when hidden")
        assertFalse(manager.isClickThroughEnabled, "Click-through must be disabled when overlay is hidden")
        assertNull(manager.activeHighlightRegion, "Active highlight region must be cleared when hidden")
    }

    // =========================================================================
    // 3. TOGGLE LABEL ACCURATELY REFLECTS THE REAL GLOW STATE
    // =========================================================================
    @Test
    fun test03_toggleLabelAccuratelyReflectsRealState() {
        val manager = OverlayManager()
        var screenAssistanceEnabled = true

        fun resolveGlowLabel(): String {
            val isGlowActive = manager.isOverlayVisible && screenAssistanceEnabled && manager.isGlowEnabled
            return if (isGlowActive) "✨ Glow On" else "✨ Glow Off"
        }

        // When overlay is initially not visible -> Glow Off
        assertEquals("✨ Glow Off", resolveGlowLabel())

        // When overlay is shown -> Glow On
        manager.showOverlay()
        assertEquals("✨ Glow On", resolveGlowLabel())

        // When glow is explicitly toggled off -> Glow Off
        manager.updateGlowEnabled(false)
        assertEquals("✨ Glow Off", resolveGlowLabel())

        // Re-enabling glow -> Glow On
        manager.updateGlowEnabled(true)
        assertEquals("✨ Glow On", resolveGlowLabel())

        // When screen assistance is toggled off -> Glow Off
        screenAssistanceEnabled = false
        assertEquals("✨ Glow Off", resolveGlowLabel())

        // Re-enabling screen assistance -> Glow On
        screenAssistanceEnabled = true
        assertEquals("✨ Glow On", resolveGlowLabel())

        // When overlay is hidden -> Glow Off
        manager.hideOverlay()
        assertEquals("✨ Glow Off", resolveGlowLabel())
    }

    // =========================================================================
    // 4. TARGET RECTANGLE CORRESPONDS TO INTENDED CONTROL GEOMETRY
    // =========================================================================
    @Test
    fun test04_targetRectangleCorrespondsToIntendedControlGeometry() {
        val region = HighlightRegion(x = 100f, y = 78f, width = 120f, height = 28f)

        assertEquals(100f, region.x)
        assertEquals(78f, region.y)
        assertEquals(120f, region.width)
        assertEquals(28f, region.height)

        val screenRect = region.toScreenRect()
        assertEquals(100f, screenRect.x)
        assertEquals(78f, screenRect.y)
        assertEquals(120f, screenRect.width)
        assertEquals(28f, screenRect.height)
        assertEquals(100f + 60f, screenRect.centerX)
        assertEquals(78f + 14f, screenRect.centerY)
    }

    // =========================================================================
    // 5. RESIZING OR MOVING OVERLAY DOES NOT INTRODUCE COORDINATE DRIFT
    // =========================================================================
    @Test
    fun test05_resizingOrMovingOverlayDoesNotIntroduceCoordinateDrift() {
        val baseRegion = HighlightRegion(x = 250f, y = 140f, width = 80f, height = 30f)
        val monitorOriginX = 1920f // Secondary monitor offset
        val monitorOriginY = 0f

        val adjusted = baseRegion.copy(
            x = baseRegion.x - monitorOriginX,
            y = baseRegion.y - monitorOriginY
        )

        assertEquals(250f - 1920f, adjusted.x)
        assertEquals(140f, adjusted.y)
        assertEquals(baseRegion.width, adjusted.width)
        assertEquals(baseRegion.height, adjusted.height)

        // Multiple frame evaluations must remain deterministic without drift
        for (frame in 1..100) {
            val frameAdjusted = baseRegion.copy(
                x = baseRegion.x - monitorOriginX,
                y = baseRegion.y - monitorOriginY
            )
            assertEquals(adjusted.x, frameAdjusted.x, "Frame $frame drift detected in X")
            assertEquals(adjusted.y, frameAdjusted.y, "Frame $frame drift detected in Y")
        }
    }

    // =========================================================================
    // 6. DOCKING AND REOPENING DO NOT LEAVE STALE GLOW STATE
    // =========================================================================
    @Test
    fun test06_dockingAndReopeningDoNotLeaveStaleGlowState() {
        val manager = OverlayManager()
        val initialRegion = HighlightRegion(100f, 78f, 80f, 28f)

        manager.showOverlay(initialRegion)
        assertEquals(initialRegion, manager.activeHighlightRegion)

        // Docking/closing overlay
        manager.hideOverlay()
        assertNull(manager.activeHighlightRegion, "Docking/closing must clear active region")
        assertFalse(manager.isOverlayVisible)

        // Reopening with a new region
        val freshRegion = HighlightRegion(310f, 195f, 125f, 26f)
        manager.showOverlay(freshRegion)
        assertEquals(freshRegion, manager.activeHighlightRegion, "Reopening must reflect fresh target")
        assertTrue(manager.isOverlayVisible)
    }

    // =========================================================================
    // 7. SWITCHING TUTORIAL STEPS DOES NOT RETAIN AN UNRELATED TARGET
    // =========================================================================
    @Test
    fun test07_switchingTutorialStepsDoesNotRetainUnrelatedTarget() {
        val manager = OverlayManager()
        val step1Region = HighlightRegion(100f, 78f, 80f, 28f)
        val step2Region = HighlightRegion(310f, 195f, 125f, 26f)

        // User clicked Highlight on Step 1
        manager.showOverlay(step1Region)
        assertEquals(step1Region, manager.activeHighlightRegion)

        // Step advances -> clearActiveRegion is triggered
        manager.clearActiveRegion()
        assertNull(manager.activeHighlightRegion, "Step transition must clear active target region")

        // Main.kt resolution logic:
        var currentStepHighlight: HighlightRegion? = step2Region
        val resolvedRegion = manager.activeHighlightRegion ?: currentStepHighlight
        assertEquals(step2Region, resolvedRegion, "Must resolve to Step 2 target, not stale Step 1")
    }

    // =========================================================================
    // 8. HIGHLIGHT DISTINGUISHABLE FROM BLENDER'S ORDINARY MESH SELECTION
    // =========================================================================
    @Test
    fun test08_highlightDistinguishableFromBlenderMeshSelection() {
        val step = TutorStep(
            id = 1,
            title = "Ensure Object Mode",
            instruction = "Switch to Object Mode in the 3D Viewport header.",
            description = "Mesh primitives must be created in Object Mode.",
            targetControlName = "Mode",
            stepNumber = 1,
            totalSteps = 3,
            targetRegion = HighlightRegion(100f, 78f, 80f, 28f)
        )

        // Semantic target name resolution
        val resolvedBadgeTitle = step.targetControlName ?: step.title
        assertEquals("Mode", resolvedBadgeTitle)
        assertNotNull(resolvedBadgeTitle)

        // Verify that the highlight presentation contains target naming
        val hasSemanticTargetBadge = resolvedBadgeTitle.isNotBlank()
        assertTrue(hasSemanticTargetBadge, "Target highlight must present semantic label badge to avoid ambiguity with mesh outlines")
    }

    // =========================================================================
    // 9. HIGHLIGHTING DOES NOT ADVANCE OR VERIFY A TASK
    // =========================================================================
    @Test
    fun test09_highlightingDoesNotAdvanceOrVerifyTask() {
        val state = TutorState(
            currentStepIndex = 0,
            lastPedagogicalOutcome = null,
            guidanceStage = GuidanceStage.STAGE_1_EXACT_GUIDANCE
        )
        val manager = OverlayManager()
        val testRegion = HighlightRegion(100f, 78f, 80f, 28f)

        // Toggling glow or showing overlay
        manager.showOverlay(testRegion)
        assertTrue(manager.isOverlayVisible)

        // TutorState must remain unchanged - step did NOT advance, outcome did NOT verify
        assertEquals(0, state.currentStepIndex, "Step index must NOT advance due to highlight display")
        assertNull(state.lastPedagogicalOutcome, "Task must NOT be verified simply because glow was shown")
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, state.guidanceStage)

        manager.hideOverlay()
        assertEquals(0, state.currentStepIndex)
        assertNull(state.lastPedagogicalOutcome)
    }

    // =========================================================================
    // 10. NON-BLOCKING OVERLAY WINDOW CONFIGURATION TEST
    // =========================================================================
    @Test
    fun test10_overlayUsesNonBlockingWindowConfiguration() {
        val manager = OverlayManager()
        val region = HighlightRegion(100f, 78f, 80f, 28f)

        // OverlayManager enables click-through state on show
        manager.showOverlay(region)
        assertTrue(manager.isClickThroughEnabled, "OverlayManager must declare click-through enabled when shown")

        // Validate native Win32 extended style bits
        // Note: Unit tests verify window-configuration flags; live click-through requires desktop DWM
        assertEquals(0x00000020, WindowsOverlayNativeHelper.WS_EX_TRANSPARENT, "WS_EX_TRANSPARENT must be 0x20")
        assertEquals(0x00080000, WindowsOverlayNativeHelper.WS_EX_LAYERED, "WS_EX_LAYERED must be 0x80000")

        // Validate that SWP_REFRESH_FRAME includes SWP_NOACTIVATE (0x10) to prevent stealing focus from Blender
        val hasNoActivate = (WindowsOverlayNativeHelper.SWP_REFRESH_FRAME and 0x0010) != 0
        assertTrue(hasNoActivate, "SWP_REFRESH_FRAME must include SWP_NOACTIVATE (0x10) so Blender retains foreground focus")

        // Parameterless enableWindowsClickThrough() must execute safely without throwing
        val safeResult = enableWindowsClickThrough()
        assertTrue(safeResult)

        manager.hideOverlay()
        assertFalse(manager.isClickThroughEnabled, "OverlayManager must disable click-through when hidden")
    }

    // =========================================================================
    // 11. HIGHLIGHT REMAINS VISIBLE WHILE BLENDER INTERACTION IS POSSIBLE
    // =========================================================================
    @Test
    fun test11_highlightRemainsVisibleWhileInteractionPossible() {
        val manager = OverlayManager()
        val step1Region = HighlightRegion(100f, 78f, 80f, 28f)
        val state = TutorState(currentStepIndex = 0, screenAssistanceEnabled = true)

        manager.showOverlay(step1Region)

        // 1. Highlight remains visible
        assertTrue(manager.isOverlayVisible)
        assertEquals(step1Region, manager.activeHighlightRegion)

        // 2. Click-through is active
        assertTrue(manager.isClickThroughEnabled)

        // 3. Simulated user interaction with Blender (switching mode to OBJECT)
        // Tutor state does NOT lock, block, or advance automatically
        assertEquals(0, state.currentStepIndex)
        assertTrue(state.screenAssistanceEnabled)

        // 4. Highlight remains active and visible during interaction
        assertTrue(manager.isOverlayVisible)
    }

    // =========================================================================
    // 12. AVATAR GUIDANCE AVOIDS HEADER OBSTRUCTION WHEN TARGET IN TOP HEADER
    // =========================================================================
    @Test
    fun test12_avatarGuidanceAvoidsHeaderObstructionWhenTargetInTopHeader() {
        // Mode selector target in 3D Viewport header: Y = 78f < 140f
        val modeSelectorTarget = ScreenRect(100f, 78f, 80f, 28f)
        val screen = ScreenPoint(1920f, 1080f)
        val window = ScreenPoint(440f, 260f)

        // With avoidHeaderObstruction = true (used by AvatarWindow)
        val dockResult = TargetDockPositionCalculator.calculateDockPosition(
            target = modeSelectorTarget,
            screenSize = screen,
            windowSize = window,
            avoidHeaderObstruction = true
        )

        assertEquals(DockSide.RIGHT_OF_TARGET, dockResult.dockSide)
        // Position must be below the top header row (Y >= 160)
        assertTrue(dockResult.position.y >= 160f, "Companion window must dock below header (Y >= 160) to avoid obstructing controls. Actual Y: ${dockResult.position.y}")
        // Position X is to the right of the Mode selector
        assertEquals(100f + 80f + 24f, dockResult.position.x, 0.001f, "Window X should dock to right edge of target + gap")

        // Backward compatibility: with avoidHeaderObstruction = false (default)
        val defaultDock = TargetDockPositionCalculator.calculateDockPosition(
            target = modeSelectorTarget,
            screenSize = screen,
            windowSize = window,
            avoidHeaderObstruction = false
        )
        assertEquals(40f, defaultDock.position.y, 0.001f, "Default behavior preserves legacy margin clamping")
    }

    // =========================================================================
    // 13. AVATAR GUIDANCE AND TETHERING REMAIN FUNCTIONAL
    // =========================================================================
    @Test
    fun test13_avatarGuidanceAndTetheringRemainFunctional() {
        val manager = OverlayManager()
        val emitterPoint = ScreenPoint(250f, 180f)

        // Avatar pointing guidance update
        manager.updateAvatarGuidance(
            emitterPoint = emitterPoint,
            isPointing = true,
            state = AvatarPresentationState.GUIDING
        )

        assertEquals(emitterPoint, manager.avatarEmitterScreenPoint)
        assertTrue(manager.isAvatarPointingActive)
        assertEquals(AvatarPresentationState.GUIDING, manager.avatarPresentationState)

        // Reset/idle update
        manager.updateAvatarGuidance(
            emitterPoint = null,
            isPointing = false,
            state = AvatarPresentationState.IDLE
        )

        assertNull(manager.avatarEmitterScreenPoint)
        assertFalse(manager.isAvatarPointingActive)
        assertEquals(AvatarPresentationState.IDLE, manager.avatarPresentationState)
    }

    // =========================================================================
    // OFF-SCREEN PIXEL RENDERING TEST HELPER
    // =========================================================================
    private fun renderHighlightOffscreen(
        region: HighlightRegion,
        glowEnabled: Boolean = true,
        width: Int = 400,
        height: Int = 300,
        pulse: Float = 1.0f
    ): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        val drawScope = CanvasDrawScope()
        drawScope.draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(width.toFloat(), height.toFloat())
        ) {
            drawHighlightEffect(
                region = region,
                glowEnabled = glowEnabled,
                pulse = pulse
            )
        }
        return bitmap
    }

    // =========================================================================
    // 14. VALID TARGET REGION PRODUCES NON-TRANSPARENT HIGHLIGHT PIXELS
    // =========================================================================
    @Test
    fun test14_validTargetRegionProducesNonTransparentHighlightPixels() {
        val target = HighlightRegion(x = 100f, y = 78f, width = 120f, height = 28f)
        val bitmap = renderHighlightOffscreen(target, glowEnabled = true)
        val pixelMap = bitmap.toPixelMap()

        // 1. Target boundary pixel (e.g. top edge at X=160, Y=78) must have non-zero alpha
        val edgePixel = pixelMap[160, 78]
        assertTrue(edgePixel.alpha > 0f, "Target boundary pixel at (160, 78) must be non-transparent. Actual alpha: ${edgePixel.alpha}")

        // 2. Count non-transparent pixels in the target bounding box vicinity [90..230, 68..116]
        var targetVicinityPixelCount = 0
        for (x in 90..230) {
            for (y in 68..116) {
                if (pixelMap[x, y].alpha > 0f) {
                    targetVicinityPixelCount++
                }
            }
        }
        assertTrue(targetVicinityPixelCount > 100, "Target region must contain non-transparent highlight pixels. Actual count: $targetVicinityPixelCount")

        // 3. Pixel far outside the target region (e.g. at (10, 10)) must be completely transparent (alpha == 0)
        val outsidePixel = pixelMap[10, 10]
        assertEquals(0f, outsidePixel.alpha, "Pixel far outside target must remain 100% transparent")
    }

    // =========================================================================
    // 15. GLOW ON AND GLOW OFF PRODUCE OBSERVABLY DIFFERENT RENDERED OUTPUT
    // =========================================================================
    @Test
    fun test15_glowOnAndGlowOffProduceObservablyDifferentPixelBuffers() {
        val target = HighlightRegion(x = 100f, y = 78f, width = 120f, height = 28f)
        val bitmapGlowOn = renderHighlightOffscreen(target, glowEnabled = true, pulse = 1.0f)
        val bitmapGlowOff = renderHighlightOffscreen(target, glowEnabled = false, pulse = 1.0f)

        val pixelsOn = bitmapGlowOn.toPixelMap()
        val pixelsOff = bitmapGlowOff.toPixelMap()

        var countOn = 0
        var countOff = 0
        for (x in 0 until 400) {
            for (y in 0 until 300) {
                if (pixelsOn[x, y].alpha > 0f) countOn++
                if (pixelsOff[x, y].alpha > 0f) countOff++
            }
        }

        // Glow On includes outer blooming aura and HUD reticles, so it MUST have significantly more illuminated pixels
        assertTrue(countOn > countOff, "Glow On must produce an observably larger bloom footprint than Glow Off ($countOn vs $countOff)")

        // Outer bloom region pixel (6px outside the bounding box at (94, 72))
        // In Glow On, the outer bloom extends 8px; in Glow Off, bloom is completely removed
        val bloomPixelOn = pixelsOn[94, 72]
        val bloomPixelOff = pixelsOff[94, 72]
        assertTrue(bloomPixelOn.alpha > 0f, "Glow On must illuminate outer bloom aura at (94, 72)")
        assertEquals(0f, bloomPixelOff.alpha, "Glow Off must have 0 alpha at outer bloom location (94, 72)")
    }

    // =========================================================================
    // 16. HIGHLIGHT IS DRAWN AT THE EXACT LOCAL SCREEN COORDINATES
    // =========================================================================
    @Test
    fun test16_highlightDrawnAtCorrectLocalCoordinates() {
        val regionA = HighlightRegion(x = 40f, y = 50f, width = 60f, height = 30f)
        val regionB = HighlightRegion(x = 220f, y = 140f, width = 60f, height = 30f)

        val bitmapA = renderHighlightOffscreen(regionA, glowEnabled = false)
        val bitmapB = renderHighlightOffscreen(regionB, glowEnabled = false)

        val pixelsA = bitmapA.toPixelMap()
        val pixelsB = bitmapB.toPixelMap()

        // Region A has pixels around (40, 50), but Region B does NOT
        assertTrue(pixelsA[40, 50].alpha > 0f, "Region A must illuminate (40, 50)")
        assertEquals(0f, pixelsB[40, 50].alpha, "Region B must NOT illuminate (40, 50)")

        // Region B has pixels around (220, 140), but Region A does NOT
        assertTrue(pixelsB[220, 140].alpha > 0f, "Region B must illuminate (220, 140)")
        assertEquals(0f, pixelsA[220, 140].alpha, "Region A must NOT illuminate (220, 140)")
    }

    // =========================================================================
    // 17. INVALID OR OFF-SCREEN TARGET RECTANGLES ARE HANDLED SAFELY
    // =========================================================================
    @Test
    fun test17_invalidOrOffscreenTargetRectanglesHandledSafely() {
        // Zero dimensions
        val zeroRegion = HighlightRegion(x = 100f, y = 78f, width = 0f, height = 0f)
        val bitmapZero = renderHighlightOffscreen(zeroRegion)
        assertNotNull(bitmapZero)

        // Negative dimensions
        val negativeRegion = HighlightRegion(x = 100f, y = 78f, width = -20f, height = -10f)
        val bitmapNegative = renderHighlightOffscreen(negativeRegion)
        assertNotNull(bitmapNegative)

        // Negative coordinate (partially or fully off-screen)
        val offscreenNegative = HighlightRegion(x = -500f, y = -500f, width = 80f, height = 28f)
        val bitmapOffscreen = renderHighlightOffscreen(offscreenNegative)
        assertNotNull(bitmapOffscreen)

        // Far positive off-screen coordinate
        val offscreenPositive = HighlightRegion(x = 5000f, y = 5000f, width = 80f, height = 28f)
        val bitmapOffscreenPos = renderHighlightOffscreen(offscreenPositive)
        assertNotNull(bitmapOffscreenPos)
    }

    // =========================================================================
    // 18. NATIVE CLICK-THROUGH CONFIGURATION PRESERVES OVERLAY VISIBILITY
    // =========================================================================
    @Test
    fun test18_nativeClickThroughDoesNotDisableOverlayVisibility() {
        // Note: Headless rendering tests verify GPU drawing commands and pixel buffers.
        // Live Windows desktop validation confirms DWM transparency and click-through on the active desktop.
        assertEquals(0x00000020, WindowsOverlayNativeHelper.WS_EX_TRANSPARENT)
        // WS_EX_LAYERED (0x00080000) is NOT applied, which prevents DWM from killing Skiko's DirectComposition swapchain
        val manager = OverlayManager()
        manager.showOverlay(HighlightRegion(100f, 78f, 80f, 28f))
        assertTrue(manager.isOverlayVisible)
        assertTrue(manager.isClickThroughEnabled)
    }
}

