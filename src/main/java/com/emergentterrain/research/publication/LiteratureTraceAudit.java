package com.emergentterrain.research.publication;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.ResearchPaper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces a compact, machine-readable audit of the literature evidence lifecycle in a selected
 * paper trace. The raw trace remains the source evidence; this report indexes its decisions so a
 * reviewer can see whether a cited source was safely issued, read, and actually cited.
 */
final class LiteratureTraceAudit {

    private static final Pattern KEY = Pattern.compile("\\[([A-Za-z0-9]+)]");
    private static final Pattern READ = Pattern.compile(
            "(?s)^read_reference\\s+([A-Za-z0-9]+)(?:\\s+—|\\s+\\[).*Top passages matching.*");

    private LiteratureTraceAudit() {}

    static Report from(ResearchPaper paper, List<ToolInvocation> trace,
                       PublicationAssessment assessment, ObjectMapper mapper) {
        List<Search> searches = new ArrayList<>();
        List<ReferenceRead> reads = new ArrayList<>();
        Set<String> issuedKeys = new LinkedHashSet<>();
        Set<String> successfullyReadKeys = new LinkedHashSet<>();
        List<ToolInvocation> safeTrace = trace == null ? List.of() : trace;

        for (ToolInvocation call : safeTrace) {
            if (call == null || call.functionName() == null) continue;
            if ("search_literature".equals(call.functionName())) {
                Argument argument = argument(call.argsJson(), "claim", mapper);
                String result = nullToEmpty(call.resultJson());
                SearchOutcome outcome = searchOutcome(result);
                List<String> keys = outcome == SearchOutcome.EVIDENCE ? keys(result) : List.of();
                issuedKeys.addAll(keys);
                searches.add(new Search(call.callId(), call.round(), argument.value(), argument.parseStatus(),
                        outcome.name(), keys, summary(result)));
            } else if ("read_reference".equals(call.functionName())) {
                Argument requestedKey = argument(call.argsJson(), "key", mapper);
                Argument query = argument(call.argsJson(), "query", mapper);
                String result = nullToEmpty(call.resultJson());
                Matcher match = READ.matcher(result);
                String returnedKey = match.matches() ? match.group(1) : "";
                boolean successful = !returnedKey.isBlank() && returnedKey.equals(requestedKey.value())
                        && issuedKeys.contains(returnedKey);
                if (successful) successfullyReadKeys.add(returnedKey);
                reads.add(new ReferenceRead(call.callId(), call.round(), requestedKey.value(),
                        requestedKey.parseStatus(), query.value(), query.parseStatus(), returnedKey,
                        successful ? "READ" : readOutcome(result, requestedKey.value(), issuedKeys),
                        successful, summary(result)));
            }
        }

        List<String> citedKeys = keys(nullToEmpty(paper == null ? null : paper.relatedWork()));
        Set<String> citedReadKeys = new LinkedHashSet<>(citedKeys);
        citedReadKeys.retainAll(successfullyReadKeys);
        Set<String> unreadIssuedKeys = new LinkedHashSet<>(issuedKeys);
        unreadIssuedKeys.removeAll(successfullyReadKeys);
        Set<String> readButUncitedKeys = new LinkedHashSet<>(successfullyReadKeys);
        readButUncitedKeys.removeAll(citedKeys);

        Gate gate = new Gate(List.copyOf(issuedKeys), List.copyOf(successfullyReadKeys), citedKeys,
                List.copyOf(citedReadKeys), List.copyOf(unreadIssuedKeys), List.copyOf(readButUncitedKeys),
                !citedReadKeys.isEmpty(), paper == null ? "" : nullToEmpty(paper.status()),
                paper == null || paper.publicationLabel() == null ? "" : paper.publicationLabel().name(),
                assessment == null ? List.of() : assessment.missingRequirements());
        return new Report(1, List.copyOf(searches), List.copyOf(reads), gate);
    }

    private static SearchOutcome searchOutcome(String result) {
        if (result.contains("EVIDENCE")) return SearchOutcome.EVIDENCE;
        String lower = result.toLowerCase(Locale.ROOT);
        if (lower.contains("timeout")) return SearchOutcome.TIMEOUT;
        if (lower.contains("not available") || lower.contains("unavailable")) return SearchOutcome.UNAVAILABLE;
        if (lower.contains("no evidence") || lower.contains("no theorem-index matches")
                || lower.contains("no sufficiently specific")) return SearchOutcome.NO_EVIDENCE;
        return SearchOutcome.ERROR;
    }

    private static String readOutcome(String result, String requestedKey, Set<String> issuedKeys) {
        String lower = result.toLowerCase(Locale.ROOT);
        if (!requestedKey.isBlank() && !issuedKeys.contains(requestedKey)) return "UNISSUED_KEY";
        if (lower.contains("unknown phase 34 key")) return "UNISSUED_KEY";
        if (lower.contains("no matching full-text passage")) return "NO_PASSAGE";
        if (lower.contains("not available") || lower.contains("unavailable")) return "UNAVAILABLE";
        return "ERROR";
    }

    private static Argument argument(String rawArgs, String field, ObjectMapper mapper) {
        if (rawArgs == null || rawArgs.isBlank()) return new Argument("", "MISSING");
        try {
            JsonNode node = mapper.readTree(rawArgs);
            if (node == null || !node.isObject()) return new Argument("", "MALFORMED");
            JsonNode value = node.path(field);
            return value.isTextual() && !value.asText().isBlank()
                    ? new Argument(value.asText(), "PRESENT") : new Argument("", "MISSING");
        } catch (Exception ignored) {
            return new Argument("", "MALFORMED");
        }
    }

    private static List<String> keys(String value) {
        Set<String> distinct = new LinkedHashSet<>();
        Matcher matcher = KEY.matcher(value == null ? "" : value);
        while (matcher.find()) distinct.add(matcher.group(1));
        return List.copyOf(distinct);
    }

    private static String summary(String result) {
        String normalized = nullToEmpty(result).replaceAll("\\s+", " ").strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 499) + "…";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private enum SearchOutcome { EVIDENCE, NO_EVIDENCE, TIMEOUT, UNAVAILABLE, ERROR }

    record Report(int schemaVersion, List<Search> searches, List<ReferenceRead> referenceReads, Gate gate) {}
    record Search(String callId, int round, String claim, String claimParseStatus, String outcome,
                  List<String> issuedKeys, String resultSummary) {}
    record ReferenceRead(String callId, int round, String requestedKey, String requestedKeyParseStatus,
                         String query, String queryParseStatus, String returnedKey, String outcome,
                         boolean issuedKeyReadSuccessfully, String resultSummary) {}
    record Gate(List<String> relevanceSafeIssuedKeys, List<String> successfullyReadKeys,
                List<String> relatedWorkCitedKeys, List<String> citedReadKeys,
                List<String> issuedButUnreadKeys, List<String> readButUncitedKeys,
                boolean sourceGroundedRelatedWork, String workflowStatus, String publicationLabel,
                List<String> missingPublicationRequirements) {}
    private record Argument(String value, String parseStatus) {}
}
