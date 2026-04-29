package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MathPhysicsEngineTest {

    @Test
    void polygonHypothesisUsesActualRatioAndLimitOne() {
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute("MATHEMATICS", 2, 6, 1.0);

        double expectedNineGonRatio = Math.PI / (9 * Math.tan(Math.PI / 9));

        assertThat(result.tertiaryValue()).isCloseTo(expectedNineGonRatio, within(1e-12));
        assertThat(result.reviewValue()).isCloseTo(expectedNineGonRatio, within(1e-12));
        assertThat(result.theoryConsistent()).isTrue();
        assertThat(result.theoryCheck()).contains("asymptotic limit is 1");

        String hypothesis = MathPhysicsEngine.generateHypothesis("MATHEMATICS", 2, 6, 1.0);

        assertThat(hypothesis).contains("regular 9-gon");
        assertThat(hypothesis).contains("0.959051");
        assertThat(hypothesis).contains("approaches 1");
        assertThat(hypothesis).doesNotContain("approaches π/4");
    }

    @Test
    void polygonTheoryCheckDistinguishesSquareValueFromAsymptoticLimit() {
        MathPhysicsEngine.ResearchResult square =
                MathPhysicsEngine.compute("MATHEMATICS", 2, 1, 1.0);
        MathPhysicsEngine.ResearchResult nineGon =
                MathPhysicsEngine.compute("MATHEMATICS", 2, 6, 1.0);

        assertThat(square.tertiaryValue()).isCloseTo(Math.PI / 4, within(1e-12));
        assertThat(nineGon.tertiaryValue()).isGreaterThan(square.tertiaryValue());
        assertThat(nineGon.tertiaryValue()).isLessThan(1.0);
    }

    @Test
    void conclusionRefusesTheoryInconsistentResultsEvenWhenReviewPassed() {
        MathPhysicsEngine.ResearchResult badResult = new MathPhysicsEngine.ResearchResult(
                "bad result", 1.0, 1.0, 1.0, 1.0, false, "contradicted its own limit");

        assertThat(MathPhysicsEngine.generateConclusion(badResult, true))
                .contains("Theory check failed")
                .contains("contradicted its own limit")
                .doesNotContain("hypothesis is supported");
    }
}
