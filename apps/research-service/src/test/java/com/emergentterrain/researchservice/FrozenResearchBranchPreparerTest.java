package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchStudySpecification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrozenResearchBranchPreparerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final FrozenResearchBranchPreparer preparer = new FrozenResearchBranchPreparer(mapper);

    @Test
    void preparesAStudyOnlyWhenItsPayloadMatchesItsFrozenTopic() {
        ResearchStudySpecification study = study("study-7");

        var prepared = preparer.prepare(plan(ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY,
                "study/study-7", mapper.valueToTree(study)));

        assertThat(prepared.status()).isEqualTo("PREPARED");
        assertThat(prepared.canonicalPayload().path("studyId").asText()).isEqualTo("study-7");
    }

    @Test
    void rejectsAStudyPayloadWhoseIdentityDoesNotMatchItsFrozenTopic() {
        assertThatThrownBy(() -> preparer.prepare(plan(ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY,
                "study/other", mapper.valueToTree(study("study-7")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match draft topic");
    }

    @Test
    void rejectsAConjecturePayloadWhoseIdentityDoesNotMatchItsFrozenTopic() {
        ResearchConjectureSpecification conjecture = new ResearchConjectureSpecification(
                ResearchConjectureSpecification.LEGACY_CONJECTURE_V1, "conjecture-7", "MATHEMATICS", "BOUND",
                "left_metric", "right_metric", null, 1.0, 0.0, 0.25, 4, 0.05, 100L,
                ResearchConjectureSpecification.QUEUE_CANDIDATE);

        assertThatThrownBy(() -> preparer.prepare(plan(ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY,
                "conjecture/other", mapper.valueToTree(conjecture))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match draft topic");
    }

    @Test
    void rejectsAnUnknownFrozenStudyVersion() {
        ResearchStudySpecification unsupported = new ResearchStudySpecification("future-study-v2", "study-7",
                "bounded study", 10, 50, "isPrime", "fraction_satisfying", 0.5,
                "above baseline", "ABOVE_BASELINE", null);

        assertThatThrownBy(() -> preparer.prepare(plan(ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY,
                "study/study-7", mapper.valueToTree(unsupported))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported frozen parameterized study version");
    }

    private static ResearchStudySpecification study(String studyId) {
        return new ResearchStudySpecification(ResearchStudySpecification.LEGACY_PARAMETERIZED_STUDY_V1, studyId,
                "bounded study", 10, 50, "isPrime", "fraction_satisfying", 0.5,
                "above baseline", "ABOVE_BASELINE", null);
    }

    private static ResearchDraftPlan plan(String executionKind, String topic,
                                          com.fasterxml.jackson.databind.JsonNode payload) {
        return new ResearchDraftPlan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MATHEMATICS", topic, "CANDIDATE", "legacy-standard-catalog-v1", "fingerprint",
                executionKind, payload, Instant.parse("2026-08-07T00:00:00Z"));
    }
}
