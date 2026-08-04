package com.emergentterrain.research.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 30 fork 2b — Spring Data repository for the verified-method library
 * ({@link VerifiedMethodEntity}). Top-level so repository scanning registers it.
 */
public interface VerifiedMethodRepository extends JpaRepository<VerifiedMethodEntity, UUID> {

    Optional<VerifiedMethodEntity> findByBoundAndMethodSha256(String bound, String methodSha256);

    /** Library ranked by the cost curve: furthest reach first, then cheapest. */
    List<VerifiedMethodEntity> findByBoundOrderByMaxScaleDescCostMsAsc(String bound);
}
