# Phases for Human-Like Performance — Implementation Status

This file is the rolling index of what's been shipped against the original
design docs in this directory. The phase docs ([phase-0](phase-0-prerequisites.md)
through [phase-4](phase-4-formal-knowledge-and-meta-science.md)) describe the
intended architecture; `STATUS.md` tracks what's actually in `main`.

The original 10-iteration design lives in [readme.md](readme.md) for
historical reference — that doc has not been edited since the design
phase. Named `STATUS.md` rather than `README.md` because git's
`core.ignorecase=true` on this checkout would collide `README.md` with the
existing lowercase `readme.md` and effectively hide one of the two files.

---

## Snapshot

| Metric | Value |
|--------|-------|
| Phases shipped | 6 of 6 (0, 1, 2, 2b, 3, 4) |
| Iterations shipped | 10 of 10 (every Iter from the design docs) |
| Commits | 24 |
| Net new code | ~10,000 lines |
| Tests | 135 → 234 (+99) |
| Behaviour-changing iterations | 6, all behind feature flags |

Every behaviour change is opt-in per room via `app.research.*.enabled`
flags. With every flag off, the system behaves exactly as it did before
Phase 0 began.

---

## Phase tree

### Phase 0 — Refactor foundation ✅

The doc that justified Phase 0's atomic-PR rule: even though `LlmService`
and `UniversityService` were both being carved at the same time, the
state-machine invariants couldn't tolerate half-extractions in `main`.
Ten commits landed in a tight series.

```
0.1   ResearchPaper → JPA + PaperCitation table        5dfb3e7
0.2a  Extract GenomeMentoringService                   030c5da
0.2b  Extract FormulationService                       9e6eeb8
0.2c  Extract ResearchNarrationService                 f902cb3
0.3a  Extract PopulationEcologyService                 72a4fc2
0.3b  Extract ReplicationService                       f0d4211
0.3c  Extract BotLifecycleService                      96df316
0.3d  Extract ResearchCycleService                     1f821d4
0.4   ReplicationServiceTest gap                       616e0af
```

`LlmService`: 1988 → 209 lines.
`UniversityService`: 1326 → 858 lines.

See [phase-0-prerequisites.md](phase-0-prerequisites.md).

---

### Phase 1 — Identity + graph + programs ✅

```
Iter 2  ResearchIdentity view + skepticismLevel + ReputationHistory   ffa80d0
Iter 3  Typed PaperCitation graph + PaperGraphService                 686d210
Iter 4  ResearchProgram + ProgramOpenQuestion + ProgramFailedApproach c07aa17
```

- **Iter 2** wired skepticism into peer-review tolerance and sampled
  reputation trajectory per bot every 10 ticks or on phase transition.
- **Iter 3** added `RelationshipType` (SUPPORTS / CONTRADICTS / EXTENDS /
  REPLICATES / CRITICIZES / DERIVES_FROM) plus `PaperGraphService` with
  union-find contradiction-cluster detection. All new edges default to
  `EXTENDS` until Phase 2b.2's LLM classifier upgrades them.
- **Iter 4** introduced longitudinal programs behind
  `app.research.programs.enabled`. A program accumulates evidenceFor /
  evidenceAgainst and closes as CONVERGED when the margin crosses 5.0.

See [phase-1-foundation.md](phase-1-foundation.md).

---

### Phase 2 — Surprise + planner ✅

```
Iter 1  ExpectationModelService + SurpriseEvent (Welford prior)       f2af789
Iter 5  ExperimentPlannerService (4 strategies, flag-gated)           03bdcf3
```

- **Iter 1** wires Welford-style priors per (bot, domain). Surprise
  events fire when an observed peer score is more than 2σ from the prior
  mean (after a 5-paper bootstrap). High-surprise events become new
  `ProgramOpenQuestion` entries on the bot's research program.
- **Iter 5** lets the planner replace LLM-suggested follow-ups with
  algorithmic parameter selection, behind `app.research.planner.enabled`.
  Strategy priority: CONTRADICTION_TARGET → REPLICATION → EXPLORATION →
  ADAPTIVE_SWEEP. BAYESIAN_OPTIMIZATION is the deferred Phase 2b strategy.

