package com.emergentterrain.research.manifest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 32 Wave 1 (32.2, shadow stage) — resolve every salient number in the
 * narrated prose against the draft's {@link ValueManifest} instead of the
 * validator ladder's per-pattern regex passes.
 *
 * <p>Tolerance rules ported from data-to-paper's {@code check_numeric_values}
 * (the four that independently re-derive ladder FP classes ET fixed by hand):
 * <ul>
 *   <li><b>Trivial-number allowlist</b> — counts, axis labels, canonical CI
 *       multipliers, common framing percentages: never demanded traceable.</li>
 *   <li><b>Rounding match</b> — a prose value matches a manifest value when the
 *       source rounds to the cited precision ("0.013" vs manifest 0.012610),
 *       beyond the flat ±1%.</li>
 *   <li><b>Percent 100× ambiguity</b> — "62 %" matches a manifest fraction
 *       0.62 (and the reverse).</li>
 *   <li><b>Bound exemption</b> — a number directly after {@code <}/{@code ≤}/
 *       {@code >}/{@code ≥} states a bound, not a measurement
 *       ("p &lt; 0.001" when the trace holds 0.0004).</li>
 * </ul>
 *
 * <p><b>Shadow contract:</b> pure and stamp-only — the caller logs the
 * {@link Stats} line for the ladder-agreement diff and appends a
 * {@code publish:manifest_miss:warn} stamp; nothing routes on this class until
 * the enforce flip (≥2 sessions at ≥95% agreement + ≥1 net-new catch + zero
 * new FP classes — the Wave 1 acceptance bar). Stateless, no Spring.
 */
public final class ManifestTraceabilityValidator {

    private ManifestTraceabilityValidator() {}

    /**
     * Any complete numeric token (grouped / decimal / scientific). The
     * trailing {@code (?![\w.])} guard is load-bearing: without the dot, a
     * 4-digit integer prefix of "9700.5" matches alone and the token splits.
     * Salience is then decided in code ({@link #isSalient}) so the
     * percent-suffix case can participate.
     */
    private static final Pattern NUMBER_TOKEN = Pattern.compile(
            "(?<![\\w.])("
                    + "[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]+)?"           // grouped
                    + "|[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"        // plain / sci
                    + ")(?![\\w.])");

    /**
     * Salient = a number that reads as a CLAIM: ≥2 decimal places,
     * scientific notation, magnitude ≥ 1000, or carrying a {@code %} suffix
     * (P-b72c-221's confirmed fabrication was the 2-digit "97 %"). Small
     * bare integers ("10 windows", "3 ranges") are structural prose.
     */
    private static boolean isSalient(String token, double value, boolean percent) {
        if (percent) return true;
        if (token.contains("e") || token.contains("E")) return true;
        int dot = token.indexOf('.');
        if (dot >= 0 && token.length() - dot - 1 >= 2) return true;
        return Math.abs(value) >= 1000;
    }

    /** {@code [call_N]} markers — their digits are provenance, not claims. */
    private static final Pattern CALL_REF = Pattern.compile("\\[call_\\d+\\]");

    /** A {@code <=} / {@code <} / {@code >=} / {@code >} / {@code ≤} / {@code ≥} just before the number. */
    private static final Pattern BOUND_PREFIX = Pattern.compile("[<>≤≥]\\s*$");

    /** Percent marker right after the number. */
    private static final Pattern PERCENT_SUFFIX = Pattern.compile("^\\s*%");

    /**
     * Trivial-number allowlist (data-to-paper line-204 pattern, ET-tuned):
     * canonical CI multipliers, CI levels, common framing percentages, unity.
     * Deliberately small — every addition erodes the fabrication catch.
     */
    private static final double[] ALLOWLIST = {
            0.0, 1.0, 0.05, 0.01, 0.001,          // unity + canonical alpha levels
            1.645, 1.96, 2.576,                    // CI multipliers (ladder 2026-06-05 rule)
            90, 95, 99,                            // CI levels
            25, 50, 75, 100,                       // framing percentages (ladder Phase 16)
    };

