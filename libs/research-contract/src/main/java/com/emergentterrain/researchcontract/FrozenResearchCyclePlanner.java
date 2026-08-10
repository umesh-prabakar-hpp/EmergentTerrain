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

    /**
     * Replays the legacy author-election and read-only queue-observation stages before a generic
     * work submission is constructed. It never reads or consumes a live queue.
     */
    public static FrozenResearchQueueObservation observeConjectureQueue(ResearchDraftSelectionInput input,
                                                                          ResearchConjectureQueueSnapshot snapshot) {
        if (ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY.equals(input.executionIntent().kind())) {
            throw new IllegalArgumentException("A parameterized study must not observe a conjecture queue");
        }
        Random random = new Random(input.selectionSeed());
        ResearchDraftCandidate author = StandardResearchDraftSelector.selectCandidate(input, random);
        ResearchConjectureQueueSelection selection = StandardResearchConjectureQueueSelector.select(snapshot, random);
        return new FrozenResearchQueueObservation(author, selection);
    }

    /**
     * Fails closed if the terminal branch stored in a work request is not the one reproduced from
     * its immutable queue observation. This validates ordering, priority, and random-draw cadence
     * without accessing or consuming a live queue.
     */
    public static void verifyConjectureQueueObservation(ResearchDraftSelectionInput input,
                                                         ResearchConjectureQueueSelection recordedSelection) {
        ResearchConjectureQueueSnapshot snapshot = input.conjectureQueueSnapshot();
        if (snapshot == null) return;
        ResearchConjectureQueueSelection expected = observeConjectureQueue(input, snapshot).conjectureSelection();
        if (!expected.equals(recordedSelection)) {
            throw new IllegalArgumentException("Frozen conjecture queue observation does not match its recorded selection");
        }
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

    /** Immutable read-only queue observation paired with the author elected by the same frozen seed. */
    public record FrozenResearchQueueObservation(ResearchDraftCandidate electedAuthor,
                                                 ResearchConjectureQueueSelection conjectureSelection) { }
}
