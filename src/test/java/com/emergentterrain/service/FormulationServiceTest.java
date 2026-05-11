package com.emergentterrain.service;

import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.ResearchIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the Phase 1 Iter 2 wiring in {@link FormulationService}: the prompt
 * builder should bias hypothesis style based on a {@link ResearchIdentity}'s
 * skepticismLevel and riskTolerance, and should fall back to no preamble when
 * no identity is available (legacy 5-arg callers).
 */
class FormulationServiceTest {

    @Test
    void emptyHintWhenIdentityMissing() {
        assertThat(FormulationService.identityHint(null)).isEmpty();
    }

    @Test
    void highSkepticismRequestsSharplyFalsifiableFraming() {
        String hint = FormulationService.identityHint(identityWith(0.85, 0.20));

        assertThat(hint).contains("Researcher style");
        assertThat(hint).contains("sharply falsifiable");
        assertThat(hint).doesNotContain("exploratory");
    }

    @Test
    void lowSkepticismRequestsExploratoryFraming() {
        String hint = FormulationService.identityHint(identityWith(0.10, 0.20));

        assertThat(hint).contains("exploratory");
        assertThat(hint).doesNotContain("sharply falsifiable");
    }

    @Test
    void mediumSkepticismGivesModerateFraming() {
        String hint = FormulationService.identityHint(identityWith(0.50, 0.20));

        assertThat(hint).contains("moderately specific");
        assertThat(hint).doesNotContain("sharply falsifiable");
        assertThat(hint).doesNotContain("exploratory");
    }

    @Test
    void highRiskEncouragesEdgeOfSearchRadius() {
        String hint = FormulationService.identityHint(identityWith(0.50, 0.40));

        assertThat(hint).contains("edge of the learned search radius");
        assertThat(hint).doesNotContain("conservative");
    }

    @Test
    void lowRiskPrefersConservativeSteps() {
        String hint = FormulationService.identityHint(identityWith(0.50, 0.10));

        assertThat(hint).contains("learned search center");
        assertThat(hint).contains("conservative");
        assertThat(hint).doesNotContain("edge of the learned search radius");
    }

    @Test
    void hintIncludesCareerSummary() {
        ResearchIdentity id = new ResearchIdentity(
                "EMPIRICAL", 0.25, 0.6,
                new double[]{0, 0, 0, 0, 0},
                new double[]{0.5, 0.5, 0.5, 0.5},
                0.72, 12, 3, List.of());

        String hint = FormulationService.identityHint(id);

        assertThat(hint).contains("methodology=EMPIRICAL");
        assertThat(hint).contains("career: 12 published / 3 rejected");
    }

    private static ResearchIdentity identityWith(double skepticism, double risk) {
        return new ResearchIdentity(
                "THEORETICAL", risk, skepticism,
                new double[5],
                new double[]{0.5, 0.5, 0.5, 0.5},
                0.5, 0, 0, List.of());
    }

    @Test
    void identityFromBareLifecycleStillRendersDefaults() {
        BotLifecycleState ls = new BotLifecycleState();
        ResearchIdentity id = ResearchIdentity.from(ls, null, List.of());

        String hint = FormulationService.identityHint(id);

        assertThat(hint).contains("methodology=THEORETICAL");
    }
}
