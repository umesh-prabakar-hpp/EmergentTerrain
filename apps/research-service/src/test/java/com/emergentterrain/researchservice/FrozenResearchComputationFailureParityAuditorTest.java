package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationFailureParity;
import com.emergentterrain.researchcontract.ResearchComputationSpecification;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchExecutionPreparation;
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

class FrozenResearchComputationFailureParityAuditorTest {
    @Test
    void recordsMatchedCanonicalFailureOutcomesWithoutExceptionText() {
        ResearchComputationFailureParityRepository repository = mock(ResearchComputationFailureParityRepository.class);
        ResearchDraftPlan draft = draft();
        ResearchComputationFailureParity parity = new FrozenResearchComputationFailureParityAuditor(repository, true)
                .compare(draft, preparation(draft), new IllegalStateException("sidecar unavailable"),
                        new IllegalStateException("sidecar unavailable"))
                .orElseThrow();

        assertThat(parity.status()).isEqualTo(ResearchComputationFailureParity.MATCHED);
        assertThat(parity.legacyOutcome()).isEqualTo(FrozenResearchComputationFailureParityAuditor.SIDECAR_UNAVAILABLE);
        assertThat(parity.directOutcome()).doesNotContain("unavailable");
        verify(repository).save(any(ResearchComputationFailureParityRecord.class));
    }

    @Test
    void recordsSuccessVersusFailureAsMismatch() {
        ResearchComputationFailureParityRepository repository = mock(ResearchComputationFailureParityRepository.class);
        ResearchDraftPlan draft = draft();
        ResearchComputationFailureParity parity = new FrozenResearchComputationFailureParityAuditor(repository, true)
                .compare(draft, preparation(draft), null, new IllegalStateException("HTTP 503"))
                .orElseThrow();

        assertThat(parity.status()).isEqualTo(ResearchComputationFailureParity.MISMATCHED);
        assertThat(parity.legacyOutcome()).isEqualTo(FrozenResearchComputationFailureParityAuditor.SUCCESS);
        assertThat(parity.directOutcome()).isEqualTo(FrozenResearchComputationFailureParityAuditor.TRANSPORT_OR_TIMEOUT);
    }

    @Test
    void isInertWhenTheControlledFlagIsOff() {
        ResearchComputationFailureParityRepository repository = mock(ResearchComputationFailureParityRepository.class);
        ResearchDraftPlan draft = draft();

        assertThat(new FrozenResearchComputationFailureParityAuditor(repository, false)
                .compare(draft, preparation(draft), new IllegalStateException("timeout"), null)).isEmpty();

        verify(repository, never()).save(any());
    }

    @Test
    void exactReplayReturnsTheStoredFailureComparison() {
        ResearchComputationFailureParityRepository repository = mock(ResearchComputationFailureParityRepository.class);
        ResearchDraftPlan draft = draft();
        ResearchExecutionPreparation preparation = preparation(draft);
        UUID comparisonId = UUID.nameUUIDFromBytes(("research-computation-failure-parity:" + draft.draftId())
                .getBytes(StandardCharsets.UTF_8));
        ResearchComputationFailureParity expected = new ResearchComputationFailureParity(comparisonId, draft.draftId(),
                draft.requestId(), ResearchComputationFailureParity.FAILURE_SEMANTICS_PARITY_V1,
                preparation.inputFingerprint(), ResearchComputationFailureParity.MATCHED,
                FrozenResearchComputationFailureParityAuditor.SIDECAR_UNAVAILABLE,
                FrozenResearchComputationFailureParityAuditor.SIDECAR_UNAVAILABLE, Instant.parse("2026-08-13T12:05:00Z"));
        when(repository.findById(comparisonId)).thenReturn(Optional.of(new ResearchComputationFailureParityRecord(expected)));

        assertThat(new FrozenResearchComputationFailureParityAuditor(repository, true)
                .compare(draft, preparation, new IllegalStateException("unavailable"), new IllegalStateException("unavailable")))
                .contains(expected);

        verify(repository, never()).save(any());
    }

    private static ResearchDraftPlan draft() {
        UUID subjectId = UUID.randomUUID();
        ResearchComputationSpecification computation = new ResearchComputationSpecification(
                ResearchComputationSpecification.LEGACY_COMPUTATION_INPUT_V1, subjectId, "MATHEMATICS",
                "Prime Constellation Search", 42, 1.5, 0.5, 0.2, 40, 0.5, 0.0);
        return new ResearchDraftPlan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), subjectId,
                "MATHEMATICS", "Prime Constellation Search", "CANDIDATE", "legacy-standard-catalog-v1",
                "draft-fingerprint", ResearchDraftExecutionIntent.STANDARD_CATALOG, null, null, null, computation,
                Instant.parse("2026-08-13T12:00:00Z"));
    }

    private static ResearchExecutionPreparation preparation(ResearchDraftPlan draft) {
        return new ResearchExecutionPreparation(UUID.randomUUID(), draft.draftId(), draft.requestId(),
                ResearchExecutionPreparation.FROZEN_EXECUTION_PREPARATION_V1, draft.executionKind(),
                ResearchExecutionPreparation.COMPUTATION_FROZEN_AWAITING_EXECUTION, "parity-fingerprint", null,
                Instant.parse("2026-08-13T12:00:00Z"));
    }
}
