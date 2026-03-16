# EmergentTerrain

Spring Boot POC that turns a user profile into a signed QR payload and a deterministic, editable 2D game world.

## Stack
- Java 21, Spring Boot 3.x, Spring Security, Spring Data JPA (Hibernate)
- PostgreSQL
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
- Highway corridors now persist as a separate overlay and are maintained by bot travel demand instead of being erased by normal terrain changes
- Bot map changes applied in-memory with periodic room state snapshot flushes
- Normal runtime now eases bot movement in the browser and crossfades terrain refreshes so the room feels less snap-based
- Pending votes now render as a live WebGPU influence field with staged pressure, propagation waves, and server-sent proposal / tile-transition deltas
- The browser now uses WebGPU for all world rendering layers: terrain, bots/players, proposal fields, transitions, particles, and the minimap
- Highways now emerge from repeated long-distance bot movement and exploration pressure. Ordinary terrain proposals no longer clear them, and unused corridors decay over time so roads do not take over the map.

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

## Line Endings
- The repo now uses `.gitattributes` to normalize text files to `LF`.
- `*.bat` and `*.cmd` are kept as `CRLF`.
- Recommended local Git settings:
  - `git config --global core.autocrlf false`
  - `git config --global core.eol lf`

## Tests (summary)
- `RoomRapidChangePersistenceIntegrationTest` (profile: `test`) runs a short rapid-change loop, forces maintenance/flushes, and asserts persisted snapshots and change counts. Expected time: ~80s.
- `RoomStateSamplingIntegrationTest` (profile: `test`) seeds a room, waits `sample.waitMs` (default 3 minutes), applies proposals, flushes state, and writes sampling output to `sample-output/`. Expected time: ~3m 15s with the default wait.
- `scripts/run-real-gpu-e2e.ps1` starts the real Rust GPU engine plus the Java app from the native Windows repos, forces GPU proof thresholds for bots and patch rebuilds, verifies non-stub world data over `/game/state`, creates a room, waits for live bot movement over `/room/{roomId}/state`, submits a proposal, and proves the live `rebuild_patch` RPC. Expected time: ~30-60s after startup.
- `scripts/compare-cpu-vs-gpu-performance.ps1` runs release-binary CPU-vs-GPU comparisons with the `perf` profile and prints JSON with per-run timings, averages, and GPU-minus-CPU deltas.
- `scripts/minimap-route-ui-test.js` (Playwright) clicks the minimap, waits for route choices, and verifies the minimap canvas updates. Expected time: ~50s.
- `scripts/minimap-travel-smoothness-test.js` (Playwright) auto-navigates via minimap, samples render/logic positions, and reports movement update cadence and move request latency. Expected time: ~20s.

## Benchmarks (JMH)
Benchmarks live under `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\src\jmh\java\com\emergentterrain\bench`.

```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd -DskipTests -Pbench package
```

Standalone (forked, recommended for real numbers):
```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

java -jar target\\emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar -f 1 -rf json -rff target\\jmh-result.json
```

Profiles:
```powershell
# RPC overhead (small sizes)
java -jar target\\emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar ".*GpuVsCpu.*" -p profile=RPC -f 1

# Compute-heavy (larger sizes)
java -jar target\\emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar ".*GpuVsCpu.*" -p profile=HEAVY -f 1
```

In-process (no fork, quick sanity):
```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd --% -DskipTests -Pbench exec:java -Dexec.args=".*GpuVsCpu.* -f 0 -rf json -rff target\\jmh-result.json"
```

GPU benchmarks require the Rust GPU engine to be running. Use `GPU_ENGINE_HOST` / `GPU_ENGINE_PORT` or `-Dgpu.host` / `-Dgpu.port` if needed.

Focused patch benchmark:
```powershell
java -jar target\\emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar ".*PatchRebuild.*" -f 1
```

## GPU Engine Integration
- World generation can call the GPU engine when enabled.
- Bot simulation can call the GPU engine when enabled (optional tile edits).
- Bot simulation sends structured preference fields and can cache world state in the GPU engine; diffs + step batching are controlled by `app.gpu.bots.*`.
- World generation now uses a short-lived response cache keyed by seed/profile/size so `/game/state` and `/room/create` can reuse the same generated world.
- GPU dispatch is now adaptive by default:
  - `app.gpu.world.minCells` keeps small worlds on CPU.
  - `app.gpu.bots.minBotCount` and `app.gpu.bots.minWorkItems` keep low-work bot ticks on CPU.
  - `app.gpu.patch.minArea` keeps tiny patch rebuilds on CPU; 1x1 proposals stay CPU by default.
