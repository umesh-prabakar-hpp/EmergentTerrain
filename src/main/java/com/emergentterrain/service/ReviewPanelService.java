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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 * <p>Reviewers run <b>provider-aware parallel</b> (2026-05-25): each provider's
 * reviewers run concurrently with other providers', but reviewers sharing a
 * provider run sequentially on one worker so they don't burst that provider's
 * per-minute token budget together. This keeps the quota-pacing the 2026-05-14
 * fully-sequential panel bought while recovering the latency that was stalling
 * drafts in the panel. Each reviewer's raw response is parsed via
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
     * Default panel target size — number of distinct {@link ModelHandle}s that
     * grade each paper. With NVIDIA contributing 5 models, Groq 2, OpenRouter
     * 2, Cerebras 1 — the aggregate registry holds ~10 70B+ models — capping at
     * 4 keeps per-paper cost bounded while still giving the panel cross-vendor
     * representation when all providers are healthy.
     */
    static final int DEFAULT_PANEL_SIZE = 4;

    /** Phase 29 §2.B — the tool a schema-pinned reviewer calls to submit its review. */
    static final String SUBMIT_REVIEW_TOOL_NAME = "submit_review";

    /**
     * Phase 29 §2.B — system steer for the tool-call review path. Tells the model
     * to submit via the tool, not as prose/JSON text.
     */
    private static final String SUBMIT_REVIEW_SYSTEM =
            "You are an expert, skeptical peer reviewer. Read the paper and its tool-call "
            + "trace in the user message, then submit your assessment by calling the "
            + "submit_review tool exactly once with the structured fields. Do not write the "
            + "review as prose or JSON text — use the submit_review tool.";

    /**
     * Phase 29 §2.B — single-tool spec (a JSON array, the shape the pools'
     * {@code callWithToolsOnModel} expects). The argument shape mirrors exactly
     * what {@link PanelReview#parse} reads (grades.{honesty,completeness,rigor,
     * novelty}, cited_calls, omitted_relevant, contradictions[].{trace_idx,
     * vs_prose}, recommendations, evidence_label). Deliberately uses NO JSON-schema
     * {@code enum} anywhere — xAI/grok rejects tool schemas whose enum values
     * contain '/', and the §1.A operators fix removed the last such enum; the
     * allowed evidence labels live in the field description instead.
     */
    static final String SUBMIT_REVIEW_TOOLS_JSON = """
            [
              {
                "type":"function",
                "function":{
                  "name":"submit_review",
                  "description":"Submit your structured peer review of the paper under review. Call this exactly ONCE with all fields filled in; do not also write the review as prose.",
                  "parameters":{
                    "type":"object",
                    "properties":{
                      "grades":{
                        "type":"object",
                        "description":"Four-axis grades, each a number in [0,1].",
                        "properties":{
                          "honesty":{"type":"number","description":"0..1 — does the prose faithfully represent the tool-call evidence?"},
                          "completeness":{"type":"number","description":"0..1 — are the relevant computations covered?"},
                          "rigor":{"type":"number","description":"0..1 — soundness of method and reasoning."},
                          "novelty":{"type":"number","description":"0..1 — does the result add something non-trivial?"}
                        },
                        "required":["honesty","completeness","rigor","novelty"]
                      },
                      "cited_calls":{"type":"array","items":{"type":"integer"},"description":"1-based trace indices the prose correctly cites."},
                      "omitted_relevant":{"type":"array","items":{"type":"integer"},"description":"1-based trace indices that were relevant but the prose omitted."},
                      "contradictions":{"type":"array","items":{"type":"object","properties":{"trace_idx":{"type":"integer"},"vs_prose":{"type":"string"}},"required":["trace_idx","vs_prose"]},"description":"Trace entries that contradict the synthesis."},
                      "recommendations":{"type":"array","items":{"type":"string"},"description":"Concrete, actionable recommendations."},
                      "evidence_label":{"type":"string","description":"Proposed evidence label — one of THEOREM, EMPIRICAL_RESULT, CONJECTURE, UNEXPLAINED_OBSERVATION. Omit if unsure."}
                    },
                    "required":["grades"]
                  }
                }
              }
            ]""";

    /**
     * Phase 29 §2.B — repair suffix appended on the text path's retry: tells a
     * model that returned prose exactly what JSON to emit. Catches the common
     * "prose preamble + JSON" / fenced-JSON failures without a tool call.
     */
    private static final String REPAIR_SUFFIX =
            "\n\nIMPORTANT: your previous response could not be parsed. Respond with ONLY a "
            + "single JSON object — no prose, no markdown code fences — matching exactly this "
            + "shape:\n"
            + "{\"grades\":{\"honesty\":0.0,\"completeness\":0.0,\"rigor\":0.0,\"novelty\":0.0},"
            + "\"cited_calls\":[],\"omitted_relevant\":[],"
            + "\"contradictions\":[{\"trace_idx\":1,\"vs_prose\":\"...\"}],"
            + "\"recommendations\":[\"...\"],\"evidence_label\":\"EMPIRICAL_RESULT\"}";

    private final PrimaryModelRegistry primaryRegistry;
    private final PromptTemplateRegistry promptRegistry;
    private final ObjectMapper mapper;
    private final boolean enabled;
    /** Lower-cased opt-in set of providers. Empty = no filter. */
    private final Set<String> providerFilter;
    private final int panelSize;

    /**
     * Phase 29 §2.B — schema-pinned reviewers. When on, tool-capable reviewer
     * models submit their review by calling the {@code submit_review} tool, so
     * the provider enforces the JSON shape and {@link PanelReview#parse} can't
     * fail on prose. Non-tool models (or any tool-path miss) fall back to the
     * text path, whose retry is now a repair prompt (parse error + exact schema)
     * rather than a verbatim re-send. Default off — rollout-gated per wishlist P3.
     */
    private final boolean toolCallSchemaEnabled;

    /**
     * Provider-aware panel execution: each provider's reviewers run on their own
     * virtual thread (so different providers' calls overlap), while reviewers that
     * share a provider run sequentially on that thread (so two handles on one
     * provider can't burst its per-minute token budget together — the quota-pacing
     * the old fully-sequential panel bought, kept, while recovering the latency).
     */
    private final ExecutorService panelExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ReviewPanelService(
            PrimaryModelRegistry primaryRegistry,
            PromptTemplateRegistry promptRegistry,
            ObjectMapper mapper,
            @Value("${app.research.review-panel.enabled:true}") boolean enabled,
            @Value("${app.research.review-panel.providers:}") String providersCsv,
            @Value("${app.research.review-panel.size:" + DEFAULT_PANEL_SIZE + "}") int panelSize,
            @Value("${app.research.review-panel.tool-call-schema.enabled:false}")
            boolean toolCallSchemaEnabled) {
        this.primaryRegistry = primaryRegistry;
        this.promptRegistry  = promptRegistry;
        this.mapper          = mapper;
        this.enabled         = enabled;
        this.providerFilter  = parseProviderFilter(providersCsv);
        this.panelSize       = Math.max(1, panelSize);
        this.toolCallSchemaEnabled = toolCallSchemaEnabled;

        log.info("ReviewPanelService: enabled={} mode=sequential providers={} panelSize={} "
                        + "toolCallSchemaEnabled={} registryHandles={}",
                enabled,
                providerFilter.isEmpty() ? "* (all)" : providerFilter,
                this.panelSize,
                toolCallSchemaEnabled,
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

    /**
     * The model that generated the paper's prose, parsed from the {@code fp:<model>}
     * token at the head of the narration call path (e.g.
     * {@code "fp:claude-sonnet-4-6 → audit:4b:… → …"} → {@code "claude-sonnet-4-6"}).
     * Returns null when the call path is absent or has no {@code fp:} prefix — in
     * which case no handle is treated as the narrator and the panel is unchanged.
     * Matched against {@link ModelHandle#modelId()} to bar the narrator from a
     * panel majority.
     */
    static String narrationModelId(String callPath) {
        if (callPath == null) return null;
        int i = callPath.indexOf("fp:");
        if (i < 0) return null;
        String[] tokens = callPath.substring(i + 3).trim().split("\\s+");
        if (tokens.length == 0) return null;
        String id = tokens[0].replace("→", "").trim();
        return id.isEmpty() ? null : id;
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
        // providerFilter. Partition into the narration model (the model that
        // generated the prose under review) and independent reviewers, so the
        // narrator can be barred from a panel majority below.
        String narratorModelId = narrationModelId(narration.callPath());
        List<ModelHandle> independents = new ArrayList<>();
        List<ModelHandle> narratorHandles = new ArrayList<>();
        for (ModelHandle h : primaryRegistry.availableModels()) {
            if (!providerFilter.isEmpty() && !providerFilter.contains(h.providerKey())) continue;
            if (narratorModelId != null && narratorModelId.equals(h.modelId())) narratorHandles.add(h);
            else independents.add(h);
        }

        // Fill the panel with independent reviewers first.
        List<ModelHandle> picks = new ArrayList<>();
        for (ModelHandle h : independents) {
            if (picks.size() >= panelSize) break;
            picks.add(h);
        }
        // Add the narration model only as a strict minority — never a majority of
        // the panel. It wrote the text under review, so letting it be ≥ half the
        // reviewers would let it swing the median grade and the "≥2 reviewers"
        // contradiction signal (self-review). Cap its slots at the number of
        // independent reviewers already picked (so narrator ≤ half), bounded by
        // the remaining panel budget and how many narrator handles exist. With no
        // independent reviewer available, the narrator is dropped entirely (an
        // empty/degraded panel beats a self-graded one). No-op under the default
        // config, where the narrator (claude) isn't in providerFilter at all.
        int narratorBudget = Math.min(picks.size(),
                Math.min(panelSize - picks.size(), narratorHandles.size()));
        for (int k = 0; k < narratorBudget; k++) {
            picks.add(narratorHandles.get(k));
        }
        if (!narratorHandles.isEmpty()) {
            log.info("ReviewPanelService: narration model '{}' capped to {}/{} panel slots "
                            + "(independent reviewers={}) — barred from majority",
                    narratorModelId, narratorBudget, picks.size(), picks.size() - narratorBudget);
        }

        // Group picks by provider, preserving discovery order. Providers run
        // concurrently; handles within a provider run sequentially so they pace
        // that provider's per-minute token budget (a parallel burst of same-
        // provider reviewers was what tripped groq's TPM gate).
        Map<String, List<ModelHandle>> byProvider = new LinkedHashMap<>();
        for (ModelHandle h : picks) {
            byProvider.computeIfAbsent(h.providerKey(), k -> new ArrayList<>()).add(h);
        }

        // Reviewer calls run on fresh virtual threads, so the ambient
        // LlmCallContext (paperId, set by the narration task's withPaperId scope)
        // does not propagate automatically — capture it and re-establish it per
        // worker so LlmTraceWriter still attributes panel calls to this paper.
        // (The panel-review role is re-scoped inside callReviewer itself.)
        String paperId = com.emergentterrain.llm.LlmCallContext.currentPaperId();

        long start = System.currentTimeMillis();
        List<PanelReview> reviews = new CopyOnWriteArrayList<>(); // aggregate() is order-independent
        List<CompletableFuture<Void>> futures = new ArrayList<>(byProvider.size());
        for (List<ModelHandle> group : byProvider.values()) {
            futures.add(CompletableFuture.runAsync(() -> {
                for (ModelHandle handle : group) {
                    try {
                        com.emergentterrain.llm.LlmCallContext.withPaperId(paperId,
                                () -> callReviewer(handle, prompt)).ifPresent(reviews::add);
                    } catch (RuntimeException e) {
                        // Defensive: callReviewer already converts call failures to
                        // Optional.empty(); this catches anything else (e.g. a thread
                        // interrupt from upstream shutdown).
                        log.warn("ReviewPanelService: reviewer={} dropped ({})",
                                handle.reviewerId(), e.getClass().getSimpleName());
                    }
                }
            }, panelExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long ms = System.currentTimeMillis() - start;

        PanelOutcome outcome = PanelOutcome.aggregate(new ArrayList<>(reviews));
        log.info("ReviewPanelService: paperId={} reviewers={}/{} target={} agreement={} ms={} "
                        + "mode=provider-parallel providers={}",
                draft != null ? draft.paperId() : "?", reviews.size(), picks.size(),
                panelSize, String.format("%.2f", outcome.agreementScore()), ms, byProvider.size());
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
        // Phase 23.6d follow-on — tag the LlmCallContext so every panel-review
        // LLM call (including the parse-failure retry) shows up under the
        // panel-review role bucket in LlmCallMetricsReporter's 5-min heartbeat.
        // Without this scope, panel calls landed under role=unknown.
        return com.emergentterrain.llm.LlmCallContext.withRole("panel-review", () -> {
            String reviewerId = handle.reviewerId();
            // Phase 29 §2.B — schema-pinned path for tool-capable reviewers. On
            // any miss (model didn't call the tool, args unparseable, tool-call
            // exception) fall through to the text path, which is the unchanged
            // reliable contract plus a repair retry.
            if (toolCallSchemaEnabled && handle.pool().supportsTools()) {
                Optional<PanelReview> viaTool = callReviewerViaTool(handle, prompt, reviewerId);
                if (viaTool.isPresent()) return viaTool;
                log.info("ReviewPanelService: reviewer={} submit_review tool path yielded nothing "
                        + "— falling back to text", reviewerId);
            }
            return callReviewerViaText(handle, prompt, reviewerId);
        });
    }

    /**
     * Phase 29 §2.B — call a tool-capable reviewer with the {@code submit_review}
     * tool so the provider enforces the review shape. The executor captures the
     * tool-call arguments (and returns a terminal ack); we read the arguments
     * back — preferring the live capture, falling back to the response trace —
     * and parse them as a {@link PanelReview}. Returns empty on any miss so the
     * caller falls through to the text path.
     */
    private Optional<PanelReview> callReviewerViaTool(ModelHandle handle, String prompt,
                                                      String reviewerId) {
        java.util.concurrent.atomic.AtomicReference<String> capturedArgs =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.Function<String, String> executor = callJson -> {
            try {
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(callJson);
                if (SUBMIT_REVIEW_TOOL_NAME.equals(node.path("name").asText())) {
                    capturedArgs.compareAndSet(null, node.path("arguments").toString());
                }
            } catch (Exception ignore) {
                // malformed tool-call payload — leave capture null; trace fallback handles it
            }
            return "{\"status\":\"received\",\"done\":true}";
        };
        com.emergentterrain.llm.ToolCallResponse resp;
        try {
            resp = handle.pool().callWithToolsOnModel(
                    handle.modelId(), SUBMIT_REVIEW_SYSTEM, prompt, SUBMIT_REVIEW_TOOLS_JSON, executor);
        } catch (RuntimeException e) {
            log.warn("ReviewPanelService: reviewer={} submit_review tool-call failed: {}",
                    reviewerId, e.getMessage());
            return Optional.empty();
        }
        String args = capturedArgs.get();
        if ((args == null || args.isBlank()) && resp != null && resp.trace() != null) {
            for (ToolInvocation inv : resp.trace()) {
                if (SUBMIT_REVIEW_TOOL_NAME.equals(inv.functionName()) && inv.argsJson() != null) {
                    args = inv.argsJson();
                    break;
                }
            }
        }
        if (args == null || args.isBlank()) return Optional.empty();
        Optional<PanelReview> parsed = PanelReview.parse(args, reviewerId);
        if (parsed.isEmpty()) {
            log.info("ReviewPanelService: reviewer={} submit_review args present but unparseable", reviewerId);
        }
        return parsed;
    }

    /**
     * Phase 29 §2.B — the text path (the pre-29 contract). Two attempts: the
     * second is a REPAIR prompt (parse error reminder + exact schema) rather than
     * a verbatim re-send, so a model that wrapped its JSON in prose is told what
     * to fix. Used for non-tool models, when the tool path misses, or when the
     * tool-call flag is off.
     */
    private Optional<PanelReview> callReviewerViaText(ModelHandle handle, String prompt,
                                                      String reviewerId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String attemptPrompt = attempt == 0 ? prompt : prompt + REPAIR_SUFFIX;
            String raw;
            try {
                raw = handle.pool().callSyncOnModel(handle.modelId(), attemptPrompt);
            } catch (RuntimeException e) {
                log.warn("ReviewPanelService: reviewer={} attempt={} call failed: {}",
                        reviewerId, attempt, e.getMessage());
                return Optional.<PanelReview>empty();
            }
            Optional<PanelReview> parsed = PanelReview.parse(raw, reviewerId);
            if (parsed.isPresent()) return parsed;
            log.info("ReviewPanelService: reviewer={} attempt={} parse failed, {}",
                    reviewerId, attempt,
                    attempt == 0 ? "retrying with repair prompt" : "giving up");
        }
        return Optional.<PanelReview>empty();
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
