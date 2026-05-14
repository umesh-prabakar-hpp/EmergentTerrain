package com.emergentterrain.service;

import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.LiteratureSearchService;
import com.emergentterrain.research.ResearchIdentity;
import com.emergentterrain.research.ResearchPaper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Phase 1 Iter 2 wiring in {@link FormulationService}: the prompt
 * builder should bias hypothesis style based on a {@link ResearchIdentity}'s
 * skepticismLevel and riskTolerance, and should fall back to no preamble when
 * no identity is available (legacy 5-arg callers).
 */
class FormulationServiceTest {

    @Test
    void emptyHintWhenIdentityMissing() {
        assertThat(FormulationService.identityHint(null)).isEmpty();
    }

    @Test
    void highSkepticismRequestsSharplyFalsifiableFraming() {
        String hint = FormulationService.identityHint(identityWith(0.85, 0.20));

        assertThat(hint).contains("Researcher style");
        assertThat(hint).contains("sharply falsifiable");
        assertThat(hint).doesNotContain("exploratory");
    }

    @Test
    void lowSkepticismRequestsExploratoryFraming() {
        String hint = FormulationService.identityHint(identityWith(0.10, 0.20));

        assertThat(hint).contains("exploratory");
        assertThat(hint).doesNotContain("sharply falsifiable");
    }

    @Test
    void mediumSkepticismGivesModerateFraming() {
        String hint = FormulationService.identityHint(identityWith(0.50, 0.20));

        assertThat(hint).contains("moderately specific");
        assertThat(hint).doesNotContain("sharply falsifiable");
        assertThat(hint).doesNotContain("exploratory");
    }

    @Test
    void highRiskEncouragesEdgeOfSearchRadius() {
        String hint = FormulationService.identityHint(identityWith(0.50, 0.40));

        assertThat(hint).contains("edge of the learned search radius");
        assertThat(hint).doesNotContain("conservative");
    }

    @Test
    void lowRiskPrefersConservativeSteps() {
        String hint = FormulationService.identityHint(identityWith(0.50, 0.10));

        assertThat(hint).contains("learned search center");
        assertThat(hint).contains("conservative");
        assertThat(hint).doesNotContain("edge of the learned search radius");
    }

    @Test
    void hintIncludesCareerSummary() {
        ResearchIdentity id = new ResearchIdentity(
                "EMPIRICAL", 0.25, 0.6,
                new double[]{0, 0, 0, 0, 0},
                new double[]{0.5, 0.5, 0.5, 0.5},
                0.72, 12, 3, List.of());

        String hint = FormulationService.identityHint(id);

        assertThat(hint).contains("methodology=EMPIRICAL");
        assertThat(hint).contains("career: 12 published / 3 rejected");
    }

    private static ResearchIdentity identityWith(double skepticism, double risk) {
        return new ResearchIdentity(
                "THEORETICAL", risk, skepticism,
                new double[5],
                new double[]{0.5, 0.5, 0.5, 0.5},
                0.5, 0, 0, List.of());
    }

    @Test
    void identityFromBareLifecycleStillRendersDefaults() {
        BotLifecycleState ls = new BotLifecycleState();
        ResearchIdentity id = ResearchIdentity.from(ls, null, List.of());

        String hint = FormulationService.identityHint(id);

        assertThat(hint).contains("methodology=THEORETICAL");
    }

    // ── Phase 7 G9: intra-room sibling novelty penalty ──────────────────────

    @Test
    void g9DocksNoveltyByHalfWhenRecentSiblingExists() {
        FormulationService svc = serviceWithWindowMinutes(30);
        ResearchPaper recent = paperWithDomainTopic("MATHEMATICS", "Goldbach Density",
                Instant.now().minus(8, ChronoUnit.MINUTES));
        FormulationService.NoveltyAssessment in =
                new FormulationService.NoveltyAssessment(0.9, "", "");

        FormulationService.NoveltyAssessment out = svc.applyIntraRoomSiblingPenalty(
                in, "MATHEMATICS", "Goldbach Density", List.of(recent));

        assertThat(out.score()).isEqualTo(0.4);
        assertThat(out.coverageNote()).contains("intra-room sibling within 30min");
    }

    @Test
    void g9LeavesNoveltyUnchangedWhenSiblingIsOutsideWindow() {
        FormulationService svc = serviceWithWindowMinutes(30);
        ResearchPaper old = paperWithDomainTopic("MATHEMATICS", "Goldbach Density",
                Instant.now().minus(2, ChronoUnit.HOURS));
        FormulationService.NoveltyAssessment in =
                new FormulationService.NoveltyAssessment(0.9, "prior note", "hint");

        FormulationService.NoveltyAssessment out = svc.applyIntraRoomSiblingPenalty(
                in, "MATHEMATICS", "Goldbach Density", List.of(old));

        assertThat(out).isEqualTo(in);
    }

