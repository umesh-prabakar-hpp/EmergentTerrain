package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.publication.ComputationalStudyPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Locked fit/holdout protocol for {@code max_prime_gap} investigations.
 *
 * <p>The coefficient and intercept are deliberately <em>not</em> fixed before computation: they
 * are estimated only on the registered fit grid.  Once a tight fit is selected, however, every
 * holdout, width, and offset check must use that exact pair.  This prevents a common loose
 * envelope, tuned separately on each scale, from being presented as a multi-scale result.
 */
public final class MaxPrimeGapMultiScalePreRegistration {
    private static final String PREFIX = "locked-max-prime-gap-multiscale:v1;";
    private static final String LHS = "max_prime_gap";
    private static final String RHS = "log_sq_window_start";
    /** Match the affine binding policy: a looser hold is exploratory, not a validated holdout. */
    private static final double MAX_RELATIVE_SLACK = 0.05;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Grid FIT = new Grid("fit", 100_000_000L, 10_000L, 10);
    private static final List<Grid> HOLDOUTS = List.of(
            new Grid("near_holdout", 150_000_000L, 10_000L, 10),
            new Grid("far_holdout", 900_000_000L, 10_000L, 10),
            new Grid("offset_holdout", 900_005_000L, 10_000L, 10),
            // Keep the doubled-width check inside ConjectureVerifier's 100,000-integer cap.
            new Grid("width_holdout", 900_000_000L, 20_000L, 5));

    private MaxPrimeGapMultiScalePreRegistration() {}

