package bridge

import context.EvidenceObservation
import context.EvidenceSource
import context.ExpectedState
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Test
import pedagogy.AdaptationStrategy
import pedagogy.AdaptivePedagogicalEngine
import pedagogy.DetailPreference
import pedagogy.FailureCause
import pedagogy.GuidanceStage
import pedagogy.LearnerPreferences
import pedagogy.LearnerState
import pedagogy.LearnerStatePersistence
import pedagogy.LearningEvent
import pedagogy.LearningTask
import pedagogy.SkillMasteryLevel
import tutor.GoalProgressTracker
import tutor.OutcomeRequirementLevel
import tutor.RequiredOutcome
import tutor.TutorController
import tutor.UserGoalPlan
import tutor.UserGoalPlanStep
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-End Validation Runner for Phase 13:
 * Adaptive Learning and Persistent Progress.
 *
 * Runs comprehensive scenarios verifying skill tracking, conservative adaptation,
 * fault-tolerant persistence, and full integration across Phases 6–12, writing
 * tangible evidence to validation/phase-13/evidence/.
 */
class Phase13EndToEndValidationRunner {

    private val evidenceDirs = listOf(
        File("validation/phase-13/evidence").apply { mkdirs() },
        File("composeApp/validation/phase-13/evidence").apply { mkdirs() }
    )

    private fun writeEvidence(filename: String, content: String) {
        evidenceDirs.forEach { dir ->
            File(dir, filename).writeText(content)
        }
    }

    private fun sampleTask(
        id: String = "cube_add",
        skillId: String = "blender.primitives",
        title: String = "Add Cube Primitive"
    ): LearningTask = LearningTask(
        taskId = id,
        skillId = skillId,
        title = title,
        objective = "Create a default 3D mesh cube in the scene",
        targetControlName = "Add Cube",
        exactInstruction = "Press Shift+A and select Mesh > Cube.",
        conceptualHint = "Open the Add Mesh menu in the 3D Viewport and select the cube.",
        reflectionQuestion = "Which menu adds 3D mesh primitives?",
        expectedState = ExpectedState(operation = VerificationOperation.CREATE, targetName = "Cube")
    )

    // ==========================================
    // SCENARIO 1: Verified Skill Mastery Progression
    // ==========================================
    @Test
    fun `Scenario 1 - Verified Skill Mastery Progression`() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        val log = StringBuilder()
        log.appendLine("=== SCENARIO 1: VERIFIED SKILL MASTERY PROGRESSION ===")
        log.appendLine("Timestamp: ${Instant.now()}")
        log.appendLine("Initial Skill Level: ${engine.state.getSkillLevel("blender.primitives")}")

        // Attempt 1 -> EMERGING
        val res1 = engine.onVerificationResult(
            task,
            VerificationResult(VerificationStatus.SUCCESS, "run_1", VerificationOperation.CREATE, "Cube created")
        )
        val level1 = engine.state.getSkillLevel("blender.primitives")
        log.appendLine("After Attempt 1 (SUCCESS): Level=$level1, Stage=${res1.newStage}, Consecutive=${engine.state.consecutiveSuccesses}")

        // Attempt 2 -> PRACTICED
        val res2 = engine.onVerificationResult(
            task,
            VerificationResult(VerificationStatus.SUCCESS, "run_2", VerificationOperation.CREATE, "Cube created")
        )
        val level2 = engine.state.getSkillLevel("blender.primitives")
        log.appendLine("After Attempt 2 (SUCCESS): Level=$level2, Stage=${res2.newStage}, Consecutive=${engine.state.consecutiveSuccesses}")

        // Attempt 3 -> CONSISTENTLY_DEMONSTRATED
        val res3 = engine.onVerificationResult(
            task,
            VerificationResult(VerificationStatus.SUCCESS, "run_3", VerificationOperation.CREATE, "Cube created")
        )
        val level3 = engine.state.getSkillLevel("blender.primitives")
        log.appendLine("After Attempt 3 (SUCCESS): Level=$level3, Stage=${res3.newStage}, Strategy=${res3.strategy}")

        writeEvidence("scenario_1_skill_progression.txt", log.toString())

