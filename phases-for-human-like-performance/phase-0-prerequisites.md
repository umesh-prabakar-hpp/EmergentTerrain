# Phase 0 — Prerequisites and Refactoring

**Scope:** Architectural cleanup that must happen before Phase 1 starts.

**Goal:** Make the codebase ready to absorb the new domain models without amplifying existing structural debt.
None of this is user-visible. All of it is required.

**Estimated effort:** 3–7 days (AI-assisted; assumes Claude Code or equivalent + a single developer reviewing and integrating)

**Implementation strategy:** **Implement atomically — this is the one exception to the per-iteration rule.** The JPA migration, service splits, and invariant tests reference each other tightly. A half-finished state (e.g., `ResearchPaper` migrated but `LlmService` still monolithic, or `UniversityService` partially carved) is genuinely worse than not starting, because the coupling between subsystems means subsequent work will fight the partial state. Do this as a single coordinated effort — one PR or a tight series merged together — and verify the existing test suite passes end-to-end before declaring it done.

---

# Task 0.1 — Migrate `ResearchPaper` to a JPA Entity

## Why this matters

Currently:

- `ResearchPaper` is a Java record stored in `UniversityService.published`, a `ConcurrentHashMap<UUID, List<ResearchPaper>>`.
- Persistence is a markdown file written to `research/<roomPrefix>/*.md`.
- Restart relies on lazy-load of the max paper number to avoid ID collisions.
- Citation IDs (`citedPaperIds`) live on `NarrationResult` only — they are processed by `UniversityService.processCitations()` (line 221) for the `+0.03` reputation boost, but never persisted on the paper itself. The narration text is the only durable record of citation links.

This is fine for narration output. It is not fine for:

- The citation graph in Phase 1 (Iteration 3) — needs queryable edges.
- Research programs in Phase 1 (Iteration 4) — needs FK relationships from program → papers.
- Surprise scoring in Phase 2 (Iteration 1) — needs per-domain-topic historical queries.
- Formal findings in Phase 4 (Iteration 9) — needs paper IDs as foreign keys.

Every later phase depends on this.

## What to do

- Convert `ResearchPaper` from a record to a JPA entity (or introduce a `ResearchPaperEntity` mirror and keep the record as a transport DTO).
- Create `ResearchPaperRepository extends JpaRepository<ResearchPaperEntity, UUID>`.
- Migrate `UniversityService.published` reads to repository queries; keep the in-memory cache as a write-through cache only if profiling shows it's needed.
- Keep the markdown writer for human-readable output, but treat the DB as the source of truth.
- Index by `roomId`, `domain`, `topic`, `status`, `createdAt`, `peerScore`.
- **Persist citation IDs alongside the paper.** Add a `citedPaperIds` column (or, equivalently, write a `PaperCitation` join table) populated from `NarrationResult.citedPaperIds` at publish time. Currently this data is consumed by `processCitations()` and discarded — Phase 1 Iteration 3 needs it durable.

## Risks

- `UniversityService.maybeTick()` runs frequently; DB calls in the hot path need to be bounded. Bulk-load papers per room at tick start, not per-bot.
- Existing markdown files need a one-time backfill into the DB on first startup after migration.

---

# Task 0.2 — Split `LlmService` into Domain-Specific Services

## Why this matters

`LlmService` is currently 1988 lines. It owns:

- Narration pipeline (`enqueueNarration`, critique loop)
- Formulation (`formulateResearch`)
- Genome mentoring (`enqueueGenomeMentoring`)
- Government digest generation
- Concurrency control (5 semaphore slots)
- Prompt construction for all of the above
- Output parsing for all of the above

Phases 2–4 add at least 5 new LLM workflows:

- Expectation model prediction extraction (Phase 2)
- Experiment plan generation (Phase 2)
- World-intervention hypothesis framing (Phase 3)
- Formal finding extraction (Phase 4)
- Meta-science prompt evaluation (Phase 4)

Adding all of these into `LlmService` makes it unmaintainable.

## What to do

Extract domain-specific services that depend on a thin `LlmService`:

```text
LlmService (thin)
 ├── routing (LlmRouter)
 ├── concurrency slots
 └── raw call/stream API

ResearchNarrationService    ← narration pipeline, critique loop
FormulationService          ← pre-computation hypothesis formulation
GenomeMentoringService      ← genome delta suggestions
GovernmentDigestService     ← digest generation (already partially separated)
```

Each service owns its own prompt builders and result parsers. `LlmService` becomes a routing + concurrency layer only.

## Risks

