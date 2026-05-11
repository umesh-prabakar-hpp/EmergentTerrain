package com.emergentterrain.research;

import com.emergentterrain.repo.BotCalibrationRepository;
import com.emergentterrain.repo.EpistemicMarketRepository;
import com.emergentterrain.repo.MarketStakeRepository;
import com.emergentterrain.repo.PaperCitationRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for Phase 4 Iter 10 MetaResearchService.
 *
 * The repositories are mocked; the EpistemicMarketService is a real
 * instance over mocked repos so calibrationScore() integrates end-to-end.
 */
class MetaResearchServiceTest {

    private ResearchPaperRepository papers;
    private PaperCitationRepository citations;
    private EpistemicMarketService  markets;

    @BeforeEach
    void setUp() {
        papers    = mock(ResearchPaperRepository.class);
        citations = mock(PaperCitationRepository.class);
        markets   = new EpistemicMarketService(
                false,
                mock(EpistemicMarketRepository.class),
                mock(MarketStakeRepository.class),
                mock(BotCalibrationRepository.class),
                mock(RoomBotRepository.class),
                new ObjectMapper());
    }

    @Test
    void disabledServiceReturnsEmptyStats() {
        MetaResearchService svc = new MetaResearchService(false, papers, citations, markets);

        assertThat(svc.statsForPath("audit:fp:llama").paperCount()).isZero();
        assertThat(svc.statsForBot(UUID.randomUUID()).paperCount()).isZero();
        assertThat(svc.statsForMethod("THEORETICAL").paperCount()).isZero();
    }

