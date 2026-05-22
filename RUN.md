# Run — copy-paste commands

Terse companion to [STARTUP.md](STARTUP.md). Each step in its own
terminal. Order: Ollama → GPU engine → Julia math sidecar (optional) →
Spring Boot → paper-review.

## 1. Ollama (fast LLM pool)

```bash
OLLAMA_NUM_PARALLEL=3 ollama serve
```

## 2. GPU engine

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine && ./scripts/start.sh
```

Stop: `./scripts/stop.sh`. Log: `tail -f logs/gpu-engine.log`. Both
scripts in `scripts/` are idempotent — refuse to double-start, no-op
when nothing is running.

## 3. Julia math sidecar (optional — phase-22, opt-in)

```bash
cd /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar && ./scripts/start.sh &
```

Stop: `./scripts/stop.sh`. Log: `tail -f logs/sidecar.log`.

First-time setup or after `Project.toml`/`Manifest.toml` changes:

```bash
cd /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar
julia --project=. -e 'using Pkg; Pkg.resolve(); Pkg.instantiate()'
```

PySR recovery probe (run from the ET repo, sidecar must be running):

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
./scripts/pysr-probe/run.sh                                       # default
./scripts/pysr-probe/run.sh --filter hooke                        # one fixture
./scripts/pysr-probe/run.sh --auto-normalize                      # phase 24.7 Lever 1
./scripts/pysr-probe/run.sh \
  --iterations 160 --populations 24 --samples-per-dim 8 --timeout-ms 90000   # deep
```

## 4. Spring Boot app

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain && ./scripts/start.sh
```

Stop: `./scripts/stop.sh`. App listens on http://localhost:8081. Logs:
- `tail -f logs/et-app.log` — Spring's logback file
- `tail -f logs/maven-stdout.log` — Maven + JVM startup banner

> Fedora 44 dropped the JDK 21 package; JDK 25 is the only
> `dnf`-installable JDK. The pom targets `release 21` so cross-compile
> is fine. `scripts/start.sh` exports `JAVA_HOME=/usr/lib/jvm/java-25-openjdk`
> for you. To override, set `JAVA_HOME` in your shell before invoking.

## 5. Paper-review loop (Claude Code)

```
/loop 30m /paper-review research/a3289005 \
       --logs        logs/et-app.log \
       --julia-log   ../julia-math-sidecar/logs/sidecar.log \
       --gpu-log     ../EmergentTerrainGPUEngine/logs/gpu-engine.log
```

`--julia-log` and `--gpu-log` are optional. When supplied, the review report
gains a Julia-sidecar subsection (startup state, `UndefVarError`/`ParseError`
groupings, `EADDRINUSE` detection) and a GPU-engine subsection (Vulkan adapter,
`VK_ERROR_*`, kernel OOM, Rust panics from the `.err.log` peer). The Java app
log alone shows 5-minute `JuliaSidecarReporter` rollup counts; pairing it with
the sidecar logs gives the exception messages behind those counts.

## 6. Phase-doc pending-items review + archive (Claude Code)

```
/phase-review
```

Walks `phases-for-human-like-performance/` and prints a categorized list of
genuinely-open items (code lanes, flag flips queued, live-run acceptance
pending, operational follow-ups, deferred-by-design, doc hygiene). **Also
archives completed docs** via `git mv` to `archive/` when their Status is
terminal (`CLOSED` / `SHIPPED` / `COMPLETE`) and every closing condition is
marked done. Archive moves are staged but not committed.

Add `--out PENDING_REVIEW.md` to also write a markdown artifact. Add
`--no-archive` to suppress the archive step (audit-only mode). Pair with
`/loop 1d /phase-review` for a daily walk, or `/loop 1d /phase-review
--no-archive` if you only want archive moves during focused human-driven
sessions.
