package com.emergentterrain.service;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PanelOutcome;
import com.emergentterrain.research.PanelReview;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PromptTemplateIds;
import com.emergentterrain.research.PromptTemplateRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Phase 7 F7.2 — runs a multi-reviewer panel against a paper's draft and
 * deterministic tool-call trace, then returns the aggregated {@link PanelOutcome}.
 *
 * <p>Each panelist is one {@link LlmPool} resolved from the configured
 * {@code app.research.review-panel.providers} list. Reviewers run in parallel
 * — latency is bounded by the slowest provider, not the sum. Each reviewer's
 * raw response is parsed via {@link PanelReview#parse(String, String)};
 * if parsing fails the reviewer is retried once, then dropped if it still
 * fails so a single bad reviewer can't block the rest of the panel.
 *
 * <p>The service is intentionally side-effect-free: it does not persist anything,
 * it does not stamp the outcome onto a {@link NarrationResult}, and it does not
 * decide DISPUTED routing. {@link ResearchNarrationService} integrates it after
 * the synthesis turn; {@code UniversityService.publishPaper} reads the stamped
 * outcome at write time. F7.3 will add the cherry-picked-evidence audit-gap
 * dimension based on this outcome.
 */
@Service
public class ReviewPanelService {

    private static final Logger log = LoggerFactory.getLogger(ReviewPanelService.class);

    /**
     * Default panel size — every provider that ships a 70B+ tools-capable pool
     * per the May 2026 provider survey. Operator opt-in via the
     * {@code app.research.review-panel.providers} CSV (this exact string is the
     * default; a comma-separated subset narrows the panel).
     */
    static final String DEFAULT_PROVIDERS = "groq,nvidia,openrouter,cerebras";

    /** Hard timeout per reviewer call. Slow providers drop, panel proceeds. */
    static final long REVIEWER_TIMEOUT_SECONDS = 45;

    private final PromptTemplateRegistry registry;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final List<LlmPool> reviewerPools;
    private final ExecutorService panelExecutor;

    public ReviewPanelService(
            ApplicationContext ctx,
            PromptTemplateRegistry registry,
            ObjectMapper mapper,
            @Value("${app.research.review-panel.enabled:true}") boolean enabled,
            @Value("${app.research.review-panel.providers:" + DEFAULT_PROVIDERS + "}") String providersCsv) {
        this.registry = registry;
        this.mapper   = mapper;
        this.enabled  = enabled;

        // Look up the configured providers in the bean registry. Missing entries
        // are silently skipped so an operator who drops a key (e.g. removes
        // OpenRouter) still gets a usable panel from the remaining providers.
        Map<String, LlmPool> byProvider = new LinkedHashMap<>();
        for (LlmPool p : ctx.getBeansOfType(LlmPool.class).values()) {
            byProvider.putIfAbsent(p.providerKey().toLowerCase(), p);
        }
        List<LlmPool> resolved = new ArrayList<>();
        for (String raw : providersCsv.split(",")) {
            String key = raw.trim().toLowerCase();
            if (key.isEmpty()) continue;
            LlmPool pool = byProvider.get(key);
            if (pool != null) resolved.add(pool);
            else log.warn("ReviewPanelService: configured provider '{}' has no registered pool; skipping", key);
        }
        this.reviewerPools = Collections.unmodifiableList(resolved);

        // Bounded executor sized to the panel — each reviewer gets its own
        // thread so the parallel fan-out doesn't queue. Daemon threads so they
        // don't keep the JVM alive at shutdown.
        int size = Math.max(1, reviewerPools.size());
        this.panelExecutor = Executors.newFixedThreadPool(size, r -> {
            Thread t = new Thread(r, "review-panel");
            t.setDaemon(true);
            return t;
        });

        log.info("ReviewPanelService: enabled={} providers={} resolved={}",
                enabled, providersCsv,
                reviewerPools.stream().map(LlmPool::providerKey).toList());
    }

    /** True when the panel is enabled AND at least one reviewer pool resolved. */
    public boolean isActive() {
        return enabled && !reviewerPools.isEmpty();
    }

    public int panelSize() {
        return reviewerPools.size();
    }

    /**
     * Run the panel against a paper draft + narration result. The narration's
     * {@code toolCallTrace} is the load-bearing input — without a trace the
     * panel has nothing to grade against and returns {@link PanelOutcome#empty()}.
     */
    public PanelOutcome runPanel(PaperDraft draft, NarrationResult narration) {
        if (!isActive()) return PanelOutcome.empty();
        if (narration == null) return PanelOutcome.empty();
        List<ToolInvocation> trace = narration.toolCallTrace();
        if (trace == null || trace.isEmpty()) return PanelOutcome.empty();

        String prompt = renderPrompt(draft, narration, trace);

        long start = System.currentTimeMillis();
        List<CompletableFuture<Optional<PanelReview>>> futures = new ArrayList<>();
        for (LlmPool pool : reviewerPools) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> callReviewer(pool, prompt), panelExecutor));
        }

        List<PanelReview> reviews = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            LlmPool pool = reviewerPools.get(i);
            try {
                Optional<PanelReview> result =
                        futures.get(i).get(REVIEWER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                result.ifPresent(reviews::add);
            } catch (Exception e) {
                log.warn("ReviewPanelService: reviewer={} dropped ({})",
                        pool.providerKey(), e.getClass().getSimpleName());
            }
        }
        long ms = System.currentTimeMillis() - start;

        PanelOutcome outcome = PanelOutcome.aggregate(reviews);
        log.info("ReviewPanelService: paperId={} reviewers={}/{} agreement={} ms={}",
                draft != null ? draft.paperId() : "?", reviews.size(), reviewerPools.size(),
                String.format("%.2f", outcome.agreementScore()), ms);
        return outcome;
    }

    /**
     * Call one reviewer with single retry on parse failure. Network/RPC errors
     * propagate as runtime exceptions and are caught by the orchestrator's
     * future.get() so they drop the reviewer rather than retry.
     */
    private Optional<PanelReview> callReviewer(LlmPool pool, String prompt) {
        String reviewerId = pool.providerKey() + "/" + pool.selectedModel();
        for (int attempt = 0; attempt < 2; attempt++) {
            String raw;
            try {
                raw = pool.callSync(prompt);
            } catch (RuntimeException e) {
                log.warn("ReviewPanelService: reviewer={} attempt={} call failed: {}",
                        reviewerId, attempt, e.getMessage());
                return Optional.empty();
            }
            Optional<PanelReview> parsed = PanelReview.parse(raw, reviewerId);
            if (parsed.isPresent()) return parsed;
            log.info("ReviewPanelService: reviewer={} attempt={} parse failed, retrying",
                    reviewerId, attempt);
        }
        return Optional.empty();
    }

    private String renderPrompt(PaperDraft draft, NarrationResult narration,
                                 List<ToolInvocation> trace) {
        String traceJson;
        try { traceJson = mapper.writeValueAsString(trace); }
        catch (Exception e) { traceJson = "[]"; }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",        draft != null ? draft.domain()    : "");
        params.put("topicName",     draft != null ? draft.topicName() : "");
        params.put("hypothesis",    safe(narration.hypothesis()));
        params.put("findings",      safe(narration.findings()));
        params.put("conclusion",    safe(narration.conclusion()));
        params.put("toolCallTrace", traceJson);
        return registry.render(PromptTemplateIds.NARRATION_PANEL_REVIEW_ID, params);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
