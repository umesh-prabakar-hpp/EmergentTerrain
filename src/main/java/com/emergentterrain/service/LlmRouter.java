package com.emergentterrain.service;

import com.emergentterrain.llm.ProviderBudgetExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Routes LLM calls to the appropriate pool based on task quality requirements.
 *
 * <p>Phase 7 E1.5 — multi-provider routing:
 * <ul>
 *   <li><b>Primary chain</b> is an ordered list of high-quality pools. A call falls
 *       through the chain on {@code allBlocked()} or thrown exception until one pool
 *       succeeds. Order is controlled by {@code app.ai.primaryProviderOrder}
 *       (comma-separated, default {@code "groq,nvidia,openrouter,cerebras"}).</li>
 *   <li><b>Fast pool</b> stays a single pool (Ollama by default) for low-stakes
 *       high-frequency calls — refinement decisions, audit, formulation. Operators
 *       can override via {@code app.ai.fastProvider}.</li>
 * </ul>
 *
 * <p>The chain consults only enabled pool beans. A pool's bean is conditional on its
 * {@code app.ai.<provider>.enabled} flag — flipping a flag adds or removes the
 * provider from routing without a code change.
 */
@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true", matchIfMissing = false)
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    /** Default provider order — first match found in the bean registry wins. */
    private static final String DEFAULT_PRIMARY_ORDER = "groq,nvidia,openrouter,cerebras";
    private static final String DEFAULT_FAST_PROVIDER = "ollama";
    /**
     * Phase 7 E2 — default opt-in list of providers allowed to receive tool-calling
     * professor-stage calls. Only "groq" by default — the spec requires operator
     * opt-in for non-Groq tool calls after the ≥3-day E1 soak demonstrates the
     * non-Groq pools are stable. Set to {@code "*"} (or include every key) to allow
     * the full primary chain.
     */
    private static final String DEFAULT_TOOL_PROVIDERS = "groq";

    /**
     * Phase 20.11 fixup — prompt-size threshold (estimated tokens) above
     * which {@link #callFast} and {@link #streamFast} silently reroute to
     * the primary chain. Picked to stay under the fast tier's tightest
     * per-minute TPM budget: groq-fast's llama-3.1-8b-instant caps at
     * 6000 TPM, and the pool's max_tokens output cap is 2000, so prompts
     * up to ~4000 estimated tokens have headroom; anything larger trips
     * a TPM 413 on every fast handle in turn and the registry can do
     * nothing useful. Critique prompts in particular grow to 30k+ tokens
     * on tool-heavy papers (multi-block findings + full first-pass
     * narration). Routing those to primary preserves the call instead
     * of letting the fast tier's fallback chain emit a wall of 413s.
     */
    private static final int FAST_TIER_PROMPT_CAP_TOKENS = 4000;

    final List<LlmPool> primaryChain;
    final LlmPool       fastPool;
    /** Preserved for backward-compat: first pool in primaryChain. */
    final LlmPool       primaryPool;
    /** Pools indexed by their {@link LlmPool#role()} tag — first-registered wins. */
    final Map<String, LlmPool> poolsByRole;
    /**
     * Phase 7 E2 — provider keys allowed to be called with tools. Empty set means
     * "no restriction" (legacy behaviour, every {@code supportsTools=true} pool
     * participates). A populated set restricts {@link #callPrimaryWithTools} to
     * pools whose {@link LlmPool#providerKey()} is in the set.
     */
    final java.util.Set<String> toolProviders;
    /**
     * Phase 9 U3b — provider-key ordering for sorting the registry's handles.
     * Captured from the same {@code app.ai.primaryProviderOrder} config that
     * populates {@link #primaryChain}, so registry-driven walks honour the
     * operator's preferred order even when Spring's bean iteration would
     * otherwise differ.
     */
    final List<String> primaryProviderOrder;
    /**
     * Phase 9 U3b — model-centric registry that {@link #callPrimary},
     * {@link #streamPrimary}, and {@link #callPrimaryWithTools} route through.
     * The registry aggregates every {@code supportsTools=true} pool's models
     * into a single flat list, so a 429 on one model falls through to the next
     * across providers transparently.
     */
    final PrimaryModelRegistry primaryRegistry;
    /**
     * Phase 9 U6 — fast-tier registry. Aggregates every non-tool-capable pool
     * (Ollama today) into a single flat list. {@link #callFast},
     * {@link #streamFast}, {@link #fastModel}, and {@link #hasFastPool} all
     * route through it.
     */
    final FastModelRegistry fastRegistry;

    /**
     * Phase 20.5 — per-role override policy consulted by
     * {@link #orderedAvailable} when {@link com.emergentterrain.llm.LlmCallContext#currentRole()}
     * is set. Field-injected (required=false) so unit tests without the
     * policy bean construct cleanly; null-checked at each consult site.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    RoleRouterPolicy roleRouterPolicy;

    public LlmRouter(ApplicationContext ctx,
                     PrimaryModelRegistry primaryRegistry,
                     FastModelRegistry fastRegistry,
                     @org.springframework.beans.factory.annotation.Value(
                             "${app.ai.primaryProviderOrder:" + DEFAULT_PRIMARY_ORDER + "}")
                     String primaryOrder,
                     @org.springframework.beans.factory.annotation.Value(
                             "${app.ai.fastProvider:" + DEFAULT_FAST_PROVIDER + "}")
                     String fastProvider,
                     @org.springframework.beans.factory.annotation.Value(
                             "${app.ai.tool-calling.providers:" + DEFAULT_TOOL_PROVIDERS + "}")
                     String toolProvidersCsv) {
        this.primaryRegistry = primaryRegistry;
        this.fastRegistry    = fastRegistry;
        this.primaryProviderOrder = Arrays.stream(primaryOrder.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
        Map<String, LlmPool> beans = ctx.getBeansOfType(LlmPool.class);
        if (beans.isEmpty()) throw new IllegalStateException("LlmRouter: no LlmPool beans found");

        // Index by providerKey for order-based lookup. Two pools with the same key
        // (e.g. two test stubs) would collide; the first wins to match deterministic
        // Spring bean ordering.
        Map<String, LlmPool> byProvider = new LinkedHashMap<>();
        for (LlmPool p : beans.values()) byProvider.putIfAbsent(p.providerKey(), p);

        // Build the primary chain in the configured order. Skip unknown / disabled keys.
        List<LlmPool> chain = new ArrayList<>();
        for (String key : Arrays.stream(primaryOrder.split(","))
                                .map(String::trim).filter(s -> !s.isBlank()).toList()) {
            LlmPool p = byProvider.get(key);
            if (p != null) chain.add(p);
        }
        if (chain.isEmpty()) {
            // No pool from the order list registered — fall back to any available pool.
            chain.add(beans.values().iterator().next());
        }
        this.primaryChain = Collections.unmodifiableList(chain);
        this.primaryPool  = chain.get(0);

        // Fast pool: explicit lookup, fall back to first non-primary or the primary itself.
        LlmPool fast = byProvider.get(fastProvider);
        if (fast == null) {
            for (LlmPool p : beans.values()) {
                if (!chain.contains(p)) { fast = p; break; }
            }
            if (fast == null) fast = primaryPool;
        }
        this.fastPool = fast;

        // Phase 7 E1.6 — index pools by their declared role (e.g. "fast-audit").
        // First-registered wins on duplicate roles; pools with empty role() are skipped.
        Map<String, LlmPool> roleMap = new LinkedHashMap<>();
        for (LlmPool p : beans.values()) {
            String r = p.role();
            if (r != null && !r.isBlank()) roleMap.putIfAbsent(r, p);
        }
        this.poolsByRole = Collections.unmodifiableMap(roleMap);

        // Phase 7 E2 — parse the tool-calling.providers opt-in list. The literal
        // wildcard "*" means "no restriction; every supportsTools pool participates".
        // Empty / blank string also means no restriction so the legacy unrestricted
        // path survives any operator-side typo.
        java.util.Set<String> tp = new java.util.LinkedHashSet<>();
        String trimmed = toolProvidersCsv == null ? "" : toolProvidersCsv.trim();
        if (!trimmed.isEmpty() && !"*".equals(trimmed)) {
            for (String key : trimmed.split(",")) {
                String k = key.trim().toLowerCase();
                if (!k.isEmpty()) tp.add(k);
            }
        }
        this.toolProviders = Collections.unmodifiableSet(tp);

        log.info("LlmRouter: primaryChain={} fast={} [{}] roles={} toolProviders={} "
                        + "primaryHandles={} fastHandles={}",
                primaryChain.stream().map(LlmPool::providerKey).toList(),
                fastPool.providerKey(), fastPool.selectedModel(),
                poolsByRole.entrySet().stream()
                        .map(e -> e.getKey() + "→" + e.getValue().providerKey())
                        .toList(),
                toolProviders.isEmpty() ? "* (all)" : toolProviders,
                primaryRegistry.totalModelCount(),
                fastRegistry.totalModelCount());
    }

    /**
     * Phase 9 U3b — order the registry's currently-available handles by
     * {@link #primaryProviderOrder}. Handles whose provider isn't in the order
     * list sort last. Preserves the pre-U3b "primary chain order wins" semantic
     * across the registry-backed call paths.
     *
     * <p>Phase 20.5 — after the global sort, apply
     * {@link RoleRouterPolicy#applyForCurrentRole} so per-role overrides
     * (preferred-providers reorder, avoid-models exclude) take effect when
     * the caller has tagged the LLM call with a role via
     * {@link com.emergentterrain.llm.LlmCallContext#withRole}. No-op when
     * no role is set or the policy bean isn't wired.
     */
    private List<ModelHandle> orderedAvailable() {
        List<ModelHandle> out = new ArrayList<>(primaryRegistry.availableModels());
        out.sort(Comparator.comparingInt(h -> {
            int idx = primaryProviderOrder.indexOf(h.providerKey());
            return idx == -1 ? Integer.MAX_VALUE : idx;
        }));
        if (roleRouterPolicy != null) {
            return roleRouterPolicy.applyForCurrentRole(out);
        }
        return out;
    }

    /**
     * Phase 9 U3b — orderedAvailable() further filtered by the E2 opt-in set.
     * Used by {@link #callPrimaryWithTools} and {@link #supportsProfessorTools}.
     */
    private List<ModelHandle> orderedAvailableToolEnabled() {
        return orderedAvailable().stream()
                .filter(this::isToolEnabled)
                .toList();
    }

    /**
     * Phase 9 U3b — handle is eligible for tool-calling iff the E2 opt-in set
     * is empty (no restriction) OR its providerKey is in the set. The
     * {@code supportsTools} check is redundant — the registry only contains
     * tools-capable pools — but kept for clarity.
     */
    private boolean isToolEnabled(ModelHandle h) {
        if (!h.pool().supportsTools()) return false;
        if (toolProviders.isEmpty()) return true;
        return toolProviders.contains(h.providerKey());
    }

    // ── Pool selection ──────────────────────────────────────────────────────

    /**
     * Returns the next pool in the primary chain that isn't fully blocked.
     * Falls through to the last pool even if blocked, so callers always have
     * something to call (the call will surface the block as an exception).
     */
    private LlmPool nextHealthyPrimary() {
        for (LlmPool p : primaryChain) {
            if (!p.allBlocked()) return p;
        }
        return primaryChain.get(primaryChain.size() - 1);
    }

    // ── Primary routing ─────────────────────────────────────────────────────

    /**
     * Synchronous call routed via {@link PrimaryModelRegistry} — walks every
     * available 70B+ model across every provider. Cross-handle fallthrough on
     * RuntimeException; only when every available model has been tried does it
     * re-throw (or fall back to the first registered model when all are blocked,
     * so the caller sees a real exception rather than "no available models").
     */
    public String callPrimary(String prompt) {
        List<ModelHandle> available = orderedAvailable();
        RuntimeException last = null;
        for (ModelHandle h : available) {
            try { return h.pool().callSyncOnModel(h.modelId(), prompt); }
            catch (RuntimeException e) {
                last = e;
                log.warn("LlmRouter: handle={} callSync failed, trying next: {}",
                        h.reviewerId(), e.getMessage());
            }
        }
        if (last != null) throw last;
        // No available handles — fall back to the first registered handle so its
        // own block-state exception surfaces (instead of a confusing
        // "no available models" message from the registry).
        if (!primaryRegistry.allModels().isEmpty()) {
            ModelHandle first = primaryRegistry.allModels().get(0);
            return first.pool().callSyncOnModel(first.modelId(), prompt);
        }
        // Empty registry — fall through to the legacy pool path so tests without
        // a tools-capable pool still see a meaningful exception.
        return primaryChain.get(0).callSync(prompt);
    }

    /**
     * Streaming call routed via {@link PrimaryModelRegistry}. Same selection
     * contract as {@link #callPrimary(String)}.
     */
    public void streamPrimary(String prompt, Consumer<String> tokenConsumer) {
        List<ModelHandle> available = orderedAvailable();
        RuntimeException last = null;
        for (ModelHandle h : available) {
            try { h.pool().callStreamOnModel(h.modelId(), prompt, tokenConsumer); return; }
            catch (RuntimeException e) {
                last = e;
                log.warn("LlmRouter: handle={} callStream failed, trying next: {}",
                        h.reviewerId(), e.getMessage());
            }
        }
        if (last != null) throw last;
        if (!primaryRegistry.allModels().isEmpty()) {
            ModelHandle first = primaryRegistry.allModels().get(0);
            first.pool().callStreamOnModel(first.modelId(), prompt, tokenConsumer);
            return;
        }
        primaryChain.get(0).callStream(prompt, tokenConsumer);
    }

    /**
     * Phase 9 U6 — synchronous call routed via {@link FastModelRegistry}.
     * Falls back to the legacy {@link #fastPool} when no fast model is
     * registered (e.g. tests built without an Ollama-style pool).
     */
    public String callFast(String prompt) {
        if (shouldRerouteToPrimary(prompt) && primaryRegistry.totalModelCount() > 0) {
            log.info("LlmRouter.callFast: prompt ~{} tokens > {} cap — rerouting to primary "
                            + "(fast-tier TPM would 413 every handle)",
                    estimateTokens(prompt), FAST_TIER_PROMPT_CAP_TOKENS);
            return callPrimary(prompt);
        }
        if (fastRegistry.totalModelCount() > 0) {
            try {
                return fastRegistry.callSync(prompt);
            } catch (ProviderBudgetExhaustedException e) {
                // Every fast handle is budget-exhausted. The budget gate throws
                // before any network call, so nothing was dispatched — spill
                // over to the primary chain if it still has capacity.
                if (primaryRegistry.totalModelCount() > 0) {
                    log.warn("LlmRouter.callFast: fast tier budget-exhausted ({}) — "
                            + "falling back to primary chain", e.getMessage());
                    return callPrimary(prompt);
                }
                throw e;
            }
        }
        return fastPool.callSync(prompt);
    }

    /**
     * Phase 7 E1.6 — synchronous call routed to the pool tagged with the given role,
     * with transparent fallback to {@link #callFast(String)} when the role-tagged
     * pool is absent, blocked, or throws. Intended for stages like
     * {@code runQualityAudit} that want a specific provider (Cerebras's 14 ms
     * inference) but must remain available when that provider is rate-limited.
     */
    public String callForRole(String role, String prompt) {
        // Phase 23.6d follow-on — tag the LlmCallContext with the role so
        // LlmCallMetrics.record / recordTokens attribute every call AND its
        // fallback under the same role bucket in the 5-min heartbeat. Covers
        // `fast-audit` today (the only declared role caller) and any future
        // role registered via app.ai.role-overrides.
        return com.emergentterrain.llm.LlmCallContext.withRole(role, () -> {
            LlmPool p = poolsByRole.get(role);
            if (p != null && !p.allBlocked()) {
                try { return p.callSync(prompt); }
                catch (RuntimeException e) {
                    log.warn("LlmRouter: role={} provider={} failed, falling back to fast: {}",
                            role, p.providerKey(), e.getMessage());
                }
            }
            return callFast(prompt);
        });
    }

    /** True when a pool is registered against the given role and is currently usable. */
    public boolean roleAvailable(String role) {
        LlmPool p = poolsByRole.get(role);
        return p != null && !p.allBlocked();
    }

    /**
     * Phase 9 U6 — streaming call routed via {@link FastModelRegistry}.
     * Falls back to {@link #fastPool} when no fast model is registered.
     */
    public void streamFast(String prompt, Consumer<String> tokenConsumer) {
        if (shouldRerouteToPrimary(prompt) && primaryRegistry.totalModelCount() > 0) {
            log.info("LlmRouter.streamFast: prompt ~{} tokens > {} cap — rerouting to primary "
                            + "(fast-tier TPM would 413 every handle)",
                    estimateTokens(prompt), FAST_TIER_PROMPT_CAP_TOKENS);
            streamPrimary(prompt, tokenConsumer);
            return;
        }
        if (fastRegistry.totalModelCount() > 0) {
            try {
                fastRegistry.callStream(prompt, tokenConsumer);
                return;
            } catch (ProviderBudgetExhaustedException e) {
                // Budget gate throws pre-stream, so no tokens reached the
                // consumer — safe to restart on the primary chain.
                if (primaryRegistry.totalModelCount() > 0) {
                    log.warn("LlmRouter.streamFast: fast tier budget-exhausted ({}) — "
                            + "falling back to primary chain", e.getMessage());
                    streamPrimary(prompt, tokenConsumer);
                    return;
                }
                throw e;
            }
        }
        fastPool.callStream(prompt, tokenConsumer);
    }

    /**
     * Phase 20.11 fixup — prompt-size guard for fast-tier dispatch. Returns
     * true when the chars/4 estimate exceeds {@link #FAST_TIER_PROMPT_CAP_TOKENS}.
     * Used by {@link #callFast} / {@link #streamFast} to reroute to the
     * primary chain instead of cascading 413s through every fast handle.
     */
    private boolean shouldRerouteToPrimary(String prompt) {
        return prompt != null && estimateTokens(prompt) > FAST_TIER_PROMPT_CAP_TOKENS;
    }

    /**
     * Conservative chars/4 estimator. Matches the pool-level
     * {@code estimateTokens} so the budgetGate and the router agree on
     * sizes. Output tokens aren't included — this is a prompt-size guard,
     * not a total-request budget.
     */
    private static int estimateTokens(String prompt) {
        if (prompt == null || prompt.isEmpty()) return 0;
        return Math.max(1, prompt.length() / 4);
    }

    /**
     * Phase 9 U3b — first available model in the ordered registry view, or the
     * first registered model's id if everything is blocked, or the legacy pool
     * selection when the registry is empty (e.g. tests without a supportsTools
     * pool).
     */
    public String primaryModel() {
        List<ModelHandle> available = orderedAvailable();
        if (!available.isEmpty()) return available.get(0).modelId();
        if (!primaryRegistry.allModels().isEmpty()) {
            return primaryRegistry.allModels().get(0).modelId();
        }
        return nextHealthyPrimary().selectedModel();
    }
    public String fastModel() {
        if (fastRegistry.totalModelCount() > 0) {
            List<ModelHandle> available = fastRegistry.availableModels();
            if (!available.isEmpty()) return available.get(0).modelId();
            return fastRegistry.allModels().get(0).modelId();
        }
        return fastPool.selectedModel();
    }
    public int    primaryPoolSize() { return Math.max(1, primaryRegistry.totalModelCount()); }

    /** Provider-key chain (e.g. ["groq", "nvidia", "openrouter", "cerebras"]). */
    public List<String> primaryProviderChain() {
        return primaryChain.stream().map(LlmPool::providerKey).toList();
    }

    // ── Capability + quota probes ──────────────────────────────────────────

    /**
     * True when at least one currently-available model in the registry passes
     * the {@code app.ai.tool-calling.providers} opt-in filter. Phase 7 E2 —
     * operators flip non-Groq providers into the list after the soak; Phase 9
     * U3b — the filter is applied per-model rather than per-pool.
     */
    public boolean supportsProfessorTools() {
        return !orderedAvailableToolEnabled().isEmpty();
    }

    /** True when no model in the registry is currently available. */
    public boolean primaryFullyExhausted() {
        return primaryRegistry.availableModels().isEmpty();
    }

    /**
     * Phase 7 E3 — true when the JSON-path revision target (a primary pool reachable
     * by {@link #streamPrimary(String, Consumer)}) has at least one unblocked pool.
     * The JSON path uses the fast pool for critique but a primary pool for the
     * revision stream, so an exhausted primary chain alone fails the JSON path.
     */
    public boolean hasJsonPathPrimary() {
        return !primaryFullyExhausted();
    }

    /**
     * Phase 7 E3 — true when a fast-tier model is wired and currently usable.
     * Gate for routing to the {@code rev:ollama:ok} fallback when both the
     * tools and JSON-path branches are unavailable.
     *
     * <p>Phase 9 U6 — derives from {@link FastModelRegistry#availableModels()}
     * when the registry has any handle; otherwise falls back to the legacy
     * {@link #fastPool} health check.
     */
    public boolean hasFastPool() {
        if (fastRegistry.totalModelCount() > 0) {
            return !fastRegistry.availableModels().isEmpty();
        }
        return fastPool != null && !fastPool.allBlocked();
    }

    /** Earliest time any pool in the primary chain recovers from its block. */
    public java.time.Instant earliestPrimaryRecovery() {
        return primaryChain.stream()
                .map(LlmPool::earliestRecovery)
                .min(java.time.Instant::compareTo)
                .orElse(java.time.Instant.now());
    }

    /**
     * Tool-enabled primary call routed via {@link PrimaryModelRegistry}. Walks
     * every available tool-eligible model (registry availability + E2 opt-in
     * filter), dispatching each via {@link LlmPool#callWithToolsOnModel}. The
     * first non-throwing handle wins.
     *
     * <p>Phase 7 F5.2 — returns the full {@link com.emergentterrain.llm.ToolCallResponse}
     * so the caller (ResearchNarrationService) gets both the synthesis text and
     * the trace of tool invocations the LLM issued. The plain-primary fallback
     * path returns a trace-empty response.
     *
     * <p>Phase 9 U3b — the E2 {@code tool-calling.providers} filter migrates
     * from pool-level (skip pools not in the list) to model-level (skip handles
     * whose provider isn't in the list). Bit-identical when each provider has
     * one model; cross-handle when providers carry multiple models.
     */
    public com.emergentterrain.llm.ToolCallResponse callPrimaryWithTools(
            String systemPrompt, String userPrompt,
            String toolsJson,
            Function<String, String> executor) {
        return callPrimaryWithTools(systemPrompt, userPrompt, toolsJson, executor, null);
    }

    /**
     * 2026-05-22 — overload accepting a per-retry reset hook. Callers
     * whose executor closes over shared mutable state (e.g. a
     * {@link com.emergentterrain.service.MathToolSuite.ToolMetrics}
     * accumulator) pass {@code onRetry} so that state can be cleared
     * when the router moves to the next handle. Without this hook, a
     * tool call that succeeded on handle A, then a subsequent network
     * failure that bubbles a RuntimeException, leaves the executor's
     * shared counters inflated for whichever handle ultimately returns
     * — even when that handle's own trace is empty. Surfaced root cause
     * for the P-a328-865/880/890 trace-empty-yet-prof:ok divergence.
     */
    public com.emergentterrain.llm.ToolCallResponse callPrimaryWithTools(
            String systemPrompt, String userPrompt,
            String toolsJson,
            Function<String, String> executor,
            Runnable onRetry) {
        List<ModelHandle> eligible = orderedAvailableToolEnabled();
        RuntimeException last = null;
        // Phase 12 D7 — record every model handle the router skips before reaching
        // the one that succeeds. Today the warn log at line ~441 is the only
        // signal; without an in-band channel the persisted callPath shows only
        // the final model that produced the synthesis, hiding the budget /
        // TPD-recovery / tool-call failures that drove the fallback.
        java.util.List<String> skipped = new java.util.ArrayList<>();
        for (ModelHandle h : eligible) {
            try {
                com.emergentterrain.llm.ToolCallResponse resp = h.pool().callWithToolsOnModel(
                        h.modelId(), systemPrompt, userPrompt, toolsJson, executor);
                if (skipped.isEmpty()) {
                    return resp;
                }
                // Phase 12 D7 — preserve the underlying response's own skippedModels
                // (the pool may have skipped intra-pool models for the same reasons)
                // and prepend our router-level skips so the order is router→pool.
                java.util.List<String> merged = new java.util.ArrayList<>(skipped);
                merged.addAll(resp.skippedModels());
                return new com.emergentterrain.llm.ToolCallResponse(
                        resp.synthesis(), resp.trace(), java.util.List.copyOf(merged));
            } catch (RuntimeException e) {
                last = e;
                skipped.add(h.modelId());
                log.warn("LlmRouter: handle={} callWithTools failed, trying next: {}",
                        h.reviewerId(), e.getMessage());
                // 2026-05-22 — clear shared-state accumulators in the caller's
                // executor (typically a MathToolSuite.ToolMetrics) so partial
                // success counts from this failed attempt don't leak into the
                // next handle's response. Trace is naturally per-handle (built
                // inside the pool's loop, discarded on exception), but metrics
                // is shared via closure.
                if (onRetry != null) {
                    try { onRetry.run(); }
                    catch (RuntimeException re) {
                        log.warn("LlmRouter: onRetry callback failed: {}", re.getMessage());
                    }
                }
            }
        }
        if (last != null) throw last;
        // No eligible handle — degrade to a plain primary call. Empty trace.
        return com.emergentterrain.llm.ToolCallResponse.ofSynthesis(
                callPrimary(systemPrompt + "\n\n" + userPrompt));
    }
}
