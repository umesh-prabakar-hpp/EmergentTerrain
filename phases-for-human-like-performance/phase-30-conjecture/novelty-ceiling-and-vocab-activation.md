# Phase 30 — the novelty ceiling, and activating vocabulary-expansion

**Status: FINDINGS + SCOPE (2026-06-16).** Written off the room-b72c4e10 batch-1 review
(`research/b72c4e10/REVIEW_REPORT.md`). Part 1 records *why* every paper plateaus at panel-novelty
0.20–0.35. Part 2 scopes the highest-leverage unblock — vocabulary expansion — and **corrects a stale
assumption**: the live-wiring is already done; the gap is an *inert admission gate*, not missing plumbing.
Diagnosis-first, because the evidence forks two ways and only instrumentation tells us which.

Sibling of [`open-behavior-generation-activation.md`](open-behavior-generation-activation.md) (the G1–G5
program); this doc is the batch-1-grounded follow-up focused on the binding substrate blocker.
See [[project_phase23_8_calibration]], [[project_open_behavior_generation]].

---

## Part 1 — Why the novelty ceiling holds (for *all* papers)

Batch 1 was 4 conjecture-investigation papers (1 PUBLISHED, 2 NEEDS_REVISION, 1 NULL_RESULT). Honesty
ran high (0.73–0.93), 3/4 were honest negative results. **Novelty was 0.20–0.35 across the board**, and
every "interesting" number was a *known constant rediscovered without proof* — `mean_divisor_excess → 2γ`,
intercept `1.7832 ≈ e^γ`, `twin_vs_prime_sq → 1.3203` (Hardy–Littlewood). The blockers stack in three
layers, plus one hard physical ceiling.

### Layer 1 — Substrate: what the system *can* conjecture (the binding constraint)

Every conjecture is an affine combination `lhs ≤ k·rhs + k2·rhs2 + c` over the ~25 scalars in
[`MathInvariants`](../../src/main/java/com/emergentterrain/service/MathInvariants.java). Genuinely novel
mathematics needs *new quantities*, not new linear combinations of existing ones — and:

- **The objects under study are classically settled.** Their leading-order behaviour is known; the
  scorer correctly rates rediscovery low.
- **The vocabulary is effectively frozen.** Vocabulary expansion exists and runs, but admits ~0 in
  practice (Part 2). So no new derived quantity ever enters the hypothesis space.

This is the binding blocker: even a perfect pipeline conjecturing affine relations among 25 settled
scalars produces rediscovery. *Novelty lives in the residual/exception* — but you cannot investigate a
residual you cannot **name as a new object**, and naming is exactly the step that is inert.

### Layer 2 — Pipeline: the available novelty isn't surfaced or rewarded

- **The surprise stratum is starved.** `AnomalyMiner` promotion is ON
  (`app.research.conjecture-miner.anomalyPromotionEnabled`), but it fires only on conjectures that held
  ≥ `anomalyMinConfirmed`(=5) runs then broke — which the post-W4 pool rarely reaccumulates.
- **`QualityDiversityArchive` is not wired into selection/retention** (referenced only by the triviality
  filter). Nothing pushes the corpus toward novelty/openness cells → it drifts to a confirmable-bound
  monoculture.
- **Quality gates suppress novelty.** The reward is "a bound that HELD." binding-regime, manifest,
  citations and well-posedness gates all push toward conservative, grounded, *known* claims — a
  genuinely novel claim reads to those gates as overclaim / unsourced / ill-posed. The revision loop is
  subtractive (the novelty-preservation rule exists precisely because critique sands away the boldest
  sentence). Quality and novelty are in tension; every shipped gate so far has optimised quality.

### Layer 3 — Measurement: the system can't reliably *recognise* novelty

No trustworthy novelty oracle. Panel-novelty is conservative (0.2–0.35); `FormulationService`
tuple-novelty produces 1.00 false positives (happy-number packing). Literature retrieval (Phase 30.4)
was justified to fix the FP rate but is moot until generation emits real candidates. So even if Layers
1–2 produced novelty, selection couldn't reward it over a non-sequitur.

