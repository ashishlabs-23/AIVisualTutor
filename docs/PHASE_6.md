# Phase 6: Closed-Loop State Verification

## Scope

This phase adds a deterministic verification pipeline that distinguishes:

- the instruction shown to the user,
- the expected application state,
- the observed state,
- the evidence used to judge the outcome,
- and the final verification decision.

The implementation is intentionally conservative: it does not claim real Blender success without sufficient evidence.

## Current audit

- Audited HEAD: `b463073`.
- Phase 6 implementation files were untracked at the start of this continuation; they were preserved.
- Phase 5 grounding implementations and routing remain separate and were not changed.
- Blender 5.2.2 LTS is installed at `C:\Program Files\Blender Foundation\Blender 5.2\blender.exe`, but is not on PATH.
- A headless smoke test against a disposable factory scene created, renamed, selected, and deleted an object successfully. This tests Blender's executable and data API only; it does not exercise the AIVisualTutor verifier or a live GUI session.
- The verification panel and workflow are connected to the production tutor UI. The current user's desktop Blender process has not yet been confirmed responding to the adapter.

## Implemented contracts

The new Phase 6 models are in `composeApp/src/main/kotlin/context/ClosedLoopStateVerification.kt`.

Implemented concepts:

- `ExpectedState`
- `BaselineState`
- `ObservedState`
- `StateDiff`
- `EvidenceObservation`
- `VerificationResult`
- `VerificationStatus` with `SUCCESS`, `FAILURE`, and `UNCERTAIN`
- `VerificationOperation` with `CREATE`, `DELETE`, `RENAME`, `SELECT`, and `MODIFY`
- `BlenderStateProvider` and `UnavailableBlenderStateProvider`
- `BlenderTcpStateProvider` with an opt-in Blender add-on at `blender/ai_visual_tutor_state/__init__.py`
- `VerificationRun` for run IDs, deadlines, baseline association, and Blender session correlation
- `StateVerifier` for deterministic validation and decision logic

These are kept separate from the Phase 5 grounding provider logic; the Phase 5 interfaces remain intact.

## Verification rules

The deterministic verifier separates:

- instruction text,
- expected app state,
- observed actual state,
- evidence provenance,
- and the final status.

Invalid expected states, mismatched run IDs, stale timestamps, mismatched known applications, incomplete inventories, and conflicting provider observations do not produce `SUCCESS`. Conditions can be satisfied, contradicted, or indeterminate; missing data is not automatically a negative observation. The verifier only returns `SUCCESS` when the operation-specific transition and every required condition are supported by complete baseline/post-action observations and available UIA or Blender state evidence. Visual-only evidence cannot establish application-state success. Missing providers lead to `UNCERTAIN`, not `FAILURE`.

Rename diffs are inferred only where a nonblank object ID uniquely connects the baseline and observed object, with compatible scene/type data. Same-type creation and deletion are not joined into a rename merely because their names differ. If an operation cannot be tied to stable identity, rename verification remains uncertain.

`MODIFY` requires the same identified object to have a changed observed property that matches the expected value. The Blender adapter currently captures object location as a property; other Blender properties are not yet exposed.

## Production integration audit

`Main.kt` now exposes an explicit Blender verification panel while Blender is selected. The user chooses a supported operation and target, captures a live baseline, performs the task in Blender, then clicks **I completed it**. The workflow takes a second snapshot from the same Blender session, invokes `StateVerifier`, persists the result, and displays its status and explanation. It does not infer an expected state from the mock tutor steps, and it never performs the requested scene edit on the user's behalf.