- Initial room creation now lazily materializes bots up to `app.bots.initialRoomCreateTarget` and batches persistence work to reduce room setup overhead.
- The default runtime on `radical-changes` now uses more aggressive GPU bot-simulation defaults than `main`: lower GPU thresholds, a longer world cache TTL, and `app.gpu.bots.steps=1` so repeated live maintenance ticks can reuse cached GPU world buffers more effectively.
- The default runtime on `radical-changes` also uses a tighter live-update cadence than `main`: `maintenanceIntervalMs=150`, `broadcastMinIntervalMs=40`, `occupancyRefreshMs=250`, `flushIntervalMs=750`, and `flushMinChanges=80`.
- The browser now uses WebGPU for the full world scene, not just an overlay: `#worldFx` renders terrain, actors, proposal fields, transitions, and particles, while the minimap is also rendered through WebGPU. The base `#world` canvas remains in place as the interaction/input surface.
- Bot movement now carries short-term motion memory in both the Java CPU path and the Rust GPU path: reverse moves are penalized, heading continuity is preferred, bots keep a short plan, and immediate reversals are cooled down for a few ticks.
- Highway state now uses a dedicated persisted road-pressure layer in room state. Bot movement adds pressure during long relocation and exploration moves, maintenance promotes/decays highway overlay cells, and normal non-highway tile changes preserve existing roads instead of clearing them.
- Defaults are off.

### Two-app integration check
A real Java app + Rust GPU engine smoke check was verified on this Windows machine:
- Start the Rust engine on `127.0.0.1:50051`
- Start the Java app on `127.0.0.1:8080`
- Call `GET /game/test` to obtain a token
- Call `POST /game/state`

With the native Windows GPU path, a successful GPU-backed engine call now returns a non-stub `220x165` world with non-zero tiles and highways, and live bot simulation RPCs are also handled by the GPU engine. The current Windows-native verification on this machine used `Radeon RX 580 Series via Vulkan` for both `GenerateRegion` and `SimulateBots`.

Windows shortcut for a full two-process check:
```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

.\scripts\run-real-gpu-e2e.ps1
```

The script starts the real Rust engine from `C:\dev\ide\vscode\EmergentTerrain\EmergentTerrainGPUEngine` by default, starts the Java app with GPU world/bot flags forced on, temporarily lowers the adaptive bot and patch thresholds for proof, verifies non-stub world output, creates a room, polls until at least one bot position changes, submits a proposal near the active participant, and waits for the patch to apply. It checks the engine log to prove `generate_region`, `rebuild_patch`, and `simulate_bots` were handled by the live engine. Logs go under `sample-output\gpu-e2e-*`.
Important caveat: the supported local workflow is now the native Windows path. `GenerateRegion`, `RebuildPatch`, and `SimulateBots` are GPU-backed there.

### Release perf comparison
Use the release-binary comparison harness for CPU-vs-GPU timings:
```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

.\scripts\compare-cpu-vs-gpu-performance.ps1 -Iterations 2 -BuildArtifacts
```

It packages the Spring app to `target\emergent-terrain-0.0.1-SNAPSHOT.jar`, builds the Rust engine in `release`, runs the app with `--spring.profiles.active=perf`, and prints JSON with run-by-run timings plus GPU-minus-CPU deltas. This is the supported local performance path; do not use `mvn spring-boot:run` or `cargo run` for comparisons.

Perf profile notes:
- `application-perf.yml` stays on PostgreSQL and targets `emergentterrain_perf` by default.
- Override perf DB settings with `PG_PERF_URL`, `PG_PERF_USER`, and `PG_PERF_PASSWORD` when needed.
- The compare script now launches `java.exe` and the Rust `release` binary directly, clears the inherited `DEBUG` environment variable, and uses startup-oriented JVM flags. That is the supported way to measure startup on this machine.
- The compare script now measures a GPU-relevant heavy session:
  - create a room
  - apply one live proposal
  - fully populate the room to the perf-profile bot target
  - run repeated `/perf/room/{roomId}/bot-simulation` iterations through the live app
