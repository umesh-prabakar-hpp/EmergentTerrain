# Wave 5 — 30.5 vocabulary expansion via a closed operator grammar, MDL-gated

**Status (2026-06-03 shipped; flipped ON 2026-06-05): SHIPPED + flag ON — P1+P2+P3a+P3b complete; `vocabularyExpansionEnabled: true` for the live A/B (targets fall back to the anomaly stratum).** This is the last
open Wave-5 item and the one the proposal flags **"beyond human-level," highest-risk/highest-reward**.
Unlike every W0–W5 slice shipped so far — which *filter* or *score* over a fixed scalar set — 30.5
*expands the substrate vocabulary itself*: it invents new scalars. That makes it the first Phase-30
change that touches [`MathInvariants`](../../src/main/java/com/emergentterrain/research/conjecture/MathInvariants.java)
(the scalar registry), so it carries real blast radius (parity tests, pool explosion, interpretability).
The open decisions (G/M/I) were resolved on 2026-06-03 — see each "**Decision**" below and the resolved
summary at the end; the chosen shape is the **conservative-but-real** one that cannot quietly flood the
vocabulary (minimal multiplicative depth-1 grammar, residual-variance-bits MDL with mandatory held-out
confirmation, a separate table, non-recursive).

---

## 1. The idea

The conjecture engine mines bounds `lhs <= k*rhs + c` over a **fixed** vocabulary
([`MathInvariants.vocabulary()`](../../src/main/java/com/emergentterrain/research/conjecture/MathInvariants.java):
the 9 BASE scalars + 6 DERIVED). 30.5 asks: *what if a genuinely new quantity — not in the vocabulary —
is the one that makes a tight, real relation?* e.g. `log(mean_divisor_count)`,
`mertens_delta^2`, `prime_density / mean_omega`. Vocabulary expansion generates such candidate scalars
from a **closed operator grammar** over the existing ones, and admits a new scalar **only when it earns
its keep under MDL** — it must let the substrate describe the data more cheaply than the bits its own
expression costs. Otherwise it's noise dressed as discovery.

This is the open-ended-search half of Stage 1; 30.1/30.2/30.3 generate *relations* over fixed scalars,
30.5 generates the *scalars*.

---

## 2. The closed operator grammar (candidate generation)

A **closed** grammar: operators map existing scalar series to a new scalar series, so results are
themselves eligible operands (composition), bounded by a small depth to keep it finite.

- **Operands (depth 0):** the current `vocabulary()` scalars + a small constant set (e.g. `{1, 2}`) for
  affine forms. Window-param raw values (`_window_size`, `_window_start`) stay *out* (the harness-window
  scalars already wrap them; see the unit-consistency gate).
- **Unary operators:** `log` (guarded: operand > 0), `sqrt` (operand ≥ 0), `square`, `reciprocal`
  (guarded: |operand| > ε), `abs`. Domain-guarded — a window where the guard fails yields no value for
  that scalar that window (the miner already tolerates absent scalars).
- **Binary operators:** `+`, `-`, `*`, `/` (guarded denominator). Commutative ops emit one ordering only.
- **Depth bound:** ≤ 2 (a unary-of-binary or binary-of-unary at most). Depth is the primary explosion
  control; see §6.
- **Canonicalisation + dedup:** normalise expressions (sort commutative args, fold `square(sqrt(x))→x`,
  drop expressions numerically identical to an existing scalar via the near-identity Pearson check the
  engine already uses, `NEAR_IDENTITY_CORR`). Reuse `ConjectureTrivialityFilter` / the descriptor key so a
  generated scalar that collapses to an existing one is never admitted.
- **Dimension guard:** reuse the [`ConjectureUnitConsistencyFilter`](../../src/main/java/com/emergentterrain/research/conjecture/ConjectureUnitConsistencyFilter.java)
  class registry (bounded-NT vs harness-window) to reject category-error combinations up front (e.g.
  `density * window_magnitude`), trimming the candidate set before the expensive MDL test.

