package com.emergentterrain.research.discovery;

import java.util.Map;

/**
 * Phase 30 fork 2b Phase 3 — the sidecar's {@code /discover/validate} verdict
 * for one candidate, mapped from the JSON envelope. The candidate emitted raw
 * {@code values}; the trusted sidecar compared them to {@code oracle} and
 * returned {@code verdict}.
 *
 * @param status     sandbox status: ok | ast_reject | timeout | oom | crash | error | disabled
 * @param verdict    accept | reject | na (na when the candidate did not run)
 * @param values     candidate's M(k) at the requested points (may be empty on na)
 * @param oracle     trusted M(k) at the checkpoints
 * @param firstFail  the first overlap/anchor mismatch, or null on accept
 * @param targetValue the candidate's value at the task target, present on accept
 * @param detail     human-readable reason on failure
 */
public record ValidationResult(
        String status,
        String verdict,
        Map<Long, Long> values,
        Map<Long, Long> oracle,
        FirstFail firstFail,
        Long targetValue,
        String detail,
        long elapsedMs) {

    /** Back-compatible constructor (elapsed unknown → 0). */
    public ValidationResult(String status, String verdict, Map<Long, Long> values,
                            Map<Long, Long> oracle, FirstFail firstFail, Long targetValue, String detail) {
        this(status, verdict, values, oracle, firstFail, targetValue, detail, 0L);
    }

    /** A single moat mismatch: candidate said {@code got} at {@code k}, truth is {@code want}. */
    public record FirstFail(long k, Long got, long want) {}

    public boolean accepted() {
        return "accept".equals(verdict);
    }
}
