package com.emergentterrain.llm;

/**
 * Phase 22 §22.2 — exception type for failures talking to the
 * {@code julia-math-sidecar} HTTP service. Distinct from generic
 * {@code IOException} / {@code RuntimeException} so callers in
 * {@code MathToolSuite} can catch it specifically and fall back to
 * the in-process Symja path without swallowing unrelated errors.
 *
 * <p>Three flavours of failure use this:
 * <ul>
 *   <li><b>Transport</b> — sidecar unreachable, network error, timeout
 *       at the HTTP layer (not the sidecar's own evaluation timeout).</li>
 *   <li><b>Protocol</b> — sidecar returned non-200 or a malformed JSON
 *       envelope. The {@code error} code from the response is preserved
 *       in {@link #errorCode()} when available.</li>
 *   <li><b>Evaluation</b> — sidecar returned {@code ok:false} with a
 *       known error code ({@code safety_rejected}, {@code parse_error},
 *       {@code timeout}, {@code eval_error}, {@code bad_request}).</li>
 * </ul>
 */
public class JuliaMathException extends RuntimeException {

    /**
     * Machine-readable error code from the sidecar's JSON response, or
     * {@code "transport"} when the failure happened before parsing.
     */
    private final String errorCode;

    public JuliaMathException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public JuliaMathException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
