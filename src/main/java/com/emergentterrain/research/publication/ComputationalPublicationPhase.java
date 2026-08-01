package com.emergentterrain.research.publication;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.ConstantLimitPreRegistration;
import com.emergentterrain.research.PreRegistration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 34 — publication-quality classification for non-physical,
 * computational research.  It never upgrades a workflow status and never
 * claims peer-review acceptance; it makes the missing conditions visible in
 * both the paper and its artifact bundle.
 */
public final class ComputationalPublicationPhase {

    private static final Pattern REFERENCE_READ = Pattern.compile(
            "(?s)^read_reference\\s+([A-Za-z0-9]+)(?:\\s+—|\\s+\\[).*Top passages matching.*");
    private static final Pattern ACCEPTED_RELEVANT_REFERENCE = Pattern.compile(
            "^reference_relevance_assessment\\s+([A-Za-z0-9]+)\\s+— ACCEPTED RELEVANT:.*");

    private ComputationalPublicationPhase() {}

    public static PublicationAssessment assess(PreRegistration preRegistration,
                                                List<ToolInvocation> trace,
                                                String relatedWork,
                                                String workflowStatus) {
        List<String> missing = new ArrayList<>();
        if (preRegistration == null) {
            missing.add("locked pre-registration");
        }
        if (trace == null || trace.isEmpty()) {
            missing.add("executed computational trace");
        }

        ComputationalStudyPlan plan = preRegistration == null
                ? null : preRegistration.computationalStudyPlan();
        if (plan == null || !plan.hasSelectionSeparation()) {
            missing.add("separate discovery and confirmation scopes plus a fixed selection rule");
        }
        if (plan == null || !plan.hasSensitivityPlan()) {
            missing.add("predeclared sensitivity analysis");
        }
        if (plan == null || !plan.hasReproductionCommand()) {
            missing.add("declared reproduction command");
        }
        if (!hasRequiredLiteraturePositioning(preRegistration, relatedWork, trace)) {
            missing.add("source-grounded related-work positioning");
        }
        if (!"PUBLISHED".equals(workflowStatus)) {
            missing.add("clean PUBLISHED workflow status");
        }

        PublicationLabel label;
        if (preRegistration == null || trace == null || trace.isEmpty()) {
            label = PublicationLabel.INTERNAL_RESEARCH_NOTE;
        } else if (missing.isEmpty()) {
            label = PublicationLabel.ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE;
        } else {
            label = PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE;
        }
        return new PublicationAssessment(label, List.copyOf(missing));
    }

    /** True only when a searched, successfully read source is explicitly cited in Related Work. */
    public static boolean hasLiteraturePositioning(String relatedWork, List<ToolInvocation> trace) {
        if (relatedWork == null || relatedWork.isBlank() || trace == null) {
            return false;
        }
        return successfullyReadSearchKeys(trace).stream().anyMatch(key -> citesKey(relatedWork, key));
    }

    /**
     * Applies the stricter target check only where a registration names an established numerical
     * target. Ordinary computational notes retain the issued → read → cited requirement; a target
     * calibration additionally needs a successful RELEVANT assessment and a returned passage that
     * names the registered scalar, preventing a polysemous title match from becoming publication
     * grounding.
     */
    public static boolean hasRequiredLiteraturePositioning(PreRegistration registration,
                                                            String relatedWork,
                                                            List<ToolInvocation> trace) {
        if (!hasLiteraturePositioning(relatedWork, trace)) return false;
        if (!ConstantLimitPreRegistration.requiresSourceGrounding(registration)) return true;
        List<String> identityTokens = ConstantLimitPreRegistration.targetIdentityTokens(registration);
        if (identityTokens.isEmpty()) return false;
        for (String key : successfullyReadSearchKeys(trace)) {
            if (!citesKey(relatedWork, key) || !hasAcceptedRelevantAssessment(trace, key)) continue;
            if (readPassageMentionsAnyIdentityToken(trace, key, identityTokens)) return true;
        }
        return false;
    }

    /**
     * Ordered, de-duplicated keys that were both issued by {@code search_literature} and returned
     * successfully by {@code read_reference}. These are the only keys a final narration may use to
     * satisfy source-grounded Related Work.
     */
    public static List<String> successfullyReadSearchKeys(List<ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) return List.of();
        LinkedHashSet<String> searchedKeys = new LinkedHashSet<>();
        LinkedHashSet<String> readKeys = new LinkedHashSet<>();
        for (ToolInvocation call : trace) {
            if (call == null || call.functionName() == null) continue;
            String result = call.resultJson();
            if (result == null || result.isBlank()) continue;
            if ("search_literature".equals(call.functionName())) {
                searchedKeys.addAll(referenceKeys(result));
            } else if ("read_reference".equals(call.functionName())) {
                Matcher read = REFERENCE_READ.matcher(result);
                if (read.matches()) readKeys.add(read.group(1));
            }
        }
        readKeys.retainAll(searchedKeys);
        return List.copyOf(readKeys);
    }

    /**
     * True when the trace acquired a usable searched-and-read source, regardless
     * of whether the manuscript rendered its citation. This is deliberately
     * separate from {@link #hasLiteraturePositioning(String, List)} so the
     * publication-state resolver can identify P33-style citation omissions.
     */
    public static boolean hasReadLiteratureEvidence(List<ToolInvocation> trace) {
        return !successfullyReadSearchKeys(trace).isEmpty();
    }

    private static List<String> referenceKeys(String result) {
        List<String> keys = new ArrayList<>();
        Matcher keysInResult = Pattern.compile("\\[([A-Za-z0-9]+)]").matcher(result);
        while (keysInResult.find()) {
            keys.add(keysInResult.group(1));
        }
        return keys;
    }

    private static boolean citesKey(String relatedWork, String key) {
        return relatedWork.contains("[" + key + "]");
    }

    private static boolean hasAcceptedRelevantAssessment(List<ToolInvocation> trace, String key) {
        for (ToolInvocation call : trace) {
            if (call == null || !"assess_reference_relevance".equals(call.functionName())) continue;
            Matcher assessment = ACCEPTED_RELEVANT_REFERENCE.matcher(nullToEmpty(call.resultJson()));
            if (assessment.matches() && key.equals(assessment.group(1))) return true;
        }
        return false;
    }

    private static boolean readPassageMentionsAnyIdentityToken(List<ToolInvocation> trace, String key,
                                                                List<String> identityTokens) {
        for (ToolInvocation call : trace) {
            if (call == null || !"read_reference".equals(call.functionName())) continue;
            Matcher read = REFERENCE_READ.matcher(nullToEmpty(call.resultJson()));
            if (!read.matches() || !key.equals(read.group(1))) continue;
            String result = call.resultJson();
            int passageStart = result.indexOf("\n---\n");
            String passage = (passageStart >= 0 ? result.substring(passageStart + 5) : result)
                    .toLowerCase(java.util.Locale.ROOT);
            if (identityTokens.stream().anyMatch(passage::contains)) return true;
        }
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
