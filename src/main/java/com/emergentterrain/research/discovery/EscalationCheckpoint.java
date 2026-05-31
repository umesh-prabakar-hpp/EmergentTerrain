package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 1 — one rung of the escalation: the scale
 * {@code x}, the computed {@code value} (null when {@code CANNOT_COMPUTE}), the
 * {@code verdict}, and which branch produced it ({@code "sieve"} for x ≤ NT_MAX_N,
 * {@code "discovery"} for x beyond it). The accumulating list of these IS the
 * frontier-paper corpus.
 */
public record EscalationCheckpoint(long x, Long value, EscalationVerdict verdict, String source, long elapsedMs) {}