See [phase-2-active-experimentation.md](phase-2-active-experimentation.md).

---

### Phase 2b — Cleanup ✅

```
2b.1  Planner recordOutcome wired through publishPaper                f471fdf
2b.2  LLM-driven typed edge classification for PaperCitation          f510c77
```

Closes two explicit deferrals from Phase 1/2:

- **2b.1** threaded the planner's targeted question id through
  `PaperDraft` → `publishPaper` → `recordOutcome`, so question
  uncertainty decays automatically when a planned paper publishes.
  High-surprise outcomes decay less (the model was wrong, so less was
  learned); calm outcomes decay more.
- **2b.2** extended `NarrationResult` with a `typedCitations` list and
  taught the narration prompt to emit
  `cited_papers:[{id,type,confidence}]`. Now non-EXTENDS edges actually
  appear in the graph; PaperGraphService's contradiction-cluster
  detection has meaningful input.

---

### Phase 2.5 — RoomService refactor ⏳ partially shipped

Pre-Phase-3 refactor. The bulk remains deferred until Phase 3 Iter 6
design crystallises (see [phase-2-5-roomservice-refactor.md](phase-2-5-roomservice-refactor.md)
for the broader carve plan), but the one extraction the design doc
called "almost certainly correct regardless of how Phase 3 design
lands" has shipped:

- ✅ **`BotArchetypeBehaviorService`** — pulled the seven-archetype
  dispatch (`STEWARD` … `NEIGHBOR_MIRROR`) plus its prosperity-score,
  prefers-type and dislikes-type rules out of `RoomService` into
  [BotArchetypeBehaviorService.java](../src/main/java/com/emergentterrain/service/BotArchetypeBehaviorService.java).
  The extraction also promoted the previously-private
  `RoomService.LocalStats` nested class to a top-level package-private
  record in [LocalStats.java](../src/main/java/com/emergentterrain/service/LocalStats.java)
  so the new service can take it as input without depending on a
  `RoomService` inner class. Pure refactor — `RoomService` now
  delegates to `archetypes.archetypeFor / prosperityScore / prefersType
  / dislikesType` from `botVoteDecision`. `RoomService` dropped from
  4,746 → 4,657 lines (−89). Pinned by
  [BotArchetypeBehaviorServiceTest.java](../src/test/java/com/emergentterrain/service/BotArchetypeBehaviorServiceTest.java)
  (8 tests covering deterministic assignment, distribution across all
  7 archetypes, target shape, prosperity-score L1 metric, prefers-type
  rules per archetype, contextual dislikes-type vetoes).

Still deferred (need Phase 3 Iter 6 design pressure to pick the right
seams): `BotMovementService`, `HighwayEmergenceService`,
`RoomStateOrchestrator`, `BotRoomLifecycleService`. Premature extraction
of any of these would create the wrong abstractions.

---

### Phase 3 — World + institutions + markets ✅

Re-ordered from the doc's `6 → 7 → 8` to `6 → 8 → 7`: epistemic markets
(Iter 7) need institutions (Iter 8) to exist as wagering venues.

```
Iter 6 (read-only)  WorldDataQueryService                              ff6abf2
Iter 8 (MVP)        University/Journal/FundingAgency/ReplicationCouncil b481d3f
Iter 7              EpistemicMarketService                              0309225
```

- **Iter 6 (read-only)** — bots can query the running simulation as
  experimental variables (`federal.public_trust`, budgets, mean state
  trust). The write side (`WorldInterventionService`, routing bot
  proposals through the constitutional process) is deferred until
  Phase 2.5 ships.
- **Iter 8 (MVP)** — four institution entities + lazy bootstrap on
  first publish per room. Deeper behaviours (journal-mediated review,
  impact-factor accumulation from citations, FundingAgency grant
  allocation, ReplicationCouncil triggering campaigns, dominantMethodology
  emergence) are deferred to Phase 3b.
- **Iter 7** — prediction markets staked on `PreRegistration` outcomes.
  Resolution is automatic when a later paper publishes after the close
  window; `SurpriseEvent` score determines which side won. Calibration
  scores accumulate per bot so the FundingAgency.calibrationWeight has
  something to consume (currently 0.33 default; the allocation loop that
  reads it is Phase 3b).

