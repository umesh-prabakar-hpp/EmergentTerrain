package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchExecutionPreparation;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Research-owned persisted readiness record; it is not a computation result or a game command. */
@Entity
@Table(name = "research_execution_preparations")
public class ResearchExecutionPreparationRecord {
    @Id private UUID preparationId;
    @Column(nullable = false, unique = true) private UUID draftId;
    @Column(nullable = false, unique = true) private UUID requestId;
    @Column(nullable = false, length = 128) private String preparationVersion;
    @Column(nullable = false, length = 64) private String executionKind;
    @Column(nullable = false, length = 64) private String readiness;
    @Column(nullable = false, length = 64) private String inputFingerprint;
    @Column(nullable = false, columnDefinition = "text") private String canonicalInputJson;
    @Column(nullable = false) private Instant preparedAt;

    protected ResearchExecutionPreparationRecord() { }

    ResearchExecutionPreparationRecord(ResearchExecutionPreparation preparation, String canonicalInputJson) {
        this.preparationId = preparation.preparationId();
        this.draftId = preparation.draftId();
        this.requestId = preparation.requestId();
        this.preparationVersion = preparation.preparationVersion();
        this.executionKind = preparation.executionKind();
        this.readiness = preparation.readiness();
        this.inputFingerprint = preparation.inputFingerprint();
        this.canonicalInputJson = canonicalInputJson;
        this.preparedAt = preparation.preparedAt();
    }

    ResearchExecutionPreparation toPreparation(ObjectMapper mapper) {
        try {
            return new ResearchExecutionPreparation(preparationId, draftId, requestId, preparationVersion,
                    executionKind, readiness, inputFingerprint, mapper.readTree(canonicalInputJson), preparedAt);
        } catch (Exception invalidJson) {
            throw new IllegalStateException("Stored execution preparation has invalid canonical input", invalidJson);
        }
    }
}
