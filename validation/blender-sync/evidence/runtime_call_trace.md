# Production Runtime Verification Call Trace

## Verified Production Trace from UI Click to Resolution

```
Learner Clicks "🎯 Verify"
  │
  ▼
TutorPanel (onClick)
  │
  ▼
controller.recoveryCoordinator.verifyActiveStep()
  │
  ├── 1. Check Transitive Prerequisites (resolvePrerequisites)
  │     ├── Inspect allPrereqIds in topological order (step_1, step_2)
  │     ├── If unsatisfied, probe live Blender telemetry via workflow.provider.observe()
  │     ├── If Blender state satisfies condition (e.g. mode == "OBJECT"):
  │     │     └── controller.recordPrerequisiteSatisfied(prereqId)
  │     └── If unsatisfied condition contradicted by Blender:
  │           └── return NeedsMoreEvidence with precise prerequisite title & instructions
  │
  ├── 2. Capture Baseline State
  │     └── workflow.captureBaseline(expected)
  │           └── BlenderTcpStateProvider.observe() [5000ms timeout with retry]
  │                 └── Connect to 127.0.0.1:47629
  │                 └── Read TSV header and objects
  │
  ├── 3. Verify Post-Action State
  │     ├── workflow.verifyAction(run)
  │     │     └── Observe post-action scene telemetry
  │     └── If CREATE failed due to pre-existing creation before baseline:
  │           └── workflow.verifyWithPreCreationBaseline(run)
  │                 └── Evaluates clean baseline vs observed
  │
  ├── 4. Process Outcome & Update Progress
  │     ├── handleSuccess(reqId, stepIndex, result, expected)
  │     └── controller.recordVerificationResult(result, expected)
  │           ├── goalProgressTracker.recordStepVerification(stepId, verified = true)
  │           ├── Recompute outcome satisfaction (out_mode_obj, out_sphere_exists)
  │           ├── pedagogicalEngine.ingestLearningEvent(OutcomeVerified)
  │           └── If active step succeeded, auto-advance via nextStep()
  │
  ▼
Compose UI Recomposition
  ├── Header status shows "Blender connected (port 47629)" [Green]
  ├── Prerequisite guard banner clears
  ├── Step card displays "✔"
  └── Speech bubble displays "Action Verified! ✔"
```
