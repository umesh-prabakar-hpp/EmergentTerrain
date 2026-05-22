package com.emergentterrain.research;

/**
 * Phase 23.6 — epistemic claim level of a paper, independent of its
 * {@link PaperKind} (which is a structural category) and its
 * {@link ResearchPaper#status} (which is the peer-review outcome).
 *
 * <p>Ordered from strongest to weakest claim:
 *
 * <dl>
 *   <dt>{@link #THEOREM}</dt>
 *   <dd>Formal proof present. Reviewers should refuse this label unless
 *       the paper carries a "Proof" structure backed by tool-verified
 *       deductive steps. Phase 25 made this reachable for hypergeometric
 *       sum identities via the {@code prove_identity} tool;
 *       {@link LabelFloor} also raises THEOREM when prose carries the
 *       {@code prove_identity}+{@code **PROVED**} marker pair (§25.4).</dd>
 *
 *   <dt>{@link #EMPIRICAL_RESULT}</dt>
 *   <dd>Conjecture survived a held-out check, AND the paper proposes a
 *       structural mechanism for why it holds. Requires both
 *       <em>verified evidence</em> (adversarial check via
 *       {@code verify_conjecture} or analogous tool) and
 *       <em>articulated mechanism</em>.</dd>
 *
 *   <dt>{@link #CONJECTURE}</dt>
 *   <dd>Conjecture survived the miner's runs but has not yet been
 *       independently verified or mechanistically explained.
 *       <strong>Default for newly published papers</strong> — every
 *       paper starts here and a higher label must be earned.</dd>
 *
 *   <dt>{@link #UNEXPLAINED_OBSERVATION}</dt>
 *   <dd>Hypothesis was tested and the result is recorded, but neither
 *       mechanism nor sustained survival under adversarial conditions
 *       is established. Often the right label for one-shot
 *       observational papers.</dd>
 *
 *   <dt>{@link #KNOWN_RESULT}</dt>
 *   <dd><b>Phase 26 §26.5.</b> The mined / proposed conjecture turned
 *       out to be a recognised constant, sequence, or algebraic identity
 *       — the system rediscovered something already in OEIS / PSLQ basis
 *       sets / LLL minimal-polynomial recovery. Set explicitly by the
 *       recognition pipeline when {@code ConstantRecognitionService}
 *       returns a {@code PrimaryHit}; orthogonal to the
 *       THEOREM/EMPIRICAL_RESULT/CONJECTURE evidence axis above.
 *       Conjectures with this label are <strong>not queued for
 *       publication</strong> — they're audited as rediscoveries with
 *       a pointer at the recognition result. {@link LabelFloor} never
 *       promotes <em>to</em> KNOWN_RESULT (there's no structural-text
 *       marker for "this is a rediscovery"); it's an explicit setting
 *       by the miner/recognition step, and once set, the panel
 *       cannot override it.</dd>
 * </dl>
 *
 * <p>The label is persisted on {@link ResearchPaper#evidenceLabel} and
 * defaults to {@code CONJECTURE} both as the field initializer and via
 * {@link ResearchPaper#setEvidenceLabel} null-coalescing, mirroring the
 * pattern used by {@link PaperKind}.
 */
public enum EvidenceLabel {
    THEOREM,
    EMPIRICAL_RESULT,
    CONJECTURE,
    UNEXPLAINED_OBSERVATION,
    KNOWN_RESULT
}
