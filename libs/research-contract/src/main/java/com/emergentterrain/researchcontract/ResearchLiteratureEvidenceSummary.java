package com.emergentterrain.researchcontract;

/** Compact counts from a retained literature audit; no reference text or identifiers are exposed. */
public record ResearchLiteratureEvidenceSummary(
        boolean available,
        int issuedReferenceCount,
        int readReferenceCount,
        int citedReferenceCount,
        int citedReadReferenceCount,
        boolean sourceGroundedRelatedWork) { }
