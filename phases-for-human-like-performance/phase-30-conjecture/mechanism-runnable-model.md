# Wave 6 — 30.7 mechanism-as-runnable-model + closure check + conservation gates

**Status (2026-06-03): IN PROGRESS — decisions D1–D4 settled (recommended options), P1 (Mechanism model) shipping.** The roadmap's **"largest build."**
30.7 turns a mechanism from prose into a *runnable model*: a declared mechanism must parse, lower to a
`predict()` callable, **regenerate its claim out-of-sample**, structurally **close**, and respect
**conservation/unit invariants** — or it is rejected. Sources: sim #3 (executable model), #4 (closure),
#7 (conservation). This doc scopes the build and the decisions a reviewer must settle before code.

**De-risking headline:** despite the "largest build" label, most of 30.7 is a new *container + checks*
over pieces already shipped — the expression layer, closure math, dimension registry, held-out split,
and permutation null all exist. The genuinely new surface is the mechanism model, its `predict()`
lowering, and the conservation extractor. See §7 (reuse map).

---

## 1. The idea

A bound `lhs <= k*rhs + c` states *that* a relationship holds. A **mechanism** states *why*: a small
runnable model that, given a window's inputs, predicts the target and thereby regenerates the bound. Per
sim #3 the mechanism must be executable, not freeform — "reject any mechanism whose `predict()` cannot
regenerate the claimed relation out-of-sample." Per #4 it must *close* (no dangling intermediate); per #7
it must respect invariants (dimensional balance, conserved combinations, declared monotonicities). The
published unit becomes a mechanism that survives all three, not an isolated inequality.

---

## 2. The mechanism model & DSL

A mechanism is a small set of **quantity definitions** culminating in a target, plus declared invariants:

```
  # intermediate := <expression over inputs/intermediates>
  spread      := sqrt(mean_divisor_count)
  predict(mean_omega) := log(spread) + 0.5
  invariant: dimension(predict) == dimension(mean_omega)
  invariant: monotone_increasing(predict in mean_divisor_count)
```

- **Expression layer = `OperatorGrammar`** (decision D1, recommended): each definition's RHS is an
  {@code OperatorGrammar.Expr} over the vocabulary + previously-defined intermediates. It is
  human-readable, already has a guarded `eval`, and runs **in-process** — no sandbox, no RCE (the
  alternative, LLM-authored sandboxed Julia via the discovery moat, is more expressive but carries the
  runtime-self-verifying-execution security surface; reserve it for when grammar expressivity is the
  bottleneck).
- **Lowering to `predict()`**: topologically order the definitions and evaluate them per window into a
  callable `predict(window) -> double` for the target. Deterministic.
- **Grammar bound**: the same depth/operator bounds as 30.5 keep mechanisms small and legible.

**Decisions:** **D1** expression layer (OperatorGrammar in-process vs sandboxed Julia). **D2** authoring
source — who proposes a mechanism: an LLM professor tool (`propose_mechanism`) during paper authoring,
or auto-derivation from the composition/derivation graph (the `derivedFrom` chains W2 already records)?

---

## 3. Out-of-sample regeneration gate (sim #3)

A mechanism is admitted only if its `predict()` **regenerates the claim on held-out windows**:

- **Held-out split** (reuse the replication/private-holdout fold): fit any free constants on a fit fold,
  evaluate `predict()` on the held fold.
- **Regeneration test** (decision D3): the claim is "regenerated" iff the bound it explains still holds
  under the prediction out-of-sample — e.g. `lhs <= predict + 2*TOL` on every held window (bound form),
  or `|predict − lhs| <= 2*TOL` (value form). Recommend the **bound form** (it's the claim being
  explained).
- **Mean AND ensemble** (sim #3): run `predict_mean` (deterministic) and `predict_ensemble` (over
  resampled window subsets — the substrate is deterministic, so "ensemble" is bootstrap over windows, as
  in W4 replication, not RNG). A finding survives only if it holds in the mean **and** is not swamped by
  ensemble spread. Significance reuses `ConjectureStochasticNull`.

