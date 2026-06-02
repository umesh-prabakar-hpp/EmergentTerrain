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
     near-optimal, it usually defers to B4 — honest). (`BranchProposerImplTest`, 39/39.)
   - ✅ **Generalized B3 oracle** — **Done** (`ProxySpec` + `ProxyOracle` + `SummatoryProxyOracle` +
     `ProxyValidator` + `ProxyCatalog`; 7/7). A genuinely different proxy *quantity* — a
     linear-combination summatory `Σ(cμ·μ + cω·ω + cΩ·Ω + cτ·τ)` over the trusted primitives — now
     has trusted ground truth on the overlap, computed from the **existing window aggregator** (no
     sidecar change): `window(1,x)` already sums μ/ω/Ω/τ over `[2,x]`; the n=1 terms are added back.
     `ProxyValidator` is the proxy moat (judge candidate values vs the oracle on the overlap — the
     same contract as M(x), any proxy). Nonlinear terms (e.g. μ²) would need a new trusted sidecar
     evaluator (further follow-on); and *wiring B3's runtime to it* (a proxy-discovery loop:
     generate → sandbox-exec → judge via the proxy oracle, mirroring `MertensDiscoveryService`) is
     the remaining consumption step. The honest open question stays: whether any proxy is a *useful*
     constraint on Mertens — the oracle only makes proxies **verifiable**.
4. ✅ **Frontier paper wiring** — **Done** (`FrontierFindingsRenderer` + `SidecarMertensValueSource` +
   `MertensFrontierService` + `FrontierPaper`; `FrontierFindingsRendererTest` +
   `MertensFrontierServiceTest`, 7/7). The renderer turns an `EscalationOutcome` into markdown
   Findings: a checkpoint table (x, value, ratio, verdict, source), a terminal narrative (a
   counterexample is framed as *the real result*; held / stalled / pivoted / boundary otherwise), the
   compute forecast, and the pivot result with its `TrustLevel` **verbatim** plus a plain-words gloss
   (`FULL` / `VALIDATED_PROXY` / `MODEL_FIT` = extrapolation-not-proof / `LITERATURE_ONLY`) — never
   laundering trust — closed by a regime caveat so "held" never reads as "resolved".
   `SidecarMertensValueSource` is the prod ≤NT_MAX_N value source (`M(x) = mertens_delta(window 1..x)
   + 1`); `MertensFrontierService.produce()` composes the task, runs `escalate()`, renders a
   `FrontierPaper`. Gated via the escalation flag (disabled → a "did not run" paper). The tool/cycle
   that surfaces `produce()` is the flip+watch step.
5. ◐ **Flip + watch** — **logic flipped ON** (`frontierEscalationEnabled`, `branchPivotEnabled`) — SAFE,
   since `escalate()`/pivot are now functional but spend nothing until `produce()` runs. The trigger is
   `MertensFrontierService.scheduledFrontierRun()` — a gated, long-interval (`6h`) `@Scheduled` run
   (a heavy multi-LLM-call escalation is the wrong fit for a mid-paper professor tool), with a
   **separate** `mertensFrontierScheduleEnabled` cost gate (default OFF) so flipping the logic does not
   auto-spend. **Remaining (operator):** restart to load the logic (boot-check
   `FrontierEscalationService ready … frontierEscalation=ON`), then flip `mertensFrontierScheduleEnabled` +
   restart to begin autonomous 6-hourly runs — watch the log for `scheduled frontier run` / `frontier
   paper produced`, escalating → forecasting its own stall → pivoting. (A live run also needs the
   discovery stack live: `runtimeExecEnabled` + sidecar `ET_DISCOVER_EXEC=1`.)
