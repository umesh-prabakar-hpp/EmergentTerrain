package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationParity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Persisted research-owned parity evidence; it is not a result-delivery or publication record. */
@Entity
@Table(name = "research_computation_parities")
public class ResearchComputationParityRecord {
    @Id private UUID comparisonId;
    @Column(nullable = false, unique = true) private UUID draftId;
    @Column(nullable = false, unique = true) private UUID requestId;
    @Column(nullable = false, length = 128) private String comparisonVersion;
    @Column(nullable = false, length = 64) private String inputFingerprint;
    @Column(nullable = false, length = 32) private String status;
    @Column(nullable = false, columnDefinition = "text") private String comparedEnvelopeJson;
    @Column(nullable = false) private Instant comparedAt;

    protected ResearchComputationParityRecord() { }

    ResearchComputationParityRecord(ResearchComputationParity parity, String comparedEnvelopeJson) {
        this.comparisonId = parity.comparisonId();
        this.draftId = parity.draftId();
        this.requestId = parity.requestId();
        this.comparisonVersion = parity.comparisonVersion();
        this.inputFingerprint = parity.inputFingerprint();
        this.status = parity.status();
        this.comparedEnvelopeJson = comparedEnvelopeJson;
        this.comparedAt = parity.comparedAt();
    }

    ResearchComputationParity toParity(ObjectMapper mapper) {
        try {
            return new ResearchComputationParity(comparisonId, draftId, requestId, comparisonVersion, inputFingerprint,
                    status, mapper.readTree(comparedEnvelopeJson), comparedAt);
        } catch (Exception invalidOutput) {
            throw new IllegalStateException("Stored computation parity has invalid compared envelope", invalidOutput);
        }
    }
}
