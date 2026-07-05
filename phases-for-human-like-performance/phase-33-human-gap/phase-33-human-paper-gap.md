# Phase 33 — The human-paper gap: five lanes from "excellent memo" to "paper"

**Status: DESIGN DOC (2026-07-05). Nothing below is built.** Successor program to the closed
open-behavior activation (G1–G5, archived 2026-07-05) and the live §32.5 grounding. Those programs
fixed *honesty, instrumentation, and novelty scoring*; the corpus now produces short empirical
memos with referee-grade epistemics (peer 0.833 / novelty 0.82 records; three consecutive
zero-Major/Reject batches; the full measure→anchor→tighten→refute→publish loop demonstrated live
in P-b72c-77627784). This phase names what still separates those memos from a paper a working
mathematician would sign, and stages concrete steps for each lane.

**North-star discipline** ([[project_scientist_qualities_northstar]]): every lane is framed as a
capability the *system* gains, not a score to inflate. Per [[feedback_flag_gated_dormant]], every
step ships flag-OFF/dormant with a measurement-framed acceptance bar; pipeline-wide wording stays
domain-neutral ([[feedback_common_code_domain_neutral]]).

**Evidence base (what "current papers" means).** Runs 17–24, rooms b72c4e10: P-774/783 (held-out
growth anchor, k(x) discovery), P-784 (first demonstrated binding regime), P-786 (instrument-
critique hypothesis), P-788 (self-withdrawn claim, near-binding at relative tolerance), P-780
(uncharted-density publication 0.806/0.76). These are the *best* outputs; the gaps below are what
even they lack.

---

## The five lanes

| # | Lane | One-line gap statement | Cost class |
|---|------|------------------------|------------|
| T | Taste | The system executes questions superbly but does not CHOOSE them — its two best open questions (k(x) rate, 2C₂ deficit rate) were operator-injected | Small |
| D | Depth | Five tool rounds produce a single-question memo; a paper develops an argument (P-788 lost its milestone to round exhaustion) | Small–Medium |
| L | Literature | Grounding retrieves abstracts; a human READS the prior paper and positions against its actual content | Medium |
| C | Cumulativity | Rooms reset; write-back is one hop; a human runs a multi-paper program | Medium |
| R | Derivation | The system measures and fits; a mathematician derives — mechanism sections are heuristic prose with no machine-checked step | Large (the long pole) |

Sequencing rationale: **T and D are cheap and compounding** (better questions × deeper execution
multiplies every later lane), **L and C build on live infrastructure** (§32.5 corpus, phase-31
memory core loop), **R is the research-grade bet** and is staged so its early steps pay for
themselves even if the later ones stall.

```
T1 question harvester ──> T2 question ledger serving ──┐
D1 adaptive rounds ────────────────────────────────────┼──> C2 program synthesis
L1 full-text cores ──> L2 read_reference tool ──> L3 grounded related-work
C1 verified-results ledger ────────────────────────────┘
R1 derivation obligations ──> R2 truncation-check tool ──> R3 identity-prover integration
```

---

## Lane T — Taste: the system should serve its own questions

**Gap evidence.** P-774 itself posed "whether k drifts toward the Cramér asymptotic value 1.0 at
larger x was not tested" — the sharpest question in the room — and no machinery ever routed it
back into topic selection; it took an operator directive (the twin_vs `secondOrder` field,
2026-07-05) to register the sibling question. The system *generates* taste; it discards it.

### T1 — Open-question harvester *(the single highest-leverage small step in this phase)*

Papers already emit structured self-critique: "remains untested", "the open question is", "would
be needed to". Harvest them at publish time — a `PaperOpenQuestion` entity per paper:
`(paperId, lhs-family, question text, kind: RATE|BINDING|SENSITIVITY|SCOPE, params)` — extracted
by a cheap LLM pass over the Conclusion/Adversarial sections (fast-audit pool, same pattern as
`runQualityAudit`). Persist outside the room (survives resets, like the conjecture pool).
*Flag:* `app.research.question-harvest.enabled`. *Acceptance:* after one session the ledger
contains ≥1 harvested question per substantive paper, with the k(x)-drift class correctly typed
RATE. *Effort:* S.

### T2 — Question-ledger serving

Extend topic selection (the `selectPromotedAnomaly` → `selectConjecture` chain in
`ResearchCycleService`) with a third source: `selectOpenQuestion` — serve the highest-ranked
unanswered ledger question for the least-recently-served family, rendered as a directive the way
`MeasurementSpec.secondOrder` is today (that mechanism is the hand-built prototype of this lane).
Rank v1 = recency + family staleness; no ML. Mark answered when a later paper's trace covers the
question's params (same coverage predicate family as `ScoreCalibration.hypothesisLhsUncovered`).
*Flag:* `question-serve.enabled` + `paperShare`-style probability. *Acceptance:* one run serves a
question harvested from a prior run's paper — unprompted by the operator — and the resulting
paper answers or narrows it. *Effort:* M. *Depends:* T1.

