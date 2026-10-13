# Root Cause Analysis: Blender State Synchronization & UV Sphere Verification Defect

## Executive Summary
A critical defect in AIVisualTutor caused the tutoring UI to report prerequisite failures (`⚠️ Prerequisite steps must be satisfied before this step can be verified`) and retry exhaustion (`Retry limit reached`) while the learner had successfully created a UV sphere in Blender.

The failure was not caused by a single bug, but by **three interacting defects** across prerequisite tracking, baseline capture lifecycle, and delta verification:
1. **Prerequisite & Tracker Disconnect**: Instructional steps without explicit `expectedState` (such as Step 2: "Add Mesh Sphere") were not recorded in `GoalProgressTracker.completedStepIds` when advancing past them. Consequently, Step 3 (`step_3`), which listed `step_2` as a prerequisite, failed prerequisite checks.
2. **Post-Action Baseline Contamination**: `ClosedLoopRecoveryCoordinator.verifyActiveStep()` captured its baseline state synchronously at the moment the user clicked "Verify" (after the user had already created the sphere in Step 2). Both baseline and post-action observations contained `Sphere`.
3. **Rigid Delta Rejection**: `StateVerifier.verifyOperation` for `VerificationOperation.CREATE` enforces `before.isNotEmpty() -> false`. Because `run.baseline` already contained the sphere created by the learner, verification unconditionally evaluated to `FAILURE`, looping until the retry limit (3 attempts) was exhausted.
4. **Fragile Name Matching**: `objectMatchesName` used strict case-insensitive equality, failing on Blender auto-numbered names such as `"Sphere.001"`.

---

## Source Locations & Affected Symbols

| Component | File | Symbol(s) | Role in Defect |
|---|---|---|---|
| Goal Planning | `UserGoalPlanner.kt` | `planCreateSphere()` | Step 1 mode check was unmodeled; Step 2 had `expectedState = null`, while Step 3 declared `prerequisiteStepIds = listOf("step_2")`. |
| Step Navigation | `TutorController.kt` | `nextStep()`, `goToStep()` | Advancing past non-verifiable steps did not register them in `GoalProgressTracker.completedStepIds`. |
| UI State Display | `TutorPanel.kt` | `allSteps.forEachIndexed` | Checkmarks displayed `✔` whenever `idx < currentStepIndex`, masking the fact that the underlying tracker had not completed earlier prerequisites. |
| Verification Lifecycle | `ClosedLoopRecoveryCoordinator.kt` | `verifyActiveStep()`, `onStepChanged()` | Baseline was captured *after* the learner created the object, poisoning delta verification. |
| Re-evaluation Fallback | `BlenderVerificationWorkflow.kt` | `verifyWithPreCreationBaseline()` | Workflow lacked a defensible mechanism to confirm an object's genuine presence if baseline was established after creation. |
| Object Matching | `ClosedLoopStateVerification.kt` | `objectMatchesName()` | Did not support Blender standard numbered duplicates (`Sphere.001`). |

---

## Evidence Establishing the Root Cause

1. **Prerequisite Warning Banner**:
   In `TutorPanel.kt`:
   ```kotlin
   if (!controller.canExecuteCurrentStep()) {
       Text("⚠️ Prerequisite steps must be satisfied before this step can be verified.")
   }
   ```
   `controller.canExecuteCurrentStep()` delegated to `goalProgressTracker.canExecuteStep(step)`. Because `step_2` was never verified through `recordStepVerification()`, `canExecuteStep()` returned `false`. Meanwhile, the step card rendered a cosmetic `✔` merely because `idx < currentStepIndex`.

2. **Baseline Contamination Trace**:
   When the learner was on Step 2 ("Add Mesh Sphere"), they created the sphere in Blender. Advancing to Step 3 cleared `activeBaselineRun`. When clicking "Verify", `coordinator.verifyActiveStep()` called `workflow.captureBaseline(expected)`. At this timestamp, the sphere was already in Blender. Immediately thereafter, `workflow.verifyAction(run)` captured the post-action state. Both states had `Sphere`.

3. **Delta Rejection Trace**:
   In `StateVerifier.verifyOperation`:
   ```kotlin
   VerificationOperation.CREATE -> when {
       before.isNotEmpty() -> false
   }
   ```
   Because `before` (from baseline) had `Sphere`, the operation verdict was `false`, producing `VerificationResult(status = FAILURE)`.

4. **Retry Limit Deadlock**:
   Every retry repeated the contaminated capture (`before` containing `Sphere`), failing every time until `attemptNumber >= policy.maxVerifiedFailuresPerStep` triggered `RetryLimitReached`.

---

## Why Previous Tests Did Not Catch It

- **Faked Sequential Test Providers**:
  In `ClosedLoopRecoveryCoordinatorTest.kt:p11_01_successfulVerificationClosesLifecycleAndAdvancesLesson`:
  The test used `SequentialBlenderStateProvider`:
  - Call 1 returned `Cube` only.
  - Call 2 returned `Cube` and `Sphere`.
  This test artificially simulated the creation happening *between* `captureBaseline()` and `verifyAction()`. In live user tutoring, the user creates the object *before* clicking verify.
- **Isolated Unit Tests**:
  `GoalProgressTrackerTest` tested tracker logic with programmatic `recordStepVerification` calls, never testing the UI click-through path where instructional steps (`expectedState == null`) were navigated past using `nextStep()`.
