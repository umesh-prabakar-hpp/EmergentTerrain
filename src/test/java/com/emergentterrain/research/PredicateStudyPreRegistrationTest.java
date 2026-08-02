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
    void registersPairwiseDisjointLocationAndSecondWidthBeforeTheCensus() {
        PreRegistration registration = PredicateStudyPreRegistration.from(PROBLEM);

        assertThat(PredicateStudyPreRegistration.isLocked(registration)).isTrue();
        assertThat(PredicateStudyPreRegistration.exactTestDirective(registration))
                .contains("baseline=0.22").contains("expected_relation=APPROXIMATELY_EQUAL")
                .contains("comparison_tolerance=0.03")
                .contains("[350,850)").contains("[1350,1850)").contains("[1850,2100)");
        assertThat(PredicateStudyPreRegistration.hasExactEvidence(registration,
                List.of(scan(350, 850, 0.22), scan(1350, 1850, 0.22), scan(1850, 2100, 0.22)))).isTrue();
    }

    @Test
    void missingOrChangedRegisteredScanCannotSatisfyThePlan() {
        PreRegistration registration = PredicateStudyPreRegistration.from(PROBLEM);

        assertThat(PredicateStudyPreRegistration.hasExactEvidence(registration,
                List.of(scan(350, 850, 0.22), scan(1350, 1850, 0.22), scan(1850, 2100, 0.25)))).isFalse();
    }

    @Test
    void legacyOverlappingRegistrationCannotBeUsedAsIndependentConfirmation() {
        PreRegistration legacy = new PreRegistration("h", "p", "f",
                "locked-predicate-study:v2;predicate=isAbundant;metric=fraction_satisfying;baseline=0.22;"
                        + "expected_relation=APPROXIMATELY_EQUAL;comparison_tolerance=0.03;"
                        + "scan=350,850;scan=1350,1850;scan=350,600;");

        assertThat(PredicateStudyPreRegistration.isLocked(legacy)).isTrue();
        assertThat(PredicateStudyPreRegistration.hasExactEvidence(legacy,
                List.of(scan(350, 850, 0.22), scan(1350, 1850, 0.22), scan(350, 600, 0.22)))).isFalse();
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

    @Test
    void rejectsProseThatCallsExecutedLockedConfirmationWindowsPostHoc() {
        PreRegistration registration = PredicateStudyPreRegistration.from(PROBLEM);
        List<ToolInvocation> trace = List.of(scan(350, 850, 0.22), scan(1350, 1850, 0.22),
                scan(1850, 2100, 0.22));

        assertThat(PredicateStudyPreRegistration.narrativeConsistencyFailures(
                "The additional windows are post-hoc descriptive checks.", registration, trace))
                .isNotEmpty();
        assertThat(PredicateStudyPreRegistration.narrativeConsistencyFailures(
                "An additional scan outside the registered plan is exploratory.", registration, trace))
                .isEmpty();
    }
}
