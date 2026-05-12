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

At startup, the app fetches the live Groq model list, asks each candidate to rank
all others for scientific and mathematical reasoning (no ties allowed), aggregates
via Borda count, and selects the top `primaryModelCount` models (default: 2).
Calls fall back through the ranked list automatically if the primary model fails.
The selected models are logged on startup; the model used per paper is stamped as **AI Narrator**.

To control how many fallback models are selected:

```yaml
app:
  ai:
    groq:
      primaryModelCount: 2   # pick top-2 by peer ranking (1–N)
```

To pin specific models instead of auto-discovering (comma-separated, in fallback order):

```yaml
app:
  ai:
    groq:
      preferredModels: "llama-3.3-70b-versatile,groq/compound"
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
OLLAMA_HOSTS=localhost,192.168.1.145 mvn clean spring-boot:run
```

Or build and run the jar:

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn clean package -DskipTests
java -jar target/emergent-terrain-0.0.1-SNAPSHOT.jar
```

The app starts on **http://localhost:8081**.

### With multiple Ollama backends

Groq is enabled by default — the API key is pre-configured in `application.yml`. Just set the Ollama hosts:

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn clean package -DskipTests
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
- **Professor tool-calling + revision** — after first-pass narration, the Groq professor receives a suite of four mathematical tools it can call freely before writing its synthesis: `run_param_sweep` (re-run the paper's own computation at different parameters), `run_predicate_scan` (number-theoretic predicate density), `evaluate_symbolic` (evaluate f(n) over a range), and `run_sandboxed_code` (arbitrary pseudocode reviewed by 4b before execution). After the professor's synthesis, a student (second Groq call) revises the paper incorporating the experimental evidence. Falls back to the legacy JSON-prescribed-experiment path when Groq is not configured.
- **Genome mentoring** — when a bot publishes high-scoring work, it earns a mentoring session that adjusts its research genome and may redirect its parameter search region. The mentor prompt now includes cross-domain anomalous findings from other bots, enabling transfer of structural patterns across research areas.

**Fast pool (Ollama):**
- **Novelty check** — before formulation, when ≥3 prior papers exist in the topic, a fast call assesses how saturated the topic is (score 0–1). Saturated topics (< 0.30) inject a redirect hint into the formulation prompt. Fewer than 3 papers → assumed fresh, no call.
- **Research formulation** — the LLM chooses specific parameter values and writes a pre-registered hypothesis+prediction+falsification condition locked before computation runs.
- **Statistical framing** — after refinement and before narration, a fast call rewrites the raw computational findings with appropriate significant figures (≤3), uncertainty qualifiers, and scientific notation. Skipped for dynamic papers; falls back to raw on failure.
- **Multi-step computation** — after the primary result, the LLM decides whether a focused follow-up computation adds value. Up to 2 additional computations run before narration (adaptive: throttles to 1 if the narration queue is backed up). Duplicate parameter proposals are rejected.
- **Government digest** — when the government simulation decides court cases or enacts bills, a civic bulletin streams into the Government Commentary sidebar.
- **Pattern explanation** — when you click a tile to propose a change, a 1–2 sentence explanation streams into the Pattern Insights sidebar.

**Scientific integrity pipeline (built into research cycle, no extra LLM call):**
- **Deterministic classification** — after computation, `classifyResult()` assigns SUPPORTED / PARTIALLY_SUPPORTED / NULL_RESULT / DEGENERATE from the engine's `ClaimStatus` and `authorScore`, before any narration call.
- **Degenerate rejection** — non-finite values, all-zero sentinels, or non-positive author scores are silently discarded; no draft is created and no LLM call is made.
- **Null result honesty** — NULL_RESULT papers are stored with status `NULL_RESULT` (not PUBLISHED), receive no confidence boost, and display a null-result banner in markdown output. The narration prompt explicitly prohibits reframing null results as partial success.
- **Cross-domain anomaly context** — the formulation prompt includes anomalous findings from adjacent topics in the same domain, enabling cross-paper reasoning when interesting patterns emerge nearby.

**LLM calls per paper (typical case):**

| Step | Pool | When |
|---|---|---|
| Novelty check | Ollama | Before formulation, if ≥3 topic papers exist |
| Formulation | Ollama | Before computation (skipped if slot busy) |
| Refinement decision(s) | Ollama | 0–2 calls between compute and narration |
| Statistical framing | Ollama | After refinement, before narration |
| Narration (first pass) | Groq | Once per paper |
| Professor critique + tools | Groq (tool-calling) | After first-pass narration |
| Tool execution | (sync, no LLM) | For each tool the professor calls |
| Revision (student) | Groq | After professor synthesis |
| Genome mentoring | Groq | Once per high-scoring paper (cooldown ~50 min) |

Routing rationale: Groq's free tier is rate-limited. Formulation, refinement, novelty, framing, and critique fire frequently but parse short responses — model quality is indistinguishable there. Narration, revision, and mentoring produce text users read; that is where the quality gap between a 4B and 120B model is clearly visible.

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
/paper-review research/<room-id> --logs /tmp/et-app.log
```

**Periodic refresh every 15 minutes** — pair with the `loop` skill:

```
/loop 15m /paper-review research/<room-id> --logs /tmp/et-app.log
```

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
