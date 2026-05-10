package com.emergentterrain.research;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for ReplicationService — the queue + dispute marking that runs when an
 * anomalous paper (|z| ≥ 3.0) is queued and a non-author replicator re-runs the
 * deterministic computation.
 *
 * No Spring, no DB. Repositories are mocked; a paper-lookup callback returns
 * the same in-memory paper instance the test asserts on, mirroring how
 * UniversityService wires its cache-aware lookup in production.
 */
class ReplicationServiceTest {

    private ResearchPaperRepository papersRepo;
    private RoomBotRepository       botRepo;
    private ObjectMapper            mapper;
    private ReplicationService      service;

    @BeforeEach
    void setUp() {
        papersRepo = mock(ResearchPaperRepository.class);
        botRepo    = mock(RoomBotRepository.class);
        mapper     = new ObjectMapper().findAndRegisterModules();
        service    = new ReplicationService(papersRepo, botRepo, mapper);
    }

    @Test
    void enqueueIncreasesPendingCount() {
        UUID room = UUID.randomUUID();
        assertThat(service.pendingCount()).isZero();

        service.enqueue(req(room, "P-test-1", 50, 5.0, 0.42));
        assertThat(service.pendingCount()).isEqualTo(1);

        service.enqueue(req(room, "P-test-2", 60, 5.5, 0.55));
        assertThat(service.pendingCount()).isEqualTo(2);
    }

    @Test
    void processOneSkipsWhenQueueIsEmpty() {
        UUID room = UUID.randomUUID();
        service.processOne(room, List.of(researcher("bot-1")), new Random(1), 0.5);
        // No-op — no exception, no save calls.
        verify(papersRepo, never()).save(any());
    }

    @Test
    void processOneSkipsWhenHeadIsForDifferentRoom() {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        service.enqueue(req(roomA, "P-aaaa-1", 50, 5.0, 0.5));

        // Asking for roomB with the head queued for roomA is a no-op; head stays.
        service.processOne(roomB, List.of(researcher("bot-1")), new Random(1), 0.5);
        assertThat(service.pendingCount()).isEqualTo(1);
        verify(papersRepo, never()).save(any());
    }

    @Test
    void replicationWithinThresholdLeavesPaperUntouched() {
        UUID room = UUID.randomUUID();
        ResearchPaper paper = paper(room, "P-aaaa-1", "bot-author", "MATHEMATICS",
                "Prime Constellation Search", 0.50);
        service.setPaperLookup(id -> Optional.of(paper));

        // Original score 0.50 ≈ replicator's score with their own genome on the same
        // domain/topic/params — under the 0.15 threshold, no dispute fires.
        Researcher replicator = researcher("bot-replicator");
        // Score-diff is (originalScore - replicaScore); here we set originalScore close
        // to whatever the deterministic compute yields. We check the *behaviour*: paper
        // status stays unchanged when the gap is small. See the diverged variant below
        // for the failure case.
        service.enqueue(new ReplicationService.ReplicationRequest(
                room, "P-aaaa-1", "MATHEMATICS", 0, 50, 5.0,
                /* originalScore = */ replicaScore(replicator, "MATHEMATICS", 0, 50, 5.0)));

        service.processOne(room, List.of(replicator), new Random(1), 0.5);

        assertThat(paper.status()).isEqualTo("PUBLISHED");
        // No save call because no mutation happened.
        verify(papersRepo, never()).save(any());
    }

    @Test
    void replicationDivergedBeyondThresholdMarksPaperDisputed() {
        UUID room = UUID.randomUUID();
        ResearchPaper paper = paper(room, "P-bbbb-1", "bot-author", "MATHEMATICS",
                "Prime Constellation Search", 0.10);
        service.setPaperLookup(id -> Optional.of(paper));

        AtomicReference<ResearchPaper> disputed = new AtomicReference<>();
        service.setDisputeListener(disputed::set);

        Researcher replicator = researcher("bot-replicator");
        // Set originalScore far from what the replicator would actually produce so the
        // diff exceeds the 0.15 threshold and the paper flips to DISPUTED.
        service.enqueue(new ReplicationService.ReplicationRequest(
                room, "P-bbbb-1", "MATHEMATICS", 0, 50, 5.0,
                /* originalScore = */ 0.95));

        service.processOne(room, List.of(replicator), new Random(1), 0.5);

        assertThat(paper.status()).isEqualTo("DISPUTED");
        assertThat(paper.conclusion()).contains("REPLICATION NOTE");
        assertThat(disputed.get()).isSameAs(paper);
        verify(papersRepo).save(paper);
    }

    @Test
    void replicationOnMissingPaperIsTolerated() {
        UUID room = UUID.randomUUID();
        // Lookup returns empty — paper was deleted/never persisted. The cycle should
        // not throw or save.
        service.setPaperLookup(id -> Optional.empty());
        // Score gap large so the dispute path would fire if the paper were present.
        service.enqueue(new ReplicationService.ReplicationRequest(
                room, "P-nonexistent", "MATHEMATICS", 0, 50, 5.0, 0.95));

        service.processOne(room, List.of(researcher("bot-1")), new Random(1), 0.5);
        // No save — cycle bailed cleanly.
        verify(papersRepo, never()).save(any());
    }

    // ---- helpers ----

    private static ReplicationService.ReplicationRequest req(UUID room, String paperId,
                                                             int p1, double p2,
                                                             double original) {
        return new ReplicationService.ReplicationRequest(
                room, paperId, "MATHEMATICS", 0, p1, p2, original);
    }

    private static double replicaScore(Researcher replicator, String domain, int topicIndex,
                                       int param1, double param2) {
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.from(
                replicator.getState().getGenome(), 0.5);
        MathPhysicsEngine.ResearchResult result = MathPhysicsEngine.compute(
                domain, topicIndex, param1, param2, sp);
        return MathPhysicsEngine.scoreResult(domain, topicIndex, result.reviewValue(), sp);
    }

    private static Researcher researcher(String botId) {
        BotLifecycleState ls = new BotLifecycleState();
        ls.setGenome(BotGenome.initialise(UUID.randomUUID(), 0));
        return new Researcher(botId, ls);
    }

    private static ResearchPaper paper(UUID room, String paperId, String authorBotId,
                                       String domain, String topic, double peerScore) {
        return new ResearchPaper(
                paperId, room, authorBotId, domain, topic,
                "Test Paper Title", "test hypothesis", "THEORETICAL",
                "test findings", "test conclusion",
                List.of(), peerScore, "PUBLISHED",
                100L, Instant.now(), 1, 0.0, null, null);
    }
}
