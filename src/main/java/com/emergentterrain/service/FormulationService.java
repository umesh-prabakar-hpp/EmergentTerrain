package com.emergentterrain.service;

import com.emergentterrain.research.BotGenome;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.UniversityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final Semaphore formulationSlot = new Semaphore(1);

    public FormulationService(LlmRouter router, ObjectMapper objectMapper) {
        this.router       = router;
        this.objectMapper = objectMapper;
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
                return formulateResearch(domain, topicName, genome, papers, hypothesis);
            });
            log.info("FormulationService: formulation callback wired to UniversityService");
        }
    }

    public FormulationResult formulateResearch(String domain, String topicName,
                                               BotGenome genome, List<ResearchPaper> priorPapers,
                                               String researchHypothesis) {
        if (!formulationSlot.tryAcquire()) {
            log.debug("LLM formulation slot busy — falling back to genome sampling");
            return null;
        }
        long start = System.currentTimeMillis();
        try {
            NoveltyAssessment novelty = checkNovelty(domain, topicName, priorPapers);
            String prompt = buildFormulationPrompt(domain, topicName, genome, priorPapers, researchHypothesis, novelty);
            String response = router.callFast(prompt);
            log.info("LLM formulation done domain={} topic={} novelty={} ms={}", domain, topicName,
                    String.format("%.2f", novelty.score()), System.currentTimeMillis() - start);
            return parseFormulationResult(response);
        } catch (Exception e) {
            log.warn("LLM formulation failed domain={} topic={} ms={}: {}", domain, topicName,
                    System.currentTimeMillis() - start, e.getMessage());
            return null;
        } finally {
            formulationSlot.release();
        }
    }

    private String buildFormulationPrompt(String domain, String topicName,
                                          BotGenome genome, List<ResearchPaper> priorPapers,
                                          String researchHypothesis, NoveltyAssessment novelty) {
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

        return """
                You are a research advisor choosing where a computational researcher should focus next.

                Domain: %s | Topic: %s
                Researcher's learned search region: param1 center=%d (±%d), param2 center=%.1f (±%.1f)
                param1 is an integer 10–99 (controls the starting position of the search space).
                param2 is a decimal 1.0–9.0 (controls depth/scale of the search).
                %s%s
                Prior papers in this topic:
                %s%s
                Based on the prior work, choose a specific unexplored or promising parameter region
                within or near the researcher's learned search region. Explain the research question
                this choice targets in one sentence.

                Before any computation runs, pre-register your hypothesis. This is locked and \
                cannot be revised once results are seen.

                Reply with ONLY this JSON, no other text:
                {"param1":N,"param2":X.X,"question":"one sentence research question",\
                "pre_registration":{"hypothesis":"one testable claim this parameter choice will investigate",\
                "prediction":"expected direction or magnitude (e.g. monotone increase, value near 0.3)",\
                "falsification_condition":"what outcome would contradict this (e.g. no correlation, 0 exceedance)",\
                "mapping_notes":"if crossing domains: state any constants or analogies and their justification; empty string otherwise"}}

                param1 must be an integer 10–99. param2 must be a decimal 1.0–9.0.
                Choose values that differ from or extend what prior papers have already explored.
                """.formatted(domain, topicName, center1, radius1, center2, radius2,
                        hypothesisSection, noveltyContext, priorSection.trim(), anomalyContext);
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
        if (count < 3) return new NoveltyAssessment(1.0, "", "");
        try {
            long start = System.currentTimeMillis();
            String response = router.callFast(buildNoveltyPrompt(domain, topicName, papers, count));
            NoveltyAssessment result = parseNoveltyAssessment(response);
            log.info("LLM novelty check done domain={} topic={} score={} saturated={} ms={}",
                    domain, topicName, String.format("%.2f", result.score()),
                    result.isSaturated(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.warn("LLM novelty check failed domain={} topic={}: {}", domain, topicName, e.getMessage());
            return new NoveltyAssessment(1.0, "", "");
        }
    }

    private static String buildNoveltyPrompt(String domain, String topicName,
                                              List<ResearchPaper> papers, long totalCount) {
        StringBuilder recent = new StringBuilder();
        papers.stream()
                .filter(p -> p.domain().equalsIgnoreCase(domain) && p.topic().equalsIgnoreCase(topicName))
                .limit(8)
                .forEach(p -> {
                    String snippet = p.findings().length() > 100
                            ? p.findings().substring(0, 100) + "…" : p.findings();
                    recent.append("  %s [score %.2f]: %s\n".formatted(p.paperId(), p.peerScore(), snippet));
                });
        return """
                You are assessing how much computational novelty remains in a research topic.

                Domain: %s | Topic: %s
                Total papers published in this topic so far: %d

                Most recent papers (findings summaries):
                %s
                Rate how much unexplored territory remains: 1.0 = completely fresh, 0.0 = fully saturated.
                Note which parameter regions or angles have NOT been tried yet.

                Reply with ONLY this JSON — no other text:
                {"novelty_score":0.7,"coverage_note":"brief note on what has been covered","unexplored_hint":"specific suggestion for an unexplored region or angle; empty string if no clear gap"}
                """.formatted(domain, topicName, totalCount, recent.toString().trim());
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
