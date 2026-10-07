package tutor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import models.ApplicationType
import models.HighlightRegion
import models.TutorStep

class TutorControllerTest {
    @Test
    fun mockStepsProvideNonBlankInstructionForTargetDescriptionSource() {
        listOf(ApplicationType.BLENDER, ApplicationType.PDF, ApplicationType.EXCEL).forEach { application ->
            val steps = MockTutorData.stepsFor(application)
            assertTrue(steps.all { it.instruction.isNotBlank() })
        }
    }

    @Test
    fun currentTargetDescriptionTracksCurrentStepAndRemainsAbsentForBlankInstruction() {
        val controller = TutorController()
        assertEquals(controller.currentStep.instruction, controller.currentTargetDescription)
        controller.nextStep()
        assertEquals(controller.currentStep.instruction, controller.currentTargetDescription)
        assertEquals(null, controller.targetDescriptionForStep(TutorStep(
            id = 1, title = "No target", instruction = " ", description = "",
            stepNumber = 1, totalSteps = 1
        )))
    }


    @Test
    fun startsAtFirstBlenderStepWithItsHighlight() {
        val controller = TutorController()

        assertEquals(ApplicationType.BLENDER, controller.state.selectedApplication)
        assertEquals(0, controller.state.currentStepIndex)
        assertEquals("Open the Add menu.", controller.currentStep.instruction)
        assertNotNull(controller.state.currentHighlight)
    }

    @Test
    fun selectingEachApplicationResetsToItsFirstStepAndHighlight() {
        val controller = TutorController()
        controller.nextStep()

        val expectedFirstInstructions = mapOf(
            ApplicationType.PDF to "Locate the target section.",
            ApplicationType.EXCEL to "Select cell B2.",
            ApplicationType.BLENDER to "Open the Add menu."
        )

        expectedFirstInstructions.forEach { (application, instruction) ->
            controller.selectApplication(application)

            assertEquals(application, controller.state.selectedApplication)
            assertEquals(0, controller.state.currentStepIndex)
            assertEquals(instruction, controller.currentStep.instruction)
            assertEquals(controller.currentStep.targetRegion, controller.state.currentHighlight)
        }
    }

    @Test
    fun nextAndPreviousStayWithinWorkflowAndUpdateHighlight() {
        val controller = TutorController()

        controller.previousStep()
        assertEquals(0, controller.state.currentStepIndex)

        repeat(10) { controller.nextStep() }
        assertEquals(4, controller.state.currentStepIndex)
        assertEquals(controller.currentStep.targetRegion, controller.state.currentHighlight)

        repeat(10) { controller.previousStep() }
        assertEquals(0, controller.state.currentStepIndex)
        assertEquals(controller.currentStep.targetRegion, controller.state.currentHighlight)
    }

    @Test
    fun pauseAndScreenAssistanceControlsToggleIndependently() {
        val controller = TutorController()

        assertFalse(controller.state.isPaused)
        assertTrue(controller.state.screenAssistanceEnabled)

        controller.togglePause()
        controller.toggleScreenAssistance()

        assertTrue(controller.state.isPaused)
        assertFalse(controller.state.screenAssistanceEnabled)

        controller.togglePause()
        controller.toggleScreenAssistance()

        assertFalse(controller.state.isPaused)
        assertTrue(controller.state.screenAssistanceEnabled)
    }

    @Test
    fun manualRegionReplacesCurrentMockHighlight() {
        val controller = TutorController()
        val selectedRegion = HighlightRegion(x = 12f, y = 24f, width = 80f, height = 40f)

        controller.setHighlight(selectedRegion)

        assertEquals(selectedRegion, controller.state.currentHighlight)
    }
}
