# EmergentTerrain — Startup Guide

## Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL 14+
- Rust + Cargo (for the GPU engine + Phase 8 `run_gpu_eval` tool)
- Ollama (for local LLM fast-path — optional but recommended)
- One or more primary-tier API keys — Groq, NVIDIA NIM, OpenRouter, Cerebras (all free-tier; Groq is the default and most opinionated)

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

Pull the model(s) — at least one, more if you want multi-model Ollama (Phase 9 U4):

```bash
ollama pull gemma3:4b              # default
# optional additional fast-tier models:
# ollama pull qwen2:7b
```

Verify it's running:

```bash
curl http://localhost:11434/api/tags
```

You should see your pulled models listed.

> **Phase 9 U4 — multi-model Ollama.** Configure a CSV list of pulled models via
> `app.ai.ollama.preferredModels: "gemma3:4b,qwen2:7b"`. The pool creates one
> Backend per `(host × model)` tuple; `callSyncOnModel(modelId, …)` targets a
> specific model so the U6 `FastModelRegistry` can round-robin across them.
> The legacy `spring.ai.ollama.chat.model` key still works (with a deprecation
> WARN at startup) for one release cycle.

> **Without Ollama:** The app starts normally with `app.ai.enabled=false` (or if Ollama is unreachable). Research narration falls back to template text and LLM-driven features are skipped. No errors.

---

## 2. Set up primary providers (Groq + optional NVIDIA / OpenRouter / Cerebras)

The primary tier handles **paper narration**, **professor tool-calling**,
**student revision**, **panel review**, and **genome mentoring** — the text
users actually read and the agentic computation that backs it. Phase 7 + 9
aggregate every configured provider's 70B+ tool-capable models into a single
`PrimaryModelRegistry`; a single primary call falls through up to ~10 models
across providers before throwing.

**Minimum**: one provider key. Groq is the default and most opinionated.
Additional providers add depth — they're consulted automatically when Groq is
rate-limited or fails a model probe.

### Groq (default)

