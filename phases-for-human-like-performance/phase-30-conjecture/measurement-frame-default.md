# Phase 30 — the claim-type lever: measurement-frame as the default for open objects

**Status: SCOPE (2026-06-16, after tag `claim-type-shift`).** The object-substrate arc proved novelty is
gated by **claim type**, not object inventory: a genuinely-new, literature-absent object
(`richly_divisible_density`, P-77627670) investigated *as a topic* scored Novelty **0.22** — the same
affine proxy-wrapper level as settled objects (run-5 review). Every >0.4 paper this session was instead a
**measurement / asymptotic-frame** claim (`dirichlet_error` O(1) envelope 0.41; `omega_Omega_gap` →
Σ1/p(p−1) ≈ 0.773, 0.53). This lever makes the measurement frame the **default** for the objects that have
a genuine asymptotic, instead of the professor's occasional pivot.

## The finding that defines the lever

The pipeline's default claim is the affine bound `lhs ≤ k·rhs + c` — a *proxy-wrapper* relating the object
to near-constant scalars, which the panel reads as "no structural advantage over a single constant" → ~0.2
novelty, whatever the object. The high-novelty claims are **intrinsic measurements**: does the object
converge to a constant, oscillate within an envelope, or grow at an exponent — a claim *about the object
itself*, which for an **open** object is a genuine sieve-capped result against an open problem.

The recipe the >0.4 papers actually had is **(open object) × (measurement frame)** — both. This lever
supplies the frame; it cannot supply open well-posed objects (see Honest limits).

## Current state (touchpoints, confirmed)

- `ScalarOpenness` already has `PRIORITY_TARGETS` (open/second-order objects) and `OSCILLATING_TARGETS =
  {dirichlet_error}`. **This is where the measurement registry lives.**
- `ResearchCycleService.synthesizeConjectureHypothesis` frames EVERY conjecture topic as *"this paper
  investigates whether the bound holds on held-out windows"* — always a bound frame.
- `ConjectureEngine.seed` seeds **affine bounds** `lhs ≤ k·rhs + c`; measurement happens only when the
  professor *pivots* to `verify_constant`/`verify_oscillation`/`verify_growth` (the directive nudges this
  per-scalar for a few: `mean_divisor_excess→2γ`, `omega_ek_ratio→1`, `dirichlet_error→oscillation`).
- The measured-invariant write-back (lever D) already PERSISTS `CONSTANT`/`OSCILLATION` survivors and the
  miner skips re-testing them as bounds — so the *output* side understands measurements; the *topic/frame*
  side does not.

So the gap: the measurement frame is a pivot, not the **default topic frame**. The professor often
defaults to (or is framed into) an affine bound and produces a 0.22 proxy-wrapper even on an object whose
interesting behaviour is a limit/envelope.

## The lever — measurement-frame as default

### Stage 1 — SHIPPED 2026-06-16 — a measurement registry + framing default

Built: `ScalarOpenness.MEASUREMENT_FRAMES` (the registry below) + `ResearchCycleService
.synthesizeConjectureHypothesis` reframes a registry object as the measurement when
`app.research.measurement-frame-default.enabled` (OFF code / ON yaml). Frame-only; seeding unchanged.
Tests: ScalarOpennessTest +1, TwoTermFramingTest +4. Acceptance is the next run (below).

1. **Registry** in `ScalarOpenness`: `MEASUREMENT_FRAME: Map<String, Frame>` where `Frame ∈
   {CONSTANT(target?), OSCILLATION, GROWTH(scale,target)}`. Seed it from what's already known:
   `dirichlet_error→OSCILLATION` (O(1) envelope, no scale_rhs), `omega_Omega_gap→CONSTANT(0.77316)`,
   `mean_divisor_excess→CONSTANT(2γ)`, `omega_ek_ratio→CONSTANT(1)`, `mean_omega/mean_Omega→GROWTH(ln ln
   x)`, `max_divisor_count→GROWTH(wigert)`, `max_prime_gap→GROWTH(log_sq)`. These are the open/second-order
   objects with a genuine asymptotic.
2. **Framing default**: when the topic's `lhs` is in `MEASUREMENT_FRAME`,
   `synthesizeConjectureHypothesis` frames the investigation as the **measurement** — *"measure whether
   `lhs` converges to C / oscillates within an O(1) envelope / grows as the named scale, using
   verify_constant/oscillation/growth — this is the load-bearing claim, not an affine bound"* — instead of
   *"does the bound hold."* The professor starts in the right frame.
