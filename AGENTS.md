# AGENTS.md - EmergentTerrain (Java app + GPU engine)

This file captures practical repo guidance learned while working on the app(s).

## Repo locations (this machine)
- Java web app: `C:\dev\ide\eclipse\workspace-web\EmergentTerrain`
- Rust GPU engine: `C:\dev\ide\vscode\EmergentTerrain\EmergentTerrainGPUEngine`
- Linux-native Java app copy for WSL/OpenClaw: `/home/umesh/workspace/EmergentTerrain`
- Linux-native GPU engine copy for WSL/OpenClaw: `/home/umesh/workspace/EmergentTerrainGPUEngine`

## How to run / test / build
### Required environment
- `JAVA_HOME` should point to JDK 21 (the app runs on Java 21).
- Postgres must be running and reachable at the configured URL.

### Run the app (dev)
```powershell
setx JAVA_HOME "C:\dev\lang\java\amazon-corretto-21.0.6.7.1-windows-x64-jdk\jdk21.0.6_7"
setx PATH "%JAVA_HOME%\bin;%PATH%"
```

Note: `setx` updates future shells only. For the current shell, use:
```powershell
$env:JAVA_HOME = "C:\dev\lang\java\amazon-corretto-21.0.6.7.1-windows-x64-jdk\jdk21.0.6_7"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd spring-boot:run
```

### Build
```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd -DskipTests package
```

### Tests
```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd test -DtrimStackTrace=false
```

If tests need a real DB, ensure Postgres is running and the app datasource properties are correct in `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\src\main\resources\application.yml` (or via env vars).

Real two-process GPU smoke check:
```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

.\scripts\run-real-gpu-e2e.ps1
```

What it proves today:
- the Java app can reach the real Rust GPU engine on `127.0.0.1:50051`
- `POST /game/state` returns the current stub-engine signal (all-zero world, zero highways)
- room bot positions change while the Java app is running against the live engine

The script writes logs under `sample-output\gpu-e2e-*`.

Linux-native WSL smoke check:
```bash
cd /home/umesh/workspace/EmergentTerrain

./scripts/run-real-gpu-e2e-wsl.sh
```

What it proves today:
- both the Java app and Rust GPU engine are running inside WSL
- `POST /game/state` returns the current stub-engine signal (all-zero world, zero highways)
- room bot positions change while the Java app is running against the live engine in Linux

The script writes logs under `/home/umesh/workspace/EmergentTerrain/sample-output/gpu-e2e-wsl-*`.
It also prints end-to-end timings for engine startup, app startup, token fetch, `/game/state`, `/room/create`, and bot-move detection.

### Benchmarks (JMH)
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

GPU benchmarks require the Rust GPU engine running. Use `GPU_ENGINE_HOST` / `GPU_ENGINE_PORT` or `-Dgpu.host` / `-Dgpu.port` to point at it.
Benchmark classes:
- `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\src\jmh\java\com\emergentterrain\bench\GpuVsCpuWorldBenchmark.java`
- `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\src\jmh\java\com\emergentterrain\bench\GpuVsCpuBotSimulationBenchmark.java`

### GPU Engine (Rust gRPC service)
See `C:\dev\ide\vscode\EmergentTerrain\EmergentTerrainGPUEngine\README.md` for full setup.

Quick run (after Rust + MSVC build tools + protoc are installed):
```powershell
cd C:\dev\ide\vscode\EmergentTerrain\EmergentTerrainGPUEngine

cargo run
```

Defaults: listens on `127.0.0.1:50051`. Override:
```powershell
$env:GPU_ENGINE_ADDR="127.0.0.1:50052"
cargo run
```

### WSL / OpenClaw workflow
- Prefer the Linux-native copies under `/home/umesh/workspace/...` when using WSL or OpenClaw. They are faster and avoid `/mnt/c/...` filesystem overhead.
- Do not actively develop the same working tree from both Windows Git and WSL Git. Keep separate Windows and Linux-native copies and sync intentionally.
- The repo now enforces line endings with `.gitattributes`: default text files are `LF`; `*.bat` and `*.cmd` stay `CRLF`.

## Runtime ports / URLs
- Web app: `http://localhost:8080/`
- GPU engine gRPC: `127.0.0.1:50051` by default (`GPU_ENGINE_ADDR` to override)

