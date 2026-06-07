# Phase 30 addendum — categorical-framework review (Wang & Buehler)

Fourth external-pattern review folded into the phase-30 conjecture track, alongside
[`phase-30-external-patterns.md`](phase-30-external-patterns.md),
[`phase-30-arc-patterns.md`](phase-30-arc-patterns.md), and
[`phase-30-simulation-patterns.md`](phase-30-simulation-patterns.md). Unlike those (code clones),
the source here is a **theory paper**, so its value is vocabulary + acceptance criteria for the
already-queued 30.5/30.6 work — not a new build. Originally drafted as a standalone "phase 34"
folder; folded here because the buildable content is a single guard.

**Source:** Wang & Buehler, *Self-Revising Discovery Systems for Science: A Categorical Framework
for Agentic Artificial Intelligence* (arXiv:2606.01444v1, 31 May 2026). Same MIT LAMM lineage as
ScienceClaw / SciAgents / ProtAgents / Sparks. PDF kept in this folder:
[`2606.01444v1.pdf`](2606.01444v1.pdf).

---

## Bottom line

**Most of this paper is not worth building for ET.** Its thesis — *most "AI scientists" only do
search inside a fixed vocabulary; genuine discovery means changing the vocabulary* — describes a
limitation ET has **already diagnosed**: the 23.8 calibration found the corpus is 100% known-result
verification, 0 papers at panel-novelty ≥0.7, and the standing finding is *generation, not
retrieval, is the blocker* ([[project_phase23_8_calibration]]).

So the paper gives ET better **vocabulary to describe/measure** that limitation, and is **silent on
ET's actual bottleneck** — how to make the generator *propose* a new DERIVED scalar worth admitting.
It offers an **audit** for such proposals (30.5's job), not a **generator** for them. Its case
studies quietly confirm this: the "discovery" in Builder/Breaker came from a frontier LLM
(GPT-5.5, `reasoning_effort=high`) proposing DAG edits, with the category theory as post-hoc
bookkeeping over what the LLM did.

**Confirmed at the code level (2026-06-06).** The three released repos were cloned and inspected
(`/opt/apps/ide/vscode/ai/reference-from-github/{scienceclaw, scienceclaw-categoryscienceclaw-mechanics,
infinite, BreakingTheWorld}`; see the NOTES file there). The code is *stronger* evidence than the
prose:
- `BreakingTheWorld/src/dag_search.py` — the Builder's "generation" is **four hardcoded random
  mutation operators** (add/remove/swap/perturb, fixed weights) sampling from a **hardcoded 6-type
  factor alphabet** (`Const, Ident, Pow, IndLE, ReLU, IndEq`). The LLM seed phase proposes only
  *combinations* of those factors, constrained by a strict JSON schema (off-schema proposals
  silently dropped). New factor types cannot be proposed at all.
- `scienceclaw/` — hypothesis/skill generation is LLM **selection/composition of a static skill
  catalog**; the artifact mutator is topology-only (no new payload keys, no LLM). No auto-extension
  of the type ontology.
- `categoryscienceclaw/` (mechanics branch) — **purely audit/gate/proof** (schema validation,
  Kan-transport audit, AIC gate, residual computation). Zero generation; `formalize.py` imports an
  existing run and types it post-hoc.

So the paper's headline "regime extension" (`ModeConditionedCompliance` = a *product* of two
factors) is reachable only because products of existing alphabet factors are already in the search
space — not because anything invents a new type. Generation = **LLM-free-proposes within a fixed,
hand-authored space; a deterministic MDL/AIC gate selects.** This is exactly ET's situation, and
the code offers no escape from it.

**Net:** adopt one cheap guard; keep the rest as a reading lens for 30.5/30.6.

---

## The one thing to adopt — MDL-on-widening-evidence guard

**Pattern (Builder/Breaker R² trajectory 0.48→0.68→0.54→0.41).** Acceptance across stress-test
rounds must be **paired compression on the same refit, *widened* evidence set** — never monotone
improvement of a single predictive score. A falling fit metric under harder/wider evidence is
*correct*; rewarding monotone metric improvement rewards term-stuffing on heterogeneous data.
Signed `ΔL_model` in the paper shows accepted late transitions actually *shrink* the model while
giving the largest MDL gains — discovery includes **retraction/compression**, not just accumulation.

**ET status:** **covered in behavior** — stochastic-null, held-out, replication, and the
anti-stagnation design ([[project_frontier_discovery_feedback_design]]) already embody paired
evaluation. The net-new piece is *documentation*: a line in the gate-authoring checklist flagging
any acceptance rule that compares a bare metric across evidence-set changes (rather than
paired-refit MDL/likelihood on the union), plus optionally one assertion where evidence sets widen
between iterations. ~Footnote effort, pure regression-prevention, no runtime flag. Domain-neutral
([[feedback_common_code_domain_neutral]]).

---

## Vocabulary to carry into 30.5 / 30.6 (lens, not code)

- **retrieval / search / discovery** — a non-subjective trichotomy: retrieval adds an
  already-representable artifact; search finds a new path *inside* the fixed invariant vocabulary
  (what ~all ET papers are); discovery requires a **regime transition** (a new DERIVED scalar /
  invariant type the old vocabulary couldn't express). Useful framing when writing/reviewing 30.5.
- **Kan obstruction = forced residual** (Prop 4) — the non-subjective signature of discovery: an
  isolated new type that transport of existing vocabulary cannot populate is *forced* external
  content. This is precisely a 30.6 `UNEXPLAINED_OBSERVATION` — content no existing-vocabulary
  transport explains.
- **generator- vs composite-reachable** (Table 2) — a candidate new scalar that is a unary
  transform of an existing invariant (log/z-score/rescale) is *generator-reachable* (the trivial-
  rescaling failure mode 30.5 already guards against); one reachable only via a *newly admitted
  product/interaction* of two invariants is *composite-reachable* (the genuine case). A sharper way
  to *talk about* 30.5's MDL + identity-prover irreducibility checks — largely redundant with them
  as *code*, so not worth implementing separately unless those checks prove to miss nonlinear
  unary rescalings once 30.5 ships.
- **discovery cost** = bits in the post-transition state *beyond* transported evidence — a
  continuous "how novel" measure if ET ever has non-zero discovery to quantify
  (`DiscoveryYieldReporter` would be the home).

---

## Explicitly parked (revisit only after 30.5/30.6 produce discovery candidates)

| Candidate lever | Why parked |
|---|---|
| novelty-type stamp (search/discovery) | output is ~constant ("all SEARCH") until discovery is non-zero; re-measures the known blocker |
| reachability audit as code | gates behind unbuilt 30.5; largely duplicates identity-prover irreducibility + MDL guard |
| discovery-cost metric | depends on the two above; ~0 until discovery happens |
| rejected-alternatives as durable provenance | hard-depends on unbuilt phase-31 §31.6; and ET's miner re-seed churn ([[feedback_no_reseed_skip]]) isn't the paper's "accept M1 over M0" model-selection surface |

The honest extent of the contribution for ET: **one checklist guard + a vocabulary for 30.5/30.6.**
