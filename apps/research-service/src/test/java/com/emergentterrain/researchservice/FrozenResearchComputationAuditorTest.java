package com.emergentterrain.researchservice;

import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.researchcontract.ResearchComputationSpecification;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchExecutionPreparation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrozenResearchComputationAuditorTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsOneSidecarObservationForFullyFrozenStandardInputWithoutRoutingIt() {
        ResearchComputationAuditRepository repository = mock(ResearchComputationAuditRepository.class);
        FrozenResearchComputationAuditor.LegacySidecarComputation legacy = mock(
                FrozenResearchComputationAuditor.LegacySidecarComputation.class);
        when(legacy.compute(any(), any(Integer.class), any(Integer.class), any(Double.class), any()))
                .thenReturn(new MathPhysicsEngine.ResearchResult("bounded observation", 1.0, 2.0, 3.0));
        FrozenResearchComputationAuditor auditor = new FrozenResearchComputationAuditor(repository, mapper, legacy, true);
        ResearchDraftPlan draft = frozenDraft();
        ResearchExecutionPreparation preparation = preparation(draft);

        var audit = auditor.audit(draft, preparation).orElseThrow();

        assertThat(audit.inputFingerprint()).isEqualTo("preparation-fingerprint");
        assertThat(audit.observedOutput().path("topic").asText()).isEqualTo("Prime Constellation Search");
        assertThat(audit.observedOutput().path("primaryValue").asDouble()).isEqualTo(1.0);
        verify(legacy).compute("MATHEMATICS", 0, 42, 1.5, new MathPhysicsEngine.SearchParams(0.5, 0.2, 40, 0.5, 0.0));
        verify(repository).save(any(ResearchComputationAuditRecord.class));
    }

    @Test
    void staysInertWhenTheControlledAuditFlagIsOff() {
        ResearchComputationAuditRepository repository = mock(ResearchComputationAuditRepository.class);
        FrozenResearchComputationAuditor.LegacySidecarComputation legacy = mock(
                FrozenResearchComputationAuditor.LegacySidecarComputation.class);
        FrozenResearchComputationAuditor auditor = new FrozenResearchComputationAuditor(repository, mapper, legacy, false);
        ResearchDraftPlan draft = frozenDraft();

        assertThat(auditor.audit(draft, preparation(draft))).isEmpty();

        verify(legacy, never()).compute(any(), any(Integer.class), any(Integer.class), any(Double.class), any());
        verify(repository, never()).save(any());
    }

    @Test
    void exactReplayReturnsTheStoredObservationWithoutCallingTheSidecarAgain() throws Exception {
        ResearchComputationAuditRepository repository = mock(ResearchComputationAuditRepository.class);
        FrozenResearchComputationAuditor.LegacySidecarComputation legacy = mock(
                FrozenResearchComputationAuditor.LegacySidecarComputation.class);
        ResearchDraftPlan draft = frozenDraft();
        ResearchExecutionPreparation preparation = preparation(draft);
        UUID auditId = UUID.nameUUIDFromBytes(("research-computation-audit:" + draft.draftId())
                .getBytes(StandardCharsets.UTF_8));
        var expected = new com.emergentterrain.researchcontract.ResearchComputationAudit(auditId, draft.draftId(),
                draft.requestId(), com.emergentterrain.researchcontract.ResearchComputationAudit.SIDECAR_SHADOW_AUDIT_V1,
                preparation.inputFingerprint(), mapper.createObjectNode().put("primaryValue", 1.0),
                Instant.parse("2026-08-13T12:05:00Z"));
        when(repository.findById(auditId)).thenReturn(Optional.of(new ResearchComputationAuditRecord(expected,
                mapper.writeValueAsString(expected.observedOutput()))));
        FrozenResearchComputationAuditor auditor = new FrozenResearchComputationAuditor(repository, mapper, legacy, true);

        assertThat(auditor.audit(draft, preparation)).contains(expected);

        verify(legacy, never()).compute(any(), any(Integer.class), any(Integer.class), any(Double.class), any());
        verify(repository, never()).save(any());
    }

    private static ResearchDraftPlan frozenDraft() {
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
                ResearchExecutionPreparation.COMPUTATION_FROZEN_AWAITING_EXECUTION, "preparation-fingerprint", null,
                Instant.parse("2026-08-13T12:00:00Z"));
    }
}
