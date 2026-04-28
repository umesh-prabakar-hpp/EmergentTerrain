package com.emergentterrain.research;

import com.emergentterrain.domain.RoomBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Per-room university simulation. Each room maintains a population of Researcher bots
 * that select topics based on their genome, perform real computations via MathPhysicsEngine,
 * undergo deterministic peer review, and accumulate publications.
 *
 * Genetic evolution kicks in every GENERATION_SIZE papers: tournament selection, crossover,
 * and mutation gradually improve the population's research fitness.
 */
@Service
@ConditionalOnProperty(name = "app.university.enabled", havingValue = "true", matchIfMissing = false)
public class UniversityService {

    private static final Logger log = LoggerFactory.getLogger(UniversityService.class);

    private static final int POPULATION_SIZE     = 12;
    private static final int REVIEWERS_PER_PAPER = 2;
    private static final int GENERATION_SIZE     = 20;   // papers before evolution cycle
    private static final int MAX_PAPERS_PER_ROOM = 500;  // ring buffer ceiling
    private static final int TOURNAMENT_K        = 3;
    private static final double EVOLVE_REPLACE_FRACTION = 0.20;

    // Peer review: |result1 - result2| / max(|result1|, 1e-9) must be below this
    private static final double REVIEW_TOLERANCE = 1e-6;

    private final long researchIntervalMs;

    // Per-room state
    private final ConcurrentHashMap<UUID, List<Researcher>> researchers     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<ResearchPaper>> published    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long>              lastTickAt     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer>           papersSinceEvo = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long>              paperCounter   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer>           totalRejected  = new ConcurrentHashMap<>();

    public UniversityService(
            @Value("${app.university.researchIntervalMs:15000}") long researchIntervalMs) {
        this.researchIntervalMs = researchIntervalMs;
    }

    // ---- Public API ----

    /**
     * Called from the room maintenance loop. Runs at most one research cycle per interval.
     * Initialises the researcher population from the bot list on first call.
     */
    public void maybeTick(UUID roomId, List<RoomBot> bots, long gameTick) {
        long now = System.currentTimeMillis();
        Long last = lastTickAt.get(roomId);
        if (last != null && now - last < researchIntervalMs) return;
        lastTickAt.put(roomId, now);

        ensurePopulation(roomId, bots);
        List<Researcher> pop = researchers.get(roomId);
        if (pop == null || pop.isEmpty()) return;

        Random rng = new Random(gameTick * 1337L + roomId.getLeastSignificantBits());
        runResearchCycle(roomId, pop, rng, gameTick);
    }

    public List<ResearchPaper> getPublishedPapers(UUID roomId, int limit) {
        List<ResearchPaper> all = published.get(roomId);
        if (all == null || all.isEmpty()) return List.of();
        List<ResearchPaper> copy = new ArrayList<>(all);
        // most recent first
        int fromIndex = Math.max(0, copy.size() - limit);
        List<ResearchPaper> slice = new ArrayList<>(copy.subList(fromIndex, copy.size()));
        Collections.reverse(slice);
        return slice;
    }

    public Map<String, Object> getStats(UUID roomId) {
        List<Researcher> pop = researchers.getOrDefault(roomId, List.of());
        List<ResearchPaper> papers = published.getOrDefault(roomId, List.of());

        Map<String, Long> byDomain = papers.stream()
                .collect(Collectors.groupingBy(ResearchPaper::domain, Collectors.counting()));

        OptionalDouble avgScore = papers.stream().mapToDouble(ResearchPaper::peerScore).average();
        int maxGen = pop.stream().mapToInt(Researcher::getGeneration).max().orElse(0);
        double avgReputation = pop.stream().mapToDouble(Researcher::getReputation).average().orElse(0);
        int totalRej = totalRejected.getOrDefault(roomId, 0);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalPublished", papers.size());
        stats.put("totalRejected", totalRej);
        stats.put("researcherCount", pop.size());
        stats.put("maxGeneration", maxGen);
        stats.put("avgReputation", Math.round(avgReputation * 1000.0) / 1000.0);
        stats.put("avgPeerScore", avgScore.isPresent() ? Math.round(avgScore.getAsDouble() * 1000.0) / 1000.0 : 0.0);
        stats.put("byDomain", byDomain);
        return stats;
    }

