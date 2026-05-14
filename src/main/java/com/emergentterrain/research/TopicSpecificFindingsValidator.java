package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Phase 7 G6 — flag findings prose that uses topic-specific failure-mode
 * idioms documented in refresh #1-#12 of REVIEW_REPORT.md. Stateless and
 * pure: returns the list of textual reasons. Empty list = no failure.
 *
 * <p>This is the validator-side counterpart to the prompt-template tightening
 * the G6 bullet originally framed. Catching the prose post-narration is
 * orthogonal to prompt nudging — even with a perfect template, an LLM can
 * regress, and the validator gives an audit-time signal that the narration
 * stage drifted into a known anti-pattern.
 *
 * <p>Topic match is case-insensitive substring (so "RLC" hits both
 * "RLC Damping Transition" and "RLC Resonance"). Each check fires
 * independently and contributes its own reason string. Callers
 * (UniversityService.publishPaper) downgrade to NEEDS_REVISION when
 * non-empty and the flag is on — fixable in revision, not REJECTED.
 */
public final class TopicSpecificFindingsValidator {

    private TopicSpecificFindingsValidator() {}

    /**
     * RLC anti-pattern — narrator emits one of the round-number sentinels for
     * R_c (10, 25, 50, 100, 200, 400) that match the prompt's example values
     * rather than the actually-computed R_crit. Refresh rec #25.
     */
    private static final Pattern RLC_RC_BOILERPLATE = Pattern.compile(
            "(?i)\\bR_?c\\s+value\\s+of\\s+(?:10|25|50|100|200|400)\\b");

    /**
     * Thermal-Stress anti-pattern — narrator labels the computed stress as
     * "yield strength" instead of "thermal stress" (or σ_thermal). Refresh
     * rec #15: 4 papers in the May-2026 corpus carry this mis-labelling.
     */
    private static final Pattern THERMAL_YIELD_STRENGTH_LABEL = Pattern.compile(
            "(?i)\\byield\\s+strength\\b");

    /**
     * Arrhenius anti-pattern — narrator emits the rate multiplier in scientific
     * notation (e.g. "3.89×10^1" or "1.20 x 10^2") instead of the plain decimal
     * the topic's prompt asks for. Refresh rec #26: ambiguous between 38.9 and
     * 3.89 at a glance.
     */
    private static final Pattern ARRHENIUS_SCIENTIFIC_NOTATION = Pattern.compile(
            "[0-9]+(?:\\.[0-9]+)?\\s*[×x]\\s*10\\^?[0-9]+");

    /**
     * Phase 7 F2 — structured-findings overload. Operates on the concatenation of
     * every block's prose — every per-topic anti-pattern is a regex match, so the
     * map and the joined String produce identical reason lists.
     */
    public static List<String> validate(String domain, String topicName,
                                         java.util.Map<Integer, String> findingsMap) {
        return validate(domain, topicName, MagnitudeRangeValidator.joinBlocks(findingsMap));
    }

    public static List<String> validate(String domain, String topicName, String findings) {
        if (findings == null || findings.isBlank() || topicName == null) return List.of();
        List<String> reasons = new ArrayList<>();
        String topic = topicName.toLowerCase();

        if (topic.contains("rlc") && RLC_RC_BOILERPLATE.matcher(findings).find()) {
            reasons.add("RLC findings cite a boilerplate R_c sentinel value "
                    + "(10/25/50/100/200/400) — verify the value matches the "
                    + "actually-computed R_crit and is not echoing the prompt's example.");
        }
        if (topic.contains("thermal stress") && THERMAL_YIELD_STRENGTH_LABEL.matcher(findings).find()) {
            reasons.add("Thermal-Stress findings reference 'yield strength' — "
                    + "the computed quantity is thermal stress (σ_thermal), not yield strength.");
        }
        if (topic.contains("arrhenius") && ARRHENIUS_SCIENTIFIC_NOTATION.matcher(findings).find()) {
            reasons.add("Arrhenius findings emit a rate multiplier in scientific "
                    + "notation (N×10^M); emit as a plain decimal to avoid the "
                    + "3.89× / 38.9× ambiguity flagged in refresh rec #26.");
        }
        return List.copyOf(reasons);
    }
}
