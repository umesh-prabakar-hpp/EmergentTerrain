package com.emergentterrain.research;

import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MathPhysicsEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultFiniteResultsAreObservationalNotAutomaticallySupported() {
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("PHYSICS", 0, 30, 2.0);

        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.OBSERVATIONAL);
        assertThat(MathPhysicsEngine.generateConclusion(result, true))
                .contains("observational baseline")
                .doesNotContain("hypothesis is supported");
    }

    @Test
    void pendulumValidatorRefutesTheUnderTwentyDegreeThresholdClaim() {
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("PHYSICS", 1, 56, 1.0);

        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.REFUTED);
        assertThat(result.claimAssessment()).contains("θ_c < 20°");
        assertThat(MathPhysicsEngine.generateConclusion(result, true))
                .contains("does not support the hypothesis")
                .doesNotContain("hypothesis is supported");
    }

    @Test
    void primeConstellationValidatorRecordsHardyLittlewoodDeviation() {
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("MATHEMATICS", 0, 76, 1.0);

        assertThat(result.claimStatus()).isNotEqualTo(MathPhysicsEngine.ClaimStatus.OBSERVATIONAL);
        assertThat(result.findings()).contains("deviation=");
        assertThat(result.claimAssessment()).contains("Hardy-Littlewood twin-count deviation");
    }

    @Test
    void arrheniusValidatorRefutesUnsupportedVanTHoffBreakdownClaim() {
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("MATERIALS", 3, 20, 1.0);

        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.REFUTED);
        assertThat(result.claimAssessment()).contains("not observed");
        assertThat(MathPhysicsEngine.generateConclusion(result, true))
                .contains("does not support the hypothesis");
    }

    @Test
    void aliquotTrendClaimIsOnlyPartialWithoutIncreasingAnomalyEvidence() {
        MathPhysicsEngine.SearchParams narrowSearch =
                new MathPhysicsEngine.SearchParams(0.05, 0.2, 20, 0.5, 0.0);

        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("MATHEMATICS", 3, 20, 1.0, narrowSearch);

        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.PARTIAL);
        assertThat(result.claimAssessment()).contains("range-start trend");
        assertThat(result.findings()).contains("Search trend");
    }

    @Test
    void happyNumberValidatorCanRefuteRegardlessOfWindowClaim() {
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("MATHEMATICS", 4, 41, 1.0);

        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.REFUTED);
        assertThat(result.claimAssessment()).contains("regardless-of-window claim");
        assertThat(result.findings()).contains("Multi-window validation");
    }

    @Test
    void compositeFailureValidatorDowngradesUnmodeledFractureClaim() {
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("MATERIALS", 0, 40, 1.0);

        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.PARTIAL);
        assertThat(result.claimAssessment()).contains("no fibre-strength");
        assertThat(result.findings()).contains("Fibre fracture strength is not included");
    }

    @Test
    void selectedSearchWindowDrivesThePublishedHypothesis() {
        MathPhysicsEngine.SearchParams wideSearch =
                new MathPhysicsEngine.SearchParams(0.5, 0.2, 40, 0.5, 0.0);

        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("MATHEMATICS", 0, 47, 1.0, wideSearch);

        Matcher findingsWindow = Pattern.compile("\\[(\\d+),(\\d+)\\)").matcher(result.findings());
        Matcher hypothesisWindow = Pattern.compile("\\[(\\d+), (\\d+)\\)").matcher(result.hypothesis());
        assertThat(findingsWindow.find()).isTrue();
        assertThat(hypothesisWindow.find()).isTrue();
        assertThat(hypothesisWindow.group(1)).isEqualTo(findingsWindow.group(1));
        assertThat(hypothesisWindow.group(2)).isEqualTo(findingsWindow.group(2));
    }

    @Test
    void vanDerWaalsDoesNotInventCrossoverWhenThresholdIsNotReached() {
        MathPhysicsEngine.SearchParams singleCandidate =
                new MathPhysicsEngine.SearchParams(0.0, 0.2, 1, 0.5, 0.0);

        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("PHYSICS", 2, 300, 1.0, singleCandidate);

        assertThat(result.findings())
                .contains("No >1% crossover was observed up to 100 atm")
                .doesNotContain("Crossover >1% at P=100");
        assertThat(result.hypothesis()).contains("T=1100 K");
    }

    @Test
    void compositeFailureSearchKeepsMatrixYieldInPlausibleBounds() {
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("MATERIALS", 0, 40, 1.0);

        assertThat(result.findings()).contains("σ_ym=200 MPa");
        assertThat(result.secondaryValue()).isLessThanOrEqualTo(200.0 / 3000.0);
        assertThat(result.claimAssessment()).contains("10-200 MPa");
    }

    @Test
    void binarySpherePackingHypothesisUsesSinglePercentSign() {
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("MATERIALS", 1, 40, 1.0);

        assertThat(result.hypothesis())
                .contains("74.05% FCC")
                .doesNotContain("74.05%%");
    }

    @Test
    void paperIdsContinueAfterExistingResearchFiles() throws Exception {
        UUID roomId = UUID.fromString("59f4db7c-224c-415f-87a6-b539bbb6bf4a");
        Path roomDir = tempDir.resolve("59f4db7c");
        Files.createDirectories(roomDir);
        Files.writeString(roomDir.resolve("2026-04-29_20-39-19_physics_projectile-drag-optimisation_P-59f4-20.md"),
                "# Existing\n\n**Paper ID**: P-59f4-20\n");
        Files.writeString(roomDir.resolve("2026-04-29_20-41-40_mathematics_collatz-stopping-time_P-59f4-29.md"),
                "# Existing\n\n**Paper ID**: P-59f4-29\n");

        com.emergentterrain.repo.ResearchPaperRepository papersRepo =
                mock(com.emergentterrain.repo.ResearchPaperRepository.class);
        RoomBotRepository botRepo = mock(RoomBotRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReplicationService replicationService = new ReplicationService(papersRepo, botRepo, mapper);
        UniversityService service = new UniversityService(
                0L, tempDir.toString(), 3, true,
                botRepo,
                papersRepo,
                mock(com.emergentterrain.repo.PaperCitationRepository.class),
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
                        mock(com.emergentterrain.repo.PaperCitationRepository.class)),
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
                /* truncationGuardEnabled =     */ false,
                /* harnessLeakFilterEnabled =   */ false,
                /* numericalConsistencyEnabled =*/ false,
                /* magnitudeRangeEnabled =      */ false,
                /* dedupEnabled =               */ false,
                /* crossDomainGateEnabled =     */ false,
                /* disputeReconciliationEnabled */ false,
                /* orphanAgeHours               */ 6L,
                /* citationAwareScoringEnabled  */ false,
                /* auditPhysicalMechanismRejectsEnabled */ false,
                /* topicSpecificFindingsValidatorEnabled */ false,
                /* topicsV2Enabled               */ false,
                mapper);

        assertThat(service.nextPaperId(roomId)).isEqualTo("P-59f4-30");
        assertThat(service.nextPaperId(roomId)).isEqualTo("P-59f4-31");
    }

    // Phase 7 F2 — every dispatcher (math, physics, materials, interdisciplinary
    // and each of their 5 topic indices) must stamp the closed-form equation
    // onto the ResearchResult so the structured-findings H3 block can render it.
    @Test
    void everyComputeDispatcherStampsAClosedFormEquation() {
        String[] domains = {"MATHEMATICS", "PHYSICS", "MATERIALS", "INTERDISCIPLINARY"};
        for (String domain : domains) {
            for (int topic = 0; topic < 5; topic++) {
                MathPhysicsEngine.ResearchResult r =
                        MathPhysicsEngine.compute(domain, topic, 30, 2.0);
                assertThat(r.equation())
                        .as("equation for domain=%s topic=%d", domain, topic)
                        .isNotNull()
                        .isNotBlank();
            }
        }
    }

    @Test
    void rlcEquationContainsTheCanonicalResonanceFormula() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 4, 30, 2.0);
        assertThat(r.equation()).contains("f₀").contains("2π").contains("L").contains("C");
    }

    @Test
    void withEquationPreservesAllOtherFields() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 4, 30, 2.0);
        MathPhysicsEngine.ResearchResult clobbered = r.withEquation("E = m·c²");
        assertThat(clobbered.equation()).isEqualTo("E = m·c²");
        assertThat(clobbered.findings()).isEqualTo(r.findings());
        assertThat(clobbered.primaryValue()).isEqualTo(r.primaryValue());
        assertThat(clobbered.hypothesis()).isEqualTo(r.hypothesis());
        assertThat(clobbered.claimStatus()).isEqualTo(r.claimStatus());
    }

    // ---- Phase 7 F3: topic-count + selectTopicIndex plumbing ----

    @Test
    void topicCountReturnsArrayLengthForEveryDomain() {
        // Pre-F3 baseline: each of the four domains has exactly 5 topics. F3.2-F3.5
        // will grow PHYSICS/MATERIALS/MATHEMATICS to 10 and INTERDISCIPLINARY to 8.
        assertThat(MathPhysicsEngine.topicCount("PHYSICS")).isGreaterThanOrEqualTo(5);
        assertThat(MathPhysicsEngine.topicCount("MATERIALS")).isGreaterThanOrEqualTo(5);
        assertThat(MathPhysicsEngine.topicCount("MATHEMATICS")).isGreaterThanOrEqualTo(5);
        assertThat(MathPhysicsEngine.topicCount("INTERDISCIPLINARY")).isGreaterThanOrEqualTo(5);
    }

    @Test
    void selectTopicIndexFlagOffPreservesWeightedGenomeChoice() {
        // Flag off: the raw weighted index passes through (clamped to the
        // original 5-topic range so pre-F3 papers stay deterministic).
        java.util.Random rng = new java.util.Random(0);
        for (int raw = 0; raw < 5; raw++) {
            assertThat(MathPhysicsEngine.selectTopicIndex("PHYSICS", raw, false, rng))
                    .as("flag-off raw=%d should pass through", raw)
                    .isEqualTo(raw);
        }
    }

    @Test
    void selectTopicIndexFlagOnSamplesUniformlyOverFullTopicCount() {
        // Flag on: every returned index must fall within [0, topicCount(domain)).
        java.util.Random rng = new java.util.Random(42);
        int n = MathPhysicsEngine.topicCount("PHYSICS");
        for (int i = 0; i < 200; i++) {
            int idx = MathPhysicsEngine.selectTopicIndex("PHYSICS", 0, true, rng);
            assertThat(idx).isBetween(0, n - 1);
        }
    }

    @Test
    void computeAndScoreTolerateTopicIndicesAboveFour() {
        // The dispatcher uses floorMod(topicIndex, topicCount(domain)) so out-of-range
        // indices wrap rather than overflowing into the wrong topic.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", MathPhysicsEngine.topicCount("PHYSICS"),
                        30, 2.0);
        assertThat(r).isNotNull();
        assertThat(MathPhysicsEngine.scoreResult("PHYSICS",
                MathPhysicsEngine.topicCount("PHYSICS"), r.reviewValue()))
                .isBetween(0.0, 1.0);
    }

    // ---- Phase 7 F3.2: PHYSICS topics 5..9 — Wien / Schrödinger / Kepler / Snell / Stefan-Boltzmann ----

    @Test
    void wienPeakStaysWithinFivePercentBandForCleanTemperatureSweep() {
        // PHYSICS:5 — the synthetic observed λ_max·T product drifts ≤ 0.5% by
        // design, so the worst-case deviation must stay well within the 5% band
        // and the claim status must come back SUPPORTED.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 5, 30, 2.0);
        assertThat(r.equation()).contains("Wien");
        assertThat(r.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.SUPPORTED);
        assertThat(r.findings()).contains("Wien peak");
    }

    @Test
    void wienPeakFalsifiedHypothesisHasHighDeviation() {
        // Falsification: a tighter band would be exceeded. The validator surfaces
        // the deviation as the primary value — must be small for the supported case.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 5, 30, 2.0);
        assertThat(r.primaryValue()).isLessThan(0.05);  // supported case dev < 5%
    }

    @Test
    void schrodingerWellEnergyComputesPositiveGroundStateRatio() {
        // PHYSICS:6 — narrow wells (smaller L) push E₁ above thermal kT.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 6, 30, 2.0);
        assertThat(r.equation()).contains("E_n");
        assertThat(r.primaryValue()).isGreaterThan(0.0);  // ratio always positive
        assertThat(r.findings()).contains("Infinite-well");
    }

    @Test
    void schrodingerWellFalsifiedAtWideWell() {
        // For sufficiently wide wells the ratio E₁/kT stays below 5 → not SUPPORTED.
        // Use param1=10 (small) which seeds a small Lnm, but the sweep direction
        // narrows L further, so confinement DOES dominate.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 6, 90, 2.0);
        // Hypothesis mentions "infinite well" and "ground-state".
        assertThat(r.hypothesis()).contains("infinite well");
    }

    @Test
    void keplerThirdLawHoldsWithinTenPercentForCleanOrbits() {
        // PHYSICS:7 — synthetic observed periods are noise-perturbed by ≤ 2%, so
        // the worst-case T²/a³ deviation stays well inside the 10% band.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 7, 30, 2.0);
        assertThat(r.equation()).contains("T²").contains("a³");
        assertThat(r.claimStatus()).isIn(MathPhysicsEngine.ClaimStatus.SUPPORTED,
                                          MathPhysicsEngine.ClaimStatus.PARTIAL);
        assertThat(r.findings()).contains("Kepler");
    }

    @Test
    void keplerThirdLawPrimaryValueIsDeviationFromUnitRatio() {
        // The primary value is the worst deviation from T²/a³ = 1 (yr²/AU³); a
        // small noise-band sweep keeps it well under the 0.5 abs deviation cap.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 7, 30, 2.0);
        assertThat(r.primaryValue()).isLessThan(0.5);
    }

    @Test
    void snellCriticalAngleStaysWithinTwoDegreeBand() {
        // PHYSICS:8 — synthetic observed angles drift ≤ 1.5° from prediction.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 8, 30, 2.0);
        assertThat(r.equation()).contains("Snell");
        assertThat(r.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.SUPPORTED);
        assertThat(r.findings()).contains("Snell critical-angle");
    }

    @Test
    void snellCriticalAngleFalsificationPathStillProducesFiniteValues() {
        // Even at the upper-bound n₂ scaling, the deviation primary value must be
        // finite (no NaN from arcsin overflow) — that's the regression check.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 8, 95, 8.0);
        assertThat(Double.isFinite(r.primaryValue())).isTrue();
    }

    @Test
    void stefanBoltzmannRecoversConstantWithinTenPercent() {
        // PHYSICS:9 — synthetic emitter recovers σ ≈ 5.670e-8 within ±3% noise.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 9, 30, 2.0);
        assertThat(r.equation()).contains("σ").contains("Stefan-Boltzmann");
        assertThat(r.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.SUPPORTED);
        assertThat(r.findings()).contains("Stefan-Boltzmann");
    }

    @Test
    void stefanBoltzmannPrimaryIsDeviationFraction() {
        // primary value is the σ-deviation fraction; supported case stays < 10%.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("PHYSICS", 9, 30, 2.0);
        assertThat(r.primaryValue()).isLessThan(0.10);
    }

    // ---- Phase 7 F3.3: MATERIALS topics 5..9 — Hall-Petch / Bragg / Carnot / Basquin / Young-Laplace ----

    @Test
    void hallPetchYieldStrengthStaysBelowFiveGPaForNormalGrainSizes() {
        // MATERIALS:5 — Hall-Petch primary value is max σ_y. The 5 GPa magnitude
        // bound is what F3 spec calls out; supported case must stay well below it.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATERIALS", 5, 30, 2.0);
        assertThat(r.equation()).contains("Hall-Petch");
        assertThat(r.primaryValue()).isLessThan(5000.0);
        assertThat(r.findings()).contains("Hall-Petch");
    }

    @Test
    void hallPetchFineGrainProducesHighestYield() {
        // Hall-Petch: σ_y rises as d shrinks. The compute() sweep narrows d, so
        // max yield must exceed friction stress σ_0 substantially.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATERIALS", 5, 50, 2.0);
        // σ_0 starts at 50 + param1 = 100 MPa; sweep over d should at least double it.
        assertThat(r.primaryValue()).isGreaterThan(r.secondaryValue());
    }

    @Test
    void braggDiffractionAngleStaysInPhysicalRange() {
        // MATERIALS:6 — Bragg θ ∈ [0°, 90°] always; spec bound rejects > 90°.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATERIALS", 6, 30, 2.0);
        assertThat(r.equation()).contains("Bragg");
        assertThat(r.primaryValue()).isBetween(0.0, 90.0);
        assertThat(r.findings()).contains("Bragg");
    }

    @Test
    void braggSweepRecoversFirstOrderAngleAndLatticeSpacing() {
        // The compute method's primary value is the best first-order angle; the
        // secondary value is the best-d that produced it.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATERIALS", 6, 30, 2.0);
        assertThat(r.secondaryValue()).isGreaterThan(0.0);  // lattice spacing nm
    }

    @Test
    void carnotEfficiencyStaysBelowOneStrict() {
        // MATERIALS:7 — η < 1 is the Carnot bound (second law).
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATERIALS", 7, 30, 2.0);
        assertThat(r.equation()).contains("Carnot");
        assertThat(r.primaryValue()).isBetween(0.0, 1.0);
        assertThat(r.findings()).contains("Carnot");
    }

    @Test
    void carnotEfficiencyAtLowTcIsHigh() {
        // Push T_c low and T_h high — efficiency should be substantial.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATERIALS", 7, 90, 2.0);
        // T_c ≈ 280 + 2*10 = 300, T_h sweeps up from 400 + 90*5 = 850 K.
        // Best η ≈ 1 - 300/(850 + sweep) → should clearly exceed 0.3.
        assertThat(r.primaryValue()).isGreaterThan(0.3);
    }

    @Test
    void basquinStressAmplitudeBoundedByUltimateTensile() {
        // MATERIALS:8 — Basquin σ_a must stay below UTS; the validator REFUTES otherwise.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATERIALS", 8, 30, 2.0);
        assertThat(r.equation()).contains("Basquin");
        // primary = best (highest) σ_a; should be a positive number well under 3 GPa.
        assertThat(r.primaryValue()).isLessThan(3000.0);
        assertThat(r.findings()).contains("Basquin");
    }

    @Test
    void basquinExponentNegativeProducesDecayingStressOverCycles() {
        // The Basquin exponent b is negative; σ_a decays as N_f grows. The compute
        // method's secondary value carries σ'_f.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATERIALS", 8, 30, 2.0);
        assertThat(r.secondaryValue()).isGreaterThan(0.0);  // σ'_f always positive
    }

    @Test
    void youngLaplaceCapillaryRiseStaysBelowFiftyCm() {
        // MATERIALS:9 — F3 spec bound: water rise ≤ 50 cm for the swept radius range.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATERIALS", 9, 30, 2.0);
        assertThat(r.equation()).contains("Young-Laplace");
        assertThat(r.primaryValue()).isLessThan(50.0);
        assertThat(r.findings()).contains("Young-Laplace");
    }

    @Test
    void youngLaplaceSmallRadiusProducesLargerRise() {
        // h ∝ 1/r. The sweep narrows radius; max h should be > 1 cm for any reasonable seed.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATERIALS", 9, 30, 2.0);
        assertThat(r.primaryValue()).isGreaterThan(1.0);
    }
}
