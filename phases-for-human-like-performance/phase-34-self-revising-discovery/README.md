# Phase 34 — Self-Revising Discovery (categorical framework review)

**Status: scoping proposal (2026-06-06) — nothing built.** Sixth external-pattern review, over a
single theory paper rather than a code clone: **Wang & Buehler, *Self-Revising Discovery Systems
for Science: A Categorical Framework for Agentic Artificial Intelligence*** (arXiv:2606.01444v1,
31 May 2026) — the same MIT LAMM lineage as ScienceClaw / SciAgents / ProtAgents / Sparks that the
earlier phase-30 reviews drew on.

Every adopted item ships flag-gated **OFF** per house convention; pipeline-wide code stays
domain-neutral; items gated on external/upstream work ship dormant.

## Files

- [`2606.01444v1.pdf`](2606.01444v1.pdf) — the source paper (36pp + SI), moved here from `docs/`.
- [`phase-34-paper-summary.md`](phase-34-paper-summary.md) — detailed summary: the
  retrieval/search/discovery trichotomy, the copresheaf + category-of-elements formalism, the Kan
  obstruction (the non-subjective discovery signature), the Builder/Breaker MDL case, and
  CategoryScienceClaw.
- [`phase-34-patterns.md`](phase-34-patterns.md) — disposition table + scoped items 34.1–34.5 with
  flags and sequencing.

## Thesis (what this review adds that the earlier ones did not)

The phase-30/31/32 reviews mined *engineering patterns* from sibling repos. This paper supplies a
**formal vocabulary and non-subjective acceptance criteria** for distinctions ET currently makes by
heuristic:

- **Retrieval vs search vs discovery** — most ET papers are *search* (new path in fixed invariant
  vocabulary); genuine *discovery* requires a regime transition (new DERIVED scalar / type). This
  makes ET's standing finding "generation, not retrieval, is the blocker" **measurable** (34.1).
- **The Kan obstruction** — a forced residual that transport of existing vocabulary cannot populate
  *is* the non-subjective signature of discovery; this is exactly a 30.6 `UNEXPLAINED_OBSERVATION`,
  and the generator- vs composite-reachable audit sharpens the 30.5 vocabulary-expansion gate (34.2).
- **MDL on widening adversarial evidence; non-monotone R² is correct** — names ET's anti-stagnation
  behavior as an explicit anti-pattern guard (34.3).
- **Rejected alternatives + old commitments as durable inspectable provenance** — ET wipes these on
  room reset; recording them is net-new and hard-depends on phase-31 §31.6 (34.4, 34.5).

Most items are *diagnostic*, not new subsystems. Lightest honest win: **34.1** — a one-session
novelty-type stamp distribution that quantifies how rarely the room does discovery in the strong
sense.
