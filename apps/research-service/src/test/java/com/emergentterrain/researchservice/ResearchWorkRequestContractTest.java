package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchDraftCandidate;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchTopicOption;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchWorkRequestContractTest {

    @Test
    void rejectsDuplicateGenericSubjectIds() {
        UUID subjectId = UUID.randomUUID();

        assertThatThrownBy(() -> new ResearchWorkRequest(UUID.randomUUID(), UUID.randomUUID(), "RESEARCH_CYCLE", 1,
                Instant.parse("2026-08-07T00:00:00Z"), List.of(new ResearchSubject(subjectId, "AGENT", null),
                new ResearchSubject(subjectId, "AGENT", null)), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate subject IDs");
    }

    @Test
    void rejectsDuplicateFrozenDraftCandidateIds() {
        UUID subjectId = UUID.randomUUID();
        ResearchDraftCandidate candidate = new ResearchDraftCandidate(subjectId, "PRIMARY", 1.0, "MATHEMATICS",
                List.of(new ResearchTopicOption("topic", 1.0)));

        assertThatThrownBy(() -> new ResearchDraftSelectionInput(
                ResearchDraftSelectionInput.LEGACY_STANDARD_CATALOG_V1, 1L, "WEIGHTED",
                List.of(candidate, candidate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate subject IDs");
    }
}
