package com.emergentterrain.research.discovery;

/**
 * Phase 30 fork 2b §10.3 — a persisted discovery <b>failure/boundary event</b>: at scale
 * {@code x}, an attempt to compute the bound's quantity failed with sandbox {@code outcome}
 * ({@code oom}, {@code timeout}, {@code wrong}, …); {@code reason} is the validator's
 * discrepancy and {@code code}/{@code codeSha256} the last rejected LLM candidate.
 *
 * <p>An OOM is knowledge: this records the computational boundary so a later run can be
 * <i>informed</i> (never suppressed — see [[project_frontier_discovery_feedback_design]]) and
 * a paper can cite it as an honest negative result — a FULL-trust fact about the harness's
 * reach, never a mathematical claim.
 */
public record FrontierEvent(String bound, long x, String outcome, String reason,
                            String code, String codeSha256) {}
