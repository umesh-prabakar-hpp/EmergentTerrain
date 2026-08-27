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

### Stage A result (2026-06-16 run 2) — read off the histogram

Stable across every mine pass: **`{fails_null=32, explained_by_existing=20, below_description_cost=8}`**
(0 admitted, budget 8). Not purely Fork A: ~half the depth-1 candidates are noise (`fails_null`), a
third are redundant with existing vocab (`explained_by_existing`), and **8/60 carry genuine incremental
structure missing only the description-cost margin** (`below_description_cost` — the addressable Fork-B
tail). So there IS a tail to pursue — Stage B/C are warranted.

Important refinement this read forces: the `ResidualStructureMiner` bounds are exactly the
`explained_by_existing` ones (the miner *finds* an existing scalar `z` that explains the residual), so
routing them as vocab targets is counterproductive. The lever is **open-object** targeting, where
uncaptured structure is plausible.

### Stage B — bias targets toward open objects — SHIPPED 2026-06-16

`ConjectureMinerService` vocab-expansion now orders the MDL target set by
`ScalarOpenness.selectionRank` (open-priority lhs first) before `confirmedRuns`, so the openness-priority
objects (`dirichlet_error`, `max_prime_gap`, `max_divisor_count`, …) lead instead of the most-confirmed
SETTLED bounds (whose residuals are already-captured). Same order applied to the anomaly-stratum
fallback. The vocab-expansion log now reports `{open}/{total} target(s) open-object`.

- **Flag:** folded under the existing `app.research.conjecture-miner.opennessSelectionEnabled` (OFF code
  / ON yaml) — semantically the same openness-biased selection; off = original confirmedRuns-desc order.
- **Acceptance (next run):** the log shows ≥1 open-object target, and the rejection histogram shifts —
  `explained_by_existing` should drop relative to run 2 if open-object residuals are less-explained by
  existing vocab. If it does NOT shift, the limiter is the description-cost margin → Stage C.

### Stage C — type-aware MDL description cost — SHIPPED 2026-06-16

Run 3 confirmed Stage B is inert (histogram target-invariant) ⇒ the limiter is candidate-side: the 8
`below_description_cost` candidates beat the null AND beat existing vocab but miss the MDL cost margin.
Root cause found in the cost code: `lExpr = nodeCount · log2(grammarSymbols≈31)` charges **operator**
nodes the full scalar+operator alphabet (~5 bits) when an operator has only 2–4 choices (1–2 bits) — a
~4-bit-per-op over-charge on exactly the compositions in the bucket.

Fix (`OperatorGrammar.Expr.descriptionBits`): a **type-aware** code — a leaf identifies one of
`leafAlphabet` scalars (`log2`), a unary/binary node identifies one of its own operator set
(`log2(4)` / `log2(2)`) plus its children. For a binary `a/b` that's ~10.3 bits vs the uniform ~14.85.
More accurate MDL, not an arbitrary threshold drop. Threaded `MdlGate.Params.typeAwareDescriptionCost`
→ `VocabularyExpansion` → the miner.

- **Why noise rejection is safe:** the cost is charged AFTER the permutation-null and held-out
  compression checks, so `fails_null` / `no_compression` / `explained_by_existing` are unaffected — only
  the `below_description_cost → admit` boundary moves. Unit test `typeAwareStillRejectsNoise` pins it.
- **Flag:** `app.research.conjecture-miner.vocabTypeAwareDescriptionCost` (OFF code / ON yaml).
- **Acceptance (next run):** the histogram's `below_description_cost` count drops (some cross to
  `admit`); any admitted scalar must pass the [`vocabulary-expansion-mdl.md`](vocabulary-expansion-mdl.md)
  §8 hand-check (legible ratio/log of base scalars, no spurious inflation). If `below_description_cost`
  drops but `admit` stays 0, the remaining gap is genuine (those candidates truly aren't worth a new
  scalar) → the honest conclusion is the corpus lacks novel structure, and the lever returns to
  open-object *generation* (G4), not the gate.

### RESULT (run 4, with observability) — vocab-expansion is a principled dead-end; pivot to G4

The decisive log line: `typeAwareCost=true; closest below-cost: 5.27 bits short (log(dirichlet_error));
histogram {fails_null=32, explained_by_existing=20, below_description_cost=8}`. Stage C is bound and
active, and the *best* candidate (the open object's log) misses admission by 5.27 bits even under the
corrected cost — it compresses only ~1.4 bits beyond existing vocab (~80% short). Depth-2 costs more
bits, so it won't cross a gap this large. **The MDL gate is now demonstrably correct and honestly
returns 0: no depth-1 composition over the 25 scalars is worth a new vocabulary entry on this settled
corpus.** Stages A→B→C have closed the vocab-expansion lever. Do not tune it further.

**The novelty lever is upstream — G4 (open-object generation), per Part 1 Layer 2:**
1. Wire `QualityDiversityArchive` into selection + retention (today referenced only by the triviality
   filter) — make novelty/openness an explicit reward axis.
2. Promote `AnomalyMiner` candidates to investigation topics — `anomalyPromotionEnabled` is ON but
   starved (the post-W4 pool rarely reaccumulates the ≥5-confirmed-then-broke anomalies it needs).
3. The substrate truth: you cannot manufacture novelty by recombining settled scalars. The corpus needs
   genuinely novel *objects/anomalies*, not new derived scalars from settled ones.

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
