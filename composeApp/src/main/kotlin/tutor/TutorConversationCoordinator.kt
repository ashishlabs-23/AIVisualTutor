package tutor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chat.ChatContextBuilder
import chat.ChatMessage
import chat.ChatResponse
import chat.ChatSender
import chat.ChatbotProvider
import chat.ConversationSource
import chat.SmartChatbotProvider
import java.awt.image.BufferedImage
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Domain-owned conversation coordinator for Phase 4.1.
 *
 * Responsibilities:
 * 1. Owns the single authoritative conversation message history across UI lifecycles.
 * 2. Manages request lifecycle (Thinking, Idle, Error) and explicit cancellation.
 * 3. Enforces session and step isolation: rejects stale responses after task switches.
 * 4. Preserves backward-compatible single-turn "Explain More" behavior.
 * 5. Strictly non-authoritative: conversational outputs can never advance or verify tutoring tasks.
 */
class TutorConversationCoordinator(
    private val controller: TutorController,
    val chatbotProvider: ChatbotProvider = SmartChatbotProvider(),
    val contextBuilder: ChatContextBuilder = ChatContextBuilder(),
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    /**
     * Authoritative reactive list of chat messages for the tutoring session.
     */
    val messages = mutableStateListOf<ChatMessage>().apply {
        add(
            ChatMessage(
                sender = ChatSender.TUTOR,
                text = "Hello! I am your AI Visual Tutor assistant. Ask me anything if you get stuck, and I can mark the overlay glow on your screen and track your actions!"
            )
        )
    }

    /**
     * Observable lifecycle state.
     */
    var state: TutorConversationState by mutableStateOf(TutorConversationState.Idle)
        internal set

    /**
     * Convenience helper indicating whether a query is actively processing.
     */
    val isThinking: Boolean
        get() = state is TutorConversationState.Thinking

    /**
     * Observable latest explanation text for the active step (consumed by the avatar bubble).
     */
    var latestExplanationText: String? by mutableStateOf(null)
        internal set

    private val actionMutex = Mutex()
    private var activeJob: Job? = null
    private var inFlightRequestId: String? = null
    private var inFlightStepIndex: Int? = null
    private var inFlightSessionId: String? = null
    private var inFlightGoalId: String? = null

    /**
     * Submits a conversational query to the intelligent tutor provider.
     * Guards against duplicate clicks and tags the message with active step and goal identifiers.
     */
    suspend fun askTutor(
        query: String,
        source: ConversationSource = ConversationSource.WORKSPACE_PANEL,
        attachVisualContext: Boolean = false,
        screenshotCrop: BufferedImage? = null,
        onResponseAction: (suspend (ChatResponse) -> Unit)? = null
    ): Boolean {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return false

        val requestId = UUID.randomUUID().toString()
        val currentStep = controller.currentStep
        val currentStepIndex = controller.state.currentStepIndex
        val currentSessionId = controller.sessionContext.sessionId
        val currentGoalId = controller.activePlan?.let { "plan_${it.goalText.hashCode()}" }

        actionMutex.withLock {
            if (state is TutorConversationState.Thinking) {
                return false // Duplicate click prevention
            }

            inFlightRequestId = requestId
            inFlightStepIndex = currentStepIndex
            inFlightSessionId = currentSessionId
            inFlightGoalId = currentGoalId

            val userMsg = ChatMessage(
                sender = ChatSender.USER,
                text = trimmed,
                stepIndex = currentStepIndex,
                goalId = currentGoalId,
                source = source
            )
            messages.add(userMsg)

            state = TutorConversationState.Thinking(
                requestId = requestId,
                stepIndex = currentStepIndex,
                queryText = trimmed,
                source = source
            )
        }

        val job = coroutineScope.launch {
            try {
                val context = contextBuilder.buildContext(
                    question = trimmed,
                    controller = controller,
                    screenshotCrop = screenshotCrop ?: controller.sessionContext.lastVisualContext?.image,
                    attachVisualContext = attachVisualContext,
                    chatHistory = messages.filter { it.stepIndex == currentStepIndex || it.stepIndex == null }.toList()
                )

                val response = chatbotProvider.askTutor(context)

                actionMutex.withLock {
                    // Stale response check: verify active step, session, and goal have not changed
                    val isStillActiveStep = controller.state.currentStepIndex == currentStepIndex
                    val isStillActiveSession = controller.sessionContext.sessionId == currentSessionId
                    val activeGoalKey = controller.activePlan?.let { "plan_${it.goalText.hashCode()}" }
                    val isStillActiveGoal = activeGoalKey == currentGoalId

                    if (inFlightRequestId != requestId || !isStillActiveStep || !isStillActiveSession || !isStillActiveGoal) {
                        // Obsolete response discarded
                        return@launch
                    }

                    val replyText = response.replyText.ifBlank {
                        response.explanation ?: "Focus on: ${currentStep?.instruction ?: "the active task."}"
                    }

                    val tutorMsg = ChatMessage(
                        sender = ChatSender.TUTOR,
                        text = replyText,
                        targetControl = response.targetControl ?: currentStep?.targetControlName,
                        suggestedAction = response.suggestedAction ?: if (currentStep?.expectedState != null) "AUTO_VERIFY" else null,
                        stepIndex = currentStepIndex,
                        goalId = currentGoalId,
                        source = source
                    )
                    messages.add(tutorMsg)
                    latestExplanationText = replyText
                    state = TutorConversationState.Idle
                    inFlightRequestId = null
                    inFlightStepIndex = null
                }

                onResponseAction?.invoke(response)
            } catch (cancelled: CancellationException) {
                actionMutex.withLock {
                    if (inFlightRequestId == requestId) {
                        state = TutorConversationState.Idle
                        inFlightRequestId = null
                        inFlightStepIndex = null
                    }
                }
            } catch (ex: Exception) {
                actionMutex.withLock {
                    if (inFlightRequestId == requestId) {
                        state = TutorConversationState.Error(
                            requestId = requestId,
                            message = ex.message ?: "Failed to communicate with tutor.",
                            canRetry = true,
                            lastQuery = trimmed,
                            source = source,
                            stepIndex = currentStepIndex
                        )
                        messages.add(
                            ChatMessage(
                                sender = ChatSender.SYSTEM,
                                text = "Error communicating with tutor: ${ex.message}",
                                isError = true,
                                stepIndex = currentStepIndex,
                                goalId = currentGoalId,
                                source = source
                            )
                        )
                        inFlightRequestId = null
                        inFlightStepIndex = null
                    }
                }
            }
        }

        activeJob = job
        job.join()
        return true
    }

    /**
     * Single-turn "Explain More" helper triggered by the avatar bubble.
     */
    fun explainCurrentStep(): Job {
        val step = controller.currentStep
        if (step == null) {
            latestExplanationText = "Start a goal or load a tutorial step to see in-depth concept guidance."
            return Job().apply { complete() }
        }
        val query = "Explain step: ${step.title}. ${step.instruction}"
        return coroutineScope.launch {
            askTutor(query = query, source = ConversationSource.AVATAR_BUBBLE)
        }
    }

    /**
     * Clears the active step explanation shown in the avatar bubble.
     */
    fun clearActiveExplanation() {
        latestExplanationText = null
    }

    /**
     * Explicitly cancels the active in-flight query without modifying tutoring state.
     */
    fun cancelActiveQuery() {
        activeJob?.cancel()
        activeJob = null
        inFlightRequestId = null
        inFlightStepIndex = null
        if (state is TutorConversationState.Thinking) {
            state = TutorConversationState.Idle
        }
    }

    /**
     * Notification from [TutorController] when the tutorial step changes.
     * Cancels any in-flight query to prevent stale answers and updates the active explanation.
     */
    fun onStepChanged(newStepIndex: Int) {
        cancelActiveQuery()
        latestExplanationText = messages
            .filter { it.sender == ChatSender.TUTOR && it.stepIndex == newStepIndex }
            .lastOrNull()?.text
    }

    /**
     * Notification from [TutorController] when a goal is started or cleared.
     */
    fun onGoalChanged() {
        cancelActiveQuery()
        latestExplanationText = null
    }

    /**
     * Retries the failed query if the coordinator is in an Error state.
     */
    fun retryLastQuery(): Job? {
        val err = state as? TutorConversationState.Error ?: return null
        val query = err.lastQuery ?: return null
        val src = err.source ?: ConversationSource.WORKSPACE_PANEL
        return coroutineScope.launch {
            askTutor(query = query, source = src)
        }
    }

    /**
     * Manually adds a message (e.g., plan generation summary) to the authoritative history.
     */
    fun addMessage(message: ChatMessage) {
        messages.add(message)
    }

    /**
     * Clears all conversational history and resets coordinator state.
     */
    fun clearHistory() {
        cancelActiveQuery()
        messages.clear()
        latestExplanationText = null
        state = TutorConversationState.Idle
    }
}
