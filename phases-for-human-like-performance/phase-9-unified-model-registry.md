# Phase 9 — Unified Model Registry + Two-Tier Architecture (scoping)

**Scope:** Replace the per-provider pool walking that Phase 7's `LlmRouter` /
`ReviewPanelService` rely on with a model-centric registry that aggregates every
70B+ model across every provider. Migrate the sub-70B "fast" tier into a parallel
registry of its own, give it the same selection-test contract every primary
model gets, and move Ollama's config from the Spring AI auto-config keys into
the unified `app.ai.*` namespace.

**Status:** Scoping document. No code in this phase yet. Implementation lands
across six commits (U1–U6) plus this doc.

**Prerequisite:** Phase 7 F5+F7 follow-on series shipped (commits `db2553f`
through `92a82c7`). The current architecture — sequential panel review,
provider-agnostic rate-limit headers, tool-call trace persistence + audit — is
the baseline this phase generalises.

**Why now:** Three pressures came from observing the F5+F7 follow-on series in
the room:

1. **Provider silos waste capacity.** The Phase 7 panel calls `pool.callSync`
   once per provider. If Groq's two 70B+ models are both quota-blocked, the
   Groq reviewer slot dies even though NVIDIA has 4 healthy 70B+ models within
   reach. Same for any other narration call routed through a single pool.
2. **Sub-70B tier is untested.** Every `<70B` Ollama call is trusted blindly
   today: the configured model name is used verbatim, no startup probe, no JSON
   capability check. The primary tier runs `discoverAndRankModels` for exactly
   this reason; the fast tier should too.
3. **Ollama config drifts from the unified pattern.** Spring AI's
   `spring.ai.ollama.*` properties don't match how Groq / NVIDIA / OpenRouter /
   Cerebras are configured (`app.ai.<provider>.*` with `preferredModels` CSVs).
   Future fast-tier additions (Together's small models, NVIDIA's `<70B` options)
   should slot into the same shape.

**Goal:** Two registries, one interface, uniform startup tests.

- **U-series** — six commits delivering the two-tier registry, Ollama config
  migration, and universal selection tests. Sizing below.
- **Outside scope:** routing policy changes (priority order, role tagging) —
  those stay as in Phase 7 E1.6. The registry is a substrate change, not a
  policy change.

**Why this is risky:**

- The registry changes the call shape for **every** LLM call in the project.
  Done wrong, narration quality, audit, refinement, and the multi-reviewer
  panel all share one failure mode.
- U3 migrates the existing E2 `app.ai.tool-calling.providers` filter from
  pool-level to model-level. A bug here silently disables non-Groq tool
  calling — the exact regression Phase 7 E2 was built to surface.
- U5 runs startup selection tests against every configured model on every
  pool. A slow Ollama probe must not block the JVM boot — but per operator
  direction (2026-05-15), no timeout drops slow models either. The test runs
  patiently; latency is logged, never used as a drop signal.

---

## Architecture

```
                     ┌──────────────────────┐
                     │   ResearchNarration  │
                     │   ReviewPanelService │
                     └──────────┬───────────┘
                                │
                ┌───────────────┼───────────────┐
                ▼                               ▼
   PrimaryModelRegistry             FastModelRegistry
   (every 70B+ model across         (every <70B / fast / cheap
    Groq, NVIDIA, OpenRouter,        model — Ollama today,
    Cerebras)                        extensible to future
                │                    fast-tier providers)
                │                               │
   ┌─────┬──────┼──────┬────────┐               │
   ▼     ▼      ▼      ▼        ▼               ▼
 Groq Nvidia OpenRtr Cerebras  ...           Ollama (+future)

   Each registry owns:
     - list of (pool, modelId) tuples
     - per-model block status (delegated to pool)
     - unified callSync / callStream / callWithTools entry points
     - sequential 429 fallthrough across all registered models
     - availability-based picking (no provider-diversity preference;
       per operator direction 2026-05-15)
```

**Unified surface** — both registries implement `ModelRegistry`:

```java
interface ModelRegistry {
    String callSync(String prompt);
    void callStream(String prompt, Consumer<String> sink);
    ToolCallResponse callWithTools(String sys, String user, String toolsJson,
                                    Function<String,String> exec);
    List<ModelHandle> availableModels();   // unblocked (pool, modelId) pairs
    int totalModelCount();
}

record ModelHandle(LlmPool pool, String modelId) {}
```