- Default bot-simulation iterations in the compare harness are `10`. Override with `-BotSimulationIterations N` for longer runs.

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
- `app.highways.emergent.*` for travel-demand highway formation and decay
- `app.world.flushIntervalMs`
- `app.world.cacheTtlMs`, `app.world.cacheMaxEntries`
- `app.consensus.neighborRadius`, `app.consensus.voteWindowSeconds`, `app.consensus.minNeighbors`
- `app.consensus.noParticipationLockSeconds`
- `app.density.areaSize`, `app.density.maxOccupants`
- `app.bots.populationFraction`, `app.bots.maxPerRoom`
- `app.bots.initialRoomCreateTarget`
- `app.gpu.enabled`, `app.gpu.world.enabled`, `app.gpu.bots.enabled`, `app.gpu.bots.applyEdits`
- `app.gpu.world.minCells`
- `app.gpu.bots.cacheWorld`, `app.gpu.bots.steps`, `app.gpu.bots.diffMaxEdits`, `app.gpu.bots.minBotCount`, `app.gpu.bots.minWorkItems`
- `app.gpu.patch.minArea`
- `PG_URL`, `PG_USER`, `PG_PASSWORD` for the default app datasource
- `PG_PERF_URL`, `PG_PERF_USER`, `PG_PERF_PASSWORD` for the perf profile datasource

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
- Highways now act as an overlay rather than a destructive terrain replacement. A tile can keep its forest/water/industry identity while still carrying a road corridor.
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
- Dev note: `GpuEngineClient` is wired via a Spring constructor; if adding constructors, keep the `@Autowired` config-based one.
- Docs policy: when behavior, setup, or performance workflows change, update both `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\README.md` and `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\AGENTS.md`.
- Any new learnings from setup, debugging, performance work, or integration checks should be added to markdown in the same change.
- Current learning: `scripts/run-real-gpu-e2e.ps1` is the supported local end-to-end path for both the Java app and Rust engine.
- Current learning: native Windows end-to-end verification now hits real GPU-backed world generation and bot simulation. On this machine the verified adapter was `Radeon RX 580 Series via Vulkan`.
- Current learning: proposal patch application now uses the persisted proposal anchor fields for GPU patch rebuilds, so `rebuild_patch` is exercised cleanly in the native Windows E2E run.
- Current learning: adaptive GPU thresholds matter more than raw GPU enablement on this workload. Small worlds, 1x1 proposal patches, and low-work bot ticks should stay on CPU by default, while the proof script lowers those thresholds explicitly when it needs to prove the live GPU RPCs.
- Current learning: release-binary comparisons now live in `scripts/compare-cpu-vs-gpu-performance.ps1`; use that path with the `perf` profile for meaningful CPU-vs-GPU numbers.
- Current learning: local perf runs on this machine must clear the inherited `DEBUG` environment variable. The compare script does that now, because otherwise Spring Boot starts in debug mode and startup timings are distorted.
- Current learning: the perf profile is PostgreSQL-only and intentionally strips UI/login/websocket beans so startup and request timings reflect the game/room APIs rather than the full interactive UI stack.
- Current learning: GPU only showed a decisive win once the benchmark shifted to repeated heavy bot simulation instead of mostly startup and a single small request. The current compare harness is intentionally bot-simulation-heavy for that reason.
- Current learning: for the normal runtime, the most portable parts of the GPU win were lower bot-simulation thresholds, longer world-cache reuse, and `steps=1`. Those are now moved into `application.yml` on `radical-changes`, while the heavy perf-only endpoints remain benchmark-only.
- Current learning: making the map feel fluid requires both faster backend cadence and browser-side interpolation. Lowering backend intervals alone only makes bots and tiles snap more often.
- Current learning: for voting visuals, browser GPU is the right first hop. The server now streams proposal and tile-transition deltas over websocket, and the browser turns them into continuous WebGPU overlays without round-tripping every animation frame.
- Current learning: once terrain, actors, and minimap all move onto WebGPU, the plain canvas should be treated as an input layer and DOM anchor, not as a competing renderer.
- Current learning: the visible two-tile bot bounce was real simulation behavior, not just a rendering artifact. Fix it in both the Java fallback path and the Rust GPU path, otherwise faster rendering just makes the oscillation easier to notice.
