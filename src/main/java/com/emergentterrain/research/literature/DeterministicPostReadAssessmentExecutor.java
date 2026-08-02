package com.emergentterrain.research.literature;

import com.emergentterrain.llm.ToolInvocation;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adds one bounded assessment for an issued key that has already been successfully read. */
public final class DeterministicPostReadAssessmentExecutor {
    private static final Pattern ISSUED_KEY = Pattern.compile("\\[(HF[0-9A-F]+)]");
    private static final Pattern READ = Pattern.compile(
            "(?s)^read_reference\\s+(HF[0-9A-F]+)(?:\\s+—|\\s+\\[).*Top passages matching.*");
    private static final Pattern ACCEPTED = Pattern.compile(
            "^reference_relevance_assessment\\s+(HF[0-9A-F]+)\\s+— ACCEPTED RELEVANT:.*");

    private final Phase34SqliteLiterature literature;
    private final ObjectMapper mapper;

    public DeterministicPostReadAssessmentExecutor(Phase34SqliteLiterature literature, ObjectMapper mapper) {
        this.literature = literature;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    public Outcome execute(List<ToolInvocation> trace) {
        if (literature == null || !literature.available()) return Outcome.skipped("adapter_unavailable");
        List<ToolInvocation> safeTrace = trace == null ? List.of() : trace;
        LinkedHashSet<String> issued = new LinkedHashSet<>();
        LinkedHashSet<String> assessed = new LinkedHashSet<>();
        for (ToolInvocation call : safeTrace) {
            if (call == null) continue;
            if ("search_literature".equals(call.functionName())) {
                Matcher key = ISSUED_KEY.matcher(value(call.resultJson()));
                while (key.find()) issued.add(key.group(1));
            } else if ("assess_reference_relevance".equals(call.functionName())) {
                Matcher accepted = ACCEPTED.matcher(value(call.resultJson()));
                if (accepted.matches()) assessed.add(accepted.group(1));
            }
        }
        for (ToolInvocation call : safeTrace) {
            if (call == null || !"read_reference".equals(call.functionName())) continue;
            Matcher read = READ.matcher(value(call.resultJson()));
            if (!read.matches()) continue;
            String key = read.group(1);
            if (!issued.contains(key) || assessed.contains(key)) continue;
            String excerpt = excerpt(call.resultJson());
            if (excerpt.isBlank()) return Outcome.skipped("read_has_no_assessable_excerpt");
            String result = literature.assessRelevance(key, excerpt, "RELEVANT");
            ToolInvocation invocation = new ToolInvocation("assess_reference_relevance",
                    arguments(key, excerpt), result, nextRound(safeTrace));
            boolean acceptedResult = result.startsWith("reference_relevance_assessment " + key
                    + " — ACCEPTED RELEVANT:");
            return new Outcome(true, acceptedResult, key, "assessed_issued_read", invocation);
        }
        return Outcome.skipped("no_unassessed_issued_read");
    }

    private String arguments(String key, String excerpt) {
        try {
            return mapper.writeValueAsString(Map.of("key", key, "supporting_excerpt", excerpt,
                    "verdict", "RELEVANT", "rationale",
                    "Deterministic assessment of the exact issued and read passage."));
        } catch (Exception ignored) {
            return "{\"key\":\"" + key + "\",\"supporting_excerpt\":\""
                    + excerpt.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"verdict\":\"RELEVANT\",\"rationale\":\"deterministic assessment\"}";
        }
    }

    private static String excerpt(String read) {
        int marker = read.indexOf("\n---\n");
        if (marker < 0) return "";
        String passage = read.substring(marker + 5).replaceAll("\\s+", " ").strip();
        return passage.length() <= 320 ? passage : passage.substring(0, 320).strip();
    }

    private static int nextRound(List<ToolInvocation> trace) {
        return trace.stream().filter(call -> call != null).mapToInt(ToolInvocation::round).max().orElse(-1) + 1;
    }

    private static String value(String text) { return text == null ? "" : text; }

    public record Outcome(boolean attempted, boolean accepted, String key, String reason,
                          ToolInvocation invocation) {
        private static Outcome skipped(String reason) { return new Outcome(false, false, "", reason, null); }
    }
}
