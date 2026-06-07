package com.emergentterrain.research.memory;

import com.emergentterrain.service.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 31 §31.2 — the offline compiler over the 31.1 capture store:
 * observations → crystals → lessons, with explicit contradiction
 * resolution. Scheduled off the hot path; bounded LLM spend; deterministic
 * wherever the domain allows.
 *
 * <p>One pass (hash-incremental — the cursor is the latest pass-crystal's
 * {@code createdAt}, so re-running on unchanged input is a no-op by
 * construction):
 * <ol>
 *   <li><b>Deterministic supersession</b> (the LightMem update-queue, no LLM
 *       needed for ET's headline case): a retirement FACT supersedes earlier
 *       live confirm-outcome FACTs on the same conjecture topic —
 *       timestamp-causal, newer evidence revises older belief, never the
 *       reverse. History stays queryable (isLatest=false, lineage via
 *       supersedesId).</li>
 *   <li><b>Group → LESSON synthesis</b> (the agentmemory consolidate shape):
 *       new FACT rows grouped by topic; groups with ≥{@code minGroupSize}
 *       entries get ONE fast-tier LLM call to synthesize a durable lesson
 *       (hash-deduped via the 31.1 capture path, so re-synthesis of the same
 *       content reinforces). Capped at {@code maxLlmCallsPerPass}; drain-aware
 *       (discretionary spend).</li>
 *   <li><b>Run crystal</b> (deterministic): one INSIGHT row per pass —
 *       what arrived, what was superseded, what was synthesized.</li>
 * </ol>
 *
 * <p>The first real target: the cramer-ceiling convergence (six papers, six
 * dressings of one ≈0.43–0.45 constant) — its outcome FACTs share the
 * {@code conjecture/cramer_gap_ratio} topic and become one synthesized LESSON
 * the first pass after enough capture accumulates.
 */
@Service
public class MemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidationService.class);

    static final String CRYSTAL_TOPIC = "consolidation-pass";

    private final ResearchMemoryRepository repository;
    private final ResearchMemoryService memoryService;
    private final LlmRouter router;
    private final boolean enabled;
    private final int maxLlmCallsPerPass;
    private final int minGroupSize;

    /** Test seam: replaces the router call without standing up providers. */
    private Function<String, String> synthesizer;

    private volatile com.emergentterrain.llm.RunBudgetTracker runBudgetTracker;

    @Autowired(required = false)
    public void setRunBudgetTracker(com.emergentterrain.llm.RunBudgetTracker tracker) {
        this.runBudgetTracker = tracker;
    }

    public MemoryConsolidationService(ResearchMemoryRepository repository,
            ResearchMemoryService memoryService,
            LlmRouter router,
            @Value("${app.research.memory.consolidation.enabled:false}") boolean enabled,
            @Value("${app.research.memory.consolidation.maxLlmCallsPerPass:10}") int maxLlmCallsPerPass,
            @Value("${app.research.memory.consolidation.minGroupSize:3}") int minGroupSize) {
        this.repository = repository;
        this.memoryService = memoryService;
        this.router = router;
        this.enabled = enabled;
        this.maxLlmCallsPerPass = maxLlmCallsPerPass;
        this.minGroupSize = minGroupSize;
        this.synthesizer = prompt -> com.emergentterrain.llm.LlmCallContext.withRole(
                "memory-consolidation", () -> this.router.callForRole(
                        "memory-consolidation", prompt));
    }

    /** Package-private: tests inject a deterministic synthesizer. */
    void setSynthesizer(Function<String, String> synthesizer) {
        this.synthesizer = synthesizer;
    }

    /** Every 30 min, off the hot path; no-ops when nothing new arrived. */
    @Scheduled(initialDelay = 600_000, fixedDelay = 1_800_000)
    public void scheduledPass() {
        if (!enabled || !memoryService.enabled()) return;
        try {
            runPass();
        } catch (Exception e) {
            log.warn("memory-consolidation pass failed (store unaffected): {}", e.getMessage());
        }
    }

    /** Result counts for the log line and tests. */
    public record PassResult(int newRows, int superseded, int lessons, boolean ranLlm) {}

    public PassResult runPass() {
        Instant cursor = repository
                .findFirstByTypeAndTopicOrderByCreatedAtDesc(
                        ResearchMemoryEntity.TYPE_INSIGHT, CRYSTAL_TOPIC)
                .map(ResearchMemoryEntity::createdAt)
                .orElse(Instant.EPOCH);
        List<ResearchMemoryEntity> fresh =
                repository.findByCreatedAtAfterOrderByCreatedAtAsc(cursor);
        // Only consolidate FACTs; crystals/lessons from prior passes are outputs.
        List<ResearchMemoryEntity> facts = fresh.stream()
                .filter(r -> ResearchMemoryEntity.TYPE_FACT.equals(r.type()))
                .toList();
        if (facts.isEmpty()) {
            return new PassResult(0, 0, 0, false);   // no-op on unchanged input
        }

        int superseded = supersedeRefutedConfirms(facts);
        int lessons = synthesizeGroupLessons(facts);

        // Run crystal — the pass record AND the next pass's cursor. 2026-06-06
        // commit-review fix: the cursor must be the createdAt of the LAST
        // CONSUMED row, not the crystal's own wall-clock — synthesis takes
        // seconds, and a FACT persisted by a concurrent publish during that
        // window would land createdAt < crystal.createdAt and be skipped
        // FOREVER under the old scheme. With cursor = max(consumed), mid-pass
        // writes are strictly after it and picked up next pass. (Residual
        // edge: a row sharing the exact microsecond of the last consumed row
        // — accepted; reprocessing safety makes even that benign.)
        Instant maxConsumed = facts.get(facts.size() - 1).createdAt();
        memoryService.captureAt(ResearchMemoryEntity.TYPE_INSIGHT,
                String.format("consolidation pass: newFacts=%d superseded=%d lessons=%d "
                                + "topics=%s at=%s",
                        facts.size(), superseded, lessons,
                        facts.stream().map(ResearchMemoryEntity::topic).distinct().sorted()
                                .toList(),
                        Instant.now()),
                "consolidation", CRYSTAL_TOPIC, "META", maxConsumed);
        log.info("memory-consolidation: newFacts={} superseded={} lessons={} (cursor was {})",
                facts.size(), superseded, lessons, cursor);
        return new PassResult(facts.size(), superseded, lessons, lessons > 0);
    }

    // ── step 1: deterministic supersession ──────────────────────────────────

    /** "conjecture retired: <lhs> <= ..." — the refuting-evidence marker. */
    private static final Pattern RETIREMENT = Pattern.compile("^conjecture retired: ");
    /** Confirm-outcome marker on the same conjecture topic. */
    private static final Pattern CONFIRM_OUTCOME = Pattern.compile(
            "^paper outcome: .*classification=SUPPORTED");

    private int supersedeRefutedConfirms(List<ResearchMemoryEntity> freshFacts) {
        int superseded = 0;
        for (ResearchMemoryEntity fact : freshFacts) {
            if (fact.topic() == null || !RETIREMENT.matcher(fact.content()).find()) continue;
            // Timestamp-causal: only OLDER live confirms on the same topic.
            List<ResearchMemoryEntity> candidates = repository
                    .findByTypeAndTopicAndIsLatestTrueOrderByCreatedAtAsc(
                            ResearchMemoryEntity.TYPE_FACT, fact.topic());
            boolean pointerSet = false;
            for (ResearchMemoryEntity older : candidates) {
                if (!older.createdAt().isBefore(fact.createdAt())) continue;
                if (!CONFIRM_OUTCOME.matcher(older.content()).find()) continue;
                older.markSuperseded();
                // 2026-06-06 commit-review fix: supersedesId is a SINGLE
                // pointer — setting it per match overwrote all but the last
                // lineage link and bumped version once per match. Point it at
                // the OLDEST superseded confirm only (candidates are
                // createdAt-ascending); the full fan-in lineage lives in the
                // per-pair INFO line below, which is part of the audit record.
                if (!pointerSet) {
                    fact.supersedes(older.id());
                    pointerSet = true;
                }
                repository.save(older);
                repository.save(fact);
                superseded++;
                log.info("memory-consolidation: retirement {} supersedes confirm {} (topic {})",
                        fact.id(), older.id(), fact.topic());
            }
        }
        return superseded;
    }

    // ── step 2: group → LESSON ───────────────────────────────────────────────

    private int synthesizeGroupLessons(List<ResearchMemoryEntity> freshFacts) {
        Map<String, List<ResearchMemoryEntity>> byTopic = new LinkedHashMap<>();
        for (ResearchMemoryEntity f : freshFacts) {
            if (f.topic() == null) continue;
            byTopic.computeIfAbsent(f.topic(), k -> new ArrayList<>()).add(f);
        }
        int calls = 0;
        int lessons = 0;
        for (Map.Entry<String, List<ResearchMemoryEntity>> group : byTopic.entrySet()) {
            if (group.getValue().size() < minGroupSize) continue;
            if (calls >= maxLlmCallsPerPass) {
                log.info("memory-consolidation: LLM cap reached ({}); {} group(s) deferred "
                        + "to the next pass", maxLlmCallsPerPass, byTopic.size());
                break;
            }
            com.emergentterrain.llm.RunBudgetTracker t = runBudgetTracker;
            if (t != null && !t.allowDiscretionarySpend()) {
                log.info("memory-consolidation: draining — synthesis deferred");
                break;
            }
            StringBuilder factList = new StringBuilder();
            for (ResearchMemoryEntity f : group.getValue()) {
                factList.append("- ").append(f.content()).append('\n');
            }
            String prompt = "These observations about topic '" + group.getKey()
                    + "' accumulated across research runs:\n" + factList
                    + "\nSynthesize ONE durable lesson (<= 300 chars, plain text, no "
                    + "preamble) capturing the cross-run pattern — what keeps happening "
                    + "and what it implies. State only what these observations support.";
            try {
                calls++;
                String lesson = synthesizer.apply(prompt);
                if (lesson != null && !lesson.isBlank()) {
                    memoryService.capture(ResearchMemoryEntity.TYPE_LESSON,
                            lesson.strip(),
                            group.getValue().stream()
                                    .map(f -> f.id().toString()).toList().toString(),
                            group.getKey(), "MATHEMATICS", null, null);
                    lessons++;
                }
            } catch (Exception e) {
                log.warn("memory-consolidation: synthesis failed for topic {}: {}",
                        group.getKey(), e.getMessage());
            }
        }
        return lessons;
    }
}
