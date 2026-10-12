package models

import context.ExpectedState
import pedagogy.LearningTask

/**
 * A single step in a tutorial workflow.
 *
 * [targetRegion] is optional because not every step needs an on-screen
 * highlight (e.g. a purely instructional step). When present, it is used
 * to draw the highlight rectangle and arrow on the overlay window.
 */
data class TutorStep(
    val id: Int,
    val title: String,
    val instruction: String,
    val description: String,
    val stepNumber: Int,
    val totalSteps: Int,
    val targetRegion: HighlightRegion? = null,
    val taskId: String = "step_$id",
    val skillId: String = "general",
    val conceptualHint: String? = null,
    val reflectionQuestion: String? = null,
    val targetControlName: String? = null,
    val targetAreaDescription: String? = null,
    val expectedState: ExpectedState? = null,
    val supportsStage3: Boolean = true
) {
    fun toLearningTask(): LearningTask = LearningTask(
        taskId = taskId,
        skillId = skillId,
        title = title,
        objective = description,
        targetControlName = targetControlName,
        targetAreaDescription = targetAreaDescription,
        exactInstruction = instruction,
        conceptualHint = conceptualHint
            ?: "Accomplish '$title' by locating the appropriate command in the application interface.",
        reflectionQuestion = reflectionQuestion
            ?: "What command or control in the application interface allows you to '${title.lowercase()}'?",
        expectedState = expectedState,
        targetRegion = targetRegion,
        supportsStage3 = supportsStage3,
        stepIndex = stepNumber - 1
    )
}
