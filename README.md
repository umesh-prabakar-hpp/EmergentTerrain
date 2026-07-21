# EmergentTerrain

Spring Boot POC that turns a user profile into a signed QR payload and a deterministic, editable 2D game world.

## Stack
- Java 21, Spring Boot 3.x, Spring Security, Spring Data JPA (Hibernate)
- PostgreSQL
- Thymeleaf + Material Web (M3) components + minimal JS
- ZXing (QR generation)

## Features
- User registration + login (session-based)
- Profile creation (age, sex, location, yes/no preferences)
- Deterministic hidden physical properties derived from profile
- QR payload signing (HMAC), gzip + base64url encoding
- Negotiation-first multiplayer terrain (no solo mode)
- Neighbor-based consensus voting for 2x2 terrain changes
- Bots with persistent profiles and visible positions
- Highway corridors now persist as a separate overlay and are maintained by bot travel demand instead of being erased by normal terrain changes
- Bot map changes applied in-memory with periodic room state snapshot flushes
- Normal runtime now eases bot movement in the browser and crossfades terrain refreshes so the room feels less snap-based
- Pending votes now render as a live WebGPU influence field with staged pressure, propagation waves, and server-sent proposal / tile-transition deltas
- The browser now uses WebGPU for all world rendering layers: terrain, bots/players, proposal fields, transitions, particles, and the minimap
- Highways now emerge from repeated long-distance bot movement and exploration pressure. Ordinary terrain proposals no longer clear them, and unused corridors decay over time so roads do not take over the map.
- Constitutional simulation now spans the first three runtime slices: bots carry civic state, rooms carry government state, deterministic parties/elections/bills/budgets tick in maintenance, executive orders and court review can intervene, office roles are assigned to bots, and append-only records are written under `simulation-history/`
- Government trend history is now stored server-side and fetched on demand through `/room/{roomId}/government-trend`; recent points stay at per-tick resolution for 6 hours, then compact to 1-minute, 5-minute, and 15-minute buckets as they age.
- The game sidebar now renders that government trend history as an on-demand chart with trust/service/state-trust lines, action load, budget mix, and event markers; it loads only when the government panel is shown and refreshes manually from the UI.
- The in-game trend card now has an `Open` action that launches `/game/government-trend` in a new tab for a larger interactive chart with hover tooltips, series toggles, and adaptive vertical scaling so small trust/service changes do not disappear into a flat `0..1` baseline.
- Those trend views now also include the newer government signals end-to-end: pressure, implementation score, administrative/conflict load, and civic legal climate through petitioning/review/sanction counts and shares.
- Government action cards in the sidebar now include richer detail lines. Court entries show the plaintiff, defendant, legal basis, constitutional basis, and affected laws; bill and executive-order entries also expose jurisdiction, sponsor/issuer, and policy details.
- Court summaries now also carry the challenged bill/order title plus an issue statement describing what is being argued, and repeated court reviews of the same unchanged posture are suppressed so one bill does not spam near-identical case entries.
- Government institutions now carry legitimacy/workload/capacity profiles, bills and executive orders carry structured sections/rights/enforcement/funding metadata, and court review runs through a staged lifecycle with doctrine-driven scrutiny, generated plaintiffs, and precedent memory instead of a single hard-coded one-tick decision.
- The government sidebar now also surfaces institution-pressure cards and recent precedent-memory cards so docket load, legitimacy, and doctrine carry-through are visible without inspecting raw state JSON.
- The simulation now has first-pass versions of the next realism layers too: committee/amendment/whip/conference bill flow, administrative rulemaking and enforcement actions, named civic actors, political-pressure events, federal/state implementation conflicts, live implementation outcomes, and historical-memory markers that record elections, precedent shifts, conflicts, and policy backfires.
- Those added layers now feed back into the core simulation as well: pressure can move bill support and executive action, administrative appeals raise judicial risk, conflicts and backfires drag trust/service, and strong implementation improves institutional standing.
- The main knobs for those newer feedback loops are now config-driven under `app.government.feedback.*` and `app.government.thresholds.*`, so tuning implementation drag, pressure sensitivity, conflict effects, and court block thresholds no longer requires Java edits.
- Bots now also carry a first-pass individualized legal-exposure layer: enacted laws, active orders, and administrative enforcement can move each bot into `COMPLIANCE_REVIEW`, `UNDER_NOTICE`, `ACTIVE_SANCTION`, `REVIEW`, or `PETITIONING`, and large petitioning cohorts can now feed political-pressure generation instead of government pressure staying purely top-down.
- The government sidebar now surfaces that individualized layer too through a civic legal climate section, so bot-side legal exposure is no longer hidden behind aggregate trust and service scores.

## Run
Java 21 required.

Fedora / Linux example:
```bash
# Fedora 44's native repositories provide OpenJDK 25, not OpenJDK 21. Install
# the official Eclipse Adoptium Temurin 21 JDK, then use it explicitly.
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export PATH="$JAVA_HOME/bin:$PATH"

cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn spring-boot:run
```
Open `http://localhost:8081/`

