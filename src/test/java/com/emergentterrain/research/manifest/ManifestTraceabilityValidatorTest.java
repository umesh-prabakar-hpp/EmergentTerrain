package com.emergentterrain.research.manifest;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 32 Wave 1 (32.2 shadow) — the ported data-to-paper tolerance rules,
 * pinned to the 2026-06-06 live fixtures and the ladder FP classes they
 * generalize.
 */
class ManifestTraceabilityValidatorTest {

    private static ValueManifest manifest(String hypothesis, String... results) {
        List<ToolInvocation> trace = new java.util.ArrayList<>();
        for (int i = 0; i < results.length; i++) {
            trace.add(new ToolInvocation("verify_conjecture", "{}", results[i], i, "c" + i));
        }
        return ValueManifest.capture(hypothesis, trace);
    }

    private static ManifestTraceabilityValidator.Stats run(
            ValueManifest m, String findings, String conclusion) {
        return ManifestTraceabilityValidator.validate(
                m, Map.of(1, findings), conclusion);
    }

    @Test
    void tracedValuesResolve_callMarkersIgnored() {
        // P-b72c-211's shape: prose cites the margin and window start with
        // call markers attached; both trace.
        ValueManifest m = manifest("k = -2.0000, c = 166.6706",
                "worst margin: +120.97163 at window starting 1441000");
        ManifestTraceabilityValidator.Stats s = run(m,
                "The worst violation is +120.97163 [call_1] at 1,441,000 [call_1].", "");
        assertThat(s.misses()).isEmpty();
        assertThat(s.resolved()).isEqualTo(2);
    }

    @Test
    void p221_fabricatedPercentage_misses() {
        // P-b72c-221's confirmed TP, verbatim: "refutation density rises from
        // 62% [call_1] to 97%" — actual counts 62/100 and 92/100. Both
        // percentages are count-derived (not literal trace values), so BOTH
        // miss — documented conservative behavior; a counting-aware rule is a
        // later refinement, and the wrong 97% is never resolvable regardless.
        ValueManifest m = manifest(null, "REFUTED margin +0.703 margin -22.31");
        ManifestTraceabilityValidator.Stats s = run(m,
                "The refutation density rises from 62 % [call_1] to 97 % here.", "");
        assertThat(s.misses()).extracting(ManifestTraceabilityValidator.Miss::token)
                .containsExactly("62 %", "97 %");
    }

    @Test
    void tokenizerDoesNotSplitAtDecimalPoint() {
        // "9700.5": a 4-digit integer prefix must not match alone leaving ".5".
        ValueManifest m = manifest(null, "value 9700.5 recorded");
        ManifestTraceabilityValidator.Stats s =
                run(m, "The peak reaches 9700.5 in the upper range.", "");
        assertThat(s.misses()).isEmpty();
        assertThat(s.resolved()).isEqualTo(1);
    }

    @Test
    void roundingMatch_citedAtLowerPrecision() {
        // Generalizes the ladder's flat ±1% : cited 0.013 vs source 0.012610
        // is 3% off but rounds exactly at the cited precision.
        ValueManifest m = manifest(null, "margin -0.012610 holds");
        assertThat(run(m, "The slack is approximately 0.013 at the witness.", "")
                .misses()).isEmpty();
    }

    @Test
    void percentScaleAmbiguity_bothDirections() {
        // "2.50 %" vs manifest fraction 0.025 — and a fraction cited where the
        // manifest holds the percentage.
        ValueManifest m = manifest(null, "fraction satisfying predicate = 0.025");
        assertThat(run(m, "About 2.50 % of integers satisfy both predicates.", "")
                .misses()).isEmpty();
    }

    @Test
    void percentDescaleDoesNotLaunderThroughDerivations() {
        // Commit-review fixture: fabricated 1230 must NOT resolve via
        // 1230/100 = 12.3 = 3·4.1 (hypothesis coefficient × tool value).
        // The /100 descale now requires a real % suffix, and scaled probes
        // hit direct entries + rounding only — never the derivation ladder.
        ValueManifest m = ValueManifest.capture("bound ≤ 3 · rhs + 0.5",
                java.util.List.of(new ToolInvocation("verify_conjecture", "{}",
                        "scalar = 4.1 observed", 0, "c1")));
        ManifestTraceabilityValidator.Stats s = run(m,
                "The aggregate reaches 1230.0 in this regime.", "");
        assertThat(s.misses()).hasSize(1);
        // And with an explicit % suffix, scaled DIRECT matches still work.
        ValueManifest m2 = ValueManifest.capture(null,
                java.util.List.of(new ToolInvocation("run_predicate_scan", "{}",
                        "fraction = 0.123 measured", 0, "c1")));
        assertThat(run(m2, "Roughly 12.30 % of the population qualifies.", "")
                .misses()).isEmpty();
    }