    @Test
    void statsForPathAggregatesReplicationAndCitations() {
        MetaResearchService svc = enabled();
        String path = "audit:fp:llama";

        ResearchPaper a = paper("P-1", "MATHEMATICS", "bot-1", "PUBLISHED", 1.0, path);
        ResearchPaper b = paper("P-2", "MATHEMATICS", "bot-2", "DISPUTED",  3.0, path);
        ResearchPaper c = paper("P-3", "MATHEMATICS", "bot-3", "PUBLISHED", 0.5, path);
        when(papers.findByLlmCallPath(eq(path))).thenReturn(List.of(a, b, c));
        when(citations.countByCitedPaperId("P-1")).thenReturn(2L);
        when(citations.countByCitedPaperId("P-2")).thenReturn(0L);
        when(citations.countByCitedPaperId("P-3")).thenReturn(4L);

        MetaResearchService.PromptPathStats stats = svc.statsForPath(path);

        assertThat(stats.paperCount()).isEqualTo(3);
        // 2 out of 3 not disputed → replication rate 2/3
        assertThat(stats.meanReplicationRate()).isCloseTo(2.0 / 3.0, within(1e-9));
        // mean |anomaly z| = (1.0 + 3.0 + 0.5) / 3 = 1.5
        assertThat(stats.meanSurprise()).isCloseTo(1.5, within(1e-9));
        // mean citations = (2 + 0 + 4) / 3 = 2
        assertThat(stats.meanCitationCount()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void statsForPathEmptyWhenNoPapersMatch() {
        MetaResearchService svc = enabled();
        when(papers.findByLlmCallPath(any())).thenReturn(List.of());

        MetaResearchService.PromptPathStats stats = svc.statsForPath("audit:nothing");

        assertThat(stats.paperCount()).isZero();
    }

    @Test
    void statsForPathFiltersOutMetaPapers() {
        MetaResearchService svc = enabled();
        String path = "audit:meta";

        ResearchPaper domainPaper = paper("P-d", "MATHEMATICS", "bot-1", "PUBLISHED", 0.5, path);
        ResearchPaper metaPaper   = paper("P-m", "MATHEMATICS", "bot-2", "PUBLISHED", 0.5, path);
        metaPaper.setKind(PaperKind.META);
        when(papers.findByLlmCallPath(eq(path))).thenReturn(List.of(domainPaper, metaPaper));
        when(citations.countByCitedPaperId(any())).thenReturn(0L);

        assertThat(svc.statsForPath(path).paperCount()).isEqualTo(1);
    }

    @Test
    void statsForBotComputesHallucinationRate() {
        MetaResearchService svc = enabled();
        UUID bot = UUID.randomUUID();

        ResearchPaper a = paper("P-a", "MATHEMATICS", bot.toString(), "PUBLISHED", 0.5, "p1");
        ResearchPaper b = paper("P-b", "MATHEMATICS", bot.toString(), "DISPUTED",  2.5, "p1");
        ResearchPaper c = paper("P-c", "MATHEMATICS", bot.toString(), "DISPUTED",  3.0, "p1");
        ResearchPaper d = paper("P-d", "MATHEMATICS", bot.toString(), "PUBLISHED", 0.4, "p1");
        when(papers.findByAuthorBotId(eq(bot.toString()))).thenReturn(List.of(a, b, c, d));
        when(citations.countByCitedPaperId(any())).thenReturn(0L);

        MetaResearchService.BotMetaStats stats = svc.statsForBot(bot);

        assertThat(stats.paperCount()).isEqualTo(4);
        // 2 of 4 disputed → 50% hallucination rate.
        assertThat(stats.hallucinationRate()).isEqualTo(0.5);
        // No calibration data → default 0.5.
        assertThat(stats.calibrationScore()).isEqualTo(0.5);
    }

    @Test
    void statsForBotEmptyWhenNoPapers() {
        MetaResearchService svc = enabled();
        UUID bot = UUID.randomUUID();
        when(papers.findByAuthorBotId(any())).thenReturn(List.of());

        MetaResearchService.BotMetaStats stats = svc.statsForBot(bot);

        assertThat(stats.paperCount()).isZero();
        assertThat(stats.hallucinationRate()).isZero();
        // Still surfaces the bot's calibration score even with zero papers.
        assertThat(stats.calibrationScore()).isEqualTo(0.5);
    }

    @Test
    void statsForMethodComputesLongTermStability() {
        MetaResearchService svc = enabled();
        String method = "THEORETICAL";

        ResearchPaper a = paper("P-1", "MATHEMATICS", "bot-1", "PUBLISHED", 0.5, "p");
        ResearchPaper b = paper("P-2", "MATHEMATICS", "bot-2", "DISPUTED",  2.0, "p");
        ResearchPaper c = paper("P-3", "MATHEMATICS", "bot-3", "NULL_RESULT", 0.5, "p");
        ResearchPaper d = paper("P-4", "MATHEMATICS", "bot-4", "PUBLISHED", 0.5, "p");
        when(papers.findByMethodology(eq(method))).thenReturn(List.of(a, b, c, d));
        when(citations.countByCitedPaperId(any())).thenReturn(1L);

        MetaResearchService.MethodMetaStats stats = svc.statsForMethod(method);

        assertThat(stats.paperCount()).isEqualTo(4);
        // 3 of 4 not disputed (PUBLISHED + NULL_RESULT + PUBLISHED count as stable).
        assertThat(stats.meanLongTermStability()).isEqualTo(0.75);
        assertThat(stats.meanCitationCount()).isEqualTo(1.0);
    }

    @Test
    void statsForMethodFiltersOutMetaPapers() {
        MetaResearchService svc = enabled();
        ResearchPaper domain = paper("P-1", "MATHEMATICS", "bot-1", "PUBLISHED", 0.5, "p");
        ResearchPaper metaP  = paper("P-2", "MATHEMATICS", "bot-2", "PUBLISHED", 0.5, "p");
        metaP.setKind(PaperKind.META);
        when(papers.findByMethodology(eq("THEORETICAL"))).thenReturn(List.of(domain, metaP));
        when(citations.countByCitedPaperId(any())).thenReturn(0L);

        assertThat(svc.statsForMethod("THEORETICAL").paperCount()).isEqualTo(1);
    }

    @Test
    void researchPaperKindDefaultsToDomain() {
        ResearchPaper p = paper("P-1", "MATHEMATICS", "bot-1", "PUBLISHED", 0.0, "p");
        assertThat(p.kind()).isEqualTo(PaperKind.DOMAIN);
    }

    @Test
    void researchPaperKindCanBeSetToMeta() {
        ResearchPaper p = paper("P-1", "MATHEMATICS", "bot-1", "PUBLISHED", 0.0, "p");
        p.setKind(PaperKind.META);
        assertThat(p.kind()).isEqualTo(PaperKind.META);
        // Null setter clamps back to DOMAIN.
        p.setKind(null);
        assertThat(p.kind()).isEqualTo(PaperKind.DOMAIN);
    }

    // ---- helpers ----

    private MetaResearchService enabled() {
        return new MetaResearchService(true, papers, citations, markets);
    }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }

    private static ResearchPaper paper(String paperId, String domain, String authorBotId,
                                       String status, double anomalyZ, String callPath) {
        return new ResearchPaper(
                paperId, UUID.randomUUID(), authorBotId, domain, "topic-x",
                "title", "h", "THEORETICAL", "f", "c",
                List.of(), 0.5, status,
                100L, Instant.now(), 1, anomalyZ, null, callPath);
    }
}
