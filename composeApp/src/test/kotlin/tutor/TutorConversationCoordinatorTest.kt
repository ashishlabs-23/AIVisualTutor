package tutor

import chat.ChatContext
import chat.ChatMessage
import chat.ChatResponse
import chat.ChatSender
import chat.ChatbotProvider
import chat.ConversationSource
import context.ExpectedState
import context.VerificationOperation
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import models.ApplicationType

class TutorConversationCoordinatorTest {

    private class FakeChatbotProvider(
        var replyGenerator: (ChatContext) -> ChatResponse = { ctx ->
            ChatResponse(
                replyText = "Advice for ${ctx.question}",
                targetControl = "ToolBar",
                suggestedAction = null
            )
        }
    ) : ChatbotProvider {
        override suspend fun askTutor(context: ChatContext): ChatResponse {
            return replyGenerator(context)
        }
    }

    @Test
    fun `message history survives simulated UI observer disposal and recreation`(): Unit = runBlocking {
        val controller = TutorController(loadMockTutorialByDefault = true)
        val coordinator = controller.conversationCoordinator

        // Initial state has welcome message
        assertEquals(1, coordinator.messages.size)
        assertEquals(ChatSender.TUTOR, coordinator.messages[0].sender)

        // UI Observer 1 (e.g. ChatbotPanel) interacts
        val observer1MessagesSnapshot = coordinator.messages.toList()
        coordinator.askTutor("Where is the Add menu?", source = ConversationSource.WORKSPACE_PANEL)

        assertEquals(3, coordinator.messages.size) // Welcome + User + Tutor
        assertEquals("Where is the Add menu?", coordinator.messages[1].text)
        assertEquals(ChatSender.USER, coordinator.messages[1].sender)
        assertEquals(ChatSender.TUTOR, coordinator.messages[2].sender)

        // Simulate UI disposal (Observer 1 destroyed)
        // Observer 2 (e.g. user toggles drawer closed and reopened) connects to coordinator
        val observer2Messages = coordinator.messages
        assertEquals(3, observer2Messages.size)
        assertEquals(coordinator.messages[1].id, observer2Messages[1].id)
        assertEquals(coordinator.messages[2].id, observer2Messages[2].id)
    }

    @Test
    fun `request cancellation via cancelActiveQuery restores Idle without losing step`(): Unit = runBlocking {
        val deferred = CompletableDeferred<ChatResponse>()
        val slowProvider = FakeChatbotProvider {
            runBlocking { deferred.await() }
        }

        val controller = TutorController(loadMockTutorialByDefault = true)
        val coordinator = TutorConversationCoordinator(
            controller = controller,
            chatbotProvider = slowProvider
        )

        val initialStep = controller.state.currentStepIndex

        // Launch query in background coroutine
        val job = launch {
            coordinator.askTutor("Tell me more about this step", source = ConversationSource.AVATAR_BUBBLE)
        }

        // Wait briefly for Thinking state
        var tries = 0
        while (coordinator.state !is TutorConversationState.Thinking && tries++ < 20) {
            delay(20)
        }
        assertTrue(coordinator.isThinking, "Expected coordinator to be in Thinking state")
        assertTrue(coordinator.state is TutorConversationState.Thinking)

        // Cancel active query
        coordinator.cancelActiveQuery()

        assertEquals(TutorConversationState.Idle, coordinator.state)
        assertFalse(coordinator.isThinking)
        // Verify active step remains preserved
        assertEquals(initialStep, controller.state.currentStepIndex)

        // Complete the deferred to unblock provider
        deferred.complete(ChatResponse(replyText = "Late response"))
        job.join()

        // Verify no stale tutor reply was appended
        val tutorReplies = coordinator.messages.filter { it.sender == ChatSender.TUTOR && it.text == "Late response" }
        assertTrue(tutorReplies.isEmpty(), "Late response must not be recorded after cancellation")
    }

