package com.emergentterrain.research.discovery;

/**
 * Phase 30 fork 2b — a proxy quantity for B3 reformulation: the summatory
 * {@code P(x) = Σ_{n=1}^{x} ( cMu·μ(n) + cOmega·ω(n) + cBigOmega·Ω(n) + cTau·τ(n) )}
 * over the trusted number-theory primitives. A *linear combination* keeps the
 * ground truth computable from the existing window aggregates (no new sidecar
 * code) while still being genuinely different from M(x) — the generalized oracle
 * the doc flagged B3 was missing. (Nonlinear terms like μ(n)² would need a new
 * trusted sidecar evaluator — a further follow-on.)
 */
public record ProxySpec(String name, double cMu, double cOmega, double cBigOmega, double cTau) {}
