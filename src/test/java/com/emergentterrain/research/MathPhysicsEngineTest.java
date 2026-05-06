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
                .contains("observational computational finding")
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

        UniversityService service = new UniversityService(
                0L, tempDir.toString(), 3, true,
                mock(RoomBotRepository.class),
                new ObjectMapper().findAndRegisterModules());

        assertThat(service.nextPaperId(roomId)).isEqualTo("P-59f4-30");
        assertThat(service.nextPaperId(roomId)).isEqualTo("P-59f4-31");
    }
}
