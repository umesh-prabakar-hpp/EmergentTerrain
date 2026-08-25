# ARC feature considerations — reference, NOT queued, NOT priority

Deferred feature notes from the AutoResearchClaw review (2026-06-04;
clone + security review at `/opt/apps/ide/vscode/ai/reference-from-github/AutoResearchClaw/`).
These are the ARC subsystems judged **peripheral to ET's research goals** — recorded here so the
details survive without entering any phase queue. Same convention as
[`phase-wishlist-human-level.md`](phase-wishlist-human-level.md): *reference, not queued*. None of
these get flags, effort estimates, or acceptance bars unless deliberately promoted later.

The actionable ARC patterns went elsewhere: pipeline-layer proposals in
[`phase-30-conjecture/phase-30-arc-patterns.md`](phase-30-conjecture/phase-30-arc-patterns.md);
the 30.4 literature-grounding design update in
[`phase-30-conjecture/phase-30-genuinely-novel-claims.md`](phase-30-conjecture/phase-30-genuinely-novel-claims.md);
lesson-overlay / prompt-skill notes in
[`phase-29-self-improving-paper-creation.md`](phase-29-self-improving-paper-creation.md) §29.2.3/§29.3.4.

---

## 1. Voice interface

- **ARC:** `researchclaw/voice/` — `transcriber.py` (speech→text via a configurable Whisper-style
  endpoint), `synthesizer.py` (text→speech), `commands.py` (voice command parsing for pipeline
  control: start/status/pause).
- **ET shape, if ever wanted:** voice control of room/research operations through the existing
  Spring controllers; narrated digests of government/research events.
- **Why not priority:** pure UX; touches no research-quality lever. ET's operator surface (REST +
  logs + paper-review reports) is sufficient.

## 2. Calendar / deadline scheduling

- **ARC:** `researchclaw/calendar/` — `deadlines.py` (conference-deadline dataset),
  `planner.py` (schedule pipeline runs to land before a deadline), `reminder.py`.
- **ET shape:** ET already has its own cadence (`ConjectureMinerService` every 5 min, research
  cycle hourly, frontier escalation 6-hourly) via Spring `@Scheduled`; a deadline-aware planner
  only matters if ET papers ever target external venues.
- **Why not priority:** ET publishes to its own corpus on its own clock; no external deadlines
  exist.

## 3. Collaboration pub-sub (shared KB across instances)

- **ARC:** `researchclaw/collaboration/` — `publisher.py` / `subscriber.py` (publish findings to /
  subscribe from a shared knowledge repository), `dedup.py` (avoid two team members re-running the
  same topic), `repository.py`.
- **ET shape:** would matter only with **multiple ET instances** sharing a conjecture corpus —
  publish surviving conjectures/anomalies to a shared store, subscribe to others' retirements so a
  refuted bound retires everywhere, dedup investigations across instances. The single-JVM
  constraint (known limitation) makes this moot today; if distributed execution ever lands, this
  is the coordination pattern to revisit.
- **Why not priority:** one instance, one corpus; the dedup problem is already solved locally
  (triviality dedup + QD archive + investigation rotation).

## 4. MCP server (expose ET to external agents)

- **ARC:** `researchclaw/mcp/` — `server.py`, `tools.py`, `registry.py`, `transport.py`,
  `client.py`. Exposes the pipeline as Model Context Protocol tools so external agents can drive
  runs and read results. Security-relevant detail from the review: ARC keeps its MCP surface
  **read-only** (status/results/paper fetch with regex-validated run IDs and
  `is_relative_to` path confinement) — no code-execution tool is exposed. That restraint is the
  part worth copying if ET ever does this.
- **ET shape:** an MCP server exposing read-only tools — `list_papers`, `get_paper`,
  `get_frontier_state`, `get_conjecture_pool`, `get_run_health` — would let a Claude
  Code / IDE session interrogate the corpus directly instead of via psql/log spelunking.
  Write/trigger tools (start investigation, flip flags) should stay out, mirroring ARC.
- **Why not priority:** operator tooling, not research capability. Revisit if corpus-inspection
  friction becomes a recurring time sink.

## 5. Setup wizard

- **ARC:** `researchclaw/wizard/` — `quickstart.py` (interactive first-run config),
  `templates.py` (config templates), `validator.py` (config validation with actionable errors);
  paired with a `doctor` health-check command (`health.py`: API connectivity, sidecar/tool
  availability, disk, GPU).
- **ET shape:** the closer match is the **doctor** half, not the wizard half: a single
  `et doctor` (or actuator endpoint) verifying Julia sidecar `:8088`, Python sidecar `:8089`,
  GPU engine `:50051`, LLM provider reachability, secrets vault, and pgsql — replacing the
  current RUN.md manual checklist. First-run wizardry matters less for a single-operator
  project.
- **Why not priority:** ET has one operator who already knows the checklist; value appears only
  on new-machine setup (RUN.md covers it).

## 6. OpenClaw integration (bridge + agent-operable service contract)

- **ARC:** two distinct layers —
  1. **Agent-operable service contract:** ARC ships `RESEARCHCLAW_AGENTS.md` so any capable
     assistant (OpenClaw, Claude Code, …) given the repo URL can self-serve: read the contract,
     learn the orchestrator role, clone/install/configure/run, and return results from a single
     chat message ("Research X" → paper).
  2. **OpenClaw bridge** (`config: openclaw_bridge`, see `docs/integration-guide.md`): six
     **typed adapter protocols**, each behind a capability flag — the host assistant provides the
     capability, ARC's adapter consumes it with no code changes:
     - `use_cron` — scheduled research runs
     - `use_message` — progress notifications to Discord/Slack/Telegram/Lark/WeChat
     - `use_memory` — cross-session knowledge persistence
     - `use_sessions_spawn` — parallel sub-sessions for concurrent stages
     - `use_web_fetch` — live web search during literature review
     - `use_browser` — browser-based paper collection
     Related: the **ACP backend** (`llm.provider: acp` via `acpx`) lets any ACP-compatible CLI
     agent (Claude Code, Codex, Copilot, Gemini, Kimi, OpenCode) *be* the LLM backend with one
     persistent session across all stages — no API keys.
- **ET shape:** ET already has an `AGENTS.md`, so layer (1) is half-present — the gap is that it
  documents the codebase for *developers*, not run-and-operate instructions for an *operator
  agent* (start the JVM, check sidecars, trigger a cycle, fetch results). The transferable design
  from layer (2) is the **typed capability-adapter** idea: define narrow ports (notify, schedule,
  fetch) that an external assistant can fulfill, instead of hard-wiring integrations. The
  `use_message` port overlaps the notification plumbing already sketched for the cost-guard and
  publish-approval proposals (phase-30-arc-patterns §B5/§B6) — if those ship, design the
  notification interface as a port from day one so a future bridge is config, not code.
- **Why not priority:** ET is operator-driven, not chat-driven; nothing currently needs an
  assistant in the loop. The cheap, no-regret slice — extending AGENTS.md with an "operating ET"
  section — can ride along with any future doc refresh.

---

*Promotion rule:* if any item here becomes load-bearing, move it into a phase doc with a flag,
effort estimate, and acceptance bar — don't grow this file into a shadow queue.
