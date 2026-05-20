package com.emergentterrain.service;

import com.emergentterrain.research.BotGenome;
import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.LiteratureReference;
import com.emergentterrain.research.LiteratureSearchService;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.PromptTemplateIds;
import com.emergentterrain.research.PromptTemplateRegistry;
import com.emergentterrain.research.ResearchIdentity;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.UniversityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Synchronous pre-computation step. Carved out of LlmService in Phase 0 Task 0.2.
 *
 * Bots ask FormulationService for the next param1/param2 plus a pre-registered
 * hypothesis before running MathPhysicsEngine.compute. The service runs a
 * novelty pre-check on the LLM, then a formulation prompt that locks the
 * hypothesis before any results are seen — this is the post-hoc-rationalization
 * gate that the research pipeline relies on.
 *
 * Concurrency: a dedicated formulationSlot (1 permit) so formulation cannot
 * starve narration. If the slot is busy, formulateResearch returns null so
 * the caller falls back to genome sampling.
 */
@Service
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true", matchIfMissing = false)
public class FormulationService {

    private static final Logger log = LoggerFactory.getLogger(FormulationService.class);

    public record FormulationResult(int param1, double param2, String researchQuestion,
                                    PreRegistration preRegistration) {}

    /** Novelty assessment for a domain+topic before formulation. score=1.0 means fully fresh. */
    record NoveltyAssessment(double score, String coverageNote, String unexploredHint) {
        boolean isSaturated() { return score < 0.30; }
    }

    private final LlmRouter router;
    private final ObjectMapper objectMapper;
    private final PromptTemplateRegistry promptRegistry;
    private final long intraRoomNoveltyWindowMinutes;
    private final LiteratureSearchService literatureSearch;
    private final boolean literatureNoveltyCapEnabled;
    private final double literatureNoveltyCapScore;
    private final Semaphore formulationSlot = new Semaphore(1);

    /**
     * Phase 16 P6b — when on, the formulation prompt injects a "Strong-
     * confidence prior" hint when exactly one PUBLISHED prior with
     * {@code peerScore >= priorResultMinScore} exists for the (domain, topic)
     * pair. The hint asks the bot to either replicate, refute-with-citation,
     * or shift sub-question so a same-topic re-attempt doesn't silently
     * contradict (or silently re-fabricate) the prior. P-a328-541 inverted
     * P-a328-516's correct Reuss V_f=1 result without acknowledging it;
     * P-a328-539 re-fabricated P-a328-534's rejected convergence claim.
     *
     * <p>Default off so the rollout matches the discipline used for other
     * phase-16 prompt-stage flags — flip on after one corpus refresh
     * exercises P6a's verdict-tag behaviour first.
     */
    @Value("${app.research.prior-result-injection.enabled:false}")
    private boolean priorResultInjectionEnabled;

    /**
     * Phase 16 P6b — peer-score floor for a prior to qualify as
     * "math-verified" for the prior-result hint. The corpus baseline (per
     * REVIEW_REPORT refresh #3) puts P-a328-516 at 0.535 and P-a328-552 at
     * 0.723, both math-correct. The 0.50 floor is conservative — it admits
     * P-516 and excludes the lower-quality cluster (0.30s and below).
     */
    @Value("${app.research.prior-result-injection.min-score:0.5}")
    private double priorResultMinScore;

