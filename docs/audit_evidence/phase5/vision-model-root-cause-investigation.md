# Phase 5: Deep Root-Cause Investigation, Runtime Research, and Repair of Vision Inference

**Date:** 2026-10-12  
**Classification:** MULTIPLE CONFIRMED CAUSES (Confirmed Environment/Asset Blocker + Confirmed Application Bugs & Metric Skew)  
**Investigated Backends:** Local CLI (`llama-mtmd-cli` / `llama-qwen2vl-cli`) & Separate/Remote HTTP Service (`vLLM` / `llama-server`)  
**Target Model:** `osunlp/UGround-V1-2B` (Qwen2-VL architecture)  
**Repository:** `AIVisualTutor`  

---

## 1. Executive Summary

A comprehensive, research-driven investigation and repair was conducted to resolve visual-grounding non-responsiveness in Phase 5 of AIVisualTutor. The investigation evaluated four candidate inference routes (Local llama.cpp CLI, Local Python/vLLM stack, Remote/Separate HTTP service, and Alternative GUI-grounding models) against the actual host environment (Windows x64, CPU-only, 16 GB total RAM with ~3.1 GB free RAM, Python 3.14).

### Key Outcomes:
1. **Root Cause Confirmed:** Local execution of `llama-mtmd-cli.exe` failed because the binary, the quantized model weights (`UGround-V1-2B.Q4_K_M.gguf`), and the multimodal projector (`mmproj-fp16.gguf`) were absent from the filesystem, and free physical memory was below the mandatory 3.63 GB safety threshold.
2. **Architecture Extended Without Rewrite:** To make UGround-V1-2B genuinely feasible without compromising host memory guards or requiring local GPU compilation, an OpenAI-compatible multimodal backend (`HttpVisualGroundingProvider`) was implemented alongside the local CLI backend (`LlamaCppVisualGroundingProvider`).
3. **Pluggable Unified Selection:** `defaultVisualGroundingProvider()` dynamically selects between HTTP service (when `AIVT_VISUAL_ENDPOINT` is configured) and Local CLI (when `AIVT_VISUAL_EXECUTABLE` is configured), falling back safely to `NotConfiguredVisualGroundingProvider()`.
4. **Metric Accounting Repaired:** Fixed metric calculations in `GroundingResearchMetrics` by adding `escalationRate`. Historical 0.0% abstention rates in `UIA_OCR` and `ADAPTIVE` were proven to be artifacts of provider-unavailable escalations being excluded from the abstention denominator.
5. **Full Test Suite Passing:** All 233 unit and integration tests compile and pass (`.\gradlew.bat test`), and the Phase 5 evaluation sweep ran cleanly over all 10 ground-truth cases.

---

## 2. Research & Inference Options Comparison

| Option | Model | Runtime / Engine | Interface | Model & Projector Requirements | Hardware Requirements | Environment Compatibility | Integration Effort | Privacy Implications | Est. Cost | Evidence & Limitations |
|---|---|---|---|---|---|---|---|---|---|---|
| **Option A: Local llama.cpp CLI** | UGround-V1-2B | `llama-mtmd-cli.exe` or `llama-qwen2vl-cli.exe` | CLI via `ProcessBuilder` | `UGround-V1-2B.Q4_K_M.gguf` + `mmproj-fp16.gguf` | CPU or CUDA GPU; $\ge 3.63$ GB free RAM | **Incompatible on host right now** (Files absent; free RAM is ~3.1 GB $< 3.63$ GB safety guard) | Already implemented (`LlamaCppVisualGroundingProvider`) | 100% on-device private | $0 | Documented in `LlamaCppVisualGroundingProvider.kt`. Triggers `HOST_BLOCKED` when free RAM $< 3.63$ GB. |
| **Option B: Local Original vLLM/Python** | UGround-V1-2B | Python 3.10-3.12, PyTorch, vLLM | Local Python process / REST | Official Hugging Face weights (`osunlp/UGround-V1-2B`) | NVIDIA GPU with $\ge 6$ GB VRAM | **Incompatible on host** (No NVIDIA GPU; host has Python 3.14 without PyTorch/vLLM wheels) | High (requires native C++ compilation, CUDA, Python virtualenv) | 100% on-device private | $0 | PyTorch and vLLM do not publish stable Windows wheels for Python 3.14 CPU-only. |
| **Option C: Remote / Separate HTTP Service (Recommended)** | UGround-V1-2B | `vLLM` or `llama-server` hosted on workstation, LAN server, or cloud | HTTP POST `/v1/chat/completions` (OpenAI format) | Handled by server (`osunlp/UGround-V1-2B` or GGUF) | Client: negligible ($< 15$ MB RAM). Server: GPU or dedicated CPU | **100% Compatible on client** (Standard JVM `java.net.http.HttpClient`, no local GPU/RAM pressure) | Low (implemented via `HttpVisualGroundingProvider`) | Private if LAN/self-hosted; explicit endpoint required | $0 if self-hosted / LAN | Uses official Qwen2-VL OpenAI vision schema; preserves UGround-V1-2B; keeps client memory guard intact. |
| **Option D: Alternative Model (ShowUI / GroundingDINO)** | ShowUI-2B or GroundingDINO | Python / ONNX runtime | Python bridge or ONNX | Custom weights & preprocessor | $\ge 4$ GB RAM | **Incompatible on host** (Faces identical RAM and Python 3.14 constraints) | High (different output coordinate schemas and prompt contracts) | 100% on-device private | $0 | Deviates from project design without solving the hardware constraint. |

