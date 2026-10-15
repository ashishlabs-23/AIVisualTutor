package tutor

import chat.ConversationSource

/**
 * Observable UI state representing the conversation coordinator lifecycle.
 */
sealed interface TutorConversationState {
    /**
     * Idle state: ready to receive new questions or follow-ups.
     */
    object Idle : TutorConversationState

    /**
     * Thinking state: an asynchronous conversational query is currently in flight.
     */
    data class Thinking(
        val requestId: String,
        val stepIndex: Int?,
        val queryText: String,
        val source: ConversationSource
    ) : TutorConversationState

    /**
     * Error state: the query failed due to timeout, network, or provider failure.
     */
    data class Error(
        val requestId: String,
        val message: String,
        val canRetry: Boolean = true,
        val lastQuery: String? = null,
        val source: ConversationSource? = null,
        val stepIndex: Int? = null
    ) : TutorConversationState
}
