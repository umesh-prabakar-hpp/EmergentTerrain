package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchComputationRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Research-owned direct-sidecar evidence, intentionally separate from the legacy shadow audit. */
@Entity
@Table(name = "research_computation_runs")
public class ResearchComputationRunRecord {
    @Id private UUID runId;
    @Column(nullable = false, unique = true) private UUID draftId;
    @Column(nullable = false, unique = true) private UUID requestId;
    @Column(nullable = false, length = 128) private String runnerVersion;
    @Column(nullable = false, length = 64) private String inputFingerprint;
    @Column(nullable = false, columnDefinition = "text") private String observedOutputJson;
    @Column(nullable = false) private Instant observedAt;

    protected ResearchComputationRunRecord() { }

    ResearchComputationRunRecord(ResearchComputationRun run, String observedOutputJson) {
        this.runId = run.runId();
        this.draftId = run.draftId();
        this.requestId = run.requestId();
        this.runnerVersion = run.runnerVersion();
        this.inputFingerprint = run.inputFingerprint();
        this.observedOutputJson = observedOutputJson;
        this.observedAt = run.observedAt();
    }

    ResearchComputationRun toRun(ObjectMapper mapper) {
        try {
            return new ResearchComputationRun(runId, draftId, requestId, runnerVersion, inputFingerprint,
                    mapper.readTree(observedOutputJson), observedAt);
        } catch (Exception invalidOutput) {
            throw new IllegalStateException("Stored direct computation run has invalid observed output", invalidOutput);
        }
    }
}
