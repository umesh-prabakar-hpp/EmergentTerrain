# Phase 15 — Flag Rollout Queue

**Scope:** Rolling tracker for validator flags that are coded + shipped
across Phases 7 / 11 / 12 / 14 but deliberately held at default-off (or
warn-mode) pending corpus observation. Each entry names the **trigger
condition** that flips the flag from off to on (or escalates a mode
from `warn` to `downgrade` / `suppress`).

**Status:** Created 2026-05-17 after Tier 1 of Phase 14 flipped on
(commit `041671a` — Phase 12 D6, Phase 13 E6, Phase 14 F1a / F3 / F4 /
F5). The Tier 1 flags landed because each had a clear "flag-off path
is bit-identical to today" guarantee plus a one-refresh observation
window already met by REVIEW_REPORT refresh #1 + #2 in
`research/a3289005/`. The flags below need either *more* corpus
evidence or operator-judgement before flipping.

Phase 15 is unlike Phases 0–14 in that it ships no new code on its own
— its commits are configuration changes that arrive ad-hoc as refresh
evidence accumulates. Update each row's *State* / *Trigger met?* /
*Decision* columns when REVIEW_REPORT refreshes land. Phase 15 closes
when this queue is empty (every flag either flipped on or removed as a
deliberate keep-off).

**Queue depth**: 4 of 5 original entries remaining. Tier 1 (six flags)
flipped 2026-05-17 in commit `041671a`. Tier 2 (one flag — F1b
`block-count-guard`) flipped 2026-05-17 in commit `918ac90`. The four
remaining are all Tier 3 (operator-judgement or larger-sample
prerequisites) — see Snapshot below.

---

## Snapshot

| Tier | Flag | Phase | Current | Trigger | Risk |
|------|------|-------|---------|---------|------|
| 3 | `app.research.panel-degradation-demote.enabled` | 12 D4 | off | F7.2 panel agreement scores stable + ≥30 papers | Medium — status-changing |
| 3 | `app.research.audit-cherry-picked-rejects.enabled` | 7 F7.3 | off | ≥10 PUBLISHED + SUPPORTED with ≥3 cherry firings (tightened 2026-05-17 — see note below) | Medium — status-changing |
| 3 | `app.research.numerical-consistency.mode` | 12 D2 | `warn` | Operator review of warn-mode suppress markers **AND** known validator FPs fixed (see "Known validator FPs blocking D2") | High — flips structural validator from log-only to enforcing |
| 3 | `app.research.citations-as-schema.mode` | 11 C5b | `warn` | Two refreshes with stable uncited / out_of_tolerance rates | High — flips citation validator from log-only to enforcing |

## Recently flipped — Tier 1 (commit `041671a`, 2026-05-17)

The first wave of rollouts. Each entry shipped its `enabled: true` flip
under the criterion "flag-off path is bit-identical to today + a clear
named failure mode in REVIEW_REPORT refresh #1 or #2 + a one-refresh
observation window already met." No status-precedence changes were
needed beyond the existing UniversityService ladder.

