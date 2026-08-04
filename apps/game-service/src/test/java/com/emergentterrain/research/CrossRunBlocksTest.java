package com.emergentterrain.research;

import com.emergentterrain.research.question.PaperOpenQuestion;
import com.emergentterrain.research.question.VerifiedResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 33 T2+C1 — the cross-run context rendered onto a served topic's hypothesis. */
class CrossRunBlocksTest {

    @Test
    void rendersResultsAsDataAndQuestionAsPreferredClaim() {
        List<VerifiedResult> results = List.of(
                new VerifiedResult("P-a", "max_prime_gap", "TIGHT_BOUND",
                        "max_prime_gap <= +0.6490 * log_sq_window_start HELD, relative slack: 0.0003"),
                new VerifiedResult("P-b", "max_prime_gap", "TIGHT_BOUND",
                        "max_prime_gap <= +0.4280 * log_sq_window_start HELD, relative slack: 0.0012"));
        PaperOpenQuestion q = new PaperOpenQuestion("P-a", "max_prime_gap",
                "Does k continue to rise toward the Cramér value 1.0 beyond x=1.5e6?", "RATE");

        String out = ResearchCycleService.renderCrossRunBlocks("BASE HYPOTHESIS", results, q);
        assertThat(out).startsWith("BASE HYPOTHESIS")
                .contains("PRIOR VERIFIED RESULTS")
                .contains("[P-a, TIGHT_BOUND] max_prime_gap <= +0.6490")
                .contains("[P-b, TIGHT_BOUND]")
                .contains("fitting that trend")
                .contains("OPEN QUESTION FROM PRIOR WORK (RATE, posed by P-a)")
                .contains("Cramér value 1.0")
                .contains("ANSWERING or NARROWING");
    }

    @Test
    void emptyLedgers_leaveHypothesisUntouched() {
        assertThat(ResearchCycleService.renderCrossRunBlocks("H", List.of(), null)).isEqualTo("H");
        assertThat(ResearchCycleService.renderCrossRunBlocks("H", null, null)).isEqualTo("H");
    }
}
