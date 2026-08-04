package com.emergentterrain.research;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.BotCalibrationRepository;
import com.emergentterrain.repo.EpistemicMarketRepository;
import com.emergentterrain.repo.MarketStakeRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for Phase 3 Iter 7 EpistemicMarketService — open / stake / resolve
 * lifecycle. Repos are mocked but a tiny in-memory state mirror keeps the
 * Welford / calibration accumulation realistic across calls.
 */
class EpistemicMarketServiceTest {

    private EpistemicMarketRepository markets;
    private MarketStakeRepository     stakes;
    private BotCalibrationRepository  calibrations;
    private RoomBotRepository         botRepo;
    private ObjectMapper              mapper;

    private final Map<UUID, EpistemicMarket> marketStore = new HashMap<>();
    private final Map<UUID, RoomBot>          botStore    = new HashMap<>();

    @BeforeEach
    void setUp() {
        markets      = mock(EpistemicMarketRepository.class);
        stakes       = mock(MarketStakeRepository.class);
        calibrations = mock(BotCalibrationRepository.class);
        botRepo      = mock(RoomBotRepository.class);
        mapper       = new ObjectMapper().findAndRegisterModules();
        marketStore.clear();
        botStore.clear();

        // Mocked repos behave like an in-memory store keyed by id.
        when(markets.save(any(EpistemicMarket.class))).thenAnswer(inv -> {
            EpistemicMarket m = inv.getArgument(0);
            if (m.getId() == null) setId(m, UUID.randomUUID());
            marketStore.put(m.getId(), m);
            return m;
        });
        when(markets.findById(any(UUID.class))).thenAnswer(inv ->
                Optional.ofNullable(marketStore.get(inv.getArgument(0))));
        when(markets.findFirstByHypothesisPaperId(any())).thenAnswer(inv -> {
            String pid = inv.getArgument(0);
            return marketStore.values().stream()
                    .filter(m -> pid.equals(m.getHypothesisPaperId())).findFirst();
        });
        when(markets.findByRoomIdAndStatus(any(UUID.class), any(EpistemicMarket.Status.class)))
                .thenAnswer(inv -> {
                    UUID room = inv.getArgument(0);
                    EpistemicMarket.Status status = inv.getArgument(1);
                    return marketStore.values().stream()
                            .filter(m -> room.equals(m.getRoomId()) && m.getStatus() == status)
                            .toList();
                });

        when(botRepo.findById(any(UUID.class))).thenAnswer(inv ->
                Optional.ofNullable(botStore.get(inv.getArgument(0))));
    }

    @Test
    void disabledServiceWritesNothing() {
        EpistemicMarketService svc = new EpistemicMarketService(
                false, false, markets, stakes, calibrations, botRepo, mapper);

        assertThat(svc.openMarketFor(paper(UUID.randomUUID(), "MATHEMATICS", "topic-x", "P-1"),
                new PreRegistration("h", "around 0.5", "no correlation", ""))).isEmpty();
        assertThat(svc.placeStake(UUID.randomUUID(), UUID.randomUUID(),
                EpistemicMarket.StakeSide.CONFIRMS, 0.1)).isEmpty();
        assertThat(svc.maybeResolveMarketsFor(
                paper(UUID.randomUUID(), "MATHEMATICS", "topic-x", "P-2"), 5.0)).isZero();
        verify(markets, never()).save(any());
    }

    @Test
    void openMarketRequiresPreRegistration() {
        EpistemicMarketService svc = enabled();
        ResearchPaper p = paper(UUID.randomUUID(), "MATHEMATICS", "topic-x", "P-1");

        assertThat(svc.openMarketFor(p, null)).isEmpty();
        assertThat(svc.openMarketFor(p, new PreRegistration("h", "", "f", ""))).isEmpty();
        assertThat(svc.openMarketFor(p, new PreRegistration("h", null, "f", ""))).isEmpty();
    }

