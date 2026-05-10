# Phase 1 — Foundation Layer

**Scope:** Iterations 2, 3, 4

**Prerequisite:** Phase 0 complete (paper migrated to JPA, services carved out).

**Goal:** Establish the identity, memory, and graph structures that every later phase depends on.
A surprising amount of this is already in the codebase — much of Phase 1 is *formalizing existing primitives*, not building from scratch.

**Estimated effort:** 2–3 weeks (AI-assisted). Per-iteration breakdown: Iter 2 ≈ 0.5–1 week (mostly view code over existing fields, ~3–4x compression), Iter 3 ≈ 1–1.5 weeks (clear JPA schema, structured-output extraction, ~3–4x compression), Iter 4 ≈ 1 week (entities + LLM program-creation prompt, ~2–3x compression).

**Implementation strategy:** **Three separate PRs, one per iteration.** Do not bundle. Each iteration is independently demoable: Iter 2 ships a measurable identity surface, Iter 3 ships a citation graph view, Iter 4 ships longitudinal program tracking. Bundling the phase into a single 2–3 week branch causes review friction and bit-rot against `UniversityService`. Recommended order: Iter 2 → Iter 3 → Iter 4 (the order they're written). Iter 4 is behavior-changing — it replaces `pendingFollowUp` semantics with `ResearchProgram.openQuestions` — so ship it behind a feature flag (e.g., `app.research.programs.enabled`) and roll out per room.

---

# Iteration 2 — Persistent Research Identity

## What already exists

The bot already carries most of what `ResearchIdentity` would hold:

| Phase document field | Existing source |
|---|---|
| `favored_methods` | `BotGenome.methodology` (THEORETICAL/COMPUTATIONAL/EMPIRICAL) |
| `risk_tolerance` | `BotGenome.explorationRate` (0.05–0.5) |
| `mathematical_style` | `BotGenome.topicWeights[5]` (implicit specialization) |
| `abstraction_preference` | Derivable from domain affinity in `BotGenome.domain` |
| `reputation_history` | `BotLifecycleState.reputation`, `publishedCount`, `rejectedCount` |
| `ideological_bias` (partial) | `BotCivicState.ideologyLiberty/Order/Welfare/Growth` |

**What's actually missing:**

- `skepticism_level` — how harshly the bot peer-reviews others
- An explicit, queryable "research identity" surface that combines the above into the formats the LLM prompts expect

## What to add

Add a `ResearchIdentity` view class that **reads from existing sources** rather than duplicating state:

```java
public record ResearchIdentity(
    Methodology favoredMethod,           // from BotGenome.methodology
    double riskTolerance,                // from BotGenome.explorationRate
    double skepticismLevel,              // NEW: peer review harshness
    double[] topicAffinity,              // from BotGenome.topicWeights
    double[] ideologyVector,             // from BotCivicState
    ReputationHistory reputationHistory  // NEW: trajectory over time
) {}
```

`skepticismLevel` is genuinely new state — store it in `BotLifecycleState` (already serialized to `lifecycleJson`).

`ReputationHistory` is a new sub-table: `(bot_id, tick, reputation, published_count, rejected_count)` written when the lifecycle persists. This is what makes "reputation_history" actually queryable.

## Where this plugs in

- `FormulationService.buildFormulationPrompt()` (post-Phase-0) reads `ResearchIdentity` to bias hypothesis style.
- `ResearchCycleService.tournamentSelectAuthor()` can weight `skepticismLevel` when assigning reviewers.
- The mentoring callback (`enqueueGenomeMentoring`) already mutates genome fields — extend it to also mutate `skepticismLevel` slowly based on review accuracy.

## Result

The "schools emerge" claim from the original document already partly happens via `BotGenome.domain` and `methodology`. Adding `skepticismLevel` and a reputation trajectory makes this *measurable* — you can ask "are empiricists out-publishing formalists this generation?" and get an answer.

## Implementation notes

- ~50% of this iteration is already in the codebase. Don't duplicate. Treat `ResearchIdentity` as a **view**, not an entity.
- `ReputationHistory` writes should be sampled, not per-tick. Persist on phase transition or every N ticks.

---

# Iteration 3 — Citation and Debate Graphs

## What already exists

- `ResearchPaper.citedPaperIds` is already populated. Either the LLM emits structured IDs in `NarrationResult.citedPaperIds` or `LlmService.parseNarrationResult()` falls back to regex over the `P-XXXX-N` format.
- Citations already grant `+0.03` reputation to cited authors.
- After Phase 0, `ResearchPaper` is a JPA entity, so cross-paper queries are now possible.

## What's missing

- The citations are an **untyped** graph. There's no `supports` vs `contradicts` vs `extends` distinction.
- There's no service for traversing the graph (no contradiction-cluster detection, no citation-count rank).

## What to add

A new edge entity:

```java
@Entity
public class PaperRelationship {
    UUID id;
    UUID sourcePaperId;        // citing paper
    UUID targetPaperId;        // cited paper
    RelationshipType type;     // SUPPORTS, CONTRADICTS, EXTENDS, REPLICATES, CRITICIZES, DERIVES_FROM
    UUID classifiedByBotId;    // null if extracted heuristically
    double confidence;         // LLM-reported confidence in the classification
    Instant createdAt;
}
```

A new service:

```java
public class PaperGraphService {
    List<UUID> citationsOf(UUID paperId);
    List<UUID> contradictedBy(UUID paperId);
    List<ContradictionCluster> findContradictionClusters(UUID roomId, String domain);
    int citationCount(UUID paperId);
    List<UUID> topCited(UUID roomId, int limit);
}
```

## Where this plugs in

- After narration completes, the post-narration step extracts `RelationshipType` for each cited paper. The LLM is already analyzing prior papers in the formulation prompt — extend that to also classify relationship type as structured JSON output.
- `ContradictionCluster` results feed `ExperimentPlannerService` in Phase 2 (Iteration 5) for contradiction-targeted experiments.
- Cited reputation boost (`+0.03`) can be modulated by relationship type: `SUPPORTS` keeps the boost; `CONTRADICTS` is neutral; `CRITICIZES` carries no boost.

## Implementation notes

- Backfill historical papers' `citedPaperIds` into `PaperRelationship` records on first deployment, classifying all existing edges as `EXTENDS` (the safe default).
- LLM-classified edges have higher trust than regex-extracted ones. Track `classifiedByBotId` so meta-science (Phase 4) can score classifier accuracy.
- Don't try to enforce cycle-freeness. Real science has citation cycles (paper A cites B which cites C which cites A).

---

# Iteration 4 — Multi-Step Research Programs

## What already exists

- `BotLifecycleState.pendingFollowUp` carries `FollowUpParams` (param1, param2, predictedScore, priorMethodology) — a primitive single-step chain.
- `BotLifecycleState.researchHypothesis` is a cumulative research question updated from each paper's conclusion.
- `FormulationService.buildFormulationPrompt()` (post-Phase-0) already includes prior hypothesis in the prompt for continuity.

This is **single-step chaining** — not a research program. But the data flow is already in place.

## What to add

Promote the chain to a first-class entity:

```java
@Entity
public class ResearchProgram {
    UUID id;
    UUID ownerBotId;
    UUID roomId;
    String title;                          // LLM-generated at program creation
    String longTermObjective;              // generated from initial hypothesis
    ProgramStatus status;                  // ACTIVE, CONVERGED, ABANDONED
    int paperCount;
    double evidenceFor;                    // accumulated support score
    double evidenceAgainst;                // accumulated contradiction score
    Instant createdAt;
    Instant lastPaperAt;
}

@Entity
public class ProgramOpenQuestion {
    UUID id;
    UUID programId;
    String question;
    double uncertainty;                    // 1.0 = totally open, 0.0 = resolved
    QuestionStatus status;                 // OPEN, BEING_INVESTIGATED, RESOLVED
}

@Entity
public class ProgramFailedApproach {
    UUID id;
    UUID programId;
    String approach;
    String whyFailed;
    UUID associatedPaperId;
}
```

Each `ResearchPaper` gets an optional FK to `ResearchProgram`.

## Where this plugs in

- A bot's first paper in a domain creates a `ResearchProgram` (LLM names it and writes the long-term objective).
- Subsequent papers attach to the program. The existing `pendingFollowUp` mechanism becomes the program's question queue (more durable storage than a single field on the bot).
- When `evidenceFor` or `evidenceAgainst` exceeds threshold, the program closes with a summary paper.
- A bot with high `risk_tolerance` (Iteration 2) can run 2–3 programs simultaneously; conservative bots run 1.

## Result

Instead of:

> "Paper about graph resonance"

You get:

```text
Program: Emergent transport harmonics in constrained networks
  ├── Paper P-1023-1: "Initial harmonic detection..." (evidence: +0.6)
  ├── Paper P-1024-3: "Replication failed at higher density..." (evidence: -0.3)
  ├── Paper P-1027-5: "Refined model accounts for failure..." (evidence: +0.4)
  └── Open questions: 3, Failed approaches: 2
```

## Implementation notes

- `pendingFollowUp` becomes a denormalized cache of "the next open question I'm about to investigate" — but the source of truth is `ProgramOpenQuestion`.
- Don't migrate `researchHypothesis` away from `BotLifecycleState` immediately — keep both for a release, then remove once `ResearchProgram` is proven stable.
- Program creation is LLM-mediated. Add this prompt to `FormulationService` — when a bot starts a paper and has no active program in that domain, prompt the LLM to either: (a) attach to an existing program, (b) start a new program with a stated objective, or (c) skip program tracking for one-off exploration.

---

# Phase 1 Completion Criteria

- [ ] `ResearchIdentity` view class reads from `BotGenome` + `BotCivicState` + `BotLifecycleState`
- [ ] `skepticismLevel` field added to `BotLifecycleState` and used in reviewer assignment
- [ ] `ReputationHistory` table records trajectory at sampled intervals
- [ ] `ResearchIdentity` consumed by `FormulationService` to bias hypothesis style
- [ ] `PaperRelationship` entity persisted with typed relationship classifications
- [ ] LLM extracts relationship type as structured output during narration
- [ ] `PaperGraphService` exposes citations, contradictions, clusters, top-cited queries
- [ ] Historical papers backfilled into `PaperRelationship` (default `EXTENDS`)
- [ ] `ResearchProgram`, `ProgramOpenQuestion`, `ProgramFailedApproach` entities persisted
- [ ] Bots open programs on first domain paper; subsequent papers attach
- [ ] Programs close with summary paper when evidence threshold reached
- [ ] `pendingFollowUp` becomes a cache of `ProgramOpenQuestion` rather than the source of truth
