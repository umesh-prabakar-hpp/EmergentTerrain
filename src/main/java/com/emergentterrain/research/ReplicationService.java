package com.emergentterrain.research;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Replication queue + dispute marking. Carved out of UniversityService in
 * Phase 0 Task 0.3b.
 *
 * Anomalous papers (|z| ≥ {@link #ANOMALY_REPLICATION_Z}) are enqueued by
 * UniversityService at publish time. Each tick, UniversityService asks this
 * service to process at most one queued request for the room: pick a
 * non-author replicator, re-run the deterministic computation with the
 * replicator's genome, and compare the new score to the original.
 *
 * If the score gap exceeds {@link #REPLICATION_FAIL_THRESHOLD}, the original
 * paper is marked DISPUTED, the conclusion is appended with a replication
 * note, the original author's reputation takes a {@code -0.08} hit, and the
 * dispute listener fires (UniversityService uses it to keep the markdown
 * file in sync).
 *
 * The paper-lookup callback is the seam that lets UniversityService keep its
 * in-memory cache in sync: by looking up papers through the lookup, we mutate
 * the same object reference the cache holds. Without this, the DB would be
 * updated but the cache would remain stale.
 */
@Service
public class ReplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReplicationService.class);

    /** z-score threshold (per (domain, topic) distribution) above which a paper is queued. */
    public static final double ANOMALY_REPLICATION_Z = 3.0;

    /** Score gap that triggers DISPUTED status when the replication score diverges. */
    private static final double REPLICATION_FAIL_THRESHOLD = 0.15;

    public record ReplicationRequest(UUID roomId, String paperId, String domain,
                                     int topicIndex, int param1, double param2,
                                     double originalScore) {}

    private final ResearchPaperRepository papersRepository;
    private final RoomBotRepository botRepository;
    private final ObjectMapper objectMapper;

    private final ConcurrentLinkedQueue<ReplicationRequest> queue = new ConcurrentLinkedQueue<>();

    /**
     * Cache-aware paper lookup. UniversityService sets this to consult its
     * in-memory cache first so mutations propagate without a re-read.
     */
    private volatile Function<String, Optional<ResearchPaper>> paperLookup;

    /** Side-effect listener for paper-dispute events (markdown rewrite, etc.). */
    private volatile Consumer<ResearchPaper> disputeListener = p -> {};

    /**
     * Phase 5 C2 — optional reconciliation listener receiving (disputedPaper,
     * replicatorBotId). UniversityService wires it to queue a typed
     * {@link ProgramOpenQuestion} on both bots' programs so the next paper
     * from either author must address the divergent peer scores.
     */
    private volatile java.util.function.BiConsumer<ResearchPaper, String>
            reconciliationListener = (p, r) -> {};

    /**
     * Phase 4b — per-room sliding window of recent replication outcomes. Each entry
     * is true when the replication failed (paper marked DISPUTED), false when it
     * confirmed. {@link ExplorationTuningService} reads {@link #recentFailureRate}
     * to decide whether the anomaly z-threshold should be tightened or loosened.
     * Capped at {@link #FAILURE_WINDOW_SIZE} per room — older outcomes drop off.
     */
    private static final int FAILURE_WINDOW_SIZE = 30;
    private final java.util.concurrent.ConcurrentMap<UUID, java.util.Deque<Boolean>>
            recentOutcomesByRoom = new java.util.concurrent.ConcurrentHashMap<>();

    public ReplicationService(ResearchPaperRepository papersRepository,
                              RoomBotRepository botRepository,
                              ObjectMapper objectMapper) {
        this.papersRepository = papersRepository;
        this.botRepository    = botRepository;
        this.objectMapper     = objectMapper;
        this.paperLookup      = papersRepository::findByPaperId;
    }

    /**
     * Phase 4b — fraction of recent replications in this room that failed
     * (paper marked DISPUTED). Returns -1.0 when the window has fewer than
     * {@link #FAILURE_WINDOW_MIN_SAMPLES} samples — callers should fall back
     * to the static threshold during cold-start.
     */
    public static final int FAILURE_WINDOW_MIN_SAMPLES = 10;
    public double recentFailureRate(UUID roomId) {
        if (roomId == null) return -1.0;
        java.util.Deque<Boolean> window = recentOutcomesByRoom.get(roomId);
        if (window == null) return -1.0;
        synchronized (window) {
            if (window.size() < FAILURE_WINDOW_MIN_SAMPLES) return -1.0;
            long failed = window.stream().filter(Boolean::booleanValue).count();
            return (double) failed / window.size();
        }
    }

    /** For tests: how many outcomes are currently tracked for this room. */
    public int recentOutcomeCount(UUID roomId) {
        if (roomId == null) return 0;
        java.util.Deque<Boolean> w = recentOutcomesByRoom.get(roomId);
        if (w == null) return 0;
        synchronized (w) { return w.size(); }
    }

    private void recordOutcome(UUID roomId, boolean failed) {
        if (roomId == null) return;
        java.util.Deque<Boolean> window = recentOutcomesByRoom.computeIfAbsent(
                roomId, k -> new java.util.ArrayDeque<>());
        synchronized (window) {
            window.addLast(failed);
            while (window.size() > FAILURE_WINDOW_SIZE) window.removeFirst();
        }
    }

    public void setPaperLookup(Function<String, Optional<ResearchPaper>> lookup) {
        this.paperLookup = lookup != null ? lookup : papersRepository::findByPaperId;
    }

    public void setDisputeListener(Consumer<ResearchPaper> listener) {
        this.disputeListener = listener != null ? listener : p -> {};
    }

    /** Phase 5 C2 — optional reconciliation listener; defaults to a no-op. */
    public void setReconciliationListener(java.util.function.BiConsumer<ResearchPaper, String> listener) {
        this.reconciliationListener = listener != null ? listener : (p, r) -> {};
    }

    public void enqueue(ReplicationRequest request) {
        queue.offer(request);
    }

    public int pendingCount() {
        return queue.size();
    }

    /**
     * Process at most one queued replication request whose room matches the given
     * roomId. No-op when the queue is empty or the head is for a different room.
     *
     * @return true if a request was processed (regardless of pass/fail outcome),
     *         false when the queue head didn't match this room. Callers use the
     *         return value to update {@code ReplicationCouncil} counters.
     */
    public boolean processOne(UUID roomId, List<Researcher> pool, Random rng,
                              double educationFactor) {
        ReplicationRequest req = queue.peek();
        if (req == null || !req.roomId().equals(roomId)) return false;
        queue.poll();

        String originalAuthor = paperLookup.apply(req.paperId())
                .map(ResearchPaper::authorBotId).orElse("");

        Researcher replicator = pool.stream()
                .filter(r -> !r.getBotId().equals(originalAuthor))
                .findFirst().orElse(pool.get(rng.nextInt(pool.size())));

        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.from(
                replicator.getState().getGenome(), educationFactor);
        MathPhysicsEngine.ResearchResult replicaResult = MathPhysicsEngine.compute(
                req.domain(), req.topicIndex(), req.param1(), req.param2(), sp);
        double replicaScore = MathPhysicsEngine.scoreResult(req.domain(), req.topicIndex(),
                replicaResult.reviewValue(), sp);

        double scoreDiff = Math.abs(req.originalScore() - replicaScore);
        boolean failed = scoreDiff > REPLICATION_FAIL_THRESHOLD;
        log.info("university replication paperId={} original={} replica={} diff={} failed={}",
                req.paperId(), req.originalScore(), replicaScore, scoreDiff, failed);

        if (failed) {
            markPaperDisputed(req.paperId(), replicator.getBotId(), replicaScore);
        }
        recordOutcome(req.roomId(), failed);
        return true;
    }

    private void markPaperDisputed(String paperId, String replicatorBotId, double replicaScore) {
        ResearchPaper paper = paperLookup.apply(paperId).orElse(null);
        if (paper == null) return;

        String note = " [REPLICATION NOTE: Independent replication by bot-"
                + replicatorBotId.substring(0, 6)
                + " produced score " + String.format("%.3f", replicaScore)
                + " vs original " + String.format("%.3f", paper.peerScore())
                + " — result is under dispute.]";
        paper.setStatus("DISPUTED");
        paper.setConclusion(paper.conclusion() + note);
        // Phase 21 §21.2 — persist the replicator bot id so the orphan-scan
        // sweep can re-ping the replicator side too instead of refreshing
        // only the original author (the comment at UniversityService.java
        // line 1815 cited this gap as the reason 17 IDs stayed orphaned for
        // ~10 sweep cycles in the a3289005 corpus).
        paper.setDisputeReplicatorBotId(replicatorBotId);
        try {
            papersRepository.save(paper);
        } catch (Exception e) {
            log.warn("university failed to persist DISPUTED status paperId={}: {}",
                    paperId, e.getMessage());
        }

        // Side-effect: e.g. UniversityService overwrites the markdown file.
        disputeListener.accept(paper);

        // Phase 5 C2 — emit a typed ProgramOpenQuestion on both bots' programs
        // (no-op when the flag is off or no listener is wired).
        reconciliationListener.accept(paper, replicatorBotId);

        // Penalise original author's reputation.
        try {
            UUID authorId = UUID.fromString(paper.authorBotId());
            botRepository.findById(authorId).ifPresent(bot -> {
                String json = bot.getLifecycleJson();
                if (json == null || json.isBlank()) return;
                try {
                    BotLifecycleState ls = objectMapper.readValue(json, BotLifecycleState.class);
                    ls.setReputation(Math.max(0.0, ls.getReputation() - 0.08));
                    bot.setLifecycleJson(objectMapper.writeValueAsString(ls));
                    botRepository.save(bot);
                } catch (Exception e) {
                    log.warn("dispute reputation penalty failed: {}", e.getMessage());
                }
            });
        } catch (IllegalArgumentException ignored) {}
        log.info("university paper DISPUTED paperId={}", paperId);
    }
}
