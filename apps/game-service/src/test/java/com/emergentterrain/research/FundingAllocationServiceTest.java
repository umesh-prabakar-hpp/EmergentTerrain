package com.emergentterrain.research;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.BotCalibrationRepository;
import com.emergentterrain.repo.EpistemicMarketRepository;
import com.emergentterrain.repo.FundingAgencyRepository;
import com.emergentterrain.repo.GrantRepository;
import com.emergentterrain.repo.MarketStakeRepository;
import com.emergentterrain.repo.PaperCitationRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.UniversityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
 * Tests for the Phase 4b FundingAllocationService.
 */
class FundingAllocationServiceTest {

    private FundingAgencyRepository  agencies;
    private UniversityRepository     universities;
    private GrantRepository          grants;
    private RoomBotRepository        botRepo;
    private MetaResearchService      meta;
    private ObjectMapper             mapper;

    @BeforeEach
    void setUp() {
        agencies     = mock(FundingAgencyRepository.class);
        universities = mock(UniversityRepository.class);
        grants       = mock(GrantRepository.class);
        botRepo      = mock(RoomBotRepository.class);
        mapper       = new ObjectMapper().findAndRegisterModules();
        meta         = new MetaResearchService(false,
                mock(ResearchPaperRepository.class),
                mock(PaperCitationRepository.class),
                new EpistemicMarketService(false, false,
                        mock(EpistemicMarketRepository.class),
                        mock(MarketStakeRepository.class),
                        mock(BotCalibrationRepository.class),
                        botRepo, mapper));
    }

    @Test
    void disabledServiceAllocatesNothing() {
        FundingAllocationService svc = new FundingAllocationService(
                false, agencies, universities, grants, botRepo, meta, mapper);

        Grant g = svc.maybeAllocate(UUID.randomUUID(), govStateWithEducation(100_000), 100L);

        assertThat(g).isNull();
        verify(grants, never()).save(any());
        verify(universities, never()).save(any());
    }

    @Test
    void noAgencyMeansNoAllocation() {
        FundingAllocationService svc = enabled();
        UUID room = UUID.randomUUID();
        when(agencies.findFirstByRoomId(room)).thenReturn(Optional.empty());

        Grant g = svc.maybeAllocate(room, govStateWithEducation(100_000), 100L);

        assertThat(g).isNull();
        verify(grants, never()).save(any());
    }

    @Test
    void noUniversityMeansNoAllocation() {
        FundingAllocationService svc = enabled();
        UUID room = UUID.randomUUID();
        when(agencies.findFirstByRoomId(room))
                .thenReturn(Optional.of(agency(room)));
        when(universities.findFirstByRoomId(room)).thenReturn(Optional.empty());

        assertThat(svc.maybeAllocate(room, govStateWithEducation(100_000), 100L)).isNull();
    }

