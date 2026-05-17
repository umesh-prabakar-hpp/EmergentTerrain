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

---

## Snapshot

| Tier | Flag | Phase | Current | Trigger | Risk |
|------|------|-------|---------|---------|------|
| 2 | `app.research.block-count-guard.enabled` | 14 F1b | off | One refresh of F1a stamp-mode observation | Medium — status-changing |
| 3 | `app.research.panel-degradation-demote.enabled` | 12 D4 | off | F7.2 panel agreement scores stable + ≥30 papers | Medium — status-changing |
| 3 | `app.research.audit-cherry-picked-rejects.enabled` | 7 F7.3 | off | False-positive rate measured over ≥30 SUPPORTED papers | Medium — status-changing |
| 3 | `app.research.numerical-consistency.mode` | 12 D2 | `warn` | Operator review of warn-mode suppress markers | High — flips structural validator from log-only to enforcing |
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

## F1b — block-count-guard

**Phase:** 14 F1b. **Current:** `app.research.block-count-guard.enabled: false`.
**File:** `src/main/resources/application.yml:425`.

**What flipping it does:** Papers whose `findingsBlocks.size() > max(1,
toolCallTrace.size())` publish as NEEDS_REVISION instead of PUBLISHED.
The Five-Computation stub-block fabrication cluster (6 of 20 papers in
refresh #2 — P-a328-480/485/489/492/502/503) is the primary target.

**Trigger condition:** **One REVIEW_REPORT refresh after the
`block-count-stamp` (F1a) flag soaked.** F1a is observability-only —
markdown renders `### Computation N … `[call_N]`` for blocks with a
matching trace entry and `(no data — call_N missing)` for blocks
beyond `trace.size()`. Once refresh #3 (the first after F1a flipped
ON in commit `041671a`) is published:

- Count papers carrying `(no data — call_N missing)` markers. If the
  count tracks refresh #2's ~6 of 20 (±2), F1a's heuristic is calibrated
  and F1b is safe to flip.
- If the count is substantially higher (say ≥12 of 20), F1b would
  over-fire — investigate whether legitimate sweep-style narrations are
  being mis-flagged before flipping.
- If the count is substantially lower (say ≤2 of 20), the narrator has
  adapted and F1b's gain is marginal — flip anyway (low cost) but the
  acceptance-rate lift will be smaller than projected.

**Risk:** Medium. The gate is publish-time NEEDS_REVISION routing, not
permanent paper loss. Mitigation: F1a already shipped on (commit
`041671a`); flag-off-then-on is one yaml edit.

**Acceptance:** After flip, zero published papers carry a
`### Computation K — …` block with a `(no data — call_K missing)`
marker; all such papers route to NEEDS_REVISION with the call-path
stamp `publish:block_count_overshoot:blocks=N:successes=M→needs_revision`.

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

**Trigger condition:** ≥30 PUBLISHED + SUPPORTED papers in the corpus
that ran through the multi-reviewer panel, with the
`cherry_picked_evidence` dimension counted and false-positive-spot-
checked. Mirrors the rollout discipline used for the earlier
`audit-physical-mechanism-rejects` gate (Phase 7 G8, now default ON).

**Risk:** Medium. REJECTED is a stronger downgrade than NEEDS_REVISION —
the paper doesn't get a revision pass. Mitigation: F7.3 only fires on
SUPPORTED (high-confidence) papers, and only when ≥2 reviewers
independently agreed on the omission.

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
