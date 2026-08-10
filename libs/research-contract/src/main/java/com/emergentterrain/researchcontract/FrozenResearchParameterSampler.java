package com.emergentterrain.researchcontract;

import java.util.Random;

/** Replays only the legacy no-formulation fallback draw after frozen standard-catalog selection. */
public final class FrozenResearchParameterSampler {
    private FrozenResearchParameterSampler() { }

    /**
     * Returns empty when the game did not freeze a sampling profile. This never substitutes for
     * a formulation decision and never authorizes computation.
     */
    public static java.util.Optional<ResearchParameterSample> sampleFallback(ResearchDraftSelectionInput input) {
        if (!ResearchDraftExecutionIntent.STANDARD_CATALOG.equals(input.executionIntent().kind())) {
            return java.util.Optional.empty();
        }
        Random random = new Random(input.selectionSeed());
        ResearchDraftCandidate author = StandardResearchDraftSelector.selectCandidate(input, random);
        for (int draw = 0; draw < input.preTopicRandomDoubleDraws(); draw++) random.nextDouble();
        ResearchDraftSelectionInput topicInput = withoutPreTopicDraws(input);
        ResearchDraftSelection selection = StandardResearchDraftSelector.selectForCandidate(topicInput, author, random);
        ResearchParameterSamplingProfile profile = author.parameterSamplingProfile();
        if (profile == null) return java.util.Optional.empty();
        int parameterOne = bounded((int) Math.round(profile.parameterOneCenter()
                + random.nextGaussian() * profile.parameterOneRadius()), 10, 99);
        double parameterTwo = Math.max(1.0, Math.min(9.0, profile.parameterTwoCenter()
                + random.nextGaussian() * profile.parameterTwoRadius()));
        return java.util.Optional.of(new ResearchParameterSample(ResearchParameterSample.LEGACY_FALLBACK_SAMPLING_V1,
                author.subjectId(), selection.domain(), selection.topic(), parameterOne, parameterTwo));
    }

    private static ResearchDraftSelectionInput withoutPreTopicDraws(ResearchDraftSelectionInput input) {
        return new ResearchDraftSelectionInput(input.selectorVersion(), input.selectionSeed(),
                input.topicSelectionMode(), input.candidates(), input.executionIntent(), input.topicHistory(),
                input.topicReselectionPolicy(), input.conjectureQueueSnapshot(), 0);
    }

    private static int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