    // ---- Core research cycle ----

    private void runResearchCycle(UUID roomId, List<Researcher> pop, Random rng, long gameTick) {
        // Pick researcher by weighted tournament on fitness
        Researcher author = tournamentSelect(pop, rng);
        int topicIndex    = author.selectTopic(rng);
        String domain     = author.getDomain();

        // Derive params deterministically from gameTick + author id so peer reviewers can reproduce
        long seed = gameTick * 997L + author.getBotId().hashCode();
        int    param1 = 10 + (int) Math.abs(seed % 90);          // integer range 10–99
        double param2 = 1.0 + Math.abs((seed >> 16) % 9);        // 1–9

        MathPhysicsEngine.ResearchResult result = MathPhysicsEngine.compute(domain, topicIndex, param1, param2);

        // Simulate author error
        boolean authorError = rng.nextDouble() < author.errorRate();
        double reportedPrimary = authorError
                ? result.primaryValue() * (1 + (rng.nextDouble() - 0.5) * 0.3)
                : result.primaryValue();

        // Peer review: two independent reviewers re-run same computation
        List<String> reviewerIds = new ArrayList<>();
        boolean reviewPassed = true;
        double totalReviewScore = 0;
        int reviewCount = 0;

        for (int i = 0; i < REVIEWERS_PER_PAPER && reviewPassed; i++) {
            Researcher reviewer = pickReviewer(pop, author, rng);
            reviewerIds.add(reviewer.getBotId());
            boolean reviewerError = rng.nextDouble() < reviewer.errorRate();

            MathPhysicsEngine.ResearchResult reviewResult = MathPhysicsEngine.compute(domain, topicIndex, param1, param2);
            double reviewPrimary = reviewerError
                    ? reviewResult.primaryValue() * (1 + (rng.nextDouble() - 0.5) * 0.15)
                    : reviewResult.primaryValue();

            double relDiff = Math.abs(reportedPrimary - reviewPrimary)
                    / Math.max(Math.abs(reviewPrimary), 1e-9);
            boolean agrees = relDiff < REVIEW_TOLERANCE || (!authorError && !reviewerError);
            if (!agrees) reviewPassed = false;

            double score = reviewer.getReputation() * (agrees ? 1.0 : 0.3);
            totalReviewScore += score;
            reviewCount++;
        }

        double peerScore = reviewCount > 0 ? totalReviewScore / reviewCount : 0;
        String status    = reviewPassed ? "PUBLISHED" : "REJECTED";

        if (reviewPassed) {
            String paperId = "P-" + roomId.toString().substring(0, 4)
                    + "-" + paperCounter.merge(roomId, 1L, Long::sum);

            String title      = MathPhysicsEngine.generateTitle(domain, topicIndex, rng);
            String hypothesis = MathPhysicsEngine.generateHypothesis(domain, topicIndex, param1, param2);
            String conclusion = MathPhysicsEngine.generateConclusion(result, true);
            String topicName  = MathPhysicsEngine.topicName(domain, topicIndex);

            ResearchPaper paper = new ResearchPaper(
                    paperId, author.getBotId(), domain, topicName,
                    title, hypothesis, author.getMethodology(),
                    result.findings(), conclusion,
                    Collections.unmodifiableList(reviewerIds),
                    peerScore, status, gameTick, Instant.now(), author.getGeneration());

            List<ResearchPaper> roomPapers = published.computeIfAbsent(roomId, k -> new ArrayList<>());
            synchronized (roomPapers) {
                roomPapers.add(paper);
                if (roomPapers.size() > MAX_PAPERS_PER_ROOM) {
                    roomPapers.remove(0);
                }
            }
            author.recordPublished(peerScore);
            log.debug("university published paperId={} domain={} topic={} author={} score={}",
                    paperId, domain, topicName, author.getBotId(), peerScore);
        } else {
            author.recordRejected();
            totalRejected.merge(roomId, 1, Integer::sum);
            log.debug("university rejected domain={} topicIndex={} author={}", domain, topicIndex, author.getBotId());
        }

        // Reward reviewers slightly for their service
        for (String rid : reviewerIds) {
            pop.stream().filter(r -> r.getBotId().equals(rid)).findFirst()
               .ifPresent(r -> r.recordPublished(0.1));
        }

        // Check if it's time for a genetic evolution cycle
        int papers = papersSinceEvo.merge(roomId, 1, Integer::sum);
        if (papers >= GENERATION_SIZE) {
            papersSinceEvo.put(roomId, 0);
            evolvePopulation(pop, rng);
        }
    }

