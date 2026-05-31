# Phase 30 — implementation order

Dependency- and leverage-ordered roadmap that sequences the original Phase 30 sub-items
([`phase-30-genuinely-novel-claims.md`](phase-30-genuinely-novel-claims.md)), the engine-triviality
fixes ([`conjecture-engine-triviality.md`](conjecture-engine-triviality.md),
[`mertens-conjecture-vs-literature.md`](mertens-conjecture-vs-literature.md)), and the two external-pattern
addenda — the *process* layer ([`phase-30-external-patterns.md`](phase-30-external-patterns.md)) and the
*content* layer ([`phase-30-simulation-patterns.md`](phase-30-simulation-patterns.md)).

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
| **Retirement loop** — feed published refutations back to retire conjectures; sample the binding regime in the mine pass so false bounds die at mine time | triviality §2/§5; ext #7 | fixes the "0 retired (counterexample)" loop |
| **Domain field on `Inequality`** — record fitted start-range; state domain-restricted claims | mertens §2 | fixes "stated universally / refuted at small start" |

**Acceptance:** a mine pass retires ≥1 already-refuted conjecture; published conjectures carry an explicit
domain; the 212-pool count drops on the next pass.

---

## Wave 1 — Cheap reference-free rejection gates (highest ROI)

Each is a standalone validator (same shape as the already-shipped `SinglePointSweepValidator`),
domain-neutral, no dependencies. Together they collapse 212 → a handful.

| Item | Source | Kills |
|------|--------|-------|
| **FIM identifiability gate** | sim #5 | constants the data doesn't pin (cheapest single win) |
| **Unit/dimensional-consistency gate** | sim #6 | `meters ≤ k·seconds`-class fits |
| **Copy/tautology suppression** | sim #2 | `A ≤ k·A'` self-reproducers |
| **Convergence/equilibration gate** | sim #9 | bounds whose value depends on where the window starts (the mertens pathology) |
| **Descriptor + dedup bitmask** | sim #11 + ext #1 | near-identical duplicates (one claim per capability-set) |

**Acceptance:** on the current substrate, the gates reduce the surviving conjecture count by an order of
magnitude with zero false-retirement of a hand-checked control set of genuine relations.

---

## Wave 2 — Representation: the derivation graph (the pivot)

| Item | Source | Notes |
|------|--------|-------|
| **Findings-as-network with typed input/output ports + provenance edges** | sim #15 typed ports; ext #9 lineage | the substrate RAF closure (W6), composition (W5), and clade-value selection (W3) all require |

**Acceptance:** every accepted finding is a node with typed ports and `DERIVES_FROM` provenance; the graph
round-trips (serialise/reload) and a finding's full derivation lineage is reconstructable.

---

## Wave 3 — Selection & diversity bookkeeping (process layer)

Now that the pool is small and graph-structured, keep the *right* things.

| Item | Source | Depends on |
|------|--------|-----------|
| **Quality-diversity descriptor archive** (one elite per `(variables, derivation-type, mechanism-kind)` cell) | ext #1 | W1 descriptor |
| **Sublinear generation budget** (cap new conjectures as `n_validations**α`, α<1) | ext #4 | — |
| **Clade/lineage-value selection + pseudo-count smoothing** (rank by descendant productivity; fit = eligibility gate only) | ext #2, ext #6 | W2 graph |
| **Tiered cheap→expensive gate ordering** | ext #5 | W1, W4 |

**Acceptance:** selection samples parents by clade-value × inverse-popularity (not tightest fit); the
archive holds one elite per cell; generation rate is sublinear in validations performed.

---

## Wave 4 — Evaluation hardening (verdict side, before trusting any novelty)

You must judge novelty honestly before producing it, or Stage-1 generation just makes new noise.

| Item | Source | Notes |
|------|--------|-------|
| **Generative stochastic null + Fano/CV floor** | sim #8 | simulate the no-effect mechanism; beat the corrected ensemble quantile |
| **Multiple-comparisons correction** (Bonferroni/BH) | 30.1 guard | now over a tiny W1-reduced denominator |
| **Private validator the generator can't read + gaming-rate metric** | ext #3 | anti-HARKing; track "passed public, failed private" rate |
| **Ensemble/seed replication + stochastic-pass semantics** | sim #10, MD | hold across N seeds with a quantified failure rate |

**Acceptance:** a claim only scores on held-out data against a generated null; a known-trivial relation
and a known-fabricated value are both correctly rejected; the gaming-rate metric is emitted.

---

## Wave 5 — Generative novelty (Phase 30 Stage 1)

Claim generation, on a clean pool with honest evaluation.

| Item | Source | Depends on |
|------|--------|-----------|
| **30.5 vocabulary expansion via closed operator grammar, MDL-gated** | sim #14, 30.5 | W4 (MDL needs null) |
| **30.1 anomaly mining** (keep the exceptions) | 30.1 | W0 retirement, W4 null + MCC |
| **30.2 residual-structure discovery** (subtract known law, beat null) | 30.2 | W4 null |
| **30.3 compositional derivation** (compose + flatten) | sim #15, 30.3 | W2 graph |

**Acceptance (per 30.x bar in the proposal):** ≥5 surviving anomalies past held-out re-check + MCC;
a residual form beating the linear null on held-out data; one composition paper whose hypothesis is
absent from both parents.

---

## Wave 6 — Irreducibility & mechanism rigor (content gates)

The "is it real / irreducible" tests, on generated candidates.

| Item | Source | Depends on | Effort |
|------|--------|-----------|--------|
| **RAF closure → report iRAF/organizations** as the publishable unit | sim #1 | W2 graph | medium |
| **30.7 mechanism-as-runnable-model + closure check + conservation gates** | sim #3/#4/#7, 30.7 | W2, mechanism DSL | **largest build** |
| **Behavioral-signature discovery detector** | sim #12 | W4 | low |

**Acceptance:** a published unit is an irreducible closed set (iRAF), not an isolated edge; a declared
mechanism compiles to `predict()` and regenerates its claim out-of-sample, respecting conservation/units.

---

## Wave 7 — Promotion & open problems (Phase 30 Stage 2)

| Item | Source | Gate |
|------|--------|------|
| **30.6 anomaly→open-problem promotion** (adversarial replication + clade-value) + **discovery-yield health metric** | 30.6, sim #13 | W3, W4, W6 |
| **30.4 literature grounding** | 30.4 | **23.8 calibration** (see below) |

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

| Wave | Theme | Effort | Payoff | Risk |
|------|-------|--------|--------|------|
| 0 | retirement + domain | low | high | low |
| 1 | cheap rejection gates | low | **highest** | low |
| 2 | derivation graph | low-med | enabling | low |
| 3 | selection/diversity | med | high | low |
| 4 | evaluation hardening | med | high | low |
| 5 | generation (Stage 1) | med-high | high | med |
| 6 | irreducibility + mechanism | **high** | high | med |
| 7 | promotion (Stage 2) | med | med | med (gated on calibration) |
