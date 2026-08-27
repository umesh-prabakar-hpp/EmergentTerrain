# Why the mined conjectures are trivially designed — and it's the engine, not any one conjecture

This note documents a structural finding about the EmergentTerrain conjecture flow: the
`mertens_per_sqrt_W` bound that room `b72c4e10` keeps refuting is **not a special trivial case**. It is
one of **212 identically-shaped mined inequalities**, all produced by the same blind correlation-miner.
The triviality lives in the engine, not the conjecture.

For the mertens-specific deep-dive (formula, literature comparison, resource scaling) see
[`mertens-conjecture-vs-literature.md`](mertens-conjecture-vs-literature.md). This note is the
generalisation.

Source of truth: `research/conjecture/ConjectureEngine.java` (mining), `MathInvariants.java`
(vocabulary), `BindingRegimeProbe.java` (why only some surface), `research/conjectures-math.json`
(the 212 stored inequalities).

---

## 1. The mechanism: blind pair-mining

`ConjectureEngine.seed` enumerates **every ordered pair** of vocabulary scalars and keeps the tightest
line `lhs ≤ k·rhs + c` that holds across the run's sample windows (`ConjectureEngine.java:63-78`):

```java
for (String lhs : vocabulary) {
    for (String rhs : vocabulary) {
        if (lhs.equals(rhs)) continue;
        if (Math.abs(pearson(xs, ys)) >= NEAR_IDENTITY_CORR) continue;  // drop near-identities
        double[] fit = tightestKC(xs, ys);
        out.add(new Inequality(lhs, rhs, fit[0], fit[1]));              // (lhs, rhs, k, c) — no domain
    }
}
```

There is no notion of *why* two quantities should relate — it curve-fits whichever windowed scalars
happen to correlate. The vocabulary (`MathInvariants.BASE`/`DERIVED`) is all O(1) windowed averages:
`abundant_density`, `deficient_density`, `prime_density`, `happy_density`, `mean_omega`, `mean_Omega`,
`mean_divisor_count`, `max_divisor_count`, `mertens_delta`, plus derived `mertens_per_sqrt_W`,
`log_window_start`, `log_window_size`, `sqrt_window_size`, `omega_Omega_gap`, …

---

## 2. The evidence

From `research/conjectures-math.json`:

- **212 mined inequalities**, across 24 distinct `lhs` × 25 distinct `rhs` scalars.
- **`k` is almost always snapped to 2.0** (or 0.0) — so each "conjecture" is effectively a
  **one-parameter fit of the intercept `c`**.
- **All carry `refuted=False`** — none are ever retired (the "0 retired (counterexample)" loop is
  universal, not mertens-specific): refutations happen in published papers and are never fed back.

> **Count note.** `212` is the total in `conjectures-math.json` (the persisted, all-time / cross-room
> mined set). A single live mine pass logs far fewer *active* survivors (≈16 in room b72c4e10:
> `16 survivors … 0 retired … 0 dropped`). The triviality argument holds at both counts — every entry
> is the same data-mined linear shape and none is retired — but where this note says "the store" below
> it means the persisted 212, of which only the active survivors are actually served to bots.

Representative mined entries (verbatim shape):

```
abundant_density   <= 2.0 * prime_density       + 0.094
abundant_density   <= 2.0 * happy_density        - 0.0198
abundant_density   <= 2.0 * mean_omega           - 4.793
abundant_density   <= 2.0 * mean_divisor_count   - 20.588
abundant_density   <= 0.0 * mertens_delta        + 0.25146     (k=0 → constant bound)
mertens_per_sqrt_W <= 2.0 * log_window_start     - 7.7621      (the one that gets investigated)
```

---

## 3. Three trivial sub-types — same engine, different symptom

| Sub-type | Example (from the pool) | Why trivial | Symptom in corpus |
|---|---|---|---|
| **Trivially-false** (start-monotone) | `mertens_per_sqrt_W ≤ 2.0·log_window_start − 7.7621` | RHS goes negative below x≈7600 → any positive LHS violates by construction | the **only** ones investigated (binding-regime probe) → "REFUTED" papers |
| **Tautological-true** (avg vs avg) | `abundant_density ≤ 2.0·prime_density + 0.094`; `… ≤ 2.0·mean_omega − 4.793` | two correlated O(1) averages; bound holds with slack everywhere | "observational baseline" boilerplate conclusions |
| **Vacuous constant** (k=0) | `abundant_density ≤ 0.0·mertens_delta + 0.25146` | literally `abundant_density ≤ 0.2515` — a fitted ceiling on one scalar | no content; not surfaced |

Why only mertens-type gets investigated: `BindingRegimeProbe.appliesTo` returns true only for
`k > 0 && rhs == "log_window_start"` (start-monotone). Of the 212, only the `log_window_start` bounds
qualify, so they alone become "conjecture investigation" topics and generate the refutation papers; the
other ~200 persisted entries sit in the store, never investigated, never retired (see the §2 count note
on persisted-212 vs active-survivors).

---

## 4. Why this is "trivial" — the four moves

1. **No mechanism.** Blind curve-fitting over scalar pairs, not a structural reason a relationship
   should hold. Real conjectures come from zero density of ζ, a generative model, an open question.
2. **Flat vocabulary.** All BASE scalars are O(1) windowed averages hovering near known constants
   (`abundant_density → ~0.2476`, `mean_omega → log log n`, …). Bounding one O(1) average by an affine
   function of another is structurally vacuous.
3. **Quantized slope.** `k` snaps to 2.0/0.0 → the conjecture is a one-parameter intercept fit, the
   lowest form of "discovery."
