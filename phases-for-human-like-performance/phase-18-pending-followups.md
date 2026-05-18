# Phase 18 — Pending Follow-Ups (consolidated)

**Status:** Tracking doc, not a single-PR phase. Created 2026-05-18 by
walking every prior phase doc's unchecked items, cross-checking against
the current code state, and collecting only items that are **genuinely
open** (file/symbol absent OR explicit deferral with a named trigger).

**Scope:** Three code-pending items, one deferred-decision item, and
pointers to two existing trackers that cover everything else. Most prior
unchecked items audited as DONE-DESPITE-UNCHECKED (Phase 0–4 entities +
services all exist; Phase 7 F3 topics-v2 flipped on; F4 max-computations
bumped 2→5; Phase 12 D1–D7, Phase 13 E1–E6, Phase 14 F1–F6, Phase 16
P5/P6/P7, Phase 17 P8a/P8b/P8e all shipped).

**What this phase deliberately does NOT track:**

- **Flag flips** — see `phase-flags.md`. Nine flags coded + shipped but
  deliberately held at default-off / `warn` mode pending the next
  REVIEW_REPORT refresh (D2 `numerical-consistency.mode`,
  `gate-retry.enabled`, `panel-revision.enabled`,
  `panel-degradation-demote`, `audit-cherry-picked-rejects`,
  `panel-grade-floor`, `citations-as-schema.mode`,
  `corpus-citations.suppress-rejected`, `prior-result-injection`,
  `topic-clone-backoff`). Each row already names its trigger condition
  in `phase-flags.md`.
- **Human-level paper-building levers** — see
  `phase-wishlist-human-level.md` (the renamed-and-trimmed wishlist).
  P1 (expanded tool surface), P2 (sandbox friction relaxation), P3
  (schema-pinned reviewer prompts), P4 (coupling-equation gates for
  INTERDISCIPLINARY). None shipped; each carries an open architectural
  question; pull from there once Phase 12's rollout signals which lever
  to pull next.
- **Phase 0–4 unchecked boxes** — stale presentation, not stale work.
  Audited 2026-05-18: every entity / service / field named in the
  unchecked lists exists in `src/main/java/com/emergentterrain/`. The
  boxes were never ticked because the work landed in adjacent commits
  that didn't touch the doc. A future cleanup pass can flip them; the
  underlying code is not pending.

---

## Items pending

### 18.1 — P8c: magnitude-range validator scans tool-call trace

