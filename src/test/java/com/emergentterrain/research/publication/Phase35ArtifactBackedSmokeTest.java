package com.emergentterrain.research.publication;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.research.literature.DeterministicPostSearchReadExecutor;
import com.emergentterrain.research.literature.Phase34SqliteLiterature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controlled Phase 35 paper-artifact smoke test against the real read-only index.
 *
 * <p>This deliberately uses a fixed claim and deterministic publication inputs instead of an LLM
 * turn. It proves the executable lifecycle required for an artifact-backed candidate:
 * relevance-safe search → issued key → successful read → matching Related Work citation. It first
 * demonstrates that a search-only trace cannot qualify, then writes a disposable artifact only
 * after the complete chain passes.
 */
@EnabledIfEnvironmentVariable(named = "PHASE34_REAL_INDEX_DIR", matches = ".+")
class Phase35ArtifactBackedSmokeTest {

    private static final Pattern KEY = Pattern.compile("\\[(HF[0-9A-F]+)]");

    @TempDir Path output;

    @Test
    void controlledPaperArtifactRequiresAndCompletesSearchReadCiteChain() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Phase34SqliteLiterature literature = new Phase34SqliteLiterature(
                Path.of(System.getenv("PHASE34_REAL_INDEX_DIR")), true, 15_000);
        assertThat(literature.available()).isTrue();

        String claim = "multiple zeta values";
        String search = literature.search(claim);
        Matcher matcher = KEY.matcher(search);
        assertThat(matcher.find()).as("the fixed real-index claim must issue a safe key").isTrue();
        String key = matcher.group(1);
        String searchArgs = mapper.writeValueAsString(java.util.Map.of(
                "claim", claim,
                "subject_anchors", List.of("multiple", "zeta"),
                "claimed_relation", "position a finite calculation against the indexed result",
                "relevance_rationale", "the source supplies the established comparison"));
        List<ToolInvocation> searchOnlyTrace = List.of(
                new ToolInvocation("verify_constant", "{\"lhs\":\"controlled_metric\"}", "verified finite measurement", 0,
                        "call_compute"),
                new ToolInvocation("search_literature", searchArgs, search, 0, "call_search"));
        String relatedWork = "## Related Work\n\nThe fixed comparison is positioned against the source "
                + "read from the Phase 35 index [" + key + "].";
        PreRegistration registration = registration();

        PublicationAssessment missingRead = ComputationalPublicationPhase.assess(
                registration, searchOnlyTrace, relatedWork, "PUBLISHED");
        assertThat(missingRead.label()).isEqualTo(PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE);
        assertThat(missingRead.missingRequirements()).contains("source-grounded related-work positioning");

        DeterministicPostSearchReadExecutor.Outcome postSearchRead =
                new DeterministicPostSearchReadExecutor(literature, mapper).execute(searchOnlyTrace);
        assertThat(postSearchRead.attempted()).isTrue();
        assertThat(postSearchRead.successful()).isTrue();
        assertThat(postSearchRead.key()).isEqualTo(key);
        assertThat(postSearchRead.invocation().resultJson()).startsWith("read_reference " + key)
                .contains("Top passages matching");
        List<ToolInvocation> completeTrace = List.of(
                searchOnlyTrace.get(0), searchOnlyTrace.get(1),
                new ToolInvocation(postSearchRead.invocation().functionName(),
                        postSearchRead.invocation().argsJson(), postSearchRead.invocation().resultJson(),
                        postSearchRead.invocation().round(), "call_read"));
        PublicationAssessment assessment = ComputationalPublicationPhase.assess(
                registration, completeTrace, relatedWork, "PUBLISHED");
        assertThat(assessment.label()).isEqualTo(PublicationLabel.ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE);
        assertThat(assessment.missingRequirements()).isEmpty();

        ResearchPaper paper = new ResearchPaper("P-phase35-smoke", UUID.randomUUID(), "controlled-smoke",
                "MATHEMATICS", "multiple zeta values", "Controlled Phase 35 literature smoke",
                "A finite calculation is positioned against an indexed source.", "COMPUTATIONAL",
                "The controlled computation is recorded in the trace.", "No general claim is made.",
                List.of(), 1.0, "PUBLISHED", 1L, Instant.now(), 1, 0.0, "controlled", "phase35-smoke");
        paper.setRelatedWork(relatedWork);
        paper.setPublicationLabel(assessment.label());

        Path artifact = ArtifactPackageWriter.write(output, "phase35-smoke", paper, registration,
                completeTrace, assessment, "# Controlled Phase 35 literature smoke\n", mapper);
        JsonNode audit = mapper.readTree(Files.readString(artifact.resolve("evidence/literature-audit.json")));
        JsonNode gate = audit.path("gate");
        assertThat(gate.path("sourceGroundedRelatedWork").asBoolean()).isTrue();
        assertThat(gate.path("relevanceSafeIssuedKeys").toString()).contains(key);
        assertThat(gate.path("successfullyReadKeys").toString()).contains(key);
        assertThat(gate.path("citedReadKeys").toString()).contains(key);
        assertThat(Files.readString(artifact.resolve("manifest.json")))
                .contains("ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE");
    }

    private static PreRegistration registration() {
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "fixed discovery window [1e6, 2e6)",
                "fixed confirmation window [2e6, 3e6)",
                "select the registered statistic before confirmation",
                "repeat at three fixed widths",
                "./scripts/reproduce-controlled-phase35-smoke.sh");
        return new PreRegistration("Controlled hypothesis", "Fixed prediction", "Fixed falsification", "", plan);
    }
}
