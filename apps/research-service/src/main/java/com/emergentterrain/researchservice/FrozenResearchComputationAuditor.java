package com.emergentterrain.researchservice;

import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.research.MathPhysicsEngineDelegate;
import com.emergentterrain.researchcontract.ResearchComputationAudit;
import com.emergentterrain.researchcontract.ResearchComputationSpecification;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchExecutionPreparation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Controlled sidecar shadow observation for a fully frozen standard computation. Disabled by
 * default; it never calls the university workflow, narration, publication, or game projection.
 */
@Service
public class FrozenResearchComputationAuditor {
    private final ResearchComputationAuditRepository audits;
    private final ObjectMapper objectMapper;
    private final LegacySidecarComputation legacy;
    private final boolean enabled;

    @Autowired
    public FrozenResearchComputationAuditor(ResearchComputationAuditRepository audits, ObjectMapper objectMapper,
                                            @Autowired(required = false) MathPhysicsEngineDelegate delegate,
                                            @Value("${app.research-service.computation-audit.enabled:false}") boolean enabled) {
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.legacy = delegate == null ? null : delegate::compute;
        this.enabled = enabled;
    }

    FrozenResearchComputationAuditor(ResearchComputationAuditRepository audits, ObjectMapper objectMapper,
                                     LegacySidecarComputation legacy, boolean enabled) {
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.legacy = legacy;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public Optional<ResearchComputationAudit> audit(ResearchDraftPlan draft, ResearchExecutionPreparation preparation) {
        if (!enabled || !ResearchExecutionPreparation.COMPUTATION_FROZEN_AWAITING_EXECUTION.equals(preparation.readiness())
                || !ResearchDraftExecutionIntent.STANDARD_CATALOG.equals(draft.executionKind())
                || draft.computationSpecification() == null) {
            return Optional.empty();
        }
        UUID auditId = UUID.nameUUIDFromBytes(("research-computation-audit:" + draft.draftId())
                .getBytes(StandardCharsets.UTF_8));
        return audits.findById(auditId).map(existing -> reuse(existing, draft, preparation))
                .or(() -> Optional.of(save(newAudit(auditId, draft, preparation))));
    }

    private ResearchComputationAudit newAudit(UUID auditId, ResearchDraftPlan draft,
                                              ResearchExecutionPreparation preparation) {
        if (legacy == null) {
            throw new IllegalStateException("Computation audit requires the sidecar-backed legacy delegate");
        }
        ResearchComputationSpecification input = draft.computationSpecification();
        int topicIndex = MathPhysicsEngine.topicIndex(input.domain(), input.topic());
        if (topicIndex < 0) throw new IllegalArgumentException("Frozen computation topic is not registered by the legacy catalog");
        MathPhysicsEngine.SearchParams search = new MathPhysicsEngine.SearchParams(input.stepSize(),
                input.restartProbability(), input.candidateBreadth(), input.depthBias(), input.acceptanceThreshold());
        MathPhysicsEngine.ResearchResult result = legacy.compute(input.domain(), topicIndex, input.parameterOne(),
                input.parameterTwo(), search);
        ObjectNode output = output(input, topicIndex, result);
        return new ResearchComputationAudit(auditId, draft.draftId(), draft.requestId(),
                ResearchComputationAudit.SIDECAR_SHADOW_AUDIT_V1, preparation.inputFingerprint(), output, Instant.now());
    }

    private ResearchComputationAudit save(ResearchComputationAudit audit) {
        try {
            audits.save(new ResearchComputationAuditRecord(audit, objectMapper.writeValueAsString(audit.observedOutput())));
            return audit;
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("Unable to persist computation shadow audit", serializationFailure);
        }
    }

    private ResearchComputationAudit reuse(ResearchComputationAuditRecord existing, ResearchDraftPlan draft,
                                           ResearchExecutionPreparation preparation) {
        ResearchComputationAudit audit = existing.toAudit(objectMapper);
        if (!audit.draftId().equals(draft.draftId()) || !audit.requestId().equals(draft.requestId())
                || !audit.inputFingerprint().equals(preparation.inputFingerprint())) {
            throw new IllegalStateException("Computation audit is already bound to different frozen input");
        }
        return audit;
    }

    private static ObjectNode output(ResearchComputationSpecification input, int topicIndex,
                                     MathPhysicsEngine.ResearchResult result) {
        ObjectNode output = new ObjectMapper().createObjectNode();
        output.put("domain", input.domain());
        output.put("topic", input.topic());
        output.put("legacyTopicIndex", topicIndex);
        output.put("findings", result.findings());
        output.put("primaryValue", result.primaryValue());
        output.put("secondaryValue", result.secondaryValue());
        output.put("tertiaryValue", result.tertiaryValue());
        output.put("reviewValue", result.reviewValue());
        output.put("theoryConsistent", result.theoryConsistent());
        output.put("theoryCheck", result.theoryCheck());
        output.put("claimStatus", result.claimStatus().name());
        output.put("claimAssessment", result.claimAssessment());
        output.put("equation", result.equation());
        return output;
    }

    @FunctionalInterface
    interface LegacySidecarComputation {
        MathPhysicsEngine.ResearchResult compute(String domain, int topicIndex, int parameterOne, double parameterTwo,
                                                  MathPhysicsEngine.SearchParams search);
    }
}
