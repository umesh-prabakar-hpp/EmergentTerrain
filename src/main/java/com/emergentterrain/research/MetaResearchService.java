package com.emergentterrain.research;

import com.emergentterrain.repo.PaperCitationRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 4 Iter 10 — the recursive layer: the system observes itself.
 *
 * Three slicings of accumulated research history:
 *   - by callPath (which prompt/audit path produced the paper)
 *   - by bot     (which author produced it; calibration + hallucination rate)
 *   - by method  (which BotGenome.methodology category)
 *
 * All read-only views over data the rest of the system already accumulates
 * ({@link ResearchPaper}, {@link PaperCitation}, {@link BotCalibration},
 * {@link EpistemicMarketService}). Nothing here writes — Phase 4b will add
 * write-back evolution targets (reviewer routing, funding weights, prompt
 * selection) that read these stats as their input signal.
 *
 * Gated by {@code app.research.meta.enabled} (default false). When the flag
 * is off, every method returns an empty stats record so callers can ignore
 * the result without needing to check the flag themselves.
 *
 * Meta-papers (where the meta-service writes its findings) are marked with
 * {@code paper.kind = META} so domain-paper consumers can filter them out.
 * Iter 10 doesn't yet produce meta-papers — that's a Phase 4b follow-up.
 */
@Service
public class MetaResearchService {

    private static final Logger log = LoggerFactory.getLogger(MetaResearchService.class);

    /** Per-prompt/path performance — mean replication rate, calibration, surprise, citations. */
    public record PromptPathStats(
            String  callPath,
            int     paperCount,
            double  meanReplicationRate,
            double  meanCalibration,
            double  meanSurprise,
            double  meanCitationCount) {

        public static PromptPathStats empty(String callPath) {
            return new PromptPathStats(callPath, 0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** Per-bot meta stats — hallucination rate, calibration, mentoring effect. */
    public record BotMetaStats(
            UUID    botId,
            int     paperCount,
            double  hallucinationRate,
            double  calibrationScore,
            double  meanCitationCount) {

        public static BotMetaStats empty(UUID botId) {
            return new BotMetaStats(botId, 0, 0.0, 0.5, 0.0);
        }
    }

    /** Per-methodology stats — long-term stability and citation impact. */
    public record MethodMetaStats(
            String  method,
            int     paperCount,
            double  meanLongTermStability,
            double  meanCitationCount) {

        public static MethodMetaStats empty(String method) {
            return new MethodMetaStats(method, 0, 0.0, 0.0);
        }
    }

    private final boolean                   enabled;
    private final ResearchPaperRepository   papers;
    private final PaperCitationRepository   citations;
    private final EpistemicMarketService    epistemicMarketService;

    public MetaResearchService(
            @Value("${app.research.meta.enabled:false}") boolean enabled,
            ResearchPaperRepository papers,
            PaperCitationRepository citations,
            EpistemicMarketService epistemicMarketService) {
        this.enabled                = enabled;
        this.papers                 = papers;
        this.citations              = citations;
        this.epistemicMarketService = epistemicMarketService;
        if (enabled) {
            log.info("MetaResearchService: ENABLED — analytics over per-bot, per-path, "
                    + "per-method history are available. Write-back evolution targets "
                    + "(reviewer routing, funding weights, prompt selection) follow in Phase 4b.");
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Aggregate stats for a specific LLM call path. Counts papers with that path,
     * computes the share that ended up DISPUTED (replicationRate's complement),
     * and averages downstream citation counts.
     *
     * Returns an empty record when the flag is off or no papers used this path.
     */
    public PromptPathStats statsForPath(String callPath) {
        if (!enabled || callPath == null) return PromptPathStats.empty(callPath);
        List<ResearchPaper> ps = papers.findByLlmCallPath(callPath).stream()
                .filter(p -> p.kind() == PaperKind.DOMAIN)
                .toList();
        if (ps.isEmpty()) return PromptPathStats.empty(callPath);

        int count = ps.size();
        long disputed = ps.stream().filter(p -> "DISPUTED".equals(p.status())).count();
        double meanReplication = 1.0 - ((double) disputed / count);
        double meanSurprise    = ps.stream().mapToDouble(ResearchPaper::anomalyZScore)
                                    .map(Math::abs).average().orElse(0.0);
        double meanCitations   = meanCitationCountOf(ps);

        // Calibration is per-bot, so for a path we use the mean across the path's authors.
        double meanCalibration = ps.stream()
                .map(ResearchPaper::authorBotId)
                .map(MetaResearchService::parseUuidOrNull)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(epistemicMarketService::calibrationScore)
                .average().orElse(0.5);

        return new PromptPathStats(callPath, count, meanReplication, meanCalibration,
                meanSurprise, meanCitations);
    }

    /**
     * Per-bot stats. hallucinationRate counts how often the bot's papers were
     * marked DISPUTED via replication failure (Phase 0 Task 0.1's pipeline);
     * calibrationScore is read from {@link EpistemicMarketService}.
     */
    public BotMetaStats statsForBot(UUID botId) {
        if (!enabled || botId == null) return BotMetaStats.empty(botId);
        List<ResearchPaper> ps = papers.findByAuthorBotId(botId.toString()).stream()
                .filter(p -> p.kind() == PaperKind.DOMAIN)
                .toList();
        if (ps.isEmpty()) {
            return new BotMetaStats(botId, 0, 0.0,
                    epistemicMarketService.calibrationScore(botId), 0.0);
        }

        int count = ps.size();
        long disputed = ps.stream().filter(p -> "DISPUTED".equals(p.status())).count();
        double hallucinationRate = (double) disputed / count;
        double calibration       = epistemicMarketService.calibrationScore(botId);
        double meanCitations     = meanCitationCountOf(ps);

        return new BotMetaStats(botId, count, hallucinationRate, calibration, meanCitations);
    }

    /**
     * Per-methodology stats. meanLongTermStability is the share of papers in this
     * methodology that did NOT end up DISPUTED — a rough proxy for whether the
     * methodology produces findings that hold up under replication.
     */
    public MethodMetaStats statsForMethod(String methodology) {
        if (!enabled || methodology == null) return MethodMetaStats.empty(methodology);
        List<ResearchPaper> ps = papers.findByMethodology(methodology).stream()
                .filter(p -> p.kind() == PaperKind.DOMAIN)
                .toList();
        if (ps.isEmpty()) return MethodMetaStats.empty(methodology);

        int count = ps.size();
        long stable = ps.stream().filter(p -> !"DISPUTED".equals(p.status())).count();
        double meanStability = (double) stable / count;
        double meanCitations = meanCitationCountOf(ps);

        return new MethodMetaStats(methodology, count, meanStability, meanCitations);
    }

    // ---- internal ----

    private double meanCitationCountOf(List<ResearchPaper> ps) {
        if (ps.isEmpty()) return 0.0;
        return ps.stream()
                .mapToLong(p -> citations.countByCitedPaperId(p.paperId()))
                .average().orElse(0.0);
    }

    private static UUID parseUuidOrNull(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}
