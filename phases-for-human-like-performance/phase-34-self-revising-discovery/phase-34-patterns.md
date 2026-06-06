# Phase 34 — patterns for EmergentTerrain + scoping

Adoptable patterns from *Self-Revising Discovery Systems for Science* (arXiv:2606.01444v1;
detailed summary in [`phase-34-paper-summary.md`](phase-34-paper-summary.md)). This is the **sixth
external-pattern review** in the lineage (after phase-30 external/simulation/ARC, phase-31 memory,
phase-32 manuscript-grounding). Same convention: every adopted item ships flag-gated **OFF**
(`@Value … = false`), pipeline-wide code stays **domain-neutral** (it runs on every domain's
papers, not just the one that surfaced it — [[feedback_common_code_domain_neutral]]), and anything
gated on an external decision ships dormant rather than stalling the PR
([[feedback_flag_gated_dormant]]).

**Disposition discipline.** This paper is *theory*, not a clonable codebase — most of its value is
**diagnostic vocabulary and acceptance criteria** for mechanisms ET already has, not new
subsystems. Several patterns are **covered** by shipped/queued work and recorded here only to note
the formal backing; a few are genuinely **net-new** and scoped as waves below.

---

## Disposition table

| Paper concept | ET surface today | Disposition |
|---|---|---|
| retrieval / search / discovery trichotomy | novelty assessment in `FormulationService`; the standing finding "generation, not retrieval, is the blocker" ([[project_phase23_8_calibration]]) | **adopt as a classifier** (34.1) — gives a non-subjective novelty type |
| copresheaf `Iₜ : 𝒮_b → Set` + `∫ Iₜ` provenance | typed citation graph, `DERIVES_FROM` edges, `KnowledgeBaseService` | **covered** — ET's typed provenance graph *is* the category of elements; recorded for vocabulary only |
| fixed-regime endofunctor `Φ_b` (audit rule: never silently identify two distinct accepted artifacts) | paper/citation lifecycle; provenance fragmentation flagged in phase-31 | **covered by 31.6** (provenance unification) — note the injectivity audit rule as an acceptance test |
| verified regime transition `u` + Kan transport + **residual** | 30.5 vocabulary expansion (miner proposes new DERIVED scalars, MDL-gated) | **sharpens 30.5** (34.2) — adds generator- vs composite-reachable audit |
| Kan obstruction = forced residual = non-subjective discovery signature | 30.6 `UNEXPLAINED_OBSERVATION` promotion | **sharpens 30.6** (34.2) — residual is the formal "no transport explains this" |
| MDL on *widening adversarial evidence*; non-monotone R² is correct | stochastic-null, held-out verify, replication, anti-stagnation (invert objective-maximization) | **covered + named** (34.3 records the R²-non-monotonicity guard as an explicit anti-pattern) |
| rejected alternatives retained as first-class provenance | miner retires/drops seeds; `/paper-review` REVIEW_REPORT; **room reset wipes everything** ([[project_room_reset_on_restart]]) | **net-new** (34.4) — record rejected conjectures/alternatives as durable contrast objects |
| discovery cost `L_b'(post-state \| transported evidence)` | `DiscoveryYieldReporter` (5-min funnel, shipped 2026-06-05) | **net-new metric** (34.5) — per-claim "bits beyond transport" |
| Builder/Breaker adversarial stress-test loop | dispute path; adversarial promotion gate (30.6); refutation re-runs | **covered** — ET's refutation loop is the Breaker; recorded for vocabulary |
| learn the base schema `𝒮_b` (open problem) | hand-curated `MathInvariants` BASE+DERIVED | **rejected / parked** — open research problem in the paper too; 30.5's bounded proposal is the tractable slice ET already chose |

---

## 34.1 — Retrieval / search / discovery novelty classifier  *(adopt; sharpens the novelty assessment)*

**Pattern.** Classify every published-candidate claim into one of three non-subjective types by
asking what *operation* produced it, not how surprising it reads:

- **RETRIEVAL** — the claim is an artifact already expressible in the current invariant vocabulary
  and already present/derivable (a restatement; ET's `|corr|>0.98` knob-restatement filter already
  catches the strongest case).
- **SEARCH** — a new *path* inside the fixed vocabulary (a new relation among existing BASE/DERIVED
  invariants). This is what the great majority of ET papers actually are.
