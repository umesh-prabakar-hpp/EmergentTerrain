# Run EmergentTerrain — minimal

## 1. Repository setup

```bash
cd "$(git rev-parse --show-toplevel)"
chmod +x scripts/*.sh scripts/*.py
chmod +x engines/gpu-engine/scripts/*.sh
chmod +x services/julia-math-sidecar/scripts/*.sh
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/status-related-apps.sh || true
```

Runtime files are stored in `.runtime/`. To use an external location instead:

```bash
export ET_RUNTIME_DIR=/var/lib/emergentterrain
```

## 2. Fedora bootstrap

```bash
cd "$(git rev-parse --show-toplevel)"
bash ./scripts/setup-fedora-prereqs.sh
```

## 3. Download papers and build the required literature index

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/bootstrap-hf-math-literature.sh
HF_DATASET=marin-community/ar5iv-no-problem-markdown \
  ./scripts/bootstrap-hf-math-literature.sh
```

Build the local SQLite/FTS5 index:

```bash
python3 -m pip install --user pyarrow

cd "$(git rev-parse --show-toplevel)"
./scripts/prepare-hf-math-index.py --stage metadata
./scripts/prepare-hf-math-index.py --stage theorems
./scripts/prepare-hf-math-index.py --stage fulltext
./scripts/prepare-hf-math-index.py --stage optimize
./scripts/verify-research-paper-prerequisites.sh
```

The complete index at `~/.emergentterrain/indexes/math-literature/` is mandatory for university
paper generation. The research launcher refuses university work until this check passes.

## 4. First-time Julia sidecar setup

```bash
cd "$(git rev-parse --show-toplevel)"/services/julia-math-sidecar
julia --project=. -e 'using Pkg; Pkg.instantiate()'
```

## 5. Julia math sidecar

Run this in its own terminal before starting the web app:

```bash
cd "$(git rev-parse --show-toplevel)"
JULIA_NUM_THREADS=4 ET_DISCOVER_EXEC=1 \
./services/julia-math-sidecar/scripts/start.sh
```

In another terminal, follow the log:

```bash
cd "$(git rev-parse --show-toplevel)"
tail -F services/julia-math-sidecar/logs/sidecar.log
```

Stop both Julia sidecars with:

```bash
cd "$(git rev-parse --show-toplevel)"
./services/julia-math-sidecar/scripts/stop.sh
```

## 6. PyCall and grounding sidecar

After the main Julia sidecar is listening, run this in a second terminal:

```bash
cd "$(git rev-parse --show-toplevel)"
ET_GROUND_SUBJECTS=mathematics,msc:11 \
./services/julia-math-sidecar/scripts/start-py-sidecar.sh
```

In another terminal, follow the log:

```bash
cd "$(git rev-parse --show-toplevel)"
tail -F services/julia-math-sidecar/logs/py-sidecar.log
```

Stop both Julia sidecars with:

```bash
cd "$(git rev-parse --show-toplevel)"
./services/julia-math-sidecar/scripts/stop.sh
```

## 7. Research audit without the game

`start-research-service.sh` applies its required idempotent game-schema migrations first.

```bash
cd "$(git rev-parse --show-toplevel)"
APP_UNIVERSITY_ENABLED=false \
RESEARCH_LIFECYCLE_SHADOW_ENABLED=true \
RESEARCH_LIFECYCLE_SHADOW_ONLY=true \
./scripts/start-research-service.sh
```

## 8. University research without the game

Complete the encrypted provider-secret setup in [RUN.md](RUN.md#encrypted-provider-secrets) before
starting this workflow. The existing fast and two-reviewer routes use `OPENROUTER_API_KEY`; do not
set `APP_SECRETS_ENABLED=false` for a full paper-production run.

Run this in one terminal when using the optional local AI command:

```bash
cd "$(git rev-parse --show-toplevel)"
LOCAL_AI_GATEWAY_COMMAND=/absolute/path/to/local-ai-adapter \
node scripts/local-ai-gateway/server.mjs
```

Verify its multi-round application-tool protocol before beginning university work:

```bash
cd "$(git rev-parse --show-toplevel)"
node scripts/local-ai-gateway/test-tool-loop.mjs
```

Run this in another terminal:

```bash
cd "$(git rev-parse --show-toplevel)"
APP_UNIVERSITY_ENABLED=true \
RESEARCH_LIFECYCLE_SHADOW_ONLY=false \
./scripts/start-research-service.sh
```

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/stop-research-service.sh
```

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/stop-local-ai-gateway.sh
```

## 9. GPU engine

```bash
cd "$(git rev-parse --show-toplevel)"
./engines/gpu-engine/scripts/start.sh
```

```bash
cd "$(git rev-parse --show-toplevel)"
./engines/gpu-engine/scripts/stop.sh
```

## 10. Web app

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/start.sh
```

## 11. Web app with local AI gateway

```bash
cd "$(git rev-parse --show-toplevel)"
LOCAL_AI_GATEWAY_COMMAND=/absolute/path/to/local-ai-adapter \
./scripts/start.sh
```

## 12. Status and logs

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/status-related-apps.sh || true
```

```bash
cd "$(git rev-parse --show-toplevel)"
tail -F \
  .runtime/logs/et-app.log \
  .runtime/logs/maven-stdout.log \
  .runtime/logs/local-ai-gateway.log \
  services/julia-math-sidecar/logs/sidecar.log \
  services/julia-math-sidecar/logs/py-sidecar.log \
  engines/gpu-engine/logs/gpu-engine.log
```

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/stop.sh
```

## 13. Build

```bash
cd "$(git rev-parse --show-toplevel)"
mvn -pl apps/game-service -am -DskipTests package
```

## 14. Test

```bash
cd "$(git rev-parse --show-toplevel)"
mvn -pl apps/game-service -am test -DtrimStackTrace=false
cargo test --manifest-path engines/gpu-engine/Cargo.toml
```
