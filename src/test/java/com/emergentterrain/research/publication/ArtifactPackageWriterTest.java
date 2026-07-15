package com.emergentterrain.research.publication;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResearchPaper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactPackageWriterTest {

    @TempDir Path output;

    @Test
    void writesEvidenceResultsAndChecksummedManifestWithoutExecutingCommand() throws Exception {
        ResearchPaper paper = new ResearchPaper("P-test-1", UUID.randomUUID(), "bot", "MATHEMATICS",
                "prime gaps", "Title", "Hypothesis", "COMPUTATIONAL", "Finding", "Conclusion",
                List.of(), 0.8, "PUBLISHED", 1L, Instant.now(), 1, 0.0, "model", "trace");
        paper.setPublicationLabel(PublicationLabel.ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE);
        ComputationalStudyPlan plan = new ComputationalStudyPlan("low range", "high range",
                "lowest error", "three widths", "./scripts/reproduce.sh");
        PreRegistration registration = new PreRegistration("Hypothesis", "Prediction", "Falsify", "", plan);
        PublicationAssessment assessment = new PublicationAssessment(
                PublicationLabel.ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE, List.of());

        Path artifact = ArtifactPackageWriter.write(output, "room1234", paper, registration,
                List.of(new ToolInvocation("verify_constant", "{\"n\":1}", "value=2", 0)),
                assessment, "# Title\n", new ObjectMapper());

        assertThat(artifact).isDirectory();
        assertThat(artifact.resolve("manifest.json")).isRegularFile();
        assertThat(artifact.resolve("evidence/pre-registration.json")).isRegularFile();
        assertThat(artifact.resolve("evidence/tool-trace.json")).isRegularFile();
        assertThat(Files.readString(artifact.resolve("manifest.json"))).contains("README.md");
        assertThat(Files.readString(artifact.resolve("reproduce.sh")))
                .contains("Declared reproduction command")
                .doesNotContain("eval ");
    }

    @Test
    void usesThePaperFinalLabelRatherThanAnEarlierAssessment() throws Exception {
        ResearchPaper paper = new ResearchPaper("P-test-2", UUID.randomUUID(), "bot", "MATHEMATICS",
                "prime gaps", "Title", "Hypothesis", "COMPUTATIONAL", "Finding", "Conclusion",
                List.of(), 0.8, "NEEDS_REVISION", 1L, Instant.now(), 1, 0.0, "model", "trace");
        paper.setPublicationLabel(PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE);
        PublicationAssessment earlierAssessment = new PublicationAssessment(
                PublicationLabel.ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE, List.of());

        Path artifact = ArtifactPackageWriter.write(output, "room1234", paper, null, List.of(),
                earlierAssessment, "# Title\n", new ObjectMapper());

        assertThat(Files.readString(artifact.resolve("manifest.json")))
                .contains("COMPUTATIONAL_RESEARCH_NOTE")
                .doesNotContain("ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE");
        assertThat(Files.readString(artifact.resolve("README.md")))
                .contains("Computational research note")
                .doesNotContain("Artifact-backed computational paper candidate");
    }
}