    @Test
    void boundPrefixExemptSurvivesWideSpacing() {
        ValueManifest m = manifest(null, "exact p 0.000412");
        assertThat(run(m, "Significant at p <   0.0042 with wide spacing.", "")
                .boundExempt()).isEqualTo(1);
    }

    @Test
    void boundPrefixExempt() {
        // "< 0.0042" states a bound, not a measurement (0.0042 chosen off the
        // allowlist — canonical alphas like 0.001 are allowlisted first).
        ValueManifest m = manifest(null, "exact p 0.000412");
        ManifestTraceabilityValidator.Stats s =
                run(m, "The deviation is significant at p < 0.0042 here.", "");
        assertThat(s.misses()).isEmpty();
        assertThat(s.boundExempt()).isEqualTo(1);
    }

    @Test
    void allowlistedConstantsNeverDemanded() {
        // CI multiplier + CI level + framing percentage: the three ladder FP
        // classes fixed by hand on 2026-06-04/05, now one rule.
        ValueManifest m = manifest(null, "nothing numeric here");
        ManifestTraceabilityValidator.Stats s = run(m,
                "Intervals use the 1.96 multiplier at the 95.00 level; 50.00 is framing.",
                "");
        assertThat(s.misses()).isEmpty();
        assertThat(s.allowlisted()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void smallStructuralIntegersAreNotSalient() {
        // "10 windows of size 500" — structural prose, below the salience bar.
        ValueManifest m = manifest(null, "HELD");
        ManifestTraceabilityValidator.Stats s =
                run(m, "Across 10 windows of size 500 the bound held.", "");
        assertThat(s.salient()).isZero();
    }

    @Test
    void derivedOneStepResolvesThroughManifest() {
        // k·x via hypothesis constant — resolution delegated to ValueManifest.
        ValueManifest m = manifest("lhs ≤ 2.0000 · rhs + 1.14", "lhs=+0.07700");
        assertThat(run(m, "The scaled density reaches 0.15400 at the start.", "")
                .misses()).isEmpty();
    }

    @Test
    void p236_rangeEndArithmetic_resolves() {
        // Second shadow session: "(150,000–170,000)" where the call was a
        // stats summary (no per-window dump) — 170,000 = 150000 + 20·1000.
        ValueManifest m = manifest(null,
                "stats over 20 window(s) of size 1000 starting at 150000: mean +0.5");
        assertThat(run(m, "This holds across the range 150,000–170,000 here.", "")
                .misses()).isEmpty();
    }

    @Test
    void p238_shownWorkFormula_exempt() {
        // "c ≈ +0.07938 − 2·(+0.00822) = +0.06294" — operands resolve, the
        // formula is displayed; the residual is derivation-shown.
        ValueManifest m = manifest("prime_density ≤ 2·twin_prime_density + c",
                "means: prime_density +0.07938 twin_prime_density +0.00822");
        ManifestTraceabilityValidator.Stats s = run(m,
                "Solving gives c ≈ +0.07938 − 2·(+0.00822) = +0.06294 as a bound.", "");
        assertThat(s.misses()).isEmpty();
    }

    @Test
    void shownWorkWithoutResolvableOperands_stillMisses() {
        // An equals sign alone must not launder: operands here trace to nothing.
        ValueManifest m = manifest(null, "value 5.5 recorded");
        ManifestTraceabilityValidator.Stats s = run(m,
                "Thus 33.33 = 11.11 × 3.0001 in this configuration.", "");
        assertThat(s.misses()).isNotEmpty();
    }

    @Test
    void nullAndEmptyInputsAreSafe() {
        assertThat(ManifestTraceabilityValidator.validate(null, null, null).salient())
                .isZero();
        ValueManifest m = manifest(null);
        assertThat(ManifestTraceabilityValidator.validate(m, Map.of(), "").misses())
                .isEmpty();
    }

    @Test
    void reasonsFormatCarriesTokenAndBlock() {
        ValueManifest m = manifest(null, "value 5.5");
        ManifestTraceabilityValidator.Stats s =
                run(m, "An untraceable 77.77 appears here.", "");
        List<String> reasons = ManifestTraceabilityValidator.reasons(s);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0))
                .startsWith("manifest_miss — salient value '77.77' (block 1)");
    }
}
