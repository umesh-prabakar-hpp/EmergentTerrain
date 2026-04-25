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
- Constitutional simulation now spans the first three runtime slices: bots carry civic state, rooms carry government state, deterministic parties/elections/bills/budgets tick in maintenance, executive orders and court review can intervene, office roles are assigned to bots, and append-only records are written under `simulation-history/`
- Government trend history is now stored server-side and fetched on demand through `/room/{roomId}/government-trend`; recent points stay at per-tick resolution for 6 hours, then compact to 1-minute, 5-minute, and 15-minute buckets as they age.
- The game sidebar now renders that government trend history as an on-demand chart with trust/service/state-trust lines, action load, budget mix, and event markers; it loads only when the government panel is shown and refreshes manually from the UI.
- The in-game trend card now has an `Open` action that launches `/game/government-trend` in a new tab for a larger interactive chart with hover tooltips, series toggles, and adaptive vertical scaling so small trust/service changes do not disappear into a flat `0..1` baseline.
- Those trend views now also include the newer government signals end-to-end: pressure, implementation score, administrative/conflict load, and civic legal climate through petitioning/review/sanction counts and shares.
- Government action cards in the sidebar now include richer detail lines. Court entries show the plaintiff, defendant, legal basis, constitutional basis, and affected laws; bill and executive-order entries also expose jurisdiction, sponsor/issuer, and policy details.
- Court summaries now also carry the challenged bill/order title plus an issue statement describing what is being argued, and repeated court reviews of the same unchanged posture are suppressed so one bill does not spam near-identical case entries.
- Government institutions now carry legitimacy/workload/capacity profiles, bills and executive orders carry structured sections/rights/enforcement/funding metadata, and court review runs through a staged lifecycle with doctrine-driven scrutiny, generated plaintiffs, and precedent memory instead of a single hard-coded one-tick decision.
- The government sidebar now also surfaces institution-pressure cards and recent precedent-memory cards so docket load, legitimacy, and doctrine carry-through are visible without inspecting raw state JSON.
- The simulation now has first-pass versions of the next realism layers too: committee/amendment/whip/conference bill flow, administrative rulemaking and enforcement actions, named civic actors, political-pressure events, federal/state implementation conflicts, live implementation outcomes, and historical-memory markers that record elections, precedent shifts, conflicts, and policy backfires.
- Those added layers now feed back into the core simulation as well: pressure can move bill support and executive action, administrative appeals raise judicial risk, conflicts and backfires drag trust/service, and strong implementation improves institutional standing.
- The main knobs for those newer feedback loops are now config-driven under `app.government.feedback.*` and `app.government.thresholds.*`, so tuning implementation drag, pressure sensitivity, conflict effects, and court block thresholds no longer requires Java edits.
- Bots now also carry a first-pass individualized legal-exposure layer: enacted laws, active orders, and administrative enforcement can move each bot into `COMPLIANCE_REVIEW`, `UNDER_NOTICE`, `ACTIVE_SANCTION`, `REVIEW`, or `PETITIONING`, and large petitioning cohorts can now feed political-pressure generation instead of government pressure staying purely top-down.
- The government sidebar now surfaces that individualized layer too through a civic legal climate section, so bot-side legal exposure is no longer hidden behind aggregate trust and service scores.

## Run
Java 21 required.

Fedora / Linux example:
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH="$JAVA_HOME/bin:$PATH"

cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn spring-boot:run
```
Open `http://localhost:8080/`

## Fedora Bootstrap
If you want one native Fedora setup path for both repos, use:

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
bash ./scripts/setup-fedora-prereqs.sh
```

What it does:
- installs the Java, Maven, PostgreSQL, Rust, protobuf, Vulkan, Node, `jq`, `curl`, and PowerShell packages used by the app and GPU engine
- initializes and starts PostgreSQL
- sets the local `postgres` password to `NewStrongPasswordHere` by default so it matches `application.yml`
- creates `emergentterrain` and `emergentterrain_perf`
- installs the local npm dependency and Playwright Chromium for the UI scripts

Useful flags:
- `--postgres-password YourOwnPassword`
- `--skip-playwright`
- `--skip-node-deps`
- `--skip-packages`
- `--skip-postgres`

Smoke test for the helper:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
bash ./scripts/test-setup-fedora-prereqs.sh
```

