package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Prevents a paper from presenting an uncited external result as established
 * related work when every recorded literature lookup returned no
 * relevance-safe evidence.
 *
 * <p>This is intentionally narrower than a general fact checker. It does not
 * judge whether a claim is true, and it does not require literature for a
 * paper that made no literature lookup. It only catches the contradictory
 * trace/prose state: {@code search_literature} found no safe source, yet
 * Related Work asserts that outside theory or prior work establishes a claim.
 */
public final class RelatedWorkEvidenceValidator {

    private static final Pattern EXTERNAL_SUPPORT_ASSERTION = Pattern.compile(
            "(?i)\\b(?:established|known|classical|standard|prior)\\s+"
                    + "(?:theory|theorem|result|literature|work)\\b"
                    + "|\\b(?:it is|is)\\s+known\\s+that\\b"
                    + "|\\b(?:has been|was)\\s+proved\\b"
                    + "|\\b(?:the literature|prior work|earlier work)\\s+"
                    + "(?:shows?|implies|establishes|proves?|demonstrates)\\b"
                    + "|\\b(?:by|from)\\s+(?:a|the)\\s+"
                    + "(?:known|classical|standard)\\s+(?:theorem|result)\\b");

    /** A bounded no-match is retrieval telemetry, never support for novelty or mechanism absence. */
    private static final Pattern NO_EVIDENCE_INFERENCE = Pattern.compile(
            "(?i)\\b(?:supports?|indicates?|suggests?|establishes?|demonstrates?)\\s+"
                    + "(?:a\\s+)?(?:limited\\s+|specific\\s+)?novelty\\b"
                    + "|\\b(?:no|without)\\s+(?:known|documented)\\s+"
                    + "(?:structural\\s+)?mechanism\\b"
                    + "|\\bno\\s+(?:prior|earlier)\\s+(?:work|literature|result)\\b");

    private RelatedWorkEvidenceValidator() {}

    /**
     * Returns a stable reason when Related Work makes an external-support
     * assertion after one or more literature searches all returned no evidence.
     */
    public static List<String> validate(String relatedWork, List<ToolInvocation> trace) {
        if (relatedWork == null || relatedWork.isBlank() || trace == null || trace.isEmpty()) {
            return List.of();
        }

        boolean sawSearch = false;
        boolean sawEvidence = false;
        for (ToolInvocation call : trace) {
            if (call == null || !"search_literature".equals(call.functionName())) {
                continue;
            }
            sawSearch = true;
            if (issuedRelevanceSafeEvidence(call.resultJson())) {
                sawEvidence = true;
                break;
            }
        }
        if (!sawSearch || sawEvidence) {
            return List.of();
        }
        if (EXTERNAL_SUPPORT_ASSERTION.matcher(relatedWork).find()) {
            return List.of("related_work:external_claim_without_relevance_safe_evidence");
        }
        if (NO_EVIDENCE_INFERENCE.matcher(relatedWork).find()) {
            return List.of("related_work:novelty_or_mechanism_inference_without_relevance_safe_evidence");
        }
        return List.of();
    }

    /**
     * Replaces an overclaiming no-evidence paragraph with the only safe statement the trace can
     * support.  This is deliberately provenance-only: it makes no claim about novelty, mechanism,
     * or the state of the literature outside the bounded retrieval.
     */
    public static String neutralizeNoEvidenceRelatedWork(String relatedWork, List<ToolInvocation> trace) {
        if (validate(relatedWork, trace).isEmpty()) return relatedWork;
        return "The recorded bounded literature search returned no relevance-safe source for this claim. "
                + "This is retrieval coverage state only and does not establish an external conclusion.";
    }

    private static boolean issuedRelevanceSafeEvidence(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        String normalized = result.toLowerCase(Locale.ROOT);
        if (normalized.contains("no evidence was issued")
                || normalized.contains("no sufficiently specific literature anchors")
                || normalized.contains("no theorem-index matches")
                || normalized.contains("no references found")
                || normalized.contains("no qualifying source")) {
            return false;
        }
        return normalized.contains("evidence:") || result.matches("(?s).*\\[HF[A-Za-z0-9_.:-]+].*");
    }
}
