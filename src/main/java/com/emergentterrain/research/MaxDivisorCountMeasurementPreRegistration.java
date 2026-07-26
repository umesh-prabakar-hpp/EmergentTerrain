package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.publication.ComputationalStudyPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locked measurement-study protocol for {@code max_divisor_count} investigations.
 *
 * <p>The protocol deliberately permits a coefficient/intercept to be selected on one fixed fit
 * grid, but it forbids quietly re-fitting it on another scale.  A source-backed Wigert reference
 * is a separate workflow condition: a search result alone is not enough; the selected source must
 * be read and cited in related work before the study can be promoted.
 */
public final class MaxDivisorCountMeasurementPreRegistration {
    private static final String PREFIX = "locked-max-divisor-count-measurement:v1;";
    private static final String LHS = "max_divisor_count";
    private static final String RHS = "wigert_bound";
    private static final double MAX_RELATIVE_SLACK = 0.05;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern KEY = Pattern.compile("\\[([A-Za-z0-9]+)]");
    private static final Pattern READ = Pattern.compile(
            "(?s)^read_reference\\s+([A-Za-z0-9]+)(?:\\s+—|\\s+\\[).*Top passages matching.*");

    private static final Grid FIT = new Grid("fit", 1_000_000_000L, 5_000L, 20);
    private static final List<Grid> HOLDOUTS = List.of(
            new Grid("location_holdout", 1_500_000_000L, 5_000L, 20),
            new Grid("offset_holdout", 1_000_005_000L, 5_000L, 20),
            new Grid("width_holdout", 1_000_000_000L, 10_000L, 10));
    private static final int LADDER_WINDOW_SIZE = 5_000;
    private static final int LADDER_WINDOWS = 20;

    private MaxDivisorCountMeasurementPreRegistration() {}

