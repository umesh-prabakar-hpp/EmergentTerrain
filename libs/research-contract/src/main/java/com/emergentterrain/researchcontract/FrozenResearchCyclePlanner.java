package com.emergentterrain.researchcontract;

import java.util.Random;

/** Pure frozen-cycle planner. It never consumes a game queue or executes research. */
public final class FrozenResearchCyclePlanner {
    private FrozenResearchCyclePlanner() { }

    public static FrozenResearchCyclePlan plan(ResearchDraftSelectionInput catalogInput,
                                                ResearchDraftExecutionIntent parameterizedStudy,
                                                ResearchConjectureQueueSelection conjectureSelection,
                                                ResearchDraftExecutionIntent followUp) {
        Random random = new Random(catalogInput.selectionSeed());
        ResearchDraftCandidate author = StandardResearchDraftSelector.selectCandidate(catalogInput, random);
        var branch = ResearchDraftBranchResolver.resolve(parameterizedStudy, conjectureSelection, followUp);
        ResearchDraftSelection catalogSelection = branch.catalogTopicSelectionRequired()
                ? StandardResearchDraftSelector.selectForCandidate(withBranch(catalogInput, branch), author, random)
                : null;
        return new FrozenResearchCyclePlan(author, branch, catalogSelection);
    }

    private static ResearchDraftSelectionInput withBranch(ResearchDraftSelectionInput input,
                                                           ResearchDraftBranchResolver.ResearchDraftBranchDecision branch) {
        return new ResearchDraftSelectionInput(input.selectorVersion(), input.selectionSeed(),
                input.topicSelectionMode(), input.candidates(), branch.executionIntent(), input.topicHistory(),
                input.topicReselectionPolicy(), branch.preTopicRandomDoubleDraws());
    }

    public record FrozenResearchCyclePlan(ResearchDraftCandidate electedAuthor,
                                          ResearchDraftBranchResolver.ResearchDraftBranchDecision branch,
                                          ResearchDraftSelection catalogSelection) { }
}