### The hard ceiling underneath all of it

Verification is sieve-capped at x ≤ 2×10⁶ (frontier recall extends *values* to ~4×10¹², not
*structure*). Nothing can be **proven** — only measured. The maximum achievable novelty is *"a
computational measurement that constrains an open problem in a way not previously tabulated"* — the
Dirichlet `Δ(x)/x^{1/4} → O(1)` paper (P-b72c-77627633) is the existing landmark. Real but modest; the
system cannot produce a theorem. That is a substrate decision, not a bug.

### Priority of unblocks

1. **Vocabulary expansion → live-admitting** (Part 2) — the only path to *new quantities*; binding.
2. **`QualityDiversityArchive` into selection + retention** — make novelty an explicit reward axis.
3. **A trustworthy novelty oracle** (literature-retrieval embedding check) — so 1–2 can be selected for.

Honest expectation: all three move the *typical* paper from "rediscovery of a known constant (~0.3)" to
"novel derived relation / open-problem measurement (~0.5–0.6)." They do **not** break the no-proof
ceiling.

---

## Part 2 — Vocabulary-expansion live-wiring: scope

### Correction: the wiring is already done

The yaml comment (`application.yml` ~L2225, *"OBSERVABILITY ONLY — persisting them + wiring into
[generation] not done"*) and the `VocabularyExpansion` Javadoc (*"P3b needs persistence"*) are **stale**.
The full loop exists in
[`ConjectureMinerService.java:881–990`](../../src/main/java/com/emergentterrain/research/conjecture/ConjectureMinerService.java):

1. enumerate grammar candidates ([`OperatorGrammar`](../../src/main/java/com/emergentterrain/research/conjecture/OperatorGrammar.java)),
2. admit via [`VocabularyExpansion.admit`](../../src/main/java/com/emergentterrain/research/conjecture/VocabularyExpansion.java) →
   [`MdlGate`](../../src/main/java/com/emergentterrain/research/conjecture/MdlGate.java),
3. **persist** admitted scalars to the `derived_scalars` table (`DerivedScalarRepository.save`),
4. **seed** new conjectures that touch an admitted scalar (`derivedFrom="vocab:…"`),
5. `loadActiveAdmitted()` reconstructs them next pass; `materialize()` extracts them into every window —
   so vocab-referencing conjectures are re-evaluable across passes.

So this is **not** a plumbing task. Update those two stale comments as part of the work.

### The actual gap: the admission gate admits ~0

Batch-1 live evidence (`logs/et-app.log`):

```
vocabulary expansion — 60 candidate(s) probed, 0 admitted (budget 8), 0 new conjectures seeded
residual-structure discovery — probed 7 bound(s), 6 with residual structure beating the null, 0 promoted (two-term)
```

Budget is 8 (non-zero), targets exist, 60 candidates were probed — **the MDL gate rejected all 60**, and
`derived_scalars` has never received a row. The admit rule
([`MdlGate.java:106–124`](../../src/main/java/com/emergentterrain/research/conjecture/MdlGate.java)):

```
admit  ⇐  beatsNull  AND  incremental > 0  AND  netGainBits > 0
netGainBits = (compressionBits − bestExistingBits) − descriptionBits
```

A candidate must explain a target residual **more than the best existing scalar already does**, by
enough to pay its description cost. The cross-signal is the tell: `ResidualStructureMiner` finds 6/7
bounds *with residual structure beating the null* — i.e. the structure is real **but already explained
by an existing scalar** (those 6 are exactly the residuals a known companion term fits). On settled
objects, every residual is either noise or already-captured, so a *new* grammar scalar earns ~0 net bits
and the gate **correctly** admits 0.

### The fork (why this is diagnosis-first)

The "0 admitted" has two materially different causes, and we don't yet know which:

- **Fork A — correctly inert.** Existing vocabulary already explains the residuals (`bestExistingBits`
  dominates). Then vocab-expansion is *right* to admit 0, and the blocker is **upstream**: there are no
  unexplained residuals on settled objects. The fix is to feed it *open-object* residuals (downstream of
  G1/G4 openness selection), not to touch the gate.
- **Fork B — addressably starved.** Real unexplained structure exists but the candidate set can't
  express it (depth-1 grammar over base vocab only: unary `{log, sqrt, square, reciprocal}`, binary
  `{*, /}`, **no `+`/`−`**, `VOCAB_TARGET_CAP=3`), or the description-cost calibration is too strict.
  Then the fix is in the gate/grammar/target wiring.

We cannot tell A from B today because the miner logs only the aggregate `0 admitted`. So:

### Stage A — instrument the rejection reason (cheap, first, no behaviour change)

`MdlGate.Verdict` already carries a per-candidate `reason` string. Surface a histogram across the 60
candidates: `beats_null_fail | best_existing_dominates | net_gain_le_0 | domain_nan | admitted`. Log it
in the existing `vocabulary expansion — …` line. This single change tells us Fork A vs B.

- **Flag:** observability only; no new flag needed (rides `vocabularyExpansionEnabled`, already ON).
- **Acceptance:** one mine pass logs the histogram; we read off whether `best_existing_dominates` (Fork A)
  or `net_gain_le_0`/`beats_null_fail` with non-trivial compression (Fork B) is the dominant reason.

### Stage B — bridge G2 residuals → G3 targets (do if Stage A shows Fork B, or to bias toward open objects)

Today vocab-expansion targets the top-3 confirmed bounds (`VOCAB_TARGET_CAP=3`) plus an anomaly-stratum
fallback. But `ResidualStructureMiner` *already identified the 6 bounds with unexplained-beyond-null
structure* — those are the residuals most likely to need a new scalar. Route them as the targets, and
bias the set toward the openness-prior priority objects (`ScalarOpenness.PRIORITY_TARGETS`:
`dirichlet_error`, `max_prime_gap`, `max_divisor_count`, …) where genuine uncaptured structure is most
plausible.

- **Flag:** new `app.research.conjecture-miner.vocabTargetsFromResidualMiner` (OFF code / ON yaml).
- **Acceptance:** the vocab-expansion target set names ≥1 open-object residual flagged by G2; the
  rejection histogram (Stage A) shifts.

### Stage C — grammar / MDL levers (do ONLY if Stage A+B prove coverage or calibration is the limiter)

Conditional on evidence, not speculative:
- **Depth-2 for the highest-residual targets only** (cost-bounded by `SublinearGenerationBudget`): if
  Stage A shows candidates beat the null but `net_gain_le_0`, the residual may need a 2-op composition
  the depth-1 grammar can't render. Raise depth selectively, not globally.
- **Re-examine `descriptionBits`**: if `net_gain_le_0` is driven by description cost on candidates that
  otherwise compress well, recalibrate against the design-doc §8 noise battery so the noise-rejection
  guarantee is preserved.

- **Flag:** reuse `vocabularyExpansionDepth` (already a property) for the depth lever, gated to
  high-residual targets in code.
- **Acceptance:** vocab-expansion admits ≥1 scalar that **passes the
  [`vocabulary-expansion-mdl.md`](vocabulary-expansion-mdl.md) §8 hand-check** (no spurious inflation;
  battery-of-noise still rejected), a conjecture seeded from it surfaces as a paper, and the admitted
  scalar is legible (e.g. a ratio/log of two base scalars with a clear meaning).

### What success looks like (and what it does not)

End state: vocab-expansion admits a small number of MDL-justified, hand-legible derived scalars on
*open-object* residuals, and conjectures built from them reach papers. That lifts Layer-1 from "affine
combos of 25 settled scalars" to "relations among discovered quantities" — the only path to genuinely
new claims. It does **not** break the sieve-cap no-proof ceiling (Part 1); the prize remains a novel
*measurement*, not a theorem.

### Risk / convention

Per the flag-gated-dormant convention: each stage ships OFF in code / ON in yaml, acceptance bar = the
next-run review's rejection histogram (Stage A) and admitted-scalar hand-check (Stage C). The MdlGate
noise-rejection unit tests are the regression guard for any calibration change — do not loosen the gate
without re-running the §8 battery. Stage A is pure observability and can land immediately.
