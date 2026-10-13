package chat

import context.ApplicationContext
import context.ObservedState
import java.awt.image.BufferedImage
import tutor.TutorController

/**
 * Builds minimal, relevant context for tutor conversational queries
 * without needlessly transferring large full-screen screenshots.
 */
class ChatContextBuilder {

    fun buildContext(
        question: String,
        controller: TutorController,
        appContext: ApplicationContext? = null,
        observedState: ObservedState? = null,
        screenshotCrop: BufferedImage? = null,
        attachVisualContext: Boolean = false,
        chatHistory: List<ChatMessage> = emptyList()
    ): ChatContext {
        val step = controller.currentStep
        val appName = appContext?.applicationName ?: controller.state.selectedApplication.name

        val stateSummary = buildStateSummary(observedState)

        val shouldAttachVisual = attachVisualContext && screenshotCrop != null

        return ChatContext(
            question = question.trim(),
            applicationName = appName,
            applicationMode = observedState?.mode,
            applicationStateSummary = stateSummary,
            currentTaskTitle = step?.title ?: "User Goal Guidance",
            currentStepNumber = step?.stepNumber,
            totalSteps = step?.totalSteps,
            activeInstruction = controller.state.adaptiveInstruction?.instructionText ?: step?.instruction,
            recentChatHistory = chatHistory.takeLast(6),
            screenshotCrop = if (shouldAttachVisual) screenshotCrop else null,
            includeVisualContext = shouldAttachVisual
        )
    }

    private fun buildStateSummary(state: ObservedState?): String? {
        if (state == null) return null
        val parts = mutableListOf<String>()
        if (state.mode != null) parts += "Mode: ${state.mode}"
        val activeObj = state.objects.firstOrNull { it.selected == true } ?: state.objects.firstOrNull()
        if (activeObj != null) {
            parts += "Target Object: ${activeObj.name} (${activeObj.type})"
            if (activeObj.metadata.isNotEmpty()) {
                val loc = activeObj.metadata["location"]
                if (loc != null) parts += "Location: $loc"
            }
        }
        parts += "Total Scene Objects: ${state.objects.size}"
        return parts.joinToString("; ")
    }
}
