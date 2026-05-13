package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 5 B3 — detects near-byte-identical reruns of prior room papers.
 *
 * <p>Five confirmed near-clone clusters in the May 2026 corpus, no citations
 * among them:
 * <ul>
 *   <li>P-a328-49 ≡ P-a328-15 (RLC, same L/C/f₀/R_crit)</li>
 *   <li>P-a328-58 ≡ P-a328-33 (VdW, every digit matches)</li>
 *   <li>P-a328-9 / 32 / 53 (binary sphere packing, same 78.96% template)</li>
 *   <li>P-a328-79 ≡ P-a328-68 (Collatz, identical range/peak/mean/CI)</li>
 *   <li>P-a328-69 / 73 / 85 (happy-number density, same 34/150 = 0.23±0.07)</li>
 * </ul>
 *
 * <p>The fingerprint is the sorted multiset of numeric tokens extracted from
 * the findings, rounded to 3 significant figures, prefixed by the (domain,
 * topic) pair. Two papers that ran with the same simulator seed will share
 * the same fingerprint to the last digit; papers with slightly drifted seeds
 * round to the same buckets.
 */
public final class ContentHashDeduplicator {

    private ContentHashDeduplicator() {}

    // (?<![0-9.]) and (?![0-9]) anchor to digit-sequence boundaries rather than
    // word boundaries, so values fused to unit suffixes — "9620MPa", "0.405r" —
    // still register. The fractional lookbehind also prevents "5.344" from
    // emitting both "5" and "344" as separate tokens.
    private static final Pattern NUMERIC_TOKEN =
            Pattern.compile("(?<![0-9.])([0-9]+(?:\\.[0-9]+)?)(?![0-9])");

    /**
     * Returns a stable, sorted fingerprint of the findings string for the given
     * (domain, topic) pair. Empty string when the findings are null or carry
     * fewer than 3 numeric tokens (insufficient signal to declare a clone).
     */
    public static String fingerprint(String domain, String topic, String findings) {
        if (findings == null || domain == null || topic == null) return "";
        List<String> tokens = new ArrayList<>();
        Matcher m = NUMERIC_TOKEN.matcher(findings);
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                tokens.add(roundToThreeSigFigs(v));
            } catch (NumberFormatException ignore) {}
        }
        if (tokens.size() < 3) return "";
        Collections.sort(tokens);
        return domain + "::" + topic + "::" + String.join(",", tokens);
    }

    /**
     * Finds prior room papers whose fingerprint matches the new paper's. Returns
     * paper IDs only (the caller can resolve to {@link ResearchPaper} via the
     * room cache or repository).
     *
     * <p>Excludes papers in NULL_RESULT status — a clone of a "computation ran
     * but hypothesis not supported" paper isn't a duplicate publication. We
     * intentionally do NOT exclude NEEDS_REVISION here: when the critique-skip
     * funnel is dominant, the entire prior cluster is NEEDS_REVISION, and
     * skipping that bucket leaves the fingerprint match-set empty. The
     * downstream uncited-clone check still publishes the duplicate as
     * NEEDS_REVISION; whether the prior was peer-reviewed is irrelevant to
     * "is this paper repeating findings already in the room."
     */
    public static List<String> findClones(String domain, String topic, String findings,
                                           List<ResearchPaper> roomPapers) {
        String fp = fingerprint(domain, topic, findings);
        if (fp.isEmpty()) return List.of();
        List<String> matches = new ArrayList<>();
        for (ResearchPaper prior : roomPapers) {
            if (prior == null || prior.paperId() == null) continue;
            String status = prior.status();
            if ("NULL_RESULT".equals(status)) continue;
            String priorFp = fingerprint(prior.domain(), prior.topic(), prior.findings());
            if (!priorFp.isEmpty() && priorFp.equals(fp)) {
                matches.add(prior.paperId());
            }
        }
        return matches;
    }

    /**
     * Rounds a double to 3 significant figures and returns it as a string. Used
     * by the fingerprinter to bucket near-duplicates: 0.789 → "0.789",
     * 0.7896 → "0.790", 12020.0 → "12000".
     */
    private static String roundToThreeSigFigs(double v) {
        if (v == 0.0) return "0";
        double sign = Math.signum(v);
        double abs  = Math.abs(v);
        double mag  = Math.floor(Math.log10(abs));
        double factor = Math.pow(10, mag - 2);
        double rounded = Math.round(abs / factor) * factor * sign;
        // Drop trailing .0 on whole numbers; keep otherwise. 12020.0 → "12000",
        // 0.789 → "0.789".
        if (rounded == Math.floor(rounded) && Math.abs(rounded) < 1e15) {
            return String.valueOf((long) rounded);
        }
        return String.valueOf(rounded);
    }
}