- The narration callback wiring in `UniversityService` (`mentoringCallback`, `narratingCallback`, `formulationCallback`) needs to be re-pointed to the new services. Easy mistake to drop a callback.
- Don't refactor and add features in the same PR. Refactor first, ship, then start Phase 1.

---

# Task 0.3 — Carve `UniversityService` into Composable Services

## Why this matters

`UniversityService` is 1326 lines and owns:

- Bot lifecycle ticking (aging, qualification, dating, elder merges)
- Research cycle (author selection, computation, peer review, draft enqueue)
- Replication pass
- Population ecology
- Anomaly tracking
- Paper persistence

Phase 1 adds `ResearchProgramService`. Phase 2 adds `ExpectationModelService` and `ExperimentPlannerService`. Phase 3 adds institution services. Phase 4 adds `KnowledgeBaseService` and `MetaResearchService`.

Adding these inside `UniversityService` is not viable.

## What to do

Extract three subsystems now, before Phase 1 begins:

```text
UniversityService (orchestrator only)
 ├── BotLifecycleService     ← aging, qualification, dating, elder merges
 ├── ResearchCycleService    ← author selection, computation, peer review, drafts
 ├── ReplicationService      ← replication pass, dispute marking
 └── PopulationEcologyService ← K, lifespan, dating cooldown calculations
```

`UniversityService.maybeTick()` becomes a 30-line orchestrator that delegates to these.

## Risks

- This is the riskiest task in Phase 0. The state machine inside `UniversityService` is subtle (lifecycle phase transitions, follow-up consumption, dispute propagation). Take small steps and run the existing test suite after each extraction.
- If `UniversityService` lacks tests for these flows, **add them before refactoring**, not after.

---

# Task 0.4 — Backfill Tests for the Research Pipeline

## Why this matters

The research pipeline has a lot of subtle invariants:

- `ResultClassification` must be assigned before LLM narration (post-hoc rationalization gate)
- `PreRegistration` must be written before computation runs
- Peer reviewers must reproduce the deterministic computation
- Anomaly z-score triggers replication only at |z| ≥ 3.0

Phases 1–4 all depend on these invariants. Breaking any of them silently regresses the "no fake science" claim that justifies the whole project.

## What already exists

The codebase already has tests for several research invariants:

- `MathPhysicsEngineTest` — deterministic computation
- `PreRegistrationTest` — pre-narration hypothesis fixing
- `PublishPaperTest` — paper publication path
- `ResultClassificationTest` — classification before narration
- `LlmServiceParseTest`, `LlmServiceQualityPipelineTest` — LLM output handling
- `GovernmentSimulationServiceTest` — government tick coverage

**Run the existing suite first.** This task is "fill gaps," not "build from scratch."

## What to add (gaps to fill)

After running the existing tests, add coverage for whichever of these is missing:

- A degenerate result never produces a paper (silent reject).
- A failed peer review (reviewer disagreement) never produces a paper.
- `AnomalyTracker` Welford state advances correctly across multiple papers in the same `(domain, topic)`.
- Replication mismatch (>0.15 score diff) marks the original paper `DISPUTED` and penalizes the original author's reputation.
- The `+0.03` citation reputation boost fires once per cited paper (and not on self-citation).

These tests are also the safety net for the Task 0.3 refactor.

---

# Phase 0 Completion Criteria

- [ ] `ResearchPaper` persisted as a JPA entity with repository and indexes
- [ ] `citedPaperIds` (or a `PaperCitation` join table) persisted alongside the paper
- [ ] In-memory cache (if retained) is write-through; DB is source of truth
- [ ] One-time markdown → DB backfill executed on first migrated startup
- [ ] `LlmService` reduced to routing + concurrency only
- [ ] `ResearchNarrationService`, `FormulationService`, `GenomeMentoringService` carved out
- [ ] `UniversityService` reduced to orchestration only
- [ ] `BotLifecycleService`, `ResearchCycleService`, `ReplicationService`, `PopulationEcologyService` carved out
- [ ] Existing test suite passes on the refactored code; new gap tests added per Task 0.4
- [ ] No behavioral changes user-visible (this is pure refactor)

---

# Out of Scope for Phase 0

`RoomService` is 4,746 lines — the largest service in the codebase. It is not touched in Phase 0 because it is the room/game-loop layer, not the research pipeline. Any Phase 3 work involving room-scoped institutions or world interventions will brush up against it; plan for incremental extraction at that point, not now.

`MathPhysicsEngine` at 1,430 lines is also large but is a self-contained deterministic computation library with no entanglement with bot lifecycle or LLM state. It does not need refactoring.
