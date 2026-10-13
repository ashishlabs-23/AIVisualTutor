package chat

import context.ObjectObservation
import context.ObservedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import models.ApplicationType
import tutor.TutorController

class ChatbotTest {

    @Test
    fun `ChatContextBuilder builds minimal focused context without unnecessary fields`() {
        val controller = TutorController()
        controller.selectApplication(ApplicationType.BLENDER)

        val observed = ObservedState(
            mode = "EDIT_MESH",
            objects = listOf(
                ObjectObservation(
                    id = "obj1",
                    name = "Cube",
                    type = "MESH",
                    selected = true,
                    metadata = mapOf("location" to "0.0, 0.0, 2.0")
                )
            )
        )

        val builder = ChatContextBuilder()
        val context = builder.buildContext(
            question = "How do I extrude a face?",
            controller = controller,
            observedState = observed,
            attachVisualContext = false
        )

        assertEquals("How do I extrude a face?", context.question)
        assertEquals("BLENDER", context.applicationName)
        assertEquals("EDIT_MESH", context.applicationMode)
        assertNotNull(context.applicationStateSummary)
        assertTrue(context.applicationStateSummary!!.contains("Target Object: Cube"))
        assertFalse(context.includeVisualContext)
    }

    @Test
    fun `GeminiChatbotProvider provides grounded offline answers when API key is unconfigured`() = runBlocking {
        val provider = GeminiChatbotProvider(apiKey = "")
        val context = ChatContext(
            question = "What is the difference between Object Mode and Edit Mode?",
            applicationName = "Blender",
            currentTaskTitle = "Add Cube",
            activeInstruction = "Select Cube"
        )

        val response = provider.askTutor(context)

        assertNotNull(response.replyText)
        assertTrue(response.replyText.contains("Object Mode"))
        assertTrue(response.replyText.contains("Edit Mode"))
        assertEquals("local-offline-tutor", response.providerModel)
    }

    @Test
    fun `ApiKeyConfig detects Groq API key and active provider`() {
        ApiKeyConfig.setRuntimeGroqKey("test-groq-key")
        try {
            val groqKey = ApiKeyConfig.getGroqApiKey()
            assertTrue(groqKey.isNotBlank(), "Expected GROQ_API_KEY to be detected")
            assertEquals("Groq", ApiKeyConfig.getActiveProvider())
        } finally {
            ApiKeyConfig.setRuntimeGroqKey("")
        }
    }

    @Test
    fun `GroqChatbotProvider offline guidance identifies targetControl and suggestedAction`() = runBlocking {
        val provider = GroqChatbotProvider(apiKey = "")
        val context = ChatContext(
            question = "Where is the extrude tool located?",
            applicationName = "Blender",
            currentTaskTitle = "Extrude Face",
            activeInstruction = "Press E to extrude"
        )

        val response = provider.askTutor(context)

        assertNotNull(response.replyText)
        assertEquals("Extrude", response.targetControl)
        assertEquals("AUTO_VERIFY", response.suggestedAction)
    }

    @Test
    fun `SmartChatbotProvider routes to Groq when key is configured`() = runBlocking {
        ApiKeyConfig.setRuntimeGroqKey("test-groq-key")
        try {
            val fakeGroq = object : ChatbotProvider {
                override suspend fun askTutor(context: ChatContext) = ChatResponse(
                    replyText = "Loop Cut is located on the left toolbar",
                    providerModel = "openai/gpt-oss-120b"
                )
            }
            val smartProvider = SmartChatbotProvider(groqProvider = fakeGroq)
            val context = ChatContext(
                question = "Where is the loop cut tool?",
                applicationName = "Blender",
                currentTaskTitle = "Subdivide geometry"
            )

            val response = smartProvider.askTutor(context)

            assertNotNull(response.replyText)
            assertEquals("openai/gpt-oss-120b", response.providerModel)
        } finally {
            ApiKeyConfig.setRuntimeGroqKey("")
        }
    }

    @Test
    fun `TutorController resolveTargetForName updates currentHighlight and activates assistance`() = runBlocking {
        val controller = TutorController(loadMockTutorialByDefault = true)
        controller.selectApplication(ApplicationType.BLENDER)

        val result = controller.resolveTargetForName("Extrude")

        assertNotNull(result.region)
        assertTrue(controller.state.screenAssistanceEnabled)
        assertEquals(result.region, controller.state.currentHighlight)
    }
}
