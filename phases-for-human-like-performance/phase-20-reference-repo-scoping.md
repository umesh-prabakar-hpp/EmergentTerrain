# Phase 20 — Reference-Repo Pattern Mining (scoping doc)

**Status:** Scoping doc, not a single-PR phase. Created 2026-05-18 by
surveying a batch of external reference codebases (multi-provider LLM
routers, agent harnesses, GPU operator benchmarking frameworks,
JAX-based numerical workflows, multimodal agent stacks) against current
ET (Java app) and GPU-engine (Rust/wgpu) surfaces, and recording only
patterns that are **genuine gaps** — i.e., the file/symbol is absent
in our tree and the pattern would carry weight given our current
architecture. Items the scan claimed were gaps but the audit found
were already implemented are recorded in the **Items NOT pending**
table at the bottom.

**Categories surveyed (eight codebases):**

| Category | Verdict |
|----------|---------|
| Multi-provider LLM router (Node.js) | RELEVANT — router patterns |
| Super-agent harness (sub-agents + memory + sandboxes) | RELEVANT — orchestration / config patterns |
| Self-evolving agent in ~3K lines of Python | RELEVANT — agent loop + memory patterns |
| Multimodal agent stack with MCP (TS/Electron) | PARTIALLY RELEVANT — MCP-only |
| GPU operator benchmarking (Python, MFU/MBU/CBU) | RELEVANT — GPU engine instrumentation |
| JAX neural-network quantum Monte Carlo | RELEVANT — GPU engine architecture |
| GUI trajectory data synthesis | SKIP — GUI-bound, no transfer |
| Vision-language foundation model | SKIP — model weights, not orchestration |

**Scope of this doc:** Seven items targeted at the Java app, four at
the GPU engine, plus an audit table of claimed gaps that the source
already covers. Nothing here ships in a single PR; treat each numbered
item as a candidate that would graduate to its own phase doc (or fold
into an existing tracker) before any code lands.

**What this phase deliberately does NOT track:**

- **MCP integration.** Real opportunity, but the cost (MCP SDK
  dependency, process-management layer, auth design) is a multi-week
  lift. Recorded in 20.6 below as a deferred item with named triggers,
  not committed.
- **Skill crystallization / learned-skill cache.** Would land in
  adjacent territory to Phase 4c Piece 3 (template A/B testing — see
  `phase-18-pending-followups.md` §18.3). Recorded in 20.5 but deferred
  until Piece 3 lands so the two systems share a substrate.
- **Multi-GPU support for the engine.** The engine is single-GPU today
  (`EmergentTerrainGPUEngine/src/main.rs` ~line 1160); multi-GPU is a
  separable scaling project, not Phase-20 scope.
- **The skip list at the bottom** for patterns explicitly ruled out.

---

## Items targeted at the Java app

### 20.1 — Compression of repeated `tool_result` payloads in `ResearchNarrationService`

