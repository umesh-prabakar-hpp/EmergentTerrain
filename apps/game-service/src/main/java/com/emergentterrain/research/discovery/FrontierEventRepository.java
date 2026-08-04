package com.emergentterrain.research.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 30 fork 2b §10.3 — Spring Data repository for {@link FrontierEventEntity}.
 * Top-level so repository scanning registers it.
 */
public interface FrontierEventRepository extends JpaRepository<FrontierEventEntity, UUID> {

    Optional<FrontierEventEntity> findByBoundAndScaleXAndOutcome(String bound, long scaleX, String outcome);

    List<FrontierEventEntity> findByBoundOrderByScaleXAsc(String bound);
}