    @Test
    fun `stale response rejection after step change prevents context contamination`(): Unit = runBlocking {
        val deferred = CompletableDeferred<ChatResponse>()
        val slowProvider = FakeChatbotProvider {
            runBlocking { deferred.await() }
        }

        val controller = TutorController(loadMockTutorialByDefault = true)
        val coordinator = TutorConversationCoordinator(
            controller = controller,
            chatbotProvider = slowProvider
        )

        assertEquals(0, controller.state.currentStepIndex)

        // Start query on Step 0
        val queryJob = launch {
            coordinator.askTutor("Explain Step 0", source = ConversationSource.AVATAR_BUBBLE)
        }

        var tries = 0
        while (coordinator.state !is TutorConversationState.Thinking && tries++ < 20) {
            delay(20)
        }
        assertTrue(coordinator.isThinking)

        // User navigates to Step 1 before response arrives
        controller.nextStep()
        assertEquals(1, controller.state.currentStepIndex)

        // Complete delayed response for Step 0
        deferred.complete(ChatResponse(replyText = "Detailed explanation of Step 0"))
        queryJob.join()

        // Assert response was rejected and does NOT contaminate Step 1 explanation
        assertNull(coordinator.latestExplanationText, "Step 0 explanation must not contaminate Step 1")
        val step1TutorMessages = coordinator.messages.filter { it.stepIndex == 1 && it.sender == ChatSender.TUTOR }
        assertTrue(step1TutorMessages.isEmpty(), "Step 0 reply must not be attributed to Step 1")
    }

    @Test
    fun `stale response rejection after goal change`(): Unit = runBlocking {
        val deferred = CompletableDeferred<ChatResponse>()
        val slowProvider = FakeChatbotProvider {
            runBlocking { deferred.await() }
        }

        val controller = TutorController(loadMockTutorialByDefault = false)
        val coordinator = TutorConversationCoordinator(
            controller = controller,
            chatbotProvider = slowProvider
        )

        controller.startUserGoal("Create a UV sphere")
        val initialGoalId = controller.activePlan?.goalText

        val queryJob = launch {
            coordinator.askTutor("How do I make the sphere?", source = ConversationSource.WORKSPACE_PANEL)
        }

        var tries = 0
        while (coordinator.state !is TutorConversationState.Thinking && tries++ < 20) {
            delay(20)
        }
        assertTrue(coordinator.isThinking)

        // User switches goal entirely
        controller.startUserGoal("Make a cube and move it up")

        deferred.complete(ChatResponse(replyText = "Sphere advice"))
        queryJob.join()

        // Assert obsolete sphere advice does not contaminate new goal
        val cubeMessages = coordinator.messages.filter { it.text == "Sphere advice" }
        assertTrue(cubeMessages.isEmpty(), "Obsolete goal query must be discarded")
    }

    @Test
    fun `loading state and duplicate click prevention`(): Unit = runBlocking {
        val deferred = CompletableDeferred<ChatResponse>()
        val slowProvider = FakeChatbotProvider {
            runBlocking { deferred.await() }
        }

        val controller = TutorController(loadMockTutorialByDefault = true)
        val coordinator = TutorConversationCoordinator(
            controller = controller,
            chatbotProvider = slowProvider
        )

        // First click
        launch {
            coordinator.askTutor("Query 1", source = ConversationSource.WORKSPACE_PANEL)
        }

        var tries = 0
        while (coordinator.state !is TutorConversationState.Thinking && tries++ < 20) {
            delay(20)
        }
        assertTrue(coordinator.isThinking)

        // Second click while first is thinking
        val secondAccepted = coordinator.askTutor("Query 2", source = ConversationSource.WORKSPACE_PANEL)
        assertFalse(secondAccepted, "Second query must be rejected while first is thinking")

        // Unblock first
        deferred.complete(ChatResponse(replyText = "Response 1"))
    }

