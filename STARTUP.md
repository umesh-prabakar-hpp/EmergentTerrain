# EmergentTerrain — Startup Guide

## Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL 14+
- Rust + Cargo (for the GPU engine)
- Ollama (for LLM features — optional but recommended)

---

## 1. Install and start Ollama

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

Start the Ollama daemon with parallel inference slots (runs on port 11434 by default):

```bash
OLLAMA_NUM_PARALLEL=3 ollama serve
```

Pull the model (do this once — downloads ~3 GB):

```bash
ollama pull gemma3:4b
```

Verify it's running:

```bash
curl http://localhost:11434/api/tags
```

You should see `gemma3:4b` listed.

> **Without Ollama:** The app starts normally with `app.ai.enabled=false` (or if Ollama is unreachable). Research narration falls back to template text and LLM-driven features are skipped. No errors.

---

## 2. Set up PostgreSQL

Create the database if it doesn't exist:

```bash
psql -U postgres -c "CREATE DATABASE emergentterrain;"
```

The schema is managed by Hibernate (`ddl-auto: update`) — no migration scripts needed.

---

## 3. Start the GPU engine (EmergentTerrainGPUEngine)

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine
cargo run --release
```

The engine listens on its gRPC port (default 50051). The Spring Boot app connects to it automatically if `app.gpu.enabled=true`.

---

## 4. Start the Spring Boot app

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn spring-boot:run
```

Or build and run the jar:

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn package -DskipTests
java -jar target/emergent-terrain-0.0.1-SNAPSHOT.jar
```

The app starts on **http://localhost:8080**.

### Multiple Ollama backends

The app load-balances across multiple Ollama instances automatically. Pass hostnames as a comma-separated list at startup:

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn package -DskipTests
java -jar target/emergent-terrain-0.0.1-SNAPSHOT.jar --app.ai.backendHosts=localhost,192.168.1.145
```

Or via environment variable:

```bash
OLLAMA_HOSTS=localhost,192.168.1.145 java -jar target/emergent-terrain-0.0.1-SNAPSHOT.jar
```

Each backend must have Ollama running with `OLLAMA_NUM_PARALLEL=3` and the `gemma3:4b` model pulled. The app selects the least-loaded healthy backend for each request and automatically retries unhealthy backends every 30 seconds.

---

## Environment variables

All have sensible defaults for local development. Override as needed:

| Variable | Default | Description |
|---|---|---|
| `PG_URL` | `jdbc:postgresql://localhost:5432/emergentterrain` | PostgreSQL JDBC URL |
| `PG_USER` | `postgres` | Database username |
| `PG_PASSWORD` | `NewStrongPasswordHere` | Database password |
| `OLLAMA_HOSTS` | `localhost,192.168.1.145` | Comma-separated Ollama hostnames |
| `OLLAMA_MODEL` | `gemma3:4b` | Model name (must be pulled on every backend) |

Port and per-backend parallelism are set in `application.yml` (`app.ai.backendPort`, `app.ai.backendParallelism`).

---

## Startup order

```
1. PostgreSQL
2. ollama serve          (optional — LLM features; one or more machines)
3. GPU engine (cargo)    (optional — terrain simulation)
4. Spring Boot app
```

Steps 2 and 3 can be started in any order relative to each other; the Spring Boot app connects to both lazily.

---

## LLM features

When Ollama is running and `app.ai.enabled=true` (the default), the following pipelines are active:

- **Research formulation** — before each computation, the LLM chooses specific parameter values based on the bot's learned search region and prior papers in that domain/topic.
- **Multi-step computation** — after the primary result, the LLM decides whether a focused follow-up computation adds value. Up to 2 additional computations run before narration (adaptive: throttles to 1 if the narration queue is backed up).
- **Paper narration** — once computations are complete, the LLM writes hypothesis, findings, and conclusion for the paper, citing prior work and flagging anomalies. Follow-up papers reference the chain of prior predictions.
- **Genome mentoring** — when a bot publishes high-scoring work, it earns a mentoring session that adjusts its research genome and may redirect its parameter search region.
- **Government digest** — when the government simulation decides court cases or enacts bills, a civic bulletin streams into the Government Commentary sidebar.
- **Pattern explanation** — when you click a tile to propose a change, a 1–2 sentence explanation streams into the Pattern Insights sidebar.

All pipelines use independent semaphores and route through the shared `OllamaPool`, which enforces per-backend concurrency limits. With two backends each running `OLLAMA_NUM_PARALLEL=3`, up to 6 LLM requests can be in flight simultaneously.

To disable LLM features without stopping Ollama:

```yaml
# application.yml
app:
  ai:
    enabled: false
```

---

## Logs

```bash
tail -f /tmp/et-app.log
```

LLM generation failures log at WARN level and do not affect game stability. Backend health events (marked unhealthy / recovered) log at WARN/INFO from `OllamaPool`.

---

## Switching models

Pull the new model on every Ollama backend, then update the config:

```bash
ollama pull llama3.2:3b
```

```yaml
spring:
  ai:
    ollama:
      chat:
        model: llama3.2:3b
```

Restart the Spring Boot app for the model change to take effect.
