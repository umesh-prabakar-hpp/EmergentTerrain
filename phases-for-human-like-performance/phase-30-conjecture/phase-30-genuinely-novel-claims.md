# Phase 30 — Genuinely Novel Claims

**Status (2026-05-29):** Proposal / scoping doc. Not queued. Every piece
below is flag-gated dormant by default (`app.research.*.enabled = false`),
per the project convention — ship the code, leave the flip to the operator
after the prior stage's acceptance bar is met.

> **Empirical diagnosis (companion docs, same folder).** The triviality this phase fixes is *measured*
> in [`conjecture-engine-triviality.md`](conjecture-engine-triviality.md) — the engine produces 212
> identically-shaped data-mined linear bounds, none retired in practice — and
> [`mertens-conjecture-vs-literature.md`](mertens-conjecture-vs-literature.md) — why the room's flagship
> `mertens_per_sqrt_W` refutations are not real Mertens research. Those describe the "Today" state this
> phase moves away from. For external-framework patterns that strengthen this proposal, see two
> addenda: [`phase-30-external-patterns.md`](phase-30-external-patterns.md) — the *process* layer
> (quality-diversity archive, clade-value selection, private-validator anti-gaming, sublinear budget,
> from self-improving frameworks); and
> [`phase-30-simulation-patterns.md`](phase-30-simulation-patterns.md) — the *content* layer (RAF-closure
> irreducibility, mechanism-as-runnable-model, FIM identifiability, conservation/null/convergence gates,
> dedup primitives, from chemistry / artificial-life / simulation frameworks).

**Premise.** The novelty *machinery* already exists and is live: Phase 23
(conjecture mining + held-out `verify_conjecture` + `EvidenceLabel`
discipline), Phase 24 (PySR symbolic regression), Phase 25 (sympy identity
prover), Phase 26 (constant recognition), Phase 28 (Julia conjecture
consolidation). What Phase 23 moved was novelty *from zero to measurable*
on a **human-curated vocabulary** of regularities that **hold**. This phase
attacks the three frontiers Phase 23 explicitly deferred, because each is
where genuinely novel claims actually come from:

- **Anomaly mining** — claims live in the *exceptions* to strong
  regularities, not the regularities. (Phase 23 deferred to "Phase 25+";
  Phase 25 became the identity prover, so the slot is still open.)
- **Vocabulary / representation expansion** — a genuinely new claim almost
  always rides on a *new observable* (a derived quantity nobody was
  tracking). Phase 23 froze the vocabulary as human-curated and tagged
  expansion "beyond human-level."
- **Literature grounding** — outside OEIS integer sequences, the system
  *cannot currently know* whether a claim is novel. Phase 23 shipped OEIS
  only and gated richer retrieval (arxiv / Semantic Scholar / OpenAlex
  embeddings) on the **23.8 calibration check**, which remains the decision
  gate for this whole phase.

**Honest ceiling, stated up front.** This does not produce theorems
(formal proof remains out of reach — `THEOREM` stays reserved/unreachable,
per Phase 23). It does not produce an "AI scientist." It moves the system
from *"verify known relationships and mine the regularities that hold"* to
*"surface reproducible structure that the system's own reference model does
not predict, and label it honestly as a conjecture or an unexplained
observation."* That is the realistic definition of "genuinely novel" here.

---

## The definition this phase commits to

A claim is *genuinely novel* iff it is all three of:

1. **Irreducible** — not algebraically derivable from a reference relation
   the system already holds. *Already enforceable* via Phase 25 (identity
   prover) + Phase 26 (constant recognition) + the Phase 23.2 OEIS gate +
   the `TextbookBoundRegistry`. This phase reuses them as a gate, doesn't
   rebuild them.
