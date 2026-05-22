package com.emergentterrain.research.recognise;

/**
 * Phase 26 §26.1 — exception type for failures talking to the
 * julia-math-sidecar's {@code /recognise/sequence} endpoint OR
 * reading/writing the Postgres {@code oeis_cache} table. Separate
 * from other recognise exception types so {@code ConstantRecognitionService}
 * can catch sequence-side failures distinctly from constant-side
 * (PSLQ / LLL) failures.
 *
 * <p>Error codes follow the sidecar's response envelope plus
 * client-side categories:
 * <ul>
 *   <li>{@code transport} — sidecar unreachable / OEIS unreachable /
 *       HTTP-layer timeout. Recognition pipeline degrades to
 *       "no match"; narration falls back to naked decimals.</li>
 *   <li>{@code bad_request} — input rejected client-side (too few
 *       terms, non-integer, oversize) or sidecar-side.</li>
 *   <li>{@code not_enabled} — app.julia.recognise.enabled is false
 *       so the client short-circuits.</li>
 *   <li>{@code cache_error} — Postgres read/write failure. Recognise
 *       loops still complete by skipping cache.</li>
 *   <li>{@code response_parse} — sidecar returned non-JSON or an
 *       unexpected envelope.</li>
 * </ul>
 */
public class OeisRecogniserException extends RuntimeException {

    private final String errorCode;

    public OeisRecogniserException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public OeisRecogniserException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
