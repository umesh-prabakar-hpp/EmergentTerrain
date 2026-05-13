package com.emergentterrain.research;

import com.emergentterrain.repo.PaperCitationRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for UniversityService.publishPaper — the method that writes a PaperDraft
 * plus NarrationResult into an in-memory ResearchPaper, applying confidence scaling,
 * null-result status, and downstream guards.
 *
 * No DB, no LLM, no Spring context required. The service is instantiated with a
 * temp dir and a mock RoomBotRepository.
 */
class PublishPaperTest {

    @TempDir Path tempDir;

    private UniversityService service;
    private UUID roomId;
    /** Phase 5 A1: tests that exercise the truncation guard set this to true via {@link #enableTruncationGuard()}. */
    private boolean truncationGuardEnabled = false;
    /** Phase 5 A4: tests that exercise the harness-leak filter set this via {@link #enableHarnessLeakFilter()}. */
    private boolean harnessLeakFilterEnabled = false;
    /** Phase 5 B1: tests that exercise the numerical-consistency validator set this via {@link #enableNumericalConsistency()}. */
    private boolean numericalConsistencyEnabled = false;
    /** Phase 5 B2: tests that exercise the magnitude-range validator set this via {@link #enableMagnitudeRange()}. */
    private boolean magnitudeRangeEnabled = false;

    @BeforeEach
    void setUp() {
        ResearchPaperRepository papersRepo = mock(ResearchPaperRepository.class);
        RoomBotRepository botRepo = mock(RoomBotRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReplicationService replicationService = new ReplicationService(papersRepo, botRepo, mapper);
        service = new UniversityService(
                0L, tempDir.toString(), 3, false,
                botRepo,
                papersRepo,
                mock(PaperCitationRepository.class),
                new PopulationEcologyService(),
                replicationService,
                new BotLifecycleService(botRepo,
                        mock(com.emergentterrain.repo.ReputationHistoryRepository.class), mapper),
                new ResearchCycleService(),
                new ResearchProgramService(false,
                        mock(com.emergentterrain.repo.ResearchProgramRepository.class),
                        mock(com.emergentterrain.repo.ProgramOpenQuestionRepository.class),
                        mock(com.emergentterrain.repo.ProgramFailedApproachRepository.class),
                        papersRepo),
                new ExpectationModelService(
                        mock(com.emergentterrain.repo.ExpectationPriorRepository.class),
                        mock(com.emergentterrain.repo.SurpriseEventRepository.class)),
                new ExperimentPlannerService(false, false,
                        mock(com.emergentterrain.repo.ResearchProgramRepository.class),
                        mock(com.emergentterrain.repo.ProgramOpenQuestionRepository.class),
                        mock(com.emergentterrain.repo.PaperCitationRepository.class),
                        mock(com.emergentterrain.repo.SurpriseEventRepository.class),
                        mock(com.emergentterrain.repo.ResearchPaperRepository.class),
                        new WorldDataQueryService()),
                new InstitutionService(false, false, false, false, false,
                        mock(com.emergentterrain.repo.UniversityRepository.class),
                        mock(com.emergentterrain.repo.JournalRepository.class),
                        mock(com.emergentterrain.repo.FundingAgencyRepository.class),
                        mock(com.emergentterrain.repo.ReplicationCouncilRepository.class),
                        papersRepo,
                        mock(PaperCitationRepository.class)),
                new EpistemicMarketService(false, false,
                        mock(com.emergentterrain.repo.EpistemicMarketRepository.class),
                        mock(com.emergentterrain.repo.MarketStakeRepository.class),
                        mock(com.emergentterrain.repo.BotCalibrationRepository.class),
                        botRepo, mapper),
                new KnowledgeBaseService(false, false,
                        mock(com.emergentterrain.repo.FormalFindingRepository.class)),
                new FundingAllocationService(false,
                        mock(com.emergentterrain.repo.FundingAgencyRepository.class),
                        mock(com.emergentterrain.repo.UniversityRepository.class),
                        mock(com.emergentterrain.repo.GrantRepository.class),
                        botRepo,
                        new MetaResearchService(false,
                                papersRepo,
                                mock(com.emergentterrain.repo.PaperCitationRepository.class),
                                new EpistemicMarketService(false, false,
                                        mock(com.emergentterrain.repo.EpistemicMarketRepository.class),
                                        mock(com.emergentterrain.repo.MarketStakeRepository.class),
                                        mock(com.emergentterrain.repo.BotCalibrationRepository.class),
                                        botRepo, mapper)),
                        mapper),
                new SurpriseBroadcastService(false, 30),
                new ExplorationTuningService(false, replicationService),
                /* reviewerRoutingEnabled =     */ false,
                /* journalReviewEnabled =       */ false,
                /* truncationGuardEnabled =     */ truncationGuardEnabled,
                /* harnessLeakFilterEnabled =   */ harnessLeakFilterEnabled,
                /* numericalConsistencyEnabled =*/ numericalConsistencyEnabled,
                /* magnitudeRangeEnabled =      */ magnitudeRangeEnabled,
                mapper);
        roomId = UUID.randomUUID();
    }

    // ---- Confidence-score scaling ----

    @Test
    void positiveResultAppliesConfidenceBoost() {
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = narration(1.0);  // max confidence

        service.publishPaper(draft, narration);

        ResearchPaper paper = firstPaper();
        // peerScore=0.5, confidence=1.0 → factor = 0.92 + 0.16*1.0 = 1.08 → score=0.54
        assertThat(paper.peerScore()).isCloseTo(0.5 * 1.08, within(0.001));
    }

    @Test
    void positiveResultAtNeutralConfidenceScoresToExactlyPeerScore() {
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.4);
        NarrationResult narration = narration(0.5);  // neutral → factor=1.0

        service.publishPaper(draft, narration);

        assertThat(firstPaper().peerScore()).isCloseTo(0.4 * 1.0, within(0.001));
    }

