package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 2 — one observed cost point: computing the
 * quantity at scale {@code x} took {@code elapsedMs}. Fed to {@link StallPredictor}.
 */
public record CostObservation(long x, long elapsedMs) {}
