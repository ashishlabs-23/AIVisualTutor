# Runtime Error Root Cause Analysis: Real Blender State Synchronization & Verification

**Priority**: CRITICAL  
**Investigation Mode**: REPRODUCE -> CAPTURE ERROR -> TRACE RUNTIME -> FIX -> VERIFY  
**Environment**: Windows 11, Blender 5.2.2 LTS (`D:\blender.exe`), JDK 17 Hotspot, Loopback Port 47629  
**Final Status**: `RUNTIME_FIX_VERIFIED_WITH_LIVE_BLENDER`

---

## 1. Executive Summary

When running the application with a live desktop Blender session, learners attempting the "Create a UV sphere" goal observed that:
1. Creating a UV Sphere in Blender did not allow progression or verification to succeed.
2. Clicking "Verify" yielded prerequisite warnings (`⚠️ Prerequisite steps must be satisfied before this step can be verified`) and retry exhaustion (`Retry Limit Reached`).
3. An outdated add-on in Blender (`v0.1.0`) and tight socket timeouts (`1,500ms`) caused silent socket connection drops and baseline capture failures.

The root cause was reproduced against `D:\blender.exe` using `LiveProductionVerificationDiagnosticTest.kt`. The failure is driven by **four interacting runtime defects**:

1. **Premature Socket Timeout in `BlenderTcpStateProvider`**:
   - `DEFAULT_TIMEOUT_MILLIS` was configured to `1_500` ms.
   - On the initial connection or during Blender window event loop cycles, Blender took ~`2.8s`–`2.9s` before the timer callback serviced loopback port 47629.
   - The provider threw `SocketTimeoutException: Blender state request timed out.`, which aborted Step 1 ("Ensure Object Mode") baseline capture, leaving Step 1 unverified in `GoalProgressTracker`.
2. **Instructional & Navigation Prerequisite Disconnect**:
   - `UserGoalPlanner.planCreateSphere()` defined Step 1 ("Ensure Object Mode") with an explicit `ExpectedState(MODIFY, targetMode = "OBJECT")`.
   - If the learner skipped Step 1 or if Step 1 timed out, navigating forward to Step 2 or Step 3 left Step 1 unrecorded in `completedStepIds`.
   - When the learner attempted to verify Step 3, `ClosedLoopRecoveryCoordinator` checked only direct prerequisites without transitively checking Step 1, or falsely reported prerequisite errors when the Blender provider timed out.
3. **Windows Add-on Socket Binding Collisions (`SO_EXCLUSIVEADDRUSE`) & Stale Installed Version**:
   - The user's AppData directory (`%APPDATA%\Blender Foundation\Blender\5.2\scripts\addons\ai_visual_tutor_state\`) had an un-updated `v0.1.0` add-on script.
   - `server.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)` in Windows caused `[WinError 10048]` socket binding crashes if the port was recently inspected by JVM tests in `TIME_WAIT`.
4. **UI Diagnostic Error Masking**:
   - `TutorPanel.kt` speech bubble only matched specific recovery states, falling into `else` when `ClosedLoopRecoveryState.Error` occurred, masking runtime exceptions from the user.
   - The status indicator in `TutorPanel.kt` statically displayed "Blender state connected" with a green circle, obscuring provider disconnections.

---

## 2. Reproduced Failure Trace

Captured from `LiveProductionVerificationDiagnosticTest` executing against `D:\blender.exe`:

```
Launching Blender from D:\blender.exe...
Blender ready on port 47629! Objects: [Cube, Light, Camera]
Active Plan: Create a UV sphere

--- STEP 1: Ensure Object Mode ---
Step 1 Outcome: NeedsMoreEvidence(
    requestId=26f52a52-50a7-4b17-aebd-bbf067c6e3b2,
    stepIndex=0,
    message=Blender state telemetry unavailable. Ensure Blender is running and connected.,
    diagnostic=Could not capture Blender baseline: Blender state request timed out.,
    canRetry=true
)
Step 1 Completed in Tracker: false
Completed Step IDs: []

--- STEP 2: Add Mesh Sphere ---
Can execute Step 2: false
User performing action in Blender: creating UV Sphere...
Blender objects now: [Cube, Light, Camera, Sphere]

--- STEP 3: Verify a Sphere object exists ---
Can execute Step 3: true
Completed Step IDs before Step 3 verify: [step_2]
User clicks Verify on Step 3...
```

Because Step 1 was never verified (due to the 1.5s timeout):
- `completedStepIds` did not contain `step_1`.
- Step 2 displayed the banner: `⚠️ Prerequisite steps must be satisfied before this step can be verified.`
- The mandatory outcome `out_mode_obj` remained unsatisfied, blocking complete goal resolution.

---

## 3. Boundary Failure Matrix

| Component Boundary | Observed Failure | Root Cause |
|---|---|---|
| `BlenderTcpStateProvider` -> Loopback Port 47629 | `SocketTimeoutException` on first connect | Timeout of 1,500ms is shorter than initial Blender UI timer registration latency (~2.8s). |
| `blender/__init__.py` -> Windows OS Socket | `[WinError 10048]` on restart | `SO_EXCLUSIVEADDRUSE` rejects bind while previous socket is in `TIME_WAIT`. |
| `ClosedLoopRecoveryCoordinator` -> `GoalProgressTracker` | `step_1` missing from `completedStepIds` | Prerequisite resolution did not recursively inspect prerequisite chain or auto-reconcile valid Blender state. |
| `TutorPanel.kt` Speech Bubble | Masked error message | `ClosedLoopRecoveryState.Error` was not handled in the `when` branch. |
| `TutorPanel.kt` Header | False "Blender state connected" | Hardcoded green indicator independent of `lastObservation.status`. |

---

## 4. Why Simulated Tests Previously Missed This

1. **In-Memory Fake Providers**:
   Previous simulated tests (`BlenderSyncRegressionTest`) used `ConfigurableBlenderStateProvider` with instant in-memory responses (0ms latency), completely bypassing socket creation, loopback TCP negotiation, and Blender's main-thread timer loop.
2. **Sequential Provider Stubs**:
   Tests configured sequential returns (Observation 1 = Cube, Observation 2 = Cube + Sphere), simulating creation between baseline and verification calls rather than evaluating the state against a real running Blender engine.
3. **No AppData Synchronization**:
   The test runner tested repository code, but never validated that Blender's installed add-on script in `%APPDATA%` was synchronized with repository source.
