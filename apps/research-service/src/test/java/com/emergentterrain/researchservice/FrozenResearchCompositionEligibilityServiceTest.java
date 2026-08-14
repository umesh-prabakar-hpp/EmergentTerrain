package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationFailureParity;
import com.emergentterrain.researchcontract.ResearchComputationParity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrozenResearchCompositionEligibilityServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void permitsOnlyAMatchedNumericParityWithMatchingOptionalFailureEvidence() throws Exception {
        UUID draftId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        String fingerprint = "frozen-input";
        ResearchComputationParityRepository numeric = mock(ResearchComputationParityRepository.class);
        ResearchComputationFailureParityRepository failures = mock(ResearchComputationFailureParityRepository.class);
        ResearchComputationParity parity = new ResearchComputationParity(numericId(draftId), draftId, requestId,
                ResearchComputationParity.NUMERIC_ENVELOPE_PARITY_V1, fingerprint, ResearchComputationParity.MATCHED,
                mapper.createObjectNode(), Instant.EPOCH);
        when(numeric.findById(numericId(draftId))).thenReturn(Optional.of(new ResearchComputationParityRecord(parity,
                mapper.writeValueAsString(parity.comparedEnvelope()))));
        when(failures.findById(failureId(draftId))).thenReturn(Optional.empty());

        assertThat(new FrozenResearchCompositionEligibilityService(numeric, failures, mapper)
                .assess(draftId, requestId, fingerprint))
                .isEqualTo(FrozenResearchCompositionEligibilityService.Eligibility.ELIGIBLE);
    }

    @Test
    void rejectsMissingOrMismatchedEvidence() throws Exception {
        UUID draftId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ResearchComputationParityRepository numeric = mock(ResearchComputationParityRepository.class);
        ResearchComputationFailureParityRepository failures = mock(ResearchComputationFailureParityRepository.class);
        when(numeric.findById(numericId(draftId))).thenReturn(Optional.empty());
        assertThat(new FrozenResearchCompositionEligibilityService(numeric, failures, mapper)
                .assess(draftId, requestId, "frozen-input"))
                .isEqualTo(FrozenResearchCompositionEligibilityService.Eligibility.NO_NUMERIC_PARITY);

        ResearchComputationParity mismatch = new ResearchComputationParity(numericId(draftId), draftId, requestId,
                ResearchComputationParity.NUMERIC_ENVELOPE_PARITY_V1, "frozen-input",
                ResearchComputationParity.MISMATCHED, mapper.createObjectNode(), Instant.EPOCH);
        when(numeric.findById(numericId(draftId))).thenReturn(Optional.of(new ResearchComputationParityRecord(mismatch,
                mapper.writeValueAsString(mismatch.comparedEnvelope()))));
        assertThat(new FrozenResearchCompositionEligibilityService(numeric, failures, mapper)
                .assess(draftId, requestId, "frozen-input"))
                .isEqualTo(FrozenResearchCompositionEligibilityService.Eligibility.NUMERIC_MISMATCH);
    }

    @Test
    void failsClosedWhenPersistedParityEvidenceCannotBeRead() {
        UUID draftId = UUID.randomUUID();
        ResearchComputationParityRepository numeric = mock(ResearchComputationParityRepository.class);
        ResearchComputationFailureParityRepository failures = mock(ResearchComputationFailureParityRepository.class);
        ResearchComputationParity parity = new ResearchComputationParity(numericId(draftId), draftId,
                UUID.randomUUID(), ResearchComputationParity.NUMERIC_ENVELOPE_PARITY_V1, "frozen-input",
                ResearchComputationParity.MATCHED, mapper.createObjectNode(), Instant.EPOCH);
        when(numeric.findById(numericId(draftId))).thenReturn(Optional.of(new ResearchComputationParityRecord(parity,
                "not-valid-json")));

        assertThat(new FrozenResearchCompositionEligibilityService(numeric, failures, mapper)
                .assess(draftId, parity.requestId(), "frozen-input"))
                .isEqualTo(FrozenResearchCompositionEligibilityService.Eligibility.INVALID_EVIDENCE);
    }

    private static UUID numericId(UUID draftId) {
        return UUID.nameUUIDFromBytes(("research-computation-parity:" + draftId).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID failureId(UUID draftId) {
        return UUID.nameUUIDFromBytes(("research-computation-failure-parity:" + draftId)
                .getBytes(StandardCharsets.UTF_8));
    }
}
