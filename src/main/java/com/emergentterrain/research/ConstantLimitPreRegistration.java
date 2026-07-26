package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.conjecture.ScalarOpenness;
import com.emergentterrain.research.publication.ComputationalStudyPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Locked, executable scale-ladder measurement for a registered constant-limit scalar. */
public final class ConstantLimitPreRegistration {
    private static final String PREFIX = "locked-constant-limit:v1;";
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConstantLimitPreRegistration() {}

    /**
     * Registers only an intrinsic CONSTANT measurement.  The miner's affine row is intentionally
     * not copied: it is a selection scaffold, whereas the scale ladder is the confirmatory object.
     */
    public static PreRegistration from(Conjecture conjecture, ScalarOpenness.MeasurementSpec spec) {
        if (conjecture == null || spec == null
                || spec.frame() != ScalarOpenness.MeasurementFrame.CONSTANT) return null;
        String target = spec.target() == null ? "" : Double.toString(spec.target());
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "Run verify_constant with scale_ladder=true for " + conjecture.lhs()
                        + " before interpreting the miner's affine scaffold.",
                "The tool-selected geometric ladder supplies the held-out scale comparison.",
                "Keep the registered scalar, target (when present), and scale_ladder=true unchanged.",
                "A missing or failed ladder is inconclusive; exploratory affine bounds cannot replace it.",
                "mvn test -Dtest=ConstantLimitPreRegistrationTest");
        return new PreRegistration(
                "Measure whether " + conjecture.lhs() + " approaches a constant across a locked geometric scale ladder.",
                "A successful verify_constant scale ladder records the observed trajectory"
                        + (target.isBlank() ? "." : " relative to target=" + target + "."),
                "The constant-limit study is incomplete when the registered scale ladder is absent or fails.",
                PREFIX + "lhs=" + conjecture.lhs() + ";target=" + target + ";scale_ladder=true;",
                plan);
    }

    public static boolean isLocked(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && registration.mappingNotes().startsWith(PREFIX);
    }

    /** True only for a completed registered ladder, never a disabled, partial, or failed attempt. */
    public static boolean hasExactEvidence(PreRegistration registration, List<ToolInvocation> trace) {
        Specification spec = parse(registration);
        if (spec == null || trace == null) return false;
        for (ToolInvocation invocation : trace) {
            if (invocation == null || !"verify_constant".equals(invocation.functionName())) continue;
            try {
                JsonNode args = JSON.readTree(invocation.argsJson());
                if (!spec.lhs.equals(args.path("lhs").asText())
                        || !args.path("scale_ladder").asBoolean(false)) continue;
                if (spec.target == null ? !args.path("target").isMissingNode()
                        && !args.path("target").isNull()
                        : Math.abs(args.path("target").asDouble(Double.NaN) - spec.target) > 1e-9) continue;
                String result = invocation.resultJson();
                if (result != null && result.startsWith("verify_constant scale-ladder:")
                        && !result.contains(" PARTIAL:") && !result.contains(" unavailable:")
                        && !result.contains(" eval failed") && !result.contains("no rung fits")) return true;
            } catch (Exception ignored) {
                // Malformed arguments never satisfy the locked instrument contract.
            }
        }
        return false;
    }

    public static String exactTestDirective(PreRegistration registration) {
        Specification spec = parse(registration);
        return spec == null ? "" : "verify_constant lhs=\"" + spec.lhs + "\""
                + (spec.target == null ? "" : ", target=" + spec.target) + ", scale_ladder=true";
    }

    private static Specification parse(PreRegistration registration) {
        if (!isLocked(registration)) return null;
        String lhs = null;
        Double target = null;
        for (String part : registration.mappingNotes().substring(PREFIX.length()).split(";")) {
            int equals = part.indexOf('=');
            if (equals < 1) continue;
            String key = part.substring(0, equals);
            String value = part.substring(equals + 1);
            try {
                if ("lhs".equals(key)) lhs = value;
                if ("target".equals(key) && !value.isBlank()) target = Double.valueOf(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return lhs == null || lhs.isBlank() ? null : new Specification(lhs, target);
    }

    private record Specification(String lhs, Double target) {}
}
