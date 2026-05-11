package com.emergentterrain.research;

import com.emergentterrain.repo.BotCalibrationRepository;
import com.emergentterrain.repo.EpistemicMarketRepository;
import com.emergentterrain.repo.FormalFindingRepository;
import com.emergentterrain.repo.FundingAgencyRepository;
import com.emergentterrain.repo.JournalRepository;
import com.emergentterrain.repo.MarketStakeRepository;
import com.emergentterrain.repo.PaperCitationRepository;
import com.emergentterrain.repo.ProgramFailedApproachRepository;
import com.emergentterrain.repo.ProgramOpenQuestionRepository;
import com.emergentterrain.repo.ReplicationCouncilRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.ResearchProgramRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.UniversityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4b integration smoke test. The Phase 4b services each have unit tests
 * covering their behaviour in isolation; this test composes them with all
 * flags on and walks through a canonical multi-paper scenario to verify the
 * cross-service wiring is consistent: institutions bootstrap from publish,
 * journal acceptance recomputes impact factor, surprise events feed the
 * broadcast buffer, findings feed theory composition, replications feed both
 * the council counters and the exploration tuner, and per-room state stays
 * isolated.
 *
 * <p>Mocked repositories with in-memory maps stand in for the database — the
 * existing JPA integration tests are not used because they require a live
 * Postgres schema that isn't available in this environment.
 */
class Phase4bIntegrationSmokeTest {

    // ---- In-memory stores ----
    private final Map<UUID, University>          universityStore = new HashMap<>();
    private final Map<UUID, Journal>             journalStore    = new HashMap<>();
    private final Map<UUID, FundingAgency>       agencyStore     = new HashMap<>();
    private final Map<UUID, ReplicationCouncil>  councilStore    = new HashMap<>();
    private final Map<UUID, EpistemicMarket>     marketStore     = new HashMap<>();
    private final Map<UUID, MarketStake>         stakeStore      = new HashMap<>();
    private final Map<UUID, FormalFinding>       findingStore    = new HashMap<>();
    private final Map<String, ResearchPaper>     paperStore      = new HashMap<>();

    // ---- Mocks ----
    private UniversityRepository universities;
    private JournalRepository journals;
    private FundingAgencyRepository agencies;
    private ReplicationCouncilRepository councils;
    private EpistemicMarketRepository markets;
    private MarketStakeRepository stakes;
    private FormalFindingRepository findings;
    private ResearchPaperRepository papers;
    private PaperCitationRepository citations;
    private BotCalibrationRepository calibrations;
    private RoomBotRepository bots;

    // ---- Services (all Phase 4b flags ON) ----
    private InstitutionService          institutions;
    private EpistemicMarketService      marketsService;
    private KnowledgeBaseService        knowledge;
    private SurpriseBroadcastService    broadcast;
    private ResearchProgramService      programs;
    private ReplicationService          replication;
    private ExplorationTuningService    tuning;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        universityStore.clear(); journalStore.clear(); agencyStore.clear(); councilStore.clear();
        marketStore.clear(); stakeStore.clear(); findingStore.clear(); paperStore.clear();

        universities = mock(UniversityRepository.class);
        journals     = mock(JournalRepository.class);
        agencies     = mock(FundingAgencyRepository.class);
        councils     = mock(ReplicationCouncilRepository.class);
        markets      = mock(EpistemicMarketRepository.class);
        stakes       = mock(MarketStakeRepository.class);
        findings     = mock(FormalFindingRepository.class);
        papers       = mock(ResearchPaperRepository.class);
        citations    = mock(PaperCitationRepository.class);
        calibrations = mock(BotCalibrationRepository.class);
        bots         = mock(RoomBotRepository.class);

        // Wire repository save/find behaviour to the in-memory stores.
        wireUniversityRepo(); wireJournalRepo(); wireAgencyRepo(); wireCouncilRepo();
        wireMarketRepo(); wireStakeRepo(); wireFindingRepo(); wirePaperRepo();

