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

Start the Ollama daemon (runs on port 11434 by default):

```bash
ollama serve
```

For parallel LLM requests (recommended — allows digest and genome mentoring to run concurrently):

```bash
OLLAMA_NUM_PARALLEL=3 ollama serve
```

Pull the model (do this once — downloads ~1.5 GB):

```bash
ollama pull gemma2:2b
```

Verify it's running:

```bash
curl http://localhost:11434/api/tags
```

You should see `gemma2:2b` listed. Ollama keeps the model in memory after first use so subsequent requests are fast.

> **Without Ollama:** The app starts normally with `app.ai.enabled=false` (or if Ollama is unreachable). Pattern explanations fall back to static text and court opinions are simply not generated. No errors.

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
mvn package -DskipTests
java -jar target/emergent-terrain-0.0.1-SNAPSHOT.jar
```

The app starts on **http://localhost:8080**.

---

## Environment variables

All have sensible defaults for local development. Override as needed:

| Variable | Default | Description |
|---|---|---|
| `PG_URL` | `jdbc:postgresql://localhost:5432/emergentterrain` | PostgreSQL JDBC URL |
| `PG_USER` | `postgres` | Database username |
| `PG_PASSWORD` | `NewStrongPasswordHere` | Database password |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama base URL |
| `OLLAMA_MODEL` | `gemma2:2b` | Model to use for generation |
| `OLLAMA_NUM_PARALLEL` | `1` | Parallel inference slots in Ollama (set to 3 to match the app's 3 LLM lanes) |

---

## Startup order

```
1. PostgreSQL
2. ollama serve          (optional — LLM features)
3. GPU engine (cargo)    (optional — terrain simulation)
4. Spring Boot app
```

Steps 2 and 3 can be started in any order relative to each other; the Spring Boot app connects to both lazily.

---

## LLM features

When Ollama is running and `app.ai.enabled=true` (the default):

- **Pattern explanation** — when you click a tile to propose a change, a 1–2 sentence explanation streams into the Pattern Insights sidebar in real time.
- **Court opinions / government digest** — when the government simulation decides court cases or enacts bills, a civic bulletin streams into the Government Commentary sidebar.
- **Genome mentoring** — when a researcher bot publishes high-scoring work, it earns an LLM mentoring session that adjusts its research genome based on its emerging identity.

All three lanes use independent semaphores so they never block each other. With `OLLAMA_NUM_PARALLEL=3`, all three can run concurrently. Expected speed on this hardware: ~8–12 tokens/second (~5–40 seconds per generation depending on length).

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

LLM generation failures log at WARN level and do not affect game stability.

---

## Switching models

To try a different model, pull it and update the config:

```bash
ollama pull llama3.2:3b
```

```yaml
app:
  ai:
    enabled: true

spring:
  ai:
    ollama:
      chat:
        model: llama3.2:3b
```

Restart the Spring Boot app for the model change to take effect.
