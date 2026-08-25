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

## 3. Julia math sidecar

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

## 4. PyCall and grounding sidecar

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

## 5. Research audit without the game

```bash
cd "$(git rev-parse --show-toplevel)"
APP_UNIVERSITY_ENABLED=false \
RESEARCH_LIFECYCLE_SHADOW_ENABLED=true \
RESEARCH_LIFECYCLE_SHADOW_ONLY=true \
./scripts/start-research-service.sh
```

## 6. University research without the game

```bash
cd "$(git rev-parse --show-toplevel)"
APP_UNIVERSITY_ENABLED=true \
RESEARCH_LIFECYCLE_SHADOW_ONLY=false \
./scripts/start-research-service.sh
```

## 7. GPU engine

```bash
cd "$(git rev-parse --show-toplevel)"
./engines/gpu-engine/scripts/start.sh
```

```bash
cd "$(git rev-parse --show-toplevel)"
./engines/gpu-engine/scripts/stop.sh
```

## 8. Web app

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/start.sh
```

## 9. Status and logs

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/status-related-apps.sh || true
```

```bash
cd "$(git rev-parse --show-toplevel)"
tail -F \
  logs/et-app.log \
  logs/maven-stdout.log \
  logs/local-ai-gateway.log \
  services/julia-math-sidecar/logs/sidecar.log \
  services/julia-math-sidecar/logs/py-sidecar.log \
  engines/gpu-engine/logs/gpu-engine.log
```

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/stop.sh
```

## 11. Build and test

```bash
cd "$(git rev-parse --show-toplevel)"
mvn -f apps/game-service/pom.xml -DskipTests package
mvn -f apps/game-service/pom.xml test -DtrimStackTrace=false
cargo test --manifest-path engines/gpu-engine/Cargo.toml
```
