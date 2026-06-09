package com.emergentterrain.repo;

import com.emergentterrain.research.conjecture.Conjecture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    /**
     * Phase 30 Wave 0 rotation — targeted column write for
     * {@code markInvestigated}. A whole-entity {@code save()} loses against the
     * miner's pass-long stale snapshot: {@code mine()} loads its byKey map at
     * pass start and {@code persistRetired} saves those entities up to ~10 s
     * later (post-W4-flip re-seed churn), overwriting any
     * {@code lastInvestigatedAt} written in between — which re-serves the same
     * conjecture to concurrent paper cycles (P-b72c-115/116 duplicate,
     * 2026-06-04: marked 19:18:50.171 inside the 19:18:49–59 pass window).
     * Updating only this column cannot be clobbered by entity saves that don't
     * include it in their UPDATE, and itself touches nothing else.
     */
    @Modifying
    @Transactional
    @Query("update Conjecture c set c.lastInvestigatedAt = :ts where c.id = :id")
    int stampInvestigated(@Param("id") UUID id, @Param("ts") Instant ts);

    /**
     * Phase 30 Wave 7 — 30.6: targeted stamp for an anomaly that survived the
     * adversarial promotion gate. Same single-writer/JPQL pattern (and rationale)
     * as {@link #stampInvestigated}: the column is {@code updatable=false} so the
     * miner's pass-long re-save loop cannot clobber it.
     */
    @Modifying
    @Transactional
    @Query("update Conjecture c set c.promotedAnomalyAt = :ts where c.id = :id")
    int stampPromotedAnomaly(@Param("id") UUID id, @Param("ts") Instant ts);

    /** 2026-06-09 — lookup for the investigation write-back: the one row per
     *  (domain, lhs, rhs), whatever its state (live or retired). */
    java.util.Optional<Conjecture> findFirstByDomainAndLhsAndRhs(
            String domain, String lhs, String rhs);

    /**
     * 2026-06-09 — targeted stamp for a bound an investigation proved HELD at the
     * failure region. Same single-writer/JPQL pattern as the other stamps; the
     * column is {@code updatable=false} so the miner's re-save loop can't clobber
     * the discovery→pool audit trail.
     */
    @Modifying
    @Transactional
    @Query("update Conjecture c set c.investigationVerifiedAt = :ts where c.id = :id")
    int stampInvestigationVerified(@Param("id") UUID id, @Param("ts") Instant ts);
}
