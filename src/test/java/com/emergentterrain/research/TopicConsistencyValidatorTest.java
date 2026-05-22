package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-05-21 review §1 rec #6 — unit tests for {@link
 * TopicConsistencyValidator}. The canonical signature is P-a328-886:
 * declared topic "Abundant Density" with Related Work prose claiming
 * "the specific focus on the Pendulum Breakdown Angle is new".
 */
class TopicConsistencyValidatorTest {

    private static final Set<String> TOPICS = Set.of(
            "Abundant Density",
            "Pendulum Breakdown Angle",
            "Projectile Drag Optimisation",
            "Collatz Stopping Time",
            "Hall-Petch Grain Size Strengthening",
            "Black-body Wien Peak");

    @Test
    void p886SignatureFlagsPendulumLeak() {
        // Paper topic: Abundant Density. Prose claims focus on a foreign topic.
        List<String> hits = TopicConsistencyValidator.validate(
                "Abundant Density",
                "There are no prior papers in this domain and topic, making this a novel "
                        + "contribution to the field. The established literature on abundant "
                        + "numbers provides a foundation for this research, but the specific "
                        + "focus on the Pendulum Breakdown Angle is new.",
                TOPICS);
        assertThat(hits).containsExactly("Pendulum Breakdown Angle");
    }

    @Test
    void declaredTopicInProsePasses() {
        // Mentioning the paper's own topic in its prose is expected and not flagged.
        List<String> hits = TopicConsistencyValidator.validate(
                "Abundant Density",
                "Abundant Density grows logarithmically in the range studied.",
                TOPICS);
        assertThat(hits).isEmpty();
    }

    @Test
    void mixOfOwnAndForeignTopicFlagsOnlyForeign() {
        List<String> hits = TopicConsistencyValidator.validate(
                "Abundant Density",
                "Abundant Density behaves like the Collatz Stopping Time distribution.",
                TOPICS);
        assertThat(hits).containsExactly("Collatz Stopping Time");
    }

    @Test
    void substringSingleWordDoesNotMatch() {
        // "pendulum" alone (a common physics noun) is not flagged. Only the
        // full topic phrase "Pendulum Breakdown Angle" triggers.
        List<String> hits = TopicConsistencyValidator.validate(
                "Abundant Density",
                "A pendulum oscillates with period T = 2π√(L/g) for small angles.",
                TOPICS);
        assertThat(hits).isEmpty();
    }

    @Test
    void caseInsensitiveMatchWorks() {
        // Match the full phrase regardless of case in the prose.
        List<String> hits = TopicConsistencyValidator.validate(
                "Abundant Density",
                "We reference the pendulum BREAKDOWN angle result from prior work.",
                TOPICS);
        assertThat(hits).containsExactly("Pendulum Breakdown Angle");
    }

    @Test
    void multipleForeignTopicsAllFlagged() {
        List<String> hits = TopicConsistencyValidator.validate(
                "Abundant Density",
                "Building on Projectile Drag Optimisation and Black-body Wien Peak, "
                        + "we examine the same approach here.",
                TOPICS);
        assertThat(hits)
                .hasSize(2)
                .contains("Projectile Drag Optimisation", "Black-body Wien Peak");
    }

    @Test
    void duplicateMentionsDeduplicated() {
        List<String> hits = TopicConsistencyValidator.validate(
                "Abundant Density",
                "Pendulum Breakdown Angle appears once. Pendulum Breakdown Angle again.",
                TOPICS);
        assertThat(hits).containsExactly("Pendulum Breakdown Angle");
    }

    @Test
    void declaredTopicCaseDifferenceStillExcluded() {
        // Normalisation is case-insensitive on the declared topic too.
        List<String> hits = TopicConsistencyValidator.validate(
                "abundant density",
                "Abundant Density grows logarithmically.",
                TOPICS);
        assertThat(hits).isEmpty();
    }

    @Test
    void nullInputsReturnEmpty() {
        assertThat(TopicConsistencyValidator.validate(null, "Pendulum Breakdown Angle", TOPICS))
                .isEmpty();
        assertThat(TopicConsistencyValidator.validate("Abundant Density", null, TOPICS))
                .isEmpty();
        assertThat(TopicConsistencyValidator.validate("Abundant Density",
                "Pendulum Breakdown Angle", null))
                .isEmpty();
        assertThat(TopicConsistencyValidator.validate("Abundant Density", "", TOPICS))
                .isEmpty();
    }

    @Test
    void emptyTopicListReturnsEmpty() {
        // Defensive: don't flag everything when the registry hasn't seeded yet.
        assertThat(TopicConsistencyValidator.validate(
                "Abundant Density",
                "Pendulum Breakdown Angle appears here.",
                Set.of()))
                .isEmpty();
    }

    @Test
    void hyphenatedTopicMatches() {
        // "Hall-Petch Grain Size Strengthening" — hyphen in the topic name
        // should still match as a whole-phrase token.
        List<String> hits = TopicConsistencyValidator.validate(
                "Abundant Density",
                "Comparable to the Hall-Petch Grain Size Strengthening trend.",
                TOPICS);
        assertThat(hits).containsExactly("Hall-Petch Grain Size Strengthening");
    }
}