## Line Endings
- The repo now uses `.gitattributes` to normalize text files to `LF`.
- `*.bat` and `*.cmd` are kept as `CRLF`.
- Recommended local Git settings:
  - `git config --global core.autocrlf false`
  - `git config --global core.eol lf`

## Tests (summary)
- `RoomRapidChangePersistenceIntegrationTest` (profile: `test`) runs a short rapid-change loop, forces maintenance/flushes, and asserts persisted snapshots and change counts. Expected time: ~80s.
- `RoomStateSamplingIntegrationTest` (profile: `test`) seeds a room, waits `sample.waitMs` (default 3 minutes), applies proposals, flushes state, and writes sampling output to `sample-output/`. Expected time: ~3m 15s with the default wait.
- `scripts/run-real-gpu-e2e.ps1` starts the real Rust GPU engine plus the Java app, forces GPU proof thresholds for bots and patch rebuilds, verifies non-stub world data over `/game/state`, creates a room, waits for live bot movement over `/room/{roomId}/state`, submits a proposal, and proves the live `rebuild_patch` RPC. Use `scripts/run-real-gpu-e2e.sh` on Fedora and `pwsh ./scripts/run-real-gpu-e2e.ps1` on Windows. Expected time: ~30-60s after startup.
- `scripts/compare-cpu-vs-gpu-performance.ps1` runs release-binary CPU-vs-GPU comparisons with the `perf` profile and prints JSON with per-run timings, averages, and GPU-minus-CPU deltas. Use `scripts/compare-cpu-vs-gpu-performance.sh` on Fedora and `pwsh ./scripts/compare-cpu-vs-gpu-performance.ps1` on Windows.
- `scripts/test-setup-fedora-prereqs.sh` smoke-tests the Fedora bootstrap helper in a no-op mode so setup docs and argument parsing can be verified without reinstalling packages or touching PostgreSQL. Expected time: a few seconds.
- `scripts/minimap-route-ui-test.js` (Playwright) clicks the minimap, waits for route choices, and verifies the minimap canvas updates. Expected time: ~50s.
- `scripts/minimap-travel-smoothness-test.js` (Playwright) auto-navigates via minimap, samples render/logic positions, and reports movement update cadence and move request latency. Expected time: ~20s.

## Benchmarks (JMH)
Benchmarks live under `src/jmh/java/com/emergentterrain/bench`.

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn -DskipTests -Pbench package
```

Standalone (forked, recommended for real numbers):
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar -f 1 -rf json -rff target/jmh-result.json
```

Profiles:
```bash
# RPC overhead (small sizes)
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar ".*GpuVsCpu.*" -p profile=RPC -f 1

# Compute-heavy (larger sizes)
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar ".*GpuVsCpu.*" -p profile=HEAVY -f 1
```

In-process (no fork, quick sanity):
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn -DskipTests -Pbench exec:java -Dexec.args=".*GpuVsCpu.* -f 0 -rf json -rff target/jmh-result.json"
```

GPU benchmarks require the Rust GPU engine to be running. Use `GPU_ENGINE_HOST` / `GPU_ENGINE_PORT` or `-Dgpu.host` / `-Dgpu.port` if needed.

Focused patch benchmark:
```bash
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar ".*PatchRebuild.*" -f 1
```

Focused government benchmark:
```bash
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar ".*GovernmentSimulationBenchmark.*" -f 1
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
A real Java app + Rust GPU engine smoke check is expected to run from Fedora with the repos living under `/opt/apps/ide/eclipse/workspace-web/`:
- Start the Rust engine on `127.0.0.1:50051`
- Start the Java app on `127.0.0.1:8080`
- Call `GET /game/test` to obtain a token
- Call `POST /game/state`

A successful GPU-backed engine call returns a non-stub `220x165` world with non-zero tiles and highways, and live bot simulation RPCs are also handled by the GPU engine. The last verified adapter on this machine was `Radeon RX 580 Series via Vulkan`.

Fedora shortcut for a full two-process check:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
./scripts/run-real-gpu-e2e.sh
```

The script starts the real Rust engine from `/opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine` by default, starts the Java app with GPU world/bot flags forced on, temporarily lowers the adaptive bot and patch thresholds for proof, verifies non-stub world output, creates a room, polls until at least one bot position changes, submits a proposal near the active participant, and waits for the patch to apply. It checks the engine log to prove `generate_region`, `rebuild_patch`, and `simulate_bots` were handled by the live engine. Logs go under `sample-output/gpu-e2e-*`.

### Release perf comparison
Use the release-binary comparison harness for CPU-vs-GPU timings:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
./scripts/compare-cpu-vs-gpu-performance.sh --iterations 2 --build-artifacts
```

