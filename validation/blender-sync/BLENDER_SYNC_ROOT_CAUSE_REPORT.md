# Root Cause Analysis: Blender State Synchronization, Object Verification, and Universal Verify Button Defect

## 1. Executive Summary
During manual and integration testing of AIVisualTutor, four critical failure modes were observed:
1. **Failure A (Object Creation Detection)**: When the user added a UV sphere in Blender, the tutoring system did not detect the sphere reliably, repeatedly reporting mismatch or prerequisite issues.
2. **Failure B (Verify Button Disappearance)**: After completing Step 1 ("Ensure Object Mode"), the tutoring panel displayed Step 2 ("Add Mesh Sphere"), but the "Verify" button completely disappeared from the action area, replaced by "Next" and a stale "Action Verified!" bubble.
3. **Failure C (Object Deletion Detection)**: When a learner deleted an object so that the scene became empty of mesh geometry, verification failed with `Retry Limit Reached` after 3 attempts.
4. **Failure D (Placeholder Identity Bug)**: In deletion tasks, the expected target object was set to `"Active"`, causing verification logic to literally look for a Blender object named `"Active"` instead of resolving the actual selected/active object in the scene.

---

## 2. Root Cause Breakdown by Component and Failure Mode

### Failure A: Object Creation (UV Sphere)
- **Component**: `context.StateVerifier.kt`, `context.ClosedLoopRecoveryCoordinator.kt`, `context.StateMismatchDiagnosticEngine.kt`
- **Root Cause**:
  1. *Rigid Delta Logic*: `StateVerifier.verifyOperation` for `VerificationOperation.CREATE` checked `before.isNotEmpty() -> false`. If baseline was captured after the learner created the object, or if an existing sphere was present, verification unconditionally returned `FAILURE`.
  2. *Duplicate Name Rigidity*: Blender auto-numbers duplicates (e.g. `Sphere.001`, `Sphere.002`). Name matching performed strict exact equality against `"Sphere"`, failing when Blender named the new mesh `Sphere.001`.
  3. *Unresolved Prerequisite Deadlock*: Step 2 ("Add Mesh Sphere") had no `expectedState`, so completing or advancing past it did not register it as satisfied in `GoalProgressTracker`. Step 3 had `step_2` as a prerequisite, blocking verification.

### Failure B: Verify Button Disappearance
- **Component**: `ui.TutorPanel.kt`, `tutor.ClosedLoopRecoveryCoordinator.kt`
- **Root Cause**:
  1. *Conditional UI Rendering*: In `TutorPanel.kt`, the Verify button was enclosed in `if (activeStep.expectedState != null)`. Multi-step goal plans for object creation and manipulation have instructional/action steps (such as Step 2: "Press Shift+A and select Mesh > UV Sphere") where `expectedState` is `null`. Compose completely omitted the button for these steps.
  2. *Stale Speech Bubble*: In `TutorPanel.kt`, the speech bubble logic fell through to checking `controller.state.lastPedagogicalOutcome == PedagogicalOutcome.VERIFIED_SUCCESS` from Step 1, rendering a misleading "Action Verified! ✔" banner on Step 2 before any action had been taken.

### Failure C: Object Deletion Detection
- **Component**: `context.ClosedLoopStateVerification.kt`, `tutor.ClosedLoopRecoveryCoordinator.kt`
- **Root Cause**:
  1. *Rigid Before/After Requirement*: `StateVerifier.verifyOperation` for `VerificationOperation.DELETE` evaluated `before.isEmpty() -> false`. When the learner deleted the object *before* clicking Verify (the natural user behavior), the baseline state already lacked the object. The verifier required the object to be present in baseline, causing immediate failure even though the object was genuinely deleted.
  2. *Missing Scene Absence Fallback*: If the scene was completely emptied of mesh objects (`after.isEmpty()`), the verifier did not treat complete mesh absence as satisfying deletion of the target mesh when baseline lacked it.

### Failure D: "Active" / "Selected" Placeholder Identity Bug
- **Component**: `tutor.UserGoalPlanner.kt`, `context.ClosedLoopStateVerification.kt`, `context.StateMismatchDiagnosticEngine.kt`, `tutor.ClosedLoopRecoveryCoordinator.kt`
- **Root Cause**:
  1. *Literal Regex Extraction*: In `UserGoalPlanner.kt`, `extractTargetObjectName("delete active object")` matched `"active"` as a target object name and returned `"Active"`.
  2. *Literal State Evaluation*: `ExpectedState(operation = DELETE, targetName = "Active")` was passed to the verifier, which searched Blender's scene objects for an object with `name.equals("Active", ignoreCase = true)`. Because Blender's default objects are named `Cube`, `Sphere`, `Camera`, `Light`, the object was never found, or deletion of `Cube` was not recognized as deleting `"Active"`.
  3. *Lack of Dynamic Identity Resolution*: The planner and coordinator did not resolve `"Active"` or `"Selected"` against live Blender telemetry (the active object in the snapshot).

---

## 3. Detailed Call Trace of Defect Propagation

```
User enters goal: "Delete active object" (Blender scene contains 'Sphere' active)
  │
  ▼
UserGoalPlanner.planGoal()
  ├── extracts target name -> "Active" (Literal bug!)
  └── creates step with expectedState: DELETE "Active"
  │
  ▼
Learner deletes 'Sphere' in Blender (Viewport now has only Camera, Light)
  │
  ▼
Learner clicks Verify (or auto-verify triggers)
  │
  ▼
ClosedLoopRecoveryCoordinator.verifyActiveStep()
  ├── Captures baseline -> scene has [Camera, Light] (No 'Sphere', No 'Active')
  ├── Calls workflow.verifyAction()
  │     ├── StateVerifier evaluates DELETE:
  │     │     ├── Target "Active" not in before (before.isEmpty() -> false!)
  │     │     └── Result: FAILURE
  │     └── Result stored as FAILURE
  │
  ▼
Retry loop increments attempt counter (1 -> 2 -> 3)
  └── Triggers: RetryLimitReached ("Step 'Delete Object' reached retry limit of 3 attempts")
```

---

## 4. Source Files Affected
1. `composeApp/src/main/kotlin/ui/TutorPanel.kt`
2. `composeApp/src/main/kotlin/tutor/UserGoalPlanner.kt`
3. `composeApp/src/main/kotlin/tutor/TutorController.kt`
4. `composeApp/src/main/kotlin/tutor/ClosedLoopRecoveryCoordinator.kt`
5. `composeApp/src/main/kotlin/context/ClosedLoopStateVerification.kt`
6. `composeApp/src/main/kotlin/context/StateMismatchDiagnosticEngine.kt`
7. `composeApp/src/main/kotlin/context/BlenderVerificationWorkflow.kt`
8. `blender/ai_visual_tutor_state/__init__.py`
