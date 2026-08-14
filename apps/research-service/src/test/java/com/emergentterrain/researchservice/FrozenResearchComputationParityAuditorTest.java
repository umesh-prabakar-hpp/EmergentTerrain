package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationAudit;
import com.emergentterrain.researchcontract.ResearchComputationParity;
import com.emergentterrain.researchcontract.ResearchComputationRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrozenResearchComputationParityAuditorTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsACompleteMatchedNumericEnvelopeForPairedFrozenObservations() {
        ResearchComputationParityRepository repository = mock(ResearchComputationParityRepository.class);
        FrozenResearchComputationParityAuditor auditor = new FrozenResearchComputationParityAuditor(repository, mapper, true);

        ResearchComputationParity parity = auditor.compare(legacy(1.0, "OBSERVATIONAL", true),
                direct(1.0, "OBSERVATIONAL", true)).orElseThrow();

        assertThat(parity.status()).isEqualTo(ResearchComputationParity.MATCHED);
        assertThat(parity.comparedEnvelope().path("primaryValue").path("matched").asBoolean()).isTrue();
        assertThat(parity.comparedEnvelope().path("claimStatus").path("matched").asBoolean()).isTrue();
        verify(repository).save(any(ResearchComputationParityRecord.class));
    }

    @Test
    void recordsMismatchesWithoutSuppressingTheObservedValues() {
        ResearchComputationParityRepository repository = mock(ResearchComputationParityRepository.class);
        FrozenResearchComputationParityAuditor auditor = new FrozenResearchComputationParityAuditor(repository, mapper, true);

        ResearchComputationParity parity = auditor.compare(legacy(1.0, "OBSERVATIONAL", true),
                direct(2.0, "REFUTED", false)).orElseThrow();

        assertThat(parity.status()).isEqualTo(ResearchComputationParity.MISMATCHED);
        assertThat(parity.comparedEnvelope().path("primaryValue").path("legacy").asDouble()).isEqualTo(1.0);
        assertThat(parity.comparedEnvelope().path("primaryValue").path("direct").asDouble()).isEqualTo(2.0);
        assertThat(parity.comparedEnvelope().path("theoryConsistent").path("matched").asBoolean()).isFalse();
    }

    @Test
    void isInertWhenTheControlledParityFlagIsOff() {
        ResearchComputationParityRepository repository = mock(ResearchComputationParityRepository.class);
        FrozenResearchComputationParityAuditor auditor = new FrozenResearchComputationParityAuditor(repository, mapper, false);

        assertThat(auditor.compare(legacy(1.0, "OBSERVATIONAL", true), direct(1.0, "OBSERVATIONAL", true))).isEmpty();

        verify(repository, never()).save(any());
    }

    @Test
    void exactReplayReusesThePersistedComparison() throws Exception {
        ResearchComputationParityRepository repository = mock(ResearchComputationParityRepository.class);
        ResearchComputationAudit legacy = legacy(1.0, "OBSERVATIONAL", true);
        ResearchComputationRun direct = direct(1.0, "OBSERVATIONAL", true);
        UUID comparisonId = UUID.nameUUIDFromBytes(("research-computation-parity:" + legacy.draftId())
                .getBytes(StandardCharsets.UTF_8));
        ResearchComputationParity expected = new ResearchComputationParity(comparisonId, legacy.draftId(), legacy.requestId(),
                ResearchComputationParity.NUMERIC_ENVELOPE_PARITY_V1, legacy.inputFingerprint(),
                ResearchComputationParity.MATCHED, mapper.createObjectNode(), Instant.parse("2026-08-13T12:05:00Z"));
        when(repository.findById(comparisonId)).thenReturn(Optional.of(new ResearchComputationParityRecord(expected,
                mapper.writeValueAsString(expected.comparedEnvelope()))));

        assertThat(new FrozenResearchComputationParityAuditor(repository, mapper, true).compare(legacy, direct))
                .contains(expected);

        verify(repository, never()).save(any());
    }

    private ResearchComputationAudit legacy(double value, String status, boolean theoryConsistent) {
        UUID draftId = UUID.nameUUIDFromBytes("parity-draft".getBytes(StandardCharsets.UTF_8));
        UUID requestId = UUID.nameUUIDFromBytes("parity-request".getBytes(StandardCharsets.UTF_8));
        return new ResearchComputationAudit(UUID.randomUUID(), draftId, requestId,
                ResearchComputationAudit.SIDECAR_SHADOW_AUDIT_V1, "parity-fingerprint",
                output(value, status, theoryConsistent), Instant.parse("2026-08-13T12:00:00Z"));
    }

    private ResearchComputationRun direct(double value, String status, boolean theoryConsistent) {
        UUID draftId = UUID.nameUUIDFromBytes("parity-draft".getBytes(StandardCharsets.UTF_8));
        UUID requestId = UUID.nameUUIDFromBytes("parity-request".getBytes(StandardCharsets.UTF_8));
        return new ResearchComputationRun(UUID.randomUUID(), draftId, requestId,
                ResearchComputationRun.DIRECT_SIDECAR_SCAN_V1, "parity-fingerprint",
                output(value, status, theoryConsistent), Instant.parse("2026-08-13T12:00:00Z"));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode output(double value, String status, boolean theoryConsistent) {
        return mapper.createObjectNode().put("primaryValue", value).put("secondaryValue", 2.0)
                .put("tertiaryValue", 3.0).put("reviewValue", 4.0).put("claimStatus", status)
                .put("theoryConsistent", theoryConsistent);
    }
}