**Decision (2026-06-03).** **G1 — minimal multiplicative grammar:** unary `{log, sqrt, square,
reciprocal}`, binary `{*, /}`. Exclude `+`/`−` — the engine already fits `lhs ≤ k*rhs + c`, so affine
combinations are covered; `*` and `/` create the genuinely new products/ratios the affine form can't.
**G2 — depth 1 first** (~15 scalars × 6 ops ≈ 90 candidates), depth a config knob; depth 2 only after
depth-1 admissions prove real and bounded. **G3 — no constants:** `k,c` carry all affine freedom, so
grammar constants are redundant and only inflate the space.

---

## 3. The MDL gate (admission)

A candidate scalar `z = expr(...)` is admitted only if it **compresses the data more than it costs to
describe** — the minimum-description-length principle, which is what stops the grammar from manufacturing
overfit junk:

```
  net_gain_bits  =  compression_bits(z)  −  L(expr)     >   0   ⇒  admit
```

- **`L(expr)` — model cost.** Code length of the expression: `Σ over nodes log2(grammar_size)` (operators
  + operands), so deeper / more-complex scalars must earn proportionally more. A flat, per-node prior.
- **`compression_bits(z)` — data cost saved.** How much describing the substrate gets cheaper *because*
  `z` exists. Operationalised by **reusing 30.2**: run
  [`ResidualStructureMiner`](../../src/main/java/com/emergentterrain/research/conjecture/ResidualStructureMiner.java)
  with `z` as a candidate explainer across the confirmed bounds; if `z` explains residual structure **no
  existing scalar does** and **beats the W4 null**
  ([`ConjectureStochasticNull`](../../src/main/java/com/emergentterrain/research/conjecture/ConjectureStochasticNull.java)),
  the residual-variance reduction it buys converts to bits (`0.5*n*log2(var_before/var_after)` for a
  Gaussian residual code). A candidate that only re-explains structure an existing scalar already covers
  saves ~0 bits → rejected.
- **Family correction.** Many candidates are tested at once → reuse
  [`MultipleComparisonsCorrection`](../../src/main/java/com/emergentterrain/research/conjecture/MultipleComparisonsCorrection.java)
  over the candidate family so admission controls the false-discovery rate, not just per-candidate gain.
  This is why the roadmap notes "MDL needs the null" (Wave 4).

So 30.5's admission test is **literally 30.2 + a description-length budget** — the generative engine is
mostly already shipped; 30.5 adds the grammar that proposes `z` and the MDL ledger that prices it.

**Decision (2026-06-03).** **M1 — residual-variance → bits** is the admission currency
(`0.5*n*log2(var_before/var_after)`): continuous and principled, and it reuses `ResidualStructureMiner`'s
residuals directly. The "count of new MCC-surviving bounds" alternative is coarser/noisier — rejected.
**M2 — uniform** per-node prior (`log2(grammar_size)`): no operator hand-weighting initially, fewer knobs
to mis-tune. **M3 — held-out split is REQUIRED for admission** (not a follow-on): estimate the gain on a
fit window subset and confirm it on held-out windows (reuse the replication / private-holdout split).
In-sample MDL overfits exactly the way the wave warns — this is the single most important spurious-scalar
guard, so it is in scope from P2, not deferred.

---

## 4. Integration — how admitted scalars feed the miner

The hard constraint: **additive and reversible**, never mutate `BASE`. The domain-field idea was
*deferred* in [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md) Wave 0 precisely because it "changes
`seed()`/`Inequality` shape and risks `ConjectureEngineParityTest`." Vocabulary expansion must not repeat
that.

- A new `DerivedScalar(name, expr, depth)` registry, populated by the grammar+MDL pass, *separate* from
  `MathInvariants.BASE`. `MathInvariants.extract(...)` gains an optional pass that computes admitted
  derived scalars per window from the already-extracted base/derived values.
- `vocabulary()` returns BASE + DERIVED + **admitted** scalars only when the feature is on; with the flag
  OFF, `vocabulary()` is byte-identical to today → `ConjectureEngineParityTest` is unaffected.
