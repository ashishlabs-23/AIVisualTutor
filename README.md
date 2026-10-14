# AI Visual Tutor

AI Visual Tutor is an intelligent Windows desktop application built with Kotlin, Compose Multiplatform Desktop, and a .NET capture bridge. It combines guided tutorial workflows, transparent click-through screen overlays, closed-loop application state verification, an evidence-adaptive pedagogical fading engine, dynamic target positioning, and an interactive conversational AI framework.

---

## Key Capabilities

* **Unified Tutorial & Real-Time Assistant Interface:**
  * **Header & Workflow Progress:** Displays the active tutorial (e.g. *Blender Tutorial: Create a Sphere*), difficulty badge (*🌱 Beginner*), and multi-step progress bar.
  * **Action Preview Card:** Visual step illustration, action instructions, and direct step navigation controls.
  * **Automated Action Verification Card:** Real-time closed-loop tracking displaying verification status (*"Waiting for your action... The tutor will automatically verify once action is completed"*).
  * **"Need Help?" Scaffolding:** Immediate deterministic recovery via **`[🆘 I'm Stuck on this Step]`** and **`[💡 Give me a Hint]`**.
  * **Interactive Chat & Pedagogical Explanation Tabs:** Chat directly with the tutor or view in-depth conceptual hints, reflection questions, and adaptive guidance rationale.
  * **Current Progress Checklist:** Visual task progression checklist tracking completed steps, current active task, and remaining goals.

* **Conversational AI Framework (Groq & Gemini):**
  * **Groq Cloud Integration:** Powered by ultra-fast `openai/gpt-oss-120b` for deep instruction reasoning and `qwen/qwen3.8-27b` for multimodal vision queries, executed asynchronously over `Dispatchers.IO`.
  * **Automatic Secret Discovery:** Seamlessly resolves `GROQ_API_KEY` from `.env`, process environment variables, or in-memory settings without false "missing key" warnings.
  * **Accurate Status Indicators:** Unambiguous `● Groq Ultra-Fast AI (Active)` status with integrated key management.
  * **Deterministic State Mismatch Diagnosis:** When clicking "I'm Stuck", the tutor immediately runs `StateMismatchDiagnosticEngine` to identify discrepancies (e.g., Object Mode vs Edit Mode, active object selection) and provides deterministic recovery guidance even when offline.

* **Layered Dynamic Target Positioning & Visual Glow Overlay:**
  * **Multi-Layer Resolution:** Resolves target coordinates via **UI Automation (UIA) $\rightarrow$ Spatial OCR $\rightarrow$ Multimodal Visual Grounding $\rightarrow$ Window Bounds Validation $\rightarrow$ Graceful Abstention**.
  * **Physical Screen Illumination:** The **`[✨ Show Glow]`** button and tutor guidance directives automatically illuminate glowing highlight rectangles and callout arrows over physical controls on the learner's display.
  * **Transparent Click-Through:** Layered window composites graphics over external applications without intercepting mouse clicks or stealing window focus.

* **Adaptive Pedagogical Engine (Phase 7):** Dynamically adjusts guidance specificity using pedagogical fading:
  * **Stage 1 (Direct Guidance):** Explicit control naming, exact button location, and visual overlay bounding box.
  * **Stage 2 (Conceptual Hint):** High-level task rationale and interface region; withholds low-level clicks to encourage independent recall.
  * **Stage 3 (Reflection Question):** Socratic conceptual prompt with interactive answer input, "Submit Answer" evaluation, and "I'm Stuck" scaffolding step-down.
  * **Scaffolding Recovery:** Automatically steps down to stronger guidance when the learner struggles, while isolating competence across skill domains.

* **Application Adapters & Deep Blender Integration (Phase 8):**
  * Deep application state inspection over loopback HTTP/REST (`127.0.0.1:47629`).
  * Real-time 3D viewport transform tracking (`G`, `R`, `S` modal operations) via a lightweight timer and `/api/v1/version` event counter.
  * Authoritative multi-modal evidence reconciliation: ground-truth application facts take precedence over visual/OCR inferences, and stale or out-of-order snapshots are rejected.
  * Continuous background auto-verification tracking learner progress in real time.

* **Universal Screen Capture Bridge:**
  * High-performance .NET 10 Windows Graphics Capture (WGC) bridge and frozen-snapshot multi-monitor region selector (`Ctrl+Alt+F12` and `Ctrl+Shift+Space`).

---

## System Architecture

