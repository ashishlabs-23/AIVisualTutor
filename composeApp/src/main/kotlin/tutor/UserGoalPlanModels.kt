package tutor

import context.ExpectedState
import models.ApplicationType
import models.HighlightRegion
import models.TutorStep

/**
 * Represents a structured, ordered tutorial plan generated for a user-specified goal.
 *
 * NOTE: This is part of the initial deterministic, rule-based planning layer,
 * not full general AI or cloud-based planning.
 */
data class UserGoalPlan(
    val goalText: String,
    val application: ApplicationType = ApplicationType.BLENDER,
    val steps: List<UserGoalPlanStep>
) {
    fun toTutorSteps(): List<TutorStep> = steps.map { it.toTutorStep(steps.size) }
}

/**
 * A single step within a [UserGoalPlan].
 */
data class UserGoalPlanStep(
    val stepIndex: Int,
    val title: String,
    val instruction: String,
    val description: String = instruction,
    val targetControlName: String? = null,
    val expectedState: ExpectedState? = null,
    val conceptualHint: String? = null,
    val fallbackHint: String? = null,
    val reflectionQuestion: String? = null,
    val targetRegion: HighlightRegion? = null
) {
    fun toTutorStep(totalSteps: Int): TutorStep = TutorStep(
        id = stepIndex,
        title = title,
        instruction = instruction,
        description = description,
        stepNumber = stepIndex,
        totalSteps = totalSteps,
        targetRegion = targetRegion,
        taskId = "goal_step_${stepIndex}_${title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}",
        skillId = "blender.user_goal",
        conceptualHint = conceptualHint ?: fallbackHint,
        reflectionQuestion = reflectionQuestion,
        targetControlName = targetControlName,
        expectedState = expectedState
    )
}
