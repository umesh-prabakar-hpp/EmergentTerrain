package com.emergentterrain.repo;

import com.emergentterrain.research.conjecture.DerivedScalarEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link DerivedScalarEntity} rows (Phase 30 Wave 5, 30.5 P3b). Lives in
 * {@code com.emergentterrain.repo} to match the rest of the codebase's repository placement.
 */
public interface DerivedScalarRepository extends JpaRepository<DerivedScalarEntity, UUID> {

    /** Admitted derived scalars for a domain (math/physics/materials). */
    List<DerivedScalarEntity> findByDomain(String domain);

    boolean existsByDomainAndName(String domain, String name);
}
