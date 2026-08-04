package com.emergentterrain.service;

import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.EpistemicMarketService;
import com.emergentterrain.research.PromptTemplateRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the Phase 1 Iter 2 wiring in {@link GenomeMentoringService}: the
 * skepticism drift loop should nudge {@link BotLifecycleState#getSkepticismLevel}
 * toward the bot's epistemic-market calibration score, slowly and within bounds.
 */
class GenomeMentoringServiceTest {

    private GenomeMentoringService    svc;
    private EpistemicMarketService    market;
    private UUID                      botId;

    @BeforeEach
    void setUp() {
        market = mock(EpistemicMarketService.class);
        svc = new GenomeMentoringService(
                mock(LlmRouter.class),
                mock(RoomUpdateBroadcaster.class),
                mock(RoomBotRepository.class),
                new ObjectMapper().findAndRegisterModules(),
                market,
                new PromptTemplateRegistry());
        botId = UUID.randomUUID();
    }

    @Test
    void driftsTowardHigherCalibration() {
        when(market.calibrationScore(botId)).thenReturn(0.9);
        BotLifecycleState ls = stateWithSkepticism(0.5);

        boolean changed = svc.driftSkepticismLevel(botId, ls);

        assertThat(changed).isTrue();
        // (0.9 - 0.5) * 0.1 = 0.04, under the 0.05 cap, so the full delta applies.
        assertThat(ls.getSkepticismLevel()).isEqualTo(0.54, within(1e-9));
    }

    @Test
    void driftsTowardLowerCalibration() {
        when(market.calibrationScore(botId)).thenReturn(0.1);
        BotLifecycleState ls = stateWithSkepticism(0.5);

        boolean changed = svc.driftSkepticismLevel(botId, ls);

        assertThat(changed).isTrue();
        // (0.1 - 0.5) * 0.1 = -0.04, under the 0.05 cap, so the full delta applies.
        assertThat(ls.getSkepticismLevel()).isEqualTo(0.46, within(1e-9));
    }

    @Test
    void capsPerSessionDelta() {
        // Calibration far below current skepticism: raw delta = (0 - 1) * 0.1 = -0.1,
        // which would exceed the -0.05 cap. Confirm the cap binds.
        when(market.calibrationScore(botId)).thenReturn(0.0);
        BotLifecycleState ls = stateWithSkepticism(1.0);

        boolean changed = svc.driftSkepticismLevel(botId, ls);

        assertThat(changed).isTrue();
        assertThat(ls.getSkepticismLevel()).isEqualTo(0.95, within(1e-9));
    }

    @Test
    void noOpWhenAlreadyAtCalibration() {
        when(market.calibrationScore(botId)).thenReturn(0.5);
        BotLifecycleState ls = stateWithSkepticism(0.5);

        boolean changed = svc.driftSkepticismLevel(botId, ls);

        assertThat(changed).isFalse();
        assertThat(ls.getSkepticismLevel()).isEqualTo(0.5);
    }

    @Test
    void noOpWhenCalibrationDefaultMatchesDefaultSkepticism() {
        // Cold-start case: a bot with no prior market activity has calibrationScore=0.5
        // (neutral default) and skepticismLevel=0.5 (default). Drift should be a no-op
        // — important because mentoring runs every ~50 min and shouldn't churn the value
        // before any signal has accumulated.
        when(market.calibrationScore(botId)).thenReturn(0.5);
        BotLifecycleState ls = new BotLifecycleState();

        boolean changed = svc.driftSkepticismLevel(botId, ls);

        assertThat(changed).isFalse();
        assertThat(ls.getSkepticismLevel()).isEqualTo(0.5);
    }

    @Test
    void clampsToUnitInterval() {
        // Even with the cap, repeatedly drifting downward should not push below 0.
        when(market.calibrationScore(botId)).thenReturn(0.0);
        BotLifecycleState ls = stateWithSkepticism(0.02);

        svc.driftSkepticismLevel(botId, ls);

        assertThat(ls.getSkepticismLevel()).isBetween(0.0, 1.0);
    }

    private static BotLifecycleState stateWithSkepticism(double s) {
        BotLifecycleState ls = new BotLifecycleState();
        ls.setSkepticismLevel(s);
        return ls;
    }
}
