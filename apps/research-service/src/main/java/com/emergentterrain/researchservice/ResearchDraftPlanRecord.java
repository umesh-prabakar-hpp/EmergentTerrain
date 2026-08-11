package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Research-owned frozen planning record; downstream stages consume this instead of mutable source state. */
@Entity
@Table(name = "research_draft_plans")
public class ResearchDraftPlanRecord {
    @Id private UUID draftId;
    @Column(nullable = false, unique = true) private UUID requestId;
    @Column(nullable = false) private UUID workspaceId;
    @Column(nullable = false) private UUID subjectId;
    @Column(nullable = false, length = 128) private String domain;
    @Column(nullable = false, length = 512) private String topic;
    @Column(nullable = false, length = 32) private String eligibility;
    @Column(nullable = false, length = 128) private String selectorVersion;
    @Column(nullable = false, length = 64) private String inputFingerprint;
    @Column(nullable = false, length = 64) private String executionKind;
    @Column(nullable = false, columnDefinition = "text") private String executionPayloadJson;
    @Column(columnDefinition = "text") private String fallbackParameterSampleJson;
    @Column(columnDefinition = "text") private String formulationSpecificationJson;
    @Column(columnDefinition = "text") private String computationSpecificationJson;
    @Column(nullable = false) private Instant plannedAt;
    protected ResearchDraftPlanRecord() { }
    public ResearchDraftPlanRecord(UUID draftId, UUID requestId, UUID workspaceId, UUID subjectId, String domain,
                                   String topic, String eligibility, String selectorVersion, String inputFingerprint,
                                   String executionKind, String executionPayloadJson, String fallbackParameterSampleJson,
                                   String formulationSpecificationJson, String computationSpecificationJson, Instant plannedAt) {
        this.draftId=draftId; this.requestId=requestId; this.workspaceId=workspaceId; this.subjectId=subjectId;
        this.domain=domain; this.topic=topic; this.eligibility=eligibility;
        this.selectorVersion=selectorVersion; this.inputFingerprint=inputFingerprint;
        this.executionKind=executionKind; this.executionPayloadJson=executionPayloadJson;
        this.fallbackParameterSampleJson=fallbackParameterSampleJson;
        this.formulationSpecificationJson=formulationSpecificationJson;
        this.computationSpecificationJson=computationSpecificationJson; this.plannedAt=plannedAt;
    }

    ResearchDraftPlanRecord(UUID draftId, UUID requestId, UUID workspaceId, UUID subjectId, String domain,
                            String topic, String eligibility, String selectorVersion, String inputFingerprint,
                            String executionKind, String executionPayloadJson, Instant plannedAt) {
        this(draftId, requestId, workspaceId, subjectId, domain, topic, eligibility, selectorVersion,
                inputFingerprint, executionKind, executionPayloadJson, null, null, null, plannedAt);
    }

    ResearchDraftPlan toPlan(ObjectMapper objectMapper) {
        try {
            return new ResearchDraftPlan(draftId, requestId, workspaceId, subjectId, domain, topic, eligibility,
                    selectorVersion, inputFingerprint, executionKind, objectMapper.readTree(executionPayloadJson),
                    fallbackParameterSampleJson == null ? null : objectMapper.readValue(fallbackParameterSampleJson,
                            com.emergentterrain.researchcontract.ResearchParameterSample.class),
                    formulationSpecificationJson == null ? null : objectMapper.readValue(formulationSpecificationJson,
                            com.emergentterrain.researchcontract.ResearchFormulationSpecification.class),
                    computationSpecificationJson == null ? null : objectMapper.readValue(computationSpecificationJson,
                            com.emergentterrain.researchcontract.ResearchComputationSpecification.class), plannedAt);
        } catch (Exception invalidPayload) {
            throw new IllegalStateException("Stored frozen draft plan has invalid execution payload", invalidPayload);
        }
    }

    boolean matches(ResearchDraftPlan expected, ObjectMapper objectMapper) {
        ResearchDraftPlan actual = toPlan(objectMapper);
        return actual.draftId().equals(expected.draftId())
                && actual.requestId().equals(expected.requestId())
                && actual.workspaceId().equals(expected.workspaceId())
                && actual.subjectId().equals(expected.subjectId())
                && actual.domain().equals(expected.domain())
                && actual.topic().equals(expected.topic())
                && actual.eligibility().equals(expected.eligibility())
                && actual.selectorVersion().equals(expected.selectorVersion())
                && actual.inputFingerprint().equals(expected.inputFingerprint())
                && actual.executionKind().equals(expected.executionKind())
                && actual.executionPayload().equals(expected.executionPayload())
                && java.util.Objects.equals(actual.fallbackParameterSample(), expected.fallbackParameterSample())
                && java.util.Objects.equals(actual.formulationSpecification(), expected.formulationSpecification())
                && java.util.Objects.equals(actual.computationSpecification(), expected.computationSpecification());
    }
}
