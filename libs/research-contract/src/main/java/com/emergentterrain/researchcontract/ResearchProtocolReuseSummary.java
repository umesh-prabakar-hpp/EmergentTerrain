package com.emergentterrain.researchcontract;

import java.util.List;

/** Read-only locked provenance for an exact protocol reuse; never a quality or novelty claim. */
public record ResearchProtocolReuseSummary(
        String sourcePaperId,
        String sourceProtocolFingerprint,
        String reason,
        boolean protocolChanged,
        List<String> requestedRemediations) { }
