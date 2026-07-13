# AGENTS.md - EmergentTerrain

Practical repo guidance for the Spring Boot app and the Rust GPU engine.

## Repo locations
- Java web app: `/opt/apps/ide/eclipse/workspace-web/EmergentTerrain`
- Rust GPU engine: `/opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine`

## Required environment
- Java 21
- Maven
- PostgreSQL
- Rust / Cargo
- `protoc`
- Vulkan userspace stack for the GPU engine
- PowerShell (`pwsh`) if you want to run the existing `.ps1` helper scripts on Fedora
- Node.js and the Codex CLI when using the enabled ChatGPT/local-ai provider

Fedora bootstrap helper:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
bash ./scripts/setup-fedora-prereqs.sh
```

That script installs the local Fedora prerequisites for both repos, initializes PostgreSQL, creates `emergentterrain` and `emergentterrain_perf`, and installs the optional Playwright browser dependency unless told to skip it.

## Run the app
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH="$JAVA_HOME/bin:$PATH"

cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn spring-boot:run
```

For the configured primary LLM, prefer `./scripts/start.sh`: it starts the
local `local-ai-gateway` on `127.0.0.1:8788` before launching the JVM.
The proxy uses the machine's `codex login` session with an ephemeral read-only
turn in `/tmp`; it pins the signed-in account's confirmed default,
`gpt-5.6-sol`, and reports its token usage for API-equivalent budget accounting.
The ChatGPT-authenticated CLI rejects `gpt-5-codex` as an explicit model
selection on this installation. Claude is intentionally dormant
(`legacy-ai.enabled: false`); OpenRouter is the fallback. Set
`ET_DISABLE_LOCAL_AI_GATEWAY=1` to skip the bridge, or `ET_ENABLE_LEGACY_AI_GATEWAY=1`
only to start the preserved legacy AI gateway for rollback testing. The Codex pool
has `parallelism: 2`; keep it small because every request launches a Codex turn.

If tests or runtime need a real DB, ensure PostgreSQL is running and the app datasource properties are correct in `src/main/resources/application.yml` or via env vars.

## Build
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn -DskipTests package
```

## Tests
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn test -DtrimStackTrace=false
```

Real two-process GPU smoke check:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
./scripts/run-real-gpu-e2e.sh
```

What it proves:
- the Java app can reach the real Rust GPU engine on `127.0.0.1:50051`
- `POST /game/state` returns non-stub GPU-generated world data
- room bot positions change while the Java app is running against the live engine
- a live proposal applies through `rebuild_patch`
- the engine log contains `wgpu-world`, `wgpu-patch`, and `wgpu-bot-sim` proof markers

Logs are written under `sample-output/gpu-e2e-*`.

Release-binary perf comparison:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
./scripts/compare-cpu-vs-gpu-performance.sh --iterations 2 --build-artifacts
```

That script is the supported Fedora performance path. It uses the packaged Spring Boot jar, the Rust `release` binary, and the `perf` Spring profile instead of `spring-boot:run` / `cargo run`.

## Benchmarks
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn -DskipTests -Pbench package
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar -f 1 -rf json -rff target/jmh-result.json
```

GPU benchmarks require the Rust GPU engine running. Use `GPU_ENGINE_HOST` / `GPU_ENGINE_PORT` or `-Dgpu.host` / `-Dgpu.port` to point at it.

## GPU engine
See `/opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine/README.md` for full setup.

Quick run:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine
cargo run
```

Override the default bind:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine
GPU_ENGINE_ADDR=127.0.0.1:50052 cargo run
```

## Runtime ports
- Web app: `http://localhost:8080/`
- GPU engine gRPC: `127.0.0.1:50051` by default