### T3 — Interestingness prior *(v2, deferred until T2 has data)*

Rank questions by expected surprise: prefer questions where the miner's fitted model and the
registered asymptotic DISAGREE (e.g. measured drift against a conjectured constant). Explicitly
NOT an LLM "rate how interesting" call — use the disagreement signal already in the pool
(confirmedRuns vs refutation history vs registered targets). *Trigger to build:* T2 serves ≥5
questions and their novelty spread is flat (i.e. ranking would have mattered). *Effort:* M.

---

## Lane D — Depth: from memo to developed argument

**Gap evidence.** P-788 reached a near-binding hold (+0.00002 vs threshold 0.00026) and could not
spend the one further call that completes the binding demonstration — 2 of its 5 rounds were
burned on cap misfires. P-774's paper posed its own follow-up in the same trace that had no
budget to run it. The 5-round cap is calibrated for memos.

### D1 — Adaptive round extension on a live result

When the trace at round-exhaustion contains an INCOMPLETE high-value state — machine-detectable:
a HELD bound with rel-slack < 0.01 lacking its REFUTED pair, or a REFUTED lacking its tight HOLD
(the two halves of the binding protocol) — grant +2 rounds, once, with the extension reason
injected ("you are 1–2 calls from a demonstrated binding regime; complete the pair"). Detection
is string-free: inspect the `VerifyResult`s already accumulated in the professor loop.
*Flag:* `professor.extra-rounds-on-binding.enabled`, cap `+2`, once per paper.
*Acceptance:* a paper that would have ended near-binding completes the pair in the same run
(P-788's exact shape); median rounds/paper rises by <1 (the extension is rare, not a budget
creep). *Effort:* S.

### D2 — Follow-up chaining inside a run

A published paper whose harvested question (T1) targets its OWN family may trigger one chained
follow-up investigation in the same run (bounded: ≤1 chain per run), reusing the gate-retry queue
mechanics. This converts "the next paper is one rotation away" (days) into "the next paper is
this run" for the highest-value threads. *Flag:* `question-chain.enabled`. *Acceptance:* a
chained pair publishes where paper 2's hypothesis is paper 1's open question; combined novelty of
the pair exceeds either alone. *Effort:* M. *Depends:* T1; T2 recommended first.

### D3 — Full-paper section contract for promotion-track papers *(deferred)*

Multi-section development (motivation → prior work → method → results → limitations → outlook)
only for papers that clear a peer threshold on the memo form — depth is expensive; spend it on
winners. *Trigger:* D1+D2 live and a Q5-grade result exists to write up. *Effort:* M.

---

## Lane L — Literature: from retrieval to reading

**Gap evidence.** P-765 correctly cited De Koninck (1974) — from a 300-char abstract snippet. No
paper has ever engaged the CONTENT of a prior work (its theorem statement, its constant, its
range of validity). The §32.5 corpus is abstracts-only by design (the full-text core was spec'd
as addendum item B2 and deferred).

### L1 — Full-text cores for the top-cited neighborhood

Execute the deferred B2 slice: 300–500 OA full texts (TEI/plain) for the works nearest the live
families (divisor problem, prime gaps, Erdős–Kac, twin constants), fetched once via the existing
`OpenAlexClient` + ledger (≤$5 one-shot, cached like the abstract corpus). Store beside
`corpus-nt/` with a manifest. *Acceptance:* corpus manifest lists ≥300 full texts; zero recurring
spend. *Effort:* S–M (mostly the fetch/format plumbing).

### L2 — `read_reference` professor tool

When grounding returns EVIDENCE (or the professor cites a key), a new tool lets it pull the
work's abstract + (when L1 has it) sectioned full text, chunked to a token budget. This is the
difference between "a match exists" and "the prior result states θ ≤ 131/416 for the divisor
exponent — our window measurement is consistent with but far weaker than it". Same
failure-soft/caller-policy pattern as `GroundingClient`. *Flag:* `literature.read-reference.enabled`.
*Acceptance:* a paper quotes a specific claim from a referenced work and positions its own result
against it, and the panel's novelty rationale cites that positioning. *Effort:* M. *Depends:* L1
for full text (works degraded on abstracts alone).

### L3 — Grounded related-work paragraph

The narration contract gains a related-work obligation for grounded papers: ≥1 paragraph
situating the result against 2–3 provided keys (cite-only-provided-keys already enforced), with
the §32.6 reference-position check (citations may not appear after Related Work) shipped as the
validator one-liner it was always scoped to be. *Acceptance:* zero fabricated cites (unchanged)
AND panel Completeness on grounded papers does not drop; §32.6 items 2–3 close. *Effort:* S.

---

## Lane C — Cumulativity: from papers to a program