        institutions = new InstitutionService(true, true, true, true,
                universities, journals, agencies, councils, papers, citations);
        marketsService = new EpistemicMarketService(true, true,
                markets, stakes, calibrations, bots, mapper);
        knowledge = new KnowledgeBaseService(true, true, findings);
        broadcast = new SurpriseBroadcastService(true, /* cooldownSeconds = */ 0);
        programs  = new ResearchProgramService(true,
                mock(ResearchProgramRepository.class),
                mock(ProgramOpenQuestionRepository.class),
                mock(ProgramFailedApproachRepository.class),
                papers);
        replication = new ReplicationService(papers, bots, mapper);
        tuning      = new ExplorationTuningService(true, replication);

        knowledge.setResearchProgramService(programs);
    }

    /**
     * Canonical happy path: bootstrap institutions, publish two papers in the
     * journal, accept them (impact factor recomputes), broadcast surprises,
     * compose theories from extracted findings, and run replications that
     * feed both the council counter and the tuner.
     */
    @Test
    void canonicalPhase4bScenarioExercisesAllServicesEndToEnd() {
        UUID room = UUID.randomUUID();

        // 1. Bootstrap institutions for the room (idempotent).
        InstitutionService.BootstrapResult br =
                institutions.bootstrapForPaper(room, "MATHEMATICS");
        assertThat(br).isNotNull();
        assertThat(br.university()).isNotNull();
        assertThat(br.journal()).isNotNull();
        assertThat(universityStore).hasSize(1);
        assertThat(journalStore).hasSize(1);
        assertThat(agencyStore).hasSize(1);
        assertThat(councilStore).hasSize(1);

        // Second call in same domain re-uses everything (no duplicates).
        institutions.bootstrapForPaper(room, "MATHEMATICS");
        assertThat(universityStore).hasSize(1);
        assertThat(journalStore).hasSize(1);

        // 2. Publish two papers attached to the journal, with citation counts.
        UUID journalId = br.journal().getId();
        ResearchPaper p1 = paper("P-1", room, "MATHEMATICS", journalId);
        ResearchPaper p2 = paper("P-2", room, "MATHEMATICS", journalId);
        paperStore.put(p1.paperId(), p1);
        paperStore.put(p2.paperId(), p2);
        when(papers.findByJournalId(journalId)).thenReturn(List.of(p1, p2));
        when(citations.countByCitedPaperId("P-1")).thenReturn(4L);
        when(citations.countByCitedPaperId("P-2")).thenReturn(2L);

        // 3. Journal acceptance recomputes impact factor = mean(4, 2) = 3.0.
        institutions.recordJournalAcceptance(journalId);
        Journal j = journalStore.get(journalId);
        assertThat(j.getImpactFactor()).isCloseTo(3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(j.getPapersAccepted()).isEqualTo(1);

        // 4. Broadcast a high-surprise event.
        SurpriseEvent surprise = new SurpriseEvent(
                "P-1", UUID.randomUUID(), room, "MATHEMATICS",
                0.5, 3.5, SurpriseEvent.Source.PRIOR, Instant.now());
        assertThat(broadcast.maybeBroadcast(surprise)).isTrue();
        assertThat(broadcast.recentBroadcasts(room, null, 5))
                .extracting(SurpriseEvent::getPaperId).containsExactly("P-1");

        // 5. Council triggers on a paper whose narration carries a CONTRADICTS typed citation.
        NarrationResult contradictsNarration = new NarrationResult(
                "h", "f", "c", null, "model", 0.8,
                List.of("P-1"), "audit",
                List.of(new NarrationResult.TypedCitation("P-1",
                        RelationshipType.CONTRADICTS, 0.9)));
        assertThat(institutions.councilWantsReplication(contradictsNarration)).isTrue();
        institutions.recordReplicationEnqueued(room);
        ReplicationCouncil council = councilStore.values().iterator().next();
        assertThat(council.getActiveReplicationCount()).isEqualTo(1);

        // 6. Persist findings and trigger theory composition: A~PROP(B), B~PROP(C) → A~PROP(C).
        FormalFinding f1 = finding("P-1", "MATHEMATICS", RelationType.PROPORTIONAL, "A", "B", 0.8);
        FormalFinding f2 = finding("P-2", "MATHEMATICS", RelationType.PROPORTIONAL, "B", "C", 0.8);
        findingStore.put(UUID.randomUUID(), f1);
        findingStore.put(UUID.randomUUID(), f2);
        // recordOpenQuestion is mocked — the composition call shouldn't NPE on null program.
        int emitted = knowledge.maybeComposeTheories("MATHEMATICS");
        assertThat(emitted).isEqualTo(1);  // one valid composition pair

        // 7. Run replications. Half succeed, half fail, exercising both branches of
        //    the tuner. ReplicationService.recordOutcome is package-private; drive it
        //    via reflection (same pathway as ExplorationTuningServiceTest).
        for (int i = 0; i < 10; i++) {
            recordReplicationOutcome(room, /* failed = */ i % 2 == 0);
            tuning.onReplicationProcessed(room);
            institutions.recordReplicationCompleted(room);
        }
        // Half of 10 = 50% failure rate — between LOW_FAILURE_FLOOR and HIGH_FAILURE_CEILING.
        // Wait — actually 0.5 > HIGH_FAILURE_CEILING (0.40), so tuner lowers thresholds.
        assertThat(tuning.getZThreshold(room))
                .isLessThan(ExplorationTuningService.DEFAULT_Z_THRESHOLD);
        // Council should have completed each enqueued replication tracked in step 5
        // plus all 10 from this loop (recordReplicationCompleted called once per loop).
        // The council was at active=1 (from step 5), now at active=1 - 10 (clamped to 0
        // by the entity setter) and completed=10.
        assertThat(council.getCompletedCount()).isEqualTo(10);
    }

    /**
     * Sanity check: per-room state is isolated. Bootstrap, broadcast, and
     * tuning state in roomA don't leak into roomB.
     */
    @Test
    void perRoomStateRemainsIsolatedAcrossRooms() {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();

        institutions.bootstrapForPaper(roomA, "MATHEMATICS");
        institutions.bootstrapForPaper(roomB, "PHYSICS");
        assertThat(universityStore).hasSize(2);
        assertThat(journalStore).hasSize(2);

        SurpriseEvent sA = new SurpriseEvent("P-A", UUID.randomUUID(), roomA,
                "MATHEMATICS", 0.5, 3.0, SurpriseEvent.Source.PRIOR, Instant.now());
        broadcast.maybeBroadcast(sA);
        assertThat(broadcast.recentBroadcasts(roomA, null, 5)).hasSize(1);
        assertThat(broadcast.recentBroadcasts(roomB, null, 5)).isEmpty();

        // RoomA gets all-failing replications, roomB gets all-confirming.
        for (int i = 0; i < 10; i++) {
            recordReplicationOutcome(roomA, true);
            tuning.onReplicationProcessed(roomA);
            recordReplicationOutcome(roomB, false);
            tuning.onReplicationProcessed(roomB);
        }
        // RoomA tuner lowers (catching anomalies), roomB tuner raises (wasted compute).
        assertThat(tuning.getZThreshold(roomA))
                .isLessThan(tuning.getZThreshold(roomB));
    }

    // ---- Repository wiring ----

    private void wireUniversityRepo() {
        when(universities.save(any(University.class))).thenAnswer(inv -> {
            University u = inv.getArgument(0);
            if (u.getId() == null) setField(u, "id", UUID.randomUUID());
            universityStore.put(u.getId(), u);
            return u;
        });
        when(universities.findFirstByRoomId(any())).thenAnswer(inv -> {
            UUID r = inv.getArgument(0);
            return universityStore.values().stream()
                    .filter(u -> r.equals(u.getRoomId())).findFirst();
        });
    }

    private void wireJournalRepo() {
        when(journals.save(any(Journal.class))).thenAnswer(inv -> {
            Journal j = inv.getArgument(0);
            if (j.getId() == null) setField(j, "id", UUID.randomUUID());
            journalStore.put(j.getId(), j);
            return j;
        });
        when(journals.findById(any())).thenAnswer(inv ->
                Optional.ofNullable(journalStore.get(inv.getArgument(0))));
        when(journals.findFirstByRoomIdAndFocusDomain(any(), any())).thenAnswer(inv -> {
            UUID r = inv.getArgument(0);
            String d = inv.getArgument(1);
            return journalStore.values().stream()
                    .filter(j -> r.equals(j.getRoomId()) && d.equals(j.getFocusDomain()))
                    .findFirst();
        });
    }

    private void wireAgencyRepo() {
        when(agencies.save(any(FundingAgency.class))).thenAnswer(inv -> {
            FundingAgency a = inv.getArgument(0);
            if (a.getId() == null) setField(a, "id", UUID.randomUUID());
            agencyStore.put(a.getId(), a);
            return a;
        });
        when(agencies.findFirstByRoomId(any())).thenAnswer(inv -> {
            UUID r = inv.getArgument(0);
            return agencyStore.values().stream()
                    .filter(a -> r.equals(a.getRoomId())).findFirst();
        });
    }

    private void wireCouncilRepo() {
        when(councils.save(any(ReplicationCouncil.class))).thenAnswer(inv -> {
            ReplicationCouncil c = inv.getArgument(0);
            if (c.getId() == null) setField(c, "id", UUID.randomUUID());
            councilStore.put(c.getId(), c);
            return c;
        });
        when(councils.findFirstByRoomId(any())).thenAnswer(inv -> {
            UUID r = inv.getArgument(0);
            return councilStore.values().stream()
                    .filter(c -> r.equals(c.getRoomId())).findFirst();
        });
    }

    private void wireMarketRepo() {
        when(markets.save(any(EpistemicMarket.class))).thenAnswer(inv -> {
            EpistemicMarket m = inv.getArgument(0);
            if (m.getId() == null) setField(m, "id", UUID.randomUUID());
            marketStore.put(m.getId(), m);
            return m;
        });
    }

    private void wireStakeRepo() {
        when(stakes.save(any(MarketStake.class))).thenAnswer(inv -> {
            MarketStake s = inv.getArgument(0);
            stakeStore.put(UUID.randomUUID(), s);
            return s;
        });
    }

    private void wireFindingRepo() {
        when(findings.save(any(FormalFinding.class))).thenAnswer(inv -> {
            FormalFinding f = inv.getArgument(0);
            if (f.getId() == null) setField(f, "id", UUID.randomUUID());
            findingStore.put(f.getId(), f);
            return f;
        });
        when(findings.findByDomain(any())).thenAnswer(inv -> {
            String d = inv.getArgument(0);
            return findingStore.values().stream()
                    .filter(f -> d.equals(f.getDomain())).toList();
        });
    }

    private void wirePaperRepo() {
        when(papers.findByPaperId(any())).thenAnswer(inv ->
                Optional.ofNullable(paperStore.get(inv.getArgument(0))));
    }

    // ---- Helpers ----

    private static void setField(Object obj, String name, Object value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void recordReplicationOutcome(UUID roomId, boolean failed) {
        try {
            java.lang.reflect.Method m = ReplicationService.class.getDeclaredMethod(
                    "recordOutcome", UUID.class, boolean.class);
            m.setAccessible(true);
            m.invoke(replication, roomId, failed);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ResearchPaper paper(String paperId, UUID roomId, String domain, UUID journalId) {
        ResearchPaper p = new ResearchPaper(
                paperId, roomId, UUID.randomUUID().toString(), domain, "topic-x",
                "title", "h", "THEORETICAL", "f", "c",
                new ArrayList<>(), 0.5, "PUBLISHED",
                100L, Instant.now(), 1, 0.0, null, null);
        p.setJournalId(journalId);
        return p;
    }

    private static FormalFinding finding(String paperId, String domain, RelationType relation,
                                         String dep, String indep, double confidence) {
        return new FormalFinding(
                paperId, UUID.randomUUID(), relation, dep, List.of(indep), null,
                MathPhysicsEngine.ClaimStatus.SUPPORTED, confidence,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                domain, Instant.now());
    }

    @SuppressWarnings("unused")
    private static Object unusedAny() { return eq(""); }
}
