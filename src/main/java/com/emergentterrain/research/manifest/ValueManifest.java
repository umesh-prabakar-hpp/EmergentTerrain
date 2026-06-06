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
                if (call == null || call.resultJson() == null) continue;
                String producedBy = "call_" + (i + 1);
                int seq = 0;
                Set<String> seen = new LinkedHashSet<>();
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
        return new ValueManifest(List.copyOf(entries), List.copyOf(perCall),
                List.copyOf(hypoConstants), List.copyOf(truncated));
    }

    /** A successful lookup: the entry (or derivation) that grounds a value. */
    public record Resolution(String description, ManifestEntry entry) {}

    /**
     * Resolve a prose-cited value against the manifest at the ladder's ±1%
     * tolerance, magnitude-symmetric (prose cites unsigned magnitudes of
     * signed trace values). Direct entries win; one-step derivations
     * (hypothesis {@code a·x} with a ∉ {0, ±1}, STRICT tolerance only — the
     * P-b72c-119 laundering lesson; same-call {@code x±y}) are checked next
     * and described explicitly. Empty = untraceable at one step.
     */
    public Optional<Resolution> resolve(double value) {
        if (!Double.isFinite(value)) return Optional.empty();
        for (ManifestEntry e : entries) {
            if (matches(value, e.value())) {
                return Optional.of(new Resolution(e.label(), e));
            }
        }
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
     * Compact JSON for the sidecar file. Hand-rolled: every field is a numeric
     * token or a {@code [A-Za-z0-9_]} label, so no escaping is required; this
     * keeps the class dependency-free.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(entries.size() * 64 + 128);
        sb.append("{\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            ManifestEntry e = entries.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"label\":\"").append(e.label())
                    .append("\",\"value\":").append(e.value())
                    .append(",\"rendering\":\"").append(e.rendering())
                    .append("\",\"producedBy\":\"").append(e.producedBy())
                    .append("\",\"kind\":\"").append(e.kind())
                    .append("\"}");
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
