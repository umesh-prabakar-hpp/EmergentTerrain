# Phase 30 — open-behavior generation: activating the dormant Stage-1 layer

**Status: DESIGN DOC LANDED (2026-06-14).** Proposal, not yet built. Sequences five stages (G1–G5)
that turn the conjecture generator from an *affine-bound factory over a fixed vocabulary* into an
*open-behavior probe generator*. The novelty blocker named by [[project_phase23_8_calibration]]
("corpus is 100% known-result verification; generation is the real blocker") is what this targets.

## The finding that reframes the work

The upstream generation layer **already exists** — Phase 30 Wave 5 "Stage-1 generation":
[`AnomalyMiner`](../../src/main/java/com/emergentterrain/research/conjecture/AnomalyMiner.java) (30.1),
[`ResidualStructureMiner`](../../src/main/java/com/emergentterrain/research/conjecture/ResidualStructureMiner.java) (30.2),
[`QualityDiversityArchive`](../../src/main/java/com/emergentterrain/research/conjecture/QualityDiversityArchive.java) (30.3),
`VocabularyExpansion` + `OperatorGrammar` + `MdlGate` + `SublinearGenerationBudget` (30.5),
and the result-frames `ConstantConvergence` / oscillation / growth. **All pure, tested, and flag-ON —
but log-only or admitting ~nothing in practice**, per the Wave-5 status table in
[`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md): 30.2 residual + 30.1 anomaly are "flag ON
(log-only)" (candidates logged, never promoted to topics); 30.5 vocabulary expansion is fully shipped
incl. P3b (2026-06-05) yet admits ~0; the live mine pass confirms the empty output:

```
conjecture mine pass — ... 0 composed (persisted) ...
vocabulary expansion — 60 candidate(s) probed, 0 admitted (budget 8), 0 new conjectures seeded
```

**Root cause (one sentence):** the affine `ConjectureEngine.seed()` enumerator is the *only* generator
whose output reaches the topic queue — the generative components run but their candidates are
**log-only (residual, anomaly), admit ~nothing (vocab-expansion: the post-W4 live pool gives the MDL
gate no real targets), or rarely clear the bar (composition)**. So the corpus is affine bounds on a
vocabulary that mixes settled objects (`mean_omega ~ ln ln x`, densities) with open ones
(`dirichlet_error`, `cramer_gap_ratio`), and the settled-flat-bound seeds dominate → trivial refutations.

## The reframe (governing principle)

> **Novelty lives in the residual** (what's left after the known law), **the exponent** (open growth
> rates), and **the exception** (anomalies). Each already has a built component; none feeds live topics.

This complements — and is the upstream sibling of — the now-complete *growth-anchor* arc
([[project_growth_anchor_milestone]]): that arc made *bounds on growing objects* tight, durable, and
captured; this program changes *what gets conjectured at all*, away from affine bounds on settled
objects. The openness taxonomy already exists, but as **prose in `conjectureDirective` step 9**
(ResearchNarrationService) — `dirichlet_error` tagged OPEN, `mean_divisor_excess` tagged "known
fallback". The investment lifts that taxonomy into the **generator** so the corpus is open-behavior-first
by construction instead of relying on the professor to pivot per paper.

**Convention (inherited):** every stage ships flag-gated **OFF** (`@Value … = false`,
[[feedback_flag_gated_dormant]]), flipped only after its acceptance bar; pipeline-wide pieces stay
domain-neutral ([[feedback_common_code_domain_neutral]]).

## The hard ceiling (stated up front, do not oversell)

- **Sieve cap x ≤ 2×10⁶.** No generation change resolves an asymptotic open problem from finite data.
  The realistic prize is a **novel computational *measurement* against an open problem** (empirical
  Dirichlet exponent, Mertens envelope at reachable x) — strictly more novel than re-deriving textbook
  constants, but **evidence, not proof**. Every stage's acceptance bar is framed as a measurement.
- **Validation gets fuzzier.** An open-behavior conjecture's result is a measurement-with-uncertainty,
  not held/refuted. The `verify_constant`/`verify_oscillation`/`verify_growth` frames + the panel
  novelty axis partly cover it; G4 addresses the acceptance criteria explicitly.
- This is a **multi-PR program** (≈ "finish Phase 30 Wave 5 + orient it"), not one change.

## Dependency order

```
G1 openness prior ──┬─────────────────────────────────────────────> (corpus shifts immediately)
                    │
G2 two-term schema ─┴─> G3 vocab-expansion admits on real targets ──> G4 QD/anomaly selection ──> G5 Dirichlet
   (promote 30.2)        (feed 30.5 the residuals)                     (keeps novelty)             flagship
```

G1 is independent and cheapest (start here). G2 promotes the already-built residual miner from log-only
to live conjectures — highest long-term leverage. G3 then gives vocabulary-expansion (already shipped +
ON, but admitting ~0) real residual targets to explain; the incremental re-seed (`585fb2b`) activates
any newly-admitted scalar without a wipe. G4 stops the corpus collapsing back to a monoculture. G5 is
the flagship demonstration once G2/G3 are live.

---

## G1 — Openness prior as data (start here)

**Status: BUILT 2026-06-14 (flag-gated, ON in yaml; acceptance pending next refresh).** `ScalarOpenness`
registry + a mine()-level drop of SLOPED affine bounds on settled-leading-order LHS with a flat-feature
rhs (keeps growth-anchor + k≈0 constant bounds), flag `opennessPriorEnabled`. Mirrors the
triviality/dimension filters; logs `N dropped (openness)` in the mine-pass summary. Tests:
ScalarOpennessTest (4). The MVP is the demotion filter; the promotion-weighting half (actively
prioritising open/second-order topics in selection) folds into G4.

**Goal:** stop generating affine bounds on settled-leading-order objects; preferentially generate
(i) residual/second-order targets and (ii) open objects.

**Build:** lift the `conjectureDirective` step-9 taxonomy into a machine-readable registry —
`{scalar → {SETTLED_LEADING_ORDER | SETTLED_CONSTANT(value) | OPEN_EXPONENT(range) | OPEN_ENVELOPE |
OPEN_CORRELATION}}`. The seed/selection path uses it to **demote** settled-leading-order affine seeds
(densities, raw `mean_omega`/`mean_divisor_count` flat bounds) and **promote** open objects
(`dirichlet_error`, `cramer_gap_ratio`, `mertens_delta` envelope, `twin_vs_prime_sq`) and second-order
residual targets. Reuses the existing topic selection (`ConjectureTopicProvider`) — a weighting, not a
new mechanism.

**Unblocks / closes:** the "affine bounds on settled ones" waste directly.
**Flag:** `app.research.conjecture-miner.opennessPriorEnabled` (OFF code / ON yaml after bar).
**Acceptance bar:** in a refresh, ≥ 60% of conjecture topics are open-behavior / second-order (vs the
current ~0%); no settled-leading-order *flat* bound published as a "result".
**Effort:** Low.

## G2 — Two-term conjecture schema `lhs ≤ k₁·rhs + k₂·z + c`

**Goal:** let the already-built residual miner's output become storable, verifiable conjectures.

**Context:** `ResidualStructureMiner` (30.2, flag-ON but **log-only**) fits `lhs ≈ β·rhs + α`, takes
`r = lhs − (α+β·rhs)`, and finds the scalar `z` whose association with `r` beats the permutation null —
"a newly discovered relation the primary law missed." The roadmap: *"Persisting `lhs~rhs+z` needs a
multivariate claim schema (follow-on)."* That schema gap is why the candidates stay in the log instead
of becoming conjectures — the single blocker.

**Build:** extend the `Inequality`/`Conjecture` entity + `ConjectureVerifier` + `verify_conjecture`
tool to a two-term form, and **promote** the residual miner's top candidate from a log line to a seeded
conjecture. Second-order structure (the residual) is exactly where open behavior lives — this is the
generative engine that surfaced 2γ, B₁, the Erdős–Kac variance, but currently only by hand in the directive.

**Unblocks / closes:** `ResidualStructureMiner` (30.2) end-to-end (log-only → promoted).
**DDL note:** the new columns/constraints require a manual `ALTER` — the classifier blocks autonomous
DDL ([[feedback_ddl_nonnull_columns]]); hand the operator the SQL.
**Flag:** `app.research.conjecture-miner.twoTermEnabled` (OFF code / ON yaml).
**Acceptance bar:** a residual-discovered `lhs ≤ k₁·rhs + k₂·z + c` is seeded, verified on held-out
windows, and survives a pass — a relation absent from either single-term parent.
**Effort:** Medium (schema + verify path + DDL).

## G3 — Make vocabulary-expansion actually admit (feed 30.5 real targets)

**Goal:** let the grammar grow the vocabulary toward what the data needs, including open composites.

**Context (corrected):** the persistence wiring `VocabularyExpansion` + `OperatorGrammar` + `MdlGate` +
`DerivedScalarEntity` is **fully shipped (P3b, 2026-06-05) and flag-ON** — the gap is *not* persistence.
The live log shows "60 probed, 0 admitted, 0 seeded" because the MDL gate has **no real targets to
explain**: the post-W4 live pool is empty, so admission falls back to counterexample-retired bounds and
nothing earns its keep. The grammar can compose open scalars (fitted-residuals, normalized exponents)
but there is no *residual structure* for them to explain until residuals are first-class.

**Build:** feed the admission gate the **G2 residual targets** (the `lhs ~ rhs + z` structures the
residual miner finds), so a grammar candidate that explains a real residual is admitted; re-tune the
budget/MDL threshold if admission stays at 0 on genuine targets. The incremental re-seed (`585fb2b`)
already activates any newly-admitted scalar without a wipe.

**Depends on:** G2 (without storable residuals there is nothing to explain — this is why G3 follows G2).
**Unblocks / closes:** 30.5 admission in practice; the
[`generation-engine-library-learning`](generation-engine-library-learning.md) reference class
(DreamCoder-style abstraction admission) starts admitting.
**Flag:** reuse `vocabularyExpansionEnabled` (already ON); add a target-source toggle if needed.
**Acceptance bar:** ≥ 1 grammar-admitted scalar (admitted against a real residual target, not a retired
fallback) persists across passes and appears in a published conjecture; vocabulary grows then plateaus.
**Effort:** Medium.

## G4 — QD-archive + anomaly selection (keep novelty)

**Goal:** stop the corpus collapsing to one family; retain diverse open-behavior elites; treat
regime-failures as findings.

**Build:** wire `QualityDiversityArchive` (30.3, built but not wired into live selection) into
selection/retention with a behavioral cell key that includes a **novelty/openness axis** (so open
probes occupy their own cells and aren't out-competed by a flood of near-identical bounds). Promote
`AnomalyMiner` (30.1, flag-ON but **log-only**) output from a log line to an investigation topic — a
regime where a robust pattern fails is open behavior, not just a retirement.

**Unblocks / closes:** 30.3 (wire into selection) + 30.1 (log-only → promoted to topics).
**Flag:** `app.research.conjecture-miner.qdSelectionEnabled`, `…anomalyMiningEnabled` (the latter exists).
**Acceptance bar:** over N refreshes, conjecture-paper topic diversity (distinct behavioral cells)
rises and does not regress to a single-LHS monoculture (cf. the mean_omega monoculture, review-b).
**Effort:** Medium.

## G5 — The Dirichlet exponent (flagship open target)

**Goal:** one genuinely-novel *measurement* against a 175-year-open problem.

**Build:** a typed GrowthExponent conjecture on `dirichlet_error` (= Δ(x)/x^{1/4}, Δ the divisor-
summatory error). Fit the empirical exponent θ̂ of |Δ(x)| over the reachable range and report it against
the open bound (θ conjectured ¼, proven ≈ 0.314 / Huxley 131/416). Uses the `verify_oscillation` /
growth-exponent frame the directive already points at as "GENUINELY NOVEL … against an unsolved problem".

**Honest framing (mandatory in the paper):** finite-range computational evidence, sieve-capped at
2×10⁶, **not** a resolution — but a novel measured object, not a textbook re-derivation.
**Flag:** none beyond G1/G2 being live.
**Acceptance bar:** a published paper reports θ̂ with its fit uncertainty and positions it against the
open bound, scoring panel-novelty ≥ 0.7 (the calibration target from [[project_phase23_8_calibration]]).
**Effort:** Low once G1/G2 exist.

---

## Closing condition

This program closes when: G1–G4 are flag-ON and burned-in; the conjecture corpus is open-behavior-first
(G1 bar held across ≥ 3 refreshes); the two-term + grammar-expansion generators produce published
conjectures absent from any single-term parent (G2/G3 bars); and at least one open-target measurement
(G5) clears the panel-novelty ≥ 0.7 calibration bar that has been empty since Phase 23.8. Until then the
status stays DESIGN DOC LANDED with per-stage progress tracked above.

## Cross-references

- Roadmap: [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md) (this is the activation of Wave 5).
- Vocabulary growth: [`vocabulary-expansion-mdl.md`](vocabulary-expansion-mdl.md),
  [`generation-engine-library-learning.md`](generation-engine-library-learning.md).
- Novelty target: [`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md).
- Memory: [[project_phase23_8_calibration]] (generation is the blocker), [[project_second_order_novelty]]
  (push past leading order), [[project_growth_anchor_milestone]] (the completed sibling arc),
  [[project_scientist_qualities_northstar]] (long-term capability over quick metric wins).
