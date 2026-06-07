package com.emergentterrain.research.manifest;

import com.emergentterrain.llm.ToolInvocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 32 Wave 0 (32.1) — the per-draft verified-values manifest.
 *
 * <p><strong>Capture</strong> walks the hypothesis and every executed tool
 * call's result text with the same broad numeric tokenizer the validator
 * ladder uses ({@code NumericalConsistencyValidator.collectAllNumbers}'s
 * regex, scientific notation included), so manifest recall is ≥ ladder recall
 * <em>by construction</em> — the Wave 0 acceptance bar. Capture is string
 * level in this wave; structured at-source emission (per-tool) is a later
 * refinement that this class's API deliberately doesn't preclude.
 *
 * <p><strong>Derivations are resolved on demand, not pre-materialized.</strong>
 * A 100-window {@code verify_conjecture} dump carries ~500 numeric tokens;
 * pairwise same-call {@code x±y} materialization would explode the manifest
 * quadratically. {@link #resolve} instead checks one-step derivations
 * (hypothesis-constant {@code a·x}; same-call {@code x±y}) at lookup time —
 * mirroring the ladder's {@code isDerivedOneStep} semantics — and reports the
 * derivation it used, so what gets recorded is what was actually cited.
 *
 * <p>Pure, stateless after construction, no Spring. Wave 0 is write-only:
 * nothing routes on this class; it persists beside the paper and powers the
 * Wave 1 shadow validator.
 */
public final class ValueManifest {

    /** Same token surface as the ladder's collectAllNumbers. */
    private static final Pattern NUMERIC_TOKEN = Pattern.compile(
            "[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    /** Matches the ladder's F5.5 relative tolerance. */
    public static final double REL_TOL = 0.01;

    /** Per-call entry cap — no silent truncation: see {@link #truncatedCalls}. */
    static final int MAX_ENTRIES_PER_CALL = 400;

    private final List<ManifestEntry> entries;
    private final List<List<Double>> perCallValues;   // index = call ordinal (0-based)
    private final List<Double> hypothesisConstants;
    private final List<String> truncatedCalls;

    private ValueManifest(List<ManifestEntry> entries,
                          List<List<Double>> perCallValues,
                          List<Double> hypothesisConstants,
                          List<String> truncatedCalls) {
        this.entries = entries;
        this.perCallValues = perCallValues;
        this.hypothesisConstants = hypothesisConstants;
        this.truncatedCalls = truncatedCalls;
    }

    /**
     * Build the manifest for one draft from its pre-registered hypothesis and
     * executed tool-call trace. Null-tolerant: a null/empty trace yields a
     * hypothesis-only manifest (the missing-methods gate owns that case).
     */
    public static ValueManifest capture(String hypothesis, List<ToolInvocation> trace) {
        List<ManifestEntry> entries = new ArrayList<>();
        List<List<Double>> perCall = new ArrayList<>();
        List<Double> hypoConstants = new ArrayList<>();
        List<String> truncated = new ArrayList<>();

        if (hypothesis != null && !hypothesis.isBlank()) {
            int seq = 0;
            Set<String> seen = new LinkedHashSet<>();
            Matcher m = NUMERIC_TOKEN.matcher(hypothesis);
            while (m.find()) {
                String rendering = m.group();
                if (!seen.add(rendering)) continue;
                double v = parse(rendering);
                if (Double.isNaN(v)) continue;
                entries.add(new ManifestEntry("h_" + (++seq), v, rendering,
                        "hypothesis", ManifestEntry.Kind.HYPOTHESIS_CONSTANT));
                hypoConstants.add(v);
            }
        }

        if (trace != null) {
            for (int i = 0; i < trace.size(); i++) {
                ToolInvocation call = trace.get(i);
                List<Double> values = new ArrayList<>();
                perCall.add(values);
                if (call == null) continue;
                String producedBy = "call_" + (i + 1);
                int seq = 0;
                Set<String> seen = new LinkedHashSet<>();
                // ARGS first — preregistered inputs (baseline, k, c, window
                // coordinates) are provenance the result may never echo
                // (P-b72c-241's baseline=0.140). Sharing the per-call value
                // list with outputs lets input-vs-output derivations resolve.
                if (call.argsJson() != null) {
                    Matcher a = NUMERIC_TOKEN.matcher(call.argsJson());
                    while (a.find()) {
                        String rendering = a.group();
                        if (!seen.add(rendering)) continue;
                        double v = parse(rendering);
                        if (Double.isNaN(v)) continue;
                        entries.add(new ManifestEntry(
                                "a" + (i + 1) + "_" + (++seq), v, rendering,
                                producedBy, ManifestEntry.Kind.TOOL_INPUT));
                        values.add(v);
                    }
                }
                if (call.resultJson() != null) {
                    Matcher m = NUMERIC_TOKEN.matcher(call.resultJson());
                    while (m.find()) {
                        String rendering = m.group();
                        if (!seen.add(rendering)) continue;
                        double v = parse(rendering);
                        if (Double.isNaN(v)) continue;
                        if (seq >= MAX_ENTRIES_PER_CALL) {
                            truncated.add(producedBy);
                            break;
                        }
                        entries.add(new ManifestEntry(
                                "m" + (i + 1) + "_" + (++seq), v, rendering,
                                producedBy, ManifestEntry.Kind.TOOL_OUTPUT));
                        values.add(v);
                    }
                }
            }
        }
        return new ValueManifest(List.copyOf(entries), List.copyOf(perCall),
                List.copyOf(hypoConstants), List.copyOf(truncated));
    }

    /** A successful lookup: the entry (or derivation) that grounds a value. */
    public record Resolution(String description, ManifestEntry entry) {}

    /**
     * Resolve a prose-cited value against the manifest at the ladder's ±1%
     * tolerance, magnitude-symmetric (prose cites unsigned magnitudes of
     * signed trace values). Direct entries win; one-step derivations
     * (hypothesis {@code a·x}; {@code h±x}; same-call {@code x±y};
     * range-end) are checked next and described explicitly.
     *
     * <p>Laundering guards are STRUCTURAL, not tolerance-based (2026-06-06
     * commit-review correction — an earlier comment claimed a stricter band
     * that never existed): {@code a ∉ {0, ±1}} on coefficients, same-call
     * grouping on {@code x±y}, the ≤50-value header-call bound on range-end,
     * and NO mantissa-scale fallback anywhere in the derivation pass (the
     * P-b72c-119 lesson). All matching uses the single {@link #REL_TOL} band.
     * Empty = untraceable at one step.
     */
    public Optional<Resolution> resolve(double value) {
        if (!Double.isFinite(value)) return Optional.empty();
        Optional<Resolution> direct = resolveDirect(value);
        if (direct.isPresent()) return direct;
        // a·x — hypothesis constant times any tool value.
        for (double a : hypothesisConstants) {
            if (a == 0.0 || Math.abs(a) == 1.0) continue;
            for (int c = 0; c < perCallValues.size(); c++) {
                for (double x : perCallValues.get(c)) {
                    if (x == 0.0) continue;
                    if (matches(value, a * x)) {
                        return Optional.of(new Resolution(
                                "derived:" + trim(a) + "*" + trim(x)
                                        + "@call_" + (c + 1), null));
                    }
                }
            }
        }
        // h±x — hypothesis constant plus/minus a tool value (P-b72c-229's
        // first-shadow-session finding: "deviation of 0.147" = preregistered
        // 0.180 − observed 0.033, prose-labeled "computed post-hoc". The
        // prediction-vs-observation gap is the single most natural honest
        // derivation a results section makes; strict tolerance only.
        for (double h : hypothesisConstants) {
            if (h == 0.0) continue;
            for (int c = 0; c < perCallValues.size(); c++) {
                for (double x : perCallValues.get(c)) {
                    if (x == 0.0) continue;
                    if (matches(value, Math.abs(h - x))) {
                        return Optional.of(new Resolution("derived:|" + trim(h)
                                + "-" + trim(x) + "|@hyp,call_" + (c + 1), null));
                    }
                    if (matches(value, h + x)) {
                        return Optional.of(new Resolution("derived:" + trim(h)
                                + "+" + trim(x) + "@hyp,call_" + (c + 1), null));
                    }
                }
            }
        }
        // x±y over values from the SAME call.
        for (int c = 0; c < perCallValues.size(); c++) {
            List<Double> vs = perCallValues.get(c);
            for (int i = 0; i < vs.size(); i++) {
                for (int j = i + 1; j < vs.size(); j++) {
                    if (matches(value, vs.get(i) + vs.get(j))) {
                        return Optional.of(new Resolution("derived:" + trim(vs.get(i))
                                + "+" + trim(vs.get(j)) + "@call_" + (c + 1), null));
                    }
                    if (matches(value, Math.abs(vs.get(i) - vs.get(j)))) {
                        return Optional.of(new Resolution("derived:|" + trim(vs.get(i))
                                + "-" + trim(vs.get(j)) + "|@call_" + (c + 1), null));
                    }
                }
            }
        }
        // Range end: start + count·size over SAME-call values (P-b72c-236's
        // second-shadow-session finding: "ranges (150,000–170,000, …)" where
        // the scan was scalar_window_stats — no per-window dump, so the end
        // coordinate is three-operand arithmetic over the call's own header
        // values). Bounded: count must be a small positive integer (≤ 10⁴),
        // start/size positive — the window-sweep geometry, nothing else.
        for (int c = 0; c < perCallValues.size(); c++) {
            List<Double> vs = perCallValues.get(c);
            // O(n³) probe — restricted to header-style calls (stats summaries,
            // ~15 values). Per-window dumps carry their range ends literally
            // and resolve directly, so they never need this rule.
            if (vs.size() > 50) continue;
            for (double count : vs) {
                if (count <= 0 || count > 10_000 || count != Math.rint(count)) continue;
                for (double size : vs) {
                    if (size <= 0) continue;
                    for (double start : vs) {
                        if (start <= 0) continue;
                        if (matches(value, start + count * size)) {
                            return Optional.of(new Resolution("derived:" + trim(start)
                                    + "+" + trim(count) + "*" + trim(size)
                                    + "@call_" + (c + 1), null));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Direct-entry resolution only (no derivations). 2026-06-06
     * commit-review fix: the validator's percent-scale probes must NOT run
     * the full derivation ladder on the scaled value — a fabricated 1230
     * resolved via 1230/100 = 3·4.1 (hypothesis-constant × tool value), the
     * exact composition laundering the review flagged. Scaled probes get
     * direct entries (±1%, magnitude-symmetric) and nothing else.
     */
    public Optional<Resolution> resolveDirect(double value) {
        if (!Double.isFinite(value)) return Optional.empty();
        for (ManifestEntry e : entries) {
            if (matches(value, e.value())) {
                return Optional.of(new Resolution(e.label(), e));
            }
        }
        return Optional.empty();
    }

    private static boolean matches(double cited, double source) {
        if (source == 0.0) return cited == 0.0;
        if (Math.abs(cited - source) / Math.abs(source) <= REL_TOL) return true;
        // Magnitude symmetry (ladder Phase 16 behavior).
        return Math.abs(cited - Math.abs(source)) / Math.abs(source) <= REL_TOL;
    }

    private static double parse(String token) {
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String trim(double v) {
        String s = String.valueOf(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    public List<ManifestEntry> entries() {
        return entries;
    }

    public int entryCount() {
        return entries.size();
    }

    public int callCount() {
        return perCallValues.size();
    }

    public List<String> truncatedCalls() {
        return truncatedCalls;
    }

    /**
     * Columnar JSON for the sidecar file — {@code cols} header + one row per
     * entry. Chosen over per-entry objects after P-b72c-230's 705-entry
     * verify-heavy manifest hit 70KB in object form (repeated keys dominate);
     * columnar cuts ~60% while staying self-describing. Hand-rolled: every
     * field is a numeric token or a {@code [A-Za-z0-9_]} label, so no
     * escaping is required and the class stays dependency-free.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(entries.size() * 40 + 160);
        sb.append("{\"cols\":[\"label\",\"value\",\"rendering\",\"producedBy\",\"kind\"],"
                + "\"rows\":[");
        for (int i = 0; i < entries.size(); i++) {
            ManifestEntry e = entries.get(i);
            if (i > 0) sb.append(',');
            sb.append("[\"").append(e.label())
                    .append("\",").append(e.value())
                    .append(",\"").append(e.rendering())
                    .append("\",\"").append(e.producedBy())
                    .append("\",\"").append(switch (e.kind()) {
                        case TOOL_OUTPUT -> "T";
                        case TOOL_INPUT -> "I";
                        case HYPOTHESIS_CONSTANT -> "H";
                    })
                    .append("\"]");
        }
        sb.append("],\"truncatedCalls\":[");
        for (int i = 0; i < truncatedCalls.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(truncatedCalls.get(i)).append('"');
        }
        sb.append("]}");
        return sb.toString();
    }
}
