# AGENTS.md - EmergentTerrain

Practical repo guidance for the Spring Boot app and the Rust GPU engine.

## Repo locations
- Java web app: `/opt/apps/ide/eclipse/workspace-web/EmergentTerrain`
- Rust GPU engine: `/opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine`

## Required environment
- Java 21
- Maven
- PostgreSQL
- Rust / Cargo
- `protoc`
- Vulkan userspace stack for the GPU engine
- PowerShell (`pwsh`) if you want to run the existing `.ps1` helper scripts on Fedora

## Run the app
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH="$JAVA_HOME/bin:$PATH"

cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn spring-boot:run
```

If tests or runtime need a real DB, ensure PostgreSQL is running and the app datasource properties are correct in `src/main/resources/application.yml` or via env vars.

## Build
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn -DskipTests package
```

## Tests
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn test -DtrimStackTrace=false
```

Real two-process GPU smoke check:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
./scripts/run-real-gpu-e2e.sh
```

What it proves:
- the Java app can reach the real Rust GPU engine on `127.0.0.1:50051`
- `POST /game/state` returns non-stub GPU-generated world data
- room bot positions change while the Java app is running against the live engine
- a live proposal applies through `rebuild_patch`
- the engine log contains `wgpu-world`, `wgpu-patch`, and `wgpu-bot-sim` proof markers

Logs are written under `sample-output/gpu-e2e-*`.

Release-binary perf comparison:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
pwsh ./scripts/compare-cpu-vs-gpu-performance.ps1 -Iterations 2 -BuildArtifacts
```

That script is the supported local performance path. It uses the packaged Spring Boot jar, the Rust `release` binary, and the `perf` Spring profile instead of `spring-boot:run` / `cargo run`.

## Benchmarks
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn -DskipTests -Pbench package
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar -f 1 -rf json -rff target/jmh-result.json
```

GPU benchmarks require the Rust GPU engine running. Use `GPU_ENGINE_HOST` / `GPU_ENGINE_PORT` or `-Dgpu.host` / `-Dgpu.port` to point at it.

## GPU engine
See `/opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine/README.md` for full setup.

Quick run:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine
cargo run
```

Override the default bind:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine
GPU_ENGINE_ADDR=127.0.0.1:50052 cargo run
```

## Runtime ports
- Web app: `http://localhost:8080/`
- GPU engine gRPC: `127.0.0.1:50051` by default

## Architecture notes
- `src/main/java/com/emergentterrain/service/RoomService.java` is the core gameplay loop.
- `src/main/java/com/emergentterrain/service/WorldService.java` can call the GPU engine when `app.gpu.*` flags are enabled.
- `src/main/java/com/emergentterrain/gpu/GpuEngineCodec.java` handles tile and bool-grid encoding.
- `src/main/resources/application.yml` holds runtime defaults including GPU, consensus, cache, bot, and simulation-history settings.
- Append-only simulation history is written under `simulation-history/constitution`, `simulation-history/events`, `simulation-history/elections`, `simulation-history/courts`, and `simulation-history/legislation`.

## Constraints
- Do not commit local outputs under `sample-output/`.
- Avoid touching `target/` or generated test reports in commits.
- Keep secrets out of git. Use env vars or local overrides for credentials.
- If behavior, setup, or performance workflows change, update both `README.md` and `AGENTS.md` in the same change.
- Add new learnings from setup, debugging, performance work, or integration checks to markdown in the same change.
