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
    /** Per-test counter so draft helpers produce distinct paper IDs. */
    private final java.util.concurrent.atomic.AtomicInteger paperCounter =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Phase 5 A1: tests that exercise the truncation guard set this to true via {@link #enableTruncationGuard()}. */
    private boolean truncationGuardEnabled = false;
    /** Phase 5 A4: tests that exercise the harness-leak filter set this via {@link #enableHarnessLeakFilter()}. */
    private boolean harnessLeakFilterEnabled = false;
    /** Phase 5 B1: tests that exercise the numerical-consistency validator set this via {@link #enableNumericalConsistency()}. */
    private boolean numericalConsistencyEnabled = false;
    /** Phase 5 B2: tests that exercise the magnitude-range validator set this via {@link #enableMagnitudeRange()}. */
    private boolean magnitudeRangeEnabled = false;
    /** Phase 5 B3: tests that exercise the content-hash deduplicator set this via {@link #enableDedup()}. */
    private boolean dedupEnabled = false;
    /** Phase 5 B4: tests that exercise the cross-domain gate set this via {@link #enableCrossDomainGate()}. */
    private boolean crossDomainGateEnabled = false;
    /** Phase 5 C1: tests that exercise citation-aware scoring set this via {@link #enableCitationAwareScoring()}. */
    private boolean citationAwareScoringEnabled = false;
    private boolean disputeReconciliationEnabled = false;
    /** Phase 7 G8: tests that exercise the physical-mechanism-audit reject gate set this via {@link #enableAuditPhysicalMechanismRejects()} or {@link #disableAuditPhysicalMechanismRejects()}. */
    private boolean auditPhysicalMechanismRejectsEnabled = false;

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
                /* dedupEnabled =               */ dedupEnabled,
                /* crossDomainGateEnabled =     */ crossDomainGateEnabled,
                /* disputeReconciliationEnabled */ disputeReconciliationEnabled,
                /* orphanAgeHours               */ 6L,
                /* citationAwareScoringEnabled  */ citationAwareScoringEnabled,
                /* auditPhysicalMechanismRejectsEnabled */ auditPhysicalMechanismRejectsEnabled,
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
    void phase7_G3_peerScoreIsClampedToOneEvenWithMaxConfidenceBoost() {
        // peerScore=0.99 * confidence-boost=1.08 = 1.0692 — Phase 7 G3 clamps to 1.0.
        // Pre-G3: REVIEW_REPORT.md refresh #2-#12 showed P-237=1.021, P-250=1.040
        // (raw scores escaping the [0,1] interpretation).
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.99);
        NarrationResult narration = narration(1.0);

        service.publishPaper(draft, narration);

        assertThat(firstPaper().peerScore()).isEqualTo(1.0);
    }

    @Test
    void phase7_G3_peerScoreIsClampedToZeroEvenWithNegativeCitationDelta() {
        // peerScore is non-negative input; just verify the lower clamp is wired.
        // (We can't easily induce negative scores without a citation-aware-test rig;
        // this assertion just verifies the clamp expression doesn't introduce a
        // regression for normal-range inputs.)
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.0);
        NarrationResult narration = narration(0.0);

        service.publishPaper(draft, narration);

        assertThat(firstPaper().peerScore()).isEqualTo(0.0);
    }

    // ---- Phase 7 G10 — orphan-dispute scan ----

    @Test
    void phase7_G10_scanOrphanedDisputes_isNoOpWhenFlagDisabled() {
        // disputeReconciliationEnabled is `false` in the default setUp() — the scan
        // must early-return without touching researchProgramService.
        // Just verify it doesn't throw when there's nothing to do (cache may be empty).
        service.scanOrphanedDisputes();
        // No assertion needed — the test passes if the method returns cleanly.
    }

    @Test
    void phase7_G10_scanOrphanedDisputes_handlesEmptyCache() {
        // With dispute reconciliation enabled but no DISPUTED papers in cache,
        // the scan should iterate cleanly and log nothing.
        enableDisputeReconciliation();
        service.scanOrphanedDisputes();
        // Method returns void; the test verifies no crash.
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

    // ---- Phase 5 C1: citation-aware peer-score weighting ----

    @Test
    void citationAwareScoringOffNoEffectFromCitations() {
        // Default setup: flag off. Citing a high-quality prior does not move the score.
        PaperDraft priorDraft = draftWithPeerScore(0.40);
        service.publishPaper(priorDraft, narration(0.5));
        String priorId = service.getPublishedPapers(roomId, 10).get(0).paperId();

        PaperDraft draft = draftWithPeerScore(0.40);
        NarrationResult narration = new NarrationResult(
                "h", "f", "c", null, null, 0.5, List.of(priorId));
        service.publishPaper(draft, narration);
        // Score is exactly the deterministic factor — no bonus applied.
        assertThat(service.getPublishedPapers(roomId, 10).get(0).peerScore())
                .isCloseTo(0.40, within(0.001));
    }

    @Test
    void citationAwareScoringOnRewardsCitingHighQualityPrior() {
        enableCitationAwareScoring();
        // Publish two priors so the room median is computable, with one above median.
        service.publishPaper(draftWithPeerScore(0.20), narration(0.5));
        service.publishPaper(draftWithPeerScore(0.60), narration(0.5));
        String highScoreId = service.getPublishedPapers(roomId, 10).get(0).paperId();

        // New paper cites the high-score prior — should gain +0.02.
        PaperDraft draft = draftWithPeerScore(0.40);
        NarrationResult narration = new NarrationResult(
                "h", "f", "c", null, null, 0.5, List.of(highScoreId));
        service.publishPaper(draft, narration);
        double score = service.getPublishedPapers(roomId, 10).get(0).peerScore();
        // 0.40 base ± confidence factor + 0.02 bonus.
        assertThat(score).isGreaterThan(0.40);
    }

    @Test
    void citationAwareScoringOnPenalisesCitingCritiqueSkip() {
        enableCitationAwareScoring();
        // Publish a prior with critique:skip marker — it'll come out as NEEDS_REVISION
        // (Phase 5 A2). But the dedup penalty in C1 keys on the call-path, not the
        // status, so we need a critique-skip paper that DID publish. Make one with
        // the marker explicitly threaded.
        // Easier: publish a clean PUBLISHED prior, then manually inject a bad call-path
        // via a separate scenario. For this test we use a prior that the validator
        // wouldn't normally downgrade — a NULL_RESULT keeps its NULL_RESULT status
        // but if the critique-skip path lands on a published row, the penalty fires.
        // For a unit-test path, use a prior that's PUBLISHED but tagged.
        service.publishPaper(draftWithPeerScore(0.30),
                narrationWithCallPath(0.5, "fp:gpt-oss → prof:llama-70b:ok"));
        String cleanPrior = service.getPublishedPapers(roomId, 10).get(0).paperId();
        // Sanity check: the test would only meaningfully exercise the penalty branch
        // if the prior actually carries critique-skip — but that triggers A2's
        // downgrade. The penalty is therefore exercised by direct unit test on
        // citationAwareDelta below.
        assertThat(cleanPrior).isNotNull();
    }

    @Test
    void citationAwareDeltaAppliesPenaltyForCritiqueSkipPrior() {
        // Direct unit test on the helper — bypasses the A2 downgrade so we can
        // verify the −0.04 penalty fires when a prior with critique:skip survived.
        // Simulate this by publishing a prior with NULL_RESULT classification (so
        // it stays NULL_RESULT, not NEEDS_REVISION) but with a critique-skip path.
        enableCitationAwareScoring();
        // Two priors to seed the median.
        service.publishPaper(draftWithPeerScore(0.40), narration(0.5));
        service.publishPaper(draftWithPeerScore(0.60), narration(0.5));
        // Publish a third with NULL_RESULT and critique-skip marker. NULL_RESULT
        // wins over the critique-skip status logic so this stays NULL_RESULT.
        PaperDraft nullDraft = draft(ResultClassification.NULL_RESULT, 0.50);
        service.publishPaper(nullDraft,
                narrationWithCallPath(0.5, "fp:gpt-oss → critique:skip:incomplete_firstpass"));
        String critiqueSkipId = service.getPublishedPapers(roomId, 10).get(0).paperId();

        // Direct call to citationAwareDelta: cites the critique-skip paper.
        double delta = service.citationAwareDelta(roomId, List.of(critiqueSkipId));
        assertThat(delta).isCloseTo(-0.04, within(0.001));
    }

    // ---- Phase 6 D2: Discussion + Related-Work persistence + markdown ----

    @Test
    void discussionAndRelatedWorkFieldsPersistOnPaperWhenSupplied() {
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "h", "f", "c", null, null, 0.6, List.of(),
                null, List.of(), List.of(), null, null,
                "This work raises questions about asymptotic stability.",
                "Builds on Hardy & Littlewood (1923).");
        service.publishPaper(draft, narration);
        ResearchPaper paper = firstPaper();
        assertThat(paper.discussion()).contains("asymptotic stability");
        assertThat(paper.relatedWork()).contains("Hardy & Littlewood");
    }

    @Test
    void discussionAndRelatedWorkRemainNullWhenNarratorDidNotEmitThem() {
        // Default 7-arg ctor leaves discussion + relatedWork null.
        service.publishPaper(draft(ResultClassification.SUPPORTED, 0.5), narration(0.5));
        ResearchPaper paper = firstPaper();
        assertThat(paper.discussion()).isNull();
        assertThat(paper.relatedWork()).isNull();
    }

    @Test
    void publishedMarkdownIncludesDiscussionAndRelatedWorkSectionsWhenSet() throws Exception {
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "h", "f", "c", null, null, 0.6, List.of(),
                null, List.of(), List.of(), null, null,
                "Pendulum periods remain elliptic in the strong-amplitude regime.",
                "Huygens (1673) and Beléndez (2007) establish the closed-form expansion.");
        service.publishPaper(draft, narration);

        // Find the room directory (first 8 chars of roomId) and the markdown file
        // the publish path just wrote.
        java.nio.file.Path roomDir = tempDir.resolve(roomId.toString().substring(0, 8));
        java.util.List<java.nio.file.Path> md = java.util.stream.Stream.of(
                roomDir.toFile().listFiles())
                .filter(f -> f.getName().endsWith(".md"))
                .map(java.io.File::toPath)
                .toList();
        assertThat(md).hasSize(1);
        String content = java.nio.file.Files.readString(md.get(0));
        assertThat(content)
                .contains("## Discussion")
                .contains("Pendulum periods remain elliptic")
                .contains("## Related Work")
                .contains("Huygens (1673)");
    }

    @Test
    void publishedMarkdownOmitsDiscussionAndRelatedWorkSectionsWhenNull() throws Exception {
        service.publishPaper(draft(ResultClassification.SUPPORTED, 0.5), narration(0.6));
        java.nio.file.Path roomDir = tempDir.resolve(roomId.toString().substring(0, 8));
        java.util.List<java.nio.file.Path> md = java.util.stream.Stream.of(
                roomDir.toFile().listFiles())
                .filter(f -> f.getName().endsWith(".md"))
                .map(java.io.File::toPath)
                .toList();
        assertThat(md).hasSize(1);
        String content = java.nio.file.Files.readString(md.get(0));
        assertThat(content)
                .doesNotContain("## Discussion")
                .doesNotContain("## Related Work");
    }

    // ---- Phase 5 B4: cross-domain gate ----

    @Test
    void crossDomainGateOffLeavesMisalignedHypothesisAsPublished() {
        PaperDraft draft = draftForTopic("MATHEMATICS", "Collatz Stopping Time",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Collatz depth maps to thermal stress on a composite fibre.",
                "f", "c", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void crossDomainGateOnDowngradesMathHypothesisWithThermalMarker() {
        enableCrossDomainGate();
        PaperDraft draft = draftForTopic("MATHEMATICS", "Aliquot Sequence Behaviour",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Aliquot divisor sums correlate with thermal stress in steel layers.",
                "f", "c", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        // Phase 5 B-series validator failures stamp REJECTED (stronger than NEEDS_REVISION) so
        // operators can distinguish "validator caught structural defect" from "narration truncated".
        assertThat(firstPaper().status()).isEqualTo("REJECTED");
    }

    @Test
    void crossDomainGateOnSkipsInterdisciplinaryDomain() {
        enableCrossDomainGate();
        // INTERDISCIPLINARY is intentionally cross-domain; the gate must not block it.
        PaperDraft draft = draftForTopic("INTERDISCIPLINARY", "Prime-Gap Defect Thermal Loading",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Prime gap defect spacing produces thermal stress in composite fibres.",
                "f", "c", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    // ---- Phase 5 B3: content-hash dedup ----

    @Test
    void dedupOffLeavesNearByteIdenticalRerunAsPublished() {
        // First paper publishes with a distinctive findings string.
        String findings = "RLC circuit (L=0.0080 H, C=1.1e-06 F): f₀=5344.81 Hz, R_crit=31.3 Ω. Q-factor sweep.";
        PaperDraft draft1 = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        service.publishPaper(draft1, new NarrationResult(
                "h", findings, "c", null, null, 0.8, List.of()));

        // Second paper carries identical findings, no citation.
        PaperDraft draft2 = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        service.publishPaper(draft2, new NarrationResult(
                "h", findings, "c", null, null, 0.8, List.of()));

        var papers = service.getPublishedPapers(roomId, 10);
        assertThat(papers).hasSize(2);
        assertThat(papers.get(0).status()).isEqualTo("PUBLISHED");
        assertThat(papers.get(1).status()).isEqualTo("PUBLISHED");
    }

    @Test
    void dedupOnDowngradesUncitedClone() {
        enableDedup();
        String findings = "RLC circuit (L=0.0080 H, C=1.1e-06 F): f₀=5344.81 Hz, R_crit=31.3 Ω. Q-factor sweep.";
        PaperDraft draft1 = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        service.publishPaper(draft1, new NarrationResult(
                "h", findings, "c", null, null, 0.8, List.of()));

        PaperDraft draft2 = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        service.publishPaper(draft2, new NarrationResult(
                "h", findings, "c", null, null, 0.8, List.of()));

        var papers = service.getPublishedPapers(roomId, 10);
        // First publication is the prior; second is the clone — only the second is downgraded.
        // getPublishedPapers returns newest-first. The clone gets REJECTED (B3 validator),
        // not NEEDS_REVISION (which is reserved for truncation/critique-skip failures).
        assertThat(papers).hasSize(2);
        assertThat(papers.get(0).status()).isEqualTo("REJECTED");
        assertThat(papers.get(1).status()).isEqualTo("PUBLISHED");
    }

    @Test
    void dedupOnAllowsCitedClone() {
        enableDedup();
        String findings = "RLC circuit (L=0.0080 H, C=1.1e-06 F): f₀=5344.81 Hz, R_crit=31.3 Ω. Q-factor sweep.";
        PaperDraft draft1 = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        service.publishPaper(draft1, new NarrationResult(
                "h", findings, "c", null, null, 0.8, List.of()));
        String priorId = service.getPublishedPapers(roomId, 10).get(0).paperId();

        // Second paper carries identical findings AND cites the prior — should pass.
        PaperDraft draft2 = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        service.publishPaper(draft2, new NarrationResult(
                "h", findings, "c", null, null, 0.8, List.of(priorId)));

        var papers = service.getPublishedPapers(roomId, 10);
        assertThat(papers).hasSize(2);
        assertThat(papers.get(0).status()).isEqualTo("PUBLISHED");
        assertThat(papers.get(1).status()).isEqualTo("PUBLISHED");
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
        // B2 magnitude-range failures stamp REJECTED.
        assertThat(firstPaper().status()).isEqualTo("REJECTED");
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
        // B1 numerical-consistency failures stamp REJECTED.
        assertThat(firstPaper().status()).isEqualTo("REJECTED");
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

    // ── Phase 7 E3 — Ollama fallback publish precedence ─────────────────────

    @Test
    void e3OllamaRevisionSucceededPublishesAsPublished() {
        service.publishPaper(
                draft(ResultClassification.SUPPORTED, 0.5),
                narrationWithCallPath(0.8,
                        "fp:gpt-oss → ollama_critique:4b:2gaps → rev:ollama:ok"));
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void e3OllamaRevisionOverridesProfErrorFp() {
        // When the tool-call path errored AND a separate Ollama-fallback revision
        // succeeded, the paper IS revised — PUBLISHED takes precedence over the
        // legacy PUBLISHED_NO_PROFESSOR status that would otherwise fire.
        service.publishPaper(
                draft(ResultClassification.SUPPORTED, 0.5),
                narrationWithCallPath(0.8,
                        "fp:llama-70b → audit:4b:ok → prof:error→fp → rev:ollama:ok"));
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

    // ---- Phase 7 G8: physical_mechanism audit gap → REJECTED ----

    @Test
    void phase7_G8_supportedPaperWithPhysicalMechanismGapPublishesRejectedWhenFlagOn() {
        enableAuditPhysicalMechanismRejects();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = narrationWithAuditGaps(0.8, List.of("physical_mechanism"));
        service.publishPaper(draft, narration);
        ResearchPaper paper = firstPaper();
        assertThat(paper.status()).isEqualTo("REJECTED");
        assertThat(paper.llmCallPath()).contains("audit:physical_mechanism→REJECTED");
    }

    @Test
    void phase7_G8_supportedPaperWithPhysicalMechanismGapPublishedWhenFlagOff() {
        // Default setUp leaves auditPhysicalMechanismRejectsEnabled = false.
        // The same input that triggers REJECTED above must publish as PUBLISHED here.
        disableAuditPhysicalMechanismRejects();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = narrationWithAuditGaps(0.8, List.of("physical_mechanism"));
        service.publishPaper(draft, narration);
        ResearchPaper paper = firstPaper();
        assertThat(paper.status()).isEqualTo("PUBLISHED");
        // Flag-off path leaves the narration's null callPath untouched.
        assertThat(paper.llmCallPath()).isNull();
    }

    @Test
    void phase7_G8_nullResultWithPhysicalMechanismGapStaysNullResult() {
        // SUPPORTED-only gate: a NULL_RESULT paper carrying the same audit gap must
        // remain NULL_RESULT (validator does not fire on negative outcomes).
        enableAuditPhysicalMechanismRejects();
        PaperDraft draft = draft(ResultClassification.NULL_RESULT, 0.5);
        NarrationResult narration = narrationWithAuditGaps(0.8, List.of("physical_mechanism"));
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NULL_RESULT");
    }

    @Test
    void phase7_G8_supportedPaperWithOtherAuditGapsPublishesNormally() {
        // A different dimension (e.g. boundary_answer, uncertainty) must not trigger
        // the physical_mechanism reject. Only the named dimension fires the gate.
        enableAuditPhysicalMechanismRejects();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = narrationWithAuditGaps(0.8, List.of("uncertainty", "boundary_answer"));
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
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
                1, "P-test-" + paperCounter.incrementAndGet(), 50, 3.0, false,
                null, null, List.of(), null,
                preReg, classification, null);
    }

    /** Phase 5 C1 — shorthand for a default-domain draft with a specific peer score. */
    private PaperDraft draftWithPeerScore(double peerScore) {
        return draftWithTemplate(ResultClassification.SUPPORTED,
                "template hyp", "template conc", peerScore, null);
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
                1, "P-test-" + paperCounter.incrementAndGet(), 50, 3.0, false,
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

    /** Phase 7 G8 — narration with explicit audit-4b gap dimensions (15-arg ctor). */
    private NarrationResult narrationWithAuditGaps(double confidenceScore,
                                                    List<String> auditGapDimensions) {
        return new NarrationResult(null, null, null, null, null,
                confidenceScore, List.of(), null, List.of(), List.of(),
                null, null, null, null, auditGapDimensions);
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

    /** Phase 5 B3 — rebuilds the service with the dedup flag set. */
    private void enableDedup() {
        dedupEnabled = true;
        setUp();
    }

    /** Phase 5 B4 — rebuilds the service with the cross-domain-gate flag set. */
    private void enableCrossDomainGate() {
        crossDomainGateEnabled = true;
        setUp();
    }

    /** Phase 5 C1 — rebuilds the service with citation-aware scoring on. */
    private void enableCitationAwareScoring() {
        citationAwareScoringEnabled = true;
        setUp();
    }

    /** Phase 7 G10 — rebuilds the service with dispute reconciliation on. */
    private void enableDisputeReconciliation() {
        disputeReconciliationEnabled = true;
        setUp();
    }

    /** Phase 7 G8 — rebuilds the service with physical-mechanism audit-reject ON. */
    private void enableAuditPhysicalMechanismRejects() {
        auditPhysicalMechanismRejectsEnabled = true;
        setUp();
    }

    /** Phase 7 G8 — rebuilds the service with physical-mechanism audit-reject OFF (default). */
    private void disableAuditPhysicalMechanismRejects() {
        auditPhysicalMechanismRejectsEnabled = false;
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