2. **Predictive & reproducible** — it forecasts data it was not fit on, the
   forecast holds, and it survives an independent re-run that *tries* to
   break it. *Already enforceable* via Phase 23.5 held-out
   `verify_conjecture` + the existing `ReplicationService` / dispute path;
   this phase tightens the latter into an adversarial promotion gate.
3. **Literature-absent** — not a restatement of a known published result.
   *Only enforceable for integer sequences today* (OEIS). The open-domain
   case is the binding constraint and the reason Stage 2 is gated on
   calibration.

Miss #1 → the trivial-identity failure (Kepler `T²/a³≡1`). Miss #2 → the
tolerance-band confirmation failure. Miss #3 → confident rediscovery.

---

## Two stages, matching the agreed target ("both, staged")

**Stage 1 — derivable-but-non-trivial.** Reuses the entire shipped stack;
the answer is *checkable* against an extended reference. Lowest risk; prove
the machinery here first.

**Stage 2 — genuinely open.** Problems with no known closed form; "correct"
can only mean predictive + reproducible + literature-absent, never proven.
Higher novelty ceiling, gated behind Stage 1's acceptance + the 23.8
calibration.

| | Today | After Phase 30 |
|---|---|---|
| Conjecture miner surfaces | regularities that **hold** across windows | Stage 1 adds **anomalies**: single-point / single-config violations of otherwise-strong conjectures, and **residuals**: structure left after subtracting the known relation |
| Vocabulary | human-curated `MathInvariants` BASE + DERIVED | Stage 2 adds **machine-proposed DERIVED scalars**, kept only if they yield surviving, irreducible, literature-absent conjectures (MDL-gated) |
| Novelty check | OEIS (integer sequences) + implicit LLM judgment | Stage 2 adds **embedding-based literature retrieval** (the 23.8-gated arxiv/OpenAlex path) as a hard novelty gate |
| Strongest honest label | `EMPIRICAL_RESULT` (mechanism + adversarial check) | `UNEXPLAINED_OBSERVATION` becomes a *first-class, promoted* outcome for reproducible-but-unexplained anomalies, seeding open problems |

---

## Stage 1 — derivable-but-non-trivial

### 30.1 — Anomaly mining (invert the miner) [P0]

Today `ConjectureEngine` seeds inequalities and **promotes the ones that
survive** (tighten on confirmation, drop on counterexample). Anomaly mining
keeps the *discarded* arm: a conjecture that holds at 999/1000 configs but
fails sharply at one is not noise to drop — the one violation is the
candidate claim.

- New `AnomalyMiner` alongside `ConjectureMinerService`: for each
  high-confirmation conjecture, record its **worst-margin witness** (the
  `verify_conjecture` machinery already computes this) and flag witnesses
  whose deviation exceeds the conjecture's established `p99Slack` by a
  multiple. The anomaly *is* the (conjecture, witness, margin) triple.
- The claim a paper writes: *"Relation R holds across [range] except at W,
  where it deviates by M — reproducibly, across seeds."*
- **Reuses:** `verify_conjecture` (held-out re-check at W and W±ε to
  confirm the anomaly is not a single floating-point artifact), Phase 25
  identity prover (is the anomaly explained by a known identity at that
  point? if so, not an anomaly), `EvidenceLabel.UNEXPLAINED_OBSERVATION`.
- **Critical guardrail — multiple-comparisons correction.** Anomaly mining
  is the single highest p-hacking risk in this phase: scan enough configs
  and you *will* find "violations" that are noise. Before an anomaly is
  emitted, correct its significance threshold for the number of configs
  scanned (Bonferroni / Benjamini-Hochberg on the per-config deviation
  z-scores). `log()` the scan count so silent truncation can't masquerade
  as "checked everything." This is the load-bearing guard; without it,
  30.1 manufactures the exact HARKing failure the rest of the system
  guards against.
- **Acceptance:** with the flag on, a mine pass over the existing math
  substrate produces ≥5 anomalies that survive (a) held-out re-check at the
  violating config and (b) the multiple-comparisons correction. Zero of the
  5 are explained by a Phase 25 identity at the violating point (spot-check).

