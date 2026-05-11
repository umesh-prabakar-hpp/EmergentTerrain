package com.emergentterrain.research;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.FundingAgencyRepository;
import com.emergentterrain.repo.GrantRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.UniversityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 4b — the Phase 3 Iter 8 {@link FundingAgency} now actually allocates.
 *
 * Per allocation cycle (every {@link #ALLOCATION_INTERVAL_TICKS} game ticks):
 *   1. Top up the room's agency with a fraction of {@code BudgetState.education}
 *      so the funds pool tracks the simulation's investment level.
 *   2. Compute a weighted room-wide quality score from the agency's
 *      GrantPolicy weights × per-bot signals (reputation for trackRecord,
 *      EpistemicMarketService.calibrationScore via MetaResearchService for
 *      calibration, 1 - hallucinationRate for novelty).
 *   3. Transfer {@code availableFunds × score} from the agency to the room's
 *      single University as compute budget.
 *   4. Persist a {@link Grant} row capturing the amount + score breakdown.
 *
 * Gated by {@code app.research.funding-allocation.enabled} (default false).
 * No-op when:
 *   - the flag is off,
 *   - no FundingAgency exists for the room (Phase 3 Iter 8 institutions flag
 *     wasn't enabled when this room first published),
 *   - no University exists for the room,
 *   - the allocation interval hasn't elapsed since the last allocation.
 *
 * Self-paces via an in-memory {@code lastAllocationTickByRoom} map to keep
 * the call cheap when callers ask too often. The cadence survives restarts
 * because Grant rows record the last tick — but the in-memory map starts
 * empty after restart, which is fine: the first post-restart call always
 * allocates regardless of recent history.
 */
@Service
public class FundingAllocationService {

    private static final Logger log = LoggerFactory.getLogger(FundingAllocationService.class);

    /** Ticks between allocation cycles per room. Matches TERM_PAPERS so this fires
     *  on the same cadence as competency tests. */
    private static final long ALLOCATION_INTERVAL_TICKS = 30L;

    /** Fraction of BudgetState.education that tops up the agency per cycle. */
    private static final double TOP_UP_FRACTION = 0.01;

    private final boolean                  enabled;
    private final FundingAgencyRepository  agencies;
    private final UniversityRepository     universities;
    private final GrantRepository          grants;
    private final RoomBotRepository        botRepository;
    private final MetaResearchService      metaResearchService;
    private final ObjectMapper             objectMapper;

    private final ConcurrentHashMap<UUID, Long> lastAllocationTickByRoom = new ConcurrentHashMap<>();

    public FundingAllocationService(
            @Value("${app.research.funding-allocation.enabled:false}") boolean enabled,
            FundingAgencyRepository agencies,
            UniversityRepository universities,
            GrantRepository grants,
            RoomBotRepository botRepository,
            MetaResearchService metaResearchService,
            ObjectMapper objectMapper) {
        this.enabled              = enabled;
        this.agencies             = agencies;
        this.universities         = universities;
        this.grants               = grants;
        this.botRepository        = botRepository;
        this.metaResearchService  = metaResearchService;
        this.objectMapper         = objectMapper;
        if (enabled) {
            log.info("FundingAllocationService: ENABLED — agencies top up from "
                    + "BudgetState.education and allocate to universities every {} ticks "
                    + "based on (trackRecord, calibration, novelty) score.",
                    ALLOCATION_INTERVAL_TICKS);
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Allocate grants for the room if the cadence interval has elapsed. No-op
     * otherwise. Returns the persisted Grant when an allocation fired, or
     * null when this call was a cadence-throttled or guard-clause no-op.
     */
    public Grant maybeAllocate(UUID roomId, GovernmentState govState, long gameTick) {
        if (!enabled || roomId == null) return null;
        Long last = lastAllocationTickByRoom.get(roomId);
        if (last != null && gameTick - last < ALLOCATION_INTERVAL_TICKS) return null;

        FundingAgency agency = agencies.findFirstByRoomId(roomId).orElse(null);
        if (agency == null) return null;
        University university = universities.findFirstByRoomId(roomId).orElse(null);
        if (university == null) return null;

        topUp(agency, govState);
        if (agency.getAvailableFunds() <= 0) {
            // Mark this tick processed so a starved room doesn't retry every tick.
            lastAllocationTickByRoom.put(roomId, gameTick);
            return null;
        }

        ScoreBreakdown s = computeRoomScore(roomId, agency);
        double grantAmount = agency.getAvailableFunds() * s.combined;
        if (grantAmount <= 0) {
            lastAllocationTickByRoom.put(roomId, gameTick);
            return null;
        }

        agency.setAvailableFunds(agency.getAvailableFunds() - grantAmount);
        university.setComputeBudget(university.getComputeBudget() + grantAmount);
        try {
            agencies.save(agency);
            universities.save(university);
        } catch (Exception e) {
            log.warn("funding: agency/university save failed room={}: {}", roomId, e.getMessage());
            return null;
        }

        Grant grant = new Grant(agency.getId(), university.getId(), roomId,
                grantAmount, s.combined,
                s.trackRecordComponent, s.calibrationComponent, s.noveltyComponent,
                gameTick, Instant.now());
        try {
            grant = grants.save(grant);
        } catch (Exception e) {
            log.warn("funding: grant persist failed room={}: {}", roomId, e.getMessage());
        }
        lastAllocationTickByRoom.put(roomId, gameTick);
        log.info("funding: ALLOCATED roomId={} universityId={} amount={} score={} (tr={} cal={} nov={})",
                roomId, university.getId(),
                String.format("%.3f", grantAmount), String.format("%.3f", s.combined),
                String.format("%.3f", s.trackRecordComponent),
                String.format("%.3f", s.calibrationComponent),
                String.format("%.3f", s.noveltyComponent));
        return grant;
    }

    // ---- internal ----

    private record ScoreBreakdown(double combined,
                                  double trackRecordComponent,
                                  double calibrationComponent,
                                  double noveltyComponent) {}

    private ScoreBreakdown computeRoomScore(UUID roomId, FundingAgency agency) {
        List<RoomBot> bots = botRepository.findByRoom_IdAndStatus(
                roomId, RoomBot.BotStatus.ACTIVE);
        if (bots.isEmpty()) return new ScoreBreakdown(0, 0, 0, 0);

        double sumRep = 0, sumCal = 0, sumNov = 0;
        int count = 0;
        for (RoomBot bot : bots) {
            BotLifecycleState ls = readLifecycle(bot);
            if (ls == null) continue;
            double rep = ls.getReputation();
            MetaResearchService.BotMetaStats stats = metaResearchService.statsForBot(bot.getId());
            double cal = stats.calibrationScore();
            double nov = 1.0 - stats.hallucinationRate();
            sumRep += rep;
            sumCal += cal;
            sumNov += nov;
            count++;
        }
        if (count == 0) return new ScoreBreakdown(0, 0, 0, 0);

        double meanRep = sumRep / count;
        double meanCal = sumCal / count;
        double meanNov = sumNov / count;
        double tr  = agency.getTrackRecordWeight()  * meanRep;
        double cb  = agency.getCalibrationWeight()  * meanCal;
        double nv  = agency.getNoveltyWeight()       * meanNov;
        return new ScoreBreakdown(tr + cb + nv, tr, cb, nv);
    }

    private BotLifecycleState readLifecycle(RoomBot bot) {
        String json = bot.getLifecycleJson();
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, BotLifecycleState.class);
        } catch (Exception e) {
            log.warn("funding: lifecycle parse failed bot={}: {}", bot.getId(), e.getMessage());
            return null;
        }
    }

    private void topUp(FundingAgency agency, GovernmentState govState) {
        if (govState == null || govState.getFederal() == null) return;
        GovernmentState.BudgetState budget = govState.getFederal().getBudget();
        if (budget == null) return;
        double topUp = budget.getEducation() * TOP_UP_FRACTION;
        if (topUp <= 0) return;
        agency.setAvailableFunds(agency.getAvailableFunds() + topUp);
    }
}