```text
+-----------------------------------------------------------------------------------------+
|                                    AIVisualTutor                                        |
|                                                                                         |
|  +---------------------------+       +------------------------+                         |
|  | Windows UI Automation     |       | Visual Context / OCR   |                         |
|  | PerceptionEngine (UIA)    |       | Tesseract & Screenshot |                         |
|  +-------------+-------------+       +-----------+------------+                         |
|                |                                 |                                      |
|                | [SEMANTIC_UI_AUTOMATION]        | [OCR_TEXT / VISUAL_PREDICTION]       |
|                v                                 v                                      |
|       +--------------------------------------------------------+                        |
|       |               EvidenceReconciler                       |                        |
|       |  - Enforces Ground-Truth Precedence                    |                        |
|       |  - Reconciles Mode & Selection Conflicts               |                        |
|       |  - Rejects Stale / Out-of-Order Snapshot Versions      |                        |
|       +---------------------------+----------------------------+                        |
|                                   ^                                                     |
|                                   | [DIRECT_APPLICATION_STATE]                         |
|                                   |                                                     |
|                     +-------------+---------------------+                               |
|                     |     BlenderApplicationAdapter     |                               |
|                     |  - REST API (/api/v1/snapshot)    |                               |
|                     |  - Fast Change (/api/v1/version)  |                               |
|                     +-------------+---------------------+                               |
+-----------------------------------|-----------------------------------------------------+
                                    | Loopback HTTP (127.0.0.1:47629)
                                    v
+-----------------------------------------------------------------------------------------+
|                               Blender 5.2.2 LTS                                         |
|                                                                                         |
|  +-----------------------------------------------------------------------------------+  |
|  | AI Visual Tutor State Add-on (ai_visual_tutor_state v0.2.0)                       |  |
|  | - Main-thread inspection via bpy.app.timers                                       |  |
|  | - Viewport transform timer capturing interactive G/R/S manipulations             |  |
|  | - Stable object session UIDs and versioned state snapshots                        |  |
|  +-----------------------------------------------------------------------------------+  |
+-----------------------------------------------------------------------------------------+
                                    |
                                    v
       +--------------------------------------------------------+
       |           Adaptive Pedagogical Engine (Phase 7)        |
       |  - Pedagogical Fading (Direct -> Hint -> Question)     |
       |  - Scaffolding Recovery on Consecutive Failure         |
       |  - Closed-Loop Action Verification Feedback Loop       |
       +--------------------------------------------------------+
                                    |
                                    v
       +--------------------------------------------------------+
       |         Conversational AI & Overlay Controller         |
       |  - Smart Chatbot Provider (Groq / Gemini / Offline)    |
       |  - Dynamic Target Positioning (UIA -> OCR -> Ground)   |
       |  - Interactive Glow Overlay & Continuous Auto-Verify   |
       +--------------------------------------------------------+
```

---

## Getting Started

### Prerequisites

* **Operating System:** Windows 10 (build 19041+) or Windows 11 (x64)
* **Java Development Kit:** JDK 17 (Microsoft Build of OpenJDK 17 or Eclipse Adoptium Temurin 17 recommended)
* **.NET Runtime:** .NET 10 SDK (for the Windows Graphics Capture bridge)
* **Blender:** Blender 4.0+ (Blender 5.2.2 LTS verified)

### Blender API Connection & Integration Setup

The AI Visual Tutor connects directly to Blender via an embedded, non-blocking state adapter add-on over local loopback (`127.0.0.1:47629`).

#### 1. Add-on Location & Installation
The add-on package is located at `blender/ai_visual_tutor_state/`. To install it in Blender 5.2 (or 4.x):
```powershell
$blenderVersion = "5.2"  # or your installed version (e.g. 4.2, 4.0)
$dest = "$env:APPDATA\Blender Foundation\Blender\$blenderVersion\scripts\addons\ai_visual_tutor_state"
New-Item -ItemType Directory -Force -Path $dest
Copy-Item -Path "blender\ai_visual_tutor_state\*" -Destination $dest -Recurse -Force
```

#### 2. Enable the Add-on in Blender
- **Option A (GUI)**: In Blender, go to **Edit** > **Preferences** > **Add-ons** (or **Legacy Add-ons** in 4.2+), search for **AI Visual Tutor State Adapter**, and enable the checkbox.
- **Option B (Python Console)**: In Blender's Python console workspace, run:
  ```python
  import addon_utils; addon_utils.enable("ai_visual_tutor_state", default_set=True)
  import bpy; bpy.ops.wm.save_userpref()
  ```

#### 3. Communication Bridge Protocols
Once enabled, Blender listens on `127.0.0.1:47629` using `SO_REUSEADDR`:
* **HTTP REST API (v1 Schema)**:
  - `GET /api/v1/health`: Session status, version, and active scene.
  - `GET /api/v1/snapshot`: Complete JSON scene graph snapshot (objects, transforms, active mode, active object, selection).
  - `GET /api/v1/version`: Lightweight sequence tracking (`snapshotVersion`, `eventCounter`, `activeMode`, `selectedCount`).
  - `GET /api/v1/close`: Graceful session disconnection.
* **Raw TCP Streaming**:
  - `AIVT_STATE_V1` TSV snapshot streaming for low-latency Kotlin socket observations.
* **Real-time Event Tracking**:
  - Viewport transform timer polling interactive transformations (`G`, `R`, `S`) every 0.1s.
  - Dependency graph hooks (`depsgraph_update_post` and `load_post`) for scene graph mutations.

