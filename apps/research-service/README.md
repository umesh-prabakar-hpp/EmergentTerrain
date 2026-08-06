# EmergentTerrain research service

This is the independently deployable research runtime. It serves the v1 paper and aggregate-stat
read API and runs the university research cycle from PostgreSQL on port `8082` by default. It does
not start the game UI, WebSocket stack, world generation, GPU adapter, or `RoomService`.

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn -pl apps/research-service -am install -DskipTests
mvn -f apps/research-service/pom.xml spring-boot:run
```

Then start the game process with:

```bash
RESEARCH_API_BASE_URL=http://127.0.0.1:8082 \
  mvn -f apps/game-service/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments="--app.research-api.mode=remote --app.university.enabled=false"
```

Set the game process to `app.research-api.mode=remote` when it should display this service's
results. In that mode it does not create `ResearchTickGateway`, so only this process runs research
ticks. The service reads persisted room bot and government state during this transitional stage;
the next extraction replaces that shared-database read with a versioned game-state snapshot API.

Each completed room tick is checkpointed in the research-owned
`research_service_checkpoints` table. After a restart the worker resumes above both its last
checkpoint and the latest persisted government tick, avoiding a reset to an in-memory counter.

For the next migration cut, make government context game-owned over HTTP rather than reading it
from the shared database:

```bash
RESEARCH_GAME_CONTEXT_MODE=remote \
  RESEARCH_GAME_API_BASE_URL=http://127.0.0.1:8081 \
  RESEARCH_CONTEXT_API_KEY=shared-service-credential \
  ./scripts/start-research-service.sh
```

The default `database` mode remains the standalone compatibility path when no game service is
running. Remote mode reads only `GET /api/v1/game/rooms/{roomId}/research-context`; it does not
start, control, or write to the game service. Configure the same nonempty
`RESEARCH_CONTEXT_API_KEY` in the game process: the client sends it as `X-Research-Service-Key`,
and game rejects absent or invalid credentials.

## Generic work intake (Phase 42 boundary)

The research-owned `POST /api/v1/research/work` endpoint accepts a versioned, generic
`ResearchWorkRequest` when `X-Research-Work-Key` equals the nonempty `RESEARCH_WORK_API_KEY`.
It has no game request types: callers provide opaque workspace/subject identifiers, a source
revision, a work type, and an immutable context snapshot. Accepted requests are stored
idempotently in `research_work_items`; submitting the same request ID again returns the existing
receipt.

The research-owned scheduler claims queued `RESEARCH_CYCLE` work, records attempts and a durable
generic `ResearchResult`, and retries delivery of that result to the game projection endpoint when
`RESEARCH_RESULT_API_KEY` is configured. Its first extracted executable slice consumes a frozen,
versioned standard-draft selection input and persists the selected subject/domain/topic together
with a selector version and input fingerprint. Parameterized and follow-up branch inputs are mapped
to versioned, game-free study/parameter payloads and return `DRAFT_BRANCH_PREPARED` after canonical
planning validation only—no computation, narration, review, or publication runs. It intentionally
contains no game types and does not yet replace the legacy paper-generation engine; conjecture and
history-dependent branches remain out of scope.

To exercise game delivery, start both processes with the same `RESEARCH_WORK_API_KEY` and set the
game's `app.research-api.mode=outbox`. The game writes its own transactional outbox record before
relay, so temporary research-service downtime does not discard work. This intake is the first
decoupling seam. The legacy university executor still consumes transition-period game-derived
state, so queued generic work is not yet a paper-engine replacement; do not enable `outbox` as a
production replacement for the current local/remote research-cycle modes yet.

Every execution snapshot is first imported into the research-owned `research_bot_replicas` table.
That table records the bot selection and lifecycle payload at its game tick, and marks a bot
inactive only when a same-or-newer game snapshot omits it. The university engine still reloads
mutable transition-period `RoomBot` rows after that import; the replica is the durable handoff for
the next engine extraction step.

The worker also seeds `research_bot_states` from each replica. New bots begin with the source
lifecycle payload, but later game snapshots update only bot type/activity/source tick and never
overwrite `research_bot_states.lifecycle_json`. That lifecycle field is reserved for research
ownership when `UniversityService` is extracted.

Before moving lifecycle transitions, enable the read-only parity audit with
`RESEARCH_LIFECYCLE_SHADOW_ENABLED=true`. It compares persisted lifecycle fields in
`research_bot_states` against the game API snapshot, records an append-only
`research_lifecycle_shadow_records` observation, and never mutates either lifecycle authority.
When `RESEARCH_LIFECYCLE_SHADOW_ONLY=true`, this path performs no `RoomBot` repository lookup;
the versioned game context is its only bot input.

The context includes `lifecycleExecution` only when the game has an observation for the exact
snapshot tick. It identifies whether legacy lifecycle transitions ran and supplies the effective
adult-to-elder threshold. Research treats this strictly as evidence for the forthcoming predicted-
state comparison; it never writes a lifecycle update back to the game.

Set `RESEARCH_LIFECYCLE_SHADOW_PROJECTOR_ENABLED=true` only for a bounded shadow audit. It stores
its predictions in `research_lifecycle_projections`, separate from `research_bot_states`, and
currently projects only ageing and reputation decay. Missing or stale `lifecycleExecution` metadata
makes that observation inconclusive rather than applying a guessed transition. The same game tick
is projected at most once; an `advanced` event missing its effective threshold is also inconclusive.
The game context now includes a bounded, contiguous execution window with a process epoch and
sequence range. The projector replays all unseen events in order; a window gap or changed epoch
is inconclusive and requires a fresh projection baseline.
