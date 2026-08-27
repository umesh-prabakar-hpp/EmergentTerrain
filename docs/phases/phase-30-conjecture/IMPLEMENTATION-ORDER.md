# Phase 30 — implementation order

Dependency- and leverage-ordered roadmap that sequences the original Phase 30 sub-items
([`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md)), the engine-triviality
fixes ([`conjecture-engine-triviality.md`](conjecture-engine-triviality.md),
[`mertens-conjecture-vs-literature.md`](mertens-conjecture-vs-literature.md)), and the external-pattern
addenda — the *process* layer ([`phase-30-external-patterns.md`](phase-30-external-patterns.md)), the
*content* layer ([`phase-30-simulation-patterns.md`](phase-30-simulation-patterns.md)), and the
*pipeline/production* layer ([`phase-30-arc-patterns.md`](phase-30-arc-patterns.md), AutoResearchClaw
review 2026-06-04 — figures, metrics manifest, refine/pivot, lesson overlay; proposals, not yet
sequenced into the waves below).

**Governing principle:** *reduce before you generate, represent before you select, verify before you
promote.* Most of Phase 30's value is unlocked by first making the existing conjecture pool small and
meaningful — the cheap rejection gates retire the 212-flood and shrink the multiple-comparisons
denominator that every later (expensive) stage pays for.

**Convention:** every wave ships flag-gated **OFF** (`@Value … = false`), flipped only after its
acceptance bar; pipeline-wide pieces stay domain-neutral (AGENTS.md constraint). Pattern IDs below refer
to the numbered lists in the two addenda (e.g. `sim #5` = simulation-patterns item 5; `ext #2` =
external-patterns item 2).

---

## Dependency DAG (text)

```
W0 retirement + domain field ─┬─> W1 cheap rejection gates ─┬─> W3 selection/diversity ─┐
                              │                             │                           │
                              └─────────> W2 derivation graph ──┬──> W3 (clade-value)    ├─> W5 generation
                                                                ├──> W5 composition      │
                                                                └──> W6 RAF closure      │
                              W4 evaluation hardening (null/replication/anti-gaming) <────┘
                                          │
                                          └──> W5 generation ──> W6 irreducibility+mechanism ──> W7 promotion
23.8 calibration (operator, run early) ───────────────────────────────────────────────> W7 literature grounding
```

---

## Wave 0 — Prerequisites (cheap, no deps; unblock everything)

Make the pool meaningful before adding machinery.

| Item | Source | Notes |
|------|--------|-------|
| **Investigation rotation** — mark a conjecture consumed when it is selected for a paper (`lastInvestigatedAt`); serve least-recently-investigated first so the queue rotates instead of re-serving the same refuted conjecture | this trace | fixes "every math paper is the same mertens refutation" |
| **Domain field on `Inequality`** (defer) — record fitted start-range; state domain-restricted claims so a start-monotone bound becomes a confirmed bound instead of perpetual refutation fodder | mertens §2 | fixes "stated universally / refuted at small start" |

> **Source-trace correction (2026-05-31).** The original framing here ("feed published refutations
> back"; "sample the binding regime") was wrong: retirement **already works** — the miner samples small
> starts (`ConjectureMinerService.RUNS` offsets include 100/1000/5000) and `ConjectureEngine.update`
> retires on counterexample (**76 retired** on a fresh seed). The "0 retired" steady-state log is benign
> (already-retired conjectures aren't re-loaded). The real bug is that the refuted (Tier-1) queue is a
> **stateless peek**: `nextN` re-serves the same most-recently-refuted conjecture every cycle with no
> consumption tracking, so with `paperShare=1.0` every math paper investigates the identical mertens
> bound. The fix is investigation rotation (above), implemented flag-gated as
> `app.research.conjectureMiner.investigationRotationEnabled`. The domain-field fix remains valid but is
> deferred (it changes `seed()`/`Inequality` shape and risks `ConjectureEngineParityTest`).

**Acceptance:** with rotation ON, consecutive conjecture papers cover **distinct** conjectures (not the
same mertens bound); `markInvestigated` sets `lastInvestigatedAt` on consume; ordering unchanged when OFF.

---

## Wave 1 — Cheap reference-free rejection gates (highest ROI)

Each is a standalone validator (same shape as the already-shipped `SinglePointSweepValidator`),
domain-neutral, no dependencies. Together they collapse 212 → a handful.

| Item | Source | Status | Kills |
|------|--------|--------|-------|
| **FIM identifiability gate** | sim #5 | ✅ flag ON 2026-06-03 | `ConjectureIdentifiabilityFilter` — for a slope-claiming bound (k≠0), drops it when the OLS slope's relative standard error over the window samples shows the data is consistent with k=0 (`identifiabilityEnabled`). |
| **Unit/dimensional-consistency gate** | sim #6 | ✅ flag ON 2026-06-03 | `ConjectureUnitConsistencyFilter` — all O(1)-bounded NT scalars ≤ k·window-magnitude (k>0); generalises `ConjectureDimensionFilter` from densities to the full bounded class (`unitConsistencyEnabled`). |
| **Copy/tautology suppression** | sim #2 | ✅ shipped | `ConjectureTrivialityFilter` (`trivialityFilterEnabled` ON). |
| **Convergence/equilibration gate** | sim #9 | ◐ partial | the window_size knob filter / mertens-pathology handling; not a standalone gate. |
| **Descriptor + dedup bitmask** | sim #11 + ext #1 | ◐ partial | `QualityDiversityArchive` + triviality dedup. |

**Acceptance:** on the current substrate, the gates reduce the surviving conjecture count by an order of
magnitude with zero false-retirement of a hand-checked control set of genuine relations.

---

## Wave 2 — Representation: the derivation graph (the pivot)

| Item | Source | Notes |
|------|--------|-------|
| **Findings-as-network with typed input/output ports + provenance edges** | sim #15 typed ports; ext #2 lineage | the substrate RAF closure (W6), composition (W5), and clade-value selection (W3) all require |

**Acceptance:** every accepted finding is a node with typed ports and `DERIVES_FROM` provenance; the graph
round-trips (serialise/reload) and a finding's full derivation lineage is reconstructable.

---

## Wave 3 — Selection & diversity bookkeeping (process layer)

Now that the pool is small and graph-structured, keep the *right* things.

| Item | Source | Status | Notes |
|------|--------|--------|-------|
| **Quality-diversity descriptor archive** (one elite per `(variables, derivation-type, mechanism-kind)` cell) | ext #1 | ✅ shipped | `QualityDiversityArchive` |
| **Clade/lineage-value selection + pseudo-count smoothing** (rank by descendant productivity; fit = eligibility gate only) | ext #2, ext #6 | ✅ flag ON 2026-06-03 | `CladeValueSelector` — ranks the live (Tier-2) investigation queue by clade-value (descendant productivity via `derivedFrom`, Laplace-smoothed) × inverse-popularity (1/(1+cell pop), cell=lhs); replaces rank-by-fit in `nextN` (`cladeValueSelectionEnabled`). Clade term strengthens as composition deepens the graph. |
| **Sublinear generation budget** (cap new conjectures as `n_validations**α`, α<1) | ext #4 | ✅ flag ON 2026-06-03 | `SublinearGenerationBudget` — the composition cap becomes `floor(n_validations^α)` (n_validations = cumulative confirmedRuns over the live pool), clamped to `compositionPersistCap` (`sublinearBudgetEnabled`, α default 0.5). |
| **Tiered cheap→expensive gate ordering** | ext #5 | ◐ de facto | `mine()` already orders cheap (triviality/dimension/unit) before expensive (FIM/null/replication); not formalised as a config |

**Acceptance:** selection samples parents by clade-value × inverse-popularity (not tightest fit); the
archive holds one elite per cell; generation rate is sublinear in validations performed.

---

## Wave 4 — Evaluation hardening (verdict side, before trusting any novelty)

You must judge novelty honestly before producing it, or Stage-1 generation just makes new noise.

| Item | Source | Status | Notes |
|------|--------|--------|-------|
| **Generative stochastic null + Fano/CV floor** | sim #8 | ✅ flag ON 2026-06-04 | `ConjectureStochasticNull` — permutation-tests \|Spearman ρ\| of lhs↔rhs vs the rhs-shuffled no-effect null + CV floor; wired into `mine()` (`stochasticNullEnabled`); emits the per-conjecture p-value the MCC slice consumes. A/B passed (see flip status below). |
| **Multiple-comparisons correction** (Bonferroni/BH) | 30.1 guard | ✅ flag ON 2026-06-04 | `MultipleComparisonsCorrection` — Bonferroni (FWER) / Benjamini-Hochberg (FDR, default) over the null p-value family; wired into `mine()` two-phase (`multipleComparisonsEnabled`; requires the null), so a bound is kept only if its H0 is rejected at the corrected family level over the W1-reduced denominator. No separate shadow path by design — its A/B is the null's. |
| **Private validator the generator can't read + gaming-rate metric** | ext #3 | ✅ flag ON 2026-06-03 | `PrivateHoldoutValidator` — re-checks each surviving bound on PRIVATE windows held out from both the fitter and the `verify_conjecture` prompt; "passed public, failed private" bounds are retired and the gaming rate (failed/tested) is logged. Wired into `mine()` (`privateValidatorEnabled`, default OFF). |
| **Ensemble/seed replication + stochastic-pass semantics** | sim #10, MD | ✅ flag ON 2026-06-03 | `ConjectureReplicationValidator` — K-fold CV of the intercept (refit `c` per fold for the fixed slope `k`, test out-of-fold); the pass rate (folds-held / K) is the stochastic-pass score; below `replicationMinPassRate` → retired. Deterministic windows → the ensemble is the folds, not RNG seeds. Wired into `mine()` (`replicationValidatorEnabled`, default OFF). |

**Acceptance:** a claim only scores on held-out data against a generated null; a known-trivial relation
and a known-fabricated value are both correctly rejected; the gaming-rate metric is emitted.

**Lane COMPLETE 2026-06-02** — all four components shipped flag-gated OFF (stochastic null →
multiple-comparisons correction → private holdout/gaming-rate → replication). Each ships dormant;
the A/B (zero false-retirement on a hand-checked control set) gates flipping them on for steady state.

**Shadow mode (2026-06-03, the A/B enabler):** `app.research.conjectureMiner.w4ShadowEnabled` — when ON,
any W4 gate whose own flag is OFF runs and LOGS `W4 shadow — WOULD-RETIRE <conjecture> (reason …)`
without retiring (no `persistRetired`, corpus unchanged). Turn it on in production, collect the
would-retire log, hand-check for false-retirement, then flip the individual gate once clean. Verified
by `ConjectureMinerServiceTest.w4ShadowModeLogsButRetiresNothing` (no W4 reason persisted under shadow).

**Flip status (2026-06-03):** `privateValidatorEnabled` and `replicationValidatorEnabled` flipped ON
after the shadow A/B (0 retired on the live pool — the two flagged `happy_density` bounds each breach
only one private window and are spared under the ≥2-window rule; see the yml comments).

**Flip status (2026-06-04) — W4 complete:** `stochasticNullEnabled` + `multipleComparisonsEnabled`
flipped ON. The would-retire log did **not** need a shadow re-arm — the 2026-06-03 shadow run already
captured it (`logs/archive/et-app-20260603-133911.log`, 16 mine passes, stable set): 7 distinct
`stochastic_null` lines, all flat-O(1) density cross-fits (`deficient/happy_density` vs
`mertens_per_sqrt_W`/`prime_density`; `omega_Omega_gap` vs `happy/abundant_density`/`mertens_delta`;
one `rhs_near_constant_cv` tautology). Hand-checked 2026-06-04: zero genuine relations among them →
zero false-retirement, A/B bar met. MCC flips with the null (it family-corrects the null p-values;
no separate shadow path in `mine()` by design). All four W4 gates are now live.

---

## Wave 5 — Generative novelty (Phase 30 Stage 1)

Claim generation, on a clean pool with honest evaluation.

| Item | Source | Status | Notes |
|------|--------|--------|-------|
| **30.3 compositional derivation** (compose + flatten) | sim #15, 30.3 | ✅ shipped (flag) | `ConjectureComposer` — sound transitive composition with provenance; derived bound is on a new pair (absent from both parents); wired via `compositionPersistEnabled`. |
| **30.2 residual-structure discovery** (subtract known law, beat null) | 30.2 | ✅ flag ON 2026-06-03 (log-only) | `ResidualStructureMiner` — removes the OLS rhs-trend, tests whether the residual tracks another scalar beyond the W4 null; probes the most-confirmed live bounds in `mine()` (`residualStructureEnabled`), logs candidates. Persisting `lhs~rhs+z` needs a multivariate claim schema (follow-on). |
| **30.1 anomaly mining** (keep the exceptions) | 30.1 | ✅ flag ON 2026-06-03 (log-only) | `AnomalyMiner` — surfaces counterexample-refuted bounds that held ≥ `anomalyMinConfirmed` runs (held-out evidence) then broke surprisingly hard (exp tail vs confirmed slack), surviving an MCC over the family; probed in `mine()` (`anomalyMiningEnabled`), logs candidates. |
| **30.5 vocabulary expansion via closed operator grammar, MDL-gated** | sim #14, 30.5 | ✅ flag ON 2026-06-05 (A/B live) | [`vocabulary-expansion-mdl.md`](vocabulary-expansion-mdl.md). Fully shipped 2026-06-03: `OperatorGrammar` (P1) → `MdlGate` (P2) → `VocabularyExpansion` admission (P3a) → `DerivedScalarEntity`/repo persistence + `materialize` extraction + new-pair seeding (P3b). Minimal multiplicative depth-1, held-out-confirmed residual-variance MDL, separate table, non-recursive. **Flipped ON 2026-06-05** with the anomaly-stratum target fallback (the post-W4 live pool is empty, making targets/budget a silent no-op; targets now fall back to counterexample-retired bounds with confirmedRuns ≥ anomalyMinConfirmed). A/B = hand-check every admitted scalar in the next sessions' logs + `derived_scalars` rows for spurious inflation. |

**Acceptance (per 30.x bar in the proposal):** ≥5 surviving anomalies past held-out re-check + MCC;
a residual form beating the linear null on held-out data; one composition paper whose hypothesis is
absent from both parents.

> **Activation (2026-06-14):** the Wave-5 components above are flag-ON but **log-only / admit ~0** —
> their candidates never reach the topic queue, so the corpus is still affine bounds on settled objects
> ([[project_phase23_8_calibration]]). [`open-behavior-generation-activation.md`](../archive/open-behavior-generation-activation.md)
> sequences the five stages (G1–G5) that promote them to live open-behavior topics: openness prior,
> two-term schema (promote 30.2), feed 30.5 real residual targets, wire QD/anomaly selection, and the
> Dirichlet-exponent flagship. **G1–G5 BUILT + flag-ON as of 2026-06-30** (see that doc's per-stage
> status); remaining to close = burn-in + one G5 paper clearing panel-novelty ≥ 0.7.

---

## Wave 6 — Irreducibility & mechanism rigor (content gates)

The "is it real / irreducible" tests, on generated candidates.

| Item | Source | Status | Notes |
|------|--------|--------|-------|
| **RAF closure → report iRAF/organizations** as the publishable unit | sim #1 | ◐ shipped flag-OFF | `DerivationClosure` (maxRAF/iRAF math, W2) + `RafReport` — over the live findings' derivation graph (food = observable vocabulary), `mine()` logs the iRAF of the top confirmed findings + the count grounded in no RAF (`rafClosureEnabled`). Observability (doesn't retire); non-trivial once composition / vocab-expansion chains exist. |
| **30.7 mechanism-as-runnable-model + closure check + conservation gates** | sim #3/#4/#7, 30.7 | ✅ shipped flag-OFF | [`mechanism-runnable-model.md`](mechanism-runnable-model.md), D1–D4 settled. P1 (`Mechanism`+DSL) + P2 (`MechanismGate`) + P3 (`propose_mechanism` tool, flag-gated). Follow-on: persist accepted mechanisms onto papers. |
| **Behavioral-signature discovery detector** | sim #12 | ⏳ | low; truth-table/I-O fingerprint over held-out predictions (ties to the discovery-method substrate) |

**Acceptance:** a published unit is an irreducible closed set (iRAF), not an isolated edge; a declared
mechanism compiles to `predict()` and regenerates its claim out-of-sample, respecting conservation/units.

---

## Wave 7 — Promotion & open problems (Phase 30 Stage 2)

| Item | Source | Gate |
|------|--------|------|
| **30.6 anomaly→open-problem promotion** — ✅ **shipped + ON 2026-06-05** (`842f9c6`): `AnomalyPromotionGate` (N adversarial re-verification attempts, both regimes, fresh deterministic windows) → `promoted_anomaly_at` stamp (clobber-proof JPQL) → one `PaperKind.ANOMALY_PROMOTION` UNEXPLAINED_OBSERVATION paper per promotion → `ProgramOpenQuestion` seeded at publish. No-op until the high-water `confirmedRuns` mark reaccumulates past `anomalyMinConfirmed` (same ramp as 30.5). Discovery-yield health metric not yet built. | 30.6, sim #13 | W3, W4, W6 (all ON) |
| **30.4 literature grounding** — layered-resolution design updated 2026-06-04 per the ARC review ([`phase-30-arc-patterns.md`](phase-30-arc-patterns.md)); still pending behind the calibration | 30.4 | **23.8 calibration** (see below) |

**Acceptance:** one `UNEXPLAINED_OBSERVATION` paper that cites a surviving anomaly/residual, records ≥N
failed refutation attempts, carries no literature match, and seeds a `ProgramOpenQuestion`.

---

## Two cross-cutting decisions — schedule *now*, even though they gate late work

1. **Run the 23.8 calibration early** (operator afternoon: hand-verify 20 high-novelty papers). It decides
   whether Stage 2's literature retrieval (30.4) and richer 30.5 are even needed — front-load it so you
   don't build retrieval you don't need. The room memory (`project_phase23_8_calibration`) already
   suggests the answer: *generation, not retrieval, is the blocker* → Waves 5–6 matter more than 30.4.
2. **Flag-gated-OFF rollout** — each wave dormant until its acceptance bar; pipeline-wide validators stay
   domain-neutral.

---

## The shape of it

- **Waves 0–1 are ~80% of the practical payoff for ~20% of the effort** — small validators/engine tweaks
  that retire the trivial flood; the natural continuation of the already-shipped Rec 1–5 work.
- **Wave 2 (the graph) is the pivot** — cheap to build, but everything advanced depends on it; do not
  skip ahead of it.
- **Effort/risk hotspots last:** Wave 6's runnable-mechanism (30.7) is the largest build; Wave 5's
  vocabulary expansion (30.5) is highest-risk/highest-reward (the proposal flags it "beyond human-level").
  Sequence them last so they sit on a verified foundation.

**Status legend (refreshed 2026-06-06):** ✅ shipped · ◐ partial / built-but-flag-OFF · ⏳ not started.

| Wave | Theme | Effort | Payoff | Risk | Status (2026-06-06) |
|------|-------|--------|--------|------|---------------------|
| 0 | retirement + domain | low | high | low | ✅ investigation rotation shipped (`investigationRotationEnabled` ON); domain-field on `Inequality` **deferred** (parity risk) |
| 1 | cheap rejection gates | low | **highest** | low | ✅ all gates shipped + ON — triviality/dedup (ON) + window-geometry-LHS exclusion (ON, 2026-06-04), dimension + unit-consistency + FIM-identifiability (all three ON 2026-06-03) |
| 2 | derivation graph | low-med | enabling | low | ✅ `DerivationGraph`/`DerivationNode`/`DerivationClosure`/`ConjectureComposer` + composition persist (`compositionPersistEnabled` ON) |
| 3 | selection/diversity | med | high | low | ✅ QD archive + clade-value selection + sublinear generation budget shipped (flags ON 2026-06-03); tiered ordering de facto |
| 4 | evaluation hardening | med | high | low | ✅ **all four ON** — private-holdout + replication 2026-06-03; stochastic null + multiple-comparisons 2026-06-04 (null A/B passed on the 2026-06-03 shadow log) |
| 5 | generation (Stage 1) | med-high | high | med | ✅ **all shipped + ON** — 30.3 composition (ON), 30.2 residual-structure + 30.1 anomaly mining (ON 2026-06-03, log-only), 30.5 vocabulary expansion (ON 2026-06-05, A/B live via anomaly-stratum targets) |
| 6 | irreducibility + mechanism | **high** | high | med | ◐ RAF iRAF-reporting + 30.7 runnable-mechanism shipped, **flags ON 2026-06-05** (`rafClosureEnabled`, `mechanismProposalEnabled`); only behavioral-signature detector (sim #12, low) remains |
| 7 | promotion (Stage 2) | med | med | med (gated on calibration) | ◐ 30.6 promotion **shipped + ON 2026-06-05** (hearings live since 2026-06-06 — all REFUTED so far, correctly; acceptance = first UNEXPLAINED_OBSERVATION promotion paper); 30.4 literature grounding **deferred** (calibration: generation is the blocker; engine selected 2026-06-06 → phase-32 §32.5/PaperQA2); discovery-yield metric ✅ shipped 2026-06-05 (`DiscoveryYieldReporter`, 5-min funnel line) |

**Review-driven pipeline fixes (2026-06-04).** A `/paper-review` session shipped production-layer
hardening alongside the waves above — window-geometry-LHS exclusion (Wave-1 triviality extension, ON;
see [`conjecture-engine-triviality.md`](conjecture-engine-triviality.md) §7), NULL_RESULT
coherent-abstention wording, comma-grouped / leading-`+` validator repairs, priority-reserved LLM-pool
concurrency (fixed civic-flood starvation), and broadened first-pass prose-constant grounding. Full set
+ commit refs + live-verified outcomes: [`../../archive/2026-06-04-pipeline-hardening.md`](../../archive/2026-06-04-pipeline-hardening.md).
These are the §5.3 "gate out trivializers" + production plumbing; per the calibration note, *generation*
(Waves 5/7 content) remains the standing blocker, not gating.

> Discovery/frontier work (Mertens/divisor/prime beyond-sieve escalation) is a **separate track** —
> see [`frontier-escalation-loop.md`](frontier-escalation-loop.md) /
> [`runtime-self-verifying-execution.md`](runtime-self-verifying-execution.md) — not part of these waves.
