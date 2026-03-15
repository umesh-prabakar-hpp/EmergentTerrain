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
Add any new engine-specific learnings from setup, WSL usage, integration checks, or debugging to markdown in the same change.

## Linux / WSL
For WSL or OpenClaw use, prefer a Linux-native copy such as:
`/home/umesh/workspace/EmergentTerrainGPUEngine`

Important:
- `Cargo.lock` currently requires a newer Cargo than the Ubuntu 24.04 distro package.
- Use `rustup` inside Ubuntu so `cargo` and `rustc` come from `~/.cargo/bin`.

Example:
```bash
curl https://sh.rustup.rs -sSf | sh -s -- -y --profile minimal
. "$HOME/.cargo/env"
rustc --version
cargo --version
protoc --version
```

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

Ubuntu / WSL:
```bash
cd /home/umesh/workspace/EmergentTerrainGPUEngine
. "$HOME/.cargo/env"
cargo build
cargo run
```

## Protobuf
The contract lives at `proto/terrain_gpu.proto` and is compiled by `build.rs` (tonic-build).

## Simulation Notes
- `SimulateBotsRequest` supports world caching via `world_id` (send `tiles`/`highways` once, then only diffs via `tile_edits`).
- Use `steps` to batch multiple simulation ticks per RPC.
- `BotState` includes numeric preference fields (`forest`, `water`, `industry`, `clear`, `mobility`); `state` bytes remain legacy.