#### 4. Automatic Connection & Avatar Feedback
When both Blender and the tutor desktop application are running:
* Telemetry queries `127.0.0.1:47629` every 2.5 seconds.
* The header status badge indicates `● Blender connected (port 47629)` in green.
* The robot avatar wakes up from `OFFLINE` (sleeping) to `IDLE` (warm glowing amber eyes) with target-aware gaze and pointing.

### Installation & Execution

1. **Clone the repository:**
   ```powershell
   git clone https://github.com/ashishlabs-23/AIVisualTutor.git
   cd AIVisualTutor
   ```

2. **Configure API Keys (Optional):**
   Copy `.env.example` to `.env` and set your preferred provider key:
   ```env
   # Groq Cloud API Key (Recommended for ultra-fast response)
   GROQ_API_KEY=your_groq_api_key_here

   # Google Gemini API Key
   GEMINI_API_KEY=your_gemini_api_key_here
   ```
   *Note: If no keys are provided, the tutor runs in local offline mode using built-in deterministic pedagogical rules. No API key is required for Blender.*

3. **Run the Application:**
   ```powershell
   $env:JAVA_HOME = "C:\Path\To\JDK17"
   .\gradlew.bat :composeApp:run
   ```

4. **Run the Test Suite:**
   ```powershell
   .\gradlew.bat test
   ```
   *Validated Baseline: 346 tests executed, 343 passed, 0 failures, 3 skipped (100% pass rate).*

---

## Global Keyboard Shortcuts

| Shortcut | Action |
| --- | --- |
| `Ctrl+Alt+F12` | Capture previous external application window |
| `Ctrl+Shift+Space` | Launch universal fullscreen region selector |
| `Esc` | Cancel active region selection and restore focus |

---

## Project Documentation & Phase Roadmap

For each phase of development, refer to its single comprehensive documentation file in `docs/`:

* [docs/PHASE_0.md](docs/PHASE_0.md) — Research Questions, Empirical Methodology, and Evaluation Framework
* [docs/PHASE_1.md](docs/PHASE_1.md) — Core Tutor Panel, Floating Dock, and Transparent Click-Through Overlay
* [docs/PHASE_2.md](docs/PHASE_2.md) — Windows Graphics Capture Bridge and Frozen Snapshot Selection
* [docs/PHASE_3.md](docs/PHASE_3.md) — Universal Visual Context Acquisition and Coordinate Normalization
* [docs/PHASE_4.md](docs/PHASE_4.md) — Multi-Modal Perception (UIA, Tesseract OCR) and Deterministic Evidence Evaluation
* [docs/PHASE_5.md](docs/PHASE_5.md) — Evidence-Adaptive Grounding, Mode Routing, and Research Evaluation
* [docs/PHASE_6.md](docs/PHASE_6.md) — Closed-Loop State Verification and Initial Loopback TCP Provider
* [docs/PHASE_7.md](docs/PHASE_7.md) — Adaptive Pedagogical Engine, Pedagogical Fading, and Scaffolding Recovery
* [docs/PHASE_8.md](docs/PHASE_8.md) — Application Adapters, Deep Blender Integration, Evidence Provenance, and Real-Time State Verification

Technical Remediation & Quality Audits:
* [docs/remediation/chat-and-provider-audit.md](docs/remediation/chat-and-provider-audit.md) — Provider Discovery, Groq API, and Config Audit
* [docs/remediation/stuck-and-glow-validation.md](docs/remediation/stuck-and-glow-validation.md) — State Mismatch Diagnosis & Dynamic Overlay Validation
* [docs/remediation/feature-status.md](docs/remediation/feature-status.md) — Full Architectural Feature Implementation Status Matrix

---

## Repository Structure

```text
AIVisualTutor/
├── composeApp/
│   ├── src/main/kotlin/
│   │   ├── adapter/            # Reusable ApplicationAdapter contracts, BlenderAdapter, EvidenceReconciler
│   │   ├── bridge/             # Windows Graphics Capture bridge and global hotkey hooks
│   │   ├── chat/               # Conversational AI framework (Groq, Gemini, SmartProvider, ApiKeyConfig)
│   │   ├── context/            # Perception, DynamicTargetPositioningService, OCR, VisualGrounding
│   │   ├── models/             # Application, tutor event, and session models
│   │   ├── overlay/            # Transparent Skiko click-through overlay window
│   │   ├── pedagogy/           # AdaptivePedagogicalEngine, AdaptationPolicy, LearnerStatePersistence
│   │   ├── selection/          # Multi-monitor desktop freezing and region selection
│   │   ├── tutor/              # TutorController, step navigation, and mock curriculum data
│   │   └── ui/                 # Compose Desktop panels: TutorPanel, InstructionCard, ChatbotPanel
│   └── src/test/kotlin/        # 346 comprehensive headless and live integration tests
├── blender/
│   └── ai_visual_tutor_state/  # Blender add-on exposing REST /api/v1/ endpoints and viewport tracking
├── wgc-bridge/                 # .NET 10 Windows Graphics Capture C# native bridge
└── docs/                       # Single specification & validation report for each phase
```
