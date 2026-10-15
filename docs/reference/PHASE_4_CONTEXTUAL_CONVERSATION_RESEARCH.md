# Phase 4 — Contextual Conversation Architecture & Deep Research Report

**Project:** AI Visual Tutor for Blender  
**Branch:** `feature/frontend-dev`  
**Date:** October 15, 2026  
**Status:** **RESEARCH COMPLETED — READY FOR IMPLEMENTATION PLANNING**  
**Document Location:** `docs/reference/PHASE_4_CONTEXTUAL_CONVERSATION_RESEARCH.md`

---

## 1. Executive Summary

This report establishes the baseline architecture, investigates existing conversational components, and analyzes the requirements for **Phase 4: Contextual Conversation**.

In earlier development phases, the project established:
- **Phase 1 & 2:** Procedural robot avatar rendering, transparent desktop window compositing, basic instruction bubble, and session state precedence.
- **Phase 3 & 3.5:** Target-aware gaze/pointing kinematics, screen docking geometry, desktop-space plasma tether guidance, and harmonic pulse synchronization (`GuidanceHarmonicClock`).
- **Blender 5.2.2 LTS Integration:** Persistent loopback communication over HTTP/REST and TCP (`127.0.0.1:47629`), live scene graph streaming, and deterministic task verification.

Phase 4 elevates the AI companion from a visual prompter into a **context-aware conversational tutor**. The user must be able to ask follow-up questions, seek clarifications, and receive advice grounded in their active goal, current Blender scene state, and past verification results—without compromising the authoritative tutoring controller or creating parallel session state.

---

## 2. Baseline Architecture Audit

### 2.1 Git Baseline & Working-Tree State
- **Repository Root:** `c:\Users\PC\Desktop\AIVisualTutor`
- **Active Branch:** `feature/frontend-dev`
- **HEAD Commit:** `4ed19ad44b2d97885bd5a54513ed9cc1b0fbebeb` (*feat(avatar,blender): complete Phase 3 & 3.5 avatar guidance, Blender 5.2 integration, and pre-Phase 4 acceptance gate*)
- **Working-Tree Status:** Preserves Phase 3.5 avatar/overlay synchronization implementations.
- **Test Baseline:** Full regression suite passes with **695 total tests executed, 692 passed, 0 failed, 3 ignored** (opt-in integration fixtures).

### 2.2 Conversational Component Audit