    /** Returns a protocol registration only for the targeted conjecture family. */
    public static PreRegistration from(Conjecture conjecture) {
        if (conjecture == null || !LHS.equals(conjecture.lhs())) return null;
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "Fit the one-term " + LHS + " versus " + RHS + " relation on " + FIT.describe() + ".",
                "Hold the fit-selected coefficient and intercept fixed on "
                        + HOLDOUTS.stream().map(Grid::describe).reduce((a, b) -> a + "; " + b).orElse(""),
                "Select one tight HELD fit (relative slack at most " + MAX_RELATIVE_SLACK
                        + ") and lock its k and c before any holdout result is read.",
                "Record a same-grid fit refutation at a changed intercept, then run the unchanged pair "
                        + "on both location holdouts, the overlapping offset, and the doubled-width grid. "
                        + "Extra grids are exploratory and cannot replace these calls.",
                "");
        return new PreRegistration(
                "Estimate a finite-window affine relation for " + LHS + " on the registered fit grid, "
                        + "then test that unchanged relation across pre-registered location, offset, and width holdouts.",
                "A selected fit pair that holds tightly on every registered holdout supports only the "
                        + "recorded finite multi-scale envelope; a failure on any holdout is reported as a failed holdout, "
                        + "not repaired by refitting.",
                "The protocol fails if the fit grid, same-grid refutation, any registered holdout, or its "
                        + "relative-slack evidence is absent; it also fails if k or c changes after fitting.",
                PREFIX + "lhs=" + LHS + ";rhs=" + RHS + ";fit=" + FIT.encode() + ";"
                        + HOLDOUTS.stream().map(grid -> "holdout=" + grid.encode() + ";")
                        .reduce("", String::concat),
                plan);
    }

    public static boolean isLocked(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && registration.mappingNotes().startsWith(PREFIX);
    }

    static Grid fitGrid() { return FIT; }

    static List<Grid> holdoutGrids() { return HOLDOUTS; }

    /** True only when the selected trace completes the registered fit/holdout protocol. */
    public static boolean hasExactEvidence(PreRegistration registration, List<ToolInvocation> trace) {
        return assess(registration, trace).complete();
    }

    /** A compact evidence directive, including the fitted pair when the trace has reached that stage. */
    public static String completionDirective(PreRegistration registration, List<ToolInvocation> trace) {
        Assessment assessment = assess(registration, trace);
        if (!assessment.applies()) return "";
        if (assessment.fit() == null) {
            return "First run verify_conjecture with lhs=\"" + LHS + "\", rhs=\"" + RHS
                    + "\" on the fixed fit grid " + FIT.directive() + ". Select one tight HELD k,c there; "
                    + "do not inspect a holdout before that selection.";
        }
        Relation fit = assessment.fit();
        List<String> missing = new ArrayList<>();
        if (!assessment.fitRefutationRecorded()) {
            missing.add("a REFUTED same-grid fit variant with the same lhs/rhs/k and a changed c on "
                    + FIT.directive());
        }
        for (Grid grid : assessment.missingHoldouts()) {
            missing.add(grid.label + " " + grid.directive());
        }
        if (missing.isEmpty()) return "The registered multi-scale protocol is complete.";
        return "Keep lhs=\"" + LHS + "\", rhs=\"" + RHS + "\", k=" + fit.k
                + ", c=" + fit.c + " fixed. Execute the remaining registered evidence: "
                + String.join("; ", missing) + ". Every holdout must emit HELD with relative slack <= "
                + MAX_RELATIVE_SLACK + "; do not refit on a holdout.";
    }

    /** Pure assessment used by both narration repair and the publication gate. */
    public static Assessment assess(PreRegistration registration, List<ToolInvocation> trace) {
        if (!isLocked(registration)) return Assessment.notApplicable();
        List<Call> calls = calls(trace);
        for (int i = 0; i < calls.size(); i++) {
            Call fit = calls.get(i);
            if (!fit.matches(FIT) || !fit.tightHeld()) continue;
            int refutationIndex = -1;
            for (int j = i + 1; j < calls.size(); j++) {
                Call candidate = calls.get(j);
                if (candidate.refuted() && candidate.sameTermsAndGrid(fit) && !same(candidate.c, fit.c)) {
                    refutationIndex = j;
                    break;
                }
            }
            boolean refuted = refutationIndex >= 0;
            List<Grid> missing = new ArrayList<>();
            for (Grid holdout : HOLDOUTS) {
                boolean matched = false;
                int firstHoldoutIndex = refuted ? refutationIndex + 1 : calls.size();
                for (int j = firstHoldoutIndex; j < calls.size(); j++) {
                    Call candidate = calls.get(j);
                    if (candidate.matches(holdout) && candidate.tightHeld()
                            && candidate.sameRelation(fit)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) missing.add(holdout);
            }
            if (refuted && missing.isEmpty()) {
                return new Assessment(true, new Relation(fit.k, fit.c), true, List.of());
            }
            // The first tight fit is the locked selection. A later fit cannot rescue failed holdouts by refitting.
            return new Assessment(true, new Relation(fit.k, fit.c), refuted, List.copyOf(missing));
        }
        return new Assessment(true, null, false, List.copyOf(HOLDOUTS));
    }

    private static List<Call> calls(List<ToolInvocation> trace) {
        List<Call> out = new ArrayList<>();
        if (trace == null) return out;
        for (ToolInvocation invocation : trace) {
            if (invocation == null || !"verify_conjecture".equals(invocation.functionName())
                    || invocation.argsJson() == null || invocation.resultJson() == null) continue;
            try {
                JsonNode args = JSON.readTree(invocation.argsJson());
                if (!LHS.equals(text(args, "lhs")) || !RHS.equals(text(args, "rhs"))
                        || args.path("rhs2").isTextual()) continue;
                Double k = number(args, "k");
                Double c = number(args, "c");
                Long start = integer(args, "window_start");
                Long size = integer(args, "window_size");
                Integer windows = intValue(args, "num_windows");
                if (k == null || c == null || start == null || size == null || windows == null
                        || !Double.isFinite(k) || !Double.isFinite(c)) continue;
                out.add(new Call(k, c, start, size, windows, invocation.resultJson()));
            } catch (Exception ignored) {
                // Malformed tool calls cannot satisfy a pre-registered contract.
            }
        }
        return out;
    }

    private static String text(JsonNode args, String key) {
        JsonNode node = args.path(key);
        return node.isTextual() ? node.textValue() : null;
    }

    private static Double number(JsonNode args, String key) {
        JsonNode node = args.path(key);
        return node.isNumber() ? node.doubleValue() : null;
    }

    private static Long integer(JsonNode args, String key) {
        JsonNode node = args.path(key);
        return node.canConvertToLong() ? node.longValue() : null;
    }

    private static Integer intValue(JsonNode args, String key) {
        JsonNode node = args.path(key);
        return node.canConvertToInt() ? node.intValue() : null;
    }

    private static boolean same(double left, double right) {
        return Math.abs(left - right) <= 1e-9 * Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
    }

    public record Assessment(boolean applies, Relation fit, boolean fitRefutationRecorded,
                             List<Grid> missingHoldouts) {
        static Assessment notApplicable() { return new Assessment(false, null, false, List.of()); }
        public boolean complete() { return applies && fit != null && fitRefutationRecorded && missingHoldouts.isEmpty(); }
    }

    public record Relation(double k, double c) {}

    public record Grid(String label, long start, long size, int windows) {
        boolean matches(Call call) {
            return call.start == start && call.size == size && call.windows == windows;
        }
        String encode() { return label + "," + start + "," + size + "," + windows; }
        String describe() { return label + " grid [start=" + start + ", size=" + size + ", windows=" + windows + "]"; }
        String directive() { return "window_start=" + start + ", window_size=" + size + ", num_windows=" + windows; }
    }

    private record Call(double k, double c, long start, long size, int windows, String result) {
        boolean matches(Grid grid) { return grid.matches(this); }
        boolean sameRelation(Call other) { return same(k, other.k) && same(c, other.c); }
        boolean sameTermsAndGrid(Call other) {
            return same(k, other.k) && start == other.start && size == other.size && windows == other.windows;
        }
        boolean tightHeld() { return result.contains(" HELD across") && relativeSlack() != null
                && relativeSlack() <= MAX_RELATIVE_SLACK; }
        boolean refuted() { return result.contains(" REFUTED across"); }
        Double relativeSlack() {
            int index = result.indexOf("relative slack:");
            if (index < 0) return null;
            String tail = result.substring(index + "relative slack:".length()).trim();
            int end = 0;
            boolean decimal = false;
            while (end < tail.length() && (Character.isDigit(tail.charAt(end))
                    || (tail.charAt(end) == '.' && !decimal))) {
                if (tail.charAt(end) == '.') decimal = true;
                end++;
            }
            if (end == 0) return null;
            try { return Double.valueOf(tail.substring(0, end)); }
            catch (NumberFormatException ignored) { return null; }
        }
    }
}
