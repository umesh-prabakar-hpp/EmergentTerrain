# julia-math-sidecar

A small HTTP service that wraps Julia's symbolic + numerical math libraries
behind a JSON API. Designed to be called from any process — JVM, Python,
Node, shell — that wants Julia's math stack without embedding `libjulia`.

Built as the math evaluator for [EmergentTerrain][et] (which used to use the
in-process Symja JVM library and hit walls on hard integrals, ODEs, and
LLM-emitted Mathematica syntax). The sidecar itself is application-agnostic
and reusable.

[et]: ../EmergentTerrain

## What it gives you

| Endpoint     | Verb | Purpose                                                             |
|--------------|------|---------------------------------------------------------------------|
| `/healthz`   | GET  | Readiness probe — 200 + uptime when ready                            |
| `/metrics`   | GET  | Per-endpoint call counts + latency, JSON                            |
| `/eval`      | POST | Evaluate arbitrary Julia expression, return value + type            |
| `/solve`     | POST | Symbolic root finder via Symbolics.jl                               |
| `/nsolve`    | POST | Numeric root finder via Roots.jl                                    |
| `/integrate` | POST | Numeric definite integral via QuadGK.jl                             |
| `/sweep`     | POST | Evaluate `f(x)` over uniform grid, return mean/std/min/max          |

Every endpoint enforces:

- A **safety filter** that rejects expressions containing I/O, package
  management, process control, FFI, module loading, and reflection that
  exposes internal state. See `src/safety.jl` for the full list.
- A **per-call timeout** (default 5 s, max 30 s). Long-running workers
  outlive the response — production deployment must pair this with OS-level
  memory/CPU caps and a periodic process restart.

## Install

### 1. Install Julia

Julia 1.10+ required. Easiest:

```bash
curl -fsSL https://install.julialang.org | sh
```

Or via your package manager:

- **Fedora:** `dnf install julia`
- **macOS:** `brew install julia`
- **Ubuntu/Debian:** download from <https://julialang.org/downloads/>
- **Arch:** `pacman -S julia`

Verify:

```bash
julia --version    # should print 1.10.0 or later
```

### 2. Clone & instantiate

```bash
git clone <repo-url> julia-math-sidecar
cd julia-math-sidecar
julia --project=. -e 'using Pkg; Pkg.instantiate()'
```

First run pulls down Symbolics.jl + dependencies (~150 MB compiled).
Allow 1-3 minutes on a fresh machine.

### 3. Run

Easiest:

```bash
./scripts/start.sh
```

This binds 127.0.0.1:8088 and prints

```
[ Info: julia-math-sidecar starting host=127.0.0.1 port=8088 julia_version="1.10.x"
```

Override via env vars:

```bash
PORT=9000 ./scripts/start.sh                 # different port
HOST=0.0.0.0 ./scripts/start.sh              # bind all interfaces (NOT for prod)
JULIA_NUM_THREADS=4 ./scripts/start.sh       # parallel request handling
```

## Quick check

```bash
# Liveness
curl -s localhost:8088/healthz | jq

# Arithmetic
curl -s -X POST localhost:8088/eval \
  -H 'Content-Type: application/json' \
  -d '{"expr": "floor(2.04e-6 / 400e-9)"}' | jq

# Quadratic root (initial guess 1.0)
curl -s -X POST localhost:8088/nsolve \
  -H 'Content-Type: application/json' \
  -d '{"expr": "x^2 - 4", "var": "x", "x0": 1.0}' | jq

# Definite integral
curl -s -X POST localhost:8088/integrate \
  -H 'Content-Type: application/json' \
  -d '{"expr": "sin(x)", "var": "x", "a": 0.0, "b": "π"}' | jq

# Sweep statistics
curl -s -X POST localhost:8088/sweep \
  -H 'Content-Type: application/json' \
  -d '{"expr": "x^2", "var": "x", "start": 1, "end": 10, "samples": 100}' | jq

# Metrics
curl -s localhost:8088/metrics | jq
```

Each call returns a JSON envelope:

```json
{ "ok": true, "elapsed_ms": 4, "result": ... }
```

or, on failure:

```json
{ "ok": false, "error": "<code>", "explanation": "...", "elapsed_ms": ... }
```

`error` is one of: `bad_request`, `parse_error`, `safety_rejected`,
`timeout`, `eval_error`.

## Tests

```bash
julia --project=. -e 'using Pkg; Pkg.test()'
```

The integration tests boot the server on a random ephemeral port and hit
each endpoint with an in-process `HTTP.get` / `HTTP.post`. A fresh checkout
runs in ~2 minutes (most of which is Symbolics.jl precompilation on first
run; rerunning is ~30 s).

## Performance notes

- **JIT warmup:** Julia compiles each call path on first invocation.
  Expect 200 ms–2 s on the first `/eval` of a new expression shape;
  subsequent calls of the same shape are sub-millisecond.
- **Memory footprint:** ~200 MB RSS at idle after package precompile.
  Hard cap with `systemd MemoryMax=512M` or `docker run --memory=512m`.
- **Threading:** set `JULIA_NUM_THREADS=N` to handle N concurrent CPU-bound
  requests. The HTTP layer multiplexes requests onto tasks regardless;
  threading helps when the math itself is heavy.
