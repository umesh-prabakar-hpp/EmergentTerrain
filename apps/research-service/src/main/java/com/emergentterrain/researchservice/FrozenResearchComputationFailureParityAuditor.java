package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationFailureParity;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchExecutionPreparation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Default-off comparison of two controlled attempt outcomes. The original failure is rethrown by
 * the caller after this record is written, preserving the existing work-item retry behavior.
 */
@Service
public class FrozenResearchComputationFailureParityAuditor {
    public static final String SUCCESS = "SUCCESS";
    public static final String SIDECAR_UNAVAILABLE = "SIDECAR_UNAVAILABLE";
    public static final String NOT_PORTED = "NOT_PORTED";
    public static final String TRANSPORT_OR_TIMEOUT = "TRANSPORT_OR_TIMEOUT";
    public static final String OTHER_FAILURE = "OTHER_FAILURE";

    private final ResearchComputationFailureParityRepository parities;
    private final boolean enabled;

    public FrozenResearchComputationFailureParityAuditor(ResearchComputationFailureParityRepository parities,
                                                         @Value("${app.research-service.computation-failure-parity-audit.enabled:false}") boolean enabled) {
        this.parities = parities;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public Optional<ResearchComputationFailureParity> compare(ResearchDraftPlan draft,
                                                                ResearchExecutionPreparation preparation,
                                                                Throwable legacyFailure, Throwable directFailure) {
        if (!enabled || (legacyFailure == null && directFailure == null)) return Optional.empty();
        UUID comparisonId = UUID.nameUUIDFromBytes(("research-computation-failure-parity:" + draft.draftId())
                .getBytes(StandardCharsets.UTF_8));
        return parities.findById(comparisonId).map(existing -> reuse(existing, draft, preparation))
                .or(() -> Optional.of(save(newParity(comparisonId, draft, preparation, legacyFailure, directFailure))));
    }

    private ResearchComputationFailureParity newParity(UUID comparisonId, ResearchDraftPlan draft,
                                                       ResearchExecutionPreparation preparation,
                                                       Throwable legacyFailure, Throwable directFailure) {
        String legacyOutcome = classify(legacyFailure);
        String directOutcome = classify(directFailure);
        return new ResearchComputationFailureParity(comparisonId, draft.draftId(), draft.requestId(),
                ResearchComputationFailureParity.FAILURE_SEMANTICS_PARITY_V1, preparation.inputFingerprint(),
                legacyOutcome.equals(directOutcome) ? ResearchComputationFailureParity.MATCHED
                        : ResearchComputationFailureParity.MISMATCHED,
                legacyOutcome, directOutcome, Instant.now());
    }

    private ResearchComputationFailureParity save(ResearchComputationFailureParity parity) {
        parities.save(new ResearchComputationFailureParityRecord(parity));
        return parity;
    }

    private ResearchComputationFailureParity reuse(ResearchComputationFailureParityRecord existing,
                                                   ResearchDraftPlan draft,
                                                   ResearchExecutionPreparation preparation) {
        ResearchComputationFailureParity parity = existing.toParity();
        if (!parity.draftId().equals(draft.draftId()) || !parity.requestId().equals(draft.requestId())
                || !parity.inputFingerprint().equals(preparation.inputFingerprint())) {
            throw new IllegalStateException("Computation failure parity is already bound to different frozen input");
        }
        return parity;
    }

    static String classify(Throwable failure) {
        if (failure == null) return SUCCESS;
        String message = String.valueOf(failure.getMessage()).toLowerCase();
        if (message.contains("not ported") || message.contains("not_ported")) return NOT_PORTED;
        if (message.contains("unavailable")) return SIDECAR_UNAVAILABLE;
        if (message.contains("timeout") || message.contains("transport") || message.contains("connect")
                || message.contains("http ")) return TRANSPORT_OR_TIMEOUT;
        return OTHER_FAILURE;
    }
}
