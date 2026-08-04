package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 4 — the paper content produced by a frontier
 * run: a title and the trust-framed Findings markdown. Consumed by the pipeline at
 * the flip+watch step (a tool / cycle surfaces it).
 */
public record FrontierPaper(String title, String findings, EscalationOutcome outcome) {}