- **DISCOVERY** — required a *regime transition*: a new DERIVED scalar / invariant type / verifier
  that the existing vocabulary could not express (i.e. 30.5 admitted something, or 30.6 promoted a
  forced residual).

**ET shape.** A stamp on the published paper (`novelty-type:RETRIEVAL|SEARCH|DISCOVERY`) computed
in `FormulationService` from signals ET already has (vocabulary delta, corr-filter, 30.5 admission
event, 30.6 promotion event). **Advisory first** — it does not gate; it makes the standing finding
("generation is the blocker") *measurable* per-paper instead of asserted. The expected distribution
(near-100% SEARCH, ~0% DISCOVERY) is itself the headline diagnostic and validates [[project_phase23_8_calibration]].

**Acceptance.** One `/paper-review` session: the stamp distribution matches operator intuition on
≥80% of papers; zero papers mis-stamped DISCOVERY without a recorded vocabulary delta.

## 34.2 — Generator- vs composite-reachable audit for new DERIVED scalars  *(sharpens 30.5 / 30.6)*

**Pattern (Table 2 / Prop 8).** When the miner proposes a new DERIVED scalar (30.5), the MDL guard
asks *is it shorter?*; this adds a second, orthogonal question — *is it actually a new type, or a
transport of old vocabulary?*:

- **generator-reachable** — a unary transform of an existing invariant (log, z-score, rescale).
  This is the trivial-rescaling failure mode 30.5 already warns against → **reject** (or admit only
  with strong MDL justification).
