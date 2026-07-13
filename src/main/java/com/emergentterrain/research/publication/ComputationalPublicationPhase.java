package com.emergentterrain.research.publication;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.PreRegistration;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 34 — publication-quality classification for non-physical,
 * computational research.  It never upgrades a workflow status and never
 * claims peer-review acceptance; it makes the missing conditions visible in
 * both the paper and its artifact bundle.
 */
public final class ComputationalPublicationPhase {

    private ComputationalPublicationPhase() {}

    public static PublicationAssessment assess(PreRegistration preRegistration,
                                                List<ToolInvocation> trace,
                                                String relatedWork) {
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
        boolean searched = false;
        boolean readPrimary = false;
        for (ToolInvocation call : trace) {
            if (call == null || call.functionName() == null) continue;
            searched |= "search_literature".equals(call.functionName());
            readPrimary |= "read_reference".equals(call.functionName());
        }
        return searched && readPrimary;
    }
}
