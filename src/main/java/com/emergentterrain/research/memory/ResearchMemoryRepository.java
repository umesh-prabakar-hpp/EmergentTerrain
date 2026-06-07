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
}