The system `java` alternative may remain on Java 25; keeping `JAVA_HOME` and `PATH`
above scoped to the terminal preserves Java 21 for this project without changing the
machine-wide default. Follow the [official Adoptium Fedora RPM instructions](https://adoptium.net/installation/linux/)
to add its signed repository, then install `temurin-21-jdk`.

### local-ai provider

The enabled primary LLM provider is the local `local-ai-gateway` on
`127.0.0.1:8788`. It invokes the machine's signed-in ChatGPT/Codex session
through an ephemeral, read-only Codex CLI turn with its interactive tools
disabled; it does **not** use an OpenAI API key or a fixed public API model id.
Start the application through the helper so that proxy is present:

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
./scripts/start.sh
```

`start.sh` first runs the graceful `stop.sh` sweep, so invoking it always
reloads the Spring app and its managed subscription proxies rather than reusing
a stale process. Use `stop.sh` directly when you only intend to shut down.

Run `codex login` first if this machine has not been authenticated. The proxy
pins the locally validated `gpt-5.6-terra`, and returns Codex token usage for
API-equivalent budget accounting at Terra's $2.50 input / $15 output per
million-token public rates. This includes a final usage frame on streamed
narration, so those calls count toward the run-budget guard; it rejects the public
API slug `gpt-5-codex` on this ChatGPT-authenticated installation. Claude is disabled by default;
OpenRouter remains the automatic fallback. Set `ET_DISABLE_LOCAL_AI_GATEWAY=1` to
skip the local bridge, or `ET_ENABLE_LEGACY_AI_GATEWAY=1` only when intentionally
bringing the dormant Claude bridge back up. See
[`docs/subscription-providers.md`](docs/subscription-providers.md) for the
complete Codex and Claude login, activation, connection, verification, and
troubleshooting guide. The active Codex pool permits two concurrent requests;
account-level usage limits still apply. A full research narration has a
90-minute wall-clock deadline; its stale-draft reaper is 100 minutes, so the
narration timeout is the first recovery mechanism. Phase 35 retrieval work
remains necessary to avoid consuming that allowance in a broad FTS5 search.

The run-wide LLM budget has a $5.00 hard cost cap. It enters drain at $2.20,
stops admitting new research, and retains the remaining $2.80 for already
started papers. The independent 350-call cap and 60-call reserve are unchanged
(call drain begins at 290). This is aggregate closeout headroom, not per-paper
escrow: optional work can still consume the shared reserve.

Phase 35 literature retrieval deterministically drops generic connective,
ordinal, measurement-framing, model-prose, and numeric/coefficient fragments before issuing FTS5 queries. Each
enabled lookup logs the retained expressions and its bounded completion
telemetry, so problematic query shaping can be corrected without exposing
paper prompts or bypassing the evidence gate. The theorem-record index is
searched first; if it has no qualifying source, the same subject pair plus an
independent corroborating anchor is searched in the indexed full text. That
fallback still issues only a process-scoped `HF...` key and requires
`read_reference` before a source can support paper prose. Search tool output is
also capped before it is passed to the model/proxy, so an anomalously large
indexed record cannot make the next tool turn fail at the operating-system
argument-size limit.

Only a clean `PUBLISHED` paper may promote tool findings into the conjecture
pool or cross-run verified-results ledger. `NEEDS_REVISION`, `NULL_RESULT`,
`PUBLISHED_NO_PROFESSOR`, and `REJECTED` papers retain their local trace for
revision but cannot seed later hypotheses or evidence.

### Related-service status

Use the read-only status helper to check the Spring app (`8081`), local AI gateway
(`8788`), optional/dormant legacy AI gateway (`8787`), Julia math sidecar (`8088`),
isolated PyCall/grounding sidecar (`8089`), and Rust GPU engine (`50051`). For
each listener it prints the owning PID and exact active command from `ps`, then
the copy-pasteable command to start it when absent. It exits nonzero if a
required service is not healthy (or, for the gRPC GPU engine, not listening);
the disabled legacy AI gateway is reported as `DORMANT`. It accepts matching
`ET_*_URL` / `ET_*_PORT` overrides and `ET_STATUS_TIMEOUT_SECONDS`. Its terminal
output uses color; pipe it or set `NO_COLOR=1` for plain text. The proxy entries
are lifecycle-managed by `./scripts/start.sh`: Codex starts by default, while
Claude starts only with `ET_ENABLE_LEGACY_AI_GATEWAY=1`. A redirect to `/login` is
reported as ready-but-protected; set `ET_STATUS_COMMAND_CHARS` to adjust the
abbreviated long `ps` command display.

```bash
./scripts/status-related-apps.sh
```

## Fedora Bootstrap
If you want one native Fedora setup path for both repos, use:

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
bash ./scripts/setup-fedora-prereqs.sh
```

What it does:
- installs the Java, Maven, PostgreSQL, Rust, protobuf, Vulkan, Node, `jq`, `curl`, and PowerShell packages used by the app and GPU engine
- initializes and starts PostgreSQL
- sets the local `postgres` password to `NewStrongPasswordHere` by default so it matches `application.yml`
- creates `emergentterrain` and `emergentterrain_perf`
- installs the local npm dependency and Playwright Chromium for the UI scripts

On Fedora 44, install Temurin 21 first as described in [Run](#run). The helper detects
`/usr/lib/jvm/java-21-temurin-jdk`, skips the unavailable native `java-21-openjdk-devel`
package, and prints the matching `JAVA_HOME` in its next-steps output.

Useful flags:
- `--postgres-password YourOwnPassword`
- `--skip-playwright`
- `--skip-node-deps`
- `--skip-packages`
- `--skip-postgres`

Smoke test for the helper:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
bash ./scripts/test-setup-fedora-prereqs.sh
```

## Line Endings
- The repo now uses `.gitattributes` to normalize text files to `LF`.
- `*.bat` and `*.cmd` are kept as `CRLF`.
- Recommended local Git settings:
  - `git config --global core.autocrlf false`
  - `git config --global core.eol lf`

## Scientific Integrity Pipeline

> **Flow diagram:** `docs/paper-creation-flow.md` shows both paper-producing paths (conjecture
> investigation + discovery/frontier) end-to-end. Latest integrity/throughput fixes:
> `docs/2026-06-04-pipeline-hardening.md`.

Research papers go through a deterministic integrity layer before any LLM narration call, and a multi-step quality pipeline that uses additional LLM calls to improve per-paper quality:

**Deterministic integrity (no LLM):**
1. **Pre-registration** — the fast-pool LLM writes a hypothesis, prediction, and falsification condition *before* computation runs (`PreRegistration` record). For cross-domain papers, `mappingNotes` forces explicit justification of any constant mappings.
2. **Deterministic classification** — `UniversityService.classifyResult()` maps `MathPhysicsEngine.ClaimStatus` + `authorScore` to one of four outcomes: SUPPORTED, PARTIALLY_SUPPORTED, NULL_RESULT, or DEGENERATE. This runs before peer review and before any LLM call, preventing post-hoc rationalization.
3. **Degenerate rejection** — non-finite values, all-zero sentinels, and non-positive author scores are silently rejected; no draft is created and no LLM call is made.
4. **Classification-aware narration** — the narration prompt includes the pre-registered hypothesis and classification. NULL_RESULT prompts explicitly prohibit reframing as partial success.
5. **Honest null reporting** — NULL_RESULT papers are stored with status `NULL_RESULT` (not PUBLISHED), receive no confidence boost (fixed ×0.92), are excluded from average score calculations, and display a `∅ NULL RESULT` banner in markdown.
6. **Replication and bridge guards** — null results are excluded from anomaly replication queues and cross-domain knowledge bridging; only positive results are eligible.
7. **Cross-topic anomaly context** — the formulation prompt surfaces anomalous papers from adjacent topics in the same domain, enabling the LLM to reason across papers when exploring new parameter regions.
8. **Computational-publication tiers** — workflow status is separate from the reader-facing publication label. Every paper receives an audit bundle under `research/<room>/artifacts/<paper-id>/`; only `PUBLISHED` papers with a locked discovery/confirmation plan, sensitivity and reproduction declarations, an executed trace, and source-grounded related work are labeled artifact-backed computational-paper candidates. The gate requires a successful `read_reference` result for a key returned by `search_literature`, and that same key must appear in the related-work text. Artifact metadata is written from the final paper label. `./scripts/bootstrap-hf-math-literature.sh` downloads the fast theorem-search corpus by default and records both requested and resolved dataset revisions; set `HF_DATASET=marin-community/ar5iv-no-problem-markdown` for the complementary full-text source used to read and position claims. `python3 -m pip install --user pyarrow` followed by `./scripts/prepare-hf-math-index.py --stage metadata`, `--stage theorems`, and resumable `--stage fulltext --fulltext-shard-limit 20` builds the separate local SQLite/FTS5 staging index under `~/.emergentterrain/indexes/math-literature/`; the shard pass stores source text first and bulk-builds then optimizes `fulltext_fts` only after every shard is committed, avoiding costly per-record FTS rewrites. For an already-complete immutable index, run `./scripts/prepare-hf-math-index.py --stage optimize` once; it merges both theorem and full-text FTS segments and records the completion time without changing documents. It records raw-input SHA-256 values, dataset revisions, and importer provenance and retains broad source data for future subjects while mathematics remains the current query-time scope. The completed-index adapter is enabled through `app.research.literature.phase34-sqlite.enabled`. Its Phase 35 path is explicitly controlled by `app.research.literature.phase34-sqlite.phase35-retrieval.enabled` (currently `true`); it uses limited `ORDER BY rank` FTS5 retrieval, validated exact subject phrases for multi-anchor claims, and `search-timeout-ms` (currently 15 seconds). Each candidate first checks `initial-candidates` (currently 64) and only then widens to the established 512-result range when no evidence is found, so every paper retains the same complete eligibility range under the same fail-soft deadline. Completion logs record elapsed time, attempted candidates, batches, inspected rows, result count, and the last expression without logging passage text. Set the flag to `false` to restore the former Phase 34 all-terms-then-OR query. Both modes return only sources with indexed full text. Full-paper promotions use that same adapter first for their related-work search and up to two source reads; the older BM25/core route is fallback-only. Its fixed claim-set acceptance suite is still required before treating live retrieval as publication-ready. Neither corpus alone proves novelty.

Phase 35 relevance safety needs no additional index stage. The enabled adapter
uses only validated adjacent subject phrases, fails closed rather than issuing
an irrelevant `HF...` key, and enforces record/title corroboration before a
key is issued. Its deterministic `LiteratureQueryPlanner` and validated
`LiteratureQueryPlan` are the future boundary for an AI-assisted query-planning
tool: an AI may propose one bounded query kind and two safe anchors, but cannot
bypass record-evidence or title-anchor validation.
Each Phase 35 lookup logs its exact FTS expressions, evidence anchors, and
required title anchors at INFO level in `logs/et-app.log`, enabling a failed
or no-match lookup to be reproduced manually without logging full paper text.

**Quality pipeline (additional LLM calls):**
8. **Novelty check** — before formulation, when ≥3 papers exist in the topic, a fast Ollama call assesses saturation (0=fully covered, 1=fresh). Score < 0.30 injects a NOVELTY WARNING redirecting the LLM to unexplored parameter regions; 0.30–0.59 injects a softer hint.
9. **Statistical framing** — after refinement and before narration, a fast Ollama call rewrites `rawFindings` with ≤3 significant figures, uncertainty qualifiers ("approximately", "within ~X%", "±"), and scientific notation for extreme values. Addresses fabricated-precision and uncertainty-quantification gaps.
10. **Professor tool-calling + revision** — after first-pass Groq narration, the Groq professor is given a tool suite (`run_param_sweep`, `run_predicate_scan`, `evaluate_symbolic`, `run_sandboxed_code`) and can call any combination to gather evidence before writing a synthesis. Sandboxed code is reviewed by the fast 4b model, then executed in `SandboxEngine` (JavaScript-like mini-language; 5 000-iteration cap; whitelisted builtins only). A student (second Groq call) revises the paper incorporating the experimental evidence. Falls back to the legacy JSON-ExperimentalGap path when Groq is not configured.

When a tool-bearing student revision is malformed, the pipeline now emits a trace-grounded fallback
instead of reusing first-pass prose. It renders only the completed tool results and, when any result
is `REFUTED`, concludes that no positive result is claimed. The `rev:parse_failed→trace_fallback`
call-path stamp distinguishes this evidence-preserving path from the legacy no-tool fallback.
11. **Cross-bot anomaly mentoring** — the genome mentoring prompt now includes anomalous findings from other bots in different domains (|z| ≥ 1.5), enabling the advisor to suggest cross-domain redirects. The mentoring callback passes 40 papers instead of 20 for broader coverage.

Stats endpoint (`getStats`) now reports: `totalPublished`, `positiveResults`, `nullResults`, `disputed`, `totalRejected`.

## Self-Verifying Discovery & Frontier Escalation (Phase 30)

A substrate that lets the system *compute and verify* numeric results beyond the precomputed sieve, and push a bound's frontier autonomously — never trusting an unverified value. Flipped **ON** and live (`app.research.discovery.*`; design docs in `phases-for-human-like-performance/phase-30-conjecture/`). **Live result (2026-05-31):** an autonomous run ratcheted the Mertens frontier **2×10⁶ → 2×10⁸** (100× past the sieve), banking 3 LLM-generated, moat-validated methods and 4 boundary events — `M(10⁸)=1928` (the known value) confirms the moat.

- **Runtime self-verifying execution** — an LLM proposes a numeric method (e.g. the Mertens function `M(x)` for `x > 2,000,000`); it runs in an isolated subprocess sandbox (AST allowlist + `ulimit`/`timeout`) via the sidecar `/discover/validate` endpoint, and its output is trusted *only* after exact agreement with a trusted oracle on the overlap range `[1, 2,000,000]`. Generate → validate → repair, with provenance tagging ("computed, not established"). The endpoint executes model-authored code, so it is gated by `ET_DISCOVER_EXEC=1` on the sidecar (see STARTUP.md §4.5) plus `runtimeExecEnabled` + a per-invariant tool gate.
- **Frontier-escalation loop with feedback-driven discovery** — escalates a holding bound (e.g. the Mertens conjecture `|M(x)| < √x`) across scales. A scale that **can't be computed (OOM/timeout) feeds back to the LLM rather than ending the run** — it sees the failure (and the persisted boundaries from prior runs) and self-corrects to a *reachable* x; we **inform, never clamp**. It **forecasts its own compute stall** (power-law cost fit → compute frontier `x_c`) and on repeated failure **pivots** to a verifiable branch (B3 proxy reformulation / B4 predictive model) or the honest **analytic boundary**. Pluggable step policies (`default | adaptive | llm`), **counterexample bisection**, a **generalized proxy oracle** (any linear-combination summatory over μ/ω/Ω/τ), and tunable budgets (`maxRepairAttempts`, `frontierCpuSeconds`/`frontierWallSeconds`, `frontierEscalationMaxRounds`). Trust travels with every value (`FULL` / `VALIDATED_PROXY` / `MODEL_FIT` / `LITERATURE_ONLY`) and is never laundered.
- **Knowledge accumulation + reuse** — validated values, the reusable **verified-method library** (ranked by the cost curve), run-until-converged state, and **OOM/timeout boundary events** persist to the DB (`frontier_records`, `frontier_convergence`, `verified_methods`, `frontier_events`) and survive restart. Runs **resume from the persisted frontier** instead of re-walking. A paper reads the corpus via the read-only `recall_frontier_knowledge` tool with trust carried verbatim — a `FULL` value is reported as *computed-and-verified at x*, a boundary as an *honest negative result about the harness's reach*, never as a proof. The LLM-generated method code is logged to the **log file, the DB, and the paper**.

Honest by construction: it does **not** resolve Mertens (the failure regime ~10¹⁴ is beyond feasible compute) — it computes/extrapolates *toward* the wall with the trust level stated, an OOM is recorded as a *boundary* not a claim, and the renderer's regime caveat keeps "held" from reading as "resolved".

## Documentation

In-repo specs and design notes under `docs/`:

- [`paper-creation-flow.md`](docs/paper-creation-flow.md) — end-to-end flow diagram: conjecture-investigation + discovery/frontier paths and their shared narration → validation → publish tail.
- [`2026-06-04-pipeline-hardening.md`](docs/2026-06-04-pipeline-hardening.md) — review-driven integrity/throughput fixes (validators, NULL coherent-abstention, geometry-LHS exclusion, priority-reserved LLM pool, prose-constant grounding) with commit refs.
- [`phase-34-computational-publication/README.md`](phases-for-human-like-performance/phase-34-computational-publication/README.md) — publication tiers, artifact/provenance rules, current SQLite adapter checkpoint, and the remaining live-activation checklist.
- [`phase-35-fts-retrieval-efficiency/README.md`](phases-for-human-like-performance/phase-35-fts-retrieval-efficiency/README.md) — performance-preserving FTS5 retrieval plan, evidence invariants, acceptance suite, and rollout/rollback criteria.
- [`research-puzzle-spec.md`](docs/research-puzzle-spec.md) · [`bot-lifecycle-spec.md`](docs/bot-lifecycle-spec.md) · [`constitutional-terrain-phase1.md`](docs/constitutional-terrain-phase1.md) · [`llm-cost-analysis.md`](docs/llm-cost-analysis.md)

Phase-30 conjecture/discovery design + tracking: `phases-for-human-like-performance/phase-30-conjecture/`.

## Tests (summary)
- **Java/Mockito on sandboxed hosts** — run Maven with the project JDK
  (`JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk`). Mockito's inline mock maker dynamically
  attaches Byte Buddy by default, which restricted sandboxes can deny even on Java 21; the
  affected test class fails before its mock-based tests run. Preload the cached agent instead:
  `mvn test -Dtest=SynthesisPaperServiceTest -DargLine=-javaagent:$HOME/.m2/repository/net/bytebuddy/byte-buddy-agent/1.15.11/byte-buddy-agent-1.15.11.jar`.
  This runs all 11 synthesis tests without changing the system Java default. Java 25 also works
  with that preload for tests, but Java 21 remains the supported app runtime.
- `ResultClassificationTest` — all 17 branches of `classifyResult`: DEGENERATE (NaN, Infinity, zero sentinel, zero authorScore), NULL_RESULT (score floor, REFUTED claim, low OBSERVATIONAL), SUPPORTED, PARTIALLY_SUPPORTED, and real computation round-trips.
- `LlmServiceParseTest` — 35 tests for `parseFormulationResult`, `parseNarrationResult`, and `parseRefinementDecision`: valid JSON, boundary params, malformed input, preamble extraction, confidence clamping, paper ID filtering, follow-up validation.
- `LlmServiceQualityPipelineTest` — 50 tests for the quality-pipeline parse/build methods: `parseNoveltyAssessment` (score clamping, fallback, saturation threshold), `buildStatisticalFramingPrompt` / `parseStatisticalFraming` (truncation, JSON rejection, preamble stripping), `parseCritiqueResult` (weaknesses + `ExperimentalGap` parsing for param and dynamic types, validation of param1/param2 ranges, predicate/metric allowlists, range limits), `buildCritiquePrompt` (domain-aware experiment specs, dynamic-paper exclusion), `buildRevisionPrompt` (professor experiment section, null-result vs supported spec).
- `PublishPaperTest` — 13 tests for `publishPaper`: confidence scaling by classification, null-result fixed factor, status assignment (PUBLISHED vs NULL_RESULT), template fallback, stats separation.
- `PreRegistrationTest` — record structure, equality, null-tolerant fields, PaperDraft carries both pre-registration and classification.
- `RoomRapidChangePersistenceIntegrationTest` (profile: `test`) runs a short rapid-change loop, forces maintenance/flushes, and asserts persisted snapshots and change counts. Expected time: ~80s.
- `RoomStateSamplingIntegrationTest` (profile: `test`) seeds a room, waits `sample.waitMs` (default 3 minutes), applies proposals, flushes state, and writes sampling output to `sample-output/`. Expected time: ~3m 15s with the default wait.
- `scripts/run-real-gpu-e2e.ps1` starts the real Rust GPU engine plus the Java app, forces GPU proof thresholds for bots and patch rebuilds, verifies non-stub world data over `/game/state`, creates a room, waits for live bot movement over `/room/{roomId}/state`, submits a proposal, and proves the live `rebuild_patch` RPC. Use `scripts/run-real-gpu-e2e.sh` on Fedora and `pwsh ./scripts/run-real-gpu-e2e.ps1` on Windows. Expected time: ~30-60s after startup.
- `scripts/compare-cpu-vs-gpu-performance.ps1` runs release-binary CPU-vs-GPU comparisons with the `perf` profile and prints JSON with per-run timings, averages, and GPU-minus-CPU deltas. Use `scripts/compare-cpu-vs-gpu-performance.sh` on Fedora and `pwsh ./scripts/compare-cpu-vs-gpu-performance.ps1` on Windows.
- `scripts/test-setup-fedora-prereqs.sh` smoke-tests the Fedora bootstrap helper in a no-op mode so setup docs and argument parsing can be verified without reinstalling packages or touching PostgreSQL. Expected time: a few seconds.
- `scripts/minimap-route-ui-test.js` (Playwright) clicks the minimap, waits for route choices, and verifies the minimap canvas updates. Expected time: ~50s.
- `scripts/minimap-travel-smoothness-test.js` (Playwright) auto-navigates via minimap, samples render/logic positions, and reports movement update cadence and move request latency. Expected time: ~20s.

## Benchmarks (JMH)
Benchmarks live under `src/jmh/java/com/emergentterrain/bench`.

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn -DskipTests -Pbench package
```

Standalone (forked, recommended for real numbers):
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar -f 1 -rf json -rff target/jmh-result.json
```

Profiles:
```bash
# RPC overhead (small sizes)
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar ".*GpuVsCpu.*" -p profile=RPC -f 1

# Compute-heavy (larger sizes)
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar ".*GpuVsCpu.*" -p profile=HEAVY -f 1
```

In-process (no fork, quick sanity):
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
mvn -DskipTests -Pbench exec:java -Dexec.args=".*GpuVsCpu.* -f 0 -rf json -rff target/jmh-result.json"
```

GPU benchmarks require the Rust GPU engine to be running. Use `GPU_ENGINE_HOST` / `GPU_ENGINE_PORT` or `-Dgpu.host` / `-Dgpu.port` if needed.

Focused patch benchmark:
```bash
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar ".*PatchRebuild.*" -f 1
```

Focused government benchmark:
```bash
java -jar target/emergent-terrain-0.0.1-SNAPSHOT-benchmarks.jar ".*GovernmentSimulationBenchmark.*" -f 1
```

## GPU Engine Integration
- World generation can call the GPU engine when enabled.
- Bot simulation can call the GPU engine when enabled (optional tile edits).
- Bot simulation sends structured preference fields and can cache world state in the GPU engine; diffs + step batching are controlled by `app.gpu.bots.*`.
- World generation now uses a short-lived response cache keyed by seed/profile/size so `/game/state` and `/room/create` can reuse the same generated world.
- GPU dispatch is now adaptive by default:
  - `app.gpu.world.minCells` keeps small worlds on CPU.
  - `app.gpu.bots.minBotCount` and `app.gpu.bots.minWorkItems` keep low-work bot ticks on CPU.
  - `app.gpu.patch.minArea` keeps tiny patch rebuilds on CPU; 1x1 proposals stay CPU by default.
- Initial room creation now lazily materializes bots up to `app.bots.initialRoomCreateTarget` and batches persistence work to reduce room setup overhead.
- The default runtime on `radical-changes` now uses more aggressive GPU bot-simulation defaults than `main`: lower GPU thresholds, a longer world cache TTL, and `app.gpu.bots.steps=1` so repeated live maintenance ticks can reuse cached GPU world buffers more effectively.
- The default runtime on `radical-changes` also uses a tighter live-update cadence than `main`: `maintenanceIntervalMs=150`, `broadcastMinIntervalMs=40`, `occupancyRefreshMs=250`, `flushIntervalMs=750`, and `flushMinChanges=80`.
- The browser now uses WebGPU for the full world scene, not just an overlay: `#worldFx` renders terrain, actors, proposal fields, transitions, and particles, while the minimap is also rendered through WebGPU. The base `#world` canvas remains in place as the interaction/input surface.
- Bot movement now carries short-term motion memory in both the Java CPU path and the Rust GPU path: reverse moves are penalized, heading continuity is preferred, bots keep a short plan, and immediate reversals are cooled down for a few ticks.
- Highway state now uses a dedicated persisted road-pressure layer in room state. Bot movement adds pressure during long relocation and exploration moves, maintenance promotes/decays highway overlay cells, and normal non-highway tile changes preserve existing roads instead of clearing them.
- Defaults are off.

### Two-app integration check
A real Java app + Rust GPU engine smoke check is expected to run from Fedora with the repos living under `/opt/apps/ide/eclipse/workspace-web/`:
- Start the Rust engine on `127.0.0.1:50051`
- Start the Java app on `127.0.0.1:8081`
- Call `GET /game/test` to obtain a token
- Call `POST /game/state`

A successful GPU-backed engine call returns a non-stub `220x165` world with non-zero tiles and highways, and live bot simulation RPCs are also handled by the GPU engine. The last verified adapter on this machine was `Radeon RX 580 Series via Vulkan`.

Fedora shortcut for a full two-process check:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
./scripts/run-real-gpu-e2e.sh
```

The script starts the real Rust engine from `/opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine` by default, starts the Java app with GPU world/bot flags forced on, temporarily lowers the adaptive bot and patch thresholds for proof, verifies non-stub world output, creates a room, polls until at least one bot position changes, submits a proposal near the active participant, and waits for the patch to apply. It checks the engine log to prove `generate_region`, `rebuild_patch`, and `simulate_bots` were handled by the live engine. Logs go under `sample-output/gpu-e2e-*`.

### Release perf comparison
Use the release-binary comparison harness for CPU-vs-GPU timings:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
./scripts/compare-cpu-vs-gpu-performance.sh --iterations 2 --build-artifacts
```

It packages the Spring app to `target/emergent-terrain-0.0.1-SNAPSHOT.jar`, builds the Rust engine in `release`, runs the app with `--spring.profiles.active=perf`, and prints JSON with run-by-run timings plus GPU-minus-CPU deltas. This is the supported local performance path; do not use `mvn spring-boot:run` or `cargo run` for comparisons.

Perf profile notes:
- `application-perf.yml` stays on PostgreSQL and targets `emergentterrain_perf` by default.
- Override perf DB settings with `PG_PERF_URL`, `PG_PERF_USER`, and `PG_PERF_PASSWORD` when needed.
- The compare script now launches the Java runtime and the Rust `release` binary directly, clears the inherited `DEBUG` environment variable, and uses startup-oriented JVM flags. That is the supported way to measure startup on this machine.
- The compare script now measures a GPU-relevant heavy session:
  - create a room
  - apply one live proposal
  - fully populate the room to the perf-profile bot target
  - run repeated `/perf/room/{roomId}/bot-simulation` iterations through the live app
- Default bot-simulation iterations in the compare harness are `10`. Override with `-BotSimulationIterations N` for longer runs.

## PostgreSQL on Fedora
PostgreSQL is required for normal app and perf-profile runs, but it is not bundled with Fedora by default.

Install and initialize it with:
```bash
sudo dnf install -y postgresql-server postgresql
sudo postgresql-setup --initdb
sudo systemctl enable --now postgresql
```

If you need to reset the local `postgres` password:
```bash
sudo -u postgres psql -c "ALTER USER postgres WITH PASSWORD 'NewStrongPasswordHere';"
```

Or use the native helper from this repo:
```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain
bash ./scripts/setup-fedora-prereqs.sh
```

## UI Assets
Material Web, Roboto, and Material Symbols are loaded from CDN in the templates.

## Configuration
See `src/main/resources/application.yml` for app settings:
- `app.qr.hmacSecret`
- `app.room.multipleRoomsEnabled` defaults to false for the single-room player experience
- `app.ai.digestCooldownMinutes`, `app.ai.advisorCooldownSeconds`, and `app.ai.explanationCooldownMs` for LLM request pacing
- `app.world.auto`, `app.world.maxTiles`, `app.world.aspectRatio`, `app.world.highwayRatio`
- `app.highways.emergent.*` for travel-demand highway formation and decay
- `app.government.*` for deterministic constitutional/government timing
- `app.government.feedback.*` and `app.government.thresholds.*` for government simulation tuning
- `app.government.timeline.*` for on-demand government trend storage, retention, and rollup cadence
- `app.simulationHistory.root` for append-only civic history output
- `app.research.experiments.range-chunking.*` for Phase 36's restart-safe Julia scale-ladder
  execution; it preserves every count-weighted density metric's full logical window and journals
  chunks under `simulation-history/research-experiments/` (enabled at 50,000 integers per chunk).
  A ladder checks Julia readiness before it schedules a wide request, and each chunk records
  actual queue wait, Julia compute, and transport time separately.
- `app.world.flushIntervalMs`
- `app.world.cacheTtlMs`, `app.world.cacheMaxEntries`
- `app.consensus.neighborRadius`, `app.consensus.voteWindowSeconds`, `app.consensus.minNeighbors`
- `app.consensus.noParticipationLockSeconds`
- `app.density.areaSize`, `app.density.maxOccupants`
- `app.bots.populationFraction`, `app.bots.maxPerRoom`
- `app.bots.initialRoomCreateTarget`
- `app.gpu.enabled`, `app.gpu.world.enabled`, `app.gpu.bots.enabled`, `app.gpu.bots.applyEdits`
- `app.gpu.world.minCells`
- `app.gpu.bots.cacheWorld`, `app.gpu.bots.steps`, `app.gpu.bots.diffMaxEdits`, `app.gpu.bots.minBotCount`, `app.gpu.bots.minWorkItems`
- `app.gpu.patch.minArea`
- `PG_URL`, `PG_USER`, `PG_PASSWORD` for the default app datasource
- `PG_PERF_URL`, `PG_PERF_USER`, `PG_PERF_PASSWORD` for the perf profile datasource

## Constitutional Terrain
- Current implementation notes live in `docs/constitutional-terrain-phase1.md`.
- Machine-readable constitutional rules live in `src/main/resources/government/constitution-rules.json`.
- That constitution file contains structured content, not just a version string: Articles I-VII plus Amendments I-XXVII each carry a title, category, trigger, enforcement rule, and affected systems used by runtime doctrine and summary generation.
- Runtime history output is append-only and written under `simulation-history/` with subfolders for:
  - `constitution`
  - `events`
  - `elections`
  - `courts`
  - `legislation`
- Phase 36 range-chunk journals are separate mutable execution state under
  `simulation-history/research-experiments/`. The enabled first slice applies only to the safely
  count-weighted `abundant_density` CONSTANT scale-ladder metric: a 500,000-wide requested window
  remains the same logical interval, but runs as resumable 50,000-integer chunks. It records
  queue, Julia compute, and transport timings; a partial job cannot support a full-range claim.
- The current runtime slice now includes:
  - deterministic federal and state party / budget / election / bill simulation
  - executive order issuance when services or trust degrade
  - court review that can block rights-process actions
  - office-role assignment back onto bots for federal and state branches
  - focused unit coverage in `GovernmentSimulationServiceTest`
  - focused JMH coverage in `GovernmentSimulationBenchmark`
- Richer agencies, amendment gameplay, and GPU civic kernels are still deferred.

## Routes (summary)
- `GET /register`, `POST /register`
- `GET /login`, `POST /login`
- `GET /profile/new`, `POST /profile` (first player profile only)
- `GET /qr?token=...`
- `GET /game?token=...`
- `GET /game/government-trend?token=...&roomId=...&window=...`
- `POST /game/state` (world seed + regions)
- `POST /room/create` (resolves to the singleton room)
- `POST /room/join` (resolves to the singleton room)
- `POST /room/last` (join or create the singleton room)
- `POST /room/{roomId}/state`
- `POST /room/{roomId}/government-trend`
- `POST /room/{roomId}/move`
- `POST /room/{roomId}/propose`
- `POST /room/{roomId}/vote`
- `POST /room/{roomId}/apply` (returns error: auto-apply on majority)
- `POST /room/{roomId}/memory`
- `POST /room/{roomId}/bot`
- `POST /room/{roomId}/resonance`

## Controls
- Move: `WASD` or arrow keys
- Highways: moving while on a highway tile jumps 4 tiles per keypress
- Highways now act as an overlay rather than a destructive terrain replacement. A tile can keep its forest/water/industry identity while still carrying a road corridor.
- Propose change: click a tile to mark a 2x2 area, choose a tool, then press `Apply Changes`
- Vote: select a proposal then use `Approve` or `Reject`
- Bots: open the Companions panel and select a bot to get a prompt

## Consensus Rules
- Only neighbors within `neighborRadius` can vote
- At least `minNeighbors` must be nearby to submit a proposal
- Non-participating neighbors count as NO after the vote window
- Two missed votes locks a player from play for `noParticipationLockSeconds`

## Notes
- This is a POC focused on end-to-end flow, not production hardening.
- The QR payload is signed (tamper detection) but not encrypted.
- Bot-generated changes are applied in memory and flushed as room state snapshots; bot proposals are not persisted.
- Dev note: `GpuEngineClient` is wired via a Spring constructor; if adding constructors, keep the `@Autowired` config-based one.
- Docs policy: when behavior, setup, or performance workflows change, update both `README.md` and `AGENTS.md`.
- Any new learnings from setup, debugging, performance work, or integration checks should be added to markdown in the same change.
- Current learning: `scripts/run-real-gpu-e2e.sh` is now the native Fedora implementation for the supported local end-to-end path, while `scripts/run-real-gpu-e2e.ps1` remains the Windows path.
- Current learning: native Windows end-to-end verification now hits real GPU-backed world generation and bot simulation. On this machine the verified adapter was `Radeon RX 580 Series via Vulkan`.
- Current learning: proposal patch application now uses the persisted proposal anchor fields for GPU patch rebuilds, so `rebuild_patch` is exercised cleanly in the native Windows E2E run.
- Current learning: adaptive GPU thresholds matter more than raw GPU enablement on this workload. Small worlds, 1x1 proposal patches, and low-work bot ticks should stay on CPU by default, while the proof script lowers those thresholds explicitly when it needs to prove the live GPU RPCs.
- Current learning: `scripts/compare-cpu-vs-gpu-performance.sh` is now the native Fedora perf harness, while `scripts/compare-cpu-vs-gpu-performance.ps1` remains the Windows path for the same workload.
- Current learning: local perf runs on this machine must clear the inherited `DEBUG` environment variable. The compare script does that now, because otherwise Spring Boot starts in debug mode and startup timings are distorted.
- Current learning: the perf profile is PostgreSQL-only and intentionally strips UI/login/websocket beans so startup and request timings reflect the game/room APIs rather than the full interactive UI stack.
- Current learning: GPU only showed a decisive win once the benchmark shifted to repeated heavy bot simulation instead of mostly startup and a single small request. The current compare harness is intentionally bot-simulation-heavy for that reason.
- Current learning: for the normal runtime, the most portable parts of the GPU win were lower bot-simulation thresholds, longer world-cache reuse, and `steps=1`. Those are now moved into `application.yml` on `radical-changes`, while the heavy perf-only endpoints remain benchmark-only.
- Current learning: making the map feel fluid requires both faster backend cadence and browser-side interpolation. Lowering backend intervals alone only makes bots and tiles snap more often.
- Current learning: for voting visuals, browser GPU is the right first hop. The server now streams proposal and tile-transition deltas over websocket, and the browser turns them into continuous WebGPU overlays without round-tripping every animation frame.
- Current learning: once terrain, actors, and minimap all move onto WebGPU, the plain canvas should be treated as an input layer and DOM anchor, not as a competing renderer.
- Current learning: the visible two-tile bot bounce was real simulation behavior, not just a rendering artifact. Fix it in both the Java fallback path and the Rust GPU path, otherwise faster rendering just makes the oscillation easier to notice.



DRI_PRIME=1 google-chrome-stable \
  --user-data-dir=/tmp/chrome-amd-x11-webgpu2 \
  --ozone-platform=x11 \
  --enable-features=Vulkan \
  --enable-unsafe-webgpu \
  --render-node-override=/dev/dri/renderD129 \
  --no-first-run \
  --no-default-browser-check


check chrome://gpu again. What we want to see is:
*ACTIVE* on AMD
GL_RENDERER showing AMD, not Intel

http://localhost:8080/game?token=H4sIAAAAAAAA_12QvW7DMAyE30WzHUjUv7cMHQq0W6cugRMxqVBbNmS5aRDk3UunKYpk5H135IFn9sUaUbExD_vY4XNgDZPQWhnktt56BbWCra_bvZa1sMFIbHkIgrOKtQdkjdQVm_CbUq_rlydSu2HXljgkUgRIpRdjmo6YJ9acWRc_cdrsh4xTIaHkGaubeGwL5nupH-ZU2pgenMOIabOP2IXHFVg2uy72tOkeTHNKp1_pUrGPGAKmpc5IPaY5k5uvjLYKnPIAFiT3tmIF-xFzW_4MxkvFlXPegKWBFs19DLGcrtR65bh3WggtyFUxujH9Q-3AOcul05zTvzFhPtyYsU4o44T0FJULK3kYr9AKgCUI0ikqJal9pMYY1oUeDBxMzXXNxRuHRkEDYmWtMobuwDu7_AAMgcEN3QEAAA.ka9_l0tQVynz7POZSdaMYHO4Jj2WGpqf-jWksT-8xMQ

1. Conjecture generation (math)

Exploring integer patterns, sequences, graph properties
Can run on a laptop if you keep ranges modest
Many discoveries historically came from small-scale computation + insight

2. Small-scale inverse problems

Fit simple models to synthetic or limited data
Use regression, symbolic search, or heuristics

3. Non-ideal extensions of known systems

Slight deviations from ideal gas, perfect crystals, etc.
You’re adding corrections, not simulating the universe

4. Noise + uncertainty experiments

Add randomness and test robustness
Doesn’t require scale, just repetition


-----

The fundamental gaps (current status)

1. **No surprise or novelty detection** *(partially addressed)*. A fast Ollama novelty check now runs before each formulation call (when ≥3 topic papers exist). It rates topic saturation and injects a redirect hint into the formulation prompt for saturated topics (score < 0.30). Bots still don't detect *surprising* values within a computation (deviations from a prior expectation); the novelty check addresses topic-level saturation, not intra-paper surprise.

2. **Cross-paper reasoning** *(substantially addressed)*. The formulation prompt surfaces same-domain anomalies; the genome mentoring prompt now also surfaces cross-domain anomalies from other bots (|z| ≥ 1.5), enabling structural-pattern transfers across research areas. The remaining open piece is proactive bot-to-bot citation and discussion, where a bot explicitly references another's finding and designs a follow-up computation to test it.

3. **Findings don't inform the hypothesis** *(mitigated, not solved)*. Pre-registration + the professor-student critique+revision pipeline both guard against post-hoc rationalization. The critique step explicitly checks whether conclusions are inconsistent with the pre-registered prediction. The professor can now prescribe a new experiment; the student runs it and the revision is grounded in fresh evidence rather than just rewording. A further step would be mid-investigation hypothesis revision (before results, not after).

4. **No uncertainty quantification** *(substantially addressed)*. The statistical framing call rewrites `rawFindings` with uncertainty qualifiers ("approximately", "within ~X%", "±") before narration. Single point estimates without any qualifier should now be rare. Formal standard-error computation in `MathPhysicsEngine` itself remains open.

5. **Fabricated precision** *(substantially addressed)*. The statistical framing call enforces ≤3 significant figures and scientific notation for extreme values. The narration LLM now receives already-framed findings rather than raw exact decimals. Systematic enforcement at the engine output layer (before framing) remains open.

**What has been addressed (post-hoc rationalization)**

The redesign in commit `b4c7f45` eliminated the root cause of papers like P-a29f-244 and P-a29f-252, where the LLM reframed null results as partial confirmations. The fixes: pre-registration locks the hypothesis before computation; deterministic classification assigns SUPPORTED/PARTIALLY_SUPPORTED/NULL_RESULT/DEGENERATE from numeric evidence alone; the narration prompt explicitly prohibits reframing null results; null result papers are stored separately with no confidence boost and a null-result banner.




------------------------
**DNA and gene expression for future reference and possible game implementation:**
```
Almost every human cell contains a complete copy of your DNA — approximately 3 billion base pairs organized into 46 chromosomes, located in the cell nucleus.

The few exceptions:

Red blood cells — mature ones eject their nucleus entirely, so no DNA
Platelets — cell fragments, no nucleus, no DNA
Some lens cells in the eye — also lose their nuclei at maturity
Every other cell type — neurons, muscle cells, skin cells, liver cells — carries the full genome. The reason a liver cell is a liver cell and not a neuron isn't that it has different DNA; it's that different genes are switched on or off in each cell type through a process called gene expression.

When genes first switch on

The earliest switching happens during embryonic development. A fertilized egg starts as one cell with all genes mostly silent, then as it divides, different genes activate in different daughter cells, driving them to become different tissue types. By the time organs are forming (weeks 3–8 in humans), thousands of genes are already being selectively expressed — this is how a liver cell lineage diverges from a neuron lineage from what was originally identical cells.

Does it stay on permanently?

No, not in general. There are three patterns:

Constitutive (always on) — housekeeping genes that every cell needs constantly, like the ones running basic metabolism and DNA repair. These stay on in essentially all cells all the time.

Inducible (on/off by signal) — most genes. They respond to hormones, nutrients, stress, infection, temperature, physical activity, even time of day (circadian genes). Insulin tells liver cells to switch on glucose storage genes. Exercise activates muscle-repair genes. These can cycle on and off repeatedly throughout life.

Developmental (on once, then silenced) — some genes fire during a specific developmental window and are then permanently shut off via epigenetic silencing (DNA methylation, histone modification). Fetal hemoglobin genes are a classic example — active before birth, then largely silenced as adult hemoglobin genes take over.

What does the switching

The machinery is mostly transcription factors — proteins that bind to DNA near a gene and either recruit or block the RNA polymerase that reads it. External signals (hormones, nutrients, damage signals) change which transcription factors are active, which changes which genes get read.

The short answer: gene expression is more like a constantly adjusting equalizer than a set of light switches that flip once and stay.
```
------------
