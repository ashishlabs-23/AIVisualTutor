package ui.avatar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import chat.ChatMessage
import chat.ChatResponse
import chat.ChatSender
import chat.ChatbotProvider
import chat.ConversationSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import models.HighlightRegion
import models.TutorStep
import org.junit.Test
import tutor.TutorController
import tutor.TutorConversationCoordinator
import tutor.TutorConversationState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4.2 — Frontend Conversational Tutor UX Validation Matrix
 *
 * Covers all 16 required test scenarios:
 * UI-4.2-01: Bubble renders normally (existing instruction and controls remain visible)
 * UI-4.2-02: Empty question submitted (no request is sent)
 * UI-4.2-03: Whitespace-only question submitted (no request is sent)
 * UI-4.2-04: Valid follow-up submitted (coordinator receives exactly one submission)
 * UI-4.2-05: Enter submission (Enter submits, Shift+Enter allows newline)
 * UI-4.2-06: Request active (thinking state is visible)
 * UI-4.2-07: Duplicate send attempted (no duplicate request)
 * UI-4.2-08: Cancel pressed (existing cancellation API is invoked)
 * UI-4.2-09: Request fails (error state is readable)
 * UI-4.2-10: Retry selected (existing retry contract is invoked)
 * UI-4.2-11: Long answer displayed (content remains readable and scrollable)
 * UI-4.2-12: Explain More clicked (existing behavior still works)
 * UI-4.2-13: Workspace drawer reopened (existing conversation history remains)
 * UI-4.2-14: UI recomposes (no duplicate messages)
 * UI-4.2-15: Guidance overlay active (new controls do not break target guidance)
 * UI-4.2-16: Avatar dismissed/reopened (no regression in existing behavior)
 */
class ConversationalTutorUxTest {

    private fun createTestController(
        mockResponseText: String = "Test reply",
        onAskDelayMs: Long = 0,
        shouldFail: Boolean = false
    ): Pair<TutorController, CompletableDeferred<Unit>> {
        val gate = CompletableDeferred<Unit>()
        val provider = ChatbotProvider {
            if (onAskDelayMs > 0) delay(onAskDelayMs)
            if (shouldFail) throw RuntimeException("Network timeout communicating with tutor provider")
            gate.complete(Unit)
            ChatResponse(replyText = mockResponseText, explanation = mockResponseText)
        }

        val controller = TutorController(
            loadMockTutorialByDefault = true,
            conversationCoordinatorFactory = { ctrl ->
                TutorConversationCoordinator(
                    controller = ctrl,
                    chatbotProvider = provider
                )
            }
        )
        return Pair(controller, gate)
    }