See [phase-3-world-integration.md](phase-3-world-integration.md).

---

### Phase 4 — Formal knowledge + meta-science ✅

```
Iter 9   FormalFinding + KnowledgeBaseService                         eb323d8
Iter 10  MetaResearchService                                          ab42534
```

- **Iter 9** — paper prose becomes queryable structure. The narration
  LLM is asked for an optional `formal_findings:[{relation_type,
  dependent_variable, independent_variables, equation_form, claim_status,
  confidence, valid_region_min, valid_region_max}]` array; valid
  entries persist as `FormalFinding` rows. `KnowledgeBaseService`
  exposes by-variable / by-domain / by-paper queries and detects direct
  contradictions (PROPORTIONAL ↔ INVERSE on the same variables, or NULL
  vs. any relationship). Theory composition (A→B, B→C ⇒ A→C) is the
  Phase 4b deferral.
- **Iter 10** — the system observes itself. `MetaResearchService` slices
  history three ways: by `llmCallPath` (which prompt produced what), by
  bot (calibration + hallucination rate), by methodology (long-term
  stability). `PaperKind` (DOMAIN, META) keeps meta-research from
  polluting domain stats. Write-back evolution targets (reviewer
  routing, funding weights, prompt evolution) are Phase 4b.

See [phase-4-formal-knowledge-and-meta-science.md](phase-4-formal-knowledge-and-meta-science.md).

---

## Feature flag inventory

Every behaviour-changing iteration is gated. Default value for all is
`false` — flipping a flag enables the iteration for the rooms that
process under it.

```yaml
app.research.programs.enabled       # Phase 1 Iter 4 — longitudinal programs
app.research.planner.enabled        # Phase 2 Iter 5 — algorithmic experiment plans
app.research.institutions.enabled   # Phase 3 Iter 8 — universities/journals/funding/council
app.research.markets.enabled        # Phase 3 Iter 7 — reputation-staked markets
app.research.findings.enabled       # Phase 4 Iter 9 — formal-finding persistence
app.research.meta.enabled           # Phase 4 Iter 10 — self-observation stats
app.research.reviewer-routing.enabled  # Phase 4b — adversarial reviewer picking
app.research.funding-allocation.enabled # Phase 4b — periodic grants from agency → university
app.research.journal-review.enabled  # Phase 4b — reviewers drawn from journal editor pool
app.research.impact-factor.enabled   # Phase 4b — Journal.impactFactor recomputed from citation counts
app.research.replication-council.enabled # Phase 4b — CONTRADICTS citations force replication + council counters
app.research.auto-staking.enabled    # Phase 4b — same-domain bots auto-stake on open markets each tick
app.research.dominant-methodology.enabled # Phase 4b — University.dominantMethodology emerges from member modes
app.research.surprise-broadcast.enabled # Phase 4b — fan out high-surprise events to per-room in-memory buffer (rate-limited)
app.research.exploration-tuning.enabled # Phase 4b — per-room z-threshold + EXPLORATION trigger adjust from replication failure rate
app.research.theory-composition.enabled # Phase 4b — A~f(B) ^ B~g(C) chains emit derived ProgramOpenQuestions
```

Phase 0 (refactor), Phase 1 Iter 2 (identity view), Phase 1 Iter 3 (typed
edge schema), Phase 2 Iter 1 (surprise scoring), Phase 2b (cleanup), and
Phase 3 Iter 6 read-side queries are not flag-gated — they're either
pure refactors or new read-only surfaces that don't change existing
behaviour.

### Rollout recipe

To enable an iteration per room, set the corresponding flag in
`application.yml` (or via env var) and restart. Each flag's service
performs lazy bootstrap on the first paper publish after the flag is on,
so existing rooms pick up the feature without a data migration.

---

## Service map

After Phase 0 + four behavioural phases (plus Phase 4b and the first
Phase 2.5 carve), the research package is 17 specialist services +
1 archetype service in `com.emergentterrain.service` + 14 entities.
Refreshed against the working tree (post-`ab42534`):