- **Sysimage (future):** `PackageCompiler.jl` can bake a precompiled
  sysimage that eliminates first-call latency. Not done in 0.1 — bench
  first to decide if it's worth the build-time cost.

## Production deployment

The substring-based safety filter is necessary but not sufficient. Real
isolation comes from OS-level confinement. Use either:

### systemd (Linux)

A starter unit lives at `systemd/julia-math-sidecar.service`. Key
hardening directives:

```ini
[Service]
ExecStart=/usr/local/julia-math-sidecar/scripts/start.sh
Restart=on-failure
RestartSec=5

# Memory + CPU caps
MemoryMax=512M
CPUQuota=200%

# Filesystem
ProtectSystem=strict
ProtectHome=true
ReadOnlyPaths=/usr/local/julia-math-sidecar
NoNewPrivileges=true

# Network — bind to localhost only; block egress.
PrivateNetwork=false
RestrictAddressFamilies=AF_UNIX AF_INET

# Process control
ProtectProc=invisible
ProcSubset=pid
RemoveIPC=true
PrivateUsers=true
```

### Docker

A multi-stage Dockerfile lives at the repo root (Phase 22.3). Build:

```bash
docker build -t julia-math-sidecar:0.1 .
```

Run hardened:

```bash
docker run --rm --name julia-math-sidecar \
    -p 127.0.0.1:8088:8088 \
    --read-only --tmpfs /tmp \
    --memory=512m --cpus=2 \
    --network=bridge \
    --cap-drop=ALL \
    --security-opt=no-new-privileges \
    julia-math-sidecar:0.1
```

The build uses two stages:

- **builder** — pulls Symbolics.jl + transitive deps via
  `Pkg.instantiate()`, runs `Pkg.precompile()` at build time so
  first-request latency in the runtime image is sub-second.
- **runtime** — copies only the precompiled depot + source. Drops
  to a non-root `julia-math` user. Health check is wired via
  Docker's built-in `HEALTHCHECK` so `docker ps` shows reachability
  at a glance.

The container's safety stance:

- Read-only root FS + tmpfs only on `/tmp`: no writes possible
  outside `/tmp`.
- All Linux capabilities dropped (`--cap-drop=ALL`).
- `no-new-privileges`: no setuid escalation possible from inside.
- Memory + CPU caps match the systemd unit (`512m` / 2 cores).
- Localhost-only port binding via `127.0.0.1:` prefix on `-p`.

## Calling from EmergentTerrain

The EmergentTerrain side is wired via `JuliaMathClient` (Phase 22.2, not
yet landed). When that ships, EmergentTerrain's `MathToolSuite.runSymbolic`
will detect a reachable sidecar via `GET /healthz` and route calls there
instead of to the in-process Symja library. The sidecar is opt-in via
`app.julia.enabled: true` in EmergentTerrain's `application.yml`.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Caller (EmergentTerrain / curl / any HTTP client)              │
└─────────────────────┬───────────────────────────────────────────┘
                      │  HTTP POST /eval {expr, timeoutMs}
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│  HTTP.jl router (src/server.jl)                                 │
└─────────────────────┬───────────────────────────────────────────┘
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│  Endpoint handler (src/handlers.jl)                             │
│  1. JSON parse                                                  │
│  2. safety_check(expr)        ─── src/safety.jl                 │
│  3. clamp_timeout_ms          ─── default 5s, max 30s          │
│  4. timed_evaluate(...)       ─── Threads.@spawn + race        │
│  5. record_call! (latency, outcome) ─── src/metrics.jl         │
│  6. Build JSON response                                         │
└─────────────────────┬───────────────────────────────────────────┘
                      ▼
       ┌──────────────┴────────────┐
       │                           │
       ▼                           ▼
┌──────────────┐         ┌────────────────────┐
│ Symbolics.jl │         │ Roots.jl, QuadGK,  │
│   (solve)    │         │   Main.eval (eval) │
└──────────────┘         └────────────────────┘
```

## Roadmap

Tracked in [`phase-22-julia-math-sidecar.md`][p22] in EmergentTerrain.

[p22]: ../EmergentTerrain/phases-for-human-like-performance/phase-22-julia-math-sidecar.md

- **0.1 (this release)** — HTTP server, `/eval`/`/solve`/`/nsolve`/`/integrate`/`/sweep`/`/healthz`/`/metrics`, safety filter, timeout wrapper, smoke tests. Phase 22.1.
- **0.2** — Spring `JuliaMathClient` bean on the EmergentTerrain side, opt-in via `app.julia.enabled`. Phase 22.2.
- **0.3** — Systemd unit + Dockerfile + outbound-code Java filter for defense in depth. Phase 22.3.
- **0.4** — Remove Symja's `ECHO_PRONE_HEADS` retry plumbing on the EmergentTerrain side once the Julia path is the default. Phase 22.4.
- **0.5** — Sandbox-limit relaxation for the Julia path (no statement/loop cap; server-side timeout only). Phase 22.5.
- **0.6** — TokenAccountant integration to track compute spend per call. Phase 22.6.
- **1.0** — Acceptance gate met: ≥50% acceptance rate on a corpus refresh, or ≥3 Q1 papers in one run. Phase 22.7.

## License

TBD — operator's call. The Julia ecosystem is uniformly MIT; this project
will likely follow.
