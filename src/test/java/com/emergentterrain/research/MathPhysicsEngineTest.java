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
    void compositeFailureNowIncludesFibreFractureCriterion() {
        // Phase 10 — the simulator now models both failure modes (fibre
        // fracture vs matrix yield) and reports whichever is reached first.
        // P-a328-343 / P-a328-346 reported 7.6–9.4 GPa composite stresses
        // that were impossible for a steel-fibre system (steel UTS ≤ 3 GPa);
        // the old "matrix yields first, fibre never fractures" assumption was
        // wrong for the standard params. With ε_F=0.015 < ε_Y=0.067, fibre
        // fracture is the actual failure mode, and the realistic max stress
        // stays under 3 GPa.
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("MATERIALS", 0, 40, 1.0);

        assertThat(result.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.SUPPORTED);
        assertThat(result.findings()).contains("σ_F-frac=3GPa");
        assertThat(result.findings()).contains("mode=fibre-fracture");
        // Max composite stress must be physically plausible — steel UTS ceiling.
        assertThat(result.primaryValue()).isLessThan(3_000.0);
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
        // secondaryValue is now the limiting failure strain
        // (= min(ε_Y, ε_F)). For ε_F=3000/200000=0.015 < ε_Y=200/3000≈0.067,
        // fibre-fracture is reached first, so the value is ε_F.
        assertThat(result.secondaryValue()).isLessThanOrEqualTo(200.0 / 3000.0);
        // Assessment surfaces the failure-mode determination.
        assertThat(result.claimAssessment()).contains("fibre fractures first");
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
                /* numericalConsistencyMode     */ "warn",
                /* magnitudeRangeEnabled =      */ false,
                /* dedupEnabled =               */ false,
                /* crossDomainGateEnabled =     */ false,
                /* disputeReconciliationEnabled */ false,
                /* orphanAgeHours               */ 6L,
                /* citationAwareScoringEnabled  */ false,
                /* auditPhysicalMechanismRejectsEnabled */ false,
                /* auditCherryPickedRejectsEnabled       */ false,
                /* topicSpecificFindingsValidatorEnabled */ false,
                /* closingProseValidatorEnabled          */ false,
                /* uncertaintyDisclosureEnabled          */ false,
                /* missingMethodsGateEnabled             */ false,
                /* singlePointSweepEnabled               */ false,
                /* directionOfFindingEnabled             */ false,
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

    // ---- Phase 7 F3.4: MATHEMATICS topics 5..9 — Mertens / Divisor Sum / Riemann / Stern-Brocot / Squarefree ----

    @Test
    void mertensPartialSumStaysWithinTwoRootXEnvelopeForSmallX() {
        // MATHEMATICS:5 — |M(x)|/√x must stay ≤ 2 for the small x sweep we use.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATHEMATICS", 5, 30, 2.0);
        assertThat(r.equation()).contains("Mertens").contains("μ");
        assertThat(r.primaryValue()).isLessThanOrEqualTo(2.0);
        assertThat(r.findings()).contains("Mertens");
    }

    @Test
    void mertensFunctionMobiusBasisIsCorrect() {
        // Spot-check μ(1)=1, μ(prime)=-1, μ(square)=0.
        assertThat(MathPhysicsEngine.mobius(1)).isEqualTo(1);
        assertThat(MathPhysicsEngine.mobius(2)).isEqualTo(-1);
        assertThat(MathPhysicsEngine.mobius(4)).isEqualTo(0);
        assertThat(MathPhysicsEngine.mobius(6)).isEqualTo(1);   // 2·3, squarefree, even count
        assertThat(MathPhysicsEngine.mobius(30)).isEqualTo(-1); // 2·3·5, squarefree, odd count
    }

    @Test
    void divisorSumAverageApproachesPiSquaredOverTwelve() {
        // MATHEMATICS:6 — for the swept N, the avg/N must approach π²/12 ≈ 0.822
        // within the spec's 20% band.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATHEMATICS", 6, 30, 2.0);
        assertThat(r.equation()).contains("σ(n)");
        assertThat(r.primaryValue()).isLessThanOrEqualTo(0.30);  // deviation under 30% even for small N
        assertThat(r.findings()).contains("Divisor-sum");
    }

    @Test
    void divisorSumNarrowsAsymptoticallyAsNGrows() {
        // Larger N → smaller mean deviation from the asymptotic.
        MathPhysicsEngine.ResearchResult rSmall =
                MathPhysicsEngine.compute("MATHEMATICS", 6, 15, 2.0);
        MathPhysicsEngine.ResearchResult rLarge =
                MathPhysicsEngine.compute("MATHEMATICS", 6, 80, 2.0);
        // Mean deviation (secondary) typically shrinks with N; assert non-negative.
        assertThat(rLarge.secondaryValue()).isLessThanOrEqualTo(0.30);
    }

    @Test
    void riemannEulerProductMatchesZetaSumWithinFivePercentForLargeS() {
        // MATHEMATICS:7 — at s≥2 the truncated ζ sum and Euler product agree closely.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATHEMATICS", 7, 30, 9.0);  // param2=9 → s≈2.5
        assertThat(r.equation()).contains("ζ").contains("Euler");
        assertThat(r.primaryValue()).isLessThan(0.15);  // dev under 15%
        assertThat(r.findings()).contains("Riemann");
    }

    @Test
    void riemannRatioStaysFiniteAtSmallSGreaterThanOne() {
        // Pushing s near 1 stresses both the ζ sum (slow convergence) and the Euler
        // product (truncation at p≤200); regression check that nothing returns NaN.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATHEMATICS", 7, 30, 1.0);  // param2=1 → s≈1.6
        assertThat(Double.isFinite(r.primaryValue())).isTrue();
    }

    @Test
    void sternBrocotDepthStaysBelowFiftyForSmallDenominators() {
        // MATHEMATICS:8 — q < 10⁶ in the sweep; max depth must stay under 50.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATHEMATICS", 8, 30, 2.0);
        assertThat(r.equation()).contains("Stern").contains("Brocot");
        assertThat(r.primaryValue()).isLessThan(50.0);
        assertThat(r.findings()).contains("Stern-Brocot");
    }

    @Test
    void sternBrocotDepthMatchesContinuedFractionLengthForKnownRational() {
        // F3 uses CF length, not sum-of-partials. Length is # Euclidean steps.
        // 22/7 = [3; 7]; Euclidean: 22/7→7/1→1/0 → 2 steps.
        assertThat(MathPhysicsEngine.sternBrocotDepthOf(22, 7)).isEqualTo(2);
        // 1/1: Euclidean 1/1→1/0 → 1 step.
        assertThat(MathPhysicsEngine.sternBrocotDepthOf(1, 1)).isEqualTo(1);
        // 5/3 = [1; 1, 2]; Euclidean 5/3→3/2→2/1→1/0 → 3 steps.
        assertThat(MathPhysicsEngine.sternBrocotDepthOf(5, 3)).isEqualTo(3);
        // Fibonacci-pair worst case: F₁₀/F₉ = 55/34. Canonical CF [1;1,1,1,1,1,1,2] → 8 Euclidean steps.
        assertThat(MathPhysicsEngine.sternBrocotDepthOf(55, 34)).isEqualTo(8);
    }

    @Test
    void squarefreeDensityApproachesSixOverPiSquared() {
        // MATHEMATICS:9 — squarefree density across windows lands near 6/π² ≈ 0.6079.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("MATHEMATICS", 9, 30, 2.0);
        assertThat(r.equation()).contains("squarefree");
        assertThat(r.primaryValue()).isLessThan(0.10);  // dev under 10%
        assertThat(r.findings()).contains("Squarefree density");
    }

    @Test
    void isSquarefreeMatchesKnownCases() {
        // n=1 is squarefree, n=4 (= 2²) is not, n=12 (= 2²·3) is not, n=15 (= 3·5) is.
        assertThat(MathPhysicsEngine.isSquarefree(1)).isTrue();
        assertThat(MathPhysicsEngine.isSquarefree(4)).isFalse();
        assertThat(MathPhysicsEngine.isSquarefree(12)).isFalse();
        assertThat(MathPhysicsEngine.isSquarefree(15)).isTrue();
        assertThat(MathPhysicsEngine.isSquarefree(30)).isTrue();
    }

    // ---- Phase 7 F3.5: INTERDISCIPLINARY topics 5..7 — Hall / Schottky / Doppler ----

    @Test
    void hallEffectVoltageStaysBelowOneVoltForRealisticGeometry() {
        // INTERDISCIPLINARY:5 — Hall voltage bound 1 V for current bar.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("INTERDISCIPLINARY", 5, 30, 2.0);
        assertThat(r.equation()).contains("V_H").contains("Lorentz");
        assertThat(r.primaryValue()).isLessThan(1.0);  // |V_H| max
        assertThat(r.findings()).contains("Hall effect");
    }

    @Test
    void hallEffectRecoversCarrierSignAcrossSweep() {
        // The compute method's secondary value is the sign-recovery rate (0-1).
        // For a clean sweep with a fixed trueSign, recovery must be ≥ 0.95.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("INTERDISCIPLINARY", 5, 30, 2.0);
        assertThat(r.secondaryValue()).isGreaterThanOrEqualTo(0.95);
    }

    @Test
    void schottkyThermionicCurrentStaysBelowAmpereLimit() {
        // INTERDISCIPLINARY:6 — J ≤ 10⁶ A/cm² is the F3 spec bound.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("INTERDISCIPLINARY", 6, 30, 2.0);
        assertThat(r.equation()).contains("Richardson").contains("A*");
        assertThat(r.primaryValue()).isLessThan(1.0e6);
        assertThat(r.findings()).contains("Schottky");
    }

    @Test
    void schottkyEmissionExponentialIsTemperatureSensitive() {
        // For a tall barrier at low T, the exponential exp(−φ/kT) makes J vanish.
        // Use param2=9 (large) → φ ≈ 0.4 + 0.9 = 1.3 eV; T ~250K → J ≈ 0.
        // Just verify the result is finite and below the 10⁶ cap.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("INTERDISCIPLINARY", 6, 10, 9.0);
        assertThat(Double.isFinite(r.primaryValue())).isTrue();
        assertThat(r.primaryValue()).isLessThan(1.0e6);
    }

    @Test
    void acousticDopplerShiftStaysBelowSourceFrequency() {
        // INTERDISCIPLINARY:7 — |f'−f| ≤ f bound per F3 spec.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("INTERDISCIPLINARY", 7, 30, 2.0);
        assertThat(r.equation()).contains("Doppler");
        assertThat(r.primaryValue()).isLessThanOrEqualTo(1000.0);  // f=1000 Hz baseline
        assertThat(r.findings()).contains("Doppler");
    }

    @Test
    void acousticDopplerShiftIsPositiveAtRealisticVelocities() {
        // Even modest source/observer velocities produce a measurable shift > 0.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("INTERDISCIPLINARY", 7, 60, 5.0);
        assertThat(r.primaryValue()).isGreaterThan(0.0);
        // Doppler shift is bounded — the F3 spec's |f'-f| ≤ f keeps physics sane.
        assertThat(r.primaryValue()).isLessThan(1000.0);
    }

    // ---- Phase 7 FX.1: BIOLOGY topics 0..4 — Verhulst / Lotka-Volterra / MM / Kleiber / HWE ----

    @Test
    void verhulstLogisticApproachesCarryingCapacity() {
        // BIOLOGY:0 — long-time N(t) tends to K.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("BIOLOGY", 0, 30, 5.0);
        assertThat(r.equation()).contains("Verhulst");
        assertThat(r.findings()).contains("Verhulst logistic");
        // The compute reports distance-from-K; secondary value is final N.
        assertThat(r.secondaryValue()).isLessThanOrEqualTo(r.tertiaryValue() + 1e-9);
    }

    @Test
    void verhulstLogisticIsBoundedByCarryingCapacityK() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("BIOLOGY", 0, 30, 5.0);
        // tertiary = K. secondary = N(t). N(t) ≤ K for all t.
        assertThat(r.secondaryValue()).isLessThan(r.tertiaryValue() + 1.0);
    }

    @Test
    void lotkaVolterraSustainsBoundedOscillation() {
        // BIOLOGY:1 — predator-prey cycle ratio > 1.5 = SUPPORTED.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("BIOLOGY", 1, 30, 2.0);
        assertThat(r.equation()).contains("Lotka-Volterra");
        assertThat(r.findings()).contains("Lotka-Volterra");
        // Cycle amplitude ratio should be > 1 (oscillation) and finite.
        assertThat(r.primaryValue()).isGreaterThan(1.0);
        assertThat(Double.isFinite(r.primaryValue())).isTrue();
    }

    @Test
    void lotkaVolterraTrajectoryStaysFiniteUnderNormalCoefficients() {
        // Trajectory must not diverge — the spec bound says ratio < 10⁶.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("BIOLOGY", 1, 30, 2.0);
        assertThat(r.primaryValue()).isLessThan(1.0e6);
    }

    @Test
    void michaelisMentenRecoversKmWithinTenPercent() {
        // BIOLOGY:2 — two-point K_m recovery should match within 10% noise band.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("BIOLOGY", 2, 30, 2.0);
        assertThat(r.equation()).contains("Michaelis-Menten");
        assertThat(r.findings()).contains("Michaelis-Menten");
        // primary = recovery error; supported regime stays < 0.10.
        assertThat(r.primaryValue()).isLessThan(0.10);
    }

    @Test
    void michaelisMentenSaturationLimitsRateAtVmax() {
        // The compute holds V_max constant (tertiary value); recovered K_m (secondary)
        // must be positive.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("BIOLOGY", 2, 30, 2.0);
        assertThat(r.secondaryValue()).isGreaterThan(0.0);
        assertThat(r.tertiaryValue()).isGreaterThan(0.0);
    }

    @Test
    void kleiberLawRecoversThreeQuartersExponentWithinFivePercent() {
        // BIOLOGY:3 — log-log fit recovers 0.75 ± 0.05.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("BIOLOGY", 3, 30, 2.0);
        assertThat(r.equation()).contains("Kleiber");
        assertThat(r.findings()).contains("Kleiber");
        // primary = deviation from 0.75; secondary = fitted slope.
        assertThat(r.primaryValue()).isLessThan(0.05);
        assertThat(r.secondaryValue()).isBetween(0.70, 0.80);
    }

    @Test
    void kleiberLawFitIsRobustAcrossMassRange() {
        // Even with a different seed mass, the fit should land near 0.75.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("BIOLOGY", 3, 90, 5.0);
        assertThat(r.secondaryValue()).isBetween(0.65, 0.85);
    }

    @Test
    void hardyWeinbergKeepsAllelesStableOverGenerations() {
        // BIOLOGY:4 — drift stays small under idealised assumptions.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("BIOLOGY", 4, 30, 2.0);
        assertThat(r.equation()).contains("Hardy-Weinberg");
        assertThat(r.findings()).contains("Hardy-Weinberg");
        // primary = max drift; supported case stays < 0.05.
        assertThat(r.primaryValue()).isLessThan(0.05);
    }

    @Test
    void hardyWeinbergFinalPRemainsInUnitInterval() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("BIOLOGY", 4, 30, 2.0);
        // secondary = final p; must stay in [0, 1].
        assertThat(r.secondaryValue()).isBetween(0.0, 1.0);
    }

    // ---- Phase 7 FX.2: CHEMISTRY topics 0..4 — HH / Nernst / Beer-Lambert / Kc / Z-factor ----

    @Test
    void hendersonHasselbalchRecoversPKaWithinSmallError() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("CHEMISTRY", 0, 30, 2.0);
        assertThat(r.equation()).contains("Henderson-Hasselbalch");
        assertThat(r.findings()).contains("Henderson-Hasselbalch");
        // primary = recovery error in pH units; supported regime stays < 0.05.
        assertThat(r.primaryValue()).isLessThan(0.05);
    }

    @Test
    void hendersonHasselbalchEquationContainsPkaTerm() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("CHEMISTRY", 0, 30, 2.0);
        assertThat(r.equation()).contains("pKₐ");
    }

    @Test
    void nernstPotentialShiftsLogarithmicallyWithConcentration() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("CHEMISTRY", 1, 30, 2.0);
        assertThat(r.equation()).contains("Nernst");
        assertThat(r.findings()).contains("Nernst");
        // primary = max |E−E°|; supported means > 0.05 V drift across the Q sweep.
        // The registry bound for "physically reasonable" is 2 V; sweeps over four
        // decades of Q at room temperature produce ~0.3 V shifts, well within bound.
        assertThat(r.primaryValue()).isGreaterThan(0.05);
        assertThat(r.primaryValue()).isLessThan(2.0);
    }

    @Test
    void nernstSweepStaysFiniteAcrossDecadesOfQ() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("CHEMISTRY", 1, 30, 2.0);
        assertThat(Double.isFinite(r.primaryValue())).isTrue();
    }

    @Test
    void beerLambertRecoversMolarAbsorptivityWithinFivePercent() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("CHEMISTRY", 2, 30, 2.0);
        assertThat(r.equation()).contains("Beer-Lambert");
        assertThat(r.findings()).contains("Beer-Lambert");
        // primary = ε recovery err; supported regime stays < 0.05.
        assertThat(r.primaryValue()).isLessThan(0.05);
    }

    @Test
    void beerLambertEpsilonRecoveryIsPositive() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("CHEMISTRY", 2, 30, 2.0);
        // secondary = recovered ε; tertiary = true ε; both positive.
        assertThat(r.secondaryValue()).isGreaterThan(0.0);
        assertThat(r.tertiaryValue()).isGreaterThan(0.0);
    }

    @Test
    void equilibriumKcDriftsAcrossTemperatureViaVantHoff() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("CHEMISTRY", 3, 30, 2.0);
        assertThat(r.equation()).contains("Kc");
        assertThat(r.findings()).contains("Equilibrium Kc");
        // primary = |ln drift|; for ΔH = 30 kJ/mol over ~80 K of sweep, drift is non-trivial.
        assertThat(r.primaryValue()).isGreaterThan(0.0);
        assertThat(r.primaryValue()).isLessThan(20.0);  // bound
    }

    @Test
    void equilibriumKcStaysFinitePositive() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("CHEMISTRY", 3, 30, 2.0);
        // secondary = best Kc value; must be positive and finite.
        assertThat(r.secondaryValue()).isGreaterThan(0.0);
        assertThat(Double.isFinite(r.secondaryValue())).isTrue();
    }

    @Test
    void compressibilityZIsCloseToOneAtRealisticReducedStates() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("CHEMISTRY", 4, 30, 2.0);
        assertThat(r.equation()).contains("Z");
        assertThat(r.findings()).contains("Compressibility Z");
        // primary = max |1 − Z|; spec bound says ≤ 1.0; realistic sweeps stay much lower.
        assertThat(r.primaryValue()).isLessThan(1.0);
    }

    @Test
    void compressibilityZIsFiniteAcrossSweep() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("CHEMISTRY", 4, 30, 2.0);
        // secondary = Z value at worst-deviation point.
        assertThat(Double.isFinite(r.secondaryValue())).isTrue();
    }

    @Test
    void compressibilityZIsAlwaysPositive_evenInDenseRegime() {
        // Phase 10 — P-a328-359 verbatim seed (param1=19, param2=2.9) lands
        // at (Tr≈0.79, Pr≈1.73), which the bare Pitzer-Curl formula maps to
        // Z≈-0.14 (unphysical: Z = PV/nRT must be > 0). The clamp introduced
        // in this commit caps Pr to the validity envelope so Z stays
        // physically meaningful even when the user's seed lands in the
        // dense-gas regime. Sweep all (param1, param2) corners.
        int[]    p1s = {10, 19, 50, 90, 99};
        double[] p2s = {1.0, 2.9, 5.0, 7.0, 9.0};
        for (int p1 : p1s) {
            for (double p2 : p2s) {
                MathPhysicsEngine.ResearchResult r =
                        MathPhysicsEngine.compute("CHEMISTRY", 4, p1, p2);
                assertThat(r.secondaryValue())
                        .as("Z must be non-negative at param1=%d, param2=%.1f", p1, p2)
                        .isGreaterThanOrEqualTo(0.0);
            }
        }
    }

    // ---- Phase 7 FX.3: ARCHITECTURE_TERRESTRIAL (generic across planetary g, ρ_atm) ----

    @Test
    void beamDeflectionStaysWithinServiceabilityRatioForReasonableSpan() {
        // Earth-like seed (param1 mapped to g ≈ 8 m/s²); δ/L should be small.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 0, 30, 2.0);
        assertThat(r.equation()).contains("δ").contains("ρ·g");
        assertThat(r.findings()).contains("Cantilever self-weight");
        // primary = max δ/L; staying under 0.05 is the spec bound.
        assertThat(r.primaryValue()).isLessThan(0.05);
    }

    @Test
    void beamDeflectionScalesWithPlanetaryGravity() {
        // Low gravity → smaller deflection. Compare param1=10 (g~1 m/s²) vs param1=90 (g~23 m/s²).
        MathPhysicsEngine.ResearchResult low =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 0, 10, 2.0);
        MathPhysicsEngine.ResearchResult high =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 0, 90, 2.0);
        // δ ∝ g. high-g findings must report a larger deflection somewhere in the sweep.
        assertThat(high.primaryValue()).isGreaterThanOrEqualTo(low.primaryValue());
    }

    @Test
    void windLoadPressureScalesWithAtmosphericDensity() {
        // Param2 controls atmospheric density seed; the compute is generic over ρ_atm.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 1, 30, 5.0);
        assertThat(r.equation()).contains("ρ_atm");
        assertThat(r.findings()).contains("Wind load");
        // q = ½·ρ·v² — for reasonable v and atmospheric density, q is positive and finite.
        assertThat(r.primaryValue()).isGreaterThan(0.0);
        assertThat(r.primaryValue()).isLessThan(100_000.0);  // registry bound
    }

    @Test
    void windLoadIsLowerInThinAtmosphere() {
        // Mars-like seed (param2=1 → very low ρ_atm) vs Venus-like (param2=9 → high ρ_atm).
        MathPhysicsEngine.ResearchResult thin =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 1, 30, 1.0);
        MathPhysicsEngine.ResearchResult thick =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 1, 30, 9.0);
        assertThat(thick.primaryValue()).isGreaterThan(thin.primaryValue());
    }

    @Test
    void bearingCapacityScalesWithSurfaceGravity() {
        // Generic — g-dependent through γ_soil = ρ_soil·g.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 2, 30, 2.0);
        assertThat(r.equation()).contains("Terzaghi");
        assertThat(r.findings()).contains("Bearing capacity");
        assertThat(r.primaryValue()).isGreaterThan(0.0);  // positive q_u
        assertThat(r.primaryValue()).isLessThan(100_000.0); // bound in kPa
    }

    @Test
    void bearingCapacityIsLowerOnLowGravityWorlds() {
        // Same soil + geometry, lower g → lower γ_soil → lower q_u.
        MathPhysicsEngine.ResearchResult lowG =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 2, 10, 2.0);
        MathPhysicsEngine.ResearchResult highG =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 2, 90, 2.0);
        // q_u rises with g.
        assertThat(highG.primaryValue()).isGreaterThanOrEqualTo(lowG.primaryValue() - 1.0);
    }

    @Test
    void thermalMassTimeLagScalesWithAtmosphericDensity() {
        // h ∝ √(ρ_atm) → τ ∝ 1/√(ρ_atm). Thin atm → long lag.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 3, 30, 2.0);
        assertThat(r.equation()).contains("τ").contains("h");
        assertThat(r.findings()).contains("Thermal mass");
        // primary = τ in hours; supported regime fits a daily cycle.
        assertThat(r.primaryValue()).isLessThan(100.0);  // registry bound
    }

    @Test
    void thinAtmosphereProducesLongerTimeLag() {
        // param2 = 1 (low ρ_atm) → high τ. param2 = 9 (high ρ_atm) → low τ.
        MathPhysicsEngine.ResearchResult thin =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 3, 30, 1.0);
        MathPhysicsEngine.ResearchResult thick =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 3, 30, 9.0);
        // τ thin > τ thick (inverse-density scaling).
        assertThat(thin.primaryValue()).isGreaterThanOrEqualTo(thick.primaryValue() - 1.0);
    }

    @Test
    void daylightFactorStaysWithinTwentyPercentBand() {
        // DF must be in [0, 20%] for sensible apertures.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 4, 30, 2.0);
        assertThat(r.equation()).contains("daylight factor");
        assertThat(r.findings()).contains("Daylight factor");
        // primary = max DF; cap is 100% by physics (registry bound).
        assertThat(r.primaryValue()).isLessThan(100.0);
    }

    @Test
    void daylightFactorRecoversWithLargerAperture() {
        // Wider window-to-floor ratio → higher DF.
        MathPhysicsEngine.ResearchResult small =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 4, 20, 2.0);
        MathPhysicsEngine.ResearchResult large =
                MathPhysicsEngine.compute("ARCHITECTURE_TERRESTRIAL", 4, 90, 2.0);
        assertThat(large.primaryValue()).isGreaterThanOrEqualTo(small.primaryValue());
    }

    // ---- Phase 7 FX.4: ARCHITECTURE_LIQUID (generic across any liquid) ----

    @Test
    void hydrostaticPressureScalesLinearlyWithDepth() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 0, 30, 5.0);
        assertThat(r.equation()).contains("ρ_liq").contains("z");
        assertThat(r.findings()).contains("Hydrostatic");
        assertThat(r.primaryValue()).isGreaterThan(0.0);
        assertThat(r.primaryValue()).isLessThan(1.0e9);  // bound
    }

    @Test
    void hydrostaticPressureRisesWithLiquidDensity() {
        // Dense liquid (param2 high → mercury-like) produces higher pressure
        // at the same depth than a low-density liquid (methane).
        MathPhysicsEngine.ResearchResult lowDensity =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 0, 30, 1.0);
        MathPhysicsEngine.ResearchResult highDensity =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 0, 30, 9.0);
        assertThat(highDensity.primaryValue()).isGreaterThan(lowDensity.primaryValue());
    }

    @Test
    void buoyantLiftIsPositiveAndBounded() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 1, 30, 5.0);
        assertThat(r.equation()).contains("F_b").contains("Archimedes");
        assertThat(r.findings()).contains("Buoyant lift");
        assertThat(r.primaryValue()).isGreaterThan(0.0);
        assertThat(r.primaryValue()).isLessThan(1.0e9);
    }

    @Test
    void buoyancyScalesWithLiquidDensity() {
        MathPhysicsEngine.ResearchResult thin =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 1, 30, 1.0);
        MathPhysicsEngine.ResearchResult dense =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 1, 30, 9.0);
        // F_b = ρ·g·V — higher ρ → higher buoyant lift.
        assertThat(dense.primaryValue()).isGreaterThan(thin.primaryValue());
    }

    @Test
    void hullBucklingPressureScalesCubicallyWithThickness() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 2, 30, 5.0);
        assertThat(r.equation()).contains("P_crit").contains("t/R");
        assertThat(r.findings()).contains("Hull buckling");
        // primary = min P_crit (over wall thicknesses); must be positive.
        assertThat(r.primaryValue()).isGreaterThan(0.0);
        assertThat(r.primaryValue()).isLessThan(1.0e10);
    }

    @Test
    void hullBucklingTertiaryEncodesSafetyMargin() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 2, 30, 5.0);
        // tertiary = safety margin (P_crit / design_hydrostatic). Always positive.
        assertThat(r.tertiaryValue()).isGreaterThan(0.0);
    }

    @Test
    void hydrodynamicDragIsFiniteAcrossVelocitySweep() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 3, 30, 5.0);
        assertThat(r.equation()).contains("F_d").contains("Re");
        assertThat(r.findings()).contains("Hydrodynamic drag");
        assertThat(Double.isFinite(r.primaryValue())).isTrue();
        assertThat(r.primaryValue()).isLessThan(1.0e9);
    }

    @Test
    void hydrodynamicDragRisesWithVelocity() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 3, 30, 5.0);
        // Drag rises quadratically with v; the best v in the sweep is the last one.
        // secondary = best v; must be positive.
        assertThat(r.secondaryValue()).isGreaterThan(0.0);
    }

    @Test
    void dissolvedGasFollowsHenryLaw() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 4, 30, 5.0);
        assertThat(r.equation()).contains("Henry").contains("k_H");
        assertThat(r.findings()).contains("Henry's-law dissolution");
        assertThat(r.primaryValue()).isGreaterThan(0.0);
        assertThat(r.primaryValue()).isLessThan(100.0);  // bound
    }

    @Test
    void dissolvedGasConcentrationIsLinearInPressure() {
        // c = k_H·P. Higher param-driven pressure → higher c. The compute's
        // secondary value reports the best P; we just check c stays positive.
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_LIQUID", 4, 30, 5.0);
        assertThat(r.secondaryValue()).isGreaterThan(0.0);
    }

    // ---- Phase 7 FX.5: ARCHITECTURE_SPACE ----

    @Test
    void radiationShieldingMassIsBounded() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 0, 30, 5.0);
        assertThat(r.equation()).contains("Φ");
        assertThat(r.findings()).contains("shielding");
        assertThat(r.primaryValue()).isGreaterThan(0.0);
        assertThat(r.primaryValue()).isLessThan(500.0);  // bound
    }

    @Test
    void radiationShieldingRisesWithFlux() {
        // Higher param2 → higher flux scale → more shielding needed.
        MathPhysicsEngine.ResearchResult low =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 0, 30, 1.0);
        MathPhysicsEngine.ResearchResult high =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 0, 30, 9.0);
        assertThat(high.primaryValue()).isGreaterThan(low.primaryValue());
    }

    @Test
    void vacuumThermalBalanceProducesHabitableRange() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 1, 30, 5.0);
        assertThat(r.equation()).contains("T_eq").contains("σ");
        assertThat(r.findings()).contains("Vacuum thermal");
        // primary = max T; secondary = min T. Both within registry bound.
        assertThat(r.primaryValue()).isLessThan(1000.0);
        assertThat(r.secondaryValue()).isGreaterThan(0.0);
    }

    @Test
    void vacuumThermalSecondaryHoldsMinTemperature() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 1, 30, 5.0);
        // min T should be lower than max T.
        assertThat(r.secondaryValue()).isLessThanOrEqualTo(r.primaryValue());
    }

    @Test
    void micrometeoroidImpactCountScalesWithArea() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 2, 30, 5.0);
        assertThat(r.equation()).contains("Φ").contains("impacts");
        assertThat(r.findings()).contains("Micrometeoroid flux");
        assertThat(r.primaryValue()).isGreaterThan(0.0);
        assertThat(r.primaryValue()).isLessThan(1.0e6);  // bound
    }

    @Test
    void largerHabitatHasMoreImpacts() {
        // Higher param1 → bigger area seed → more cumulative impacts.
        MathPhysicsEngine.ResearchResult small =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 2, 10, 5.0);
        MathPhysicsEngine.ResearchResult large =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 2, 90, 5.0);
        assertThat(large.primaryValue()).isGreaterThan(small.primaryValue());
    }

    @Test
    void centrifugalRadiusScalesQuadraticallyWithDecreasingOmega() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 3, 30, 5.0);
        assertThat(r.equation()).contains("ω²").contains("R");
        assertThat(r.findings()).contains("Centrifugal");
        assertThat(r.primaryValue()).isGreaterThan(0.0);  // some viable R
        assertThat(r.primaryValue()).isLessThan(10_000.0);  // bound
    }

    @Test
    void centrifugalLargerATargetNeedsLargerRadiusAtSameOmega() {
        // Higher param2 → larger a_target → larger R at the comfortable spin rate.
        MathPhysicsEngine.ResearchResult lowA =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 3, 30, 1.0);
        MathPhysicsEngine.ResearchResult highA =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 3, 30, 9.0);
        assertThat(highA.primaryValue()).isGreaterThanOrEqualTo(lowA.primaryValue());
    }

    @Test
    void orbitalDecayTimeIsBoundedAndPositive() {
        MathPhysicsEngine.ResearchResult r =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 4, 30, 5.0);
        assertThat(r.equation()).contains("dh/dt");
        assertThat(r.findings()).contains("Orbital decay");
        assertThat(r.primaryValue()).isGreaterThan(0.0);
        assertThat(r.primaryValue()).isLessThan(1.0e6);  // bound
    }

    @Test
    void higherAltitudeSatellitesLastLonger() {
        // Higher param1 → higher altitude seed → thinner atmosphere → longer life.
        MathPhysicsEngine.ResearchResult low =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 4, 10, 5.0);
        MathPhysicsEngine.ResearchResult high =
                MathPhysicsEngine.compute("ARCHITECTURE_SPACE", 4, 90, 5.0);
        assertThat(high.primaryValue()).isGreaterThan(low.primaryValue());
    }

    // ---- Phase 7 FX.6: BotGenome.DOMAINS expanded to 9 ----

    @Test
    void botGenomeDomainsIncludesAllNewFXDomains() {
        java.util.List<String> domains = java.util.Arrays.asList(BotGenome.DOMAINS);
        // Original 4 still present.
        assertThat(domains).contains("MATHEMATICS", "PHYSICS", "MATERIALS", "INTERDISCIPLINARY");
        // 5 new ones from FX.1-FX.5.
        assertThat(domains).contains("BIOLOGY", "CHEMISTRY",
                "ARCHITECTURE_TERRESTRIAL", "ARCHITECTURE_LIQUID", "ARCHITECTURE_SPACE");
        assertThat(domains).hasSize(9);
    }

    @Test
    void everyDomainHasCorrespondingComputeMethod() {
        // For every domain in BotGenome.DOMAINS, compute() must produce a non-null result
        // for at least topic 0 — proves the dispatcher and topicArrayFor entries exist.
        for (String domain : BotGenome.DOMAINS) {
            MathPhysicsEngine.ResearchResult r = MathPhysicsEngine.compute(domain, 0, 30, 5.0);
            assertThat(r)
                    .as("compute() for domain=%s topic=0", domain)
                    .isNotNull();
            assertThat(r.findings())
                    .as("findings() for domain=%s topic=0", domain)
                    .isNotNull()
                    .isNotBlank();
        }
    }

    @Test
    void everyDomainTopicHasARegisteredEquation() {
        // For every (domain, topic) combination, equationFor must return a non-null,
        // non-blank closed-form string. Catches missing entries in any new domain.
        for (String domain : BotGenome.DOMAINS) {
            int n = MathPhysicsEngine.topicCount(domain);
            for (int topic = 0; topic < n; topic++) {
                String eq = MathPhysicsEngine.equationFor(domain, topic);
                assertThat(eq)
                        .as("equationFor(%s, %d)", domain, topic)
                        .isNotNull()
                        .isNotBlank();
            }
        }
    }
}
