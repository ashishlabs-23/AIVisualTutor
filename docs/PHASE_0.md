# Phase 0 - Research and Research Direction

## 1. Purpose

Phase 0 establishes the research foundation for the AI Visual Tutor project before further implementation. The goal is to identify the perception problem, compare existing GUI-perception approaches, define the proposed research direction, and establish an experimental framework.

This document is based on the uploaded **Phase 0 Research Findings Report: Evidence-Adaptive Windows GUI Perception & Visual Tutor Architecture**. The report reviewed GUI automation, multimodal GUI agents, Windows evaluation frameworks, accessibility/UIA, OCR, visual grounding, adaptive perception, and user-evaluation methods.

## 2. Core Problem

Windows GUI perception is heterogeneous. No single perception source reliably describes every interface.

### Windows UI Automation (UIA)

**Strengths**
- Structured semantic labels.
- Control types.
- Desktop element geometry.
- Low computational cost.
- Useful for native and accessibility-exposed controls.

**Limitations**
- Custom-rendered controls.
- Graphical canvases.
- Legacy/custom UI components.
- Interfaces with incomplete or stripped accessibility metadata.

### OCR

**Strengths**
- Extracts visible text directly from rendered pixels.
- Provides text bounding boxes.
- Independent of the underlying UI implementation.

**Limitations**
- Does not inherently provide control semantics.
- Cannot reliably identify non-text icons.
- Can be ambiguous when neighboring text has similar content.

### Visual Grounding / VLM

**Strengths**
- Can reason over pixels.
- Can identify icons and non-text controls.
- Can interpret visual layout and complex custom-rendered interfaces.

**Limitations**
- Higher computational cost.
- Higher latency.
- Potential privacy overhead.
- Sensitive to resolution and visual complexity.

## 3. Proposed Research Direction

The selected direction is:

> **Evidence-Adaptive GUI Perception for Reliable Windows Desktop Assistance**

The project does not propose training a new Vision-Language Model.

Instead, the proposed contribution is an evidence-adaptive decision layer that combines heterogeneous perception evidence and determines when existing evidence is sufficient and when additional visual reasoning is necessary.

The intended policy is:

```text
ACCEPT
   |
REFINE / ESCALATE
   |
ABSTAIN
```

### ACCEPT

Use strong structured or textual evidence when the target can be identified reliably.

### REFINE / ESCALATE

Request additional visual grounding when UIA/OCR evidence is missing, ambiguous, or conflicting.

### ABSTAIN

Do not make an unsafe target decision when available evidence remains insufficient.

## 4. Proposed Perception Architecture

```text
Screenshot + Selected Region + Application Context
                         |
                         v
                 Evidence Registry
                 /      |      \
                UIA    OCR    Vision
                 \      |      /
                        v
                Evidence Evaluator
                        |
              +---------+---------+
              |         |         |
           ACCEPT     REFINE    ABSTAIN
                         |
                         v
                 Visual Grounding
                         |
                         v
                 Geometry Resolver
                         |
                         v
                  Perception Result
```

The architecture is designed around heterogeneous evidence rather than assuming that a single model is always authoritative.

## 5. Research Motivation from Reviewed Literature

The uploaded research report identifies several relevant directions:

- Modern GUI agents increasingly use multimodal perception.
- ScreenSpot-Pro and SeeClick demonstrate that high-resolution GUI grounding remains difficult.
- RegionFocus / visual test-time scaling demonstrates targeted refinement when uncertainty exists.
- Windows Agent Arena provides large-scale evaluation of multimodal agents on real Windows applications.
- Dynamic UI adaptation research supports adapting system behavior to changing context and uncertainty.
- POMDP-style formulations motivate decision-making under partial observability.
- Compact multimodal models provide possible lower-resource visual reasoning alternatives.

The reviewed literature therefore supports investigating **when and how different perception sources should be selected**, rather than simply adding a larger visual model.

## 6. Research Gap / Problem Framing

The project should avoid an absolute novelty claim such as:

> "No previous system combines UIA, OCR, and visual grounding."

The defensible framing is narrower:

