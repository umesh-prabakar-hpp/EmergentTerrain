# Phase 42 — Monorepo and game/research service boundary

**Status: IN PROGRESS — monorepo import and compatibility boundary implemented.**

## Delivered in this change

- The GPU engine and Julia math sidecar are imported beneath this repository with their source
  histories preserved in the main Git graph.
- Top-level GPU, setup, performance, and related-service scripts now default to the monorepo paths.
- `contracts/research-api/openapi.yaml` defines the stable v1 read, durable-work, and display-only
  result surfaces, including their separate API-key headers.
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
- The first final-boundary delivery slice is implemented behind the opt-in
  `app.research-api.mode=outbox`: a game-owned mapper converts a research cycle into an immutable
  generic `ResearchWorkRequest`, commits it to `research_work_outbox`, and relays it to the
  research-owned authenticated `POST /api/v1/research/work` endpoint. Research stores the request
  idempotently in `research_work_items`; a duplicate request ID with a divergent work payload is
  rejected as HTTP `409 Conflict`. The Spring-free work contract contains opaque workspace
  and subject identifiers, source revision, work type, and context snapshot—not game classes.
  This establishes durable at-least-once delivery while the legacy executor is still being
  extracted. Queued work is deliberately limited to the baseline planning slice below and must
  not invoke the legacy university executor.
- Queued `RESEARCH_CYCLE` work now has a research-owned scheduler and a first game-agnostic
  executable slice: it records attempt/result state, persists a deterministic frozen
  `ResearchDraftPlan`, and emits a durable generic `ResearchResult` with outcome
  `DRAFT_CANDIDATE_PLANNED`. The game freezes generic primary/fallback candidates, selection
  score, domain, topic options, selector version, and seed; the research-owned pure selector
  persists its chosen subject/domain/topic plus an input fingerprint. Standard-catalog weighted
  and topics-v2 uniform selection have mapper and fixture parity tests. Parameterized and
  follow-up branch inputs are now mapped to versioned `ResearchStudySpecification` and
  `ResearchParameterOverride` payloads; research canonically prepares them and emits
  `DRAFT_BRANCH_PREPARED`, never a standard-catalog or computed result. Conjecture and
  history-dependent branches remain excluded from live routing. A game-owned conjecture can now be
  mapped to a frozen `ResearchConjectureSpecification` and prepared in research, but queue ordering
  and consumption semantics have not yet been captured, so the game does not enqueue it. Topic
  history can now be frozen with `ResearchTopicHistorySnapshot` and replayed under its frozen
  `ResearchTopicReselectionPolicy`; the outbox now carries the game-owned bounded history and live
  policy into planning work, while whole-path parity remains required before any paper routing.
  Result delivery retries to a
  game-owned, display-only projection authenticated separately by `RESEARCH_RESULT_API_KEY`; the
  projection cannot apply gameplay effects. This is execution-lifecycle proof, not paper-engine
  equivalence.
- Frozen conjecture queue observations are now bounded and retained in `draftSelection` alongside
  the terminal selected conjecture (or probability-gated miss). The research-owned planner
  independently replays that immutable promoted-first/probability-gated observation and rejects a
  mismatched selection before persisting a draft plan. This is audit hardening only: it neither
  reads nor consumes a live game queue, and conjecture routing remains disabled pending complete
  queue-consumption parity. The display-only planning result reports only compact replay provenance
  (version, priority-tier counts, and random-draw cadence) for later parity audits; the planner
  accepts only the supported legacy queue-observation version and rejects unknown versions.
- The game can now capture the promoted head and a bounded ordered ordinary candidate list through
  the legacy queue's read-only surfaces, then map that observation to the generic frozen snapshot.
  This is intentionally not wired to the outbox: the observation never reserves or marks a source,
  and no live conjecture routing has been enabled.
- A separate, explicitly-invoked game-owned consumption seam now proves the transactional half of
  a future cutover without enabling it: it accepts only an already-observed persistent conjecture
  whose id matches the frozen `CONJECTURE` reservation, persists/revalidates the reservation and
  outbox row, atomically claims that reservation once, then stamps that exact queue source. A
  source-stamping failure rolls back new handoff state, and an exact replay does not stamp twice.
  It is not called by the tick gateway and never observes, ranks, or selects a live queue.