**Source:** former `phase-wishlist.md` (now `phase-wishlist-human-level.md` Migration table) P8c (refresh #1 evidence on P-a328-593).

**The bug:** `MagnitudeRangeValidator` today scans Findings prose only.
P-a328-593's call_4 returned `Best: T=785 K → P_c=52 atm → longitudinal
σ=5790 MPa` — 5.79 GPa, above the 3 GPa composite-stress ceiling — but
the Findings prose dropped the 5790 MPa number, so the prose-scanning
pass found nothing to flag. Status PUBLISHED. The panel-grade-floor
gate (once flipped — tracked in `phase-flags.md`) catches the panel
aspect of this paper; this item closes the magnitude aspect at the same
publish-time tier.

**Files:**
- `src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java`
  — exists; add a tool-call-trace overload alongside the existing
  findings-text overload; share the per-(domain, topic) ceiling table.
  Only scan the final `result` field of each completed call, not the
  sweep grid, to avoid flagging legitimate intermediate sweep values.
- `src/main/java/com/emergentterrain/research/UniversityService.java`
  — at the call site (~line 800–810), pass `narration.toolCallTrace()`
  alongside the existing `findingsBlocks`.

**Acceptance:** Replaying P-a328-593 produces a
`magnitude-range:trace_violation` stamp on the call_path and routes
REJECTED based on the 5790 MPa trace value, independent of Findings
prose. Existing prose-violation papers continue to flag as before. No
new false positives on parameter-sweep papers whose intermediate values
legitimately exceed the ceiling.

**Risk:** Low–medium. False-positive risk on sweep-paper intermediate
values; mitigation is the "final `result` only" scoping above.

### 18.2 — P8d: unsourced-claim validator ("math right, prose fabricated")

**Source:** former `phase-wishlist.md` (now `phase-wishlist-human-level.md` Migration table) P8d (refresh #2 evidence on P-a328-603 +
P-a328-605).

**The bug:** Two refresh-#2 papers had **correct tool-call math** but
Findings prose that invented specific quantitative claims no call
produced:

- P-a328-603 Snell. Call 3 returned exact `arcsin(1.49/1.50) = 83.3804°`.
  Findings prose invented "1.5° maximum observed error" and "331° per
  unit n₂ derivative" — neither has a supporting tool call.
- P-a328-605 Kepler. Calls 3 + 5 returned exact 4.12% / 4.08% T²/a³
  deviations. Findings prose claimed "53 samples" and "20 samples" —
  only 5 calls ran. `SinglePointSweepValidator` caught the sample-size
  claim; nothing caught the derivative claim.

The pattern: LLM produces honest computation, then pads prose with
"physical interpretation" content that reads plausible but has no
tool-call provenance. The panel catches these (honesty / completeness
sink to 0.00–0.10) but the LLM keeps emitting them; only the panel-
grade-floor demote (tracked in `phase-flags.md`) saves the corpus.

**Files (proposed):**
- New: `src/main/java/com/emergentterrain/research/UnsourcedClaimValidator.java`
  — co-locate with `NumericalConsistencyValidator` /
  `SinglePointSweepValidator`. Single static method
  `List<String> detect(String hypothesis, Map<Integer,String> findingsBlocks, String conclusion)`.
- `src/main/java/com/emergentterrain/research/UniversityService.java`
  — wire at the publish-time validator layer alongside
  `direction-of-finding` / `single-point-sweep` / `magnitude-range`.

**Vocabulary (initial, empirical from refresh #2):**
```
derivative\s+(of|d\w/d)
uncertainty\s+of
error\s+(magnitude|propagation)
±\s*\d
(\d+)\s+samples?      ← already handled by SinglePointSweepValidator
                          — DO NOT double-stamp; defer sample-size to F3
```

**Acceptance:** Replaying P-603 + P-605 produces
`unsourced_claim:derivative` / `unsourced_claim:uncertainty` stamps
that route NEEDS_REVISION when the named claim types appear without
a paired `[call_N]` reference. P-595 Basquin (correct math AND correct
prose) continues to pass.

**Risk:** Medium. Regex for "derivative / uncertainty / error" is
fuzzy; false positives on legitimately-cited claims plausible.
Mitigation: ship in `warn` mode first (stamps on call_path but doesn't
route), promote to `downgrade` after one refresh confirms FP rate
< 5%. Same rollout shape as D2 / direction-of-finding / single-point-
sweep.

**Open questions:**
- Should the validator look for a `[call_N]` *within the same sentence*
  as the trigger phrase, or anywhere in the surrounding paragraph?
  Sentence-scoped is stricter (higher recall, more FPs); paragraph-
  scoped is looser. Empirical from a 30-paper refresh window.
- Does the existing `NumericalConsistencyValidator` cover any of these
  shapes already? Spot-check before duplicating.

### 18.3 — Phase 4c Piece 3 decision (A/B testing + epsilon-greedy)

**Source:** `phase-4c-piece-1-memo.md` + `phase-4c-prompt-template-evolution.md`.

**Context:** The Phase 4c memo (dated 2026-05-11) locked the eight
cross-cutting decisions for A/B template testing with epsilon-greedy
allocation (ε=0.10, 1000-paper threshold, 5pp promotion margin) and
deferred Piece 3 (the persistence + runtime selector) explicitly
pending "2–4 weeks of Phase 4b runtime observation" before committing
to the schema.

**Where things stand:**
- Piece 1 (memo): shipped — eight decisions locked.
- Piece 2 (registry refactor): shipped — `PromptTemplateRegistry`
  + `PromptTemplate` exist, 12+ templates registered in
  `PromptTemplateIds`. Code comments at
  `PromptTemplateRegistry.java:22` and `:892` and
  `PromptTemplate.java:15` reference the *future* epsilon-greedy
  selector — the pick rule today is the single-template default.
- Piece 3 (persistence + epsilon-greedy selector): NOT shipped.
  The deferral window opened 2026-05-11; at 7 days from open today
  (2026-05-18), the 2-week minimum is one week away.

**The decision needed:** Is the Piece 3 schema still the right shape
given everything Phase 7 / 12 / 16 / 17 surfaced about prompt-quality
failure modes? The original memo locked allocation ε=0.10 and the
1000-paper threshold against pre-Phase-7 prompt density (smaller
templates, fewer revision passes). Post-Phase-17 the prompt surface is
larger (panel-revision feedback prompts, prior-result injection
strings) and the per-paper cost is higher; a smaller ε or higher
threshold may be warranted.

**Action:** When Piece 3 implementation starts, re-read the memo's
eight locked decisions and judge whether they still fit. Specifically
revisit:
- ε value (0.10 may be too high for current cost; 0.05 plausible).
- Promotion threshold (1000 papers may be too high if the corpus
  generation rate has slowed; 500 plausible).
- Whether the unit of A/B is per-paper or per-bot (the memo locked
  per-paper; per-bot would interact with Phase 6 P6b prior-result
  injection more cleanly).

**No code change in Phase 18.** This item is a tracker pointer for
when Piece 3 implementation lands — likely as its own `phase-4c-piece-3`
doc once the operator commits.

---

## Items NOT pending (audit results, abbreviated)

For provenance, this section records what was reviewed and confirmed
done. Skip on re-read unless verifying the audit.

| Source doc | Item | Status | Evidence |
|------------|------|--------|----------|
| phase-0-prerequisites.md | All 10 unchecked boxes | DONE | `ResearchPaper`, `PaperCitation`, `LlmService` (209 lines), `ResearchNarrationService`, `FormulationService`, `GenomeMentoringService`, `BotLifecycleService`, `ResearchCycleService`, `ReplicationService`, `PopulationEcologyService` all exist |
| phase-1-foundation.md | All 12 unchecked boxes | DONE | `ResearchIdentity` record, `skepticismLevel` field on `BotLifecycleState:34`, `ReputationHistory`, `PaperCitation` (=PaperRelationship), `PaperGraphService`, `ResearchProgram` + `ProgramOpenQuestion` + `ProgramFailedApproach` entities |
| phase-2-active-experimentation.md | All 12 unchecked boxes | DONE | `ExpectationModelService`, `SurpriseEvent`, `ExperimentPlannerService`, `PlanStrategy` enum |
| phase-2-5-roomservice-refactor.md | All 7 unchecked boxes | OBSOLETE | Architecture moved past — Phase 5+ designs supersede the standalone refactor |
| phase-3-world-integration.md | All 19 unchecked boxes | DONE | `WorldDataQueryService`, `WorldInterventionService`, `University` / `Journal` / `FundingAgency` / `ReplicationCouncil` entities, `EpistemicMarketService` |
| phase-4-formal-knowledge-and-meta-science.md | All 17 unchecked boxes | DONE | `FormalFinding`, `KnowledgeBaseService.detectContradictions`, `MetaResearchService` (`PromptPathStats` / `BotMetaStats` / `MethodMetaStats`), `PaperKind` enum, `PromptTemplateRegistry` |
| phase-4c-prompt-template-evolution.md | Piece 1 + 2 | DONE | Memo locked; registry shipped. **Piece 3: deferred — see 18.3** |
| phase-7 F3 | `topics-v2.enabled` | DONE | `application.yml:678` — flag on; 38 topics across 4 domains |
| phase-7 F4 | `max-computations` bump | DONE | `application.yml:470` — bumped 2 → 5 |
| phase-12 D1–D7 | All 7 steps | DONE | Commits `363b966`, `05b2140`, `197c68d`, `041671a`, `5c06acd`. Mode escalation for D2 still pending — see `phase-flags.md`. |
| phase-13 E1–E6 | All 6 fixes | DONE | Commits `f83aa12`, `b7326ab`, `83af22b`, flag-flip `041671a` |
| phase-14 F1–F6 | All 6 validators | DONE | Commits `f02c343`, `74ca517`, `ef4a3fb`, `918ac90`, flag-flip `041671a` |
| phase-16 P5a / P5b / P6a / P6b / P7 | All 5 narrator + corpus-aware items | DONE | Commits `8b5c46a`, `af4cc97`, `72e99e1`, `4d9cad3`. Flag flips tracked in `phase-flags.md`. |
| phase-17 P8a / P8b | Diagnostics + counter symmetry | DONE | Commits `90cb52f`, `840b9b4` |
| phase-17 P8e | Internal-fragment detector | WISHLIST-ONLY | Commit `133fe83` pinned the test; no validator file. Hold per the doc — single observed occurrence (P-647); promote to implementation only if a second example appears within 3 refreshes. |
| phase-17 analyses follow-ons | Sandbox pre-scan, hypothesis-carryover, JSON-schema revision, temperature plumbing | DONE | Commits `c919a19`, `7c4b45e`, `2ac020d`, `6cc1636` |

**Audit completeness:** Every unchecked box in every phase doc under
`phases-for-human-like-performance/` (excluding `.claude/worktrees/`)
was visited. If a future review finds an item not covered above,
update this table rather than starting a new tracking doc.

---

## Closing condition for Phase 18

This phase closes when:
1. 18.1 (P8c trace scan) and 18.2 (P8d unsourced-claim validator) ship.
2. 18.3 (Phase 4c Piece 3 decision) is either resolved by starting
   Piece 3 implementation under its own phase doc OR explicitly
   retired with a "Piece 3 deferred indefinitely — reason X" note in
   `phase-4c-piece-1-memo.md`.

When all three close, this doc becomes historical; new pending items
get a successor doc rather than appending here.
