package com.emergentterrain.research.discovery;

/**
 * Phase 30 fork 2b Phase 3 — validates a candidate against the trusted oracle
 * via the sidecar {@code /discover/validate} endpoint. Injected into
 * {@link CandidateDiscoveryService} so the loop is testable with a fake validator.
 * The production impl is {@link SidecarCandidateValidator}.
 */
public interface CandidateValidator {

    ValidationResult validate(String code, DiscoveryTask task);
}
