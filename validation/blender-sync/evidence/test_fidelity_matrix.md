# Test Fidelity Matrix

This matrix categorizes the regression testing suite by fidelity level, distinguishing unit tests and simulated integration tests from live desktop Blender integration and manual UI validation.

| Test Identifier | Test Name | Tested Invariant | Fidelity Level | Execution Status |
|---|---|---|---|---|
| **TEST 1** | `test1_sphereAlreadyExists_verifiesSuccessfullyAndAdvances` | Pre-existing/created sphere in scene successfully verified | SIMULATED_INTEGRATION | PASSED |
| **TEST 2** | `test2_sphereCreatedAfterInitialEmptyObservation_verifiesFreshState` | Sphere added after initial empty state is evaluated with fresh evidence | SIMULATED_INTEGRATION | PASSED |
| **TEST 3** | `test3_wrongMeshObject_cannotClaimUvSphereConfirmed` | Non-sphere mesh (Cube, Cylinder) rejected for UV sphere verification | SIMULATED_INTEGRATION | PASSED |
| **TEST 4** | `test4_providerFailure_doesNotMistakeForEmptySceneAndAllowsRetry` | Provider socket/connection failure treated as unavailable telemetry, allowing retry | SIMULATED_INTEGRATION | PASSED |
| **TEST 5** | `test5_prerequisiteStateConsistency_uiAndAuthoritativeTrackerAgree` | UI checkmarks and `GoalProgressTracker.canExecuteStep` agreement | COMPONENT | PASSED |
| **TEST 6** | `test6_staleAsynchronousResponse_cannotOverwriteNewerState` | Stale snapshot versions and out-of-order responses dropped | SIMULATED_INTEGRATION | PASSED |
| **TEST 7** | `test7_retryLimitReached_recoversWhenBlenderStateChanges` | Retry exhaustion recovers cleanly when user corrects scene state | SIMULATED_INTEGRATION | PASSED |
| **TEST 8** | `test8_uiSynchronization_stateUpdatesWithoutRestart` | Verification success immediately updates UI state and progression | COMPONENT | PASSED |
| **TEST 9** | `test9_multipleSimilarObjects_verifierDoesNotConfuseUnrelatedNames` | Similar names (`Atmosphere`, `Sphere_backup`) rejected; `Sphere.001` accepted | UNIT | PASSED |
| **TEST 10** | `test10_planOrTaskIdentityChange_delayedResultCannotMarkNewTaskComplete` | Plan/session identity changes discard stale verification results | SIMULATED_INTEGRATION | PASSED |
| **LIVE 1** | `testLiveBlenderConnectionAndSnapshot` | Direct socket handshake with live Blender process | LIVE_INTEGRATION | NOT RUN (Interactive env unavailable) |
| **LIVE 2** | `testPhase5SuccessObjectCreationInLiveBlender` | Live primitive creation and TCP verification | LIVE_INTEGRATION | NOT RUN (Interactive env unavailable) |
| **MANUAL** | Desktop UI End-to-End Walkthrough | Human inspection of floating overlay over live Blender | MANUAL_UI | NOT RUN (Requires human tester) |