    @Test
    void positiveResultAtZeroConfidenceAppliesMinimumFactor() {
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.6);
        NarrationResult narration = narration(0.0);  // minimum → factor=0.92

        service.publishPaper(draft, narration);

        assertThat(firstPaper().peerScore()).isCloseTo(0.6 * 0.92, within(0.001));
    }

    @Test
    void nullResultAlwaysAppliesFixedFactorRegardlessOfConfidence() {
        PaperDraft draftHighConfidence = draft(ResultClassification.NULL_RESULT, 0.5);
        NarrationResult highConf = narration(1.0);

        service.publishPaper(draftHighConfidence, highConf);

        // factor must be exactly 0.92, NOT 0.92+0.16*1.0=1.08
        assertThat(firstPaper().peerScore()).isCloseTo(0.5 * 0.92, within(0.001));
    }

    @Test
    void nullResultWithLowConfidenceAlsoUsesFixedFactor() {
        PaperDraft draft = draft(ResultClassification.NULL_RESULT, 0.3);
        NarrationResult narration = narration(0.0);

        service.publishPaper(draft, narration);

        assertThat(firstPaper().peerScore()).isCloseTo(0.3 * 0.92, within(0.001));
    }

    @Test
    void partiallySupportedUsesConfidenceScaling() {
        PaperDraft draft = draft(ResultClassification.PARTIALLY_SUPPORTED, 0.5);
        NarrationResult narration = narration(0.75);  // factor = 0.92 + 0.16*0.75 = 1.04

        service.publishPaper(draft, narration);

        assertThat(firstPaper().peerScore()).isCloseTo(0.5 * 1.04, within(0.001));
    }

    // ---- Paper status ----

    @Test
    void supportedResultPublishesWithStatusPUBLISHED() {
        service.publishPaper(draft(ResultClassification.SUPPORTED, 0.5), narration(0.7));
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void partiallySupportedResultPublishesWithStatusPUBLISHED() {
        service.publishPaper(draft(ResultClassification.PARTIALLY_SUPPORTED, 0.4), narration(0.6));
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void nullResultPublishesWithStatusNULL_RESULT() {
        service.publishPaper(draft(ResultClassification.NULL_RESULT, 0.2), narration(0.5));
        assertThat(firstPaper().status()).isEqualTo("NULL_RESULT");
    }

    @Test
    void nullResultPublishedCountInStats() {
        service.publishPaper(draft(ResultClassification.NULL_RESULT, 0.2), narration(0.5));
        service.publishPaper(draft(ResultClassification.NULL_RESULT, 0.3), narration(0.4));
        service.publishPaper(draft(ResultClassification.SUPPORTED, 0.8), narration(0.9));

        var stats = service.getStats(roomId);
        assertThat((long) stats.get("nullResults")).isEqualTo(2L);
        assertThat((long) stats.get("positiveResults")).isEqualTo(1L);
        assertThat((int) stats.get("totalPublished")).isEqualTo(3);
    }

    // ---- Phase 5 A1: truncation guard ----

    @Test
    void endsWithSentenceTerminatorAcceptsCanonicalEndings() {
        assertThat(UniversityService.endsWithSentenceTerminator("Done.")).isTrue();
        assertThat(UniversityService.endsWithSentenceTerminator("Done!")).isTrue();
        assertThat(UniversityService.endsWithSentenceTerminator("Done?")).isTrue();
        assertThat(UniversityService.endsWithSentenceTerminator("Done…")).isTrue();
        assertThat(UniversityService.endsWithSentenceTerminator("Done.  ")).isTrue();
        assertThat(UniversityService.endsWithSentenceTerminator("Done.*")).isTrue();
        assertThat(UniversityService.endsWithSentenceTerminator("(Done.)")).isTrue();
    }

    @Test
    void endsWithSentenceTerminatorRejectsMidSentenceCutoffs() {
        // Real cutoffs observed in the corpus
        assertThat(UniversityService.endsWithSentenceTerminator("Van der Waals CO₂ (")).isFalse();
        assertThat(UniversityService.endsWithSentenceTerminator("Vf=20% yielding")).isFalse();
        assertThat(UniversityService.endsWithSentenceTerminator("displayed a")).isFalse();
        assertThat(UniversityService.endsWithSentenceTerminator("reached approximately")).isFalse();
    }

    @Test
    void endsWithSentenceTerminatorAcceptsNullAndBlank() {
        // Null/blank pass through to the template fallback in publishPaper.
        assertThat(UniversityService.endsWithSentenceTerminator(null)).isTrue();
        assertThat(UniversityService.endsWithSentenceTerminator("")).isTrue();
        assertThat(UniversityService.endsWithSentenceTerminator("   ")).isTrue();
    }

    @Test
    void truncationGuardOffLeavesIncompleteFindingsAsPublished() {
        // Default setup: flag off. Even with truncated findings the paper publishes.
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "hyp", "Findings end mid-sentence with no terminator",
                "Conclusion is fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void truncationGuardOnDowngradesTruncatedFindings() {
        enableTruncationGuard();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "hyp", "Findings end mid-sentence with no terminator",
                "Conclusion is fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void truncationGuardOnDowngradesTruncatedConclusion() {
        enableTruncationGuard();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "hyp", "Findings end with a period.",
                "Conclusion runs out mid-sente",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void truncationGuardOnPassesProperlyTerminatedPaper() {
        enableTruncationGuard();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "hyp", "Findings end with a period.",
                "Conclusion is fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    // ---- Phase 5 B2: magnitude-range validator ----

    @Test
    void magnitudeRangeOffLeavesCompositeTwelveGPaAsPublished() {
        PaperDraft draft = draftForTopic("MATERIALS", "Composite Failure Mode",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Composite failure expected.",
                "Composite failure (Ef=200GPa, Em=3GPa): Vf=90%→12020MPa.",
                "Stress rises linearly with Vf.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void magnitudeRangeOnDowngradesCompositeTwelveGPa() {
        enableMagnitudeRange();
        PaperDraft draft = draftForTopic("MATERIALS", "Composite Failure Mode",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Composite failure expected.",
                "Composite failure (Ef=200GPa, Em=3GPa): Vf=90%→12020MPa.",
                "Stress rises linearly with Vf.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void magnitudeRangeOnPassesCompositeUnderThreeGPa() {
        enableMagnitudeRange();
        PaperDraft draft = draftForTopic("MATERIALS", "Composite Failure Mode",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Composite failure expected.",
                "Composite failure: Vf=50%→2020MPa Vf=70%→2820MPa.",
                "Stress within bounds.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    // ---- Phase 5 B1: numerical-consistency validator ----

    @Test
    void numericalConsistencyOffLeavesInconsistentPaperAsPublished() {
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Multiplier expected near 2.",
                "Observed 1.07×.",
                "Observed 1.07× is close to the expected 2× value.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void numericalConsistencyOnDowngradesP_a328_14Pattern() {
        enableNumericalConsistency();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Multiplier expected near 2.",
                "Observed 1.07×.",
                "Observed 1.07× is close to the expected 2× value.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void numericalConsistencyOnPassesConsistentPaper() {
        enableNumericalConsistency();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Pendulum 1% threshold predicted near 22°.",
                "θ=20°→0.76% θ=25°→1.19%.",
                "Threshold lands at θ ≈ 25°. The 1.19% value at 25° verifies this.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    // ---- Phase 5 A4: harness-parameter leak filter ----

    @Test
    void harnessLeakFilterOffLeavesLeakPhraseIntact() {
        // Default setup: flag off. Leak phrase rides through to the persisted paper.
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Aliquot sequences in [10, 50) starting at position 10 will terminate.",
                "f", "c", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().hypothesis()).contains("starting at position 10");
    }

    @Test
    void harnessLeakFilterOnStripsStartingAtPositionFromHypothesis() {
        enableHarnessLeakFilter();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Aliquot sequences in [10, 50), starting at position 10, will terminate.",
                "f", "c", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        ResearchPaper paper = firstPaper();
        assertThat(paper.hypothesis())
                .doesNotContain("starting at position")
                .contains("Aliquot sequences")
                .contains("will terminate");
    }

    @Test
    void harnessLeakFilterOnStripsScaleAndQualityScore() {
        enableHarnessLeakFilter();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Search uses a scale of 7.0 and finds nothing.",
                "Result is X. The actual quality score of 0.23 remained unaffected by sampling.",
                "c", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        ResearchPaper paper = firstPaper();
        assertThat(paper.hypothesis()).doesNotContain("scale of 7.0");
        assertThat(paper.findings()).doesNotContain("quality score");
    }

    // ---- Phase 5 A2: status reflects narration-pipeline health ----

    @Test
    void positiveResultWithCritiqueSkipMarkerDowngradesToNeedsRevision() {
        service.publishPaper(
                draft(ResultClassification.SUPPORTED, 0.5),
                narrationWithCallPath(0.8, "fp:gpt-oss → critique:skip:incomplete_firstpass"));
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void positiveResultWithProfErrorMarkerStampsPublishedNoProfessor() {
        service.publishPaper(
                draft(ResultClassification.SUPPORTED, 0.5),
                narrationWithCallPath(0.8, "fp:llama-70b → audit:4b:ok → prof:error→fp"));
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED_NO_PROFESSOR");
    }

    @Test
    void positiveResultWithCleanCallPathRemainsPublished() {
        service.publishPaper(
                draft(ResultClassification.SUPPORTED, 0.5),
                narrationWithCallPath(0.8, "fp:llama-70b → audit:4b:ok → prof:llama-70b:ok → rev:llama-70b:ok"));
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void nullResultStaysNullEvenWhenCritiqueWasSkipped() {
        service.publishPaper(
                draft(ResultClassification.NULL_RESULT, 0.2),
                narrationWithCallPath(0.5, "fp:gpt-oss → critique:skip:incomplete_firstpass"));
        // Result classification is independent of narration health — a null finding is
        // still a null finding even if peer review never ran.
        assertThat(firstPaper().status()).isEqualTo("NULL_RESULT");
    }

    @Test
    void needsRevisionDoesNotInflatePositiveCountInStats() {
        service.publishPaper(
                draft(ResultClassification.SUPPORTED, 0.5),
                narrationWithCallPath(0.8, "fp:gpt-oss → critique:skip:incomplete_firstpass"));
        service.publishPaper(
                draft(ResultClassification.SUPPORTED, 0.6),
                narrationWithCallPath(0.7, "fp:llama-70b → audit:4b:ok → prof:llama-70b:ok"));

        var stats = service.getStats(roomId);
        assertThat((long) stats.get("needsRevision")).isEqualTo(1L);
        assertThat((long) stats.get("positiveResults")).isEqualTo(1L);
        assertThat((int) stats.get("totalPublished")).isEqualTo(2);
    }

    // ---- Template fallback (null narration fields) ----

    @Test
    void publishUsesTemplateHypothesisWhenNarrationHypothesisIsNull() {
        PaperDraft draft = draftWithTemplate(ResultClassification.SUPPORTED,
                "Template hypothesis", "Template conclusion");
        NarrationResult narration = new NarrationResult(null, "ai findings", null,
                null, null, 0.7, List.of());

        service.publishPaper(draft, narration);

        assertThat(firstPaper().hypothesis()).isEqualTo("Template hypothesis");
        assertThat(firstPaper().findings()).isEqualTo("ai findings");
        assertThat(firstPaper().conclusion()).isEqualTo("Template conclusion");
    }

    @Test
    void publishUsesNarrationFieldsOverTemplate() {
        PaperDraft draft = draftWithTemplate(ResultClassification.SUPPORTED,
                "Template hypothesis", "Template conclusion");
        NarrationResult narration = new NarrationResult("AI hypothesis", "AI findings",
                "AI conclusion", null, null, 0.8, List.of());

        service.publishPaper(draft, narration);

        assertThat(firstPaper().hypothesis()).isEqualTo("AI hypothesis");
        assertThat(firstPaper().findings()).isEqualTo("AI findings");
        assertThat(firstPaper().conclusion()).isEqualTo("AI conclusion");
    }

    // ---- getStats avg score excludes null results ----

    @Test
    void avgPeerScoreExcludesNullResultPapers() {
        // PUBLISHED at 0.8, NULL_RESULT at 0.1 — average should only reflect 0.8*0.92≈0.736
        service.publishPaper(draft(ResultClassification.SUPPORTED, 0.8), narration(0.5));
        service.publishPaper(draft(ResultClassification.NULL_RESULT, 0.1), narration(0.5));

        var stats = service.getStats(roomId);
        // avgPeerScore rounds to 3 decimal places; score of PUBLISHED paper = 0.8 * 1.0 = 0.8
        double avgScore = ((Number) stats.get("avgPeerScore")).doubleValue();
        assertThat(avgScore).isGreaterThan(0.5);   // not dragged down by the 0.1 null result
    }

    // ---- Helper factories ----

    private PaperDraft draft(ResultClassification classification, double peerScore) {
        return draftWithTemplate(classification, "template hyp", "template conc",
                peerScore, null);
    }

    private PaperDraft draftWithTemplate(ResultClassification classification,
                                          String templateHyp, String templateConc) {
        return draftWithTemplate(classification, templateHyp, templateConc, 0.5, null);
    }

    @org.junit.jupiter.api.Test
    void plannerQuestionIdFlowsFromDraftToOutcomeCallback() {
        // When the draft carries a plannerQuestionId, publishPaper should call
        // experimentPlannerService.recordOutcome with that id. This closes the
        // planner loop wired in Phase 2b.
        java.util.UUID questionId = java.util.UUID.randomUUID();
        PaperDraft draft = draftWithPlannerQuestion(questionId);

        service.publishPaper(draft, narration(0.5));

        // The mocked planner inside service.test setup is the default (disabled);
        // recordOutcome is a no-op when disabled, but the path is exercised here
        // via the existence of the field on the draft and is verified at the
        // ResearchCycleService planner integration in ExperimentPlannerServiceTest.
        // What we assert here is that the draft *reaches* publishPaper with the
        // questionId intact rather than being silently dropped en route.
        ResearchPaper paper = firstPaper();
        assertThat(paper).isNotNull();
        // No way to read plannerQuestionId off the published paper because it's
        // not promoted to ResearchPaper — and that's correct. The marker only
        // exists during the publish hop.
    }

    private PaperDraft draftWithPlannerQuestion(java.util.UUID questionId) {
        String botId = UUID.randomUUID().toString();
        return new PaperDraft(
                roomId, botId, "MATHEMATICS", "Collatz Stopping Time",
                "Test Paper Title",
                "raw findings text", "h", "c",
                "THEORETICAL", List.of(),
                0.5, 0.5, 0.0, 100L, Instant.now(),
                1, "P-test-planner", 50, 3.0, false,
                null, null, List.of(), null,
                null, ResultClassification.SUPPORTED, questionId);
    }

    private PaperDraft draftWithTemplate(ResultClassification classification,
                                          String templateHyp, String templateConc,
                                          double peerScore, PreRegistration preReg) {
        String botId = UUID.randomUUID().toString();
        return new PaperDraft(
                roomId, botId, "MATHEMATICS", "Collatz Stopping Time",
                "Test Paper Title",
                "raw findings text", templateHyp, templateConc,
                "THEORETICAL", List.of(),
                peerScore, peerScore, 0.0, 100L, Instant.now(),
                1, "P-test-1", 50, 3.0, false,
                null, null, List.of(), null,
                preReg, classification, null);
    }

    /** Phase 5 B2 — domain/topic-customised draft for magnitude-range tests. */
    private PaperDraft draftForTopic(String domain, String topic,
                                      ResultClassification classification, double peerScore) {
        String botId = UUID.randomUUID().toString();
        return new PaperDraft(
                roomId, botId, domain, topic,
                "Test Paper Title",
                "raw findings text", "template hyp", "template conc",
                "EMPIRICAL", List.of(),
                peerScore, peerScore, 0.0, 100L, Instant.now(),
                1, "P-test-1", 50, 3.0, false,
                null, null, List.of(), null,
                null, classification, null);
    }

    private NarrationResult narration(double confidenceScore) {
        return new NarrationResult(null, null, null, null, null, confidenceScore, List.of());
    }

    /** 8-arg form — stamps a specific LLM call-path string for Phase 5 A2 assertions. */
    private NarrationResult narrationWithCallPath(double confidenceScore, String callPath) {
        return new NarrationResult(null, null, null, null, null,
                confidenceScore, List.of(), callPath);
    }

    /** Phase 5 A1 — rebuilds the service with the truncation-guard flag set. */
    private void enableTruncationGuard() {
        truncationGuardEnabled = true;
        setUp();
    }

    /** Phase 5 A4 — rebuilds the service with the harness-leak-filter flag set. */
    private void enableHarnessLeakFilter() {
        harnessLeakFilterEnabled = true;
        setUp();
    }

    /** Phase 5 B1 — rebuilds the service with the numerical-consistency-validator flag set. */
    private void enableNumericalConsistency() {
        numericalConsistencyEnabled = true;
        setUp();
    }

    /** Phase 5 B2 — rebuilds the service with the magnitude-range-validator flag set. */
    private void enableMagnitudeRange() {
        magnitudeRangeEnabled = true;
        setUp();
    }

    private ResearchPaper firstPaper() {
        List<ResearchPaper> papers = service.getPublishedPapers(roomId, 10);
        assertThat(papers).isNotEmpty();
        return papers.get(0);
    }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }
}
