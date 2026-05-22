package com.emergentterrain.research.recognise;

/**
 * Phase 26 §26.2 — exception type for failures talking to the
 * julia-math-sidecar's {@code /recognise/constant} endpoint. Separate
 * from {@link OeisRecogniserException} so {@code ConstantRecognitionService}
 * can catch PSLQ-side failures distinctly from OEIS-side failures.
 *
 * <p>Error codes follow the sidecar's response envelope plus
 * client-side categories:
 * <ul>
 *   <li>{@code transport} — sidecar unreachable / HTTP-layer timeout.</li>
 *   <li>{@code bad_request} — input rejected client-side (oversize,
 *       non-decimal string) or sidecar-side (unknown basis).</li>
 *   <li>{@code safety_reject} — value string failed the
 *       PSLQ_VALUE_PATTERN regex on the sidecar.</li>
 *   <li>{@code eval_error} — mpmath / PyCall raised during the PSLQ
 *       call.</li>
 *   <li>{@code not_enabled} — app.julia.recognise.enabled is false.</li>
 *   <li>{@code response_parse} — sidecar returned non-JSON or an
 *       unexpected envelope.</li>
 * </ul>
 */
public class PslqRecogniserException extends RuntimeException {

    private final String errorCode;

    public PslqRecogniserException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PslqRecogniserException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