### 30.2 — Residual-structure discovery [P0]

For any topic with a known reference relation, the genuinely interesting
quantity is what's *left after subtracting it*. Reframe the existing
physics/math topics: subtract the engine's known law, then point Phase 24's
PySR at the **residual**.

- New `ResidualProbe`: given a topic's known relation (from
  `TextbookBoundRegistry` / engine ground truth) and a parameter sweep,
  compute residual = observed − predicted, then call `symbolic_regress`
  (Phase 24) on the residual series.
- The claim: *"After removing the known leading-order law, a reproducible
  structure of form S with magnitude M remains."* Kepler is the canonical
  example — `T²/a³≡1` is the trivial identity, but add a perturbing body
  and the residual precession is a derivable-but-non-trivial discovery (the
  reviewer on P-a328-1000 literally recommended this).
- **Reuses:** Phase 24 PySR (and `project_pysr_failure_classification` to
  size capacity — residual fitting is search-bound, so cloud helps);
  Phase 25 identity prover to confirm the discovered residual form is *not*
  algebraically reducible to the subtracted law (irreducibility gate);
  held-out predictive check on the residual model at fresh parameters.
- **Guardrail:** the residual model must beat a null model
  (constant/linear) on held-out data by a pre-set margin, else the
  "structure" is overfit noise. Report the null-model comparison in the
  paper.
- **Acceptance:** on a perturbed-Kepler (or RLC-with-loss) topic, the probe
  recovers a residual form whose held-out R² beats the linear null by the
  margin, and the identity prover confirms it is not reducible to the
  base law. The paper publishes as `EMPIRICAL_RESULT` (mechanism = the
  perturbation; adversarial check = held-out + null comparison).

### 30.3 — Compositional derivation [P1]

Phase 4 Iter 9 already persists `FormalFinding` rows and Phase 4b's
`app.research.theory-composition.enabled` already emits derived
`ProgramOpenQuestion`s from `A~f(B) ∧ B~g(C)` chains. It is flag-gated and
underused. A *derived composition no single paper stated* is a legitimately
novel claim.

- Promote composition from "emit an open question" to "emit a candidate
  claim": when two findings chain, synthesize the derived relation `A~h(C)`,
  run it through the identity prover (irreducible?) and `verify_conjecture`
  (does it hold out-of-sample on the substrate?), and if both pass, write a
  composition paper citing the two parents (`DERIVES_FROM` edges already
  exist in the Phase 1 Iter 3 typed graph).
- **Reuses:** `KnowledgeBaseService` composition, the typed citation graph,
  identity prover, held-out verify.
- **Acceptance:** one composition paper publishes whose hypothesis is a
  relation absent from both parent papers, cites both as `DERIVES_FROM`,
  and passes the held-out check on the derived relation.

---

## Stage 2 — genuinely open (gated on Stage 1 acceptance + the 23.8 calibration)

### 30.4 — Literature grounding (the precondition gate) [P0 for Stage 2]

This is the binding constraint and **must ship before 30.5/30.6 are
honest.** Outside OEIS, the system has no way to know a claim is not already
published. This is the deferred arxiv/Semantic Scholar/OpenAlex path from
the Phase 23.2 table and the Phase 24 deferrals.

- **Decision gate first:** run the Phase **23.8 calibration** (one
  afternoon, operator task — pick 20 high-novelty papers, hand-verify
  novelty). If the false-positive rate < 30%, OEIS + implicit-LLM judgment
  is good enough and Stage 2 can lean on it; if > 30%, build retrieval.
- **If built:** pre-embed a corpus snapshot once (OpenAlex/arxiv abstracts),
  nearest-neighbor at formulation *and* publish time, and expose the top
  matches to the novelty assessment in `FormulationService`. A claim within
  cosine-ε of a published abstract's claim is demoted to `KNOWN_RESULT`
  unless it materially extends the match.