**Gap evidence.** The k(x) data points now span three archived rooms; no live mechanism can see
all three (rooms reset; the pool holds the bounds but not the papers' measured trajectories).
The synthesis family refreshes on source count, not on programmatic intent.

### C1 — Verified-results ledger

A room-independent store of VERIFIED numerical facts with provenance:
`(family, statement-shape, value(s), scale/range, paperId, run date, verdict class)` — written at
publish for results that survived the gates (binding pairs, converged constants, ladder
trajectories). This is the memory the k(x) fit needs: three tight-k values at three scales,
queryable. Renders into professor context via the existing memory-recall channel
(phase-31 core loop, SHIPPED+ON — this is its structured sibling). *Flag:*
`research.results-ledger.enabled`. *Acceptance:* after one session the ledger holds every
published binding pair and ladder trajectory; a professor recall for a family returns them.
*Effort:* M.

### C2 — Program synthesis papers

Upgrade family synthesis from "≥5 new sources → refresh" to a PROGRAM review: when a family's
ledger holds ≥3 rate-class data points (e.g. k(x) at three scales), synthesize the cross-paper
quantitative fit — the paper that actually computes 1−k(x) against c/ln x from C1 data — and
emit next questions into the T1 ledger. This closes the loop: papers → results → questions →
papers. *Flag:* rides `reSynthesis` config. *Acceptance:* the k(x) synthesis paper exists with a
fitted rate and its residual question harvested. *Effort:* M. *Depends:* C1 (+T1 for the loop).

---

## Lane R — Derivation: the long pole

**Gap evidence.** P-774's mechanism honestly labels itself "post-hoc heuristic justification";
P-786's Background states the divisor theorem correctly but nothing CHECKS the stated
asymptotics against the claimed form. Nothing in the pipeline can distinguish a correct
derivation from fluent prose — including the panel.

Staged so each step is useful standalone:

### R1 — Derivation obligations on mechanism claims

A mechanism section that asserts a quantitative asymptotic ("scales as ln²x", "tends to 2γ")
must carry either (a) a `run_symbolic` call deriving the claimed form on a truncation, or (b) an
explicit "heuristic — not derived" label (many already self-label; this makes it a checked
contract, audit-4b dimension `underived_mechanism`, warn-first). Builds directly on
`mechanism-runnable-model` (shipped 2026-06-03). *Acceptance:* zero papers assert a quantitative
mechanism without either the call or the label; no acceptance-rate drop. *Effort:* S.

### R2 — Truncation-check tool (`derive_check`)

A deterministic tool: given a claimed identity/asymptotic over the vocabulary (e.g.
`mean_divisor_excess → 2γ` via `Σ_{n≤x} d(n) = x ln x + (2γ−1)x + O(√x)`), evaluate BOTH sides
exactly on geometric truncations and report the error trajectory — a derivation-shaped check
that is pure computation (sidecar-backed, mirrors rule applies). It cannot prove, but it can
REFUTE a wrong derivation and quantify agreement, which is what the panel needs to grade Rigor
above ~0.6. *Flag:* tool-advertise flag like verify_*. *Acceptance:* a paper's mechanism section
carries a derive_check trajectory; panel Rigor on such papers exceeds the family baseline.
*Effort:* M.

### R3 — Identity-prover integration *(the bet; do not start before R1+R2 are live)*

Revive the phase-25 SymPy identity-prover lane for the NARROW class that matters here: finite
sum/product manipulations and elementary asymptotic algebra (the steps between a textbook
theorem and the window-statistic form). Scope discipline: this is machine-checked ALGEBRA, not
theorem proving; anything outside the class degrades to R2's numeric check. *Trigger to build:*
R2 live and ≥3 papers whose Rigor was capped by an uncheckable derivation step. *Effort:* L.

---

## What this phase does NOT attempt

- **Proof-grade novelty** (new theorems): out of scope for the current architecture; R-lane
  ceilings at machine-checked algebra + refutable derivations.
- **Sieve/substrate reach** (dirichlet hyperbola oracle, >2e9 windows): tracked separately in
  PENDING.md — an enabling computation, not a paper-quality lane.
- **Model upgrades**: all lanes are harness capabilities, deliberately narrator-model-agnostic
  ([[project_narrator_model_time_confound]]).

## Closing condition

This phase closes when a single paper exists that simultaneously: answers a question the SYSTEM
harvested and served (T), completed within-run to a demonstrated result (D), engages the content
of at least one prior work (L), builds quantitatively on results from ≥2 earlier runs (C), and
carries a machine-checked derivation step or an honest checked-heuristic label for every
quantitative mechanism claim (R) — i.e., one paper produced the way a human produces one. Interim
per-lane bars are stated inline above; per-stage progress tracked here.

## Cross-references

- Predecessors: [`../archive/open-behavior-generation-activation.md`](../archive/open-behavior-generation-activation.md)
  (generation), `phase-32-manuscript-grounding.md` §32.5 (grounding), phase-31 README (memory core loop).
- Prototype for T2: `ScalarOpenness.MeasurementSpec.secondOrder` (the twin_vs deficit directive).
- Memory: [[project_novelty_ceiling_moved]] (the run 17–24 evidence base),
  [[project_second_order_novelty]], [[project_scientist_qualities_northstar]].
