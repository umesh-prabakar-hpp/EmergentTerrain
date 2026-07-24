package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PredicateBaselineSensitivityValidatorTest {
    private static ToolInvocation scan(long start, long end) {
        return new ToolInvocation("run_predicate_scan",
                "{\"predicate\":\"isAbundant\",\"range_start\":" + start
                        + ",\"range_end\":" + end + ",\"baseline\":0.22}", "exact", 0);
    }

    @Test
    void contiguousEqualWidthScansDoNotEstablishSensitivity() {
        assertThat(PredicateBaselineSensitivityValidator.validate("External baseline 0.220",
                List.of(scan(350, 850), scan(850, 1350), scan(1350, 1850))))
                .singleElement().asString()
                .contains("second window width").contains("overlapping offset window");
    }

    @Test
    void offsetAndWidthSensitivityPasses() {
        assertThat(PredicateBaselineSensitivityValidator.validate("baseline 0.220",
                List.of(scan(350, 850), scan(600, 1100), scan(350, 1350))))
                .isEmpty();
    }

    @Test
    void changingBaselineDoesNotEstablishOneBaselineComparison() {
        assertThat(PredicateBaselineSensitivityValidator.validate("baseline comparison",
                List.of(scan(350, 850), scan(600, 1100),
                        new ToolInvocation("run_predicate_scan",
                                "{\"predicate\":\"isAbundant\",\"range_start\":350,\"range_end\":1350,\"baseline\":0.25}",
                                "exact", 0))))
                .singleElement().asString().contains("changing baseline");
    }
}
