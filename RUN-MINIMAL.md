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

## 2. Fedora bootstrap

```bash
cd "$(git rev-parse --show-toplevel)"
bash ./scripts/setup-fedora-prereqs.sh
```

## 3. Web app

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/start.sh
```

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/status-related-apps.sh || true
tail -F logs/et-app.log logs/maven-stdout.log logs/codex-proxy.log
```

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/stop.sh
```

## 4. Research audit without the game

```bash
cd "$(git rev-parse --show-toplevel)"
APP_UNIVERSITY_ENABLED=false \
RESEARCH_LIFECYCLE_SHADOW_ENABLED=true \
RESEARCH_LIFECYCLE_SHADOW_ONLY=true \
./scripts/start-research-service.sh
```

## 5. University research without the game

```bash
cd "$(git rev-parse --show-toplevel)"
APP_UNIVERSITY_ENABLED=true \
RESEARCH_LIFECYCLE_SHADOW_ONLY=false \
./scripts/start-research-service.sh
```

## 6. GPU engine

```bash
cd "$(git rev-parse --show-toplevel)"
./engines/gpu-engine/scripts/start.sh
```

```bash
cd "$(git rev-parse --show-toplevel)"
./engines/gpu-engine/scripts/stop.sh
```

## 7. Julia math sidecar

```bash
cd "$(git rev-parse --show-toplevel)"
JULIA_NUM_THREADS=4 ET_DISCOVER_EXEC=1 \
./services/julia-math-sidecar/scripts/start.sh
```

```bash
cd "$(git rev-parse --show-toplevel)"
./services/julia-math-sidecar/scripts/stop.sh
```

## 8. PyCall and grounding sidecar

```bash
cd "$(git rev-parse --show-toplevel)"
ET_GROUND_SUBJECTS=mathematics,msc:11 \
./services/julia-math-sidecar/scripts/start-py-sidecar.sh
```

## 9. legacy AI gateway

```bash
cd "$(git rev-parse --show-toplevel)"
ET_ENABLE_LEGACY_AI_GATEWAY=1 ./scripts/start.sh
```

## 10. Status and logs

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/status-related-apps.sh || true
```

```bash
cd "$(git rev-parse --show-toplevel)"
tail -F \
  logs/et-app.log \
  logs/maven-stdout.log \
  logs/codex-proxy.log \
  services/julia-math-sidecar/logs/sidecar.log \
  services/julia-math-sidecar/logs/py-sidecar.log \
  engines/gpu-engine/logs/gpu-engine.log
```

## 11. Build and test

```bash
cd "$(git rev-parse --show-toplevel)"
mvn -f apps/game-service/pom.xml -DskipTests package
mvn -f apps/game-service/pom.xml test -DtrimStackTrace=false
cargo test --manifest-path engines/gpu-engine/Cargo.toml
```