| Service | Lines | Responsibility |
|---------|-------|----------------|
| ResearchNarrationService | 1304 | narration pipeline + critique + audit + professor experiments |
| UniversityService | 954 | orchestrator + citation tracking + bridging + classification + paper persistence |
| ResearchCycleService | 529 | author selection + computation + peer review + drafts |
| GenomeMentoringService | 427 | LLM genome deltas + dynamic directives + skepticism drift |
| InstitutionService | 326 | University/Journal/FundingAgency/Council bootstrap + journal editors + impact factor + council triggering + dominant-methodology |
| EpistemicMarketService | 325 | reputation-staked prediction markets + auto-staking heuristic |
| BotLifecycleService | 324 | aging + competency + dating + elder merges |
| KnowledgeBaseService | 319 | formal finding persistence + contradiction detection + theory composition |
| FormulationService | 288 | pre-computation hypothesis + novelty assessment + identity-biased framing |
| ExperimentPlannerService | 278 | algorithmic param selection (4 strategies, flag-gated) |
| ReplicationService | 221 | replication queue + dispute marking |
| LlmService | 209 | digest + consultation + streaming |
| FundingAllocationService | 209 | per-room periodic grants from agency → university (Phase 4b) |
| MetaResearchService | 193 | self-observation stats (path/bot/method) |
| ResearchProgramService | 167 | longitudinal programs (flag-gated) |
| ExplorationTuningService | 139 | per-room z-threshold + EXPLORATION trigger auto-tuning (Phase 4b) |
| PaperGraphService | 129 | citation queries + contradiction clusters |
| ExpectationModelService | 126 | Welford priors + surprise scoring |
| SurpriseBroadcastService | 126 | rate-limited per-room cross-bot surprise fan-out (in-memory buffer) |
| WorldDataQueryService | 106 | read-only simulation variables |
| BotArchetypeBehaviorService | 89 | seven-archetype dispatch + prosperity + prefers/dislikes rules (Phase 2.5) |
| PopulationEcologyService | 73 | K + lifespan + dating cooldown |

The Phase 0 carve dropped `LlmService` from 1988 lines and
`UniversityService` from 1326 lines. Together those two services account
for over 70% of the line count reduction across the project.

---

## What's deferred (Phase 4b)

Everything below was explicitly called out in a phase doc as "deferred"
— not blockers, not omissions. They fall in roughly five categories:

### State-machine deferrals

These need stateful integration with existing services:

- **`WorldInterventionService`** (Phase 3 Iter 6 write side) — bots
  propose world changes via `GovernmentBill`; needs Phase 2.5 first.
