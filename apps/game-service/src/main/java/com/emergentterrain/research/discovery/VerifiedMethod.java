package com.emergentterrain.research.discovery;

/**
 * Phase 30 fork 2b — a persisted, reusable verified method: source {@code code}
 * (hashed by {@code methodSha256}) that computed the bound's quantity and was
 * moat-validated, with the furthest scale it reached ({@code maxScale}) and its
 * cost there ({@code costMs}). The library is ranked by the cost curve — *reach*
 * desc, then *cost* asc — so the "best" method reaches furthest cheapest and can be
 * reused instead of regenerated (a token saving that compounds).
 */
public record VerifiedMethod(String bound, String methodSha256, String code, long maxScale, long costMs) {}
