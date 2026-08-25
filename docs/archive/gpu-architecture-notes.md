# Archived GPU architecture notes

Historical planning reference from before the GPU engine was implemented. It does not define the
current runtime or release procedure; use `engines/gpu-engine/README.md` and `RUN.md` for that.

Given your goal ("GPU compute skills + real projects") **and** you're building a Spring Boot/Thymeleaf terrain game (very similar to what your README describes: deterministic world generation, editable tiles, bots, consensus, highways, etc. ), the best move is:

* **Use GPU compute where it actually helps your game** (world/terrain generation, region recompute, bot simulation, heatmaps/pathfields)
* **Pick a GPU stack that's portable and works well on AMD**
* **Keep Spring Boot as the orchestration/UI**, and treat GPU compute as a separate "engine" module

## The stack choice

```
Browser (Thymeleaf / JS)
        |
        | HTTP / WebSocket
        v
Spring Boot (authoritative game server)
        |
        | HTTP / gRPC / IPC
        v
Rust GPU Engine (compute-only, stateless)
        |
        | Vulkan / OpenCL
        v
AMD GPU
```

### Best overall for AMD + "real projects": **Vulkan Compute**

Why:

* Works great on AMD.
* Very transferable GPU skills (buffers, dispatch, synchronization, memory layouts).
* Doesn't depend on ROCm support matrix.
* Lets you ship on Windows *and* Linux.


---

## Where GPU compute fits into *your* game (high leverage)

From your README, your app has deterministic world gen, editable terrain, highways, bots, and "regions" / snapshots. 
These map nicely to GPU work:

### 1) Deterministic terrain generation for regions (big win)

Instead of generating tiles on CPU for every request/region:

* Send: `seed + region bounds + parameters`
* GPU outputs: `tileTypes[]` / `height[]` / `biome[]`
* CPU stores/sends to client

This aligns directly with `POST /game/state` returning "world seed + regions". 

### 2) Fast recomputation after edits (local region "rebuild")

Your game allows 2x2 changes + voting. 
After a proposal is applied, you can:

* Recompute only an affected *patch* + a halo around it (e.g., 64x64 area)
* GPU updates derived fields: smoothing, vegetation density, waterflow, "highway influence", etc.

### 3) Bot simulation and influence maps

Instead of moving each bot and computing interactions on CPU:

* GPU runs N bots in parallel (steering, local sensing, rule-based edits)
* GPU writes an "influence map" you can use for:

  * spawn balancing
  * density constraints
  * interesting world events
    This fits your "bots with visible positions" and "bot map changes applied in-memory with periodic snapshot flushes". 

### 4) Pathfields / movement acceleration (especially with highways)

You already have highways that jump movement. 
GPU can compute a flow-field / distance transform per region quickly (great learning project).

---

## A concrete architecture that won't make Spring miserable

### Keep Spring Boot as-is; add a "GPU Engine" module

**GPU engine as a local native service**

* A small Rust/C++ service that exposes:

  * `generateRegion(seed, bounds, params) -> tile array`
  * `rebuildPatch(...) -> patch updates`
  * `simulateBots(...) -> new positions + edits`
* Spring calls it over localhost HTTP/gRPC.

Pros: isolates Vulkan, easy to crash/restart.
Cons: extra process.

---

## A 4-stage plan (each stage produces a shippable improvement)

### Stage 1 - Baseline + determinism tests

* Add golden tests: seed â†’ region â†’ stable hash.
* Measure CPU time per region.

### Stage 2 - GPU region generation (first real GPU milestone)

* Implement region generator kernel (Vulkan compute).
* Validate output matches CPU reference for the same seed/params.

### Stage 3 - GPU patch rebuild on edits

* When a proposal is applied, recompute just the affected area.
* Plug into your existing consensus/apply pipeline. 

### Stage 4 - GPU bots / influence maps

* Move bot stepping to GPU.
* Keep final authoritative state and rule enforcement in CPU (important).

---
