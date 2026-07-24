package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Requires a baseline-comparison predicate study to show both width and offset sensitivity.
 * Exact finite censuses have no sampling uncertainty to rescue an arbitrary window choice.
 */
public final class PredicateBaselineSensitivityValidator {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PredicateBaselineSensitivityValidator() {}

    public static boolean mentionsBaseline(String prose) {
        return prose != null && prose.toLowerCase(Locale.ROOT).contains("baseline");
    }

    public static List<String> validate(String prose, List<ToolInvocation> trace) {
        if (!mentionsBaseline(prose) || trace == null) return List.of();
        List<Scan> scans = scans(trace);
        if (scans.isEmpty()) return List.of(); // Missing-Methods / baseline-drift cover no-scan failures.
        boolean variedWidth = scans.stream().mapToLong(Scan::width).distinct().count() >= 2;
        boolean sameBaseline = scans.stream().map(Scan::baseline).distinct().count() == 1;
        boolean offsetOverlap = false;
        for (int i = 0; i < scans.size(); i++) {
            for (int j = i + 1; j < scans.size(); j++) {
                Scan a = scans.get(i), b = scans.get(j);
                if (a.start != b.start && Math.max(a.start, b.start) < Math.min(a.end, b.end)) {
                    offsetOverlap = true;
                }
            }
        }
        if (scans.size() >= 3 && sameBaseline && variedWidth && offsetOverlap) return List.of();
        List<String> failures = new ArrayList<>();
        if (scans.size() < 3) failures.add("fewer than three exact predicate scans");
        if (!sameBaseline) failures.add("missing or changing baseline");
        if (!variedWidth) failures.add("no second window width");
        if (!offsetOverlap) failures.add("no overlapping offset window");
        return List.of("Baseline-comparison predicate study lacks required sensitivity evidence: "
                + String.join(", ", failures)
                + ". Retain at least three scans with two widths and one overlapping offset window.");
    }

    private static List<Scan> scans(List<ToolInvocation> trace) {
        List<Scan> out = new ArrayList<>();
        for (ToolInvocation call : trace) {
            if (call == null || !"run_predicate_scan".equals(call.functionName()) || call.argsJson() == null) continue;
            try {
                JsonNode args = JSON.readTree(call.argsJson());
                if (args.path("range_start").canConvertToLong() && args.path("range_end").canConvertToLong()
                        && args.path("baseline").isNumber()
                        && args.path("range_end").longValue() > args.path("range_start").longValue()) {
                    out.add(new Scan(args.path("range_start").longValue(), args.path("range_end").longValue(),
                            args.path("baseline").doubleValue()));
                }
            } catch (Exception ignored) {
                // A malformed call cannot contribute sensitivity evidence.
            }
        }
        return out;
    }

    private record Scan(long start, long end, double baseline) {
        long width() { return end - start; }
    }
}