    @Test
    fun `error state and retry support`(): Unit = runBlocking {
        var shouldFail = true
        val flakyProvider = FakeChatbotProvider { ctx ->
            if (shouldFail) {
                throw IOException("Network timeout connecting to Groq AI")
            } else {
                ChatResponse(replyText = "Success advice for ${ctx.question}")
            }
        }

        val controller = TutorController(loadMockTutorialByDefault = true)
        val coordinator = TutorConversationCoordinator(
            controller = controller,
            chatbotProvider = flakyProvider
        )

        coordinator.askTutor("How do I extrude?", source = ConversationSource.WORKSPACE_PANEL)

        val errState = coordinator.state as? TutorConversationState.Error
        assertNotNull(errState, "Expected coordinator to enter Error state")
        assertTrue(errState.canRetry)
        assertEquals("How do I extrude?", errState.lastQuery)
        assertTrue(errState.message.contains("Network timeout"))

        // System message recorded
        val lastMsg = coordinator.messages.last()
        assertEquals(ChatSender.SYSTEM, lastMsg.sender)
        assertTrue(lastMsg.isError)

        // Now fix network and retry
        shouldFail = false
        val retryJob = coordinator.retryLastQuery()
        assertNotNull(retryJob)
        retryJob.join()

        assertEquals(TutorConversationState.Idle, coordinator.state)
        val finalMsg = coordinator.messages.last()
        assertEquals(ChatSender.TUTOR, finalMsg.sender)
        assertTrue(finalMsg.text.contains("Success advice for How do I extrude?"))
    }

    @Test
    fun `existing single-turn Explain More compatibility`(): Unit = runBlocking {
        val provider = FakeChatbotProvider { ctx ->
            ChatResponse(
                replyText = "Step explanation: To create the sphere, open Add menu and select UV Sphere.",
                explanation = "UV spheres are basic quad/tri mesh primitives in Blender."
            )
        }

        val controller = TutorController(loadMockTutorialByDefault = true)
        val coordinator = TutorConversationCoordinator(
            controller = controller,
            chatbotProvider = provider
        )

        assertNull(coordinator.latestExplanationText)

        val explainJob = coordinator.explainCurrentStep()
        explainJob.join()

        assertNotNull(coordinator.latestExplanationText)
        assertTrue(coordinator.latestExplanationText!!.contains("UV Sphere"))

        // Dismiss explanation
        coordinator.clearActiveExplanation()
        assertNull(coordinator.latestExplanationText)
    }

    @Test
    fun `isolation between different tutoring sessions`(): Unit = runBlocking {
        val controller1 = TutorController(loadMockTutorialByDefault = true)
        val controller2 = TutorController(loadMockTutorialByDefault = true)

        val coord1 = controller1.conversationCoordinator
        val coord2 = controller2.conversationCoordinator

        coord1.askTutor("Session 1 specific question", source = ConversationSource.WORKSPACE_PANEL)

        assertTrue(coord1.messages.any { it.text == "Session 1 specific question" })
        assertFalse(coord2.messages.any { it.text == "Session 1 specific question" })
    }

    @Test
    fun `conversational output cannot advance or verify task`(): Unit = runBlocking {
        // Provider that claims the task is complete and suggests AUTO_VERIFY
        val deceptiveProvider = FakeChatbotProvider {
            ChatResponse(
                replyText = "Great job! I have verified your action and the task is complete!",
                targetControl = "Cube",
                suggestedAction = "AUTO_VERIFY"
            )
        }

        val controller = TutorController(loadMockTutorialByDefault = false)
        controller.startUserGoal("Create a UV sphere")

        val coordinator = TutorConversationCoordinator(
            controller = controller,
            chatbotProvider = deceptiveProvider
        )

        val initialStepIndex = controller.state.currentStepIndex
        assertEquals(0, initialStepIndex)
        val initialProgress = controller.goalProgressState

        // User asks if they did it right
        coordinator.askTutor("Did I finish the step?", source = ConversationSource.AVATAR_BUBBLE)

        // Verify message was recorded
        val tutorReply = coordinator.messages.last()
        assertEquals(ChatSender.TUTOR, tutorReply.sender)
        assertTrue(tutorReply.text.contains("verified your action"))

        // CRITICAL ARCHITECTURAL INVARIANT:
        // Conversational advice MUST NOT independently advance steps or mark verification!
        assertEquals(initialStepIndex, controller.state.currentStepIndex, "Step index must NOT advance from conversational output")
        assertTrue(controller.goalProgressState.completedStepIds.isEmpty(), "No step IDs should be marked complete by conversational output")
        assertEquals(GoalCompletionStatus.NOT_STARTED, controller.goalProgressState.completionStatus)
    }
}