- Research now persists a separate generic `ResearchExecutionPreparation` after each frozen plan.
  It fingerprints its canonical input for exact replay, contains no result or authorization to
  compute, and is owned entirely by the research service. Standard-catalog drafts report
  `AWAITING_COMPUTATION_INPUTS` until their legacy parameter/formulation parity is frozen; prepared
  parameterized, follow-up, and conjecture branches report `PREPARED_NO_COMPUTATION` and still do
  not invoke the legacy executor.
- The game now includes `ResearchParameterSamplingProfile` with each generic draft candidate.
  `FrozenResearchParameterSampler` replays the bounded fallback parameter draw after the frozen
  author/topic and recorded queue-miss cadence, matching the legacy no-formulation path. Its
  `ResearchParameterSample` is persisted only as canonical readiness input; it never replaces a
  formulation result and does not permit computation.
- `ResearchFormulationSpecification` is the separately frozen, generic formulation payload for a
  standard draft. It binds the selected subject/domain/topic, bounded parameters, research question,
  and pre-registration text before computation. The game mapper can attach an already-produced
  instance but is not wired to invoke formulation; research rejects a target mismatch and records a
  matching payload as `FORMULATION_FROZEN_AWAITING_COMPUTATION`. That state is still preparation
  only and must not invoke computation, narration, publication, or gameplay effects.
- `ResearchComputationSpecification` is the separately frozen generic deterministic-scan input.
  It can be attached only after a matching standard formulation, and research validates its
  selected subject/domain/topic and exact formulation parameters before persistence. A matching
  input becomes `COMPUTATION_FROZEN_AWAITING_EXECUTION`; that status is still input-only and
  unwired, so it cannot call the computation engine, narration, publication, or gameplay paths.

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
- The latest execution contract replaces the single-event dependency with a bounded contiguous
  event window carrying a per-process epoch and monotonic sequence numbers. Research stores the
  consumed sequence in each independent projection and replays every unseen event in order. A
  journal gap or epoch change is explicitly inconclusive, preventing missed poll intervals or game
  restarts from silently creating lifecycle drift.
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

## Final service boundary: game-agnostic research

The final research service contains no game-domain types, terminology, repositories, or runtime
dependencies. `Room`, `RoomBot`, `GovernmentState`, `gameTick`, gameplay controllers, and
game-owned tables are transitional concerns and must not survive the extraction.

The game service owns an anti-corruption adapter that translates game events and state into the
generic, versioned research contract. Research receives immutable work, not a live game-state
read or permission to mutate game state.

```text
game event/state
  -> GameResearchRequestMapper
  -> ResearchWorkRequested(workspaceId, subject, trigger, contextSnapshot, sourceRevision)
  -> durable work transport
  -> research service

ResearchResult
  -> game result mapper
  -> display-only game projection
```

The contract vocabulary is deliberately research-specific:

- `ResearchRequest`, `ResearchJob`, `ResearchSubject`, `ResearchContextSnapshot`, and
  `ResearchResult` replace game-specific request and state classes.
- `workspaceId`, `subjectId`, and an opaque monotonic `sourceRevision` replace room, bot, and game
  tick identifiers in the research domain.
- A request contains only the frozen evidence and constraints needed for the investigation. The
  research service never polls the game for mutable state while executing that request.
- The generic processor emits only `ResearchResult`. A gameplay-effect recommendation is out of
  scope until a separately versioned extension has an explicit game-owned validation policy and
  contract test; the game remains the only service that could apply a validated effect.

This permits research to run while the game is stopped: it can complete previously delivered work,
serve its paper/result API, and recover its own queued work. New game-originated work naturally
waits until the game next emits it.

## Service ownership and interaction

```text
Browser → game service → research API → research service
                 ↑                         │
                 └── result/status/event ──┘
```

- The **game service** owns rooms, world and bot state, government simulation, game UI, and
  gameplay decisions.
- The **research service** owns research jobs, its durable queue/checkpoints, paper lifecycle, LLM
  calls, literature retrieval, evidence, traces, and research artifacts. It has no dependency on
  game entities or schemas.
- The game service maps gameplay events into versioned `ResearchWorkRequested` commands, writes
  them through a transactional outbox, and publishes them to durable transport. The research
  service consumes each work item idempotently.