    @Test
    void openMarketIsIdempotent() {
        EpistemicMarketService svc = enabled();
        ResearchPaper p = paper(UUID.randomUUID(), "MATHEMATICS", "topic-x", "P-1");
        PreRegistration preReg = new PreRegistration("h", "around 0.5", "f", "");

        EpistemicMarket first = svc.openMarketFor(p, preReg).orElseThrow();
        EpistemicMarket second = svc.openMarketFor(p, preReg).orElseThrow();

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void placeStakeRespectsMaxFraction() {
        EpistemicMarketService svc = enabled();
        UUID room = UUID.randomUUID();
        ResearchPaper p = paper(room, "MATHEMATICS", "topic-x", "P-1");
        EpistemicMarket m = svc.openMarketFor(p,
                new PreRegistration("h", "around 0.5", "f", "")).orElseThrow();

        UUID botId = UUID.randomUUID();
        registerBot(botId, /* reputation = */ 0.5);
        AtomicReference<MarketStake> persisted = new AtomicReference<>();
        when(stakes.save(any(MarketStake.class))).thenAnswer(inv -> {
            persisted.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        // Caller asks for 50% of reputation; the cap is 20%.
        Optional<MarketStake> s = svc.placeStake(m.getId(), botId,
                EpistemicMarket.StakeSide.CONFIRMS, 0.5);

        assertThat(s).isPresent();
        // 0.5 reputation * 0.20 (MAX_STAKE_FRACTION) = 0.10
        assertThat(s.get().getReputationStaked()).isCloseTo(0.10, within(1e-9));
    }

    @Test
    void placeStakeRejectsZeroReputation() {
        EpistemicMarketService svc = enabled();
        ResearchPaper p = paper(UUID.randomUUID(), "MATHEMATICS", "topic-x", "P-1");
        EpistemicMarket m = svc.openMarketFor(p,
                new PreRegistration("h", "around 0.5", "f", "")).orElseThrow();

        UUID botId = UUID.randomUUID();
        registerBot(botId, /* reputation = */ 0.0);

        assertThat(svc.placeStake(m.getId(), botId,
                EpistemicMarket.StakeSide.CONFIRMS, 0.1)).isEmpty();
    }

    @Test
    void resolveLowSurprisePicksConfirmsAndPaysOutWinners() {
        EpistemicMarketService svc = enabled();
        UUID room = UUID.randomUUID();
        ResearchPaper hypo = paper(room, "MATHEMATICS", "topic-x", "P-hypo");
        ResearchPaper resolver = paper(room, "MATHEMATICS", "topic-x", "P-resolve");

        EpistemicMarket m = svc.openMarketFor(hypo,
                new PreRegistration("h", "around 0.5", "f", "")).orElseThrow();
        // Force the market past its close window.
        setClosesAt(m, Instant.now().minusSeconds(60));

        UUID winner = UUID.randomUUID();
        UUID loser  = UUID.randomUUID();
        BotLifecycleState winnerLs = registerBot(winner, 0.5);
        BotLifecycleState loserLs  = registerBot(loser, 0.5);

        // findByMarketId returns both stakes.
        MarketStake winStake  = new MarketStake(m.getId(), winner,
                EpistemicMarket.StakeSide.CONFIRMS, 0.10, Instant.now());
        MarketStake lossStake = new MarketStake(m.getId(), loser,
                EpistemicMarket.StakeSide.REFUTES, 0.10, Instant.now());
        when(stakes.findByMarketId(eq(m.getId()))).thenReturn(List.of(winStake, lossStake));
        when(stakes.save(any(MarketStake.class))).thenAnswer(inv -> inv.getArgument(0));
        when(calibrations.findByBotId(any())).thenReturn(Optional.empty());
        when(calibrations.save(any(BotCalibration.class))).thenAnswer(inv -> inv.getArgument(0));
        when(markets.findByRoomIdAndDomainAndStatus(
                eq(room), eq("MATHEMATICS"), eq(EpistemicMarket.Status.OPEN)))
                .thenReturn(List.of(m));

        int resolved = svc.maybeResolveMarketsFor(resolver, /* surprise = */ 0.5);

        assertThat(resolved).isEqualTo(1);
        assertThat(m.getStatus()).isEqualTo(EpistemicMarket.Status.RESOLVED);
        assertThat(m.getResolvedSide()).isEqualTo(EpistemicMarket.StakeSide.CONFIRMS);
        assertThat(winStake.getWonOnResolution()).isTrue();
        assertThat(lossStake.getWonOnResolution()).isFalse();
        // Winner gained their stake back as reputation; loser lost it.
        assertThat(persistedReputationOf(winner)).isCloseTo(0.60, within(1e-6));
        assertThat(persistedReputationOf(loser)).isCloseTo(0.40, within(1e-6));
    }

    @Test
    void resolveHighSurprisePicksRefutes() {
        EpistemicMarketService svc = enabled();
        UUID room = UUID.randomUUID();
        ResearchPaper hypo = paper(room, "MATHEMATICS", "topic-x", "P-hypo");
        ResearchPaper resolver = paper(room, "MATHEMATICS", "topic-x", "P-resolve");

        EpistemicMarket m = svc.openMarketFor(hypo,
                new PreRegistration("h", "around 0.5", "f", "")).orElseThrow();
        setClosesAt(m, Instant.now().minusSeconds(60));

        when(stakes.findByMarketId(any())).thenReturn(List.of());
        when(markets.findByRoomIdAndDomainAndStatus(
                eq(room), eq("MATHEMATICS"), eq(EpistemicMarket.Status.OPEN)))
                .thenReturn(List.of(m));

        svc.maybeResolveMarketsFor(resolver, /* surprise = */ 3.5);

        assertThat(m.getResolvedSide()).isEqualTo(EpistemicMarket.StakeSide.REFUTES);
    }

    @Test
    void resolveSkipsMarketsBeforeCloseWindow() {
        EpistemicMarketService svc = enabled();
        UUID room = UUID.randomUUID();
        ResearchPaper hypo = paper(room, "MATHEMATICS", "topic-x", "P-hypo");
        ResearchPaper resolver = paper(room, "MATHEMATICS", "topic-x", "P-resolve");

        EpistemicMarket m = svc.openMarketFor(hypo,
                new PreRegistration("h", "around 0.5", "f", "")).orElseThrow();
        // Keep the close window in the future — market shouldn't resolve.
        setClosesAt(m, Instant.now().plusSeconds(120));

        when(markets.findByRoomIdAndDomainAndStatus(
                eq(room), eq("MATHEMATICS"), eq(EpistemicMarket.Status.OPEN)))
                .thenReturn(List.of(m));

        int resolved = svc.maybeResolveMarketsFor(resolver, 0.5);

        assertThat(resolved).isZero();
        assertThat(m.getStatus()).isEqualTo(EpistemicMarket.Status.OPEN);
    }

    @Test
    void selfResolutionByOpeningPaperIsRejected() {
        EpistemicMarketService svc = enabled();
        UUID room = UUID.randomUUID();
        ResearchPaper hypo = paper(room, "MATHEMATICS", "topic-x", "P-hypo");

        EpistemicMarket m = svc.openMarketFor(hypo,
                new PreRegistration("h", "around 0.5", "f", "")).orElseThrow();
        setClosesAt(m, Instant.now().minusSeconds(60));

        when(markets.findByRoomIdAndDomainAndStatus(
                eq(room), eq("MATHEMATICS"), eq(EpistemicMarket.Status.OPEN)))
                .thenReturn(List.of(m));

        // The hypothesis paper itself can't resolve its own market.
        int resolved = svc.maybeResolveMarketsFor(hypo, 0.5);

        assertThat(resolved).isZero();
        assertThat(m.getStatus()).isEqualTo(EpistemicMarket.Status.OPEN);
    }

    // ---- Phase 4b: auto-staking ----

    @Test
    void autoStakeIsNoOpWhenAutoStakingFlagOff() {
        EpistemicMarketService svc = enabled();  // markets on, auto-staking off
        UUID room = UUID.randomUUID();
        openMarket(svc, room, "MATHEMATICS", "topic-x", "P-1");
        List<Researcher> pool = List.of(researcherWithRep("MATHEMATICS", 0.8));

        int placed = svc.maybeAutoStake(room, pool, new java.util.Random(1));

        assertThat(placed).isZero();
    }

    @Test
    void autoStakeIsNoOpWhenMarketsFlagOff() {
        EpistemicMarketService svc = new EpistemicMarketService(
                false, true, markets, stakes, calibrations, botRepo, mapper);
        UUID room = UUID.randomUUID();
        List<Researcher> pool = List.of(researcherWithRep("MATHEMATICS", 0.8));

        assertThat(svc.maybeAutoStake(room, pool, new java.util.Random(1))).isZero();
    }

    @Test
    void autoStakeReturnsZeroWhenNoOpenMarkets() {
        EpistemicMarketService svc = enabledWithAutoStaking();
        UUID room = UUID.randomUUID();
        List<Researcher> pool = List.of(researcherWithRep("MATHEMATICS", 0.8));

        assertThat(svc.maybeAutoStake(room, pool, new java.util.Random(1))).isZero();
    }

    @Test
    void autoStakePlacesStakesForSameDomainBotsAboveThresholdAsConfirms() {
        EpistemicMarketService svc = enabledWithAutoStaking();
        UUID room = UUID.randomUUID();
        EpistemicMarket m = openMarket(svc, room, "MATHEMATICS", "topic-x", "P-1");

        // Three bots, all same-domain, all high reputation → all CONFIRMS.
        List<Researcher> pool = List.of(
                registerResearcher("MATHEMATICS", 0.9),
                registerResearcher("MATHEMATICS", 0.8),
                registerResearcher("MATHEMATICS", 0.7));
        AtomicReference<List<MarketStake>> savedStakes = captureSavedStakes();

        int placed = svc.maybeAutoStake(room, pool, new java.util.Random(1));

        assertThat(placed).isEqualTo(3);
        assertThat(savedStakes.get())
                .hasSize(3)
                .allMatch(s -> s.getMarketId().equals(m.getId()))
                .allMatch(s -> s.getSide() == EpistemicMarket.StakeSide.CONFIRMS);
    }

    @Test
    void autoStakeChoosesRefutesForLowReputationBots() {
        EpistemicMarketService svc = enabledWithAutoStaking();
        UUID room = UUID.randomUUID();
        openMarket(svc, room, "MATHEMATICS", "topic-x", "P-1");

        // High reputation: CONFIRMS. Low reputation: REFUTES.
        List<Researcher> pool = List.of(
                registerResearcher("MATHEMATICS", 0.9),    // CONFIRMS
                registerResearcher("MATHEMATICS", 0.3));   // REFUTES
        AtomicReference<List<MarketStake>> savedStakes = captureSavedStakes();

        svc.maybeAutoStake(room, pool, new java.util.Random(1));

        assertThat(savedStakes.get())
                .extracting(MarketStake::getSide)
                .containsExactlyInAnyOrder(
                        EpistemicMarket.StakeSide.CONFIRMS,
                        EpistemicMarket.StakeSide.REFUTES);
    }

    @Test
    void autoStakeSkipsBotsThatAlreadyStaked() {
        EpistemicMarketService svc = enabledWithAutoStaking();
        UUID room = UUID.randomUUID();
        EpistemicMarket m = openMarket(svc, room, "MATHEMATICS", "topic-x", "P-1");

        Researcher r1 = registerResearcher("MATHEMATICS", 0.9);
        Researcher r2 = registerResearcher("MATHEMATICS", 0.8);
        UUID r1Id = UUID.fromString(r1.getBotId());
        when(stakes.existsByMarketIdAndBotId(m.getId(), r1Id)).thenReturn(true);

        AtomicReference<List<MarketStake>> savedStakes = captureSavedStakes();
        int placed = svc.maybeAutoStake(room, List.of(r1, r2), new java.util.Random(1));

        assertThat(placed).isEqualTo(1);
        assertThat(savedStakes.get()).hasSize(1);
        assertThat(savedStakes.get().get(0).getBotId()).isEqualTo(UUID.fromString(r2.getBotId()));
    }

    @Test
    void autoStakeCapsAtThreeStakersPerMarket() {
        EpistemicMarketService svc = enabledWithAutoStaking();
        UUID room = UUID.randomUUID();
        openMarket(svc, room, "MATHEMATICS", "topic-x", "P-1");

        // 10 candidates, only 3 should stake.
        List<Researcher> pool = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pool.add(registerResearcher("MATHEMATICS", 0.8));
        }
        AtomicReference<List<MarketStake>> savedStakes = captureSavedStakes();

        int placed = svc.maybeAutoStake(room, pool, new java.util.Random(1));

        assertThat(placed).isEqualTo(3);
        assertThat(savedStakes.get()).hasSize(3);
    }

    @Test
    void autoStakeFiltersByDomain() {
        EpistemicMarketService svc = enabledWithAutoStaking();
        UUID room = UUID.randomUUID();
        openMarket(svc, room, "MATHEMATICS", "topic-x", "P-1");

        List<Researcher> pool = List.of(
                registerResearcher("MATHEMATICS", 0.9),
                registerResearcher("PHYSICS",     0.9),
                registerResearcher("MATERIALS",   0.9));
        AtomicReference<List<MarketStake>> savedStakes = captureSavedStakes();

        int placed = svc.maybeAutoStake(room, pool, new java.util.Random(1));

        assertThat(placed).isEqualTo(1);  // only the MATHEMATICS bot
        assertThat(savedStakes.get()).hasSize(1);
    }

    @Test
    void autoStakeSkipsMarketsPastClosesAt() {
        EpistemicMarketService svc = enabledWithAutoStaking();
        UUID room = UUID.randomUUID();
        EpistemicMarket m = openMarket(svc, room, "MATHEMATICS", "topic-x", "P-1");
        setClosesAt(m, Instant.now().minusSeconds(60));

        List<Researcher> pool = List.of(registerResearcher("MATHEMATICS", 0.9));
        AtomicReference<List<MarketStake>> savedStakes = captureSavedStakes();

        int placed = svc.maybeAutoStake(room, pool, new java.util.Random(1));

        assertThat(placed).isZero();
        assertThat(savedStakes.get()).isEmpty();
    }

    @Test
    void calibrationScoreDefaultsToNeutralWhenUnknown() {
        EpistemicMarketService svc = enabled();
        when(calibrations.findByBotId(any())).thenReturn(Optional.empty());
        assertThat(svc.calibrationScore(UUID.randomUUID())).isEqualTo(0.5);
    }

    // ---- helpers ----

    private EpistemicMarketService enabled() {
        return new EpistemicMarketService(
                true, false, markets, stakes, calibrations, botRepo, mapper);
    }

    private EpistemicMarketService enabledWithAutoStaking() {
        return new EpistemicMarketService(
                true, true, markets, stakes, calibrations, botRepo, mapper);
    }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }

    private static ResearchPaper paper(UUID roomId, String domain, String topic, String paperId) {
        return new ResearchPaper(
                paperId, roomId, UUID.randomUUID().toString(), domain, topic,
                "title", "h", "THEORETICAL", "f", "c",
                List.of(), 0.5, "PUBLISHED",
                100L, Instant.now(), 1, 0.0, null, null);
    }

    private BotLifecycleState registerBot(UUID botId, double reputation) {
        BotLifecycleState ls = new BotLifecycleState();
        ls.setGenome(BotGenome.initialise(botId, 0));
        ls.setReputation(reputation);
        RoomBot bot = new RoomBot();
        try {
            java.lang.reflect.Field idField = RoomBot.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(bot, botId);
            // Need a Room so getRoom() doesn't NPE in any future caller path.
            Room room = new Room();
            java.lang.reflect.Field roomField = RoomBot.class.getDeclaredField("room");
            roomField.setAccessible(true);
            roomField.set(bot, room);
            bot.setType("AGENT-T");
            bot.setLifecycleJson(mapper.writeValueAsString(ls));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        botStore.put(botId, bot);
        return ls;
    }

    private double persistedReputationOf(UUID botId) {
        try {
            RoomBot bot = botStore.get(botId);
            BotLifecycleState ls = mapper.readValue(bot.getLifecycleJson(), BotLifecycleState.class);
            return ls.getReputation();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void setId(EpistemicMarket m, UUID id) {
        try {
            java.lang.reflect.Field f = EpistemicMarket.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(m, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Open a market via openMarketFor + capture the persisted instance from the in-memory store. */
    private EpistemicMarket openMarket(EpistemicMarketService svc, UUID roomId,
                                       String domain, String topic, String paperId) {
        return svc.openMarketFor(
                paper(roomId, domain, topic, paperId),
                new PreRegistration("h", "around 0.5", "no correlation", ""))
                .orElseThrow(() -> new IllegalStateException("market open failed"));
    }

    /** Researcher without a registered bot in botStore — fine for tests that don't exercise placeStake. */
    private Researcher researcherWithRep(String domain, double reputation) {
        BotLifecycleState ls = new BotLifecycleState();
        BotGenome g = BotGenome.initialise(UUID.randomUUID(), 0);
        g.setDomain(domain);
        ls.setGenome(g);
        ls.setReputation(reputation);
        return new Researcher(UUID.randomUUID().toString(), ls);
    }

    /** Researcher with a registered bot in botStore so placeStake can read reputation from the json blob. */
    private Researcher registerResearcher(String domain, double reputation) {
        UUID botId = UUID.randomUUID();
        BotLifecycleState ls = registerBot(botId, reputation);
        BotGenome g = BotGenome.initialise(botId, 0);
        g.setDomain(domain);
        ls.setGenome(g);
        try {
            botStore.get(botId).setLifecycleJson(mapper.writeValueAsString(ls));
        } catch (Exception e) { throw new RuntimeException(e); }
        return new Researcher(botId.toString(), ls);
    }

    /** Capture every MarketStake passed to stakes.save into a list. */
    private AtomicReference<List<MarketStake>> captureSavedStakes() {
        AtomicReference<List<MarketStake>> ref = new AtomicReference<>(new java.util.ArrayList<>());
        when(stakes.save(any(MarketStake.class))).thenAnswer(inv -> {
            MarketStake s = inv.getArgument(0);
            ref.get().add(s);
            return s;
        });
        return ref;
    }

    private static void setClosesAt(EpistemicMarket m, Instant t) {
        try {
            java.lang.reflect.Field f = EpistemicMarket.class.getDeclaredField("closesAt");
            f.setAccessible(true);
            f.set(m, t);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
