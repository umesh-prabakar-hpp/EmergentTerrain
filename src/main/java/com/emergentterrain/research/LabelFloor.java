package com.emergentterrain.research;

/**
 * Phase 23.6 — text-heuristic floor that bounds the maximum
 * {@link EvidenceLabel} a paper can defensibly carry. Used to
 * demote labels proposed by the LLM reviewer panel when the paper's
 * text doesn't carry the evidence that label implies.
 *
 * <p>Rules (strongest defensible label first):
 * <ul>
 *   <li>{@link EvidenceLabel#THEOREM} — paper text contains
 *       (a) "Q.E.D.", "∎", or a "## Proof" section header (the
 *       Phase 23.6 narrative-proof markers), OR (b) a Phase 25 §25.4
 *       machine-certified proof marker: both the literal
 *       {@code "prove_identity"} tool name AND the bolded
 *       {@code "**PROVED**"} verdict the tool's PROVED output emits.
 *       The double-marker check is conservative — the LLM doesn't
 *       routinely emit Markdown-bolded {@code **PROVED**} in prose,
 *       so the combination is a strong signal that the tool actually
 *       fired and returned a certificate.</li>
 *   <li>{@link EvidenceLabel#EMPIRICAL_RESULT} — paper text contains
 *       "mechanism" AND ("adversarial check" OR a {@code verify_conjecture}
 *       tool-call trace).</li>
 *   <li>{@link EvidenceLabel#CONJECTURE} — default; anything not
 *       meeting the above.</li>
 * </ul>
 *
 * <p>The heuristics are intentionally lenient — they're a structural
 * floor, not a content validator. A paper that says "mechanism: it is
 * obvious" will pass the EMPIRICAL_RESULT gate; that's the reviewer
 * panel's quality call, not this floor's. The point of the floor is to
 * block the easy mis-labels: claiming THEOREM on a paper with no proof
 * structure at all, or EMPIRICAL_RESULT on a paper that's pure
 * observation with no mechanism articulated anywhere.
 *
 * <p>{@link #demote} is the integration point: callers pass the
 * proposed label and the paper, and get back the highest label that
 * both the proposal and the floor agree on.
 */
public final class LabelFloor {

    private LabelFloor() {}

    /** Maximum label justified by the paper's current text body. */
    public static EvidenceLabel maxDefensibleLabel(ResearchPaper paper) {
        if (paper == null) return EvidenceLabel.CONJECTURE;
        String corpus = corpusFor(paper);
        if (hasProofMarker(corpus) || hasMachineCertifiedProof(corpus)) {
            return EvidenceLabel.THEOREM;
        }
        if (hasMechanism(corpus) && hasAdversarialCheck(corpus)) {
            return EvidenceLabel.EMPIRICAL_RESULT;
        }
        return EvidenceLabel.CONJECTURE;
    }

    /**
     * Return the weaker of {@code proposed} and the floor's ceiling. If
     * {@code proposed} is at or below the ceiling (i.e. equal or weaker
     * label), it passes through; otherwise the floor's ceiling wins and
     * the paper is demoted.
     *
     * <p>{@code null} {@code proposed} is treated as the floor default
     * ({@link EvidenceLabel#CONJECTURE}).
     */
    public static EvidenceLabel demote(EvidenceLabel proposed, ResearchPaper paper) {
        if (proposed == null) return EvidenceLabel.CONJECTURE;
        EvidenceLabel ceiling = maxDefensibleLabel(paper);
        // Ordinal order: THEOREM=0 (strongest) → UNEXPLAINED_OBSERVATION=3 (weakest).
        // Weaker = higher ordinal. Pick the one with the higher ordinal.
        return proposed.ordinal() >= ceiling.ordinal() ? proposed : ceiling;
    }

    private static String corpusFor(ResearchPaper p) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, p.hypothesis());
        appendIfPresent(sb, p.findings());
        appendIfPresent(sb, p.conclusion());
        appendIfPresent(sb, p.discussion());
        return sb.toString().toLowerCase();
    }

    private static void appendIfPresent(StringBuilder sb, String s) {
        if (s != null) {
            sb.append(s).append('\n');
        }
    }

    private static boolean hasProofMarker(String lower) {
        return lower.contains("q.e.d.")
                || lower.contains("∎")
                || lower.contains("## proof");
    }

    /**
     * Phase 25 §25.4 — detect the marker pair emitted by
     * {@code MathToolSuite.proveIdentity()} on a successful SymPy
     * Gosper/WZ proof. Both markers must be present so the floor
     * doesn't admit prose that loosely says "proved" without the
     * tool actually firing. The corpus is already lowercased by
     * {@link #corpusFor}, hence the lowercase form of the bold
     * verdict here.
     */
    private static boolean hasMachineCertifiedProof(String lower) {
        return lower.contains("prove_identity") && lower.contains("**proved**");
    }

    private static boolean hasMechanism(String lower) {
        return lower.contains("mechanism");
    }

    private static boolean hasAdversarialCheck(String lower) {
        return lower.contains("adversarial check")
                || lower.contains("verify_conjecture");
    }
}