- The game queries research job status and publications through the `research-api` contract. It may
  maintain a compact local display projection from versioned research-result events.
- Research currently publishes only display-safe versioned results. A future gameplay-effect
  extension requires an explicit game-owned validation policy and contract test before research
  may emit a recommendation; research must never write game-owned tables, invoke privileged game
  actions, or directly apply gameplay effects.
- Hermetic direct parity tests now run the legacy standard catalog cycle alongside the frozen
  planner for both the qualified and cold-start fallback pools; with the same seed they must
  produce the same author, domain, and catalog topic. A direct follow-up parity case additionally
  proves that its parameter override retains that catalog selection. Parameterized-study parity
  pins the same elected author, terminal branch, and directive consumption while deliberately
  retaining only a deferred study plan, not a computation-output claim. The ordinary and promoted
  conjecture branch parity tests remain separate because those branches deliberately avoid a
  catalog-topic selection. Direct cycle parity also now covers both rejection-only and yield-aware
  topic-clone backoff, including their frozen history and extra random-draw cadence.
- A deterministic draft-plan ID is now a durable binding: an exact replay returns the stored
  frozen plan (including its original planning timestamp), while an altered frozen input under the
  same ID fails closed rather than silently replanning or rebinding it.
- Each service owns its database schema or database.  Cross-service reads and writes occur through
  APIs/events, never through shared ORM entities or direct SQL.

## Migration plan