4. **No domain / no retirement / `MAX_N`=2×10⁶ cap.** Stated universally (the `Inequality` record has no
   domain field), never retired on paper-refutation, and confined to a regime far below where any real
   number-theoretic phenomenon lives.

---

## 5. How to move to real research territory (applies to all 212, not just mertens)

1. **Replace blind pair-mining with mechanism-driven hypotheses.** Generate conjectures from a *reason*
   — a known asymptotic to sharpen, a generative model's prediction, an actual open question — at the
   LLM formulation stage, not from "which two of my 24 scalars correlate." (Generation, not retrieval,
   is the standing blocker.)
2. **Enrich the vocabulary toward quantities with open behavior.** Flat O(1) averages cannot host a
   real conjecture; target objects whose growth/extrema are genuinely unknown (cumulative `M(x)/√x`,
   prime-gap records, …).
3. **Fix the structural trivializers (engine-level, domain-neutral):** record the fitted domain and
   state restricted claims; sample the binding regime so false bounds die at mine time; **retire on
   refutation** so the pool turns over; **gate out tautologies** (k≈0, holds-with-huge-slack,
   near-constant) as non-results so the corpus stops being 100% trivial-bound verification.
4. **Pick decidable targets in reachable regimes.** "Verify a bound to a record height," "find a new
   extremum," "localize a transition threshold" are real, checkable contributions; use sublinear
   algorithms (Deléglise–Rivat) where the cumulative object is the target. Feasible to ~10¹⁶; the
   counterexample regime never is (see the resource table in the mertens note).
5. **Reward the residual, not the fit.** A real result is the reproducible structure left after
   subtracting what was already expected — exactly what the revision-stage novelty-preservation rule
   protects. The miner currently rewards the opposite (tightest fit = most trivial).

Items 1–2 are the hard, high-value changes (better *generation*); 3–5 are the engine plumbing that
stops the pool from being 212 curve-fits. None of it is mertens-specific.

---

## 6. Relation to Phase 30 (`phase-30-genuinely-novel-claims.md`)

This note is the **empirical diagnosis**;
[`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md) (same folder) is the
**prescription**. Phase 30's own table calls today's miner one that "surfaces regularities that hold" —
exactly the triviality measured here. (When first written, Phase 30 was a *proposal, not queued, all
flags OFF* — the room ran pre-Phase-30, which is why the pool was 212 curve-fits. As of 2026-06-04 most
of it has shipped: Waves 0–3 entirely ON, W4 private-holdout + replication ON, W5 composition +
residual-structure + anomaly mining ON; see [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md) for the
authoritative wave status.) The fixes map almost one-to-one:

| Finding here (§3–§5)                                   | Phase 30 remedy                                           |
|--------------------------------------------------------|-----------------------------------------------------------|
| Mines only regularities that hold, never exceptions    | **30.1** Anomaly mining (invert the miner)                |
| Rewards the fit, not the residual                      | **30.2** Residual-structure discovery                     |
| Flat O(1) vocabulary                                   | **30.5** Vocabulary / representation expansion (MDL-gated)|
| No mechanism (freeform unverified prose)               | **30.7** Mechanism rigor (per-`Mechanism kind:` hooks)    |
| Never retired; no novelty grounding                    | **30.6** adversarial promotion + **30.4** literature grounding |
| Three trivial sub-types (§3)                           | Phase 30's three "misses" (trivial-identity, tolerance-band, rediscovery) |

**One correction to Phase 30's baseline:** 30.1 assumes today's miner "drops on counterexample." In
practice the mine pass logs **0 retired** every cycle — the drop arm barely fires for start-monotone
bounds because the miner never samples the binding regime and paper-refutations are not fed back (§2).
So the "discarded arm" 30.1 wants to harvest is nearly empty; fixing retirement is a prerequisite.

**Already shipped:** 30.7's first step (the self-declared `Mechanism kind:` tag) is live — observed in the
b72c4e10 batch (P-20 `asymptotic_intuition`, P-24 `empirical_fit`) — but, as Phase 30 states, it is
unverified, matching the "no mechanism" finding above.

---

## 7. Update — structural-trivializer fixes shipped (2026-06-04)

A `/paper-review`-driven session shipped several of the §5.3 engine-level, domain-neutral trivializer
gates (full set + commit refs: [`../../archive/2026-06-04-pipeline-hardening.md`](../../archive/2026-06-04-pipeline-hardening.md)).
Most relevant to this note:

- **Window-geometry-LHS exclusion.** A newer degenerate shape had appeared — a window-geometry scalar
  as the conjecture **LHS** (`log_window_start ≤ 2·log_window_size − c`, `log_window_start ≤
  2·max_divisor_count − c`; b72c4e10 P-b72c-86/87). A free harness coordinate can't be the bounded
  *subject*. `ConjectureTrivialityFilter.dropReason` now drops any conjecture whose LHS is in
  `WINDOW_GEOMETRY_SCALARS` (`isWindowGeometrySubject`), **and** — the decisive gate —
  `ConjectureMinerService.nextN` filters the investigation queue (both tiers, incl.
  counterexample-retired) through `dropReason`. The live-store `reduce()` alone missed these because a
  degenerate geometry bound is counterexample-retired in its seeding pass *before* triviality runs, then
  served from tier-1 (this is why §3's start-monotone bounds still surfaced). Geometry-as-**RHS** is
  deliberately kept — that's the binding-regime case (§3 row 1) the probe exists to investigate.
- Live result: 0 `conjecture/log_window_*` investigations post-fix; the queue shifted toward
  content-vs-content bounds.

These are the §5 item-3 "gate out the structural trivializers" plumbing — necessary but, as §5 says, not
sufficient: the standing blocker remains *generation* (items 1–2), not gating.
