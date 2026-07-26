package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.ConjectureInvestigationWriteBackService;
import com.emergentterrain.research.conjecture.ConjectureInvestigationWriteBackService.HeldBound;
import com.emergentterrain.service.BindingExtensionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.OptionalLong;

/**
 * A tight affine result needs more than a same-grid HOLD/REFUTED pair.  The
 * execution policy deterministically chooses and locks a second grid before
 * running it; this validator accepts only a HOLD on that exact grid with the
 * bound coefficients unchanged.
 */
public final class AffineBindingIndependentGridValidator {
    private static final ObjectMapper JSON = new ObjectMapper();

    private AffineBindingIndependentGridValidator() {}

    /** True when a selected trace proves local binding but lacks its locked independent replication. */
    public static boolean tightBindingWithoutIndependentReplication(List<ToolInvocation> trace) {
        List<HeldBound> heldBounds = ConjectureInvestigationWriteBackService.parseHeldBounds(trace);
        boolean tightDocumentedPair = false;
        for (HeldBound held : heldBounds) {
            if (held.relSlack() == null || held.relSlack() > BindingExtensionPolicy.TIGHT_RELATIVE_SLACK
                    || !ConjectureInvestigationWriteBackService.hasDocumentedBindingPair(held, trace)) {
                continue;
            }
            tightDocumentedPair = true;
            if (hasLockedIndependentHold(held, trace)) return false;
        }
        return tightDocumentedPair;
    }

    private static boolean hasLockedIndependentHold(HeldBound held, List<ToolInvocation> trace) {
        if (trace == null) return false;
        OptionalLong expectedStart = BindingExtensionPolicy.independentWindowStart(
                held.windowStart(), held.windowSize(), held.numWindows());
        if (expectedStart.isEmpty()) return false;
        for (ToolInvocation invocation : trace) {
            if (invocation == null || !"verify_conjecture".equals(invocation.functionName())
                    || invocation.argsJson() == null || invocation.resultJson() == null
                    || !invocation.resultJson().contains(" HELD across ")) continue;
            try {
                JsonNode args = JSON.readTree(invocation.argsJson());
                if (matchesLockedReplication(held, expectedStart.getAsLong(), args)) return true;
            } catch (Exception ignored) {
                // A malformed invocation cannot evidence a locked replication.
            }
        }
        return false;
    }

    private static boolean matchesLockedReplication(HeldBound held, long expectedStart, JsonNode args) {
        return held.lhs().equals(text(args, "lhs"))
                && held.rhs().equals(text(args, "rhs"))
                && sameOptionalText(held.rhs2(), args, "rhs2")
                && same(held.k(), number(args, "k"))
                && sameOptionalSecondCoefficient(held, args)
                && same(held.c(), number(args, "c"))
                && expectedStart == wholeNumber(args, "window_start")
                && held.windowSize() == wholeNumber(args, "window_size")
                && held.numWindows() == wholeNumber(args, "num_windows");
    }

    private static String text(JsonNode args, String key) {
        JsonNode value = args.path(key);
        return value.isTextual() ? value.textValue() : null;
    }

    private static boolean sameOptionalText(String expected, JsonNode args, String key) {
        JsonNode value = args.path(key);
        return expected == null ? value.isMissingNode() || value.isNull()
                : expected.equals(text(args, key));
    }

    private static Double number(JsonNode args, String key) {
        JsonNode value = args.path(key);
        return value.isNumber() ? value.doubleValue() : null;
    }

    private static boolean sameOptionalSecondCoefficient(HeldBound held, JsonNode args) {
        JsonNode value = args.path("k2");
        return held.rhs2() == null
                ? value.isMissingNode() || value.isNull() || same(0.0, number(args, "k2"))
                : same(held.k2(), number(args, "k2"));
    }

    private static long wholeNumber(JsonNode args, String key) {
        JsonNode value = args.path(key);
        return value.canConvertToLong() ? value.longValue() : Long.MIN_VALUE;
    }

    private static boolean same(double expected, Double actual) {
        return actual != null && Double.isFinite(actual)
                && Math.abs(expected - actual) <= 1e-9 * Math.max(1.0, Math.abs(expected));
    }
}
