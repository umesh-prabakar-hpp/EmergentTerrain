# Phase 42 — Monorepo and game/research service boundary

**Status: IN PROGRESS — monorepo import and compatibility boundary implemented.**

## Delivered in this change

- The GPU engine and Julia math sidecar are imported beneath this repository with their source
  histories preserved in the main Git graph.
- Top-level GPU, setup, performance, and related-service scripts now default to the monorepo paths.
- `contracts/research-api/openapi.yaml` defines the stable v1 game-to-research read surface.
- `libs/research-contract/` contains the corresponding Spring-free Java paper-summary DTO, used by
  both services instead of duplicate application-local response classes.
- The legacy browser paths and the new v1 paths both use `ResearchQueryClient`; they no longer
  expose the research JPA entity as the cross-service response type.
- `RoomService` invokes research ticks through `ResearchTickGateway`, rather than directly calling
  `UniversityService`.
- `app.research-api.mode=local` is the default compatibility implementation. `remote` enables the
  HTTP read client once an independent research service is deployed.
- `apps/game-service/` and `apps/research-service/` are now Maven modules. The research module
  runs the university cycle independently from persisted bot/government state and excludes the
  game UI, world, GPU, and WebSocket runtime components.
- The research worker persists its per-room progress in the research-owned
  `research_service_checkpoints` table. It resumes above its last successful checkpoint and the
  current government tick, so restarting it does not reset research tick progress.
- Game service exposes the read-only `GET /api/v1/game/rooms/{roomId}/research-context` snapshot.
  The research worker can select it with `RESEARCH_GAME_CONTEXT_MODE=remote`; `database` remains
  the compatibility default until bot-state extraction is complete. The snapshot includes the
  game-selected active bots and lifecycle payloads, so remote mode already uses game-owned bot
  selection even while it reloads mutable bot entities from the transition database.
- The research worker durably imports those immutable bot inputs into its own
  `research_bot_replicas` table before running the legacy university engine. A same-or-newer game
  snapshot is required to overwrite or deactivate a replica, preserving a monotonic migration
  handoff while the engine still needs mutable transition rows.
- The worker now seeds separate mutable `research_bot_states` records from those replicas. Source
  snapshots may update the bot type, activity, and source tick, but never overwrite the lifecycle
  JSON after initial creation; that field is reserved for the extracted research engine.
- With `RESEARCH_LIFECYCLE_SHADOW_ENABLED=true`, the worker records a pre-cycle parity comparison
  between research-owned lifecycle state and the game API's bot snapshot in
  `research_lifecycle_shadow_records`. This is strictly read-only and is the gate before moving
  lifecycle transitions to research ownership.
- `RESEARCH_LIFECYCLE_SHADOW_ONLY=true` makes that audit an execution-safe migration mode: it
  imports the game snapshot, records the parity observation, advances the checkpoint, and does
  not call the legacy university cycle or load mutable game bot rows.

## Live standalone validation — 2026-08-04

- PostgreSQL and the standalone research service were verified `UP` on port `8082` using Java 21.
  The service starts without the game UI, WebSocket stack, GPU engine, or subscription proxies.
- The research service is now packaged as an executable Spring Boot jar. Its supported launcher
  passes an absolute `GAME_CONFIG_PATH`, an explicit research port, and
  `spring.jpa.hibernate.ddl-auto=none`. This avoids losing the shared research/LLM configuration
  when Maven changes directory and prevents a research process from competing with the game for
  transition-database schema DDL locks.
- Autonomous startup also required scanning the research repository package; it is now included.
- The game and research health endpoints were simultaneously `UP`. The Julia/Python and GPU
  sidecars were intentionally down, so number-theory work logged safe skips rather than starting
  those services.
- The remote game-context request remains protected by the running game's login boundary in this
  host configuration. It now uses `RESEARCH_CONTEXT_API_KEY`, which the game accepts only for the
  read-only context request and rejects when absent or wrong. The research module's `schema.sql`
  owns its replica, state, checkpoint, and parity-record tables, allowing a shadow-only audit
  without Hibernate schema races.
- Final remote audit: both services were simultaneously healthy; an unauthenticated context request
  was redirected to login and the authenticated research request succeeded. The dedicated research
  scheduler persisted a checkpoint at game tick `124701` and five 50-bot parity observations.
  The first two were exact matches; the following three were `0` matched / `50` mismatched as the
  legacy game continued advancing lifecycle state after research's one-time seed. This is the
  expected evidence that lifecycle transition extraction is still incomplete, not a cutover pass.
  The audit therefore confirms API, credential, durable snapshot, schema-upgrade, checkpoint, and
  observability paths, while explicitly blocking lifecycle ownership cutover until research computes
  and validates the same transitions.
- The next isolation increment removes the final bot-table read from shadow-only mode: parity now
  compares research state exclusively with `ResearchBotSnapshot` payloads returned by the versioned
  game API. Mutable `RoomBot` loading remains only in the non-shadow compatibility execution path.
- The game context now also reports exact-tick legacy lifecycle execution metadata: whether the
  lifecycle gate advanced and the effective adult-to-elder threshold. Its in-memory registry returns
  metadata only when it belongs to the same game tick as the snapshot, preventing a remote projector
  from applying a stale transition. This remains observation-only until the projected state matches
  the complete legacy transition set.
- Projector hardening requires an exact-tick event, a threshold whenever that event says it
  advanced, and at most one projection per game tick. Missing/stale/incomplete metadata is recorded
  as inconclusive rather than causing a guessed or duplicate transition.
- A second, disabled-by-default projector now persists a separate
  `research_lifecycle_projections` stream. It seeds from a post-tick snapshot, then projects only
  the exact legacy ageing and reputation-decay slice when fresh execution metadata says the legacy
  lifecycle advanced. It compares that prediction with the following game snapshot without
  overwriting research bot state or game data. Competency, publication, merge, and dating mutations
  remain deliberately out of scope, so this is an evidence-gathering increment, not a cutover.
