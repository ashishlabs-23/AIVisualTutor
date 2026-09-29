package models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContractsTest {

    @Test
    fun sessionContextInitializesWithDefaultValues() {
        val session = SessionContext(application = ApplicationType.BLENDER)

        assertNotNull(session.sessionId)
        assertEquals(ApplicationType.BLENDER, session.application)
        assertTrue(session.startTimeMillis > 0)
    }

    @Test
    fun capturedFrameStoresMetadataCorrectly() {
        val region = HighlightRegion(10f, 20f, 100f, 50f)
        val frame = CapturedFrame(
            filePath = "test.png",
            metadata = FrameMetadata(
                sourceMode = CaptureSourceMode.MANUAL_REGION_ROBOT,
                bounds = region,
                scaleFactor = 1.5f
            )
        )

        assertEquals("test.png", frame.filePath)
        assertEquals(CaptureSourceMode.MANUAL_REGION_ROBOT, frame.metadata.sourceMode)
        assertEquals(region, frame.metadata.bounds)
        assertEquals(1.5f, frame.metadata.scaleFactor)
    }

    @Test
    fun visualGuidanceHoldsCorrectState() {
        val region = HighlightRegion(50f, 60f, 200f, 100f)
        val guidance = VisualGuidance(
            targetRegion = region,
            stepLabel = "Step 1",
            isVisible = true
        )

        assertEquals(region, guidance.targetRegion)
        assertEquals("Step 1", guidance.stepLabel)
        assertTrue(guidance.isVisible)
    }

    @Test
    fun tutorEventsPreserveDataAndTimestamps() {
        val appEvent = TutorEvent.ApplicationChanged(ApplicationType.EXCEL)
        val stepEvent = TutorEvent.StepAdvanced(0, 1)

        assertEquals(ApplicationType.EXCEL, appEvent.application)
        assertTrue(appEvent.timestampMillis > 0)
        assertEquals(0, stepEvent.fromIndex)
        assertEquals(1, stepEvent.toIndex)
    }
}