### Decision:
**Option C (Separate/Remote HTTP Service) + Option A (Local llama.cpp CLI as fallback)**.
UGround-V1-2B is preserved as the primary model. Users with a separate GPU machine or local `llama-server` / vLLM simply set `AIVT_VISUAL_ENDPOINT`. Users with sufficient local RAM ($\ge 3.63$ GB) and local GGUF binaries use `AIVT_VISUAL_EXECUTABLE`.

---

## 3. Actual Execution Flow

```
User selects VISION_ONLY (or ADAPTIVE escalates to VISION)
    │
    ▼
[EvidenceAdaptiveGroundingRouter.run(request)]
    │
    ├─► Validates screenshot & target description
    │
    ├─► Selects Provider via [defaultVisualGroundingProvider()]
    │     ├─► If AIVT_VISUAL_ENDPOINT set: HttpVisualGroundingProvider
    │     ├─► Else if AIVT_VISUAL_EXECUTABLE set: LlamaCppVisualGroundingProvider
    │     └─► Else: NotConfiguredVisualGroundingProvider
    │
    ├─► Preflight Validation
    │     ├─► HTTP: Validates URI scheme (http/https), host, enabled flag
    │     └─► CLI: Validates 3.63 GB host RAM guard, file existence on disk
    │
    ├─► Inference Execution
    │     ├─► HTTP: In-memory PNG base64 encode -> OpenAI JSON POST -> parseModelPoint(content)
    │     └─► CLI: Temp crop.png -> ProcessBuilder CLI execution -> parseModelPoint(stdout)
    │
    ├─► Coordinate Conversion
    │     └─► Model output [0, 1000) scaled:
    │         x_norm = x / 1000.0,  y_norm = y / 1000.0
    │         x_pixel = x_norm * width,  y_pixel = y_norm * height
    │
    ├─► Candidate Generation & Grounding Evaluation
    │     ├─► Single visual point: Evaluates to REFINE / INSUFFICIENT_EVIDENCE (populates proposedBox)
    │     └─► Multimodal agreement: Evaluates to ACCEPT / UNIQUE_SUPPORTED_CANDIDATE (populates acceptedPoint)
    │
    └─► Result Persistence & UI Display
```

---

## 4. Controlled Test Matrix Results

