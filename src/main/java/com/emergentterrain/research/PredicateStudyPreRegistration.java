package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.publication.ComputationalStudyPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Locked, machine-checkable plan for a dynamic predicate baseline study.
 *
 * <p>The plan is derived from the directive before its first deterministic census runs. It fixes
 * the predicate, metric, baseline, primary range, a disjoint location holdout, and a second
 * width. The professor may add exploratory scans, but those cannot replace the three registered
 * calls that establish the finite comparison's design.
 */
public final class PredicateStudyPreRegistration {
    private static final String PREFIX_V1 = "locked-predicate-study:v1;";
    private static final String PREFIX_V2 = "locked-predicate-study:v2;";
    private static final ObjectMapper JSON = new ObjectMapper();

    private PredicateStudyPreRegistration() {}

    /** Returns null only when the directive leaves fewer than four integers to plan over. */
    public static PreRegistration from(DynamicProblem directive) {
        if (directive == null || directive.predicate() == null || directive.predicate().isBlank()
                || directive.metric() == null || directive.metric().isBlank()
                || !Double.isFinite(directive.baseline()) || !directive.hasExplicitComparisonTolerance()) return null;
        int start = Math.max(1, directive.rangeStart());
        int end = Math.min(directive.rangeEnd(), start + DynamicComputeEngine.MAX_RANGE);
        int width = end - start;
        if (width < 4) return null;

        int narrowWidth = Math.max(1, width / 2);
        List<Scan> scans = List.of(
                new Scan(start, end),
                new Scan(end + width, end + 2 * width),
                new Scan(start, start + narrowWidth));
        String subject = directive.predicate() + " / " + directive.metric();
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "The directive's initial exact census " + scans.get(0).range() + ".",
                "The registered disjoint-location and second-width exact censuses "
                        + scans.get(1).range() + " and " + scans.get(2).range() + ".",
                "Compare all three registered windows against the unchanged baseline "
                        + directive.baseline() + " with the pre-registered relation "
                        + directive.expectedRelation() + toleranceClause(directive)
                        + "; do not select a window after seeing results.",
                "The disjoint window tests location and the narrower window tests width sensitivity; "
                        + "additional scans are exploratory and cannot replace these calls.",
                "");
        return new PreRegistration(
                "Compare the exact finite " + subject + " census " + directive.expectedRelation()
                        + " the pre-registered baseline " + directive.baseline() + " across three fixed windows.",
                "The registered windows may differ finitely, but a " + directive.expectedRelation()
                        + " result is assessed against that locked direction" + toleranceClause(directive)
                        + " only and does not establish "
                        + "persistence or an asymptotic density.",
                "The comparison design fails if any registered predicate scan is missing, uses a changed "
                        + "baseline, or the pre-registered finite values contradict the stated direction.",
                encoded(directive, scans), plan);
    }

    public static boolean isLocked(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && (registration.mappingNotes().startsWith(PREFIX_V1)
                || registration.mappingNotes().startsWith(PREFIX_V2));
    }

    /** True only when every registered predicate scan appears exactly in the selected tool trace. */
    public static boolean hasExactEvidence(PreRegistration registration, List<ToolInvocation> trace) {
        Specification spec = parse(registration);
        if (spec == null || trace == null) return false;
        boolean[] seen = new boolean[spec.scans.size()];
        for (ToolInvocation invocation : trace) {
            if (invocation == null || !"run_predicate_scan".equals(invocation.functionName())
                    || invocation.argsJson() == null || invocation.resultJson() == null
                    || !invocation.resultJson().startsWith("Predicate scan:")) continue;
            try {
                JsonNode args = JSON.readTree(invocation.argsJson());
                for (int i = 0; i < spec.scans.size(); i++) {
                    if (!seen[i] && matches(spec, spec.scans.get(i), args)) seen[i] = true;
                }
            } catch (Exception ignored) {
                // Malformed arguments cannot satisfy a locked contract.
            }
        }
        for (boolean executed : seen) if (!executed) return false;
        return true;
    }

    /** Compact directive for the professor's evidence-bearing tool turn. */
    public static String exactTestDirective(PreRegistration registration) {
        Specification spec = parse(registration);
        if (spec == null) return "";
        return "run_predicate_scan predicate=\"" + spec.predicate + "\", metric=\"" + spec.metric
                + "\", baseline=" + spec.baseline + ", expected_relation=" + spec.expectedRelation
                + (spec.comparisonTolerance == null ? "" : ", comparison_tolerance=" + spec.comparisonTolerance)
                + " on "
                + String.join(", ", spec.scans.stream().map(Scan::range).toList());
    }

    private static boolean matches(Specification spec, Scan scan, JsonNode args) {
        return spec.predicate.equals(text(args, "predicate")) && spec.metric.equals(text(args, "metric"))
                && same(spec.baseline, number(args, "baseline"))
                && (spec.comparisonTolerance == null
                || same(spec.comparisonTolerance, number(args, "comparison_tolerance")))
                && scan.start == integer(args, "range_start") && scan.end == integer(args, "range_end");
    }

    private static String text(JsonNode args, String key) {
        JsonNode node = args.path(key);
        return node.isTextual() ? node.textValue() : null;
    }

    private static Double number(JsonNode args, String key) {
        JsonNode node = args.path(key);
        return node.isNumber() ? node.doubleValue() : null;
    }

    private static long integer(JsonNode args, String key) {
        JsonNode node = args.path(key);
        return node.canConvertToLong() ? node.longValue() : Long.MIN_VALUE;
    }

    private static boolean same(double expected, Double actual) {
        return actual != null && Double.isFinite(actual)
                && Math.abs(expected - actual) <= 1e-9 * Math.max(1.0, Math.abs(expected));
    }

    private static String encoded(DynamicProblem directive, List<Scan> scans) {
        StringBuilder out = new StringBuilder(PREFIX_V2)
                .append("predicate=").append(directive.predicate()).append(';')
                .append("metric=").append(directive.metric()).append(';')
                .append("baseline=").append(directive.baseline()).append(';');
        out.append("expected_relation=").append(directive.expectedRelation()).append(';');
        if (directive.comparisonTolerance() != null) {
            out.append("comparison_tolerance=").append(directive.comparisonTolerance()).append(';');
        }
        for (Scan scan : scans) out.append("scan=").append(scan.start).append(',').append(scan.end).append(';');
        return out.toString();
    }

    private static Specification parse(PreRegistration registration) {
        if (!isLocked(registration)) return null;
        String predicate = null, metric = null;
        Double baseline = null;
        Double comparisonTolerance = null;
        DynamicProblem.ExpectedRelation expectedRelation = DynamicProblem.ExpectedRelation.APPROXIMATELY_EQUAL;
        List<Scan> scans = new ArrayList<>();
        String prefix = registration.mappingNotes().startsWith(PREFIX_V2) ? PREFIX_V2 : PREFIX_V1;
        for (String part : registration.mappingNotes().substring(prefix.length()).split(";", -1)) {
            int equals = part.indexOf('=');
            if (equals < 1) continue;
            String key = part.substring(0, equals);
            String value = part.substring(equals + 1);
            try {
                switch (key) {
                    case "predicate" -> predicate = value;
                    case "metric" -> metric = value;
                    case "baseline" -> baseline = Double.valueOf(value);
                    case "expected_relation" -> expectedRelation = DynamicProblem.ExpectedRelation.valueOf(value);
                    case "comparison_tolerance" -> comparisonTolerance = Double.valueOf(value);
                    case "scan" -> {
                        String[] ends = value.split(",", -1);
                        if (ends.length != 2) return null;
                        scans.add(new Scan(Integer.parseInt(ends[0]), Integer.parseInt(ends[1])));
                    }
                    default -> { }
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (predicate == null || predicate.isBlank() || metric == null || metric.isBlank()
                || baseline == null || !Double.isFinite(baseline) || scans.size() != 3
                || (expectedRelation == DynamicProblem.ExpectedRelation.APPROXIMATELY_EQUAL
                && (comparisonTolerance == null || !Double.isFinite(comparisonTolerance)
                || comparisonTolerance <= 0.0))
                || scans.stream().anyMatch(scan -> scan.end <= scan.start
                || scan.end - scan.start > DynamicComputeEngine.MAX_RANGE)) return null;
        return new Specification(predicate, metric, baseline, expectedRelation, comparisonTolerance, scans);
    }

    private record Scan(int start, int end) {
        String range() { return "[" + start + "," + end + ")"; }
    }

    private static String toleranceClause(DynamicProblem directive) {
        return directive.comparisonTolerance() == null ? ""
                : " within the locked absolute tolerance " + directive.comparisonTolerance();
    }

    private record Specification(String predicate, String metric, double baseline,
                                 DynamicProblem.ExpectedRelation expectedRelation,
                                 Double comparisonTolerance, List<Scan> scans) {}
}
