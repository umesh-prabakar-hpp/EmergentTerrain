package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaxDivisorCountMeasurementProtocolStateExecutorTest {
    private static final PreRegistration REGISTRATION = MaxDivisorCountMeasurementPreRegistration.from(
            new Conjecture("MATHEMATICS", "max_divisor_count", "wigert_bound", 1.0, 0.0));

    @Test
    void carriesVerifierSuggestedFitThenTheNamedRefutation() {
        List<ToolInvocation> trace = new ArrayList<>(List.of(call(1.0, 0.0, 1_000_000_000L, 5_000L, 20,
                "verify_conjecture: max_divisor_count <= +1 HELD across 20 window(s). relative slack: 0.5. "
                        + "LOOSE: re-verify at k=0.93")));
        var tightFit = MaxDivisorCountMeasurementProtocolStateExecutor.next(REGISTRATION, trace).orElseThrow();
        assertThat(tightFit.phase()).isEqualTo("tight_fit");
        assertThat(tightFit.argumentsJson()).contains("\"k\":0.93").contains("\"window_start\":1000000000");

        trace.add(call(0.93, 0.0, 1_000_000_000L, 5_000L, 20,
                "verify_conjecture: max_divisor_count <= +0.93 HELD across 20 window(s). relative slack: 0.01. "
                        + "TIGHT HOLD — re-verify with c=-0.25."));
        var refutation = MaxDivisorCountMeasurementProtocolStateExecutor.next(REGISTRATION, trace).orElseThrow();
        assertThat(refutation.phase()).isEqualTo("fit_refutation");
        assertThat(refutation.argumentsJson()).contains("\"k\":0.93").contains("\"c\":-0.25");
    }

    private static ToolInvocation call(double k, double c, long start, long size, int windows, String result) {
        return new ToolInvocation("verify_conjecture", "{\"lhs\":\"max_divisor_count\",\"rhs\":\"wigert_bound\",\"k\":" + k
                + ",\"c\":" + c + ",\"window_start\":" + start + ",\"window_size\":" + size
                + ",\"num_windows\":" + windows + "}", result, 0);
    }
}