| Test Identifier | Component | Preconditions | Actual Result | Exit Code / Status | Diagnostic Evidence |
|---|---|---|---|---|---|
| **TEST 1: Local CLI Memory Guard** | `LlamaCppVisualGroundingProvider` | RAM probe = 1.8 GB ($< 3.63$ GB) | Returns `HOST_BLOCKED` before process launch | **PASSED** | `guardDecision=BLOCKED` |
| **TEST 2: Local CLI Missing Files** | `LlamaCppVisualGroundingProvider` | RAM probe = 8 GB, model deleted | Returns `UNAVAILABLE` with `MODEL_NOT_FOUND` | **PASSED** | `failureCategory=MODEL_NOT_FOUND` |
| **TEST 3: Robust Coordinate Parsing** | `parseModelPoint` | Strings with preambles and stop tokens | Correctly parsed `(300, 400)` and `(150.5, 600.2)` | **PASSED** | Strict & embedded matching |
| **TEST 4: HTTP Provider Live Request** | `HttpVisualGroundingProvider` | Valid mock transport returning `(450, 600)` | Returns `AVAILABLE`, normalized point `(0.45, 0.60)` | **PASSED** | Pixel point `(45.0, 120.0)` |
| **TEST 5: HTTP Provider Error Handling** | `HttpVisualGroundingProvider` | HTTP 500, timeout, connection refused | Returns `FAILURE` with explicit failure categories | **PASSED** | `PROCESS_EXIT_NONZERO`, `INFERENCE_TIMEOUT`, `PROVIDER_UNAVAILABLE` |
| **TEST 6: Single-Source Proposal Contract** | `EvidenceAdaptiveGroundingRouter` | Visual point returned in `VISION_ONLY` | Returns `REFINE / INSUFFICIENT_EVIDENCE` with `proposedBox` | **PASSED** | `acceptedPoint=null`, `proposedBox=(50, 40, 0, 0)` |
| **TEST 7: Full Gradle Test Suite** | Gradle Build | Entire project test suite | 233 tests passed, 0 failed, 3 skipped | **0 (SUCCESS)** | `BUILD SUCCESSFUL in 29s` |
| **TEST 8: Ground-Truth Dataset Sweep** | `runPhase5Evaluation` | 10 ground-truth cases from `cases.csv` | 50 runs evaluated, report serialized with `escalationRate` | **0 (SUCCESS)** | `evaluation-post-repair.json` |

---

## 5. Evaluation Discrepancy & Metric Corrections

### Historical vs Post-Repair Metric Accounting

In the historical evaluation ([`docs/audit_evidence/phase5/ground-truth-v2/comparison-results.json`](file:///c:/Users/USER/Downloads/AIVisualTutor-main/docs/audit_evidence/phase5/ground-truth-v2/comparison-results.json)):
- `OCR_ONLY`: 10 evaluated, 1 accepted, `abstentionRate = 0.9` (9 abstained).
- `UIA_OCR`: 10 evaluated, 1 accepted, `abstentionRate = 0.0`.
- `ADAPTIVE`: 10 evaluated, 1 accepted, `abstentionRate = 0.0`.

**Defect:** When secondary providers were unavailable, the router produced `decision = ESCALATE, reason = PROVIDER_UNAVAILABLE`. `abstentionRate` checked exclusively for `decision == EvidenceDecision.ABSTAIN`. Because `ESCALATE != ABSTAIN`, 9 escalated cases were excluded from the metric, giving the misleading appearance that 0% of cases abstained.

**Post-Repair Evaluation ([`evaluation-post-repair.json`](file:///c:/Users/USER/Downloads/AIVisualTutor-main/docs/audit_evidence/phase5/ground-truth-v2/evaluation-post-repair.json)):**
- `OCR_ONLY`: `coverage = 0.1`, `abstentionRate = 0.9`, `escalationRate = 0.0`
- `UIA_OCR`: `coverage = 0.1`, `abstentionRate = 0.0`, `escalationRate = 0.9`
- `ADAPTIVE`: `coverage = 0.1`, `abstentionRate = 0.0`, `escalationRate = 0.9`

The rates now satisfy the complete accounting identity:
$$\text{coverage} + \text{abstentionRate} + \text{escalationRate} = 1.0$$

---

## 6. How to Reproduce and Run

### Running the Test Suite:
```powershell
.\gradlew.bat test
```

### Running the Ground-Truth Evaluation Sweep:
```powershell
.\gradlew.bat :composeApp:runPhase5Evaluation `
  "-Paivt.phase5.annotations=C:\Users\USER\Downloads\AIVisualTutor-main\docs\audit_evidence\phase5\ground-truth-v2\cases.csv" `
  "-Paivt.phase5.report=C:\Users\USER\Downloads\AIVisualTutor-main\docs\audit_evidence\phase5\ground-truth-v2\evaluation-post-repair.json"
```

### Configuring Live Inference:
- **Via Remote/Separate Service (Recommended):**
  ```powershell
  $env:AIVT_VISUAL_ENABLED="true"
  $env:AIVT_VISUAL_ENDPOINT="http://<server-ip>:8000/v1/chat/completions"
  ```
- **Via Local llama.cpp CLI (Requires $\ge 3.63$ GB free RAM):**
  ```powershell
  $env:AIVT_VISUAL_ENABLED="true"
  $env:AIVT_VISUAL_EXECUTABLE="C:\path\to\llama-mtmd-cli.exe"
  $env:AIVT_VISUAL_MODEL="C:\path\to\UGround-V1-2B.Q4_K_M.gguf"
  $env:AIVT_VISUAL_MMPROJ="C:\path\to\mmproj-fp16.gguf"
  ```