    // ---- Genetic evolution ----

    private void evolvePopulation(List<Researcher> pop, Random rng) {
        int replaceCount = Math.max(1, (int) (pop.size() * EVOLVE_REPLACE_FRACTION));

        // Sort ascending by fitness; lowest fitness candidates are replaced
        pop.sort(Comparator.comparingDouble(Researcher::fitness));

        List<Researcher> children = new ArrayList<>();
        for (int i = 0; i < replaceCount; i++) {
            Researcher parentA = tournamentSelect(pop, rng);
            Researcher parentB = tournamentSelect(pop, rng);
            String childId = "R-evo-" + rng.nextInt(100000);
            Researcher child = Researcher.crossover(parentA, parentB, childId, rng);
            child.mutate(rng);
            children.add(child);
        }

        for (int i = 0; i < replaceCount; i++) {
            pop.set(i, children.get(i));
        }
        log.debug("university evolution: replaced {} researchers", replaceCount);
    }

    private Researcher tournamentSelect(List<Researcher> pop, Random rng) {
        Researcher best = null;
        for (int i = 0; i < TOURNAMENT_K; i++) {
            Researcher candidate = pop.get(rng.nextInt(pop.size()));
            if (best == null || candidate.fitness() > best.fitness()) {
                best = candidate;
            }
        }
        return best;
    }

    private Researcher pickReviewer(List<Researcher> pop, Researcher author, Random rng) {
        List<Researcher> candidates = pop.stream()
                .filter(r -> !r.getBotId().equals(author.getBotId()))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) return pop.get(rng.nextInt(pop.size()));
        // Prefer same domain for better peer review quality
        List<Researcher> sameDomain = candidates.stream()
                .filter(r -> r.getDomain().equals(author.getDomain()))
                .collect(Collectors.toList());
        List<Researcher> pool = sameDomain.isEmpty() ? candidates : sameDomain;
        return pool.get(rng.nextInt(pool.size()));
    }

    // ---- Population initialisation ----

    private void ensurePopulation(UUID roomId, List<RoomBot> bots) {
        if (researchers.containsKey(roomId)) return;
        List<Researcher> pop = new ArrayList<>();
        String[] domains = Researcher.DOMAINS;
        Random rng = new Random(roomId.getLeastSignificantBits());
        int target = Math.max(POPULATION_SIZE, Math.min(bots.size(), POPULATION_SIZE * 2));
        for (int i = 0; i < target; i++) {
            String domain  = domains[i % domains.length];
            String botId   = (i < bots.size()) ? bots.get(i).getId().toString() : "R-" + i;
            pop.add(new Researcher("R-" + botId.substring(0, Math.min(8, botId.length())), domain, rng));
        }
        researchers.put(roomId, pop);
        log.info("university initialised roomId={} researchers={}", roomId, pop.size());
    }
}
