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
| Phases shipped | 7 of 7 (0, 1, 2, 2b, 2.5, 3, 4) + Phase 5 Waves 0–3 |
| Iterations shipped | 10 of 10 (every Iter from the design docs) |
| Commits | 24 + 8 Phase 5 |
| Net new code | ~10,000 + ~900 (Phase 5) lines |
| Tests | 135 → 577 → 633 (+498) |
| Behaviour-changing iterations | 6 + 3 Phase 5 (truncation guard, harness-leak filter, sandbox whitelist), all behind feature flags |

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
  ADAPTIVE_SWEEP. BAYESIAN_OPTIMIZATION shipped as a Phase 2b MVP that
  uses recent-paper peerScore to scale the sweep radius — exploits the
  bot's learned region when the recent track record is strong.

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

### Phase 2.5 — RoomService refactor ✅

Pre-Phase-3 refactor. All five extractions originally enumerated by the
design doc ([phase-2-5-roomservice-refactor.md](phase-2-5-roomservice-refactor.md))
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
app.research.dominant-methodology.reputation-weighted # Phase 4b refinement — bot reputation (clamped [0,1]) weights the methodology count instead of a flat 1
app.research.surprise-broadcast.enabled # Phase 4b — fan out high-surprise events to per-room in-memory buffer (rate-limited)
app.research.exploration-tuning.enabled # Phase 4b — per-room z-threshold + EXPLORATION trigger adjust from replication failure rate
app.research.theory-composition.enabled # Phase 4b — A~f(B) ^ B~g(C) chains emit derived ProgramOpenQuestions
app.research.world-intervention.enabled # Phase 3 Iter 6 write side — bots file GovernmentBill via WorldInterventionService; integration point checks the flag
app.research.truncation-guard.enabled   # Phase 5 A1 — papers whose findings/conclusion end mid-sentence publish as NEEDS_REVISION
app.research.harness-leak-filter.enabled # Phase 5 A4 — strip "starting at position N" / "uses a scale of M" / "quality score of X" leak phrases from prose
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
Phase 2.5 carves), the research package is 17 specialist services + 9
RoomService-derived services in `com.emergentterrain.service`
(BotArchetypeBehaviorService, HighwayEmergenceService,
BotMovementService, MapIndexService, RoomStateOrchestrator,
TileValidatorService, OccupancyService, BotSimulationGpuService,
RoomProposalService) + 14 entities. Refreshed against the working tree
(post-`ab42534`):

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

## Phase 5 — Quality Pipeline ✅ (Waves 0–3 shipped 2026-05-12)

Lifts the 27% acceptance-rate plateau the automated peer review
([REVIEW_REPORT.md](../research/a3289005-archive/REVIEW_REPORT.md))
identified across 91 papers + 13 refreshes. Each piece is anchored to
the specific recommendation # it addresses. See
[phase-5-quality-pipeline.md](phase-5-quality-pipeline.md) for the
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
```

Wave 0 pinned the simulator before behaviour changes landed. Wave 1's
three quick wins each hit a top-frequency failure mode. Wave 2 added
opt-in guards behind two new flags (`app.research.truncation-guard.enabled`,
`app.research.harness-leak-filter.enabled`). Wave 3 surfaced previously
log-only telemetry onto the persisted paper row.

Waves 4 (B1–B5 validators), 5 (citation-aware scoring + dispute
reconciliation), and 6 (D1/D2 scoping docs) remain. See the phase doc
§"Suggested execution order" and §"Pre-conditions for the validators".

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
phase-5-quality-pipeline.md                  ← quality-pipeline fixes from the peer-review corpus
```

Each phase doc retains its full design content. `STATUS.md` is the live
tracker of what's been shipped; the per-phase docs are the shape of
intent against which to evaluate the shipped state.