    public FormulationService(LlmRouter router, ObjectMapper objectMapper,
                              PromptTemplateRegistry promptRegistry,
                              @Value("${app.research.intra-room-novelty-window-minutes:30}")
                              long intraRoomNoveltyWindowMinutes) {
        this(router, objectMapper, promptRegistry, intraRoomNoveltyWindowMinutes,
             null, false, 0.4);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FormulationService(LlmRouter router, ObjectMapper objectMapper,
                              PromptTemplateRegistry promptRegistry,
                              @Value("${app.research.intra-room-novelty-window-minutes:30}")
                              long intraRoomNoveltyWindowMinutes,
                              @org.springframework.beans.factory.annotation.Autowired(required = false)
                              LiteratureSearchService literatureSearch,
                              @Value("${app.research.literature-novelty-cap.enabled:true}")
                              boolean literatureNoveltyCapEnabled,
                              @Value("${app.research.literature-novelty-cap.score:0.4}")
                              double literatureNoveltyCapScore) {
        this.router                        = router;
        this.objectMapper                  = objectMapper;
        this.promptRegistry                = promptRegistry;
        this.intraRoomNoveltyWindowMinutes = intraRoomNoveltyWindowMinutes;
        this.literatureSearch              = literatureSearch;
        this.literatureNoveltyCapEnabled   = literatureNoveltyCapEnabled;
        this.literatureNoveltyCapScore     = literatureNoveltyCapScore;
        if (literatureNoveltyCapEnabled && literatureSearch == null) {
            log.warn("FormulationService: app.research.literature-novelty-cap.enabled=true "
                    + "but no LiteratureSearchService bean on the context; degrading to off");
        }
    }

    /** Wire the formulation callback into UniversityService once the context is ready. */
    @EventListener(ContextRefreshedEvent.class)
    @ConditionalOnBean(UniversityService.class)
    public void wireUniversityCallback(ContextRefreshedEvent event) {
        UniversityService university = event.getApplicationContext()
                .getBeanProvider(UniversityService.class).getIfAvailable();
        if (university != null) {
            university.setFormulationCallback(args -> {
                String domain         = (String) args[0];
                String topicName      = (String) args[1];
                BotGenome genome      = (BotGenome) args[2];
                @SuppressWarnings("unchecked")
                List<ResearchPaper> papers = (List<ResearchPaper>) args[3];
                String hypothesis     = (args.length > 4) ? (String) args[4] : null;
                BotLifecycleState ls  = (args.length > 5 && args[5] instanceof BotLifecycleState s) ? s : null;
                return formulateResearch(domain, topicName, genome, papers, hypothesis, ls);
            });
            log.info("FormulationService: formulation callback wired to UniversityService");
        }
    }

    /** Backwards-compatible overload: callers without a lifecycle state fall back to no identity bias. */
    public FormulationResult formulateResearch(String domain, String topicName,
                                               BotGenome genome, List<ResearchPaper> priorPapers,
                                               String researchHypothesis) {
        return formulateResearch(domain, topicName, genome, priorPapers, researchHypothesis, null);
    }

    public FormulationResult formulateResearch(String domain, String topicName,
                                               BotGenome genome, List<ResearchPaper> priorPapers,
                                               String researchHypothesis,
                                               BotLifecycleState lifecycleState) {
        if (!formulationSlot.tryAcquire()) {
            log.debug("LLM formulation slot busy — falling back to genome sampling");
            return null;
        }
        long start = System.currentTimeMillis();
        try {
            NoveltyAssessment novelty = checkNovelty(domain, topicName, priorPapers);
            ResearchIdentity identity = (lifecycleState != null)
                    ? ResearchIdentity.from(lifecycleState, null, null)
                    : null;
            String prompt = buildFormulationPrompt(domain, topicName, genome, priorPapers,
                    researchHypothesis, novelty, identity);
            String response = router.callFast(prompt);
            FormulationResult result = parseFormulationResult(response);
            log.info("LLM formulation done domain={} topic={} novelty={} ms={}", domain, topicName,
                    String.format("%.2f", novelty.score()), System.currentTimeMillis() - start);
            // Phase 21 §21.4 — premise preflight. Validates the pre-
            // registered hypothesis against topic-keyed rules (TIR n₁>n₂,
            // twin-prime density qualification) before the agentic
            // professor stage runs. On REJECT, retry the formulation
            // ONCE with the validation error fed back into the prompt;
            // on a second REJECT, abandon the paper. Targets the
            // P-a328-693 failure mode (TIR with n₂>n₁ → wasted call →
            // fabricated numbers).
            result = retryOnPremiseRejection(domain, topicName, prompt, result);
            return result;
        } catch (Exception e) {
            log.warn("LLM formulation failed domain={} topic={} ms={}: {}", domain, topicName,
                    System.currentTimeMillis() - start, e.getMessage());
            return null;
        } finally {
            formulationSlot.release();
        }
    }

    /**
     * Phase 21 §21.4 — premise-preflight retry loop. Returns the input
     * unchanged when it has no pre-registration to validate or when the
     * validator says OK. On REJECT, issues a single retry against
     * {@link LlmRouter#callFast} with the validator's explanation
     * appended to the prompt; if the retry also rejects, returns null
     * (paper abandoned) and stamps a WARN with the reason code so the
     * operational-logs section of REVIEW_REPORT can track adoption.
     */
    private FormulationResult retryOnPremiseRejection(String domain, String topicName,
                                                       String originalPrompt,
                                                       FormulationResult firstAttempt) {
        if (firstAttempt == null) return null;
        PreRegistration preReg = firstAttempt.preRegistration();
        if (preReg == null) return firstAttempt;
        com.emergentterrain.research.PremiseValidator.Result first =
                com.emergentterrain.research.PremiseValidator.validate(topicName, preReg.hypothesis());
        if (first instanceof com.emergentterrain.research.PremiseValidator.Result.Ok) {
            return firstAttempt;
        }
        var firstReject = (com.emergentterrain.research.PremiseValidator.Result.Reject) first;
        log.warn("premise:rejected:{} domain={} topic={} — first attempt; retrying once. hypothesis=\"{}\"",
                firstReject.reasonCode(), domain, topicName,
                truncate(preReg.hypothesis(), 200));
        String retryPrompt = originalPrompt
                + "\n\n[PRE-REGISTRATION REJECTED — premise-preflight " + firstReject.reasonCode() + "]\n"
                + firstReject.explanation()
                + "\n\nRewrite the pre_registration.hypothesis above so the premise is "
                + "physically/mathematically coherent, then re-emit the SAME JSON shape "
                + "(param1, param2, question, pre_registration{...}).";
        String retryResponse;
        try {
            retryResponse = router.callFast(retryPrompt);
        } catch (Exception e) {
            log.warn("premise:retry_failed domain={} topic={} reason={}: {}",
                    domain, topicName, firstReject.reasonCode(), e.getMessage());
            return null;
        }
        FormulationResult retried = parseFormulationResult(retryResponse);
        if (retried == null || retried.preRegistration() == null) {
            log.warn("premise:retry_unparseable:{} domain={} topic={} — abandoning paper",
                    firstReject.reasonCode(), domain, topicName);
            return null;
        }
        com.emergentterrain.research.PremiseValidator.Result second =
                com.emergentterrain.research.PremiseValidator.validate(
                        topicName, retried.preRegistration().hypothesis());
        if (second instanceof com.emergentterrain.research.PremiseValidator.Result.Ok) {
            log.info("premise:retry_recovered:{} domain={} topic={} — second attempt OK",
                    firstReject.reasonCode(), domain, topicName);
            return retried;
        }
        var secondReject = (com.emergentterrain.research.PremiseValidator.Result.Reject) second;
        log.warn("premise:rejected:{} domain={} topic={} — second attempt also rejected; abandoning paper",
                secondReject.reasonCode(), domain, topicName);
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String buildFormulationPrompt(String domain, String topicName,
                                          BotGenome genome, List<ResearchPaper> priorPapers,
                                          String researchHypothesis, NoveltyAssessment novelty,
                                          ResearchIdentity identity) {
        int center1 = genome.getParamCenter1();
        double center2 = genome.getParamCenter2();
        int radius1 = genome.getParamRadius1();
        double radius2 = genome.getParamRadius2();

        StringBuilder priorWork = new StringBuilder();
        priorPapers.stream()
                .filter(p -> p.domain().equalsIgnoreCase(domain)
                          && p.topic().equalsIgnoreCase(topicName))
                .limit(4)
                .forEach(p -> priorWork.append("  %s [score %.2f]: %s\n"
                        .formatted(p.paperId(), p.peerScore(),
                                p.findings().length() > 120
                                        ? p.findings().substring(0, 120) + "…"
                                        : p.findings())));
        String priorSection = priorWork.isEmpty()
                ? "  (no prior papers in this topic yet — choose a fresh starting region)\n"
                : priorWork.toString();

        String hypothesisSection = (researchHypothesis != null && !researchHypothesis.isBlank())
                ? "Researcher's current research direction: " + researchHypothesis + "\n"
                : "";

        StringBuilder domainAnomalies = new StringBuilder();
        priorPapers.stream()
                .filter(p -> p.domain().equalsIgnoreCase(domain)
                          && !p.topic().equalsIgnoreCase(topicName)
                          && Math.abs(p.anomalyZScore()) >= 1.5)
                .sorted((a, b) -> Double.compare(Math.abs(b.anomalyZScore()), Math.abs(a.anomalyZScore())))
                .limit(3)
                .forEach(p -> {
                    String snippet = p.findings().length() > 100
                            ? p.findings().substring(0, 100) + "…" : p.findings();
                    domainAnomalies.append("  %s / %s [z=%+.1f score=%.2f]: %s\n"
                            .formatted(p.paperId(), p.topic(),
                                    p.anomalyZScore(), p.peerScore(), snippet));
                });
        String anomalyContext = domainAnomalies.isEmpty() ? ""
                : "Anomalous findings in adjacent topics (same domain) — consider whether similar "
                  + "parameter regions might be interesting in this topic too:\n"
                  + domainAnomalies + "\n";

        String noveltyContext = "";
        if (novelty.isSaturated()) {
            noveltyContext = "NOVELTY WARNING (score=%.2f/1.0): This topic is becoming saturated. "
                    .formatted(novelty.score())
                    + (novelty.coverageNote().isBlank() ? "" : "Covered so far: " + novelty.coverageNote() + ". ")
                    + (novelty.unexploredHint().isBlank()
                        ? "Choose parameters that differ meaningfully from all prior work above.\n"
                        : "Unexplored angle: " + novelty.unexploredHint() + " Prioritise this gap.\n");
        } else if (novelty.score() < 0.60 && !novelty.unexploredHint().isBlank()) {
            noveltyContext = "NOVELTY NOTE (score=%.2f/1.0): Suggested unexplored angle: %s\n"
                    .formatted(novelty.score(), novelty.unexploredHint());
        }

        String identitySection = identityHint(identity);

        // Phase 16 P6b — when exactly one math-verified PUBLISHED prior
        // exists on this (domain, topic), inject a "Strong-confidence prior"
        // hint asking the bot to replicate, refute-with-citation, or shift
        // sub-question. Empty string when the flag is off, when zero
        // qualifying priors exist, or when ≥2 exist (the scoping doc's
        // single-prior restriction). Sequenced AFTER identity / hypothesis /
        // novelty so it lands closest to the priorPapers block where the
        // bot will see it during prompt scanning.
        String priorResultHint = priorResultInjectionEnabled
                ? buildPriorResultHint(domain, topicName, priorPapers, priorResultMinScore)
                : "";

        // The three optional context blocks (identity / hypothesis / novelty) are
        // pre-carve concatenated by the positional "%s%s%s" pattern; the new
        // template uses a single composite slot {{ optionalContext }} so the
        // renderer stays free of conditional logic. priorSection is trimmed
        // before concatenation with anomalyContext into the {{ priorPapers }}
        // + {{ anomalyContext }} pair, matching pre-carve byte-for-byte.
        String optionalContext = identitySection + hypothesisSection + noveltyContext + priorResultHint;
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("domain",          domain);
        params.put("topicName",       topicName);
        params.put("center1",         String.valueOf(center1));
        params.put("radius1",         String.valueOf(radius1));
        params.put("center2",         String.format("%.1f", center2));
        params.put("radius2",         String.format("%.1f", radius2));
        params.put("optionalContext", optionalContext);
        params.put("priorPapers",     priorSection.trim());
        params.put("anomalyContext",  anomalyContext);
        return promptRegistry.render(PromptTemplateIds.FORMULATION_MAIN_ID, params);
    }

    /**
     * Phase 16 P6b — render the prior-result hint for the formulation prompt.
     *
     * <p>Selects the highest-peer-score PUBLISHED prior on the (domain,
     * topic) pair whose peer score is at or above
     * {@link #priorResultMinScore}. When *exactly one* such prior exists,
     * inject the hint. When zero exist (fresh topic or only-low-score
     * priors), return empty — the existing priorPapers block is the only
     * context the bot needs. When two or more exist (disagreeing priors
     * possible), return empty too — the scoping doc's first-iteration
     * restriction. A future P6b.2 may add disagreement detection to handle
     * the multi-prior case explicitly.
     *
     * <p>Static + pure: takes the min-score as a parameter so unit tests
     * can exercise selection + formatting without spinning up the full LLM
     * router or constructing a FormulationService instance.
     */
    static String buildPriorResultHint(String domain, String topicName,
                                       List<ResearchPaper> priorPapers,
                                       double minScore) {
        if (priorPapers == null || priorPapers.isEmpty()) return "";
        List<ResearchPaper> qualifying = priorPapers.stream()
                .filter(p -> p.domain() != null && p.domain().equalsIgnoreCase(domain))
                .filter(p -> p.topic() != null && p.topic().equalsIgnoreCase(topicName))
                .filter(p -> "PUBLISHED".equalsIgnoreCase(p.status()))
                .filter(p -> p.peerScore() >= minScore)
                .sorted((a, b) -> Double.compare(b.peerScore(), a.peerScore()))
                .toList();
        if (qualifying.size() != 1) return "";  // 0 or 2+ → skip injection
        ResearchPaper best = qualifying.get(0);
        String conclusionSummary = firstSentence(best.conclusion(), 200);
        if (conclusionSummary.isBlank()) {
            // Fall back to a short slice of findings if the conclusion is
            // empty / non-sentential.
            conclusionSummary = firstSentence(best.findings(), 200);
        }
        if (conclusionSummary.isBlank()) return "";
        return String.format(
                "STRONG-CONFIDENCE PRIOR: %s (score %.2f) established that %s "
                        + "Choose one path: (1) replicate at materially different parameters and "
                        + "confirm or extend the trend, (2) refute with new evidence and explicitly "
                        + "cite the contradiction in your hypothesis, (3) shift to a different "
                        + "sub-question of this topic. Do NOT implicitly contradict the prior "
                        + "without acknowledging it.%n",
                best.paperId(), best.peerScore(), conclusionSummary);
    }

    /**
     * Phase 16 P6b — extract the first sentence (terminated by `.`, `!`,
     * or `?`) from {@code text}, truncated to {@code maxLen} chars. Returns
     * the empty string when {@code text} is null, blank, or contains no
     * sentence terminator within the cap. Trailing whitespace is trimmed
     * but the terminator is preserved so the hint reads naturally.
     */
    static String firstSentence(String text, int maxLen) {
        if (text == null || text.isBlank()) return "";
        String trimmed = text.trim();
        int n = Math.min(trimmed.length(), maxLen);
        for (int i = 0; i < n; i++) {
            char c = trimmed.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                return trimmed.substring(0, i + 1).trim();
            }
        }
        // No terminator within the cap — return the capped prefix with an
        // ellipsis so the hint still ends cleanly.
        if (trimmed.length() > maxLen) {
            return trimmed.substring(0, maxLen).trim() + "…";
        }
        // Whole text < maxLen but no terminator — append a period so the
        // injected sentence reads as a complete clause in the hint.
        return trimmed + ".";
    }

    /**
     * Phase 1 Iter 2 wiring: render a short "Researcher style" preamble derived from
     * {@link ResearchIdentity}. Returns "" when identity is null so callers via the
     * legacy callback signature get the original prompt unchanged. The skepticism /
     * risk-tolerance guidance biases hypothesis framing without dictating params.
     */
    static String identityHint(ResearchIdentity identity) {
        if (identity == null) return "";
        String skepticismGuidance = identity.skepticismLevel() > 0.65
                ? "Frame the hypothesis as a sharply falsifiable claim with a precise quantitative prediction."
                : identity.skepticismLevel() < 0.35
                        ? "Frame the hypothesis as exploratory: broad qualitative directions are acceptable."
                        : "Frame the hypothesis as a moderately specific testable claim.";
        String riskGuidance = identity.riskTolerance() >= 0.30
                ? "Bias param choices toward the edge of the learned search radius — bolder bets are encouraged."
                : "Bias param choices closer to the learned search center — conservative steps are preferred.";
        return ("Researcher style — methodology=%s, risk=%.2f, skepticism=%.2f, reputation=%.2f "
                + "(career: %d published / %d rejected).\n%s %s\n")
                .formatted(identity.favoredMethod(),
                        identity.riskTolerance(),
                        identity.skepticismLevel(),
                        identity.reputation(),
                        identity.publishedCount(),
                        identity.rejectedCount(),
                        skepticismGuidance,
                        riskGuidance);
    }

    public FormulationResult parseFormulationResult(String response) {
        return parseFormulationResult(objectMapper, response);
    }

    public static FormulationResult parseFormulationResult(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank()) return null;
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode root = mapper.readTree(response.substring(start, end + 1));
            int    p1 = root.path("param1").asInt(-1);
            double p2 = root.path("param2").asDouble(-1.0);
            String q  = root.path("question").asText(null);
            if (p1 < 10 || p1 > 99 || p2 < 1.0 || p2 > 9.0) {
                return null;
            }
            PreRegistration preReg = null;
            JsonNode prNode = root.get("pre_registration");
            if (prNode != null && !prNode.isNull()) {
                String hyp   = prNode.path("hypothesis").asText(null);
                String pred  = prNode.path("prediction").asText(null);
                String falsc = prNode.path("falsification_condition").asText(null);
                String mapN  = prNode.path("mapping_notes").asText(null);
                if (hyp != null && !hyp.isBlank() && pred != null && !pred.isBlank()) {
                    preReg = new PreRegistration(hyp, pred, falsc, mapN);
                }
            }
            return new FormulationResult(p1, p2, q, preReg);
        } catch (Exception e) {
            log.warn("LLM formulation: JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    private NoveltyAssessment checkNovelty(String domain, String topicName, List<ResearchPaper> papers) {
        long count = papers.stream()
                .filter(p -> p.domain().equalsIgnoreCase(domain) && p.topic().equalsIgnoreCase(topicName))
                .count();
        NoveltyAssessment result;
        if (count < 3) {
            result = new NoveltyAssessment(1.0, "", "");
        } else {
            try {
                long start = System.currentTimeMillis();
                String response = router.callFast(buildNoveltyPrompt(domain, topicName, papers, count));
                result = parseNoveltyAssessment(response);
                log.info("LLM novelty check done domain={} topic={} score={} saturated={} ms={}",
                        domain, topicName, String.format("%.2f", result.score()),
                        result.isSaturated(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.warn("LLM novelty check failed domain={} topic={}: {}", domain, topicName, e.getMessage());
                result = new NoveltyAssessment(1.0, "", "");
            }
        }
        return applyLiteratureCap(
                applyIntraRoomSiblingPenalty(result, domain, topicName, papers),
                domain, topicName);
    }

    /**
     * Phase 6 D1.4 — when the curated literature corpus carries a non-sentinel
     * reference for {@code (domain, topic)}, the topic has a textbook closed-form
     * the bot would at best replicate. Cap novelty at {@link #literatureNoveltyCapScore}
     * (default 0.4) so the saturated-topic prompt branch fires and the planner
     * either reroutes or the bot frames the paper as an explicit replication.
     * Sentinel entries (engine-constructed bridges) are skipped — those are
     * the cross-domain gate's responsibility, not novelty's.
     */
    NoveltyAssessment applyLiteratureCap(NoveltyAssessment in, String domain, String topic) {
        if (!literatureNoveltyCapEnabled || literatureSearch == null) return in;
        List<LiteratureReference> refs = literatureSearch.findFor(domain, topic, 1);
        if (refs.isEmpty()) return in;
        LiteratureReference top = refs.get(0);
        if (top.isEngineConstructed()) return in;
        if (in.score() <= literatureNoveltyCapScore) return in;
        String marker = "literature-known: " + abbreviate(top.citation(), 60);
        String note = (in.coverageNote() == null || in.coverageNote().isBlank())
                ? marker : in.coverageNote() + "; " + marker;
        log.info("LLM novelty literature-cap applied domain={} topic={} priorScore={} cappedScore={} citation={}",
                domain, topic, String.format("%.2f", in.score()),
                String.format("%.2f", literatureNoveltyCapScore), top.citation());
        return new NoveltyAssessment(literatureNoveltyCapScore, note, in.unexploredHint());
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * Phase 7 G9 — dock novelty by 0.5 when a same-{@code (domain, topic)} paper
     * was published within {@code intraRoomNoveltyWindowMinutes}. Stops parallel
     * formulation runs from each scoring as high-novelty because neither sibling
     * was visible to the other at formulation time (P-260 / P-265 Goldbach
     * cluster in refresh #6 published within 8 min of each other). Disabled
     * by setting the window to 0.
     */
    NoveltyAssessment applyIntraRoomSiblingPenalty(
            NoveltyAssessment in, String domain, String topicName, List<ResearchPaper> papers) {
        if (intraRoomNoveltyWindowMinutes <= 0) return in;
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(intraRoomNoveltyWindowMinutes));
        boolean hasRecentSibling = papers.stream()
                .filter(p -> p.domain().equalsIgnoreCase(domain) && p.topic().equalsIgnoreCase(topicName))
                .anyMatch(p -> p.publishedAt() != null && p.publishedAt().isAfter(cutoff));
        if (!hasRecentSibling) return in;
        double dockedScore = Math.max(0.0, in.score() - 0.5);
        String marker = "intra-room sibling within " + intraRoomNoveltyWindowMinutes + "min";
        String note = (in.coverageNote() == null || in.coverageNote().isBlank())
                ? marker : in.coverageNote() + "; " + marker;
        log.info("LLM novelty intra-room sibling penalty applied domain={} topic={} priorScore={} dockedScore={}",
                domain, topicName, String.format("%.2f", in.score()), String.format("%.2f", dockedScore));
        return new NoveltyAssessment(dockedScore, note, in.unexploredHint());
    }

    private String buildNoveltyPrompt(String domain, String topicName,
                                       List<ResearchPaper> papers, long totalCount) {
        // The "recent papers" pre-format stays here: the renderer is intentionally
        // narrow (no loops, no number formatting), so the caller assembles the
        // multi-line block and the registered template substitutes it as one slot.
        StringBuilder recent = new StringBuilder();
        papers.stream()
                .filter(p -> p.domain().equalsIgnoreCase(domain) && p.topic().equalsIgnoreCase(topicName))
                .limit(8)
                .forEach(p -> {
                    String snippet = p.findings().length() > 100
                            ? p.findings().substring(0, 100) + "…" : p.findings();
                    recent.append("  %s [score %.2f]: %s\n".formatted(p.paperId(), p.peerScore(), snippet));
                });
        return promptRegistry.render(PromptTemplateIds.FORMULATION_NOVELTY_ID, Map.of(
                "domain",       domain,
                "topicName",    topicName,
                "totalCount",   String.valueOf(totalCount),
                "recentPapers", recent.toString().trim()));
    }

    public NoveltyAssessment parseNoveltyAssessment(String response) {
        return parseNoveltyAssessment(objectMapper, response);
    }

    public static NoveltyAssessment parseNoveltyAssessment(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank()) return new NoveltyAssessment(1.0, "", "");
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start) return new NoveltyAssessment(1.0, "", "");
        try {
            JsonNode root = mapper.readTree(response.substring(start, end + 1));
            double score = Math.max(0.0, Math.min(1.0, root.path("novelty_score").asDouble(1.0)));
            String coverageNote   = root.path("coverage_note").asText("");
            String unexploredHint = root.path("unexplored_hint").asText("");
            return new NoveltyAssessment(score, coverageNote, unexploredHint);
        } catch (Exception e) {
            return new NoveltyAssessment(1.0, "", "");
        }
    }
}
