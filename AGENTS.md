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

Fedora 44's native repositories ship Java 25 but not the required OpenJDK 21 package. Use
the signed official Adoptium RPM repository and install `temurin-21-jdk`, then set
`JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk` for this project. Do not needlessly change the
machine-wide `java` alternative. See the [official Adoptium Linux instructions](https://adoptium.net/installation/linux/).
After Temurin is installed, `setup-fedora-prereqs.sh` detects it, omits the unavailable native
`java-21-openjdk-devel` package, and prints the corresponding `JAVA_HOME` value.

## Run the app
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export PATH="$JAVA_HOME/bin:$PATH"

cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn spring-boot:run
```

For the configured primary LLM, prefer `./scripts/start.sh`: it starts the
local `local-ai-gateway` on `127.0.0.1:8788` before launching the JVM.
It first performs the graceful `./scripts/stop.sh` sweep and a clean Maven
build, so each start reloads the app and its managed proxies without retaining
stale compiled classes.
The proxy uses the machine's `codex login` session with an ephemeral read-only
turn in `/tmp`; it pins the locally validated `gpt-5.6-terra`, and reports its
token usage for API-equivalent budget accounting at Terra's $2.50 input / $15
output per million-token public rates, including streamed narration through a
final SSE usage frame.
The ChatGPT-authenticated CLI rejects `gpt-5-codex` as an explicit model
selection on this installation. Claude is intentionally dormant
(`legacy-ai.enabled: false`); OpenRouter is the fallback. Set
`ET_DISABLE_LOCAL_AI_GATEWAY=1` to skip the bridge, or `ET_ENABLE_LEGACY_AI_GATEWAY=1`
only to start the preserved legacy AI gateway for rollback testing. The Codex pool
has `parallelism: 2`; keep it small because every request launches a Codex turn.
For the exact local Codex/Claude login, proxy connection, enablement, and
verification procedure, see `docs/subscription-providers.md`.

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

On sandboxed hosts, Mockito's inline mock maker cannot dynamically attach Byte Buddy even
when Maven runs on Java 21. For mock-based synthesis tests, preload the local Maven-cache
agent instead (tested: all 11 `SynthesisPaperServiceTest` tests pass):
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export PATH="$JAVA_HOME/bin:$PATH"

cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn test -Dtest=SynthesisPaperServiceTest \
  -DargLine=-javaagent:$HOME/.m2/repository/net/bytebuddy/byte-buddy-agent/1.15.11/byte-buddy-agent-1.15.11.jar
```

Java 25 can run that command with the preloaded agent, but Java 21 remains the supported
runtime for compiling and running the application.

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
- Web app: `http://127.0.0.1:8081/`
- GPU engine gRPC: `127.0.0.1:50051` by default
- Related-service health: `./scripts/status-related-apps.sh` first runs an
  authenticated `SELECT 1` against the configured PostgreSQL datasource, then
  reports the Spring app (`8081`), local AI gateway (`8788`), optional legacy AI gateway
  (`8787`), Julia math sidecar (`8088`), PyCall/grounding sidecar (`8089`), and
  GPU gRPC listener (`50051`) without changing any process. PostgreSQL uses the
  app's `PG_URL`, `PG_USER`, and `PG_PASSWORD` values by default; override only
  the status check with `ET_POSTGRES_URL`, `ET_POSTGRES_USER`, and
  `ET_POSTGRES_PASSWORD`. It prints each listener's PID and active `ps`
  command, plus its start command when absent. The disabled legacy AI gateway is
  `DORMANT`; PostgreSQL or other unavailable required services make it exit
  nonzero. It uses color when attached to a terminal; `NO_COLOR=1` disables it.
  Both proxies are lifecycle-managed by `./scripts/start.sh`: Codex starts by
  default and Claude only with `ET_ENABLE_LEGACY_AI_GATEWAY=1`. `/login` redirects
  are ready-but-protected, and `ET_STATUS_COMMAND_CHARS` controls abbreviation
  of long active `ps` commands.
- **Runtime-state checks:** always use `./scripts/status-related-apps.sh` to
  determine whether the app or related services are running; do not substitute
  ad-hoc process or port checks.

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
- **Phase 36 range-preserving chunk slice** — `RangePreservingChunkExecutor` journals mutable,
  restart-safe research execution under `simulation-history/research-experiments/` (not
  `research/<room>/`, which resets). With
  `app.research.experiments.range-chunking.enabled=true`, only the metric-safe
  count-weighted-density CONSTANT scale-ladder paths chunk a wide logical window; their union
  remains exactly the original `[start,end)` request and they resume only unfinished chunks. The
  eligible scalars are the literal per-integer density metrics in `MathInvariants`, including
  `richly_divisible_density`; their reducer is count-weighted density. Do not add another metric
  until it has an exact reducer or enough state. A ladder must check Julia conjecture-sidecar
  readiness before scheduling its wide request. Keep `app.julia.conjecture.timeoutMs=15000` for
  now: number-theory requests use that deadline rather than the generic Julia timeout, and a
  request timeout does not mark a previously healthy sidecar globally unavailable. Use the
  separately recorded actual queue, compute, and transport times before changing deadlines or
  concurrency. Disable the flag for rollback.
- `src/main/java/com/emergentterrain/research/ResultClassification.java` is an enum with four values (SUPPORTED, PARTIALLY_SUPPORTED, NULL_RESULT, DEGENERATE) that drives narration prompt framing and the semantic outcome. It is assigned deterministically by `UniversityService.classifyResult()` before any LLM call. `PublicationStateResolver` then combines that semantic outcome with independent citation, sensitivity, and replication deficits: deficits can route a supported result to `NEEDS_REVISION`, but never relabel it as `NULL_RESULT`; a genuine null remains `NULL_RESULT` with deficits auditable through review state and call-path stamps.
- `src/main/java/com/emergentterrain/research/PreRegistration.java` is a record holding the LLM-written hypothesis, prediction, falsification condition, and mapping notes — locked before computation runs. It is populated by `LlmService.parseFormulationResult()` and stored on `PaperDraft`.
- `UniversityService.classifyResult()` is the sole source of truth for result classification; it is static package-private and directly unit-tested. Do not call it after narration — classification must precede narration.
- `LlmService.parseFormulationResult()`, `parseNarrationResult()`, `parseRefinementDecision()`, `parseNoveltyAssessment()`, `parseStatisticalFraming()`, `parseCritiqueResult()`, and `parseCritiqueWeaknesses()` are static package-private methods so they can be unit-tested without a Spring context or LLM. `parseCritiqueResult()` returns a `CritiqueResult` carrying both a weakness list and an optional `ExperimentalGap`; `parseCritiqueWeaknesses()` delegates to it. When modifying JSON parsing logic, update these methods and their tests together.
- NULL_RESULT papers are stored in the `published` map with status `"NULL_RESULT"` and are excluded from: anomaly replication queues, cross-domain knowledge bridging, and the `avgPeerScore` in `getStats`. Only papers with status `"PUBLISHED"` count as positive results.
- The formulation prompt includes anomalous papers from adjacent topics in the same domain (z ≥ 1.5) to enable cross-topic reasoning; this section is empty when no anomalies exist and costs no extra LLM call.
- **Novelty check (Gap 1):** A fast Ollama call runs inside the formulation slot before the main formulation call whenever ≥3 prior papers exist in the topic. It returns a `novelty_score` (0=saturated, 1=fresh) plus a coverage note and unexplored-hint. Score < 0.30 injects a NOVELTY WARNING into the formulation prompt redirecting the LLM toward the unexplored angle; scores 0.30–0.59 inject a softer NOVELTY NOTE. Fewer than 3 papers → score assumed 1.0, no extra call.
- **Statistical framing (Gaps 4 & 5):** After refinement and before narration, a fast Ollama call rewrites `rawFindings` with appropriate significant figures (3 sig figs max), uncertainty qualifiers ("approximately", "within ~X%", "±"), and scientific notation for extreme values. Skipped for dynamic papers. Falls back to raw findings on failure. The framed text replaces `rawFindings` in the draft passed to narration.
- **Professor-student critique + revision (tool-calling path):** After first-pass narration, the professor (Groq) is given a tool suite — `run_param_sweep`, `run_predicate_scan`, `evaluate_symbolic`, `run_sandboxed_code`. Its first evidence-bearing turn sends `tool_choice: required`, preventing an empty trace; later turns retain the model's normal choice to synthesize or continue investigating. `BindingExtensionPolicy.TIGHT_RELATIVE_SLACK` is the inclusive 5% boundary shared by `verify_conjecture`, the extension policy, and affine publication validators: above it the tool must label a HELD result `LOOSE` and prescribe tightening, never a binding regime. When a `verify_conjecture` result explicitly names the same-grid refuting intercept after a `TIGHT HOLD`, `BindingExtensionPolicy` locks and executes both the deterministic independent-grid replication (unchanged affine parameters) and that exact verifier-selected call before final synthesis; the selected trace records both. Sandboxed code is reviewed by the fast 4b model before execution; the interpreter itself enforces safety (no I/O, no imports, 5 000-iteration cap). After the professor's synthesis, a student (second Groq call) revises the paper incorporating the experimental evidence. Falls back to the JSON-based ExperimentalGap path when Groq is not configured. Total narration cost: 1 primary (tool-calling) + 1 primary (revision) per paper.
- **Trace-grounded revision fallback (2026-07-20):** If a tool-bearing student revision has no
  parseable content, `ResearchNarrationService` uses `traceGroundedRevisionFallback`, stamped
  `rev:parse_failed→trace_fallback`, rather than returning first-pass prose. It writes only the
  executed trace and concludes `REFUTED`/no positive result when any tool result is REFUTED. Keep
  this wording domain-neutral; the no-trace case retains the legacy first-pass fallback.
- **`SandboxEngine`** (`research/SandboxEngine.java`): mini-language interpreter supporting JavaScript-like pseudocode (var/for/if/return, arithmetic, `&&`/`||`, ternary, `n++`, `+=`, and whitelisted built-in functions). Static entry points: `run(code, start, end, param1, param2)` and `evalExpr(expression, start, end)`.
- **`MathToolSuite`** (`service/MathToolSuite.java`): builds the tools-array JSON for Groq and provides a `buildExecutor(draft)` factory that returns a `Function<String, String>` routing tool-call JSON to the appropriate engine. The 4b code review for sandboxed code uses `router.callFast()`.
- **`GenericOpenAICompatPool.callWithTools(systemPrompt, userPrompt, toolsJson, executor)`**: multi-turn HTTP loop. Adds tool results as `role:tool` messages, retries on model failure, forces final synthesis after `MAX_TOOL_ROUNDS`. Routed via `LlmRouter.callPrimaryWithTools()` across every tool-capable pool in the primary chain.
- **`LlmRouter.supportsProfessorTools()`**: returns true when the primary chain has an available tool-capable model (registry-aware); used by `LlmService` to branch between tool-calling and JSON-based critique paths.
- **Cross-bot anomaly mentoring (Gap 2):** `buildGenomeMentoringPrompt` now includes a cross-domain anomaly section showing other bots' anomalous findings (|z| ≥ 1.5) in domains different from the mentored bot's. The mentor prompt instructs the advisor to consider whether any structural pattern (mathematical property, scale threshold) transfers across domains. The mentoring callback now passes 40 recent papers instead of 20 for broader coverage.
- **`LlmConcurrencyLimiter`** (`service/LlmConcurrencyLimiter.java`): every LLM call in `GenericOpenAICompatPool` acquires a permit through it. Bounded wait (≤ `requestTimeout`, then throws — never blocks the synchronous research cycle) **and** a priority reservation: non-priority calls (no `LlmCallContext` role — the civic simulation, background) are capped at `parallelism-1` so a role-tagged research-pipeline call always has a permit. Fixes the civic-flood starvation that stalled paper generation. `currentRole() != null` = priority.
- **`ConjectureTrivialityFilter`** drops a conjecture when its **LHS** is a window-geometry scalar (`log_window_start`/`log_window_size`/`sqrt_window_size`) — a free harness coordinate can't be the bounded subject; geometry-as-RHS stays (binding-regime case). The decisive gate is `ConjectureMinerService.nextN`, which filters the investigation queue (both tiers, incl. counterexample-retired) through `dropReason` — the live-store `reduce()` alone misses geometry bounds that get counterexample-retired before triviality runs.
- **NULL_RESULT downgrade prose** (`UniversityService` numeric path + `ResearchNarrationService.downgradeNarrationToNull`): on a citation/numeric-consistency downgrade the findings+conclusion are rewritten to abstain around the *untraceable prose numbers* ("no result is claimed; the computation is recorded in the Methods trace"), NOT "the hypothesis remains untested" — that wording is false against the populated trace and trips `DirectionOfFindingValidator.CONCLUSION_NULL`. Keep new downgrade wording clear of those phrases.
- **`FIRST_PASS_METRIC_GUARD_RULE`** (active via `app.research.first-pass-metric-guard.enabled`) bars the first-pass narrator from asserting any numeric value absent from the tool output — including textbook/theoretical constants (`1/ln 2`) and round-number characterizations ("close to 5"), not just the older quality-score/optimum case. See `docs/2026-06-04-pipeline-hardening.md` for the full set of review-driven pipeline fixes.

- **Phase 34 index-maintenance timing** — `scripts/prepare-hf-math-index.py` now emits elapsed
  seconds for FTS optimization, manifest/provenance finalization, and total runtime. Retain those
  values during Phase 35 timeout-recovery validation; do not run a second optimizer or importer in
  parallel, because SQLite FTS maintenance is a single-writer offline task.

## Constraints
- Do not commit local outputs under `sample-output/`.
- Avoid touching `target/` or generated test reports in commits.
- Keep secrets out of git. Use env vars or local overrides for credentials.
- If behavior, setup, or performance workflows change, update both `README.md` and `AGENTS.md` in the same change.
- **Check `application.yml` for live flag state, not the `@Value` default.** Several flags read `…:false` in the Java `@Value` annotation but are set `true` in YAML (e.g. `first-pass-metric-guard`, `revision-trace-grounding`, the `app.research.discovery.*` flags). A flag's annotation default is not its running value.
- **Durable paper-ID allocation (2026-07-24)** — every ID is reserved before narration in the
  append-only `simulation-history/research-paper-ids/<room>.paper-id-allocations.log` ledger.
  Suppressed, timed-out, and interrupted drafts therefore consume an ID even without a DB row or
  markdown file; do not derive the next ID solely from `research/<room>/` or the papers table. The
  ledger is allocation/audit state only and must never seed promotion, evidence, or paper content.
  `research/<room>/` may still archive/reset on restart; treat each post-restart `/paper-review` as
  a fresh output batch, but IDs remain stable across those batches.
- **Phase 34 publication labels** — `status` is workflow state; `publicationLabel` is the reader-facing tier (`INTERNAL_RESEARCH_NOTE`, `COMPUTATIONAL_RESEARCH_NOTE`, or `ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE`). The candidate tier requires `PUBLISHED`, a complete computational plan and trace, plus a successful `read_reference` result for a key emitted by `search_literature` and cited in related-work text. Artifact bundles go under `research/<room>/artifacts/<paper-id>/`, declare but never execute LLM-supplied reproduction commands, and are written from the final label. `./scripts/bootstrap-hf-math-literature.sh` downloads the UW theorem corpus by default; `HF_DATASET=marin-community/ar5iv-no-problem-markdown` downloads the full-text companion. The preparer creates a resumable provenance-recording SQLite/FTS5 index under `~/.emergentterrain/indexes/math-literature/`; use `--stage optimize` after a complete immutable import. The `fulltext_fts_complete` marker must be true before the read-only Java adapter can be advertised. The enabled Phase 35 path uses bounded ranked retrieval and validated relevance-safe planning; set its flag false for the byte-compatible Phase 34 all-terms-then-OR fallback. Both paths return only indexed full-text sources and opaque `HF...` keys. A fixed real-claim acceptance suite remains required before live output is publication-ready. The corpus retains UW labels and broad Marin full text; mathematics remains a query-time scope, not a destructive corpus restriction.
- **Phase 35 FTS5 retrieval efficiency** — the Phase 35 path is enabled by `app.research.literature.phase34-sqlite.phase35-retrieval.enabled` in `application.yml`; setting it `false` restores the legacy Phase 34 all-terms-then-OR path. It preserves source/key/gate semantics while using FTS5 `ORDER BY rank`, limiting FTS candidates before metadata/snippet joins, a strict 15-second aggregate budget with interrupt headroom, and no request-time index mutation. Each candidate checks `initial-candidates` (64 by default) first, then checks the unchanged 512-result range only if no evidence has been issued; this is an optimization, not a recall reduction or a paper-specific privilege. The theorem-record FTS surface is tried first; a no-match retries the same safe subject pair plus its independent corroborating anchor against the indexed full-text surface. Completion telemetry at INFO records the outcome, elapsed time, candidate/batch count, inspected rows, result count, final surface, and final FTS expression. See `phases-for-human-like-performance/phase-35-fts-retrieval-efficiency/README.md`; run the fixed real-claim acceptance suite and concurrent-search smoke test after changing this flag or its timeout.
- **Durable literature-timeout recovery** — a result stating `Phase 34 SQLite retrieval exceeded` is neither evidence nor a verified no-match. `LiteratureTimeoutRecoveryQueue` holds that draft before publication and journals its `ReworkDraftSnapshot`, exact semantic request, and local index fingerprint under `simulation-history/research-literature-recovery/`. It uses OpenAlex only on its asynchronous worker to record possible open-access backfill candidates; `EvidenceCoreBackfill` consumes `AWAITING_INDEX_BACKFILL` candidate ids from that journal on its next offline run (`LITERATURE_RECOVERY_DIR` overrides the default). Those records are non-evidentiary, never receive `HF...` capabilities, and cannot satisfy a citation, `read_reference`, or publication-label gate. After an operator completes the ordinary offline core/index import, a changed index fingerprint re-dispatches the draft through the ordinary local search/read/revision path. Never add request-time index mutation or treat OpenAlex metadata/abstracts as source evidence. Configure this lane with `app.research.literature.timeout-recovery.*`; the live OpenAlex client is enabled solely for this bounded, ledgered discovery use and retains its daily credit ceiling/cache.
- **Phase 35 artifact smoke** — run `./scripts/run-phase35-artifact-smoke.sh` against the completed real index after changing retrieval, evidence lifecycle, or publication-label code. It is deterministic and writes only a JUnit temporary bundle: it requires the exact lifecycle `search_literature` issued key → successful `read_reference` → matching `[HF...]` Related Work citation, and asserts that omitting the read cannot reach `ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE`. It must not start the app, make an LLM request, or leave a research-room artifact behind.
- **Paper-review-screened literature index** — on every paper-review pass, prepare Phase 35 log
  plans only for the single app run that produced that review batch; no
  `--prepare-literature-backfill` flag is required. Use a bounded four-lane subject map (exact
  target/predicate, quantitative context, finite method, caveat) paired with the locked
  mathematical subject; do not treat the logged phrase or a mined affine coefficient as the
  subject by itself. New `schema_version: 2` manifests record `discovery_lane` and
  `relevance_tier`; only `DIRECT_TARGET` and `METHOD_CONTEXT` sources may be
  `RETAINED_FOR_IMPORT`. Deduplicate by canonical URL, arXiv ID, and SHA-256, download only text
  sources, and record URL/query/paper mapping/SHA-256.
  After validating `RETAINED_FOR_IMPORT` entries, the paper-review skill imports them with
  `prepare-paper-review-screened-literature-index.py --staged-text-manifest <manifest>` unless a
  primary or screened index writer is active; it then leaves the manifest staged pending safe
  import rather than running writers concurrently. That separate incremental SQLite/FTS5 index
  validates every file hash, refuses source-text replacement, records manifest provenance, and
  does not rebuild or mutate the primary Phase 34 index. Its staged directory is never a
  request-time search surface. The screened index becomes
  searchable only when `app.research.literature.paper-review-screened.enabled=true`; Phase 35 then
  combines it with primary results using the same planner, title/record relevance gates, opaque
  `HF...` capabilities, and `read_reference` flow. Never compare raw FTS rank values across the
  databases. A screened source still needs the normal issued key → successful read → matching
  Related Work citation chain before it supports evidence or changes a publication label. A missing
  companion is coverage state; a timeout is `UNRESOLVED_RETRIEVAL`, not proof that literature is
  absent. See `phase-37-paper-review-screened-literature/README.md` before changing this lane.
- **Paper-to-literature key map** — after every paper-review pass, run `./scripts/update-paper-literature-key-map.sh`. It regenerates `docs/paper-literature-key-map.md` from final artifacts in both `research/` and `research/archive/`; retain only a key issued by `search_literature` in `EVIDENCE`, successfully returned by `read_reference`, and cited in that paper's `## Related Work`. Never retain a search hit, snippet, failed read, or model relevance assertion alone. The map is a reviewer index; raw paper/trace artifacts remain authoritative. Both Codex and Claude paper-review skill copies require this regeneration.
- **Phase 35 deterministic post-search read** — with `app.research.literature.phase34-sqlite.deterministic-post-search-read.enabled=true`, the professor trace gets one bounded read only when it has an `EVIDENCE` result with an issued `HF...` key and no earlier `read_reference` attempt. It selects the first issued key in trace order and uses that search call's structured claim as the focused query; it does not search again, retry a failed read, make a relevance judgment, alter Related Work, or upgrade a label. The read is appended before student revision so the model can cite it, but `ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE` still requires the final Related Work to contain that same key. Set the flag false for rollback.
- **Cramér gap ratio protocol** — `cramer_gap_ratio` is a finite, window-dependent maximum-gap
  envelope, never a `verify_constant` convergence target. `CramerGapEnvelopePreRegistration`
  locks discovery plus far-location, offset, and width holdouts with `scale_ladder=false`; every
  requested effective width must survive to the executed trace. The publication gate demotes an
  incomplete protocol to `NEEDS_REVISION`; report only the finite envelope, never a limiting
  constant or theorem.
- **Phase 35 relevance-safe planning** — no additional index stage is required. `DeterministicLiteratureQueryPlanner` considers only adjacent non-generic lexical subject phrases from the claim, dropping generic connective, ordinal, measurement-framing, comparative/relation prose, and numeric/coefficient terms (for example `has`, `order`, `consecutive`, `requiring`, `elevated`, `discriminate`, `between`, `minus`, or `0000`) before candidate generation. A pair made entirely of weak measurement words such as `number density` is also invalid. Each candidate keeps its pair plus one early independent anchor, so later interpretive qualifiers do not discard a source that establishes the core subject/result. The adapter searches theorem-record FTS first; multi-anchor theorem matches need both pair anchors in the paper title plus the full candidate evidence in the returned record. A two-anchor `AND_PAIR` now also needs both case/plural-normalized subject anchors in title-level metadata before it can issue an evidence key; this rejects body-only coincidences such as the observed prime-gap/geometry false positive. If theorem search has no qualifying source, it searches the indexed full text with the same pair plus the independent corroborating anchor; a metadata or indexed full-text title must satisfy that same two-anchor rule, so generic body wording alone cannot issue a key. A title-less companion record is the narrow exception for a three-anchor exact phrase: it needs two non-weak phrase terms and all anchors in its indexed text. The fallback may issue a key only for that safe full-text match and still requires `read_reference`; it never accepts raw model FTS syntax. No valid source means no evidence/key. `LiteratureQueryPlanner` / `LiteratureQueryPlan` is the future AI-tool boundary: any model-proposed query must be one validated `EXACT_PHRASE` or `AND_PAIR` with two safe anchors and cannot bypass record-evidence/title-anchor validation before querying.
- **No-evidence related-work and zero-panel safeguards (2026-07-24)** — when every recorded
  `search_literature` call returns no relevance-safe evidence, Related Work may state that absence
  but cannot assert that outside theory, prior work, or the literature establishes a claim. The
  `related-work-evidence-gate` stamps the draft and routes it to `NEEDS_REVISION`; it is not a
  general fact checker. An active review panel returning zero reviewers likewise stamps
  `panel:n=0:degraded:unavailable:demoted_to_needs_revision` and persists as `NEEDS_REVISION`,
  retaining the selected trace for a later independent panel retry. Do not restore clean
  `PUBLISHED` status merely because a provider failed or a no-evidence lookup was convenient.
- **Cross-run finding promotion** — conjecture-pool write-back and the verified-results ledger are terminal-state gated: only `PUBLISHED` papers whose selected trace was written into an artifact and whose source paper record persisted may promote tool output. `NEEDS_REVISION`, `NULL_RESULT`, `PUBLISHED_NO_PROFESSOR`, and `REJECTED` outputs retain their local trace but cannot seed future hypotheses or cross-run evidence. Both paths consume only the final selected paper trace (which becomes the artifact evidence), never an accumulated losing-candidate trace or run-log file; a losing rework candidate or cache/disk-only persistence fallback also cannot promote. Each tight `HELD` bound additionally requires a nearby `REFUTED` variant on the identical grid.
- **Executable conjecture allocation (2026-07-30)** — validate the proposed study instrument before calling `nextPaperId`. A valid affine relation uses `ConjecturePreRegistration` and `verify_conjecture`; a registered `CONSTANT` scalar uses `ConstantLimitPreRegistration` and a locked `verify_constant` protocol; an unknown/placeholder affine scalar is skipped before draft allocation. `cramer_gap_ratio` is a slow constant-limit measurement, so its miner `_constant_` scaffold must never be sent to `verify_conjecture`. A v5 constant protocol has one deadline-aware geometric ladder plus two non-overlapping direct high-range holdouts (location and width); all three exact calls are required. The deadline-aware cap is `app.julia.conjecture.ladder-deadline-budget` and applies only to that ladder, not the declared holdouts. `verify_constant` emits per-call/rung sample standard deviation and standard error; do not describe raw finite-window values as a theorem.
- **Known constant-limit targets (2026-07-29)** — `KnownLimitRegistry` owns targets whose value follows from an exact instrument definition. `mean_divisor_excess` is the local-window statistic `mean_window(d(n)) − ln(window_center)` and locks `2γ ≈ 1.1544313298`; `2γ−1` belongs only to the different cumulative statistic `D(x)/x − ln(x)`. `omega_Omega_gap` is the mean-window difference `Ω(n) − ω(n)` and locks the established prime-power correction `≈0.77316`. Both known targets are calibration controls, not novelty claims. `ConstantLimitPreRegistration` records the known-limit id and canonical target, and `verify_constant` rejects a contradictory supplied target before doing work. An omitted target remains an exploratory estimate but cannot replace the locked confirmation target.
- **Locked affine conjecture tests (2026-07-23)** — normal conjecture investigations carry a versioned pre-registration with their exact lhs, rhs term(s), coefficients, and intercept. The selected trace must execute that exact `verify_conjecture` relation or the paper is `NEEDS_REVISION`. A verifier-suggested coefficient/intercept is a separately labelled sensitivity/binding probe, never a replacement hypothesis; retain the registered call and every corrective call in the selected artifact trace. Measurement-framed investigations are excluded because their registered object is a scale-ladder measurement rather than the miner's affine scaffold. But affine prose in any paper still needs the selected trace's exact same-relation/grid `HELD`/`REFUTED` pair (`app.research.affine-binding-claim-gate.enabled`). A verifier-named refuting intercept is authoritative even when percentage proximity to a zero intercept would fail. Every tight documented affine pair also needs a HOLD on the deterministic, policy-locked independent grid with unchanged terms, coefficients, intercept, width, and window count (`app.research.affine-binding-independent-grid-gate.enabled`); an arbitrary post-result second window cannot satisfy it.
- **Deterministic loose-bound completion (2026-07-30)** — with `app.research.professor.extra-rounds-on-binding.enabled=true`, a one-term `verify_conjecture` result that emits `LOOSE … re-verify at k=…` is completed by the generic tool loop using that exact verifier-selected coefficient and the unchanged grid. It runs at most once; a prior exact attempt, a malformed call, or a two-term relation never triggers a substitute. If the result is a `TIGHT HOLD`, the existing policy then locks the independent location grid, executes its same-coefficient hold plus changed-intercept sensitivity probe, and executes the verifier-named same-grid refutation. This is mechanical evidence completion, not a refit or a replacement of the pre-registered relation. An explicit `Mechanism kind: none` / `no mechanism identified` declaration is mechanically capped at `UNEXPLAINED_OBSERVATION`; it must remain a finite calibration even if a panel proposes a stronger label. Predicate/density literature calls must name their actual threshold and component arithmetic functions, not merely an internal scalar nickname or fitted coefficient, so the two validated source anchors carry the mathematical subject. The discovery tools share the explicit 2,000,000 direct-oracle ceiling with their descriptions and validation, so the LLM can choose an `x` the implementation actually routes to discovery.
- **`max_prime_gap` multi-scale fit/holdout protocol (2026-07-24)** — `max_prime_gap` investigations use `locked-max-prime-gap-multiscale:v1`, not the generic fixed-coefficient affine registration. Fit `k,c` only at `start=100_000_000,size=10_000,num_windows=10`; after the first tight fit, retain that exact pair for a changed-intercept same-grid REFUTED call and for tight (`relative slack <= 0.05`) holds at the 150M and 900M location holdouts, the 900,005,000 overlapping-offset holdout, and the 900M doubled-width holdout at `size=20_000,num_windows=5` (exactly the verifier's 100,000-integer cap). Do not let the model refit any holdout or replace a missing registered call with an exploratory grid. With `app.research.max-prime-gap-multiscale-preregistration-executor.enabled`, `MaxPrimeGapProtocolStateExecutor` carries only verifier-supplied `k` and `c` values through the tight fit, refutation, and ordered holdouts (at most six calls); the AI retains framing and interpretation. A failed automatic call is never retried or repaired by refit. The publication gate (`app.research.max-prime-gap-multiscale-preregistration-gate.enabled`) makes a residual omission `NEEDS_REVISION` and is the sole independent-holdout authority for this topic; do not also apply the generic affine independent-grid changed-intercept gate. See `docs/2026-07-24-max-prime-gap-multiscale-preregistration.md`.
- **`max_divisor_count` locked measurement study (2026-07-25)** — `max_divisor_count` investigations use `locked-max-divisor-count-measurement:v1`: first execute the fixed Wigert scale ladder (`window_size=5_000,num_windows=20,target=0.69315`), then fit `max_divisor_count` versus `wigert_bound` only at `start=1_000_000_000,size=5_000,num_windows=20`. After the first tight fit, retain that exact `k,c` for a changed-intercept same-grid REFUTED call and tight holds at the fixed 1.5B location, 1,000,005,000 offset, and doubled-width grids. Before workflow promotion, the selected trace must also issue a `search_literature` call using exactly `divisor` + `wigert`, successfully `read_reference` the returned key, and cite that key in Related Work. A loose/refitted holdout or unread/unrelated/uncited source routes the paper to `NEEDS_REVISION` under `app.research.max-divisor-count-measurement-preregistration-gate.enabled`. See `docs/2026-07-25-max-divisor-count-measurement-preregistration.md`.
- **Predicate baseline sensitivity (2026-07-23)** — an exact predicate census compared with a baseline must use at least three `run_predicate_scan` windows with one unchanged baseline, two widths, and a positively overlapping offset pair before it can publish cleanly (`app.research.predicate-baseline-sensitivity-gate.enabled`). Adjacent replicas at one width do not distinguish a finite result from an arbitrary window choice. This is a finite-design guard, not evidence of an asymptotic claim.
- **Predicate-study pre-registration (2026-07-23)** — before `DynamicComputeEngine.compute` runs, a valid dynamic directive is encoded as a `locked-predicate-study:v1` registration: predicate, metric, baseline, expected relation (`ABOVE_BASELINE`, `BELOW_BASELINE`, or `APPROXIMATELY_EQUAL`), original range, same-width overlapping offset, and second width. An `APPROXIMATELY_EQUAL` directive additionally needs one positive absolute `comparisonTolerance`; allocation rejects an omitted/invalid value, and every registered `run_predicate_scan` must repeat it as `comparison_tolerance` in the selected trace. The executor evaluates directional studies by their locked inequality and approximate studies by that locked band: inside is `SUPPORTED`, outside is `REFUTED`, with no wider post-hoc `PARTIAL` band. Legacy fallback bands only preserve old stored directives and cannot satisfy a new registration. Do not convert a confirmed exceedance/below-baseline result into `NULL_RESULT` because it is far from the comparison value. The professor must execute each registered tuple exactly or the paper is `NEEDS_REVISION` (`app.research.predicate-study-preregistration-gate.enabled`). Extra scans are exploratory; they cannot replace a registered call. See `docs/2026-07-23-predicate-study-preregistration.md`.
- **Question-served protocol clone screen (2026-07-26)** — `PaperOpenQuestion.protocolFingerprint` stores the normalized `mappingNotes` of its source paper's locked pre-registration. `ResearchCycleService.publishConjectureInvestigation` selects/render-serves a question, then compares that fingerprint with the candidate registration before `nextPaperId`, question reservation, or narration. An exact same-family match logs `question-serve: declined identical locked protocol before draft allocation` and leaves the question servable; a changed holdout/window/design, a different family, or a legacy row without a fingerprint proceeds. Do not weaken this to topic-only similarity: follow-up value lies in an explicit pre-registered protocol difference.
- **Protocol, availability, and confirmation screen (2026-07-30)** — Every newly persisted paper stores the normalized locked-study fingerprint. `ResearchCycleService` also compares it with recent same-topic published papers before ID allocation or narration; an exact match declines, while legacy rows without one retain the terminal content check. `ConjecturePreRegistration` declares (never executes in the app) `mvn test -Dtest=ConjecturePreRegistrationTest`. A tight affine result needs the unchanged-relation hold and a changed-intercept `REFUTED` sensitivity probe on the policy-locked independent grid. A v5 constant-limit registration locks `5,000×5` deadline-aware ladder rungs from `ladder_start=100,000`, a location holdout at 1.5B, and a non-overlapping `10,000×5` width holdout at 1.5001B. An exact trace must contain all three; requests that collapse placement or width cannot confirm. Every named numerical target—including a conjectural target such as the Hardy–Littlewood twin-prime constant—must complete issued-key → successful read → matching Related Work before scoring. Known targets are calibration controls; conjectural/open targets may claim only finite-window numerical consistency, never novelty or proof. The enabled trace-definition-availability gate routes a paper to `NEEDS_REVISION` only when prose denies a formula/window/registered instrument that its selected trace or locked registration records. A malformed tool-bearing student revision is retained as trace-only `NEEDS_REVISION`, not sent through panel/best-of-N selection. Phase 35 query planning retains claim-subject anchors even where document-count estimates omit one; title/evidence validation still owns key issuance.
- **Final panel revision and reviewer eligibility (2026-07-29)** — The panel-revision JSON contract includes `discussion` and `related_work`; a Related Work-only correction is material, not a no-op. When the selected trace both issues and successfully reads an `HF...` key, final narration must retain an exact `[HF...]` citation in `related_work`. Its omission stamps `literature:read_key_omitted:demoted_to_needs_revision` before best-of-N scoring, and a revision cannot say a definition, registered instrument, or trace table is unavailable when the selected trace records it. `app.research.review-panel.excluded-models` is a lower-cased CSV denylist for panel seats only: do not use it to disable a model's ordinary provider routing. The live DeepSeek exclusion is limited to its repeated panel tool/JSON parse failure; remove it only after a verified panel-format repair.
- **Question-served host smoke** — `QuestionServedProtocolCloneSmokeRunner` is dormant unless `app.research.question-serve.clone-smoke.enabled=true`. Run it with `app.university.enabled=false` and `spring.task.scheduling.enabled=false` so it persists one temporary exact-clone question, calls the real allocation service, requires no draft-ID/reservation/narration path, logs the allocation decline, and deletes the row. It is the safe live verification path; never use the normal scheduler merely to test this guard.
- **Phase 35 search observability** — every enabled lookup logs its exact FTS expressions plus per-candidate record-evidence and required-title anchors at INFO level in `logs/et-app.log`; use those values for manual SQLite/FTS tests. Completion logs also name the final search surface (`theorem` or `fulltext`). Repeated tokens emitted by an identifier such as `omega_Omega_gap` are normally skipped as invalid two-anchor pairs; the exact case-distinct mathematical pair `omega`/`Omega` is the narrow exception and maps to validated `prime` + `factors` title anchors with `multiplicity` evidence, preserving the ordinary source-read gate.
- **Narration deadline (2026-07-17)** — `app.research.narrationTimeoutMs` is 90 minutes and `stuckDraftTtlMs` is 100 minutes, so the narration timeout fires before the stale-draft reaper. This is room for a full best-of-N/FTS-backed cascade, not a replacement for Phase 35's bounded retrieval work. Keep the dormant legacy AI gateway's session TTL above the narration deadline (default: 95 minutes) if it is enabled for rollback testing.
- **Run-budget drain (2026-07-21)** — Global LLM cost is capped at `$5.00` with `drain-reserve-cost-usd: 2.80`, so the derived drain point remains `$2.20`. At drain, no new research starts and the aggregate `$2.80` is retained for already-started papers. Calls remain capped at 350 with a 60-call reserve (drain at 290). This is not per-paper escrow; do not describe it as a completion guarantee until optional work is credit-gated.
- **Bounded shutdown reconciliation (2026-07-26)** — A hard budget stop calls `ResearchShutdownReconciler` before `SpringApplication.exit`: it waits up to `app.research.shutdown-reconciliation.timeout-ms` (30s) for `ResearchCycleService`'s in-flight drafts, records every remaining durable paper ID and last phase as `shutdown_timeout:<phase>` suppression, then closes `UniversityService`'s publication gate for up to `publication-lock-ms` (5s). Do not bypass this by calling `SpringApplication.exit` directly from another budget path. If the gate cannot close, the process exits fail-closed without Spring context teardown; closing Hikari under a publication transaction reintroduces the P-b72c-77627930 `Socket closed` persistence race.
- **End-of-run paper summary** — `UniversityService` logs `Run paper summary` during context shutdown, listing the paper ID, title, and terminal status for papers created by that JVM only. It separately logs deliberately suppressed drafts and any still-active drafts with their last narration phase, so a hard budget stop leaves its sequence gaps auditable. Do not substitute the database/cache for these lists: both can contain historical rows from earlier app runs.
- Add new learnings from setup, debugging, performance work, or integration checks to markdown in the same change.
- **Keep wording domain-neutral in pipeline-wide code.** Code applied to every paper regardless of domain — the `SinglePointSweepValidator` gate and the revision-stage grounding rules (`EXECUTED_SCOPE_RULE`/`DEFINITION_GROUNDING_RULE` appended in `ResearchNarrationService.buildRevisionPromptFromSynthesis`) — must phrase its prompt rules, validator messages, and examples generically, not around the one domain that surfaced the bug (most papers here are MATHEMATICS conjecture papers, so fixes drift math-specific). Reserve domain-specific wording (mertens/window/`log_window_*` terms) for domain-gated code like the `verify_conjecture` tool output, which is advertised only when a `ConjectureVerifier` is wired. Before editing a user-facing string, check whether its call site is domain/paper-kind-gated or runs for everything.
