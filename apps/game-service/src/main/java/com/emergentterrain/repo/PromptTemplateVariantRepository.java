package com.emergentterrain.repo;

import com.emergentterrain.research.PromptTemplateVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4c Piece 3 PR1 — Spring Data access to
 * {@link PromptTemplateVariant}. Read paths are populated by
 * {@code PromptTemplateRegistry}'s idempotent upsert at startup;
 * write paths land in subsequent piece-3 PRs (counter increments in
 * PR3, status flips in PR4).
 */
public interface PromptTemplateVariantRepository
        extends JpaRepository<PromptTemplateVariant, UUID> {

    /**
     * Idempotency lookup used by the registry's startup upsert. Returns
     * the existing row when a {@code (templateId, versionTag)} pair has
     * already been seeded; the registry leaves counters / status alone.
     */
    Optional<PromptTemplateVariant> findByTemplateIdAndVersionTag(
            String templateId, String versionTag);

    /**
     * All variants for a given template id, regardless of status. Piece-3
     * PR2's epsilon-greedy selector filters down to ACTIVE; PR4's
     * rollback judge iterates the full set (it needs to see ROLLED_BACK
     * + RETIRED for audit logging).
     */
    List<PromptTemplateVariant> findByTemplateId(String templateId);

    /** All ACTIVE variants for piece-3 PR2's selector. */
    List<PromptTemplateVariant> findByTemplateIdAndStatus(
            String templateId, PromptTemplateVariant.Status status);
}