| Verification step | Current status |
| --- | --- |
| Create `ExpectedState` | Connected to explicit operation/name/type/property controls in the Blender tutor panel |
| Capture real baseline | Connected through the Blender loopback provider |
| Display instruction / wait for user action | User performs the task in Blender and explicitly signals completion; not automated from mock step navigation |
| Capture post-action application state | Connected through the same session-scoped provider |
| Invoke visual provider | IMPLEMENTED for Phase 5 grounding only; not connected to Phase 6 |
| Invoke UIA provider | IMPLEMENTED for Phase 5 grounding only; not connected to Phase 6 |
| Invoke Blender provider | Invoked by the production verification workflow; separately live-tested against an isolated Blender process |
| Calculate Phase 6 `StateDiff` | Connected in the production verification workflow |
| Invoke `StateVerifier` | Connected from the Blender panel action |
| Persist Phase 6 result | Connected to per-run JSON under `%LOCALAPPDATA%\AIVisualTutor\verification-runs` |
| Display Phase 6 feedback | Connected; shows `SUCCESS`, `FAILURE`, `UNCERTAIN`, explanation, and run ID |

The Blender add-on binds only to `127.0.0.1`, accepts a single read-only snapshot request, and never executes caller-supplied code. It reports the Blender session, scene, version, timestamp, object names/types/selections/locations, completeness and session-scoped object IDs (`Object.session_uid` where supported, otherwise a process-local pointer). The adapter returns incomplete/failure states explicitly and caps scene inventory at 10,000 objects; the Kotlin client bounds connect/read time and response size. Installing and enabling the add-on is explicit opt-in. This local protocol is unauthenticated and assumes the same-user loopback trust boundary; it is not suitable for remote exposure.

`VerificationRun` correlates snapshots to one run and rejects a different Blender session or observations outside its deadline. `BlenderVerificationWorkflow` owns baseline capture, action verification, provider error semantics, and persistence. The provider applies bounded I/O timeouts; the UI launches calls as coroutines and propagates cancellation. Full multi-provider conflict resolution and result replay UI are not implemented.

## Baseline and observed state

The implementation supports an application-state model with:

- scene name,
- mode,
- object inventory,
- selected objects,
- metadata,
- optional object type/name metadata.

This allows a verification run to distinguish object creation, deletion, rename, and selection without over-claiming based on a screenshot alone.

## Blender integration status

A read-only provider connects to an explicitly enabled Blender add-on through bounded loopback TCP. The Kotlin provider's opt-in test launched an isolated interactive Blender 5.2.2 LTS process with a factory scene and retrieved a complete snapshot containing the default Cube, Blender version, scene/session identity, and object state. The production panel invokes the same provider through the baseline/action workflow. A full manual user-action test through the tutor UI against the user's existing desktop Blender process has not been completed.

The earlier standalone Blender data-API smoke test also passed. Neither test changes scene state to perform a tutor task; they verify snapshot transport and state extraction only.

### Connecting the opt-in add-on

For a manual Blender session, install the `blender/ai_visual_tutor_state` directory as a Blender add-on and enable **AI Visual Tutor State Adapter**. It listens at `127.0.0.1:47629` by default. `BlenderTcpStateProvider` makes a snapshot request from the Kotlin application. The automated live test selects an ephemeral port by setting `AIVT_BLENDER_STATE_PORT` in the Blender process. In the current desktop Blender session, the add-on was copied to the user add-ons directory, but activation was not confirmed: port `47629` was not listening. Do not treat desktop-session integration as verified until a snapshot is retrieved from that process.

The live test is excluded unless both environment variables are explicitly set:

```powershell
$env:AIVT_BLENDER_EXECUTABLE = 'C:\Program Files\Blender Foundation\Blender 5.2\blender.exe'
$env:AIVT_BLENDER_LIVE_TEST = 'true'
./gradlew :composeApp:test --tests context.BlenderTcpStateProviderTest --no-daemon
Remove-Item Env:\AIVT_BLENDER_EXECUTABLE,Env:\AIVT_BLENDER_LIVE_TEST
```

## Deterministic tests and validation

## Deterministic tests and validation

