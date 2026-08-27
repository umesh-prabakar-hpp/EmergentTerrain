# Phase 16 — Recommendation Fixes #3 (as shipped)

**Scope:** Eight items drawn from REVIEW_REPORT refreshes #3 + #4
(`research/a3289005/REVIEW_REPORT.md`, 2026-05-17, papers P-a328-514 –
P-a328-555, n=21). Each item closes a *named* failure mode visible in
the corpus — three FP fixes + four coverage extensions to
`NumericalConsistencyValidator`, two narrator-stage scrubs, two
corpus-aware narration upgrades, and one scheduler-stage topic-clone
backoff. Total work shipped across five commits between 10:14 and
11:30 on 2026-05-17.

**Status:** ✅ Code shipped. Three new default-OFF flags joined the
`phase-flags.md` rollout queue:
- `app.research.corpus-citations.suppress-rejected` (P6a suppression mode)
- `app.research.prior-result-injection.enabled` (P6b)
- `app.research.topic-clone-backoff.enabled` (P7)

The validator changes (FP fixes + coverage) and P5 narrator scrubs ship
unflagged — they extend what `warn` mode catches without escalating
status decisions.

**Prerequisite:** Phase 15 Tier 1 (six flags, commit `041671a`) and
Tier 2 (F1b `block-count-guard`, commit `918ac90`) flipped on
2026-05-17 before Phase 16 started, so the corpus baseline for Phase
16's acceptance is post-Phase-14 + Phase-15-Tier-1/2.

**Why now:** REVIEW_REPORT refresh #3 (n=11, 5.3% cumulative
acceptance) showed the failure surface had shifted. The math substrate
was producing exact arithmetic in 5 of 11 papers, but the citation /
labelling / narrator-stage layers were garbling the references and
inserting unsourced framing text. Phase 16's eight items target that
shifted surface; the validator extensions raise what `warn` catches,
the narrator scrubs close the most-visible source of fabricated
conclusions, and the corpus-aware narration + scheduler backoff close
the cross-paper failure loop (P-a328-541 inverting P-a328-516's
correct Reuss result; P-a328-539 re-fabricating P-a328-534's rejected
convergence claim).

**Scoping origin:** P5/P6/P7 originated in `phase-wishlist.md` as
high-leverage levers (added 2026-05-17 from refresh #3/#4 evidence;
the wishlist has since been renamed to `phase-wishlist-human-level.md`
and the P5–P8 sections trimmed — see its Migration table for the
disposition of each former item).
The validator items originated in `phase-flags.md` (then named
`phase-15-flag-rollout-queue.md`) under "Known validator FPs blocking
D2 escalation" + "Coverage gaps to ship alongside the FP fixes".
Both source docs are still authoritative for the scoping rationale;
this doc captures the *as-shipped* state with flag-flip
specifications for operator rollout.

---

## D2-prep — NumericalConsistencyValidator FP fixes + coverage extensions

**Shipped:** 2026-05-17 in commit `f6b89b9`. All seven changes live in
`src/main/java/com/emergentterrain/research/NumericalConsistencyValidator.java`;
16 new tests in `NumericalConsistencyValidatorTest` (28 → 44). The new
reasons emit in `warn` mode — no flag flip required. D2's
`warn → downgrade` escalation in `phase-flags.md` still gates on a
post-fix corpus re-run dropping the FP rate below ~5%.