| Flag | Phase | Closes (refresh #2 corpus) |
|------|-------|----------------------------|
| `app.research.closing-prose-validator.enabled` | 12 D6 | Conclusions citing "no prior theoretical bound" on a topic with a known textbook closed-form (P-422 Beer-Lambert, P-423 Stefan-Boltzmann, P-427 Doppler, P-432 Hall, P-416 Mertens). |
| `app.research.uncertainty-disclosure.enabled` | 13 E6 | The most-repeated panel critique in refresh #1 (~38 mentions across ~18 of 23 papers) — Findings citing unit-anchored numerics without `±`, "exact closed form", or "to within X%" qualifiers. |
| `app.research.missing-methods-gate.enabled` | 14 F5 | Papers reaching publishPaper with a null or empty tool-call trace (P-503 signature: no Methods + no Panel section). |
| `app.research.single-point-sweep.enabled` | 14 F3 | Inflated sample-size claims — "sixteen sampled cases" or "n=40 windows" backed by ≤2 successful tool calls (P-494, P-507, P-512). |
| `app.research.direction-of-finding.enabled` | 14 F4 | Conclusion polarity inversion — "ruled out" against a Findings value clearly within the Hypothesis bound (P-491, P-496, P-511). |
| `app.research.block-count-stamp.enabled` | 14 F1a | **Observability-only.** Annotates the structured-findings markdown with `[call_N]` per block and replaces blocks beyond `trace.size()` with `(no data — call_N missing)` markers. Precursor to F1b: refresh #3 will show how many papers would have been gated. |

Phase 13's verification status (recorded in STATUS.md) now has E6
covered by this flip; the remaining unverified piece is E3 (Q-factor
paren swap), which needs an RLC paper in the next refresh's batch to
exercise. E1, E2, E4, E5 were already verified by refresh #2 evidence.

---

## Recently flipped — Tier 2 (commit `<pending>`, 2026-05-17)

The second wave. Same criterion as Tier 1: a clear "flag-off path is
bit-identical to today" guarantee, a named failure mode, and the
observation window required by the per-flag trigger condition was met
by a subsequent REVIEW_REPORT refresh.

| Flag | Phase | Closes (refresh #3 evidence) |
|------|-------|------------------------------|
| `app.research.block-count-guard.enabled` | 14 F1b | F1a (`block-count-stamp`) soaked through refresh #3 of `research/a3289005/`. Four of eleven new papers carried `(no data — call_N missing)` markers — P-a328-526 (5 blocks, 2 calls), P-a328-533 (5 blocks, 3 calls), P-a328-540 (5 blocks, 1 call), P-a328-548 (5 blocks, 1 call). The 4-of-11 (~36%) rate tracks refresh #2's ~6-of-20 (~30%) baseline within the ±2 trigger window. None of the four looked like a legitimate sweep-narration that would over-fire the gate. |

After this flip the upper bound on acceptance lift cited in refresh #3's
report (Stub-block fix → +5-10%) becomes realised whenever a new
generation run produces stub-block candidates.

---

## D4 — panel-degradation-demote

**Phase:** 12 D4. **Current:** `app.research.panel-degradation-demote.enabled:
false`. **File:** `src/main/resources/application.yml:453`.

**What flipping it does:** Papers whose panel review degraded to n=2 or
n=3 of the configured 4 reviewers demote from PUBLISHED to NEEDS_REVISION.
The n=1 suppression branch is unchanged (single-reviewer agreement is
non-informative; suppress outright) regardless of this flag.

**Trigger condition:** Operator confirms (a) panel agreement scores on
*full* (n=4) panels are stable across at least one REVIEW_REPORT refresh,
and (b) the corpus has ≥30 PUBLISHED papers with degraded panels (n=2 or
n=3) so the false-positive rate can be measured. Refresh #2's 20-paper
window is below that threshold.

**Risk:** Medium. Demoting a paper changes its status from PUBLISHED to
NEEDS_REVISION; the paper is still on disk and queryable but no longer
counts as accepted. Reversible by flipping the flag back to false (the
demoted-paper status is computed each publish; rerunning the corpus
produces fresh statuses).

**Acceptance:** After flip, every degraded-panel paper's persisted
call_path contains `:degraded:demoted_to_needs_revision` and its
`paper.status == "NEEDS_REVISION"`. Acceptance rate lift is measured
against the next refresh's full-panel-only subset (degraded papers are
no longer counted as accepted, so the headline acceptance percentage
*tightens* even if the underlying paper-quality distribution is
unchanged).

---

## F7.3 — audit-cherry-picked-rejects

**Phase:** 7 F7.3. **Current:**
`app.research.audit-cherry-picked-rejects.enabled: false`. **File:**
`src/main/resources/application.yml:461`.

**What flipping it does:** SUPPORTED papers whose panel review surfaced
the `cherry_picked_evidence` audit-gap dimension (≥2 reviewers flagged
the same omitted-relevant tool call) publish as REJECTED instead of
PUBLISHED. NULL_RESULT / PARTIALLY_SUPPORTED papers are unaffected.

**Trigger condition (revised 2026-05-17):** ≥10 PUBLISHED + SUPPORTED
papers carrying the `cherry_picked_evidence` dimension, with operator
spot-check of the FP rate. The original ≥30 threshold (mirrored from
the earlier `audit-physical-mechanism-rejects` rollout, Phase 7 G8) was
chosen before any corpus evidence existed. Refresh #3 (21 papers, 10
SUPPORTED, 5 SUPPORTED+cherry firings — P-a328-519, 526, 533, 534, 539
— all five judged true positives in REVIEW_REPORT refresh #3) gave a
0/5 observed FP rate. With nothing in the 5-firing sample looking like
a false positive, the conservative 30-paper threshold over-defers the
flip; tightening to ≥10 keeps the discipline of having a measurable
baseline but stops waiting once the signal is clearly true-positive.
Revert to ≥30 if any of the next five firings spot-check as a false
positive.

**Risk:** Medium. REJECTED is a stronger downgrade than NEEDS_REVISION —
the paper doesn't get a revision pass. Mitigation: F7.3 only fires on
SUPPORTED (high-confidence) papers, and only when ≥2 reviewers
independently agreed on the omission. Confirmed 0/5 FP rate in refresh
#3 is the empirical safety margin.

**Acceptance:** After flip, no SUPPORTED paper carrying
`auditGapDimensions.contains("cherry_picked_evidence")` publishes as
PUBLISHED; each routes to REJECTED with the call-path stamp
`audit:cherry_picked_evidence→REJECTED`.

---

## D2 — numerical-consistency mode escalation

**Phase:** 12 D2. **Current:** `app.research.numerical-consistency.mode:
warn`. **File:** `src/main/resources/application.yml:329`.

**What flipping it does:** Mode escalation from `warn` (log + stamp,
never suppress) through `downgrade` (rewrite findings + conclusion to a
NULL_RESULT preamble naming the unverifiable values) to `suppress` (drop
the paper entirely — no addPaper, no disk write, no DB row).

**Trigger condition (warn → downgrade):** Operator reviews the
`numeric_inconsistent` stamp on the call_path across two consecutive
REVIEW_REPORT refreshes and confirms (a) the stamp fires on the
documented failure patterns (P-a328-14 close-to-N×, P-a328-50/61
hypothesis-vs-conclusion coord mismatch, P-a328-60 Conclusion-off-grid),
and (b) doesn't false-positive on legitimate "approximately"
phrasings within the 20% / 50% tolerance.

**Trigger condition (downgrade → suppress):** After ≥1 refresh with
mode=downgrade where the rewritten NULL_RESULT preambles are reviewed
for plausibility — i.e. the validator named the *right* unverifiable
values.

**Risk:** High. `suppress` mode drops papers entirely; reversing the
decision requires re-running the bots. Each escalation step is
operator-gated, not automatic.

**Acceptance (warn → downgrade):** Papers stamped
`numeric_inconsistent` now also carry NULL_RESULT preambles starting
with the standard "The hypothesis remains untested in this submission"
language, and `paper.status == "NULL_RESULT"`.
**Acceptance (downgrade → suppress):** Stamped papers no longer
appear in the corpus at all; the suppression marker shows in the
operational log only.

---

## C5b — citations-as-schema mode escalation

**Phase:** 11 C5b. **Current:** `app.research.citations-as-schema.mode:
warn`. **File:** `src/main/resources/application.yml:414`.

**What flipping it does:** Same shape as D2: mode escalation from `warn`
through `downgrade` (rewrite findings + conclusion to a NULL_RESULT
preamble naming the unmatched / out-of-tolerance citations) to
`suppress` (drop the paper, like Phase 10's
`all_tools_failed→suppressed` gate).

**Trigger condition (warn → downgrade):** Two consecutive REVIEW_REPORT
refreshes with stable `citations:uncited=N:unmatched=M:out_of_tolerance=K`
distributions, and operator review confirms the validator's
characterisations are accurate. Refresh #2 P-506 carrying
`citations:out_of_tolerance=1` is a known example.

**Trigger condition (downgrade → suppress):** ≥1 refresh with
mode=downgrade where the NULL_RESULT preambles are reviewed; same
pattern as D2.

**Risk:** High. Same reasoning as D2 — `suppress` is irreversible per
paper.

**Acceptance:** Mirrors D2's table — first the preamble appears, then
the paper drops entirely.

---

## Known validator FPs blocking D2 escalation

**Status (2026-05-17):** Items #1-#3 below shipped in commit `f6b89b9`
(Phase 16 step 1). Items #4-#7 in the "Coverage gaps" subsection also
shipped in the same commit. The validator now emits the softer reasons
in `warn` mode; D2's warn → downgrade escalation still gates on a
post-fix corpus re-run confirming the FP rate drops below ~5%. Re-run
is pending the next active generation run (paper pipeline has been
idle since 2026-05-17 04:04 EDT — Groq daily budget exhaustion, see
STATUS.md Phase 15).

Recorded 2026-05-17 from refresh #3 of `research/a3289005/`. The
`numerical-consistency` validator (D2, currently `warn`) fired on 16 of
21 papers. 13 firings are clear true positives. The list below names the
known false-positive patterns that need a validator fix *before* D2
escalates to `downgrade`, because `downgrade` mode rewrites the paper
into a NULL_RESULT preamble — an FP at `downgrade` produces a visibly
broken paper, whereas an FP at `warn` is log-only.

1. **Negative-number extraction from tool-result strings (P-a328-555).**
   The tool returned `Mean of collatzSteps over 7 matching = 35 ± 13
   (SE, n=7) (max=110, baseline 50.000, deviation -15.286)`. The
   validator extracted `35`, `13`, `110`, `50.000` from the trace
   result but missed `-15.286`, then flagged the Findings citation of
   `15.286` as fabricated. The number is literally in the trace; the
   tokeniser is failing on sign-prefixed numbers (or possibly stripping
   the dash and looking for `15.286` whose nearest match in the
   tokenised set is too far away). Fix: in
   `NumericalConsistencyChecker`, ensure the trace-result tokeniser
   captures the leading sign character (or treats `-X` and `X` as the
   same match candidate).

2. **Off-by-one `[call_N]` numbering vs. trace indexing (P-a328-516).**
   Prose cites `[call_0]`; trace contains calls 1-6. The validator
   correctly reports `[call_0]` is missing, but the substantive issue
   is a labelling off-by-one — the prose meant call_1. Not a fix for
   the validator; this one is the narrator's bug, but it inflates D2's
   apparent error rate. Either tighten the narrator's call-numbering
   convention to always 1-base, or have the validator treat `[call_0]`
   on a 1-based trace as a likely labelling drift and downgrade the
   severity from `salient_value_unsourced` to `labelling_drift`.

3. **Boundary-style percentages cited without a tool source (P-a328-526
   `50 %`).** Prose discusses a 50% efficiency reference point that is
   never derived from a tool — it's used as a target framing, not a
   computed value. The validator can't distinguish a "framing" number
   from a "computed" number. Lower-priority than #1 — flag with an
   `unsourced_framing_value` severity rather than `salient_value_unsourced`.

Once #1 is fixed and re-run on the existing corpus drops the FP rate
below ~5% (currently ~6-19% depending on how the marginal cases are
counted), D2 is safe to flip warn → downgrade. Track this in
[phase-wishlist.md](phase-wishlist.md) under a `D2-prep` item.

### Coverage gaps to ship alongside the FP fixes

Recorded 2026-05-17 from REVIEW_REPORT refreshes #1-#4 of
`research/a3289005/`. These four checks live in the same validator file
(`NumericalConsistencyChecker`) as the FPs above and can ship in one
commit, but they're net-new coverage — the validator does not fire on
these patterns today. Independent of D2's mode escalation: each
strengthens what `warn` *catches*, not whether it suppresses.

4. **`[call_N]` resolves but the call failed.** Today the validator
   checks `[call_N]` against `trace.size()` only. It does not check
   whether `trace[N-1].status == ok`. Affected papers: P-a328-519 (cites
   `[call_2]`/`[call_3]` whose results were "REJECTED by safety review"),
   P-a328-532 (`[call_2]` rejected, cited), P-a328-539 (`[call_3]`/
   `[call_4]` rejected, cited), P-a328-543 (`[call_1]`/`[call_2]` failed,
   cited instead of successful call_3/call_5). Refresh #3 noted the
   warn-mode stamp already flags some of these as "numerics tied to this
   marker are unsourced" — the check needs to be unconditional. Severity:
   `unsourced_failed_call` (high — pure fabrication signature).

5. **One-sided CI tightening.** Prose cites `± X` where `X` is *smaller*
   than the matching `± Y` in the tool trace. Tightening uncertainty is
   the fabrication direction; widening is fine. Affected: P-a328-528
   (prose `±0.02`, tool `±0.07` — a 71% reduction), P-a328-521 (similar
   pattern, prose narrows the CI to make the deviation look significant).
   Design: extract every `± X` pattern from Findings; for each, find the
   nearest `± Y` in the tool trace with a matching central value; flag
   when `X < Y × (1 − tolerance)`. Severity: `ci_shrinkage`.

6. **Hypothesis-vs-executed-range scope mismatch.** Prose claims results
   over a range that is not a subset of the union of executed
   `range_start, range_end` arguments. Affected: P-a328-547 (Hypothesis
   says "first ten thousand positive integers", Findings claims scans
   over `[1,5000)` and `[5000,10000)` that were never executed; only
   `[85,110)` actually ran). Design: parse `[X, Y)` and similar interval
   notations from Hypothesis + Findings; compare against the executed-
   ranges union extracted from the tool trace; flag when a prose interval
   is not contained in the executed set within tolerance. Severity:
   `scope_overreach`.

7. **Inline `Computation N` stub in Findings prose.** F1b's
   `block-count-guard` (flipped on in commit `918ac90`) catches stub
   blocks that appear as separate `### Computation N` markdown blocks.
   It does *not* catch stubs embedded inside a Findings paragraph as a
   sentence. Affected: P-a328-553 — "*Computation 3 — Testing a scale
   closer to the starting point (44) with a slightly smaller scale may
   expose deviations from the asymptotic trend.*" That phrase is inside
   the Findings prose, so `findingsBlocks.size()` does not increment
   and F1b lets it through. Design: regex-match `\bComputation [0-9]+
   —` anywhere in Findings prose; count occurrences against
   `trace.size()` the same way F1b does; route to NEEDS_REVISION when
   mentions exceed successes. Severity: `inline_computation_stub`.

---

## What this doc deliberately does NOT track

- **Operational flags** (token budgets, HTTP timeouts, model registry
  selection) — these are runtime tuning, not rollout decisions.
- **Phase wishlist items** (sandbox grammar relaxation, schema-pinned
  reviewer prompts, fit_curve tool) — those need *code*, not just a
  flag flip. See [phase-wishlist.md](phase-wishlist.md). The
  sandbox-grammar-relaxation item (wishlist P2 Layer 1) is the
  highest-leverage one named in Phase 14's "deliberately does NOT
  change" section and is a candidate for its own scoping doc once the
  Phase 14 rollout settles.
- **Already-on flags** — once a flag is on in `application.yml` it
  leaves this queue.

---

## How to update this doc

When a refresh confirms a trigger condition:

1. Edit the flag in `application.yml` (set `enabled: true` or escalate
   `mode:`).
2. Update this doc's row in the Snapshot table — strike through or
   delete the row entirely.
3. Move the per-flag section to a "Recently flipped" log section (or
   delete if the change is self-evident from `git log application.yml`).
4. Commit with a `config:` prefix matching `041671a`'s style.

When a new flag joins the queue (a future phase ships a default-off
validator), add it to the table + a per-flag section. Future Phase 15+
deferred flips arrive this way.