`callSync` walks `availableModels()` in order, dispatching each to its owning
pool. On 429 the model is marked blocked (existing per-pool state) and the
walk continues to the next available model. The walk only fails when every
model in the registry is blocked.

---

## U1 — `LlmPool` surface expansion

**Effort:** ~30 min. **Pre-condition:** none.

Add four entry points to the `LlmPool` interface so the registry can target
specific models:

```java
default String callSyncOnModel(String modelId, String prompt) {...}
default void callStreamOnModel(String modelId, String prompt, Consumer<String> sink) {...}
default ToolCallResponse callWithToolsOnModel(String modelId, String sys, String user,
                                                String toolsJson, Function<String,String> exec) {...}
default boolean isModelBlocked(String modelId) {...}
default List<String> modelIds() {...}
```

Each pool already has the underlying machinery — `syncCallWithBackoff(modelId,
prompt, tokenLimit)`, `runToolLoop(modelId, …)`, `rateLimitExpiry` / `tpdExpiry`
maps, `activeModels` list. The new methods are thin forwarders.

Default `UnsupportedOperationException` for `callWithToolsOnModel` on pools
without tool support (Ollama). Default `false` for `isModelBlocked` on stateless
pools.

**Acceptance:** every existing `LlmPool` consumer compiles unchanged. Test
fakes that override `callSync` continue to work via the default delegating
impls.

---

## U2 — `PrimaryModelRegistry` standalone

**Effort:** ~2 hours. **Pre-condition:** U1.

New `com.emergentterrain.service.PrimaryModelRegistry` (Spring `@Service`).

**Construction:**
- Inject `ApplicationContext`; collect every `LlmPool` bean where
  `supportsTools()` is true.
- For each pool, enumerate `pool.modelIds()`.
- Build `List<ModelHandle>` — every (pool, modelId) tuple.

**API:**
- `availableModels()` → filter to handles where `!pool.isModelBlocked(modelId)`,
  preserving registration order. Per operator direction (2026-05-15), **pure
  availability-based picking** — no provider-diversity bonus.
- `callSync(prompt)` → walk `availableModels()`, dispatch each to
  `pool.callSyncOnModel(modelId, prompt)`. Return first success. Throw if
  every model exhausted.
- `callStream`, `callWithTools` — analogous sequential walks.

**E2 filter migration:** the existing `app.ai.tool-calling.providers` CSV
becomes a model-level filter — at registry construction, exclude handles whose
`pool.providerKey()` is not in the opt-in list. The flag semantics stay the
same; what it filters changes from pool to model.

**Acceptance:** registry has unit tests covering: availability iteration,
sequential 429 fallthrough across providers, empty-after-all-blocked behaviour,
E2 filter migration.

---

## U3 — Wire registry into `ReviewPanelService` and `LlmRouter`

**Effort:** ~2 hours. **Pre-condition:** U2.

**ReviewPanelService:**
- Replace per-pool loop with `registry.availableModels()`.
- Pick up to `panelSize` distinct models (configurable target, default 4).
- For each `ModelHandle`, call `pool.callSyncOnModel(modelId, prompt)`.
- Reviewer ID becomes `"<providerKey>/<modelId>"` — same shape as today, but
  two reviewers may now share a provider when others are blocked.
- F7.4 degraded stamps continue to apply (`panel:n=<got>:degraded:from=<N>`).

**LlmRouter:**
- `callPrimary(prompt)` → `primaryRegistry.callSync(prompt)`.
- `streamPrimary(prompt, sink)` → `primaryRegistry.callStream(prompt, sink)`.
- `callPrimaryWithTools(sys, user, toolsJson, exec)` →
  `primaryRegistry.callWithTools(...)`. The existing role-tag preferences
  (`fast-audit` → Cerebras) become a sort key on `availableModels()`.

**Hard requirement:** with no pools blocked, the new path produces a
bit-identical first-model choice to the pre-U3 path. The acceptance test runs
both the old and new walk side-by-side over a 50-call synthetic workload and
asserts identical model selection at index 0.