    @Test
    void g9DoesNotMatchAcrossDomainOrTopic() {
        FormulationService svc = serviceWithWindowMinutes(30);
        ResearchPaper differentDomain = paperWithDomainTopic("PHYSICS", "Goldbach Density",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        ResearchPaper differentTopic = paperWithDomainTopic("MATHEMATICS", "Collatz Stopping",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        FormulationService.NoveltyAssessment in =
                new FormulationService.NoveltyAssessment(0.8, "", "");

        FormulationService.NoveltyAssessment out = svc.applyIntraRoomSiblingPenalty(
                in, "MATHEMATICS", "Goldbach Density", List.of(differentDomain, differentTopic));

        assertThat(out.score()).isEqualTo(0.8);
        assertThat(out.coverageNote()).isEmpty();
    }

    @Test
    void g9ClampsDockedScoreAtZero() {
        FormulationService svc = serviceWithWindowMinutes(30);
        ResearchPaper recent = paperWithDomainTopic("PHYSICS", "RLC Damping",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        FormulationService.NoveltyAssessment in =
                new FormulationService.NoveltyAssessment(0.3, "", "");

        FormulationService.NoveltyAssessment out = svc.applyIntraRoomSiblingPenalty(
                in, "PHYSICS", "RLC Damping", List.of(recent));

        assertThat(out.score()).isEqualTo(0.0);
    }

    @Test
    void g9DisabledWhenWindowIsZero() {
        FormulationService svc = serviceWithWindowMinutes(0);
        ResearchPaper recent = paperWithDomainTopic("PHYSICS", "RLC Damping",
                Instant.now().minus(1, ChronoUnit.MINUTES));
        FormulationService.NoveltyAssessment in =
                new FormulationService.NoveltyAssessment(0.9, "", "");

        FormulationService.NoveltyAssessment out = svc.applyIntraRoomSiblingPenalty(
                in, "PHYSICS", "RLC Damping", List.of(recent));

        assertThat(out).isEqualTo(in);
    }

    private static FormulationService serviceWithWindowMinutes(long minutes) {
        return new FormulationService(null, null, null, minutes);
    }

    // ── Phase 6 D1.4: literature-known novelty cap ────────────────────────────

    @Test
    void d14CapsNoveltyWhenCorpusHasNonSentinelReference() {
        FormulationService svc = serviceWithLiteratureCap(0.4);
        FormulationService.NoveltyAssessment in =
                new FormulationService.NoveltyAssessment(0.95, "", "");

        FormulationService.NoveltyAssessment out =
                svc.applyLiteratureCap(in, "MATHEMATICS", "Prime Constellation Search");

        assertThat(out.score()).isEqualTo(0.4);
        assertThat(out.coverageNote()).contains("literature-known").contains("Hardy");
    }

    @Test
    void d14LeavesNoveltyAloneForSentinelInterdisciplinaryEntries() {
        FormulationService svc = serviceWithLiteratureCap(0.4);
        FormulationService.NoveltyAssessment in =
                new FormulationService.NoveltyAssessment(0.95, "", "");

        FormulationService.NoveltyAssessment out =
                svc.applyLiteratureCap(in, "INTERDISCIPLINARY", "Collatz-RLC Depth Resonance");

        assertThat(out).isEqualTo(in);
    }

    @Test
    void d14LeavesNoveltyAloneWhenCorpusHasNoEntry() {
        FormulationService svc = serviceWithLiteratureCap(0.4);
        FormulationService.NoveltyAssessment in =
                new FormulationService.NoveltyAssessment(0.9, "prior", "hint");

        FormulationService.NoveltyAssessment out =
                svc.applyLiteratureCap(in, "MATHEMATICS", "Some Unindexed Topic");

        assertThat(out).isEqualTo(in);
    }

    @Test
    void d14DoesNotRaiseAlreadyLowScore() {
        FormulationService svc = serviceWithLiteratureCap(0.4);
        FormulationService.NoveltyAssessment in =
                new FormulationService.NoveltyAssessment(0.2, "", "");

        FormulationService.NoveltyAssessment out =
                svc.applyLiteratureCap(in, "MATHEMATICS", "Prime Constellation Search");

        assertThat(out).isEqualTo(in);
    }

    @Test
    void d14DisabledWhenLiteratureSearchNull() {
        FormulationService svc = new FormulationService(null, null, null, 30, null, true, 0.4);
        FormulationService.NoveltyAssessment in =
                new FormulationService.NoveltyAssessment(0.9, "", "");

        FormulationService.NoveltyAssessment out =
                svc.applyLiteratureCap(in, "MATHEMATICS", "Prime Constellation Search");

        assertThat(out).isEqualTo(in);
    }

    @Test
    void d14DisabledWhenFlagOff() {
        LiteratureSearchService corpus =
                new LiteratureSearchService(new ClassPathResource("literature-corpus.yml"));
        corpus.loadCorpus();
        FormulationService svc = new FormulationService(null, null, null, 30, corpus, false, 0.4);
        FormulationService.NoveltyAssessment in =
                new FormulationService.NoveltyAssessment(0.9, "", "");

        FormulationService.NoveltyAssessment out =
                svc.applyLiteratureCap(in, "MATHEMATICS", "Prime Constellation Search");

        assertThat(out).isEqualTo(in);
    }

    private static FormulationService serviceWithLiteratureCap(double capScore) {
        LiteratureSearchService corpus =
                new LiteratureSearchService(new ClassPathResource("literature-corpus.yml"));
        corpus.loadCorpus();
        return new FormulationService(null, null, null, 30, corpus, true, capScore);
    }

    private static ResearchPaper paperWithDomainTopic(String domain, String topic, Instant publishedAt) {
        return new ResearchPaper(
                "P-test-" + UUID.randomUUID(), UUID.randomUUID(), "bot-1",
                domain, topic,
                "title", "hypothesis", "THEORETICAL", "findings", "conclusion",
                List.of("rev-1", "rev-2"), 0.42, "PUBLISHED",
                100L, publishedAt, 0, 0.0,
                "test-model", "test:path");
    }
}
