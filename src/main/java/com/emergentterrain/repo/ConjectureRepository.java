package com.emergentterrain.repo;

import com.emergentterrain.research.conjecture.Conjecture;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link Conjecture} rows. Lives in
 * {@code com.emergentterrain.repo} to match the rest of the codebase's
 * repository placement.
 */
public interface ConjectureRepository extends JpaRepository<Conjecture, UUID> {

    /** All conjectures for a domain (math/physics/materials). */
    List<Conjecture> findByDomain(String domain);

    /** Live survivors for a domain — confirmed runs and not dropped by any knob filter. */
    List<Conjecture> findByDomainAndDroppedByKnobIsNull(String domain);

    /** Promoted conjectures for a domain (≥ PROMOTE_RUNS confirmed runs). */
    List<Conjecture> findByDomainAndConfirmedRunsGreaterThanEqual(String domain, int minRuns);

    long countByDomain(String domain);
}
