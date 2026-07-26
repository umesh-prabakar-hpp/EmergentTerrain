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

    @TempDir
    Path output;

    @Test
    void writesInspectableArtifactForAboveBaselineRegisteredPredicateStudy() throws Exception {
        DynamicComputeEngine engine = new DynamicComputeEngine();
        PreRegistration registration = PredicateStudyPreRegistration.from(STUDY);
        List<ToolInvocation> trace = List.of(
                scan(engine, 61, 411, "primary"),
                scan(engine, 148, 498, "offset"),
                scan(engine, 61, 236, "narrow"));

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

    private static ToolInvocation scan(DynamicComputeEngine engine, int start, int end, String callId) {
        DynamicProblem registeredWindow = new DynamicProblem(
                STUDY.id(), STUDY.description(), start, end, STUDY.predicate(), STUDY.metric(),
                STUDY.baseline(), STUDY.hypothesis(), STUDY.expectedRelation());
        MathPhysicsEngine.ResearchResult result = engine.compute(registeredWindow);
        String arguments = "{\"predicate\":\"isAbundant OR isPerfect\",\"range_start\":" + start
                + ",\"range_end\":" + end + ",\"metric\":\"fraction_satisfying\",\"baseline\":0.17}";
        return new ToolInvocation("run_predicate_scan", arguments,
                "Predicate scan: " + result.findings() + " Claim status: " + result.claimStatus(), 0, callId);
    }
}