- Admitted scalars persist (name + expression + MDL provenance) so the set is stable across restarts and
  the derivation graph can carry "scalar invented at pass N, gain B bits" lineage.
- **Budget:** reuse [`SublinearGenerationBudget`](../../src/main/java/com/emergentterrain/research/conjecture/SublinearGenerationBudget.java)
  to cap admitted scalars per pass as `floor(n_validations^α)` — vocabulary growth, like conjecture
  generation, must stay sublinear in validation work.

**Decision (2026-06-03).** **I1 — a new `derived_scalars` table** (name, expression, depth, admitted_at,
gain_bits), separate from the conjecture store — mirrors how the discovery corpus is kept separate and
keeps the conjecture schema clean. **I2 — one level only** to start: admitted scalars do NOT feed the
next pass's grammar. Recursive operand eligibility is the real open-endedness/explosion lever and is
deferred behind its own flag, to be turned on only after one-level admission proves stable and bounded.

---

## 5. Phasing (all flag-gated OFF; pure-first, like the rest of W5)

1. **P1 — grammar + canonicalisation (pure). ✅ SHIPPED** (`OperatorGrammar`, 2026-06-03). `enumerate(scalars,
   maxDepth)` → candidate `Expr`s with canonical render, guarded `eval`, depth + operands; structural
   dedup, `x∘x` degeneracy pruning, and the bounded-NT × window-magnitude dimension guard. No admission.
   `OperatorGrammarTest` (9: depth-1 count/contents, dimension guard, canonical `*`, no self-degeneracies,
   base-scalars-not-re-emitted, depth-2 composition determinism/dedup, guarded eval, null safety).
   *(Numeric near-identity dedup against existing series is data-driven → deferred to P2 admission.)*
2. **P2 — MDL ledger (pure). ✅ SHIPPED** (`MdlGate`, 2026-06-03). `evaluate(z, samples, lhs, rhs,
   existingScalars, params)` → `Verdict(descriptionBits, compressionBits, bestExistingBits, netGainBits,
   beatsNull, admit, reason)`. Compression = held-out variance-reduction bits on the lhs~rhs residual
   (M1/M3: fit `a+b·z` on a fit fold, score on the held fold); incremental over the best existing
   explainer (M1 "no existing scalar does it"); significance via the W4 null; `L = nodeCount·log2(grammarSymbols)`
   (M2). `MdlGateTest` (6: planted explainer admitted, unrelated rejected, duplicate-of-existing rejected,
   description-cost scales with node count, too-few-samples, null safety). Scores one candidate vs one
   target residual — cross-bound iteration + family MCC + persistence are P3.
3. **P3a — admission orchestration + parity-safe seam (flag-gated). ✅ SHIPPED** (2026-06-03).
   `DerivedScalar` (admitted term: name = canonical render, the `Expr`, gainBits); `VocabularyExpansion.admit`
   (enumerate → `MdlGate` over the top confirmed bounds → rank by net gain → cap by `SublinearGenerationBudget`);
   parity-safe `MathInvariants.vocabulary(admitted)` / `extract(…, admitted)` overloads (no-arg paths
   untouched → byte-identical when empty, verified by `MathInvariantsExtensionTest`; `ConjectureEngineParityTest`
   unaffected). Wired into `mine()` flag-gated OFF (`vocabularyExpansionEnabled`, `vocabularyExpansionDepth`),
   bounded (60 candidates × 3 targets, ≤8 admitted/pass), **observability log only**. Tests:
   `VocabularyExpansionTest` (5), `MathInvariantsExtensionTest` (3).
