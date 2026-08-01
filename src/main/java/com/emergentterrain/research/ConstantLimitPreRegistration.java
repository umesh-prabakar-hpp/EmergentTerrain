package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.conjecture.KnownLimitRegistry;
import com.emergentterrain.research.conjecture.ScalarOpenness;
import com.emergentterrain.research.publication.ComputationalStudyPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

/** Locked, executable scale-ladder measurement for a registered constant-limit scalar. */
public final class ConstantLimitPreRegistration {
    private static final String PREFIX_V1 = "locked-constant-limit:v1;";
    private static final String PREFIX_V2 = "locked-constant-limit:v2;";
    private static final String PREFIX_V3 = "locked-constant-limit:v3;";
    private static final String PREFIX_V4 = "locked-constant-limit:v4;";
    private static final String PREFIX_V5 = "locked-constant-limit:v5;";
    private static final int PRIMARY_WINDOW_SIZE = 5_000;
    private static final int PRIMARY_NUM_WINDOWS = 5;
    private static final int CONFIRMATION_WINDOW_SIZE = 10_000;
    private static final int CONFIRMATION_NUM_WINDOWS = 5;
    private static final long PRIMARY_LADDER_START = 100_000L;
    private static final long CONFIRMATION_LADDER_START = 500_000L;
    /* Kept well clear of each other and of the geometric ladder: these are real holdouts, not aliases. */
    private static final long LOCATION_HOLDOUT_START = 1_500_000_000L;
    private static final int LOCATION_HOLDOUT_WINDOW_SIZE = PRIMARY_WINDOW_SIZE;
    private static final int LOCATION_HOLDOUT_NUM_WINDOWS = PRIMARY_NUM_WINDOWS;
    private static final long WIDTH_HOLDOUT_START = 1_500_100_000L;
    private static final int WIDTH_HOLDOUT_WINDOW_SIZE = CONFIRMATION_WINDOW_SIZE;
    private static final int WIDTH_HOLDOUT_NUM_WINDOWS = CONFIRMATION_NUM_WINDOWS;
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
                "Run the deadline-aware primary ladder, then the locked location and width holdouts.",
                "Keep the registered scalar, target (when present), requested allocations, and non-overlapping starts unchanged.",
                "A missing, failed, or execution-collapsed ladder or holdout is inconclusive; exploratory affine bounds cannot replace it.",
                "mvn test -Dtest=ConstantLimitPreRegistrationTest");
        return new PreRegistration(
                knownLimit == null
                        ? "Measure whether " + conjecture.lhs() + " approaches a constant with a locked scale ladder and independent location/width holdouts."
                        : "Calibrate " + conjecture.lhs() + " against its established instrument-defined constant with a locked scale ladder and independent location/width holdouts.",
                "A successful primary scale ladder plus independent location and width verify_constant holdouts record the observed trajectory"
                        + (target.isBlank() ? "." : " relative to target=" + target + "."),
                "The constant-limit study is incomplete when the registered ladder or either independent holdout is absent, fails, or executes a collapsed placement.",
                PREFIX_V5 + "lhs=" + conjecture.lhs() + ";target=" + target + ";"
                        + "known_limit=" + (knownLimit == null ? "" : knownLimit.id()) + ";scale_ladder=true;"
                        + "primary_window_size=" + PRIMARY_WINDOW_SIZE + ";primary_num_windows=" + PRIMARY_NUM_WINDOWS + ";"
                        + "primary_ladder_start=" + PRIMARY_LADDER_START + ";"
                        + "deadline_aware=true;"
                        + "location_holdout_window_size=" + LOCATION_HOLDOUT_WINDOW_SIZE + ";location_holdout_num_windows=" + LOCATION_HOLDOUT_NUM_WINDOWS + ";"
                        + "location_holdout_start=" + LOCATION_HOLDOUT_START + ";"
                        + "width_holdout_window_size=" + WIDTH_HOLDOUT_WINDOW_SIZE + ";width_holdout_num_windows=" + WIDTH_HOLDOUT_NUM_WINDOWS + ";"
                        + "width_holdout_start=" + WIDTH_HOLDOUT_START + ";"
                        + "literature_required=" + (!target.isBlank()) + ";"
                        + "literature_subject=" + literatureSubjectFor(conjecture.lhs(), knownLimit) + ";"
                        + "target_framing=" + (knownLimit == null ? "conjectural_or_open_calibration" : "established_calibration") + ";",
                plan);
    }

    public static boolean isLocked(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && (registration.mappingNotes().startsWith(PREFIX_V1)
                || registration.mappingNotes().startsWith(PREFIX_V2)
                || registration.mappingNotes().startsWith(PREFIX_V3)
                || registration.mappingNotes().startsWith(PREFIX_V4)
                || registration.mappingNotes().startsWith(PREFIX_V5));
    }

    /** A named numerical target needs a readable source before it can be scored as a positioned result. */
    public static boolean requiresSourceGrounding(PreRegistration registration) {
        Specification spec = parse(registration);
        return spec != null && (spec.literatureRequired || isLegacyEstablishedCalibration(registration));
    }

    public static String literatureSubject(PreRegistration registration) {
        Specification spec = parse(registration);
        if (spec == null) return "";
        return spec.literatureSubject.isBlank()
                ? literatureSubjectFor(spec.lhs, KnownLimitRegistry.find(spec.lhs).orElse(null))
                : spec.literatureSubject;
    }

    /**
     * Stable lexical identity tokens for the measured scalar. These are deliberately narrower
     * than the broad literature subject: a generic overlap such as "prime powers" must not
     * ground an established target whose scalar is {@code omega_Omega_gap}.
     */
    public static List<String> targetIdentityTokens(PreRegistration registration) {
        Specification spec = parse(registration);
        if (spec == null || spec.lhs == null || spec.lhs.isBlank()) return List.of();
        return java.util.Arrays.stream(spec.lhs.toLowerCase(Locale.ROOT).split("_"))
                .map(String::strip)
                .filter(token -> token.length() >= 4)
                .distinct()
                .toList();
    }

    public static boolean isEstablishedCalibration(PreRegistration registration) {
        Specification spec = parse(registration);
        return spec != null && ("established_calibration".equals(spec.targetFraming)
                || isLegacyEstablishedCalibration(registration));
    }

    private static boolean isLegacyEstablishedCalibration(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && registration.mappingNotes().matches("(?s).*known_limit=[^;]+;.*");
    }

    /** True only for a completed registered ladder, never a disabled, partial, or failed attempt. */
    public static boolean hasExactEvidence(PreRegistration registration, List<ToolInvocation> trace) {
        Specification spec = parse(registration);
        if (spec == null || trace == null) return false;
        boolean primary = false;
        boolean confirmation = !spec.requiresConfirmation;
        boolean locationHoldout = !spec.requiresIndependentHoldouts;
        boolean widthHoldout = !spec.requiresIndependentHoldouts;
        for (ToolInvocation invocation : trace) {
            if (invocation == null || !"verify_constant".equals(invocation.functionName())) continue;
            try {
                JsonNode args = JSON.readTree(invocation.argsJson());
                if (!matchesScalarAndTarget(spec, args)) continue;
                if (spec.requiresIndependentHoldouts) {
                    if (args.path("scale_ladder").asBoolean(false) && completedLadder(invocation.resultJson())
                            && matchesAllocation(args, spec.primaryWindowSize, spec.primaryNumWindows, spec.primaryLadderStart)
                            && recordsLadderStart(invocation.resultJson(), spec.primaryLadderStart)
                            && args.path("deadline_aware").asBoolean(false)) primary = true;
                    if (!args.path("scale_ladder").asBoolean(false) && completedDirect(invocation.resultJson())) {
                        if (matchesDirectAllocation(args, spec.locationHoldoutWindowSize, spec.locationHoldoutNumWindows,
                                spec.locationHoldoutStart)) locationHoldout = true;
                        if (matchesDirectAllocation(args, spec.widthHoldoutWindowSize, spec.widthHoldoutNumWindows,
                                spec.widthHoldoutStart)) widthHoldout = true;
                    }
                    continue;
                }
                if (!args.path("scale_ladder").asBoolean(false) || !completedLadder(invocation.resultJson())) continue;
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
        return primary && confirmation && locationHoldout && widthHoldout;
    }

    public static String exactTestDirective(PreRegistration registration) {
        Specification spec = parse(registration);
        if (spec == null) return "";
        String base = "verify_constant lhs=\"" + spec.lhs + "\""
                + (spec.target == null ? "" : ", target=" + spec.target) + ", scale_ladder=true";
        if (spec.requiresIndependentHoldouts) {
            String direct = "verify_constant lhs=\"" + spec.lhs + "\""
                    + (spec.target == null ? "" : ", target=" + spec.target);
            return base + ", window_size=" + spec.primaryWindowSize + ", num_windows=" + spec.primaryNumWindows
                    + ladderStartDirective(spec.primaryLadderStart) + ", deadline_aware=true; then "
                    + direct + ", window_start=" + spec.locationHoldoutStart + ", window_size="
                    + spec.locationHoldoutWindowSize + ", num_windows=" + spec.locationHoldoutNumWindows
                    + "; then " + direct + ", window_start=" + spec.widthHoldoutStart + ", window_size="
                    + spec.widthHoldoutWindowSize + ", num_windows=" + spec.widthHoldoutNumWindows;
        }
        return !spec.requiresConfirmation ? base : base + ", window_size=" + spec.primaryWindowSize
                + ", num_windows=" + spec.primaryNumWindows + ladderStartDirective(spec.primaryLadderStart)
                + "; then " + base + ", window_size="
                + spec.confirmationWindowSize + ", num_windows=" + spec.confirmationNumWindows
                + ladderStartDirective(spec.confirmationLadderStart);
    }

    private static boolean matchesScalarAndTarget(Specification spec, JsonNode args) {
        if (!spec.lhs.equals(args.path("lhs").asText())) return false;
        return spec.target == null ? args.path("target").isMissingNode() || args.path("target").isNull()
                : Math.abs(args.path("target").asDouble(Double.NaN) - spec.target) <= 1e-9;
    }

    private static boolean matchesDirectAllocation(JsonNode args, Integer windowSize, Integer numWindows, Long windowStart) {
        return windowSize != null && numWindows != null && windowStart != null
                && args.path("window_size").asInt(-1) == windowSize
                && args.path("num_windows").asInt(-1) == numWindows
                && args.path("window_start").asLong(Long.MIN_VALUE) == windowStart;
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

    private static boolean completedDirect(String result) {
        return result != null && result.startsWith("verify_constant:")
                && !result.contains(" evaluation failed") && !result.contains("No windows evaluated");
    }

    private static Specification parse(PreRegistration registration) {
        if (!isLocked(registration)) return null;
        boolean v5 = registration.mappingNotes().startsWith(PREFIX_V5);
        boolean requiresConfirmation = registration.mappingNotes().startsWith(PREFIX_V2)
                || registration.mappingNotes().startsWith(PREFIX_V3)
                || registration.mappingNotes().startsWith(PREFIX_V4);
        String prefix = v5 ? PREFIX_V5 : registration.mappingNotes().startsWith(PREFIX_V4) ? PREFIX_V4
                : registration.mappingNotes().startsWith(PREFIX_V3) ? PREFIX_V3
                : requiresConfirmation ? PREFIX_V2 : PREFIX_V1;
        String lhs = null;
        Double target = null;
        Integer primaryWindowSize = null, primaryNumWindows = null;
        Integer confirmationWindowSize = null, confirmationNumWindows = null;
        Long primaryLadderStart = null, confirmationLadderStart = null;
        Integer locationHoldoutWindowSize = null, locationHoldoutNumWindows = null;
        Integer widthHoldoutWindowSize = null, widthHoldoutNumWindows = null;
        Long locationHoldoutStart = null, widthHoldoutStart = null;
        boolean deadlineAware = false, literatureRequired = false;
        String literatureSubject = "", targetFraming = "";
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
                if ("location_holdout_window_size".equals(key)) locationHoldoutWindowSize = Integer.valueOf(value);
                if ("location_holdout_num_windows".equals(key)) locationHoldoutNumWindows = Integer.valueOf(value);
                if ("location_holdout_start".equals(key)) locationHoldoutStart = Long.valueOf(value);
                if ("width_holdout_window_size".equals(key)) widthHoldoutWindowSize = Integer.valueOf(value);
                if ("width_holdout_num_windows".equals(key)) widthHoldoutNumWindows = Integer.valueOf(value);
                if ("width_holdout_start".equals(key)) widthHoldoutStart = Long.valueOf(value);
                if ("deadline_aware".equals(key)) deadlineAware = Boolean.parseBoolean(value);
                if ("literature_required".equals(key)) literatureRequired = Boolean.parseBoolean(value);
                if ("literature_subject".equals(key)) literatureSubject = value;
                if ("target_framing".equals(key)) targetFraming = value;
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
        if (v5 && (primaryWindowSize == null || primaryNumWindows == null
                || primaryWindowSize <= 0 || primaryNumWindows <= 0
                || !deadlineAware || primaryLadderStart == null || primaryLadderStart <= 0
                || locationHoldoutWindowSize == null || locationHoldoutNumWindows == null || locationHoldoutStart == null
                || widthHoldoutWindowSize == null || widthHoldoutNumWindows == null || widthHoldoutStart == null
                || locationHoldoutWindowSize <= 0 || locationHoldoutNumWindows <= 0
                || widthHoldoutWindowSize <= 0 || widthHoldoutNumWindows <= 0
                || locationHoldoutStart <= 0 || widthHoldoutStart <= 0
                || locationHoldoutStart.equals(widthHoldoutStart)
                || (locationHoldoutWindowSize.equals(widthHoldoutWindowSize)
                && locationHoldoutNumWindows.equals(widthHoldoutNumWindows))
                || (literatureRequired && literatureSubject.isBlank()))) return null;
        return new Specification(lhs, target, requiresConfirmation, primaryWindowSize, primaryNumWindows,
                confirmationWindowSize, confirmationNumWindows, primaryLadderStart, confirmationLadderStart,
                v5, locationHoldoutWindowSize, locationHoldoutNumWindows, locationHoldoutStart,
                widthHoldoutWindowSize, widthHoldoutNumWindows, widthHoldoutStart,
                literatureRequired, literatureSubject, targetFraming);
    }

    private record Specification(String lhs, Double target, boolean requiresConfirmation,
                                 Integer primaryWindowSize, Integer primaryNumWindows,
                                 Integer confirmationWindowSize, Integer confirmationNumWindows,
                                 Long primaryLadderStart, Long confirmationLadderStart,
                                 boolean requiresIndependentHoldouts,
                                 Integer locationHoldoutWindowSize, Integer locationHoldoutNumWindows, Long locationHoldoutStart,
                                 Integer widthHoldoutWindowSize, Integer widthHoldoutNumWindows, Long widthHoldoutStart,
                                 boolean literatureRequired, String literatureSubject, String targetFraming) {}

    private static String literatureSubjectFor(String lhs, KnownLimitRegistry.KnownLimit knownLimit) {
        if (knownLimit != null && "mean_divisor_excess".equals(lhs)) {
            return "Dirichlet divisor summatory function";
        }
        if (knownLimit != null && "omega_Omega_gap".equals(lhs)) {
            return "prime powers omega Omega summatory";
        }
        if (knownLimit != null) return knownLimit.id();
        if ("twin_vs_prime_sq".equals(lhs)) return "Hardy-Littlewood twin prime constant";
        return lhs;
    }
}
