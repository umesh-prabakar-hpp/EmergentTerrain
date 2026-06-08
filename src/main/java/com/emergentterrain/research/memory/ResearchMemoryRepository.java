package com.emergentterrain.research.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Phase 31 §31.1 — the research-memory store's data access. */
public interface ResearchMemoryRepository extends JpaRepository<ResearchMemoryEntity, UUID> {

    Optional<ResearchMemoryEntity> findByContentHash(String contentHash);

    long countByRunId(String runId);

    /** 31.2 — rows captured since the consolidation cursor, oldest first. */
    java.util.List<ResearchMemoryEntity> findByCreatedAtAfterOrderByCreatedAtAsc(
            java.time.Instant after);

    /** 31.2 — live rows of one type+topic (the contradiction-check candidates). */
    java.util.List<ResearchMemoryEntity> findByTypeAndTopicAndIsLatestTrueOrderByCreatedAtAsc(
            String type, String topic);

    /** 31.2 — the latest pass crystal; its createdAt is the consolidation cursor. */
    Optional<ResearchMemoryEntity> findFirstByTypeAndTopicOrderByCreatedAtDesc(
            String type, String topic);

    /**
     * 31.3 — the recall candidate pool: live LESSON rows for a topic (the
     * compiled cross-run knowledge), newest first. Ranking/trimming happens
     * in MemoryRecallService — the query just bounds the read.
     */
    java.util.List<ResearchMemoryEntity> findByTypeAndTopicAndIsLatestTrueOrderByCreatedAtDesc(
            String type, String topic);

    /**
     * 31.2 — topics with ≥{@code minSize} FACT rows and NO live LESSON yet.
     * This is the backlog-draining driver for lesson synthesis: cursor-
     * INDEPENDENT (the cap-and-defer left the cursor unable to revisit deferred
     * groups; "has no lesson" is the durable incremental state instead).
     */
    @org.springframework.data.jpa.repository.Query("""
            select m.topic from ResearchMemoryEntity m
            where m.type = 'FACT' and m.topic is not null
              and m.topic not in (
                  select l.topic from ResearchMemoryEntity l
                  where l.type = 'LESSON' and l.isLatest = true)
            group by m.topic
            having count(m) >= :minSize
            order by count(m) desc""")
    java.util.List<String> findTopicsNeedingLesson(
            @org.springframework.data.repository.query.Param("minSize") int minSize);

    /** 31.2 — all FACT rows for a topic (the synthesis input for that group). */
    java.util.List<ResearchMemoryEntity> findByTypeAndTopicOrderByCreatedAtAsc(
            String type, String topic);
}
