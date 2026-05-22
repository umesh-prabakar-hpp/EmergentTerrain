package com.emergentterrain.research.recognise;

/**
 * Phase 26 §26.4 — sum type returned by {@link ConstantRecognitionService}.
 *
 * <p>Three variants:
 * <ul>
 *   <li>{@link PrimaryHit} — engine produced a recognised constant
 *       with sufficient confidence to cite. The narration layer
 *       embeds the display name + citation handle in prose; the
 *       {@link RecognitionEvidence} flows into the paper's audit
 *       chain for the validator.</li>
 *   <li>{@link NoMatch} — no engine recognised the value. Narration
 *       falls back to the naked decimal / sequence; no audit row.</li>
 *   <li>{@link LowConfidence} — engine found something but at
 *       borderline quality (e.g. PSLQ relation with near-maximum
 *       coefficients, OEIS match with low-rank transform).
 *       Surfaced separately so the narration layer can either skip
 *       or cite with a hedge ("possibly ζ(3); confidence low").
 *       Currently unused in v1 — engines self-filter to PrimaryHit
 *       / NoMatch — but reserved for the §26.6 acceptance work
 *       which may need finer-grained confidence signals.</li>
 * </ul>
 */
public sealed interface RecognitionResult
        permits RecognitionResult.PrimaryHit,
                RecognitionResult.NoMatch,
                RecognitionResult.LowConfidence {

    /** Recognition succeeded with full confidence. */
    record PrimaryHit(RecognitionEvidence evidence) implements RecognitionResult {
        public String engine()         { return evidence.engine(); }
        public String displayName()    { return evidence.displayName(); }
        public String citationHandle() { return evidence.citationHandle(); }
    }

    /** No engine recognised the value. */
    record NoMatch() implements RecognitionResult {
        public static final NoMatch INSTANCE = new NoMatch();
    }

    /**
     * Engine found something but at borderline quality. {@code reason}
     * is operator-readable. Partial evidence is preserved for the
     * validator to inspect even though we wouldn't cite it.
     */
    record LowConfidence(String reason, RecognitionEvidence partialEvidence)
            implements RecognitionResult {}
}
