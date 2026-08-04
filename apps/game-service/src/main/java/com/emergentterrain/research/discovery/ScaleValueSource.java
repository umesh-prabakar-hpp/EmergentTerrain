package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 1 — source of the studied quantity at a
 * scale {@code x <= NT_MAX_N} (the trusted sieve range). Returns the value, or
 * {@code null} if it can't be obtained (→ {@code CANNOT_COMPUTE}). Beyond
 * NT_MAX_N the loop routes to {@link CandidateDiscoveryService} instead.
 *
 * <p>Passed to {@link FrontierEscalationService#escalate} rather than injected:
 * the live adapter (a thin sidecar call for M(x) on [1, NT_MAX_N]) is wired at
 * the Phase-4 frontier-paper step; the skeleton + tests supply it directly.
 */
@FunctionalInterface
public interface ScaleValueSource {

    Long valueAt(long x);
}