## Architecture notes
- `src/main/java/com/emergentterrain/service/RoomService.java` is the core gameplay loop.
- `src/main/java/com/emergentterrain/service/WorldService.java` can call the GPU engine when `app.gpu.*` flags are enabled.
- `src/main/java/com/emergentterrain/government/GovernmentTimelineService.java` stores compact government trend history for on-demand UI fetches and rolls old samples from per-tick to 1-minute, 5-minute, then 15-minute buckets.
- `src/main/resources/static/js/game.js` now calls `/room/{roomId}/government-trend` on demand and renders the sidebar trend chart in the government section; the chart is manual-refresh and window-switch driven rather than fed by periodic pushes.
- `src/main/resources/static/js/government-trend.js` powers the dedicated `/game/government-trend` page with hover inspection, series toggles, and adaptive scaling; use it instead of reimplementing another trend client, and keep it in sync with sidebar trend fields when adding new government metrics.
- Government action items now support multi-line detail payloads through `GovernmentActionSummary.details`; prefer filling those for bills, orders, and court cases rather than cramming all context into one summary sentence.
- Court-case summaries should prefer challenged titles and issue statements over bare ids, and repeated reviews for the same unchanged posture should be deduped before adding another `CourtCase`.
- `GovernmentState` now also carries institution profiles, structured bill/order substance, and legal precedents; keep those in sync when extending government simulation rather than adding new one-off strings or hard-coded court outcomes.
- The sidebar’s government section now renders institution and precedent cards alongside parties, so changes to those payloads should be reflected through `GovernmentSummary` rather than left stranded in state-only fields.
- Additional first-pass government layers now live in state as civic actors, administrative actions, political pressure events, federal/state conflicts, implementation outcomes, and historical markers; when extending those flows, prefer enriching the shared summary builders instead of creating hidden server-only side channels.
- Those layers now also feed back into votes, executive-order issuance, court block risk, and trust/service aggregates, so changes to them can have gameplay effects beyond the sidebar.
- The newer government feedback loops are now tunable through `app.government.feedback.*` and `app.government.thresholds.*`; prefer exposing new weights there instead of baking more constants into `GovernmentSimulationService`.
- Bot civic state now also participates in the government loop via `proceduralState` and `sanctionState`; when adding more individualized enforcement, prefer updating bot civic state and pressure generation together rather than inventing disconnected side records.
- Civic legal climate is now surfaced through `GovernmentSummary.civicLegalItems` and trend payload counts/shares, so new individualized government layers should generally wire through those before adding another bespoke panel.
- `src/main/java/com/emergentterrain/gpu/GpuEngineCodec.java` handles tile and bool-grid encoding.
- `src/main/resources/application.yml` holds runtime defaults including GPU, consensus, cache, bot, simulation-history, government feedback thresholds, and `app.government.timeline.*` retention settings.
- Append-only simulation history is written under `simulation-history/constitution`, `simulation-history/events`, `simulation-history/elections`, `simulation-history/courts`, and `simulation-history/legislation`.
- `src/main/java/com/emergentterrain/research/ResultClassification.java` is an enum with four values (SUPPORTED, PARTIALLY_SUPPORTED, NULL_RESULT, DEGENERATE) that drives narration prompt framing, paper status, and downstream guards. It is assigned deterministically by `UniversityService.classifyResult()` before any LLM call.
- `src/main/java/com/emergentterrain/research/PreRegistration.java` is a record holding the LLM-written hypothesis, prediction, falsification condition, and mapping notes — locked before computation runs. It is populated by `LlmService.parseFormulationResult()` and stored on `PaperDraft`.
- `UniversityService.classifyResult()` is the sole source of truth for result classification; it is static package-private and directly unit-tested. Do not call it after narration — classification must precede narration.
- `LlmService.parseFormulationResult()`, `parseNarrationResult()`, `parseRefinementDecision()`, `parseNoveltyAssessment()`, `parseStatisticalFraming()`, `parseCritiqueResult()`, and `parseCritiqueWeaknesses()` are static package-private methods so they can be unit-tested without a Spring context or LLM. `parseCritiqueResult()` returns a `CritiqueResult` carrying both a weakness list and an optional `ExperimentalGap`; `parseCritiqueWeaknesses()` delegates to it. When modifying JSON parsing logic, update these methods and their tests together.
- NULL_RESULT papers are stored in the `published` map with status `"NULL_RESULT"` and are excluded from: anomaly replication queues, cross-domain knowledge bridging, and the `avgPeerScore` in `getStats`. Only papers with status `"PUBLISHED"` count as positive results.
- The formulation prompt includes anomalous papers from adjacent topics in the same domain (z ≥ 1.5) to enable cross-topic reasoning; this section is empty when no anomalies exist and costs no extra LLM call.
- **Novelty check (Gap 1):** A fast Ollama call runs inside the formulation slot before the main formulation call whenever ≥3 prior papers exist in the topic. It returns a `novelty_score` (0=saturated, 1=fresh) plus a coverage note and unexplored-hint. Score < 0.30 injects a NOVELTY WARNING into the formulation prompt redirecting the LLM toward the unexplored angle; scores 0.30–0.59 inject a softer NOVELTY NOTE. Fewer than 3 papers → score assumed 1.0, no extra call.
- **Statistical framing (Gaps 4 & 5):** After refinement and before narration, a fast Ollama call rewrites `rawFindings` with appropriate significant figures (3 sig figs max), uncertainty qualifiers ("approximately", "within ~X%", "±"), and scientific notation for extreme values. Skipped for dynamic papers. Falls back to raw findings on failure. The framed text replaces `rawFindings` in the draft passed to narration.
- **Professor-student critique + revision (tool-calling path):** After first-pass narration, the professor (Groq) is given a tool suite — `run_param_sweep`, `run_predicate_scan`, `evaluate_symbolic`, `run_sandboxed_code` — and can call any combination before writing a synthesis. Sandboxed code is reviewed by the fast 4b model before execution; the interpreter itself enforces safety (no I/O, no imports, 5 000-iteration cap). After the professor's synthesis, a student (second Groq call) revises the paper incorporating the experimental evidence. Falls back to the JSON-based ExperimentalGap path when Groq is not configured. Total narration cost: 1 primary (tool-calling) + 1 primary (revision) per paper.
- **`SandboxEngine`** (`research/SandboxEngine.java`): mini-language interpreter supporting JavaScript-like pseudocode (var/for/if/return, arithmetic, `&&`/`||`, ternary, `n++`, `+=`, and whitelisted built-in functions). Static entry points: `run(code, start, end, param1, param2)` and `evalExpr(expression, start, end)`.
- **`MathToolSuite`** (`service/MathToolSuite.java`): builds the tools-array JSON for Groq and provides a `buildExecutor(draft)` factory that returns a `Function<String, String>` routing tool-call JSON to the appropriate engine. The 4b code review for sandboxed code uses `router.callFast()`.
- **`GenericOpenAICompatPool.callWithTools(systemPrompt, userPrompt, toolsJson, executor)`**: multi-turn HTTP loop. Adds tool results as `role:tool` messages, retries on model failure, forces final synthesis after `MAX_TOOL_ROUNDS`. Routed via `LlmRouter.callPrimaryWithTools()` across every tool-capable pool in the primary chain.
- **`LlmRouter.supportsProfessorTools()`**: returns true when the primary chain has an available tool-capable model (registry-aware); used by `LlmService` to branch between tool-calling and JSON-based critique paths.
- **Cross-bot anomaly mentoring (Gap 2):** `buildGenomeMentoringPrompt` now includes a cross-domain anomaly section showing other bots' anomalous findings (|z| ≥ 1.5) in domains different from the mentored bot's. The mentor prompt instructs the advisor to consider whether any structural pattern (mathematical property, scale threshold) transfers across domains. The mentoring callback now passes 40 recent papers instead of 20 for broader coverage.
- **`LlmConcurrencyLimiter`** (`service/LlmConcurrencyLimiter.java`): every LLM call in `GenericOpenAICompatPool` acquires a permit through it. Bounded wait (≤ `requestTimeout`, then throws — never blocks the synchronous research cycle) **and** a priority reservation: non-priority calls (no `LlmCallContext` role — the civic simulation, background) are capped at `parallelism-1` so a role-tagged research-pipeline call always has a permit. Fixes the civic-flood starvation that stalled paper generation. `currentRole() != null` = priority.
- **`ConjectureTrivialityFilter`** drops a conjecture when its **LHS** is a window-geometry scalar (`log_window_start`/`log_window_size`/`sqrt_window_size`) — a free harness coordinate can't be the bounded subject; geometry-as-RHS stays (binding-regime case). The decisive gate is `ConjectureMinerService.nextN`, which filters the investigation queue (both tiers, incl. counterexample-retired) through `dropReason` — the live-store `reduce()` alone misses geometry bounds that get counterexample-retired before triviality runs.
- **NULL_RESULT downgrade prose** (`UniversityService` numeric path + `ResearchNarrationService.downgradeNarrationToNull`): on a citation/numeric-consistency downgrade the findings+conclusion are rewritten to abstain around the *untraceable prose numbers* ("no result is claimed; the computation is recorded in the Methods trace"), NOT "the hypothesis remains untested" — that wording is false against the populated trace and trips `DirectionOfFindingValidator.CONCLUSION_NULL`. Keep new downgrade wording clear of those phrases.
- **`FIRST_PASS_METRIC_GUARD_RULE`** (active via `app.research.first-pass-metric-guard.enabled`) bars the first-pass narrator from asserting any numeric value absent from the tool output — including textbook/theoretical constants (`1/ln 2`) and round-number characterizations ("close to 5"), not just the older quality-score/optimum case. See `docs/2026-06-04-pipeline-hardening.md` for the full set of review-driven pipeline fixes.