| Item | Status | Evidence / next gate |
|---|---|---|
| 1–3. Monorepo import and repository tooling | **COMPLETE** | Imported services and monorepo-relative tooling are the active layout. |
| 4–6. Generic contract and durable work delivery | **COMPLETE (opt-in delivery)** | `ResearchWorkRequest`, transactional game outbox, authenticated research intake with exact-payload duplicate protection, idempotent receipts, retrying delivery, published v1 work/result schemas, and endpoint contract tests are implemented. The game stays in `local` mode unless an operator selects `outbox`. |
| Generic work execution and display-only result event | **COMPLETE (baseline slice)** | The research-owned scheduler atomically claims immutable-context baselines before execution, so only one research worker may run a queued request; it publishes `ResearchResult` to an idempotent game projection. |
| 7a. Frozen subject eligibility, topic selection, and generic draft record | **IN PROGRESS (standard-catalog and branch-preparation parity)** | The game freezes generic primary/fallback candidates and weighted or uniform topic options; research persists a versioned selector result and canonical input fingerprint, so harmless JSON object-field order changes cannot create a different frozen identity. Generic subject and candidate IDs are unique, the elected frozen author must be included in the request’s generic subject set, and the research planner accepts only the explicitly supported frozen selector, topic-history, and reselection-policy versions. Golden fixtures plus game-mapper parity cover this standard path. Parameterized studies, follow-up parameter overrides, and conjecture specifications are versioned, canonically prepared inputs with mapper/executor parity; only their explicitly supported legacy versions are accepted, and study/conjecture payload identities must match their frozen `study/…`/`conjecture/…` topics before preparation, but they make no computational claim. `FrozenResearchCyclePlanner` now elects the frozen author once, resolves terminal branch precedence, and runs catalog selection only when required. The research-owned planner now applies that same branch rule: parameterized and conjecture work persist deterministic `study/…` or `conjecture/…` plan topics without a catalog roll, while a follow-up retains its catalog topic. Its JSON-boundary tests also pin that terminal branches ignore an otherwise stale queue-miss draw, while a queue miss before a follow-up consumes that one draw before catalog selection. A read-only conjecture queue snapshot now rejects cross-domain, wrong-priority, duplicate, or database-oversized candidates before it reproduces the elected-author/selection handoff. Its durable handoff seam persists a selected observation with a source reservation and a miss as ordinary frozen planning work; neither path accesses or mutates the live queue. PostgreSQL tests cover the full selected/missed replay matrix: exact retries retain one matching outcome, either conflicting later outcome preserves the first outbox payload while rolling back any newly attempted reservation, and concurrent exact/conflicting snapshot replays settle on one coherent durable outcome. Legacy-cycle cadence fixtures pin author election before terminal branches: dynamic and promoted-conjecture branches avoid catalog-topic rolls, while ordinary conjecture selection consumes only its paper-share roll. Direct three-author parity tests now run the real ordinary and promoted-conjecture branches and confirm the frozen planner elects the same author; neither frozen path selects a catalog topic or mutates the queue, the promoted path skips the ordinary probability roll, and an ordinary queue miss replays that one draw before producing the identical catalog selection. They also pin that either selected conjecture is marked investigated exactly once before its draft allocation. The legacy cycle and frozen mapper now share one Spring-free selection-seed derivation, so their author-election, queue-probability, and catalog-topic random cadence cannot drift through duplicated arithmetic. The game now has a request-keyed source-reservation row that is inserted before its outbox row in the same transaction; its request ID must match the work request, and retries must preserve both source identity and the exact serialized generic work payload, but it is not wired to selection or queue mutation yet. PostgreSQL-backed persistence tests prove that conflicting source or payload replays preserve the committed pair, an outbox-payload conflict rolls back a newly inserted source reservation, simultaneous identical retries settle as one durable pair, and simultaneous conflicting replays commit one pair while rejecting the loser. Another fixture confirms that a follow-up is a parameter override after catalog topic selection, not a catalog bypass. Topic history plus its policy replay generic clone/backoff selection with matching random draws and mapper-to-planner JSON-boundary coverage; the game outbox freezes its bounded history and active policy into planning work, including a real gateway-to-request fixture. Direct legacy-cycle fixtures now force and reproduce both rejection-only and yield-aware topic backoff resamples; capture the remaining whole queue-consumption parity before routing either branch live. |
| 7b. Computation, narration, review, and publication | **IN PROGRESS (controlled direct-sidecar comparison)** | Standard work can durably bind a matching generic formulation and deterministic scan controls as `COMPUTATION_FROZEN_AWAITING_EXECUTION`. Alongside the legacy-shadow audit, the default-off direct audit calls the sidecar scan contract without `MathPhysicsEngineDelegate` for the first narrow frozen target and stores a separate raw research-owned observation. A third default-off parity audit persists a `MATCHED` or `MISMATCHED` comparison of their shared numeric envelope for the same frozen fingerprint, retaining both values when mismatched. It does not compose findings, route a new result, invoke the university workflow, narrate, publish, or change game state. Only a controlled matching evidence run may precede research-owned composition. |
| 8. Authorized gameplay consequences | **IN PROGRESS (negative validation policy)** | The game now admits only the two planning outcomes for display projection and rejects nested effect recommendations, gameplay actions, mutations, and commands before persistence. It still has no positive effect contract or authority; any such contract requires separately versioned game-owned validation and tests. |
| 9. Remove game dependency and cut over | **IN PROGRESS (result-delivery recovery and reconciliation)** | Result delivery now durably distinguishes a game `400` policy rejection from retryable authentication, transport, and server failures; rejected records stay auditable but are excluded from redelivery. A bounded research-owned delivery lease atomically elects one sender and permits stale-lease recovery after a crash; game result-ID idempotency remains the final at-least-once safeguard. The authenticated generic work-status read now exposes lifecycle/delivery metadata for restart reconciliation without leaking request context or result content. Still requires paper-output parity, independent recovery tests, and removal of game-model/database reads. |

## Reviewed completion accounting — 2026-08-13

The earlier 97% estimate overstated completion by treating frozen inputs and a dependent sidecar
shadow audit as if the independent computation, narration/review, and cutover gates had passed.
They have not. The conservative weighted accounting below is the current planning estimate:

| Boundary | Weight | Credited | Reason |
|---|---:|---:|---|
| 1–3. Monorepo and tooling | 15% | 15% | Active monorepo layout and repository-relative tooling are complete. |
| 4–6. Generic contract and durable delivery | 20% | 20% | Versioned contract, outbox, idempotent intake, result delivery, and status reconciliation are present. |
| 7a. Frozen selection and branch preparation | 30% | 18% | Standard/branch parity and queue observations are frozen; whole live queue-consumption parity is still not captured or enabled. |
| 7b. Computation, narration, review, publication | 25% | 18% | The first direct target and its immutable matched/mismatched numeric-envelope comparison are research-owned and exclude the legacy delegate; controlled evidence, research-owned composition, and the paper workflow remain. |
| 8. Game-effect boundary | 5% | 4% | The negative display-only policy is enforced; no separately versioned, game-owned positive-effect policy exists. |
| 9. Cutover and dependency removal | 5% | 3% | Delivery leases, rejection audit, and status reconciliation exist; two-process recovery evidence and game dependency removal remain. |
| **Total** | **100%** | **78%** | This percentage counts demonstrated ownership/parity, not scaffolding alone. |