**Acceptance:** Phase 7 E2 test (`LlmRouterChainTest` provider filter) passes
with the model-level filter equivalent. Phase 7 F7.2 panel tests pass with the
registry-backed implementation. No new test failures elsewhere.

---

## U4 — OllamaPool config migration

**Effort:** ~2 hours. **Pre-condition:** none (independent of U1–U3, but lands
after for narrative coherence).

**New config schema:**

```yaml
app:
  ai:
    ollama:
      enabled: true
      backendHosts: ${OLLAMA_HOSTS:localhost}     # CSV
      backendPort: ${OLLAMA_PORT:11434}
      backendParallelism: 3
      preferredModels: "gemma3:4b"                # CSV — multi-model future-proof
      temperature: 0.7
      maxTokens: 200
```

**Deprecated path:** `spring.ai.ollama.chat.model` etc. — if any are still
set, log a `WARN OllamaPool: legacy spring.ai.ollama.* keys detected; please
migrate to app.ai.ollama.*` and read them with lower priority than the new
keys for one release cycle.

**Multi-model support:** the existing `OllamaPool` keys one `OllamaChatModel`
per (backend × modelName). With multi-model config, the pool tracks a
`Map<String, List<Backend>>` where the key is modelId. `callSync(modelId, …)`
dispatches to the right backend list.

**Acceptance:** existing tests pass with the new config keys substituted.
Single-model config (`preferredModels: "gemma3:4b"`) behaves bit-identical to
today.

---

## U5 — Universal startup selection tests

**Effort:** ~3 hours. **Pre-condition:** U1, U4. (Could land before U2/U3.)

**Contract (per operator direction 2026-05-15):**

Every pool — primary and fast — runs selection tests at startup for every
configured model. A model joins `activeModels` only if it passes:

1. **Basic chat completion.** Prompt: `"reply with the single word OK"`. Pass:
   the response contains `OK`. Tests the model responds at all.
2. **JSON-mode test.** Prompt: `"return JSON with name=Euler, value=2.71828,
   good=true"`. Pass: response parses as JSON with the three keys present.
   Tests structured-output capability.
3. **(Tool-capable pools only) Tool-call test.** Same as the Phase 7 T-B
   probe: `tools=[get_value(key:string)]`, `tool_choice:auto`, user prompt
   `"call get_value with key=pi"`. Pass: response shape includes a
   `tool_calls[0].function.name == "get_value"` with `arguments.key == "pi"`.

**No timeout drops.** Ollama models can be slow; per operator direction, the
selection tests are patient. A model that eventually responds with valid
output passes regardless of how long it took. Latency is recorded in a
per-model log line (for visibility) but is **never used as a drop criterion**.

**What does drop a model:**
- Test returns an error (HTTP 4xx/5xx other than 429 — 429 retries with backoff)
- Test response is not parseable / does not contain the expected keys
- Test response contains an explicit `"error": …` envelope from the provider
- Account-entitlement errors (NVIDIA's "Function not found for account" — already
  caught by `paidTierModels` logic, preserved)

**Logging:**
- Per model: `INFO PoolName: selection-test model=X PASSED chat=15ms json=23ms [tools=42ms] total=80ms`
- On fail: `WARN PoolName: selection-test model=X FAILED reason=<short> — dropping from activeModels`
- Per pool summary: `INFO PoolName: selection-tests complete passed=N/M [models...]`