    /** One unresolved salient number. */
    public record Miss(String token, int blockIndex) {}

    /** Shadow-diff counters for the per-paper log line. */
    public record Stats(int salient, int resolved, int allowlisted,
                        int boundExempt, List<Miss> misses) {}

    /**
     * Resolve every salient number in Findings/Conclusion against the
     * manifest. Null-tolerant on all text inputs.
     */
    public static Stats validate(ValueManifest manifest,
                                 Map<Integer, String> findingsBlocks,
                                 String conclusion) {
        List<Miss> misses = new ArrayList<>();
        int salient = 0, resolved = 0, allowlisted = 0, boundExempt = 0;

        List<String> blocks = new ArrayList<>();
        if (findingsBlocks != null) {
            blocks.addAll(new TreeMap<>(findingsBlocks).values());
        }
        if (conclusion != null && !conclusion.isBlank()) {
            blocks.add(conclusion);
        }

        for (int b = 0; b < blocks.size(); b++) {
            String block = blocks.get(b);
            if (block == null || block.isBlank()) continue;
            // Strip provenance markers so call indices never count as claims.
            String text = CALL_REF.matcher(block).replaceAll(" ");
            Set<String> seenInBlock = new LinkedHashSet<>();
            Matcher m = NUMBER_TOKEN.matcher(text);
            while (m.find()) {
                String token = m.group(1);
                if (!seenInBlock.add(token)) continue;   // one verdict per literal per block
                double value = parse(token);
                if (Double.isNaN(value)) continue;
                boolean percent = PERCENT_SUFFIX.matcher(
                        text.substring(m.end(), Math.min(text.length(), m.end() + 2))).find();
                if (!isSalient(token, value, percent)) continue;
                salient++;

                if (isAllowlisted(value)) {
                    allowlisted++;
                    continue;
                }
                // 8-char lookback (commit-review fix: 3 chars missed "p <   x"
                // with 3+ spaces); BOUND_PREFIX anchors the operator at the end.
                String prefix = text.substring(Math.max(0, m.start() - 8), m.start());
                if (BOUND_PREFIX.matcher(prefix).find()) {
                    boundExempt++;
                    continue;
                }

                if (resolves(manifest, value, percent)) {
                    resolved++;
                } else if (isShownWork(text, m.start(), m.end(), manifest)) {
                    // P-b72c-238's class: the sentence DISPLAYS the formula
                    // ("c ≈ +0.07938 − 2·(+0.00822) = +0.06294") and its
                    // operands individually resolve — derivation-shown, the
                    // data-to-paper \num{formula,"explanation"} case. Counted
                    // as resolved; multi-step laundering stays caught because
                    // the exemption demands BOTH an arithmetic chain in-sentence
                    // AND ≥2 resolvable operands.
                    resolved++;
                } else {
                    misses.add(new Miss(token + (percent ? " %" : ""), b + 1));
                }
            }
        }
        return new Stats(salient, resolved, allowlisted, boundExempt, List.copyOf(misses));
    }

    /** Reasons in the ladder's format, for the additive call-path stamp + WARN line. */
    public static List<String> reasons(Stats stats) {
        List<String> out = new ArrayList<>();
        for (Miss miss : stats.misses()) {
            out.add(String.format(
                    "manifest_miss — salient value '%s' (block %d) resolves to no manifest "
                            + "entry, one-step derivation, rounding, or percent-scale match.",
                    miss.token(), miss.blockIndex()));
        }
        return out;
    }

    /** Arithmetic-chain marker near the token: =, ≈, −, ×, ·, / between numbers. */
    private static final Pattern ARITHMETIC_CHAIN = Pattern.compile("[=≈]");

