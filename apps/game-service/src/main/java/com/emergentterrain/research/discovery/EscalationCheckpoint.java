package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 1 — one rung of the escalation: the scale
 * {@code x}, the computed {@code value} (null when {@code CANNOT_COMPUTE}), the
 * {@code verdict}, and which branch produced it ({@code "sieve"} for x ≤ NT_MAX_N,
 * {@code "discovery"} for x beyond it). The accumulating list of these IS the
 * frontier-paper corpus.
 *
 * <p>{@code methodCode} is the LLM-generated candidate at this rung — the
 * <i>validated</i> method on a HOLDS discovery rung, or the <i>last rejected</i>
 * candidate on a CANNOT_COMPUTE discovery rung. {@code failureStatus} is the sandbox
 * status on a failure ({@code oom}, {@code timeout}, {@code wrong}, …), null otherwise
 * — so a failed rung is itself recordable knowledge (§10.3), not just an absence.
 */
public record EscalationCheckpoint(long x, Long value, EscalationVerdict verdict, String source,
                                  long elapsedMs, String methodCode, String failureStatus) {

    /** Back-compatible constructor (validated method / no failure). */
    public EscalationCheckpoint(long x, Long value, EscalationVerdict verdict, String source,
                                long elapsedMs, String methodCode) {
        this(x, value, verdict, source, elapsedMs, methodCode, null);
    }

    /** Back-compatible constructor (no method code — sieve rungs and tests). */
    public EscalationCheckpoint(long x, Long value, EscalationVerdict verdict, String source, long elapsedMs) {
        this(x, value, verdict, source, elapsedMs, null, null);
    }
}
