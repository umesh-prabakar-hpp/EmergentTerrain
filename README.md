# EmergentTerrain

Spring Boot POC that turns a user profile into a signed QR payload and a deterministic, editable 2D game world.

## Stack
- Java 21, Spring Boot 3.x, Spring Security, Spring Data JPA (Hibernate)
- PostgreSQL (dev)
- Thymeleaf + Material Web (M3) components + minimal JS
- ZXing (QR generation)

## Features
- User registration + login (session-based)
- Profile creation (age, sex, location, yes/no preferences)
- Deterministic hidden physical properties derived from profile
- QR payload signing (HMAC), gzip + base64url encoding
- Negotiation-first multiplayer terrain (no solo mode)
- Neighbor-based consensus voting for 2x2 terrain changes
- Bots with persistent profiles and visible positions
- High-speed highway corridors (precomputed)
- Bot map changes applied in-memory with periodic room state snapshot flushes

## Run
Java 21 required. On this machine, the installed JDK 21 path is:
`C:\dev\lang\java\amazon-corretto-21.0.6.7.1-windows-x64-jdk\jdk21.0.6_7`

Example (current shell):
```powershell
$env:JAVA_HOME = "C:\dev\lang\java\amazon-corretto-21.0.6.7.1-windows-x64-jdk\jdk21.0.6_7"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd spring-boot:run
```
Open `http://localhost:8080/`

## Tests (summary)
- `RoomRapidChangePersistenceIntegrationTest` (profile: `test`) runs a short rapid-change loop, forces maintenance/flushes, and asserts persisted snapshots and change counts. Expected time: ~80s.
- `RoomStateSamplingIntegrationTest` (profile: `test`) seeds a room, waits `sample.waitMs` (default 3 minutes), applies proposals, flushes state, and writes sampling output to `sample-output/`. Expected time: ~3m 15s with the default wait.
- `scripts/minimap-route-ui-test.js` (Playwright) clicks the minimap, waits for route choices, and verifies the minimap canvas updates. Expected time: ~50s.
- `scripts/minimap-travel-smoothness-test.js` (Playwright) auto-navigates via minimap, samples render/logic positions, and reports movement update cadence and move request latency. Expected time: ~20s.

## Benchmarks (JMH)
Benchmarks live under `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\src\test\java\com\emergentterrain\bench`.

```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd -DskipTests jmh:jmh
```

GPU benchmarks require the Rust GPU engine to be running. Use `GPU_ENGINE_HOST` / `GPU_ENGINE_PORT` or `-Dgpu.host` / `-Dgpu.port` if needed.

## GPU Engine Integration
- World generation can call the GPU engine when enabled.
- Bot simulation can call the GPU engine when enabled (optional tile edits).
- Defaults are off.

## Reset Postgres Password (Windows)
If you need to reset the local `postgres` superuser password to `NewStrongPasswordHere` (PostgreSQL 18), use these manual steps:

1. Open **Services** (`services.msc`) as Administrator and stop the service `postgresql-x64-18`.
2. Open `C:\Program Files\PostgreSQL\18\data\pg_hba.conf` in a text editor running as Administrator.
3. Find the localhost entries and change the auth method to `trust`:
   - `host    all    all    127.0.0.1/32    scram-sha-256` → `trust`
   - `host    all    all    ::1/128         scram-sha-256` → `trust`
4. Save the file, then start the `postgresql-x64-18` service.
5. Open PowerShell and run:
   - `& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -h localhost -p 5432 -d postgres -c "ALTER USER postgres WITH PASSWORD 'NewStrongPasswordHere';"`
6. Re-open `pg_hba.conf` and revert both localhost lines back to `scram-sha-256`.
7. Restart the `postgresql-x64-18` service.

## UI Assets
Material Web, Roboto, and Material Symbols are loaded from CDN in the templates.

## Configuration
See `src/main/resources/application.yml` for app settings:
- `app.qr.hmacSecret`
- `app.world.auto`, `app.world.maxTiles`, `app.world.aspectRatio`, `app.world.highwayRatio`
- `app.world.flushIntervalMs`
- `app.consensus.neighborRadius`, `app.consensus.voteWindowSeconds`, `app.consensus.minNeighbors`
- `app.consensus.noParticipationLockSeconds`
- `app.density.areaSize`, `app.density.maxOccupants`
- `app.bots.populationFraction`, `app.bots.maxPerRoom`
- `app.gpu.enabled`, `app.gpu.world.enabled`, `app.gpu.bots.enabled`, `app.gpu.bots.applyEdits`

## Routes (summary)
- `GET /register`, `POST /register`
- `GET /login`, `POST /login`
- `GET /profile/new`, `POST /profile`
- `GET /qr?token=...`
- `GET /game?token=...`
- `POST /game/state` (world seed + regions)
- `POST /room/create`
- `POST /room/join`
- `POST /room/last` (join last room or create new)
- `POST /room/{roomId}/state`
- `POST /room/{roomId}/move`
- `POST /room/{roomId}/propose`
- `POST /room/{roomId}/vote`
- `POST /room/{roomId}/apply` (returns error: auto-apply on majority)
- `POST /room/{roomId}/memory`
- `POST /room/{roomId}/bot`
- `POST /room/{roomId}/resonance`

## Controls
- Move: `WASD` or arrow keys
- Highways: moving while on a highway tile jumps 4 tiles per keypress
- Propose change: click a tile to mark a 2x2 area, choose a tool, then press `Apply Changes`
- Vote: select a proposal then use `Approve` or `Reject`
- Bots: open the Companions panel and select a bot to get a prompt

## Consensus Rules
- Only neighbors within `neighborRadius` can vote
- At least `minNeighbors` must be nearby to submit a proposal
- Non-participating neighbors count as NO after the vote window
- Two missed votes locks a player from play for `noParticipationLockSeconds`

## Notes
- This is a POC focused on end-to-end flow, not production hardening.
- The QR payload is signed (tamper detection) but not encrypted.
- Bot-generated changes are applied in memory and flushed as room state snapshots; bot proposals are not persisted.
- Docs policy: when behavior, setup, or performance workflows change, update both `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\README.md` and `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\AGENTS.md`.
