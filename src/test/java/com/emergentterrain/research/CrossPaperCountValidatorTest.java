package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.CrossPaperCountValidator.ScanCount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure tests for the cross-paper exact-count reconciliation validator (brief Change 6 core). */
class CrossPaperCountValidatorTest {

    private static ToolInvocation scan(String predicate, int start, int end, String result) {
        String args = String.format(
                "{\"predicate\":\"%s\",\"range_start\":%d,\"range_end\":%d,\"metric\":\"fraction\"}",
                predicate, start, end);
        return new ToolInvocation("run_predicate_scan", args, result, 0);
    }

    @Test
    void fromTrace_extractsExactCounts_skipsNonScanAndCiResults() {
        List<ScanCount> got = CrossPaperCountValidator.fromTrace(List.of(
                scan("isPrime AND isHappy", 400, 800,
                        "Dynamic [400,800) \"x\": fraction satisfying predicate = 11/400 = 0.02750 "
                        + "(exact count over the full interval; exhaustive census)."),
                new ToolInvocation("verify_conjecture", "{}", "HELD", 0),          // ignored
                scan("isPrime", 100, 200, "fraction = 0.20 ± 0.05 (95% CI, n=100)")));  // pre-Change-5, skipped
        assertThat(got).containsExactly(new ScanCount("isPrime AND isHappy", 400, 800, 11));
    }

    @Test
    void validate_flagsContainmentViolation() {
        // [400,800) ⊆ [400,850) but reports MORE matches → impossible.
        List<String> v = CrossPaperCountValidator.validate(List.of(
                new ScanCount("isPrime AND isHappy", 400, 800, 11),
                new ScanCount("isPrime AND isHappy", 400, 850, 10)));
        assertThat(v).hasSize(1);
        assertThat(v.get(0)).contains("sub-range [400,800) count 11").contains("[400,850) count 10");
    }

    @Test
    void validate_flagsSameRangeInconsistency() {
        List<String> v = CrossPaperCountValidator.validate(List.of(
                new ScanCount("isPrime", 400, 800, 10),
                new ScanCount("isPrime", 400, 800, 12)));
        assertThat(v).hasSize(1);
        assertThat(v.get(0)).contains("same range [400,800)").contains("10 vs 12");
    }

    @Test
    void validate_cleanWhenConsistentOrDifferentPredicate() {
        // consistent containment (sub ≤ super) + a different predicate that must not cross-check.
        assertThat(CrossPaperCountValidator.validate(List.of(
                new ScanCount("isPrime AND isHappy", 400, 800, 10),
                new ScanCount("isPrime AND isHappy", 400, 850, 11),
                new ScanCount("isHappy", 400, 800, 57)))).isEmpty();
        assertThat(CrossPaperCountValidator.validate(List.of())).isEmpty();
    }

    @Test
    void factRoundTrips() {
        ScanCount c = new ScanCount("isPrime AND isHappy", 400, 800, 11);
        assertThat(CrossPaperCountValidator.fromFact(CrossPaperCountValidator.toFact(c))).isEqualTo(c);
        assertThat(CrossPaperCountValidator.fromFact("not a scan-count line")).isNull();
    }
}
