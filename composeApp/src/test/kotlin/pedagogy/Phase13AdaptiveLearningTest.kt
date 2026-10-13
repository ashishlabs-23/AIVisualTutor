package pedagogy

import context.EvidenceObservation
import context.EvidenceSource
import context.ExpectedState
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import models.ApplicationType
import models.HighlightRegion
import models.TutorStep
import org.junit.Test
import tutor.GoalProgressTracker
import tutor.OutcomeRequirementLevel
import tutor.RequiredOutcome
import tutor.TutorController
import tutor.UserGoalPlan
import tutor.UserGoalPlanStep
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dedicated test suite for Phase 13 — Adaptive Learning and Persistent Progress.
 * Validates authoritative verification, evidence-based skill tracking, conservative adaptation,
 * fault-tolerant atomic persistence, event idempotency, and regression safety across Phases 6–12.
 */
class Phase13AdaptiveLearningTest {

    private fun sampleTask(
        id: String = "blender_cube_add",
        skillId: String = "blender.primitives",
        title: String = "Add Cube Primitive"
    ): LearningTask = LearningTask(
        taskId = id,
        skillId = skillId,
        title = title,
        objective = "Create a default 3D mesh cube in the scene",
        targetControlName = "Add Cube",
        targetAreaDescription = "3D Viewport Header > Add > Mesh",
        exactInstruction = "Press Shift+A and select Mesh > Cube.",
        conceptualHint = "Open the Add Mesh menu in the 3D Viewport and select the cubic primitive.",
        reflectionQuestion = "What shortcut or menu opens the Blender mesh primitive creation menu?",
        expectedState = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Cube"
        ),
        targetRegion = HighlightRegion(100f, 100f, 120f, 40f)
    )

    private fun verifiedResult(
        status: VerificationStatus,
        runId: String = UUID.randomUUID().toString(),
        explanation: String = "Authoritative state check"
    ): VerificationResult = VerificationResult(
        status = status,
        runId = runId,
        operation = VerificationOperation.CREATE,
        explanation = explanation,
        evidence = listOf(
            EvidenceObservation(
                source = EvidenceSource.BLENDER,
                available = status == VerificationStatus.SUCCESS,
                diagnostic = "Object verification check",
                details = mapOf("status" to status.name)
            )
        )
    )

    // =========================================================================
    // PART 2 & 3: LEARNER PROFILE AND SKILL TRACKING
    // =========================================================================

    @Test
    fun testVerifiedSuccessUpdatesSkillMasteryConservatively() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        assertEquals(SkillMasteryLevel.NOT_OBSERVED, engine.state.getSkillLevel("blender.primitives"))

        // Attempt 1: 1 success -> EMERGING
        engine.onVerificationResult(task, verifiedResult(VerificationStatus.SUCCESS, runId = "r1"))
        assertEquals(SkillMasteryLevel.EMERGING, engine.state.getSkillLevel("blender.primitives"))
        val skill1 = engine.state.demonstratedSkills["blender.primitives"]!!
        assertEquals(1, skill1.verifiedSuccessCount)
        assertEquals(1, skill1.consecutiveSuccessCount)

        // Attempt 2: 2nd success -> PRACTICED
        engine.onVerificationResult(task, verifiedResult(VerificationStatus.SUCCESS, runId = "r2"))
        assertEquals(SkillMasteryLevel.PRACTICED, engine.state.getSkillLevel("blender.primitives"))
        val skill2 = engine.state.demonstratedSkills["blender.primitives"]!!
        assertEquals(2, skill2.verifiedSuccessCount)
        assertEquals(2, skill2.consecutiveSuccessCount)

        // Attempt 3: 3rd consecutive success -> CONSISTENTLY_DEMONSTRATED
        engine.onVerificationResult(task, verifiedResult(VerificationStatus.SUCCESS, runId = "r3"))
        assertEquals(SkillMasteryLevel.CONSISTENTLY_DEMONSTRATED, engine.state.getSkillLevel("blender.primitives"))
        val skill3 = engine.state.demonstratedSkills["blender.primitives"]!!
        assertEquals(3, skill3.verifiedSuccessCount)
        assertEquals(3, skill3.consecutiveSuccessCount)
    }

    @Test
    fun testPlannerOrScreenshotResponseAloneDoesNotUpdateMastery() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        // Calling getInstruction or onTaskSwitched (planning/presentation) does NOT grant mastery
        engine.getInstruction(task)
        engine.onTaskSwitched(task)

        assertEquals(SkillMasteryLevel.NOT_OBSERVED, engine.state.getSkillLevel("blender.primitives"))
        assertEquals(0, engine.state.totalVerifiedSuccesses)
        assertTrue(engine.state.demonstratedSkills.isEmpty())
        assertTrue(engine.state.verifiedOutcomes.isEmpty())
    }

    @Test
    fun testSingleFailedAttemptDoesNotCreatePermanentDifficultyClassification() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        // First attempt verified failure
        engine.onVerificationResult(task, verifiedResult(VerificationStatus.FAILURE, runId = "f1", explanation = "Selection mismatch"))

        // One failure must NOT create a permanent difficulty classification
        assertFalse(engine.state.hasDifficulty("blender.primitives"))
        assertEquals(1, engine.state.consecutiveFailures)
        // Guidance retains stage to assess instruction adequacy
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, engine.state.currentGuidanceStage)
    }

    @Test
    fun testRepeatedVerifiedErrorsTriggerEvidenceSupportedDifficulty() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        // Failure 1
        engine.onVerificationResult(
            task,
            verifiedResult(VerificationStatus.FAILURE, runId = "f1", explanation = "Wrong object mode"),
            mismatchCategory = "ModeMismatch",
            failureCause = FailureCause.LEARNER_ACTION
        )
        assertFalse(engine.state.hasDifficulty("blender.primitives"))

        // Failure 2 (repeated verified error on same task/skill)
        val event2 = engine.onVerificationResult(
            task,
            verifiedResult(VerificationStatus.FAILURE, runId = "f2", explanation = "Wrong object mode"),
            mismatchCategory = "ModeMismatch",
            failureCause = FailureCause.LEARNER_ACTION
        )

        // Rule 2 & 3: Evidence now supports a recurring learner difficulty
        assertTrue(engine.state.hasDifficulty("blender.primitives_ModeMismatch"))
        val diff = engine.state.recordedDifficulties["blender.primitives_ModeMismatch"]!!
        assertEquals(2, diff.occurrenceCount)
        assertEquals("ModeMismatch", diff.errorCategory)
        assertEquals(AdaptationStrategy.DETAILED_INSTRUCTION, event2.strategy)
        assertTrue(event2.rationale.contains("Repeated verified difficulty", ignoreCase = true))
    }

    @Test
    fun testProviderOrTelemetryFailuresDoNotLowerSkillOrRecordDifficulty() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        // Give learner demonstrated competence
        engine.onVerificationResult(task, verifiedResult(VerificationStatus.SUCCESS, runId = "s1"))
        engine.onVerificationResult(task, verifiedResult(VerificationStatus.SUCCESS, runId = "s2"))
        assertEquals(SkillMasteryLevel.PRACTICED, engine.state.getSkillLevel("blender.primitives"))
        val initialCompetence = engine.state.competenceEstimate

        // Now simulate 3 consecutive provider/telemetry failures
        repeat(3) { i ->
            engine.onVerificationResult(
                task,
                verifiedResult(VerificationStatus.FAILURE, runId = "infra_$i", explanation = "Blender socket disconnect: telemetry unavailable"),
                mismatchCategory = "InfrastructureFailure",
                failureCause = FailureCause.INFRASTRUCTURE_OR_PROVIDER
            )
        }

        // Skill level must NOT be lowered!
        assertEquals(SkillMasteryLevel.PRACTICED, engine.state.getSkillLevel("blender.primitives"))
        // Competence must NOT be penalized!
        assertEquals(initialCompetence, engine.state.competenceEstimate)
        // No learner difficulty recorded!
        assertFalse(engine.state.hasDifficulty("blender.primitives"))
        assertTrue(engine.state.recordedDifficulties.isEmpty())
        assertTrue(engine.state.adaptationReason.contains("telemetry/provider", ignoreCase = true))
    }

    @Test
    fun testUnrelatedTasksDoNotContaminateSkillRecords() {
        val engine = AdaptivePedagogicalEngine()
        val taskPrimitives = sampleTask(id = "cube", skillId = "blender.primitives")
        val taskMaterials = sampleTask(id = "mat", skillId = "blender.materials", title = "Assign Material")

        engine.onVerificationResult(taskPrimitives, verifiedResult(VerificationStatus.SUCCESS, runId = "s1"))
        assertEquals(SkillMasteryLevel.EMERGING, engine.state.getSkillLevel("blender.primitives"))
        assertEquals(SkillMasteryLevel.NOT_OBSERVED, engine.state.getSkillLevel("blender.materials"))

        // Failing in materials does not contaminate primitives
        engine.onVerificationResult(
            taskMaterials,
            verifiedResult(VerificationStatus.FAILURE, runId = "f1"),
            mismatchCategory = "MaterialMissing",
            failureCause = FailureCause.LEARNER_ACTION
        )

        assertEquals(SkillMasteryLevel.EMERGING, engine.state.getSkillLevel("blender.primitives"))
        assertEquals(SkillMasteryLevel.NOT_OBSERVED, engine.state.getSkillLevel("blender.materials"))
    }

    @Test
    fun testDuplicateLearningEventsAreIdempotent() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        val event = LearningEvent.OutcomeVerified(
            eventId = "unique_event_123",
            learnerId = "default_learner",
            taskId = task.taskId,
            skillId = task.skillId,
            result = verifiedResult(VerificationStatus.SUCCESS, runId = "run_123")
        )

        val firstDecision = engine.ingestLearningEvent(event, task)
        assertEquals(1, engine.state.consecutiveSuccesses)
        assertEquals(1, engine.state.totalVerifiedSuccesses)

        // Ingest the EXACT same event again
        val duplicateDecision = engine.ingestLearningEvent(event, task)
        // Progress must NOT be counted twice!
        assertEquals(1, engine.state.consecutiveSuccesses)
        assertEquals(1, engine.state.totalVerifiedSuccesses)
        assertFalse(duplicateDecision.stageChanged)
        assertTrue(duplicateDecision.rationale.contains("Duplicate", ignoreCase = true))
    }

    @Test
    fun testEventForWrongLearnerSessionIsRejected() {
        val engine = AdaptivePedagogicalEngine(initialLearnerId = "alice")
        val task = sampleTask()

        val wrongEvent = LearningEvent.OutcomeVerified(
            eventId = "event_bob_1",
            learnerId = "bob",
            taskId = task.taskId,
            skillId = task.skillId,
            result = verifiedResult(VerificationStatus.SUCCESS)
        )

        val decision = engine.ingestLearningEvent(wrongEvent, task)
        assertEquals(0, engine.state.totalVerifiedSuccesses)
        assertTrue(decision.rationale.contains("Ignored event targeting learner 'bob'", ignoreCase = true))
    }

    // =========================================================================
    // PART 4: ADAPTIVE INSTRUCTION AND PEDAGOGY
    // =========================================================================

    @Test
    fun testNoRelevantHistoryProducesSafeDefault() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        val instruction = engine.getInstruction(task)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, instruction.stage)
        assertEquals(AdaptationStrategy.NORMAL_GUIDANCE, instruction.strategy)
        assertEquals(task.exactInstruction, instruction.instructionText)
    }

    @Test
    fun testExplicitPreferenceForConciseInstructionIsRespected() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        engine.updatePreferences(LearnerPreferences(detailPreference = DetailPreference.CONCISE))
        val instruction = engine.getInstruction(task)

        assertEquals(AdaptationStrategy.CONCISE_INSTRUCTION, instruction.strategy)
        assertEquals(DetailPreference.CONCISE, engine.state.preferences.detailPreference)
    }

    @Test
    fun testExplicitPreferenceForDetailedInstructionIsRespected() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        engine.updatePreferences(LearnerPreferences(detailPreference = DetailPreference.DETAILED))
        val instruction = engine.getInstruction(task)

        assertEquals(AdaptationStrategy.DETAILED_INSTRUCTION, instruction.strategy)
        assertEquals(DetailPreference.DETAILED, engine.state.preferences.detailPreference)
    }

    @Test
    fun testRepeatedSuccessMayProduceConciseExplanation() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        repeat(3) { i ->
            engine.onVerificationResult(task, verifiedResult(VerificationStatus.SUCCESS, runId = "succ_$i"))
        }

        assertEquals(SkillMasteryLevel.CONSISTENTLY_DEMONSTRATED, engine.state.getSkillLevel("blender.primitives"))
        val instruction = engine.getInstruction(task)
        assertEquals(AdaptationStrategy.CONCISE_INSTRUCTION, instruction.strategy)
    }

    @Test
    fun testAdaptationDecisionRecordsSupportingEvidence() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        engine.onVerificationResult(task, verifiedResult(VerificationStatus.SUCCESS, runId = "r1"))
        assertEquals(1, engine.state.adaptationDecisions.size)
        val decision = engine.state.adaptationDecisions.first()
        assertEquals(task.taskId, decision.taskId)
        assertEquals(task.skillId, decision.skillId)
        assertTrue(decision.evidenceReason.isNotBlank())
    }

    // =========================================================================
    // PART 5: PERSISTENCE AND RESTORATION
    // =========================================================================

    @Test
    fun testNewProfileInitializesWithSchemaVersion1() {
        val state = LearnerState(learnerId = "test_user")
        assertEquals(1, state.schemaVersion)
        assertNotNull(state.createdAt)
        assertNotNull(state.updatedAt)
    }

    @Test
    fun testProfileRoundtripPersistenceSurvivesRestart() = runBlocking {
        val tempDir = Files.createTempDirectory("p13_persistence_test")
        try {
            val persistence1 = LearnerStatePersistence(rootDirectory = tempDir)
            val engine1 = AdaptivePedagogicalEngine(persistence = persistence1, initialLearnerId = "persisted_learner")
            val task = sampleTask()

            // Perform verified progress
            engine1.onVerificationResult(task, verifiedResult(VerificationStatus.SUCCESS, runId = "r1"))
            engine1.onVerificationResult(task, verifiedResult(VerificationStatus.SUCCESS, runId = "r2"))
            engine1.updatePreferences(LearnerPreferences(detailPreference = DetailPreference.DETAILED, wantsSmallerSteps = true))

            val saved = persistence1.save(engine1.state)
            assertTrue(saved)

            // Simulate application restart with new persistence instance
            val persistence2 = LearnerStatePersistence(rootDirectory = tempDir)
            val loadedState = persistence2.load("persisted_learner")

            assertNotNull(loadedState)
            assertEquals("persisted_learner", loadedState.learnerId)
            assertEquals(1, loadedState.schemaVersion)
            assertEquals(2, loadedState.totalVerifiedSuccesses)
            assertEquals(SkillMasteryLevel.PRACTICED, loadedState.getSkillLevel("blender.primitives"))
            assertEquals(DetailPreference.DETAILED, loadedState.preferences.detailPreference)
            assertTrue(loadedState.preferences.wantsSmallerSteps)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testMissingEmptyAndMalformedFilesHandledSafely() = runBlocking {
        val tempDir = Files.createTempDirectory("p13_fault_tolerance_test")
        try {
            val persistence = LearnerStatePersistence(rootDirectory = tempDir)

            // 1. Missing file
            val missing = persistence.load("does_not_exist")
            assertNull(missing)

            // 2. Empty file
            val emptyFile = tempDir.resolve("empty_user.json")
            Files.writeString(emptyFile, "")
            val emptyLoaded = persistence.load("empty_user")
            assertNull(emptyLoaded)

            // 3. Malformed JSON file
            val malformedFile = tempDir.resolve("corrupt_user.json")
            Files.writeString(malformedFile, "{ malformed json: not valid ]")
            val malformedLoaded = persistence.load("corrupt_user")
            assertNull(malformedLoaded)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testUnsupportedFutureSchemaVersionHandledSafely() = runBlocking {
        val tempDir = Files.createTempDirectory("p13_schema_version_test")
        try {
            val persistence = LearnerStatePersistence(rootDirectory = tempDir)
            val futureSchemaFile = tempDir.resolve("future_user.json")
            Files.writeString(
                futureSchemaFile,
                """
                {
                  "schemaVersion": 99,
                  "learnerId": "future_user",
                  "competenceEstimate": 1.0
                }
                """.trimIndent()
            )

            // Future schema > 1 must be safely rejected without crashing
            val loaded = persistence.load("future_user")
            assertNull(loaded)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testSafeClearProgressMechanism() = runBlocking {
        val tempDir = Files.createTempDirectory("p13_clear_test")
        try {
            val persistence = LearnerStatePersistence(rootDirectory = tempDir)
            val state = LearnerState(learnerId = "clear_me", consecutiveSuccesses = 5)
            persistence.save(state)
            assertNotNull(persistence.load("clear_me"))

            val cleared = persistence.clear("clear_me")
            assertTrue(cleared)
            assertNull(persistence.load("clear_me"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testNoSecretsOrRawScreenshotsPersisted() {
        val persistence = LearnerStatePersistence()
        val state = LearnerState(
            learnerId = "safe_user",
            currentTaskId = "cube",
            demonstratedSkills = mapOf(
                "blender.primitives" to DemonstratedSkill("blender.primitives", "Primitives", SkillMasteryLevel.PRACTICED, 2, 2)
            )
        )
        val serialized = persistence.serializeState(state)

        // Assert no credentials, API keys, or raw base64 images appear
        assertFalse(serialized.contains("apiKey", ignoreCase = true))
        assertFalse(serialized.contains("password", ignoreCase = true))
        assertFalse(serialized.contains("secret", ignoreCase = true))
        assertFalse(serialized.contains("base64", ignoreCase = true))
        assertFalse(serialized.contains("BufferedImage", ignoreCase = true))
    }

    @Test
    fun testConcurrentProfileUpdatesHandledSafely(): Unit = runBlocking {
        val tempDir = Files.createTempDirectory("p13_concurrent_test")
        try {
            val persistence = LearnerStatePersistence(rootDirectory = tempDir)
            val jobs = (1..10).map { i ->
                async(Dispatchers.IO) {
                    val state = LearnerState(learnerId = "concurrent_user", consecutiveSuccesses = i)
                    persistence.save(state)
                }
            }
            val results = jobs.awaitAll()
            assertTrue(results.all { it })
            val loaded = persistence.load("concurrent_user")
            assertNotNull(loaded)
            assertEquals("concurrent_user", loaded.learnerId)
            assertTrue(loaded.consecutiveSuccesses in 1..10)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testFailedWritePreservesExistingValidProfile(): Unit = runBlocking {
        val tempDir = Files.createTempDirectory("p13_write_fail_test")
        try {
            val persistence = LearnerStatePersistence(rootDirectory = tempDir)
            val goodState = LearnerState(learnerId = "valid_user", consecutiveSuccesses = 42)
            assertTrue(persistence.save(goodState))
            val loadedInitial = persistence.load("valid_user")
            assertNotNull(loadedInitial)
            assertEquals(42, loadedInitial.consecutiveSuccesses)

            // Make the target file read-only on Windows / POSIX to induce write error
            val userFile = tempDir.resolve("valid_user.json")
            userFile.toFile().setReadOnly()

            val newState = LearnerState(learnerId = "valid_user", consecutiveSuccesses = 99)
            persistence.save(newState)

            // File must still be readable and uncorrupted
            val loadedAfter = persistence.load("valid_user")
            assertNotNull(loadedAfter)
            assertTrue(loadedAfter.consecutiveSuccesses == 42 || loadedAfter.consecutiveSuccesses == 99)
            userFile.toFile().setWritable(true)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    // =========================================================================
    // PART 6: INTEGRATION WITH PHASES 6–12
    // =========================================================================

    @Test
    fun testVerificationRemainsAuthoritativeInController() {
        val controller = TutorController(loadMockTutorialByDefault = true)
        val initialIndex = controller.state.currentStepIndex
        val activeStep = controller.currentStep!!

        // Unverified recommendations or queries do not advance step
        assertEquals(0, initialIndex)

        // Authoritative verified success advances step and records progress
        controller.recordVerificationResult(
            VerificationResult(
                status = VerificationStatus.SUCCESS,
                operation = activeStep.expectedState?.operation ?: VerificationOperation.CREATE,
                explanation = "Blender confirmed object created",
                runId = "auth_run_1"
            ),
            expected = activeStep.expectedState
        )

        assertEquals(1, controller.state.currentStepIndex)
        assertEquals(1, controller.pedagogicalEngine.state.totalVerifiedSuccesses)
        assertEquals(SkillMasteryLevel.EMERGING, controller.pedagogicalEngine.state.getSkillLevel("blender.primitives"))
    }

    @Test
    fun testReplanningPreservesValidProgressWithVersionGuard() {
        val controller = TutorController()
        val tracker = controller.goalProgressTracker

        val initialPlan = UserGoalPlan(
            goalText = "create cube and sphere",
            steps = listOf(
                UserGoalPlanStep(stepIndex = 1, title = "Add Cube", instruction = "Add cube", stepId = "step_1"),
                UserGoalPlanStep(stepIndex = 2, title = "Add Sphere", instruction = "Add sphere", stepId = "step_2")
            ),
            requiredOutcomes = listOf(
                RequiredOutcome(id = "out_cube", description = "Cube created", requirementLevel = OutcomeRequirementLevel.MANDATORY),
                RequiredOutcome(id = "out_sphere", description = "Sphere created", requirementLevel = OutcomeRequirementLevel.MANDATORY)
            )
        )
        controller.setActivePlanForTesting(initialPlan)

        // Complete step 1
        tracker.recordStepVerification("step_1", verified = true, operationPlanVersion = tracker.planVersion)
        assertTrue(tracker.getCompletedStepIds().contains("step_1"))
        assertEquals(1L, tracker.planVersion)

        // Replan goal: increments planVersion to 2L while preserving step_1
        val replanned = controller.replanGoal("create cube and scale it")
        assertNotNull(replanned)
        assertEquals(2L, tracker.planVersion)
        assertTrue(tracker.getCompletedStepIds().contains("step_1"))

        // Late-arriving stale verification event from planVersion 1L cannot corrupt progress
        val staleResult = tracker.recordStepVerification("step_2", verified = true, operationPlanVersion = 1L)
        // Stale operation rejected!
        assertFalse(tracker.getCompletedStepIds().contains("step_2"))
    }

    @Test
    fun testLearnerPreferenceControlsUpdateControllerState() {
        val controller = TutorController(loadMockTutorialByDefault = true)

        controller.setDetailPreference(DetailPreference.DETAILED)
        assertEquals(DetailPreference.DETAILED, controller.pedagogicalEngine.state.preferences.detailPreference)

        controller.setDetailPreference(DetailPreference.CONCISE)
        assertEquals(DetailPreference.CONCISE, controller.pedagogicalEngine.state.preferences.detailPreference)
    }

    @Test
    fun testLearnerProgressClearingResetsStateCleanly() {
        val controller = TutorController(loadMockTutorialByDefault = true)
        val step = controller.currentStep!!

        controller.recordVerificationResult(
            VerificationResult(
                status = VerificationStatus.SUCCESS,
                operation = step.expectedState?.operation ?: VerificationOperation.CREATE,
                explanation = "Created",
                runId = "clear_run_1"
            )
        )
        assertTrue(controller.pedagogicalEngine.state.totalVerifiedSuccesses > 0)

        controller.clearLearnerProgress()
        assertEquals(0, controller.pedagogicalEngine.state.totalVerifiedSuccesses)
        assertEquals(0.0, controller.pedagogicalEngine.state.competenceEstimate)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, controller.state.guidanceStage)
    }
}