- **Reviewer-orthogonality routing** (Phase 4 Iter 10 evolution target
  #1) — needs `BotCivicState.ideologyVector` threaded through
  `ResearchCycleService.pickReviewer`.
- ✅ **Auto-staking heuristic** (Phase 3 Iter 7) — shipped (Phase 4b
  sixth commit). `EpistemicMarketService.maybeAutoStake` fires from
  `UniversityService.maybeTick` for each room: picks up to 3 random
  same-domain bots per open market, has bots with reputation > 0.6
  back CONFIRMS and the rest back REFUTES at a 2% stake fraction.
  Skips bots that already staked (`existsByMarketIdAndBotId`) and
  markets past their `closesAt`. Gated by
  `app.research.auto-staking.enabled` independently of the markets
  flag — auto-staking implicitly requires markets to be on too.
- ✅ **Cross-bot surprise broadcast** (Phase 2 Iter 1) — shipped
  (Phase 4b eighth commit). New `SurpriseBroadcastService` maintains
  an in-memory per-room buffer (max 5 retained, newest-first). Every
  `SurpriseEvent` persisted by `ExpectationModelService` is routed
  through `maybeBroadcast` from the publish path, subject to a
  per-room cooldown (default 30s, configurable via
  `app.research.surprise-broadcast.cooldown-seconds`). The read-side
  surface `recentBroadcasts(roomId, excludeBotId, limit)` lets future
  LLM-prompt builders inject the room's recent high-surprise findings
  into a neighbour's context without re-querying the SurpriseEvent
  table. Gated by `app.research.surprise-broadcast.enabled`.

### Service-deepening deferrals

The Phase 3 Iter 8 institutions MVP ships the entities + bootstrap but
not their full behaviour:

- ✅ **Journal-mediated review** — shipped (Phase 4b third commit).
  `ResearchCycleService.pickReviewer` now consults
  `InstitutionService.journalEditorsFor` to narrow the candidate pool
  to the top-5 reputation same-domain bots when
  `app.research.journal-review.enabled` is set. Falls back to the
  existing pool when no journal exists for the (room, domain).
- ✅ **Impact-factor accumulation** — shipped (Phase 4b fourth commit).
  `InstitutionService.maybeRecomputeImpactFactor` recomputes
  `Journal.impactFactor` as the mean citation count across its papers
  (read via `ResearchPaperRepository.findByJournalId` + per-paper
  `PaperCitationRepository.countByCitedPaperId`). Fires from the publish
  path's `recordJournalAcceptance` / `recordJournalRejection` branches.
  Gated by `app.research.impact-factor.enabled`.
- ✅ **FundingAgency grant allocation** — shipped. See "Funding
  allocation weighting" under *Meta-science evolution targets* below
  (`FundingAllocationService`, gated by
  `app.research.funding-allocation.enabled`).
- ✅ **ReplicationCouncil triggering** — shipped (Phase 4b fifth commit).
  Any paper whose narration asserts a `CONTRADICTS` typed citation is
  queued for replication regardless of z-score; the council's
  `activeReplicationCount` / `completedCount` counters are updated on
  enqueue and on each successful `ReplicationService.processOne` call
  (which now returns a boolean signalling whether a request was actually
  processed). Gated by `app.research.replication-council.enabled`. The
  high-surprise (z ≥ 3) path was already covered by ReplicationService
  itself, so the council only adds the contradiction trigger.
- ✅ **DominantMethodology emergence** — shipped (Phase 4b seventh
  commit). `InstitutionService.maybeRecomputeDominantMethodology`
  fires from `UniversityService.maybeTick` per room: counts each
  bot's `BotGenome.methodology`, picks the mode (alphabetic tie-break
  for determinism), and writes it to `University.dominantMethodology`
  when changed. Idempotent — no save when the mode is unchanged.
  Gated by `app.research.dominant-methodology.enabled`. Reputation
  weighting was deliberately left out of the MVP; a future iteration
  can replace plain counts with reputation-weighted counts.

### Knowledge-substrate deferrals

- ✅ **Theory composition** (Phase 4 Iter 9) — shipped (Phase 4b tenth
  commit). `KnowledgeBaseService.maybeComposeTheories(domain)` fires
  from `UniversityService.publishPaper` after `persistFindings`. For
  each pair of single-IV findings `A~f(B)` and `B~g(C)` with both
  confidences ≥ 0.5 and overlapping valid regions, emits a derived
  hypothesis as a `ProgramOpenQuestion` on the bot that extracted
  f1 — letting the next bot to investigate produce a real
  `FormalFinding` through normal experimentation. The simulation
  verifies, the LLM doesn't.

  Composition table: PROP×PROP=PROP, PROP×INV=INV, INV×PROP=INV,
  INV×INV=PROP. THRESHOLD and NULL never compose. Confidence
  propagates multiplicatively. Pairs deduped by sorted paperId-pair
  key. Circular compositions (A→A) skipped. Cap of 5 derived
  questions per call, highest-confidence first. Gated by
  `app.research.theory-composition.enabled`.
- **LOGARITHMIC / CATEGORICAL relation types** (Phase 4 Iter 9) — narrow
  to PROPORTIONAL / INVERSE / THRESHOLD / NULL initially.

### Meta-science evolution targets

Per the phase doc's ordering (ascending risk):

1. ✅ **Reviewer-orthogonality routing** — shipped (Phase 4b first commit).
   `ResearchCycleService.pickReviewer` now picks the candidate with the
   highest topic-weight orthogonality to the author instead of a random
   same-domain pick, gated by `app.research.reviewer-routing.enabled`.
