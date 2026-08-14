package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationFailureParity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Persisted controlled-failure evidence; no exception text is retained. */
@Entity
@Table(name = "research_computation_failure_parities")
public class ResearchComputationFailureParityRecord {
    @Id private UUID comparisonId;
    @Column(nullable = false, unique = true) private UUID draftId;
    @Column(nullable = false, unique = true) private UUID requestId;
    @Column(nullable = false, length = 128) private String comparisonVersion;
    @Column(nullable = false, length = 64) private String inputFingerprint;
    @Column(nullable = false, length = 32) private String status;
    @Column(nullable = false, length = 64) private String legacyOutcome;
    @Column(nullable = false, length = 64) private String directOutcome;
    @Column(nullable = false) private Instant comparedAt;

    protected ResearchComputationFailureParityRecord() { }

    ResearchComputationFailureParityRecord(ResearchComputationFailureParity parity) {
        this.comparisonId = parity.comparisonId();
        this.draftId = parity.draftId();
        this.requestId = parity.requestId();
        this.comparisonVersion = parity.comparisonVersion();
        this.inputFingerprint = parity.inputFingerprint();
        this.status = parity.status();
        this.legacyOutcome = parity.legacyOutcome();
        this.directOutcome = parity.directOutcome();
        this.comparedAt = parity.comparedAt();
    }

    ResearchComputationFailureParity toParity() {
        return new ResearchComputationFailureParity(comparisonId, draftId, requestId, comparisonVersion,
                inputFingerprint, status, legacyOutcome, directOutcome, comparedAt);
    }
}
