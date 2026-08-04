package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // Phase 7 G2 — Jaccard threshold for near-clone match. 0.85 of the smaller
    // multiset means: at most ~15% of tokens diverge between the two papers.
    // Caught misses from REVIEW_REPORT.md refresh #8 (P-276 vs REJECTED P-255,
    // Computation-2 stub added ~3 extra numeric tokens) and refresh #9 (P-284
    // truncated at Vf=60% trimmed the same number from the tail). Both clusters
    // share 12+ numeric tokens; |∩| / min = 12/15 = 0.80, slightly below 0.85
    // — but the (domain, topic) gate already restricts the candidate set to
    // intra-topic priors, so a 0.85 threshold is conservative without being
    // sensitive to legitimate intra-topic variants.
    private static final double JACCARD_THRESHOLD = 0.85;

    /**
     * Returns a stable, sorted fingerprint of the findings string for the given
     * (domain, topic) pair. Empty string when the findings are null or carry
     * fewer than 3 numeric tokens (insufficient signal to declare a clone).
     */
    public static String fingerprint(String domain, String topic, String findings) {
        List<String> tokens = numericTokens(findings);
        if (tokens.size() < 3) return "";
        if (domain == null || topic == null) return "";
        Collections.sort(tokens);
        return domain + "::" + topic + "::" + String.join(",", tokens);
    }

    /**
     * Extracts the 3-sig-fig-rounded numeric tokens from the findings string,
     * preserving multiplicity (so the sphere-packing template's 0.7405 repeated
     * 4× contributes 4 tokens). Order is the appearance order in the input;
     * callers that want a stable fingerprint should sort.
     */
    static List<String> numericTokens(String findings) {
        List<String> tokens = new ArrayList<>();
        if (findings == null) return tokens;
        Matcher m = NUMERIC_TOKEN.matcher(findings);
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                tokens.add(roundToThreeSigFigs(v));
            } catch (NumberFormatException ignore) {}
        }
        return tokens;
    }

    /**
     * Phase 7 G2 — multiset Jaccard similarity normalised by the SMALLER set.
     * Returns 1.0 when one multiset is a subset of the other (so tail-truncation
     * or tail-extension by a few tokens scores high), and 0.0 when the multisets
     * are disjoint. Multiplicity is respected: ["0.7405", "0.7405"] ∩ ["0.7405"]
     * has size 1, not 2.
     */
    static double jaccardSimilarity(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Map<String, Integer> countsA = new HashMap<>();
        for (String t : a) countsA.merge(t, 1, Integer::sum);
        int intersection = 0;
        for (String t : b) {
            Integer remaining = countsA.get(t);
            if (remaining != null && remaining > 0) {
                countsA.put(t, remaining - 1);
                intersection++;
            }
        }
        return (double) intersection / Math.min(a.size(), b.size());
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
    /**
     * Phase 7 F2 — structured-findings overload. Operates on the concatenation of
     * every block's prose — fingerprinting and Jaccard tokenisation see the same
     * tokens they would have seen for today's joined String layout.
     */
    public static List<String> findClones(String domain, String topic,
                                           java.util.Map<Integer, String> findingsMap,
                                           List<ResearchPaper> roomPapers) {
        return findClones(domain, topic, MagnitudeRangeValidator.joinBlocks(findingsMap), roomPapers);
    }

    public static List<String> findClones(String domain, String topic, String findings,
                                           List<ResearchPaper> roomPapers) {
        String fp = fingerprint(domain, topic, findings);
        if (fp.isEmpty()) return List.of();
        List<String> tokens = numericTokens(findings);
        List<String> matches = new ArrayList<>();
        for (ResearchPaper prior : roomPapers) {
            if (prior == null || prior.paperId() == null) continue;
            String status = prior.status();
            if ("NULL_RESULT".equals(status)) continue;
            // Domain + topic gate — Jaccard fallback would otherwise produce
            // false positives across unrelated topics that happen to share
            // common numeric tokens (e.g. "1" or "10").
            if (!domain.equalsIgnoreCase(prior.domain())
                    || !topic.equalsIgnoreCase(prior.topic())) continue;
            String priorFp = fingerprint(prior.domain(), prior.topic(), prior.findings());
            if (priorFp.isEmpty()) continue;
            // Fast path — exact fingerprint match (Phase 5 B3 behaviour).
            if (priorFp.equals(fp)) {
                matches.add(prior.paperId());
                continue;
            }
            // Phase 7 G2 — Jaccard fallback for near-clones that diverge by a
            // few tokens due to truncation (paper trimmed early) or extension
            // (Computation-2 stub adds tokens). REVIEW_REPORT.md refresh #8/#9
            // documented 3 confirmed dedup-misses fixed by this path.
            List<String> priorTokens = numericTokens(prior.findings());
            if (priorTokens.size() < 3) continue;
            if (jaccardSimilarity(tokens, priorTokens) >= JACCARD_THRESHOLD) {
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
