package com.emergentterrain.researchcontract;

/** Pure precedence rule for frozen draft branches; it performs no queue consumption or execution. */
public final class ResearchDraftBranchResolver {
    private ResearchDraftBranchResolver() { }

    public static ResearchDraftBranchDecision resolve(ResearchDraftExecutionIntent parameterizedStudy,
                                                       ResearchConjectureQueueSelection conjectureSelection,
                                                       ResearchDraftExecutionIntent followUp) {
        if (parameterizedStudy != null) return new ResearchDraftBranchDecision(parameterizedStudy, null, 0);
        if (conjectureSelection != null && conjectureSelection.specification() != null) {
            return new ResearchDraftBranchDecision(null, conjectureSelection,
                    conjectureSelection.preTopicRandomDoubleDraws());
        }
        if (followUp != null) return new ResearchDraftBranchDecision(followUp, null,
                conjectureSelection == null ? 0 : conjectureSelection.preTopicRandomDoubleDraws());
        return new ResearchDraftBranchDecision(ResearchDraftExecutionIntent.standardCatalog(), null,
                conjectureSelection == null ? 0 : conjectureSelection.preTopicRandomDoubleDraws());
    }

    public record ResearchDraftBranchDecision(ResearchDraftExecutionIntent executionIntent,
                                              ResearchConjectureQueueSelection conjectureSelection,
                                              int preTopicRandomDoubleDraws) { }
}
