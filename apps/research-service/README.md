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
