# Production Path Trace: Blender Observation, Expected State & Verification Workflow

## 1. Complete Path Overview

```
[Live Blender or BlenderStateProvider]
       │
       ▼ (Socket / Telemetry Observation)
[BlenderStateObservation]
       │
       ▼ (Conversion via toBaselineState() / observedState())
[BaselineState] / [ObservedState]
       │
       ▼
[UserGoalPlanner] ───► [UserGoalPlanStep.expectedState]
       │
       ▼
[GoalProgressTracker] ◄─── (Prerequisite Step IDs & Completion Check)
       │
       ▼
[ClosedLoopRecoveryCoordinator.verifyActiveStep()]
       │
       ├─► workflow.captureBaseline(expected) (Establish baseline)
       ├─► workflow.verifyAction(run) (Direct State verification)
       ├─► workflow.verifyWithPreCreationBaseline(run) (Fallback for existing scene primitives)
       ▼
[EvidenceReconciler.reconcileAndVerify()]
       │
       ├─► StateVerifier.matchesCondition() (Field & Type Evaluation)
       ├─► StateVerifier.verifyOperation() (Delta / Existence Verdict)
       ▼
[VerificationResult] (SUCCESS / FAILURE / UNCERTAIN)
       │
       ▼
[ClosedLoopRecoveryCoordinator.handleSuccess()]
       │
       ▼
[TutorController.recordVerificationResult()]
       │
       ├─► GoalProgressTracker.recordStepVerification(stepId, verified = true)
       ├─► TutorState.copy(currentStepIndex = nextIndex, lastPedagogicalOutcome = VERIFIED_SUCCESS)
       ├─► mutableEvents.emit(TutorEvent.StepAdvanced)
       ▼
[TutorPanel (Compose Desktop UI)]
       │
       ├─► Renders ✔ checkmark using controller.isStepIndexCompleted(idx)
       ├─► Renders Assistant Speech Bubble: "Action Verified! ✔"
       └─► Clears Prerequisite Warning Banner (canExecuteCurrentStep() == true)
```

## 2. Boundary Trace & Parameter Invariants

### Boundary 1: Blender State Capture
- **Class / Symbol**: `context.BlenderTcpStateProvider`
- **Method**: `suspend fun observe(): BlenderStateObservation`
- **Inputs**: TCP loopback connection to Blender on port `9871`.
- **Outputs**: `BlenderStateObservation(sceneName = "Scene", mode = "OBJECT", objects = [...], status = AVAILABLE)`.
- **Invariant**: Must return `BlenderObservationStatus.AVAILABLE` with non-null `sessionId` and complete object records. If connection drops, returns `FAILED` with diagnostic string without fabricating empty scenes.

### Boundary 2: Baseline & Observed State Conversion
- **Class / Symbol**: `context.ClosedLoopStateVerificationKt`
- **Functions**: `toBaselineState(runId)`, `toObservedState(runId)`
- **Inputs**: `BlenderStateObservation`, verification `runId`.
- **Outputs**: `BaselineState` and `ObservedState` containing normalized `ObjectObservation` items.

### Boundary 3: Expected State & Prerequisite Validation
- **Class / Symbol**: `tutor.UserGoalPlanner`, `tutor.GoalProgressTracker`
- **Methods**: `planCreateSphere()`, `canExecuteStep(step)`
- **Step 1**: "Ensure Object Mode" (`step_1`, prerequisites: `[]`, `expectedState = ExpectedState(MODIFY, targetMode = "OBJECT")`).
- **Step 2**: "Add Mesh Sphere" (`step_2`, prerequisites: `["step_1"]`, `expectedState = null`).
- **Step 3**: "Verify a Sphere object exists" (`step_3`, prerequisites: `["step_2"]`, `expectedState = ExpectedState(CREATE, targetName = "Sphere", targetType = "MESH")`).
- **Invariant**: Step 3 can only be executed when `goalProgressTracker.completedStepIds` contains `"step_2"`.

### Boundary 4: Verification Dispatch & Delta Reconciliation
- **Class / Symbol**: `tutor.ClosedLoopRecoveryCoordinator`, `context.BlenderVerificationWorkflow`
- **Method**: `verifyActiveStep()`, `verifyAction(run)`, `verifyWithPreCreationBaseline(run)`
- **Behavior**:
  1. Captures fresh baseline and post-action state via `BlenderVerificationWorkflow`.
  2. If the user already created the sphere during Step 2 (or in an existing scene), `run.baseline` contains `Sphere`.
  3. Delta verification in `StateVerifier.verifyOperation` flags `before.isNotEmpty() -> false`.
  4. The coordinator intercepts this specific condition and invokes `workflow.verifyWithPreCreationBaseline(run)`.
  5. The reconciler validates that `Sphere` (or `Sphere.001`) of type `MESH` genuinely exists in the post-action state and satisfies all required conditions.
  6. Verification transitions to `VerificationStatus.SUCCESS`.

### Boundary 5: TutorController State Update & UI Recomposition
- **Class / Symbol**: `tutor.TutorController`, `ui.TutorPanel`
- **Methods**: `recordVerificationResult()`, `isStepIndexCompleted()`, `canExecuteCurrentStep()`
- **Behavior**:
  1. `goalProgressTracker.recordStepVerification(stepId = "step_3", verified = true)` updates `completedStepIds`.
  2. `TutorController.state` updates `lastPedagogicalOutcome = VERIFIED_SUCCESS`.
  3. `TutorPanel` checks `controller.isStepIndexCompleted(idx)` for step cards, displaying `✔` only for authoritatively verified or completed steps.
  4. Prerequisite warning banner is dismissed because `canExecuteCurrentStep()` returns true.
