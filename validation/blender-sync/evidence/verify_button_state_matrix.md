# Universal Verify Button State Matrix

| Step / Tutoring State | Expected State Configured? | Action Button Visible? | Button Label & State | Visual State Indicator (Speech Bubble) | Action Executed on Click | Recovery / Continuation Path |
|---|---|---|---|---|---|---|
| **Step 1: Initial (Not Started)** | Yes (`MODE: OBJECT`) | Yes | "Verify" (Enabled, Purple Gradient) | "Ready for Action" | Evaluates Mode from Blender telemetry | On success: marks completed & advances |
| **Step 1: Running Verification** | Yes | Yes | "Verifying..." (Disabled, Gray) | "Checking Blender state..." | Blocked by Mutex / In-flight guard | Prevents duplicate click races |
| **Step 1: Verified Success** | Yes | Yes | "Verify" (Enabled) | "Action Verified! ✔" (Green) | Re-evaluates state if clicked | Unlocks "Next" navigation |
| **Step 2: Informational / Action** | None (`expectedState == null`) | Yes | "Verify" (Enabled, Purple Gradient) | "Ready for Action" (Neutral) | Verifies telemetry connection & marks step complete | Unlocks "Next" without getting stuck |
| **Step 2: After Creation in Blender** | Inferred or Explicit (`CREATE Sphere`) | Yes | "Verify" (Enabled, Purple Gradient) | "Ready for Action" | Verifies created mesh against clean baseline | On success: marks completed & advances |
| **Step 2: Verification Failed (Attempt 1-2)** | Yes | Yes | "Verify" (Enabled, Amber/Red accent) | "Correction Needed (Attempt 1 of 3)" | Captures fresh observation & re-evaluates | Guidance and hints provided |
| **Step 2: Retry Limit Reached (Attempt 3)** | Yes | Yes | "Verify" / "Reset & Retry" (Enabled) | "Retry Limit Reached" (Red) | Explicit reset and re-verification permitted | Reset control restores attempt budget |
| **Step 3: Object Deletion (Placeholder 'Active')** | Yes (`DELETE Active`) | Yes | "Verify" (Enabled) | "Ready for Action" | Resolves 'Active' -> 'Sphere' via snapshot; verifies absence | Succeeds when object is absent from scene |
| **Provider Offline / TCP Disconnect** | Any | Yes | "Verify" (Enabled) | "Verification Notice: Blender telemetry unavailable" | Attempts fresh connection; returns diagnostic | Does not falsely claim object absence |
| **Completed Step Re-visit (Previous)** | Yes | Yes | "Verify" (Enabled) | "Action Verified! ✔" | Re-verifies fresh state if requested | Completed status preserved |
