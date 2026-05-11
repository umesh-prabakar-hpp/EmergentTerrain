package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4b — tests for the reviewer-orthogonality picking logic in
 * {@link ResearchCycleService#pickMostOrthogonal}.
 *
 * The static helper is package-private and pure; we exercise it directly
 * rather than through the full ResearchCycleService.runCycle path.
 */
class ReviewerOrthogonalityTest {

    @Test
    void identicalTopicWeightsScoreZeroOrthogonalityAndReturnNull() {
        Researcher author     = researcher(weights(1, 0, 0, 0, 0));
        Researcher candidate1 = researcher(weights(1, 0, 0, 0, 0));
        Researcher candidate2 = researcher(weights(1, 0, 0, 0, 0));

        // All candidates are perfectly aligned with the author → no preference.
        Researcher picked = ResearchCycleService.pickMostOrthogonal(
                author, List.of(candidate1, candidate2));

        assertThat(picked).isNull();
    }

    @Test
    void mostOrthogonalCandidateIsChosen() {
        Researcher author    = researcher(weights(1, 0, 0, 0, 0));
        Researcher aligned   = researcher(weights(1, 0, 0, 0, 0));   // cos = 1, orth = 0
        Researcher partial   = researcher(weights(1, 1, 0, 0, 0));   // cos ≈ 0.707
        Researcher orthogonal = researcher(weights(0, 1, 0, 0, 0));  // cos = 0, orth = 1

        Researcher picked = ResearchCycleService.pickMostOrthogonal(
                author, List.of(aligned, partial, orthogonal));

        assertThat(picked).isSameAs(orthogonal);
    }

    @Test
    void picksFromSinglePartiallyOrthogonalCandidate() {
        Researcher author = researcher(weights(1, 0, 0, 0, 0));
        Researcher partial = researcher(weights(1, 1, 0, 0, 0));

        Researcher picked = ResearchCycleService.pickMostOrthogonal(
                author, List.of(partial));

        assertThat(picked).isSameAs(partial);
    }

    @Test
    void allZeroWeightVectorsReturnNull() {
        Researcher author    = researcher(weights(0, 0, 0, 0, 0));
        Researcher candidate = researcher(weights(0, 0, 0, 0, 0));

        Researcher picked = ResearchCycleService.pickMostOrthogonal(
                author, List.of(candidate));

        assertThat(picked).isNull();
    }

    @Test
    void nullGenomeCandidatesAreSkipped() {
        Researcher author = researcher(weights(1, 0, 0, 0, 0));
        Researcher viable = researcher(weights(0, 1, 0, 0, 0));
        Researcher nullGenome = new Researcher(
                UUID.randomUUID().toString(), new BotLifecycleState());

        Researcher picked = ResearchCycleService.pickMostOrthogonal(
                author, List.of(nullGenome, viable));

        assertThat(picked).isSameAs(viable);
    }

    // ---- helpers ----

    private static Researcher researcher(double[] topicWeights) {
        BotLifecycleState ls = new BotLifecycleState();
        BotGenome g = BotGenome.initialise(UUID.randomUUID(), 0);
        // Replace the auto-initialised weights with the test's chosen vector.
        double[] target = g.getTopicWeights();
        for (int i = 0; i < target.length && i < topicWeights.length; i++) {
            target[i] = topicWeights[i];
        }
        ls.setGenome(g);
        return new Researcher(UUID.randomUUID().toString(), ls);
    }

    private static double[] weights(double w0, double w1, double w2, double w3, double w4) {
        return new double[]{w0, w1, w2, w3, w4};
    }
}
