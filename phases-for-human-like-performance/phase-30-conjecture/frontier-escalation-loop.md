# Frontier-escalation loop — autonomous scale-curriculum with stall-aware branch pivoting

The discovery machinery ([`runtime-self-verifying-execution.md`](runtime-self-verifying-execution.md))
gives the harness a *tool* to compute a quantity beyond the sieve. It does **not** give it a *drive*
to push the frontier on its own. This note scopes that drive: a loop that, when a bound keeps
holding, **escalates the scale automatically**, **predicts when computation will stall**, and — when
it will — **pivots to a different, more "innovative" branch** rather than grinding to a halt.

The animating observation (the operator's): *scarcity has driven scientists to innovate, especially
in theory.* When brute force became infeasible, number theorists reached for analytic methods,
physicists for scaling/dimensional arguments. Taken seriously, that principle does real work here —
because it forces the question the moat thesis can't dodge: **which innovative branches can the
harness still verify, and which is it forced to merely trust?**

> Prerequisites: the discovery loop (Phases 1–5) is the computational branch's engine. The honest
> compute/verify ceilings come from [`mertens-analytic-reachability.md`](mertens-analytic-reachability.md);
> the science-frontier numbers from [`mertens-conjecture-vs-literature.md`](mertens-conjecture-vs-literature.md).

---

## 1. The gap: nothing escalates today

The mining→investigation loop is **fixed-scale**: the miner runs a hardcoded `RUNS` set (windows
≤ 2×10⁶), conjectures are bounds on *windowed* statistics, and the verifier rejects any window past
`NT_MAX_N`. There is no mechanism that says "this held everywhere I looked — look further." The system
mines a fixed range, fits an envelope, investigates it within that range, and stops. "Large x" isn't a
goal it can represent, so nothing pulls it there. The escalation drive must be **added**.

## 2. The escalation loop (the dynamic curriculum)

```
seed: a bound B(x) that holds across all mined scales (high confirmedRuns, no counterexample)
loop:
  x_next = 10 × x_current                      # escalate the regime
  if x_next ≤ NT_MAX_N: value = verify on the sieve (today's path)
  else:                 value = discover(quantity, x_next)   # validated method, fork 2b
  classify(value vs B):
    holds        -> record (x_next, value); continue            # frontier advances
    counterexample -> PUBLISH the refutation (the real result); stop
    cannot-compute (stall predicted, §3) -> PIVOT to an innovative branch (§4); 
  until a terminal state
```

Each round advances the frontier 10× and produces a *checkpoint* `(x, value, verdict)`. The corpus of
checkpoints is itself the paper: "the bound's slack across `x ∈ {2×10⁶ … 10¹²}`, computed by validated
methods" — a study of the bound's *approach* toward its limit, which is genuinely less trivial than
the current "refute a windowed fit" type.

## 3. Stall prediction (the novel mechanism — "will compute run out?")

Escalation must know *before wasting a budget* when computation will fail. Two frontiers, both
estimable:

- **Compute frontier `x_c`** — where the method's cost crosses the budget. The cost model is *known
  per method* (direct sieve O(x); sublinear Deléglise–Rivat O(x^{2/3})), so fit the observed
  `elapsed_ms` from prior `discover` calls to that functional form and solve `cost(x_c) = budget`.
  This is data-driven extrapolation of timings already in hand — no oracle needed. (E.g. a 30 s
  budget at ~10⁹ ops/s with O(x^{2/3}) puts `x_c ≈ 10¹³`.)
- **Science frontier `x_s`** — where the *question actually gets decided*: the smallest scale at
  which a counterexample/transition is known or plausible (from literature; for Mertens `x_s ≈
  10¹⁴–10¹⁶`). May be "unknown/unbounded."

Decision:
- `x_c ≥ x_s` → keep escalating; computation can reach the answer.
- `x_c < x_s` → **computation will stall before the answer.** Pivot (§4). This is the harness
  *recognizing scarcity* — the precondition for innovation.

The predictor is honest about its own uncertainty: it `log()`s the fitted cost curve and both
frontiers, so a wrong extrapolation is visible, not silent.

## 4. The branch taxonomy + pivot policy (where "scarcity → innovation" becomes concrete)

When direct escalation stalls, pivot to a *different kind* of approach. But the branches are not equal
in what the harness can **trust** — and that, not cleverness, is what orders them:

| Branch | What it does | Validatable here? | Reachable frontier | Trust |
|--------|--------------|-------------------|--------------------|-------|
| **B1 Direct evaluation** | compute the value exactly at x | yes (moat) | budget-bounded (~2×10⁶ sieve) | full |
| **B2 Algorithmic acceleration** | a cleverer algorithm (sublinear/segmented) | yes, on the overlap | algorithm-bounded (~10¹²–10¹⁵) | full |
| **B3 Proxy reformulation** | study a *related, more tractable* quantity that constrains the target (smoothed/weighted/averaged form; a sibling summatory function) | yes — it's a real computation, of a *nearby* question | varies | full, but answers a proxy |
| **B4 Predictive model** | a heuristic/probabilistic model whose *distributional* predictions are checked against computed samples on the overlap, then extrapolated | **partially** — model-fit on the overlap, extrapolation unverified | extrapolative | fit-quality only |
| **B5 Analytic / non-constructive** | proof-style reasoning (explicit formula, ζ-zeros, asymptotics) yielding existence/bounds, not values | **no** — an evaluate-and-check engine cannot verify a proof-by-zero-distribution (Wall 2) | the true science frontier | literature-grounded / human only |

**Pivot policy (the principled part).** Scarcity should push toward **innovation the harness can still
verify**, not toward unverifiable theory:
1. Exhaust **B1→B2** while affordable (accelerate before reframing).
2. On predicted stall, prefer **B3 then B4** — *reframe to a tractable proxy* or *fit a checkable
   model*. These are the verifiable-innovation sweet spot: the LLM proposes the specific proxy/model
   (the creative act), and the moat validates it **on the overlap** before any extrapolation is
   trusted. This is exactly how scarcity has *historically* bred theory — find structure that lets you
   reason about the answer without computing it directly — but kept inside the verification regime.
3. Treat **B5** as the **boundary**, not a step: when only non-constructive methods can reach `x_s`,
   the loop does **not** pretend to verify them. It either grounds the claim in literature (cited,
   "not derived here") or, better, **reports the boundary itself as the result**: *"this question is
   computationally unreachable within budget and analytically unverifiable within this harness"* — an
   honest, publishable terminal finding, and a far better paper than a fabricated bound.

The innovation here is **bounded and honest**: the system can autonomously *select a different
verifiable branch* and have the LLM propose its specifics (B3/B4), with the moat as the gate. It
**cannot** autonomously produce verified novel theory (B5) — and the loop's job at that edge is to
*recognize and name the edge*, which is itself the scarcity-driven scientific move done truthfully.

## 5. The verifiability boundary (why this stays honest)

The whole design rests on one line: **a different branch earns a different trust level, and the loop
never launders trust across the line.** B1–B3 results are moat-validated values. B4 carries an explicit
fit-quality caveat and an extrapolation flag. B5 is never presented as derived-here. A frontier paper
therefore reads: "computed (validated) to x_c; beyond that, the behavior was modeled (B4, fit R²=…) /
is governed by methods outside this harness's verification (B5, see [citation]]" — regime-aware to the
end, the discipline the discovery tool's own output already enforces.

## 6. Honest ceilings

- For Mertens, escalation + B2 reaches ~10¹²–10¹⁵ and **stalls short of the 10¹⁴ counterexample** — it
  builds across ~6 orders of magnitude beyond 2×10⁶ (real, less-trivial science: the √x-ratio's
  *approach*) but does **not resolve** the conjecture. No branch the harness can *verify* reaches `x_s`.
- B3/B4 extend *reach* or *insight*, not *proof*: a validated proxy answers a nearby question; a fitted
  model predicts, it doesn't prove.
- "Innovative branch" ≠ "the LLM invents new mathematics." It means *select a different verifiable
  method-class and propose its specifics under the moat*. The genuinely new-to-mathematics move lives
  in B5, which the harness can host only as cited literature, never as its own verified output.

## 7. Phasing

1. ✅ **Escalation skeleton** — the §2 loop over a *holding* bound, routing ≤NT_MAX_N to a sieve value
   source and beyond to `MertensDiscoveryService.discover()`; emits the checkpoint corpus + terminal
   state + `frontierReached`. **Done** (`research/discovery/FrontierEscalationService` + the
   `Escalation*` types + `BoundPredicate.MERTENS` = `|M(x)|<√x` + `ScaleValueSource`;
   `FrontierEscalationServiceTest`, 5/5). Flag-gated OFF (`frontierEscalationEnabled`); `escalate()`
   returns a disabled outcome otherwise. Stall is **reactive** here (a `CANNOT_COMPUTE` stops the
   loop) — the predictor below is Phase 2. The bound + sieve source are injected, so the loop is
   bound-agnostic and unit-tested with fakes (escalate-while-holding → `exhausted_rounds` with
   sieve→discovery source routing; counterexample → stop at last-held; discovery fail →
   `discovery_stalled`). Dormant — nothing calls `escalate()` until the frontier-paper wiring (phase 4).
2. ✅ **Stall predictor** — **Done** (`StallPredictor` + `StallPrediction` + `CostObservation`;
   `StallPredictorTest`, and the loop's `predicted_stall` path). Fits `cost ≈ a·xᵖ` by log-log least
   squares from the discovery rungs' observed `elapsed` (threaded through
   `ValidationResult`/`DiscoveryOutcome`/`EscalationCheckpoint`), solves `a·x_cᵖ = stallBudgetMs` for
   `x_c`, and flags `x_c < scienceFrontier`. The exponent is **learned empirically** (honest about
   whatever method discovery produced — recovers p=1 and p=2/3 in tests), not assumed. The loop now
   **stops at `predicted_stall` before escalating past `x_c`**, rather than grinding to the reactive
   `discovery_stalled`. Config: `frontierStallBudgetMs` (per-rung ceiling), `frontierScienceFrontier`
   (`x_s`; 0 = unknown). Still flag-gated/dormant. The Phase-3 pivot **replaces** this stop with a
   branch switch.
3. ✅ **B3/B4 branch pivot** — **Done** (`BranchPivotService` + `Branch`/`TrustLevel`/`PivotOutcome`/
   `PivotRequest`/`BranchProposer`; `BranchPivotServiceTest` + loop integration; 33/33). On a
   predicted stall the loop pivots: try verifiable branches in **trust order** (B3 then B4), take the
   first that validates, else return the **honest B5 boundary** ("requires non-constructive methods
   outside this harness; ground in literature, don't report a derived value") — never invent one. The
   outcome carries its `TrustLevel` verbatim (`FULL`/`VALIDATED_PROXY`/`MODEL_FIT`/`LITERATURE_ONLY`)
   and the loop never launders one into another. Flag-gated OFF (`branchPivotEnabled`); degrades to a
   plain `predicted_stall` when no pivot is wired. B5 remains a leaf — there is deliberately no
   `BranchProposer` for it.
   - ✅ **Concrete B3/B4 proposers** — built as injected `@Component`s (so the pivot now tries B3→B4→
     boundary). **B4 `PredictiveModelProposer`** (pure): least-squares-fits the corpus ratio
     `|M(x)|/√x` vs `ln(x)`, extrapolates to the stalled target, predicts HOLD/FAIL tagged
     `MODEL_FIT` with the R² and an explicit "extrapolation, not proof — below the regime where the
     bound is known to fail" caveat. **B3 `ProxyReformulationProposer`**: asks the discovery moat for
     a *fundamentally more efficient reformulation* at the stalled target; `VALIDATED_PROXY` on a
     validated cheaper method, defers otherwise (for Mertens, where the sublinear method is
     near-optimal, it usually defers to B4 — honest). A genuinely different proxy *quantity* (a
     sibling summatory function with its own oracle) needs a **generalized validation oracle the
     harness lacks** — a documented follow-on, not faked. (`BranchProposerImplTest`, 39/39.)
4. **Frontier paper wiring** — the narrator builds Findings from the checkpoint corpus + the pivot
   result, with per-branch trust framing.
5. **Flip + watch** — enable on a holding bound; watch it escalate, predict its own stall, and pivot.

## 8. Non-goals
- Resolving Mertens (or any question whose only path to `x_s` is B5).
- Autonomous *verified* theory generation (B5 is a boundary, not a capability).
- Unbounded compute — every escalation step is budget-bounded and stall-predicted.

---

## Cross-references
- [`runtime-self-verifying-execution.md`](runtime-self-verifying-execution.md) — the discovery engine
  this loop drives (branch B1/B2; the moat that gates B3/B4).
- [`mertens-analytic-reachability.md`](mertens-analytic-reachability.md) — Wall 2 (why B5 is
  unverifiable here) and the compute/verify ceilings the stall predictor encodes.
- [`mertens-conjecture-vs-literature.md`](mertens-conjecture-vs-literature.md) — the science-frontier
  `x_s` and why escalation builds *toward* the wall, not through it.
- [`segmented-mertens-spike.md`](segmented-mertens-spike.md) — branch B2's reach (sublinear M(x)).
- [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md) — where this sits relative to the composition lane.
