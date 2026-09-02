# Run EmergentTerrain

This guide starts the project using its supported launchers. Start the Julia sidecars before the
web app when using the standard math and grounding integrations. The GPU engine, LLM proxies, and
literature indexes are only needed for their respective features.

For a command-only reference, see [RUN-MINIMAL.md](RUN-MINIMAL.md). For scope, safety boundaries,
and the repository map, start with the [README](README.md).

Clone the repository into any directory and enter it before using this guide. Every command below
is relative to that checkout; no fixed workspace layout is required.

## 1. Prerequisites

Required for the main services:

- Java 21
- Maven
- PostgreSQL
- `curl`, `psql`, and `pg_isready` for the status check

Required only for optional components:

- Rust/Cargo, `protoc`, and Vulkan userspace for the GPU engine
- Julia for the math sidecars
- Node.js for the optional local AI gateway

On Fedora, the bootstrap helper installs the local prerequisites and initializes the development
PostgreSQL databases:

```bash
cd "$(git rev-parse --show-toplevel)"
bash ./scripts/setup-fedora-prereqs.sh
```

Fedora's native repositories do not provide the supported Java 21 package. Install Temurin 21 as
described in [AGENTS.md](AGENTS.md), then select it for this shell:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export PATH="$JAVA_HOME/bin:$PATH"
```

After a fresh checkout, restore execute permissions if needed:

```bash
chmod +x scripts/*.sh scripts/*.py
chmod +x engines/gpu-engine/scripts/*.sh
chmod +x services/julia-math-sidecar/scripts/*.sh
```

Check the local dependencies before starting anything. A down optional service is expected at this
point, so the command deliberately permits a nonzero status:

```bash
./scripts/status-related-apps.sh || true
```

### Runtime data location

The checkout keeps generated runtime data in `.runtime/`: disposable test artifacts, durable
simulation/research history, and root application logs. To keep durable data outside the checkout,
set an absolute runtime root before invoking a launcher:

```bash
export ET_RUNTIME_DIR=/var/lib/emergentterrain
```

`ET_RUNTIME_HISTORY_DIR`, `ET_RUNTIME_LOG_DIR`, and `ET_TEST_ARTIFACTS_DIR` can override the
individual subdirectories when needed.

### Encrypted provider secrets

The current game-service configuration enables the local encrypted secret store. If a configured
remote provider needs credentials, create the encrypted store once before starting the app. Keep the
plaintext JSON out of Git and delete it after sealing it.

```bash
mkdir -p ~/.emergentterrain
chmod 700 ~/.emergentterrain
$EDITOR ~/.emergentterrain/secrets.json
```

Use a JSON object whose keys match provider environment-variable names, such as
`{"OPENROUTER_API_KEY":"..."}`. Then seal it with a passphrase stored in a mode-0600 file:

```bash
( umask 077; openssl rand -base64 32 | tr -d '\n' > ~/.emergentterrain/passphrase )
mvn -q -f apps/game-service/pom.xml compile
java -cp apps/game-service/target/classes \
  com.emergentterrain.secrets.EncryptedSecretsFile \
  seal ~/.emergentterrain/secrets.enc \
  --passphrase-file ~/.emergentterrain/passphrase \
  < ~/.emergentterrain/secrets.json
rm ~/.emergentterrain/secrets.json
```

The app reads the sealed file automatically. A shell-exported secret still takes precedence. To run
without the encrypted store, explicitly set `APP_SECRETS_ENABLED=false` for that launch.

## 2. Download papers and build the literature index

Download the theorem-search corpus:

```bash
./scripts/bootstrap-hf-math-literature.sh
```

For full-text papers, download the complementary corpus:

```bash
HF_DATASET=marin-community/ar5iv-no-problem-markdown \
  ./scripts/bootstrap-hf-math-literature.sh
```

Build the local SQLite/FTS5 index:

```bash
python3 -m pip install --user pyarrow

./scripts/prepare-hf-math-index.py --stage metadata
./scripts/prepare-hf-math-index.py --stage theorems
./scripts/prepare-hf-math-index.py --stage fulltext --fulltext-shard-limit 20
```

After a complete immutable import, optimize the index:

```bash
./scripts/prepare-hf-math-index.py --stage optimize
```

The index is stored under `~/.emergentterrain/indexes/math-literature/`.

## 3. Start the Julia sidecars

Start each sidecar in its own terminal. Start the main Julia sidecar first, then start the
single-threaded PyCall/grounding companion in a second terminal.

On a fresh clone, install Julia and initialize the sidecar environment once before starting it:

```bash
cd services/julia-math-sidecar
julia --project=. -e 'using Pkg; Pkg.instantiate()'
```

Routine restarts do not need that command. See
[services/julia-math-sidecar/README.md](services/julia-math-sidecar/README.md) for supported Julia
installation methods.

### Julia math sidecar

```bash
JULIA_NUM_THREADS=4 ET_DISCOVER_EXEC=1 \
./services/julia-math-sidecar/scripts/start.sh
```

This listens on `127.0.0.1:8088`. `ET_DISCOVER_EXEC=1` enables opt-in discovery execution; omit it
when that capability is not needed. The `/discover/validate` endpoint executes model-authored Julia
only when this environment flag, `app.research.discovery.runtimeExecEnabled`, and the relevant
per-invariant tool gate are all enabled. Keep it off for normal operation.

In another terminal, follow its log:

```bash
tail -F services/julia-math-sidecar/logs/sidecar.log
```

Stop both Julia sidecars with:

```bash
./services/julia-math-sidecar/scripts/stop.sh
```

### PyCall and literature-grounding sidecar

After the main Julia sidecar is listening, run this in a second terminal:

```bash
ET_GROUND_SUBJECTS=mathematics,msc:11 \
./services/julia-math-sidecar/scripts/start-py-sidecar.sh
```

This single-threaded companion listens on `127.0.0.1:8089`. The subject gate is optional; omit
`ET_GROUND_SUBJECTS` to leave it disabled.

In another terminal, follow its log:

```bash
tail -F services/julia-math-sidecar/logs/py-sidecar.log
```

Stop both Julia sidecars with:

```bash
./services/julia-math-sidecar/scripts/stop.sh
```

## 4. Run research without the game

The research worker serves API v1 on port `8082`, requires PostgreSQL, and runs in the current
terminal. It does not start the game UI, WebSocket stack, GPU engine, or subscription proxies.

### Safe migration audit

This mode imports and checkpoints lifecycle context, then records read-only parity observations. It
does not call the legacy university workflow or write game state.

```bash
APP_UNIVERSITY_ENABLED=false \
RESEARCH_LIFECYCLE_SHADOW_ENABLED=true \
RESEARCH_LIFECYCLE_SHADOW_ONLY=true \
./scripts/start-research-service.sh
```

### University research workflow

This transition-period path can use the configured LLM provider chain and write research artifacts.
Use it only when that is intended; it is not the safe audit path above.

`start-research-service.sh` deliberately does not start the optional local AI gateway. If university
research should use that provider, start it in a separate terminal first. When the sibling external
gateway copy exists, prefer the tested local-CLI gateway:

```bash
cd "$(dirname "$(git rev-parse --show-toplevel)")/ai-proxy/local-ai-gateway"
node server.mjs
```

Otherwise, use the portable in-repository adapter with a command that reads the prompt from standard
input and writes the response to standard output:

```bash
LOCAL_AI_GATEWAY_COMMAND=/absolute/path/to/local-ai-adapter \
node scripts/local-ai-gateway/server.mjs
```

Leave that terminal running, then start the worker in another terminal:

```bash
APP_UNIVERSITY_ENABLED=true \
RESEARCH_LIFECYCLE_SHADOW_ONLY=false \
./scripts/start-research-service.sh
```

The gateway is not required when another configured provider is available; the provider chain can
fall back to it. The safe migration-audit mode does not invoke university LLM work and needs neither.

Stop the standalone worker and gateway independently:

```bash
./scripts/stop-research-service.sh
./scripts/stop-local-ai-gateway.sh
```

In another terminal, verify the worker:

```bash
./scripts/status-related-apps.sh || true
```

Use `Ctrl-C` in the worker terminal to stop it. See
[apps/research-service/README.md](apps/research-service/README.md) for remote game-context mode,
API authentication, durable work intake, and Phase 42 ownership boundaries.

## 5. Additional services

Start each service in its own terminal when the feature you are testing requires it.

### Rust GPU engine

```bash
./engines/gpu-engine/scripts/start.sh
```

It listens on `127.0.0.1:50051` by default. Use `--foreground` while debugging or `--debug` for a
debug Cargo build. Stop it with:

```bash
./engines/gpu-engine/scripts/stop.sh
```

## 6. Run the web app

From the repository root:

```bash
./scripts/start.sh
```

The launcher stops a previous managed app/gateway set, starts the local AI gateway when Node.js and
`LOCAL_AI_GATEWAY_COMMAND` are configured, performs a clean Maven build, and starts the game on
<http://127.0.0.1:8081/>. The gateway is optional: set
`ET_DISABLE_LOCAL_AI_GATEWAY=1` to skip it.

For foreground debugging, use `./scripts/start.sh --foreground`.

## 7. Status and logs

Always use the read-only status script to determine runtime state:

```bash
./scripts/status-related-apps.sh
```

It checks PostgreSQL with an authenticated query and reports the game, research service, local
proxies, Julia sidecars, and GPU listener. It prints each detected service's PID and command plus a
preferred start command when absent. It may exit nonzero when a service is down; use `|| true` when
inspecting a partial runtime.

To follow all usual logs at once:

```bash
tail -F \
  .runtime/logs/et-app.log \
  .runtime/logs/maven-stdout.log \
  .runtime/logs/local-ai-gateway.log \
  services/julia-math-sidecar/logs/sidecar.log \
  services/julia-math-sidecar/logs/py-sidecar.log \
  engines/gpu-engine/logs/gpu-engine.log
```

`tail -F` tolerates services that have not yet created their log file and continues across log
rotation.

When finished, stop the managed web app and its managed proxies:

```bash
./scripts/stop.sh
```

## 8. Build and test

Run Java checks from the repository root:

```bash
mvn -f apps/game-service/pom.xml -DskipTests package
mvn -f apps/game-service/pom.xml test -DtrimStackTrace=false
```

Run Rust checks when changing the GPU engine:

```bash
cargo test --manifest-path engines/gpu-engine/Cargo.toml
```

For the real two-process GPU smoke test and packaged performance comparison, see
[AGENTS.md](AGENTS.md). Do not use `spring-boot:run` or `cargo run` as substitutes for the
documented release-performance workflow.

## 9. Review workflows

For literature provenance, licensing, and acceptance requirements, see [CORPUS.md](CORPUS.md) and
the relevant phase documentation. Do not commit downloaded corpora, generated papers, logs, or
runtime output.

For maintainers using the local Codex skills, the paper-review and phase-review workflows are
documented in `skills/paper-review/` and `skills/phase-review/`. They review evidence and planning;
they do not grant permission to mutate gameplay state or treat AI narration as evidence.

## 10. Troubleshooting

- **PostgreSQL is down or unauthenticated:** start PostgreSQL, verify the configured `PG_URL`,
  `PG_USER`, and `PG_PASSWORD`, then rerun the status script.
- **Java is wrong:** export the Temurin 21 `JAVA_HOME` shown above before running Maven. The launch
  script can fall back to a locally installed JDK when `JAVA_HOME` is invalid, but Java 21 is the
  supported runtime.
- **Port already in use:** use the status script to identify the listener and its command. Stop it
  using that service's documented launcher rather than killing an unrelated process.
- **Local AI gateway is down:** if the sibling external gateway copy exists, start it with
  `node server.mjs` from `../ai-proxy/local-ai-gateway`; otherwise confirm Node.js is on `PATH` and
  `LOCAL_AI_GATEWAY_COMMAND` is configured, then start the in-repository adapter with
  `node scripts/local-ai-gateway/server.mjs`. Restart the web app to have `start.sh` manage the
  adapter, or set `ET_DISABLE_LOCAL_AI_GATEWAY=1` when it is not needed.
- **Research worker cannot start:** verify PostgreSQL first. The launcher installs the required
  transition-period game-service artifact before it starts the standalone worker.
