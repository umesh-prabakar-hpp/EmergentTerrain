package com.emergentterrain.researchcontract;

/**
 * Pure branch rule after the legacy author-election stage; it performs no queue consumption or execution.
 * A follow-up changes the later parameter source, but does not skip catalog-topic selection.
 */
public final class ResearchDraftBranchResolver {
    private ResearchDraftBranchResolver() { }

    public static ResearchDraftBranchDecision resolve(ResearchDraftExecutionIntent parameterizedStudy,
                                                       ResearchConjectureQueueSelection conjectureSelection,
                                                       ResearchDraftExecutionIntent followUp) {
        if (parameterizedStudy != null) return new ResearchDraftBranchDecision(parameterizedStudy, null, 0, false);
        if (conjectureSelection != null && conjectureSelection.specification() != null) {
            return new ResearchDraftBranchDecision(null, conjectureSelection,
                    conjectureSelection.preTopicRandomDoubleDraws(), false);
        }
        if (followUp != null) return new ResearchDraftBranchDecision(followUp, null,
                conjectureSelection == null ? 0 : conjectureSelection.preTopicRandomDoubleDraws(), true);
        return new ResearchDraftBranchDecision(ResearchDraftExecutionIntent.standardCatalog(), null,
                conjectureSelection == null ? 0 : conjectureSelection.preTopicRandomDoubleDraws(), true);
    }

    public record ResearchDraftBranchDecision(ResearchDraftExecutionIntent executionIntent,
                                              ResearchConjectureQueueSelection conjectureSelection,
                                              int preTopicRandomDoubleDraws,
                                              boolean catalogTopicSelectionRequired) { }
}