---

## 4. Closure check (sim #4)

Represent the mechanism as a directed quantity-graph (each definition is a node: output := f(inputs))
and verify it **closes**: every intermediate it introduces is **both produced and consumed** within the
mechanism's relations — no dangling un-grounded term (an input that is neither a food observable nor
produced) and no un-consumed product (an intermediate nothing reads). This is exactly the RAF/CAF check
at mechanism granularity, so it **reuses `DerivationClosure`** (W6) over a `DerivationGraph` built from
the mechanism's definitions, food = the observable vocabulary. A mechanism that doesn't close is
rejected as ill-formed.

---

## 5. Conservation / unit invariant gates (sim #7)

Auto-extract each mechanism's invariant set and reject any prediction that violates it, logged as a
**model defect** (not a tuning miss):

- **Dimensional balance (realizable now):** reuse the `ConjectureUnitConsistencyFilter` class registry —
  a definition must be dimensionally coherent (e.g. don't add a bounded-NT density to a window-magnitude;
  the target's class must match the prediction's). This is the cleanest NT realization of "units."
- **Declared monotonicities:** if a mechanism declares `monotone_increasing(predict in X)`, check it on
  the samples; a violation auto-rejects.
- **Conserved combinations (honest caveat):** the chemistry/MD notion of a conserved moiety (Householder
  reduction of a stoichiometry matrix) has **no clean number-theory analog** — there is no stoichiometry
  here. Scope #7 to dimensional balance + declared monotonicities + (optionally) a declared
  "augmented-balance" identity the mechanism claims and that must hold out-of-sample. **Decision D4:**
  confirm this scoping, or define a substrate-specific invariant family.

---

## 6. Where mechanisms come from (the load-bearing open question)

The gates above *judge* a mechanism; something must *propose* one. **Decision D2** is the crux:

- **LLM-proposed** — a `propose_mechanism` professor tool (mirroring the `verify_conjecture` /
  `discover_*` tools) lets the paper author declare a mechanism in the §2 DSL for the bound it's writing
  about; the gates accept/reject it. Fits the paper pipeline; needs prompt + tool wiring.
- **Auto-derived** — synthesise candidate mechanisms from the `derivedFrom` composition chains (W2) +
  the 30.5 grammar, and admit the ones that pass. Fully autonomous; larger search.

Recommend **LLM-proposed first** (bounded, fits the existing tool pattern, the author already has
context), auto-derivation as a follow-on.

---

## 7. Reuse map (why this is smaller than "largest build" implies)

