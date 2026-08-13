package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationRun;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchExecutionPreparation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Default-off direct computation evidence for one frozen catalog target. It never changes the
 * planning result, invokes paper workflow services, or delivers a game consequence.
 */
@Service
public class FrozenResearchComputationRunner {
    private final ResearchComputationRunRepository runs;
    private final ObjectMapper objectMapper;
    private final DirectFrozenComputationClient sidecar;
    private final boolean enabled;

    public FrozenResearchComputationRunner(ResearchComputationRunRepository runs, ObjectMapper objectMapper,
                                           DirectFrozenComputationClient sidecar,
                                           @Value("${app.research-service.direct-computation-audit.enabled:false}") boolean enabled) {
        this.runs = runs;
        this.objectMapper = objectMapper;
        this.sidecar = sidecar;
        this.enabled = enabled;
    }

    public Optional<ResearchComputationRun> run(ResearchDraftPlan draft, ResearchExecutionPreparation preparation) {
        if (!enabled || !ResearchExecutionPreparation.COMPUTATION_FROZEN_AWAITING_EXECUTION.equals(preparation.readiness())
                || !ResearchDraftExecutionIntent.STANDARD_CATALOG.equals(draft.executionKind())
                || draft.computationSpecification() == null
                || !DirectFrozenComputationClient.supports(draft.computationSpecification())) {
            return Optional.empty();
        }
        UUID runId = UUID.nameUUIDFromBytes(("research-direct-computation-run:" + draft.draftId())
                .getBytes(StandardCharsets.UTF_8));
        return runs.findById(runId).map(existing -> reuse(existing, draft, preparation))
                .or(() -> Optional.of(save(newRun(runId, draft, preparation))));
    }

    private ResearchComputationRun newRun(UUID runId, ResearchDraftPlan draft,
                                          ResearchExecutionPreparation preparation) {
        return new ResearchComputationRun(runId, draft.draftId(), draft.requestId(),
                ResearchComputationRun.DIRECT_SIDECAR_SCAN_V1, preparation.inputFingerprint(),
                sidecar.execute(draft.computationSpecification()), Instant.now());
    }

    private ResearchComputationRun save(ResearchComputationRun run) {
        try {
            runs.save(new ResearchComputationRunRecord(run, objectMapper.writeValueAsString(run.observedOutput())));
            return run;
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("Unable to persist direct computation run", serializationFailure);
        }
    }

    private ResearchComputationRun reuse(ResearchComputationRunRecord existing, ResearchDraftPlan draft,
                                         ResearchExecutionPreparation preparation) {
        ResearchComputationRun run = existing.toRun(objectMapper);
        if (!run.draftId().equals(draft.draftId()) || !run.requestId().equals(draft.requestId())
                || !run.inputFingerprint().equals(preparation.inputFingerprint())) {
            throw new IllegalStateException("Direct computation run is already bound to different frozen input");
        }
        return run;
    }
}
