package com.emergentterrain.research.api;

import com.emergentterrain.researchcontract.ResearchPaperArtifactProvenanceReader;
import com.emergentterrain.researchcontract.ResearchPaperProvenanceSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchPaperArtifactProvenanceReaderTest {

    @TempDir
    Path temp;

    @Test
    void returnsOnlyDerivedSummaryFromTheFixedArtifactLayout() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UUID roomId = UUID.randomUUID();
        Path artifact = temp.resolve(roomId.toString().substring(0, 8)).resolve("artifacts").resolve("P-1");
        Files.createDirectories(artifact.resolve("evidence"));
        Files.createDirectories(artifact.resolve("results"));
        Files.writeString(artifact.resolve("evidence/pre-registration.json"), """
                {"hypothesis":"h","prediction":"p","falsificationCondition":"f",
                 "computationalStudyPlan":{"kind":"scan"},"protocolReuseProvenance":null}
                """);
        Files.writeString(artifact.resolve("evidence/tool-trace.json"), "[{" + "\"resultJson\":\"secret\"}]" );
        Files.writeString(artifact.resolve("evidence/literature-audit.json"), """
                {"gate":{"relevanceSafeIssuedKeys":["a","b"],"successfullyReadKeys":["a"],
                "relatedWorkCitedKeys":["a"],"citedReadKeys":["a"],"sourceGroundedRelatedWork":true}}
                """);
        Files.writeString(artifact.resolve("results/findings.md"), "retained findings");
        Files.writeString(artifact.resolve("results/conclusion.md"), "retained conclusion");
        String registrationHash = hash(artifact.resolve("evidence/pre-registration.json"));
        String traceHash = hash(artifact.resolve("evidence/tool-trace.json"));
        String auditHash = hash(artifact.resolve("evidence/literature-audit.json"));
        String findingsHash = hash(artifact.resolve("results/findings.md"));
        String conclusionHash = hash(artifact.resolve("results/conclusion.md"));
        Files.writeString(artifact.resolve("manifest.json"), """
                {"schema_version":2,"created_at":"%s","paper_id":"P-1",
                "publication_label":"CANDIDATE","paper_status":"PUBLISHED","source_revision":"abc123",
                "files":{"evidence/pre-registration.json":"%s","evidence/tool-trace.json":"%s",
                "evidence/literature-audit.json":"%s","results/findings.md":"%s","results/conclusion.md":"%s"}}
                """.formatted(Instant.parse("2026-01-02T03:04:05Z"), registrationHash, traceHash, auditHash,
                findingsHash, conclusionHash));

        ResearchPaperProvenanceSummary summary = new ResearchPaperArtifactProvenanceReader(mapper)
                .read(temp, roomId, "P-1");

        assertThat(summary.artifact().integrityStatus()).isEqualTo("VERIFIED");
        assertThat(summary.artifact().declaredFileCount()).isEqualTo(5);
        assertThat(summary.study()).extracting("preregistrationAvailable", "hypothesisDeclared",
                        "predictionDeclared", "falsificationConditionDeclared", "computationalStudyPlanDeclared")
                .containsExactly(true, true, true, true, true);
        assertThat(summary.evidence().recordedToolCallCount()).isEqualTo(1);
        assertThat(summary.evidence().literature()).extracting("issuedReferenceCount", "readReferenceCount",
                        "citedReferenceCount", "citedReadReferenceCount", "sourceGroundedRelatedWork")
                .containsExactly(2, 1, 1, 1, true);
        assertThat(mapper.writeValueAsString(summary)).doesNotContain("secret", "tool-trace.json", "reproduce.sh");
    }

    @Test
    void rejectsAnUnsafePaperIdentifierWithoutReadingTheFilesystem() {
        ResearchPaperProvenanceSummary summary = new ResearchPaperArtifactProvenanceReader(new ObjectMapper())
                .read(temp, UUID.randomUUID(), "../outside");

        assertThat(summary.artifact().integrityStatus()).isEqualTo("MISSING");
    }

    private static String hash(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte value : digest) output.append(String.format("%02x", value));
        return output.toString();
    }
}
