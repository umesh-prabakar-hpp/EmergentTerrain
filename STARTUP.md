# EmergentTerrain — Startup Guide

## Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL 14+
- Rust + Cargo (for the GPU engine)
- Ollama (for local LLM fast-path — optional but recommended)
- Groq API key (for high-quality paper narration — optional, free tier sufficient)

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

## 2. Set up Groq (optional — recommended for paper quality)

Groq handles high-quality tasks: **paper narration** and **genome mentoring** — the
text users actually read. The free tier (~14,400 req/day) is sufficient for normal
simulation load.

1. Sign up at [console.groq.com](https://console.groq.com) (no credit card required)
2. Create an API key
3. Set it via environment variable at startup (see Section 4) or in `application.yml`:

```yaml
app:
  ai:
    groq:
      apiKey: your-key-here
```

At startup, the app queries Groq's model list, benchmarks each candidate with a
research narration prompt, and automatically selects the best-performing model.
The selected model is logged on startup and stamped on every paper as **AI Narrator**.

To pin a specific model instead of auto-discovering:

```yaml
app:
  ai:
    groq:
      preferredModel: openai/gpt-oss-120b
```

> **Without Groq:** Paper narration and genome mentoring fall back to Ollama
> (`gemma3:4b`). The simulation runs normally but paper conclusions and mentoring
> quality will be noticeably weaker.

---

## 3. Set up PostgreSQL

Create the database if it doesn't exist:

```bash
psql -U postgres -c "CREATE DATABASE emergentterrain;"
```

The schema is managed by Hibernate (`ddl-auto: update`) — no migration scripts needed.

---

## 4. Start the GPU engine (EmergentTerrainGPUEngine)

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine
cargo run --release
```

The engine listens on its gRPC port (default 50051). The Spring Boot app connects to it automatically if `app.gpu.enabled=true`.

---

## 5. Start the Spring Boot app

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

### With multiple Ollama backends

Groq is enabled by default — the API key is pre-configured in `application.yml`. Just set the Ollama hosts:

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn package -DskipTests
OLLAMA_HOSTS=localhost,192.168.1.145 java -jar target/emergent-terrain-0.0.1-SNAPSHOT.jar
```

To use a different Groq API key, override via env var:

```bash
GROQ_API_KEY=gsk_... OLLAMA_HOSTS=localhost,192.168.1.145 java -jar target/emergent-terrain-0.0.1-SNAPSHOT.jar
```

On startup you will see the LLM routing decision:

```
GroqPool: selected model=openai/gpt-oss-120b parallelism=3
LlmRouter: primary=GroqPool [openai/gpt-oss-120b]  fast=OllamaPool [gemma3:4b]
LlmService: primary=openai/gpt-oss-120b  fast=gemma3:4b
```

Each Ollama backend must have `OLLAMA_NUM_PARALLEL=3` and `gemma3:4b` pulled.
The app round-robins across healthy Ollama backends and retries unhealthy ones every 30 seconds.

---

## Environment variables

All have sensible defaults for local development. Override as needed:

| Variable | Default | Description |
|---|---|---|
| `PG_URL` | `jdbc:postgresql://localhost:5432/emergentterrain` | PostgreSQL JDBC URL |
| `PG_USER` | `postgres` | Database username |
| `PG_PASSWORD` | `NewStrongPasswordHere` | Database password |
| `GROQ_API_KEY` | *(pre-configured in application.yml)* | Groq API key — override to use a different key |
| `OLLAMA_HOSTS` | `localhost,192.168.1.145` | Comma-separated Ollama hostnames (fast pool) |
| `OLLAMA_MODEL` | `gemma3:4b` | Model name (must be pulled on every Ollama backend) |

Ports and per-backend parallelism are set in `application.yml` (`app.ai.backendPort`, `app.ai.backendParallelism`, `app.ai.groq.parallelism`).

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

When `app.ai.enabled=true` (the default), the following pipelines are active, routed
across two pools based on task quality requirements:

**Primary pool (Groq when configured, else Ollama):**
- **Paper narration** — once computations are complete, the LLM writes hypothesis, findings, and conclusion for the paper, citing prior work and flagging anomalies. The model name is stamped on each paper as **AI Narrator**.
- **Genome mentoring** — when a bot publishes high-scoring work, it earns a mentoring session that adjusts its research genome and may redirect its parameter search region.

**Fast pool (Ollama):**
- **Research formulation** — before each computation, the LLM chooses specific parameter values based on the bot's learned search region and prior papers in that domain/topic.
- **Multi-step computation** — after the primary result, the LLM decides whether a focused follow-up computation adds value. Up to 2 additional computations run before narration (adaptive: throttles to 1 if the narration queue is backed up).
- **Government digest** — when the government simulation decides court cases or enacts bills, a civic bulletin streams into the Government Commentary sidebar.
- **Pattern explanation** — when you click a tile to propose a change, a 1–2 sentence explanation streams into the Pattern Insights sidebar.

Routing rationale: Groq's free tier is rate-limited. Formulation and refinement fire
frequently but only parse a short JSON response — model quality is indistinguishable
there. Narration and mentoring produce text users read; that is where the quality gap
between a 4B and 120B model is clearly visible.

To disable LLM features entirely:

```yaml
# application.yml
app:
  ai:
    enabled: false
```

To disable only Groq (use Ollama for everything):

```yaml
app:
  ai:
    groq:
      enabled: false
```

---

## Logs

```bash
tail -f /tmp/et-app.log
```

LLM generation failures log at WARN level and do not affect game stability. Backend
health events (marked unhealthy / recovered) log at WARN/INFO from `OllamaPool` and
`GroqPool`.

---

## Switching models

**Groq model** — either set `preferredModel` to skip auto-discovery, or leave blank
to re-run the benchmark on next startup:

```yaml
app:
  ai:
    groq:
      preferredModel: llama-3.3-70b-versatile
```

**Ollama model** — pull on every backend, then update config:

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

Restart the Spring Boot app for any model change to take effect.
