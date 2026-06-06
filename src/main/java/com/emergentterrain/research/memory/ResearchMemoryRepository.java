package com.emergentterrain.research.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Phase 31 §31.1 — the research-memory store's data access. */
public interface ResearchMemoryRepository extends JpaRepository<ResearchMemoryEntity, UUID> {

    Optional<ResearchMemoryEntity> findByContentHash(String contentHash);

    long countByRunId(String runId);
}
