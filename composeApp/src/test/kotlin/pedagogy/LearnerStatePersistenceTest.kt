package pedagogy

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LearnerStatePersistenceTest {

    @Test
    fun testSerializationAndDeserialization() {
        val persistence = LearnerStatePersistence()

        val taskProfile1 = TaskLearnerProfile(
            taskId = "task_sketch",
            skillId = "sketching",
            guidanceStage = GuidanceStage.STAGE_2_CONCEPTUAL_HINT,
            consecutiveSuccesses = 2,
            consecutiveFailures = 0,
            totalSuccesses = 2,
            totalFailures = 0,
            totalUncertain = 1,
            competenceEstimate = 0.5
        )

        val taskProfile2 = TaskLearnerProfile(
            taskId = "task_extrude",
            skillId = "features",
            guidanceStage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
            consecutiveSuccesses = 0,
            consecutiveFailures = 1,
            totalSuccesses = 0,
            totalFailures = 1,
            totalUncertain = 0,
            competenceEstimate = 0.0
        )

        val state = LearnerState(
            learnerId = "user_42",
            currentTaskId = "task_sketch",
            currentSkillId = "sketching",
            currentGuidanceStage = GuidanceStage.STAGE_2_CONCEPTUAL_HINT,
            consecutiveSuccesses = 2,
            consecutiveFailures = 0,
            totalVerifiedSuccesses = 2,
            totalVerifiedFailures = 1,
            totalUncertainOutcomes = 1,
            competenceEstimate = 0.5,
            adaptationReason = "Progressed to Stage 2 after 2 consecutive verified successes.",
            requiresReassessment = false,
            taskProfiles = mapOf(
                "task_sketch" to taskProfile1,
                "task_extrude" to taskProfile2
            )
        )

        val serialized = persistence.serializeState(state)
        val deserialized = persistence.deserializeState(serialized)

        assertEquals(state.learnerId, deserialized.learnerId)
        assertEquals(state.currentTaskId, deserialized.currentTaskId)
        assertEquals(state.currentSkillId, deserialized.currentSkillId)
        assertEquals(state.currentGuidanceStage, deserialized.currentGuidanceStage)
        assertEquals(state.consecutiveSuccesses, deserialized.consecutiveSuccesses)
        assertEquals(state.consecutiveFailures, deserialized.consecutiveFailures)
        assertEquals(state.totalVerifiedSuccesses, deserialized.totalVerifiedSuccesses)
        assertEquals(state.totalVerifiedFailures, deserialized.totalVerifiedFailures)
        assertEquals(state.totalUncertainOutcomes, deserialized.totalUncertainOutcomes)
        assertEquals(state.competenceEstimate, deserialized.competenceEstimate)
        assertEquals(state.adaptationReason, deserialized.adaptationReason)
        assertEquals(state.requiresReassessment, deserialized.requiresReassessment)
        assertEquals(2, deserialized.taskProfiles.size)

        val loadedP1 = deserialized.taskProfiles["task_sketch"]
        assertNotNull(loadedP1)
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, loadedP1.guidanceStage)
        assertEquals(2, loadedP1.consecutiveSuccesses)
    }

    @Test
    fun testFilePersistence() = runBlocking {
        val tempDir = Files.createTempDirectory("learner_test")
        try {
            val persistence = LearnerStatePersistence(rootDirectory = tempDir)
            val state = LearnerState(
                learnerId = "learner_persisted",
                currentTaskId = "task_cube",
                currentSkillId = "blender.primitives",
                currentGuidanceStage = GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION,
                consecutiveSuccesses = 4,
                competenceEstimate = 0.9,
                adaptationReason = "Mastered cube primitive."
            )

            persistence.save(state)
            val loaded = persistence.load("learner_persisted")

            assertNotNull(loaded)
            assertEquals("learner_persisted", loaded.learnerId)
            assertEquals(GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION, loaded.currentGuidanceStage)
            assertEquals(4, loaded.consecutiveSuccesses)
            assertEquals(0.9, loaded.competenceEstimate)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testMissingFileReturnsNull() = runBlocking {
        val tempDir = Files.createTempDirectory("learner_missing_test")
        try {
            val persistence = LearnerStatePersistence(rootDirectory = tempDir)
            val loaded = persistence.load("non_existent_learner")
            kotlin.test.assertNull(loaded)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testMalformedJsonHandling() = runBlocking {
        val tempDir = Files.createTempDirectory("learner_malformed_test")
        try {
            val persistence = LearnerStatePersistence(rootDirectory = tempDir)
            // Invalid non-JSON content written directly
            val corruptedFile = tempDir.resolve("corrupted_user.json")
            Files.writeString(corruptedFile, "NOT_JSON_DATA_GARBAGE")

            val loaded = persistence.load("corrupted_user")
            kotlin.test.assertNull(loaded) // Safely caught, returns null

            // Direct deserialize throws IllegalArgumentException
            var threw = false
            try {
                persistence.deserializeState("INVALID_DATA")
            } catch (ex: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testLearnerIdMismatchIgnored() = runBlocking {
        val tempDir = Files.createTempDirectory("learner_mismatch_test")
        try {
            val persistence = LearnerStatePersistence(rootDirectory = tempDir)
            // State saved under user_A, but file named user_B.json
            val file = tempDir.resolve("user_B.json")
            Files.writeString(file, "{\"learnerId\": \"user_A\", \"currentTaskId\": \"task1\"}")

            val loaded = persistence.load("user_B")
            kotlin.test.assertNull(loaded) // Mismatch detected, profile leakage prevented
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testSanitizationOfNegativeValues() {
        val persistence = LearnerStatePersistence()
        val jsonWithNegativeValues = """
            {
              "learnerId": "sanitized_user",
              "currentTaskId": "task1",
              "consecutiveSuccesses": -5,
              "consecutiveFailures": -2,
              "competenceEstimate": -0.8
            }
        """.trimIndent()

        val state = persistence.deserializeState(jsonWithNegativeValues)
        assertEquals(0, state.consecutiveSuccesses)
        assertEquals(0, state.consecutiveFailures)
        assertEquals(0.0, state.competenceEstimate)
    }
}
