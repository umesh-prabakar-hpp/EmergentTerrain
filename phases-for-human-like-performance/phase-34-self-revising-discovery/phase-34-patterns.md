# Phase 34 — patterns for EmergentTerrain + scoping

Adoptable patterns from *Self-Revising Discovery Systems for Science* (arXiv:2606.01444v1;
detailed summary in [`phase-34-paper-summary.md`](phase-34-paper-summary.md)). Sixth
external-pattern review in the lineage.

## Bottom line (read this first)

**Most of phase-34 is not worth building right now.** The paper is a category-theory *framework*,
not a clonable codebase, and its central thesis — *most "AI scientists" only do search inside a
fixed vocabulary; genuine discovery means changing the vocabulary* — describes a limitation **ET
has already diagnosed**: the 23.8 calibration found the corpus is 100% known-result verification,
0 papers at panel-novelty ≥0.7, and the standing finding is *generation, not retrieval, is the
blocker* ([[project_phase23_8_calibration]]).

So the paper gives ET **better vocabulary to describe and measure a limitation it already knows
about**. It is silent on the one thing that is actually ET's bottleneck — *how to make the
generator propose a new DERIVED scalar / invariant type worth admitting*. It offers an **audit**
for such proposals (30.5's job), not a **generator** for them.

Net disposition:

1. **Adopt 34.3** — a one-line gate-authoring-checklist guard (cheap, real, prevents a future
   regression). This is the only buildable win today.
2. **Keep the paper summary** as a reference lens for the already-queued 30.5/30.6 work (same MIT
   LAMM lineage as the phase-30 source repos).
3. **Park 34.1 / 34.2 / 34.4 / 34.5** — each either re-measures the known blocker (output is ~0
   until discovery actually happens), duplicates checks ET already has, or hard-depends on unbuilt
   work. Revisit **only after 30.5/30.6 ship and start producing discovery candidates worth
   measuring.**

House convention if/when any parked item is revived: flag-gated **OFF**, domain-neutral
pipeline code ([[feedback_common_code_domain_neutral]]), dormant-not-stalled
([[feedback_flag_gated_dormant]]).

---

## Why each item lands where it does

### 34.3 — MDL-on-widening-evidence guard (the non-monotone-R² anti-pattern) — **ADOPT**

**Pattern (Builder/Breaker R² 0.48→0.68→0.54→0.41).** Acceptance across stress-test rounds must be
**paired compression on the same refit, widened evidence set**, never monotone improvement of a
single predictive score. A falling fit metric under harder/wider evidence is *correct*; rewarding
monotone improvement rewards term-stuffing.

**Why adopt.** ET's stochastic-null / held-out / replication / anti-stagnation design already
embodies this ([[project_frontier_discovery_feedback_design]]) — so this is **covered**, and the
only net-new piece is *documentation*: a sentence in the gate-authoring checklist flagging any
acceptance rule that compares a bare metric across evidence-set changes, plus optionally one
assertion at the point where evidence sets widen between iterations. Pure regression-prevention,
no runtime flag, ~10 minutes. **Not a phase — a footnote, but a worthwhile one.**

### 34.1 — retrieval/search/discovery novelty stamp — **PARK**

The stamp's output is ~constant ("everything is SEARCH") until 30.5/30.6 produce regime
transitions. Building a classifier whose distribution you already know (and have already measured
once, via 23.8) is busywork. Revive it as a *yield signal* once there is non-zero discovery to
count.

### 34.2 — generator- vs composite-reachable audit — **PARK (premature + largely redundant)**

Gates behind **30.5, which is not built** (flag OFF, deferred). And it substantially overlaps the
identity prover (irreducibility) + MDL guard that 30.5's design already specifies — it is mostly a
categorical *re-framing* of those checks, not a new check. If 30.5 ever ships and the existing
irreducibility test proves to miss nonlinear unary rescalings, *then* this reframing earns its
place. Not before.

### 34.4 — rejected alternatives as durable provenance — **PARK (blocked + weak fit)**

Genuinely net-new, but (a) hard-depends on phase-31 §31.6 (also unbuilt), and (b) ET lacks the
paper's "accept M1 over M0" model-selection surface — the miner retires/re-seeds seeds as
*intentional churn* ([[feedback_no_reseed_skip]]), which is not the same as committing one model
over a competing one. Risk of building machinery for a comparison ET doesn't actually perform.
Revisit only if a real model-selection surface appears *and* 31.6 lands.

### 34.5 — per-claim discovery-cost metric — **PARK**

Depends on 34.1 + 34.2 signals; if those are ~0 (everything is SEARCH), discovery-cost is ~0 too.
Same "re-measures the known blocker" problem as 34.1. Revive alongside 34.1 once discovery is
non-zero.

---

## What ET should take from this paper instead of features

- **A vocabulary for the 30.5/30.6 design**, not new code: "regime transition," "forced residual
  (Kan obstruction) = the non-subjective signature of discovery," "generator- vs composite-
  reachable," "discovery cost as bits beyond transport." Useful when *writing* or reviewing the
  30.5/30.6 work, not as standalone subsystems.
- **Confirmation, with a formal backing, of ET's own finding** that it operates in search mode —
  which strengthens the case for prioritizing the *generative* levers (30.5 vocabulary proposal,
  30.6 promotion) over more retrieval/grounding machinery (30.4/32.5/33.x).
- **34.3** as a concrete guard.

That is the honest extent of it. The earlier disposition table (which scoped five flag-gated waves)
over-stated the buildable value; this section supersedes it.