4. **P3b — persistence + generation (flag-gated). ✅ SHIPPED** (2026-06-03). `DerivedScalarEntity` +
   `DerivedScalarRepository` (new additive `derived_scalars` table, created by `ddl-auto: update`);
   the admitted set is loaded each pass and its `Expr`s reconstructed by **re-enumerating the grammar
   and matching by render** (no serialiser); `materialize(run, activeAdmitted)` extracts admitted
   scalars into every window (so conjectures referencing them are re-evaluable), and after admission the
   miner **seeds new conjecture pairs that touch a newly-admitted scalar** (additive, dedup'd, capped at
   20/pass, provenance `derivedFrom="vocab:<name>"`). Repo is setter-injected (existing constructor call
   sites unchanged); strictly flag-gated — with the flag OFF, `loadActiveAdmitted` returns empty, so
   `materialize`/seed are byte-identical and the table is untouched (only auto-created, empty).
   *Pure admission (`VocabularyExpansion`) + the parity invariant are unit-tested; the load/persist/seed
   wiring is exercised at live-run (needs a DB + flag ON), not in unit tests.*

P1+P2+P3a were the pure/observability pieces; P3b is the substrate-mutating generation step that closes
the loop — admitted scalar persisted → extracted next pass → its conjectures evaluated like any other.

---

## 6. Risks

- **Combinatorial explosion.** Depth-2 over ~15 scalars × ~6 ops is thousands of candidates; the
  per-candidate MDL test runs a permutation null. Mitigation: depth bound (§2), dimension/near-identity
  pre-filters, the sublinear budget on *admissions*, and a per-pass *candidate* cap (probe ceiling, like
  `RESIDUAL_PROBE_CAP`). Cost must be bounded before P3.
- **Overfitting / spurious scalars.** The whole point of MDL + null + MCC; without a held-out split the
  compression estimate can be optimistic (M3).
- **Parity / blast radius.** Mutating the vocabulary is the deferred-domain-field hazard. Mitigation: the
  flag-OFF byte-identity invariant + an explicit `vocabulary()` parity test.
- **Interpretability.** A published paper must state a human-readable hypothesis; admitted scalars must
  render as legible expressions (`log(mean_divisor_count)`), and a paper using one must define it inline.
- **Sidecar coupling.** If the operator set ever needs quantities the window aggregator doesn't compute,
  that's a sidecar change (recompile/restart hazard). The grammar above deliberately stays within
  already-extracted scalars to avoid this.

## 7. Non-goals
- Free-form symbolic regression / arbitrary expression search (that's the PySR path, separate). 30.5 is a
  *bounded, MDL-gated* grammar, not unbounded search.
- Mutating `BASE` or changing `Inequality`/`seed()` shape.
- Persisting multivariate relations — 30.2/30.1 already note the 2-term-schema boundary; 30.5 expands the
  *operands*, still feeding 2-term bounds.

## 8. Acceptance (proposed)
A planted derived scalar that genuinely tightens/explains a relation is admitted and surfaces in a mined
bound; a battery of random expressions is rejected by the MDL gate (≈0 admitted on noise); with the flag
OFF, `vocabulary()` and the mined survivor set are byte-identical to today (parity preserved). Steady-state
flip gated by the same A/B as the rest of W5 (zero false-retirement + no spurious-scalar inflation on a
hand-checked control set).

## Decisions settled (2026-06-03)
- **G1/G2/G3** (§2) — minimal multiplicative grammar `{log, sqrt, square, reciprocal, *, /}`; depth 1
  first (config knob); no constants.
- **M1/M2/M3** (§3) — residual-variance→bits compression currency; uniform per-node code-length prior;
  **held-out confirmation required** for admission (in scope from P2).
- **I1/I2** (§4) — new `derived_scalars` table; one level only (recursive operands deferred behind their
  own flag).

Net shape: a bounded, non-recursive, depth-1 multiplicative grammar admitted by held-out-confirmed
residual-variance MDL — the version that cannot quietly flood the vocabulary. **Ready to queue**;
P1 (grammar, pure) is the first slice.

## Cross-references
- [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md) — Wave 5 (30.5 is the last open item).
- [`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md) — 30.5 in the original proposal.
- Reused shipped components: `ResidualStructureMiner` (30.2), `ConjectureStochasticNull` +
  `MultipleComparisonsCorrection` (W4), `SublinearGenerationBudget` (W3), `ConjectureUnitConsistencyFilter`
  + `ConjectureTrivialityFilter` (W1).