1. Sign up at [console.groq.com](https://console.groq.com) (no credit card)
2. Create an API key
3. Set via env var or `application.yml`:

```yaml
app:
  ai:
    groq:
      apiKey: your-key-here
```

At startup the app fetches the live Groq model list, ranks 70B+ candidates via
self-judging Borda count, and picks the top `primaryModelCount` (default 10 —
effectively "all 70B+", which today is 2 models). Pin specific models with:

```yaml
app:
  ai:
    groq:
      preferredModels: "llama-3.3-70b-versatile,openai/gpt-oss-120b"
```

### NVIDIA NIM (optional)

1. Sign up at [build.nvidia.com](https://build.nvidia.com); verify your email
2. Generate a key (existing keys generated pre-verification do not have inference
   scope — revoke and reissue after verifying)
3. Configure:

```yaml
app:
  ai:
    nvidia:
      enabled: true
      apiKey: ${NVIDIA_API_KEY:nvapi-…}
      preferredModels: "meta/llama-3.3-70b-instruct,openai/gpt-oss-120b,nvidia/nemotron-3-super-120b-a12b,qwen/qwen3-next-80b-a3b-instruct,qwen/qwen3-coder-480b-a35b-instruct"
```

Free tier ~1000 credits/month. Models like `nvidia/nemotron-4-340b-instruct` are
paid-tier-only — the pool catches the 404 "Function not found for account" and
permanently disables those for the JVM lifetime.

### OpenRouter (optional)

1. Sign up at [openrouter.ai](https://openrouter.ai); set spend cap = $0 to
   stay on free routes
2. Configure:

```yaml
app:
  ai:
    openrouter:
      enabled: true
      apiKey: ${OPENROUTER_API_KEY:sk-or-v1-…}
      preferredModels: "openai/gpt-oss-120b:free,nvidia/nemotron-3-super-120b-a12b:free"
      excludedBackends: "Venice"   # Venice's free pool is rate-capped globally
```

### Cerebras (optional — fastest inference)

1. Sign up at [cloud.cerebras.ai](https://cloud.cerebras.ai)
2. Configure:

```yaml
app:
  ai:
    cerebras:
      enabled: true
      apiKey: ${CEREBRAS_API_KEY:csk-…}
      preferredModels: "qwen-3-235b-a22b-instruct-2507"
      role: "fast-audit"   # ~14 ms WSE-3 inference for runQualityAudit
```

Free tier ~30 RPM per model. Role-tagged so the audit stage prefers Cerebras
and falls back to Ollama when blocked.

### Tool-calling opt-in (Phase 7 E2)

Tool-calling at the professor stage is gated by an opt-in CSV — defaults to
Groq only so non-Groq pools soak before getting agentic traffic:

```yaml
app:
  ai:
    tool-calling:
      providers: "groq"   # default; expand to "groq,nvidia,openrouter,cerebras" after soak
```

> **Without any primary provider:** Paper narration and panel review fall back
> to Ollama (`gemma3:4b`). The simulation runs but paper conclusions are
> noticeably weaker and tool-call provenance is empty.

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

On startup you will see (Phase 7/8/9 — multi-provider + selection-tested):

```
GroqPool: activeModels=[llama-3.3-70b-versatile, openai/gpt-oss-120b] selection-tests=on
NvidiaPool: models=[meta/llama-3.3-70b-instruct, openai/gpt-oss-120b, nvidia/nemotron-3-super-120b-a12b, qwen/qwen3-next-80b-a3b-instruct, qwen/qwen3-coder-480b-a35b-instruct] selection-tests=on
OpenRouterPool: models=[openai/gpt-oss-120b:free, nvidia/nemotron-3-super-120b-a12b:free] excludedBackends=[Venice] selection-tests=on
CerebrasPool: models=[qwen-3-235b-a22b-instruct-2507] role=fast-audit selection-tests=on
OllamaPool: models=[gemma3:4b] parallelism=3 selection-tests=on

groq: selection-test model=llama-3.3-70b-versatile PASSED chat=412ms json=836ms tools=1241ms
groq: selection-test model=openai/gpt-oss-120b PASSED chat=523ms json=1108ms tools=2017ms
groq: selection-tests complete passed=2/2 models=[llama-3.3-70b-versatile, openai/gpt-oss-120b]
... (similar lines per provider) ...

PrimaryModelRegistry: resolved=10 handles=[groq/llama-3.3-70b-versatile, groq/openai/gpt-oss-120b, nvidia/meta/llama-3.3-70b-instruct, …]
FastModelRegistry: resolved=1 handles=[ollama/gemma3:4b]
ReviewPanelService: enabled=true mode=sequential providers=[groq, nvidia, openrouter, cerebras] panelSize=4 registryHandles=10
LlmRouter: primaryChain=[groq, nvidia, openrouter, cerebras] fast=ollama [gemma3:4b] roles=[fast-audit→cerebras] toolProviders=[groq] primaryHandles=10 fastHandles=1
```

Each Ollama backend must have `OLLAMA_NUM_PARALLEL=3` and the configured
models pulled. The app round-robins across healthy Ollama backends and
retries unhealthy ones every 30 seconds.

---

## Environment variables

All have sensible defaults for local development. Override as needed:

| Variable | Default | Description |
|---|---|---|
| `PG_URL` | `jdbc:postgresql://localhost:5432/emergentterrain` | PostgreSQL JDBC URL |
| `PG_USER` | `postgres` | Database username |
| `PG_PASSWORD` | `NewStrongPasswordHere` | Database password |
| `GROQ_API_KEY` | *(pre-configured in `application.yml`)* | Groq API key |
| `NVIDIA_API_KEY` | *(pre-configured)* | NVIDIA NIM API key — Phase 7 E1.3 |
| `OPENROUTER_API_KEY` | *(pre-configured)* | OpenRouter API key — Phase 7 E1.2 |
| `CEREBRAS_API_KEY` | *(pre-configured)* | Cerebras Cloud API key — Phase 7 E1.4 |
| `OLLAMA_HOSTS` | `localhost,192.168.1.145` | Comma-separated Ollama hostnames (fast pool) |
| `OLLAMA_PORT` | `11434` | Ollama daemon port |

> **Deprecated** (Phase 9 U4): `OLLAMA_MODEL` / `spring.ai.ollama.chat.model`
> still read as fallback with a `@PostConstruct` deprecation WARN. Migrate
> to `app.ai.ollama.preferredModels` (CSV of model names).

Ports and per-backend parallelism are set in `application.yml` under
`app.ai.ollama.*` (fast tier) and `app.ai.<provider>.*` (primary tier).
Selection-test behaviour is governed by `app.ai.selection-tests.enabled`
(default `true`).

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

When `app.ai.enabled=true` (the default), the following pipelines are active,
routed across **two model-centric registries** (Phase 9 U2 / U6):

- **`PrimaryModelRegistry`** — aggregates every 70B+ tool-capable model across
  Groq + NVIDIA + OpenRouter + Cerebras into one flat list. `LlmRouter.callPrimary`
  and `ReviewPanelService` walk it sequentially; a single primary call falls
  through up to ~10 models across providers before throwing.
- **`FastModelRegistry`** — aggregates Ollama's configured models (and any
  future sub-70B provider). `LlmRouter.callFast` routes through it.

**Primary registry stages:**
- **Paper narration** — once computations are complete, the LLM writes hypothesis, findings, and conclusion for the paper, citing prior work and flagging anomalies. The model name is stamped on each paper as **AI Narrator**.
- **Professor tool-calling** — Phase 7 + 8. The professor receives a suite of
  agentic tools it can invoke up to 5 times before writing its synthesis:
  - `run_param_sweep` — re-run the paper's own computation at different parameters
  - `run_predicate_scan` — number-theoretic predicate density (Mathematics / Interdisciplinary topics)
  - `evaluate_symbolic` — evaluate f(n) over a range with statistics
  - `run_sandboxed_code` — arbitrary pseudocode (reflective `Math.*` + SI constants + Phase 8 P1 number-theory helpers + Phase 8 P2 user-defined functions; safety-reviewed)
  - `run_symbolic` — Symja-backed integral / derivative / simplification / series verification (Phase 8 P3)
  - `run_gpu_eval` — formula `f(n)` over up to 10M values on a GPU (Phase 8 P4; aggregates mean/std/min/max/sum/finite_count)
  - In-sandbox bridges (Phase 8 P5): `integrate(expr, var, lo, hi)`, `differentiate(expr, var, at)`, `simplify(expr)` callable from within `run_sandboxed_code`
- **Student revision** — after professor synthesis, a separate primary call revises the paper incorporating the experimental evidence. The full tool-call trace (Phase 7 F5.4) persists as `panel_outcome` / `tool_call_trace` JSON columns and renders as a `## Methods` appendix in the published markdown.
- **Multi-reviewer panel review** — Phase 7 F7.2. After revision, the panel runs N reviewers sequentially (configurable via `app.research.review-panel.size`, default 4) against the synthesis + tool-call trace. Each reviewer is one `ModelHandle` from `PrimaryModelRegistry.availableModels()`. Each returns structured JSON grades (honesty / completeness / rigor / novelty); aggregation is deterministic (median grades, intersection of cited_calls, contradictions flagged by ≥2 reviewers, `reviewer_agreement` = 1 − mean stddev across axes). Renders as `## Panel Review` markdown section.
- **Genome mentoring** — when a bot publishes high-scoring work, it earns a mentoring session that adjusts its research genome and may redirect its parameter search region. The mentor prompt includes cross-domain anomalous findings from other bots.

**Fast registry stages:**
- **Novelty check** — before formulation, when ≥3 prior papers exist in the topic, a fast call assesses how saturated the topic is (score 0–1).
- **Research formulation** — the LLM chooses specific parameter values and writes a pre-registered hypothesis+prediction+falsification condition locked before computation runs.
- **Statistical framing** — after refinement and before narration, a fast call rewrites the raw computational findings with appropriate significant figures (≤3), uncertainty qualifiers, and scientific notation.
- **Multi-step computation** — after the primary result, the LLM decides whether a focused follow-up computation adds value. Up to `app.research.max-computations` independent computations (currently `5` per Phase 7 F4) run before narration.
- **Audit:quality** — Phase 7. Pre-professor structural audit. Role-tagged to Cerebras (~14 ms inference) with Ollama fallback.
- **Government digest, Pattern explanation** — unchanged.

**Selection tests at startup (Phase 9 U5):**
Every pool runs three probes against each configured model before adding it to
`activeModels`:
1. Chat — "Reply with the single word OK." Pass: response contains `OK`.
2. JSON-mode — "Return JSON with name=Euler, value=2.71828, good=true." Pass: response parses with the three expected fields.
3. Tool-call (tool-capable pools only) — "Call get_value with key=pi." Pass: trace contains a call to `get_value` with `key=pi`.

Latency is logged per-probe but **never used as a drop signal** — slow Ollama
models pass if they eventually respond. Disable with
`app.ai.selection-tests.enabled: false` (e.g. in unit tests).

**Scientific integrity pipeline (built into research cycle, no extra LLM call):**
- **Deterministic classification** — after computation, `classifyResult()` assigns SUPPORTED / PARTIALLY_SUPPORTED / NULL_RESULT / DEGENERATE.
- **Degenerate rejection** — non-finite values, all-zero sentinels, or non-positive author scores are silently discarded.
- **Null result honesty** — NULL_RESULT papers are stored with status `NULL_RESULT`, no confidence boost, with a null-result banner in markdown.
- **F5.5 Check C** — Phase 7. Numerical-consistency validator now spans the
  tool-call trace: any "salient" number in findings (≥2 decimal digits or
  scientific notation) must appear in hypothesis ∪ conclusion ∪ any tool result
  within ±1%.
- **F7.3 cherry-picked evidence** — when ≥2 panel reviewers flag the same
  omitted-relevant tool call, the `cherry_picked_evidence` audit-gap fires.
  Advisory by default; flip `app.research.audit-cherry-picked-rejects.enabled`
  after measuring FP rate over ≥30 papers.

**LLM calls per paper (typical case, Phase 9):**

| Step | Tier | When |
|---|---|---|
| Novelty check | Fast | Before formulation, if ≥3 topic papers exist |
| Formulation | Fast | Before computation |
| Refinement decision(s) | Fast | 0–4 calls between compute and narration (matches `max-computations: 5`) |
| Statistical framing | Fast | After refinement, before narration |
| Narration (first pass) | Primary | Once per paper |
| Audit:quality | Fast / Cerebras | Before professor stage |
| Professor critique + tools | Primary (tool-calling) | After first-pass narration; up to 5 tool rounds |
| Tool execution (sandbox/symbolic/GPU) | (deterministic, no LLM) | Per tool the professor invokes |
| Revision (student) | Primary | After professor synthesis |
| **Panel review** | Primary (× N) | After revision, sequential; default N=4 reviewers |
| Genome mentoring | Primary | Once per high-scoring paper (cooldown ~50 min) |

Routing rationale unchanged: tier separation is by task quality requirement,
not by provider. The primary tier now spans 4 providers so a single rate-limit
on one provider doesn't block the pipeline; the panel further diversifies by
sampling N distinct models per paper.

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

**Groq models** — pin a comma-separated CSV (Phase 7 — note `preferredModels`,
plural):

```yaml
app:
  ai:
    groq:
      preferredModels: "llama-3.3-70b-versatile,openai/gpt-oss-120b"
```

Leave blank to re-run the auto-discovery + Borda ranking on next startup.

**NVIDIA / OpenRouter / Cerebras models** — same `preferredModels` CSV pattern
under each provider's section (see Section 2). Each provider's pool resolves
its own model list independently; the `PrimaryModelRegistry` aggregates them
all into one flat view at startup.

**Ollama models** — Phase 9 U4. Pull on every Ollama backend, then update the
new unified config:

```bash
ollama pull qwen2:7b
```

```yaml
app:
  ai:
    ollama:
      preferredModels: "gemma3:4b,qwen2:7b"   # CSV; one Backend per (host × model)
```

The legacy `spring.ai.ollama.chat.model` key still works as fallback but logs
a deprecation WARN at startup.

Restart the Spring Boot app for any model change to take effect — selection
tests re-run against the new candidates.

---

## Startup checks

After restart, the first 5–60 seconds of `/tmp/et-app.log` carry the highest-
signal information about what actually came up. Key things to grep for:

```bash
# Did selection tests pass on every model?
grep -E "selection-test " /tmp/et-app.log | head -50

# Did any model fail a probe?
grep -E "selection-test.*FAILED" /tmp/et-app.log

# Per-pool summary
grep -E "selection-tests complete" /tmp/et-app.log

# Final registry composition (primary + fast handles)
grep -E "(PrimaryModel|FastModel|LlmRouter|ReviewPanelService): " /tmp/et-app.log

# Phase 9 U4 — deprecation warnings for legacy Ollama config keys
grep -E "OllamaPool: legacy" /tmp/et-app.log
```

**What you want to see:**
- `selection-test model=X PASSED chat=Yms json=Zms tools=Wms` for each configured tool-capable model
- `selection-test model=X PASSED chat=Yms json=Zms` for each Ollama model
- `selection-tests complete passed=N/M models=[...]` per pool — `N==M` ideal; `N<M` means some dropped (check the FAILED lines to see why)
- `PrimaryModelRegistry: resolved=10 handles=[...]` — registry-aggregated 70B+ models
- `FastModelRegistry: resolved=M handles=[...]` — sub-70B models
- `LlmRouter: ... primaryHandles=10 fastHandles=M` — the router sees both registries

**Common failures and what they mean:**
- `selection-test model=X FAILED reason=chat-probe threw: 401 Unauthorized` — bad API key for that provider
- `selection-test model=X FAILED reason=json-probe missing or wrong 'name'` — the model doesn't reliably follow JSON instructions; consider dropping it from `preferredModels`
- `selection-test model=X FAILED reason=tool-probe trace was empty` — the model didn't invoke the probe tool; the provider may not actually support OpenAI-style `tools=[…]` for this model
- `OllamaPool: legacy config keys detected — please migrate to app.ai.ollama.*: [spring.ai.ollama.chat.model, ...]` — update your `application.yml` to the new keys per Phase 9 U4
- `OllamaPool: selection tests failed for all candidates ...` — Ollama isn't reachable or the configured models aren't pulled. Pool keeps unverified candidates so the periodic health probe can recover.

If everything passed, the first published paper after restart should show
`panel:n=4:agreement=0.NN` in its `llmCallPath` and a `## Panel Review`
section in the published markdown. That confirms the F5/F7 + Phase 9 wiring
is fully active.
