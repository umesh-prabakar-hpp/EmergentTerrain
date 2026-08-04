package com.emergentterrain.research.literature;

import com.emergentterrain.llm.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes one bounded, deterministic follow-up read after a relevance-safe Phase 34/35 search.
 *
 * <p>The executor deliberately has no model judgment: it selects the first {@code HF...} key in
 * trace order from an {@code EVIDENCE} search whose structured claim is present, and uses that
 * exact claim as the focused read query. It runs only if the professor trace contains no prior
 * {@code read_reference} attempt, so it cannot turn a failed model read into an unbounded retry
 * loop or exceed the tool-loop's two-read budget. A successful read remains insufficient for a
 * publication-candidate label until the student revision cites the same key in Related Work.
 */
public final class DeterministicPostSearchReadExecutor {

    private static final Pattern HF_KEY = Pattern.compile("\\[(HF[0-9A-F]+)]");
    private static final Pattern SUCCESSFUL_READ = Pattern.compile(
            "(?s)^read_reference\\s+(HF[0-9A-F]+)(?:\\s+—|\\s+\\[).*Top passages matching.*");

    private final Phase34SqliteLiterature literature;
    private final ObjectMapper mapper;

    public DeterministicPostSearchReadExecutor(Phase34SqliteLiterature literature, ObjectMapper mapper) {
        this.literature = literature;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    /**
     * Attempts the one eligible read and returns the exact invocation to append to the raw trace.
     * The caller owns stable call-id normalization, preserving the existing trace convention.
     */
    public Outcome execute(List<ToolInvocation> trace) {
        if (literature == null || !literature.available()) {
            return Outcome.skipped("adapter_unavailable");
        }
        List<ToolInvocation> safeTrace = trace == null ? List.of() : trace;
        if (safeTrace.stream().anyMatch(call -> call != null
                && "read_reference".equals(call.functionName()))) {
            return Outcome.skipped("existing_read_attempt");
        }

        for (ToolInvocation call : safeTrace) {
            if (call == null || !"search_literature".equals(call.functionName())) continue;
            String result = nullToEmpty(call.resultJson());
            if (!result.contains("EVIDENCE")) continue;
            String claim = textArgument(call.argsJson(), "claim");
            if (claim.isBlank()) continue;
            Matcher key = HF_KEY.matcher(result);
            if (!key.find()) continue;

            String issuedKey = key.group(1);
            String readResult = literature.read(issuedKey, claim);
            String args = arguments(issuedKey, claim);
            ToolInvocation invocation = new ToolInvocation("read_reference", args, readResult,
                    nextRound(safeTrace));
            boolean successful = successfulRead(readResult, issuedKey);
            return new Outcome(true, successful, issuedKey,
                    successful ? "read_succeeded" : "read_returned_no_passage", invocation);
        }
        return Outcome.skipped("no_eligible_issued_key");
    }

    private String arguments(String key, String query) {
        try {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("key", key);
            args.put("query", query);
            return mapper.writeValueAsString(args);
        } catch (Exception e) {
            // Both values originate from validated trace/adapter data; retain a valid, auditable
            // fallback rather than dropping a completed read because serialization is unavailable.
            return "{\"key\":\"" + escape(key) + "\",\"query\":\"" + escape(query) + "\"}";
        }
    }

    private String textArgument(String raw, String field) {
        if (raw == null || raw.isBlank()) return "";
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode value = root == null ? null : root.path(field);
            return value != null && value.isTextual() ? value.asText().strip() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int nextRound(List<ToolInvocation> trace) {
        return trace.stream().filter(call -> call != null).mapToInt(ToolInvocation::round).max().orElse(-1) + 1;
    }

    private static boolean successfulRead(String result, String key) {
        Matcher match = SUCCESSFUL_READ.matcher(nullToEmpty(result));
        return match.matches() && key.equals(match.group(1));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    public record Outcome(boolean attempted, boolean successful, String key, String reason,
                          ToolInvocation invocation) {
        private static Outcome skipped(String reason) {
            return new Outcome(false, false, "", reason, null);
        }
    }
}