> Existing research has extensively studied GUI visual grounding, OCR-assisted perception, accessibility-tree representations, multimodal GUI agents, and adaptive visual refinement. However, the reliability and decision-making behavior of heterogeneous Windows desktop evidence sources - particularly when UI Automation, OCR, and visual evidence differ in semantic coverage, geometry, confidence, or availability - requires further empirical characterization.

This is a research hypothesis and investigation target, not a proven novelty claim.

## 7. Phase 0 Research Questions

### RQ1 - Perception effectiveness

How does each perception modality contribute to GUI target identification across different Windows application and UI-element classes?

### RQ2 - Adaptive evidence selection

Can adaptive evidence selection provide an improved reliability-latency trade-off compared with single-modality or always-on multimodal perception?

### RQ3 - Reliability and abstention

Can explicit uncertainty and abstention reduce incorrect GUI targeting when available evidence is incomplete or conflicting?

These questions must be answered experimentally; they must not be treated as established results.

## 8. Experimental Conditions

The uploaded research report proposes four user-level comparison conditions:

| Condition | Description |
|---|---|
| A | Conventional video/static step-by-step guide |
| B | Generic AI chat without visual contextual grounding |
| C | Fixed visual guidance using a static/single perception pipeline |
| D | Full adaptive visual tutor using UIA + OCR + visual grounding |

Condition D represents the proposed system and should only be evaluated after the perception and adaptive-decision components are implemented.

## 9. Evaluation Metrics

### Task performance

- Completion time.
- Task success rate.
- Error count.
- Action trajectory length where applicable.

### Support dependency

- Hint requests.
- Manual interventions.
- Escalations.

### Learning

The report proposes:

```text
Learning Gain =
(S_post - S_pre) / (S_max - S_pre)
```

Also evaluate:
- Retention gain.
- Skill transfer.

### Usability and workload

- SUS.
- UEQ.
- NASA-TLX.
- Mental demand.
- Effort.
- Temporal demand.
- Frustration.

## 10. Perception-Level Metrics

For the technical perception system, the project should separately measure:

- Target identification accuracy.
- Element-match accuracy.
- Text-match accuracy.
- Bounding-box IoU where coordinate spaces are validly aligned.
- Point-in-target accuracy.
- Confidence/calibration.
- Abstention rate.
- Provider invocation rate.
- Latency.
- Computational/resource cost.
- Failure rate.

Cross-source geometry must only be compared when coordinate transformations are explicitly verified.

## 11. Phase 0 Output

Phase 0 establishes:

1. The Windows GUI perception problem.
2. The complementary strengths and weaknesses of UIA, OCR, and visual grounding.
3. Evidence-adaptive perception as the selected research direction.
4. ACCEPT / REFINE-ESCALATE / ABSTAIN as the proposed decision policy.
5. A comparative experimental framework.
6. Technical and user-centered evaluation metrics.
7. A conservative research-gap formulation.

## 12. Boundaries

Phase 0 does not claim:

- That adaptive perception is already superior.
- That a particular VLM is the best model.
- That UIA/OCR/vision fusion is novel by itself.
- That real Windows benchmark results have already been obtained.
- That the proposed evaluator has already improved latency or accuracy.

Those statements require implementation and empirical validation.

## 13. Relationship to Later Implementation

Phase 0 provides the research basis for the later perception implementation.

The implementation path is:

```text
Phase 0
Research foundation
      |
      v
Phase 4A-4D
Perception contracts + UIA + OCR
      |
      v
Phase 4E-4F
Visual-grounding boundary + feasibility
      |
      v
Phase 4G-4H
Evidence evaluation + Windows validation
      |
      v
Final Phase 4
Visual-grounding provider + adaptive evaluator
      |
      v
Experimental validation
```

Phase 0 should remain a research foundation document. It should not be treated as proof of experimental results.

## 14. Source Basis

Primary source for this Phase 0 document:

**Phase 0 Research Findings Report: Evidence-Adaptive Windows GUI Perception & Visual Tutor Architecture**

The uploaded report reviewed:
- GUI automation and multimodal GUI-agent literature.
- Windows OS environment evaluation and context-adaptation literature.
- Project architecture and repository implementation.

The report's consolidated references include Windows Agent Arena, SeeClick, Pix2Struct, ScreenSpot-Pro, RegionFocus, Reflexion, Spider2-V, WorkArena++, and InfiGUIAgent.
