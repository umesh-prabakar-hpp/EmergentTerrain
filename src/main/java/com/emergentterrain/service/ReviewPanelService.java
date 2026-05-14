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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 7 F7.2 + Phase 9 U3a — runs a multi-reviewer panel against a paper's
 * draft and deterministic tool-call trace, then returns the aggregated
 * {@link PanelOutcome}.
 *
 * <p>Phase 9 U3a: reviewer selection migrated from pool-walking to model-walking
 * via {@link PrimaryModelRegistry}. Each panelist is one {@link ModelHandle}
 * pulled from {@link PrimaryModelRegistry#availableModels()}, filtered by the
 * {@code app.research.review-panel.providers} CSV and capped at
 * {@code app.research.review-panel.size}. Pure availability-based picking
 * (operator direction 2026-05-15) — the first N healthy handles win
 * regardless of provider diversity.
 *
 * <p>Reviewers still run <b>sequentially</b> — per the 2026-05-14 operator
 * direction the panel intentionally trades latency for cheaper quota usage
 * and clearer telemetry. Each reviewer's raw response is parsed via
 * {@link PanelReview#parse(String, String)}; if parsing fails the reviewer is
 * retried once, then dropped.
 *
 * <p>Per-model 429 fallthrough still happens at the pool level — when a
 * reviewer's chosen {@link ModelHandle} 429s, the pool's existing
 * {@code callSyncWithFallback} walks the pool's remaining models. The handle
 * is dropped only when the pool exhausts everything it owns; the panel then
 * proceeds to the next handle in {@link PrimaryModelRegistry#availableModels()}.
 *
 * <p>The service is intentionally side-effect-free: it does not persist
 * anything, it does not stamp the outcome onto a {@link NarrationResult}, and
 * it does not decide DISPUTED routing. {@link ResearchNarrationService}
 * integrates it after the synthesis turn; {@code UniversityService.publishPaper}
 * reads the stamped outcome at write time. F7.3 added the
 * cherry-picked-evidence audit-gap dimension based on this outcome.
 */
@Service
public class ReviewPanelService {

    private static final Logger log = LoggerFactory.getLogger(ReviewPanelService.class);

    /**
     * Default provider filter — every provider that ships a 70B+ tools-capable
     * pool per the May 2026 provider survey. Operator opt-in via the
     * {@code app.research.review-panel.providers} CSV; a comma-separated subset
     * narrows the eligible providers.
     */
    static final String DEFAULT_PROVIDERS = "groq,nvidia,openrouter,cerebras";

    /**
     * Default panel target size — number of distinct {@link ModelHandle}s that
     * grade each paper. With NVIDIA contributing 5 models, Groq 2, OpenRouter
     * 2, Cerebras 1 — the aggregate registry holds ~10 70B+ models — capping at
     * 4 keeps per-paper cost bounded while still giving the panel cross-vendor
     * representation when all providers are healthy.
     */
    static final int DEFAULT_PANEL_SIZE = 4;

    private final PrimaryModelRegistry primaryRegistry;
    private final PromptTemplateRegistry promptRegistry;
    private final ObjectMapper mapper;
    private final boolean enabled;
    /** Lower-cased opt-in set of providers. Empty = no filter. */
    private final Set<String> providerFilter;
    private final int panelSize;

    public ReviewPanelService(
            PrimaryModelRegistry primaryRegistry,
            PromptTemplateRegistry promptRegistry,
            ObjectMapper mapper,
            @Value("${app.research.review-panel.enabled:true}") boolean enabled,
            @Value("${app.research.review-panel.providers:" + DEFAULT_PROVIDERS + "}") String providersCsv,
            @Value("${app.research.review-panel.size:" + DEFAULT_PANEL_SIZE + "}") int panelSize) {
        this.primaryRegistry = primaryRegistry;
        this.promptRegistry  = promptRegistry;
        this.mapper          = mapper;
        this.enabled         = enabled;
        this.providerFilter  = parseProviderFilter(providersCsv);
        this.panelSize       = Math.max(1, panelSize);

        log.info("ReviewPanelService: enabled={} mode=sequential providers={} panelSize={} "
                        + "registryHandles={}",
                enabled,
                providerFilter.isEmpty() ? "* (all)" : providerFilter,
                this.panelSize,
                primaryRegistry.totalModelCount());
    }

    /**
     * Lower-cased opt-in set parsed from the providers CSV. Empty set / blank /
     * wildcard {@code "*"} means "no restriction; every primary model is
     * eligible".
     */
    private static Set<String> parseProviderFilter(String csv) {
        if (csv == null) return Set.of();
        String trimmed = csv.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String raw : trimmed.split(",")) {
            String k = raw.trim().toLowerCase();
            if (!k.isEmpty()) out.add(k);
        }
        return Collections.unmodifiableSet(out);
    }

    /** True when the panel is enabled AND the registry has at least one model. */
    public boolean isActive() {
        return enabled && primaryRegistry.totalModelCount() > 0;
    }

    /** Configured target panel size — the cap on number of reviewers per panel. */
    public int panelSize() { return panelSize; }

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

        // Pick the panel: walk the registry's available models, filter by
        // providerFilter, take the first panelSize handles. Pure availability —
        // no diversity bonus.
        List<ModelHandle> picks = new ArrayList<>();
        for (ModelHandle h : primaryRegistry.availableModels()) {
            if (!providerFilter.isEmpty() && !providerFilter.contains(h.providerKey())) continue;
            picks.add(h);
            if (picks.size() >= panelSize) break;
        }

        long start = System.currentTimeMillis();
        List<PanelReview> reviews = new ArrayList<>();
        for (ModelHandle handle : picks) {
            try {
                Optional<PanelReview> result = callReviewer(handle, prompt);
                result.ifPresent(reviews::add);
            } catch (RuntimeException e) {
                // Defensive: callReviewer already converts call failures to
                // Optional.empty(); this catches anything else (e.g. a thread
                // interrupt from upstream shutdown).
                log.warn("ReviewPanelService: reviewer={} dropped ({})",
                        handle.reviewerId(), e.getClass().getSimpleName());
            }
        }
        long ms = System.currentTimeMillis() - start;

        PanelOutcome outcome = PanelOutcome.aggregate(reviews);
        log.info("ReviewPanelService: paperId={} reviewers={}/{} target={} agreement={} ms={} mode=sequential",
                draft != null ? draft.paperId() : "?", reviews.size(), picks.size(),
                panelSize, String.format("%.2f", outcome.agreementScore()), ms);
        return outcome;
    }

    /**
     * Call one reviewer with single retry on parse failure.
     *
     * <p>The handle's owning pool dispatches through
     * {@link LlmPool#callSyncOnModel(String, String)} — for multi-model pools
     * this targets the specific model the registry picked, with no cross-model
     * round-robin. Cross-handle fallthrough on 429 is the registry's job (the
     * next handle in {@link PrimaryModelRegistry#availableModels()} is tried by
     * {@link #runPanel}'s outer loop).
     *
     * <p>Parse failures are different from call failures — they get one retry,
     * because the prompt didn't change and a transient JSON-shape glitch may
     * resolve on a fresh sample.
     */
    private Optional<PanelReview> callReviewer(ModelHandle handle, String prompt) {
        String reviewerId = handle.reviewerId();
        for (int attempt = 0; attempt < 2; attempt++) {
            String raw;
            try {
                raw = handle.pool().callSyncOnModel(handle.modelId(), prompt);
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
        return promptRegistry.render(PromptTemplateIds.NARRATION_PANEL_REVIEW_ID, params);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
