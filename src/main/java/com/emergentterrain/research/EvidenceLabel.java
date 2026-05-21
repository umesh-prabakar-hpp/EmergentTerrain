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
 *       deductive steps. For Phase 23 this is reserved-but-unreachable —
 *       no current automated theorem prover can certify novel
 *       Graffiti-style inequalities, so {@code THEOREM} should not appear
 *       on output until a proof-engine slice ships.</dd>
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
    UNEXPLAINED_OBSERVATION
}