    /**
     * Shown-work exemption (P-b72c-238): the unresolved value sits in a
     * sentence that contains an explicit equality/approximation chain AND at
     * least two OTHER numbers that individually resolve against the manifest.
     * The reader can check the arithmetic from cited operands — the honest
     * derivation narration 32.2-enforce explicitly permits.
     */
    private static boolean isShownWork(String text, int tokenStart, int tokenEnd,
                                       ValueManifest manifest) {
        if (manifest == null) return false;
        // Sentence bounds around the token. Boundary is ". " (period+space) or
        // newline — NEVER a bare '.', which would split inside decimals
        // ("+0.00822" → "00822…", the bug this comment guards against).
        int s = Math.max(text.lastIndexOf(". ", Math.max(0, tokenStart - 1)),
                text.lastIndexOf('\n', tokenStart));
        int e = text.indexOf('\n', tokenEnd);
        int eDot = text.indexOf(". ", tokenEnd);
        if (eDot >= 0 && (e < 0 || eDot < e)) e = eDot;
        String sentence = text.substring(s < 0 ? 0 : s + 1,
                e < 0 ? text.length() : e);
        if (!ARITHMETIC_CHAIN.matcher(sentence).find()) return false;
        int resolvableOperands = 0;
        Matcher m = NUMBER_TOKEN.matcher(sentence);
        while (m.find() && resolvableOperands < 2) {
            double v = parse(m.group(1));
            if (Double.isNaN(v)) continue;
            if (isAllowlisted(v)) continue;
            if (manifest.resolve(v).isPresent()) resolvableOperands++;
        }
        return resolvableOperands >= 2;
    }

    // ── resolution ──────────────────────────────────────────────────────────

    private static boolean resolves(ValueManifest manifest, double value, boolean percent) {
        if (manifest == null) return false;
        if (manifest.resolve(value).isPresent()) return true;
        // Rounding match: the cited literal is the source rounded to its own
        // precision — try the source set at the cited number's granularity by
        // probing a half-ulp band around the cited value.
        if (resolvesByRounding(manifest, value)) return true;
        // Percent 100x ambiguity. 2026-06-06 commit-review fix: (a) the /100
        // descale fires ONLY on an actual % suffix — the old `|| value > 1.0`
        // descaled every large salient number; (b) scaled probes resolve
        // against DIRECT entries + rounding only, never the derivation ladder
        // (a fabricated 1230 resolved via 1230/100 = 3·4.1 — hypothesis
        // coefficient × tool value — the composition-laundering path).
        if (percent) {
            if (manifest.resolveDirect(value / 100.0).isPresent()
                    || resolvesByRounding(manifest, value / 100.0)) return true;
        }
        if (value < 1.0) {
            if (manifest.resolveDirect(value * 100.0).isPresent()
                    || resolvesByRounding(manifest, value * 100.0)) return true;
        }
        return false;
    }

    /**
     * True when some manifest entry rounds to the cited value at the cited
     * value's decimal precision (e.g. cited 0.013, source 0.012610: rounds to
     * 0.013 at 3 decimals — outside flat ±1%, still the same quantity).
     */
    private static boolean resolvesByRounding(ValueManifest manifest, double cited) {
        int decimals = decimalsOf(cited);
        if (decimals <= 0) return false;
        double halfUlp = 0.5 * Math.pow(10, -decimals);
        for (ManifestEntry e : manifest.entries()) {
            double src = e.value();
            if (Math.abs(Math.abs(src) - cited) <= halfUlp
                    || Math.abs(src - cited) <= halfUlp) {
                return true;
            }
        }
        return false;
    }

    private static int decimalsOf(double cited) {
        String s = String.valueOf(cited);
        int dot = s.indexOf('.');
        if (dot < 0 || s.contains("E") || s.contains("e")) return -1;
        int d = s.length() - dot - 1;
        return (d == 1 && s.endsWith("0")) ? -1 : d;   // "5.0" carries no precision claim
    }

    private static boolean isAllowlisted(double value) {
        for (double a : ALLOWLIST) {
            if (a == 0.0 ? value == 0.0
                    : Math.abs(value - a) / a <= 1e-9) {
                return true;
            }
        }
        return false;
    }

    private static double parse(String token) {
        try {
            return Double.parseDouble(token.replace(",", ""));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