- **Acceptance:** the calibration FP rate is recorded; if retrieval ships,
  re-running the 20-paper calibration with retrieval on drops the FP rate
  below 30%.

### 30.5 — Vocabulary / representation expansion [P1, high-risk/high-reward]

The deepest lever and the one Phase 23 called "beyond human-level." Today
`MathInvariants` BASE + DERIVED is hand-curated; the miner can only find
relationships *expressible in that vocabulary*. Genuinely novel claims
often require a derived quantity nobody was tracking.

- Let the miner **propose new DERIVED scalars** — bounded compositions of
  BASE invariants (ratios, products, log-ratios) — and admit one into the
  vocabulary **only if** it yields a surviving, irreducible,
  literature-absent conjecture that the existing vocabulary could not
  express.
- **MDL guard (load-bearing):** a proposed scalar earns its place only if
  it produces a *shorter description* of the data than the best
  existing-vocabulary conjecture (compression as the "is it interesting"
  signal). This prevents combinatorial explosion of meaningless derived
  knobs — the exact failure mode that makes naive feature-search
  worthless.
- **Reuses:** `ConjectureEngine` novelty filter (|corr|>0.98 already drops
  knob-restatements), identity prover (irreducibility), 30.4 (literature
  absence).
- **Acceptance:** the miner admits ≥1 machine-proposed scalar that (a)
  beats the existing vocabulary on MDL, (b) yields a conjecture the
  existing vocabulary cannot state, (c) is irreducible and literature-
  absent. Hand-verify the one scalar is not a trivial rescaling of a BASE
  invariant.

### 30.6 — Anomaly → open-problem promotion [P1]

Close the loop. An anomaly (30.1) or residual structure (30.2) that
survives replication and has **no** literature explanation (30.4) is
exactly an `UNEXPLAINED_OBSERVATION` — the most honest "novel" outcome the
system can produce.

- Promote such items to first-class open problems: write them as
  `UNEXPLAINED_OBSERVATION` papers, and seed a `ProgramOpenQuestion` so
  future cycles deliberately re-target them (the Phase 2 surprise →
  open-question wiring already exists).
- **Adversarial promotion gate:** before an anomaly is published as a
  claim (not just logged), an independent bot re-runs it at fresh
  parameters/seeds *trying to refute it* (extend the existing dispute
  path). Only survivors of N refutation attempts publish; this is what
  turns Phase 23's nominal `falsificationCondition` into a real loop.
- **Acceptance:** one `UNEXPLAINED_OBSERVATION` paper publishes that (a)
  cites a surviving anomaly/residual, (b) records ≥N independent refutation
  attempts that failed to break it, (c) carries no OEIS/literature match,
  and (d) seeds a `ProgramOpenQuestion`.

### 30.7 — Mechanism rigor for conjecture investigations [P1]

The `## Mechanism` section of a `CONJECTURE_INVESTIGATION` paper currently asks
the narrator for "a structural reason the bound holds" as **freeform, unverified
prose**. The adversarial check (`verify_conjecture`) tests the *bound* on
held-out windows — it never tests the *mechanism*. So a stated mechanism can
name any reasoning mode but the system performs **none** of them rigorously.

**Shipped (cheap first step, 2026-05-29):** the conjecture template
(`narration.conjecture_investigation` v2-mechanism-kind) now forces the narrator
to declare a **`Mechanism kind:`** tag — one of:

