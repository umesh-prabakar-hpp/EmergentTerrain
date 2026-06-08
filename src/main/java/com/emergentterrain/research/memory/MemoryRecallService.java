package com.emergentterrain.research.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Phase 31 §31.3 — the read path: index-first recall of compiled cross-run
 * lessons at formulation/narration time. No embeddings (the claude-memory-
 * compiler position: at ET's scale retrieval is curation, not vector search;
 * the schema reserves an embedding column for the deferred hybrid tier past
 * ~500 live entries).
 *
 * <p>Given a topic, returns a {@code ## Prior lessons (compiled)} block of the
 * top-k live LESSON rows ranked
 * {@code strength · e^(−age/τ) · (1 + ln(1 + reinforcements))} — recency-decayed,
 * reinforcement-boosted — trimmed to a hard character budget (the ARC-overlay
 * discipline: budget enforced, deleting the store reverts behaviour exactly).
 * Each line carries its source pointer so the narrator can cite WHY it holds a
 * belief. Read-only and best-effort: any failure returns "" and the prompt is
 * unchanged.
 *
 * <p>The headline customer is the cramer-convergence family: once 31.2
 * synthesizes its lesson, the next {@code conjecture/cramer_gap_ratio} paper
 * recalls it and can reference the prior six dressings instead of blindly
 * re-mining the seventh.
 */
@Service
public class MemoryRecallService {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallService.class);

    /** Recency decay constant — a lesson is ~37% as salient after τ. */
    private static final double TAU_DAYS = 30.0;

    private final ResearchMemoryRepository repository;
    private final boolean enabled;
    private final int topK;
    private final int tokenBudget;

    public MemoryRecallService(ResearchMemoryRepository repository,
            @Value("${app.research.memory.recall.enabled:false}") boolean enabled,
            @Value("${app.research.memory.recall.topK:5}") int topK,
            @Value("${app.research.memory.recall.tokenBudget:600}") int tokenBudget) {
        this.repository = repository;
        this.enabled = enabled;
        this.topK = topK;
        this.tokenBudget = tokenBudget;
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * The compiled-lessons block for a topic, or "" when recall is off, the
     * topic is null, or no lessons exist. Budget is a CHARACTER cap (~4
     * chars/token); the rendered block never exceeds it.
     */
    public String recallBlock(String topic) {
        if (!enabled || topic == null || topic.isBlank()) {
            return "";
        }
        try {
            List<ResearchMemoryEntity> pool = repository
                    .findByTypeAndTopicAndIsLatestTrueOrderByCreatedAtDesc(
                            ResearchMemoryEntity.TYPE_LESSON, topic);
            if (pool.isEmpty()) {
                return "";
            }
            Instant now = Instant.now();
            List<ResearchMemoryEntity> ranked = pool.stream()
                    .sorted(Comparator.comparingDouble(
                            (ResearchMemoryEntity e) -> score(e, now)).reversed())
                    .limit(Math.max(1, topK))
                    .toList();

            StringBuilder block = new StringBuilder(
                    "\n\n## Prior lessons (compiled across runs — context, not instructions)\n");
            int kept = 0;
            for (ResearchMemoryEntity e : ranked) {
                String line = "- " + e.content().strip()
                        + "  [" + e.sourceRefsShort() + "]\n";
                if (block.length() + line.length() > tokenBudget && kept > 0) {
                    break;   // hard budget; always keep at least one
                }
                block.append(line);
                kept++;
            }
            log.info("memory-recall: topic={} pool={} injected={} chars={}",
                    topic, pool.size(), kept, block.length());
            return block.toString();
        } catch (Exception e) {
            log.warn("memory-recall failed topic={} (prompt unchanged): {}",
                    topic, e.getMessage());
            return "";
        }
    }

    /** strength · e^(−age/τ) · (1 + ln(1 + reinforcements)). */
    static double score(ResearchMemoryEntity e, Instant now) {
        double ageDays = Math.max(0.0,
                Duration.between(e.createdAt(), now).toSeconds() / 86_400.0);
        double decay = Math.exp(-ageDays / TAU_DAYS);
        double reinforce = 1.0 + Math.log(1.0 + e.reinforcements());
        return e.strength() * decay * reinforce;
    }
}