        assertEquals(SkillMasteryLevel.EMERGING, level1)
        assertEquals(SkillMasteryLevel.PRACTICED, level2)
        assertEquals(SkillMasteryLevel.CONSISTENTLY_DEMONSTRATED, level3)
        assertEquals(AdaptationStrategy.CONCISE_INSTRUCTION, res3.strategy)
    }

    // ==========================================
    // SCENARIO 2: Infrastructure / Telemetry Failure Protection
    // ==========================================
    @Test
    fun `Scenario 2 - Telemetry Failure Protection`() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        // Give learner practiced competence first
        engine.onVerificationResult(task, VerificationResult(VerificationStatus.SUCCESS, "s1", VerificationOperation.CREATE, "Cube created"))
        engine.onVerificationResult(task, VerificationResult(VerificationStatus.SUCCESS, "s2", VerificationOperation.CREATE, "Cube created"))
        val baselineLevel = engine.state.getSkillLevel("blender.primitives")
        val baselineCompetence = engine.state.competenceEstimate

        val log = StringBuilder()
        log.appendLine("=== SCENARIO 2: TELEMETRY FAILURE PROTECTION ===")
        log.appendLine("Timestamp: ${Instant.now()}")
        log.appendLine("Baseline Skill Level: $baselineLevel, Competence: $baselineCompetence")

        // 3 consecutive socket disconnect / missing telemetry failures
        repeat(3) { i ->
            val event = engine.onVerificationResult(
                task,
                VerificationResult(VerificationStatus.FAILURE, "disc_$i", VerificationOperation.CREATE, "Blender socket disconnected; telemetry unavailable"),
                mismatchCategory = "SocketDisconnected",
                failureCause = FailureCause.INFRASTRUCTURE_OR_PROVIDER
            )
            log.appendLine("Infra Failure $i: Rationale='${event.rationale}', Level=${engine.state.getSkillLevel("blender.primitives")}")
        }

        log.appendLine("Final Skill Level: ${engine.state.getSkillLevel("blender.primitives")}")
        log.appendLine("Final Competence: ${engine.state.competenceEstimate}")
        log.appendLine("Learner Difficulties Count: ${engine.state.recordedDifficulties.size}")
        writeEvidence("scenario_2_telemetry_failure_protection.txt", log.toString())

        // Rule 2: Competence and skill level must remain unharmed by infrastructure failures
        assertEquals(baselineLevel, engine.state.getSkillLevel("blender.primitives"))
        assertEquals(baselineCompetence, engine.state.competenceEstimate)
        assertTrue(engine.state.recordedDifficulties.isEmpty())
    }

    // ==========================================
    // SCENARIO 3: Repeated Verified Difficulties & Adaptation
    // ==========================================
    @Test
    fun `Scenario 3 - Repeated Verified Difficulties and Explainable Adaptation`() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        val log = StringBuilder()
        log.appendLine("=== SCENARIO 3: REPEATED VERIFIED DIFFICULTIES & ADAPTATION ===")
        log.appendLine("Timestamp: ${Instant.now()}")

        // Single failure -> ordinary recovery
        val event1 = engine.onVerificationResult(
            task,
            VerificationResult(VerificationStatus.FAILURE, "f1", VerificationOperation.CREATE, "Object mode required but Edit mode active"),
            mismatchCategory = "ModeMismatch",
            failureCause = FailureCause.LEARNER_ACTION
        )
        log.appendLine("Failure 1: HasDifficulty=${engine.state.hasDifficulty("blender.primitives_ModeMismatch")}, Strategy=${event1.strategy}")

        // Repeated failure -> evidence-supported difficulty
        val event2 = engine.onVerificationResult(
            task,
            VerificationResult(VerificationStatus.FAILURE, "f2", VerificationOperation.CREATE, "Object mode required but Edit mode active"),
            mismatchCategory = "ModeMismatch",
            failureCause = FailureCause.LEARNER_ACTION
        )
        log.appendLine("Failure 2 (Repeated): HasDifficulty=${engine.state.hasDifficulty("blender.primitives_ModeMismatch")}, Strategy=${event2.strategy}")
        log.appendLine("Rationale: ${event2.rationale}")

        writeEvidence("scenario_3_repeated_difficulties_adaptation.txt", log.toString())

        assertFalse(engine.state.hasDifficulty("blender.primitives_ModeMismatch") && event1.strategy == AdaptationStrategy.DETAILED_INSTRUCTION)
        assertTrue(engine.state.hasDifficulty("blender.primitives_ModeMismatch"))
        assertEquals(AdaptationStrategy.DETAILED_INSTRUCTION, event2.strategy)
        assertTrue(event2.rationale.contains("Repeated verified difficulty", ignoreCase = true))
    }

    // ==========================================
    // SCENARIO 4: Learner Preference Controls
    // ==========================================
    @Test
    fun `Scenario 4 - Learner Preference Controls`() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        val log = StringBuilder()
        log.appendLine("=== SCENARIO 4: LEARNER PREFERENCE CONTROLS ===")
        log.appendLine("Timestamp: ${Instant.now()}")

        // Switch to CONCISE
        val conciseEvent = engine.updatePreferences(LearnerPreferences(detailPreference = DetailPreference.CONCISE))
        val instrConcise = engine.getInstruction(task)
        log.appendLine("Preference CONCISE: Strategy=${conciseEvent.strategy}, InstrStrategy=${instrConcise.strategy}")

        // Switch to DETAILED
        val detailedEvent = engine.updatePreferences(LearnerPreferences(detailPreference = DetailPreference.DETAILED))
        val instrDetailed = engine.getInstruction(task)
        log.appendLine("Preference DETAILED: Strategy=${detailedEvent.strategy}, InstrStrategy=${instrDetailed.strategy}")

        writeEvidence("scenario_4_learner_preferences.txt", log.toString())

        assertEquals(AdaptationStrategy.CONCISE_INSTRUCTION, instrConcise.strategy)
        assertEquals(AdaptationStrategy.DETAILED_INSTRUCTION, instrDetailed.strategy)
    }

    // ==========================================
    // SCENARIO 5: Atomic Persistence & Restoration Across Restarts
    // ==========================================
    @Test
    fun `Scenario 5 - Atomic Persistence and Restoration Across Restarts`() = runBlocking {
        val tempDir = Files.createTempDirectory("p13_e2e_persistence")
        try {
            val persistence1 = LearnerStatePersistence(rootDirectory = tempDir)
            val engine1 = AdaptivePedagogicalEngine(persistence = persistence1, initialLearnerId = "student_alpha")
            val task = sampleTask()

            engine1.onVerificationResult(task, VerificationResult(VerificationStatus.SUCCESS, "r1", VerificationOperation.CREATE, "Success 1"))
            engine1.onVerificationResult(task, VerificationResult(VerificationStatus.SUCCESS, "r2", VerificationOperation.CREATE, "Success 2"))
            engine1.updatePreferences(LearnerPreferences(detailPreference = DetailPreference.CONCISE, wantsSmallerSteps = false))

            val saved = engine1.persistState()
            assertTrue(saved)

            // Simulate fresh application boot
            val persistence2 = LearnerStatePersistence(rootDirectory = tempDir)
            val engine2 = AdaptivePedagogicalEngine(persistence = persistence2, initialLearnerId = "student_alpha")
            val restored = engine2.restoreState("student_alpha")
            assertTrue(restored)

            val log = buildString {
                appendLine("=== SCENARIO 5: ATOMIC PERSISTENCE & RESTORATION ===")
                appendLine("Timestamp: ${Instant.now()}")
                appendLine("Restored Learner ID: ${engine2.state.learnerId}")
                appendLine("Schema Version: ${engine2.state.schemaVersion}")
                appendLine("Verified Successes: ${engine2.state.totalVerifiedSuccesses}")
                appendLine("Skill Level: ${engine2.state.getSkillLevel("blender.primitives")}")
                appendLine("Detail Preference: ${engine2.state.preferences.detailPreference}")
            }
            writeEvidence("scenario_5_atomic_persistence_restoration.txt", log)

            assertEquals("student_alpha", engine2.state.learnerId)
            assertEquals(1, engine2.state.schemaVersion)
            assertEquals(2, engine2.state.totalVerifiedSuccesses)
            assertEquals(SkillMasteryLevel.PRACTICED, engine2.state.getSkillLevel("blender.primitives"))
            assertEquals(DetailPreference.CONCISE, engine2.state.preferences.detailPreference)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    // ==========================================
    // SCENARIO 6: Persistence Fault Tolerance
    // ==========================================
    @Test
    fun `Scenario 6 - Persistence Fault Tolerance`() = runBlocking {
        val tempDir = Files.createTempDirectory("p13_e2e_faults")
        try {
            val persistence = LearnerStatePersistence(rootDirectory = tempDir)

            // 1. Missing file -> null
            val resMissing = persistence.load("ghost_user")
            assertNull(resMissing)

            // 2. Empty file -> null
            val emptyFile = tempDir.resolve("empty.json")
            Files.writeString(emptyFile, "   ")
            val resEmpty = persistence.load("empty")
            assertNull(resEmpty)

            // 3. Corrupt file -> null
            val corruptFile = tempDir.resolve("corrupt.json")
            Files.writeString(corruptFile, "{not a valid json...")
            val resCorrupt = persistence.load("corrupt")
            assertNull(resCorrupt)

            // 4. Unsupported future schema -> null
            val futureFile = tempDir.resolve("future.json")
            Files.writeString(futureFile, "{\"schemaVersion\": 50, \"learnerId\": \"future\"}")
            val resFuture = persistence.load("future")
            assertNull(resFuture)

            val log = buildString {
                appendLine("=== SCENARIO 6: PERSISTENCE FAULT TOLERANCE ===")
                appendLine("Timestamp: ${Instant.now()}")
                appendLine("Missing file handled safely: ${resMissing == null}")
                appendLine("Empty file handled safely: ${resEmpty == null}")
                appendLine("Corrupted file handled safely: ${resCorrupt == null}")
                appendLine("Unsupported schema version 50 handled safely: ${resFuture == null}")
            }
            writeEvidence("scenario_6_persistence_fault_tolerance.txt", log)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    // ==========================================
    // SCENARIO 7: Full Closed-Loop Integration Across Phases 6-12
    // ==========================================
    @Test
    fun `Scenario 7 - Closed-Loop Tutoring Integration with Phases 6-12`() {
        val controller = TutorController(loadMockTutorialByDefault = true)
        val initialIndex = controller.state.currentStepIndex
        val tracker = controller.goalProgressTracker

        val log = StringBuilder()
        log.appendLine("=== SCENARIO 7: CLOSED-LOOP TUTORING INTEGRATION ===")
        log.appendLine("Timestamp: ${Instant.now()}")
        log.appendLine("Initial Step Index: $initialIndex, Initial Plan Version: ${tracker.planVersion}")

        val activeStep = controller.currentStep!!
        val outcome = activeStep.expectedState?.operation ?: VerificationOperation.CREATE

        // Execute verified step success
        controller.recordVerificationResult(
            VerificationResult(VerificationStatus.SUCCESS, "auth_e2e_1", outcome, "Object successfully created in Blender"),
            expected = activeStep.expectedState
        )

        val stepIndexAfterVerification = controller.state.currentStepIndex
        log.appendLine("After Verification Success: StepIndex=$stepIndexAfterVerification, GuidanceStage=${controller.state.guidanceStage}")
        log.appendLine("Learner Total Verified: ${controller.pedagogicalEngine.state.totalVerifiedSuccesses}")
        log.appendLine("Learner Demonstrated Skills: ${controller.pedagogicalEngine.state.demonstratedSkills.keys}")

        // Replan preserves progress with incremented version
        val replanned = controller.replanGoal("create cube and scale it")
        assertNotNull(replanned)
        log.appendLine("After Replan: PlanVersion=${tracker.planVersion}, CurrentStepIndex=${controller.state.currentStepIndex}")

        writeEvidence("scenario_7_closed_loop_integration.txt", log.toString())

        assertEquals(1, stepIndexAfterVerification)
        assertEquals(1, controller.pedagogicalEngine.state.totalVerifiedSuccesses)
        assertEquals(2L, tracker.planVersion)
        assertEquals(0, controller.state.currentStepIndex)
    }
}
