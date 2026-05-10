package com.emergentterrain.research;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.RoomBotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bot lifecycle operations carved out of UniversityService in Phase 0 Task 0.3c:
 * load/persist of {@link BotLifecycleState}, competency testing, elder merges,
 * dating, and researcher-pool construction.
 *
 * State machine reminder (preserved exactly from the original code):
 *   - JUVENILE → ADULT at age = JUVENILE_TO_ADULT
 *   - ADULT → ELDER at age = adultToElder (dynamic, from PopulationEcologyService)
 *   - QUALIFIED is set by competency tests every TERM_PAPERS publications
 *   - ELDER pairs with lowest fitness merge into ADULT (one merge per tick)
 *   - ADULT pairs date based on explorationRate × DATING_BASE_PROB (one pair per tick)
 *
 * The phase doc flagged this state machine as the highest-risk extraction, so the
 * methods are moved verbatim — only the surface (the methods are now public on a
 * separate service) changes.
 */
@Service
public class BotLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(BotLifecycleService.class);

    /** Competency-test pass threshold (0..1). Used to flip UNQUALIFIED ↔ QUALIFIED. */
    private static final double COMPETENCY_THRESHOLD = 0.60;

    /** Base dating probability — multiplied by both parents' explorationRate. */
    private static final double DATING_BASE_PROB = 0.05;

    private final RoomBotRepository botRepository;
    private final ObjectMapper objectMapper;

    public BotLifecycleService(RoomBotRepository botRepository, ObjectMapper objectMapper) {
        this.botRepository = botRepository;
        this.objectMapper  = objectMapper;
    }

    // ---- State load / persist ----

    /**
     * Load lifecycle state for each bot, initialising any bot that doesn't have one yet.
     * Bots with a blank lifecycleJson are pre-system bots — start them as ADULT so they
     * can research immediately rather than spending JUVENILE_TO_ADULT ticks idle.
     */
    public Map<UUID, BotLifecycleState> loadStates(List<RoomBot> bots, long gameTick) {
        Map<UUID, BotLifecycleState> states = new LinkedHashMap<>();
        int domainIdx = 0;
        for (RoomBot bot : bots) {
            boolean isNew = bot.getLifecycleJson() == null || bot.getLifecycleJson().isBlank();
            BotLifecycleState ls = readLifecycleState(bot);
            if (ls.getGenome() == null) {
                ls.setGenome(BotGenome.initialise(bot.getId(), domainIdx));
            }
            if (isNew) {
                ls.setAgeTicks(BotLifecycleState.JUVENILE_TO_ADULT);
                ls.setLifecycle(BotLifecycleState.Phase.ADULT);
            }
            states.put(bot.getId(), ls);
            domainIdx++;
        }
        return states;
    }

    /**
     * Load lifecycle state without applying the "pre-system bot starts as ADULT" rule.
     * Used by read-only paths (e.g. getStats) where we don't want to mutate.
     */
    public Map<UUID, BotLifecycleState> loadStatesQuiet(List<RoomBot> bots) {
        Map<UUID, BotLifecycleState> states = new LinkedHashMap<>();
        int idx = 0;
        for (RoomBot bot : bots) {
            BotLifecycleState ls = readLifecycleState(bot);
            if (ls.getGenome() == null) ls.setGenome(BotGenome.initialise(bot.getId(), idx));
            states.put(bot.getId(), ls);
            idx++;
        }
        return states;
    }

    private BotLifecycleState readLifecycleState(RoomBot bot) {
        String json = bot.getLifecycleJson();
        if (json == null || json.isBlank()) return new BotLifecycleState();
        try {
            return objectMapper.readValue(json, BotLifecycleState.class);
        } catch (Exception e) {
            log.warn("Failed to parse lifecycleJson for bot={}: {}", bot.getId(), e.getMessage());
            return new BotLifecycleState();
        }
    }

    /** Serialise each state back into bot.lifecycleJson. Caller is responsible for saveAll. */
    public void persistStates(List<RoomBot> bots, Map<UUID, BotLifecycleState> states) {
        for (RoomBot bot : bots) {
            BotLifecycleState ls = states.get(bot.getId());
            if (ls != null) {
                bot.setLifecycleJson(toJson(ls));
            }
        }
    }

    // ---- Competency testing ----

    /**
     * Every TERM_PAPERS publications, all non-juvenile bots face the same deterministic
     * puzzle. Pass → QUALIFIED, fail → UNQUALIFIED. Only QUALIFIED bots author papers.
     */
    public void runCompetencyTests(List<RoomBot> bots, Map<UUID, BotLifecycleState> states,
                                   long gameTick, List<RoomBot> dirty) {
        String[] domains = BotGenome.DOMAINS;
        String testDomain = domains[(int) (gameTick % domains.length)];
        int topicIndex    = (int) ((gameTick / 3) % 5);
        int param1        = 10 + (int) Math.abs(gameTick % 90);
        double param2     = 1.0 + Math.abs((gameTick >> 16) % 9);

        Random rng = new Random(gameTick);
        for (RoomBot bot : bots) {
            BotLifecycleState ls = states.get(bot.getId());
            if (ls.getLifecycle() == BotLifecycleState.Phase.JUVENILE) continue;

            double score = runCompetencyPuzzle(ls.getGenome(), testDomain, topicIndex, param1, param2, rng);
            ls.setLastCompetencyScore(score);
            ls.setLastCompetencyTick(gameTick);
            ls.setResearchStatus(score >= COMPETENCY_THRESHOLD
                    ? BotLifecycleState.ResearchStatus.QUALIFIED
                    : BotLifecycleState.ResearchStatus.UNQUALIFIED);
        }
        log.info("university competency test domain={} topic={} bots={}",
                testDomain, topicIndex, bots.size());
    }

    private double runCompetencyPuzzle(BotGenome genome, String domain, int topicIndex,
                                       int param1, double param2, Random rng) {
        if (genome == null) return 0.0;
        MathPhysicsEngine.SearchParams sp = MathPhysicsEngine.SearchParams.from(genome);
        MathPhysicsEngine.ResearchResult result =
                MathPhysicsEngine.compute(domain, topicIndex, param1, param2, sp);
        double score = MathPhysicsEngine.scoreResult(domain, topicIndex, result.reviewValue(), sp);
        return Math.max(0.0, score + rng.nextGaussian() * 0.05);
    }

    // ---- Elder merges ----

    /**
     * Higher-fitness elder absorbs the lowest-fitness elder's genome and resets to ADULT.
     * One merge per tick; the absorbed bot is RETIRED.
     */
    public void processElderMerges(List<RoomBot> bots, Map<UUID, BotLifecycleState> states,
                                   List<RoomBot> dirty, long gameTick) {
        List<RoomBot> elders = bots.stream()
                .filter(b -> states.get(b.getId()).getLifecycle() == BotLifecycleState.Phase.ELDER)
                .collect(Collectors.toList());
        if (elders.size() < 2) return;

        elders.sort(Comparator.comparingDouble(b -> states.get(b.getId()).fitness()));

        RoomBot botB = elders.get(0);  // lowest fitness — will be retired
        RoomBot botA = elders.get(1);  // survives, absorbs B

        BotLifecycleState stateA = states.get(botA.getId());
        BotLifecycleState stateB = states.get(botB.getId());

        BotGenome merged = BotGenome.merge(stateA.getGenome(), stateB.getGenome());
        stateA.setGenome(merged);
        stateA.setAgeTicks(BotLifecycleState.JUVENILE_TO_ADULT);
        stateA.setLifecycle(BotLifecycleState.Phase.ADULT);

        botB.setStatus(RoomBot.BotStatus.RETIRED);
        log.info("university merge: botA={} absorbed botB={} at tick={}",
                botA.getId(), botB.getId(), gameTick);
    }

    // ---- Dating ----

    /**
     * Adult bots with no recent dating attempt may pair up to spawn a child via genome
     * crossover + mutation. One pair per tick. Skipped when population is at or above K.
     */
    public void processDating(List<RoomBot> bots, Map<UUID, BotLifecycleState> states,
                              List<RoomBot> dirty, long gameTick,
                              PopulationEcologyService.PopulationParams params) {
        if (bots.size() >= params.K()) return;

        long cooldown = params.datingCooldown();
        List<RoomBot> datingCandidates = bots.stream()
                .filter(b -> {
                    BotLifecycleState ls = states.get(b.getId());
                    return ls.getLifecycle() == BotLifecycleState.Phase.ADULT
                            && (ls.getLastDatedAt() < 0 || gameTick - ls.getLastDatedAt() > cooldown);
                })
                .collect(Collectors.toList());
        if (datingCandidates.size() < 2) return;

        Random rng = new Random(gameTick ^ 0xDA7E5EEDL);
        Collections.shuffle(datingCandidates, rng);

        RoomBot botA = datingCandidates.get(0);
        RoomBot botB = datingCandidates.get(1);
        BotLifecycleState sA = states.get(botA.getId());
        BotLifecycleState sB = states.get(botB.getId());

        double datingProb = sA.getGenome().getExplorationRate()
                          * sB.getGenome().getExplorationRate()
                          * DATING_BASE_PROB;
        if (rng.nextDouble() > datingProb) return;

        BotGenome childGenome = BotGenome.crossover(
                sA.getGenome(), sB.getGenome(), rng, sA.fitness(), sB.fitness());
        childGenome.mutate(rng);

        BotLifecycleState childState = new BotLifecycleState();
        childState.setGenome(childGenome);
        childState.setReputation(0.1);

        RoomBot child = new RoomBot();
        child.setRoom(botA.getRoom());
        child.setType("AGENT-C" + gameTick);
        child.setStatus(RoomBot.BotStatus.ACTIVE);
        if (botA.getX() != null && botB.getX() != null) {
            child.setX((botA.getX() + botB.getX()) / 2);
            child.setY((botA.getY() + botB.getY()) / 2);
        }
        child.setLifecycleJson(toJson(childState));
        botRepository.save(child);

        sA.setLastDatedAt(gameTick);
        sB.setLastDatedAt(gameTick);
        log.info("university dating: parents={},{} child={} at tick={}",
                botA.getId(), botB.getId(), child.getId(), gameTick);
    }

    // ---- Researcher pool ----

    /**
     * Build the per-tick researcher pool. Only QUALIFIED non-juvenile bots qualify;
     * if none are qualified yet (early game), fall back to all non-juveniles to keep
     * the cold-start running.
     */
    public List<Researcher> buildResearcherPool(List<RoomBot> bots,
                                                Map<UUID, BotLifecycleState> states) {
        List<Researcher> pool = new ArrayList<>();
        for (RoomBot bot : bots) {
            BotLifecycleState ls = states.get(bot.getId());
            if (ls.getLifecycle() == BotLifecycleState.Phase.JUVENILE) continue;
            if (ls.getResearchStatus() != BotLifecycleState.ResearchStatus.QUALIFIED) continue;
            pool.add(new Researcher(bot.getId().toString(), ls));
        }
        if (pool.isEmpty()) {
            for (RoomBot bot : bots) {
                BotLifecycleState ls = states.get(bot.getId());
                if (ls.getLifecycle() != BotLifecycleState.Phase.JUVENILE) {
                    pool.add(new Researcher(bot.getId().toString(), ls));
                }
            }
        }
        return pool;
    }

    // ---- Internal helpers ----

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise lifecycle state", e);
        }
    }
}
