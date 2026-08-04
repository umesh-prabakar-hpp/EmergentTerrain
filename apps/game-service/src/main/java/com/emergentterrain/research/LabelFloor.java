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
 *       tool-call trace) AND the adversarial check did NOT refute the bound
 *       (no {@code verify_conjecture} "REFUTED across …" result — review finding
 *       #2, 2026-05-29). A refuted-as-stated bound caps at CONJECTURE.</li>
 *   <li>{@link EvidenceLabel#CONJECTURE} — default; anything not
 *       meeting the above.</li>
 *   <li>{@link EvidenceLabel#UNEXPLAINED_OBSERVATION} — an explicit
 *       {@code Mechanism kind: none} / {@code no mechanism identified}
 *       declaration is a finite calibration, not a conjectural explanation.
 *       It therefore caps a non-proof paper at this weaker label even if a
 *       panel proposes a stronger one.</li>
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
        if (explicitlyLacksMechanism(corpus)) {
            return EvidenceLabel.UNEXPLAINED_OBSERVATION;
        }
        if (hasMechanism(corpus) && hasAdversarialCheck(corpus)
                && !adversarialCheckRefuted(paper, corpus)) {
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
     *
     * <p>Phase 26 §26.5 carve-out: {@link EvidenceLabel#KNOWN_RESULT}
     * is orthogonal to the evidence axis — set explicitly by the
     * recognition pipeline when a mined conjecture turns out to be a
     * recognised constant / sequence. The floor never demotes
     * <em>away from</em> KNOWN_RESULT (a rediscovery stays a
     * rediscovery regardless of how strong the proof structure looks),
     * and never promotes <em>to</em> KNOWN_RESULT (no text marker
     * justifies it). So KNOWN_RESULT short-circuits both legs of the
     * decision: if proposed is KNOWN_RESULT, return KNOWN_RESULT.
     */
    public static EvidenceLabel demote(EvidenceLabel proposed, ResearchPaper paper) {
        if (proposed == null) return EvidenceLabel.CONJECTURE;
        if (proposed == EvidenceLabel.KNOWN_RESULT) return EvidenceLabel.KNOWN_RESULT;
        EvidenceLabel ceiling = maxDefensibleLabel(paper);
        // Ordinal order: THEOREM=0 (strongest) → UNEXPLAINED_OBSERVATION=3 (weakest)
        // → KNOWN_RESULT=4 (orthogonal-but-handled-above).
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

    /**
     * A narrator is allowed to say that no structural explanation was found;
     * that honesty must not accidentally satisfy {@link #hasMechanism(String)}
     * merely because it contains the word "mechanism". The explicit phrases
     * are the conjecture-template contract, so this remains a narrow, auditable
     * ceiling rather than an attempt to infer mechanism quality from prose.
     */
    private static boolean explicitlyLacksMechanism(String lower) {
        return lower.contains("mechanism kind: none")
                || lower.contains("no mechanism identified");
    }

    private static boolean hasAdversarialCheck(String lower) {
        return lower.contains("adversarial check")
                || lower.contains("verify_conjecture");
    }

    /**
     * 2026-05-29 (review finding #2) — EMPIRICAL_RESULT must rest on an adversarial
     * check that actually HELD. {@code verify_conjecture} formats its result as
     * "… HELD across N window(s) …" or "… REFUTED across N window(s) …"
     * (MathToolSuite); if any held-out window REFUTED the bound, the conjecture as
     * stated is false and the strongest honest label is {@link EvidenceLabel#CONJECTURE},
     * not EMPIRICAL_RESULT (an honest "holds only for large n" finding is still a
     * conjecture about the stated universal bound). Scans the authoritative tool-call
     * trace plus the narrated corpus (already lowercased).
     */
    private static boolean adversarialCheckRefuted(ResearchPaper p, String lowerCorpus) {
        String trace = p == null ? null : p.toolCallTrace();
        String lowerTrace = trace == null ? "" : trace.toLowerCase();
        return lowerTrace.contains("refuted across") || lowerCorpus.contains("refuted across");
    }
}
