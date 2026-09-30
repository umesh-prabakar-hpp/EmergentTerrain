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
 * Carries verifier-supplied parameters through the locked max-divisor-count protocol.
 * It never selects a fit, invents k/c, retries a failed planned call, or interprets evidence.
 */
public final class MaxDivisorCountMeasurementProtocolStateExecutor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SUGGESTED_K = Pattern.compile(
            "LOOSE:[^\\n]*?re-verify at k=([+-]?[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REFUTING_C = Pattern.compile(
            "TIGHT HOLD[^\\n]*?re-verify with c=([+-]?[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    private MaxDivisorCountMeasurementProtocolStateExecutor() {}
    public record PlannedCall(String phase, String argumentsJson) {}

    public static Optional<PlannedCall> next(PreRegistration registration, List<ToolInvocation> trace) {
        MaxDivisorCountMeasurementPreRegistration.Assessment assessment =
                MaxDivisorCountMeasurementPreRegistration.assess(registration, trace);
        if (!assessment.applies() || assessment.complete()) return Optional.empty();
        if (assessment.fit() == null) return suggestedTightFit(trace);
        if (!assessment.fitRefutationRecorded()) return verifierNamedRefutation(trace, assessment.fit());
        for (MaxDivisorCountMeasurementPreRegistration.Grid grid : assessment.missingHoldouts()) {
            String args = registeredGridArguments(trace, assessment.fit(), grid);
            if (args != null && !containsExactArguments(trace, args)) return Optional.of(new PlannedCall(grid.label(), args));
        }
        return Optional.empty();
    }

    private static Optional<PlannedCall> suggestedTightFit(List<ToolInvocation> trace) {
        for (ToolInvocation call : safe(trace)) {
            if (!matchesGrid(call, MaxDivisorCountMeasurementPreRegistration.fitGrid())
                    || call.resultJson() == null || !call.resultJson().contains(" HELD across")) continue;
            Matcher matcher = SUGGESTED_K.matcher(call.resultJson());
            if (!matcher.find()) continue;
            String args = replaceNumber(call.argsJson(), "k", matcher.group(1));
            return args != null && !containsExactArguments(trace, args)
                    ? Optional.of(new PlannedCall("tight_fit", args)) : Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<PlannedCall> verifierNamedRefutation(List<ToolInvocation> trace,
            MaxDivisorCountMeasurementPreRegistration.Relation fit) {
        for (ToolInvocation call : safe(trace)) {
            if (!matchesGrid(call, MaxDivisorCountMeasurementPreRegistration.fitGrid())
                    || !matchesRelation(call.argsJson(), fit) || call.resultJson() == null) continue;
            Matcher matcher = REFUTING_C.matcher(call.resultJson());
            if (!matcher.find()) return Optional.empty();
            String args = replaceNumber(call.argsJson(), "c", matcher.group(1));
            return args != null && !containsExactArguments(trace, args)
                    ? Optional.of(new PlannedCall("fit_refutation", args)) : Optional.empty();
        }
        return Optional.empty();
    }

    private static String registeredGridArguments(List<ToolInvocation> trace,
            MaxDivisorCountMeasurementPreRegistration.Relation fit,
            MaxDivisorCountMeasurementPreRegistration.Grid grid) {
        for (ToolInvocation call : safe(trace)) {
            if (!matchesRelation(call.argsJson(), fit)) continue;
            try {
                JsonNode parsed = JSON.readTree(call.argsJson());
                if (!(parsed instanceof ObjectNode args)) continue;
                args.put("window_start", grid.start()); args.put("window_size", grid.size()); args.put("num_windows", grid.windows());
                return JSON.writeValueAsString(args);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static boolean containsExactArguments(List<ToolInvocation> trace, String candidate) {
        for (ToolInvocation call : safe(trace)) try {
            if (call != null && "verify_conjecture".equals(call.functionName()) && call.argsJson() != null
                    && JSON.readTree(call.argsJson()).equals(JSON.readTree(candidate))) return true;
        } catch (Exception ignored) { }
        return false;
    }
    private static boolean matchesGrid(ToolInvocation call, MaxDivisorCountMeasurementPreRegistration.Grid grid) {
        if (call == null || !"verify_conjecture".equals(call.functionName()) || call.argsJson() == null) return false;
        try { JsonNode a = JSON.readTree(call.argsJson()); return a.path("window_start").asLong() == grid.start()
                && a.path("window_size").asLong() == grid.size() && a.path("num_windows").asInt() == grid.windows(); }
        catch (Exception ignored) { return false; }
    }
    private static boolean matchesRelation(String args, MaxDivisorCountMeasurementPreRegistration.Relation fit) {
        try { JsonNode a = JSON.readTree(args); return "max_divisor_count".equals(a.path("lhs").asText())
                && "wigert_bound".equals(a.path("rhs").asText()) && same(a.path("k").asDouble(Double.NaN), fit.k())
                && same(a.path("c").asDouble(Double.NaN), fit.c()); }
        catch (Exception ignored) { return false; }
    }
    private static String replaceNumber(String args, String key, String value) {
        try { JsonNode parsed = JSON.readTree(args); if (!(parsed instanceof ObjectNode node)) return null;
            node.put(key, Double.parseDouble(value)); return JSON.writeValueAsString(node); }
        catch (Exception ignored) { return null; }
    }
    private static boolean same(double a, double b) { return Double.isFinite(a) && Math.abs(a - b) <= 1e-9 * Math.max(1, Math.max(Math.abs(a), Math.abs(b))); }
    private static List<ToolInvocation> safe(List<ToolInvocation> trace) { return trace == null ? List.of() : trace; }
}
