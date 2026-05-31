package com.emergentterrain.research.discovery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 30 fork 2b — Spring Data repository for {@link FrontierRecordEntity}.
 * Top-level (not nested in {@link JpaFrontierKnowledgeStore}) so Spring Data's
 * repository scanning registers it.
 */
public interface FrontierRecordRepository extends JpaRepository<FrontierRecordEntity, UUID> {

    Optional<FrontierRecordEntity> findByBoundAndScaleX(String bound, long scaleX);

    @Query("select max(r.scaleX) from FrontierRecordEntity r where r.bound = ?1")
    Long maxScaleX(String bound);

    List<FrontierRecordEntity> findByBoundOrderByScaleXAsc(String bound);
}