It packages the Spring app to `target/emergent-terrain-0.0.1-SNAPSHOT.jar`, builds the Rust engine in `release`, runs the app with `--spring.profiles.active=perf`, and prints JSON with run-by-run timings plus GPU-minus-CPU deltas. This is the supported local performance path; do not use `mvn spring-boot:run` or `cargo run` for comparisons.

Perf profile notes:
- `application-perf.yml` stays on PostgreSQL and targets `emergentterrain_perf` by default.
- Override perf DB settings with `PG_PERF_URL`, `PG_PERF_USER`, and `PG_PERF_PASSWORD` when needed.
- The compare script now launches the Java runtime and the Rust `release` binary directly, clears the inherited `DEBUG` environment variable, and uses startup-oriented JVM flags. That is the supported way to measure startup on this machine.
- The compare script now measures a GPU-relevant heavy session:
  - create a room
  - apply one live proposal
  - fully populate the room to the perf-profile bot target
  - run repeated `/perf/room/{roomId}/bot-simulation` iterations through the live app
- Default bot-simulation iterations in the compare harness are `10`. Override with `-BotSimulationIterations N` for longer runs.

## PostgreSQL on Fedora
PostgreSQL is required for normal app and perf-profile runs, but it is not bundled with Fedora by default.

Install and initialize it with:
```bash
sudo dnf install -y postgresql-server postgresql
sudo postgresql-setup --initdb
sudo systemctl enable --now postgresql
```

If you need to reset the local `postgres` password:
```bash
sudo -u postgres psql -c "ALTER USER postgres WITH PASSWORD 'NewStrongPasswordHere';"
```

