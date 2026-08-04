package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.Conjecture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MertensEnvelopePreRegistrationTest {

    private final PreRegistration registration = MertensEnvelopePreRegistration.from(
            new Conjecture("MATHEMATICS", "mertens_delta", "omega_excess_density", 2.0, 17.6208));

    @Test
    void locksFiniteMeasurementInsteadOfMinedAffineRelation() {
        assertThat(MertensEnvelopePreRegistration.isLocked(registration)).isTrue();
        assertThat(registration.mappingNotes())
                .startsWith("locked-mertens-window-envelope:v1;")
                .contains("scale_rhs=sqrt_window_size")
                .contains("primary,100000000,5000,20")
                .contains("location_holdout,900000000,5000,20")
                .contains("width_holdout,1500000000,10000,10");
        assertThat(MertensEnvelopePreRegistration.exactTestDirective(registration))
                .contains("verify_oscillation").contains("scale_ladder=false")
                .contains("search_literature").contains("read_reference");
    }

    @Test
    void exactEvidenceRequiresPrimaryAndIndependentLocationAndWidthHoldouts() {
        List<ToolInvocation> trace = List.of(
                osc(100_000_000L, 5_000, 20, "sqrt_window_size", false),
                osc(900_000_000L, 5_000, 20, "sqrt_window_size", false),
                osc(1_500_000_000L, 10_000, 10, "sqrt_window_size", false));

        assertThat(MertensEnvelopePreRegistration.hasExactEvidence(registration, trace)).isTrue();
        assertThat(MertensEnvelopePreRegistration.hasExactEvidence(registration,
                List.of(trace.get(0), trace.get(1), osc(900_000_000L, 10_000, 10,
                        "sqrt_window_size", false)))).isFalse();
    }

    @Test
    void rejectsWrongNormalizerOrScaleLadderSubstitute() {
        List<ToolInvocation> wrongScale = List.of(
                osc(100_000_000L, 5_000, 20, "sqrt_window_start", false),
                osc(900_000_000L, 5_000, 20, "sqrt_window_size", false),
                osc(1_500_000_000L, 10_000, 10, "sqrt_window_size", false));
        assertThat(MertensEnvelopePreRegistration.hasExactEvidence(registration, wrongScale)).isFalse();

        List<ToolInvocation> ladder = List.of(
                osc(100_000_000L, 5_000, 20, "sqrt_window_size", true),
                osc(900_000_000L, 5_000, 20, "sqrt_window_size", false),
                osc(1_500_000_000L, 10_000, 10, "sqrt_window_size", false));
        assertThat(MertensEnvelopePreRegistration.hasExactEvidence(registration, ladder)).isFalse();
    }

    private static ToolInvocation osc(long start, int size, int windows, String scale, boolean ladder) {
        String args = "{\"lhs\":\"mertens_delta\",\"scale_rhs\":\"" + scale
                + "\",\"window_start\":" + start + ",\"window_size\":" + size
                + ",\"num_windows\":" + windows + ",\"scale_ladder\":" + ladder + "}";
        return new ToolInvocation("verify_oscillation", args,
                "verify_oscillation: mertens_delta over " + windows + " window(s) of size " + size, 0);
    }
}
