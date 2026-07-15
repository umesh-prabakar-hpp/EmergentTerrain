package com.emergentterrain.research.publication;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.PreRegistration;

import java.util.ArrayList;
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
        if (!hasLiteraturePositioning(relatedWork, trace)) {
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

    private static boolean hasLiteraturePositioning(String relatedWork, List<ToolInvocation> trace) {
        if (relatedWork == null || relatedWork.isBlank() || trace == null) {
            return false;
        }
        List<String> searchedKeys = new ArrayList<>();
        List<String> readKeys = new ArrayList<>();
        for (ToolInvocation call : trace) {
            if (call == null || call.functionName() == null) continue;
            String result = call.resultJson();
            if (result == null || result.isBlank()) continue;
            if ("search_literature".equals(call.functionName())) {
                searchedKeys.addAll(referenceKeys(result));
            } else if ("read_reference".equals(call.functionName())) {
                Matcher read = REFERENCE_READ.matcher(result);
                if (read.matches()) {
                    readKeys.add(read.group(1));
                }
            }
        }
        return readKeys.stream().anyMatch(key -> searchedKeys.contains(key)
                && citesKey(relatedWork, key));
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
}