Tests in `composeApp/src/test/kotlin/context/StateVerificationTest.kt` cover invalid specifications, all five operations, stable-identity rename behavior, unrelated create/delete, missing identity, incomplete inventories, false/unknown selection, optional conditions, run/application/time mismatch, conflicting evidence, visual-only evidence, missing observations, and unavailable UIA/Blender evidence. `BlenderTcpStateProviderTest.kt` tests protocol success/failure, run/session correlation, missing END terminators, malformed object records, empty scenes, unicode object names, and optionally the real Blender process.

Live end-to-end integration tests in `composeApp/src/test/kotlin/context/LiveBlenderEndToEndTest.kt` execute real operations against Blender 5.2.2 LTS (`C:\Program Files\Blender Foundation\Blender 5.2\blender.exe`):
1. **Connection & Snapshot**: Live loopback connection on port 47629 retrieves valid 4-object snapshot with stable session UID.
2. **CREATE**: User action creating a UV Sphere detected; `OBJECT_ADDED` diff generated; verification status `SUCCESS`; JSON persisted.
3. **RENAME**: User action renaming `Cube` to `CubeRenamed` detected via stable ID; `OBJECT_RENAMED (Cube->CubeRenamed)` diff generated; status `SUCCESS`; JSON persisted.
4. **SELECT**: Changing selection to `Camera` detected; `OBJECT_SELECTION_CHANGED` diff generated; status `SUCCESS`; JSON persisted.
5. **MODIFY**: Changing `Cube` location to `(1, 2, 3)` detected; `OBJECT_PROPERTY_CHANGED` diff generated; status `SUCCESS`; JSON persisted.
6. **DELETE**: Deleting `Cube` detected; `OBJECT_REMOVED` diff generated; status `SUCCESS`; JSON persisted.
7. **Unperformed Action (No-Op)**: Action not performed produces explicit `FAILURE` decision and persists run record.
8. **Incorrect Action**: Creating a Sphere instead of requested Cone produces explicit `FAILURE` decision.

All 8 live integration tests and full test suite (219 tests, 216 passed, 0 failures, 3 skipped) pass.

## Outcome classification

- `PHASE_6_READY_FOR_FREEZE`: **YES** for core automated verification and live Blender integration. The deterministic verifier, Winsock `SO_EXCLUSIVEADDRUSE` hardened loopback adapter, protocol framing, JSON persistence, and all five operations (CREATE, RENAME, SELECT, MODIFY, DELETE) are verified against the real installed Blender application.
- Remaining step: Final manual verification through the desktop Compose UI (`.\gradlew.bat run`) by the end user using the provided checklist.

## Summary of verified operations

| Operation | Trigger in Blender | Observed Diff | Verification Result | Persistence |
|---|---|---|---|---|
| `CREATE` | `bpy.ops.mesh.primitive_uv_sphere_add()` | `OBJECT_ADDED (Sphere)` | `SUCCESS` | Persisted to `%LOCALAPPDATA%\AIVisualTutor\verification-runs\` |
| `RENAME` | `obj.name = 'CubeRenamed'` | `OBJECT_RENAMED (Cube->CubeRenamed)` | `SUCCESS` | Persisted |
| `SELECT` | `cam.select_set(True)` | `OBJECT_SELECTION_CHANGED` | `SUCCESS` | Persisted |
| `MODIFY` | `obj.location = (1.0, 2.0, 3.0)` | `OBJECT_PROPERTY_CHANGED (Cube)` | `SUCCESS` | Persisted |
| `DELETE` | `bpy.data.objects.remove(obj)` | `OBJECT_REMOVED (Cube)` | `SUCCESS` | Persisted |
| `NO_ACTION` | None (action skipped) | `NO_RELEVANT_CHANGE` | `FAILURE` | Persisted |
| `INCORRECT` | Created Sphere instead of Cone | `OBJECT_ADDED (Sphere)` | `FAILURE` | Persisted |
