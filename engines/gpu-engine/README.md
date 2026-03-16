# EmergentTerrain GPU Engine

Rust gRPC service (tonic) that will host the Vulkan compute engine.

## Rust Toolchain (Windows)
Install via winget:
```powershell
winget install --id Rustlang.Rustup --source winget --accept-package-agreements --accept-source-agreements --silent
```

Ensure Rust is on PATH (new shell or run):
```powershell
setx PATH "%USERPROFILE%\.cargo\bin;%PATH%"
```

Verify:
```powershell
rustup --version
rustc --version
cargo --version
```

### Windows C++ Build Tools (required)
Rust on Windows uses the MSVC toolchain by default, so you need the C++ build tools installed.
Install either Visual Studio Build Tools or Visual Studio Community with the C++ workload:
```powershell
winget install --id Microsoft.VisualStudio.2022.BuildTools --source winget --accept-package-agreements --accept-source-agreements --silent
```

If `winget` finishes but you still do not have `link.exe`, install the workload manually:
1. Open **Visual Studio Installer**.
2. Find **Visual Studio Build Tools 2022** and click **Modify**.
3. Select **Desktop development with C++**.
4. Install and then open a new terminal.

In the installer, select the **Desktop development with C++** workload.
After install, open a new terminal so `link.exe` is on PATH.

### Protobuf Compiler (protoc)
`build.rs` uses `protoc` to compile `proto/terrain_gpu.proto`.
Install via winget:
```powershell
winget install --id Google.Protobuf --source winget --accept-package-agreements --accept-source-agreements --silent
```

If `protoc` is not on PATH in the current shell, either open a new terminal or set `PROTOC`:
```powershell
where.exe protoc
# If not found, set PROTOC to the full path:
$env:PROTOC="C:\\path\\to\\protoc.exe"
```

### Maintenance
If Rust tooling or build requirements change, update this README so it remains the source of truth for setup.
Add any new engine-specific learnings from setup, integration checks, or debugging to markdown in the same change.

## Build / Run
```powershell
cd C:\dev\ide\vscode\EmergentTerrain\EmergentTerrainGPUEngine

cargo build
```

```powershell
# Default listens on 127.0.0.1:50051
cd C:\dev\ide\vscode\EmergentTerrain\EmergentTerrainGPUEngine

cargo run

# Custom address
$env:GPU_ENGINE_ADDR="127.0.0.1:50052"
cd C:\dev\ide\vscode\EmergentTerrain\EmergentTerrainGPUEngine

cargo run
```

## Full App E2E Check
From the Java app repo on this machine, there is a Windows-side script that runs a full two-process check against the real engine on `127.0.0.1:50051`:
```powershell
cd C:\dev\ide\eclipse\workspace-web\EmergentTerrain

.\scripts\run-real-gpu-e2e.ps1
```

Today that script verifies two things:
- the Java app sees non-stub GPU-generated world data from the Rust engine
- room bot positions change while the Java app is connected to the live engine
- a live proposal applies through `rebuild_patch`
- the engine log contains `wgpu-world`, `wgpu-patch`, and `wgpu-bot-sim` proof markers
- the script lowers the adaptive bot and patch thresholds on purpose so the live GPU bot and patch RPCs are still proven after the runtime moved small work back to CPU by default

Current caveat: the supported local path is the native Windows one, and it is GPU-backed for `GenerateRegion`, `RebuildPatch`, and `SimulateBots`.
Current learning: the supported local end-to-end path is the native Windows run of `scripts/run-real-gpu-e2e.ps1`.
Current learning: the verified native Windows adapter on this machine is `Radeon RX 580 Series via Vulkan`.
Current learning: the Windows E2E path now proves `RebuildPatch` against the live engine rather than only world generation and bot simulation.
Current learning: local CPU-vs-GPU performance comparisons should use `scripts\compare-cpu-vs-gpu-performance.ps1`, which runs the Java jar plus the Rust `release` binary directly instead of `spring-boot:run` / `cargo run`.
Current learning: that compare script also clears the inherited `DEBUG` environment variable before launching both processes, because Spring Boot startup timings on this machine are otherwise distorted.
Current learning: repeated `world_id` bot-simulation requests now reuse cached GPU world buffers inside the engine. That change is what made the heavy-session GPU benchmark materially faster than CPU-only.

If engine behavior changes, update those scripts and this README note in the same change.

## Protobuf
The contract lives at `proto/terrain_gpu.proto` and is compiled by `build.rs` (tonic-build).

## Simulation Notes
- `SimulateBotsRequest` supports world caching via `world_id` (send `tiles`/`highways` once, then only diffs via `tile_edits`).
- Use `steps` to batch multiple simulation ticks per RPC.
- `BotState` includes numeric preference fields (`forest`, `water`, `industry`, `clear`, `mobility`); `state` bytes remain legacy.
- `BoolGrid` payloads now support packed booleans in addition to the legacy byte-per-cell format, which reduces highway payload size on the Java/Rust boundary.