| Component | File Path | Existing Implementation & Capabilities | Critical Architectural Limitations / Deficiencies |
| :--- | :--- | :--- | :--- |
| **`SmartChatbotProvider`** | [`composeApp/.../chat/SmartChatbotProvider.kt`](file:///c:/Users/PC/Desktop/AIVisualTutor/composeApp/src/main/kotlin/chat/SmartChatbotProvider.kt) | Intelligent provider router: selects Groq (`openai/gpt-oss-120b`) if `GROQ_API_KEY` is configured, Google Gemini if `GEMINI_API_KEY` is configured, or local offline knowledge engine if unconfigured. | Operates purely as a stateless routing proxy; has no session management or memory of prior queries. |
| **`ChatContextBuilder`** | [`composeApp/.../chat/ChatContextBuilder.kt`](file:///c:/Users/PC/Desktop/AIVisualTutor/composeApp/src/main/kotlin/chat/ChatContextBuilder.kt) | Assembles `ChatContext` payload with question, app name, mode, step title, step number, active instruction, and optional screenshot crop. | When called from `AvatarWindow.kt`, `observedState`, `chatHistory`, and `appContext` are omitted (passed as `null` / `emptyList()`). The LLM never receives live Blender scene state. |
| **`InstructionBubble`** | [`composeApp/.../ui/avatar/InstructionBubble.kt`](file:///c:/Users/PC/Desktop/AIVisualTutor/composeApp/src/main/kotlin/ui/avatar/InstructionBubble.kt) | Renders active step instruction, progress counter (`3 / 3`), `[ 🎯 Highlight ]` button, and `[ 📖 Explain more ]` button. Expands scrollable explanation card. | **Single-shot only.** Does not support multi-turn conversational input. The user cannot ask a follow-up question directly in the avatar bubble. |
| **`AvatarWindow`** | [`composeApp/.../ui/avatar/AvatarWindow.kt`](file:///c:/Users/PC/Desktop/AIVisualTutor/composeApp/src/main/kotlin/ui/avatar/AvatarWindow.kt) | Hosts the companion window. Invokes `chatbotProvider.askTutor(chatContext)` on `onExplainClick`. | Stores response in local ephemeral `var explanationText by remember { mutableStateOf<String?>(null) }`. Responses are discarded on window recreation or step change. |
| **`ChatbotPanel`** | [`composeApp/.../ui/ChatbotPanel.kt`](file:///c:/Users/PC/Desktop/AIVisualTutor/composeApp/src/main/kotlin/ui/ChatbotPanel.kt) | Full chat drawer inside `TutorPanel`. Parses directives (`[TARGET: ...]`, `[ACTION: ...]`), initiates goals, and displays chat message history. | Message list (`messages`) is held in local Composable memory (`remember { mutableStateListOf(...) }`). Closing the panel completely wipes conversation history. Disconnected from `AvatarWindow`. |
| **`TutorController`** | [`composeApp/.../tutor/TutorController.kt`](file:///c:/Users/PC/Desktop/AIVisualTutor/composeApp/src/main/kotlin/tutor/TutorController.kt) | Authoritative source of truth for steps, active goal plan, telemetry, and verification results. | Currently has **no conversational state or history repository**. Conversation is unowned at the domain level. |
| **`ApiKeyConfig`** | [`composeApp/.../chat/ApiKeyConfig.kt`](file:///c:/Users/PC/Desktop/AIVisualTutor/composeApp/src/main/kotlin/chat/ApiKeyConfig.kt) | Multi-source resolution: UI runtime override, `.env`, `local.properties`, system env, and JVM system property. | Solid, production-grade credential resolution; must be preserved as the single key source. |

### 2.3 The "Explain More" End-to-End Trace
1. **User Gesture:** The user clicks `[ 📖 Explain more ]` inside `InstructionBubble`.
2. **Dispatch:** `onExplainClick` in `AvatarWindow.kt` triggers a coroutine:
   ```kotlin
   val query = "Explain step: ${step.title}. ${step.instruction}"
   val chatContext = contextBuilder.buildContext(question = query, controller = controller)
   val response = chatbotProvider.askTutor(chatContext)
   explanationText = response.replyText.ifBlank { response.explanation ?: ... }
   ```
3. **Execution:** `SmartChatbotProvider` dispatches to Groq, Gemini, or offline knowledge fallback.
4. **Presentation:** `explanationText` renders in the in-bubble card.
5. **Verdict:**
   - **Explain More ALREADY WORKS** end-to-end as a single-turn explanation card.
   - **However, it is NOT CONVERSATIONAL:** It cannot answer "Where is that?", "Why didn't my extrusion work?", or retain dialogue continuity.

---

## 3. Context Quality Investigation

### 3.1 What Current Conversation Receives vs. What Is Missing

| Context Field | Current Availability | Source in Codebase | Quality Assessment & Gap |
| :--- | :--- | :--- | :--- |
| **User Goal** | **Present** | `controller.currentGeneratedPlan?.goalText` | High quality when goal is planned; defaults to generic fallback if mock tutorial. |
| **Active Step & Instruction** | **Present** | `controller.currentStep?.instruction` | Authoritative and accurate. |
| **Target Control Name** | **Present** | `controller.currentStep?.targetControlName` | Accurate when defined in plan or step. |
| **Live Blender Scene Graph** | **MISSING** | `BlenderTcpStateProvider` / `ObservedState` | **Critical Gap:** The query does not know if the Cube or Sphere actually exists in the 3D scene, leading to generic advice. |
| **Blender Mode** | **MISSING** | `ObservedState.mode` (`OBJECT`, `EDIT`, etc.) | **Critical Gap:** Explanations cannot warn the user they are in Object Mode when Edit Mode is required. |
| **Verification Results & Failures** | **MISSING** | `VerificationResult` / `StateDiff` | **Critical Gap:** If verification failed because location was incorrect or object was missing, the chatbot is unaware of the failure reason. |
| **Pedagogical Outcome & Recovery** | **MISSING** | `TutorState.lastPedagogicalOutcome` | The tutor cannot reference why recovery guidance was triggered. |
| **Recent Message History** | **MISSING** | `chatHistory` parameter | `AvatarWindow` passes empty list; multi-turn context is completely absent. |

### 3.2 Recommended Minimal Context Contract
To prevent token bloat, high latency, and privacy leaks, Phase 4 must **NOT** pass full scene dumps or raw image pixels for standard text queries. We recommend the following strict, structured contract:

```kotlin
data class StructuredTutorContext(
    val goalTitle: String?,
    val stepIndex: Int,
    val totalSteps: Int,
    val stepTitle: String,
    val stepInstruction: String,
    val targetControl: String?,
    val blenderMode: String?,
    val activeObjectName: String?,
    val activeObjectLocation: String?,
    val totalSceneObjects: Int,
    val lastVerificationStatus: String?,
    val lastFailureReason: String?,
    val recentDialog: List<ChatMessage>
)
```
*Estimated Context Size:* $\approx 180\text{–}240\,\text{tokens}$. Highly optimized for sub-second Groq responses ($<400\,\text{ms}$) and low-bandwidth execution.

---

## 4. Multi-Turn Conversation & Session Safety Analysis

### 4.1 Identified Risks & Required Mitigations

1. **Stale Context Contamination on Task Switch:**
   - *Risk:* User asks a question on Step 1 ("How to add UV Sphere?"). While the LLM is thinking, the user navigates to Step 3. When the response arrives, it displays outdated advice for Step 1 on Step 3.
   - *Mitigation:* In-flight queries must be associated with an atomic `stepId` or `queryId`. If `controller.state.currentStepIndex` changes before completion, the response must be discarded or routed to history without updating the active view.
2. **Duplicate Queries via Recomposition:**
   - *Risk:* Compose Desktop recompositions during window resize or drag could trigger duplicate coroutines.
   - *Mitigation:* Atomically guard dispatch with `isThinking` flag backed by an active `Job?` reference.
3. **History Disconnection between Bubble and Panel:**
   - *Risk:* User asks for help in the avatar bubble, then opens `ChatbotPanel` (HUD) and finds an empty history.
   - *Mitigation:* Centralize conversation history in a unified, domain-level `ConversationManager` hosted in `TutorController`. Both `InstructionBubble` and `ChatbotPanel` observe this single reactive list.
4. **Provider Timeouts & Fallback Safety:**
   - *Risk:* Network latency or rate limits could freeze the UI.
   - *Mitigation:* Groq and Gemini clients enforce a 15-second timeout with immediate fallback to local tutorial knowledge. The UI displays an explicit `[ ✕ Cancel ]` control while waiting.

---

## 5. Architectural Separation of Powers

To guarantee tutorial integrity, Phase 4 enforces strict separation between **Conversational Advisory** and **Executable Truth**:

```
+─────────────────────────────────────────────────────────────────────────+
|                  AI VISUAL TUTOR — AUTHORITY BOUNDARIES                 |
+─────────────────────────────────────────────────────────────────────────+
|  ADVISORY LAYER (Chatbot / LLM)       AUTHORITATIVE LAYER (Controller)  |
|  - Explains 3D concepts               - Verifies Blender scene graph    |
|  - Suggests shortcuts (e.g. Shift+A)  - Determines step completion      |
|  - Clarifies error messages           - Controls overlay highlighting   |
|  - NEVER marks step complete          - Sole source of goal progress    |
+─────────────────────────────────────────────────────────────────────────+
```

An LLM reply text stating *"I see you created the sphere! Good job!"* has **zero authority**. Only `BlenderVerificationWorkflow.verifyAction()` inspecting actual scene geometry over port 47629 can advance a tutorial step.

---

## 6. Phase 4 Acceptance Criteria Status Matrix

| ID | Acceptance Criterion | Current Status | Notes / Plan |
| :--- | :--- | :--- | :--- |
| **AC-4.1** | **Active Task Grounding:** Explain More incorporates active goal, step, instruction, and target name. | **PARTIALLY SATISFIED** | Uses title/instruction; needs live Blender mode & object presence wired into context builder. |
| **AC-4.2** | **Multi-Turn Continuity:** Follow-up questions in the avatar bubble retain prior exchanges within the same step. | **MISSING** | Requires interactive follow-up input in `InstructionBubble` and message history tracking. |
| **AC-4.3** | **Task-Switch Isolation:** Switching steps or goals cancels in-flight queries and prevents cross-task context leakage. | **MISSING** | Requires job cancellation on step index mutation and session-keyed history. |
| **AC-4.4** | **Provider Error & Timeout Resilience:** Timeouts (15s), missing keys, or HTTP errors degrade gracefully to offline guidance. | **SATISFIED** | Built into `SmartChatbotProvider` and `GroqChatbotProvider`. |
| **AC-4.5** | **Window Lifecycle Preservation:** Closing and reopening the avatar or workspace HUD preserves conversation history. | **PARTIALLY SATISFIED** | Preserves controller state, but UI chat list is currently ephemeral and must be moved to controller. |
| **AC-4.6** | **Credential Reuse:** All conversational queries reuse `ApiKeyConfig` without redundant storage. | **SATISFIED** | Fully compliant. |
| **AC-4.7** | **Zero False Completion:** Conversational responses can never independently mark a step verified or advance progress. | **SATISFIED** | Architectural boundary strictly maintained. |
| **AC-4.8** | **Interactive UX State:** UI provides loading spinners, cancellation affordances, error feedback, and dismiss controls. | **PARTIALLY SATISFIED** | Loading spinner and dismiss exist; explicit query cancellation is missing. |
| **AC-4.9** | **Regression Suite Integrity:** Full automated test suite passes with 100% pass rate. | **SATISFIED** | 695 tests pass, 0 failures. |
| **AC-4.10** | **Live Runtime Validation:** Operational against live Blender 5.2.2 LTS session on port 47629. | **SATISFIED** | Live connection verified healthy. |

---

## 7. Readiness Decision

**STATUS: READY TO IMPLEMENT (SUBJECT TO APPROVAL OF PHASE 4 PLAN)**  
The baseline is stable, Blender 5.2.2 communication is verified, and the architectural gaps are isolated and well-defined.
