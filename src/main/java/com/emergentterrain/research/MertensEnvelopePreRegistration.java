package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.publication.ComputationalStudyPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Locked finite-window protocol for the Möbius-sum increment {@code mertens_delta}.
 *
 * <p>This is intentionally an envelope measurement, not an affine inequality and not a claim about
 * the classical summatory Mertens-function conjectures. The normalizer is the exact local-window
 * definition {@code sqrt(window_size)}; using {@code sqrt(window_start)} would answer a different
 * question. A primary grid, a disjoint high-location grid, and a changed-width grid are fixed
 * before computation so an observed amplitude cannot be selected after looking at it.
 */
public final class MertensEnvelopePreRegistration {
    private static final String PREFIX = "locked-mertens-window-envelope:v1;";
    private static final String LHS = "mertens_delta";
    private static final String SCALE = "sqrt_window_size";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Grid PRIMARY = new Grid("primary", 100_000_000L, 5_000, 20);
    private static final List<Grid> HOLDOUTS = List.of(
            new Grid("location_holdout", 900_000_000L, 5_000, 20),
            new Grid("width_holdout", 1_500_000_000L, 10_000, 10));

    private MertensEnvelopePreRegistration() {}

    /** Returns the protocol only for a Mertens-window increment investigation. */
    public static PreRegistration from(Conjecture conjecture) {
        if (conjecture == null || !LHS.equals(conjecture.lhs())) return null;
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "Measure the signed window Möbius sum and its max-absolute envelope on the fixed primary grid "
                        + PRIMARY.describe() + ", normalized only by " + SCALE + ".",
                "Repeat the unchanged statistic on the predeclared disjoint location and changed-width holdouts.",
                "Treat the primary value as descriptive. A finite-window envelope statement requires all exact grids; "
                        + "it does not establish an asymptotic bound, the Riemann hypothesis, or a Mertens conjecture.",
                "Do not use an affine surrogate, a scale ladder, sqrt(window_start), or a post-result refit. "
                        + "A source search, successful read, and matching Related Work citation are also required.",
                "mvn test -Dtest=MertensEnvelopePreRegistrationTest");
        return new PreRegistration(
                "Measure the finite-window envelope of the signed Möbius sum, not an affine relation to another scalar.",
                "Only unchanged primary, location, and width measurements support the descriptive finite-window result.",
                "The study is incomplete if any exact grid is absent, if a scale ladder substitutes for it, if the "
                        + "normalizer changes, or if the classical Mertens context is not searched, read, and cited.",
                PREFIX + "lhs=" + LHS + ";scale_rhs=" + SCALE + ";primary=" + PRIMARY.encode() + ";"
                        + HOLDOUTS.stream().map(grid -> "holdout=" + grid.encode() + ";")
                        .reduce("", String::concat),
                plan);
    }

    public static boolean isLocked(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && registration.mappingNotes().startsWith(PREFIX);
    }

    /** True only when all preregistered non-ladder envelope calls are retained in the selected trace. */
    public static boolean hasExactEvidence(PreRegistration registration, List<ToolInvocation> trace) {
        if (!isLocked(registration) || trace == null) return false;
        boolean primary = false;
        boolean[] holdouts = new boolean[HOLDOUTS.size()];
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_oscillation".equals(call.functionName()) || call.argsJson() == null
                    || call.resultJson() == null || !call.resultJson().startsWith("verify_oscillation:")) continue;
            try {
                JsonNode args = JSON.readTree(call.argsJson());
                if (!LHS.equals(args.path("lhs").asText()) || !SCALE.equals(args.path("scale_rhs").asText())
                        || args.path("scale_ladder").asBoolean(false)) continue;
                Grid grid = new Grid("", args.path("window_start").asLong(-1),
                        args.path("window_size").asInt(-1), args.path("num_windows").asInt(-1));
                if (PRIMARY.same(grid)) primary = true;
                for (int i = 0; i < HOLDOUTS.size(); i++) if (HOLDOUTS.get(i).same(grid)) holdouts[i] = true;
            } catch (Exception ignored) {
                // A malformed call cannot satisfy a locked protocol.
            }
        }
        for (boolean holdout : holdouts) if (!holdout) return false;
        return primary;
    }

    public static String exactTestDirective(PreRegistration registration) {
        if (!isLocked(registration)) return "";
        return "Run verify_oscillation with lhs=\"" + LHS + "\", scale_rhs=\"" + SCALE
                + "\", and scale_ladder=false for " + PRIMARY.directive() + "; then repeat exactly for "
                + HOLDOUTS.stream().map(Grid::directive).reduce((a, b) -> a + "; " + b).orElse("")
                + ". Call search_literature with subject anchors [\"mertens\", \"mobius\"], then "
                + "read_reference on an issued evidence key and cite that same [HF...] key in Related Work.";
    }

    record Grid(String label, long start, int size, int windows) {
        boolean same(Grid other) { return start == other.start && size == other.size && windows == other.windows; }
        String encode() { return label + "," + start + "," + size + "," + windows; }
        String describe() { return label + " [start=" + start + ", size=" + size + ", windows=" + windows + "]"; }
        String directive() { return "window_start=" + start + ", window_size=" + size + ", num_windows=" + windows; }
    }
}
