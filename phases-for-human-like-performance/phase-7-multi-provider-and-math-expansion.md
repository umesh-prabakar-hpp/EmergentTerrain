# Phase 7 — Multi-Provider LLM Routing + Math Rule Engine Expansion (scoping)

**Scope:** Three intertwined workstreams that together unblock the room's first `PUBLISHED rev:*:ok` paper and clear the runway for richer per-paper computation:

- **E — Multi-provider LLM routing.** The May 13 2026 corpus shows 11 of 12 Q2+ candidates blocked at `prof:error→fp` because both Groq models hit TPD simultaneously. Operator has provisioned three additional free-tier provider keys (OpenRouter, NVIDIA, Cerebras — see [Provider readiness](#provider-readiness--2026-05-13-test-results) below). `LlmRouter`, `GroqPool`, `TokenAccountant`, `SimulationGate` need to become provider-pluralised rather than Groq-specialised.
- **F — Math Rule Engine Expansion.** [MathPhysicsEngine](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java) currently registers 5 topics per domain (20 total). Per-topic validator logic is inlined in each `compute*()` method and the [MagnitudeRangeValidator](../src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java) bound table has only 4 entries. Phase 7 promotes both into a `TopicValidatorRegistry`, adds ≥5 new topics per domain, and changes the paper output schema from "1–2 computations folded into one Findings string" to "N computations as named structured blocks" so the planner can ramp up computations-per-paper without re-introducing the truncation failure mode from Phase 5.
- **G — Critical-path fixes from refresh #1–#12.** 14 distinct issues called out in [REVIEW_REPORT.md](../research/a3289005/REVIEW_REPORT.md) that block the room from leaving the Q2+ ceiling. Mostly small surgical edits; one Ollama-fallback piece is the highest single leverage fix on the board.

**Status:** Scoping document. No code in this phase yet.

**Prerequisite:** Phase 6 D2 shipped (commit `10aec6f`) and operating with `app.research.discussion-section.enabled: true` for ≥ 1 week. **A fresh REVIEW_REPORT.md run that includes ≥ 1 `PUBLISHED rev:*:ok` paper is the ship-gate for the whole phase** — there is no point ramping computations-per-paper if the room can't currently complete one revision cycle. E1 is the unblocker for that gate.

**Goal:** Three pieces, sized differently:

- **E — Multi-provider routing.** ~1–2 weeks. The smallest-by-LoC change but the highest behavioural leverage. The existing `SimulationGate` / `TokenBudgetProperties` were already provider-keyed for this (`tokens.budget.providers.{groq,ollama,…}`); only the request-issuing side is Groq-specialised today.
- **F — Math Rule Engine Expansion.** ~3 weeks across F1–F4. F1+F2 lay the foundations (registry, structured findings); F3 grows topic count; F4 is the gated bump from `maxComputations: 2 → 3 → N`.
- **G — Critical-path fixes.** ~1 week as a bundle. Mostly 1–20 line edits each; landed as one PR-train.

**Why this is risky:**

- **E** crosses a provider trust boundary. Different providers have different rate-limit semantics (Anthropic has minute+hourly tiers, not just per-day; OpenAI uses TPM not TPD; Together-ai's tool-calling has subtly different JSON schema). The `TokenAccountant` deque is per-provider but the per-call usage parsing is hand-written for Groq's response shape. A provider whose response shape silently differs would still pass routing but leave `burnRatePerMin=0` (the exact regression already observed for Groq tool-loops in refresh #5 — rec #5 in [REVIEW_REPORT.md](../research/a3289005/REVIEW_REPORT.md)).
- **F2** changes the LLM output schema (`findings` becomes `Map<Integer,String>`). Every downstream consumer of `NarrationResult.findings()` — [UniversityService.publishPaper](../src/main/java/com/emergentterrain/research/UniversityService.java), [NumericalConsistencyValidator](../src/main/java/com/emergentterrain/research/NumericalConsistencyValidator.java), [MagnitudeRangeValidator](../src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java), [ContentHashDeduplicator](../src/main/java/com/emergentterrain/research/ContentHashDeduplicator.java), the truncation guard at [UniversityService:1266](../src/main/java/com/emergentterrain/research/UniversityService.java) — has to learn to consume a structured form. Done wrong, the existing 9-paper REJECTED catch disappears.
- **F4** is the "increase computations" bump the operator explicitly asked for. The risk is the inverse of Phase 5 A1's risk: more computations means more places to fail. F2+F3 must be solid before F4 ramps.
- **G5** wires audit-gap labels (`physical_mechanism`, `boundary_answer`, …) into the `validatorRejected` clause. If the 4b reviewer is noisy on these labels, dozens of currently-NEEDS_REVISION papers flip to REJECTED at once and we lose the Q2+ ceiling. Roll out with a flag per gap-category.

---

## What [REVIEW_REPORT.md](../research/a3289005/REVIEW_REPORT.md) refresh #1–#12 identified

The 34 numbered recommendations clustered into these blocking issues (cite numbers refer to the recommendation list in the report):

| Cluster | Recs | Status | Phase 7 piece |
|---------|------|--------|---------------|
| Professor stage blocked by Groq 429 | #1 (next-steps) | 11 Q2+ papers stranded | **E1 + E2** |
| TokenAccountant tool-loop usage drop | #5 | Confirmed silent regression | **E3** |
| Magnitude-range coverage gaps | #2, #17, #19, #30 | 9 papers escaped guard | **F1** |
| Dedup fingerprint fragility | #3, #27 | 3 confirmed misses | **G2** |
| Refinement-discard | #16 | 4 confirmed cases | **G1** |
| Peer-score >1.0 break | #13 | P-237=1.021, P-250=1.040 | **G3** (1 line) |
| Harness-leak in hypothesis | #14, #33 | 3 papers | **G4** |
| Boilerplate conclusion after critique:skip | #11 | 75% of corpus | **G5** |
| Right-values-wrong-labels (Thermal) | #15 | 4 papers | **G6** |
| Q-R label confusion (RLC) | #25 | 3 of 6 papers | **G6** |
| Numerical formatting (`3.89×10^1`) | #26 | P-274, P-287 | **G6** |
| Boundary-of-search audit | #18 | P-250 etc. | **G7** |
| Audit-gap `physical_mechanism` not routed | #32 | Catches the 3 confirmed cross-domain non-sequiturs | **G8** |
| Intra-room novelty (Goldbach×2 in 8 min) | #23 | 4 sibling duplicates | **G9** |
| DISPUTED reconciliation orphan | #9 | P-220 since refresh #1 | **G10** |
| Single-bot peer review (self-resolved) | #6 | Resolved by refresh #4 | — |
| Mathematics-template prose | #21 | MATH/PHYSICS don't truncate; only MATERIALS does | **F2** is the fix |
| Math rule engine topic ceiling | (operator request) | Hardcoded 5/domain | **F1 + F3** |
| Increase computations per paper | (operator request) | Currently 2; goal 3, then N | **F2 + F4** |

Items not addressed by Phase 7: rec #6 (bot population — already self-corrected), rec #28 (NULL_RESULT pattern observation — descriptive, not a fix).

The current Strongest list — P-a328-279 (Pendulum Q3 NULL_RESULT), P-a328-310 (H-L Q3 NULL_RESULT), P-a328-260 (Goldbach Q2+), P-a328-237 (Thermal Q2+) — all have correct math. The ceiling is exclusively the professor stage. Phase 7 E1 alone is expected to lift 4–5 of these to `PUBLISHED rev:*:ok`.

---

## E — Multi-provider LLM routing

### Four pieces, in order

#### E1 — Multi-provider `LlmRouter` + new provider pools (behaviour change)

**Effort:** ~1 week.
**Pre-condition:** Operator has provisioned at least one non-Groq provider key (Anthropic, OpenAI, or Together-ai). API key env-var(s) defined and tested manually with a `curl` call from the deploy host.

Scope:

1. **Provider abstraction.** Extract a `LlmProvider` interface from the existing [GroqPool](../src/main/java/com/emergentterrain/service/GroqPool.java) surface — minimally `streamPrimary(prompt) → Flux<String>`, `callWithTools(prompt, tools) → Response`, `recordUsage(promptTokens, completionTokens)`. [OllamaPool](../src/main/java/com/emergentterrain/service/OllamaPool.java) already implements this informally; tighten it.
2. **New pool implementations.** Add `AnthropicPool`, `OpenAiPool`, `TogetherPool` — one class each, mirroring `GroqPool`'s `WebClient` + SSE-streaming + tool-loop pattern. Each pool registers under a unique `provider` key (`anthropic`, `openai`, `together`) consumed by `TokenBudgetProperties.providers.*` (which is already a free-form map per [application.yml:262–283](../src/main/resources/application.yml)).
3. **Router routing policy.** [LlmRouter](../src/main/java/com/emergentterrain/service/LlmRouter.java) currently picks `primaryPool` from a single instance. Replace with an ordered list of primary pools and a "first non-exhausted wins" routing decision. `primaryFullyExhausted()` becomes "all primary pools TPD-exhausted".
4. **Per-provider model ranking.** GroqPool's startup-time model-ranking benchmark (`GroqPool: evaluating 2 models …`) runs per-pool. Result is per-provider, not aggregated — each provider picks its best 2 models independently.

**Flag:** `app.ai.providers.enabled` — comma-separated list (default `groq,ollama`). Adding entries opt-in expands the primary pool order.

**Hard requirement:** with the flag set to `groq,ollama` only, runtime behaviour is bit-identical to Phase 6's pre-E1 state. Routing must be additive.

**Why this can ship behind E2:** until E2's per-provider tool-calling parity lands, the new pools serve first-pass narration only — the professor stage stays on GroqPool. That alone partly addresses the Q2+ blockage (first-pass on a non-exhausted provider, professor on the still-blocked Groq) without crossing the harder tool-call schema boundary.

#### E2 — Tool-calling parity across providers (behaviour change)

**Effort:** ~1 week.
**Pre-condition:** E1 shipped, with `app.ai.providers.enabled` including at least one non-Groq entry, for ≥ 3 days. No new errors in [/tmp/et-app.log](/tmp/et-app.log) attributable to non-Groq pool startup.

Scope:

1. **Tool-call request adapter.** GroqPool's tool-loop at [GroqPool.java:227–249](../src/main/java/com/emergentterrain/service/GroqPool.java) speaks the OpenAI-style `tools=[…], tool_choice=auto` schema. Anthropic uses `tools=[…], tool_choice={"type":"auto"}` plus `tool_use` blocks (not `tool_calls`). OpenAI is closest to Groq. Each new pool's `callWithTools` translates between its native schema and the engine's canonical `ToolCallRequest`/`ToolCallResponse` records.
2. **Tool-result accounting.** Tool-call responses can be much longer than chat responses (the tools return param sweeps, predicate scans). [TokenAccountant](../src/main/java/com/emergentterrain/llm/TokenAccountant.java) must record per-iteration usage from each provider's response. **This is the rec #5 fix lifted into E.** Verify by toggling the SimulationGate proactive threshold (`pauseThresholdPct: 95`) at 80% and confirming the gate closes pre-emptively under heavy tool-loop traffic.
3. **Router professor-stage routing.** Once two providers support tools, `maybeCritiqueAndReviseWithTools` at [ResearchNarrationService.java:265](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java) loops through `router.primaryPools()` in priority order, calling each provider's `callWithTools` until one succeeds. Falls back to the JSON-path (E3 below) only when ALL primary pools fail.

**Flag:** `app.ai.tool-calling.providers` — comma-separated list of providers that the router may call with tools (default `groq`). Adding entries expands the professor-stage fallback chain.

**Acceptance test:** Manually exhaust Groq TPD (or short-circuit `GroqPool.allModelsTpdBlocked()` to return true). Submit a paper to the planner. Observe call path containing `prof:anthropic:ok` or `prof:openai:ok`. Paper status: `PUBLISHED` with `rev:*:ok`.

#### E3 — Ollama fallback for `prof:error→fp` (behaviour change)

**Effort:** ~3 days.
**Pre-condition:** E1 shipped. E2 may or may not be done; E3 is a complementary fallback for the case when all primary providers (including non-Groq) are simultaneously exhausted or otherwise unhealthy.

Scope:

1. New `maybeCritiqueAndReviseOllama()` in [ResearchNarrationService](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java), mirroring `maybeCritiqueAndReviseJsonPath` but using `router.fast()` (Ollama) for the critique and revise stages. **No tool calls** — Ollama doesn't support them reliably. The professor stage degrades to a plain critique→revise loop.
2. Router decision at [ResearchNarrationService.java:259–262](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java) becomes a three-way branch: `supportsProfessorTools() → tools` else `hasJsonPathPrimary() → json` else `hasFastPool() → ollama` else `→ skip+log`. Call path stamps `rev:ollama:ok` so telemetry can distinguish this path.
3. Adjust the `PUBLISHED_NO_PROFESSOR` precedence at [UniversityService.java:446](../src/main/java/com/emergentterrain/research/UniversityService.java): when call path ends with `rev:ollama:ok`, the paper is `PUBLISHED` (not `PUBLISHED_NO_PROFESSOR`) — Ollama did revise, just without tools. New status `PUBLISHED_NO_TOOLS` could be added if operators want telemetry distinction.

**Flag:** `app.research.ollama-fallback.enabled` (default `false` initially; flip to `true` after E2 ships).

**Hard requirement:** Ollama's prose-quality on revise is unknown — Phase 7 acceptance test for E3 reads the first 5 `rev:ollama:ok` papers and confirms they are at least Q2+ quality (no truncation, no boilerplate). If they aren't, the flag stays off and the work continues toward E2.

#### E4 — Per-provider model-ranking telemetry (operational hardening, no behaviour change)

**Effort:** ~2 days.
**Pre-condition:** E1 shipped.

Scope:

1. GroqPool's startup-time ranking emits `GroqPool ranking: rater=… ranked X/Y` to logs but no telemetry. Promote to a `MetricCollector` so per-provider ranking decisions are observable in Grafana / a dashboard.
2. `TokenBudgetReporter` (already periodic per [application.yml:268](../src/main/resources/application.yml)) extends to print per-provider model lists alongside the budget heartbeat. Useful for diagnosing "which provider is the planner currently using and why" without grepping logs.

**Flag:** none — purely additive logging.

---

## F — Math Rule Engine Expansion

### Four pieces, in order

#### F1 — Promote bounds to `TopicValidatorRegistry` (refactor, no behaviour change)

**Effort:** ~1 week.
**Pre-condition:** Phase 6 D2 shipped. No outstanding REJECTED-status bugs in [REVIEW_REPORT.md](../research/a3289005/REVIEW_REPORT.md).

Scope:

1. New class `TopicValidatorRegistry` in [com.emergentterrain.research](../src/main/java/com/emergentterrain/research). Holds `Map<TopicKey, ValidatorSpec>` where `TopicKey = (domain, topic)` and `ValidatorSpec` is a record:
   ```
   ValidatorSpec(
       String  hypothesisTemplate,
       Pattern magnitudeRegex,         // moved out of MagnitudeRangeValidator
       double  upperLimit,
       String  unit,
       double  nullResultDeviationThreshold,   // per-topic; H-L uses 0.30
       String  closedFormFormula,      // textbook formula, surfaces in markdown
       boolean requiresPhysicalMechanism,  // true for INTERDISCIPLINARY
       String  reasonForBound
   )
   ```
2. **The 20 currently-known topics register on startup** via a `@PostConstruct` in each domain's bundle. The 4 [MagnitudeRangeValidator.BOUNDS](../src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java) entries become 4 of the 20 `ValidatorSpec` entries.
3. **YAML override.** Optional `src/main/resources/topic-validators.yml` overrides registry entries at startup — lets operators expand bounds without recompiling. The class header at [MagnitudeRangeValidator.java:18–21](../src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java) already anticipated this ("v1 hard-codes the bounds … the design doc anticipates promotion to a `domain-magnitudes.yml` resource").
4. **INTERDISCIPLINARY-must-have-bound invariant.** `TopicValidatorRegistry.register()` throws if an INTERDISCIPLINARY topic is registered without a `magnitudeBound` or a `requiresPhysicalMechanism=true`. Catches anti-patterns at startup, not at publish time.
5. [MagnitudeRangeValidator.validate](../src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java) becomes a thin delegate to `TopicValidatorRegistry.findFor(domain, topic).orElse(no-op).validate(findings)`.

**Flag:** none. Pure refactor — every test in the existing [MagnitudeRangeValidatorTest](../src/test/java/com/emergentterrain/research/MagnitudeRangeValidatorTest.java) must still pass.

**Acceptance test:** existing magnitude-range test suite passes. New tests assert that an INTERDISCIPLINARY topic registered without a bound throws on startup.

#### F2 — Structured findings output (behaviour change)

**Effort:** ~2 weeks.
**Pre-condition:** F1 shipped. **No new audit-gap rollouts in flight** — F2 changes the audit's input shape.

Scope:

1. **`NarrationResult.findings` changes type** from `String` to `Map<Integer, String>` keyed by computation index. Backward-compat: a single-key map `{1: "<original-prose>"}` is the migration mode. Persistence column on [ResearchPaper](../src/main/java/com/emergentterrain/research/ResearchPaper.java) becomes JSON or a separate `paper_computations` join table — design choice deferred to F2 implementation memo.
2. **First-pass prompt template** (`NARRATION_PRIMARY` in [PromptTemplateRegistry](../src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java)) bumps version and switches output spec from
   ```
   "findings": "<single string>"
   ```
   to
   ```
   "findings": {"computation_1": "<para>", "computation_2": "<para>"}
   ```
3. **Per-block truncation guard.** [UniversityService.endsWithSentenceTerminator](../src/main/java/com/emergentterrain/research/UniversityService.java) at line 1266 becomes per-block. A paper is `NEEDS_REVISION` if **any** computation block ends mid-sentence. This decouples: truncation in computation #N doesn't poison computations #1..#(N-1).
4. **Markdown renderer** (`formatPaperAsMarkdown` near [UniversityService.java:1163](../src/main/java/com/emergentterrain/research/UniversityService.java)) emits one H3 per computation:
   ```
   ## Findings
   ### Computation 1 — RLC circuit characterisation
   **Equation:** f₀ = 1/(2π√(LC))
   The RLC circuit with L=0.0013 H and C=1.62e-06 F yields f₀=3513 Hz…
   ### Computation 2 — Sensitivity sweep
   **Equation:** Q(R) = (1/R)·√(L/C)
   …
   ```
5. **Equation field on [ResearchResult](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java).** New `String equation` field on the [ResearchResult](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java) record. Every `compute*()` method in [MathPhysicsEngine](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java) sets it. Surfaces in the H3 block's `**Equation:**` line.
6. **Validators learn the structured form.** [NumericalConsistencyValidator](../src/main/java/com/emergentterrain/research/NumericalConsistencyValidator.java), [MagnitudeRangeValidator](../src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java), [ContentHashDeduplicator](../src/main/java/com/emergentterrain/research/ContentHashDeduplicator.java) accept either a flat string (legacy) or the map (current). They operate on the concatenation of all block prose — semantic equivalent of today's flat string.
7. **`PaperDraft` and the audit:4b prompt** (`buildAuditPrompt` at [ResearchNarrationService.java:563](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java)) accept the structured form. The audit prompt asks for gap-checks per-computation.

**Flag:** `app.research.structured-findings.enabled` (default `false` initially). When false, `maxComputations` is force-clamped to 1 — flat string is preserved exactly.

**Hard requirement:** with the flag off, the entire suite of refresh-#1–#12 REJECTED catches (dedup, magnitude-range, etc.) remains bit-identical. The flag-off path is the migration safety net.

**Acceptance test:** flip the flag on, set `maxComputations: 2`, observe the next 8 papers. Each has two H3 blocks in Findings. Per-block truncation guard fires on the last block only (the others publish their content). REJECTED rate is at least equal to the flag-off baseline.

#### F3 — Add ≥5 new topics per domain (behaviour change)

**Effort:** ~2 weeks (mostly closed-form derivation + bound calibration).
**Pre-condition:** F1 shipped. F2 is **not** a hard prerequisite — F3 adds compute methods that are flat-string compatible, but is much cleaner shipped after F2.

Scope:

Add per-topic `compute*()` methods + matching `ValidatorSpec` registrations. Concrete candidates:

| Domain | Topic | Closed form / Bound | Why this topic |
|--------|-------|--------------------|---------------|
| PHYSICS | **Black-body Wien Peak** | λ_max·T = 2.898×10⁻³ m·K | Textbook, easy NULL_RESULT threshold (deviation > 5% of Wien's constant). |
| PHYSICS | **Schrödinger 1D Infinite-Well Energy** | Eₙ = n²h²/(8mL²) | Lattice spacing where ground-state energy exceeds thermal kT. |
| PHYSICS | **Kepler Third Law** | T² = (4π²/GM)·a³ | T-vs-a for a planet sweep; deviation > 10% → NULL_RESULT. |
| PHYSICS | **Snell's Law Dispersion** | n₁ sinθ₁ = n₂ sinθ₂ | Critical-angle survey across (n₁,n₂); bound: |θ_c−θ_observed| < 2°. |
| PHYSICS | **Stefan–Boltzmann Total Flux** | P = σ·A·T⁴ | Black-body emitted-power sweep; bound: σ within 10% of 5.67×10⁻⁸. |
| MATERIALS | **Hall-Petch Grain-Size Strengthening** | σ_y = σ_0 + k·d^(−1/2) | Yield-strength vs grain diameter; bound: σ_y < 5 GPa for any steel. |
| MATERIALS | **Bragg X-ray Diffraction** | nλ = 2d sinθ | Diffraction-order count survey; bound: angles in [0, 90°]. |
| MATERIALS | **Carnot Efficiency** | η = 1 − T_c/T_h | Efficiency sweep over (T_c, T_h); bound: η ∈ [0,1], strict. |
| MATERIALS | **Fatigue Basquin Law** | σ_a = σ'_f · (2N_f)^b | Stress-amplitude vs cycles-to-failure; bound: σ_a < UTS. |
| MATERIALS | **Young–Laplace Capillary Rise** | h = 2γ cosθ / (ρgr) | Capillary rise vs tube radius; bound: h ≤ 50 cm for water. |
| MATHEMATICS | **Mertens Function Partial Sums** | M(x) = Σ μ(n), known O(√x log log log x) | Empirical M(x) growth; bound: |M(x)| ≤ √x·2 in tested range. |
| MATHEMATICS | **Divisor Sum Average** | (1/N)·Σ σ(n) ~ (π²/12)·N | Average σ(n)/N for N in some range; bound: deviation < 10%. |
| MATHEMATICS | **Riemann Partial-Sum vs Euler Product** | Π(1/(1−p⁻ˢ)) vs Σ 1/nˢ | Empirical convergence at fixed s; bound: ratio in [0.95, 1.05] for s>1.5. |
| MATHEMATICS | **Stern–Brocot Tree Depth Survey** | Depth(p/q) ≤ Fib⁻¹(max(p,q)) | Depth distribution; bound: max depth < 50 for q < 10⁶. |
| MATHEMATICS | **Squarefree Density** | ρ ~ 6/π² ≈ 0.6079 | Empirical density; bound: deviation < 5%. |
| INTERDISCIPLINARY | **Hall-Effect Carrier Sign** (PHYSICS→MATERIALS) | V_H = (I·B)/(n·e·t) | Sign of V_H predicts carrier type. **Mechanism:** Lorentz force on charge carriers — legitimate. Bound: |V_H| ≤ 1 V at reasonable I,B. |
| INTERDISCIPLINARY | **Schottky-Barrier Thermionic Emission** (MATH→PHYSICS) | J = A*T² · exp(−φ/kT) | Current density vs barrier height. **Mechanism:** thermionic emission, well-defined. Bound: J ≤ 10⁶ A/cm². |
| INTERDISCIPLINARY | **Acoustic Doppler Frequency Shift** (PHYSICS↔PHYSICS) | f' = f·(c+v_o)/(c−v_s) | Frequency shift survey. **Mechanism:** trivially physical. Bound: |f'−f| ≤ f. |

Each topic ships with:
- A `compute*()` method on [MathPhysicsEngine](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java)
- A `ValidatorSpec` registration via F1's registry
- 2 unit tests per topic (correct case + falsified-hypothesis case)
- At least one entry in [magnitude-range bound coverage](../src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java) (or the F1 registry equivalent)
- For INTERDISCIPLINARY: a `physicalMechanism` field with a paragraph explaining why the cross-domain mapping is physically legitimate

**Flag:** `app.research.topics-v2.enabled` (default `false`). When false, the additions are registered but the planner doesn't pick them. When true, the planner's topic selector samples uniformly across all registered topics (current 20 + new ≥15).

**Hard requirement:** [REVIEW_REPORT.md](../research/a3289005/REVIEW_REPORT.md) `Q-R label confusion` style failure modes do not appear in the new topics. The closed-form on each topic is verified by 2 hand-written test cases before merge.

#### F4 — Bump `maxComputations` from 2 → 3 → N (config flip, gated)

**Effort:** ~1 day per bump.
**Pre-condition:** F2 shipped and observed running for ≥ 1 week with `app.research.structured-findings.enabled: true`. Per-block truncation guard observed firing (not on every paper — that means the prompt budget is too tight).

Scope:

1. Config bump: `app.research.maxComputations: 2 → 3` (then later → 4, …). The N-1 additional `maybeRefineComputation` iterations loop on [ResearchNarrationService.java:145](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java).
2. Each refinement may probe a different parameter range — the existing F3 refinement chooses `param1`/`param2` already; the loop just runs it more times with different seeds.
3. After each bump, a fresh refresh of [REVIEW_REPORT.md](../research/a3289005/REVIEW_REPORT.md) confirms:
   - Truncation-rate per-block did not spike (>10% increase).
   - REJECTED-rate did not spike (extra computations should not be reading clones).
   - Median paper word-count grew (more computations = more prose).

**Flag:** `app.research.maxComputations` — integer config.

**Hard requirement:** every bump is a separate commit with a corresponding [REVIEW_REPORT.md](../research/a3289005/REVIEW_REPORT.md) refresh in its rollback evidence. If the trajectory table shows a new failure mode appearing post-bump, roll back to the previous N before fixing.

---

## G — Critical-Path Fixes (refresh #1–#12 bundle)

### One PR-train, 10 small commits

**Effort:** ~1 week as a bundle.
**Pre-condition:** Phase 6 D2 shipped (commit `10aec6f`).

Each commit is ≤ 30 lines and ships with at least one unit test. None are flag-gated unless noted.

#### G1 — Partial-revision merge for incomplete first-pass

**File:** [ResearchNarrationService.java:255–258](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java) (the `critique:skip:incomplete_firstpass` early-return).

Today the check is "any field null → return firstPass unchanged → critique stage skipped → paper goes critique:skip:incomplete_firstpass". The refinement work already in the prompt is wasted.

Replace with a partial-merge analogous to commit `e15047f`'s revision-merge path. Fill null fields from `draft.templateHypothesis()` / `draft.rawFindings()` / `draft.templateConclusion()` and proceed to critique. Stamp call path `critique:partial:N_filled` so telemetry can see how often this fires.

Recovers ≈ 30% of the refresh #1–#12 critique:skip papers.

#### G2 — Dedup Jaccard similarity

**File:** [ContentHashDeduplicator.java:73–88](../src/main/java/com/emergentterrain/research/ContentHashDeduplicator.java).

Today: exact-string fingerprint match. Tail-truncation and tail-extension both diverge the fingerprint (recs #3, #27).

Add a second pass: when no exact match found, compute Jaccard similarity on the 3-sig-fig token multiset; fire if `intersection.size() / min(|A|,|B|) > 0.85`. Catches P-276 (Computation-2 stub extends) and P-284 (truncation at Vf=60% trims).

#### G3 — Peer-score cap at 1.0

**File:** [UniversityService.java:347–364](../src/main/java/com/emergentterrain/research/UniversityService.java).

After all score adjustments, clamp: `adjustedScore = Math.max(0.0, Math.min(1.0, adjustedScore));`. P-237=1.021 and P-250=1.040 break the [0,1] interpretation. 1 line of code; 1 test.

#### G4 — Harness-leak regex strip

**File:** [PromptSanitizer](../src/main/java/com/emergentterrain/research/PromptSanitizer.java) (already called from [UniversityService.java:341–345](../src/main/java/com/emergentterrain/research/UniversityService.java)).

Add patterns: `"as observed from the starting position of \\d+ and scale of [\\d.]+"`, `"with a scale of [\\d.]+"`, `"starting from parametric index \\d+"`, `"\\(see P-x{2,4}-\\d+\\)"` (rec #33 dummy citation), `"(\\d+\\.\\d+)\\s*[×x]\\s*10\\^1\\b"` (rec #26 formatting bug — strip to plain decimal).

#### G5 — Boilerplate conclusion suppression on critique:skip

**File:** [UniversityService.java](../src/main/java/com/emergentterrain/research/UniversityService.java) `publishPaper` near line 336 (post-template-fallback, pre-validator block).

When `callPath` contains `"critique:skip"`, replace the canned `"Computed values were independently reproduced by peer reviewers. These findings represent an observational baseline. The computed values are finite and reproduced under the search params declared above."` (sourced from [MathPhysicsEngine.java:47](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java)) with `"[critique:skip — no peer review performed; this paper has not been validated]."`.

The boilerplate is currently a falsehood (75% of corpus has critique:skip yet claims peer review). 10 lines.

#### G6 — Topic-specific prompt-template clarity fixes

**Files:** the RLC, Thermal Stress, and Arrhenius narration prompt templates in [PromptTemplateRegistry](../src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java).

Three sibling fixes:
- **RLC** (rec #25): force the prompt to emit `"× R_c"` suffix in table headers and prose. Add a post-narration regex that fires if findings contains `"R_c value of (10|25|50|100|200|400)"` AND `R_crit != that value`.
- **Thermal Stress** (rec #15): prompt with explicit `"σ_thermal"` column label, not generic "stress". Stops the 4-paper "yield strength" mis-labelling.
- **Arrhenius formatting** (rec #26): prompt template explicitly says `"emit multipliers as plain decimal (1.20, 3.89), NEVER scientific notation"`. Also catches the 38.9× / 3.89× ambiguity at the source. Combined with G4's regex strip.

Bumps the templates' versions for the [PromptTemplateRegistry](../src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java) provenance tracking.

#### G7 — Boundary-of-search audit dimension

**File:** [ResearchNarrationService.runQualityAudit](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java) (lines 533–561) + the `NARRATION_AUDIT` prompt template.

Add a new audit gap dimension `boundary_answer` that fires when the answer's primary value equals a boundary of the search range (e.g. Vf=1.00 for a Vf∈[0,1] search; θ=90° for an angle survey). Catches P-250/258/282/306 degenerate Reuss-bound answers.

#### G8 — Wire `physical_mechanism(m)` audit gap to REJECTED

**Files:** [NarrationResult](../src/main/java/com/emergentterrain/research/NarrationResult.java) gains a `List<String> auditGapDimensions` field; [UniversityService.java:436–441](../src/main/java/com/emergentterrain/research/UniversityService.java) considers `auditGapDimensions.contains("physical_mechanism") && "SUPPORTED".equals(classification)` as part of `validatorRejected`.

Catches the 3 cross-domain non-sequitur clusters (prime-gap→CTE P-264/281, Collatz→RLC P-295, happy-number→packing P-253/308) that currently slip every other validator.

**Flag:** `app.research.audit-physical-mechanism-rejects.enabled` (default `false`) — flip on after observing the audit-4b reviewer's false-positive rate on the new dimension across ≥ 30 papers.

#### G9 — Intra-room novelty sibling check

**File:** [FormulationService.java:267–279](../src/main/java/com/emergentterrain/service/FormulationService.java) (`checkNovelty`).

Add: query `roomPapers` for any prior with the same `(domain, topic)` published in the last `app.research.intra-room-novelty-window-minutes: 30`. If found AND not cited by the new paper's typed-citations, dock novelty by 0.5 and add `intra-room-sibling(P-N)` to the audit's gap list. Stops the P-260/P-265 Goldbach-in-8-min duplication observed in refresh #6.

#### G10 — DisputeReconciliationService scheduler

**File:** [UniversityService.java:1099–1121](../src/main/java/com/emergentterrain/research/UniversityService.java) (`enqueueReconciliation`).

Today the queue exists but has no drain. Add a `@Scheduled(fixedDelayString="${app.research.dispute-reconciliation-interval-ms:300000}")` method that drains the queue every 5 min. P-220 has been orphaned since refresh #1.

---

## What Phase 7 deliberately does NOT change

- **Computation count is gradually increased, not reduced** (operator direction). F4's bumps are the path forward; F2's structured-findings schema is what makes those bumps safe.
- **Cross-domain-gate's INTERDISCIPLINARY exemption is preserved.** Refresh #10 evidence (P-292 NULL_RESULT) shows topics WITH bounds get validated correctly. The fix is in the bounds map (F1), not the gate. The current exemption is correct in principle.
- **Dedup-guard's REJECTED-prior inclusion stays** (commit `36a6496` evidence: 5-prior match list on P-297 includes 3 within-run REJECTED priors — working as intended).
- **BotLifecycleService is untouched** — population restored itself between refreshes #2 and #4 with no code change.
- **DISPUTED status mechanics stay** — only the reconciliation-drain (G10) changes.

---

## Acceptance gates (ship-or-don't-ship checks)

The phase ships piece-by-piece. Each piece passes its acceptance test below before the next ships.

| Piece | Acceptance test |
|-------|-----------------|
| **E1** | A fresh JVM with `app.ai.providers.enabled: groq,ollama,anthropic` starts cleanly, ranks both Groq and Anthropic models at startup, and a paper narrates first-pass on Anthropic when Groq is TPD-blocked. |
| **E2** | With Groq manually exhausted, a paper completes `prof:anthropic:ok` (or `prof:openai:ok`) and publishes with status `PUBLISHED`. This is **the gate for declaring Q3 ceiling cleared**. |
| **E3** | First 5 `rev:ollama:ok` papers are at least Q2+ (no truncation, no boilerplate, complete prose). If not, E3 flag stays off. |
| **F1** | Existing magnitude-range test suite passes. New tests assert INTERDISCIPLINARY-without-bound throws on startup. |
| **F2** | With `structured-findings.enabled: true` and `maxComputations: 1`, runtime is bit-identical to F1-baseline. With `maxComputations: 2`, papers render H3 per computation; per-block truncation guard fires on the truncated block only. |
| **F3** | At least 5 new topics per domain ship with passing closed-form + falsified-hypothesis unit tests + magnitude bound entry. Cumulative topic count ≥ 35. |
| **F4** | Trajectory table in [REVIEW_REPORT.md](../research/a3289005/REVIEW_REPORT.md) for the 8 papers following each bump shows truncation-rate-per-block did not spike >10%, REJECTED-rate did not spike, median word count grew. |
| **G bundle** | Refresh-#13 trajectory row shows: peer-score ≤ 1.0 for all papers (G3); zero "(see P-xxxx-N)" or "starting position of N and scale of M" leaks in published prose (G4); zero "Computed values were independently reproduced…" lines on critique:skip papers (G5); zero Q-R label confusions in new RLC papers (G6); at least one `boundary_answer` audit-gap firing observed (G7); P-220's dispute resolved or queued for reconciliation (G10). |

---

## Provider readiness — 2026-05-13 test results

Each candidate provider was tested against the actual application contract: streaming JSON-mode (`response_format: {type: json_object}`) and tool-calling (`tools` + `tool_choice: auto` with a one-argument function). The tests mirror exactly what [ResearchNarrationService.runQualityAudit](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java) and `maybeCritiqueAndReviseWithTools` do today against Groq.

### Confirmed working free 70B+ models (10 total across 4 providers)

| Provider | Model ID | JSON | Tool | Notes |
|----------|----------|:---:|:---:|-------|
| Groq | `llama-3.3-70b-versatile` | ✅ | ✅ | baseline |
| Groq | `openai/gpt-oss-120b` | ✅ | ✅ | baseline |
| **NVIDIA** | `meta/llama-3.3-70b-instruct` | ✅ | ✅ | 70.5B per HF |
| **NVIDIA** | `openai/gpt-oss-120b` | ✅ | ✅ | duplicate of Groq's flagship — useful for routing redundancy |
| **NVIDIA** | `nvidia/nemotron-3-super-120b-a12b` | ✅ | ✅ | 120B MoE |
| **NVIDIA** | `qwen/qwen3-next-80b-a3b-instruct` | ✅ | ✅ | 80B MoE |
| **NVIDIA** | `qwen/qwen3-coder-480b-a35b-instruct` | ✅ | ✅ | 480B MoE — code-tuned; longest context |
| **OpenRouter** | `openai/gpt-oss-120b:free` | ✅ | ✅ | routed via `OpenInference` backend (reliable) |
| **OpenRouter** | `nvidia/nemotron-3-super-120b-a12b:free` | ✅ | ✅ | routed via `NVIDIA` backend (reliable) |
| **Cerebras** | `qwen-3-235b-a22b-instruct-2507` | ✅ | ✅ | **14 ms inference** on WSE-3 hardware — 10× faster than any other provider tested |

### Not usable (debugged, documented for completeness)

| Provider | Model | Reason |
|----------|-------|--------|
| OpenRouter | `meta-llama/llama-3.3-70b-instruct:free` | Routed via `Venice` backend; sub-minute global rate cap; persistent 429 across 75s+ backoff |
| OpenRouter | `qwen/qwen3-next-80b-a3b-instruct:free` | Same — Venice backend, persistent 429 |
| OpenRouter | `nousresearch/hermes-3-llama-3.1-405b:free` | Venice backend + no tool support per catalog |
| NVIDIA | `nvidia/nemotron-4-340b-instruct` | Listed in catalog but **paid-tier only** (404 "Function not found for account") — the unique 340B model is not available on the free trial |
| NVIDIA | `nvidia/llama-3.1-nemotron-70b-instruct` | Same — paid-tier only |
| Google AI Studio | (all Gemini/Gemma) | Operator deactivated. Filter regress: no Google model id contains `\d+b`, so the current 70B-regex would drop them all. Future revival path is the `ModelCapabilitiesRegistry` (see E1.1 below). |

### Routing-plan implications

- **10 confirmed working model+provider combinations across 4 independent quota pools** (Groq TPD, NVIDIA, Cerebras RPM, OpenRouter free pool). The chance of all four pools being simultaneously exhausted is dramatically lower than the current single-provider state where 11/12 Q2+ candidates are stranded at `prof:error→fp`.
- **NVIDIA contributes the largest free-tier lineup** (5 distinct working models) after the operator's account verification (revoke-and-regenerate flow required — keys generated pre-verification do not inherit the inference scope).
- **Cerebras is uniquely fast** (14 ms inference on the same prompt that takes Groq ~100-500 ms and OpenRouter ~1-2 s). Direct the runQualityAudit stage at Cerebras: that stage is on the critical path of every Q2+ paper and ran 357 s for P-237 in refresh #3. Cutting it to <1 s would materially change Q2+ throughput.
- **OpenRouter's `Venice` backend should be excluded.** Three of the five listed free OpenRouter models route through Venice and are functionally unusable on the shared free pool. The other two (OpenInference, NVIDIA backends) are reliable. E1's pool config will need an `excludedBackends` field.

### API key inventory

Keys are stored in `application.yml` as `${ENV_VAR:default}` fallbacks following the existing Groq pattern. Operators are expected to override via env vars in production; the committed defaults are free-tier with explicit $0 spending caps (or per-minute hard limits, in Cerebras's case).

| Env var | Provider | Free-tier constraint | Notes |
|---------|----------|----------------------|-------|
| `GROQ_API_KEY` | Groq | TPD: gpt-oss-120b=200K/day, llama-3.3-70b=100K/day | Existing; from Phase 5 |
| `OPENROUTER_API_KEY` | OpenRouter | Operator-set spend cap = $0; auto-excludes paid models | Account: free routes only |
| `NVIDIA_API_KEY` | NVIDIA NIM | 1000 credits/month; integrate.api.nvidia.com | Verified personal-tier account; revoke-and-regenerate workflow used at activation |
| `CEREBRAS_API_KEY` | Cerebras Cloud | ~30 RPM per model per account | WSE-3 inference |

If any key is compromised (or if the repo ever becomes publicly visible), all four can be revoked + reissued at their respective dashboards — the application reads env vars first, so a reissue is a one-line environment change without a redeploy.

### Test methodology (reproducible)

```bash
# T-A (JSON mode)
curl -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"model":"<id>","messages":[{"role":"user","content":"Output a JSON object with name=Euler, value=2.71828, good=true."}],"response_format":{"type":"json_object"},"max_tokens":150}' \
  <base_url>/chat/completions

# T-B (tool-call)
curl -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"model":"<id>","messages":[{"role":"user","content":"Call get_value with key=pi."}],"tools":[{"type":"function","function":{"name":"get_value","parameters":{"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}}}],"tool_choice":"auto","max_tokens":150}' \
  <base_url>/chat/completions
```

Pass criteria for T-A: response body parses as JSON with `name == "Euler"`, `value` containing `2.71`, `good == true`. Pass criteria for T-B: `choices[0].message.tool_calls[0].function.name == "get_value"` and `arguments` JSON has `key == "pi"`.

---

## Sequencing summary

```
E1 ────→ E2 ────→ E3
                  │
F1 ────→ F2 ────→ F3 ────→ F4 (then F4-2, F4-3, …)
                  │
G ───────────────┘     (G can land anytime after E1 and is independent of F)
```

E1 unlocks E2/E3 (multi-provider routing → tool-call parity → Ollama fallback).
F1 unlocks F2 (registry → structured findings).
F2 unlocks F3 (so new topics ship with the structured-findings schema from day one) and F4 (computation-count bump).
G is independent of E and F and can ship as a single PR-train any time after Phase 6 D2.

The phase is **complete** when:
- A refreshed [REVIEW_REPORT.md](../research/a3289005/REVIEW_REPORT.md) shows **≥ 5 papers with status `PUBLISHED rev:*:ok`** (the Q4 acceptable bar).
- The Q3 ceiling has lifted: at least one paper with **Q3 quality AND `PUBLISHED` status** (not the current `PUBLISHED_NO_PROFESSOR` cap).
- The trajectory table in the report shows **a true acceptable rate ≥ 10%** (not just the current 8.7% NULL_RESULT-inclusive figure).
