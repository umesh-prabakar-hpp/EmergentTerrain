# EmergentTerrain GPU Engine

Rust gRPC service (tonic) that hosts the Vulkan compute engine.

## Monorepo Location
- `engines/gpu-engine/`

## Fedora Setup
The supported one-command Fedora bootstrap now lives in the Java repo:
```bash
cd "$(git rev-parse --show-toplevel)"
bash ./scripts/setup-fedora-prereqs.sh
```

It installs the shared Java/Rust/PostgreSQL/Vulkan prerequisites, initializes PostgreSQL, creates the app databases, and installs the optional Playwright browser dependency used by the UI scripts.

Install the base toolchain and Vulkan/protobuf dependencies:
```bash
sudo dnf install -y rust cargo protobuf-compiler vulkan-loader vulkan-tools mesa-vulkan-drivers clang
```

Verify:
```bash
rustc --version
cargo --version
protoc --version
vulkaninfo --summary
```

If `protoc` is installed outside `PATH`, set:
```bash
export PROTOC=/usr/bin/protoc
```

## Build / Run
```bash
cd "$(git rev-parse --show-toplevel)"/engines/gpu-engine
cargo build
```

```bash
# Default listens on 127.0.0.1:50051
cd "$(git rev-parse --show-toplevel)"/engines/gpu-engine
cargo run

# Custom address
GPU_ENGINE_ADDR=127.0.0.1:50052 cargo run
```

## Full App E2E Check
From the monorepo root, run:
```bash
cd "$(git rev-parse --show-toplevel)"
pwsh ./scripts/run-real-gpu-e2e.ps1
```

Today that script verifies:
- the Java app sees non-stub GPU-generated world data from the Rust engine
- room bot positions change while the Java app is connected to the live engine
- a live proposal applies through `rebuild_patch`
- the engine log contains `wgpu-world`, `wgpu-patch`, and `wgpu-bot-sim` proof markers
- the script lowers the adaptive bot and patch thresholds on purpose so the live GPU bot and patch RPCs are still proven after the runtime moved small work back to CPU by default

Current learning: the last verified adapter on this machine was `Radeon RX 580 Series via Vulkan`.
Current learning: local CPU-vs-GPU performance comparisons should use `scripts/compare-cpu-vs-gpu-performance.ps1`, which runs the Java jar plus the Rust `release` binary directly instead of `spring-boot:run` / `cargo run`.
Current learning: that compare script clears the inherited `DEBUG` environment variable before launching both processes, because Spring Boot startup timings are otherwise distorted.
Current learning: repeated `world_id` bot-simulation requests reuse cached GPU world buffers inside the engine. That change materially improved the heavy-session benchmark.
Current learning: `BotState.state` is no longer legacy filler on the hot path. The engine uses it to carry bot anti-oscillation memory such as the previous tile, heading bias, reverse cooldown, and a short movement plan across GPU simulation ticks.

If engine behavior changes, update the Java repo scripts and this README in the same change.

## Protobuf
The contract lives at `proto/terrain_gpu.proto` and is compiled by `build.rs` (tonic-build).

## Simulation Notes
- `SimulateBotsRequest` supports world caching via `world_id` (send `tiles`/`highways` once, then only diffs via `tile_edits`).
- Use `steps` to batch multiple simulation ticks per RPC.
- `BotState` includes numeric preference fields (`forest`, `water`, `industry`, `clear`, `mobility`); `state` bytes are now used for bot movement memory shared with the Java app.
- `BoolGrid` payloads support packed booleans in addition to the legacy byte-per-cell format, which reduces highway payload size on the Java/Rust boundary.