- **composite-reachable** — reachable only via a *newly admitted multi-input operation* (a product /
  interaction of two existing invariants, e.g. the paper's `LogNormCompliance × ReLUModeAmpl`).
  **This is the genuine discovery case** — admit if it also clears the MDL and irreducibility gates.
- **isolated** — unreachable even by composites; needs empirical content from outside (the Kan
  obstruction). For ET this is the formal definition of a 30.6 `UNEXPLAINED_OBSERVATION`: a forced
  residual that transport of existing invariants cannot populate.

**ET shape.** A reachability classifier in the 30.5 admission path (and a residual check in the 30.6
promotion path), sitting *beside* the existing MDL guard and identity-prover irreducibility check —
not replacing them. Domain-neutral: operates on the abstract invariant-composition grammar, not on
any one domain's invariants. This is a **sharper irreducibility test** than `|corr|>0.98`: a scalar
can be weakly-correlated yet still merely generator-reachable (a nonlinear unary transform), and the
audit catches that.

**Acceptance.** On a hand-built probe of 10 proposed scalars (5 known unary rescalings, 5 genuine
products), the classifier labels reachability correctly ≥9/10; a 30.6 candidate is promoted only
when its residual is non-empty under transport of the current vocabulary.

## 34.3 — MDL-on-widening-evidence guard (the non-monotone-R² anti-pattern)  *(covered; record as explicit rule)*

**Pattern (Builder/Breaker R² 0.48→0.68→0.54→0.41).** Acceptance across stress-test rounds must be
**paired compression on the *same refit, widened* evidence set**, never monotone improvement of a
single predictive score. A falling fit metric under harder/wider evidence is *correct behavior*;
rewarding monotone improvement rewards term-stuffing on heterogeneous data.

**ET shape.** ET's stochastic-null / held-out / replication gates and anti-stagnation design
([[project_frontier_discovery_feedback_design]]: CANNOT_COMPUTE feeds back, doesn't terminate;
inform-don't-suppress) already embody this. The net-new piece is a **documented anti-pattern + a
guard**: any current or future acceptance rule that compares a bare metric across evidence-set
changes (rather than paired-refit MDL/likelihood on the union) is flagged in review. Cheap: a
lint-style note in the gate-authoring checklist + a one-line assertion where evidence sets widen
between iterations. Prevents a future regression into monotone-metric gating.

**Acceptance.** Documented in the gate-authoring checklist; one assertion added at the evidence-set
union point; zero existing gates found violating it (audit recorded).

## 34.4 — Rejected alternatives as durable contrast provenance  *(net-new; depends on 31.6)*

**Pattern.** CategoryScienceClaw keeps every *rejected* model (the isotropic fiber-count descriptor,
the mean-force null, the adhesion-only model, the curvature-only descriptor) as a **first-class
typed provenance object** — "model comparison itself becomes provenance." A rejected alternative is
a *contrast object* that explains why the accepted one passed its gate, not a deleted failure.

**ET shape.** Today ET retires/drops miner seeds and the `/paper-review` report is wiped on restart
([[project_room_reset_on_restart]]); the rejected alternative behind an accepted conjecture is not
durably recorded. Adopt: when a conjecture/model is accepted over a competing one (panel pick, MDL
pick, gate pick), persist the **rejected alternative + the gate margin** (e.g. ΔAIC / ΔMDL / panel
delta) as a typed `REJECTED_ALTERNATIVE` provenance node linked to the accepted claim. **Hard
dependency on 31.6** (provenance must survive room resets — same dependency 32.4 carries). Honors
the no-re-seed-skip rule ([[feedback_no_reseed_skip]]): this *records* rejections, it does not
filter future seeds against them.

**Acceptance.** One accepted-over-alternative claim publishes carrying a resolvable
`REJECTED_ALTERNATIVE` node with its gate margin; the node survives a room restart.

## 34.5 — Per-claim discovery-cost metric  *(net-new; extends DiscoveryYieldReporter)*

**Pattern (Prop 4.3).** Discovery cost = `L_b'(post-state | transported evidence)` — the bits a
claim needs *beyond* what re-expressing existing vocabulary supplies. A SEARCH result has ~zero
discovery cost (fully transported); a DISCOVERY result has strictly positive cost (forced residual).

**ET shape.** An approximate per-claim scalar (description-length delta attributable to a
34.2-detected vocabulary admission / 34.4 residual, vs the transported baseline), surfaced as a new
column in the existing `DiscoveryYieldReporter` 5-min funnel line. Turns the trichotomy (34.1) into
a continuous yield signal: *how much* genuine regime-enlargement per session, not just a count.
Record-only; feeds capacity/triage decisions ([[project_pysr_failure_classification]]).

**Acceptance.** Reporter emits a discovery-cost column for one session; SEARCH-classified papers
score ≈0, any DISCOVERY-classified paper scores >0 — i.e. the metric agrees with the 34.1 stamp.

---

## Sequencing (all flag-gated OFF; deferral discipline inherited)

```
34.1 novelty classifier (advisory stamp) ──┐
                                            ├──→ 34.5 discovery-cost metric (reporter column)
34.2 reachability audit (30.5/30.6 path) ──┘
34.3 MDL-widening guard — checklist + assertion, no dependency, rides any gate PR
31.6 provenance/restart survivability ──→ 34.4 rejected-alternative provenance
```

| Wave | Item | Flag | Note |
|---|---|---|---|
| 0 | 34.3 MDL-widening guard | (doc + assertion) | cheapest; no runtime flag; pure regression-prevention |
| 1 | 34.1 novelty classifier | `app.research.novelty-type-stamp.enabled` | advisory stamp; makes the "generation is the blocker" finding measurable |
| 1 | 34.2 reachability audit | `app.research.reachability-audit.enabled` | sits beside the 30.5 MDL guard + identity prover |
| 2 | 34.5 discovery-cost metric | `app.research.discovery-cost.enabled` | reporter column; depends on 34.1+34.2 signals |
| 2 | 34.4 rejected-alternative provenance | `app.research.rejected-alternative-record.enabled` | **hard-depends on phase-31 §31.6** (durable across resets) |

**Flag inventory (all default OFF):**
```yaml
app.research.novelty-type-stamp.enabled        # 34.1 (advisory)
app.research.reachability-audit.enabled        # 34.2 (30.5/30.6 path)
app.research.discovery-cost.enabled            # 34.5 (reporter column)
app.research.rejected-alternative-record.enabled # 34.4 (needs 31.6)
# 34.3 is a gate-authoring-checklist rule + one assertion — no runtime flag
```

## The shape of it

Nothing here is a new subsystem; the paper's gift is **acceptance criteria and diagnostic
vocabulary** for mechanisms ET already runs by heuristic. Wave 0 (34.3) is free insurance against a
future monotone-metric regression. Wave 1 makes ET's central standing finding — *generation, not
retrieval, is the blocker* — **measurable** (34.1) and gives the 30.5/30.6 path a sharper "is this
genuinely new or just transported old vocabulary" test (34.2). Wave 2 turns that into a continuous
yield signal (34.5) and stops throwing away the rejected alternatives that explain why accepted
claims passed (34.4). If only one item ever ships, ship 34.1: a one-session stamp distribution that
quantifies how rarely the system does discovery in the strong sense is the cheapest honest
self-assessment the room can produce.
