package com.emergentterrain.research.recognise;

/**
 * Phase 26 §26.3 — exception type for failures talking to the
 * julia-math-sidecar's {@code /recognise/minpoly} endpoint. Separate
 * from {@link PslqRecogniserException} so the orchestrator can catch
 * minimal-polynomial-side failures distinctly from constant-side
 * failures.
 *
 * <p>Error codes:
 * <ul>
 *   <li>{@code transport} — sidecar unreachable / HTTP-layer timeout.</li>
 *   <li>{@code bad_request} — input rejected client-side or sidecar-side.</li>
 *   <li>{@code safety_reject} — value string failed the decimal-literal
 *       regex on the sidecar.</li>
 *   <li>{@code eval_error} — mpmath / PyCall raised during the PSLQ
 *       call on the polynomial basis.</li>
 *   <li>{@code not_enabled} — app.julia.recognise.enabled OR
 *       app.julia.recognise.lll.enabled is false.</li>
 *   <li>{@code response_parse} — sidecar returned non-JSON or an
 *       unexpected envelope.</li>
 * </ul>
 */
public class LllRecogniserException extends RuntimeException {

    private final String errorCode;

    public LllRecogniserException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public LllRecogniserException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
