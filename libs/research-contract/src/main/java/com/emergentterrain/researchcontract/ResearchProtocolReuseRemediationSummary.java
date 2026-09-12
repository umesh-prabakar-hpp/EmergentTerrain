package com.emergentterrain.researchcontract;

import java.time.Instant;

/** Latest immutable AI-only remediation outcome for an exact protocol reuse. */
public record ResearchProtocolReuseRemediationSummary(
        int assessmentRevision, String outcome, String assessorModel, Instant assessedAt) { }
