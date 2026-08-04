package com.emergentterrain.llm;

/**
 * Phase 25 §25.2 — exception type for failures talking to the
 * {@code julia-math-sidecar}'s {@code /prove_identity} endpoint.
 * Separate from {@link JuliaMathException} so {@code MathToolSuite}
 * can catch sympy-specific failures distinctly (the recovery story
 * differs — no fall-through equivalent to Symja exists for SymPy
 * proofs, so the panel must accept the absence of a proof and
 * downgrade the label rather than swap engines).
 *
 * <p>Error codes follow the sidecar's response envelope:
 * <ul>
 *   <li>{@code safety_reject} — pre-flight regex (Java or sidecar)
 *       rejected an input field</li>
 *   <li>{@code bad_request} — malformed JSON body, missing required
 *       field, unknown algorithm, non-identifier var</li>
 *   <li>{@code eval_error} — SymPy raised during sympify or the
 *       summation/Gosper call</li>
 *   <li>{@code busy} — another /prove_identity call held
 *       PROVE_IDENTITY_LOCK for &gt;5s</li>
 *   <li>{@code transport} — sidecar unreachable / HTTP-layer
 *       timeout (not the sidecar's advisory wall-clock cap)</li>
 *   <li>{@code response_parse} — sidecar returned non-JSON or an
 *       unexpected envelope</li>
 * </ul>
 */
public class SympyIdentityException extends RuntimeException {

    private final String errorCode;

    public SympyIdentityException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SympyIdentityException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
