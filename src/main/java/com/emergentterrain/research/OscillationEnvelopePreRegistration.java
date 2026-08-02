package com.emergentterrain.research;

import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.publication.ComputationalStudyPlan;

/**
 * Stable identity for an oscillation-envelope study.  Unlike a mined affine row, this records the
 * actual instrument and its fixed scale design before a draft ID or narration budget is consumed.
 */
public final class OscillationEnvelopePreRegistration {
    private static final String PREFIX = "locked-oscillation-envelope:v1;";

    private OscillationEnvelopePreRegistration() {}

    public static PreRegistration from(Conjecture conjecture) {
        if (conjecture == null || conjecture.lhs() == null || conjecture.lhs().isBlank()) return null;
        String lhs = conjecture.lhs();
        String scaleRhs = "mertens_delta".equals(lhs) ? "sqrt_window_size" : "";
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "Run the fixed five-decade verify_oscillation scale ladder for " + lhs + ".",
                "Run the fixed independent high-location and changed-width oscillation checks.",
                "Keep lhs, normalization, scale RHS, locations, and widths unchanged; no post-hoc envelope fitting.",
                "A finite envelope is calibration only and cannot establish an asymptotic theorem.",
                "mvn test -Dtest=ConjectureInvestigationProtocolTest");
        return new PreRegistration(
                "Measure the finite normalized oscillation envelope of " + lhs + " on a locked scale ladder.",
                "The ladder and independent windows report only finite amplitude and sign-change behavior.",
                "The study is incomplete if its locked normalization or independent window checks are replaced.",
                PREFIX + "lhs=" + lhs + ";scale_rhs=" + scaleRhs
                        + ";scale_ladder=true;window_size=5000;num_windows=20;"
                        + "location_start=1500000000;location_window_size=5000;location_num_windows=20;"
                        + "width_start=1500100000;width_window_size=10000;width_num_windows=10;",
                plan);
    }

    public static boolean isLocked(PreRegistration registration) {
        return registration != null && registration.mappingNotes() != null
                && registration.mappingNotes().startsWith(PREFIX);
    }
}
