package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.conjecture.KnownLimitRegistry;
import com.emergentterrain.research.conjecture.ScalarOpenness;
import com.emergentterrain.research.publication.ComputationalStudyPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** Locked, executable scale-ladder measurement for a registered constant-limit scalar. */
public final class ConstantLimitPreRegistration {
    private static final String PREFIX_V1 = "locked-constant-limit:v1;";
    private static final String PREFIX_V2 = "locked-constant-limit:v2;";
    private static final String PREFIX_V3 = "locked-constant-limit:v3;";
    private static final String PREFIX_V4 = "locked-constant-limit:v4;";
    private static final int PRIMARY_WINDOW_SIZE = 5_000;
    private static final int PRIMARY_NUM_WINDOWS = 5;
    private static final int CONFIRMATION_WINDOW_SIZE = 10_000;
    private static final int CONFIRMATION_NUM_WINDOWS = 5;
    private static final long PRIMARY_LADDER_START = 100_000L;
    private static final long CONFIRMATION_LADDER_START = 500_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ConstantLimitPreRegistration() {}

    /**
     * Registers only an intrinsic CONSTANT measurement.  The miner's affine row is intentionally
     * not copied: it is a selection scaffold, whereas the scale ladder is the confirmatory object.
     */
    public static PreRegistration from(Conjecture conjecture, ScalarOpenness.MeasurementSpec spec) {
        if (conjecture == null || spec == null
                || spec.frame() != ScalarOpenness.MeasurementFrame.CONSTANT) return null;
        KnownLimitRegistry.KnownLimit knownLimit = KnownLimitRegistry.find(conjecture.lhs()).orElse(null);
        Double registeredTarget = knownLimit == null ? spec.target() : Double.valueOf(knownLimit.target());
        String target = registeredTarget == null ? "" : Double.toString(registeredTarget);
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "Run verify_constant with scale_ladder=true for " + conjecture.lhs()
                        + " before interpreting the miner's affine scaffold.",
                "Run the locked primary ladder and the independently placed confirmation ladder.",
                "Keep the registered scalar, target (when present), requested allocations, and distinct ladder starts unchanged.",
                "A missing, failed, or execution-collapsed confirmation ladder is inconclusive; exploratory affine bounds cannot replace it.",
                "mvn test -Dtest=ConstantLimitPreRegistrationTest");
        return new PreRegistration(
                knownLimit == null
                        ? "Measure whether " + conjecture.lhs() + " approaches a constant across two locked geometric scale ladders."
                        : "Calibrate " + conjecture.lhs() + " against its established instrument-defined constant across two locked geometric scale ladders.",
                "Successful primary and independently placed confirmation verify_constant ladders record the observed trajectory"
                        + (target.isBlank() ? "." : " relative to target=" + target + "."),
                "The constant-limit study is incomplete when either registered scale ladder is absent, fails, or executes the same placement.",
                PREFIX_V4 + "lhs=" + conjecture.lhs() + ";target=" + target + ";"
                        + "known_limit=" + (knownLimit == null ? "" : knownLimit.id()) + ";scale_ladder=true;"
                        + "primary_window_size=" + PRIMARY_WINDOW_SIZE + ";primary_num_windows=" + PRIMARY_NUM_WINDOWS + ";"
                        + "primary_ladder_start=" + PRIMARY_LADDER_START + ";"
                        + "confirmation_window_size=" + CONFIRMATION_WINDOW_SIZE + ";confirmation_num_windows=" + CONFIRMATION_NUM_WINDOWS + ";"
                        + "confirmation_ladder_start=" + CONFIRMATION_LADDER_START + ";",
                plan);
    }

    public static boolean isLocked(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && (registration.mappingNotes().startsWith(PREFIX_V1)
                || registration.mappingNotes().startsWith(PREFIX_V2)
                || registration.mappingNotes().startsWith(PREFIX_V3)
                || registration.mappingNotes().startsWith(PREFIX_V4));
    }

    /** True only for a completed registered ladder, never a disabled, partial, or failed attempt. */
    public static boolean hasExactEvidence(PreRegistration registration, List<ToolInvocation> trace) {
        Specification spec = parse(registration);
        if (spec == null || trace == null) return false;
        boolean primary = false;
        boolean confirmation = !spec.requiresConfirmation;
        for (ToolInvocation invocation : trace) {
            if (invocation == null || !"verify_constant".equals(invocation.functionName())) continue;
            try {
                JsonNode args = JSON.readTree(invocation.argsJson());
                if (!matchesBase(spec, args) || !completedLadder(invocation.resultJson())) continue;
                if (spec.primaryWindowSize == null || matchesAllocation(args,
                        spec.primaryWindowSize, spec.primaryNumWindows, spec.primaryLadderStart)
                        && recordsLadderStart(invocation.resultJson(), spec.primaryLadderStart)) primary = true;
                if (spec.requiresConfirmation && matchesAllocation(args,
                        spec.confirmationWindowSize, spec.confirmationNumWindows, spec.confirmationLadderStart)
                        && recordsLadderStart(invocation.resultJson(), spec.confirmationLadderStart)) confirmation = true;
            } catch (Exception ignored) {
                // Malformed arguments never satisfy the locked instrument contract.
            }
        }
        return primary && confirmation;
    }

    public static String exactTestDirective(PreRegistration registration) {
        Specification spec = parse(registration);
        if (spec == null) return "";
        String base = "verify_constant lhs=\"" + spec.lhs + "\""
                + (spec.target == null ? "" : ", target=" + spec.target) + ", scale_ladder=true";
        return !spec.requiresConfirmation ? base : base + ", window_size=" + spec.primaryWindowSize
                + ", num_windows=" + spec.primaryNumWindows + ladderStartDirective(spec.primaryLadderStart)
                + "; then " + base + ", window_size="
                + spec.confirmationWindowSize + ", num_windows=" + spec.confirmationNumWindows
                + ladderStartDirective(spec.confirmationLadderStart);
    }

    private static boolean matchesBase(Specification spec, JsonNode args) {
        if (!spec.lhs.equals(args.path("lhs").asText()) || !args.path("scale_ladder").asBoolean(false)) return false;
        return spec.target == null ? args.path("target").isMissingNode() || args.path("target").isNull()
                : Math.abs(args.path("target").asDouble(Double.NaN) - spec.target) <= 1e-9;
    }

    private static boolean matchesAllocation(JsonNode args, int windowSize, int numWindows, Long ladderStart) {
        return args.path("window_size").asInt(-1) == windowSize
                && args.path("num_windows").asInt(-1) == numWindows
                && (ladderStart == null || args.path("ladder_start").asLong(Long.MIN_VALUE) == ladderStart);
    }

    private static boolean recordsLadderStart(String result, Long ladderStart) {
        return ladderStart == null || (result != null && result.contains("ladder_start=" + ladderStart));
    }

    private static String ladderStartDirective(Long ladderStart) {
        return ladderStart == null ? "" : ", ladder_start=" + ladderStart;
    }

    private static boolean completedLadder(String result) {
        return result != null && result.startsWith("verify_constant scale-ladder:")
                && !result.contains(" PARTIAL:") && !result.contains(" unavailable:")
                && !result.contains(" eval failed") && !result.contains("no rung fits");
    }

    private static Specification parse(PreRegistration registration) {
        if (!isLocked(registration)) return null;
        boolean requiresConfirmation = registration.mappingNotes().startsWith(PREFIX_V2)
                || registration.mappingNotes().startsWith(PREFIX_V3)
                || registration.mappingNotes().startsWith(PREFIX_V4);
        String prefix = registration.mappingNotes().startsWith(PREFIX_V4) ? PREFIX_V4
                : registration.mappingNotes().startsWith(PREFIX_V3) ? PREFIX_V3
                : requiresConfirmation ? PREFIX_V2 : PREFIX_V1;
        String lhs = null;
        Double target = null;
        Integer primaryWindowSize = null, primaryNumWindows = null;
        Integer confirmationWindowSize = null, confirmationNumWindows = null;
        Long primaryLadderStart = null, confirmationLadderStart = null;
        for (String part : registration.mappingNotes().substring(prefix.length()).split(";")) {
            int equals = part.indexOf('=');
            if (equals < 1) continue;
            String key = part.substring(0, equals);
            String value = part.substring(equals + 1);
            try {
                if ("lhs".equals(key)) lhs = value;
                if ("target".equals(key) && !value.isBlank()) target = Double.valueOf(value);
                if ("primary_window_size".equals(key)) primaryWindowSize = Integer.valueOf(value);
                if ("primary_num_windows".equals(key)) primaryNumWindows = Integer.valueOf(value);
                if ("confirmation_window_size".equals(key)) confirmationWindowSize = Integer.valueOf(value);
                if ("confirmation_num_windows".equals(key)) confirmationNumWindows = Integer.valueOf(value);
                if ("primary_ladder_start".equals(key)) primaryLadderStart = Long.valueOf(value);
                if ("confirmation_ladder_start".equals(key)) confirmationLadderStart = Long.valueOf(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (lhs == null || lhs.isBlank()) return null;
        if (requiresConfirmation && (primaryWindowSize == null || primaryNumWindows == null
                || confirmationWindowSize == null || confirmationNumWindows == null
                || primaryWindowSize <= 0 || primaryNumWindows <= 0
                || confirmationWindowSize <= 0 || confirmationNumWindows <= 0
                || (primaryWindowSize.equals(confirmationWindowSize)
                && primaryNumWindows.equals(confirmationNumWindows))
                || (registration.mappingNotes().startsWith(PREFIX_V4)
                && (primaryLadderStart == null || confirmationLadderStart == null
                || primaryLadderStart <= 0 || confirmationLadderStart <= 0
                || primaryLadderStart.equals(confirmationLadderStart))))) return null;
        return new Specification(lhs, target, requiresConfirmation, primaryWindowSize, primaryNumWindows,
                confirmationWindowSize, confirmationNumWindows, primaryLadderStart, confirmationLadderStart);
    }

    private record Specification(String lhs, Double target, boolean requiresConfirmation,
                                 Integer primaryWindowSize, Integer primaryNumWindows,
                                 Integer confirmationWindowSize, Integer confirmationNumWindows,
                                 Long primaryLadderStart, Long confirmationLadderStart) {}
}