- Initial projector runtime audit (2026-08-05): the remote worker created 50 independent projection
  baselines while both services were healthy. The room's returned game tick did not advance during
  the observation window, so no successor transition was projected and no parity conclusion was
  drawn. The audit also exposed and corrected a record-labeling bug: shadow records now store the
  actual game-context tick, never the worker's private checkpoint counter. A future projector audit
  must exercise at least one advancing game snapshot before it can claim age/reputation parity.
- Forced successor audit (2026-08-05): with bounded maintenance enabled, a 1-second legacy
  lifecycle interval, and a zero draft backlog (to avoid paper/LLM work), both services remained
  healthy and the game snapshot advanced. At the final observed tick, the separate projection stream
  compared 51 active bots: 23 matched and 28 mismatched. This proves the age/reputation projection
  is operating against real successor snapshots, while correctly demonstrating that the unprojected
  competency, merge, dating, publication, and other lifecycle mutations still prevent cutover. Both
  temporary processes were stopped immediately after collecting the evidence.

The old sibling checkouts are intentionally left untouched so they can be retired only after this
monorepo checkout has been validated. The remaining persistence extraction is described in the
migration plan below; remote tick submission is deliberately not enabled until its complete
game-state snapshot/action contract exists.

## Goal

Consolidate the Git repositories that make up EmergentTerrain into one monorepo rooted at
`EmergentTerrain`, then separate gameplay from research into independently deployable services.
Gameplay must continue to initiate and observe research through a versioned API rather than a
direct Java dependency.

## Target repository layout

```text
EmergentTerrain/
  apps/
    game-service/          # Spring Boot game API, UI, rooms, bots, government, world state
    research-service/      # research cycles, papers, LLM, literature, evidence and artifacts
  libs/
    research-contract/     # Spring-free Java DTOs shared by both service clients
  engines/
    gpu-engine/            # Rust GPU engine (current EmergentTerrainGPUEngine repository)
  services/
    julia-math-sidecar/    # Julia/Python sidecar (current julia-math-sidecar repository)
  contracts/
    research-api/          # versioned OpenAPI or protobuf definitions and generated clients
  tools/
    local-ai-gateway/
    legacy-ai-gateway/
  docs/
  scripts/
```

The existing Codex and Claude proxies are already tracked inside the current repository.  They
move only as ordinary tracked files; they are not separate repositories to import.

## Service ownership and interaction

```text
Browser → game service → research API → research service
                 ↑                         │
                 └── result/status/event ──┘
```

- The **game service** owns rooms, world and bot state, government simulation, game UI, and
  gameplay decisions.
- The **research service** owns research jobs, paper lifecycle, LLM calls, literature retrieval,
  evidence, traces, and research artifacts.
- The game service starts research actions and queries their status and publications through the
  `research-api` contract.
- When research has a gameplay consequence, the research service sends a versioned callback/event
  to the game service.  It must not write game-owned tables directly.
- Each service owns its database schema or database.  Cross-service reads and writes occur through
  APIs/events, never through shared ORM entities or direct SQL.

## Migration plan

1. **Establish a baseline.** Record current build, test, GPU smoke, and sidecar/proxy startup
   results before moving files.
2. **Create the monorepo without changing behavior.** Import `EmergentTerrainGPUEngine` under
   `engines/gpu-engine/` and `julia-math-sidecar` under `services/julia-math-sidecar/`, preserving
   each repository's history with `git filter-repo` or `git subtree`.  Remove imported nested
   `.git` directories only after confirming the imports; do not use Git submodules for this target.
3. **Repair repository-relative tooling.** Update scripts, CI, README, and AGENTS.md paths that
   currently assume sibling repositories.  Retain the existing ports unless a separately reviewed
   compatibility change says otherwise.
4. **Define the boundary.** Add a versioned research API for action submission, job state,
   cancellation where supported, paper/status retrieval, and game-facing research outcomes.
5. **Introduce an in-process adapter.** Replace game calls to research implementation classes with
   a `ResearchClient` interface, initially backed by the existing in-process implementation.  This
   preserves behavior while tests establish the contract.
6. **Extract the research service.** Move research controllers, workers, persistence, and LLM /
   literature configuration into `apps/research-service/`; implement the API client in the game
   service; migrate research-owned data.
7. **Convert cross-effects to events/callbacks.** Move any research-to-game mutation behind the
   versioned callback/event contract and remove remaining shared entities, repositories, and direct
   database access.
8. **Validate and cut over.** Run both services, GPU and sidecar smoke checks, API contract tests,
   recovery/restart tests, and a normal paper-review workflow before removing the in-process
   adapter.

## Constraints

- Preserve the commit history of every imported repository.
- Do not leave nested Git repositories inside the final monorepo.
- Keep generated outputs, `target/`, `sample-output/`, local databases, secrets, and dependency
  directories out of Git imports.
- Do not change the existing GPU, Julia, Python-sidecar, or proxy runtime contracts as an incidental
  consequence of directory moves.
- Do not split the current research Java package mechanically: it presently shares game domain,
  persistence, and service dependencies and must first be isolated behind the API contract.

## Success criteria

- A fresh clone of the monorepo contains the game, GPU engine, Julia sidecar, and both proxies,
  with their intended histories preserved.
- The game service can enqueue a research action and obtain its status and final result solely via
  the versioned research API.
- A research result can affect gameplay through a tested callback/event, with no research-service
  write to game-owned storage.
- Game-only development and research-only development build and test independently.
- Existing full-system checks continue to prove the Spring game service can use the live GPU engine
  and configured sidecars.
