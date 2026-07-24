package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.publication.ComputationalStudyPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Locked, machine-checkable contract for an affine conjecture investigation. */
public final class ConjecturePreRegistration {
    private static final String PREFIX = "locked-conjecture-test:v1;";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConjecturePreRegistration() {}

    public static PreRegistration from(Conjecture conjecture) {
        String relation = "`" + conjecture.lhs() + " ≤ "
                + ResearchCycleService.formatBoundBody(conjecture) + "`";
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "Miner-validated configurations recorded before this investigation.",
                "Held-out verifier windows, separate from the miner's confirmed configurations.",
                "Keep the registered lhs, rhs term(s), coefficients, and intercept fixed for the confirmatory verifier call.",
                "Any changed coefficient, intercept, or window selection used to locate a binding regime is a sensitivity analysis and cannot replace the registered test.",
                "");
        return new PreRegistration(
                "Confirm the exact registered conjecture " + relation + ".",
                "At least one held-out verify_conjecture call using the exact registered relation is recorded before any sensitivity analysis.",
                "The registered conjecture is falsified if an exact-relation verify_conjecture call returns REFUTED on its executed held-out windows.",
                encodedSpec(conjecture), plan);
    }

    public static boolean isLocked(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && registration.mappingNotes().startsWith(PREFIX);
    }

    /** True only when selected evidence includes the registered relation exactly. */
    public static boolean hasExactEvidence(PreRegistration registration, List<ToolInvocation> trace) {
        Specification spec = parse(registration);
        if (spec == null || trace == null) return false;
        for (ToolInvocation invocation : trace) {
            if (invocation == null || !"verify_conjecture".equals(invocation.functionName())) continue;
            try {
                JsonNode args = JSON.readTree(invocation.argsJson());
                if (args != null && matches(spec, args)) return true;
            } catch (Exception ignored) {
                // Malformed arguments cannot satisfy a locked contract.
            }
        }
        return false;
    }

    /** Compact directive for an on-target corrective tool pass. */
    public static String exactTestDirective(PreRegistration registration) {
        Specification spec = parse(registration);
        return spec == null ? "" : "verify_conjecture lhs=\"" + spec.lhs + "\", rhs=\"" + spec.rhs
                + "\", k=" + spec.k + (spec.rhs2 == null ? "" : ", rhs2=\"" + spec.rhs2
                + "\", k2=" + spec.k2) + ", c=" + spec.c;
    }

    private static boolean matches(Specification spec, JsonNode args) {
        if (!spec.lhs.equals(text(args, "lhs")) || !spec.rhs.equals(text(args, "rhs"))
                || !same(spec.k, number(args, "k")) || !same(spec.c, number(args, "c"))) return false;
        if (spec.rhs2 == null) return args.path("rhs2").isMissingNode() || args.path("rhs2").isNull();
        return spec.rhs2.equals(text(args, "rhs2")) && same(spec.k2, number(args, "k2"));
    }

    private static boolean same(double expected, Double actual) {
        return actual != null && Double.isFinite(actual)
                && Math.abs(expected - actual) <= 1e-9 * Math.max(1.0, Math.abs(expected));
    }

    private static String text(JsonNode args, String key) {
        JsonNode node = args.path(key);
        return node.isTextual() ? node.textValue() : null;
    }

    private static Double number(JsonNode args, String key) {
        JsonNode node = args.path(key);
        return node.isNumber() ? node.doubleValue() : null;
    }

    private static Specification parse(PreRegistration registration) {
        if (!isLocked(registration)) return null;
        String[] parts = registration.mappingNotes().substring(PREFIX.length()).split(";", -1);
        String lhs = null, rhs = null, rhs2 = null;
        Double k = null, k2 = null, c = null;
        for (String part : parts) {
            int equals = part.indexOf('=');
            if (equals < 1) continue;
            String key = part.substring(0, equals);
            String value = part.substring(equals + 1);
            try {
                switch (key) {
                    case "lhs" -> lhs = value;
                    case "rhs" -> rhs = value;
                    case "rhs2" -> rhs2 = value.isBlank() ? null : value;
                    case "k" -> k = Double.valueOf(value);
                    case "k2" -> k2 = Double.valueOf(value);
                    case "c" -> c = Double.valueOf(value);
                    default -> { }
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return lhs == null || rhs == null || k == null || c == null || (rhs2 != null && k2 == null)
                ? null : new Specification(lhs, rhs, k, rhs2, k2, c);
    }

    private static String encodedSpec(Conjecture c) {
        return PREFIX + "lhs=" + c.lhs() + ";rhs=" + c.rhs() + ";k=" + c.k()
                + ";rhs2=" + (c.rhs2() == null ? "" : c.rhs2())
                + ";k2=" + c.k2() + ";c=" + c.c();
    }

    private record Specification(String lhs, String rhs, double k, String rhs2, Double k2, double c) {}
}
