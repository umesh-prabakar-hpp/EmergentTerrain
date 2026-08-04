package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.publication.ComputationalStudyPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Finite-envelope protocol for the window-dependent Cramér-normalised prime-gap statistic.
 * It deliberately does not call the statistic a constant limit: the discovery allocation is
 * separated from predeclared location, offset, and width holdouts.
 */
public final class CramerGapEnvelopePreRegistration {
    private static final String PREFIX = "locked-cramer-gap-envelope:v1;";
    private static final String LHS = "cramer_gap_ratio";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Grid DISCOVERY = new Grid("discovery", 100_000_000L, 5_000, 5);
    private static final List<Grid> HOLDOUTS = List.of(
            new Grid("far_location", 900_000_000L, 5_000, 5),
            new Grid("offset_location", 900_005_000L, 5_000, 5),
            new Grid("width_holdout", 900_000_000L, 10_000, 5));

    private CramerGapEnvelopePreRegistration() {}

    public static PreRegistration from(Conjecture conjecture) {
        if (conjecture == null || !LHS.equals(conjecture.lhs())) return null;
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "Measure the finite-window maximum-gap ratio on the fixed discovery grid " + DISCOVERY.describe() + ".",
                "Repeat the unchanged statistic on predeclared far-location, offset, and width holdouts.",
                "Do not fit or claim a limiting constant; retain the exact grid definitions and compare the observed envelope only.",
                "Every holdout must use its exact start, width, and window count. A width widened by a sample budget is not a width sensitivity check.",
                "mvn test -Dtest=CramerGapEnvelopePreRegistrationTest");
        return new PreRegistration(
                "Measure a finite-window Cramér-normalised prime-gap envelope, not convergence to a constant.",
                "The discovery value is descriptive; only unchanged measurements on all registered holdouts support a finite-envelope statement.",
                "The study is incomplete if the discovery grid or any location, offset, or width holdout is absent or if a scale-ladder substitutes for the exact grids.",
                PREFIX + "lhs=" + LHS + ";discovery=" + DISCOVERY.encode() + ";"
                        + HOLDOUTS.stream().map(g -> "holdout=" + g.encode() + ";").reduce("", String::concat),
                plan);
    }

    public static boolean isLocked(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && registration.mappingNotes().startsWith(PREFIX);
    }

    public static boolean hasExactEvidence(PreRegistration registration, List<ToolInvocation> trace) {
        if (!isLocked(registration) || trace == null) return false;
        boolean discovery = false;
        boolean[] holdouts = new boolean[HOLDOUTS.size()];
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_constant".equals(call.functionName()) || call.resultJson() == null
                    || !call.resultJson().startsWith("verify_constant:")) continue;
            try {
                JsonNode args = JSON.readTree(call.argsJson());
                if (!LHS.equals(args.path("lhs").asText()) || args.path("scale_ladder").asBoolean(false)) continue;
                Grid grid = new Grid("", args.path("window_start").asLong(-1),
                        args.path("window_size").asInt(-1), args.path("num_windows").asInt(-1));
                if (DISCOVERY.same(grid)) discovery = true;
                for (int i = 0; i < HOLDOUTS.size(); i++) if (HOLDOUTS.get(i).same(grid)) holdouts[i] = true;
            } catch (Exception ignored) { }
        }
        for (boolean holdout : holdouts) if (!holdout) return false;
        return discovery;
    }

    public static String exactTestDirective(PreRegistration registration) {
        if (!isLocked(registration)) return "";
        return "Run verify_constant with NO target and scale_ladder=false for " + DISCOVERY.directive()
                + "; then run the same unchanged finite-window measurement for "
                + HOLDOUTS.stream().map(Grid::directive).reduce((a, b) -> a + "; " + b).orElse("")
                + ". Do not call the statistic converged or refit a constant.";
    }

    record Grid(String label, long start, int size, int windows) {
        boolean same(Grid other) { return start == other.start && size == other.size && windows == other.windows; }
        String encode() { return label + "," + start + "," + size + "," + windows; }
        String describe() { return label + " [start=" + start + ", size=" + size + ", windows=" + windows + "]"; }
        String directive() { return "window_start=" + start + ", window_size=" + size + ", num_windows=" + windows; }
    }
}