| 30.7 need | Reused, already shipped |
|---|---|
| executable expression layer | `OperatorGrammar` (P1 of 30.5) — guarded `eval`, bounded grammar |
| out-of-sample / held-out | the fold split in `ConjectureReplicationValidator` / `PrivateHoldoutValidator` (W4) |
| ensemble + significance | `ConjectureStochasticNull` (W4) |
| closure check (#4) | `DerivationClosure` + `DerivationGraph` (W6 RAF) |
| dimensional balance (#7) | `ConjectureUnitConsistencyFilter` class registry (W1) |
| (optional) sandboxed predict() | the runtime-self-verifying-execution moat (`CandidateDiscoveryService`) |

Genuinely new: the `Mechanism` container + parser, the `predict()` lowering/evaluator, the conservation
extractor, and the admission orchestration.

---

## 8. Phasing (pure-first, flag-gated OFF)

1. **P1 — Mechanism model + `predict()` (pure). ✅ SHIPPED** (2026-06-03). `Mechanism` (ordered
   `Definition`s over `OperatorGrammar.Expr` + target + declared invariants); `Mechanism.parse` ingests
   the §2 DSL (`name := expr`, `predict(TARGET) := expr`, `invariant: …`, `#` comments) via the new
   `OperatorGrammar.parse` (inverse of `render`); `predict(window)` evaluates definitions in order and
   returns the target. Tests: `MechanismTest` (6), `OperatorGrammarTest` parse round-trip (+4).
2. **P2 — the three gates (pure).** Out-of-sample regeneration (mean + ensemble), closure (`DerivationClosure`),
   conservation (dimensional balance + monotonicity). `MechanismGate.evaluate(mechanism, samples, target)
   -> Verdict`. Unit-tested: a faithful mechanism passes; one that overfits / doesn't close / breaks
   dimension is rejected.
3. **P3 — authoring integration (flag-gated).** Per D2: the `propose_mechanism` tool (or auto-derivation),
   persistence of accepted mechanisms, and the paper-pipeline wiring so a published unit can carry a
   verified mechanism. Parity preserved (flag-OFF byte-identity).

P1+P2 are pure/testable; P3 is the integration (and, if D1 picks sandboxed Julia, the RCE surface).

## 9. Risks
- **DSL scope creep** — keep the grammar to `OperatorGrammar` + named definitions; resist a general
  programming language. Bounded depth/op set as in 30.5.
- **RCE** (only if D1 = sandboxed Julia) — would inherit the discovery moat's isolation; the in-process
  `OperatorGrammar` path avoids it entirely.
- **Overfit** — same as 30.5/W4: held-out regeneration + ensemble + null are the guard.
- **Conservation has weak NT footing** — §5 caveat; don't overclaim a moiety analog that isn't there.
- **Proposal bottleneck** — the gates are worthless without a mechanism source (D2); decide that first.
- **Interpretability** — a published mechanism must render legibly (the DSL is the artifact).

## 10. Non-goals
- A general-purpose modelling/simulation language (Antimony/SBML in full) — only a bounded NT-quantity DSL.
- ODE/Gillespie kinetics (no time dimension in this substrate); "simulate" = evaluate `predict()` per window.
- Replacing the bound finding — a mechanism *explains/strengthens* a finding, it doesn't remove the bound.

## 11. Acceptance (proposed)
A hand-written faithful mechanism for a confirmed bound parses, lowers to `predict()`, closes, respects
dimension, and regenerates the bound on held-out windows (mean + ensemble) → admitted. A mechanism that
(a) doesn't close, (b) violates dimension, or (c) only fits in-sample is rejected, each with a distinct
reason. With the flag OFF, the pipeline is byte-identical. Steady-state flip gated by the same A/B
discipline as the rest of Phase 30.

## Decisions settled (2026-06-03)
- **D1** — expression layer: **`OperatorGrammar` in-process** (no sandbox / no RCE).
- **D2** — mechanism source: **`propose_mechanism` LLM tool** (P3), bounded; auto-derivation deferred.
- **D3** — "regenerate the claim": **bound form** (`lhs <= predict + 2*TOL` on held-out windows).
- **D4** — conservation scope: **dimensional balance + declared monotonicities** (no moiety analog).

P1 (this slice): the pure `Mechanism` model — definitions over `OperatorGrammar.Expr`, DSL parse (the
inverse of `render`), and `predict()` lowering. The three gates (P2) and authoring/`propose_mechanism`
(P3) follow.

## Cross-references
- [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md) — Wave 6.
- [`runtime-self-verifying-execution.md`](runtime-self-verifying-execution.md) — the sandbox/moat 30.7 would
  reuse only under D1 = sandboxed Julia.
- [`vocabulary-expansion-mdl.md`](vocabulary-expansion-mdl.md) — `OperatorGrammar`, the recommended expression layer.
- Reused components: `OperatorGrammar`, `DerivationClosure`/`DerivationGraph`, `ConjectureUnitConsistencyFilter`,
  `ConjectureStochasticNull`, the W4 held-out folds.
