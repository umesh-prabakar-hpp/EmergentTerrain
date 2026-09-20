package com.emergentterrain.researchcontract;

import java.time.Instant;

/**
 * Display-safe integrity metadata for one immutable paper artifact bundle.
 *
 * <p>This deliberately contains no location, filename, command, or artifact content.</p>
 */
public record ResearchArtifactIntegritySummary(
        boolean available,
        String integrityStatus,
        Integer schemaVersion,
        Instant createdAt,
        String publicationLabel,
        String paperStatus,
        String sourceRevision,
        int declaredFileCount) { }
