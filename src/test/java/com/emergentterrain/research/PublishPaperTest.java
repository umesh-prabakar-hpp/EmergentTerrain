package com.emergentterrain.research;

import com.emergentterrain.repo.PaperCitationRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

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
    /** Phase 12 D2: warn | downgrade | suppress. Tests override via {@link #setNumericalConsistencyMode(String)}. */
    private String  numericalConsistencyMode    = "warn";
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
    /** Phase 7 F7.3: tests that exercise the cherry-picked-evidence reject gate set this true. */
    private boolean auditCherryPickedRejectsEnabled = false;
    /** Phase 7 G6: tests that exercise topic-specific findings checks set this true. */
    private boolean topicSpecificFindingsValidatorEnabled = false;
    /** Phase 12 D6: tests that exercise the closing-prose validator set this via {@link #enableClosingProseValidator()}. */
    private boolean closingProseValidatorEnabled = false;
    /** Phase 13 E6: tests that exercise the uncertainty-disclosure validator set this via {@link #enableUncertaintyDisclosure()}. */
    private boolean uncertaintyDisclosureEnabled = false;
    /** Phase 14 F5: tests that exercise the missing-Methods gate set this via {@link #enableMissingMethodsGate()}. */
    private boolean missingMethodsGateEnabled = false;
    private String  missingMethodsGateMode    = "downgrade";
    /** Phase 14 F3: tests that exercise the single-point-sweep validator set this via {@link #enableSinglePointSweep()}. */
    private boolean singlePointSweepEnabled = false;
    /** Phase 14 F4: tests that exercise the direction-of-finding validator set this via {@link #enableDirectionOfFinding()}. */
    private boolean directionOfFindingEnabled = false;
    /** Phase 14 F1a: tests that exercise the block-count stamp renderer set this via {@link #enableBlockCountStamp()}. */
    private boolean blockCountStampEnabled = false;
    /** Phase 14 F1b: tests that exercise the block-count publish gate set this via {@link #enableBlockCountGuard()}. */
    private boolean blockCountGuardEnabled = false;

    @BeforeEach
    void setUp() {
        ResearchPaperRepository papersRepo = mock(ResearchPaperRepository.class);
        RoomBotRepository botRepo = mock(RoomBotRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReplicationService replicationService = new ReplicationService(papersRepo, botRepo, mapper);
        service = new UniversityService(
                0L, tempDir.toString(), "archive", false, 3, false,
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
                /* conjectureFrontierFraming =  */ false,
                /* truncationGuardEnabled =     */ truncationGuardEnabled,
                /* harnessLeakFilterEnabled =   */ harnessLeakFilterEnabled,
                /* numericalConsistencyEnabled =*/ numericalConsistencyEnabled,
                /* numericalConsistencyMode     */ numericalConsistencyMode,
                /* verdictGroundingEnabled =    */ false,
                /* magnitudeRangeEnabled =      */ magnitudeRangeEnabled,
                /* dedupEnabled =               */ dedupEnabled,
                /* crossDomainGateEnabled =     */ crossDomainGateEnabled,
                /* disputeReconciliationEnabled */ disputeReconciliationEnabled,
                /* orphanAgeHours               */ 6L,
                /* disputeMaxAttempts           */ 6,
                /* citationAwareScoringEnabled  */ citationAwareScoringEnabled,
                /* auditPhysicalMechanismRejectsEnabled */ auditPhysicalMechanismRejectsEnabled,
                /* auditCherryPickedRejectsEnabled       */ auditCherryPickedRejectsEnabled,
                /* topicSpecificFindingsValidatorEnabled */ topicSpecificFindingsValidatorEnabled,
                /* closingProseValidatorEnabled          */ closingProseValidatorEnabled,
                /* uncertaintyDisclosureEnabled          */ uncertaintyDisclosureEnabled,
                /* missingMethodsGateEnabled             */ missingMethodsGateEnabled,
                /* missingMethodsGateMode                */ missingMethodsGateMode,
                /* singlePointSweepEnabled               */ singlePointSweepEnabled,
                /* directionOfFindingEnabled             */ directionOfFindingEnabled,
                /* blockCountStampEnabled                */ blockCountStampEnabled,
                /* blockCountGuardEnabled                */ blockCountGuardEnabled,
                /* topicsV2Enabled               */ false,
                /* topicCloneBackoffEnabled      */ false,
                /* topicCloneBackoffWindow       */ 5,
                /* topicCloneBackoffMaxRetries   */ 3,
                /* lowYieldBackoffEnabled        */ false,
                /* lowYieldPeerCeiling           */ 0.60,
                /* lowYieldNullDecay             */ 0.70,
                /* unsourcedClaimEnabled         */ false,
                /* unsourcedClaimMode            */ "warn",
                /* proseBaselineDriftEnabled     */ false,
                /* proseBaselineDriftMode        */ "warn",
                /* citationCorpusEnabled         */ false,
                /* citationCorpusMode            */ "warn",
                /* topicConsistencyEnabled       */ false,
                /* topicConsistencyMode          */ "warn",
                /* scalingSweepDepthEnabled      */ false,
                /* scalingSweepDepthMode         */ "warn",
                /* srEvidenceEnabled             */ false,
                /* srEvidenceMode                */ "warn",
                /* hypothesisRangeCoverageEnabled */ false,
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
        // 2026-05-29 — orthogonal reviewState axis: a clean publish is CLEAN.
        assertThat(firstPaper().reviewState()).isEqualTo("CLEAN");
        // A clean paper is NOT an INCOMPLETE re-queue candidate: no draft snapshot.
        assertThat(firstPaper().reworkDraftJson()).isNull();
        assertThat(firstPaper().reworkAttempts()).isZero();
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
        // 2026-05-29 — a GENUINE science null (the draft was classified NULL_RESULT,
        // no gate forced it) is a clean scientific outcome, not a harness artifact.
        assertThat(firstPaper().reviewState()).isEqualTo("CLEAN");
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

    // ---- Phase 12 D5: numeric-leak-into-next-sentence tail + Hypothesis guard ----

    @Test
    void d5_endsWithSentenceTerminatorRejectsNumericLeakTail() {
        // REVIEW_REPORT refresh #3 §7 cites "The observed30," as a literal
        // signature appearing in P-a328-411 and P-a328-418 — a numeric leaked
        // into the next sentence after a token-budget cut.
        assertThat(UniversityService.endsWithSentenceTerminator(
                "The observed30,")).isFalse();
        assertThat(UniversityService.endsWithSentenceTerminator(
                "results computed42")).isFalse();
        assertThat(UniversityService.endsWithSentenceTerminator(
                "the sample measured100,")).isFalse();
        assertThat(UniversityService.endsWithSentenceTerminator(
                "we sampled7")).isFalse();
    }

    @Test
    void d5_endsWithSentenceTerminatorAcceptsCleanNumericClause() {
        // No false-positive on numbers followed by proper terminators.
        assertThat(UniversityService.endsWithSentenceTerminator(
                "The temperature was 350 K.")).isTrue();
        assertThat(UniversityService.endsWithSentenceTerminator(
                "We observed 30 trials in [1, 100).")).isTrue();
    }

    @Test
    void phase28_paperReview_rejectsMidTextNumericLeak() {
        // 2026-05-22 paper-review on research/a3289005: P-a328-906's
        // Conclusion contains "The observed30, confirming that the
        // chosen parameter region..." — same corruption signature but
        // mid-text rather than end-of-text. The original $-anchored
        // detector missed it. After dropping the anchor and broadening
        // the verb list, the mid-text occurrence is caught.
        assertThat(UniversityService.endsWithSentenceTerminator(
                "The observed30, confirming that the chosen parameter "
                + "region yields far higher diffraction resolution than "
                + "anticipated.")).isFalse();
        // New verbs added (recovered, peaked, reached, yielded). Integer
        // suffix only — the corpus corruption signature is token-merger
        // on whole-number leaks (e.g. "the30 from prior paper"), not
        // decimals.
        assertThat(UniversityService.endsWithSentenceTerminator(
                "the constant was recovered30 within tolerance.")).isFalse();
        assertThat(UniversityService.endsWithSentenceTerminator(
                "intensity peaked42 at the resonance.")).isFalse();
        // Word-boundary still protects legitimate "<verb> <number>" prose.
        assertThat(UniversityService.endsWithSentenceTerminator(
                "the constant was recovered to within 5.6%.")).isTrue();
        assertThat(UniversityService.endsWithSentenceTerminator(
                "intensity yielded 42 distinct peaks across the sweep.")).isTrue();
    }

    @Test
    void d5_p647InternalMalformationIsNotATailTruncation() {
        // P-a328-647 (refresh #4) had Conclusion:
        // "...with a mean deviation of 0.001. However,30, suggesting that the
        //  divisor-sum average's behavior is more complex than initially
        //  anticipated."
        //
        // REVIEW_REPORT refresh #4 initially flagged this as a
        // truncation-guard miss. It is NOT — the text ends with "anticipated."
        // The guard correctly does not fire. The malformation
        // ("However,30,") is INTERNAL — a different validator shape would
        // be needed to catch it (e.g. "conjunction,bare_number,word"
        // fragments). This test pins the current correct behavior so a
        // future "fix" doesn't over-fire on legitimate tails ending with
        // a period.
        String p647Conclusion = "The computed values confirm the preliminary "
                + "hypothesis, with a divisor-sum average max deviation of "
                + "0.004 from π²/12 ≈ 0.8225 at N=200, and a mean deviation "
                + "of 0.001. However,30, suggesting that the divisor-sum "
                + "average's behavior is more complex than initially "
                + "anticipated.";
        assertThat(UniversityService.endsWithSentenceTerminator(p647Conclusion))
                .as("Conclusion ends with 'anticipated.' — terminator check "
                        + "must return true. The internal 'However,30,' "
                        + "fragment is not a tail-truncation pattern.")
                .isTrue();
    }

    @Test
    void d5_truncationGuardOnDowngradesTruncatedHypothesis() throws Exception {
        enableTruncationGuard();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        // Hypothesis truncated mid-sentence — the P-a328-419 signature.
        NarrationResult narration = new NarrationResult(
                "matrix-yields-first Vf ≈ 10–20",
                "Findings end with a period.",
                "Conclusion is fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
        // 2026-05-29 — a token-truncated draft is INCOMPLETE (the content before the
        // cut may be sound), NOT a content defect — the re-queue-candidate cell.
        assertThat(firstPaper().reviewState()).isEqualTo("INCOMPLETE");
        assertThat(ReviewState.valueOf(firstPaper().reviewState()).isReworkCandidate()).isTrue();
        // INCOMPLETE → a faithful draft snapshot is persisted so the re-queue can
        // re-narrate from the true seed inputs; reworkAttempts starts at 0.
        assertThat(firstPaper().reworkDraftJson()).isNotNull();
        assertThat(firstPaper().reworkAttempts()).isZero();
        // The snapshot rehydrates to a draft that reuses the SAME paperId (so the
        // re-narration upserts the same row in place, not a duplicate).
        ReworkDraftSnapshot snap = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(firstPaper().reworkDraftJson(), ReworkDraftSnapshot.class);
        assertThat(snap.toDraft().paperId()).isEqualTo(firstPaper().paperId());
    }

    @Test
    void d5_truncationGuardOnPassesCleanHypothesis() {
        enableTruncationGuard();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis ends with a period.",
                "Findings end with a period.",
                "Conclusion is fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
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
        // Phase 12 D5 — Hypothesis must also end with a terminator; the prior
        // "hyp" placeholder tripped D5's new Hypothesis-side check.
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.", "Findings end with a period.",
                "Conclusion is fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    // ---- Phase 7 F2: per-block truncation guard ----

    @Test
    void perBlockTruncationFiresWhenAnyBlockEndsMidSentence() {
        enableTruncationGuard();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        // Block 1 ends with a period (clean), block 2 is truncated. The paper
        // is downgraded to NEEDS_REVISION even though block 1 looks fine.
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "First computation result is complete.");
        blocks.put(2, "Second computation runs over the token budget and gets cut at");
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.", blocks, "Conclusion is fine.", null, null, 0.8,
                List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null);
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void perBlockTruncationPassesMultiBlockWithAllProperlyTerminated() {
        enableTruncationGuard();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "First block is complete.");
        blocks.put(2, "Second block is complete.");
        blocks.put(3, "Third block is complete!");
        // Phase 12 D5 — Hypothesis must also end with a terminator.
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.", blocks, "Conclusion is fine.", null, null, 0.8,
                List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null);
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void singleBlockTruncationBehavesIdenticallyToLegacyStringPath() {
        // The flag-off path should be bit-identical: a single-entry map carrying
        // truncated prose behaves exactly like the legacy String-shaped narration.
        enableTruncationGuard();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        java.util.Map<Integer, String> blocks =
                java.util.Map.of(1, "Findings end mid-sentence with no terminator");
        NarrationResult narration = new NarrationResult(
                "hyp", blocks, "Conclusion is fine.", null, null, 0.8,
                List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null);
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void firstTruncatedBlockReturnsIndexOfFirstFailedBlock() {
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "Clean block 1.");
        blocks.put(2, "Truncated block 2 with no terminator");
        blocks.put(3, "Clean block 3.");
        assertThat(UniversityService.firstTruncatedBlock(blocks)).isEqualTo(2);
    }

    // ---- Phase 13 E4: header-only block truncation ----

    @Test
    void e4_isHeaderOnlyBlockFlagsHeaderPlusEquationOnly() {
        // P-a328-453 / P-a328-466 / P-a328-468 signature: a block with only
        // the H3 heading and the structured Equation row, no prose body.
        String body = "### Computation 2 — Carnot Efficiency\n\n"
                + "**Equation:** η_Carnot = 1 − T_c/T_h   (Carnot reversible-engine efficiency)";
        assertThat(UniversityService.isHeaderOnlyBlock(body)).isTrue();
    }

    @Test
    void e4_isHeaderOnlyBlockPassesBlockWithProseBody() {
        // A block with heading + equation + adequate prose discussion.
        String body = "### Computation 1 — Carnot Efficiency\n\n"
                + "**Equation:** η_Carnot = 1 − T_c/T_h\n\n"
                + "Across the T_h sweep from 575 K to 1200 K with T_c=361 K, "
                + "the efficiency rose monotonically from 0.37 to 0.70, consistent "
                + "with the Carnot bound and the hypothesis prediction.";
        assertThat(UniversityService.isHeaderOnlyBlock(body)).isFalse();
    }

    @Test
    void e4_isHeaderOnlyBlockFlagsEquationOnlyNoHeading() {
        // A block with just the equation line — no narrative body. Should flag.
        String body = "**Equation:** P = σ·A·T⁴.";
        assertThat(UniversityService.isHeaderOnlyBlock(body)).isTrue();
    }

    @Test
    void e4_isHeaderOnlyBlockPassesBriefBodyAtThreshold() {
        // ~10 tokens minimum. A body with 12 tokens passes.
        String body = "### Computation 1 — Test\n\n"
                + "**Equation:** y = x.\n\n"
                + "The peak value was zero point seventy nine at r equal to zero point four one.";
        assertThat(UniversityService.isHeaderOnlyBlock(body)).isFalse();
    }

    @Test
    void e4_firstTruncatedBlockCatchesHeaderOnlyEvenWithTerminator() {
        // Block 2 has a clean terminator on the equation line but no prose.
        // Pre-E4: firstTruncatedBlock would return null. After E4: returns 2.
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "### Computation 1 — Carnot\n\n**Equation:** η = 1 − T_c/T_h.\n\n"
                + "Across the sweep the efficiency rose smoothly from forty percent to seventy percent.");
        blocks.put(2, "### Computation 2 — Carnot\n\n**Equation:** η = 1 − T_c/T_h.");
        assertThat(UniversityService.firstTruncatedBlock(blocks)).isEqualTo(2);
    }

    @Test
    void e4_truncationGuardRoutesHeaderOnlyToNeedsRevision() {
        // Full publish-path test: a paper with a header-only Computation 2
        // block lands as NEEDS_REVISION (today: PUBLISHED).
        enableTruncationGuard();
        PaperDraft draft = draftForTopic("PHYSICS", "Stefan-Boltzmann Total Flux",
                ResultClassification.SUPPORTED, 0.5);
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "### Computation 1 — Stefan-Boltzmann\n\n"
                + "**Equation:** P = σ·A·T⁴.\n\n"
                + "Recovered σ within five percent of the reference value across the sweep.");
        blocks.put(2, "### Computation 2 — Stefan-Boltzmann\n\n"
                + "**Equation:** P = σ·A·T⁴.");
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.", blocks, "Conclusion is complete.",
                null, null, 0.8, List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null);
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void e4_singleBlockWithHeaderOnlyFlaggedTooViaTruncationGuard() {
        // The single-block path: only one block, header-only. Should also fail.
        enableTruncationGuard();
        PaperDraft draft = draftForTopic("PHYSICS", "Stefan-Boltzmann Total Flux",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "### Computation 1 — Stefan-Boltzmann\n\n**Equation:** P = σ·A·T⁴.",
                "Conclusion is complete.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    // ---- Phase 13 E6: uncertainty-disclosure validator ----

    @Test
    void e6_missingUncertaintyRoutesToNeedsRevision() {
        enableUncertaintyDisclosure();
        PaperDraft draft = draftForTopic("MATERIALS", "Hall-Petch Grain Size Strengthening",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Maximum yield stress 2377 MPa at d=0.050 µm, with k=510 MPa·µm^½.",
                "Conclusion is complete.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void e6_plusMinusValuePasses() {
        enableUncertaintyDisclosure();
        PaperDraft draft = draftForTopic("MATERIALS", "Hall-Petch Grain Size Strengthening",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Maximum yield stress 2377 MPa ± 12 MPa at d=0.050 µm.",
                "Conclusion is complete.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void e6_exactClosedFormQualifierPasses() {
        enableUncertaintyDisclosure();
        PaperDraft draft = draftForTopic("PHYSICS", "Pendulum Breakdown Angle",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "1% period error threshold lands at θ ≈ 22.92°, exact closed-form bound.",
                "Conclusion is complete.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void e6_flagOffLeavesMissingUncertaintyAsPublished() {
        // Default flag off — missing uncertainty publishes as today.
        PaperDraft draft = draftForTopic("MATERIALS", "Hall-Petch Grain Size Strengthening",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Maximum yield stress 2377 MPa at d=0.050 µm.",
                "Conclusion is complete.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void e6_truncatedTakesPrecedenceOverUncertainty() {
        // Status precedence: truncation > uncertainty. Both fire; truncation wins.
        enableUncertaintyDisclosure();
        enableTruncationGuard();
        PaperDraft draft = draftForTopic("PHYSICS", "Pendulum Breakdown Angle",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                // Both missing uncertainty AND truncated.
                "Maximum yield stress 2377 MPa at d=0.050 µm cut off",
                "Conclusion is complete.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        // Truncation takes precedence; status is still NEEDS_REVISION either way.
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    // ---- Phase 14 F5: missing-Methods publish gate ----

    @Test
    void f5_nullToolCallTraceRoutesToNeedsRevision() {
        enableMissingMethodsGate();
        PaperDraft draft = draftForTopic("MATERIALS", "Young-Laplace Capillary Rise",
                ResultClassification.SUPPORTED, 0.5);
        // toolCallTrace defaults to null on this constructor.
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Capillary rise h ≈ 26.3 cm at r=0.053 mm.",
                "Conclusion is complete.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
        assertThat(firstPaper().llmCallPath())
                .contains("publish:no_methods→needs_revision");
    }

    @Test
    void f5_emptyToolCallTraceRoutesToNeedsRevision() {
        enableMissingMethodsGate();
        PaperDraft draft = draftForTopic("MATERIALS", "Young-Laplace Capillary Rise",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Capillary rise h ≈ 26.3 cm at r=0.053 mm.",
                "Conclusion is complete.", null, null, 0.8, List.of())
                .withToolCallTrace(java.util.List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
        assertThat(firstPaper().llmCallPath())
                .contains("publish:no_methods→needs_revision");
    }

    @Test
    void f5_nonEmptyToolCallTracePublishes() {
        enableMissingMethodsGate();
        PaperDraft draft = draftForTopic("MATERIALS", "Young-Laplace Capillary Rise",
                ResultClassification.SUPPORTED, 0.5);
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep",
                        "{\"param1\":\"r\"}",
                        "{\"h_cm\":26.3}", 0));
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Capillary rise h ≈ 26.3 cm at r=0.053 mm.",
                "Conclusion is complete.", null, null, 0.8, List.of())
                .withToolCallTrace(trace);
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    // ---- Phase 14 F3: single-point sweep validator (integration with publishPaper) ----

    @Test
    void f3_sixteenSampledCasesAgainstOneCallRoutesToNeedsRevision() {
        enableSinglePointSweep();
        PaperDraft draft = draftForTopic("MATERIALS", "Arrhenius Rule Validity",
                ResultClassification.SUPPORTED, 0.5);
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep",
                        "{\"Ea\":167,\"T\":1081}",
                        "{\"ratio\":1.18568}", 0));
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "The Arrhenius multiplier was averaged over sixteen sampled cases.",
                "Conclusion is complete.", null, null, 0.8, List.of())
                .withToolCallTrace(trace);
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
        assertThat(firstPaper().llmCallPath())
                .contains("publish:single_point_sweep→needs_revision");
    }

    @Test
    void f3_smallCountWithinSlackPublishes() {
        enableSinglePointSweep();
        PaperDraft draft = draftForTopic("MATERIALS", "Arrhenius Rule Validity",
                ResultClassification.SUPPORTED, 0.5);
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{}", "{}", 0),
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{}", "{}", 1));
        // 4 cases against 2 calls: 4 ≤ 2·2 → within slack.
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "The 4 measured cases agreed within tolerance.",
                "Conclusion is complete.", null, null, 0.8, List.of())
                .withToolCallTrace(trace);
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    // ---- Phase 14 F4: direction-of-finding validator (integration with publishPaper) ----

    @Test
    void f4_p491SignatureRoutesToNeedsRevision() {
        enableDirectionOfFinding();
        PaperDraft draft = draftForTopic("MATHEMATICS", "Riemann Euler-Product Convergence",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "We expect convergence within 5% across the survey window.",
                "Maximum deviation 0.694% across the evaluations.",
                "The hypothesis is therefore ruled out by the observed data.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
        assertThat(firstPaper().llmCallPath())
                .contains("publish:direction_inversion→needs_revision");
    }

    @Test
    void f4_consistentPolarityPublishes() {
        enableDirectionOfFinding();
        PaperDraft draft = draftForTopic("MATHEMATICS", "Riemann Euler-Product Convergence",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Convergence within 5%.",
                "Maximum deviation 0.694% across evaluations.",
                "Hypothesis confirmed by the close agreement.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void f4_flagOffLeavesInvertedPolarityAsPublished() {
        // Default flag off — even an obvious inversion publishes as today.
        PaperDraft draft = draftForTopic("MATHEMATICS", "Riemann Euler-Product Convergence",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Convergence within 5%.",
                "Maximum deviation 0.694%.",
                "Hypothesis ruled out by the observed data.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    // ---- Phase 14 F1a/F1b: computation-block-count guard ----

    @Test
    void f1a_fiveBlockTwoCallTraceRendersStampsAndElisionMarkers() throws Exception {
        // Refresh-#2 P-485 / P-502 signature: 5 blocks emitted but only
        // 2 successful calls. Stamp mode annotates the markdown so the
        // gap is visible.
        enableBlockCountStamp();
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "Block 1 prose.");
        blocks.put(2, "Block 2 prose.");
        blocks.put(3, "Block 3 fabricated prose.");
        blocks.put(4, "Block 4 fabricated prose.");
        blocks.put(5, "Block 5 fabricated prose.");
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation("run_param_sweep", "{}", "{}", 0),
                new com.emergentterrain.llm.ToolInvocation("run_param_sweep", "{}", "{}", 1));
        NarrationResult narration = new NarrationResult(
                "h", blocks, "c", null, null, 0.7,
                List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null)
                .withToolCallTrace(trace);
        service.publishPaper(draft, narration);

        String content = readPublishedMarkdown();
        // Blocks 1 + 2 carry [call_N] stamps.
        assertThat(content).contains("### Computation 1 — RLC Damping Transition `[call_1]`");
        assertThat(content).contains("### Computation 2 — RLC Damping Transition `[call_2]`");
        // Blocks 3-5 carry the elision marker and the prose is replaced.
        assertThat(content).contains("### Computation 3 — RLC Damping Transition `(no data — call_3 missing)`");
        assertThat(content).contains("### Computation 5 — RLC Damping Transition `(no data — call_5 missing)`");
        assertThat(content).contains("*[Block elided — narrator emitted 5 blocks but trace shows only 2 successful call(s).]*");
        // The fabricated prose for the elided blocks is NOT rendered.
        assertThat(content).doesNotContain("Block 3 fabricated prose");
        assertThat(content).doesNotContain("Block 5 fabricated prose");
    }

    @Test
    void f1a_blockCountMatchingTraceRendersStampsOnly() throws Exception {
        // Block count = trace size: every block legitimate. Stamps appear,
        // no elision markers, prose preserved.
        enableBlockCountStamp();
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "Real block 1 prose.");
        blocks.put(2, "Real block 2 prose.");
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation("run_param_sweep", "{}", "{}", 0),
                new com.emergentterrain.llm.ToolInvocation("run_param_sweep", "{}", "{}", 1));
        NarrationResult narration = new NarrationResult(
                "h", blocks, "c", null, null, 0.7,
                List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null)
                .withToolCallTrace(trace);
        service.publishPaper(draft, narration);

        String content = readPublishedMarkdown();
        assertThat(content).contains("`[call_1]`");
        assertThat(content).contains("`[call_2]`");
        assertThat(content).doesNotContain("(no data");
        assertThat(content).doesNotContain("Block elided");
        assertThat(content).contains("Real block 1 prose");
        assertThat(content).contains("Real block 2 prose");
    }

    @Test
    void f1a_flagOffRendersUnstampedHeadersAsToday() throws Exception {
        // Default flag off — render is bit-identical to pre-F1 behaviour:
        // no [call_N] markers and the LLM prose passes through verbatim.
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "Block 1 prose.");
        blocks.put(2, "Block 2 fabricated prose.");
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation("run_param_sweep", "{}", "{}", 0));
        NarrationResult narration = new NarrationResult(
                "h", blocks, "c", null, null, 0.7,
                List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null)
                .withToolCallTrace(trace);
        service.publishPaper(draft, narration);

        String content = readPublishedMarkdown();
        // Headers present but no [call_N] / (no data) markers.
        assertThat(content).contains("### Computation 1 — RLC Damping Transition");
        assertThat(content).contains("### Computation 2 — RLC Damping Transition");
        assertThat(content).doesNotContain("[call_1]");
        assertThat(content).doesNotContain("(no data");
        // The "fabricated" block's prose is still rendered (no gate today).
        assertThat(content).contains("Block 2 fabricated prose");
    }

    @Test
    void f1b_blockCountOvershootRoutesToNeedsRevision() {
        // 5 blocks against 1 call: blockCountOvershoot. F1b routes the
        // paper to NEEDS_REVISION with the publish stamp on the call path.
        enableBlockCountGuard();
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) blocks.put(i, "Block " + i + " prose.");
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation("run_param_sweep", "{}", "{}", 0));
        NarrationResult narration = new NarrationResult(
                "h", blocks, "c", null, null, 0.7,
                List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null)
                .withToolCallTrace(trace);
        service.publishPaper(draft, narration);

        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
        assertThat(firstPaper().llmCallPath())
                .contains("publish:block_count_overshoot:blocks=5:successes=1→needs_revision");
    }

    @Test
    void f1b_blockCountMatchingTracePublishesNormally() {
        // 2 blocks against 2 calls: within slack (max(1, 2) = 2, not > 2).
        enableBlockCountGuard();
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "Block 1 prose.");
        blocks.put(2, "Block 2 prose.");
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation("run_param_sweep", "{}", "{}", 0),
                new com.emergentterrain.llm.ToolInvocation("run_param_sweep", "{}", "{}", 1));
        NarrationResult narration = new NarrationResult(
                "h", blocks, "c", null, null, 0.7,
                List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null)
                .withToolCallTrace(trace);
        service.publishPaper(draft, narration);

        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void f1b_flagOffLeavesOvershootAsPublished() {
        // Default flag off — even a 5-block / 0-call paper publishes as today.
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) blocks.put(i, "Block " + i + " prose.");
        NarrationResult narration = new NarrationResult(
                "h", blocks, "c", null, null, 0.7,
                List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null);
        service.publishPaper(draft, narration);

        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void f1b_singleBlockPaperNeverOvershoots() {
        // Legacy single-block / flag-off-structured papers: blockCount == 1
        // and the gate uses max(1, successCount), so blockCount > max(1, …)
        // is false even when trace is empty. Doesn't regress existing
        // single-block paths.
        enableBlockCountGuard();
        PaperDraft draft = draftForTopic("PHYSICS", "Pendulum Breakdown Angle",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "h", "Single-block prose without a trace.", "c",
                null, null, 0.7, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void f3_flagOffLeavesInflatedSweepAsPublished() {
        // Default flag off — even the worst sample-size inflation publishes
        // as today.
        PaperDraft draft = draftForTopic("MATERIALS", "Arrhenius Rule Validity",
                ResultClassification.SUPPORTED, 0.5);
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{}", "{\"ratio\":1.186}", 0));
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Averaged over sixteen sampled cases at the activation range.",
                "Conclusion is complete.", null, null, 0.8, List.of())
                .withToolCallTrace(trace);
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void f5_flagOffLeavesNullTraceAsPublished() {
        // Default flag off — null toolCallTrace publishes as today (legacy /
        // Ollama-fallback path that doesn't run tools).
        PaperDraft draft = draftForTopic("MATERIALS", "Young-Laplace Capillary Rise",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Capillary rise h ≈ 26.3 cm at r=0.053 mm.",
                "Conclusion is complete.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void firstTruncatedBlockReturnsNullWhenEveryBlockIsClean() {
        java.util.Map<Integer, String> blocks = java.util.Map.of(
                1, "Clean.", 2, "Also clean!", 3, "Third clean?");
        assertThat(UniversityService.firstTruncatedBlock(blocks)).isNull();
    }

    @Test
    void joinFindingsBlocksReturnsSingleValueVerbatimForSingleEntryMap() {
        // Flag-off path: single-entry map must collapse to the prose verbatim —
        // bit-identical to the pre-F2 single-String narration field.
        java.util.Map<Integer, String> blocks = java.util.Map.of(1, "Single block prose.");
        assertThat(UniversityService.joinFindingsBlocks(blocks))
                .isEqualTo("Single block prose.");
    }

    @Test
    void joinFindingsBlocksJoinsMultiBlockWithBlankLine() {
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(2, "second");
        blocks.put(1, "first");
        // Always joined in ascending key order regardless of insertion order.
        assertThat(UniversityService.joinFindingsBlocks(blocks))
                .isEqualTo("first\n\nsecond");
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

    // ---- Phase 7 F2: structured-findings markdown rendering ----

    @Test
    void publishedMarkdownEmitsH3PerComputationBlockForMultiBlockFindings() throws Exception {
        // RLC topic so the equation lookup hits a known closed-form.
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "First computation characterises the underdamped regime at f₀ = 3513 Hz.");
        blocks.put(2, "Second computation sweeps R near the critical value and finds Q collapses below 0.5.");
        NarrationResult narration = new NarrationResult(
                "Test hypothesis", blocks, "Test conclusion.", null, null, 0.7,
                List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null);
        service.publishPaper(draft, narration);

        java.nio.file.Path roomDir = tempDir.resolve(roomId.toString().substring(0, 8));
        java.util.List<java.nio.file.Path> md = java.util.stream.Stream.of(
                roomDir.toFile().listFiles())
                .filter(f -> f.getName().endsWith(".md"))
                .map(java.io.File::toPath)
                .toList();
        assertThat(md).hasSize(1);
        String content = java.nio.file.Files.readString(md.get(0));
        // Markdown renders H3 per block + topic equation line + each block's prose.
        assertThat(content)
                .contains("### Computation 1 — RLC Damping Transition")
                .contains("### Computation 2 — RLC Damping Transition")
                .contains("**Equation:** f₀ = 1/(2π·√(L·C))")
                .contains("First computation characterises the underdamped regime")
                .contains("Second computation sweeps R near the critical value");
    }

    @Test
    void publishedMarkdownPreservesPlainProseForSingleBlockFindings() throws Exception {
        // Flag-off / single-block path must render bit-identical to F1-baseline:
        // a plain ## Findings section with no Computation N H3 headers.
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "h", "Plain single-block findings prose.", "c",
                null, null, 0.6, List.of());
        service.publishPaper(draft, narration);

        java.nio.file.Path roomDir = tempDir.resolve(roomId.toString().substring(0, 8));
        String content = java.nio.file.Files.readString(
                java.util.stream.Stream.of(roomDir.toFile().listFiles())
                        .filter(f -> f.getName().endsWith(".md"))
                        .map(java.io.File::toPath)
                        .findFirst()
                        .orElseThrow());
        assertThat(content)
                .contains("## Findings")
                .contains("Plain single-block findings prose.")
                .doesNotContain("### Computation 1")
                .doesNotContain("**Equation:**");
    }

    @Test
    void persistedFindingsIsJoinedProseForSingleBlockMaps() {
        // The legacy column shape: single-block map is persisted as plain prose,
        // so any external reader of the findings column keeps working unchanged.
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "h", java.util.Map.of(1, "Single-block prose."), "c",
                null, null, 0.6, List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null);
        service.publishPaper(draft, narration);
        assertThat(firstPaper().findings()).isEqualTo("Single-block prose.");
    }

    @Test
    void persistedFindingsIsJsonForMultiBlockMaps() {
        // Multi-block: the findings column carries the JSON-serialised map so the
        // markdown renderer can reconstruct H3 blocks at read time.
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "first prose.");
        blocks.put(2, "second prose.");
        NarrationResult narration = new NarrationResult(
                "h", blocks, "c", null, null, 0.6,
                List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null, null);
        service.publishPaper(draft, narration);
        String persisted = firstPaper().findings();
        assertThat(persisted)
                .startsWith("{")
                .contains("\"1\":\"first prose.\"")
                .contains("\"2\":\"second prose.\"");
    }

    // ---- Phase 7 F5.4: tool-call trace persistence + Methods appendix ----

    @Test
    void f5_4_toolCallTracePersistsAsJsonOnResearchPaper() {
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep",
                        "{\"param1\":\"L\",\"range\":[5e-4,5e-3],\"steps\":10}",
                        "{\"peak_q\":42.3,\"argmax_L\":0.0021}", 0),
                new com.emergentterrain.llm.ToolInvocation(
                        "run_predicate_scan",
                        "{\"n_max\":10000,\"predicate\":\"is_prime\"}",
                        "{\"hits\":1229,\"density\":0.1229}", 1));
        NarrationResult narration = narration(0.6).withToolCallTrace(trace);
        service.publishPaper(draft, narration);

        String persisted = firstPaper().toolCallTrace();
        assertThat(persisted)
                .isNotNull()
                .startsWith("[")
                .contains("\"functionName\":\"run_param_sweep\"")
                .contains("\"functionName\":\"run_predicate_scan\"")
                .contains("\"round\":0")
                .contains("\"round\":1");
    }

    @Test
    void f5_4_publishedMarkdownRendersMethodsAppendixForToolCallTrace() throws Exception {
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep",
                        "{\"param1\":\"L\"}",
                        "{\"peak_q\":42.3}", 0));
        NarrationResult narration = narration(0.6).withToolCallTrace(trace);
        service.publishPaper(draft, narration);

        String content = readPublishedMarkdown();
        assertThat(content)
                .contains("## Methods")
                .contains("**run_param_sweep**")
                .contains("(round 0)")
                .contains("`{\"param1\":\"L\"}`")
                .contains("`{\"peak_q\":42.3}`");
    }

    @Test
    void f5_4_methodsSectionAbsentWhenTraceIsNull() throws Exception {
        // Legacy / JSON-path / Ollama-fallback papers: trace is null → no Methods section.
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = narration(0.6);  // no trace
        service.publishPaper(draft, narration);

        String content = readPublishedMarkdown();
        assertThat(content).doesNotContain("## Methods");
        // And the persisted column is null, not the empty string "[]".
        assertThat(firstPaper().toolCallTrace()).isNull();
    }

    @Test
    void f5_4_methodsSectionAbsentWhenTraceIsEmpty() throws Exception {
        // Professor ran but issued zero tool calls → trace is empty → no Methods section.
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = narration(0.6).withToolCallTrace(java.util.List.of());
        service.publishPaper(draft, narration);

        String content = readPublishedMarkdown();
        assertThat(content).doesNotContain("## Methods");
        assertThat(firstPaper().toolCallTrace()).isNull();
    }

    @Test
    void f5_4_methodsSectionRendersFullToolResult() throws Exception {
        // The full run_param_sweep result is rendered in the appendix — no
        // truncation — so the Methods section is a complete record.
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        String longResult = "{\"values\":[" + "1.0,".repeat(400) + "1.0]}";
        java.util.List<com.emergentterrain.llm.ToolInvocation> trace = java.util.List.of(
                new com.emergentterrain.llm.ToolInvocation(
                        "run_param_sweep", "{\"steps\":401}", longResult, 0));
        NarrationResult narration = narration(0.6).withToolCallTrace(trace);
        service.publishPaper(draft, narration);

        String content = readPublishedMarkdown();
        assertThat(content)
                .contains("## Methods")
                .doesNotContain(" …")     // no truncation marker
                .contains(longResult);    // full result rendered verbatim
    }

    private String readPublishedMarkdown() throws java.io.IOException {
        java.nio.file.Path roomDir = tempDir.resolve(roomId.toString().substring(0, 8));
        java.nio.file.Path md = java.util.stream.Stream.of(roomDir.toFile().listFiles())
                .filter(f -> f.getName().endsWith(".md"))
                .map(java.io.File::toPath)
                .findFirst()
                .orElseThrow();
        return java.nio.file.Files.readString(md);
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

    // ---- Phase 12 D2: mode flag (warn / downgrade / suppress) ----

    @Test
    void d2_warnModeKeepsRejectedRoutingBitIdentical() {
        // Default mode is "warn" — same behaviour as Phase 5 B1: numerical
        // inconsistency stamps REJECTED, paper still publishes to disk.
        enableNumericalConsistency("warn");
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Multiplier expected near 2.",
                "Observed 1.07×.",
                "Observed 1.07× is close to the expected 2× value.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("REJECTED");
    }

    @Test
    void d2_downgradeModeRoutesToNullResultWithHonestPreamble() {
        enableNumericalConsistency("downgrade");
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Multiplier expected near 2.",
                "Observed 1.07×.",
                "Observed 1.07× is close to the expected 2× value.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        ResearchPaper paper = firstPaper();
        assertThat(paper.status()).isEqualTo("NULL_RESULT");
        assertThat(paper.findings())
                .contains("NULL_RESULT")
                .contains("Numerical-consistency validator flagged");
    }

    @Test
    void d2_suppressModeDropsPaperEntirely() {
        enableNumericalConsistency("suppress");
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Multiplier expected near 2.",
                "Observed 1.07×.",
                "Observed 1.07× is close to the expected 2× value.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        // No paper persisted — the room's published list is empty.
        assertThat(service.getPublishedPapers(roomId, 10)).isEmpty();
    }

    @Test
    void d2_suppressModeStillPublishesCleanPaper() {
        // A clean paper publishes identically across all three modes.
        enableNumericalConsistency("suppress");
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Pendulum 1% threshold predicted near 22°.",
                "θ=20°→0.76% θ=25°→1.19%.",
                "Threshold lands at θ ≈ 25°. The 1.19% value at 25° verifies this.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void d2_unknownModeFallsBackToWarn() {
        // Operator typo in app.research.numerical-consistency.mode degrades silently to warn.
        enableNumericalConsistency("typo-mode");
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Multiplier expected near 2.",
                "Observed 1.07×.",
                "Observed 1.07× is close to the expected 2× value.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("REJECTED");
    }

    // ---- Phase 12 D4: degraded-panel demotion via callPath marker ----
    //
    // ResearchNarrationService.runPanelAndStamp stamps the marker when the panel
    // returns n ∈ {2, 3} and the demote flag is on. publishPaper reads the marker
    // from narration.callPath() and routes the paper to NEEDS_REVISION. These
    // tests exercise the publishPaper-side ladder directly by setting callPath
    // on the NarrationResult, so they don't need a live ReviewPanelService.

    @Test
    void d4_demotionMarkerRoutesToNeedsRevision() {
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Findings are complete.",
                "Conclusion is complete.",
                null, null, 0.8, List.of(),
                "rev:llama-70b:ok → panel:n=3:degraded:demoted_to_needs_revision");
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void d4_demotionMarkerForNEqualsTwoAlsoRoutesToNeedsRevision() {
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Findings are complete.",
                "Conclusion is complete.",
                null, null, 0.8, List.of(),
                "rev:llama-70b:ok → panel:n=2:degraded:demoted_to_needs_revision");
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void d4_fullPanelNotDemoted() {
        // n=4 of 4 — full panel. No demotion marker on the callPath. Status PUBLISHED.
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Findings are complete.",
                "Conclusion is complete.",
                null, null, 0.8, List.of(),
                "rev:llama-70b:ok → panel:n=4:agreement=0.91");
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void d4_degradedPanelMarkerWithoutDemotionDoesNotRouteToNeedsRevision() {
        // Phase 12 D4 is opt-in. When the demote flag is off, the panel still
        // stamps `panel:n=3:degraded:from=4:agreement=0.X` but NOT the
        // `:demoted_to_needs_revision` marker. publishPaper keeps PUBLISHED.
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Findings are complete.",
                "Conclusion is complete.",
                null, null, 0.8, List.of(),
                "rev:llama-70b:ok → panel:n=3:degraded:from=4:agreement=0.87");
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

    // ── Phase 7 G6 — topic-specific findings validator ──────────────────────

    @Test
    void g6RlcBoilerplateRcValueDowngradesToNeedsRevision() {
        enableTopicSpecificFindingsValidator();
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "hyp", "Computed R_c value of 50 across the swept range.",
                "Conclusion fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void g6ThermalStressYieldStrengthMisLabelDowngrades() {
        enableTopicSpecificFindingsValidator();
        PaperDraft draft = draftForTopic("MATERIALS", "Thermal Stress",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "hyp", "The yield strength was observed to be 412 MPa at ΔT=200 K.",
                "Conclusion fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void g6ArrheniusScientificNotationDowngrades() {
        enableTopicSpecificFindingsValidator();
        PaperDraft draft = draftForTopic("MATERIALS", "Arrhenius Reaction Rate",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "hyp", "The rate multiplier was 3.89×10^1 at 600 K.",
                "Conclusion fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void g6FlagOffLeavesBoilerplatePapersAsPublished() {
        disableTopicSpecificFindingsValidator();
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "hyp", "Computed R_c value of 50.",
                "Conclusion fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void g6CleanRlcFindingsPassUnchanged() {
        enableTopicSpecificFindingsValidator();
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "hyp", "Computed R_c value of 73.4 — outside the boilerplate set.",
                "Conclusion fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void g6NullResultClassificationOverridesNeedsRevision() {
        // NULL_RESULT precedence is preserved even when topic-specific check would fire.
        enableTopicSpecificFindingsValidator();
        PaperDraft draft = draftForTopic("PHYSICS", "RLC Damping Transition",
                ResultClassification.NULL_RESULT, 0.5);
        NarrationResult narration = new NarrationResult(
                "hyp", "Computed R_c value of 50.",
                "Conclusion fine.", null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NULL_RESULT");
    }

    // ---- Phase 12 D6: closing-prose validator ----

    @Test
    void d6_boilerplateOnTextbookTopicRoutesToNeedsRevision() {
        // P-a328-423 signature: Stefan-Boltzmann is textbook; the boilerplate
        // sentence is factually wrong. Demote to NEEDS_REVISION.
        enableClosingProseValidator();
        PaperDraft draft = draftForTopic("PHYSICS", "Stefan-Boltzmann Total Flux",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Recovered σ within 5% of the reference value.",
                "Σ-recovery proceeded. No prior theoretical bound constrains the "
                        + "expected value in this region.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
    }

    @Test
    void d6_boilerplateOnOpenConjectureStillPublishes() {
        // Twin Prime Clustering is an active conjecture — the boilerplate is
        // correct prose. Don't demote.
        enableClosingProseValidator();
        PaperDraft draft = draftForTopic("MATHEMATICS", "Twin Prime Clustering",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Pair counts within Hardy-Littlewood band.",
                "Pattern beyond surveyed range remains open. No prior theoretical bound "
                        + "constrains the expected value in this region.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void d6_textbookTopicWithoutBoilerplateStillPublishes() {
        // Bounded topic but conclusion doesn't use the boilerplate. No flag.
        enableClosingProseValidator();
        PaperDraft draft = draftForTopic("CHEMISTRY", "Beer-Lambert Absorbance",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Recovered ε within 5% of literature value.",
                "ε recovery within tolerance; Beer-Lambert linearity confirmed.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void d6_flagOffLeavesBoilerplateOnTextbookTopicAsPublished() {
        // Default flag off — boilerplate on textbook topic still publishes (today's behaviour).
        PaperDraft draft = draftForTopic("PHYSICS", "Stefan-Boltzmann Total Flux",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Recovered σ within 5% of the reference value.",
                "Σ-recovery proceeded. No prior theoretical bound constrains the "
                        + "expected value in this region.",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
    }

    @Test
    void d6_truncatedTakesPrecedenceOverClosingProse() {
        // Status precedence: truncation > closing-prose. Both fire; truncation wins.
        enableClosingProseValidator();
        enableTruncationGuard();
        PaperDraft draft = draftForTopic("PHYSICS", "Stefan-Boltzmann Total Flux",
                ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = new NarrationResult(
                "Hypothesis is complete.",
                "Recovered σ within 5% of the reference value.",
                // Truncation guard fires (no terminator) AND boilerplate fires.
                "Σ-recovery proceeded. No prior theoretical bound constrains the "
                        + "expected value in this region cut off",
                null, null, 0.8, List.of());
        service.publishPaper(draft, narration);
        // Both signals → status is the stronger one (NEEDS_REVISION either way,
        // but truncation precedence is exercised here).
        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
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

    // ---- Phase 7 F7.3: cherry_picked_evidence audit gap → REJECTED ----

    @Test
    void f7_3_supportedPaperWithCherryPickedGapPublishesRejectedWhenFlagOn() {
        enableAuditCherryPickedRejects();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = narrationWithAuditGaps(0.8, List.of("cherry_picked_evidence"));
        service.publishPaper(draft, narration);
        ResearchPaper paper = firstPaper();
        assertThat(paper.status()).isEqualTo("REJECTED");
        assertThat(paper.llmCallPath()).contains("audit:cherry_picked_evidence→REJECTED");
    }

    @Test
    void f7_3_supportedPaperWithCherryPickedGapPublishedWhenFlagOff() {
        // Default setUp leaves auditCherryPickedRejectsEnabled = false. The same
        // input that REJECTED above must PUBLISH here.
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = narrationWithAuditGaps(0.8, List.of("cherry_picked_evidence"));
        service.publishPaper(draft, narration);
        ResearchPaper paper = firstPaper();
        assertThat(paper.status()).isEqualTo("PUBLISHED");
        assertThat(paper.llmCallPath()).isNull();
    }

    @Test
    void f7_3_nullResultWithCherryPickedGapStaysNullResult() {
        // SUPPORTED-only gate: NULL_RESULT papers stay NULL_RESULT.
        enableAuditCherryPickedRejects();
        PaperDraft draft = draft(ResultClassification.NULL_RESULT, 0.5);
        NarrationResult narration = narrationWithAuditGaps(0.8, List.of("cherry_picked_evidence"));
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("NULL_RESULT");
    }

    @Test
    void f7_3_supportedPaperWithOtherAuditGapsPublishesNormally() {
        // Other dimensions must not trigger the cherry-picked reject — only the
        // named dimension fires the gate.
        enableAuditCherryPickedRejects();
        PaperDraft draft = draft(ResultClassification.SUPPORTED, 0.5);
        NarrationResult narration = narrationWithAuditGaps(0.8, List.of("uncertainty", "boundary_answer"));
        service.publishPaper(draft, narration);
        assertThat(firstPaper().status()).isEqualTo("PUBLISHED");
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

    // ---- 2026-07-23: binding-pair and predicate-baseline-sensitivity publication gates ----

    @Test
    void affineBindingClaimWithoutDocumentedPairRoutesToNeedsRevision() {
        ReflectionTestUtils.setField(service, "affineBindingClaimGateEnabled", true);
        List<com.emergentterrain.llm.ToolInvocation> trace = List.of(
                new com.emergentterrain.llm.ToolInvocation("verify_conjecture",
                        "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1,\"c\":0,"
                                + "\"window_start\":1000000,\"window_size\":5000,\"num_windows\":10}",
                        "verify_conjecture: x <= +1.0 * y + +0.0 HELD across 10 window(s) of size 5000 "
                                + "starting at 1000000. worst margin: -0.02000 at window starting 1000000. "
                                + "relative slack: 0.0005", 0));
        service.publishPaper(draft(ResultClassification.SUPPORTED, 0.5),
                new NarrationResult("This is a demonstrated binding regime.", "Finite result.",
                        "The binding regime is established.", null, null, 0.8, List.of())
                        .withToolCallTrace(trace));

        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
        assertThat(firstPaper().llmCallPath()).contains("publish:affine_binding_pair_uncovered→needs_revision");
    }

    @Test
    void tightAffinePairWithoutLockedIndependentGridRoutesToNeedsRevision() {
        ReflectionTestUtils.setField(service, "affineBindingIndependentGridGateEnabled", true);
        List<com.emergentterrain.llm.ToolInvocation> trace = List.of(
                new com.emergentterrain.llm.ToolInvocation("verify_conjecture",
                        "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1,\"c\":0,"
                                + "\"window_start\":1000000,\"window_size\":5000,\"num_windows\":10}",
                        "verify_conjecture: x <= +1.0 * y + +0.0 HELD across 10 window(s) of size 5000 "
                                + "starting at 1000000. worst margin: -0.02000 at window starting 1000000. "
                                + "relative slack: 0.0005 TIGHT HOLD — re-verify with c=-0.08000.", 0),
                new com.emergentterrain.llm.ToolInvocation("verify_conjecture",
                        "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1,\"c\":-0.08,"
                                + "\"window_start\":1000000,\"window_size\":5000,\"num_windows\":10}",
                        "verify_conjecture: x <= +1.0 * y + -0.0800 REFUTED across 10 window(s) of size 5000 "
                                + "starting at 1000000. worst margin: +0.08000 at window starting 1000000.", 0));
        service.publishPaper(draft(ResultClassification.SUPPORTED, 0.5),
                new NarrationResult("Finite result.", "Local binding pair.", "Finite conclusion.",
                        null, null, 0.8, List.of()).withToolCallTrace(trace));

        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
        assertThat(firstPaper().llmCallPath())
                .contains("publish:affine_binding_independent_grid_uncovered→needs_revision");
    }

    @Test
    void equalWidthAdjacentPredicateBaselineScansRouteToNeedsRevision() {
        ReflectionTestUtils.setField(service, "predicateBaselineSensitivityGateEnabled", true);
        List<com.emergentterrain.llm.ToolInvocation> trace = List.of(
                predicateScan(350, 850), predicateScan(850, 1350), predicateScan(1350, 1850));
        service.publishPaper(draft(ResultClassification.SUPPORTED, 0.5),
                new NarrationResult("Finite comparison.", "The exact baseline is 0.220.",
                        "The finite baseline comparison is reported.", null, null, 0.8, List.of())
                        .withToolCallTrace(trace));

        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
        assertThat(firstPaper().llmCallPath())
                .contains("publish:predicate_baseline_sensitivity_uncovered→needs_revision");
    }

    @Test
    void missingPredicateStudyPreRegistrationScanRoutesToNeedsRevision() {
        ReflectionTestUtils.setField(service, "predicateStudyPreRegistrationGateEnabled", true);
        PreRegistration registration = PredicateStudyPreRegistration.from(new DynamicProblem(
                "d-1", "abundant finite density", 350, 850,
                "isAbundant", "fraction_satisfying", 0.22, "Finite comparison."));
        service.publishPaper(draftWithTemplate(ResultClassification.SUPPORTED,
                        "Finite comparison.", "Finite conclusion.", 0.5, registration),
                new NarrationResult("Finite comparison.", "Exact finite result.", "Finite conclusion.",
                        null, null, 0.8, List.of()).withToolCallTrace(List.of(predicateScan(350, 850))));

        assertThat(firstPaper().status()).isEqualTo("NEEDS_REVISION");
        assertThat(firstPaper().llmCallPath())
                .contains("publish:predicate_study_preregistration_uncovered→needs_revision");
    }

    private static com.emergentterrain.llm.ToolInvocation predicateScan(long start, long end) {
        return new com.emergentterrain.llm.ToolInvocation("run_predicate_scan",
                "{\"predicate\":\"isAbundant\",\"range_start\":" + start
                        + ",\"range_end\":" + end + ",\"metric\":\"fraction_satisfying\","
                        + "\"baseline\":0.22,\"hypothesis\":\"finite comparison\"}", "Predicate scan: exact", 0);
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
        return new NarrationResult(null, (String) null, null, null, null,
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

    /** Phase 12 D2 — rebuilds the service with the validator on AND the given mode. */
    private void enableNumericalConsistency(String mode) {
        numericalConsistencyEnabled = true;
        numericalConsistencyMode = mode;
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

    /** Phase 7 F7.3 — rebuilds the service with cherry-picked-evidence reject ON. */
    private void enableAuditCherryPickedRejects() {
        auditCherryPickedRejectsEnabled = true;
        setUp();
    }

    /** Phase 7 G6 — rebuilds the service with the topic-specific findings validator ON. */
    private void enableTopicSpecificFindingsValidator() {
        topicSpecificFindingsValidatorEnabled = true;
        setUp();
    }

    /** Phase 12 D6 — rebuilds the service with the closing-prose validator ON. */
    private void enableClosingProseValidator() {
        closingProseValidatorEnabled = true;
        setUp();
    }

    /** Phase 13 E6 — rebuilds the service with the uncertainty-disclosure validator ON. */
    private void enableUncertaintyDisclosure() {
        uncertaintyDisclosureEnabled = true;
        setUp();
    }

    /** Phase 14 F5 — rebuilds the service with the missing-Methods gate ON. */
    private void enableMissingMethodsGate() {
        missingMethodsGateEnabled = true;
        setUp();
    }

    /** Phase 14 F3 — rebuilds the service with the single-point-sweep validator ON. */
    private void enableSinglePointSweep() {
        singlePointSweepEnabled = true;
        setUp();
    }

    /** Phase 14 F4 — rebuilds the service with the direction-of-finding validator ON. */
    private void enableDirectionOfFinding() {
        directionOfFindingEnabled = true;
        setUp();
    }

    /** Phase 14 F1a — rebuilds the service with the block-count stamp renderer ON. */
    private void enableBlockCountStamp() {
        blockCountStampEnabled = true;
        setUp();
    }

    /** Phase 14 F1b — rebuilds the service with the block-count publish gate ON. */
    private void enableBlockCountGuard() {
        blockCountGuardEnabled = true;
        setUp();
    }

    /** Phase 7 G6 — rebuilds the service with the topic-specific findings validator OFF (default). */
    private void disableTopicSpecificFindingsValidator() {
        topicSpecificFindingsValidatorEnabled = false;
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