2. ✅ **Funding allocation weighting** — shipped (Phase 4b second commit).
   `FundingAllocationService` self-paces every 30 ticks per room: tops
   up the agency from `BudgetState.education`, scores the room's bots
   using `GrantPolicy.{trackRecord, calibration, novelty}Weight ×
   {reputation, calibrationScore, 1 - hallucinationRate}`, and credits
   `University.computeBudget`. Persists a `Grant` row capturing the
   score breakdown. Gated by `app.research.funding-allocation.enabled`.
3. ✅ **Exploration-heuristic auto-tuning** — shipped (Phase 4b ninth
   commit). New `ExplorationTuningService` adjusts two knobs per room
   every 10 replications, driven by the replication failure rate
   tracked in `ReplicationService.recentFailureRate`:
   - Replication z-threshold (previously the static
     `ANOMALY_REPLICATION_Z = 3.0`), now read via
     `getZThreshold(roomId)` in `UniversityService.publishPaper`.
     Clamped to `[1.5, 5.0]`.
   - `ExperimentPlannerService` EXPLORATION trigger (previously the
     static `EXPLORATION_TRIGGER_UNCERTAINTY = 0.25`), now read via
     `getExplorationTrigger(roomId)` in `planExploration`. Clamped
     to `[0.10, 0.50]`. Setter-wired to avoid constructor cycle.

   Failure rate below 10% raises both thresholds (push toward
   persistence — Ramsdell's "stubborn"). Above 40% lowers both (push
   toward broader search). Mid-range = no change. Cold-start uses
   static defaults until 10 replications have accumulated in the
   room. Gated by `app.research.exploration-tuning.enabled`.
4. **Prompt-template evolution** — versioned templates, A/B testing,
   automatic rollback if downstream replication rates degrade. Highest
   risk; needs guardrails. Scoping doc:
   [phase-4c-prompt-template-evolution.md](phase-4c-prompt-template-evolution.md).
   Three pieces (memo / registry refactor / evolution loop); only the
   first can start immediately. The evolution loop is gated on 2–4
   weeks of observed runtime with the existing Phase 4b flags on.
   - ✅ **Piece 1 (memo)** shipped — see
     [phase-4c-piece-1-memo.md](phase-4c-piece-1-memo.md). Locks
     decisions on slot syntax (`{{ name }}`), allocation
     (epsilon-greedy ε=0.10), per-paper split stratified by bot,
     1000-paper rolling window, two-proportion z-test α=0.01 with
     5pp MDE, per-domain isolation, hand-curated variant pool only
     (no LLM-driven mutation in 4c.1). Piece 2 (registry refactor)
     can start whenever; Piece 3 (evolution loop) still gated on
     observed runtime.
   - ✅ **Piece 2 gate 3 — per-paper template provenance** shipped.
     `ResearchPaper` carries `narrationTemplateId` /
     `narrationTemplateVersion` columns; constants for all twelve
     prompt templates live in `PromptTemplateIds`; `NarrationResult`
     stamps the primary narration template on every emitted result;
     `UniversityService.publishPaper` copies the provenance onto the
     persisted row. Removes the data-backfill step Piece 3 would
     otherwise need. See the memo's "Pre-conditions" section.

### Refactoring deferral

- **Phase 2.5 RoomService refactor** — 4,746-line carve. Unlocks Phase
  3 Iter 6 write side. See its dedicated design doc.

---

## How to read this directory

```
STATUS.md                                    ← you are here (shipped vs deferred)
readme.md                                    ← original 10-iteration design doc
phase-0-prerequisites.md                     ← JPA migration + service carves
phase-1-foundation.md                        ← identity + graph + programs
phase-2-active-experimentation.md            ← surprise + planner
phase-2-5-roomservice-refactor.md            ← deferred RoomService refactor
phase-3-world-integration.md                 ← world + institutions + markets
phase-4-formal-knowledge-and-meta-science.md ← formal findings + meta-science
phase-4c-prompt-template-evolution.md        ← scoping doc for prompt-template evolution
phase-4c-piece-1-memo.md                     ← design memo locking Piece 1 decisions
phase-4c-prompt-template-evolution.md        ← scoping doc for the last Phase 4b deferral
```

Each phase doc retains its full design content. `STATUS.md` is the live
tracker of what's been shipped; the per-phase docs are the shape of
intent against which to evaluate the shipped state.