3. **Flag** `app.research.conjecture-miner.measurementFrameDefault` (OFF code / ON yaml), so it A/Bs
   cleanly and the affine-bound path is bit-identical when off.

This is the smallest change that tests the thesis: it doesn't touch the miner's seeding, just the *frame*
the professor is handed for registry objects. Cheap, reversible.

#### Stage 1b — the RANGE pattern: a geometric scale ladder (run-6 follow-on)

Run 6 surfaced a gap: the measurement frame works, but the professors kept picking failure-region windows
(~1–2M) and never exercised the extended sieve reach (now ~2e9), so the measurements weren't *strong*
(omega_Omega_gap NULL'd because 0.77316 wasn't converged at 2e7). The default range guidance is
bound-oriented (held-out offsets ≤ 1e6, anchored at the failure region) — correct for "does it hold,"
wrong for "what's the limit."

`measurementHypothesis` now appends a **range pattern** to every measurement frame:
> sample a **GEOMETRIC scale ladder** of `window_start` across the trusted range (~1e5, 1e6, 1e7, 1e8, up
> to near the ceiling, each ~10× the last) as **separate calls**, weighting the high end where the
> measurement is tightest, and report how the result moves across scales — the trajectory and its rate.

"Geometric" = constant *multiplicative* step (log-spaced), because the asymptotics move slowly in x
(ln ln x, x^¹ᐟ⁴) so the interesting variation is across orders of magnitude; arithmetic spacing stays at one
scale and wastes the reach. This does two things at once: (a) it forces the professor out to 1e8–1e9
(uses the Stage-2 reach), and (b) the **cross-scale convergence trajectory** is a genuinely stronger claim
than a single window — the thing that should lift the open-object novelty. Cheap (~80 ms per 5000-window at
1e9, ~5 separate calls). Variety is deliberately left to the professor (decade vs frontier-weighted vs
two-anchor) per how fast the object converges; the framing gives the default and the rationale, not rigid
values.

### Stage 2 (if Stage 1 lands) — seed measurement topics first-class

Promote measurement to a real topic kind: the miner seeds a `MEASUREMENT` topic for registry objects
(alongside/instead of the affine-bound seed), and selection (`diversifiedNextN`) prefers it for those
objects. Down-weight affine-bound topics whose `lhs` is a registry object (the proxy-wrapper that scores
0.22). This makes the corpus measurement-led for open objects rather than relying on the professor pivot.

### Stage 3 (well-posedness coupling) — stop framing bounds on representation-dependent densities

The panel repeatedly flags window-dependent densities ("no canonical meaning"). For an object that is NOT
well-posed (no proven window-independent limit — e.g. the new `richly_divisible_density`), neither a bound
NOR a naïve measurement is novel. Extend the well-posedness gate so such objects are either skipped or
framed *with the representation-dependence stated as the result* — don't let them generate low-novelty
papers either way.

## Acceptance (Stage 1, next run)

Open registry objects (`dirichlet_error`, `omega_Omega_gap`, `cramer_gap_ratio`, …) investigated via the
measurement frame should score Novelty **>0.4 consistently** (multiple papers, not just the one-off
dirichlet pivot), and the affine proxy-wrapper papers on those objects should drop. **If only `dirichlet`
clears 0.4**, the conclusion is that novelty is **supply-limited** (few open+well-posed objects) — and the
honest frontier becomes the **segmented-sieve reach** (object-substrate-expansion.md Stage 2), to make
that one measurement as strong as possible, not more frames.

## Honest limits (state up front)

1. **Bounded by the supply of open + well-posed + measurable objects** — which is small (`dirichlet_error`
   is essentially the flagship). The lever makes the *few* open objects score novel reliably; it does NOT
   make the corpus novel.
2. **Settled objects' measurements are rediscoveries** (squarefree → 6/π²) → still low novelty (but a
   correct validation, not a proxy-wrapper).
3. **Representation-dependent objects** (the new densities) have no canonical limit → a measurement frame
   doesn't rescue them (Stage 3).
4. **No proof.** Still "at reachable x" — the prize is a novel measurement against an open problem, not a
   theorem. Same hard ceiling as the whole program.

## Why this is the right next lever (and a hypothesis, not a guarantee)

It's grounded — it's literally what the high-novelty papers did — and it's a cheap reframing of existing
tools, not a new capability. But the (open × measurement) recipe means its payoff is real only where open
objects exist, so Stage 1 is a **test**: one run tells us whether novelty is frame-limited (the lever
works) or supply-limited (pivot to sieve reach). Either answer is decisive. This is the measure-first
discipline that saved the vocab-expansion generator and the object generator from being built on a wrong
premise.
