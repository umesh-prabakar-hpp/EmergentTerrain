package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns verifier-owned suggestions into the next registered max-prime-gap measurement call.
 *
 * <p>This class does not choose a hypothesis or manufacture numeric parameters.  It only carries
 * a verifier-suggested fit coefficient, verifier-named refuting intercept, and the locked grids
 * through the measurement protocol.  A failed automatic call is terminal for that path: it is
 * preserved in the trace and never silently retried or replaced by a refit.
 */
public final class MaxPrimeGapProtocolStateExecutor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SUGGESTED_K = Pattern.compile(
            "LOOSE:[^\\n]*?re-verify at k=([+-]?[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFUTING_C = Pattern.compile(
            "TIGHT HOLD[^\\n]*?re-verify with c=([+-]?[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    private MaxPrimeGapProtocolStateExecutor() {}

    /** A single state-approved deterministic verification call. */
    public record PlannedCall(String phase, String argumentsJson) {}

    /**
     * Returns the next and only permitted automatic call, if a verifier has supplied one.
     * Empty means the protocol is complete, has no instrument-supplied next parameter, or the
     * previously planned call already ran and failed to advance the state.
     */
    public static Optional<PlannedCall> next(PreRegistration registration, List<ToolInvocation> trace) {
        MaxPrimeGapMultiScalePreRegistration.Assessment assessment =
                MaxPrimeGapMultiScalePreRegistration.assess(registration, trace);
        if (!assessment.applies() || assessment.complete()) return Optional.empty();

        if (assessment.fit() == null) {
            return suggestedTightFit(trace);
        }
        if (!assessment.fitRefutationRecorded()) {
            return verifierNamedRefutation(trace, assessment.fit());
        }
        for (MaxPrimeGapMultiScalePreRegistration.Grid grid : assessment.missingHoldouts()) {
            String args = registeredGridArguments(trace, assessment.fit(), grid);
            if (args != null && !containsExactArguments(trace, args)) {
                return Optional.of(new PlannedCall(grid.label(), args));
            }
        }
        return Optional.empty();
    }

    private static Optional<PlannedCall> suggestedTightFit(List<ToolInvocation> trace) {
        MaxPrimeGapMultiScalePreRegistration.Grid fit = MaxPrimeGapMultiScalePreRegistration.fitGrid();
        for (ToolInvocation call : safe(trace)) {
            if (!isVerification(call) || !matchesGrid(call.argsJson(), fit)
                    || call.resultJson() == null || !call.resultJson().contains(" HELD across")) continue;
            Matcher suggestion = SUGGESTED_K.matcher(call.resultJson());
            if (!suggestion.find()) continue;
            String args = replaceNumber(call.argsJson(), "k", suggestion.group(1));
            if (args != null && !containsExactArguments(trace, args)) {
                return Optional.of(new PlannedCall("tight_fit", args));
            }
            return Optional.empty(); // the tool-supplied candidate was already tried; never loop.
        }
        return Optional.empty();
    }

    private static Optional<PlannedCall> verifierNamedRefutation(List<ToolInvocation> trace,
            MaxPrimeGapMultiScalePreRegistration.Relation fit) {
        MaxPrimeGapMultiScalePreRegistration.Grid fitGrid = MaxPrimeGapMultiScalePreRegistration.fitGrid();
        for (ToolInvocation call : safe(trace)) {
            if (!isVerification(call) || !matchesGrid(call.argsJson(), fitGrid)
                    || !matchesRelation(call.argsJson(), fit) || call.resultJson() == null
                    || !call.resultJson().contains(" HELD across")) continue;
            Matcher refutation = REFUTING_C.matcher(call.resultJson());
            if (!refutation.find()) return Optional.empty();
            String args = replaceNumber(call.argsJson(), "c", refutation.group(1));
            if (args != null && !containsExactArguments(trace, args)) {
                return Optional.of(new PlannedCall("fit_refutation", args));
            }
            return Optional.empty(); // named call was tried; a later refit cannot repair it.
        }
        return Optional.empty();
    }

    private static String registeredGridArguments(List<ToolInvocation> trace,
            MaxPrimeGapMultiScalePreRegistration.Relation fit,
            MaxPrimeGapMultiScalePreRegistration.Grid grid) {
        for (ToolInvocation call : safe(trace)) {
            if (!isVerification(call) || !matchesRelation(call.argsJson(), fit)) continue;
            try {
                JsonNode parsed = JSON.readTree(call.argsJson());
                if (!(parsed instanceof ObjectNode args)) continue;
                args.put("window_start", grid.start());
                args.put("window_size", grid.size());
                args.put("num_windows", grid.windows());
                return JSON.writeValueAsString(args);
            } catch (Exception ignored) {
                // Ignore malformed trace entries; they cannot anchor a registered execution.
            }
        }
        return null;
    }

    private static boolean containsExactArguments(List<ToolInvocation> trace, String candidateArgs) {
        for (ToolInvocation call : safe(trace)) {
            if (!isVerification(call) || call.argsJson() == null) continue;
            try {
                if (JSON.readTree(call.argsJson()).equals(JSON.readTree(candidateArgs))) return true;
            } catch (Exception ignored) {
                // A malformed prior call cannot match an approved action.
            }
        }
        return false;
    }

    private static boolean isVerification(ToolInvocation call) {
        return call != null && "verify_conjecture".equals(call.functionName()) && call.argsJson() != null;
    }

    private static boolean matchesGrid(String argsJson, MaxPrimeGapMultiScalePreRegistration.Grid grid) {
        try {
            JsonNode args = JSON.readTree(argsJson);
            return args.path("window_start").asLong(Long.MIN_VALUE) == grid.start()
                    && args.path("window_size").asLong(Long.MIN_VALUE) == grid.size()
                    && args.path("num_windows").asInt(Integer.MIN_VALUE) == grid.windows();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean matchesRelation(String argsJson, MaxPrimeGapMultiScalePreRegistration.Relation fit) {
        try {
            JsonNode args = JSON.readTree(argsJson);
            return "max_prime_gap".equals(args.path("lhs").asText())
                    && "log_sq_window_start".equals(args.path("rhs").asText())
                    && same(args.path("k").asDouble(Double.NaN), fit.k())
                    && same(args.path("c").asDouble(Double.NaN), fit.c());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String replaceNumber(String argsJson, String key, String value) {
        try {
            JsonNode parsed = JSON.readTree(argsJson);
            if (!(parsed instanceof ObjectNode args)) return null;
            args.put(key, Double.parseDouble(value));
            return JSON.writeValueAsString(args);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean same(double a, double b) {
        return Double.isFinite(a) && Math.abs(a - b) <= 1e-9 * Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
    }

    private static List<ToolInvocation> safe(List<ToolInvocation> trace) {
        return trace == null ? List.of() : trace;
    }
}