## Repo conventions
- Branching: work on feature branches and merge to `main`.
- Commit style: imperative, concise (e.g., "Tune flush thresholds and bot proposal cap").
- Formatting/linting: standard Java/Spring conventions; no auto-format tool enforced in repo.

## Architecture notes / critical modules
- `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\src\main\java\com\emergentterrain\service\RoomService.java` is the core gameplay loop:
  - bot maintenance, proposals, snapshots/flush logic, and state caching.
- `room_state` is the live persisted snapshot; `room_state_history` is the snapshot history.
- Flush cadence is controlled by `app.world.flush*` settings in `application.yml`.
- Bot proposal throughput is controlled by `app.consensus.*` (e.g., `maxBotProposalsPerMaintenance`, `botProposalsPerMinute`).
- GPU integration:
  - `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\src\main\java\com\emergentterrain\service\WorldService.java` can call the GPU engine when `app.gpu.*` flags are enabled.
  - `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\src\main\java\com\emergentterrain\service\RoomService.java` can call the GPU engine for bot simulation when enabled.
  - `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\src\main\java\com\emergentterrain\gpu\GpuEngineCodec.java` handles tile/bool grid encoding.
  - GPU config flags in `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\src\main\resources\application.yml`:
    - `app.gpu.enabled`, `app.gpu.world.enabled`, `app.gpu.bots.enabled`, `app.gpu.bots.applyEdits`
    - `app.gpu.bots.cacheWorld`, `app.gpu.bots.steps`, `app.gpu.bots.diffMaxEdits`
    - `app.gpu.host`, `app.gpu.port`, `app.gpu.useTls`
- Spring wiring note: `GpuEngineClient` relies on the `@Autowired` config constructor; keep that annotation if adding constructors.
- Verified two-app smoke check: with the current Rust stub engine running, `/game/state` from the Java app returns an all-zero `220x165` world with zero highways when GPU world generation is enabled. That is a useful proof that the Java app is crossing the gRPC boundary rather than using the CPU world generator.
- Windows-side full two-process shortcut: `scripts\run-real-gpu-e2e.ps1` starts the real Rust engine from the WSL copy, starts the Java app with GPU flags forced on, verifies the current stub-world signal, creates a room, and waits for live bot movement. If engine behavior changes away from the current stub output, update the script assertions and this markdown note in the same change.
- WSL-native full two-process shortcut: `scripts/run-real-gpu-e2e-wsl.sh` runs the Linux-native Java and GPU repos under `/home/umesh/workspace/...`, verifies the current stub-world signal, and waits for live bot movement. If engine behavior changes away from the current stub output, update the script assertions and this markdown note in the same change.
- Current caveat: the Rust engine is still a stub implementation. The WSL script timings are valid Linux-native end-to-end integration metrics on this GPU-capable machine, but they are not hardware GPU/Vulkan performance numbers.

## Primary config files
- Java app config: `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\src\main\resources\application.yml`
- Java app README: `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\README.md`
- GPU engine README: `C:\dev\ide\vscode\EmergentTerrain\EmergentTerrainGPUEngine\README.md`

## Constraints
- Do not commit local outputs under `sample-output/` (already in `.gitignore`).
- Avoid touching `target/` or generated test reports in commits.
- Keep secrets out of git:
  - Don't commit real database passwords or API keys.
  - Use env vars or local overrides for sensitive values.
- If you change persistence behavior, update relevant docs in `README.md` if user-facing behavior changes.
- Documentation hygiene: if behavior, setup, or performance workflows change, update both `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\AGENTS.md` and `C:\dev\ide\eclipse\workspace-web\EmergentTerrain\README.md` in the same change.
- Any new learnings from setup, WSL/OpenClaw usage, debugging, performance work, or integration checks must be added to markdown in the same change. Use `AGENTS.md` and `README.md`, and update the GPU engine `README.md` as well when the learning is engine-specific.

## Tooling preferences
- Prefer `rg` for code search.
- Prefer PowerShell commands for consistency on Windows.
- Avoid running ad-hoc scripts unless needed; use existing scripts in `scripts/` when available.

## Data access / secrets guidance
- Do not log credentials or full connection strings.
- Avoid logging full payloads of user data or bot profiles.
- When running locally, use a dedicated dev database (not production).
