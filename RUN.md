# Run — copy-paste commands

Terse companion to [STARTUP.md](STARTUP.md). Each step in its own
terminal. Order: Ollama → GPU engine → Spring Boot → paper-review.

## 1. Ollama (fast LLM pool)

```bash
OLLAMA_NUM_PARALLEL=3 ollama serve
```

## 2. GPU engine

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine && cargo run --release
```

## 3. Spring Boot app

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain && mvn clean spring-boot:run
```

App listens on http://localhost:8081.

## 4. Paper-review loop (Claude Code)

```
/loop 30m /paper-review research/a3289005 --logs logs/et-app.log
```
