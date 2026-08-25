# EmergentTerrain — Startup Guide

## Prerequisites

- Java 21 *(on this Fedora 44 dev shell, the JDK 21 package was dropped — use JDK 25 as the build JDK; the pom still targets `release 21` so cross-compile is fine)*
- Maven 3.9+ *(needs `JAVA_HOME` exported — `/usr/bin/java` resolving via PATH is not enough; see Section 5)*
- PostgreSQL 14+
- Rust + Cargo (for the GPU engine + Phase 8 `run_gpu_eval` tool)
- Ollama (for local LLM fast-path — optional but recommended)
- One or more primary-tier API keys — Groq, OpenRouter (Groq is the default and most opinionated)

---

## 1. Install and start Ollama

Ollama handles the frequent low-stakes LLM calls (formulation, refinement decisions,
pattern explanations, government digest). It runs locally on one or more machines.

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

Start the Ollama daemon with parallel inference slots (runs on port 11434 by default):

```bash
OLLAMA_NUM_PARALLEL=3 ollama serve
```

Pull the model(s) — at least one, more if you want multi-model Ollama (Phase 9 U4):

```bash
ollama pull gemma3:4b              # default
# optional additional fast-tier models:
# ollama pull qwen2:7b
```

Verify it's running:

```bash
curl http://localhost:11434/api/tags
```

You should see your pulled models listed.

> **Phase 9 U4 — multi-model Ollama.** Configure a CSV list of pulled models via
> `app.ai.ollama.preferredModels: "gemma3:4b,qwen2:7b"`. The pool creates one
> Backend per `(host × model)` tuple; `callSyncOnModel(modelId, …)` targets a
> specific model so the U6 `FastModelRegistry` can round-robin across them.
> The legacy `spring.ai.ollama.chat.model` key still works (with a deprecation
> WARN at startup) for one release cycle.

> **Without Ollama:** The app starts normally with `app.ai.enabled=false` (or if Ollama is unreachable). Research narration falls back to template text and LLM-driven features are skipped. No errors.

---

## 2. Set up primary providers (Groq + OpenRouter)

The primary tier handles **paper narration**, **professor tool-calling**,
**student revision**, **panel review**, and **genome mentoring** — the text
users actually read and the agentic computation that backs it. Phase 7 + 9
aggregate every configured provider's 70B+ tool-capable models into a single
`PrimaryModelRegistry`; a single primary call falls through every model
across providers before throwing.

**Minimum**: one provider key. Groq is the default and most opinionated.
Additional providers add depth — they're consulted automatically when Groq is
rate-limited or fails a model probe.

Every provider slot is now declared in **one place** — an entry under
`app.ai.providers.<name>` (connection details + caps). There are no separate
`app.ai.groq:` / `app.ai.openrouter:` named blocks anymore: each pool is a
generic `GenericOpenAICompatPool` bean registered from its `providers.<name>`
entry. API keys resolve from the env var named in `api-key-env`.

### Groq (default)

