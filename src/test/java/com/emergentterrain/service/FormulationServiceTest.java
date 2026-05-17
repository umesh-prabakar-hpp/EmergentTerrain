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

    // -------- Phase 16 P6b — firstSentence helper --------

    @Test
    void p16_p6b_firstSentenceStopsAtPeriod() {
        String s = FormulationService.firstSentence(
                "The Reuss bound gives V_f=1. Further analysis confirms.", 200);
        assertThat(s).isEqualTo("The Reuss bound gives V_f=1.");
    }

    @Test
    void p16_p6b_firstSentenceTruncatesWithoutTerminator() {
        // A long string with no period/!/? within maxLen should ellipsis.
        String longText = "x".repeat(300);
        String s = FormulationService.firstSentence(longText, 100);
        assertThat(s).endsWith("…");
        assertThat(s.length()).isLessThanOrEqualTo(101); // 100 chars + ellipsis
    }

    @Test
    void p16_p6b_firstSentenceShortInputAppendsTerminator() {
        // Whole text fits within cap but no period — append one so the
        // injected sentence reads as a clean clause.
        String s = FormulationService.firstSentence("Short clause", 200);
        assertThat(s).isEqualTo("Short clause.");
    }

    @Test
    void p16_p6b_firstSentenceNullOrBlankReturnsEmpty() {
        assertThat(FormulationService.firstSentence(null, 200)).isEmpty();
        assertThat(FormulationService.firstSentence("", 200)).isEmpty();
        assertThat(FormulationService.firstSentence("   ", 200)).isEmpty();
    }

    // -------- Phase 16 P6b — buildPriorResultHint selection --------

    @Test
    void p16_p6b_singleQualifyingPriorEmitsHint() {
        // The canonical case: exactly one math-verified PUBLISHED prior on
        // the topic. The hint should name the paper, its score, and the
        // first sentence of its conclusion.
        ResearchPaper p516 = new ResearchPaper(
                "P-a328-516", UUID.randomUUID(), "bot-1",
                "MATERIALS", "Stiffness Crossover Volume Fraction",
                "title", "hyp", "EMPIRICAL", "findings",
                "Reuss bound predicts V_f=1 exactly for E_f/E_m=2. The crossover is asymptotic.",
                List.of("rev-1"), 0.535, "PUBLISHED",
                100L, Instant.now(), 0, 0.0, "test-model", "test:path");

        String hint = FormulationService.buildPriorResultHint(
                "MATERIALS", "Stiffness Crossover Volume Fraction",
                List.of(p516), 0.5);

        assertThat(hint).contains("STRONG-CONFIDENCE PRIOR");
        assertThat(hint).contains("P-a328-516");
        assertThat(hint).contains("0.54"); // peer score rounded from 0.535
        assertThat(hint).contains("Reuss bound predicts V_f=1");
        assertThat(hint).contains("replicate");
        assertThat(hint).contains("refute");
        assertThat(hint).contains("shift");
    }

    @Test
    void p16_p6b_zeroQualifyingPriorsReturnsEmpty() {
        // No PUBLISHED priors → no hint. Existing priorPapers context handles it.
        String hint = FormulationService.buildPriorResultHint(
                "MATERIALS", "Stiffness Crossover Volume Fraction",
                List.of(), 0.5);
        assertThat(hint).isEmpty();
    }

    @Test
    void p16_p6b_twoQualifyingPriorsReturnsEmpty() {
        // The scoping doc's first-iteration restriction: skip injection
        // when ≥2 math-verified priors exist (the disagreeing-priors case
        // is deferred to a future P6b.2).
        ResearchPaper p516 = paperWithStatus("P-a328-516", "MATERIALS",
                "Stiffness Crossover Volume Fraction", 0.535, "PUBLISHED",
                "Reuss bound gives V_f=1.");
        ResearchPaper p541 = paperWithStatus("P-a328-541", "MATERIALS",
                "Stiffness Crossover Volume Fraction", 0.609, "PUBLISHED",
                "The crossover lies in the interior.");

        String hint = FormulationService.buildPriorResultHint(
                "MATERIALS", "Stiffness Crossover Volume Fraction",
                List.of(p516, p541), 0.5);
        assertThat(hint).isEmpty();
    }

    @Test
    void p16_p6b_rejectedPriorDoesNotQualify() {
        // P-a328-534 was REJECTED for fabricated convergence. Even with a
        // high score, it must not seed the hint — REJECTED priors are not
        // math-verified.
        ResearchPaper p534 = paperWithStatus("P-a328-534", "MATHEMATICS",
                "Divisor Sum Average", 0.80, "REJECTED",
                "Empirical mean converges to π²/12.");

        String hint = FormulationService.buildPriorResultHint(
                "MATHEMATICS", "Divisor Sum Average",
                List.of(p534), 0.5);
        assertThat(hint).isEmpty();
    }

    @Test
    void p16_p6b_lowScorePriorDoesNotQualify() {
        // PUBLISHED but peerScore below the min — too noisy to seed the
        // hint with.
        ResearchPaper noisy = paperWithStatus("P-a328-999", "MATHEMATICS",
                "Some Topic", 0.30, "PUBLISHED",
                "Some result.");

        String hint = FormulationService.buildPriorResultHint(
                "MATHEMATICS", "Some Topic",
                List.of(noisy), 0.5);
        assertThat(hint).isEmpty();
    }

    @Test
    void p16_p6b_domainOrTopicMismatchExcludesPrior() {
        // Domain mismatch.
        ResearchPaper otherDomain = paperWithStatus("P-a328-100", "PHYSICS",
                "Stiffness Crossover Volume Fraction", 0.70, "PUBLISHED",
                "Some result.");
        assertThat(FormulationService.buildPriorResultHint(
                "MATERIALS", "Stiffness Crossover Volume Fraction",
                List.of(otherDomain), 0.5)).isEmpty();

        // Topic mismatch.
        ResearchPaper otherTopic = paperWithStatus("P-a328-101", "MATERIALS",
                "Other Topic", 0.70, "PUBLISHED", "Some result.");
        assertThat(FormulationService.buildPriorResultHint(
                "MATERIALS", "Stiffness Crossover Volume Fraction",
                List.of(otherTopic), 0.5)).isEmpty();
    }

    @Test
    void p16_p6b_selectsHighestScoringQualifying() {
        // When the qualifying set has size 1 after filtering, that one is
        // selected regardless of which slot in the input list it occupies.
        // (Two PUBLISHED on the same topic would skip injection altogether
        // per the size-1 restriction; this test confirms ordering within
        // the size-1 case is stable.)
        ResearchPaper lowScore = paperWithStatus("P-low", "MATHEMATICS",
                "T", 0.30, "PUBLISHED", "Low-score prior.");  // disqualified
        ResearchPaper qualifying = paperWithStatus("P-good", "MATHEMATICS",
                "T", 0.70, "PUBLISHED", "Good prior result.");

        String hint = FormulationService.buildPriorResultHint(
                "MATHEMATICS", "T",
                List.of(lowScore, qualifying), 0.5);
        assertThat(hint).contains("P-good");
        assertThat(hint).doesNotContain("P-low");
    }

    @Test
    void p16_p6b_nullPriorListReturnsEmpty() {
        assertThat(FormulationService.buildPriorResultHint(
                "MATERIALS", "T", null, 0.5)).isEmpty();
    }

    private static ResearchPaper paperWithStatus(
            String paperId, String domain, String topic, double peerScore,
            String status, String conclusion) {
        return new ResearchPaper(
                paperId, UUID.randomUUID(), "bot-1", domain, topic,
                "title", "hyp", "THEORETICAL", "findings", conclusion,
                List.of("rev-1"), peerScore, status,
                100L, Instant.now(), 0, 0.0, "test-model", "test:path");
    }
}
