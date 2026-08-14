package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationAudit;
import com.emergentterrain.researchcontract.ResearchComputationParity;
import com.emergentterrain.researchcontract.ResearchComputationRun;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Compares only the shared deterministic numeric envelope from paired observations. It neither
 * retries either execution nor changes planning, publication, delivery, or game authority.
 */
@Service
public class FrozenResearchComputationParityAuditor {
    private static final List<String> SCALAR_FIELDS = List.of(
            "primaryValue", "secondaryValue", "tertiaryValue", "reviewValue");
    private static final List<String> EXACT_FIELDS = List.of("claimStatus", "theoryConsistent");

    private final ResearchComputationParityRepository parities;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public FrozenResearchComputationParityAuditor(ResearchComputationParityRepository parities, ObjectMapper objectMapper,
                                                  @Value("${app.research-service.computation-parity-audit.enabled:false}") boolean enabled) {
        this.parities = parities;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    public Optional<ResearchComputationParity> compare(ResearchComputationAudit legacy, ResearchComputationRun direct) {
        if (!enabled) return Optional.empty();
        validatePair(legacy, direct);
        UUID comparisonId = UUID.nameUUIDFromBytes(("research-computation-parity:" + legacy.draftId())
                .getBytes(StandardCharsets.UTF_8));
        return parities.findById(comparisonId).map(existing -> reuse(existing, legacy, direct))
                .or(() -> Optional.of(save(newParity(comparisonId, legacy, direct))));
    }

    private ResearchComputationParity newParity(UUID comparisonId, ResearchComputationAudit legacy,
                                                ResearchComputationRun direct) {
        ObjectNode envelope = objectMapper.createObjectNode();
        boolean matches = true;
        for (String field : SCALAR_FIELDS) {
            JsonNode left = legacy.observedOutput().path(field);
            JsonNode right = direct.observedOutput().path(field);
            boolean equal = left.isNumber() && right.isNumber()
                    && Double.doubleToLongBits(left.asDouble()) == Double.doubleToLongBits(right.asDouble());
            append(envelope, field, left, right, equal);
            matches &= equal;
        }
        for (String field : EXACT_FIELDS) {
            JsonNode left = legacy.observedOutput().path(field);
            JsonNode right = direct.observedOutput().path(field);
            boolean equal = !left.isMissingNode() && left.equals(right);
            append(envelope, field, left, right, equal);
            matches &= equal;
        }
        return new ResearchComputationParity(comparisonId, legacy.draftId(), legacy.requestId(),
                ResearchComputationParity.NUMERIC_ENVELOPE_PARITY_V1, legacy.inputFingerprint(),
                matches ? ResearchComputationParity.MATCHED : ResearchComputationParity.MISMATCHED,
                envelope, Instant.now());
    }

    private static void append(ObjectNode envelope, String field, JsonNode legacy, JsonNode direct, boolean matched) {
        ObjectNode fieldComparison = envelope.putObject(field);
        fieldComparison.set("legacy", legacy);
        fieldComparison.set("direct", direct);
        fieldComparison.put("matched", matched);
    }

    private ResearchComputationParity save(ResearchComputationParity parity) {
        try {
            parities.save(new ResearchComputationParityRecord(parity,
                    objectMapper.writeValueAsString(parity.comparedEnvelope())));
            return parity;
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("Unable to persist computation parity", serializationFailure);
        }
    }

    private ResearchComputationParity reuse(ResearchComputationParityRecord existing, ResearchComputationAudit legacy,
                                            ResearchComputationRun direct) {
        ResearchComputationParity parity = existing.toParity(objectMapper);
        if (!parity.draftId().equals(legacy.draftId()) || !parity.requestId().equals(legacy.requestId())
                || !parity.inputFingerprint().equals(legacy.inputFingerprint())) {
            throw new IllegalStateException("Computation parity is already bound to different frozen input");
        }
        return parity;
    }

    private static void validatePair(ResearchComputationAudit legacy, ResearchComputationRun direct) {
        if (!legacy.draftId().equals(direct.draftId()) || !legacy.requestId().equals(direct.requestId())
                || !legacy.inputFingerprint().equals(direct.inputFingerprint())) {
            throw new IllegalArgumentException("Parity observations must bind the same frozen draft, request, and input");
        }
    }
}