1. Sign up at [console.groq.com](https://console.groq.com) (no credit card)
2. Create an API key
3. Export it as the env var named in `api-key-env` (`GROQ_API_KEY`); the
   committed `app.ai.providers.groq` entry reads it automatically:

```yaml
app:
  ai:
    providers:
      groq:
        base-url: https://api.groq.com/openai/v1
        api-key-env: GROQ_API_KEY
        models:
          - llama-3.3-70b-versatile
          - openai/gpt-oss-120b
        supports-tools: true
```

The `models` list is operator-curated, pre-ranked highest-quality first.
Pin or reorder models by editing that list, then restart (selection tests
re-run against the new candidates).

### OpenRouter (currently the free mirror)

1. Sign up at [openrouter.ai](https://openrouter.ai); set spend cap = $0 to
   stay on free routes
2. Export `OPENROUTER_API_KEY`; the committed `app.ai.providers.openrouter`
   entry reads it automatically. Today this slot points at the free
   `openai/gpt-oss-120b:free` mirror:

```yaml
app:
  ai:
    providers:
      openrouter:
        base-url: https://openrouter.ai/api/v1
        api-key-env: OPENROUTER_API_KEY
        models: [openai/gpt-oss-120b:free]
        supports-tools: true
        extra-headers:
          HTTP-Referer: https://github.com/umesh-prabakar-hpp/EmergentTerrain
          X-Title: EmergentTerrain
        excluded-backends: [Venice]    # Venice's free pool is rate-capped globally
```

### Fast-audit tier (Groq-hosted small models)

The pre-professor structural audit (`runQualityAudit`) is role-tagged to a
dedicated `fast` slot — Groq-hosted small models (e.g. `llama-3.1-8b-instant`).
It is `supports-tools: false` / `fast-tier: true` so the `PrimaryModelRegistry`
skips it and the `FastModelRegistry` picks it up. It reuses `GROQ_API_KEY` and
falls back to Ollama when blocked. This slot ships pre-configured under
`app.ai.providers.fast` (`role: fast-audit`) — no setup needed beyond the Groq
key.

### Tool-calling opt-in (Phase 7 E2)

Tool-calling at the professor stage is gated by an opt-in CSV — defaults to
Groq only so non-Groq pools soak before getting agentic traffic:

```yaml
app:
  ai:
    tool-calling:
      providers: "groq,openrouter"   # current default
```

> **Without any primary provider:** Paper narration and panel review fall back
> to Ollama (`gemma3:4b`). The simulation runs but paper conclusions are
> noticeably weaker and tool-call provenance is empty.

---

## 2.5. Token-budget tracking (every active provider must be listed)

The `SimulationGate` pauses scheduled simulation work when the configured
primary providers are collectively close to their daily/minute token caps.
Per Phase 13 E1, the gate uses the **min across metered providers** — it
closes only when *every* metered provider has crossed `pauseThresholdPct`
(default 95%). This is the whole reason the multi-provider cascade exists:
when groq's daily TPD trips, OpenRouter should keep absorbing primary calls
until it too runs out.

**Per-provider TPM/TPD caps now live inline** under each provider's own
`app.ai.providers.<name>.rate` block (merged into `TokenBudgetProperties` at
startup) — there is no separate `app.tokens.budget.providers` map anymore.
Only **metered** generic-pool slots carry a `rate:` block; the `fast` tier and
local Ollama are unmetered (absent `rate:` = unlimited). So adding a `rate:`
block to a provider is what registers it with the gate — a metered slot with
no `rate:` is invisible to the "min across metered" computation.

The thresholds and on/off switch stay top-level under `app.tokens.budget`:

```yaml
app:
  tokens:
    budget:
      enabled: true
      pauseThresholdPct: 95
      softPauseThresholdPct: 85  # tool-loop continuation rounds exit in [soft, hard)
      resumeThresholdPct: 80     # hysteresis floor — gate stays closed until spend drops below this
  ai:
    providers:
      groq:
        # … connection + models …
        rate:
          daily-limit: 270000    # 90% of aggregate Groq daily cap
          minute-limit: 8000     # matches observed TPM rate-limit
      openrouter:
        # … connection + models …
        rate:
          daily-limit: 500000    # gates at ~70% of the per-account request-rate cap on :free routes
          minute-limit: 175000
      # fast + ollama: no rate: block — unmetered (absent = unlimited)
```

**Limits are starting points**; verify against each provider's actual dashboard
and adjust before relying on them.

| Provider   | Where to read live remaining        | Cap unit                           |
|------------|--------------------------------------|-------------------------------------|
| Groq       | console.groq.com → API Keys → Usage  | TPD per model (aggregated by pool) |
| OpenRouter | openrouter.ai/activity               | RPM + requests/day per route       |

> **Cap-unit mismatch note**: OpenRouter enforces in *requests-per-minute* and
> *requests-per-day*, not tokens. The token-based `rate:` block here is a
> proxy — at the observed ~3,500 tokens per primary call you can back-convert
> `daily-limit` to ≈ (calls remaining) × 3,500. The gate's usefulness is
> keeping you below the upstream 429, not exactly matching the upstream cap.

**Heartbeat logging.** `TokenBudgetReporter` prints a one-line-per-provider
summary every `app.tokens.reportIntervalMs` (default 60s):

```
token-budget provider=openrouter gate=OPEN gateReason="open: hottest=groq/day @ 91% (coolest=openrouter/day @ 12%)" {day=12134/500000, dayRemaining=487866, …} pauseLikely=false
```

Use it to watch every metered provider spend in parallel and confirm the
gate stays open as long as at least one provider is below `resumeThresholdPct`.

> **Without a `rate:` block**: a generic pool still *records* its spend to
> `TokenAccountant`, but `SimulationGate` doesn't query it as a metered
> provider — so the data is recorded and discarded. Adding the `rate:` block
> under the provider is what activates per-provider gating.

---

## 2.6. Vault-backed secrets (optional — local encrypted file or external CLI)

Instead of putting provider keys in env vars or `application.yml`, you can keep
**one bootstrap secret** and pull every other key from an encrypted store at
startup. This is the Hermes-style external-secret-source model
(`com.emergentterrain.secrets.*`). It is **OFF by default**; turning it on does
not change any other behaviour.

How it behaves once enabled:
- **Non-destructive** — a fetched key is only applied if that env var isn't already
  set by your shell / `.env` / yaml (existing config always wins). It *does*
  supersede the committed `${GROQ_API_KEY:gsk_…}` literal fallbacks, so enabling
  the source is what retires those committed literals.
- **Fail-open** — a missing file, unset secret, or no network logs one line and
  startup continues with whatever config already existed. The app never blocks
  on the source.

### Recommended: local encrypted file (no CLI / server / network)

Set `app.secrets.source: file` (the default). One bootstrap passphrase unlocks
an AES-256-GCM blob; the encrypted file is safe to commit/sync since it's
useless without the key. The passphrase comes from a **mode-0600 file**
(`app.secrets.file.passphrase-file` — preferred; never exposed via
`/proc/<pid>/environ` the way an env var is) or, failing that, an env var
(`app.secrets.file.passphrase-env`, default `ET_SECRETS_KEY`).

Store the keys named exactly like the env vars the app reads (`GROQ_API_KEY`,
`OPENROUTER_API_KEY`, …) in a JSON object.
Create/rotate the blob with the JDK-only `seal` CLI. To keep the passphrase and
plaintext out of shell history, read the passphrase with `read -rs` into a 0600
file and feed the JSON from a file (not `echo`):

```bash
mvn -q compile
mkdir -p ~/.emergentterrain
( umask 077; read -rsp 'passphrase: ' PP; printf '%s' "$PP" > ~/.emergentterrain/passphrase; unset PP )
java -cp target/classes com.emergentterrain.secrets.EncryptedSecretsFile \
  seal ~/.emergentterrain/secrets.enc --passphrase-file ~/.emergentterrain/passphrase < secrets.json
```

Enable in `application.yml` (under `app.secrets`) or override at launch:

```yaml
app:
  secrets:
    enabled: true
    source: file
    file:
      passphrase-file: ~/.emergentterrain/passphrase   # or export ET_SECRETS_KEY
```

```bash
# or per-run, without editing yaml:
APP_SECRETS_ENABLED=true ./scripts/start.sh
```

Default blob path is `~/.emergentterrain/secrets.enc` (override via
`app.secrets.file.path`). The passphrase is stretched with PBKDF2-HMAC-SHA256
(600k iters) — ~0.5s once at startup; prefer a high-entropy passphrase (e.g.
`openssl rand -base64 32`). Fetched secrets are cached in memory for
`app.secrets.cache-ttl-seconds` (default 300; never written to disk).

> **Other vaults**: set `app.secrets.source: command` and
> `app.secrets.command.exec` to any CLI that prints JSON
> (`{"GROQ_API_KEY":"…"}` or `[{"key":"…","value":"…"}]`) — e.g.
> `["vault","kv","get","-format=json","secret/llm"]`. Same non-destructive,
> fail-open, in-memory-cached behaviour as the file source.

---

## 3. Set up PostgreSQL

Create the database if it doesn't exist:

```bash
psql -U postgres -c "CREATE DATABASE emergentterrain;"
```

The schema is managed by Hibernate (`ddl-auto: update`) — no migration scripts needed.

---

## 4. Start the GPU engine

```bash
cd "$(git rev-parse --show-toplevel)"/engines/gpu-engine
cargo run --release
```

The engine listens on its gRPC port (default 50051). The Spring Boot app connects to it automatically if `app.gpu.enabled=true`.

---

## 4.5. Start the Julia math sidecar (optional)

Phase 22.1 introduced a standalone Julia HTTP service that wraps
Symbolics.jl + Roots.jl + QuadGK.jl for symbolic/numeric math
evaluation. **Opt-in:** EmergentTerrain falls back to its in-process
Symja library when the sidecar is unreachable or
`app.julia.enabled=false` (default until the `JuliaMathClient` Spring
bean lands in Phase 22.2).

**First-time setup.** Install Julia 1.10+ (`curl -fsSL
https://install.julialang.org | sh`, or `dnf install julia` on Fedora),
then resolve + instantiate the package environment:

```bash
cd "$(git rev-parse --show-toplevel)"/services/julia-math-sidecar
julia --project=. -e 'using Pkg; Pkg.resolve(); Pkg.instantiate()'
```

`Pkg.resolve()` reconciles `Project.toml` ↔ `Manifest.toml` when
dependencies have shifted (run after `git pull` if either file
changed). `Pkg.instantiate()` downloads + precompiles. First
instantiation pulls Symbolics.jl + SymbolicRegression.jl + deps
(~300 MB compiled with PySR, ~5–10 minutes on a fresh machine);
subsequent runs reuse the compiled cache and take seconds. Skip
both on routine restarts — needed only after a dep change or a
fresh clone. Restart the sidecar after instantiating so the new
compiled state actually loads.

**Run.**

```bash
cd "$(git rev-parse --show-toplevel)"/services/julia-math-sidecar
./scripts/start.sh
```

Binds 127.0.0.1:8088. Override via env vars:

- `PORT=9000 ./scripts/start.sh` — different port
- `JULIA_NUM_THREADS=4 ./scripts/start.sh` — parallel request handling
- `ET_DISCOVER_EXEC=1 ./scripts/start.sh` — **enable the runtime-discovery
  endpoint** `/discover/validate` (Phase 30 fork 2b).

**`ET_DISCOVER_EXEC` — why and when.** `/discover/validate` is the one endpoint that
*executes LLM-authored Julia* (in an isolated subprocess sandbox) to validate a
candidate method. Because running model-written code is the most dangerous surface
in the system, it is **default-deny**: the handler returns **HTTP 403 — no parsing,
no execution — unless `ET_DISCOVER_EXEC=1`** is in the sidecar's environment. It is
deliberately **not** baked into `start.sh`, so a routine restart leaves it OFF and
you can never leave an RCE-adjacent endpoint running by accident — you opt in **per
launch** (it is not persisted across restarts). This is the sidecar-side gate; it is
one of several that must all align before any code runs (also the app's
`app.research.discovery.runtimeExecEnabled` flag and the per-invariant
`discover_mertens_value` tool gate), and it is the last line — closest to the
dangerous action — so an upstream misconfiguration can't bypass it. The
`discover_mertens_value` tool and the frontier-escalation loop both route through
this endpoint, so they **no-op until it is enabled**. Verify:

```bash
# 200 = enabled, 403 = dormant (default)
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8088/discover/validate \
    -H 'Content-Type: application/json' -d '{"code":"candidate(x)=Int64(0)","checkpoints":[1]}'
```

**Verify.**

```bash
curl localhost:8088/healthz                                # readiness
curl -X POST localhost:8088/eval \
    -H 'Content-Type: application/json' \
    -d '{"expr":"floor(2.04e-6 / 400e-9)"}'                # quick math
```

**Endpoints exposed:** `/healthz`, `/metrics`, `/eval`, `/solve`,
`/nsolve`, `/integrate`, `/sweep`, `/pysr` (phase 24). Each enforces a
safety filter (rejects I/O, package management, FFI, module loading,
reflection) and a per-call timeout (default 5 s, max 30 s for the
`/eval`-family; `/pysr` has its own 120 s ceiling — see phase 24 §24.6).
See `services/julia-math-sidecar/README.md` for the full protocol.

**PyCall endpoints — single-threaded py-sidecar.**
`/prove_identity`, `/recognise/constant`, and `/recognise/minpoly` use PyCall
(SymPy / mpmath), which segfaults under `JULIA_NUM_THREADS>1` (Julia's GC runs
PyCall's `pydecref` finalizer off the GIL-holding thread during a multithreaded
`/pysr` run). They therefore run **only** in a single-threaded companion process;
the main 4-thread sidecar is PyCall-free by construction (it never loads
libpython) and so never serves them.

EmergentTerrain connects to the py-sidecar **directly** — `app.julia.pySidecarUrl`
(default `http://127.0.0.1:8089`), with `PySidecarHealth` probing its `/healthz`
so the prove_identity / recognise tools are only offered when it is actually up.
Enabling these for ET is thus just one extra process (the plain `./scripts/start.sh`
above is unchanged):

```bash
cd "$(git rev-parse --show-toplevel)"/services/julia-math-sidecar
./scripts/start-py-sidecar.sh &                  # JULIA_NUM_THREADS=1, binds :8089
```

Verify it on `:8089` (the port ET actually talks to):

```bash
curl -X POST localhost:8089/prove_identity -H 'Content-Type: application/json' \
    -d '{"summand":"k","var":"k","lower":"0","upper":"n"}'   # → "n*(n + 1)/2", proved (gosper)
```

If the py-sidecar isn't running, ET reports those tools unavailable and falls
back cleanly — no failed round-trips. `PY_SIDECAR_ENABLED=true ./scripts/start.sh`
is **no longer required for ET**; that flag only makes the main `:8088` server
proxy these routes for a non-ET caller still pointed at 8088. Stop:
`./scripts/stop.sh`. Log: `tail -f logs/py-sidecar.log`. Override the port with
`PORT=9089 ./scripts/start-py-sidecar.sh` plus a matching
`app.julia.pySidecarUrl=http://127.0.0.1:9089` in ET config.

**PySR recovery probe (phase 24 §24.5).** Five synthetic physics
fixtures fed to `/pysr` to validate the symbolic-regression engine
end-to-end. Run from the **ET repo** (the probe script lives there,
not in the sidecar repo) with the sidecar already running:

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/pysr-probe/run.sh                                       # default smoke check (~60 s)
./scripts/pysr-probe/run.sh --filter hooke                        # one fixture
./scripts/pysr-probe/run.sh --auto-normalize                      # phase 24.7 Lever 1
./scripts/pysr-probe/run.sh --auto-retry                          # phase 24.7 Lever 3 (recommended)
./scripts/pysr-probe/run.sh \
  --iterations 160 --populations 24 --samples-per-dim 8 --timeout-ms 90000   # deep search
```

Default config (populations=16, iterations=80) runs in ~60 s total
and exercises every fixture under the JIT-warm path. The deep variant
exercises the new `MAX_PYSR_TIMEOUT_MS = 120 000` ceiling — useful
after a sidecar code change to confirm the search isn't getting
HTTP-408'd at 30 s.

`--auto-normalize` (phase 24.7 Lever 1) flips the request body's
`autoNormalize` field to `true`, so each fixture's inputs and target
are rescaled to unit-ish magnitudes before search and the returned
Pareto entries are rewritten back to original scale. Switches the
pass criterion from per-fixture absolute MSE to NRMSE (sqrt(mse)/RMS(y) < 1e-3)
since absolute MSE thresholds are meaningless on SI-magnitude
targets where a float-perfect fit still has mse ~ y² × ε².

`--auto-retry` (phase 24.7 Lever 3, **recommended production config**)
flips the request body's `autoRetry` field. The sidecar runs one
legacy-path pass, checks the best Pareto entry's NMSE, and if it
exceeds 1e-3 ("essentially wrong shape") runs a second pass with
autoNormalize=true and merges fronts. Clean fits pay zero retry
latency; stuck fits get the rescaling rescue. With `--auto-retry`
the probe clears 5/5 — newton-gravity (via retry), coulomb (legacy),
rc-decay (via retry — rescaling reshapes the GP landscape enough to
escape the linear basin), arrhenius (via retry — same correction),
hooke (legacy).

Without either flag, the probe runs against the legacy per-fixture
absolute MSE thresholds and the baseline is 3/5 — but two of those
"passes" (rc-decay, arrhenius) are coincidental small-magnitude
fits, not structurally-correct expressions. The NRMSE-1e-3 criterion
under `--auto-retry` is the honest accuracy metric.

**Production deployment:** see
`julia-math-sidecar/systemd/julia-math-sidecar.service` for a starter
systemd unit with hardened directives (read-only filesystem,
`NoNewPrivileges`, memory cap, syscall filter, localhost-only). If
prove_identity / recognise are in scope, the PyCall py-sidecar is a
**separate** long-running process (`start-py-sidecar.sh`,
`JULIA_NUM_THREADS=1`, `:8089`) and needs its own unit alongside the main one —
there is no py-sidecar systemd unit yet, so add one (a copy of the main unit with
`ExecStart=…/scripts/start-py-sidecar.sh` and `Environment=JULIA_NUM_THREADS=1`).
Point ET at it with `app.julia.pySidecarUrl` if it isn't on the default
`127.0.0.1:8089`.

**When EmergentTerrain uses it:** `app.julia.enabled=true` (default
on as of phase 22.2) routes `/eval`, `/solve`, `/sweep`, etc. through
the sidecar with Symja as the in-process fallback. `app.julia.pysr.enabled=true`
(flipped at the end of 2026-05-22) additionally wires the
`symbolic_regress` LLM tool through `/pysr`. Set either flag to
`false` in `application.yml` to revert to the JVM-only path.

---

## 5. Start the Spring Boot app

All config has sensible defaults in `application.yml` (including the Ollama
hostnames `localhost,192.168.1.145`, the primary-provider API-key env-var
references, and Phase 9's selection-test toggle). Just run:

```bash
cd "$(git rev-parse --show-toplevel)"
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk   # once per shell on Fedora 44 (no JDK 21 package)
mvn -f apps/game-service/pom.xml clean spring-boot:run
```

> If you skip the `JAVA_HOME` export, Maven aborts with `The JAVA_HOME
> environment variable is not defined correctly`, even though `java -version`
> works (the `mvn` script reads `JAVA_HOME` directly rather than walking the
> PATH symlink). The pom targets `release 21` so JDK 25 cross-compiles cleanly;
> the existing Mockito stack caps at Java 23, so test runs additionally need
> `-Dnet.bytebuddy.experimental=true` until Byte Buddy is bumped in the pom.

Or build and run the jar:

```bash
cd "$(git rev-parse --show-toplevel)"
mvn -f apps/game-service/pom.xml clean package -DskipTests
java -jar apps/game-service/target/game-service-0.0.1-SNAPSHOT-boot.jar
```

The app starts on **http://localhost:8081**.

### Overriding config from the command line

Every value in `application.yml` is overrideable via env var when the YAML
uses `${VAR:default}`. Env vars are **overrides, not requirements** — only
prefix them when you need a value different from the configured default.

```bash
# Use different Ollama hosts than the configured default
OLLAMA_HOSTS=10.0.0.5,10.0.0.6 java -jar apps/game-service/target/game-service-0.0.1-SNAPSHOT-boot.jar

# Use a different Groq API key
GROQ_API_KEY=gsk_... java -jar apps/game-service/target/game-service-0.0.1-SNAPSHOT-boot.jar

# Skip Phase 9 selection tests (useful for dev iteration; default is on)
java -jar apps/game-service/target/game-service-0.0.1-SNAPSHOT-boot.jar \
  --app.ai.selection-tests.enabled=false
```

On startup you will see (Phase 7/8/9 — multi-provider + selection-tested):

```
GenericOpenAICompatPool[groq]: activeModels=[llama-3.3-70b-versatile, openai/gpt-oss-120b] supports-tools=true selection-tests=on
GenericOpenAICompatPool[openrouter]: models=[openai/gpt-oss-120b:free] excludedBackends=[Venice] supports-tools=true selection-tests=on
GenericOpenAICompatPool[fast]: models=[llama-3.1-8b-instant, allam-2-7b, meta-llama/llama-4-scout-17b-16e-instruct] fast-tier=true role=fast-audit supports-tools=false selection-tests=on
OllamaPool: models=[gemma3:4b] parallelism=3 selection-tests=on

groq: selection-test model=llama-3.3-70b-versatile PASSED chat=412ms json=836ms tools=1241ms
groq: selection-test model=openai/gpt-oss-120b PASSED chat=523ms json=1108ms tools=2017ms
groq: selection-tests complete passed=2/2 models=[llama-3.3-70b-versatile, openai/gpt-oss-120b]
... (similar lines per provider) ...

PrimaryModelRegistry: resolved=3 handles=[groq/llama-3.3-70b-versatile, groq/openai/gpt-oss-120b, openrouter/openai/gpt-oss-120b:free]
FastModelRegistry: resolved=4 handles=[fast/llama-3.1-8b-instant, fast/allam-2-7b, fast/meta-llama/llama-4-scout-17b-16e-instruct, ollama/gemma3:4b]
ReviewPanelService: enabled=true mode=sequential providers=[groq, openrouter] panelSize=4 registryHandles=3
LlmRouter: primaryChain=[groq, openrouter] fast=[fast, ollama] roles=[fast-audit→fast] toolProviders=[groq, openrouter] primaryHandles=3 fastHandles=4
```

Each Ollama backend must have `OLLAMA_NUM_PARALLEL=3` and the configured
models pulled. The app round-robins across healthy Ollama backends and
retries unhealthy ones every 30 seconds.

---

## Environment variables

All have sensible defaults baked into `application.yml`. **None are required**
at the command line — set them only when you want a value different from the
configured default.

| Variable | Default | Description |
|---|---|---|
| `PG_URL` | `jdbc:postgresql://localhost:5432/emergentterrain` | PostgreSQL JDBC URL |
| `PG_USER` | `postgres` | Database username |
| `PG_PASSWORD` | `NewStrongPasswordHere` | Database password |
| `GROQ_API_KEY` | *(read via `api-key-env` in `application.yml`)* | Groq API key — used by the `groq` + `fast` provider slots |
| `OPENROUTER_API_KEY` | *(read via `api-key-env`)* | OpenRouter API key — Phase 7 E1.2 |
| `ET_SECRETS_KEY` | *(none)* | Decryption passphrase for the local encrypted-file source — the ONE bootstrap secret when `app.secrets.source=file` (see §2.6) |
| `OLLAMA_HOSTS` | `localhost,192.168.1.145` | Comma-separated Ollama hostnames (fast pool) |
| `OLLAMA_PORT` | `11434` | Ollama daemon port |

> **Deprecated** (Phase 9 U4): `OLLAMA_MODEL` / `spring.ai.ollama.chat.model`
> still read as fallback with a `@PostConstruct` deprecation WARN. Migrate
> to `app.ai.ollama.preferredModels` (CSV of model names).

Ports and per-backend parallelism are set in `application.yml` under
`app.ai.ollama.*` (local fast pool) and `app.ai.providers.<name>.*` (primary +
fast-audit tiers).
Selection-test behaviour is governed by `app.ai.selection-tests.enabled`
(default `true`).

---

## Startup order

```
1. PostgreSQL
2. ollama serve          (optional — fast LLM pool; one or more machines)
3. GPU engine (cargo)    (optional — terrain simulation)
4. Spring Boot app
```

Steps 2 and 3 can be started in any order relative to each other; the Spring Boot app connects to both lazily.

---

## LLM features

When `app.ai.enabled=true` (the default), the following pipelines are active,
routed across **two model-centric registries** (Phase 9 U2 / U6):

- **`PrimaryModelRegistry`** — aggregates every 70B+ tool-capable model across
  Groq + OpenRouter into one flat list. `LlmRouter.callPrimary`
  and `ReviewPanelService` walk it sequentially; a single primary call falls
  through every model across providers before throwing.
- **`FastModelRegistry`** — aggregates Ollama's configured models (and any
  future sub-70B provider). `LlmRouter.callFast` routes through it.

**Primary registry stages:**
- **Paper narration** — once computations are complete, the LLM writes hypothesis, findings, and conclusion for the paper, citing prior work and flagging anomalies. The model name is stamped on each paper as **AI Narrator**.
- **Professor tool-calling** — Phase 7 + 8. The professor receives a suite of
  agentic tools it can invoke up to 5 times before writing its synthesis:
  - `run_param_sweep` — re-run the paper's own computation at different parameters
  - `run_predicate_scan` — number-theoretic predicate density (Mathematics / Interdisciplinary topics)
  - `evaluate_symbolic` — evaluate f(n) over a range with statistics
  - `run_sandboxed_code` — arbitrary pseudocode (reflective `Math.*` + SI constants + Phase 8 P1 number-theory helpers + Phase 8 P2 user-defined functions; safety-reviewed)
  - `run_symbolic` — Symja-backed integral / derivative / simplification / series verification (Phase 8 P3)
  - `run_gpu_eval` — formula `f(n)` over up to 10M values on a GPU (Phase 8 P4; aggregates mean/std/min/max/sum/finite_count)
  - In-sandbox bridges (Phase 8 P5): `integrate(expr, var, lo, hi)`, `differentiate(expr, var, at)`, `simplify(expr)` callable from within `run_sandboxed_code`
- **Student revision** — after professor synthesis, a separate primary call revises the paper incorporating the experimental evidence. The full tool-call trace (Phase 7 F5.4) persists as `panel_outcome` / `tool_call_trace` JSON columns and renders as a `## Methods` appendix in the published markdown.
- **Multi-reviewer panel review** — Phase 7 F7.2. After revision, the panel runs N reviewers sequentially (configurable via `app.research.review-panel.size`, default 4) against the synthesis + tool-call trace. Each reviewer is one `ModelHandle` from `PrimaryModelRegistry.availableModels()`. Each returns structured JSON grades (honesty / completeness / rigor / novelty); aggregation is deterministic (median grades, intersection of cited_calls, contradictions flagged by ≥2 reviewers, `reviewer_agreement` = 1 − mean stddev across axes). Renders as `## Panel Review` markdown section.
- **Genome mentoring** — when a bot publishes high-scoring work, it earns a mentoring session that adjusts its research genome and may redirect its parameter search region. The mentor prompt includes cross-domain anomalous findings from other bots.

**Fast registry stages:**
- **Novelty check** — before formulation, when ≥3 prior papers exist in the topic, a fast call assesses how saturated the topic is (score 0–1).
- **Research formulation** — the LLM chooses specific parameter values and writes a pre-registered hypothesis+prediction+falsification condition locked before computation runs.
- **Statistical framing** — after refinement and before narration, a fast call rewrites the raw computational findings with appropriate significant figures (≤3), uncertainty qualifiers, and scientific notation.
- **Multi-step computation** — after the primary result, the LLM decides whether a focused follow-up computation adds value. Up to `app.research.max-computations` independent computations (currently `5` per Phase 7 F4) run before narration.
- **Audit:quality** — Phase 7. Pre-professor structural audit. Role-tagged to the `fast` tier (Groq-hosted small models) with Ollama fallback.
- **Government digest, Pattern explanation** — unchanged.

**Selection tests at startup (Phase 9 U5):**
Every pool runs three probes against each configured model before adding it to
`activeModels`:
1. Chat — "Reply with the single word OK." Pass: response contains `OK`.
2. JSON-mode — "Return JSON with name=Euler, value=2.71828, good=true." Pass: response parses with the three expected fields.
3. Tool-call (tool-capable pools only) — "Call get_value with key=pi." Pass: trace contains a call to `get_value` with `key=pi`.

Latency is logged per-probe but **never used as a drop signal** — slow Ollama
models pass if they eventually respond. Disable with
`app.ai.selection-tests.enabled: false` (e.g. in unit tests).

**Scientific integrity pipeline (built into research cycle, no extra LLM call):**
- **Deterministic classification** — after computation, `classifyResult()` assigns SUPPORTED / PARTIALLY_SUPPORTED / NULL_RESULT / DEGENERATE.
- **Degenerate rejection** — non-finite values, all-zero sentinels, or non-positive author scores are silently discarded.
- **Null result honesty** — NULL_RESULT papers are stored with status `NULL_RESULT`, no confidence boost, with a null-result banner in markdown.
- **F5.5 Check C** — Phase 7. Numerical-consistency validator now spans the
  tool-call trace: any "salient" number in findings (≥2 decimal digits or
  scientific notation) must appear in hypothesis ∪ conclusion ∪ any tool result
  within ±1%.
- **F7.3 cherry-picked evidence** — when ≥2 panel reviewers flag the same
  omitted-relevant tool call, the `cherry_picked_evidence` audit-gap fires.
  Advisory by default; flip `app.research.audit-cherry-picked-rejects.enabled`
  after measuring FP rate over ≥30 papers.

**LLM calls per paper (typical case, Phase 9):**

| Step | Tier | When |
|---|---|---|
| Novelty check | Fast | Before formulation, if ≥3 topic papers exist |
| Formulation | Fast | Before computation |
| Refinement decision(s) | Fast | 0–4 calls between compute and narration (matches `max-computations: 5`) |
| Statistical framing | Fast | After refinement, before narration |
| Narration (first pass) | Primary | Once per paper |
| Audit:quality | Fast (fast-audit tier) | Before professor stage |
| Professor critique + tools | Primary (tool-calling) | After first-pass narration; up to 5 tool rounds |
| Tool execution (sandbox/symbolic/GPU) | (deterministic, no LLM) | Per tool the professor invokes |
| Revision (student) | Primary | After professor synthesis |
| **Panel review** | Primary (× N) | After revision, sequential; default N=4 reviewers |
| Genome mentoring | Primary | Once per high-scoring paper (cooldown ~50 min) |

Routing rationale unchanged: tier separation is by task quality requirement,
not by provider. The primary tier spans Groq + OpenRouter so a single rate-limit
on one provider doesn't block the pipeline; the panel further diversifies by
sampling N distinct models per paper.

To disable LLM features entirely:

```yaml
# application.yml
app:
  ai:
    enabled: false
```

To disable a single primary provider (e.g. drop Groq and let OpenRouter +
Ollama carry the load), remove or comment out its entry under
`app.ai.providers`:

```yaml
app:
  ai:
    providers:
      # groq:        # commented out → pool not registered
      #   base-url: https://api.groq.com/openai/v1
      #   ...
```

---

## Logs

```bash
tail -f logs/et-app.log
# Previous runs at logs/archive/et-app-YYYYMMDD-HHMMSS.log

# Tail EVERYTHING at once — app + maven stdout + sidecar + py-sidecar + GPU engine.
# tail -F follows by name, so it tolerates not-yet-created / rotated files:
cd "$(git rev-parse --show-toplevel)"
tail -F \
  logs/et-app.log \
  logs/maven-stdout.log \
  services/julia-math-sidecar/logs/sidecar.log \
  services/julia-math-sidecar/logs/py-sidecar.log \
  engines/gpu-engine/logs/gpu-engine.log
```

LLM generation failures log at WARN level and do not affect game stability. Backend
health events (marked unhealthy / recovered) log at WARN/INFO from `OllamaPool` and
each `GenericOpenAICompatPool` (the primary/fast pools).

---

## Reviewing published papers (Claude Code skill)

A `paper-review` skill is installed at `~/.claude/skills/paper-review/SKILL.md`. It
audits any directory of LLM-generated paper markdown files for human quality and
maintains a single persistent `REVIEW_REPORT.md` that grows incrementally with each
run — only papers not already listed in the per-paper table are re-read.

**One-shot review** — open Claude Code in this repo and run:

```
/paper-review research/<room-id>
```

Defaults: paper pattern `P-*.md`, report at `<dir>/REVIEW_REPORT.md`, no log section.

**With operational-log correlation** — point at the live application log to surface
WARN/ERROR root causes and map every `prof:error→fp` paper to its upstream Groq 429:

```
/paper-review research/<room-id> --logs logs/et-app.log
```

**Periodic refresh** — pair with the `loop` skill:

```
/loop 15m /paper-review research/<room-id> --logs logs/et-app.log
```

Copy-pastable example used against room `a3289005` on a 40-minute cadence:

```
/loop 40m /paper-review research/a3289005 --logs logs/et-app.log
```

> Note: 40 doesn't divide 60 cleanly, so the `loop` skill rounds the interval
> to `30m` (cron `*/30 * * * *`). Use a clean divisor of 60 — `15m`, `20m`,
> `30m` — to get exact-cadence firing.

The cron job lives only for the current Claude Code session (auto-expires after 7 days).
For a durable cloud-based loop, use `/schedule` instead.

**What the report contains** (sections, in order):

1. Recommendations to raise the batch to human quality (numbered, growing list).
2. Strongest papers — ranked top 10–15.
3. Worst papers — clustered by failure mode.
4. Per-paper rating table — one row per paper, monotonically appended.
5. Batch summary — counts, acceptance rate, top peer score, trajectory table.
6. Quality and novelty assessment — refreshed every cycle.
7. Operational logs — only when `--logs` is provided. Lists WARN/ERROR root-cause
   distribution and correlates to specific paper IDs (e.g. which `prof:error→fp`
   paper IDs hit Groq token-per-day quotas).

**Skill arguments:**

| Arg | Default | Notes |
|---|---|---|
| `<dir>` | required | Directory containing the paper markdown files. |
| `--report <path>` | `<dir>/REVIEW_REPORT.md` | Where to write the persistent report. |
| `--pattern <glob>` | `P-*.md` | Override if your paper-ID convention differs. |
| `--logs <path>` | none | If given, parse this log file's current run only. |

> **Note:** Skills are loaded at Claude Code session start. If you just installed
> `paper-review` in an existing session, restart `claude` for `/paper-review` to
> appear in the slash-command list.

---

## Reviewing phase-doc pending items (Claude Code skill)

A `phase-review` skill is installed at `~/.claude/skills/phase-review/SKILL.md`. It
walks `phases-for-human-like-performance/` (or any phase-doc folder) and prints a
categorized list of genuinely-open items — cross-checking every unchecked box
against current code state so "DONE despite unchecked" items are not reported as
pending. As of 2026-05-20 it also **archives completed docs** (`git mv` to
`archive/`) when the doc's closing condition is fully met. Pass `--no-archive`
to suppress the archive step.

**One-shot review + archive** — open Claude Code in this repo and run:

```
/phase-review
```

Defaults: folder `phases-for-human-like-performance/`, terminal output only,
archive step enabled.

**Override the folder** (e.g. for a different project layout):

```
/phase-review docs/phases
```

**Write a markdown artifact** alongside the terminal output:

```
/phase-review --out PENDING_REVIEW.md
```

**Audit-only mode** (no `git mv`):

```
/phase-review --no-archive
```

**Periodic refresh** — pair with the `loop` skill:

```
/loop 1d /phase-review
```

Use `/loop 1d /phase-review --no-archive` if you want daily summaries but
only want archive moves to happen during a focused human-driven session.

**Output buckets** (in order):

1. **Code lanes still open** — work that requires a code change.
2. **Flag flips queued** — `enabled` / `mode` flips waiting on a named trigger
   (e.g. the Tier 6 `unsourced-claim.mode: warn → downgrade` flip in
   `phase-flags.md`).
3. **Live-run acceptance pending** — code shipped, acceptance criterion is
   observable only after the next REVIEW_REPORT or log refresh.
4. **Operational follow-ups** — mechanical PRs gated on burn-in (e.g. the Phase
   20.11 step-2b deletion checklists).
5. **Deferred by design** — items with an explicit named trigger ("if a second
   example appears within N refreshes, promote").
6. **Doc hygiene** — status-vs-body inconsistencies caught during the walk
   (status lines that contradict the body, etc.). Docs marked
   `CLOSED PENDING REFRESH` no longer auto-flag here — they're correctly
   considered awaiting an external trigger.
7. **Archived this run** — docs the skill moved to `archive/` because their
   Status is terminal (`CLOSED` / `SHIPPED` / `COMPLETE`) AND every condition
   in their `Closing condition` section is marked done. The `git mv` is staged
   but NOT committed — review and commit in a single follow-up.
8. **Skipped for archive** — docs that *almost* qualified for archive but are
   blocked by an unmet closing condition or recent unrelated activity. Fix
   the noted hygiene issue and the next run will pick them up.

**Archive eligibility — hard rules:**

- Status line must contain `CLOSED` (without `PENDING`), `SHIPPED`, `COMPLETE`,
  `LANDED`, or `MOVED TO ARCHIVE`.
- Every `Closing condition` / `Acceptance` / `Closes when` item must be marked
  ✅ / DONE / MET. Any ⏳ / ❌ / "pending" / "operator action" / "awaits"
  blocks the move.
- Always-live docs are exempt: `STATUS.md`, `README.md` / `readme.md`,
  `INDEX.md`, `phase-flags.md`, any `phase-wishlist*.md`, any doc whose
  filename contains `tracking` / `index` / `status`.
- The working tree must be clean inside `<phase_dir>/`. If you have unstaged
  edits, the skill skips the archive step and reports the situation.
- No more than 3 archives per single run without explicit confirmation. If
  4+ docs qualify, the skill lists them and asks before moving.

**Skill arguments:**

| Arg | Default | Notes |
|---|---|---|
| `<phase_dir>` | `phases-for-human-like-performance/` | Override if the project uses a different folder. |
| `--out <path>` | none | If given, write the bucket tables to this markdown file in addition to terminal output. |
| `--no-archive` | (archive enabled) | Skip step 5 (archive moves). Audit-only mode. |

> Archive moves are staged via `git mv` but never committed by the skill.
> Always review the rename diffs and commit in a single explicit commit so
> the move history is clean.

---

## Switching models

**Groq models** — edit the operator-curated `models` list under the provider
entry (pre-ranked, highest-quality first):

```yaml
app:
  ai:
    providers:
      groq:
        models:
          - llama-3.3-70b-versatile
          - openai/gpt-oss-120b
```

**OpenRouter (+ any future provider)** — same `models` list under the
provider's `app.ai.providers.<name>` entry. Each generic pool resolves its own
model list independently; the `PrimaryModelRegistry` aggregates the
tool-capable ones into one flat view at startup. (The old `preferredModels`
named-block knob is gone — every primary/fast provider now uses the inline
`models:` list.)

**Ollama models** — Phase 9 U4. Pull on every Ollama backend, then update the
new unified config:

```bash
ollama pull qwen2:7b
```

```yaml
app:
  ai:
    ollama:
      preferredModels: "gemma3:4b,qwen2:7b"   # CSV; one Backend per (host × model)
```

The legacy `spring.ai.ollama.chat.model` key still works as fallback but logs
a deprecation WARN at startup.

Restart the Spring Boot app for any model change to take effect — selection
tests re-run against the new candidates.

---

## Startup checks

After restart, the first 5–60 seconds of `logs/et-app.log` carry the highest-
signal information about what actually came up. Key things to grep for:

```bash
# Did selection tests pass on every model?
grep -E "selection-test " logs/et-app.log | head -50

# Did any model fail a probe?
grep -E "selection-test.*FAILED" logs/et-app.log

# Per-pool summary
grep -E "selection-tests complete" logs/et-app.log

# Final registry composition (primary + fast handles)
grep -E "(PrimaryModel|FastModel|LlmRouter|ReviewPanelService): " logs/et-app.log

# Phase 9 U4 — deprecation warnings for legacy Ollama config keys
grep -E "OllamaPool: legacy" logs/et-app.log
```

**What you want to see:**
- `selection-test model=X PASSED chat=Yms json=Zms tools=Wms` for each configured tool-capable model
- `selection-test model=X PASSED chat=Yms json=Zms` for each Ollama model
- `selection-tests complete passed=N/M models=[...]` per pool — `N==M` ideal; `N<M` means some dropped (check the FAILED lines to see why)
- `PrimaryModelRegistry: resolved=N handles=[...]` — registry-aggregated 70B+ models (3 today: groq ×2 + openrouter ×1)
- `FastModelRegistry: resolved=M handles=[...]` — sub-70B + fast-audit models
- `LlmRouter: ... primaryHandles=N fastHandles=M` — the router sees both registries

**Common failures and what they mean:**
- `selection-test model=X FAILED reason=chat-probe threw: 401 Unauthorized` — bad API key for that provider
- `selection-test model=X FAILED reason=json-probe missing or wrong 'name'` — the model doesn't reliably follow JSON instructions; consider dropping it from `preferredModels`
- `selection-test model=X FAILED reason=tool-probe trace was empty` — the model didn't invoke the probe tool; the provider may not actually support OpenAI-style `tools=[…]` for this model
- `OllamaPool: legacy config keys detected — please migrate to app.ai.ollama.*: [spring.ai.ollama.chat.model, ...]` — update your `application.yml` to the new keys per Phase 9 U4
- `OllamaPool: selection tests failed for all candidates ...` — Ollama isn't reachable or the configured models aren't pulled. Pool keeps unverified candidates so the periodic health probe can recover.

If everything passed, the first published paper after restart should show
`panel:n=4:agreement=0.NN` in its `llmCallPath` and a `## Panel Review`
section in the published markdown. That confirms the F5/F7 + Phase 9 wiring
is fully active.