Or use the native helper from this repo:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
bash ./scripts/setup-fedora-prereqs.sh
```

## UI Assets
Material Web, Roboto, and Material Symbols are loaded from CDN in the templates.

## Configuration
See `src/main/resources/application.yml` for app settings:
- `app.qr.hmacSecret`
- `app.room.multipleRoomsEnabled` defaults to false for the single-room player experience
- `app.ai.digestCooldownMinutes`, `app.ai.advisorCooldownSeconds`, and `app.ai.explanationCooldownMs` for LLM request pacing
- `app.world.auto`, `app.world.maxTiles`, `app.world.aspectRatio`, `app.world.highwayRatio`
- `app.highways.emergent.*` for travel-demand highway formation and decay
- `app.government.*` for deterministic constitutional/government timing
- `app.government.feedback.*` and `app.government.thresholds.*` for government simulation tuning
- `app.government.timeline.*` for on-demand government trend storage, retention, and rollup cadence
- `app.simulationHistory.root` for append-only civic history output
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

## Constitutional Terrain
- Current implementation notes live in `docs/constitutional-terrain-phase1.md`.
- Machine-readable constitutional rules live in `src/main/resources/government/constitution-rules.json`.
- That constitution file contains structured content, not just a version string: Articles I-VII plus Amendments I-XXVII each carry a title, category, trigger, enforcement rule, and affected systems used by runtime doctrine and summary generation.
- Runtime history output is append-only and written under `simulation-history/` with subfolders for:
  - `constitution`
  - `events`
  - `elections`
  - `courts`
  - `legislation`
- The current runtime slice now includes:
  - deterministic federal and state party / budget / election / bill simulation
  - executive order issuance when services or trust degrade
  - court review that can block rights-process actions
  - office-role assignment back onto bots for federal and state branches
  - focused unit coverage in `GovernmentSimulationServiceTest`
  - focused JMH coverage in `GovernmentSimulationBenchmark`
- Richer agencies, amendment gameplay, and GPU civic kernels are still deferred.

## Routes (summary)
- `GET /register`, `POST /register`
- `GET /login`, `POST /login`
- `GET /profile/new`, `POST /profile` (first player profile only)
- `GET /qr?token=...`
- `GET /game?token=...`
- `GET /game/government-trend?token=...&roomId=...&window=...`
- `POST /game/state` (world seed + regions)
- `POST /room/create` (resolves to the singleton room)
- `POST /room/join` (resolves to the singleton room)
- `POST /room/last` (join or create the singleton room)
- `POST /room/{roomId}/state`
- `POST /room/{roomId}/government-trend`
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
- Docs policy: when behavior, setup, or performance workflows change, update both `README.md` and `AGENTS.md`.
- Any new learnings from setup, debugging, performance work, or integration checks should be added to markdown in the same change.
- Current learning: `scripts/run-real-gpu-e2e.sh` is now the native Fedora implementation for the supported local end-to-end path, while `scripts/run-real-gpu-e2e.ps1` remains the Windows path.
- Current learning: native Windows end-to-end verification now hits real GPU-backed world generation and bot simulation. On this machine the verified adapter was `Radeon RX 580 Series via Vulkan`.
- Current learning: proposal patch application now uses the persisted proposal anchor fields for GPU patch rebuilds, so `rebuild_patch` is exercised cleanly in the native Windows E2E run.
- Current learning: adaptive GPU thresholds matter more than raw GPU enablement on this workload. Small worlds, 1x1 proposal patches, and low-work bot ticks should stay on CPU by default, while the proof script lowers those thresholds explicitly when it needs to prove the live GPU RPCs.
- Current learning: `scripts/compare-cpu-vs-gpu-performance.sh` is now the native Fedora perf harness, while `scripts/compare-cpu-vs-gpu-performance.ps1` remains the Windows path for the same workload.
- Current learning: local perf runs on this machine must clear the inherited `DEBUG` environment variable. The compare script does that now, because otherwise Spring Boot starts in debug mode and startup timings are distorted.
- Current learning: the perf profile is PostgreSQL-only and intentionally strips UI/login/websocket beans so startup and request timings reflect the game/room APIs rather than the full interactive UI stack.
- Current learning: GPU only showed a decisive win once the benchmark shifted to repeated heavy bot simulation instead of mostly startup and a single small request. The current compare harness is intentionally bot-simulation-heavy for that reason.
- Current learning: for the normal runtime, the most portable parts of the GPU win were lower bot-simulation thresholds, longer world-cache reuse, and `steps=1`. Those are now moved into `application.yml` on `radical-changes`, while the heavy perf-only endpoints remain benchmark-only.
- Current learning: making the map feel fluid requires both faster backend cadence and browser-side interpolation. Lowering backend intervals alone only makes bots and tiles snap more often.
- Current learning: for voting visuals, browser GPU is the right first hop. The server now streams proposal and tile-transition deltas over websocket, and the browser turns them into continuous WebGPU overlays without round-tripping every animation frame.
- Current learning: once terrain, actors, and minimap all move onto WebGPU, the plain canvas should be treated as an input layer and DOM anchor, not as a competing renderer.
- Current learning: the visible two-tile bot bounce was real simulation behavior, not just a rendering artifact. Fix it in both the Java fallback path and the Rust GPU path, otherwise faster rendering just makes the oscillation easier to notice.



DRI_PRIME=1 google-chrome-stable \
  --user-data-dir=/tmp/chrome-amd-x11-webgpu2 \
  --ozone-platform=x11 \
  --enable-features=Vulkan \
  --enable-unsafe-webgpu \
  --render-node-override=/dev/dri/renderD129 \
  --no-first-run \
  --no-default-browser-check


check chrome://gpu again. What we want to see is:
*ACTIVE* on AMD
GL_RENDERER showing AMD, not Intel

http://localhost:8080/game?token=H4sIAAAAAAAA_12Qu07EMBBF_8V1gsbjsSdJtwUFEnRUNKvsZrJYJE5kOzyE-He8vCS2Pef6-mre1bPqdKXWuIx-kptBdQpYGMlJrQ_jWNOg27plc6gPDSCQPTjHR1Wp_iSqI1upJK_l1d3u9rrQaTn22S-hEI2G7DkY0ovEpLp3NfknSftxiZJyATluUv3Alz5L_I_mZQu59-EiuawS9qOXabiskLw_Tn4uTf9F2kJ4-0YflXr0wyDhPGctO9IWSxqunCOtW0IHbAyiqVSWeZXY598AozOWCHTjNDguRdvsB5_fvmyDjMhNqQFoLVWq_JH-pANitmA1NC2We0uQePpxAIZZO83aEpizy3FZv2RBgGhbptYyWVfW-7JYhl0uB0ZAVwPVSPcaO910qK_AEFhg5Af18Qnf8fjq3QEAAA.S1tFI2UguURHEgpKvpD5muTneP4niJA2tSZ2sRolrW4
