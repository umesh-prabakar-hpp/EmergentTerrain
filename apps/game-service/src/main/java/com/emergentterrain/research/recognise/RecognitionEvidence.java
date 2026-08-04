package com.emergentterrain.research.recognise;

/**
 * Phase 26 §26.4 — structured audit record of a recognition hit.
 * One row per successful recognise() call; consumed by the future
 * {@code RecognitionCitationValidator} (§26.4b) which verifies that
 * every {@code cite-recognised:*} handle in a paper's prose matches
 * an entry in the paper's evidence chain.
 *
 * <h2>Why structured</h2>
 *
 * The narration layer needs a citation handle to embed in prose
 * ({@code "ζ(3) ≈ 1.20206 [cite-recognised:pslq:zeta(3)]"}). The
 * validator needs to verify that handle resolves to a real engine
 * result. Storing the engine, display name, handle, and engine-
 * specific evidence string in one record gives both layers a single
 * source of truth.
 *
 * @param engine          {@code "oeis"} | {@code "pslq"} | {@code "lll"}
 *                        — which sidecar engine produced the hit.
 * @param displayName     Human-readable name suitable for direct
 *                        citation in prose: {@code "Fibonacci numbers"},
 *                        {@code "ζ(3)"}, {@code "x^4 - 10x^2 + 1"}.
 * @param citationHandle  Unique key the narration embeds in prose
 *                        ({@code "cite-recognised:oeis:A000045"}),
 *                        and the validator looks up against this
 *                        record set.
 * @param evidenceString  Engine-specific audit blob:
 *                        - OEIS: the A-number plus transform chain
 *                          ({@code "A000045:identity"}).
 *                        - PSLQ: the integer relation rendered as
 *                          coefficients × basis names.
 *                        - LLL: the minimal-polynomial coefficient
 *                          array.
 *                        Verbatim so the validator can re-verify if
 *                        needed.
 * @param wallMs          Sidecar-reported elapsed time of the
 *                        recognition call. Useful for the §26.6
 *                        in-corpus uptake audit (slow recognitions
 *                        may indicate the cache isn't warming).
 */
public record RecognitionEvidence(
        String engine,
        String displayName,
        String citationHandle,
        String evidenceString,
        long wallMs) {

    /** Engine identifier strings — keep in sync with the validator's allowlist. */
    public static final String ENGINE_OEIS = "oeis";
    public static final String ENGINE_PSLQ = "pslq";
    public static final String ENGINE_LLL  = "lll";
}
