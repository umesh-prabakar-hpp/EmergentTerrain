package com.emergentterrain.research.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase 30 fork 2b — Spring Data repository for {@link FrontierConvergenceEntity}
 * (keyed by bound). Top-level so repository scanning registers it.
 */
public interface FrontierConvergenceRepository extends JpaRepository<FrontierConvergenceEntity, String> {
}
