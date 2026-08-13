package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationRun;
import com.emergentterrain.researchcontract.ResearchComputationSpecification;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchExecutionPreparation;
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

class FrozenResearchComputationRunnerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void recordsDirectRawOutputForTheNarrowFrozenTargetWithoutLegacyDelegate() {
        ResearchComputationRunRepository repository = mock(ResearchComputationRunRepository.class);
        DirectFrozenComputationClient sidecar = mock(DirectFrozenComputationClient.class);
        when(sidecar.execute(any())).thenReturn(mapper.createObjectNode().put("primaryValue", 0.42));
        ResearchDraftPlan draft = frozenDraft("MATHEMATICS", "Prime Constellation Search");
        FrozenResearchComputationRunner runner = new FrozenResearchComputationRunner(repository, mapper, sidecar, true);

        ResearchComputationRun run = runner.run(draft, preparation(draft)).orElseThrow();

        assertThat(run.runnerVersion()).isEqualTo(ResearchComputationRun.DIRECT_SIDECAR_SCAN_V1);
        assertThat(run.observedOutput().path("primaryValue").asDouble()).isEqualTo(0.42);
        verify(sidecar).execute(draft.computationSpecification());
        verify(repository).save(any(ResearchComputationRunRecord.class));
    }

    @Test
    void staysInertWhenDisabledOrTheFrozenTargetIsNotYetSupported() {
        ResearchComputationRunRepository repository = mock(ResearchComputationRunRepository.class);
        DirectFrozenComputationClient sidecar = mock(DirectFrozenComputationClient.class);
        ResearchDraftPlan supported = frozenDraft("MATHEMATICS", "Prime Constellation Search");
        ResearchDraftPlan unsupported = frozenDraft("PHYSICS", "Projectile Drag Optimisation");

        assertThat(new FrozenResearchComputationRunner(repository, mapper, sidecar, false)
                .run(supported, preparation(supported))).isEmpty();
        assertThat(new FrozenResearchComputationRunner(repository, mapper, sidecar, true)
                .run(unsupported, preparation(unsupported))).isEmpty();

        verify(sidecar, never()).execute(any());
        verify(repository, never()).save(any());
    }

    @Test
    void exactReplayReturnsTheStoredRunWithoutCallingTheSidecarAgain() throws Exception {
        ResearchComputationRunRepository repository = mock(ResearchComputationRunRepository.class);
        DirectFrozenComputationClient sidecar = mock(DirectFrozenComputationClient.class);
        ResearchDraftPlan draft = frozenDraft("MATHEMATICS", "Prime Constellation Search");
        ResearchExecutionPreparation preparation = preparation(draft);
        UUID runId = UUID.nameUUIDFromBytes(("research-direct-computation-run:" + draft.draftId())
                .getBytes(StandardCharsets.UTF_8));
        ResearchComputationRun expected = new ResearchComputationRun(runId, draft.draftId(), draft.requestId(),
                ResearchComputationRun.DIRECT_SIDECAR_SCAN_V1, preparation.inputFingerprint(),
                mapper.createObjectNode().put("primaryValue", 0.42), Instant.parse("2026-08-13T12:05:00Z"));
        when(repository.findById(runId)).thenReturn(Optional.of(new ResearchComputationRunRecord(expected,
                mapper.writeValueAsString(expected.observedOutput()))));

        assertThat(new FrozenResearchComputationRunner(repository, mapper, sidecar, true)
                .run(draft, preparation)).contains(expected);

        verify(sidecar, never()).execute(any());
        verify(repository, never()).save(any());
    }

    private static ResearchDraftPlan frozenDraft(String domain, String topic) {
        UUID subjectId = UUID.randomUUID();
        ResearchComputationSpecification computation = new ResearchComputationSpecification(
                ResearchComputationSpecification.LEGACY_COMPUTATION_INPUT_V1, subjectId, domain, topic,
                42, 1.5, 0.5, 0.2, 40, 0.5, 0.0);
        return new ResearchDraftPlan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), subjectId,
                domain, topic, "CANDIDATE", "legacy-standard-catalog-v1", "draft-fingerprint",
                ResearchDraftExecutionIntent.STANDARD_CATALOG, null, null, null, computation,
                Instant.parse("2026-08-13T12:00:00Z"));
    }

    private static ResearchExecutionPreparation preparation(ResearchDraftPlan draft) {
        return new ResearchExecutionPreparation(UUID.randomUUID(), draft.draftId(), draft.requestId(),
                ResearchExecutionPreparation.FROZEN_EXECUTION_PREPARATION_V1, draft.executionKind(),
                ResearchExecutionPreparation.COMPUTATION_FROZEN_AWAITING_EXECUTION, "preparation-fingerprint", null,
                Instant.parse("2026-08-13T12:00:00Z"));
    }
}
