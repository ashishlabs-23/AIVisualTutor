package tutor

import context.ExpectedState
import context.ObjectObservation
import context.ObservedState
import context.VerificationOperation
import context.VerificationStatus
import context.extractObjectProperty
import context.evaluatePropertyCondition
import models.DirectionalAxis
import models.DirectionalIndicator
import models.HighlightRegion
import models.TutorStep
import java.time.Instant

/**
 * PHASE 10: Explicit learner outcome classifications with rigorous evidence contracts.
 */
enum class LearnerOutcome {
    /** Fresh state satisfies all expected task conditions. */
    SUCCESS,

    /** Target object moved in the intended direction, but has not yet met completion threshold. */
    PROGRESS_OBSERVED,

    /** Target object or scene changed, but expected condition was not met (e.g. moved along wrong/irrelevant axis). */
    EXPECTED_CONDITION_NOT_MET,

    /** Target object transform is identical to baseline within tolerance; no relevant action taken. */
    NO_PROGRESS,

    /** Target object was moved or transformed strictly in the opposite direction from the requirement. */
    WRONG_DIRECTION_CONFIRMED,

    /** Intended target object was unchanged, but a different scene object was selected or modified. */
    TARGET_MISMATCH,

    /** Telemetry or target object observation is missing, disconnected, or incomplete. */
    EVIDENCE_UNAVAILABLE,

    /** Observation timestamp or snapshot version is out-of-order or older than baseline. */
    OBSERVATION_STALE
}

/**
 * 3D vector representing object transform (position, rotation, or scale).
 */
data class Vector3D(val x: Double, val y: Double, val z: Double) {
    fun distanceTo(other: Vector3D): Double {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        val ZERO = Vector3D(0.0, 0.0, 0.0)

        fun fromString(str: String?): Vector3D? {
            if (str.isNullOrBlank()) return null
            val clean = str.removePrefix("(").removeSuffix(")").trim()
            val parts = clean.split(",")
            if (parts.size >= 3) {
                val x = parts[0].trim().toDoubleOrNull() ?: return null
                val y = parts[1].trim().toDoubleOrNull() ?: return null
                val z = parts[2].trim().toDoubleOrNull() ?: return null
                return Vector3D(x, y, z)
            }
            return null
        }
    }
}

/**
 * Complete snapshot of learner evidence observed for an active tutorial step.
 */
data class LearnerStepEvidence(
    val stepId: Int,
    val stepTitle: String,
    val targetObjectName: String?,
    val baselineTransform: Vector3D?,
    val latestTransform: Vector3D?,
    val transformDelta: Vector3D?,
    val expectedCondition: String?,
    val expectedProperty: String?,
    val verificationStatus: VerificationStatus?,
    val failedAttemptsCount: Int,
    val stepStartTime: Instant,
    val observationTime: Instant,
    val outcome: LearnerOutcome,
    val diagnosticExplanation: String,
    val hintLevel: Int = 1,
    val activeHint: String? = null,
    val activeDirectionalIndicator: DirectionalIndicator? = null
)

/**
 * Deterministic 4-level progressive hint definition.
 */
data class ProgressiveHintBundle(
    val level1Explanation: String,        // Level 1: Explain requested action
    val level2ToolIdentification: String, // Level 2: Identify relevant tool or control
    val level3ConcreteSequence: String,   // Level 3: Provide concrete sequence of user actions
    val level4RecoveryGuidance: String    // Level 4: Offer recovery guidance when repeated attempts fail
) {
    fun getHintForLevel(level: Int): String {
        return when (level.coerceIn(1, 4)) {
            1 -> level1Explanation
            2 -> level2ToolIdentification
            3 -> level3ConcreteSequence
            4 -> level4RecoveryGuidance
            else -> level1Explanation
        }
    }
}

/**
 * Evidence-Adaptive Visual Tutoring Engine.
 * Evaluates learner telemetry against baseline and expected state to:
 * 1. Formulate authoritative [LearnerOutcome] classifications without guessing.
 * 2. Serve deterministic 4-level progressive hints that adapt to progress.
 * 3. Adapt visual guidance and directional indicators to correct specific learner errors.
 */
