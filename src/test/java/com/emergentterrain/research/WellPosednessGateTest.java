package com.emergentterrain.research;

import com.emergentterrain.research.WellPosednessGate.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure parse + caveat-construction tests for the well-posedness gate (no Spring/LLM). */
class WellPosednessGateTest {

    @Test
    void parsesCleanJson() {
        Verdict v = WellPosednessGate.parse("""
                {"well_defined": false, "issue": "representation_dependent",
                 "explanation": "happy numbers are base-10 specific",
                 "verdict": "admit_with_caveat", "caveat": "base-10 dependent; not a fundamental quantity",
                 "reframed_question": ""}""");
        assertThat(v).isNotNull();
        assertThat(v.wellDefined()).isFalse();
        assertThat(v.issue()).isEqualTo("representation_dependent");
        assertThat(v.verdict()).isEqualTo("admit_with_caveat");
        assertThat(v.caveat()).contains("base-10 dependent");
    }

    @Test
    void parsesJsonWrappedInProse() {
        Verdict v = WellPosednessGate.parse(
                "Sure — here is my assessment:\n{\"verdict\":\"reject\",\"issue\":\"recreational\","
                + "\"explanation\":\"puzzle, not research\"}\nLet me know if you need more.");
        assertThat(v).isNotNull();
        assertThat(v.verdict()).isEqualTo("reject");
        assertThat(v.issue()).isEqualTo("recreational");
    }

    @Test
    void noVerdictFieldReturnsNull() {
        assertThat(WellPosednessGate.parse("I cannot decide.")).isNull();
        assertThat(WellPosednessGate.parse(null)).isNull();
    }

    @Test
    void caveatForAdmitIsEmpty() {
        assertThat(WellPosednessGate.caveatFor(Verdict.ADMIT)).isEmpty();
        assertThat(WellPosednessGate.caveatFor(
                new Verdict(true, "none", "", "admit", "", ""))).isEmpty();
        assertThat(WellPosednessGate.caveatFor(null)).isEmpty();
    }

    @Test
    void caveatForAdmitWithCaveatSurfacesTheCaveat() {
        String c = WellPosednessGate.caveatFor(new Verdict(false, "no_limiting_value",
                "happy-density has no asymptotic limit", "admit_with_caveat",
                "the happy-number density has no limiting value", ""));
        assertThat(c)
                .contains("WELL-POSEDNESS NOTE")
                .contains("no_limiting_value")
                .contains("no limiting value")
                .contains("first paragraph");
    }

    @Test
    void caveatForReframeSurfacesTheBetterQuestion() {
        String c = WellPosednessGate.caveatFor(new Verdict(false, "representation_dependent",
                "base-10 specific", "reframe", "",
                "does the happy-prime count track the prime count up to a base-independent constant?"));
        assertThat(c)
                .contains("better-posed question")
                .contains("base-independent constant");
    }

    @Test
    void caveatForRejectBecomesAStrongCaveat_notASilentDrop() {
        // caveat-first: reject still yields a surfaced note (inform-don't-suppress), never empty.
        String c = WellPosednessGate.caveatFor(new Verdict(false, "tooling_artifact",
                "CI on an exhaustive census", "reject", "", ""));
        assertThat(c).isNotEmpty().contains("tooling_artifact").contains("WELL-POSEDNESS NOTE");
    }
}