    /** Returns this protocol only for the max-divisor-count conjecture family. */
    public static PreRegistration from(Conjecture conjecture) {
        if (conjecture == null || !LHS.equals(conjecture.lhs())) return null;
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "First run the registered Wigert scale ladder (window size " + LADDER_WINDOW_SIZE
                        + ", " + LADDER_WINDOWS + " windows per rung), then measure the " + LHS
                        + " versus " + RHS + " envelope on " + FIT.describe() + ".",
                "Hold the fit-selected coefficient and intercept fixed on "
                        + HOLDOUTS.stream().map(Grid::describe).reduce((a, b) -> a + "; " + b).orElse(""),
                "Select the first tight HELD fit (relative slack at most " + MAX_RELATIVE_SLACK
                        + ") and lock its k and c before any holdout is read.",
                "Record a same-grid changed-intercept refutation, the unchanged fixed-scale/window "
                        + "holdouts, and a source-backed Wigert reference (search, read, then cite). "
                        + "Additional grids are exploratory and cannot replace these checks.",
                "mvn test -Dtest=MaxDivisorCountMeasurementPreRegistrationTest");
        return new PreRegistration(
                "Estimate a finite-window affine envelope for " + LHS + " on one locked fit grid, "
                        + "then test that unchanged relation on locked location, offset, and width holdouts.",
                "A fit pair that holds tightly across every registered holdout supports only the recorded "
                        + "finite measurement envelope; a failed holdout is retained rather than repaired by refitting.",
                "The protocol fails if its scale ladder or fit/refutation/holdout calls are absent, if k or c changes after "
                        + "fitting, or if no searched, read, and cited Wigert source is recorded.",
                PREFIX + "lhs=" + LHS + ";rhs=" + RHS + ";fit=" + FIT.encode() + ";"
                        + HOLDOUTS.stream().map(grid -> "holdout=" + grid.encode() + ";")
                        .reduce("", String::concat),
                plan);
    }

    public static boolean isLocked(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && registration.mappingNotes().startsWith(PREFIX);
    }

    /** True only when the selected trace completes every fixed fit/refutation/holdout check. */
    public static boolean hasExactEvidence(PreRegistration registration, List<ToolInvocation> trace) {
        return assess(registration, trace).complete();
    }

    /** True only when a Wigert/divisor lookup produced a key that was read and cited in related work. */
    public static boolean hasSourceBackedWigertReference(List<ToolInvocation> trace, String relatedWork) {
        if (trace == null || relatedWork == null || relatedWork.isBlank()) return false;
        List<String> issued = new ArrayList<>();
        List<String> read = new ArrayList<>();
        for (ToolInvocation call : trace) {
            if (call == null || call.functionName() == null || call.resultJson() == null) continue;
            if ("search_literature".equals(call.functionName()) && isWigertDivisorSearch(call.argsJson())) {
                Matcher keys = KEY.matcher(call.resultJson());
                while (keys.find()) issued.add(keys.group(1));
            } else if ("read_reference".equals(call.functionName())) {
                Matcher readResult = READ.matcher(call.resultJson());
                if (readResult.matches()) read.add(readResult.group(1));
            }
        }
        return read.stream().anyMatch(key -> issued.contains(key) && relatedWork.contains("[" + key + "]"));
    }

    /** A compact directive for the initial prompt and corrective reruns. */
    public static String completionDirective(PreRegistration registration, List<ToolInvocation> trace) {
        Assessment assessment = assess(registration, trace);
        if (!assessment.applies()) return "";
        if (!assessment.growthLadderRecorded()) {
            return "First run verify_growth with lhs=\"" + LHS + "\", scale=\"wigert\", target=0.69315, "
                    + "window_start=100000, window_size=" + LADDER_WINDOW_SIZE + ", num_windows=" + LADDER_WINDOWS
                    + ", and scale_ladder=true. This fixed multi-scale measurement must precede fit selection.";
        }
        if (assessment.fit() == null) {
            return "First run verify_conjecture with lhs=\"" + LHS + "\", rhs=\"" + RHS
                    + "\" on the fixed fit grid " + FIT.directive() + ". Select one tight HELD k,c there; "
                    + "do not inspect a holdout before that selection. Also search_literature with the exact "
                    + "subject anchors [\"divisor\", \"wigert\"] and read_reference every returned evidence key.";
        }
        Relation fit = assessment.fit();
        List<String> missing = new ArrayList<>();
        if (!assessment.fitRefutationRecorded()) {
            missing.add("a REFUTED same-grid variant with the same lhs/rhs/k and changed c on " + FIT.directive());
        }
        for (Grid grid : assessment.missingHoldouts()) missing.add(grid.label + " " + grid.directive());
        if (missing.isEmpty()) return "The locked measurement calls are complete; retain a searched, read, and cited Wigert source before promotion.";
        return "Keep lhs=\"" + LHS + "\", rhs=\"" + RHS + "\", k=" + fit.k + ", c=" + fit.c
                + " fixed. Execute: " + String.join("; ", missing) + ". Every holdout must emit HELD with "
                + "relative slack <= " + MAX_RELATIVE_SLACK + "; do not refit on a holdout.";
    }

    /** Pure assessment shared by narration repair and publication workflow gating. */
    public static Assessment assess(PreRegistration registration, List<ToolInvocation> trace) {
        if (!isLocked(registration)) return Assessment.notApplicable();
        boolean growthLadderRecorded = hasGrowthLadder(trace);
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
                for (int j = refuted ? refutationIndex + 1 : calls.size(); j < calls.size(); j++) {
                    Call candidate = calls.get(j);
                    if (candidate.matches(holdout) && candidate.tightHeld() && candidate.sameRelation(fit)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) missing.add(holdout);
            }
            return new Assessment(true, growthLadderRecorded, new Relation(fit.k, fit.c), refuted, List.copyOf(missing));
        }
        return new Assessment(true, growthLadderRecorded, null, false, List.copyOf(HOLDOUTS));
    }

    private static boolean hasGrowthLadder(List<ToolInvocation> trace) {
        if (trace == null) return false;
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_growth".equals(call.functionName()) || call.argsJson() == null
                    || call.resultJson() == null || !call.resultJson().contains("verify_growth scale-ladder:")) continue;
            try {
                JsonNode args = JSON.readTree(call.argsJson());
                if (LHS.equals(text(args, "lhs")) && "wigert".equals(text(args, "scale"))
                        && args.path("scale_ladder").asBoolean(false)
                        && Integer.valueOf(LADDER_WINDOW_SIZE).equals(intValue(args, "window_size"))
                        && Integer.valueOf(LADDER_WINDOWS).equals(intValue(args, "num_windows"))) return true;
            } catch (Exception ignored) {
                // A malformed ladder call cannot satisfy the locked measurement study.
            }
        }
        return false;
    }

    private static boolean isWigertDivisorSearch(String argsJson) {
        if (argsJson == null) return false;
        try {
            JsonNode anchors = JSON.readTree(argsJson).path("subject_anchors");
            if (!anchors.isArray() || anchors.size() != 2) return false;
            boolean divisor = false;
            boolean wigert = false;
            for (JsonNode anchor : anchors) {
                String token = anchor.asText("").toLowerCase(Locale.ROOT);
                divisor |= "divisor".equals(token);
                wigert |= "wigert".equals(token);
            }
            return divisor && wigert;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static List<Call> calls(List<ToolInvocation> trace) {
        List<Call> out = new ArrayList<>();
        if (trace == null) return out;
        for (ToolInvocation invocation : trace) {
            if (invocation == null || !"verify_conjecture".equals(invocation.functionName())
                    || invocation.argsJson() == null || invocation.resultJson() == null) continue;
            try {
                JsonNode args = JSON.readTree(invocation.argsJson());
                if (!LHS.equals(text(args, "lhs")) || !RHS.equals(text(args, "rhs")) || args.path("rhs2").isTextual()) continue;
                Double k = number(args, "k"); Double c = number(args, "c");
                Long start = integer(args, "window_start"); Long size = integer(args, "window_size");
                Integer windows = intValue(args, "num_windows");
                if (k == null || c == null || start == null || size == null || windows == null
                        || !Double.isFinite(k) || !Double.isFinite(c)) continue;
                out.add(new Call(k, c, start, size, windows, invocation.resultJson()));
            } catch (Exception ignored) {
                // A malformed call cannot satisfy a locked protocol.
            }
        }
        return out;
    }

    private static String text(JsonNode args, String key) { JsonNode n = args.path(key); return n.isTextual() ? n.textValue() : null; }
    private static Double number(JsonNode args, String key) { JsonNode n = args.path(key); return n.isNumber() ? n.doubleValue() : null; }
    private static Long integer(JsonNode args, String key) { JsonNode n = args.path(key); return n.canConvertToLong() ? n.longValue() : null; }
    private static Integer intValue(JsonNode args, String key) { JsonNode n = args.path(key); return n.canConvertToInt() ? n.intValue() : null; }
    private static boolean same(double a, double b) { return Math.abs(a - b) <= 1e-9 * Math.max(1.0, Math.max(Math.abs(a), Math.abs(b))); }

    public record Assessment(boolean applies, boolean growthLadderRecorded, Relation fit,
                             boolean fitRefutationRecorded, List<Grid> missingHoldouts) {
        static Assessment notApplicable() { return new Assessment(false, false, null, false, List.of()); }
        public boolean complete() { return applies && growthLadderRecorded && fit != null
                && fitRefutationRecorded && missingHoldouts.isEmpty(); }
    }
    public record Relation(double k, double c) {}
    public record Grid(String label, long start, long size, int windows) {
        boolean matches(Call call) { return call.start == start && call.size == size && call.windows == windows; }
        String encode() { return label + "," + start + "," + size + "," + windows; }
        String describe() { return label + " grid [start=" + start + ", size=" + size + ", windows=" + windows + "]"; }
        String directive() { return "window_start=" + start + ", window_size=" + size + ", num_windows=" + windows; }
    }
    private record Call(double k, double c, long start, long size, int windows, String result) {
        boolean matches(Grid grid) { return grid.matches(this); }
        boolean sameRelation(Call other) { return same(k, other.k) && same(c, other.c); }
        boolean sameTermsAndGrid(Call other) { return same(k, other.k) && start == other.start && size == other.size && windows == other.windows; }
        boolean tightHeld() { return result.contains(" HELD across") && relativeSlack() != null && relativeSlack() <= MAX_RELATIVE_SLACK; }
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
            try { return end == 0 ? null : Double.valueOf(tail.substring(0, end)); }
            catch (NumberFormatException ignored) { return null; }
        }
    }
}
