package com.emergentterrain.research;

import com.emergentterrain.repo.PromptTemplateVariantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Phase 4c Piece 3 PR3 — writes per-variant counter updates from
 * publish-time and replication-outcome paths to
 * {@link PromptTemplateVariant}. Reads run cheaply from
 * {@link PromptTemplateRegistry}'s in-memory map; the DB row exists
 * only to accumulate the counters this service maintains.
 *
 * <p>Each call resolves a row by {@code (templateId, versionTag)} —
 * the pair that {@link PromptTemplateRegistry#syncVariantsToDatabase}
 * idempotently upserts on startup, and that
 * {@link ResearchPaper#narrationTemplateId()} +
 * {@link ResearchPaper#narrationTemplateVersion()} pin per paper.
 * Rows that don't exist (e.g. an unrecognised template version reaches
 * a terminal outcome) log a WARN and skip the write rather than
 * auto-creating — auto-create would mask provenance bugs.
 *
 * <p>Flag-gated: {@code app.research.prompt-template-stats.enabled}
 * defaults to {@code false} so existing behaviour is preserved.
 * Operator activates when piece-3 PR2's epsilon-greedy selector is
 * also turned on; counter writes without the selector active are
 * mostly harmless (the rollback judge in PR4 reads them) but produce
 * data that nothing currently consumes.
 */
@Service
public class PromptTemplateVariantStatsService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateVariantStatsService.class);

    /**
     * Terminal classifications consumed by {@link #record}. Aligns with
     * the publish-time + replication-time outcomes that the design doc
     * names.
     */
    public enum Terminal {
        /** Replication produced a CONFIRMED outcome. Increments replicationsConfirmed. */
        CONFIRMED,
        /** Replication produced a FAILED outcome, or the paper status was
         *  later flipped to DISPUTED / DISPUTE_ABANDONED. Increments
         *  replicationsFailed. */
        FAILED,
        /** Paper was published but never reached a terminal replication
         *  outcome (e.g. status REJECTED / NULL_RESULT / NEEDS_REVISION).
         *  Increments papersNarrated only. */
        PUBLISHED
    }

    private final PromptTemplateVariantRepository repo;

    @Value("${app.research.prompt-template-stats.enabled:false}")
    private boolean enabled;

    public PromptTemplateVariantStatsService(PromptTemplateVariantRepository repo) {
        this.repo = repo;
    }

    /**
     * Idempotent-per-event counter write. Each call increments
     * {@code papersNarrated} on the resolved variant row (so the row
     * doubles as the denominator of any future rate metric), and then
     * additionally increments the appropriate {@code replicationsX}
     * counter when {@code terminal} is a replication outcome.
     *
     * <p>No-op when the flag is off, when {@code templateId} or
     * {@code versionTag} is null/blank (the paper predates piece-2
     * gate 3's per-paper provenance), or when the variant row doesn't
     * exist (logged at WARN).
     */
    public void record(String templateId, String versionTag, Terminal terminal) {
        if (!enabled) return;
        if (templateId == null || templateId.isBlank()) return;
        if (versionTag == null || versionTag.isBlank()) return;
        if (terminal == null) return;

        Optional<PromptTemplateVariant> opt =
                repo.findByTemplateIdAndVersionTag(templateId, versionTag);
        if (opt.isEmpty()) {
            log.warn("PromptTemplateVariantStatsService: no row for ({}, {}) — terminal={} skipped. "
                            + "Check that PromptTemplateRegistry.syncVariantsToDatabase has run.",
                    templateId, versionTag, terminal);
            return;
        }
        PromptTemplateVariant row = opt.get();
        row.incrementPapersNarrated();
        switch (terminal) {
            case CONFIRMED -> row.incrementReplicationsConfirmed();
            case FAILED    -> row.incrementReplicationsFailed();
            case PUBLISHED -> { /* papersNarrated only */ }
        }
        try {
            repo.save(row);
        } catch (Exception e) {
            // Defensive: a stats-write failure must never poison the
            // narration / replication path. Log and continue.
            log.warn("PromptTemplateVariantStatsService: save failed for ({}, {}) terminal={}: {}",
                    templateId, versionTag, terminal, e.getMessage());
        }
    }

    /** Convenience overload for the publish-time path. */
    public void recordPublished(String templateId, String versionTag) {
        record(templateId, versionTag, Terminal.PUBLISHED);
    }
}
