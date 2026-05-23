package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paper-review 2026-05-22 fix 4 — tests for
 * {@link HypothesisRangeCoverageValidator}. Ships in warn mode so the
 * validator's only job is to surface true positives without flagging
 * legitimate range coverage. Tests pin the worked examples from
 * research/a3289005 and a few false-positive guards.
 */
class HypothesisRangeCoverageValidatorTest {

    private static ToolInvocation call(String args) {
        return new ToolInvocation("run_symbolic", args, "{}", 0);
    }

    // ── True positives — the corpus failures we want to catch ────────────────

    @Test
    void flagsP_a328_900_unitMismatch_notExercised() {
        // P-a328-900 — hypothesis declares 400 nm → 700 nm sweep but the
        // LLM called asin(654e-9 / 2.09e-6) in SI base units, so the
        // numeric literals visible in the args (6.54e-7, 2.09e-6) fall
        // far outside the [400, 700] hypothesis range. The validator
        // flags this as "not exercised" — which is *more* actionable
        // than "only one point" because it tells the operator the LLM
        // used different units than the hypothesis text declared.
        String hypothesis = "For a grating with d=2.09 µm, the number of "
                + "visible diffraction orders will decrease as the "
                + "wavelength increases from 400nm to 700nm";
        List<ToolInvocation> trace = List.of(
                call("{\"expression\": \"asin(654e-9/2.09e-6) * 180 / pi\"}"));
        List<String> reasons = HypothesisRangeCoverageValidator.validate(hypothesis, trace);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0))
                .contains("not exercised")
                .contains("400")
                .contains("700");
    }

    @Test
    void flagsSinglePointInWavelengthSweep_unitMatched() {
        // Same hypothesis, but the call uses nm-scale args matching the
        // hypothesis units. One in-range point → "only one" message.
        String hypothesis = "wavelength increases from 400nm to 700nm";
        List<ToolInvocation> trace = List.of(call("{\"lambda\": 654}"));
        List<String> reasons = HypothesisRangeCoverageValidator.validate(hypothesis, trace);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0))
                .contains("only one")
                .contains("400")
                .contains("700");
    }

    @Test
    void flagsAllSamplesAtBottomOfRange() {
        // The range is [0, 100] but trace samples 5/6/7 — span 2,
        // width 100, span/width = 2 % → well below 50 % coverage.
        String hypothesis = "fraction-satisfying density swept from 0 to 100";
        List<ToolInvocation> trace = List.of(
                call("{\"start\": 5}"), call("{\"start\": 6}"), call("{\"start\": 7}"));
        List<String> reasons = HypothesisRangeCoverageValidator.validate(hypothesis, trace);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("span only");
    }

    @Test
    void flagsZeroInRangePoints() {
        // Range [400, 700] but the call's args contain only values
        // outside that range. Validator fires with the "not exercised"
        // message rather than the "only one" / "span only" variants.
        String hypothesis = "wavelength increases from 400 to 700 nm";
        List<ToolInvocation> trace = List.of(call("{\"expression\": \"sin(2 * 1.5)\"}"));
        List<String> reasons = HypothesisRangeCoverageValidator.validate(hypothesis, trace);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("not exercised");
    }

    // ── True negatives — coverage is genuinely adequate ──────────────────────

    @Test
    void acceptsHalfCoverageOfRange() {
        // Range 0 → 100, samples at 10 and 60 — span 50 = 50 % of
        // width. At the threshold, accepts. (Strictly: span ≥
        // 0.5 * width is the accept condition.)
        String hypothesis = "swept from 0 to 100";
        List<ToolInvocation> trace = List.of(call("{\"v\": 10}"), call("{\"v\": 60}"));
        List<String> reasons = HypothesisRangeCoverageValidator.validate(hypothesis, trace);
        assertThat(reasons).isEmpty();
    }

    @Test
    void acceptsFullRangeCoverage() {
        // Samples at the two endpoints — full coverage.
        String hypothesis = "from 100 to 200 K";
        List<ToolInvocation> trace = List.of(call("{\"T\": 100}"), call("{\"T\": 200}"));
        assertThat(HypothesisRangeCoverageValidator.validate(hypothesis, trace)).isEmpty();
    }

    // ── No-op cases — no range pattern, no trace, etc. ───────────────────────

    @Test
    void emptyHypothesis_returnsEmpty() {
        assertThat(HypothesisRangeCoverageValidator.validate("", List.of(call("{}")))).isEmpty();
        assertThat(HypothesisRangeCoverageValidator.validate(null, List.of(call("{}")))).isEmpty();
    }

    @Test
    void emptyTrace_returnsEmpty() {
        // Legacy / Ollama-fallback paths legitimately have no trace.
        String hypothesis = "from 400 to 700 nm";
        assertThat(HypothesisRangeCoverageValidator.validate(hypothesis, null)).isEmpty();
        assertThat(HypothesisRangeCoverageValidator.validate(hypothesis, List.of())).isEmpty();
    }

    @Test
    void noRangePatternInHypothesis_returnsEmpty() {
        // Open-ended sweep ("swept from T=2800 K" with no upper) is a
        // single-point case for SinglePointSweepValidator, not this one.
        // P-a328-901 falls here — deliberately skipped.
        String hypothesis = "for a unit-area emitter swept from T=2800 K";
        List<ToolInvocation> trace = List.of(call("{\"v\": 5.8e-8}"));
        assertThat(HypothesisRangeCoverageValidator.validate(hypothesis, trace)).isEmpty();
    }

    @Test
    void degenerateRange_returnsEmpty() {
        // "from 5 to 5" has width 0 — guard against divide-by-zero.
        String hypothesis = "swept from 5 to 5";
        assertThat(HypothesisRangeCoverageValidator.validate(hypothesis,
                List.of(call("{\"v\": 5}")))).isEmpty();
    }

    // ── Pattern variants ─────────────────────────────────────────────────────

    @Test
    void supportsBetweenAndPattern() {
        // "between X and Y" — alternate phrasing the corpus uses.
        String hypothesis = "the density between 40 and 60 is roughly 20%";
        List<ToolInvocation> trace = List.of(call("{\"start\": 41}"));
        List<String> reasons = HypothesisRangeCoverageValidator.validate(hypothesis, trace);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0)).contains("only one");
    }

    @Test
    void supportsBracketRangePattern() {
        // "[40, 60)" / "[40, 60]" — common in math papers.
        String hypothesis = "the density in [40, 60) of abundant numbers";
        List<ToolInvocation> trace = List.of(
                call("{\"start\": 45}"), call("{\"start\": 50}"), call("{\"start\": 55}"));
        // Span 10 < 0.5 * 20 = 10 — strictly less than, so accepts.
        // Adjust expectation: span 10 < 10 is false; pass.
        // Actually 10 < 10 is false; span >= threshold, accept.
        assertThat(HypothesisRangeCoverageValidator.validate(hypothesis, trace)).isEmpty();
    }
}
