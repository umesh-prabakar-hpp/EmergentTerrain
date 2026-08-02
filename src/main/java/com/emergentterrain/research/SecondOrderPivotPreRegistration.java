package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.publication.ComputationalStudyPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Locks the direct second-order route for a mean-divisor-count investigation before computation. */
public final class SecondOrderPivotPreRegistration {
    private static final String PREFIX = "locked-second-order-pivot:v1;";
    private static final String ORIGIN = "mean_divisor_count";
    private static final String TARGET = "dirichlet_error";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Grid LOCATION = new Grid(900_000_000L, 5_000, 20);
    private static final Grid WIDTH = new Grid(1_500_000_000L, 10_000, 10);

    private SecondOrderPivotPreRegistration() {}

    public static PreRegistration from(Conjecture conjecture) {
        if (conjecture == null || !ORIGIN.equals(conjecture.lhs())) return null;
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "Use the fixed five-decade verify_oscillation ladder for normalized " + TARGET + ".",
                "Use predeclared disjoint location and changed-width grids for the unchanged " + TARGET + " statistic.",
                "Freeze target, raw normalization, ladder, and holdouts before computation; no fitted envelope selects a replacement target.",
                "Report finite calibration only; do not infer the Dirichlet exponent from finite reach.",
                "mvn test -Dtest=SecondOrderPivotPreRegistrationTest");
        return new PreRegistration(
                "Measure the finite normalized Dirichlet-divisor error envelope as the locked second-order object for mean divisor count.",
                "The raw " + TARGET + " envelope is recorded on the primary ladder and two independent holdouts without a scale RHS.",
                "The protocol fails if a different scalar, extra normalizer, missing holdout, or unassessed literature chain replaces the locked study.",
                PREFIX + "origin_lhs=" + ORIGIN + ";target_lhs=" + TARGET
                        + ";frame=verify_oscillation;scale_ladder=true;location=" + LOCATION.encode()
                        + ";width=" + WIDTH.encode() + ";anchors=dirichlet,divisor;",
                plan);
    }

    public static boolean isLocked(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && registration.mappingNotes().startsWith(PREFIX);
    }

    /** Requires the primary scale ladder plus exact independent location and width checks. */
    public static boolean hasExactEvidence(PreRegistration registration, List<ToolInvocation> trace) {
        if (!isLocked(registration) || trace == null) return false;
        boolean ladder = false, location = false, width = false;
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_oscillation".equals(call.functionName()) || call.argsJson() == null
                    || call.resultJson() == null) continue;
            try {
                JsonNode args = JSON.readTree(call.argsJson());
                if (!TARGET.equals(args.path("lhs").asText()) || args.hasNonNull("scale_rhs")) continue;
                if (args.path("scale_ladder").asBoolean(false)
                        && call.resultJson().startsWith("verify_oscillation scale-ladder:")) ladder = true;
                if (!args.path("scale_ladder").asBoolean(false)
                        && call.resultJson().startsWith("verify_oscillation:")) {
                    Grid grid = new Grid(args.path("window_start").asLong(-1),
                            args.path("window_size").asInt(-1), args.path("num_windows").asInt(-1));
                    location |= LOCATION.same(grid);
                    width |= WIDTH.same(grid);
                }
            } catch (Exception ignored) { }
        }
        return ladder && location && width;
    }

    public static String exactTestDirective(PreRegistration registration) {
        if (!isLocked(registration)) return "";
        return "Use ONLY lhs=\"dirichlet_error\" with verify_oscillation and OMIT scale_rhs. Run one "
                + "scale_ladder=true primary measurement, then scale_ladder=false holdouts "
                + LOCATION.directive() + " and " + WIDTH.directive() + ". Search with subject anchors "
                + "[\"dirichlet\",\"divisor\"], read one issued key, assess its exact returned excerpt, "
                + "and cite that same key in Related Work. This is a finite calibration, never an exponent proof.";
    }

    private record Grid(long start, int size, int windows) {
        boolean same(Grid other) { return start == other.start && size == other.size && windows == other.windows; }
        String encode() { return start + "," + size + "," + windows; }
        String directive() { return "window_start=" + start + ", window_size=" + size + ", num_windows=" + windows; }
    }
}