6. ✅ **Adaptive step (`NextScalePolicy` seam)** — **Done** (`NextScalePolicy` + `NextScaleContext` +
   `DefaultNextScalePolicy` + `AdaptiveNextScalePolicy`; `NextScalePolicyTest` + loop integration,
   43/43). The next scale is now a *decision*, not a fixed ×10: the **knowledge frontier stays
   monotonic**, but the **step** adapts. `BoundPredicate` gained `margin(x,value)` (MERTENS =
   `1−|M(x)|/√x`); the **aggressive** adaptive policy leaps **×1000/×100** while the bound holds with
   room, ×10 closer in, and a **×3 floor** even at the bound — it never crawls (tuned to reach the
   frontier fast, not die by small steps; the stall predictor + pivot handle the ceiling). Flag-gated
   (`frontierScalePolicy: default|adaptive|llm`, default → fixed ×10, no behavior change).
   - ✅ **Bidirectional step + LLM policy** — **Done** (`LlmNextScalePolicy`; `LlmNextScalePolicyTest`
     + loop backward-step test; 51/51). The loop no longer treats `next ≤ x` as termination: the
     **knowledge frontier stays monotonic** (`frontier = max` held scale), but the **step may go
     forward OR backward** — a visited-set guards cycles and the loop stops on no-progress / a revisit.
     So a policy can interleave forward leaps with backward **densify/localize** moves. `LlmNextScalePolicy`
     (`frontierScalePolicy=llm`) asks a frontier model for the next scale from the `(x, ratio)` history
     + margin + `x_c`, prompted **aggressive** (leap when there's room, never crawl) and explicitly
     permitted to step backward; it parses plain/comma/scientific replies and **falls back to the
     aggressive adaptive policy** on anything bad, so a poor generation never stalls the loop.
   - ✅ **Counterexample localization** — **Done** (`frontierLocalizeEnabled`, `localize` bisection +
     `counterexample_localized` outcome; tests). When on, a counterexample no longer just stops: the
     loop **bisects** the bracket `(largest-held-below, first-failed]` (geometric midpoint,
     overflow-safe) until `hi/lo ≤ 1.1`, pinning where the bound crosses; the renderer narrates the
     bracket. **Honest caveat** (in-code + Findings): for a *non-monotone* bound (Mertens' ratio
     oscillates) this localizes the bracket *boundary*, not a proven first failure. Default OFF (the
     first counterexample stays the headline result).

## 8. Non-goals
- Resolving Mertens (or any question whose only path to `x_s` is B5).
- Autonomous *verified* theory generation (B5 is a boundary, not a capability).
- Unbounded compute — every escalation step is budget-bounded and stall-predicted.

## 8b. Knowledge accumulation — persistence, convergence, reuse

The loop as first built is **amnesiac and clock-driven**: each run re-derives from
scratch and a `@Scheduled` cadence re-spends on near-identical outcomes (a frontier
run on a *fixed* bound is largely repeatable). That is the wrong model for an
*accumulating* system. The fix is three coupled changes:

1. **Persist results across restarts.** The validated `(bound, x, value, source,
   trust)` checkpoints go to the DB the conjectures already live in
   (`FrontierRecord*`), so a restart resumes instead of recomputing — and the
   frontier **ratchets** (each run extends the corpus rather than redoing it). This
   is the lynchpin: without it the system can't accumulate.
2. **Run-until-converged-then-stop** (replacing the clock). Keep running only while
   a run *progresses* (extends the frontier) or the pivot changes; after
   `convergenceThreshold` runs with no progress, mark the bound **converged** and
   stop re-running (zero further spend) until a real trigger (new bound, new
   capability, new budget, manual). Convergence state persists too, so "converged"
   survives a restart.
3. **Keep-the-best.** On a conflict at the same `(bound, x)`, keep the
   **highest-trust** value (`FULL` > `VALIDATED_PROXY` > `MODEL_FIT` >
   `LITERATURE_ONLY`); the frontier keeps the largest validated `x`. Persistence
   accumulates *quality*, not just history.
4. **Persist the method code itself (built).** Each discovery checkpoint now carries
   the LLM-generated, moat-validated `methodCode` that reached `x`
   (`EscalationCheckpoint.methodCode`). `FrontierKnowledgeService.record()` hashes it
   (SHA-256), stores it as a `VerifiedMethod(bound, sha, code, maxScale, costMs)` in a
   `verified_methods` table, and keeps-the-best by **reach**. The library is **ranked
   by the cost curve** — reach desc, then cost asc — so `bestMethod(bound)` returns the
   method that reaches furthest cheapest, i.e. the one worth *reusing* instead of
   regenerating (a token saving that compounds with the frontier).

These reinforce each other: persistence makes convergence *detectable*, and
keep-the-best makes persistence *worth keeping*. Together they turn the loop from a
re-running clock into a system that **improves by accumulating** (cf. §6 ceilings —
it accumulates verified *computation*, never *proof*).

### Reusing accumulated knowledge in future papers

The accumulated corpus is a **trusted, provenance-tagged cache** that future papers
can draw on — a Mertens-class paper can cite a previously-validated `M(10¹²)` (or the
whole `M(x)/√x`-across-scales trajectory) instead of being stuck at the 2×10⁶ sieve.
This is how papers get **less trivial**: they build on the verified frontier rather
than re-verifying known results at 2×10⁶. **The discipline that makes this safe is
non-negotiable:** every reused value carries its `TrustLevel`, and trust is preserved
**transitively** — a paper that builds on a `MODEL_FIT` extrapolation *inherits*
`MODEL_FIT` trust (never laundered up to "established"). The cache is a *verified
store with provenance* (method hash + when), not a fact store: a record can be
re-verified or invalidated, so staleness and feedback-loop dilution are guarded by
the same "never launder trust" rule that governs the live pivot (§5). Used this way,
accumulation compounds *honestly* — the corpus of trustworthy computed knowledge
grows, and each paper can reach further than the last, with the trust ledger intact.

**Wired (the read path).** A paper consumes the corpus through the read-only
`recall_frontier_knowledge` tool in `MathToolSuite` (gated by `frontierRecallEnabled`,
advertised only when a `FrontierKnowledgeService` bean is present). It returns the
persisted `(x, value, source, trust)` records and the best verified method's
reach/cost via `recallText(bound)` — **no discovery re-run, no LLM/sidecar spend**.
Every line states its `TrustLevel`, and the framing is explicit: a `FULL` value is
reported as *computed-and-verified at x against a trusted oracle*, **never** as a proof
of the bound for all `x`. The tool is wired through `ResearchNarrationService`
(field-injected, `@PostConstruct`) the same way `discover_mertens_value` is, so it stays
absent in test contexts and dormant until the flag is flipped.

## 9. Follow-ons (built infra, deferred extensions)

The loop and its proposers are shipped flag-gated; these extend them and are **not
blocking**:

1. **Nonlinear proxy terms.** The generalized B3 oracle (`SummatoryProxyOracle`)
   handles *linear* combinations of {μ, ω, Ω, τ} because their summatories come free
   from the window aggregator. **Nonlinear terms — `μ(n)²` (the squarefree
   indicator), `μ(n)·ω(n)`, `μ(n)/n^s` weights — would need a new *trusted sidecar
   evaluator*** (a small, fixed Julia summatory over the dense sieve for a
   constrained term grammar) since the window aggregator doesn't precompute them.
   That's a scoped sidecar build (recompile + restart per the sidecar hazard); the
   Java side (`ProxySpec`/`ProxyValidator`/`ProxyDiscoveryService`) already
   generalizes to it.
2. **A *useful* Mertens proxy.** The oracle makes any proxy *verifiable*; whether one
   genuinely *constrains* M(x) is the open research question (the catalog proxies are
   illustrative, not load-bearing).
3. **cgroups RSS hardening** for the sandbox (runtime-self-verifying-execution.md
   §8.1) — a hard memory cap beyond the current `timeout`/allocation-failure bound.
4. **A large-x Mertens topic** so `discover_mertens_value` fires organically in a
   paper (today it only fires if a paper needs M(x) beyond 2×10⁶).

---

## 10. Feedback-driven discovery (2026-05-31 — live-run findings + design)

The first live frontier run exposed a structural gap. The LLM scale policy leapt
`2×10⁶ → 2×10¹¹` (×100 000) in one step; all three candidates OOM'd on a naive dense
`ones(n)` allocation; the loop hit `terminal=discovery_stalled`, `pivot=none`, `0
methods kept` — only `M(2×10⁶) = −247` accumulated. Root cause was **not** an
over-eager LLM: a discovery `CANNOT_COMPUTE` was *terminal*
(`FrontierEscalationService` returned `stalled` on the first failure), so the model
got exactly one blind pick and was never allowed to adjust.

**Design principle (decided): never clamp the LLM's step — build the feedback channels
instead.** The LLM stays free to choose any scale (10×/100×/1000×) and any method; it
just gets the failure back so it can self-correct. Concretely:

1. **A discovery failure feeds back, not terminates.** `CANNOT_COMPUTE` records
   `(x, reason, code)`, the loop **continues**, and `nextScale` is consulted again
   with the failure in hand. The visited-set still guards cycles and `maxRounds`
   bounds it; after **K** consecutive no-progress failures the loop routes to the
   existing stall → branch-pivot path (which also fixes `pivot=none`).
2. **The scale policy sees the failure.** `NextScaleContext` gains `lastFailedX` +
   `lastFailureReason`; `LlmNextScalePolicy`'s prompt surfaces *"x=2×10¹¹ failed: OOM;
   largest validated x = 2×10⁶; pick your next target."* No cap — informed free choice.
3. **An OOM is knowledge — persist, recall, cite it.** A `frontier_events` table
   `(bound, x, outcome=OOM|TIMEOUT|WRONG, reason, code, sha, when)` records every
   failure alongside the validated `frontier_records`/`verified_methods`. It feeds back
   **across runs** (a post-restart run sees a known-OOM x) and is **recallable into
   papers** as a trust-framed computational boundary / negative result — a FULL-trust
   fact *about the harness*, never a math claim (fits
   [`mertens-analytic-reachability.md`](mertens-analytic-reachability.md)).
   **Inform, do not suppress:** the event is shown to the LLM as context; it may still
   retry that x/approach if it judges fit.
4. **The OOM repair hint is scoped per the common-code rule.** The *domain-neutral*
   lesson — "you allocated memory ∝ x and hit the sandbox cap; use bounded/streaming
   memory, segment the range, never allocate a size-x array" — lives in the **shared**
   `LlmCandidateGenerator` (used by Mertens *and* Proxy discovery), triggered by the
   sandbox `oom`/`timeout` status. The *Mertens-specific* realization — "e.g. a
   segmented Möbius sieve (`nt_mertens_segmented` style)" — lives only in
   `MertensDiscoveryService.describe()`.
5. **The LLM-generated method code is logged everywhere.** Each candidate's Julia
   source (+sha) is written to the **log file** (per attempt, accepted and rejected),
   persisted to the **DB** (validated → `verified_methods`; failed → `frontier_events`),
   and rendered into the **paper** (a *Methods* section for the validated method, a
   *Computational boundary* section for the failures) — so the actual computation that
   produced a frontier value is auditable end to end.

**Live result (2026-05-31, 60s budget run pending).** The first run with the feedback
loop ratcheted the frontier **2×10⁶ → 2×10⁸** in 8 rounds, banking **3 validated
methods** and **4 boundary events**: the LLM self-corrected scale on feedback
(2×10¹¹→5×10⁹→5×10⁷ found the reachable zone) and the OOM hint produced segmented
methods that validate (`M(10⁸)=1928`, the known value). The run ended on the round cap
while still progressing — so the budgets were tuned: `maxRepairAttempts` 3→6,
`frontierMaxConsecutiveFailures` 3→5, `frontierEscalationMaxRounds` 8→20, and the
per-candidate sandbox budget is now the tunable `frontierCpuSeconds`/`frontierWallSeconds`
(20/28 → 60/80; CPU bounds reachable x ~linearly, wall is the JIT/hang backstop and stays
above CPU). **Runs now RESUME from the persisted frontier** (`startScale = max(configured,
knowledge.frontier(BOUND))`) instead of re-walking from the sieve each time — so each run
pushes past the last, and convergence reflects real new ground.

Sidecar facts confirmed during the run: `/discover/validate` has no lock — each call is
its own ulimit'd subprocess (`sandbox_runner.jl`), so an OOM kills only the child
(`signal 15`), never the server; within a run the repair rounds are inherently
sequential. Overlapping discovery callers compete for host RAM (optional in-app
semaphore if it bites). The post-OOM sidecar was slow (~7 s for a trivial probe vs
~0.6 s warm) — GC/memory pressure, not a crash; this can flap the 5 s steady-state
pre-flight timeout (falls back to the 20 s cold path).

---

## 11. Path B — frontier-framed conjecture investigation (generation reframing) (2026-06-02)

§1–§10 give the *engine* (escalate past the sieve, discover+validate a beyond-sieve value)
and its *autonomous* trigger (the scheduled `*FrontierScheduleEnabled` jobs). But the
day-to-day **paper pipeline** never used any of it: `ConjectureMinerService` mines finite-window
inequalities `lhs ≤ k·rhs + c` from offsets ≤ 1,000,000 / windows ≤ 50,000 — all *inside*
NT_MAX_N — and `ResearchCycleService.publishConjectureInvestigation` framed every such paper as
an **in-range verification** ("does the bound hold on held-out windows the miner did not sample?",
checked by `verify_conjecture`, itself hard-capped to ≤ NT_MAX_N). So the corpus was 100%
known-result verification (Phase 23.8 finding): the papers neither needed nor used discovery,
because the *claim itself* never referenced a beyond-sieve quantity.

**Path B reframes generation, not the engine.** When
`app.research.conjectureInvestigation.frontierFramingEnabled` is ON, a `CONJECTURE_INVESTIGATION`
paper whose scalars map to a discovery domain is posed as an **asymptotic** question instead:

- **Domain mapping** (`ResearchCycleService.frontierDomainFor`, mirroring
  `MathToolSuite.recallBoundForTopic`): a `mertens`-named scalar (`mertens_delta`) → **MERTENS**
  (`M(x)=Σμ(n)`, corpus `mertens_M`, per-paper tool `discover_mertens_value`); a `divisor`-named
  scalar → **DIVISOR** (`D(x)=Σd(n)`, corpus `divisor_D`, per-paper tool `discover_divisor_value`).
  Everything else (ω/density) → `null` → **unchanged in-range framing**.
- **Reframed seed text** (`synthesizeFrontier{Hypothesis,Findings,Conclusion}`): the hypothesis
  asks whether the in-range bound *persists past the sieve ceiling*; the findings/conclusion steer
  the narrator to `discover_mertens_value` (compute M(x) at a chosen x>2,000,000) and/or
  `recall_frontier_knowledge` (read the accumulated frontier record), and insist that any
  beyond-sieve value is a **computed, moat-validated result, NOT a proof**.

This is flag-gated (default OFF; flipped ON 2026-06-02) and additive — with it OFF,
`publishConjectureInvestigation` behaves exactly as before. It only steers; the narrator already
has the discover/recall tools (offered to every math paper). **Pair it with the discovery flags**
(`discovery.runtimeExecEnabled` + the sidecar `ET_DISCOVER_EXEC=1`) so the tools the framing points
at are live — otherwise the narrator gets a "disabled" line and falls back to in-range evidence.

**Why this is the honest standalone half.** Path B needs no new oracle *for the domains that
already have one* (Mertens, divisor) — it bridges the existing engine into the generation pipeline.
It does **not** make ω/density papers use discovery: posing an asymptotic ω-bound that no tool can
validate beyond the sieve would be vacuous. Extending Path B to a new quantity (e.g. `Σω(n)`)
requires **Path A** first — a `ScaleValueSource`, a sidecar `quantity` oracle, a `BoundPredicate`,
and a `*FrontierService` — at which point `frontierDomainFor` gains a third branch and the same
generation reframing applies.

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
