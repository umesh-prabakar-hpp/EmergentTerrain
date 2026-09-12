package com.emergentterrain.researchcontract;

import java.time.Instant;
import java.util.List;

/** Read-only immutable evidence for an AI assessment of reviewer-remediation requests. */
public record ResearchProtocolReuseRemediationAssessmentSummary(
        int assessmentRevision,
        String outcome,
        String assessorModel,
        Instant assessedAt,
        List<String> evidenceReferences) { }
