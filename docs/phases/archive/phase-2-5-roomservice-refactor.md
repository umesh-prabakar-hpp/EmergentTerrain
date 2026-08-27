# Phase 2.5 — RoomService Refactor (Pre-Phase-3)

**Scope:** Carve `RoomService` into composable services before Phase 3 begins.

**Status:** Placeholder. The exact extraction plan must be deferred until Phase 3 design crystallizes — see *Why this is a placeholder* below.

**Prerequisite:** Phase 2 shipped and stable. Phase 3 design crystallized enough to know which `RoomService` subsystems Iterations 6 and 8 will integrate with.

**Goal:** Split the 4,746-line `RoomService` into single-responsibility services so Phase 3's world-integration and institution work has clean integration seams. Without this, Phase 3 will either bloat `RoomService` further or accumulate awkward sidecar services that fight the existing structure.

**Estimated effort:** ~1–2 weeks AI-assisted (rough estimate; concrete after Phase 3 design review). Compression rate ~1.5x — game-loop refactors are stateful and the test surface is thinner than for the research pipeline.

---

# Why This Is a Placeholder

`RoomService` is the largest service in the codebase. It handles:

- Bot movement (position updates, collision detection)
- Bot archetype dispatch (STEWARD, BUILDER, FLOW_KEEPER, PRESERVATIONIST, PRAGMATIST, CONTRARIAN, NEIGHBOR_MIRROR — 7 archetypes with different rules)
- Emergent highway formation (traffic pressure → highway promotion → decay)
- Room state transitions and snapshot/restore
- Bot lifecycle at the room level (spawning, retirement)
- Interaction with `WorldService`, `GovernmentSimulationService`, and `UniversityService`

**It clearly needs refactoring.** But splitting it now — before Phase 3 work has surfaced concrete integration needs — is guesswork. The right seams depend on what Phase 3 actually has to plug into. Specifically:

- Iteration 6 (world interventions) needs to route bot proposals through `RoomService` into `GovernmentSimulationService`. Until that flow is designed, we don't know whether intervention routing belongs in `RoomService` or a new service.
- Iteration 8 (institutions) needs `University` instances scoped per room. Whether `RoomService` should own university lifecycle, or delegate to a new `RoomInstitutionService`, depends on how institutions interact with the existing bot archetype dispatch.
- Premature extraction will create the wrong abstractions and force a second refactor in 6–8 weeks.

**Phase 0's discipline of "refactor first, then add features" applies here too — but with one critical difference: Phase 0's seams were obvious from the existing service responsibilities. Phase 2.5's seams are not obvious until Phase 3 design exists.**

---

# Likely Extraction Candidates

These are *probable* targets, not commitments. Confirm each against actual Phase 3 needs before extracting.

```text
RoomService (orchestrator only, target ~500 lines)
 ├── BotMovementService          ← position updates, collision detection
 ├── BotArchetypeBehaviorService ← STEWARD/BUILDER/etc. dispatch + rule evaluation
 ├── HighwayEmergenceService     ← traffic pressure tracking, highway promotion/decay
 ├── RoomStateOrchestrator       ← state transitions, snapshot/restore
 └── BotRoomLifecycleService     ← spawning, retirement at the room level
```

Some of these will collapse into others or be deferred. For example, `BotRoomLifecycleService` may be unnecessary if room-level lifecycle stays small after the other extractions — it might fit naturally into `RoomStateOrchestrator`.

The *one* extraction that is almost certainly right regardless of Phase 3 design: **`BotArchetypeBehaviorService`**. Seven archetypes with different rules in a single switch statement is the clearest seam in the file.

---

# Decision Criteria — When to Trigger This Phase

Begin Phase 2.5 when **all** of the following are true:

1. Phase 2 has been shipped and is stable (no active regression hunts).
2. Phase 3 Iteration 6 design is concrete enough to identify which `RoomService` methods world interventions need to call.
3. Phase 3 Iteration 8 design has identified whether `University` is room-owned or independent.
4. The team has at least 1–2 weeks of capacity to dedicate to the refactor without parallel feature work in `RoomService`.

If any of these is missing, defer. Doing this refactor with stale Phase 3 design is worse than not doing it.

---

# Implementation Strategy

**Atomic-per-extraction, not atomic-per-phase.** Unlike Phase 0 (which is internally coupled), Phase 2.5's extractions are independent. Ship each as its own PR:

1. `BotArchetypeBehaviorService` first — clearest seam, lowest risk.
2. `HighwayEmergenceService` next — self-contained subsystem.
3. `BotMovementService` — touches more of the rest of the code; do it after the easier extractions to validate the pattern.
4. `RoomStateOrchestrator` last — touches snapshot/restore and is the riskiest.

Each PR must pass the existing `RoomService` tests plus any new gap tests added during extraction. **Do not add features during these PRs.** Pure refactor only — same rule as Phase 0.

---

# Open Questions for Phase 3 Design Review

These need answers before Phase 2.5 starts. Do not start Phase 2.5 with them open.

- Will world interventions (Phase 3 Iter 6) route through `GovernmentSimulationService` only, or also through `RoomService`'s bot archetype dispatch (e.g., a `STEWARD` bot's behavior might be triggered by a passed intervention)?
- Will `University` instances be 1-per-room, 1-per-district, or independent? This determines whether `RoomService` owns university lifecycle or just references it.
- Will `Journal` instances be room-scoped or global? If global, they don't belong in `RoomService` at all; if room-scoped, they probably do.
- Does Phase 3 Iter 7 (epistemic markets) need any room-level state? If yes, where does it live?

---

# Phase 2.5 Completion Criteria

Concrete criteria can only be written after Phase 3 design review. Placeholder criteria below — refine when activating this phase.

- [ ] Phase 3 design review completed; extraction targets confirmed
- [ ] `BotArchetypeBehaviorService` extracted (almost certainly part of any final plan)
- [ ] Additional services extracted per refined plan (likely 2–4 more)
- [ ] `RoomService` reduced to a thin orchestrator (~500 lines)
- [ ] Existing `RoomService` tests pass on refactored code
- [ ] No behavioral changes user-visible (pure refactor)
- [ ] Phase 3 integration points documented for Iterations 6, 7, 8
