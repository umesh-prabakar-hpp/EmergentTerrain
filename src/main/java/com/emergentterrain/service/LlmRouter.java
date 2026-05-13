package com.emergentterrain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    final List<LlmPool> primaryChain;
    final LlmPool       fastPool;
    /** Preserved for backward-compat: first pool in primaryChain. */
    final LlmPool       primaryPool;
    /** Pools indexed by their {@link LlmPool#role()} tag — first-registered wins. */
    final Map<String, LlmPool> poolsByRole;

    public LlmRouter(ApplicationContext ctx,
                     @org.springframework.beans.factory.annotation.Value(
                             "${app.ai.primaryProviderOrder:" + DEFAULT_PRIMARY_ORDER + "}")
                     String primaryOrder,
                     @org.springframework.beans.factory.annotation.Value(
                             "${app.ai.fastProvider:" + DEFAULT_FAST_PROVIDER + "}")
                     String fastProvider) {
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

        log.info("LlmRouter: primaryChain={} fast={} [{}] roles={}",
                primaryChain.stream().map(LlmPool::providerKey).toList(),
                fastPool.providerKey(), fastPool.selectedModel(),
                poolsByRole.entrySet().stream()
                        .map(e -> e.getKey() + "→" + e.getValue().providerKey())
                        .toList());
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

    /** Synchronous call routed to the primary chain. */
    public String callPrimary(String prompt) {
        RuntimeException last = null;
        for (LlmPool p : primaryChain) {
            if (p.allBlocked()) continue;
            try { return p.callSync(prompt); }
            catch (RuntimeException e) {
                last = e;
                log.warn("LlmRouter: provider={} callSync failed, trying next: {}",
                        p.providerKey(), e.getMessage());
            }
        }
        if (last != null) throw last;
        // All pools blocked — call the first anyway so its exception surfaces.
        return primaryChain.get(0).callSync(prompt);
    }

    /** Streaming call routed to the primary chain. */
    public void streamPrimary(String prompt, Consumer<String> tokenConsumer) {
        RuntimeException last = null;
        for (LlmPool p : primaryChain) {
            if (p.allBlocked()) continue;
            try { p.callStream(prompt, tokenConsumer); return; }
            catch (RuntimeException e) {
                last = e;
                log.warn("LlmRouter: provider={} callStream failed, trying next: {}",
                        p.providerKey(), e.getMessage());
            }
        }
        if (last != null) throw last;
        primaryChain.get(0).callStream(prompt, tokenConsumer);
    }

    /** Synchronous call routed to the fast (local) pool. */
    public String callFast(String prompt) {
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
        LlmPool p = poolsByRole.get(role);
        if (p != null && !p.allBlocked()) {
            try { return p.callSync(prompt); }
            catch (RuntimeException e) {
                log.warn("LlmRouter: role={} provider={} failed, falling back to fast: {}",
                        role, p.providerKey(), e.getMessage());
            }
        }
        return callFast(prompt);
    }

    /** True when a pool is registered against the given role and is currently usable. */
    public boolean roleAvailable(String role) {
        LlmPool p = poolsByRole.get(role);
        return p != null && !p.allBlocked();
    }

    /** Streaming call routed to the fast pool. */
    public void streamFast(String prompt, Consumer<String> tokenConsumer) {
        fastPool.callStream(prompt, tokenConsumer);
    }

    public String primaryModel()    { return nextHealthyPrimary().selectedModel(); }
    public String fastModel()       { return fastPool.selectedModel(); }
    public int    primaryPoolSize() { return Math.max(1, nextHealthyPrimary().selectedModels().size()); }

    /** Provider-key chain (e.g. ["groq", "nvidia", "openrouter", "cerebras"]). */
    public List<String> primaryProviderChain() {
        return primaryChain.stream().map(LlmPool::providerKey).toList();
    }

    // ── Capability + quota probes ──────────────────────────────────────────

    /** True when at least one pool in the primary chain supports tool-calling. */
    public boolean supportsProfessorTools() {
        return primaryChain.stream().anyMatch(LlmPool::supportsTools);
    }

    /** True when EVERY pool in the primary chain reports allBlocked. */
    public boolean primaryFullyExhausted() {
        return primaryChain.stream().allMatch(LlmPool::allBlocked);
    }

    /** Earliest time any pool in the primary chain recovers from its block. */
    public java.time.Instant earliestPrimaryRecovery() {
        return primaryChain.stream()
                .map(LlmPool::earliestRecovery)
                .min(java.time.Instant::compareTo)
                .orElse(java.time.Instant.now());
    }

    /**
     * Tool-enabled primary call. Routes through the primary chain; the first
     * non-blocked tool-capable pool gets the call. Falls back to a plain
     * primary call when no pool in the chain supports tools.
     */
    public String callPrimaryWithTools(String systemPrompt, String userPrompt,
                                        String toolsJson,
                                        Function<String, String> executor) {
        RuntimeException last = null;
        for (LlmPool p : primaryChain) {
            if (!p.supportsTools()) continue;
            if (p.allBlocked()) continue;
            try { return p.callWithTools(systemPrompt, userPrompt, toolsJson, executor); }
            catch (RuntimeException e) {
                last = e;
                log.warn("LlmRouter: provider={} callWithTools failed, trying next: {}",
                        p.providerKey(), e.getMessage());
            }
        }
        if (last != null) throw last;
        // No tool-capable pool — degrade to a plain primary call.
        return callPrimary(systemPrompt + "\n\n" + userPrompt);
    }
}
