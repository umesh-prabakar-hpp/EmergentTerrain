# Phases for Human-Like Performance — Implementation Status

This file is the rolling index of what's been shipped against the original
design docs in this directory. The phase docs ([phase-0](archive/phase-0-prerequisites.md)
through [phase-4](archive/phase-4-formal-knowledge-and-meta-science.md)) describe the
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
| Phases shipped | 13 closed (0, 1, 2, 2b, 2.5, 3, 4) + Phase 5 Waves 0–5 + Phase 6 D1.1–D2 + Phase 7 E/F/G + Phase 8 P1–P5 + Phase 9 U1–U7 + Phase 10 + Phase 11 C1–C5b + Phase 12 D1–D7; **Phase 13 E1–E6 code shipped (E1/E2/E4/E5 verified vs refresh #2; E3 awaits an RLC paper; E6 flipped ON 2026-05-17); Phase 14 F1–F6 code shipped, Tier 1 + F1b (Tier 2) flags ON; Phase 15 rolling flag-rollout queue tracks 4 remaining deferred flags; Phase 16 code shipped (validator + narrator + scheduler), four new flags joining Phase 15 queue** |
| Iterations shipped | 10 of 10 (every Iter from the design docs) |
| Commits | 24 + 15 Phase 5 + 5 Phase 6 + 102 Phase 7–13 |
| Net new code | ~10,000 + ~2,300 (Phase 5) + ~1,200 (Phase 6) + ~15,000 (Phase 7–13) lines |
| Tests | 135 → 577 → 690 → 704 → 709 → 717 → 1,400+ (+1,265) |
| Behaviour-changing iterations | 6 + 10 Phase 5 + 2 Phase 6 + Phase 7 multi-provider/structured-findings/topic-expansion + Phase 8 unrestricted-math + Phase 9 unified-registry + Phases 10–13 corpus-driven hardening, all behind feature flags |

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

See [phase-0-prerequisites.md](archive/phase-0-prerequisites.md).

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

See [phase-1-foundation.md](archive/phase-1-foundation.md).

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
  ADAPTIVE_SWEEP. BAYESIAN_OPTIMIZATION shipped as a Phase 2b MVP that
  uses recent-paper peerScore to scale the sweep radius — exploits the
  bot's learned region when the recent track record is strong.

See [phase-2-active-experimentation.md](archive/phase-2-active-experimentation.md).

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

### Phase 2.5 — RoomService refactor ✅

Pre-Phase-3 refactor. All five extractions originally enumerated by the
design doc ([phase-2-5-roomservice-refactor.md](archive/phase-2-5-roomservice-refactor.md))
have now shipped, plus a dead-audit-code deletion that preceded the
RoomStateOrchestrator carve and three additional second-wave carves
(`TileValidatorService`, `OccupancyService`, `BotSimulationGpuService`),
plus the three waves of the fourth second-wave carve
(`RoomProposalService` Phase 1 — codec + tile-math helpers; Phase 2 —
bot-vote machinery; Phase 3 — vote/evaluate decisions). The
`applyProposal` cache-write side stays in RoomService as a callback
passed through. RoomService is down 4,746 → 3,211 lines (−1,535, −32%)
cumulatively. A second wave of extractions has been identified beyond
the original plan — see "Next planned carves" at the end of this section.

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
- ✅ **`HighwayEmergenceService`** — pulled the emergent-highway
  promotion / decay loop, the per-move pressure deposit, and the
  pressure-seeding helper out of `RoomService` into
  [HighwayEmergenceService.java](../src/main/java/com/emergentterrain/service/HighwayEmergenceService.java).
  All twelve `app.highways.emergent.*` `@Value` tunables migrated to
  the new service. The pure `evolve(int[][], boolean[][], double[][])`
  mutates the highways and road-pressure arrays in place; `RoomService`'s
  `evolveHighwaysFromDemand` is now a thin wrapper that delegates to
  `evolve` and refreshes its own `CachedRoomState` bookkeeping
  afterwards. `addRoadDemandForMove` delegates to
  `highways.computeMoveDeposit(...)` and applies the returned `MoveDeposit`
  to the cache. `seedRoadPressure` callsites all delegate too.
  `RoomService` dropped a further 4,657 → 4,540 lines (−117). Pinned
  by [HighwayEmergenceServiceTest.java](../src/test/java/com/emergentterrain/service/HighwayEmergenceServiceTest.java)
  (13 tests covering pressure seeding, disabled-mode no-op, long-relocation
  pressure, the 1.5× cap on travel-distance scaling, exploration pressure,
  low-mobility no-op, endpoint multipliers, decay+removal, promotion above
  threshold, water-tile skip, empty/null inputs, and the `seedPressure()`
  getter).
- ✅ **`BotMovementService`** — pulled the pure path-planning + per-step
  scoring helpers out of `RoomService` into
  [BotMovementService.java](../src/main/java/com/emergentterrain/service/BotMovementService.java).
  All four `app.bots.{planLength,reverseCooldownTicks,reversePenalty,headingBias}`
  tunables migrated to the new service. `BotMotionState` was promoted
  to its own top-level package-private class
  ([BotMotionState.java](../src/main/java/com/emergentterrain/service/BotMotionState.java))
  so the new service can mutate it without depending on a `RoomService`
  inner class. Methods extracted: `pickStep`, `stepTowardTarget`,
  `tilePreferenceScore`, `isInBounds`, `recordMoveOnMotion`, plus the
  three internal helpers `planPath`, `chooseBestNeighbor`,
  `antiOscillationAdjustment`. `RoomService` retains the orchestration
  (`tickBots`, `applyBotMove`, `addRoadDemandForMove`, the JSON / GPU
  codecs for `BotMotionState`, `assignBotSpawn`, `shouldRelocate`,
  `findBestProfileLocation`) — these still touch the room cache map,
  bot/participant repositories, and the proposal-vote machinery, none of
  which belong with movement math. `botVoteDecision` delegates
  `tilePreferenceScore` through the service. `RoomService` dropped a
  further 4,540 → 4,181 lines (−359). Pinned by
  [BotMovementServiceTest.java](../src/test/java/com/emergentterrain/service/BotMovementServiceTest.java)
  (14 tests covering bounds, profile scoring with defaults,
  `recordMoveOnMotion` cooldown application, `stepTowardTarget` corners
  including null/at-target/highway-bonus paths, `pickStep` planned-path
  reuse vs replan, surrounded-out-of-bounds, tunable accessors, the
  reverse-penalty discouraging back-into-last-tile behaviour).

- ✅ **Dead `RoomStateSnapshot` audit code removed** (preceding the
  RoomStateOrchestrator carve). The entity + repo wrote one row per
  cache flush but no production code ever read them back; the repo
  had zero custom finders and the snapshot only stored aggregate tile
  counts (not the actual tile arrays) so it couldn't have been used
  for restore even in principle. Two integration tests asserted on
  the row count but were guarding an implementation behaviour with no
  consumer; their asserts were dropped while keeping their broader
  rapid-change persistence coverage. The Phase 2.5 design doc's
  framing of RoomStateOrchestrator as "snapshot/restore is risky" was
  describing risk that didn't exist in the actual code; the next
  carve was therefore tractable.
- ✅ **`RoomStateOrchestrator`** — extracted the cache→state
  persistence step into
  [RoomStateOrchestrator.java](../src/main/java/com/emergentterrain/service/RoomStateOrchestrator.java).
  `persistIfDirty(Room, RoomState, CachedRoomState) → boolean` owns
  the four-branch decision (cache null/not-dirty no-op, no-material-
  change fast-clear, below-min-changes-not-overdue skip, live write)
  plus the `state.set*Json(...)` mapping and the JPA write. Two flush
  tunables (`app.world.flushMinChanges`, `app.world.flushMaxIntervalMs`)
  migrated to the new service; RoomService's @Scheduled flush loop
  reads them via `orchestrator.flushMinChanges()` /
  `flushMaxIntervalMs()`. Internal `toJson` and `hashTiles` helpers
  live on the new service alongside their lone consumer.
  Scope deliberately narrower than the design doc's "state transitions"
  vision — the deserialization side (`getCachedRoomState`,
  `updateCacheFromState`, `fromJsonTiles`, etc.) stays in `RoomService`
  because those helpers have 4–6 non-cache callsites (visual responses,
  GPU adapter, snapshot endpoints) and dragging them along would
  exceed the carve's value. The flush scheduler also stays in
  `RoomService` because it owns the `proposalLocks` infrastructure
  shared with the proposal-vote path. `RoomService` dropped a further
  4,181 → 4,120 lines (−61). Pinned by
  [RoomStateOrchestratorTest.java](../src/test/java/com/emergentterrain/service/RoomStateOrchestratorTest.java)
  (9 tests covering all four decision branches, the live-write field
  copy + repo `save`, the SHA-256 stateHash compute when cached hash
  is null, the overdue-overrides-min-changes branch, tunable
  accessors, and the constructor floor clamps).

- ✅ **`MapIndexService`** — extracted the per-room coarse-grid
  spatial index used for bot relocation targeting into
  [MapIndexService.java](../src/main/java/com/emergentterrain/service/MapIndexService.java).
  `getCachedMap(Room, int[][], boolean[][])` returns a {@code CachedMap}
  with content-hashed reuse (rebuilds when (tiles, highways) change),
  built from {@code IntegralMaps} (summed-area tables for O(1)
  rectangle sums) plus a stride-sampled list of {@code CoarseCell}s.
  All three nested classes promoted to top-level package-private files
  (`IntegralMaps.java`, `CoarseCell.java`, `CachedMap.java`).
  This carve also unblocked completing the earlier `BotMovementService`
  extraction: `findBestProfileLocation` (which used to live in
  `RoomService` because `CachedMap` was a private inner class) moved
  into `BotMovementService` as
  `findBestProfileTarget(currentX, currentY, tiles, profile, cached, mapIndex)`,
  decoupled from `RoomBot` and `readBotProfile`. Caller in `tickBots`
  now reads the profile once and passes it through.
  `RoomService` dropped 4,120 → 3,946 lines (−174). Pinned by
  [MapIndexServiceTest.java](../src/test/java/com/emergentterrain/service/MapIndexServiceTest.java)
  (6 tests covering integral-map correctness with mixed tile types,
  rectSum on a hand-built table, content-hashed reuse, rebuild on
  tile change, rebuild on highway change) plus 2 new tests in
  `BotMovementServiceTest` for `findBestProfileTarget` (clear-loving
  profile escapes the forest half on a 60×60 board, distance bias
  pulls toward current position when scores tie).

- ✅ **`TileValidatorService`** — extracted the five pure game-rule
  predicates that gate a proposed tile change from passing bot voting
  into
  [TileValidatorService.java](../src/main/java/com/emergentterrain/service/TileValidatorService.java).
  Surface area: `violatesCommunityRules` (the composite gate with the
  same `Math.random() < gate` call order as the pre-carve
  implementation, so observable rejection rates are unchanged with the
  same RNG seed), `hasAccessConnectivity` (largest connected component
  of access tiles in the viewport must cover ≥ 25% of total access
  tiles), `isAccessTile` (clear / road / highway), and
  `isRapidOscillation` (do the two most recent typed proposals disagree
  on type). `floodAccess` is the internal BFS that
  `hasAccessConnectivity` uses; it does not appear on the public
  surface. The service is stateless and has no Spring-injected
  dependencies — all I/O (proposal lookups, JSON parsing) stays with
  the caller, which passes the resolved delta-type list in via a
  `Supplier<List<Integer>>` so the DB lookup fires only when earlier
  rules in `violatesCommunityRules` have not already short-circuited.
  The viewport constants `VISIBLE_WIDTH` / `VISIBLE_HEIGHT` are
  duplicated from `RoomService` for now; the upcoming `OccupancyService`
  carve consolidates viewport math. `proposalDeltaType` stays in
  `RoomService` because three other RoomService callsites
  (`info.setTileType`, `isTrendMajority`, `lastHumanProposalType`) read
  it; a small private helper `recentProposalDeltaTypes(Room)` maps the
  recent proposals to types for the validator's supplier.
  `RoomService` dropped a further 3,946 → 3,851 lines (−95). Pinned by
  [TileValidatorServiceTest.java](../src/test/java/com/emergentterrain/service/TileValidatorServiceTest.java)
  (15 tests covering `isAccessTile` for clear/road/highway/non-access,
  empty highway arrays, `hasAccessConnectivity` for no-access /
  one-cluster / under-25% fragmentation / dominant-cluster cases,
  `isRapidOscillation` for null/empty/single-typed/matching/differing
  histories with null skipping, `violatesCommunityRules` for the
  empty-viewport short-circuit, the adherence-zero gate disabling, the
  share-extreme rule firing reachability, the supplier short-circuiting
  when an earlier rule wins, and the balanced-stats no-violation path).

- ✅ **`OccupancyService`** — extracted occupancy primitives + viewport
  math into
  [OccupancyService.java](../src/main/java/com/emergentterrain/service/OccupancyService.java).
  Surface: `computeViewBounds` / `inView` / `withinRadius` (pure
  viewport geometry, now the single source of truth for the fixed-size
  `VISIBLE_WIDTH × VISIBLE_HEIGHT` window — its constants previously
  lived inline in RoomService and were duplicated by
  TileValidatorService); `canOccupy(x, y, ignoreId, occupants,
  densityAreaSize, maxOccupants)` (density rule check given a
  precomputed occupant list, so the service stays free of the
  `roomStateCache` entanglement); `buildOccupancySnapshot(room)`
  (assembles the participant + bot snapshot from repos); and
  `readOccupancyJson(json)` (Jackson codec for the persisted
  `occupancyJson`). Scope deliberately narrower than the design doc's
  enumerated list: `getCachedOccupancy` and `refreshOccupancySnapshot`
  stay in RoomService because they read/write `roomStateCache` (whose
  other fields — tiles, highways, road pressure, state hash, dirty flag
  — are owned by RoomService's broader state-management responsibilities
  and have nothing to do with occupancy). Pulling them out would
  require threading the cache map across services. Same scope-reduction
  pattern as the RoomStateOrchestrator carve. RoomService keeps those
  two methods as thin cache orchestrators that delegate their
  primitives (`readOccupancyJson`, `buildOccupancySnapshot`) to this
  service. Also picked up TileValidatorService's previously-duplicated
  `VISIBLE_WIDTH` / `VISIBLE_HEIGHT` constants — TileValidatorService
  now takes OccupancyService in its constructor and calls
  `occupancy.computeViewBounds(...)` from `hasAccessConnectivity`,
  closing out the "consolidates viewport math" promise made when
  TileValidatorService shipped. RoomService dropped a further
  3,851 → 3,780 lines (−71). Pinned by
  [OccupancyServiceTest.java](../src/test/java/com/emergentterrain/service/OccupancyServiceTest.java)
  (16 tests covering the visible-constant accessors, viewport
  shrink/clamp/centre branches in `computeViewBounds`, the half-open
  `inView` rectangle, the inclusive Euclidean `withinRadius`,
  `canOccupy` happy path / density-cap / out-of-box / exact-id-skip /
  endsWith-id-skip / null-tolerance / blank-and-null-ignore-id /
  density-area math, `buildOccupancySnapshot` ordering (participants
  before bots) and missing/negative-coord skipping, and
  `readOccupancyJson` empty/blank/bad-JSON tolerance + a round-trip).

- ✅ **`BotSimulationGpuService`** — extracted the GPU bot-simulation
  primitives into
  [BotSimulationGpuService.java](../src/main/java/com/emergentterrain/service/BotSimulationGpuService.java).
  Owns the per-room `gpuWorldCache` map (via `getOrCreateCache(roomId)`
  + `clearCache(roomId)`); provides the pure helpers `buildGpuBotParams`
  (room-level JSON param builder, now takes the tunables as parameters
  so the service has no `@Value` dependencies), `applyGpuBotProfile`
  (stamps profile weights + nudges onto a {@code BotState.Builder},
  with the implicit nudge-then-clamp at 1.0), `collectGpuTileDiffs`
  (incremental world diff, returns `null` when over the edit budget),
  `applyTileEditsToCache` (in-place mutator that folds highway bits
  into type-4 edits), and `encodeBotMotionStateBytes` /
  `decodeBotMotionStateBytes` (the 40-byte little-endian buffer codec
  the GPU engine consumes). The decode signature now takes a seed
  `BotMotionState` instead of a `RoomBot` so the codec stays pure —
  the caller passes the result of `readBotMotionState(bot)` and the
  current bot (x, y). `GpuWorldCache` promoted to a top-level
  package-private class
  ([GpuWorldCache.java](../src/main/java/com/emergentterrain/service/GpuWorldCache.java))
  so the service can own the cache map without depending on a
  RoomService inner class. Also dropped a dead 2-arg
  `applyGpuBotProfile` overload that no production code called.
  Scope deliberately narrower than the design doc's enumerated list:
  `trySimulateBotsWithGpu` (the orchestrator) and
  `applyGpuEditsToCachedState` (the cached-state writer) stay in
  `RoomService`. The orchestrator threads many RoomService internals —
  bot/profile codecs, cached-state retrieval, road-pressure deposit,
  shared-tokens / shared-quests updates — that don't move cleanly
  without dragging the cache map across services. The cached-state
  writer hashes tiles, updates shared tokens / shared quests, and
  marks the cache dirty, all of which entangle with RoomService's
  broader state management. Same scope-reduction pattern as the
  `RoomStateOrchestrator` and `OccupancyService` carves. RoomService
  keeps both methods as thin orchestrators that delegate their pure
  operations to this service. `RoomService` dropped a further
  3,780 → 3,644 lines (−136). Pinned by
  [BotSimulationGpuServiceTest.java](../src/test/java/com/emergentterrain/service/BotSimulationGpuServiceTest.java)
  (17 tests covering cache-idempotency + clear-rotates-worldId,
  `buildGpuBotParams` JSON key coverage, `applyGpuBotProfile` base /
  nudged / clamped-to-one / missing-key-defaults-to-0.5 branches,
  `collectGpuTileDiffs` no-change / highway-folded-as-type-4 /
  exceed-budget-returns-null / zero-grid cases,
  `applyTileEditsToCache` in-place writes + highway clears underlying
  tile / out-of-bounds-and-null tolerance, the 40-byte little-endian
  encode layout asserted slot-by-slot, null-last-marshals-as-MIN_VALUE,
  encode/decode round-trip with currentX/Y snapped to engine-declared
  coords, short-buffer returns the seed unchanged, and the
  negative-cooldown floor clamp).

- ✅ **`RoomProposalService` (Phase 1 — codec + tile-math)** — extracted
  the pure helpers that understand the shape of a {@code RoomProposal}
  record and produce / mutate tile arrays into
  [RoomProposalService.java](../src/main/java/com/emergentterrain/service/RoomProposalService.java).
  Surface: JSON readers `proposalDeltaType` / `readBotVoteSummary` /
  `readNeighborIds` (each returning a defensive default on missing /
  malformed JSON), anchor + delta-type extraction `proposalAnchor` /
  `extractAnchor` / `extractDeltaType` / `extractDeltaTypeFromProposal`,
  and tile-array math `applyDeltaToTiles` / `applyBotChange` /
  `findChangeType` / `validateProposalChange` / `validateProposalDelta`.
  All methods are deterministic on their inputs; the only Spring
  dependency is `ObjectMapper`.
  Scope deliberately narrow — this is the first wave of the design
  doc's `RoomProposalService` carve. The behavioural pipeline
  (`propose`, `votePropose`, `evaluateProposal`, `applyProposal`,
  `applyBotVotes`, `botVoteDecision`, `tryProposeFromBots`,
  `applyNoParticipation`, `canVoteNow`, `buildProposals`, plus the
  DB-fetching variants of `isTrendMajority` and `lastHumanProposalType`)
  stays in `RoomService` because it threads many room-service
  internals — bot lifecycle, GPU adapter, websocket broadcast, the LLM
  services. Future Phase 2.5 sessions can pull the bot-vote machinery
  and the evaluate/apply pipeline on top of this codec, splitting the
  extraction further. Same scope-reduction pattern as the
  `RoomStateOrchestrator`, `OccupancyService`, and
  `BotSimulationGpuService` carves. `RoomService` dropped a further
  3,644 → 3,466 lines (−178). Pinned by
  [RoomProposalServiceTest.java](../src/test/java/com/emergentterrain/service/RoomProposalServiceTest.java)
  (23 tests covering numeric + canonical-string + unknown-string
  `proposalDeltaType` paths, defensive empty/null returns for
  `readBotVoteSummary` and `readNeighborIds`, the persisted-fields-
  prefers-over-delta `proposalAnchor` ordering, `extractAnchor` for
  raw `Map` and JSON-string input paths plus null/blank/no-coords,
  `extractDeltaType` numeric vs. string-name vs. unknown branches,
  `applyBotChange` fresh-copy + input-untouched invariants,
  `applyDeltaToTiles` happy-path and invalid-delta verbatim-copy
  fallback, `findChangeType` row-major-first-difference vs.
  identical-grids, `validateProposalChange` shape-mismatch / zero-
  changes / multi-tile rejections, and `validateProposalDelta` out-
  of-bounds and unknown-type rejections).

- ✅ **`RoomProposalService` Phase 2 — bot-vote machinery** — extended
  [RoomProposalService.java](../src/main/java/com/emergentterrain/service/RoomProposalService.java)
  with the bot-side vote machinery that used to live inline in
  RoomService. Surface added: `localStats(tiles, highways, anchorX,
  anchorY)` (pure viewport tile-share aggregation, now a public method
  the response-builder side of RoomService also delegates to);
  DB-fetching trend helpers `recentProposalDeltaTypes(room)` /
  `isTrendMajority(room, type)` / `lastHumanProposalType(room)` (the
  pre-carve in-RoomService callers of these were thin wrappers around
  `proposalRepository.findTop10ByRoomOrderByCreatedAtDesc`, so the
  whole trio moved together); `botVoteDecision(room, bot, profile,
  baseTiles, proposedTiles, highways, proposalBotGenerated,
  driftCallback)` (the seven-archetype dispatch with NEIGHBOR_MIRROR /
  CONTRARIAN / dislikes / community-rules short-circuits and the
  preference-score fall-through, byte-for-byte preserving the
  pre-carve `Math.random()` call ordering); and `applyBotVotes(room,
  proposal, baseTiles, proposedTiles, highways, profileReader,
  driftCallback)` (the neighbour-set-restricted vote tally that writes
  approvals/rejections + the bot vote summary JSON onto the
  proposal). Profile reads (`readBotProfile`) and the side-effecting
  profile drift (`maybeDriftProfile` + `localRole` / `adjustProfile` /
  `clampProfile`) stay in RoomService — `readBotProfile` has 8+ other
  callsites in RoomService and is also passed as a `Function` reference
  into the government simulation, so moving it would be disruptive;
  `maybeDriftProfile` does a bot.profileJson write + `botRepository.save`
  that's tied to RoomService's broader bot-lifecycle. The carved
  `botVoteDecision` accepts an optional `BiConsumer<RoomBot,
  LocalStats>` drift callback so RoomService can pass `this::driftBotProfile`
  through without re-introducing the coupling. `BotVote` promoted to a
  top-level public enum
  ([BotVote.java](../src/main/java/com/emergentterrain/service/BotVote.java))
  because the `shouldRelocate` bot-lifecycle check in RoomService also
  reads the returned enum (it stays put — its job is rejection-counting
  for relocation decisions, not proposal handling). RoomService dropped
  a further 3,466 → 3,294 lines (−172). Pinned by
  [RoomProposalServicePhase2Test.java](../src/test/java/com/emergentterrain/service/RoomProposalServicePhase2Test.java)
  (15 tests covering `localStats` tile-share math + highway counting,
  `recentProposalDeltaTypes` order + null-preservation,
  `isTrendMajority` empty-history / 2-of-first-3 / null-skipping
  branches, `lastHumanProposalType` bot-skip + all-bots-returns-null,
  the abstain-on-no-change short-circuit, NEIGHBOR_MIRROR matches-vs-
  differs-from-last-human dispatch, CONTRARIAN herd-reject, the drift
  callback firing on the preference-score fall-through path,
  null-callback tolerance, `applyBotVotes` empty-neighbour-list early
  return, and the neighbour-set filter preventing profile reads on
  non-neighbour bots).

- ✅ **`RoomProposalService` Phase 3 — vote/evaluate decisions** —
  extended
  [RoomProposalService.java](../src/main/java/com/emergentterrain/service/RoomProposalService.java)
  with the proposal evaluation pipeline. Surface added: `capQuorum`
  (clamps quorum to `app.consensus.quorumCap`), `canVoteNow(tiles,
  proposal, participant)` (now takes the tiles array as input — the sole
  buildProposals caller fetches once before the loop, eliminating a
  pre-carve N+1 state lookup), `evaluateProposal(room, proposal,
  applyProposal)` (the three-way quorum / min-live-gate / expiry
  decision), `evaluatePendingProposals(room, applyProposal)`
  (maintenance-loop pass over PENDING proposals), and
  `applyNoParticipation(room, proposal, applyProposal)` (expiry
  resolution: locks no-show neighbours after 2 missed votes, then
  applies or rejects based on existing votes plus the optional
  `approveOnTieAfterExpiry` toggle). The actual apply-to-state side
  (`applyProposal`, `applyProposalPatch`, `tryApplyProposalPatchWithGpu`)
  stays in RoomService and is passed through as a `BiConsumer<Room,
  RoomProposal>` callback — same scope-reduction pattern as the Phase 2
  drift callback. Pre-carve `proposalRepository.save` call ordering
  preserved verbatim, including the double-save quirk on the reject
  path of `applyNoParticipation`. RoomService dropped a further
  3,294 → 3,211 lines (−83). Pinned by
  [RoomProposalServicePhase3Test.java](../src/test/java/com/emergentterrain/service/RoomProposalServicePhase3Test.java)
  (17 tests covering `capQuorum` floor + cap clamping, `canVoteNow`
  missing-coord guards and inside/outside viewport branches,
  `evaluateProposal` non-pending no-op / approve-quorum / reject-quorum
  / below-quorum-no-op / expiry-routes-to-applyNoParticipation paths,
  `applyNoParticipation` reject-on-rejections + approve-on-tie (with
  the both-zero exception) + locks-on-second-miss / no-lock-on-first-
  miss / outside-radius-exempt / already-voted-skip branches, and the
  `evaluatePendingProposals` loop dispatch).

**Cumulative**: across BotArchetypeBehaviorService, HighwayEmergenceService,
BotMovementService, RoomStateOrchestrator, MapIndexService,
TileValidatorService, OccupancyService, BotSimulationGpuService, plus
all three RoomProposalService phases and the CachedRoomState/TileCounts/
OccupantSnapshot/BotMotionState/CachedMap/IntegralMaps/CoarseCell/
GpuWorldCache/BotVote promotions and the dead-audit deletion, `RoomService`
is down **4,746 → 3,211 lines (−1,535, −32%)**.

✅ **`BotRoomLifecycleService`** (the design doc's last enumerated
extraction) — `assignBotSpawn` folded into `BotMovementService` with
the canOccupy logic threaded through as a `BiPredicate` (RoomService
keeps a 4-line thin wrapper for its 5 internal callers). The
spawn-positioning RNG seed and 1000-try fallback semantics are
byte-for-byte preserved; pinned by 3 new tests in
`BotMovementServiceTest`.

The deeper deserialization
extraction (`getCachedRoomState`, `updateCacheFromState`, the
`fromJson*` helpers) is also still in RoomService — pulling it would
require either dragging the helpers' 4–6 cross-cutting callsites along
or accepting duplicate JSON-codec helpers in two services. Neither is
worth doing without a stronger forcing function.

**Phase 2.5 is functionally complete** for the second-wave plan: all
four enumerated carves (TileValidatorService, OccupancyService,
BotSimulationGpuService, RoomProposalService Phases 1+2+3) shipped on
2026-05-11. RoomService is down 4,746 → 3,211 lines (−32%).

What stays in RoomService and was not carved (intentional scope
reductions, documented in each carve's bullet):
- `applyProposal` + `applyProposalPatch` + `tryApplyProposalPatchWithGpu`
  — the cache-coupled state-write side of the proposal pipeline. Passed
  into the carved evaluate/apply methods as a callback.
- `buildProposals` — response builder; couples web layer (RoomProposalInfo).
- `findNeighbors` + `findNeighborsForAnchor` — proposal-creation helpers
  used by the `propose` HTTP entry point.
- `getCachedOccupancy` + `refreshOccupancySnapshot` — touch the broader
  roomStateCache that also holds tiles/highways/road pressure/hash.
- `trySimulateBotsWithGpu` + `applyGpuEditsToCachedState` — GPU bot
  simulation orchestrator + cache-write side.
- HTTP entry points (`propose`, `votePropose`, `joinRoom`, etc.).
- Bot lifecycle (`tickBots`, `shouldRelocate`, `assignBotSpawn`, etc.)
  and the maintenance scheduler.
- The `readBotProfile` / `maybeDriftProfile` / `localRole` / `clamp01` /
  `adjustProfile` / `clampProfile` profile-codec cluster.

These could be the seeds of further carves (`ProposalApplyService`,
`BotLifecycleRoomService`, etc.) but none are blocking Phase 3 of the
broader project — the Phase 3 Iter 6 write side (`WorldInterventionService`)
that was gated on Phase 2.5 is now unblocked.

One carve explicitly *not* on the next-up list:
- **Room-membership / lifecycle CRUD** (`createRoom`, `joinRoom`,
  `ensureDefaultRoom`, `populateRoomForPerf`, etc.) — mostly HTTP
  plumbing rather than gameplay logic; not the highest-value next
  step.

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
  trust). The write side (`WorldInterventionService`) shipped after
  Phase 2.5; see the bullet below.
- ✅ **Iter 6 (planner integration)** — `ExperimentPlannerService` gained
  a `planWorldIntervention(ownerBotId, domain)` method that scans the
  program's open questions for any
  `WorldDataQueryService.knownVariableNames()` match (lowercase substring),
  picks the highest-uncertainty matching question, and returns a
  `WorldInterventionService.InterventionProposal` whose `PolicyTarget`
  is mapped from the matched variable: budget axes map to their natural
  policy domains (`education`/`health` → `SERVICES`, `welfare` →
  `HOUSING`, `infrastructure` → `INFRASTRUCTURE`, `justice` →
  `RIGHTS_PROCESS`), non-budget world variables fall back to `SERVICES`
  as the broadest lever. The planner exposes this as a parallel surface
  to `planNext` (not as a `Strategy` enum value) because the output
  type is genuinely different from the math/physics `ExperimentPlan` —
  a world-intervention "experiment" is filing a bill, not picking
  numerical parameters. Caller pipes the proposal into
  `WorldInterventionService.propose(...)`. Gated by
  `app.research.world-intervention.enabled`; off by default; planner's
  own `app.research.planner.enabled` is also required (a world-flag-on
  / planner-off configuration short-circuits to empty).
- ✅ **Iter 6 (write side, `WorldInterventionService`)** — shipped
  after Phase 2.5 closed. Bots file an `InterventionProposal` (hypothesis
  + `PolicyTarget` enum + duration) and the service translates it into
  a `GovernmentState.GovernmentBill` with a real `policyDomain` that
  `GovernmentSimulationService.applyBudgetEffects` already recognises
  (`SERVICES` → "services" → health+education bumps, `HOUSING` →
  "housing" → welfare bump, `LABOR` / `INFRASTRUCTURE` /
  `RIGHTS_PROCESS` similarly). The bill is added to
  `governmentState.bills` at index 0 and the rolling 12-bill cap is
  honoured — `GovernmentSimulationService.advanceBills` picks it up on
  the next tick and runs it through the same committee → vote →
  executive → judicial pipeline as auto-generated bills. The
  `InterventionHandle` returned to the caller carries the
  before-snapshot of all `WorldDataQueryService` variables so a later
  `observe(handle, govState, tick)` call can compute deltas without
  the service needing persistent storage. Bots polling early see
  `BillStatus.IN_PROGRESS` and re-observe later; outcomes resolve to
  `ENACTED`, `BLOCKED`, or `NOT_FOUND` (when the bill ages out of the
  12-bill window). Gated externally by
  `app.research.world-intervention.enabled` at the integration point.
  The planner-side wire-up (`ExperimentPlannerService.planWorldIntervention`)
  shipped alongside, and the sandbox-room flag (`Room.sandbox` column
  + `findTopBySandboxFalseOrderByCreatedAtAsc` for the default-room
  picker) shipped after that — the phase-3 Iter 6 completion checklist
  is now fully closed.
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

See [phase-3-world-integration.md](archive/phase-3-world-integration.md).

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

See [phase-4-formal-knowledge-and-meta-science.md](archive/phase-4-formal-knowledge-and-meta-science.md).

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
app.research.dominant-methodology.reputation-weighted # Phase 4b refinement — bot reputation (clamped [0,1]) weights the methodology count instead of a flat 1
app.research.surprise-broadcast.enabled # Phase 4b — fan out high-surprise events to per-room in-memory buffer (rate-limited)
app.research.exploration-tuning.enabled # Phase 4b — per-room z-threshold + EXPLORATION trigger adjust from replication failure rate
app.research.theory-composition.enabled # Phase 4b — A~f(B) ^ B~g(C) chains emit derived ProgramOpenQuestions
app.research.world-intervention.enabled # Phase 3 Iter 6 write side — bots file GovernmentBill via WorldInterventionService; integration point checks the flag
app.research.truncation-guard.enabled   # Phase 5 A1 — papers whose findings/conclusion end mid-sentence publish as NEEDS_REVISION
app.research.harness-leak-filter.enabled # Phase 5 A4 — strip "starting at position N" / "uses a scale of M" / "quality score of X" leak phrases from prose
app.research.numerical-consistency.enabled # Phase 5 B1 — flag papers whose Conclusion contradicts its Hypothesis or Findings on a numerical claim
app.research.magnitude-range.enabled    # Phase 5 B2 — flag papers whose findings carry magnitudes outside per-(domain,topic) physical bounds
app.research.dedup.enabled              # Phase 5 B3 — flag near-byte-identical reruns of prior room papers that don't cite the prior
app.research.cross-domain-gate.enabled  # Phase 5 B4 — flag papers whose Hypothesis drifts out of its declared (non-INTERDISCIPLINARY) domain
app.research.dispute-reconciliation.enabled # Phase 5 C2 — DISPUTED papers emit a typed ProgramOpenQuestion on both bots' programs
app.research.citation-aware-scoring.enabled # Phase 5 C1 — peer score gains +0.02 per high-quality cited prior, -0.04 per critique:skip cited prior
app.research.literature-search.enabled  # Phase 6 D1.3 — professor stage sees a curated literature block (v2-with-literature templates)
app.research.discussion-section.enabled # Phase 6 D2 — first-pass narrator emits discussion + related_work JSON keys (v2-with-discussion template)

# Phase 7 E1–E4 — multi-provider LLM + budget / router infrastructure
app.ai.openrouter.enabled               # Phase 7 E1.2 — OpenRouter pool (Qwen, GPT-oss-120b)
app.ai.nvidia.enabled                   # Phase 7 E1.3 — NVIDIA NIM API pool (Llama-3.3-70b, Qwen, Nemotron)
app.ai.cerebras.enabled                 # Phase 7 E1.4 — Cerebras pool (low-latency, audit-focused)
app.ai.cerebras.role                    # Phase 7 E1.4 — role assignment for Cerebras (e.g. "fast-audit")
app.ai.tool-calling.providers           # Phase 7 E2 — whitelist of providers for professor-stage tool calls (default "groq"; widened to all four post-Phase 9)
app.research.ollama-fallback.enabled    # Phase 7 E3 — route critique→revision through Ollama when all primary pools exhausted

# Phase 7 F2–F3 — structured findings + multi-topic expansion
app.research.structured-findings.enabled # Phase 7 F2 — narrator emits N H3 prose blocks per paper (flipped ON in F2.10)
app.research.max-computations           # Phase 7 F2 — computations-per-paper cap (default 1; bumped to 5 post-F4)
app.research.topics-v2.enabled          # Phase 7 F3 — uniform sampling over full 38-topic set vs. 5-topic bot-weighted selection (flipped ON in F3.6)
app.research.intra-room-novelty-window-minutes # Phase 7 G9 — duplicate-discouragement window; second paper in same (domain, topic) docked −0.5 novelty (default 30 min)

# Phase 7 F7 + G — panel review + audit-gap routing
app.research.review-panel.enabled       # Phase 7 F7.2 — multi-reviewer panel fan-out (default ON; Phase 9 migrated to PrimaryModelRegistry)
app.research.review-panel.providers     # Phase 7 F7.2 — CSV of providers to fan panel out to (default "*"; pinned to "groq,nvidia,openrouter,cerebras" post-Phase 9)
app.research.review-panel.size          # Phase 7 F7.2 — panel size cap (default 4)
app.research.audit-cherry-picked-rejects.enabled # Phase 7 F7.3 — SUPPORTED papers with ≥2-reviewer cherry_picked_evidence gate publish REJECTED
app.research.audit-physical-mechanism-rejects.enabled # Phase 7 G8 — SUPPORTED papers carrying audit-4b physical_mechanism gap publish REJECTED
app.research.topic-specific-findings-validator.enabled # Phase 7 G6 — RLC R_c boilerplate + Arrhenius scientific-notation prose validator

# Phase 9 — selection-test + HTTP unification
app.ai.selection-tests.enabled          # Phase 9 U5 — startup model validation (chat / JSON / tool probes)
app.ai.selection-tests.retestIntervalMinutes # Phase 9 follow-on — periodic re-probe of dropped models
app.ai.http.requestTimeoutSeconds       # Phase 9 follow-on — unified HTTP read timeout across all pools (default 420s = 7 min)

# Phase 11 — citations-as-schema
app.research.citations-as-schema.mode   # Phase 11 C1–C5b — citation validation mode (warn / downgrade / suppress; default warn)

# Phase 12 — fabrication-gate hardening
app.research.numerical-consistency.mode # Phase 12 D2 — numerical validation mode (warn / downgrade / suppress; replaces the boolean .enabled flag from Phase 5 B1)
app.research.panel-degradation-demote.enabled # Phase 12 D4 — demote PUBLISHED → NEEDS_REVISION when panel degraded (n ∈ {2,3})
app.research.closing-prose-validator.enabled # Phase 12 D6 — reject conclusions citing "no prior theoretical bound" on bounded topics (paired with TextbookBoundRegistry)

# Phase 13 — recommendation fixes
app.research.uncertainty-disclosure.enabled # Phase 13 E6 — reject Findings whose unit-anchored numerics lack uncertainty indicators (default OFF; flag-gated rollout)
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

After Phase 0 + four behavioural phases (plus Phase 4b and nine
Phase 2.5 carves) and Phases 5–13, the research package now spans the
original 17 specialist services + 9 RoomService-derived services in
`com.emergentterrain.service` (BotArchetypeBehaviorService,
HighwayEmergenceService, BotMovementService, MapIndexService,
RoomStateOrchestrator, TileValidatorService, OccupancyService,
BotSimulationGpuService, RoomProposalService) + 14 entities + ~22 new
services/pools/validators/registries added across Phases 5–13 (listed
in the second table below). Pre-Phase-7 services that grew substantially
are flagged with their current line counts (post-`dab1724`):

| Service | Lines | Responsibility |
|---------|-------|----------------|
| ResearchNarrationService | 2188 | narration pipeline + critique + audit + professor experiments + panel integration (grew from 1304 across Phases 7–13) |
| UniversityService | 2165 | orchestrator + citation tracking + bridging + classification + paper persistence + validator routing for all Phase 5–13 gates (grew from 954) |
| ResearchCycleService | 529 | author selection + computation + peer review + drafts |
| GenomeMentoringService | 427 | LLM genome deltas + dynamic directives + skepticism drift |
| InstitutionService | 326 | University/Journal/FundingAgency/Council bootstrap + journal editors + impact factor + council triggering + dominant-methodology |
| EpistemicMarketService | 325 | reputation-staked prediction markets + auto-staking heuristic |
| BotLifecycleService | 324 | aging + competency + dating + elder merges |
| KnowledgeBaseService | 319 | formal finding persistence + contradiction detection + theory composition |
| FormulationService | 288 | pre-computation hypothesis + novelty assessment + identity-biased framing |
| ExperimentPlannerService | 392 | algorithmic param selection (4 strategies, flag-gated) + planWorldIntervention surface for Phase 3 Iter 6 |
| ReplicationService | 221 | replication queue + dispute marking |
| LlmService | 209 | digest + consultation + streaming |
| FundingAllocationService | 209 | per-room periodic grants from agency → university (Phase 4b) |
| MetaResearchService | 193 | self-observation stats (path/bot/method) |
| ResearchProgramService | 167 | longitudinal programs (flag-gated) |
| ExplorationTuningService | 139 | per-room z-threshold + EXPLORATION trigger auto-tuning (Phase 4b) |
| PaperGraphService | 129 | citation queries + contradiction clusters |
| ExpectationModelService | 126 | Welford priors + surprise scoring |
| SurpriseBroadcastService | 126 | rate-limited per-room cross-bot surprise fan-out (in-memory buffer) |
| BotMovementService | 211 | path planning + per-step scoring + tile-preference (Phase 2.5) |
| HighwayEmergenceService | 210 | highway promotion/decay + per-move pressure deposit + seeding (Phase 2.5) |
| RoomStateOrchestrator | 149 | dirty-cache → RoomState persist with flush-threshold gating (Phase 2.5) |
| MapIndexService | 143 | per-room coarse-grid + integral-maps spatial index for relocation targeting (Phase 2.5) |
| TileValidatorService | 192 | community-rules gate + access-connectivity + rapid-oscillation pure predicates (Phase 2.5) |
| OccupancyService | 187 | viewport math + canOccupy density rule + occupancy snapshot build/codec (Phase 2.5) |
| BotSimulationGpuService | 254 | GPU bot-sim primitives + per-room world cache + bot-state codec (Phase 2.5) |
| RoomProposalService | 797 | proposal-record JSON codec + tile-delta math + bot-vote machinery + evaluate/apply decisions (Phase 2.5 Phases 1-3) |
| WorldDataQueryService | 106 | read-only simulation variables |
| WorldInterventionService | 272 | bot-filed interventions routed through the constitutional process (Phase 3 Iter 6 write side) |
| BotArchetypeBehaviorService | 89 | seven-archetype dispatch + prosperity + prefers/dislikes rules (Phase 2.5) |
| PopulationEcologyService | 73 | K + lifespan + dating cooldown |

### New services / pools / validators / registries (Phase 5–13)

| Service | Lines | Responsibility | Phase |
|---------|-------|----------------|-------|
| OpenRouterPool | 669 | LLM pool targeting OpenRouter free/paid backends (Qwen, GPT-oss-120b) | 7 E1.2 |
| NvidiaPool | 636 | LLM pool for NVIDIA NIM API (Llama-3.3-70b, Qwen, Nemotron) | 7 E1.3 |
| CerebrasPool | 610 | Low-latency Cerebras WSE-3 pool, role-routed for fast audit | 7 E1.4 |
| LlmRouter | 467 | Multi-pool routing for chat, JSON-mode, and tool-calling calls; "first non-exhausted wins" policy | 7 E1.5 |
| PromptSanitizer | 468 | Strips harness-parameter leak phrases ("starting at position N", "± X%", chain-of-thought) from prose | 5 A4 → 13 (extended in 7 G4, 9 G7, 10, 12 D1, 13 E5) |
| TopicValidatorRegistry | 404 | Per-(domain, topic) validator dispatch (magnitude bounds, prose patterns); replaces inline `compute*` validators | 7 F1 |
| ModelSelectionTester | 275 | Startup chat / JSON / tool-call probes for every registered model | 9 U5a |
| ReviewPanelService | 230 | Multi-reviewer panel fan-out + deterministic aggregation; cherry-picked-evidence + degraded-panel handling | 7 F7.2 |
| SandboxStaticChecker | 206 | Deterministic safety predicate for `run_sandboxed_code` inputs (replaces reviewer-side heuristic) | 5/8/10 |
| SimulationGate | 181 | Pauses room maintenance when budget-tracked providers cross pause threshold; coolest-spend aggregation post-Phase 13 E1 | 7 |
| LenientJsonParser | 164 | Repair pre-pass for malformed JSON (code-fence strip, brace balance, trailing-comma trim) before Jackson; recovers `rev:parse_failed` | 13 E2 |
| TextbookBoundRegistry | 161 | Per-(domain, topic) catalog of known closed-form bounds (Stefan-Boltzmann, Hall-Petch, …) used by ClosingProseValidator | 12 D6 |
| GpuEvalStaticChecker | 153 | Safety checker for `run_gpu_eval` tool inputs | 8 P4 |
| SymbolicStaticChecker | 153 | Head-level syntax rejection for symbolic input before SymbolicEngine executes | 8 P3 |
| LiteratureSearchService | 153 | Read-only corpus lookup (`findFor(domain, topic)`) over `literature-corpus.yml`; ranks by priority + alphabetical citation | 6 D1.2 |
| PrimaryModelRegistry | 153 | Aggregated 70B+ tool-capable model registry across all providers; substrate for LlmRouter.callPrimary* | 9 U2 |
| SymbolicEngine | 150 | Symja wrapper for the `run_symbolic` tool (with time/iteration bounds) | 8 P3 |
| FastModelRegistry | 146 | Sub-70B chat-only fast-tier registry; backs LlmRouter.callFast | 9 U6 |
| ModelRetestService | 132 | Background re-probing of models that failed initial selection tests | 9 follow-on |
| UncertaintyDisclosureValidator | 119 | Rejects Findings whose unit-anchored numerics lack `±` / "exact closed form" / "not quantified" indicators | 13 E6 |
| TokenAccountant | 105 | Aggregates LLM token usage per provider in rolling minute/day windows; provider-agnostic | 7 E1 |
| ClosingProseValidator | 61 | Rejects conclusions citing "no prior theoretical bound" on topics where TextbookBoundRegistry has an entry | 12 D6 |

The Phase 0 carve dropped `LlmService` from 1988 lines and
`UniversityService` from 1326 lines. Together those two services account
for over 70% of the line count reduction across the project.

---

## What's deferred (Phase 4b)

Everything below was explicitly called out in a phase doc as "deferred"
— not blockers, not omissions. They fall in roughly five categories:

### State-machine deferrals

These need stateful integration with existing services:

- ✅ **`WorldInterventionService`** (Phase 3 Iter 6 write side) —
  shipped. See the bullet under "Phase 3 — World + institutions +
  markets" above for the full design. The planner-side wire-up (the
  design doc's `ExperimentPlannerService` `WORLD_INTERVENTION`
  strategy) is the remaining piece — that's where the
  `app.research.world-intervention.enabled` flag is checked.
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
- ✅ **LOGARITHMIC / CATEGORICAL relation types** (Phase 4 Iter 9) —
  shipped. Both values added to `RelationType` enum alongside the
  original PROPORTIONAL / INVERSE / THRESHOLD / NULL. Semantic
  integration is deliberately limited so the contradiction-detection
  rules stay legible: composition skips both (same treatment THRESHOLD
  already gets — these types are persisted and surfaced but don't fuel
  derived theory questions); contradiction detection adds one new rule
  (LOGARITHMIC ↔ INVERSE → contradiction, since both are monotonic
  with opposite directions); CATEGORICAL doesn't compare with
  continuous monotonic types (it's about discrete class structure, not
  direction). NULL vs LOGARITHMIC and NULL vs CATEGORICAL still count
  as contradictions through the existing any-vs-NULL rule. Pinned by
  5 new tests in `KnowledgeBaseServiceTest`.

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
   [phase-4c-prompt-template-evolution.md](archive/phase-4c-prompt-template-evolution.md).
   Three pieces (memo / registry refactor / evolution loop); only the
   first can start immediately. The evolution loop is gated on 2–4
   weeks of observed runtime with the existing Phase 4b flags on.
   - ✅ **Piece 1 (memo)** shipped — see
     [phase-4c-piece-1-memo.md](archive/phase-4c-piece-1-memo.md). Locks
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
   - ✅ **Piece 2 (registry refactor) — all 11 templates migrated.** New
     `PromptTemplateRegistry` service in
     [PromptTemplateRegistry.java](../src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java)
     with the memo's locked design verbatim: `(id, version)`-keyed
     `PromptTemplate` records, `{{ name }}` named-slot syntax rendered
     by a 20-line in-house substitutor (no Mustache / Jinja dep),
     Java-initialiser seeding in `@PostConstruct`, in-memory only.
     {@code FORMULATION_NOVELTY} migrated as the proof-of-concept —
     {@link FormulationService#buildNoveltyPrompt} now calls
     `registry.render(id, namedParams)` instead of the inline
     `"...".formatted(...)`. The renderer throws on missing slots
     (typo-catching contract), tolerates inner whitespace
     (`{{name}}` vs `{{ name }}`), does not recurse, and treats null
     values as empty strings. `PromptTemplateRegistry.pickActive`
     returns the last-registered variant for an id, ready for Piece 3
     to swap in an epsilon-greedy selector when A/B counters exist.
     Pinned by 15 new tests in
     [PromptTemplateRegistryTest.java](../src/test/java/com/emergentterrain/research/PromptTemplateRegistryTest.java),
     including a byte-for-byte equivalence test that compares the
     registry-rendered output to the old inline
     `String.format(...)` for the same inputs (the regression contract
     the memo asked for: "rendered output, not template body").
     **All 11 templates migrated (12 with NARRATION_REVISION's two
     variants).** Migration order in commit history:
     `FORMULATION_NOVELTY` (proof-of-concept) → `MENTORING_GENOME` +
     `FORMULATION_MAIN` → `NARRATION_AUDIT` + `NARRATION_PROFESSOR_SYSTEM`
     + `NARRATION_PROFESSOR_USER` + `NARRATION_STATISTICAL_FRAMING` →
     `NARRATION_CRITIQUE` + `NARRATION_REVISION` (`v1-from-critique` +
     `v1-from-synthesis`) + `NARRATION_REFINEMENT` + `NARRATION_PRIMARY`.
     Several used the positional `%s%s%s` pattern to splice optional
     context blocks; the migrated templates expose those as composite
     slots (e.g. `{{ optionalContext }}` in FORMULATION_MAIN,
     `{{ preRegAndClassLines }}` in NARRATION_PROFESSOR_USER /
     NARRATION_CRITIQUE, `{{ auditSection }}` in
     NARRATION_PROFESSOR_SYSTEM, `{{ paramsLine }}` +
     `{{ followUpNote }}` + `{{ anomalyNote }}` in NARRATION_PRIMARY)
     or as individually-named slots (MENTORING_GENOME's 19 args),
     keeping the renderer free of conditional logic per the memo's
     "no loops, no number formatting" stance. Numeric pre-formatting
     (`%.2f`, `%.1f`, `%d`) moves into the caller as
     `String.format(...)` so the rendered output matches the pre-carve
     byte-for-byte. NARRATION_REVISION registered two variants under
     the same id (`v1-from-synthesis`, `v1-from-critique`) — production
     callsites pass an explicit version string to disambiguate; Piece 3
     can A/B them as alternative phrasings of the same role.
     `ResearchNarrationService`'s migrated static build methods take
     the registry as their first argument so
     `LlmServiceQualityPipelineTest` can still call them statically
     (15 test callsites updated); production callsites pass
     `promptRegistry` (a new ctor-injected field on the service).
     `seedDefaults()` was promoted to `public` so cross-package tests
     can stand up a freshly-seeded registry. Pinned by 8
     byte-for-byte equivalence tests in `PromptTemplateRegistryTest`
     (one per migrated template; NARRATION_CRITIQUE / NARRATION_REVISION
     / NARRATION_REFINEMENT implicitly equivalence-tested through the
     existing `LlmServiceQualityPipelineTest` `assertThat(prompt).contains(...)`
     coverage). Piece 3 is still gated on 2–4 weeks of observed
     runtime per the scoping doc.

### Refactoring deferral

- **Phase 2.5 RoomService refactor** — 4,746-line carve. Unlocks Phase
  3 Iter 6 write side. See its dedicated design doc.

---

## Phase 5 — Quality Pipeline ✅ (Waves 0–5 shipped 2026-05-12)

Lifts the 27% acceptance-rate plateau the automated peer review
([REVIEW_REPORT.md](../research/a3289005-archive/REVIEW_REPORT.md))
identified across 91 papers + 13 refreshes. Each piece is anchored to
the specific recommendation # it addresses. See
[phase-5-quality-pipeline.md](archive/phase-5-quality-pipeline.md) for the
full scoping doc.

```
Wave 0 (diagnostics)
  C4  Closed-form regression suite (MathPhysicsEngineRegressionTest)   869a110
  C5  Pendulum series uses θ= prefix to keep narrator labels aligned   dbfabc4
Wave 1 (quick wins)
  A2  NEEDS_REVISION / PUBLISHED_NO_PROFESSOR status mapping           e6b1f42
  A3  Sandbox whitelist: sin/cos/tan/exp/PI/E + 9 trig + Math constants cf70584
  A5  Drop canned "no prior theoretical bound" boilerplate              f3130f0
Wave 2 (guards)
  A1  Truncation guard downgrades mid-sentence papers                  177b160
  A4  PromptSanitizer strips harness-parameter leak phrases            a1aac8e
Wave 3 (telemetry)
  C3  Surface sandbox_reject=N on the persisted LLM Call Path          828efca
Wave 4 (validators)
  B1  NumericalConsistencyValidator (close-to-N + range mismatch)      83862db
  B2  MagnitudeRangeValidator (per-domain physical bounds)              74d76af
  B3  ContentHashDeduplicator (uncited-clone NEEDS_REVISION)           77f0f45
  B4  CrossDomainHypothesisGate (in-lane domain drift)                 4437c0c
  B5  Tighten Hardy-Littlewood asymptotic support band                 f0d0ac3
Wave 5 (routing)
  C2  DISPUTED papers enqueue reconciliation open questions            2425ade
  C1  Citation-aware peer-score weighting                              8d285d7
```

Wave 0 pinned the simulator before behaviour changes landed. Wave 1's
three quick wins each hit a top-frequency failure mode. Wave 2 added
opt-in guards behind two new flags (`app.research.truncation-guard.enabled`,
`app.research.harness-leak-filter.enabled`). Wave 3 surfaced previously
log-only telemetry onto the persisted paper row. Wave 4 added four new
flag-gated validators (`numerical-consistency`, `magnitude-range`,
`dedup`, `cross-domain-gate`) that flow into the same NEEDS_REVISION
status path the A1/A2 guards established, plus B5 tightened the H-L
asymptotic band unconditionally. Wave 5 added routing changes: C2
queues a reconciliation question on both bots when a paper transitions
to DISPUTED, and C1 weights peer scores by the quality of the prior
papers cited (+0.02 per high-quality citation, −0.04 per critique-skip
citation).

Wave 6 (D1 LiteratureSearchService + D2 Discussion/Related-Work
section) has been **scoped** as [phase-6-novelty-levers.md](archive/phase-6-novelty-levers.md);
no code lands until the phase doc's pre-conditions (≥ 2 weeks of
Phase 5 runtime, fresh REVIEW_REPORT.md confirming the quality lift
has held) are met. Phase 6 is the first phase that attempts to lift
*novelty* rather than *correctness* — D1's curated literature corpus
crosses the boundary from "verify known closed-forms" to "extend
beyond them" and is the single largest lever the review report names.

D1.1 (the corpus scoping memo) has landed —
[phase-6-piece-1-memo.md](archive/phase-6-piece-1-memo.md). It carries the
per-topic bibliography for all 15 non-INTERDISCIPLINARY engine methods
plus the record-shape, prompt-integration, coverage, and curation
decisions D1.2 will execute against.

D1.2 (corpus YAML + read-only `LiteratureSearchService`) has also
landed. [literature-corpus.yml](../src/main/resources/literature-corpus.yml)
carries 20 references — 15 real-world citations matching each engine
`*At` method + 5 sentinel entries marking the INTERDISCIPLINARY
bridges as engine-constructed. The service exposes
`findFor(domain, topic)` with exact case-insensitive matching, ranks
multi-entry lookups by priority + alphabetical citation, and fails
open on corpus-load errors.

D1.3 (narrator-prompt integration) has shipped behind
`app.research.literature-search.enabled` (default false). When the
flag is on, the professor-tool path renders a
`PRIOR LITERATURE FOR THIS REGION:` block from the corpus into a
`{{ literatureContext }}` slot on three new v2 template variants
(`NARRATION_PROFESSOR_SYSTEM`, `NARRATION_PROFESSOR_USER`,
`NARRATION_REVISION` `v2-from-synthesis-with-literature`). The v2
system prompt also asks the professor to flag whether the paper's
claims align with or extend the cited closed-forms.

D2 (Discussion / Related-Work section) has shipped behind
`app.research.discussion-section.enabled` (default false).
`NarrationResult` and `ResearchPaper` gain `discussion` +
`relatedWork` fields; the parser picks up `discussion` and
`related_work` JSON keys with null/blank/non-string tolerance; the
first-pass narrator renders `NARRATION_PRIMARY v2-with-discussion`
which asks the LLM for the two extra sections in the same JSON
shape. The student-revision step preserves the fields across the
revision so they survive the professor stage. Markdown writer
appends `## Discussion` and `## Related Work` sections when set,
omits both when null. v1 templates remain registered for the
flag-off path and for Phase 4c Piece 3's eventual A/B.

Phase 6 is complete.

---

## Phase 7 — Multi-Provider LLM Routing + Math Rule Engine Expansion ✅

Three intertwined workstreams: **E** (provider pluralism — first non-Groq
primaries), **F** (math rule engine: registry + structured findings + topic
expansion + computations-per-paper ramp), **G** (14 critical-path fixes from
REVIEW_REPORT.md refresh #1–#12). Ship-gate was the room producing its first
`PUBLISHED rev:*:ok` paper after E1 unblocked the professor stage. See
[phase-7-multi-provider-and-math-expansion.md](archive/phase-7-multi-provider-and-math-expansion.md).

```
E — Multi-provider LLM routing
  7000  scope doc + multi-provider config scaffold                  7dce079
  E1.0  provider-pluralise LlmPool interface                        cd2326d
  E1.2  OpenRouterPool                                              5dd1338
  E1.3  NvidiaPool                                                  573337a
  E1.4  CerebrasPool                                                a5d978a
  E1.5  multi-provider routing chain in LlmRouter                   62c6844
  E1.6  Cerebras-as-fast-audit role routing                         ada0845
  E1    enable OpenRouter, NVIDIA, Cerebras provider pools          f10cdc1
  E2    tool-calling.providers opt-in filter (default groq)         7d21dfc
  E3    Ollama fallback for exhausted primary chain                 5e44806
  E4    per-provider ranked-model list in budget heartbeat          b8e9508

F — Math Rule Engine Expansion
  F1    TopicValidatorRegistry + MagnitudeRangeValidator delegate   555ec4a
  F2.1  ResearchResult.equation field + per-topic wiring            48aacad
  F2.2  NarrationResult.findings → Map<Integer, String>             d27f103
  F2.3  structured-findings flag + max-computations clamp           4dc92ee
  F2.4  parseNarrationResult learns the findings-map shape          5a67c24
  F2.5  NARRATION_PRIMARY v3 — structured-findings variants         33015b4
  F2.6  per-block truncation guard                                  1f342c0
  F2.7  validators accept the structured-findings map shape         e145d8e
  F2.8  formatPaperAsMarkdown emits per-block H3 with equation      366f7e0
  F2.9  audit/professor/revision prompts see per-block layout       ee171c7
  F2.10 flip structured-findings.enabled ON                         d76ac9c
  F3.1  variable topic count + topics-v2 flag plumbing              a011f36
  F3.2  5 new PHYSICS topics (Wien, Schrödinger, Kepler, …)         926dca8
  F3.3  5 new MATERIALS topics (Hall-Petch, Bragg, Carnot, …)       42f38b8
  F3.4  5 new MATHEMATICS topics (Mertens, Riemann, …)              21e824c
  F3.5  3 new INTERDISCIPLINARY topics + physicalMechanism          fc728bf
  F3.6  flip topics-v2.enabled ON                                   2b7a697
  FX.1  new BIOLOGY domain (5 topics)                               bb1a454
  FX.2  new CHEMISTRY domain (5 topics)                             df2b704
  FX.3  new ARCHITECTURE_TERRESTRIAL domain (generic over planet)   abb93d6
  FX.4  new ARCHITECTURE_LIQUID domain (generic over any liquid)    460a443
  FX.5  new ARCHITECTURE_SPACE domain (5 topics)                    585ef4b
  FX.6  expand BotGenome.DOMAINS — bots spawn into all 9 domains    5858a04
  F5.1  ToolInvocation record + GroqPool local trace capture        d15f1a2
  F5.2  ToolCallResponse return type across pools                   48ac7d5
  F5.3  NarrationResult carries tool-call trace                     66114f1
  F5.4  persist tool-call trace + Methods appendix in markdown      ba93be0
  F5.5  NumericalConsistencyValidator covers tool-call trace        e121d89
  F5+F7 scope doc: tool-call provenance + multi-reviewer panel      db2553f
  F7.1  panel review prompt template + PanelReview JSON schema      bf21911
  F7.2  multi-reviewer panel fan-out + deterministic aggregation    08d6f87
  F7.3+F7.4  cherry-picked-evidence gate + degraded panel fallback  ab62a77

G — Critical-path fixes (REVIEW_REPORT refresh #1–#12)
  G1    partial-revision merge for incomplete first-pass            66be2f3
  G2    dedup Jaccard fallback for truncated/extended clones        7b1cd75
  G3    clamp peer-score to [0, 1]                                  221479e
  G4    harness-leak + dummy-citation + formatting fixes            06707a2
  G5    suppress boilerplate conclusion when peer review skipped    78815d2
  G6    topic-specific findings prose validator                     5ae1679
  G7    boundary_answer audit dimension                             3f5e10a
  G8    physical_mechanism audit gap routes to REJECTED             622a85a
  G9    intra-room sibling novelty penalty                          c93d7d8
  G10   periodic scan for orphaned DISPUTED papers                  221532f
  follow-ons:
        provider-agnostic rate-limit header parsing                 9e3db91
        sequential reviewer panel; auditable model fallthrough      92a82c7
```

Phase 7 closed the multi-provider gap that had stalled the room on
`prof:error→fp` against Groq alone, lifted the topic surface from 20 to
roughly 45 (across 9 domains), and ramped computations-per-paper from 2 to
N with structured per-block findings replacing the single prose blob.
Flags introduced: `app.ai.providers.enabled`, `app.research.topics-v2.enabled`,
`app.research.structured-findings.enabled`, `app.research.review-panel.enabled`.

---

## Phase 8 — Unrestricted LLM Math Formulas ✅

Layered math sandbox: numeric DSL → user-defined functions → Symja symbolic
engine → GPU eval RPC. The LLM can now express effectively arbitrary math
formulas; the system executes them safely via a static checker per layer.
See [phase-8-unrestricted-math-formulas.md](archive/phase-8-unrestricted-math-formulas.md).

```
P1    reflective Math.* dispatch + SI constants + number-theory helpers   b1bac81
P2    user-defined functions in the sandbox DSL                           deaee89
P3.1  pull in Symja (matheclipse-core 3.2.0) + correct LGPL note          dbfe0da
P3    SymbolicEngine + SymbolicStaticChecker + run_symbolic tool          6d95724
P4.1  add EvalExpr RPC to terrain_gpu.proto (Java side)                   ad56b4e
P4    run_gpu_eval tool — Java client + static checker + tests            5b7463c
P5    Pattern C bridges — integrate / differentiate / simplify            ad50f81
```

Each layer carries its own static checker (`SandboxStaticChecker`,
`SymbolicStaticChecker`, `GpuEvalStaticChecker`) so unsafe expressions fail
before execution. `sandbox_reject` telemetry from Phase 5 C3 tells operators
which unrestricted-math gaps the narrator actually reaches for.

---

## Phase 9 — Unified Model Registry + Two-Tier Architecture ✅

Replace per-provider pool walking with a model-centric registry that
aggregates every 70B+ model across providers, plus a parallel "fast tier"
sub-70B registry with the same selection-test contract. Ollama config
migrates into the unified `app.ai.*` namespace. See
[phase-9-unified-model-registry.md](archive/phase-9-unified-model-registry.md).

```
9000  scope doc: unified model registry + two-tier architecture     b07e7dc
U1    LlmPool surface expansion for unified model registry          ba13db2
U2    PrimaryModelRegistry standalone — aggregated 70B+ models      3d29746
U3a   wire ReviewPanelService onto PrimaryModelRegistry             f9324ca
U3b   wire LlmRouter onto PrimaryModelRegistry                      12558e6
U4    OllamaPool config migration + multi-model support             1ca6993
U5a   ModelSelectionTester utility — chat + JSON + tool probes      ef05ede
U5b   integrate ModelSelectionTester into every pool                a7a3b24
U6    FastModelRegistry + wire LlmRouter.callFast through it        c73d65e
U7    fast-tier expansion to primary pools' sub-70B chat models     9450c03
follow-ons:
      retry-after-aware + 429-tolerant selection tests              56ddb10
      HTTP read timeouts + open tool-calling to all 4 providers     33c0edb
      bump HTTP read timeout 180s → 7 min                           719d0e4
      HTTP read timeout from a single config key                    7dfd630
      load-balanced registries + GroqPool TPM tracking + 10s backoff 0822291
      widen narration_template_version 32→64                        09ab0b3
      periodic re-test of dropped selection-test models             f0c03f1
      widen remaining varchar(32) columns 32→64                     a475d14
G7    PromptSanitizer patterns for research/a3289005 leaks          abc25a3
G8    surface SimulationGate reason on every token-budget row       78fc512
G9    pre-debit budget gate for GroqPool to stop TPM overshoot      1c9ac24
```

After Phase 9 the router selects by model, not by pool; selection tests run
periodically so dropped models can return to the eligible set; and the
unified `app.ai.*` namespace replaces Spring AI's Ollama auto-config keys.

---

## Phase 10 — REVIEW_REPORT refresh #3 fixes ✅

No dedicated scoping doc — landed as a single corpus-driven batch
addressing failure clusters from REVIEW_REPORT.md refresh #3.

```
10    REVIEW_REPORT refresh #3 fixes                                5d87a57
       follow-on: PromptSanitizer bare-phrasing patterns            f3f340d
```

Sandbox subset relaxations (bounded arrays, `while`, `===`, `recordSample`,
`signedDevPercent`, grammar template in rejections) plus ten harness
patches targeting: param-prose mismatch leak (P-344/347/351/355/356/362),
cross-domain non-sequitur audit exemplars (P-345/352), threshold-drift
audit dimension (P-347/355/357), all-tools-failed fabrication gate
(P-352/353), single-reviewer publish suppression (P-360), Solve `=` → `==`
normalize (P-364), signed deviation builtin (P-363), Z-factor clamp to
validity envelope (P-359), composite-failure fibre-fracture criterion
(P-343/346/360).

---

## Phase 11 — Citations-as-Schema ✅

Make numeric fabrication structurally unrepresentable. Every numeric value
in `findings` and `conclusion` carries an inline `[call_N]` marker that
resolves to a real tool-call result; a parallel `numeric_claims` array is
validated against the persisted tool-call trace before publish. See
[phase-11-citations-as-schema.md](archive/phase-11-citations-as-schema.md).

```
C1–C4  citations-as-schema warn-only rollout                        2a14a79
C5a+C5b  downgrade + suppress modes for citations-as-schema         403c6d3
```

Rolled out warn-only first so operators could observe one REVIEW_REPORT
refresh before enforcement; C5a/C5b add the `downgrade` and `suppress`
mode flips. Flag: `app.research.citations-as-schema.mode`
(`warn` | `downgrade` | `suppress`).

---

## Phase 12 — Fabrication-Gate Hardening ✅

Close the gap between *checks that fire* and *publications that get
stopped*. The May 2026 REVIEW_REPORT refresh #3 showed 88.2% of a 34-paper
batch still Reject-or-Major even with every Phase 5/7/10/11 validator
running, because the validators reported but didn't always route. See
[phase-12-fabrication-gate-hardening.md](archive/phase-12-fabrication-gate-hardening.md).

```
D1+D5+D7  chain-of-thought strip + truncation patterns +
          additive call-path                                        363b966
D2        numerical-consistency mode flag + five gap-closing checks 05b2140
D3+D4     widen zero-successes gate + degraded-panel demotion       197c68d
D6        TextbookBoundRegistry + ClosingProseValidator             9b88c66
```

D1 strips LLM chain-of-thought from prose; D2 flips
`numerical-consistency` from warn to enforcing and adds five gap-closing
checks; D3 widens the zero-successes-in-tool-trace gate to suppress;
D4 demotes panel-degraded papers; D5 adds new truncation patterns;
D6 introduces the per-topic `TextbookBoundRegistry` + `ClosingProseValidator`
so reported numbers must agree with a textbook range; D7 makes the call-path
additive so multiple validators can stamp their verdicts.

---

## Phase 13 — Recommendation Fixes 🟡 (code shipped; verification pending)

Six concrete fixes from REVIEW_REPORT refresh #1 (post-rotation) plus a
panel-recommendation pattern audit across the same corpus. Each item
closes a *named* failure mode visible in the corpus or in the running
process. See [phase-13-recommendation-fixes.md](archive/phase-13-recommendation-fixes.md).

```
0     scoping: five recommendation fixes from REVIEW_REPORT refresh #1  11ca4c4
      E6 scoping: uncertainty-disclosure requirement (panel audit)      6727eac
E1+E3+E5  SimulationGate coolest-spend + Q-factor labels +
          X placeholder                                                 f83aa12
E4    truncation guard for header-only computation blocks               83af22b
E2    LenientJsonParser for rev:parse_failed recovery                   b7326ab
E6    UncertaintyDisclosureValidator (panel-recommendation audit)       dab1724
```

E1 flips `SimulationGate` aggregation from hottest-provider to coolest-
provider so the room doesn't pause when only one of four budget-tracked
providers exhausts. E2's lenient JSON pre-parser recovers
`rev:parse_failed→fp` cases (39% of published papers in the refresh) before
falling through to the first-pass narration. E3 corrects swapped Q-factor
parentheticals in the RLC prose template. E4 extends the truncation guard
to flag header-only computation blocks. E5 adds a sanitizer pattern for the
literal `X` placeholder in `± X%`. E6 introduces the
`UncertaintyDisclosureValidator` and a new `uncertainty` slot in
`NARRATION_PRIMARY`, addressing the ~38 panel comments asking for error
bounds across ~18 of the 23 published papers.

All six pieces are committed. **Phase 13 is not yet closed** — the
phase doc's "Phase 13 complete when" gates
([phase-13-recommendation-fixes.md](archive/phase-13-recommendation-fixes.md))
are measured against post-rollout REVIEW_REPORT refreshes. Status
against [research/a3289005/REVIEW_REPORT.md](../research/a3289005/REVIEW_REPORT.md)
**refresh #2** (2026-05-16 08:25 EDT, n=20 papers P-476..P-512):

- ❌ **Acceptance rate ≥ 40% over a 25-paper window** — refresh #2 shows
      **0% Acceptable + 0% Minor + 25% Major + 75% Reject** (cumulative
      n=20). The headline number got *worse*, not better, but for reasons
      mostly orthogonal to Phase 13's targets: refresh #2 surfaces two
      **new** failure modes the phase didn't address — the "Five-
      Computation stub block" fabrication pattern (6 of 20 papers) and
      zero-tool-call publishing (P-496, P-505). Both are recommendation
      items #1 and #2 of the refresh, and queue work beyond Phase 13.
- ✅ **`SimulationGate` stays open under partial exhaustion (E1)** —
      explicitly verified: "Phase 13 E1's coolest-spend aggregation is
      letting the simulation reopen each time a non-Groq provider has
      headroom; without E1 the room would have been silent for the full
      hour" (gate paused/reopened 3× in the 55-minute window vs. one
      85-minute silent stretch in refresh #1).
- ✅ **`LenientJsonParser` recovers rev-stage parse failures (E2)** —
      `rev:parse_recovered:from=lenient` stamp visible on P-482 (refresh #1)
      and P-512 (refresh #2). Both would have been `rev:parse_failed→fp`
      pre-E2.
- ⚠️ **`rev:parse_failed→fp` rate ≤ 20%** — P-503 still ends at
      `rev:parse_failed→fp` (refresh #2's no-Methods-section paper), so
      E2 doesn't catch every case. Exact rate over the 20-paper window
      not tabulated in refresh #2; a follow-on count is needed.
- ◻ **Zero RLC Q-factor paren swap (E3)** — unverified: no RLC papers in
      refresh #2 batch (materials slots were Young-Laplace, Arrhenius,
      Carnot).
- ✅ **Zero header-only `### Computation N — …` blocks (E4)** — no
      header-only signature flagged in refresh #2; the residual
      Computation-block failure has shifted to *prose-bodied* fabricated
      blocks (recommendation #1), which is a separate gate from E4.
- ✅ **Zero `± X%` literals (E5)** — no occurrences flagged in refresh #2.
- ◻ **Panel "uncertainty / error bounds / sensitivity" recommendation
      count drops ≥ 40% (E6)** — unverified: E6 still in warn-only
      (`app.research.uncertainty-disclosure.enabled` defaults off), and
      refresh #2's top recommendations are dominated by the new
      Five-Computation / zero-tool-call failures, not by uncertainty asks.
      A refresh after flipping the E6 flag is needed.

**Net read:** four of seven gates verified ✅, one explicitly ❌ (the
headline acceptance rate, derailed by new orthogonal failure modes),
two unverified ◻ (E3 needs an RLC paper, E6 needs the flag flipped),
one partial ⚠️ (E2 working but not on every case). Phase 13 stays open
until: (a) the next refresh after E6's `uncertainty-disclosure.enabled`
flip provides the panel-recommendation drop measurement, and (b) the
`rev:parse_failed→fp` rate is tabulated and confirmed ≤ 20%. The
headline acceptance-rate miss is *not* a Phase 13 regression — it's a
queue item for the next phase (recommendations #1 + #2 of refresh #2).

---

## Phase 14 — Recommendation Fixes #2 🟡 (code shipped; rollout in progress)

Six concrete fixes (F1–F6) drawn from REVIEW_REPORT refresh #2
(2026-05-16, papers P-476..P-512). Phase 14 closes the publishing-layer
failure modes that derailed refresh #2's acceptance rate to 0% —
distinct from Phase 13's targets. See
[phase-14-recommendation-fixes-refresh2.md](archive/phase-14-recommendation-fixes-refresh2.md).

```
F3+F4+F5+F6  four publishing-layer validators (flag-gated)        74ca517
F1           computation-block-count guard (stamp + gate)         f02c343
F2           zero-successes leak diagnosis + exception-path patch ef4a3fb
config       flip Tier 1 validators ON (Phase 14 F1a/F3/F4/F5)    041671a
```

All six pieces are shipped. Tier 1 rollout flipped on 2026-05-17 (commit
`041671a`): F5 (missing-methods-gate), F3 (single-point-sweep), F4
(direction-of-finding), and F1a (block-count-stamp — observability-only
rendering). F1b (block-count-guard — status-changing gate) holds at
default-off pending one refresh of F1a stamp-mode observation; tracked
in Phase 15 below. F2 ships without a flag (a defensive patch on the
`prof:error→fp` exception path, gated narrowly on
`profSuccesses == 0 && firstPassBlocks > 1`). F6 also ships unflagged —
it modifies the audit-4b template directly and reuses the existing
`audit-physical-mechanism-rejects` routing.

F4 scope re-decision: skipped the planned `claimPolarity` field on
`NarrationResult` + NARRATION_PRIMARY prompt edit in favour of
pure-prose parsing in `DirectionOfFindingValidator`. Same corpus impact
(P-491, P-496, P-511) without churn across NarrationResult constructor
overloads.

F2 scope re-decision: the REVIEW_REPORT's recommended one-line fix is
incomplete because `ResearchNarrationService.java:492` already tests
`metrics.totalSuccesses() == 0`. The actual leak is at three fallback
exits (`prof:error→fp`, `json_rev:parse_failed→fp`,
`rev:ollama:parse_failed→fp`) that bypass the gate. F2a adds
diagnostic WARN logging at all three; F2b patches the exception path
defensively.

**Excluded from Phase 14**: F7 — sandbox grammar relaxation (wishlist
P2 Layer 1, ~3 days). 60 `MathToolSuite: sandbox code rejected by 4b
review` WARN entries per 55-minute window are the *upstream cause* of
the Five-Computation fabrications, but F7 deserves its own scoping
pass with a Layer 1 / Layer 2 / Layer 3 plan. F1's block-count guard
contains the symptom without depending on upstream relief. F7 remains
on [phase-wishlist-human-level.md](phase-wishlist-human-level.md) pending a future phase doc.

Flags introduced (default off in code; live state tracked in
[phase-flags.md](phase-flags.md)):

| Flag | State |
|------|-------|
| `app.research.missing-methods-gate.enabled` (F5) | **ON** (2026-05-17) |
| `app.research.single-point-sweep.enabled` (F3) | **ON** (2026-05-17) |
| `app.research.direction-of-finding.enabled` (F4) | **ON** (2026-05-17) |
| `app.research.block-count-stamp.enabled` (F1a) | **ON** (2026-05-17) |
| `app.research.block-count-guard.enabled` (F1b) | off — see Phase 15 |

**Phase 14 complete when** the next REVIEW_REPORT refresh (the first
with the Tier 1 flags ON) shows acceptance rate ≥ 30% over a 25-paper
window (refresh #2: 0%) and all six named per-piece gates are clean.
Full acceptance criteria in the phase doc.

---

## Phase 15 — Flag Rollout Queue 🟡 (rolling tracker; no code on its own)

Tracks the validator flags that are coded + shipped across Phases 7 /
11 / 12 / 14 but held at default-off (or `mode: warn`) pending corpus
observation. Phase 15 ships no Java — its commits are
`application.yml` configuration changes that arrive ad-hoc as
REVIEW_REPORT refresh evidence accumulates. See
[phase-flags.md](phase-flags.md) for
per-flag trigger conditions, risk, and acceptance.

Current queue (4 of 5 remaining; F1b flipped 2026-05-17 in `918ac90`):

| Tier | Flag | Phase | Trigger |
|------|------|-------|---------|
| 3 | `app.research.panel-degradation-demote.enabled` | 12 D4 | F7.2 panel agreement scores stable + ≥30 papers |
| 3 | `app.research.audit-cherry-picked-rejects.enabled` | 7 F7.3 | ≥10 PUBLISHED + SUPPORTED with ≥3 cherry firings (tightened 2026-05-17 from ≥30 after refresh-#3 showed 0/5 FPs) |
| 3 | `app.research.numerical-consistency.mode` (warn) | 12 D2 | Operator review of warn-mode suppress markers **AND** three known validator FPs fixed (see phase-15 doc) |
| 3 | `app.research.citations-as-schema.mode` (warn) | 11 C5b | Two refreshes with stable uncited / unmatched / out_of_tolerance distributions |

Recently flipped — Tier 2 (commit `918ac90`, 2026-05-17): F1b
`block-count-guard`. Closed by refresh #3 evidence — 4 of 11 new papers
(P-526, P-533, P-540, P-548) carried `(no data — call_N missing)`
markers, tracking refresh #2's ~30% baseline within the ±2 trigger
window.

**Phase 15 closes when** the queue is empty — every flag either
flipped on or removed as a deliberate keep-off.

---

## Phase 16 — Recommendation Fixes #3 ✅ (code shipped; flag rollout in queue)

Eight concrete items drawn from REVIEW_REPORT refreshes #3 + #4
(2026-05-17, papers P-a328-514..555 in `research/a3289005/`). Phase 16
addresses three distinct failure classes the earlier phases did not
cover:

1. **Validator coverage extensions** (4 items) — strengthen what
   `NumericalConsistencyChecker` catches at `warn` mode.
2. **Narrator-stage scrubs + corpus awareness** (3 items) — close
   defects in the narration pipeline surfaced when the math substrate
   started reliably producing correct arithmetic in refresh #3.
3. **Scheduler-stage topic-clone backoff** (1 item) — stop the
   formulation pipeline from cycling on already-rejected topics.

Scope lives across two existing docs:

- [phase-flags.md](phase-flags.md)
  "Known validator FPs blocking D2 escalation" + "Coverage gaps to
  ship alongside the FP fixes" — items 1-7 (the validator side).
- `phase-wishlist.md` (since renamed to
  [phase-wishlist-human-level.md](phase-wishlist-human-level.md) and trimmed)
  P5, P6, P7 — the narrator and scheduler items. After the 2026-05-18
  rename, the Migration table at the bottom of that doc records where
  each former item now lives.

| Group | Item | Source | Type |
|-------|------|--------|------|
| Validator FPs | Negative-number tokeniser miss (P-555) | Phase 15 doc #1 | Fix existing FP |
| Validator FPs | Off-by-one `[call_0]` labelling drift (P-516) | Phase 15 doc #2 | Fix existing FP |
| Validator FPs | Unsourced framing-percentage severity (P-526) | Phase 15 doc #3 | Fix existing FP |
| Validator coverage | `[call_N]` resolves but the call failed (P-519, 532, 539, 543) | Phase 15 doc #4 | New check |
| Validator coverage | One-sided CI tightening (P-528, 521) | Phase 15 doc #5 | New check |
| Validator coverage | Hypothesis-vs-executed-range scope mismatch (P-547) | Phase 15 doc #6 | New check |
| Validator coverage | Inline `Computation N` stub in Findings prose (P-553) | Phase 15 doc #7 | New check |
| Narrator scrub | Suppress canned conclusion when status ≠ PUBLISHED (P-553) | Wishlist P5a | Narrator gate |
| Narrator scrub | Strip number-words harness leaks (P-541) | Wishlist P5b | Narrator filter |
| Corpus-aware narration | Cross-citation verdict propagation (P-539 → P-534) | Wishlist P6a | Prompt-context fetch |
| Corpus-aware narration | Prior-result injection at formulation time (P-541 vs P-516) | Wishlist P6b | Prompt-context fetch |
| Scheduler | Topic-clone backoff (divisor-sum-average 4× rejected) | Wishlist P7 | Selection-weight decay |

**Implementation order** (recommended, not prescribed):

1. ✅ **The seven validator items (Phase 15 doc #1-7)** — shipped
   2026-05-17 in commit `f6b89b9`. All seven extensions live in
   `NumericalConsistencyValidator.java`; 16 new tests anchor each
   change to a refresh-#3 paper ID. The new reasons emit in `warn`
   mode; D2's warn → downgrade escalation still gates on a post-fix
   corpus re-run.
2. ✅ **P5a + P5b narrator scrubs** — shipped 2026-05-17 in commit
   `8b5c46a`. P5a (`suppressBoilerplateOnNeedsRevision`) adds the
   gate-closed / needs-revision disclaimer paths the Phase 7 G5 strip
   missed; this also quietly back-fills the comma-continuation form
   of `BOILERPLATE_INDEPENDENTLY_REPRODUCED` that Phase 7 G5 had been
   missing for SUPPORTED/REFUTED claim statuses. P5b adds number-words
   leak patterns (`fifty`, `seven point one`) sibling to the existing
   digit-form scrubs. 13 new tests (58 → 71) anchor each rule to
   either P-a328-553 (P5a) or P-a328-541 (P5b).
3. ✅ **P6a — cross-citation verdict propagation** — shipped 2026-05-17
   in commit `af4cc97`. Narration prompt's priorPapers context block
   now carries a verdict tag (`[REJECTED — do not propagate]` /
   `[DISPUTED — under replication review]`) per prior paper.
   Helpers (`isRejectedStatus`, `verdictTagFor`) live on
   `PromptSanitizer`; the suppress-rejected flag lives at
   `app.research.corpus-citations.suppress-rejected: false` (default
   off → disclaimer mode). 6 new tests (71 → 77). P6b sequences next
   once the prior-fetch query is in place.
4. ✅ **P6b — prior-result injection at formulation time** — shipped
   2026-05-17 in commit `72e99e1`. When the flag
   `app.research.prior-result-injection.enabled=true` AND exactly one
   PUBLISHED prior with `peerScore >= min-score` (default 0.5) exists
   for the (domain, topic) pair, the formulation prompt carries a
   STRONG-CONFIDENCE PRIOR hint asking the bot to replicate, refute-
   with-citation, or shift sub-question. Pure-static helpers
   (`buildPriorResultHint`, `firstSentence`) on `FormulationService`;
   12 new tests (19 → 31). Default OFF for rollout discipline — flip on
   after one corpus refresh under P6a's verdict-tag behaviour.
5. ✅ **P7 — topic-clone scheduler backoff** — shipped 2026-05-17 in
   commit `4d9cad3`. New `TopicCloneBackoff` utility (pure-static
   helpers); wired into `ResearchCycleService.runCycle` after the
   initial `selectTopicIndex` call. Flag
   `app.research.topic-clone-backoff.enabled` (default off) plus
   `window-size` (default 5) and `max-retries` (default 3). 13 new
   tests. Targets the divisor-sum-average cluster (P-534/539/553 all
   REJECTED for the same convergence-fabrication pattern).

**Phase 16 complete when** all four new flags
(`app.research.corpus-citations.suppress-rejected`,
`app.research.prior-result-injection.enabled`,
`app.research.topic-clone-backoff.enabled`, and D2's warn → downgrade
escalation) reach their flip triggers across REVIEW_REPORT refreshes.
The four flags join Phase 15's rolling rollout queue once a corpus
refresh exercises P6a's verdict-tag behaviour (P6a is unconditional in
disclaimer mode; P6b, P7 default off pending one refresh of corpus
observation).

**Phase 16 complete when** acceptance rate on a 25-paper window with
all eight items shipped reaches ≥25% (refresh #3 cumulative: 5.3%;
projected upper bound after items 1+2+3 alone: ~25%, per refresh #3
§operational-logs). Per-item acceptance gates live in their respective
source docs.

**See [phase-16-recommendation-fixes-refresh3.md](archive/phase-16-recommendation-fixes-refresh3.md)**
for the as-shipped per-piece breakdown and the flag-flip
specifications (What flipping does / Trigger / Risk / Acceptance) for
the three default-OFF Phase 16 flags (P6a suppression mode, P6b
prior-injection, P7 topic-clone-backoff). The Phase 16 doc captures
those operator-facing rollout details that `phase-flags.md` doesn't
carry (the flag queue is a thin reference; feature/flag details live
in the source phase doc).

---

## How to read this directory

```
STATUS.md                                    ← you are here (shipped vs deferred)
readme.md                                    ← original 10-iteration design doc
phase-18-pending-followups.md                ← consolidated open items (P8c, P8d, Phase 4c Piece 3 decision); pointer to phase-flags.md for flag flips
phase-flags.md                               ← rolling tracker for default-off / warn-mode flags awaiting rollout triggers (renamed from phase-15-flag-rollout-queue.md after Phase 16 added three more flags)
phase-wishlist-human-level.md                ← human-level paper-building quality levers (P1–P4); reference, not queued; renamed from phase-wishlist.md on 2026-05-18 with P5–P8 trimmed out (migrated per its bottom table)
archive/                                     ← shipped / historical phase docs (Phase 0 through Phase 17); moved here 2026-05-18 to keep the active set lean
  phase-0-prerequisites.md                     ← JPA migration + service carves
  phase-1-foundation.md                        ← identity + graph + programs
  phase-2-active-experimentation.md            ← surprise + planner
  phase-2-5-roomservice-refactor.md            ← deferred RoomService refactor (OBSOLETE — superseded by later architecture)
  phase-3-world-integration.md                 ← world + institutions + markets
  phase-4-formal-knowledge-and-meta-science.md ← formal findings + meta-science
  phase-4c-prompt-template-evolution.md        ← scoping doc for prompt-template evolution (Piece 3 decision pending — see phase-18 §18.3)
  phase-4c-piece-1-memo.md                     ← design memo locking Piece 1 decisions
  phase-5-quality-pipeline.md                  ← quality-pipeline fixes from the peer-review corpus
  phase-6-novelty-levers.md                    ← scoping doc for literature search + Discussion section
  phase-6-piece-1-memo.md                      ← D1.1 memo locking literature-corpus decisions
  phase-7-multi-provider-and-math-expansion.md ← multi-provider routing + topic + structured-findings expansion
  phase-8-unrestricted-math-formulas.md        ← layered math sandbox (numeric / symbolic / GPU)
  phase-9-unified-model-registry.md            ← unified primary + fast model registries
  phase-11-citations-as-schema.md              ← inline [call_N] markers + numeric_claims schema
  phase-12-fabrication-gate-hardening.md       ← close the gap between checks-fire and publications-stopped
  phase-13-recommendation-fixes.md             ← six named fixes from REVIEW_REPORT refresh #1 + panel audit
  phase-14-recommendation-fixes-refresh2.md    ← six named fixes from REVIEW_REPORT refresh #2 (scoping)
  phase-16-recommendation-fixes-refresh3.md    ← eight fixes from REVIEW_REPORT refresh #3/#4 (as shipped) — D2-prep, P5a/b, P6a/b, P7
  phase-17-analyses.md                         ← Phase 17 analyses + post-hang refresh #1/#2 follow-on decisions
```

Phase 10 has no dedicated scoping doc — it shipped as the
`5d87a57` corpus-driven batch addressing REVIEW_REPORT refresh #3 failure
clusters.

Each phase doc retains its full design content. `STATUS.md` is the live
tracker of what's been shipped; the per-phase docs are the shape of
intent against which to evaluate the shipped state.
