package com.emergentterrain.research;

import com.emergentterrain.research.publication.ArtifactPackageWriter;
import com.emergentterrain.research.publication.PublicationAssessment;
import com.emergentterrain.research.publication.PublicationLabel;
import com.emergentterrain.llm.ToolInvocation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicDirectionalArtifactSmokeTest {

    private static final DynamicProblem STUDY = new DynamicProblem(
            "controlled-above-baseline-abundance",
            "Controlled exact census of abundant-or-perfect integers",
            61,
            411,
            "isAbundant OR isPerfect",
            "fraction_satisfying",
            0.17,
            "The exact finite-window fraction exceeds the pre-registered baseline of 0.17.",
            DynamicProblem.ExpectedRelation.ABOVE_BASELINE);

    private static final DynamicProblem APPROXIMATE_STUDY = new DynamicProblem(
            "controlled-approximately-equal-abundance",
            "Controlled exact census of abundant integers",
            420,
            500,
            "isAbundant",
            "fraction_satisfying",
            0.24,
            "The exact finite-window abundant-number density is within the pre-registered absolute band.",
            DynamicProblem.ExpectedRelation.APPROXIMATELY_EQUAL,
            0.03);

    @TempDir
    Path output;

    @Test
    void writesInspectableArtifactForAboveBaselineRegisteredPredicateStudy() throws Exception {
        DynamicComputeEngine engine = new DynamicComputeEngine();
        PreRegistration registration = PredicateStudyPreRegistration.from(STUDY);
        List<ToolInvocation> trace = List.of(
                scan(STUDY, engine, 61, 411, "primary"),
                scan(STUDY, engine, 148, 498, "offset"),
                scan(STUDY, engine, 61, 236, "narrow"));

        MathPhysicsEngine.ResearchResult primary = engine.compute(STUDY);
        assertThat(primary.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.SUPPORTED);
        assertThat(UniversityService.classifyResult(primary, 0.82))
                .isEqualTo(ResultClassification.SUPPORTED);
        assertThat(PredicateStudyPreRegistration.hasExactEvidence(registration, trace)).isTrue();

        ResearchPaper paper = new ResearchPaper(
                "P-controlled-directional-1", UUID.randomUUID(), "controlled-smoke", "MATHEMATICS",
                "dynamic predicate", "Controlled above-baseline predicate study",
                STUDY.hypothesis(), "COMPUTATIONAL",
                primary.findings(),
                "The preregistered above-baseline prediction is supported for all three exact registered censuses.",
                List.of(), 0.82, "PUBLISHED", 1L, Instant.now(), 1, 0.0, "deterministic-smoke", "trace");
        paper.setPublicationLabel(PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE);
        PublicationAssessment assessment = new PublicationAssessment(
                PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE,
                List.of("controlled smoke artifact has no external related-work source"));

        Path artifact = ArtifactPackageWriter.write(output, "controlled-room", paper, registration,
                trace, assessment, "# Controlled directional predicate study\n\n"
                        + "Status: PUBLISHED\n\n"
                        + "The `ABOVE_BASELINE` prediction is supported by the three registered exact scans.\n",
                new ObjectMapper());

        String manifest = Files.readString(artifact.resolve("manifest.json"));
        String preregistration = Files.readString(artifact.resolve("evidence/pre-registration.json"));
        String toolTrace = Files.readString(artifact.resolve("evidence/tool-trace.json"));
        String conclusion = Files.readString(artifact.resolve("results/conclusion.md"));

        assertThat(manifest).contains("\"paper_status\" : \"PUBLISHED\"");
        assertThat(preregistration).contains("expected_relation=ABOVE_BASELINE");
        assertThat(toolTrace).contains("Predicate scan:").contains("SUPPORTED");
        assertThat(conclusion).contains("supported");
    }

    @Test
    void writesInspectableArtifactForApproximatelyEqualRegisteredPredicateStudy() throws Exception {
        DynamicComputeEngine engine = new DynamicComputeEngine();
        PreRegistration registration = PredicateStudyPreRegistration.from(APPROXIMATE_STUDY);
        List<ToolInvocation> trace = List.of(
                scan(APPROXIMATE_STUDY, engine, 420, 500, "primary"),
                scan(APPROXIMATE_STUDY, engine, 440, 520, "offset"),
                scan(APPROXIMATE_STUDY, engine, 420, 460, "narrow"));

        MathPhysicsEngine.ResearchResult primary = engine.compute(APPROXIMATE_STUDY);
        assertThat(primary.secondaryValue()).isEqualTo(0.25);
        assertThat(primary.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.SUPPORTED);
        assertThat(PredicateStudyPreRegistration.hasExactEvidence(registration, trace)).isTrue();

        ResearchPaper paper = new ResearchPaper(
                "P-controlled-approximately-equal-1", UUID.randomUUID(), "controlled-smoke", "MATHEMATICS",
                "dynamic predicate", "Controlled approximate-equality predicate study",
                APPROXIMATE_STUDY.hypothesis(), "COMPUTATIONAL",
                primary.findings(),
                "The three preregistered exact censuses are inside the locked absolute tolerance of 0.03.",
                List.of(), 0.82, "PUBLISHED", 1L, Instant.now(), 1, 0.0, "deterministic-smoke", "trace");
        paper.setPublicationLabel(PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE);
        PublicationAssessment assessment = new PublicationAssessment(
                PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE,
                List.of("controlled smoke artifact has no external related-work source"));

        Path artifact = ArtifactPackageWriter.write(output, "controlled-room", paper, registration,
                trace, assessment, "# Controlled approximate-equality predicate study\n\n"
                        + "Status: PUBLISHED\n\n"
                        + "All registered exact scans are inside the locked tolerance of 0.03.\n",
                new ObjectMapper());

        String preregistration = Files.readString(artifact.resolve("evidence/pre-registration.json"));
        String toolTrace = Files.readString(artifact.resolve("evidence/tool-trace.json"));
        String conclusion = Files.readString(artifact.resolve("results/conclusion.md"));

        assertThat(preregistration).contains("expected_relation=APPROXIMATELY_EQUAL")
                .contains("comparison_tolerance=0.03");
        assertThat(toolTrace).contains("\\\"comparison_tolerance\\\":0.03")
                .contains("SUPPORTED");
        assertThat(conclusion).contains("locked absolute tolerance of 0.03");
    }

    private static ToolInvocation scan(DynamicProblem study, DynamicComputeEngine engine,
                                       int start, int end, String callId) {
        DynamicProblem registeredWindow = new DynamicProblem(
                study.id(), study.description(), start, end, study.predicate(), study.metric(),
                study.baseline(), study.hypothesis(), study.expectedRelation(), study.comparisonTolerance());
        MathPhysicsEngine.ResearchResult result = engine.compute(registeredWindow);
        String arguments = "{\"predicate\":\"" + study.predicate() + "\",\"range_start\":" + start
                + ",\"range_end\":" + end + ",\"metric\":\"" + study.metric()
                + "\",\"baseline\":" + study.baseline()
                + (study.comparisonTolerance() == null ? "" : ",\"comparison_tolerance\":" + study.comparisonTolerance())
                + "}";
        return new ToolInvocation("run_predicate_scan", arguments,
                "Predicate scan: " + result.findings() + " Claim status: " + result.claimStatus(), 0, callId);
    }
}