| Mode | What a rigorous version would require (verification hook) |
|---|---|
| `heuristic_derivation` | self-consistency check: the heuristic's predicted constant matches the mined `k`/`c` |
| `asymptotic_intuition` | a CAS (Sympy/Symbolics via the sidecar) to derive + compare LHS vs RHS growth rates |
| `probabilistic_model` | a Monte-Carlo step simulating the random model (e.g. ω(n)~Poisson(log log n)) and checking the bound's predicted tail |
| `extremal_construction` | an optimizer that *searches for / proves* the saturating case — not just `verify_conjecture`'s worst *sampled* window |
| `analogy_to_known_theory` | the known-laws registry + identity prover ([[`phase-25`]] / 30.4) to confirm the analogy is real and the bound isn't *reducible* to the cited theorem |

The tag is **self-declared and unverified** — it makes the current hand-waving
*auditable* (the panel + a future validator can see which mode is claimed) and
sets up the per-mode hooks above. Making any mode actually rigorous is the real
work; `extremal_construction` is closest today (`verify_conjecture`'s
worst-margin witness is an empirical near-extremal point, not a construction).
Until a hook verifies the declared mode, mechanism prose cannot justify a label
above `CONJECTURE` — the `EMPIRICAL_RESULT` text-floor checking only that the
*words* "mechanism"/"adversarial check" appear is a **syntactic** gate, not a
semantic one, and should eventually require the verified hook for the claimed
kind.

---

## Guardrails (cross-cutting — without these, this phase manufactures HARKing)

Novelty-seeking is precisely the regime where the hypothesis-fitting
failure modes resurface. Every piece above inherits these:

- **Out-of-sample only.** Never score a claim on the data it was fit on
  (enforced by held-out `verify_conjecture`).
- **Multiple-comparisons correction.** Mandatory on 30.1 and 30.5 — the
  scan-many-find-noise risk. `log()` the scan breadth; no silent caps.
- **Null-model floor.** Discovered structure must beat constant/linear
  baselines on held-out data (30.2, 30.5).
- **Irreducibility gate.** Identity prover + constant recognition + OEIS
  re-checked at *publish* time, not just formulation (the narrator can
  drift toward a known form).
- **Adversarial replication.** Independent refutation attempts gate
  promotion to a published claim (30.6).
- **Honest labeling preserved.** `LabelFloor` (Phase 23.6) still caps the
  claim; `THEOREM` stays unreachable; the ceiling for this phase is
  `EMPIRICAL_RESULT` / `UNEXPLAINED_OBSERVATION`.

---

## Flag inventory (all default OFF)

```yaml
app.research.anomaly-miner.enabled            # 30.1
app.research.residual-probe.enabled           # 30.2
app.research.composition-claims.enabled       # 30.3 (extends theory-composition)
app.research.literature-retrieval.enabled     # 30.4 (gated on 23.8 calibration)
app.research.vocabulary-expansion.enabled     # 30.5
app.research.unexplained-promotion.enabled    # 30.6
```

---

## Explicit deferrals

| Item | Why deferred |
|---|---|
| Formal proof (Lean/AlphaProof) | Out of reach; `THEOREM` stays reserved. Unchanged from Phase 23. |
| "ET as instrument" self-referential papers | Different product category; separate from the domain-data novelty path. Unchanged from Phase 23. |
| Multi-domain expansion (econ, neuro) | Orthogonal to novelty mechanism; per the wishlist's deferral. |
| Streaming v2 refinement (paper learns from peer scores) | Big change, separate phase; per the wishlist's deferral. |

---

## What this is and isn't

**Is:** the next layer on the shipped Phase 23–28 novelty stack — it mines
*exceptions and residuals* instead of only regularities, lets the system
*extend its own vocabulary* under an MDL guard, and *grounds novelty in the
literature* so "novel" is verifiable beyond OEIS. The honest top outcome is
a reproducible, irreducible, literature-absent `UNEXPLAINED_OBSERVATION`.

**Isn't:** proof, an AI scientist, or a claim that the system *understands*
what it found. It surfaces structure its own reference model does not
predict and labels it honestly. The deepest piece (30.5 vocabulary
expansion) is genuinely hard and flagged high-risk; everything in Stage 1
is a reuse of components already in `main`.