    @Test
    void firstAllocationFiresThenCadenceThrottles() {
        FundingAllocationService svc = enabled();
        UUID room = UUID.randomUUID();
        FundingAgency agency = agency(room);
        University    uni    = university(room);
        when(agencies.findFirstByRoomId(room)).thenReturn(Optional.of(agency));
        when(universities.findFirstByRoomId(room)).thenReturn(Optional.of(uni));
        when(agencies.save(any(FundingAgency.class))).thenAnswer(inv -> inv.getArgument(0));
        when(universities.save(any(University.class))).thenAnswer(inv -> inv.getArgument(0));
        when(grants.save(any(Grant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(botRepo.findByRoom_IdAndStatus(room, RoomBot.BotStatus.ACTIVE))
                .thenReturn(List.of(bot(room, 0.5)));

        Grant first = svc.maybeAllocate(room, govStateWithEducation(100_000), 100L);
        assertThat(first).isNotNull();

        // Same call again well within the 30-tick cadence — throttled.
        Grant throttled = svc.maybeAllocate(room, govStateWithEducation(100_000), 110L);
        assertThat(throttled).isNull();
    }

    @Test
    void allocationAfterCadenceFiresAgain() {
        FundingAllocationService svc = enabled();
        UUID room = UUID.randomUUID();
        FundingAgency agency = agency(room);
        University    uni    = university(room);
        when(agencies.findFirstByRoomId(room)).thenReturn(Optional.of(agency));
        when(universities.findFirstByRoomId(room)).thenReturn(Optional.of(uni));
        when(agencies.save(any(FundingAgency.class))).thenAnswer(inv -> inv.getArgument(0));
        when(universities.save(any(University.class))).thenAnswer(inv -> inv.getArgument(0));
        when(grants.save(any(Grant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(botRepo.findByRoom_IdAndStatus(room, RoomBot.BotStatus.ACTIVE))
                .thenReturn(List.of(bot(room, 0.5)));

        assertThat(svc.maybeAllocate(room, govStateWithEducation(100_000), 100L)).isNotNull();
        // 30 ticks later → fires again.
        assertThat(svc.maybeAllocate(room, govStateWithEducation(100_000), 130L)).isNotNull();
    }

    @Test
    void topUpAddsFractionOfEducationBudget() {
        FundingAllocationService svc = enabled();
        UUID room = UUID.randomUUID();
        FundingAgency agency = agency(room);
        agency.setAvailableFunds(0);
        University uni = university(room);
        AtomicReference<FundingAgency> savedAgency = new AtomicReference<>();
        when(agencies.findFirstByRoomId(room)).thenReturn(Optional.of(agency));
        when(universities.findFirstByRoomId(room)).thenReturn(Optional.of(uni));
        when(agencies.save(any(FundingAgency.class))).thenAnswer(inv -> {
            savedAgency.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(universities.save(any(University.class))).thenAnswer(inv -> inv.getArgument(0));
        when(grants.save(any(Grant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(botRepo.findByRoom_IdAndStatus(room, RoomBot.BotStatus.ACTIVE))
                .thenReturn(List.of(bot(room, 0.5)));

        // 1% of 100_000 = 1000 topped up before allocation.
        Grant g = svc.maybeAllocate(room, govStateWithEducation(100_000), 100L);

        assertThat(g).isNotNull();
        // The agency was topped up to 1000 then drained by `1000 * score`. Score
        // for a single bot with reputation 0.5 + neutral calibration + zero
        // hallucinationRate is 0.34*0.5 + 0.33*0.5 + 0.33*1.0 = 0.665.
        assertThat(g.getAmount()).isCloseTo(665.0, within(0.5));
        assertThat(savedAgency.get().getAvailableFunds()).isCloseTo(335.0, within(0.5));
    }

    @Test
    void grantBreakdownReflectsPolicyWeights() {
        FundingAllocationService svc = enabled();
        UUID room = UUID.randomUUID();
        FundingAgency agency = agency(room);
        University    uni    = university(room);
        when(agencies.findFirstByRoomId(room)).thenReturn(Optional.of(agency));
        when(universities.findFirstByRoomId(room)).thenReturn(Optional.of(uni));
        when(agencies.save(any(FundingAgency.class))).thenAnswer(inv -> inv.getArgument(0));
        when(universities.save(any(University.class))).thenAnswer(inv -> inv.getArgument(0));
        AtomicReference<Grant> saved = new AtomicReference<>();
        when(grants.save(any(Grant.class))).thenAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(botRepo.findByRoom_IdAndStatus(room, RoomBot.BotStatus.ACTIVE))
                .thenReturn(List.of(bot(room, 0.8)));

        svc.maybeAllocate(room, govStateWithEducation(100_000), 100L);

        Grant g = saved.get();
        assertThat(g).isNotNull();
        // trackRecord = 0.34 * 0.8 = 0.272; calibration = 0.33 * 0.5 = 0.165;
        // novelty = 0.33 * 1.0 = 0.33; combined = 0.767
        assertThat(g.getTrackRecordComponent()).isCloseTo(0.272, within(1e-3));
        assertThat(g.getCalibrationComponent()).isCloseTo(0.165, within(1e-3));
        assertThat(g.getNoveltyComponent()).isCloseTo(0.33,  within(1e-3));
        assertThat(g.getScoreUsed()).isCloseTo(0.767, within(1e-3));
    }

    @Test
    void zeroEducationBudgetSkipsAllocation() {
        FundingAllocationService svc = enabled();
        UUID room = UUID.randomUUID();
        FundingAgency agency = agency(room);
        agency.setAvailableFunds(0);
        University uni = university(room);
        when(agencies.findFirstByRoomId(room)).thenReturn(Optional.of(agency));
        when(universities.findFirstByRoomId(room)).thenReturn(Optional.of(uni));
        when(botRepo.findByRoom_IdAndStatus(any(), any())).thenReturn(List.of(bot(room, 0.5)));

        Grant g = svc.maybeAllocate(room, govStateWithEducation(0), 100L);

        assertThat(g).isNull();
        verify(grants, never()).save(any());
    }

    // ---- helpers ----

    private FundingAllocationService enabled() {
        return new FundingAllocationService(
                true, agencies, universities, grants, botRepo, meta, mapper);
    }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }

    private static FundingAgency agency(UUID room) {
        FundingAgency a = new FundingAgency(
                room, "Agency", null, Instant.now());
        try {
            java.lang.reflect.Field f = FundingAgency.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        return a;
    }

    private static University university(UUID room) {
        University u = new University(room, "U", null, Instant.now());
        try {
            java.lang.reflect.Field f = University.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, UUID.randomUUID());
        } catch (Exception e) { throw new RuntimeException(e); }
        return u;
    }

    private RoomBot bot(UUID roomId, double reputation) {
        BotLifecycleState ls = new BotLifecycleState();
        ls.setGenome(BotGenome.initialise(UUID.randomUUID(), 0));
        ls.setReputation(reputation);

        RoomBot bot = new RoomBot();
        try {
            java.lang.reflect.Field idField = RoomBot.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(bot, UUID.randomUUID());

            Room room = new Room();
            java.lang.reflect.Field roomIdField = Room.class.getDeclaredField("id");
            roomIdField.setAccessible(true);
            roomIdField.set(room, roomId);
            java.lang.reflect.Field roomField = RoomBot.class.getDeclaredField("room");
            roomField.setAccessible(true);
            roomField.set(bot, room);

            bot.setType("AGENT-T");
            bot.setLifecycleJson(mapper.writeValueAsString(ls));
        } catch (Exception e) { throw new RuntimeException(e); }
        return bot;
    }

    private static GovernmentState govStateWithEducation(long education) {
        GovernmentState s = new GovernmentState();
        GovernmentState.GovernmentNode federal = new GovernmentState.GovernmentNode();
        GovernmentState.BudgetState budget = new GovernmentState.BudgetState();
        budget.setEducation(education);
        federal.setBudget(budget);
        s.setFederal(federal);
        return s;
    }
}
