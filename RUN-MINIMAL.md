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

## 3. First-time Julia sidecar setup

```bash
cd "$(git rev-parse --show-toplevel)"/services/julia-math-sidecar
julia --project=. -e 'using Pkg; Pkg.instantiate()'
```

## 4. Julia math sidecar

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

## 5. PyCall and grounding sidecar

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

## 6. Research audit without the game

```bash
cd "$(git rev-parse --show-toplevel)"
APP_UNIVERSITY_ENABLED=false \
RESEARCH_LIFECYCLE_SHADOW_ENABLED=true \
RESEARCH_LIFECYCLE_SHADOW_ONLY=true \
./scripts/start-research-service.sh
```

## 7. University research without the game

Run this in one terminal when the sibling local AI gateway copy exists:

```bash
cd "$(dirname "$(git rev-parse --show-toplevel)")/ai-proxy/local-ai-gateway"
node server.mjs
```

Otherwise run this in one terminal:

```bash
cd "$(git rev-parse --show-toplevel)"
LOCAL_AI_GATEWAY_COMMAND=/absolute/path/to/local-ai-adapter \
node scripts/local-ai-gateway/server.mjs
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

## 8. GPU engine

```bash
cd "$(git rev-parse --show-toplevel)"
./engines/gpu-engine/scripts/start.sh
```

```bash
cd "$(git rev-parse --show-toplevel)"
./engines/gpu-engine/scripts/stop.sh
```

## 9. Web app

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/start.sh
```

## 10. Web app with local AI gateway

```bash
cd "$(git rev-parse --show-toplevel)"
LOCAL_AI_GATEWAY_COMMAND=/absolute/path/to/local-ai-adapter \
./scripts/start.sh
```

## 11. Status and logs

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

## 12. Build and test

```bash
cd "$(git rev-parse --show-toplevel)"
mvn -f apps/game-service/pom.xml -DskipTests package
mvn -f apps/game-service/pom.xml test -DtrimStackTrace=false
cargo test --manifest-path engines/gpu-engine/Cargo.toml
```