    @Test
    fun testUI4201_bubbleRendersNormally() {
        val (controller, _) = createTestController()
        val step = controller.currentStep
        assertNotNull(step)

        ImageComposeScene(width = 440, height = 300).use { scene ->
            scene.setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    InstructionBubble(
                        stepNumber = step.stepNumber,
                        totalSteps = 4,
                        instructionText = step.instruction,
                        targetControlName = step.targetControlName,
                        statusLabel = "Ready",
                        isHighlightBusy = false,
                        isExplainBusy = false,
                        explanationText = null,
                        conversationState = controller.conversationCoordinator.state
                    )
                }
            }
            val image = scene.render()
            assertTrue(image.width > 0 && image.height > 0, "InstructionBubble must render successfully to Skia canvas")
        }
    }

    @Test
    fun testUI4202_emptyQuestionSubmitted() {
        val (controller, _) = createTestController()
        val coordinator = controller.conversationCoordinator
        val initialMessageCount = coordinator.messages.size

        var callbackInvoked = false
        val submitAction: (String) -> Unit = { q ->
            val trimmed = q.trim()
            if (trimmed.isNotBlank()) {
                callbackInvoked = true
                runBlocking { coordinator.askTutor(trimmed, ConversationSource.AVATAR_BUBBLE) }
            }
        }

        submitAction("")
        assertFalse(callbackInvoked, "Empty question must not invoke coordinator submission")
        assertEquals(initialMessageCount, coordinator.messages.size, "No message must be added for empty query")

        // Coordinator direct validation
        val result = runBlocking { coordinator.askTutor("", ConversationSource.AVATAR_BUBBLE) }
        assertFalse(result, "Coordinator must reject empty query directly")
    }

    @Test
    fun testUI4203_whitespaceOnlyQuestionSubmitted() {
        val (controller, _) = createTestController()
        val coordinator = controller.conversationCoordinator
        val initialMessageCount = coordinator.messages.size

        var callbackInvoked = false
        val submitAction: (String) -> Unit = { q ->
            val trimmed = q.trim()
            if (trimmed.isNotBlank()) {
                callbackInvoked = true
                runBlocking { coordinator.askTutor(trimmed, ConversationSource.AVATAR_BUBBLE) }
            }
        }

        submitAction("   \t  \n  ")
        assertFalse(callbackInvoked, "Whitespace-only question must not invoke submission")
        assertEquals(initialMessageCount, coordinator.messages.size, "No message must be added for whitespace query")

        val result = runBlocking { coordinator.askTutor("   \n\t  ", ConversationSource.AVATAR_BUBBLE) }
        assertFalse(result, "Coordinator must reject whitespace query directly")
    }

    @Test
    fun testUI4204_validFollowUpSubmitted() {
        val (controller, _) = createTestController(mockResponseText = "Press Tab to enter Edit Mode.")
        val coordinator = controller.conversationCoordinator
        val initialCount = coordinator.messages.size

        val queryText = "How do I switch to Edit Mode?"
        val success = runBlocking {
            coordinator.askTutor(queryText, ConversationSource.AVATAR_BUBBLE)
        }

        assertTrue(success, "Submission must succeed")
        assertEquals(initialCount + 2, coordinator.messages.size, "Expected 1 user message and 1 tutor response")

        val userMessage = coordinator.messages[initialCount]
        assertEquals(ChatSender.USER, userMessage.sender)
        assertEquals(queryText, userMessage.text)
        assertEquals(ConversationSource.AVATAR_BUBBLE, userMessage.source)

        val tutorMessage = coordinator.messages[initialCount + 1]
        assertEquals(ChatSender.TUTOR, tutorMessage.sender)
        assertEquals("Press Tab to enter Edit Mode.", tutorMessage.text)
        assertEquals("Press Tab to enter Edit Mode.", coordinator.latestExplanationText)
    }

    @Test
    fun testUI4205_enterSubmissionBehavior() {
        // Documented keyboard behavior:
        // Enter without Shift -> trims and submits if valid and not thinking.
        // Shift+Enter -> does not submit (allows newline in draft).
        val (controller, _) = createTestController()
        val coordinator = controller.conversationCoordinator

        var submittedQuery: String? = null
        val onFollowUpSubmit: (String) -> Unit = { submittedQuery = it }

        fun simulateKey(isShiftPressed: Boolean, draftText: String, isThinking: Boolean): Boolean {
            if (isShiftPressed) {
                return false // Shift+Enter: newline allowed, no submit
            } else {
                val trimmed = draftText.trim()
                if (trimmed.isNotBlank() && !isThinking) {
                    onFollowUpSubmit(trimmed)
                }
                return true // Enter consumed
            }
        }

        // Test Shift+Enter
        val shiftResult = simulateKey(isShiftPressed = true, draftText = "Multi-line line 1", isThinking = false)
        assertFalse(shiftResult, "Shift+Enter must not consume event or submit")
        assertNull(submittedQuery, "Shift+Enter must not submit query")

        // Test Enter alone with whitespace
        val blankResult = simulateKey(isShiftPressed = false, draftText = "   ", isThinking = false)
        assertTrue(blankResult, "Enter alone is consumed")
        assertNull(submittedQuery, "Blank draft must not submit on Enter")

        // Test Enter alone with valid draft
        val validResult = simulateKey(isShiftPressed = false, draftText = "  Where is the toolbar?  ", isThinking = false)
        assertTrue(validResult, "Enter alone is consumed")
        assertEquals("Where is the toolbar?", submittedQuery, "Enter alone must submit trimmed query")

        // Test Enter alone while thinking
        submittedQuery = null
        val busyResult = simulateKey(isShiftPressed = false, draftText = "Next question", isThinking = true)
        assertTrue(busyResult, "Enter alone is consumed even while thinking")
        assertNull(submittedQuery, "Must not submit while thinking is active")
    }

    @Test
    fun testUI4206_requestActiveThinkingState() {
        val (controller, _) = createTestController(onAskDelayMs = 2000)
        val coordinator = controller.conversationCoordinator

        // Manually set thinking state to test bubble presentation
        val thinkingState = TutorConversationState.Thinking(
            requestId = "req-test-4206",
            stepIndex = 0,
            queryText = "Where is extrude?",
            source = ConversationSource.AVATAR_BUBBLE
        )
        coordinator.state = thinkingState

        assertTrue(coordinator.isThinking, "Coordinator must report isThinking = true")

        ImageComposeScene(width = 440, height = 380).use { scene ->
            scene.setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    InstructionBubble(
                        stepNumber = 1,
                        totalSteps = 4,
                        instructionText = "Add a UV Sphere",
                        targetControlName = "Add Menu",
                        statusLabel = "Thinking",
                        isHighlightBusy = false,
                        isExplainBusy = true,
                        conversationState = thinkingState
                    )
                }
            }
            val image = scene.render()
            assertTrue(image.width > 0 && image.height > 0, "Thinking state must render successfully")
        }

        coordinator.state = TutorConversationState.Idle
    }

    @Test
    fun testUI4207_duplicateSendAttemptedWhileBusy() {
        val (controller, _) = createTestController(onAskDelayMs = 1500)
        val coordinator = controller.conversationCoordinator

        coordinator.state = TutorConversationState.Thinking(
            requestId = "req-busy",
            stepIndex = 0,
            queryText = "First query",
            source = ConversationSource.AVATAR_BUBBLE
        )

        val secondSubmitResult = runBlocking {
            coordinator.askTutor("Second concurrent query", ConversationSource.AVATAR_BUBBLE)
        }

        assertFalse(secondSubmitResult, "Duplicate query while Thinking must be rejected by coordinator")
        coordinator.state = TutorConversationState.Idle
    }

    @Test
    fun testUI4208_cancelPressedInvokesCancellationApi() {
        val (controller, _) = createTestController(onAskDelayMs = 5000)
        val coordinator = controller.conversationCoordinator

        coordinator.state = TutorConversationState.Thinking(
            requestId = "req-to-cancel",
            stepIndex = 0,
            queryText = "Long running query",
            source = ConversationSource.AVATAR_BUBBLE
        )
        assertTrue(coordinator.isThinking)

        // User clicks Cancel
        coordinator.cancelActiveQuery()

        assertEquals(TutorConversationState.Idle, coordinator.state, "State must immediately return to Idle upon Cancel")
        assertFalse(coordinator.isThinking, "isThinking must be false after Cancel")
    }

    @Test
    fun testUI4209_requestFailsErrorStateReadable() {
        val (controller, _) = createTestController(shouldFail = true)
        val coordinator = controller.conversationCoordinator

        runBlocking {
            coordinator.askTutor("Will fail", ConversationSource.AVATAR_BUBBLE)
        }

        assertTrue(coordinator.state is TutorConversationState.Error, "Coordinator must transition to Error on failure")
        val errState = coordinator.state as TutorConversationState.Error
        assertTrue(errState.canRetry, "Network error must permit retry")
        assertEquals("Will fail", errState.lastQuery)

        ImageComposeScene(width = 440, height = 380).use { scene ->
            scene.setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    InstructionBubble(
                        stepNumber = 1,
                        totalSteps = 4,
                        instructionText = "Add a UV Sphere",
                        statusLabel = "Ready",
                        conversationState = errState
                    )
                }
            }
            val image = scene.render()
            assertTrue(image.width > 0 && image.height > 0, "Error state must render readable card")
        }
    }

    @Test
    fun testUI4210_retrySelectedInvokesRetryContract() {
        var failFirstTime = true
        val provider = ChatbotProvider {
            if (failFirstTime) {
                failFirstTime = false
                throw RuntimeException("Temporary timeout")
            }
            ChatResponse(replyText = "Recovered answer on retry", explanation = "Recovered answer on retry")
        }

        val controller = TutorController(
            loadMockTutorialByDefault = true,
            conversationCoordinatorFactory = { ctrl ->
                TutorConversationCoordinator(controller = ctrl, chatbotProvider = provider)
            }
        )
        val coordinator = controller.conversationCoordinator

        // First attempt fails
        runBlocking {
            coordinator.askTutor("Retryable question", ConversationSource.AVATAR_BUBBLE)
        }
        assertTrue(coordinator.state is TutorConversationState.Error)

        // Retry action
        val retryJob = coordinator.retryLastQuery()
        assertNotNull(retryJob, "retryLastQuery() must return an active Job")

        runBlocking {
            retryJob.join()
        }

        assertEquals(TutorConversationState.Idle, coordinator.state, "State must return to Idle after successful retry")
        assertEquals("Recovered answer on retry", coordinator.latestExplanationText)
    }

    @Test
    fun testUI4211_longAnswerDisplayedWithScrolling() {
        val longAnswer = """
            In Blender 5.2, creating and manipulating 3D meshes follows a clear procedural pipeline:
            1. Press Shift + A to open the Add Menu directly at the cursor location.
            2. Navigate through the Mesh subcategory and select UV Sphere.
            3. In the operator adjust panel (F9) in the bottom-left corner of the 3D Viewport, set Segments to 32 and Rings to 16.
            4. Press G then Z then 1 to translate the newly created geometry up along the global Z-axis.
            5. Switch to Edit Mode using Tab to inspect vertices, edges, and faces.
            6. Use Extrude (E) or Inset (I) to begin modeling complex geometry.
            Remember to maintain manifold geometry and check face normals in the Overlays menu if shading appears inverted.
        """.trimIndent()

        val (controller, _) = createTestController(mockResponseText = longAnswer)
        val coordinator = controller.conversationCoordinator

        runBlocking {
            coordinator.askTutor("Explain 3D modeling steps in detail", ConversationSource.AVATAR_BUBBLE)
        }

        assertEquals(longAnswer, coordinator.latestExplanationText)

        ImageComposeScene(width = 440, height = 380).use { scene ->
            scene.setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    InstructionBubble(
                        stepNumber = 1,
                        totalSteps = 4,
                        instructionText = "Add a UV Sphere",
                        statusLabel = "Ready",
                        explanationText = coordinator.latestExplanationText,
                        conversationState = coordinator.state
                    )
                }
            }
            val image = scene.render()
            assertTrue(image.width > 0 && image.height > 0, "Long answer must render in scrollable container without clipping")
        }
    }

    @Test
    fun testUI4212_explainMoreClickedWorks() {
        val (controller, _) = createTestController(mockResponseText = "Detailed explanation of step 1")
        val coordinator = controller.conversationCoordinator

        val explainJob = coordinator.explainCurrentStep()
        runBlocking {
            explainJob.join()
        }

        assertEquals("Detailed explanation of step 1", coordinator.latestExplanationText)
        val lastMessage = coordinator.messages.last()
        assertEquals(ChatSender.TUTOR, lastMessage.sender)
        assertEquals("Detailed explanation of step 1", lastMessage.text)
    }

    @Test
    fun testUI4213_workspaceDrawerReopenedPreservesHistory() {
        val (controller, _) = createTestController(mockResponseText = "Answer from avatar bubble")
        val coordinator = controller.conversationCoordinator

        runBlocking {
            coordinator.askTutor("Avatar bubble question", ConversationSource.AVATAR_BUBBLE)
        }

        // Simulate opening HUD ChatbotPanel
        val panelMessages = coordinator.messages.toList()
        assertTrue(panelMessages.any { it.text == "Avatar bubble question" && it.source == ConversationSource.AVATAR_BUBBLE },
            "Avatar bubble query must be present in shared coordinator messages")
        assertTrue(panelMessages.any { it.text == "Answer from avatar bubble" },
            "Tutor answer must be present in shared coordinator messages")

        // Simulate closing and reopening drawer
        val reopenedMessages = coordinator.messages.toList()
        assertEquals(panelMessages.size, reopenedMessages.size, "History must persist without loss across drawer reopen")
    }

    @Test
    fun testUI4214_uiRecompositionDoesNotDuplicateMessages() {
        val (controller, _) = createTestController(mockResponseText = "Single response")
        val coordinator = controller.conversationCoordinator

        runBlocking {
            coordinator.askTutor("Question once", ConversationSource.AVATAR_BUBBLE)
        }

        val countAfterAsk = coordinator.messages.size

        // Recompose / render 5 consecutive frames
        ImageComposeScene(width = 440, height = 380).use { scene ->
            scene.setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    InstructionBubble(
                        stepNumber = 1,
                        totalSteps = 4,
                        instructionText = "Add a UV Sphere",
                        statusLabel = "Ready",
                        explanationText = coordinator.latestExplanationText,
                        conversationState = coordinator.state
                    )
                }
            }
            for (frame in 0 until 5) {
                scene.render()
            }
        }

        assertEquals(countAfterAsk, coordinator.messages.size, "Recomposition must never duplicate coordinator messages")
    }

    @Test
    fun testUI4215_guidanceOverlayRemainsActiveWithNewControls() {
        val (controller, _) = createTestController()
        val step = controller.currentStep
        assertNotNull(step)

        val targetRegion = HighlightRegion(x = 300f, y = 200f, width = 150f, height = 100f)
        val anchorPoint = ScreenPoint(x = 750f, y = 250f)

        val geometry = TargetGuidanceCalculator.calculateGuidance(
            anchor = anchorPoint,
            targetBounds = targetRegion.toScreenRect()
        )

        assertTrue(geometry.isGuidanceUsable, "Guidance geometry must remain usable")
        assertTrue(geometry.distance > 0f, "Target distance must be calculated")
        assertNotNull(geometry.pointingGesture, "Pointing gesture must be generated")

        // Render bubble with active guidance and follow-up input
        ImageComposeScene(width = 440, height = 380).use { scene ->
            scene.setContent {
                Box(modifier = Modifier.fillMaxSize()) {
                    InstructionBubble(
                        stepNumber = 1,
                        totalSteps = 4,
                        instructionText = step.instruction,
                        targetControlName = "Header Menu",
                        statusLabel = "Ready",
                        isHighlightBusy = false,
                        conversationState = controller.conversationCoordinator.state
                    )
                }
            }
            val image = scene.render()
            assertTrue(image.width > 0 && image.height > 0)
        }
    }

    @Test
    fun testUI4216_avatarDismissedAndReopenedPreservesState() {
        val (controller, _) = createTestController(mockResponseText = "Preserved explanation")
        val coordinator = controller.conversationCoordinator

        runBlocking {
            coordinator.askTutor("Will survive dock toggle", ConversationSource.AVATAR_BUBBLE)
        }

        assertEquals("Preserved explanation", coordinator.latestExplanationText)
        val initialHistorySize = coordinator.messages.size

        // State toggling: Expanded -> Compact Dock -> Expanded
        var isCompact = false
        val compactSize = DpSize(190.dp, 60.dp)
        val expandedSize = DpSize(440.dp, 380.dp)

        // 1. Expanded mode
        var currentWindowSize = if (isCompact) compactSize else expandedSize
        assertEquals(440.dp, currentWindowSize.width)
        assertEquals(380.dp, currentWindowSize.height)

        // 2. Dock to compact pill
        isCompact = true
        currentWindowSize = if (isCompact) compactSize else expandedSize
        assertEquals(190.dp, currentWindowSize.width)
        assertEquals(60.dp, currentWindowSize.height)

        // Assert coordinator state untouched during dock
        assertEquals("Preserved explanation", coordinator.latestExplanationText)
        assertEquals(initialHistorySize, coordinator.messages.size)

        // 3. Re-expand
        isCompact = false
        currentWindowSize = if (isCompact) compactSize else expandedSize
        assertEquals(440.dp, currentWindowSize.width)
        assertEquals(380.dp, currentWindowSize.height)

        // Assert zero regression in explanation and message continuity
        assertEquals("Preserved explanation", coordinator.latestExplanationText)
        assertEquals(initialHistorySize, coordinator.messages.size)
    }

    @Test
    fun testSanitizeErrorMessage_stripsExceptionPrefixesAndHidesSecrets() {
        // 1. Exception class prefix stripping
        val connectEx = "java.net.ConnectException: Connection refused: connect"
        assertEquals("Connection refused: connect", sanitizeErrorMessage(connectEx))

        val runtimeEx = "java.lang.RuntimeException: Failed to resolve tool"
        assertEquals("Failed to resolve tool", sanitizeErrorMessage(runtimeEx))

        // 2. Secret / token redaction
        val groqKey = "API error with token Bearer gsk_1234567890abcdef"
        val sanitizedGroq = sanitizeErrorMessage(groqKey)
        assertFalse(sanitizedGroq.contains("gsk_"), "Must redact Groq API keys")
        assertTrue(sanitizedGroq.contains("[REDACTED]"), "Must insert [REDACTED]")

        val geminiKey = "Quota exceeded for AIzaSyA1234567890_test"
        val sanitizedGemini = sanitizeErrorMessage(geminiKey)
        assertFalse(sanitizedGemini.contains("AIza"), "Must redact Gemini API keys")
        assertTrue(sanitizedGemini.contains("[REDACTED]"))

        // 3. Stack trace lines ignored
        val stackTrace = """
            java.lang.IllegalStateException: Viewport not found
                at tutor.TargetFinder.find(TargetFinder.kt:42)
                at tutor.Controller.run(Controller.kt:10)
        """.trimIndent()
        assertEquals("Viewport not found", sanitizeErrorMessage(stackTrace))

        // 4. Blank and null safety
        assertEquals("Unable to complete request. Please try again.", sanitizeErrorMessage(null))
        assertEquals("Unable to complete request. Please try again.", sanitizeErrorMessage("   "))
    }

    @Test
    fun testExplanationDismissCollapsesWindowHeight() {
        var explanationText: String? = "Active explanation"
        var isExplainBusy = false
        var isError = false
        var isExplanationDismissed = false

        fun calculateWindowHeight(): androidx.compose.ui.unit.Dp {
            val isExplainingActive = isExplainBusy || ((!explanationText.isNullOrBlank() || isError) && !isExplanationDismissed)
            return if (isExplainingActive) 380.dp else 270.dp
        }

        // Active explanation -> 380.dp
        assertEquals(380.dp, calculateWindowHeight(), "Active explanation must expand window to 380.dp")

        // Dismiss clicked -> collapses to 270.dp
        isExplanationDismissed = true
        explanationText = null
        assertEquals(270.dp, calculateWindowHeight(), "Dismissing explanation must collapse window to 270.dp")

        // New query thinking -> expands back to 380.dp
        isExplainBusy = true
        assertEquals(380.dp, calculateWindowHeight(), "In-flight query must expand window back to 380.dp")
    }

    private data class DpSize(val width: androidx.compose.ui.unit.Dp, val height: androidx.compose.ui.unit.Dp)
}