**Status (2026-05-19):** ✅ **LANDED** in commit `5415761` ("condense
older tool-result messages on next-round payload"). Behaviour-flagged
via `ToolResultCompressor` bean; persisted trace via `traceWriter`
unaffected.

**Source pattern:** content-filter pipeline that runs each
`tool_result` block through an autodetect compression chain before
re-sending to the model — gzip+base64 for binary, table folding for
CSV-shaped repeated rows, JSON key-folding for sweep grids,
numeric-precision trimming on intermediate values — with a
saved-bytes metric stamped per request.

**The gap:** Each professor tool-call round appends raw tool results
(3–10 KB JSON for `MathToolSuite` / `run_sandboxed_code` outputs) into
the conversation. By round 5 the running messages hold 50–80 KB of
tool prose, most of it sweep tables and intermediate values the model
already consumed once. We pay full input-token cost on every later
round. Existing GroqPool / CerebrasPool token accounting (per
`TokenUsage`) confirms tool-heavy rounds dominate per-paper spend.

**Files (proposed):**
- New: `src/main/java/com/emergentterrain/service/ToolResultCompressor.java`
  — single static `compress(JsonNode result)` that returns
  `(condensed JsonNode, bytesSaved long)`. Strategies: sweep-table
  truncation (keep first 3 + last 3 rows + min/max), repeated-field
  folding, numeric-precision trimming on intermediate values.
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — at the tool-result append site in `runToolLoop`-equivalent
  (around the `messages.addObject().put("role", "tool")` spots), wrap
  the result through the compressor for any round > 1. The current
  round always gets the full result.
- Telemetry: extend `CallMetrics` / `TraceWriter` to record
  `tool_result_bytes_saved` per paper for adoption verification.

**Acceptance:**
- Per-paper input-token spend drops on tool-heavy papers (sweep-style
  computations with ≥4 rounds). Baseline: 30 papers under current
  code; A/B: same 30 prompts with the compressor on.
- Compressed result preserves the final answer of each tool call
  byte-identical (the compressor only condenses sweep tables /
  intermediate fields, never the `result` field).
- No regression on existing validators (`MagnitudeRangeValidator`,
  `NumericalConsistencyValidator`) that scan tool-call trace.

**Risk:** Medium. Compressor that drops sweep rows must be paired with
the existing `MagnitudeRangeValidator` Phase 18.1 trace-scan work
(otherwise compressed traces hide the value the validator needed). Ship
behind a `tool-result-compression.mode` flag (`off` / `log` / `on`)
matching the established staged-rollout pattern.

**Open questions:**
- Should compression be opt-in per tool (`MathToolSuite` only) or
  apply to all tool results? Conservatively start with sweep-table
  outputs only (largest payloads, smallest validator-coupling risk).
- Does compressing the trace change the persisted trace shape the
  per-paper LLM tracer (`cbdef7d`) writes to disk? It should not —
  compress in the *conversation history*, persist the uncompressed
  result. Verify before shipping.

---

### 20.2 — Tag-level history compression on long tool loops

**Status (2026-05-19):** ✅ **LANDED** in commit `949a80b` ("tag-level
history compaction (two-stage)"). Wired into both pool tool-loops via
`ConversationCompactor` bean; null-safe (off by default in tests).

**Source pattern:** staged history compression treating the conversation
as a tagged stream (`<thinking>`, `<tool_use>`, `<tool_result>`,
plain). Stage 1: keep the most recent 10 of each tag intact, truncate
older instances of the same tag to first+last N characters with a
`…<truncated>…` marker. Stage 2: only if still over budget, drop
whole oldest turns. The two-stage design preserves structure before
sacrificing whole turns.

**The gap:** ET's tool-call loop has `MAX_TOOL_ROUNDS = 5` ceilings
(see `GroqPool.java:94`, mirrored across pools), but the rationale is
"truncate or fail" — we cap rounds rather than compress earlier ones.
A Phase 7 F4 bump took max-computations 2→5; future bumps will push
into context-window pressure on smaller models (Cerebras
qwen-3-235b chat window ≈ 32K, Groq llama-3.3-70b ≈ 8K).

**Why distinct from 20.1:** 20.1 condenses individual tool results in
place. 20.2 condenses the *envelope* — the per-turn
`<tool_use>`/`<tool_result>` framing, the professor LLM's
`<thinking>` blocks, etc. Stage 1 truncates content mid-block for old
turns; stage 2 drops whole turns if still over budget. The two layers
compose.

**Files (proposed):**
- New: `src/main/java/com/emergentterrain/service/ConversationCompactor.java`
  — `compact(ArrayNode messages, int targetChars, int keepRecent)`.
  Pattern: keep the most recent `keepRecent` turns intact; truncate
  older `tool_result` content fields to first+last N chars with a
  `…<truncated for budget>…` marker; if still over `targetChars`,
  drop oldest non-system turns whole.
- Wire into `ResearchNarrationService` payload build path on rounds
  ≥3 only (most papers complete in 1–3 rounds; later rounds are
  where this earns its keep).

**Acceptance:** A 5-round tool-heavy paper that today gets truncated
to round 4 via existing per-pool token caps completes 5 rounds with
the compactor on. Final synthesis text quality (panel score) is no
worse than the baseline 4-round truncated path.

**Risk:** Low. Worst case the compactor over-truncates and the LLM's
final synthesis loses context — manifests as panel-score regression,
caught by existing review pipeline.

**Open questions:**
- Pick `targetChars` per pool, not global. Different models have
  different context windows — `GroqPool.toolMaxTokens` is per-pool;
  add a parallel `toolHistoryCharBudget`.
- Interaction with the persisted per-paper trace (commit `cbdef7d`):
  compactor must not mutate the trace, only the in-flight conversation
  payload sent to the next LLM call.

---

### 20.3 — Lazy / cached tool-schema send to the LLM

**Status (2026-05-19):** ✅ **LANDED** in commit `f905619` ("cached
tool-schema policy (flag-gated, off by default)"). Per-pool
`ToolSchemaCachePolicy` consulted before each round's tool spec is
sent; flag-gated so the default behaviour is unchanged.

**Source pattern:** cache the tool-spec JSON per conversation; omit it
on every LLM call after the first; force-resend every N turns or when
the tool set actually changes.

**The gap:** `GroqPool.runToolLoop` (and the mirror in
`CerebrasPool` / `NvidiaPool`) builds `tools` JSON on every round via
`buildConversationPayload`. The tool spec is ~2–4 KB of pure
schema. Across 5 rounds × 200 papers × 3 active models that's
~12 MB of redundant tool-spec tokens per refresh batch.

**Files (proposed):**
- `GroqPool.buildConversationPayload(modelId, messages, tools, tokenLimit)`
  — drop the `tools` field on rounds ≥ 2 by default. Re-include on
  the first round of each new paper and on any round where the tool
  set changed (the `dynamicCompute` toggle, the `sandboxed-code` flag,
  etc.). Mirror in `CerebrasPool`, `NvidiaPool`.
- Track per-`(modelId, paperId)` whether the tools have been sent
  this paper. Reset on a new paper id.

**Acceptance:** Per-paper Groq token count drops on tool-heavy papers
without regressing the LLM's tool-invocation rate. If the LLM "forgets"
the tools and starts hallucinating tool names, revert by raising the
re-send cadence to every round.

**Risk:** Low–medium. Models can drop tool awareness across long
contexts; mitigation is the "re-send every N rounds" knob (start at
N=3).

**Open questions:**
- Does the Groq tool-call API require `tools` on every round, or is
  the first-round spec sticky for the conversation? Verify against
  Groq docs before assuming.
- If Cerebras / NVIDIA require it every round but Groq doesn't, this
  is a per-pool optimization, not a registry-level one.

---

### 20.4 — Structured `ToolCallResult` envelope for `ResearchNarrationService`

**Status (2026-05-19):** ✅ **LANDED** across six commits — steps 1
(`bd6e0a8` envelope + OutcomeStatus enum), 2a (`60955fa` primary
runNarration emission site), 2b-1 (`874c26b` OutcomeStatus
propagation from maybeCritiqueAndRevise), 2b-2 (`efbb440` per-paper
token accumulator via LlmCallContext), 2b-3 (`8757421` sentinel
publisher contract for suppression branches), and 3 (`1d3692e`
persist envelope on ResearchPaper + ActivityReporter rollup).

**Source pattern:** every sub-agent call returns a typed result
envelope with fields `(task_id, status enum, result, error,
started_at, completed_at, token_usage_records, ai_messages)`. Status
is an explicit enum (`PENDING`, `RUNNING`, `COMPLETED`, `FAILED`,
`TIMED_OUT`, `CANCELLED`). Callers branch on the enum, not on
exception type.

**The gap:** Today `ResearchNarrationService` returns a
`NarrationResult` (per
`src/main/java/com/emergentterrain/research/NarrationResult.java`) that
carries synthesis text, citation IDs, template id/version, and the
tool-call trace — but **no timing, no per-call token totals as a
first-class field, no explicit status enum**. Callers (UniversityService
publish path) currently infer success/failure from null-checks and
parsed call-path strings.

**Files (proposed):**
- `NarrationResult.java` — add `Instant startedAt`, `Instant completedAt`,
  `long inputTokens`, `long outputTokens`, `OutcomeStatus status` enum
  (`OK`, `PARTIAL`, `BUDGET_EXHAUSTED`, `ALL_MODELS_BLOCKED`,
  `TOOL_LOOP_LIMIT`, `ERROR`). Existing constructors stay (default the
  new fields) so the change is non-breaking.
- `ResearchNarrationService` — populate the new fields at the existing
  emission sites. Status maps directly from the existing exception
  branches (which already log distinct reasons).
- `UniversityService.publishPaper` — record the new fields onto
  `ResearchPaper` for activity reporting (`ActivityReporter` already
  rolls these into the 30-second activity rows; richer fields means
  richer reporting).

**Acceptance:** Existing publish path is bit-identical when the new
fields are unread. Activity rows gain `narration_status` and per-paper
input/output token totals.

**Risk:** Low — additive. The `NarrationResult` record has a documented
"null = template fallback" contract for several fields; the new fields
keep that pattern (null/zero when not measured).

**Open questions:**
- Should `status` cover the panel-grade-floor demote path (where the
  narration succeeded but the paper got rejected downstream)? Probably
  no — narration status is per-narration, not per-paper outcome.

---

### 20.5 — Per-agent / per-pool config overrides via `application.yml`

**Status (2026-05-19):** ✅ **LANDED** in commit `32cda10` ("per-role
config overrides via app.ai.role-overrides"). New `RoleRouterPolicy`
+ one-field `LlmCallContext` extension; operators can prefer-providers
or avoid-models per role without code edits.

**Source pattern:** YAML-driven per-agent overrides with a fixed merge
order (built-in defaults → custom config → agent-specific). Knobs
include model selection, timeout, and tool allow/deny lists. Lets
operators retune routing without code changes.

**The gap:** ET selects models via `PrimaryModelRegistry` /
`FastModelRegistry` round-robin against pool-aggregated handles. There
is no way to say "for `fast-audit` role, prefer Cerebras qwen-235b
over Groq llama-3.3" without code edits (the Phase 9 U3 / U3b primary
provider order is global, not per-role). When a specific bottleneck
appears (e.g., this conversation's Phase 18 work showed Groq llama
tool-call 429s drove the bulk of failures), the only lever is
ordering at the registry level for all roles at once.

**Pairs with 20.11.** 20.5 routes among whatever provider pools the
registry knows about. **20.11 lets the operator add new provider pools
via YAML alone.** Together they deliver end-to-end plug-and-play: add
a provider in YAML (20.11) and tune which roles prefer it in YAML
(20.5), with zero code edits. 20.5 still works without 20.11 — it
just routes over the five currently-hardcoded pools.

**Files (proposed):**
- New top-level `application.yml` section:
  ```yaml
  app.ai.role-overrides:
    fast-audit:
      preferred-providers: [cerebras, groq, ollama]
    professor-tools:
      preferred-providers: [groq, cerebras, nvidia]
      avoid-models: [groq/llama-3.3-70b-versatile]  # mid-2026-05 daily-quota issue
  ```
- New: `src/main/java/com/emergentterrain/service/RoleRouterPolicy.java`
  — reads the overrides; called by `LlmRouter.orderedAvailable` (and
  the `*ToolEnabled` variant) to filter / reorder handles for the
  specific role tag.
- Plumb the role tag through the existing `LlmCallContext` (already
  carries `paperId`, `temperature`, `jsonOutputMode` — extend with
  `currentRole()`).

**Acceptance:** Setting `avoid-models: [groq/llama-3.3-70b-versatile]`
in `application.yml` removes that handle from the dispatch list for
the specified role without restarting any other path. Removing the
config line restores it.

**Risk:** Low. The override layer is purely additive; an empty
`role-overrides` section reproduces today's behavior.

**Open questions:**
- Is "role" the right axis, or "bot archetype"? Phase 6 talks about
  bot archetypes (`BotArchetypeBehaviorService`); the override could
  key on either. Role is simpler; archetype is more powerful. Start
  with role, escalate if needed.

---

### 20.6 — MCP tool-transport integration (deferred)

**Source pattern:** an MCP-server registry config plus filtered
initialization — tools live in separate processes (stdio or HTTP), the
agent reads a registry, spawns one client per server, and pulls tool
schemas dynamically. Pattern is grounded in the Anthropic MCP spec,
not any specific implementation.

**The gap:** All ET tools (`MathToolSuite`, `DynamicComputeEngine`,
`SandboxEngine`, GPU-engine RPCs) are in-process Java methods. The MCP
protocol would let us expose them over stdio/HTTP so external tools
(or a remote professor LLM) could invoke them through a standard
schema; conversely, we could consume third-party MCP servers
(filesystem search, web fetch, etc.) without writing per-server glue.

**Why deferred:**
- Adds a runtime dependency (an MCP SDK — none mature for Java yet;
  options include a thin in-house port of the Python/TS SDK).
- Requires process / transport / auth design that doesn't have a
  clear payoff today since the professor LLM is the only consumer
  and it runs in-process.

**Trigger to revisit:**
- A second consumer of ET's tool surface appears (e.g., a remote
  reviewer agent, or someone wants to drive `MathToolSuite` from a
  notebook).
- OR a useful third-party MCP server appears that we'd want to
  consume (e.g., a literature-search MCP that could replace
  `LiteratureSearchService`'s ad-hoc client).

**No code change in Phase 20.** This item is a tracker pointer for
when one of the triggers fires; at that point spin up its own phase
doc.

---

## Items targeted at the GPU engine

### 20.7 — Operator-level instrumentation (MFU / MBU)

**Status (2026-05-19):** ✅ **LANDED** in engine repo
`EmergentTerrainGPUEngine` commit `def01b9` ("per-pipeline performance
counters on every RPC response"). Per-pipeline timings + bytes
in/out + FLOPs estimate now shipped on each gRPC response, so MFU /
MBU can be computed by callers.

**Source pattern:** every operator declares `read_bytes`,
`write_bytes`, `calc_flops`. Post-execution the harness computes
**MFU** = `calc_flops / (peak_tflops × latency)` (math utilization
versus theoretical peak) and **MBU** = `(read+write) / (peak_bw ×
latency)` (memory bandwidth utilization). The split tells you whether
a kernel is compute-bound or memory-bound.

**The gap:** The GPU engine
(`EmergentTerrainGPUEngine/src/main.rs`) has three compute pipelines
(WORLD, PATCH, BOT) and zero per-pipeline telemetry. We don't know if
PATCH is memory-bound (likely — it copies source + writes patch) or
compute-bound; we don't know what fraction of WORLD's time is on the
noise-hash ALU work versus buffer initialization. Without that, any
optimization is shooting in the dark.

**Files (proposed):**
- `EmergentTerrainGPUEngine/src/main.rs` — wrap each
  `compute_pass.dispatch_workgroups` call with `wgpu::QuerySet`
  timestamps (begin/end), record `bytes_read = sum(input buffer
  sizes)`, `bytes_written = sum(output buffer sizes)`,
  `flops_estimate = workgroup_count * threads_per_group *
  ops_per_thread` (the per-shader estimate is a constant we hand-code).
- Add a `PipelineStats` field on each gRPC response (optional via a
  `with_stats: bool` request flag) carrying `latency_us`,
  `mem_bw_gbps`, `flops_tflops`, `mem_bw_pct_of_peak`,
  `flops_pct_of_peak`.
- New gRPC method `GetEngineCapabilities` returns peak FLOPs + peak
  memory bandwidth for the active adapter so the Java side can
  contextualize percentages.

**Acceptance:** After one `rebuild_patch` RPC with `with_stats=true`,
the response includes a latency / bandwidth / FLOPs row. A regression
in the WGSL (e.g., dropping a workgroup-size hint) shows up as a
percentage drop in the right metric.

**Risk:** Low–medium. `wgpu::QuerySet` timestamp queries require the
`TIMESTAMP_QUERY` feature; not all backends support it. Fall back to
CPU-side wall-clock timing when the feature is unavailable.

**Open questions:**
- Do we surface stats to the Java side and persist them
  (`ActivityReporter`), or keep them engine-local for debugging?
  Probably persist — would let us catch engine perf regressions
  across releases.

---

### 20.8 — Streaming progress on long-running RPCs

**Status (2026-05-19):** ✅ **LANDED** in engine repo commit
`0571322` ("streaming SimulateBotsV2 RPC"). Callers can opt into the
streaming variant for progress events; the legacy unary RPC remains
for back-compat until consumers migrate.

**Source pattern:** long-running operations emit NDJSON over HTTP —
`{"event":"progress","completed":N,"total":T,"percent":X,...}` lines
during execution, then a terminal `{"event":"done","data":{...}}`. The
client knows the call is alive and how far along.

**The gap:** `SimulateBots` for a large bot population is the
long-pole RPC. Today it blocks the gRPC channel for the duration —
the Java caller has no signal between "submitted" and "got result."
On the Java side, `BotSimulationGpuService` waits, and if the engine
hangs we only notice on timeout.

**Files (proposed):**
- Change the `SimulateBots` RPC to a server-streaming call:
  ```proto
  rpc SimulateBots (SimulateBotsRequest) returns (stream SimulateBotsUpdate);
  ```
  with `SimulateBotsUpdate { oneof payload { Progress progress = 1;
  SimulateBotsResponse done = 2; } }`.
- The engine emits a progress message every N workgroups or every
  Y ms (whichever first), and a single `done` message at the end.
- `GpuEngineClient.java` — adapt to the stream; preserve the
  existing synchronous wrapper for callers that don't want progress.

**Acceptance:** A 60-second `SimulateBots` call emits ≥5 progress
updates; the Java side logs `bot-sim progress=X/Y` on each.

**Risk:** Medium. The RPC contract change is breaking for clients
that pinned the unary signature; mitigate by adding `SimulateBotsV2`
streaming and leaving `SimulateBots` unary.

**Open questions:**
- Worth doing the same for `GenerateRegion`? Region generation is
  typically <100 ms; probably not worth the complexity. Patch
  rebuild similar.

---

### 20.9 — Stage / pipeline trait split

**Status (2026-05-19):** ✅ **step 1 LANDED** in engine repo commit
`5d2f685` ("phase-20.9 step 1: extract shader constants to
pipelines/{world,patch,bots}.rs"). Shader constants moved into one
file per pipeline under `pipelines/`. The next step (a typed
`ComputePipeline` trait + per-pipeline unit tests) is **the only
remaining 20.9 work**, naturally pulled when pipeline #4 (the
distance-transform candidate from `changes-for-gpu-based-app.md`)
gets added.

**Source pattern:** workflow assembled from named stages — each stage
is a separate file implementing a typed contract (take config + state,
return state + telemetry). Stages can be tested in isolation; new
stages drop in by adding a file.

**The gap:** `main.rs` is a single 1160-line file with the three
pipelines, the gRPC service trait impl, the device init, and the
shader constants all inlined. Adding a fourth pipeline (e.g.,
distance-transform for highway influence per the
`changes-for-gpu-based-app.md` Stage 4 plan) means editing the same
file. Tests for one pipeline can't run without booting the whole
service.

**Files (proposed):**
- `EmergentTerrainGPUEngine/src/pipelines/mod.rs` — new directory.
  Trait `ComputePipeline` with `build(device, queue) -> Self`,
  `dispatch(&self, request) -> Result<Response>`, and a
  `kind() -> PipelineKind` for telemetry.
- `pipelines/world.rs`, `pipelines/patch.rs`, `pipelines/bots.rs` —
  one file per pipeline, owning its shader constant and bind-group
  layout. `main.rs` keeps the gRPC handlers thin, delegating to the
  trait.
- Each pipeline gets its own unit test that doesn't require the
  full service stand-up (mock device via wgpu's
  `RequestAdapterOptions::default()` in test mode).

**Acceptance:** Adding a fourth pipeline (the
`changes-for-gpu-based-app.md` distance-transform candidate) means
one new file under `pipelines/`, one gRPC method addition, and zero
edits to existing pipeline files.

**Risk:** Low. Refactor only — semantics preserved.

**Open questions:**
- Does WGSL hot-reload (load `.wgsl` from disk in debug builds, embed
  via `include_str!` in release) earn its keep? Probably yes for
  shader iteration speed; tracker for a sub-item if the refactor
  goes ahead.

---

### 20.10 — Externalize tunables (forest/water/industry thresholds, spacing)

**Status (2026-05-19):** ✅ **LANDED** in engine repo
`EmergentTerrainGPUEngine` commit `2b19dc9` ("phase-20.10:
externalize world tunables to gpu.toml"). New `src/config.rs` with
`WorldConfig` struct loaded from the path in `GPU_ENGINE_CONFIG`
(default `./gpu.toml`); missing file → silent defaults; malformed →
warn-and-use-defaults (engine is a perf service, not a config
validator). Reference TOML shipped as `gpu.toml.example` at the engine
repo root. Operator can now A/B world flavors by editing TOML +
restarting `cargo run` — no rebuild.

**Source pattern:** a `ConfigManager` that reads TOML, merges with a
default preset, validates that no unknown keys are present, finalizes,
and persists the finalized config alongside results so each run is
reproducible.

**The gap:** Today `forest_threshold`, `water_threshold`,
`industry_threshold`, `highway_spacing` are encoded in the
`WORLD_SHADER` constant string and the `GenerateRegionRequest`
proto. Changing one to A/B world generation means a Rust rebuild.
For experiments ("what does the bot ecology look like if we 2× the
forest fraction?") that's prohibitive.

**Files (proposed):**
- Move the four tunables out of the WGSL constant and into a uniform
  buffer (already partly true — `Params` struct is uniform — but the
  field set is incomplete).
- Add a `gpu.toml` config at the engine root with `[world.thresholds]`
  section + `[world.highways]` section. Use `serde` + `toml` crate
  (no new heavy dep — both already in the ecosystem). On RPC, the
  request can override the TOML defaults per call.
- `cargo run` with `--config gpu.toml` (default: current values).

**Acceptance:** Operator can tweak `forest_threshold` and restart
`cargo run` (no rebuild) to A/B world flavors.

**Risk:** Very low.

**Open questions:**
- Bot simulation has its own constants buried in `BOT_SHADER`;
  same treatment, separate sub-item.

---

## Plug-and-play extension

### 20.11 — Generic OpenAI-compat pool + YAML provider registration

**Status (2026-05-19):** ✅ **steps 1–3 LANDED** across three commits:
step 1 (`bc61362` — `GenericOpenAICompatPool` skeleton, sync path only),
step 2 (`1999401` — YAML-driven pool auto-registration via
`ProviderConfigLoader`), step 3 (`b24de70` — tool-call loop on
`GenericOpenAICompatPool`).

**Status (migration step 1, 2026-05-19):** scaffolding for the first
legacy-pool retirement **LANDED** in this session:
- `ProviderConfig` gained a `role` field (matching
  `LlmPool.role()`) so a YAML-configured pool can join LlmRouter's
  role-indexed routing the same way the hand-written pools do.
  `GenericOpenAICompatPool.role()` returns it.
- `CerebrasPool` now gated by `@ConditionalOnExpression` on BOTH
  `app.ai.cerebras.enabled` AND `app.ai.cerebras.legacy-pool`
  (latter defaults to true → no behaviour change). Operator flips
  `legacy-pool: false` to retire the hand-written class.
- `app.ai.providers.cerebras:` block added (commented) under the
  generic-pool config section as the operator's flip path. The
  comment block names the two-step flip + the two-week burn-in
  + the follow-up delete PR explicitly.

**Remaining 20.11 work:** the per-pool delete PRs themselves. One
per legacy class (Cerebras, OpenRouter, NVIDIA, Groq), in
ascending-quirk order. Cerebras is the smallest; Groq is the largest
(BODY-source retry-after, TPD/TPM split). OllamaPool stays — its
`/api/tags` discovery diverges from OpenAI-compat.

**Status (step 3 OpenRouter scaffolding, 2026-05-20):** **LANDED**.
- `ProviderConfig` gained `extraHeaders: Map<String,String>` to carry
  the OpenRouter `HTTP-Referer` / `X-Title` analytics headers (and any
  future per-provider header quirks). `GenericOpenAICompatPool`
  applies them at both HTTP build sites via a shared
  `applyExtraHeaders(builder)` helper.
- `OpenRouterPool` now gated by
  `@ConditionalOnExpression("${app.ai.openrouter.enabled:false} and ${app.ai.openrouter.legacy-pool:true}")`.
- Commented `app.ai.providers.openrouter:` block added under the
  generic-pool config section with `extra-headers:` filled in.
- **Feature-parity gap explicitly documented**: the OpenRouter
  request-body `excludedBackends` (translates to OpenRouter's
  `provider.ignore` knob) is NOT yet supported by
  `GenericOpenAICompatPool`. Hold the OpenRouter flip until that
  lands in a follow-up. Cerebras has no such gap and can flip today.

### 20.11 step 2b — Cerebras class-deletion checklist (pending operator burn-in)

Pre-recorded fire-when-ready checklist. After the operator has run the
flipped `app.ai.cerebras.legacy-pool=false` setting against the corpus
for at least two weeks AND a REVIEW_REPORT refresh shows no Cerebras-
specific regressions (no missed `prof:cerebras:ok` stamps, no
fast-audit role lookup failures, no missing 30-RPM block warnings),
the deletion PR is mechanical:

| Action | File | Note |
|---|---|---|
| **Delete** | `src/main/java/com/emergentterrain/service/CerebrasPool.java` | Class is the entire migration surface |
| **Delete** | `src/test/java/com/emergentterrain/service/CerebrasPoolParseTest.java` | Only test that constructs the concrete class |
| **Edit** | `src/main/resources/application.yml` | Uncomment the `providers.cerebras:` block (now the active path); remove the `app.ai.cerebras.*` legacy block (`enabled`, `apiKey`, `baseUrl`, `preferredModels`, `role`, `legacy-pool` flag); remove the `legacy-pool` comment paragraph |
| **Edit** | `src/main/java/com/emergentterrain/llm/LlmCallContext.java` | Drop `CerebrasPool` from the javadoc list (cosmetic) |
| **Edit** | `phases-for-human-like-performance/phase-20-reference-repo-scoping.md` | Mark Cerebras line in this checklist as ✅ DELETED; trim Cerebras-specific notes from §20.11 status section |

Production references to `CerebrasPool` (the class) are zero outside
the class itself — verified with
`grep -rn "CerebrasPool" src/main/java` after this commit. Production
code refers to `cerebras` only as a string provider key (in
`LlmRouter.DEFAULT_PRIMARY_ORDER`, `ReviewPanelService.DEFAULT_PROVIDERS`,
`RoleRouterPolicy` examples) — those keep working as-is because the
generic pool registers under the same provider key from YAML.

Test references (`LlmRouterChainTest`, `ReviewPanelServiceTest`,
`PanelReviewTest`) use a `FakePool` shape with provider key
`"cerebras"` — not the concrete class. Untouched by the deletion.

**Acceptance for step 2b:** `mvn test-compile` clean; full
`mvn test -Dtest='LlmRouterChainTest,ReviewPanelServiceTest,PanelReviewTest'`
green; restart the app and confirm `GenericPoolsRegistrar(cerebras):
registered bean=openAiCompatPool_cerebras …` appears in the startup
log.

**Source pattern:** treat the OpenAI `/v1/chat/completions` protocol
as the universal substrate (most modern providers — Anthropic via
their OpenAI-compat endpoint, OpenAI, DeepSeek, Together, Fireworks,
Mistral, xAI, Perplexity, plus Groq / NVIDIA NIM / OpenRouter — all
expose it). Drive per-provider config from a data file, not a code
file. Surface per-provider quirks (header names, `max_tokens` vs
`max_completion_tokens`, retry-after-source) as flags.

**The gap:** Today `GroqPool`, `CerebrasPool`, `NvidiaPool`,
`OpenRouterPool`, `OllamaPool` are five separate Java classes,
~600–1000 lines each, with ~80% identical logic (same payload shape,
same response shape, same tool-call protocol, same 429-handling
skeleton — Phase 18 just confirmed this when it had to mirror the
same fix into three of them). Adding a sixth provider (Anthropic /
DeepSeek / Fireworks / etc.) is a full pool-class implementation
plus registry wiring plus rebuild plus restart. The operator has
expressed intent to add more providers over time; today's design
makes each one a Java project.

**Files (proposed):**
- New: `src/main/java/com/emergentterrain/service/GenericOpenAICompatPool.java`
  — implements `LlmPool` against the OpenAI-compat chat-completions
  protocol, parameterized by a `ProviderConfig` it receives at
  construction time. Owns all the cross-cutting logic that the five
  existing pools duplicate: payload build, response parse, 429
  handling (with the Phase 18 `recordRateLimitBlock` helper),
  tool-call loop, token accounting, model discovery, slot
  acquisition.
- New top-level `application.yml` section:
  ```yaml
  app.ai.providers:
    anthropic:
      type: openai-compat
      base-url: https://api.anthropic.com/v1
      api-key-env: ANTHROPIC_API_KEY
      models: [claude-opus-4-7, claude-sonnet-4-6, claude-haiku-4-5]
      tool-calling: true
      context-window: 200000
      quirks:
        max-tokens-field: max_completion_tokens
        retry-after-source: header

    deepseek:
      type: openai-compat
      base-url: https://api.deepseek.com/v1
      api-key-env: DEEPSEEK_API_KEY
      models-discovery: GET /models
      tool-calling: true

    fireworks:
      type: openai-compat
      base-url: https://api.fireworks.ai/inference/v1
      api-key-env: FIREWORKS_API_KEY
      models-discovery: GET /models
  ```
- New: `src/main/java/com/emergentterrain/service/ProviderConfigLoader.java`
  — reads the new section, instantiates one `GenericOpenAICompatPool`
  per entry, registers each with `PrimaryModelRegistry` and (if the
  config opts in) `FastModelRegistry`.
- Existing pool classes (`GroqPool`, `CerebrasPool`, `NvidiaPool`,
  `OpenRouterPool`) — incrementally migrate to `GenericOpenAICompatPool`
  + a config row. Delete the class once the row covers it. Final
  shape: most existing pools disappear; only ones with truly custom
  protocols (e.g., `OllamaPool` if its API diverges) remain as
  hand-written code.

**Acceptance:**
- Adding a new OpenAI-compat provider is 3–10 lines of YAML +
  restart. No Java code. No rebuild.
- Existing five-provider behavior is bit-identical after migration —
  same models discovered, same selection probes pass, same 429
  recovery times, same per-paper token totals on a replay run.
- A new provider added via YAML appears in `PrimaryModelRegistry.allModels()`
  and gets routed by `LlmRouter.orderedAvailable()` exactly like the
  hardcoded pools.

**Risk:** Medium. The refactor touches every pool class and the
registry wiring; it's a 3–5 day project, not a 30-minute edit. The
discipline is to migrate one pool at a time behind a feature flag
(`generic-openai-compat-pool.enabled-providers: [cerebras]` first,
then add the others as each is validated against the existing
behavior on a replay corpus). Two-week burn-in per provider before
deleting the legacy class.

**Open questions:**
- How to handle `Ollama`'s discovery / model-list endpoint (it's not
  exactly OpenAI-compat — `/api/tags` instead of `/v1/models`).
  Probably leave as a separate hand-written pool unless it gains a
  fully-compliant endpoint.
- How to handle the per-pool `ModelSelectionTester` probes — they're
  pool-agnostic at the surface (chat probe + JSON probe + tool
  probe), so the generic pool should inherit them automatically.
  Verify before assuming.
- Per-provider rate-limit metadata (TPD caps, TPM caps) — let YAML
  declare them, or discover from 429 responses? Hybrid: YAML
  declaration is the floor (so we don't waste a request to find the
  cap), 429 response refines.

---

## Items NOT pending (audit results)

Patterns flagged by the survey that the audit found are already
implemented in ET or are out of scope. Skip these to avoid duplicating
work.

| Pattern flagged | Status | Evidence |
|-----------------|--------|----------|
| Per-MODEL rate-limit lock (not pool-wide) | DONE | `CerebrasPool.java:90` `rateLimitExpiry = ConcurrentHashMap<String,Long>`, `NvidiaPool.java:87` same, `GroqPool.java:139` `tpdExpiry` + `:147` `tpmExpiry` all per-model. Phase 18 just shipped (`35f1ae7`) to extend this to the tool-call path. |
| Exponential backoff with Retry-After parsing | DONE | `RateLimitParser.java` + per-pool `syncCallWithBackoff` / `recordGroqRateLimitBlock`. Level-tracked-doubling variants exist upstream but are not strictly better than our parse-server-hint approach. |
| Lazy sandbox lifecycle (reuse across tool calls) | NOT APPLICABLE | ET's `SandboxEngine` is an in-process JavaScript-like interpreter, not a subprocess (`research/SandboxEngine.java:1–30` — `Interpreter` class, no `ProcessBuilder`). The amortization win (subprocess startup) doesn't translate. |
| Per-turn loop detection middleware | NOT NEEDED | ET's tool loop is bounded by `MAX_TOOL_ROUNDS = 5` per pool; the LLM cannot loop indefinitely. |
| Deferred tool-schema discovery | OUT OF SCOPE | Marginal token savings; ET's tool surface is small (`MathToolSuite` + a handful of methods), not 20+ tools where deferred discovery earns its keep. 20.3 (cached-schema send) is the right-sized win for our surface. |
| Multi-turn agent loop | DONE | `ResearchNarrationService` + per-pool `runToolLoop` already implement this. |
| Message parity repair (orphan tool_use / tool_result fixup) | UNAUDITED | Worth a separate spot-check — if Cerebras / Groq ever reject our messages for parity issues we have not seen it logged, but the failure mode would be silent. Track as a low-priority sub-item; promote if observed. |
| Tool namespacing via prefix | NOT NEEDED YET | ET has one tool namespace (`MathToolSuite`); namespacing earns its keep when ≥2 sources export same-named tools, which we don't have today. Pull when 20.6 (MCP) lands. |
| Operator registry + vendor provider fallback | OUT OF SCOPE | The engine has three pipelines; a runtime registry is overengineering until we have ≥10 pipelines or vendor-specific kernels to swap. The stage split in 20.9 covers the modularity need. |
| Multi-device shard-map + all-gather | OUT OF SCOPE | Multi-GPU is its own phase project; don't fold into 20. |
| Typed data containers with sharding specs | OUT OF SCOPE | Same — pull when multi-GPU lands. |
| Random-exploration → goal-directed reasoning curve (GUI-trajectory style) | SKIP | GUI-bound MDP; doesn't transfer to ET's epistemic bot decisions. |
| Vision-language model serving | SKIP | Model-weights repo style — not an orchestration system. |

---

## Closing condition for Phase 20

This phase closes when each numbered item has either:
1. Graduated into its own implementation phase doc (e.g., 20.1 →
   `phase-20a-tool-result-compression.md`) with a commit landing the
   work, OR
2. Been explicitly retired with a "deferred indefinitely — reason X"
   line edited into the item above.

When all eleven close, this doc becomes historical. New patterns mined
from future repo drops get a successor doc rather than appending here.

## Feasibility verdict

Ten of the eleven items are technically implementable today. 20.6
(MCP) is **deferred by design** — its trigger conditions (a second
consumer of ET's tool surface, OR a third-party MCP server worth
consuming) have not fired, and there is no mature Java MCP SDK to
lean on. Do not pull 20.6 on speculation.

The remaining ten carry no hard blockers; a few have soft
dependencies (20.1 wants Phase 18.1 live first; 20.3 wants a
provider-docs check; 20.5 wants 20.4's `currentRole()` plumbing; the
full plug-and-play story wants 20.11 + 20.5 together).

| Item | What it is | Feasibility | Blocker / dependency |
|------|------------|-------------|----------------------|
| 20.1 | Condense repeated `tool_result` payloads (sweep tables, intermediate values) before re-sending to the LLM on later rounds | ✅ implementable | Wants Phase 18.1 (trace-scan validator) live first so compression doesn't hide values the validator needs |
| 20.2 | Stage-1 tag truncation + stage-2 whole-turn drop, so long tool loops degrade gracefully instead of failing at the context-window cap | ✅ implementable | None — pure additive Java service |
| 20.3 | Cache the tool-spec JSON per `(modelId, paperId)` so it's sent only on round 1 (plus periodic re-send insurance) | ✅ implementable | 30-min provider-docs check on Groq / Cerebras / NVIDIA tool-stickiness behavior before flipping the flag |
| 20.4 | Extend `NarrationResult` with `startedAt` / `completedAt` / input+output tokens / status enum so callers branch on enum instead of parsing call-path strings | ✅ implementable | None — additive record-field change, non-breaking |
| 20.5 | YAML `app.ai.role-overrides:` block so operators can prefer-providers or avoid-models per role (e.g. `fast-audit`, `professor-tools`) without code edits | ✅ implementable | None — new `RoleRouterPolicy` + one-field `LlmCallContext` extension |
| 20.6 | Expose ET's tools as MCP servers and/or consume third-party MCP servers (filesystem, web fetch, literature search) | ⛔ **DEFERRED by design** | No mature Java MCP SDK; trigger is "a second consumer of ET's tools appears" OR "a third-party MCP server appears worth consuming" — neither has fired |
| 20.7 | Per-pipeline GPU instrumentation: `wgpu::QuerySet` timestamps + bytes-in/out + FLOPs estimates → MFU / MBU percentages on each RPC response | ✅ implementable | `TIMESTAMP_QUERY` wgpu feature should be present on the operator's AMD GPU; CPU wall-clock fallback covers the gap |
| 20.8 | Convert long-pole `SimulateBots` from unary gRPC to server-streaming so callers see progress updates instead of just a blocked channel | ✅ implementable | Breaking proto change; mitigated by adding `SimulateBotsV2` streaming and leaving `SimulateBots` unary until callers migrate |
| 20.9 | Split `main.rs` into a `pipelines/` directory with one file per compute pipeline behind a `ComputePipeline` trait; pipelines testable in isolation | ✅ implementable | None — pure refactor; golden tests prove behavior identity |
| 20.10 | Move `forest_threshold` / `water_threshold` / `industry_threshold` / `highway_spacing` from the WGSL constant into a `gpu.toml` config | ✅ implementable | None — smallest change in the doc |
| 20.11 | Generic OpenAI-compat pool driven by YAML provider registration; new providers (Anthropic, DeepSeek, Fireworks, etc.) added via config-only edits, no Java code | ✅ implementable | Bigger lift (~3–5 days refactor touching every pool class); migrate pools one at a time behind a feature flag. Pairs with 20.5 for full plug-and-play |

## Suggested order — three waves + a backlog

Operator's call; this is the author's read. Items within a wave are
independent and can land in parallel. Across waves there is a real
ordering reason.

### Wave 1 — additive, zero behavior change, immediate lift

Pick up in parallel. Each is self-contained and unblocks downstream
work.

- **20.4 (`NarrationResult` envelope)** — non-breaking field
  additions to the record. `ActivityReporter` rows immediately gain
  `narration_status` + per-paper token totals. Diagnosing the next
  Phase-18-style incident becomes minutes instead of hours.
- **20.10 (TOML tunables for the engine)** — smallest diff in the
  doc. Unblocks A/B world-flavor experiments without a Rust rebuild.
- **20.7 (GPU MFU/MBU instrumentation)** — self-contained Rust work,
  no Java-side coordination. Establishes the measurement baseline
  every future engine perf decision will reference.

### Wave 2 — operator-leverage wins + plug-and-play

Wait for Wave 1 to land (20.5 wants 20.4's `currentRole()` plumbing;
20.11 wants 20.4's status enum populated to ground the migration
golden-tests). 20.11 is the biggest item in this wave (~3–5 days);
the others are 1–2 days each.

- **20.11 (generic OpenAI-compat pool + YAML provider registration)** —
  the foundation for adding any new OpenAI-compat provider via
  config-only edits. Pair with 20.5 for end-to-end plug-and-play.
  Migrate pools one at a time behind a feature flag; two-week burn-in
  per migrated pool before deleting the legacy class.
- **20.5 (per-role config overrides)** — would have let us route
  around this week's Groq llama issue via config alone. Multiplies in
  value once 20.11 lands, because the set of providers routes-over
  stops being capped at five hardcoded classes.
- **20.3 (cached tool-schema send)** — cheapest token-budget win.
  Verify per-provider tool-stickiness behavior first (30-min docs
  read); ship behind `cached-tool-schema.enabled=false` per pool;
  flip once verified.

### Wave 3 — pull when a triggering event makes them earn their keep

Each is a real upgrade, but each currently has zero incidents driving
demand. Pulling on speculation burns engineering time better spent on
items with a concrete pain point behind them.

- **20.1 (tool_result compression)** — gated on Phase 18.1
  (trace-scan validator) shipping first. Pull when 18.1 is live AND
  we see a paper batch hit token-quota caps that compression would
  have prevented.
- **20.2 (tag-level history compression)** — pull when the first
  paper actually fails for context-window saturation (today's
  `MAX_TOOL_ROUNDS = 5` ceiling makes this speculative).
- **20.8 (streaming progress RPC)** — pull when we hit a real
  `SimulateBots` long-pole worth observing.
- **20.9 (engine stage split)** — refactor on its own has no
  behavior payoff. Pull *as part of adding pipeline #4* (the
  flow-field / distance-transform candidate from
  `changes-for-gpu-based-app.md`) — same cost, payoff twice.

### Backlog — do not implement now

- **20.6 (MCP)** — keep as a tracker pointer with named triggers.
  Revisit when either trigger fires.

### Net single-sequence view

If you ignore parallelism and pick one global order:

`20.4 → 20.10 → 20.7 → 20.11 → 20.5 → 20.3 → (wait for trigger) → 20.1 / 20.2 / 20.8 / 20.9 → (deferred) → 20.6`