**Acceptance:** every pool's startup log shows selection-test PASS / FAIL lines
for each configured model. A deliberately-broken config (model ID that
doesn't exist on the provider) shows FAIL and the model is absent from
`activeModels`.

---

## U6 — `FastModelRegistry`

**Effort:** ~1 hour. **Pre-condition:** U2, U4, U5.

Mirror of `PrimaryModelRegistry` for the fast tier. Constructor selects pools
where `supportsTools()` is **false** (or, in the future, where a new
`isFastTier()` predicate returns true — for now `!supportsTools()` is the
de-facto split since only Ollama is in this tier).

**`LlmRouter.callFast`** routes through `fastRegistry.callSync`.

**FastModelRegistry use sites** (per operator's "your call" on point 2 of the
design questions, 2026-05-15):

| Site | Stays on fast registry? | Why |
|---|---|---|
| `runQualityAudit` | Yes | Audit is by-design cheap + fast; Cerebras-as-fast-audit role (E1.6) lives on the primary registry but the Ollama fallback stays on fast. |
| `maybeCritiqueAndReviseOllama` | Yes | This is the dedicated Ollama-fallback path (E3); routing it elsewhere defeats its purpose. |
| `statistical-framing` | Yes | Low-stakes prose reformatting; fast tier is the right cost profile. |

**Acceptance:** `LlmRouter.hasFastPool()` returns true iff
`fastRegistry.totalModelCount() > 0`. Existing fast-pool consumers continue to
work unchanged via the router.

---

## What Phase 9 deliberately does NOT change

- **Routing policy.** Phase 7's role tags (`fast-audit` → Cerebras), `app.ai.providers.enabled`
  primary order, and `app.ai.tool-calling.providers` opt-in filter all preserve
  their semantics. The registry is a different substrate; what gets routed and
  to whom is unchanged.
- **TokenAccountant / SimulationGate.** Per-provider quota tracking stays
  exactly as in Phase 7 — accounting is downstream of the per-pool call, not
  the registry walk.
- **PanelOutcome aggregation.** Median grades, agreement score, consensus
  cited / contradiction lists, and the cherry_picked_evidence audit gap all
  stay. The registry only changes *where* the N reviewer responses came from
  (specific models vs specific providers).
- **TokenBudgetReporter heartbeat.** The per-provider summary line stays; the
  registry doesn't re-aggregate token accounting.
- **F7's sequential semantics** (one reviewer at a time, ordered) stay — the
  walk is across `availableModels()` instead of `reviewerPools`, but the
  serial nature is preserved.

---

## Sequencing summary

```
U1 ────→ U2 ────→ U3
                       ╲
U4 ─────────→ U5 ─────→ U6
```

U1 unlocks U2 (the interface methods the registry calls).
U2 unlocks U3 (registry must exist before panel/router can use it).
U4 unlocks U5 (Ollama config-driven before universal tests can probe each
configured Ollama model).
U5 unlocks U6 (FastModelRegistry assumes survived models; selection tests
produce that set).

U1–U3 and U4–U6 can interleave; the doc orders them U1→U6 for narrative
coherence.

---

## Acceptance gates

| Piece | Acceptance test |
|-------|-----------------|
| **U1** | Every existing `LlmPool` consumer compiles unchanged. The new `callSyncOnModel` / `callWithToolsOnModel` methods produce identical results to today's `callSync` / `callWithTools` when the chosen model happens to be the first non-blocked one. |
| **U2** | Registry unit tests pass: availability iteration, sequential 429 fallthrough across providers, full-exhaustion exception, E2 filter migration. |
| **U3** | Bit-identical first-model choice across 50 calls vs the pre-U3 path. Phase 7 E2 test passes with the model-level E2 filter. Phase 7 F7.2 panel tests pass with the registry-backed panel. |
| **U4** | Single-model config behaves bit-identical to today. Multi-model config (`preferredModels: "gemma3:4b,qwen2:7b"`) registers both and round-robins. Legacy `spring.ai.ollama.*` keys log the deprecation warning. |
| **U5** | Per-model PASS/FAIL log lines visible at startup. A deliberately-broken model ID shows FAIL and is absent from `activeModels`. No model dropped purely for being slow. Selection tests gate startup but don't fail-fast on individual models. |
| **U6** | `LlmRouter.hasFastPool()` and `callFast()` work end-to-end via the fast registry. All three existing fast-tier consumers (`runQualityAudit` Ollama fallback, `maybeCritiqueAndReviseOllama`, `statistical-framing`) still produce papers. |

The phase is **complete** when:
- A refreshed `REVIEW_REPORT.md` after U1–U6 ship shows no regression in
  `PUBLISHED rev:*:ok` rate vs the Phase 7 F-series baseline.
- `/tmp/et-app.log` shows selection-test PASS lines for every configured model
  at startup, including every Ollama model.
- The first paper that publishes after U3 ships has `panel:n=<count>` in its
  `callPath` and the reviewer IDs visible in the persisted `panel_outcome` JSON
  span multiple providers (proving the registry is doing cross-provider
  dispatch in practice).
