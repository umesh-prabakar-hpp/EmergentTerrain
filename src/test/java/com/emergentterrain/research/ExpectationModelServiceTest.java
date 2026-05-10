package com.emergentterrain.research;

import com.emergentterrain.repo.ExpectationPriorRepository;
import com.emergentterrain.repo.SurpriseEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the Phase 2 Iter 1 ExpectationModelService — Welford prior
 * maintenance and surprise scoring.
 *
 * The prior repository is mocked but tracks state via a map so successive
 * updatePrior calls accumulate correctly, mirroring the real Hibernate
 * write-then-read flow.
 */
class ExpectationModelServiceTest {

    private ExpectationPriorRepository priors;
    private SurpriseEventRepository    surprises;
    private ExpectationModelService    service;

    private final Map<String, ExpectationPrior> priorStore = new HashMap<>();

    @BeforeEach
    void setUp() {
        priors    = mock(ExpectationPriorRepository.class);
        surprises = mock(SurpriseEventRepository.class);
        priorStore.clear();

        // Make the mocked repo behave like an in-memory store keyed by (botId, domain).
        when(priors.findByBotIdAndDomain(any(), any())).thenAnswer(inv -> {
            UUID botId = inv.getArgument(0);
            String domain = inv.getArgument(1);
            return Optional.ofNullable(priorStore.get(key(botId, domain)));
        });
        when(priors.save(any(ExpectationPrior.class))).thenAnswer(inv -> {
            ExpectationPrior p = inv.getArgument(0);
            priorStore.put(key(p.getBotId(), p.getDomain()), p);
            return p;
        });

        service = new ExpectationModelService(priors, surprises);
    }

    @Test
    void updatePriorBuildsRunningMeanViaWelford() {
        UUID bot = UUID.randomUUID();
        UUID room = UUID.randomUUID();

        // Feed 5 papers with peer scores 0.4, 0.5, 0.6, 0.5, 0.5 → mean = 0.50
        double[] scores = {0.4, 0.5, 0.6, 0.5, 0.5};
        for (double s : scores) service.updatePrior(bot, paper(bot, room, "MATHEMATICS", s));

        ExpectationPrior prior = priorStore.get(key(bot, "MATHEMATICS"));
        assertThat(prior).isNotNull();
        assertThat(prior.getSampleCount()).isEqualTo(5);
        assertThat(prior.getExpectedMean()).isCloseTo(0.5, within(1e-9));
        // Variance of (0.4, 0.5, 0.6, 0.5, 0.5) = 0.005 → stddev ~ 0.0707
        assertThat(prior.stdDev()).isCloseTo(0.0707, within(0.001));
    }

    @Test
    void scoreExperimentReturnsNullDuringBootstrap() {
        UUID bot = UUID.randomUUID();
        UUID room = UUID.randomUUID();
        // Only 4 samples — below BOOTSTRAP_SAMPLE_COUNT.
        for (int i = 0; i < 4; i++) {
            service.updatePrior(bot, paper(bot, room, "MATHEMATICS", 0.5));
        }

        SurpriseEvent surprise = service.scoreExperiment(bot,
                paper(bot, room, "MATHEMATICS", 0.95), null);

        assertThat(surprise).isNull();
        verify(surprises, never()).save(any());
    }

    @Test
    void scoreExperimentReturnsNullForLowResidual() {
        UUID bot = UUID.randomUUID();
        UUID room = UUID.randomUUID();
        // Bootstrap with mean = 0.5, stddev ~ 0.07
        for (double s : new double[]{0.4, 0.5, 0.6, 0.5, 0.5}) {
            service.updatePrior(bot, paper(bot, room, "MATHEMATICS", s));
        }

        // 0.55 is well within 1 stddev → not surprising.
        SurpriseEvent surprise = service.scoreExperiment(bot,
                paper(bot, room, "MATHEMATICS", 0.55), null);

        assertThat(surprise).isNull();
        verify(surprises, never()).save(any());
    }

    @Test
    void scoreExperimentEmitsSurpriseAboveThreshold() {
        UUID bot = UUID.randomUUID();
        UUID room = UUID.randomUUID();
        for (double s : new double[]{0.4, 0.5, 0.6, 0.5, 0.5}) {
            service.updatePrior(bot, paper(bot, room, "MATHEMATICS", s));
        }
        AtomicReference<SurpriseEvent> persisted = new AtomicReference<>();
        when(surprises.save(any(SurpriseEvent.class))).thenAnswer(inv -> {
            persisted.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        // 0.95 is far from the 0.5 mean given stddev ~ 0.07 → score >> 2.0
        SurpriseEvent surprise = service.scoreExperiment(bot,
                paper(bot, room, "MATHEMATICS", 0.95), null);

        assertThat(surprise).isNotNull();
        assertThat(surprise.getResidual()).isCloseTo(0.45, within(1e-9));
        assertThat(surprise.getSurpriseScore()).isGreaterThanOrEqualTo(2.0);
        assertThat(surprise.getSource()).isEqualTo(SurpriseEvent.Source.PRIOR);
        assertThat(persisted.get()).isSameAs(surprise);
    }

    @Test
    void surpriseSourceIsPreRegistrationWhenSupplied() {
        UUID bot = UUID.randomUUID();
        UUID room = UUID.randomUUID();
        for (double s : new double[]{0.4, 0.5, 0.6, 0.5, 0.5}) {
            service.updatePrior(bot, paper(bot, room, "MATHEMATICS", s));
        }
        when(surprises.save(any(SurpriseEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        PreRegistration preReg = new PreRegistration(
                "Score will rise", "around 0.5", "score below 0.3", "");
        SurpriseEvent surprise = service.scoreExperiment(bot,
                paper(bot, room, "MATHEMATICS", 0.95), preReg);

        assertThat(surprise).isNotNull();
        assertThat(surprise.getSource()).isEqualTo(SurpriseEvent.Source.PRE_REGISTRATION);
    }

    @Test
    void surpriseScoreIsCappedSoZeroVarianceDoesNotExplode() {
        UUID bot = UUID.randomUUID();
        UUID room = UUID.randomUUID();
        // Five identical samples → stddev = 0 → without the cap, score would be ∞.
        for (int i = 0; i < 5; i++) {
            service.updatePrior(bot, paper(bot, room, "MATHEMATICS", 0.5));
        }
        when(surprises.save(any(SurpriseEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        SurpriseEvent surprise = service.scoreExperiment(bot,
                paper(bot, room, "MATHEMATICS", 0.95), null);

        assertThat(surprise).isNotNull();
        assertThat(surprise.getSurpriseScore()).isLessThanOrEqualTo(10.0);
    }

    private static String key(UUID botId, String domain) { return botId + ":" + domain; }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }

    private static ResearchPaper paper(UUID authorBotId, UUID roomId, String domain, double peerScore) {
        return new ResearchPaper(
                "P-test-" + (peerScore + ""), roomId, authorBotId.toString(), domain, "topic-x",
                "Test Title", "h", "THEORETICAL",
                "f", "c",
                List.of(), peerScore, "PUBLISHED",
                100L, Instant.now(), 1, 0.0, null, null);
    }
}
