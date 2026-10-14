# AI Visual Tutor — Avatar States & PRD Design Specification

Reference Image: **AI Visual Tutor — Avatar States & PRD** (Supplied in Phase 3.5).

---

## 1. Official Color Palette (Blender Compatible)

| Token | Hex Code | Purpose |
| :--- | :--- | :--- |
| **Primary** | `#F5792A` | Signature Blender warm orange, primary button borders, pointer beam, glowing ear accents |
| **Accent** | `#FFA757` | Soft orange eye glow, secondary emissives, active hover highlights |
| **Dark UI** | `#1E2127` | Deep graphite background for floating instruction bubble, dock, and panels |
| **Surface** | `#282F38` | Elevated card surface for chat bubbles, secondary buttons, and tutorial list items |
| **Text** | `#F1F5F9` | Clean high-contrast white text for primary instructions and headings |
| **Text Muted**| `#8A94A6` | Secondary labels, step counts (`1 / 4`), drag hints |
| **Success** | `#22C55E` | Verification confirmation, cheerful arcs, success tags |
| **Error** | `#EF4444` | Verification failure, disconnected status, alert badge |
| **Info** | `#38BDF8` | Cyan blue guidance badges, thinking indicator |

---

## 2. Robot Avatar Anatomy

1. **Chassis & Head:**
   - Spherical floating capsule in pearl-white / warm ivory (`#FFFFFF` to `#F1F5F9`).
   - Smooth 3D-shaded ambient gradient with subtle lower graphite rim (`#334155`).
   - Floating white collar/base with orange under-glow reflection.
2. **Visor & Faceplate:**
   - Glossy obsidian oval visor (`#0E1117`).
   - Upper glass curved specular highlight arc (`Color.White.copy(alpha = 0.18f)`).
3. **Earcups / Headphones:**
   - Symmetrical cylindrical ear caps on left and right sides.
   - Glowing warm orange concentric accent ring (`#F5792A`).
4. **Floating Hands / Paws:**
   - Two floating pearl-white rounded hands / mittens that express state:
     - **Idle:** Resting gently beside/below the head.
     - **Thinking:** One hand raised touching the chin/cheek thoughtfully; 3 floating glowing amber dots `...` above head.
     - **Explaining:** One hand raised open, palm up in friendly conversational gesture.
     - **Pointing:** Hand extended toward Blender target, index pointing, emitting a glowing orange beam / line with an amber emitter dot at the fingertip.
     - **Success:** Both hands raised high in celebratory cheer / double thumbs-up `\ (^ ^) /`.
     - **Error / Retry:** Both hands drooping low with floating red circular exclamation badge `(!)`.
     - **Searching:** Hand holding a cute glowing circular magnifying glass looking for UI.
     - **User Stuck:** Floating warm yellow lightbulb `💡` badge above head.
     - **No Blender / Sleeping:** Hands folded peacefully, floating `Zzz` drifting upward.
     - **Step Not Verified:** Puzzled eyes with question mark badge `?`.
5. **Eyes & Expression:**
   - Warm amber illuminated capsule/pill optics (`#F5792A` / `#FFA757`).
   - Eye smile glints and state expressions.
   - Natural periodic blinking cycle every ~3.5 seconds.
   - Reduced-motion mode: switches to static high-clarity indicators.

---

## 3. Instruction Bubble Architecture

1. **Speech Bubble Container:**
   - Translucent dark graphite background (`#1E2127` @ 94% alpha).
   - Rounded corners (`20.dp`), subtle border (`#2D323B`).
   - **Speech Bubble Tail:** Triangular pointer pointing toward the robot avatar.
2. **Content Structure:**
   - **Title:** e.g. "Let's create a sphere!" or step title in bold white (`14.sp`, `#F1F5F9`).
   - **Instruction:** e.g. "Click on **Add** in the top menu, then select **Mesh -> UV Sphere**." (`12.sp`, `#CBD5E1`).
   - **Progress Dots Indicator:**
     - Row of 4-5 rounded progress dots:
       - Orange `#F5792A` for completed / active steps.
       - Dark slate `#3A414D` for remaining steps.
     - On the right: `1 / 4` (or `${stepNumber} / ${totalSteps}`) in muted gray (`#8A94A6`).
3. **Action Buttons:**
   - **Primary Action (Highlight):**
     - Capsule pill with warm orange border (`#F5792A`), subtle orange tint background (`#2B1D15`).
     - Target/sparkle icon (`🎯` / `✨`), bold orange text (`#F5792A` / `#FFA757`).
     - Real click action: resolves target via `DynamicTargetPositioningService` and highlights in Blender.
   - **Secondary Action (Explain More):**
     - Capsule pill with dark surface (`#282F38`), border (`#3D4552`).
     - Book icon (`📖`), white text (`#F1F5F9`).
     - Real click action: opens contextual explanation or reveals chat workspace.

---

## 4. Explain More — Chat Panel

- **Header:** "AI Visual Tutor", "● Connected" green status badge, window controls.
- **Left Sidebar:** "Current Tutorial: Create a UV Sphere", numbered step list with orange active pill (`1 Create UV Sphere`).
- **Main Chat:**
  - Conversational history between user and avatar.
  - Suggestion chips: `Show visually`, `Simplify it`, `Give a hint`.
  - Bottom input bar: Image attachment icon, placeholder "Ask anything about Blender...", orange send arrow (`➤`).

---

## 5. Minimized / Compact Mode

- Compact floating dock pill:
  - Miniature robot head.
  - Bold white label "AI Visual Tutor".
  - Close / reopen affordance.
  - Preserves underlying tutorial state and progress.