**FP fixes (Phase 15 doc items #1-#3, now closed):**
1. Sign-aware magnitude matching — `isPermittedNumber` also matches
   `Math.abs(p)` so prose "15.286" matches trace "deviation -15.286"
   (P-a328-555).
2. `[call_0]` off-by-one labelling drift — when prose cites `[call_0]`
   AND the trace has `call_1`, emit a softer "labelling drift"
   reason instead of "does not appear in trace" (P-a328-516).
3. Framing-percentage severity — `"%"` unit + value in {5, 10, ...,
   100} emits "unsourced framing value" instead of
   "derived-formula divergence" (P-a328-526).

**Coverage extensions (Phase 15 doc items #4-#7, new checks):**
4. `[call_N]` resolves but the call failed — `ERRORED_RESULT_MARKER`
   extended to catch "Invalid range:", "Invalid params:", "Sandbox(ed)
   execution failed", "Symbolic evaluation failed", "Topic not
   computable", "REJECTED (static):", "REJECTED:", plus the
   "Cannot use array 'X' as scalar" variant (P-a328-519/532/539/543).
5. One-sided CI tightening — new `findCiShrinkage` method flags prose
   `± Y < trace ± Y × 0.5` (P-a328-528/521).
6. Hypothesis-vs-executed-range scope mismatch — new `findScopeOverreach`
   method parses `[a, b)` from prose, `range_start/range_end` from
   trace argsJson, flags prose ranges not contained in the executed
   union (P-a328-547).
7. Inline `Computation N` stub in Findings prose — new
   `findInlineComputationStubs` complements F1b's block-count gate by
   catching mid-paragraph stubs (P-a328-553).

---

## P5 — Narrator scrubs

**Shipped:** 2026-05-17 in commit `8b5c46a`. Both pieces touch
`PromptSanitizer` + `UniversityService.publishPaper`; 13 new tests in
`PromptSanitizerTest` (58 → 71). Both run unconditionally — no flag.

**P5a — Suppress canned conclusion when status ≠ PUBLISHED.** New
`suppressBoilerplateOnNeedsRevision` method on PromptSanitizer; fires
on `gate:closed:professor`, `publish:no_methods`, or
`→needs_revision` markers in the call path. Emits a `[gate:closed —
no professor-stage evidence; conclusion is not supported by tool
calls]` disclaimer when the professor never ran, or `[needs_revision
— flagged for further work]` otherwise. Also quietly back-fills the
`BOILERPLATE_INDEPENDENTLY_REPRODUCED` regex to match the
comma-continuation forms ("...peer reviewers, and the topic-specific
validator..." / "...peer reviewers, but the topic-specific
validator...") that Phase 7 G5 had been missing on
SUPPORTED/REFUTED claim statuses. P-a328-553 was the canonical target.

**P5b — Strip number-words harness leaks.** Adds `NUMWORD_ATOM` /
`NUMWORD_PHRASE` fragments covering zero-hundred + thousand, compound
forms (twenty-five, one hundred), and decimal forms (seven point
one). Two new patterns:
- `PARAM_PARENTHESIZED_WORDS_PAIR` — "(starting position = fifty,
  scale = seven point one)" — P-a328-541
- `PARAM_BARE_WORDS_ASSIGN` — bare "starting position of fifty" /
  "scale of seven point one"

Both wired to `sanitize()` and `detects()`.

---

## P6a — Cross-paper citation verdict propagation

**Shipped:** 2026-05-17 in commit `af4cc97`. Helpers
(`isRejectedStatus`, `verdictTagFor`) on `PromptSanitizer`; wiring in
`ResearchNarrationService.buildNarrationPrompt`'s priorPapers
iteration. 6 new tests in `PromptSanitizerTest` (71 → 77).

**Disclaimer mode (default — unconditional):** Every prior paper
surfaced in the narration prompt's priorPapers context block carries a
verdict tag prepended to the paperId:

- `REJECTED` → `[REJECTED — do not propagate] `
- `DISPUTED` → `[DISPUTED — under replication review] `
- `PUBLISHED` / `NULL_RESULT` / `NEEDS_REVISION` / null → no tag

The narrator LLM thus sees the verdict alongside the paperId and can
choose not to propagate failed-paper conclusions verbatim. This part
of P6a runs for every paper today; no flag.

**Suppression mode (flag-gated):**
`app.research.corpus-citations.suppress-rejected` (default false) —
when on, REJECTED priors are dropped from the priorPapers context
block entirely. The narrator LLM never sees them.

**Trigger to flip suppression mode on:** One REVIEW_REPORT refresh
under disclaimer mode where the narrator continues to cite REJECTED
priors silently (without acknowledging the tag). If the tag's signal
is too weak, suppression is the right next step.

**Risk:** Low. The narration prompt content changes; paper status
does not. Flipping back is one YAML edit.

**Acceptance after flip:** Zero RELATED_WORK sections cite a paper
whose verdict was REJECTED. The disclaimer-mode tag stops appearing in
the prompt entirely (the priors aren't surfaced).

**Canonical target:** P-a328-539 → P-a328-534. P-539 cited P-534
(REJECTED for fabricated convergence) in Related Work and re-committed
the same fabrication; the disclaimer tag would have given the
narrator a clearer signal not to propagate.

---

## P6b — Prior-result injection at formulation time

**Shipped:** 2026-05-17 in commit `72e99e1`. Helpers
(`buildPriorResultHint`, `firstSentence`) on `FormulationService`;
wired into `buildFormulationPrompt`'s `optionalContext` slot. 12 new
tests in `FormulationServiceTest` (19 → 31).

**Flag-gated, default OFF:**
`app.research.prior-result-injection.enabled` (false) with
`app.research.prior-result-injection.min-score` (0.5).

**What flipping it does:** When the formulator selects a (domain,
topic) that has *exactly one* PUBLISHED prior with `peerScore >=
min-score`, the formulation prompt gains a:

> STRONG-CONFIDENCE PRIOR: P-a328-NNN (score 0.YY) established that
> <one-sentence conclusion>. Choose one path: (1) replicate at
> materially different parameters, (2) refute with new evidence and
> explicitly cite the contradiction, (3) shift to a different
> sub-question. Do NOT implicitly contradict the prior without
> acknowledging it.

Zero qualifying priors → no change. Two or more → no change (the
size-1 restriction; disagreeing priors stay on the deferred P6b.2
list).

**Trigger to flip on:** One REVIEW_REPORT refresh under P6a's
verdict-tag soak. Sequencing matters — P6a's disclaimer mode is
unconditional, so its effect lands first. If P6a alone closes the
re-fabrication-of-rejected-priors pattern (P-a328-539 → P-534), P6b's
marginal value drops. If P6a doesn't close it but the narrator does
engage with the verdict tag (acknowledging without ignoring), P6b is
the right next step.

**Risk:** Medium. Prompt expansion adds ~80-120 tokens to formulation,
which slightly changes hypothesis-style distribution even before the
hint's content effect lands. The `min-score` knob is the safety
valve.

**Acceptance after flip:** Papers landing on a topic with a strong-
confidence prior either (a) cite the prior in Related Work AND its
parameters differ materially from the prior, (b) explicitly refute
the prior's claim in Conclusion, or (c) the new Hypothesis's
question text is materially different from the prior's. The next
refresh's "Topic clone collisions" recommendation count drops vs the
baseline.

**Canonical target:** P-a328-541 inverted P-a328-516's correct
Reuss V_f=1 result without acknowledging it. The prior-result hint
would have surfaced P-516's exact conclusion before P-541's
hypothesis was written.

---

## P7 — Topic-clone scheduler backoff

**Shipped:** 2026-05-17 in commit `4d9cad3`. New `TopicCloneBackoff`
utility (`src/main/java/com/emergentterrain/research/TopicCloneBackoff.java`)
+ wiring in `ResearchCycleService.runCycle` after `selectTopicIndex`.
13 new tests in a new `TopicCloneBackoffTest` class.

**Flag-gated, default OFF:**
`app.research.topic-clone-backoff.enabled` (false) with
`app.research.topic-clone-backoff.window-size` (5) and
`app.research.topic-clone-backoff.max-retries` (3).

**What flipping it does:** After the initial topic pick, count
REJECTED papers on the chosen (domain, topic) in the last `window-
size` published papers in the room. Probability of keeping the topic
is `0.5^rejections`. On a resample-decision, `selectTopicIndex` is
invoked again with the same flags up to `max-retries` times. After
`max-retries`, the current candidate is accepted regardless — never
blocks on a fully cooled domain.

Geometric decay:
- 0 rejections → keep 1.0
- 1 → keep 0.5
- 2 → keep 0.25
- 3 → keep 0.125 (so 7 of 8 attempts resample)

**Trigger to flip on:** One REVIEW_REPORT refresh under P6b's
prior-injection soak. P7 is the *backstop* for the topic-clone
failure — P6b's prior-injection should reduce repeat-topic-reject
rates by giving the formulator clearer guidance; P7 catches the
residual cases. Flipping P7 before P6b risks the scheduler resampling
away from topics where P6b would have produced a clean follow-up.

**Risk:** Medium. Coupling to existing reward dynamics (Phase 5 C1
citation scoring, Phase 1 program rewards) means a topic that's
rejected for fabrication reasons looks identical at the
selection-weight layer to a topic that's genuinely hard. The 5-paper
rolling window naturally decays the penalty.

**Acceptance after flip:** No topic accumulates ≥3 consecutive
rejections within a 5-paper sliding window. Per-refresh log
inspection shows `topic-clone backoff: bot=… domain=… initial=X →
final=Y` lines when the backoff fires; `final == initial` means the
topic survived the keep-roll despite rejections (acceptable; the
random keep is intended behaviour at low rejection counts).

**Canonical target:** Divisor-sum-average was attempted four times in
the corpus (P-a328-534, 539, 553, plus one suppressed pre-disk).
All four failed with overlapping `(1/N)` vs `(1/N²)` convergence-
fabrication patterns. P7 caps the recurrence rate at this
geometric-decay schedule.

---

## What Phase 16 deliberately does NOT change

- **D2 mode escalation** (`warn → downgrade` / `suppress`). The FP
  fixes shipped here remove the known false-positive patterns that
  blocked D2 escalation in `phase-flags.md`. The escalation itself
  remains on the rollout queue pending a post-fix corpus re-run that
  drops the FP rate below ~5%. Not shipped in Phase 16.
- **D4, F7.3, C5b** (the other three `phase-flags.md` holdovers).
  Phase 16 doesn't touch them. F7.3's threshold was tightened
  (≥30 → ≥10) in `918ac90` based on refresh #3 evidence; the flag
  flip still pending the cohort growing to ≥10.
- **The P6b "disagreeing priors" multi-prior case.** Deferred to a
  future P6b.2. The first-iteration restriction is "exactly one
  qualifying prior"; ≥2 priors → skip injection.
- **Topic-health metric service.** Wishlist P7's scoping mentioned a
  small `TopicHealthService` for "topic heat" debug logging.
  Shipped as a pure-static `TopicCloneBackoff` utility instead — no
  service needed, no per-room state outside the existing published-
  papers list. The "topic heat" log line is logged ad-hoc when the
  backoff fires (per-call, not per-refresh).

---

## Flag rollout state (post-Phase 16)

| Flag | Default | Where to flip |
|------|---------|---------------|
| `corpus-citations.suppress-rejected` (P6a suppression) | off | one refresh under disclaimer mode |
| `prior-result-injection.enabled` (P6b) | off | one refresh under P6a soak |
| `topic-clone-backoff.enabled` (P7) | off | one refresh under P6b soak |

Tracked alongside the four original Phase 15 holdouts in
[phase-flags.md](phase-flags.md).

---

## Acceptance gates

| Piece | Headline acceptance test |
|-------|--------------------------|
| **D2-prep** | Post-fix corpus re-run shows the FP rate on `numerical-consistency check failed` drops below ~5% (refresh #3 measured 6-19%). |
| **P5a** | Zero papers ship with the canned "values reproduced by peer reviewers" line under NEEDS_REVISION over one refresh after ship. |
| **P5b** | Zero papers carry number-words harness phrasing in Hypothesis or Findings over one refresh after ship. |
| **P6a** | Disclaimer mode: every priorPapers context line for a REJECTED prior carries the `[REJECTED — do not propagate]` tag. Suppression mode (post-flip): zero REJECTED priors appear in the context. |
| **P6b** | After flip, papers on topics with strong-confidence priors either replicate, refute-with-citation, or shift sub-question; per-refresh "Topic clone collisions" recommendation count drops. |
| **P7** | After flip, no topic accumulates ≥3 consecutive rejections in a 5-paper window without being de-prioritised. |

**Phase-level success:** Acceptance rate (Acceptable + Minor) over a
25-paper window reaches ≥25% (refresh #3 cumulative: 5.3%; refresh #3
operational-logs §"Pattern explaining acceptance-rate plateau"
projected the upper bound after these fixes alone at ~25%).