## Constraints
- Do not commit local outputs under `sample-output/`.
- Avoid touching `target/` or generated test reports in commits.
- Keep secrets out of git. Use env vars or local overrides for credentials.
- If behavior, setup, or performance workflows change, update both `README.md` and `AGENTS.md` in the same change.
- **Check `application.yml` for live flag state, not the `@Value` default.** Several flags read `…:false` in the Java `@Value` annotation but are set `true` in YAML (e.g. `first-pass-metric-guard`, `revision-trace-grounding`, the `app.research.discovery.*` flags). A flag's annotation default is not its running value.
- **`research/<room>/` resets on app restart** — published paper `.md` files and `REVIEW_REPORT.md` are wiped; the paper-ID counter keeps climbing (gaps = generated-but-not-persisted). Treat each post-restart `/paper-review` as a fresh batch.
- Add new learnings from setup, debugging, performance work, or integration checks to markdown in the same change.
- **Keep wording domain-neutral in pipeline-wide code.** Code applied to every paper regardless of domain — the `SinglePointSweepValidator` gate and the revision-stage grounding rules (`EXECUTED_SCOPE_RULE`/`DEFINITION_GROUNDING_RULE` appended in `ResearchNarrationService.buildRevisionPromptFromSynthesis`) — must phrase its prompt rules, validator messages, and examples generically, not around the one domain that surfaced the bug (most papers here are MATHEMATICS conjecture papers, so fixes drift math-specific). Reserve domain-specific wording (mertens/window/`log_window_*` terms) for domain-gated code like the `verify_conjecture` tool output, which is advertised only when a `ConjectureVerifier` is wired. Before editing a user-facing string, check whether its call site is domain/paper-kind-gated or runs for everything.
