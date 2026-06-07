# Queued review — generation engines: library / abstraction learning (the real 30.5 reference class)

**Status: stub / not yet done (queued 2026-06-06).** Motivated by the categorical-framework review
([`phase-30-categorical-patterns.md`](phase-30-categorical-patterns.md)), which established that the
LAMM stack — and ET today — both reduce *generation* to "LLM proposes from a fixed alphabet, a gate
selects." The open, valuable half is **vocabulary growth**: promoting successful compositions into
the alphabet so the search space expands. ET's **30.5** (vocabulary expansion;
[`vocabulary-expansion-mdl.md`](vocabulary-expansion-mdl.md)) is the bet on that half. This review's
job is to mine the literature that actually addresses vocabulary growth — *not* more agent-framework
or categorical papers, which cover the already-solved audit/gate half.

## Why this is the right reference class

The distinction that matters (from the categorical review): does the vocabulary **grow**? The
systems that do this principled-ly are **library / abstraction learning** and **open-ended search**,
where MDL/compression is the "is this abstraction worth keeping" signal — which is *exactly* 30.5's
MDL-as-interestingness guard, generalized. So 30.5 is not naive; it is a small slice of a real,
active research program. This review should make that program explicit and extract what 30.5 should
borrow before it is built.

## Candidate sources to survey (clone/read; not yet done)

| Source | Mechanism | Relevance to 30.5 |
|---|---|---|
| **DreamCoder** (Ellis et al.) | wake/sleep loop: solve tasks, then *abstract* reusable library primitives from solutions via compression (MDL) | the canonical vocabulary-growth engine; 30.5's MDL admission is a slice of its "sleep-abstraction" phase |
| **AI-Feynman** / grammar-expanding symbolic regression | search spaces that add productions/operators, not just fill a fixed grammar | how to let the *grammar* grow, not just the formula |
| **Quality-Diversity** (MAP-Elites, POET) | maintain a diverse archive, don't collapse to one objective; co-evolve problems + solutions | dovetails with ET anti-stagnation ([[project_frontier_discovery_feedback_design]]); guards against vocabulary collapse |
| **SINDy / Cranmer symbolic-from-deep-learning** (already cited by the paper) | sparse identification of governing equations; symbolic extraction with inductive bias | compression-penalized morphisms; relates to 30.2 residual probe |
| **Library-learning follow-ups** (Stitch, LAPS, Lilo — DreamCoder lineage) | faster/LLM-guided abstraction discovery | the practical, recent end; LLM *proposes* abstractions, compression *admits* them — ET-shaped |

## Questions the review must answer (for building 30.5)

1. **Admission criterion.** DreamCoder admits a primitive when it shortens the description of the
   *solved-task corpus*. ET's 30.5 admits a DERIVED scalar when it yields a surviving, irreducible,
   literature-absent conjecture the old vocabulary can't state. Are these the same MDL test, and
   should 30.5 adopt the corpus-compression form (compress the *body of accepted papers/relations*,
   not a single conjecture)?
2. **Proposal source.** DreamCoder enumerates; LAPS/Lilo use an LLM to propose abstractions then
   verify by compression. ET should likely do the latter (LLM proposes DERIVED scalar candidates,
   MDL + identity-prover admits) — confirm this is the right division of labor and how to bound the
   proposal space without freezing it like BreakingTheWorld does.
3. **Reuse / carryover.** Vocabulary growth only compounds if admitted scalars *persist and are
   reused* across runs — which collides with the room-reset problem ([[project_room_reset_on_restart]])
   and depends on phase-31 cross-run memory (§31.x). Map the dependency.
4. **Collapse avoidance.** What stops the grown vocabulary from exploding into meaningless derived
   knobs (the failure mode 30.5's MDL guard targets)? Compare DreamCoder's compression bound vs
   quality-diversity's archive.

## Disposition

This is the **generation-side follow-up** the categorical review pointed to — and the one likely to
actually move ET's blocker, unlike more audit/grounding machinery. Not scoped into waves yet;
do the survey first, then fold concrete recommendations into 30.5's
[`vocabulary-expansion-mdl.md`](vocabulary-expansion-mdl.md). Sources clone under
`/opt/apps/ide/vscode/ai/reference-from-github/` per convention.