### Remaining gates

1. **Controlled parity evidence and research-owned composition (7b).** The first direct,
   research-owned sidecar runner is limited to `MATHEMATICS` / `Prime Constellation Search`; its
   parity audit records matching or mismatching shared numeric-envelope values for an identical
   frozen fingerprint. Run the controlled evidence audit, including failure semantics, then add
   research-owned composition only after it is matched. Keep all comparison output display-only.
2. **Narration, review, and publication parity (7b).** Extract stages one at a time after the
   preceding computation comparison passes. Preserve deterministic result classification,
   pre-registration ordering, trace grounding, and publication-label gates; do not emit a paper
   or reader-facing claim until that whole preceding path has parity evidence.
3. **Whole queue-consumption parity (7a).** Capture the remaining live queue-consumption cadence,
   including retries and restart behavior, before wiring a selected conjecture or a queue mark to
   the normal tick path.
4. **Effect decision (8).** Retain the current negative policy unless a product requirement calls
   for a gameplay consequence. If one is required, introduce a separately versioned effect
   contract, a game-owned validator, and negative/positive contract tests; research must never
   apply it directly.
5. **Two-process cutover proof (9).** Run controlled game/research restart and recovery tests with
   PostgreSQL and the sidecar available, use work-status reconciliation to verify every request,
   then remove remaining game-model/repository reads and the transition-period `game-service`
   artifact dependency only after the independent parity gates pass.

1. **Establish a baseline.** Record current build, test, GPU smoke, and sidecar/proxy startup
   results before moving files.
2. **Create the monorepo without changing behavior.** Import `EmergentTerrainGPUEngine` under
   `engines/gpu-engine/` and `julia-math-sidecar` under `services/julia-math-sidecar/`, preserving
   each repository's history with `git filter-repo` or `git subtree`.  Remove imported nested
   `.git` directories only after confirming the imports; do not use Git submodules for this target.
3. **Repair repository-relative tooling.** Update scripts, CI, README, and AGENTS.md paths that
   currently assume sibling repositories.  Retain the existing ports unless a separately reviewed
   compatibility change says otherwise.
4. **Define the boundary.** Define and publish the game-agnostic `ResearchWorkRequest` and
   `ResearchResult` contracts, including request IDs, idempotency, source revisions, immutable
   context snapshots, job state, cancellation where supported, and paper/status retrieval. Add
   API contract tests for every published work/result endpoint. Keep game-to-contract mapping
   exclusively in the game service.
5. **Introduce an in-process adapter.** Replace game calls to research implementation classes with
   a `ResearchClient` interface, initially backed by the existing in-process implementation.  This
   preserves behavior while tests establish the contract.
6. **Introduce durable delivery.** Add a game-owned transactional outbox and relay to durable
   transport. Make the research service persist idempotent work receipts and its own queue before
   execution, so either service may be stopped and restarted independently.
7. **Extract the research service.** Move research controllers, workers, persistence, and LLM /
   literature configuration into `apps/research-service/`; migrate research-owned data and replace
   game model inputs with generic research subjects and context snapshots.
8. **Convert cross-effects to events/callbacks.** Keep generic research results display-only. Do
   not add a research-to-game effect recommendation or mutation unless a separately versioned
   contract has an explicit game-owned validation policy and contract test; then remove remaining
   shared entities, repositories, direct database access, remote context polling, and lifecycle
   transition scaffolding.
9. **Validate and cut over.** Run independent game and research restart/recovery tests, durable
   delivery and idempotency tests, API contract tests, GPU and sidecar smoke checks, and a normal
   paper-review workflow before removing the in-process adapter.

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
- The research service builds, tests, starts, and processes already-delivered work with no game
  service, game model class, game schema, or game-state API available.
- A research result can affect gameplay through a tested callback/event, with no research-service
  write to game-owned storage and with game-side validation of any proposed effect.
- Game-only development and research-only development build and test independently.
- Existing full-system checks continue to prove the Spring game service can use the live GPU engine
  and configured sidecars.