class AdaptiveTutoringEngine(
    val defaultTolerance: Double = 0.02 // 2 cm tolerance
) {
    // Track hint levels per step index
    private val stepHintLevels = mutableMapOf<Int, Int>()

    fun getHintLevelForStep(stepIndex: Int): Int = stepHintLevels.getOrDefault(stepIndex, 1)

    fun resetHintLevelForStep(stepIndex: Int) {
        stepHintLevels[stepIndex] = 1
    }

    fun advanceHintLevelForStep(stepIndex: Int): Int {
        val next = (stepHintLevels.getOrDefault(stepIndex, 1) + 1).coerceAtMost(4)
        stepHintLevels[stepIndex] = next
        return next
    }

    fun getProgressiveHintLevel(stepIndex: Int): Int = getHintLevelForStep(stepIndex)

    fun resetProgressiveHintLevel(stepIndex: Int) = resetHintLevelForStep(stepIndex)

    fun getEscalatedHintForStep(step: TutorStep, stepIndex: Int): String {
        val level = advanceHintLevelForStep(stepIndex)
        return getProgressiveHintBundle(step).getHintForLevel(level)
    }

    /**
     * Builds deterministic 4-level progressive hints specific to the active step.
     */
    fun getProgressiveHintBundle(step: TutorStep): ProgressiveHintBundle {
        val target = step.expectedState?.targetName ?: step.targetControlName ?: "selected object"
        val isRotate = step.title.contains("Rotate", ignoreCase = true) ||
            step.expectedState?.targetProperty?.contains("rot", ignoreCase = true) == true
        val isUpsideDown = step.title.contains("upside down", ignoreCase = true) ||
            step.expectedState?.expectedPropertyValue?.contains("upside", ignoreCase = true) == true
        val isScale = step.title.contains("Scale", ignoreCase = true) ||
            step.expectedState?.targetProperty?.contains("scale", ignoreCase = true) == true
        val isDelete = step.expectedState?.operation == VerificationOperation.DELETE ||
            step.title.contains("Delete", ignoreCase = true)
        val isMoveUp = !isRotate && (step.title.contains("Up", ignoreCase = true) ||
            step.expectedState?.targetProperty?.contains("location.z", ignoreCase = true) == true ||
            step.expectedState?.targetProperty?.contains("location", ignoreCase = true) == true)

        return if (isMoveUp) {
            ProgressiveHintBundle(
                level1Explanation = "Translate the $target upward along Blender's global vertical Z-axis.",
                level2ToolIdentification = "Activate the Move tool (shortcut: 'G' for Grab) and lock translation to the Z-axis by pressing 'Z'.",
                level3ConcreteSequence = "1. Left-click '$target' in the 3D viewport. 2. Press 'G'. 3. Immediately press 'Z'. 4. Move your mouse upward and left-click to confirm.",
                level4RecoveryGuidance = "If '$target' moved in the wrong direction, press 'Alt + G' to clear location back to (0,0,0), or press 'N' to open the Transform panel and set Z to a positive number (e.g., 2.0 m)."
            )
        } else if (isDelete) {
            ProgressiveHintBundle(
                level1Explanation = "Remove '$target' from the active Blender scene.",
                level2ToolIdentification = "Select '$target' in the 3D Viewport or Outliner, then press 'Delete' or 'X'.",
                level3ConcreteSequence = "1. Left-click '$target' to ensure it has an orange selection outline. 2. Press 'X' or 'Delete' on your keyboard. 3. Click 'Delete' on the confirmation pop-up if prompted.",
                level4RecoveryGuidance = "If '$target' cannot be selected in the 3D viewport, locate '$target' in Blender's top-right Outliner list, right-click it, and select 'Delete'."
            )
        } else if (isRotate) {
            ProgressiveHintBundle(
                level1Explanation = if (isUpsideDown) "Rotate '$target' so its top face points downward (upside down)." else "Rotate '$target' around an axis.",
                level2ToolIdentification = "Activate the Rotate tool by pressing 'R' on your keyboard, then press X, Y, or Z to constrain rotation.",
                level3ConcreteSequence = if (isUpsideDown) {
                    "1. Left-click '$target' to select it. 2. Press 'R', then press 'X' (or 'Y'). 3. Type '180' and press Enter."
                } else {
                    "1. Left-click '$target'. 2. Press 'R'. 3. Move your mouse to rotate. 4. Left-click to confirm."
                },
                level4RecoveryGuidance = "Press 'Alt + R' to clear all rotation on '$target' back to (0, 0, 0), then try again."
            )
        } else if (isScale) {
            ProgressiveHintBundle(
                level1Explanation = "Resize '$target' to change its dimensions in the 3D scene.",
                level2ToolIdentification = "Activate the Scale tool by pressing 'S' on your keyboard.",
                level3ConcreteSequence = "1. Left-click '$target'. 2. Press 'S'. 3. Move your mouse outward to enlarge (or inward to shrink). 4. Left-click to confirm.",
                level4RecoveryGuidance = "Press 'Alt + S' to clear scale on '$target' back to default (1.0, 1.0, 1.0)."
            )
        } else {
            val tool = step.targetControlName ?: "the required tool"
            ProgressiveHintBundle(
                level1Explanation = step.description.ifBlank { "Perform the requested action: ${step.title}." },
                level2ToolIdentification = "Locate '$tool' in Blender's interface or viewport header.",
                level3ConcreteSequence = step.instruction.ifBlank { "Follow step instructions carefully to satisfy the verification requirement." },
                level4RecoveryGuidance = "Check Blender's Outliner or status bar to confirm '$target' reflects the intended state before clicking Verify."
            )
        }
    }

    /**
     * Observes and classifies learner progress from a BaselineState snapshot.
     */
    fun observeLearnerProgress(
        step: TutorStep,
        stepIndex: Int,
        baseline: context.BaselineState?,
        observed: ObservedState?,
        verificationStatus: VerificationStatus?,
        failedAttemptsCount: Int = 0,
        stepStartTime: Instant = Instant.now()
    ): LearnerStepEvidence {
        val convertedBase = baseline?.let {
            ObservedState(
                runId = it.runId,
                capturedAt = it.capturedAt,
                applicationName = it.applicationName,
                sceneName = it.sceneName,
                mode = it.mode,
                objects = it.objects,
                selectedObjects = it.selectedObjects,
                metadata = it.metadata,
                isComplete = it.isComplete
            )
        }
        return observeLearnerProgress(
            step = step,
            stepIndex = stepIndex,
            baseline = convertedBase,
            observed = observed,
            verificationStatus = verificationStatus,
            failedAttemptsCount = failedAttemptsCount,
            stepStartTime = stepStartTime
        )
    }

    /**
     * Observes and classifies learner progress based on authoritative evidence.
     */
    fun observeLearnerProgress(
        step: TutorStep,
        stepIndex: Int,
        baseline: ObservedState?,
        observed: ObservedState?,
        verificationStatus: VerificationStatus?,
        failedAttemptsCount: Int = 0,
        stepStartTime: Instant = Instant.now()
    ): LearnerStepEvidence {
        val now = Instant.now()
        val expected = step.expectedState
        val targetName = expected?.targetName ?: step.targetControlName ?: "Cube"
        val hintBundle = getProgressiveHintBundle(step)

        // 1. Check for Observation Stale
        if (observed != null && baseline != null) {
            val baseVer = baseline.metadata["snapshotVersion"]?.toLongOrNull() ?: 0L
            val obsVer = observed.metadata["snapshotVersion"]?.toLongOrNull() ?: 0L
            val isMarkedStale = observed.metadata["stale"] == "true"
            if (isMarkedStale || (baseVer > 0L && obsVer > 0L && obsVer < baseVer)) {
                return LearnerStepEvidence(
                    stepId = step.id,
                    stepTitle = step.title,
                    targetObjectName = targetName,
                    baselineTransform = null,
                    latestTransform = null,
                    transformDelta = null,
                    expectedCondition = expected?.expectedPropertyValue,
                    expectedProperty = expected?.targetProperty,
                    verificationStatus = verificationStatus,
                    failedAttemptsCount = failedAttemptsCount,
                    stepStartTime = stepStartTime,
                    observationTime = now,
                    outcome = LearnerOutcome.OBSERVATION_STALE,
                    diagnosticExplanation = "Observation rejected: arrived out-of-order or marked stale (snapshot $obsVer < baseline $baseVer).",
                    hintLevel = getHintLevelForStep(stepIndex),
                    activeHint = hintBundle.getHintForLevel(getHintLevelForStep(stepIndex)),
                    activeDirectionalIndicator = null
                )
            }
        }

        // 2. Check for Evidence Unavailable
        if (observed == null || observed.objects.isEmpty()) {
            return LearnerStepEvidence(
                stepId = step.id,
                stepTitle = step.title,
                targetObjectName = targetName,
                baselineTransform = null,
                latestTransform = null,
                transformDelta = null,
                expectedCondition = expected?.expectedPropertyValue,
                expectedProperty = expected?.targetProperty,
                verificationStatus = verificationStatus,
                failedAttemptsCount = failedAttemptsCount,
                stepStartTime = stepStartTime,
                observationTime = now,
                outcome = LearnerOutcome.EVIDENCE_UNAVAILABLE,
                diagnosticExplanation = "Telemetry unavailable: Blender state observation could not be retrieved.",
                hintLevel = getHintLevelForStep(stepIndex),
                activeHint = hintBundle.getHintForLevel(getHintLevelForStep(stepIndex)),
                activeDirectionalIndicator = null
            )
        }

        val baseObj = baseline?.objects?.find { it.name.equals(targetName, ignoreCase = true) }
        val obsObj = observed.objects.find { it.name.equals(targetName, ignoreCase = true) }

        // If target is missing and task is not DELETE
        if (obsObj == null && expected?.operation != VerificationOperation.DELETE) {
            return LearnerStepEvidence(
                stepId = step.id,
                stepTitle = step.title,
                targetObjectName = targetName,
                baselineTransform = null,
                latestTransform = null,
                transformDelta = null,
                expectedCondition = expected?.expectedPropertyValue,
                expectedProperty = expected?.targetProperty,
                verificationStatus = verificationStatus,
                failedAttemptsCount = failedAttemptsCount,
                stepStartTime = stepStartTime,
                observationTime = now,
                outcome = LearnerOutcome.EVIDENCE_UNAVAILABLE,
                diagnosticExplanation = "Target object '$targetName' not found in active Blender scene.",
                hintLevel = getHintLevelForStep(stepIndex),
                activeHint = hintBundle.getHintForLevel(getHintLevelForStep(stepIndex)),
                activeDirectionalIndicator = null
            )
        }

        // 3. Check for Verified Success
        if (verificationStatus == VerificationStatus.SUCCESS) {
            resetHintLevelForStep(stepIndex)
            return LearnerStepEvidence(
                stepId = step.id,
                stepTitle = step.title,
                targetObjectName = targetName,
                baselineTransform = extractTransform(baseObj),
                latestTransform = extractTransform(obsObj),
                transformDelta = computeDelta(extractTransform(baseObj), extractTransform(obsObj)),
                expectedCondition = expected?.expectedPropertyValue,
                expectedProperty = expected?.targetProperty,
                verificationStatus = verificationStatus,
                failedAttemptsCount = failedAttemptsCount,
                stepStartTime = stepStartTime,
                observationTime = now,
                outcome = LearnerOutcome.SUCCESS,
                diagnosticExplanation = "Action verified: required state conditions were satisfied in Blender.",
                hintLevel = 1,
                activeHint = null,
                activeDirectionalIndicator = null
            )
        }

        // Extract transforms for comparison
        val baseVec = extractTransform(baseObj)
        val obsVec = extractTransform(obsObj)
        val deltaVec = computeDelta(baseVec, obsVec)

        // 4. Check for Target Mismatch
        // If target object was not transformed (|delta| <= tolerance), but another mesh object moved or was selected
        val targetMoved = deltaVec != null && (Math.abs(deltaVec.x) > defaultTolerance || Math.abs(deltaVec.y) > defaultTolerance || Math.abs(deltaVec.z) > defaultTolerance)
        if (!targetMoved && baseline != null) {
            val otherModified = observed.objects.find { otherObs ->
                !otherObs.name.equals(targetName, ignoreCase = true) &&
                otherObs.type == "MESH" &&
                baseline.objects.find { it.name.equals(otherObs.name, ignoreCase = true) }?.let { otherBase ->
                    val otherBaseVec = extractTransform(otherBase)
                    val otherObsVec = extractTransform(otherObs)
                    val otherDelta = computeDelta(otherBaseVec, otherObsVec)
                    otherDelta != null && (Math.abs(otherDelta.x) > defaultTolerance || Math.abs(otherDelta.y) > defaultTolerance || Math.abs(otherDelta.z) > defaultTolerance)
                } == true
            }

            val otherSelected = observed.selectedObjects.firstOrNull { !it.equals(targetName, ignoreCase = true) }

            if (otherModified != null || (otherSelected != null && !observed.selectedObjects.contains(targetName))) {
                val wrongName = otherModified?.name ?: otherSelected ?: "another object"
                val level = advanceHintLevelForStep(stepIndex)
                return LearnerStepEvidence(
                    stepId = step.id,
                    stepTitle = step.title,
                    targetObjectName = targetName,
                    baselineTransform = baseVec,
                    latestTransform = obsVec,
                    transformDelta = deltaVec,
                    expectedCondition = expected?.expectedPropertyValue,
                    expectedProperty = expected?.targetProperty,
                    verificationStatus = verificationStatus,
                    failedAttemptsCount = failedAttemptsCount,
                    stepStartTime = stepStartTime,
                    observationTime = now,
                    outcome = LearnerOutcome.TARGET_MISMATCH,
                    diagnosticExplanation = "Target mismatch: '$wrongName' was selected or modified instead of intended target '$targetName'.",
                    hintLevel = level,
                    activeHint = "Make sure to select '$targetName' in Blender, not '$wrongName'. Left-click '$targetName' to activate it.",
                    activeDirectionalIndicator = DirectionalIndicator(DirectionalAxis.UP_POSITIVE_Z, "Target: $targetName", "Select '$targetName'")
                )
            }
        }

        // 4B. Evaluate Deletion Tasks
        if (expected?.operation == VerificationOperation.DELETE) {
            val level = getHintLevelForStep(stepIndex)
            val baseTarget = baseline?.objects?.find { it.name.equals(targetName, ignoreCase = true) }
            val obsTarget = observed?.objects?.find { it.name.equals(targetName, ignoreCase = true) }

            // Target was absent before the action
            if (baseTarget == null && baseline != null) {
                return LearnerStepEvidence(
                    stepId = step.id,
                    stepTitle = step.title,
                    targetObjectName = targetName,
                    baselineTransform = null,
                    latestTransform = null,
                    transformDelta = null,
                    expectedCondition = "Object '$targetName' deleted",
                    expectedProperty = "existence",
                    verificationStatus = verificationStatus,
                    failedAttemptsCount = failedAttemptsCount,
                    stepStartTime = stepStartTime,
                    observationTime = now,
                    outcome = LearnerOutcome.EVIDENCE_UNAVAILABLE,
                    diagnosticExplanation = "Target '$targetName' was not present in the scene before the deletion attempt.",
                    hintLevel = level,
                    activeHint = "Object '$targetName' did not exist in the scene before this step.",
                    activeDirectionalIndicator = null
                )
            }

            // Target still exists in observed state
            if (obsTarget != null) {
                // Check if another object was deleted instead (Target Mismatch)
                val baseMeshNames = baseline?.objects?.filter { it.type == "MESH" }?.mapNotNull { it.name }?.toSet().orEmpty()
                val obsMeshNames = observed?.objects?.filter { it.type == "MESH" }?.mapNotNull { it.name }?.toSet().orEmpty()
                val deletedOther = (baseMeshNames - obsMeshNames).firstOrNull { !it.equals(targetName, ignoreCase = true) }

                if (deletedOther != null) {
                    return LearnerStepEvidence(
                        stepId = step.id,
                        stepTitle = step.title,
                        targetObjectName = targetName,
                        baselineTransform = baseVec,
                        latestTransform = obsVec,
                        transformDelta = null,
                        expectedCondition = "Object '$targetName' deleted",
                        expectedProperty = "existence",
                        verificationStatus = verificationStatus,
                        failedAttemptsCount = failedAttemptsCount,
                        stepStartTime = stepStartTime,
                        observationTime = now,
                        outcome = LearnerOutcome.TARGET_MISMATCH,
                        diagnosticExplanation = "Target mismatch: object '$deletedOther' was deleted instead of intended target '$targetName'.",
                        hintLevel = level,
                        activeHint = "You deleted '$deletedOther'! Please delete '$targetName' instead.",
                        activeDirectionalIndicator = null
                    )
                }

                return LearnerStepEvidence(
                    stepId = step.id,
                    stepTitle = step.title,
                    targetObjectName = targetName,
                    baselineTransform = baseVec,
                    latestTransform = obsVec,
                    transformDelta = null,
                    expectedCondition = "Object '$targetName' deleted",
                    expectedProperty = "existence",
                    verificationStatus = verificationStatus,
                    failedAttemptsCount = failedAttemptsCount,
                    stepStartTime = stepStartTime,
                    observationTime = now,
                    outcome = LearnerOutcome.NO_PROGRESS,
                    diagnosticExplanation = "No progress: object '$targetName' still exists in the scene.",
                    hintLevel = level,
                    activeHint = hintBundle.getHintForLevel(level),
                    activeDirectionalIndicator = null
                )
            }

            if (verificationStatus == VerificationStatus.SUCCESS) {
                resetHintLevelForStep(stepIndex)
                return LearnerStepEvidence(
                    stepId = step.id,
                    stepTitle = step.title,
                    targetObjectName = targetName,
                    baselineTransform = baseVec,
                    latestTransform = null,
                    transformDelta = null,
                    expectedCondition = "Object '$targetName' deleted",
                    expectedProperty = "existence",
                    verificationStatus = verificationStatus,
                    failedAttemptsCount = failedAttemptsCount,
                    stepStartTime = stepStartTime,
                    observationTime = now,
                    outcome = LearnerOutcome.SUCCESS,
                    diagnosticExplanation = "Object '$targetName' successfully deleted from the active scene.",
                    hintLevel = 1,
                    activeHint = "Task verified: object '$targetName' was deleted.",
                    activeDirectionalIndicator = null
                )
            }
        }

        // 5. Evaluate Movement and Transformation Tasks
        val prop = expected?.targetProperty ?: expected?.requiredConditions?.find { it.propertyName != null }?.propertyName ?: "location.z"
        val isZMovement = prop.equals("location.z", ignoreCase = true) || prop.equals("z", ignoreCase = true)
        val expectedSpec = expected?.expectedPropertyValue ?: expected?.requiredConditions?.find { it.expectedValue != null }?.expectedValue ?: "> 0"
        val isRotate = prop.contains("rot", ignoreCase = true) || step.title.contains("rotate", ignoreCase = true)

        // 5B. Evaluate Rotation Tasks
        if (isRotate && baseline != null) {
            val level = getHintLevelForStep(stepIndex)
            val baseRot = extractObjectProperty(baseline.objects.find { it.name.equals(targetName, ignoreCase = true) }?.metadata ?: emptyMap(), "rotation")
            val obsRot = extractObjectProperty(observed.objects.find { it.name.equals(targetName, ignoreCase = true) }?.metadata ?: emptyMap(), "rotation")

            val baseRotVec = Vector3D.fromString(baseRot)
            val obsRotVec = Vector3D.fromString(obsRot)
            val rotDelta = computeDelta(baseRotVec, obsRotVec)
            val totalRotDistance = rotDelta?.distanceTo(Vector3D.ZERO) ?: 0.0

            // Check if another object was rotated
            val otherRotated = observed.objects.find { otherObs ->
                !otherObs.name.equals(targetName, ignoreCase = true) && otherObs.type == "MESH" &&
                    baseline.objects.find { it.name.equals(otherObs.name, ignoreCase = true) }?.let { otherBase ->
                        val bR = Vector3D.fromString(extractObjectProperty(otherBase.metadata, "rotation"))
                        val oR = Vector3D.fromString(extractObjectProperty(otherObs.metadata, "rotation"))
                        val d = computeDelta(bR, oR)
                        d != null && d.distanceTo(Vector3D.ZERO) > 0.05
                    } == true
            }

            if (totalRotDistance <= 0.05 && otherRotated != null) {
                return LearnerStepEvidence(
                    stepId = step.id,
                    stepTitle = step.title,
                    targetObjectName = targetName,
                    baselineTransform = baseRotVec,
                    latestTransform = obsRotVec,
                    transformDelta = rotDelta,
                    expectedCondition = expectedSpec,
                    expectedProperty = prop,
                    verificationStatus = verificationStatus,
                    failedAttemptsCount = failedAttemptsCount,
                    stepStartTime = stepStartTime,
                    observationTime = now,
                    outcome = LearnerOutcome.TARGET_MISMATCH,
                    diagnosticExplanation = "Target mismatch: '${otherRotated.name}' was rotated instead of '$targetName'.",
                    hintLevel = level,
                    activeHint = "You rotated '${otherRotated.name}'! Please select and rotate '$targetName'.",
                    activeDirectionalIndicator = null
                )
            }

            if (totalRotDistance <= 0.05 && verificationStatus != VerificationStatus.SUCCESS) {
                return LearnerStepEvidence(
                    stepId = step.id,
                    stepTitle = step.title,
                    targetObjectName = targetName,
                    baselineTransform = baseRotVec,
                    latestTransform = obsRotVec,
                    transformDelta = rotDelta,
                    expectedCondition = expectedSpec,
                    expectedProperty = prop,
                    verificationStatus = verificationStatus,
                    failedAttemptsCount = failedAttemptsCount,
                    stepStartTime = stepStartTime,
                    observationTime = now,
                    outcome = LearnerOutcome.NO_PROGRESS,
                    diagnosticExplanation = "No progress: object '$targetName' rotation is unchanged from baseline.",
                    hintLevel = level,
                    activeHint = hintBundle.getHintForLevel(level),
                    activeDirectionalIndicator = null
                )
            }

            if (expectedSpec.contains("upside", ignoreCase = true) && obsRot != null) {
                val isUpsideDown = evaluatePropertyCondition(obsRot, "upside_down")
                if (!isUpsideDown) {
                    return LearnerStepEvidence(
                        stepId = step.id,
                        stepTitle = step.title,
                        targetObjectName = targetName,
                        baselineTransform = baseRotVec,
                        latestTransform = obsRotVec,
                        transformDelta = rotDelta,
                        expectedCondition = expectedSpec,
                        expectedProperty = prop,
                        verificationStatus = verificationStatus,
                        failedAttemptsCount = failedAttemptsCount,
                        stepStartTime = stepStartTime,
                        observationTime = now,
                        outcome = LearnerOutcome.PROGRESS_OBSERVED,
                        diagnosticExplanation = "Rotation observed, but '$targetName' is not yet oriented upside down (local Z axis still points upward).",
                        hintLevel = level,
                        activeHint = "Keep rotating! Rotate the cube by approximately 180 degrees so its top points down.",
                        activeDirectionalIndicator = null
                    )
                }
            }
        }

        if (isZMovement && deltaVec != null) {
            val dz = deltaVec.z
            val dx = deltaVec.x
            val dy = deltaVec.y
            val totalDistance = deltaVec.distanceTo(Vector3D.ZERO)

            // Case A: No State Change
            if (totalDistance <= defaultTolerance) {
                val level = getHintLevelForStep(stepIndex)
                return LearnerStepEvidence(
                    stepId = step.id,
                    stepTitle = step.title,
                    targetObjectName = targetName,
                    baselineTransform = baseVec,
                    latestTransform = obsVec,
                    transformDelta = deltaVec,
                    expectedCondition = expectedSpec,
                    expectedProperty = prop,
                    verificationStatus = verificationStatus,
                    failedAttemptsCount = failedAttemptsCount,
                    stepStartTime = stepStartTime,
                    observationTime = now,
                    outcome = LearnerOutcome.NO_PROGRESS,
                    diagnosticExplanation = "No progress: object '$targetName' remains at baseline position (no movement observed).",
                    hintLevel = level,
                    activeHint = hintBundle.getHintForLevel(level),
                    activeDirectionalIndicator = DirectionalIndicator(DirectionalAxis.UP_POSITIVE_Z, "+Z Up", "Press G then Z to move upward")
                )
            }

            // Case B: Movement along irrelevant axis only (X or Y changed, but Z did not)
            if (Math.abs(dz) <= defaultTolerance && (Math.abs(dx) > defaultTolerance || Math.abs(dy) > defaultTolerance)) {
                val level = getHintLevelForStep(stepIndex)
                return LearnerStepEvidence(
                    stepId = step.id,
                    stepTitle = step.title,
                    targetObjectName = targetName,
                    baselineTransform = baseVec,
                    latestTransform = obsVec,
                    transformDelta = deltaVec,
                    expectedCondition = expectedSpec,
                    expectedProperty = prop,
                    verificationStatus = verificationStatus,
                    failedAttemptsCount = failedAttemptsCount,
                    stepStartTime = stepStartTime,
                    observationTime = now,
                    outcome = LearnerOutcome.EXPECTED_CONDITION_NOT_MET,
                    diagnosticExplanation = "Irrelevant axis movement: object '$targetName' moved horizontally (dx=${format(dx)}m, dy=${format(dy)}m) instead of vertically along the Z-axis.",
                    hintLevel = level,
                    activeHint = "You moved horizontally! Press 'Z' after pressing 'G' to lock movement to the vertical blue Z-axis.",
                    activeDirectionalIndicator = DirectionalIndicator(DirectionalAxis.UP_POSITIVE_Z, "+Z Vertical Only", "Lock to Z-axis by pressing Z")
                )
            }

            // Case C: Movement in the WRONG direction (moved downward when upward was expected)
            val expectsPositiveZ = expectedSpec.contains(">") || expectedSpec.toDoubleOrNull()?.let { it > 0.0 } == true
            if (expectsPositiveZ && dz < -defaultTolerance) {
                val level = getHintLevelForStep(stepIndex)
                return LearnerStepEvidence(
                    stepId = step.id,
                    stepTitle = step.title,
                    targetObjectName = targetName,
                    baselineTransform = baseVec,
                    latestTransform = obsVec,
                    transformDelta = deltaVec,
                    expectedCondition = expectedSpec,
                    expectedProperty = prop,
                    verificationStatus = verificationStatus,
                    failedAttemptsCount = failedAttemptsCount,
                    stepStartTime = stepStartTime,
                    observationTime = now,
                    outcome = LearnerOutcome.WRONG_DIRECTION_CONFIRMED,
                    diagnosticExplanation = "Wrong direction confirmed: object '$targetName' moved downward (dz=${format(dz)}m), but upward (+Z) movement is required.",
                    hintLevel = level,
                    activeHint = "Wrong direction: You moved downward! Move your mouse upward along the blue line to raise the object.",
                    activeDirectionalIndicator = DirectionalIndicator(DirectionalAxis.UP_POSITIVE_Z, "▲ Reverse Direction (Move UP)", "Move mouse upward")
                )
            }

            // Case D: Meaningful partial progress in the correct direction
            if (expectsPositiveZ && dz > defaultTolerance) {
                // Moved upward, but verification failed (e.g. threshold was > 2.0 and learner only reached 0.8)
                // Meaningful progress observed! Reset hint level to Level 1
                resetHintLevelForStep(stepIndex)
                return LearnerStepEvidence(
                    stepId = step.id,
                    stepTitle = step.title,
                    targetObjectName = targetName,
                    baselineTransform = baseVec,
                    latestTransform = obsVec,
                    transformDelta = deltaVec,
                    expectedCondition = expectedSpec,
                    expectedProperty = prop,
                    verificationStatus = verificationStatus,
                    failedAttemptsCount = failedAttemptsCount,
                    stepStartTime = stepStartTime,
                    observationTime = now,
                    outcome = LearnerOutcome.PROGRESS_OBSERVED,
                    diagnosticExplanation = "Progress observed: object '$targetName' moved upward (+${format(dz)}m), but has not yet met the full target condition ($expectedSpec).",
                    hintLevel = 1,
                    activeHint = "Great progress! You moved the cube upward by ${format(dz)}m. Continue moving upward to satisfy $expectedSpec.",
                    activeDirectionalIndicator = DirectionalIndicator(DirectionalAxis.UP_POSITIVE_Z, "▲ Continue Up (+Z)", "Move higher")
                )
            }
        }

        // 6. Generic Failure Fallback
        val level = getHintLevelForStep(stepIndex)
        return LearnerStepEvidence(
            stepId = step.id,
            stepTitle = step.title,
            targetObjectName = targetName,
            baselineTransform = baseVec,
            latestTransform = obsVec,
            transformDelta = deltaVec,
            expectedCondition = expectedSpec,
            expectedProperty = prop,
            verificationStatus = verificationStatus,
            failedAttemptsCount = failedAttemptsCount,
            stepStartTime = stepStartTime,
            observationTime = now,
            outcome = LearnerOutcome.EXPECTED_CONDITION_NOT_MET,
            diagnosticExplanation = "Expected condition not met: observed Blender state does not satisfy '$expectedSpec'.",
            hintLevel = level,
            activeHint = hintBundle.getHintForLevel(level),
            activeDirectionalIndicator = null
        )
    }

    /**
     * Adapts highlight region and directional guidance to reflect the outcome.
     */
    fun adaptHighlightForOutcome(
        baseRegion: HighlightRegion?,
        outcome: LearnerOutcome,
        evidence: LearnerStepEvidence
    ): HighlightRegion? {
        if (baseRegion == null) return null

        return when (outcome) {
            LearnerOutcome.SUCCESS -> baseRegion
            LearnerOutcome.WRONG_DIRECTION_CONFIRMED -> {
                baseRegion.copy(
                    directionalIndicator = DirectionalIndicator(
                        axis = DirectionalAxis.UP_POSITIVE_Z,
                        label = "▲ Reverse: Move Up (+Z)",
                        hint = evidence.activeHint ?: "Move mouse upward along Z-axis"
                    )
                )
            }
            LearnerOutcome.PROGRESS_OBSERVED -> {
                baseRegion.copy(
                    directionalIndicator = DirectionalIndicator(
                        axis = DirectionalAxis.UP_POSITIVE_Z,
                        label = "▲ Keep Moving Up (+Z)",
                        hint = evidence.activeHint ?: "Continue upward"
                    )
                )
            }
            LearnerOutcome.TARGET_MISMATCH -> {
                baseRegion.copy(
                    directionalIndicator = DirectionalIndicator(
                        axis = DirectionalAxis.UP_POSITIVE_Z,
                        label = "Target: ${evidence.targetObjectName}",
                        hint = evidence.activeHint ?: "Select this object"
                    )
                )
            }
            LearnerOutcome.NO_PROGRESS,
            LearnerOutcome.EXPECTED_CONDITION_NOT_MET -> {
                baseRegion.copy(
                    directionalIndicator = evidence.activeDirectionalIndicator ?: baseRegion.directionalIndicator
                )
            }
            LearnerOutcome.EVIDENCE_UNAVAILABLE,
            LearnerOutcome.OBSERVATION_STALE -> baseRegion
        }
    }

    private fun extractTransform(obj: ObjectObservation?): Vector3D? {
        if (obj == null) return null
        val locStr = obj.metadata["location"]
        if (!locStr.isNullOrBlank()) {
            Vector3D.fromString(locStr)?.let { return it }
        }
        val x = extractObjectProperty(obj.metadata, "location.x")?.toDoubleOrNull()
        val y = extractObjectProperty(obj.metadata, "location.y")?.toDoubleOrNull()
        val z = extractObjectProperty(obj.metadata, "location.z")?.toDoubleOrNull()
        if (x != null && y != null && z != null) {
            return Vector3D(x, y, z)
        }
        return null
    }

    private fun computeDelta(base: Vector3D?, current: Vector3D?): Vector3D? {
        if (base == null || current == null) return null
        return Vector3D(current.x - base.x, current.y - base.y, current.z - base.z)
    }

    private fun format(d: Double): String = String.format("%.2f", d)
}
