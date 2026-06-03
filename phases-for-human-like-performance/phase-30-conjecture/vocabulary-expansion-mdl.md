# Wave 5 — 30.5 vocabulary expansion via a closed operator grammar, MDL-gated

**Status (2026-06-03): DESIGN DOC — proposal for review, NOT queued.** This is the last open Wave-5
item and the one the proposal flags **"beyond human-level," highest-risk/highest-reward**. Unlike every
W0–W5 slice shipped so far — which *filter* or *score* over a fixed scalar set — 30.5 *expands the
substrate vocabulary itself*: it invents new scalars. That makes it the first Phase-30 change that
touches [`MathInvariants`](../../src/main/java/com/emergentterrain/research/conjecture/MathInvariants.java)
(the scalar registry), so it carries real blast radius (parity tests, pool explosion, interpretability).
This doc scopes the build and lists the decisions a reviewer should make **before** any code.

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

**Open question (G1):** operator set — start minimal (`log, sqrt, square, reciprocal, *, /`) or include
`+`/`-` (mostly redundant with the engine's affine `k,c`)? **(G2):** depth 1 first, or 2? **(G3):**
include constants, or let the engine's `k,c` carry all affine freedom?

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

**Open question (M1):** the residual-variance→bits conversion above is one operationalisation of
"compression"; an alternative is "number of new confirmed bounds beating the null after MCC that `z`
enables." Which is the admission currency? **(M2):** the per-node code-length prior — uniform, or
weight simpler operators cheaper? **(M3):** a held-out split for the compression estimate (fit the gain
on one window set, confirm on another) — required for admission, or a follow-on like the other W5 boundaries?

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

**Open question (I1):** persist admitted scalars in a new table, or extend the conjecture store?
**(I2):** do admitted scalars become eligible *operands* for the next pass's grammar (true open-endedness,
more explosion risk), or one level only?

---

## 5. Phasing (all flag-gated OFF; pure-first, like the rest of W5)

1. **P1 — grammar + canonicalisation (pure).** `OperatorGrammar.enumerate(vocabulary, depth)` → candidate
   expressions, with dedup/dimension/near-identity guards. No admission. Unit-tested on the real scalar
   set (counts, guard rejections, canonical forms).
2. **P2 — MDL ledger (pure).** `MdlGate.score(candidate, samples, existingScalars)` → `net_gain_bits`,
   delegating the structure test to `ResidualStructureMiner` + the null + MCC. Unit-tested: a planted
   `z = 3*log(rhs)` that explains a residual is admitted; a random expression is not.
3. **P3 — registry + extraction wiring (flag-gated).** `DerivedScalar` registry + the `MathInvariants`
   extraction pass + `vocabulary()` extension + sublinear budget + persistence. Parity test guarded by
   the flag-OFF identity. Observability log: "admitted N scalars this pass (top: `…`, gain B bits)."

P1+P2 are the genuinely new pure utilities; P3 is the (riskier) substrate wiring.

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

## Decisions needed before coding
- **G1/G2/G3** — operator set, depth, constants (§2).
- **M1/M2/M3** — compression currency, code-length prior, held-out split (§3).
- **I1/I2** — persistence location, recursive operand eligibility (§4).

## Cross-references
- [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md) — Wave 5 (30.5 is the last open item).
- [`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md) — 30.5 in the original proposal.
- Reused shipped components: `ResidualStructureMiner` (30.2), `ConjectureStochasticNull` +
  `MultipleComparisonsCorrection` (W4), `SublinearGenerationBudget` (W3), `ConjectureUnitConsistencyFilter`
  + `ConjectureTrivialityFilter` (W1).
