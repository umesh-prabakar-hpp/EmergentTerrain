package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PredicateStudyPreRegistrationTest {
    private static final DynamicProblem PROBLEM = new DynamicProblem(
            "d-1", "abundant finite density", 350, 850,
            "isAbundant", "fraction_satisfying", 0.22, "Finite comparison.",
            DynamicProblem.ExpectedRelation.APPROXIMATELY_EQUAL, 0.03);

    private static ToolInvocation scan(long start, long end, double baseline) {
        return new ToolInvocation("run_predicate_scan",
                "{\"predicate\":\"isAbundant\",\"range_start\":" + start
                        + ",\"range_end\":" + end + ",\"metric\":\"fraction_satisfying\","
                        + "\"baseline\":" + baseline
                        + ",\"comparison_tolerance\":0.03,\"hypothesis\":\"finite comparison\"}",
                "Predicate scan: exact", 0);
    }

    @Test
    void registersPrimaryOffsetAndSecondWidthBeforeTheCensus() {
        PreRegistration registration = PredicateStudyPreRegistration.from(PROBLEM);

        assertThat(PredicateStudyPreRegistration.isLocked(registration)).isTrue();
        assertThat(PredicateStudyPreRegistration.exactTestDirective(registration))
                .contains("baseline=0.22").contains("expected_relation=APPROXIMATELY_EQUAL")
                .contains("comparison_tolerance=0.03")
                .contains("[350,850)").contains("[475,975)").contains("[350,600)");
        assertThat(PredicateStudyPreRegistration.hasExactEvidence(registration,
                List.of(scan(350, 850, 0.22), scan(475, 975, 0.22), scan(350, 600, 0.22)))).isTrue();
    }

    @Test
    void missingOrChangedRegisteredScanCannotSatisfyThePlan() {
        PreRegistration registration = PredicateStudyPreRegistration.from(PROBLEM);

        assertThat(PredicateStudyPreRegistration.hasExactEvidence(registration,
                List.of(scan(350, 850, 0.22), scan(475, 975, 0.22), scan(350, 600, 0.25)))).isFalse();
    }

    @Test
    void tooSmallDirectiveDoesNotPretendToHaveAWidthSensitivityPlan() {
        assertThat(PredicateStudyPreRegistration.from(new DynamicProblem(
                "d-small", "tiny", 1, 4, "isPrime", "fraction_satisfying", 0.5, "h"))).isNull();
    }

    @Test
    void approximateEqualityWithoutExplicitToleranceCannotCreateALockedStudy() {
        DynamicProblem underspecified = new DynamicProblem(
                "d-missing-tolerance", "finite density", 100, 300,
                "isAbundant", "fraction_satisfying", 0.22, "Approximately equal.",
                DynamicProblem.ExpectedRelation.APPROXIMATELY_EQUAL);

        assertThat(PredicateStudyPreRegistration.from(underspecified)).isNull();
    }
}
