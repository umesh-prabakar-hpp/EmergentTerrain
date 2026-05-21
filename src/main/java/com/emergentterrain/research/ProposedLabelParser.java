package com.emergentterrain.research;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 23.6 — parse the LLM reviewer/narrator's textual response for a
 * proposed {@link EvidenceLabel}.
 *
 * <p>Pure logic, no LLM call here. The future prompt-emission slice will:
 * <ol>
 *   <li>Ask the LLM to label the paper as one of THEOREM /
 *       EMPIRICAL_RESULT / CONJECTURE / UNEXPLAINED_OBSERVATION;</li>
 *   <li>Pipe the LLM's response through {@link #parse} to extract the
 *       claim;</li>
 *   <li>Call {@code paper.setEvidenceLabel(parsed)} before persistence;</li>
 *   <li>{@link LabelFloor#demote} (already wired into
 *       {@code UniversityService.publishPaper}) then validates the claim
 *       against the paper's text and demotes if unsupported.</li>
 * </ol>
 *
 * <p>Recognized patterns (case-insensitive, anywhere in the response):
 * <pre>
 *   EvidenceLabel: THEOREM
 *   Label: empirical_result
 *   ## Evidence Label
 *   EMPIRICAL_RESULT
 *
 *   evidence_label = conjecture
 *   "label": "unexplained_observation"   (JSON-style)
 * </pre>
 *
 * <p>Ambiguity rule: if the LLM emits two <em>different</em> labels in
 * the same response, {@link #parse} returns {@link Optional#empty()} —
 * downstream then either applies the floor's default (CONJECTURE) or
 * asks the LLM to clarify. A response that repeats the <em>same</em>
 * label across multiple patterns parses cleanly.
 */
public final class ProposedLabelParser {

    /**
     * Inline labelled patterns. Each pattern's group(1) captures the
     * label-value-shaped substring; we then resolve it against
     * {@link EvidenceLabel#values()} case-insensitively.
     */
    private static final Pattern[] INLINE_PATTERNS = {
            // EvidenceLabel: X  /  Label: X  /  Evidence Label: X  /  evidence_label = X
            // /  "label": "X"  /  Label: empirical-result   (hyphen normalized post-match)
            Pattern.compile(
                    "(?i)\\b(?:evidence[\\s_]*label|label|evidence)[\"']?\\s*[:=]\\s*[\"']?([A-Z_\\-]+)[\"']?"),
    };

    /**
     * Section-header pattern: "## Evidence Label" followed (after blank
     * lines) by a single label token.
     */
    private static final Pattern SECTION_PATTERN = Pattern.compile(
            "(?im)^#{1,3}\\s*evidence[\\s_]*label\\s*$\\s*([A-Z_\\-]+)");

    private ProposedLabelParser() {}

    /**
     * Extract a proposed label from one LLM response. Returns empty when
     * (a) no recognizable claim is present, (b) the claim doesn't match
     * an {@link EvidenceLabel} value, or (c) the response contains two
     * different recognized labels (ambiguous).
     */
    public static Optional<EvidenceLabel> parse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return Optional.empty();
        }

        Set<EvidenceLabel> seen = new LinkedHashSet<>();

        for (Pattern p : INLINE_PATTERNS) {
            Matcher m = p.matcher(llmResponse);
            while (m.find()) {
                tryAdd(seen, m.group(1));
            }
        }

        Matcher sectionMatcher = SECTION_PATTERN.matcher(llmResponse);
        while (sectionMatcher.find()) {
            tryAdd(seen, sectionMatcher.group(1));
        }

        if (seen.isEmpty()) {
            return Optional.empty();
        }
        if (seen.size() > 1) {
            // Ambiguous: multiple distinct labels in the response. Refuse to guess.
            return Optional.empty();
        }
        return Optional.of(seen.iterator().next());
    }

    private static void tryAdd(Set<EvidenceLabel> sink, String raw) {
        if (raw == null) return;
        String normalized = raw.trim().toUpperCase().replace('-', '_');
        for (EvidenceLabel label : EvidenceLabel.values()) {
            if (label.name().equals(normalized)) {
                sink.add(label);
                return;
            }
        }
    }
}
