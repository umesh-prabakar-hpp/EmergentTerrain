package com.emergentterrain.research;

import com.emergentterrain.repo.FundingAgencyRepository;
import com.emergentterrain.repo.JournalRepository;
import com.emergentterrain.repo.ReplicationCouncilRepository;
import com.emergentterrain.repo.UniversityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3 Iter 8 (MVP) — bootstraps the four institution kinds per room:
 *   - one {@link University},
 *   - one {@link Journal} per domain,
 *   - one {@link FundingAgency} (room-scoped),
 *   - one {@link ReplicationCouncil}.
 *
 * Bootstrapping is lazy: the first paper published in a room (or in a new
 * domain) triggers creation of the missing institutions. This means rooms
 * pre-dating the flag rollout pick up institutions on their next publish
 * without a migration script.
 *
 * Gated by {@code app.research.institutions.enabled} (default false). When
 * the flag is off, every entry point is a no-op and no rows are written.
 * The deeper behavioural changes — papers routing through journals,
 * impact-factor accumulation, funding-agency allocation, council-triggered
 * replication campaigns — are deferred to Phase 3b commits.
 */
@Service
public class InstitutionService {

    private static final Logger log = LoggerFactory.getLogger(InstitutionService.class);

    private final boolean                       enabled;
    private final boolean                       impactFactorEnabled;
    private final boolean                       replicationCouncilEnabled;
    private final boolean                       dominantMethodologyEnabled;
    private final boolean                       dominantMethodologyReputationWeighted;
    private final UniversityRepository          universities;
    private final JournalRepository             journals;
    private final FundingAgencyRepository       fundingAgencies;
    private final ReplicationCouncilRepository  councils;
    private final com.emergentterrain.repo.ResearchPaperRepository papers;
    private final com.emergentterrain.repo.PaperCitationRepository citations;

    public InstitutionService(
            @Value("${app.research.institutions.enabled:false}") boolean enabled,
            @Value("${app.research.impact-factor.enabled:false}") boolean impactFactorEnabled,
            @Value("${app.research.replication-council.enabled:false}") boolean replicationCouncilEnabled,
            @Value("${app.research.dominant-methodology.enabled:false}") boolean dominantMethodologyEnabled,
            @Value("${app.research.dominant-methodology.reputation-weighted:false}") boolean dominantMethodologyReputationWeighted,
            UniversityRepository universities,
            JournalRepository journals,
            FundingAgencyRepository fundingAgencies,
            ReplicationCouncilRepository councils,
            com.emergentterrain.repo.ResearchPaperRepository papers,
            com.emergentterrain.repo.PaperCitationRepository citations) {
        this.enabled                    = enabled;
        this.impactFactorEnabled        = impactFactorEnabled;
        this.replicationCouncilEnabled  = replicationCouncilEnabled;
        this.dominantMethodologyEnabled = dominantMethodologyEnabled;
        this.dominantMethodologyReputationWeighted = dominantMethodologyReputationWeighted;
        this.universities               = universities;
        this.journals                   = journals;
        this.fundingAgencies            = fundingAgencies;
        this.councils                   = councils;
        this.papers                     = papers;
        this.citations                  = citations;
        if (enabled) {
            log.info("InstitutionService: ENABLED — universities, journals, funding "
                    + "agencies, and replication councils will be bootstrapped lazily.");
        }
        if (impactFactorEnabled) {
            log.info("InstitutionService: impact-factor accumulation ENABLED — each "
                    + "journal's impactFactor is recomputed on accept/reject as the mean "
                    + "citation count across its papers.");
        }
        if (replicationCouncilEnabled) {
            log.info("InstitutionService: replication-council triggering ENABLED — "
                    + "papers asserting a CONTRADICTS citation are queued for replication "
                    + "regardless of anomaly z-score, and active/completed counters are "
                    + "tracked on the per-room council.");
        }
        if (dominantMethodologyEnabled) {
            log.info("InstitutionService: dominant-methodology emergence ENABLED — "
                    + "University.dominantMethodology is recomputed from the modal "
                    + "BotGenome.methodology across the room's bots once per tick.");
        }
        if (dominantMethodologyReputationWeighted) {
            log.info("InstitutionService: dominant-methodology REPUTATION-WEIGHTED — "
                    + "each bot's vote is its reputation (clamped to [0,1]) rather than 1, so "
                    + "high-reputation bots influence the room's dominant methodology more.");
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Ensure the four institution kinds exist for this room + domain. Idempotent
     * and cheap — looks up existing rows before inserting. No-op when the flag is off.
     *
     * Returns the University so callers can stamp paper.universityId without a
     * second round-trip. Returns null when the flag is off.
     */
    public BootstrapResult bootstrapForPaper(UUID roomId, String domain) {
        if (!enabled || roomId == null) return null;
        University university = universities.findFirstByRoomId(roomId)
                .orElseGet(() -> universities.save(new University(
                        roomId, "University of Room " + shortId(roomId),
                        /* hostingDistrictId = */ null, Instant.now())));
        Journal journal = (domain != null)
                ? journals.findFirstByRoomIdAndFocusDomain(roomId, domain).orElseGet(() ->
                        journals.save(new Journal(roomId,
                                domain + " Journal of Room " + shortId(roomId),
                                domain, Instant.now())))
                : null;
        ensureFundingAgency(roomId);
        ensureReplicationCouncil(roomId);
        return new BootstrapResult(university, journal);
    }

    /** Returned by {@link #bootstrapForPaper} so the publish path can stamp FK columns. */
    public record BootstrapResult(University university, Journal journal) {}

    /**
     * Record an accepted publication on the journal so the rejectionRate and
     * (eventually) impactFactor track real review behaviour. No-op when the
     * flag is off or the journal id is null.
     */
    public void recordJournalAcceptance(UUID journalId) {
        if (!enabled || journalId == null) return;
        journals.findById(journalId).ifPresent(j -> {
            j.recordAcceptance();
            maybeRecomputeImpactFactor(j);
            try { journals.save(j); }
            catch (Exception e) {
                log.warn("institution: journal acceptance save failed id={}: {}",
                        journalId, e.getMessage());
            }
        });
    }

    /**
     * Phase 4b — recompute {@code j.impactFactor} as the mean citation count
     * across its papers. No-op when the impact-factor flag is off.
     *
     * Reads ResearchPaper rows by journalId and PaperCitation counts by
     * cited_paper_id. Cost is O(N) lookups per journal — small for current
     * per-journal volumes, but if journals grow to thousands of papers a
     * cached running sum on Journal would be more efficient.
     */
    void maybeRecomputeImpactFactor(Journal j) {
        if (!impactFactorEnabled || j == null) return;
        try {
            java.util.List<ResearchPaper> journalPapers = papers.findByJournalId(j.getId());
            if (journalPapers.isEmpty()) {
                j.setImpactFactor(0.0);
                return;
            }
            double totalCitations = 0;
            for (ResearchPaper p : journalPapers) {
                totalCitations += citations.countByCitedPaperId(p.paperId());
            }
            j.setImpactFactor(totalCitations / journalPapers.size());
        } catch (Exception e) {
            log.warn("institution: impact-factor recompute failed journalId={}: {}",
                    j.getId(), e.getMessage());
        }
    }

    /**
     * Phase 4b — pick the top-{@code limit} journal editors for the given
     * (room, domain) journal, drawn from the supplied researcher pool.
     *
     * "Editor" means: a researcher whose genome.domain matches the journal's
     * focusDomain, ranked by reputation descending. Returns an empty list when
     * institutions are disabled, no journal exists for this (room, domain),
     * or no pool member qualifies.
     *
     * Callers are responsible for falling back gracefully when the result is
     * empty (e.g. the existing same-domain pool in ResearchCycleService).
     */
    public java.util.List<Researcher> journalEditorsFor(
            UUID roomId, String domain,
            java.util.List<Researcher> pool, int limit) {
        if (!enabled || roomId == null || domain == null || pool == null || pool.isEmpty()
                || limit <= 0) {
            return java.util.List.of();
        }
        if (journals.findFirstByRoomIdAndFocusDomain(roomId, domain).isEmpty()) {
            return java.util.List.of();
        }
        return pool.stream()
                .filter(r -> domain.equalsIgnoreCase(r.getDomain()))
                .sorted(java.util.Comparator.comparingDouble(Researcher::getReputation).reversed())
                .limit(limit)
                .toList();
    }

    /** Record a journal rejection — fires from the publish path's review-failure branch. */
    public void recordJournalRejection(UUID journalId) {
        if (!enabled || journalId == null) return;
        journals.findById(journalId).ifPresent(j -> {
            j.recordRejection();
            maybeRecomputeImpactFactor(j);
            try { journals.save(j); }
            catch (Exception e) {
                log.warn("institution: journal rejection save failed id={}: {}",
                        journalId, e.getMessage());
            }
        });
    }

    /**
     * Phase 4b — true when the council wants this paper replicated regardless
     * of its anomaly z-score. The trigger is: any typed citation with
     * {@link RelationshipType#CONTRADICTS} — a paper asserting a refutation
     * deserves independent verification even if the underlying experiment
     * wasn't anomalous by itself.
     *
     * No-op (returns false) when institutions are disabled or the council
     * flag is off. Callers should OR this with the existing z-score check.
     */
    public boolean councilWantsReplication(NarrationResult narration) {
        if (!enabled || !replicationCouncilEnabled || narration == null) return false;
        java.util.List<NarrationResult.TypedCitation> tc = narration.typedCitations();
        if (tc == null || tc.isEmpty()) return false;
        for (NarrationResult.TypedCitation c : tc) {
            if (c.type() == RelationshipType.CONTRADICTS) return true;
        }
        return false;
    }

    /**
     * Phase 4b — fires when a paper is enqueued for replication, regardless
     * of trigger. Increments the council's activeReplicationCount.
     * No-op when institutions or the council flag is off.
     */
    public void recordReplicationEnqueued(UUID roomId) {
        if (!enabled || !replicationCouncilEnabled || roomId == null) return;
        councils.findFirstByRoomId(roomId).ifPresent(c -> {
            c.setActiveReplicationCount(c.getActiveReplicationCount() + 1);
            try { councils.save(c); }
            catch (Exception e) {
                log.warn("institution: council enqueue counter save failed roomId={}: {}",
                        roomId, e.getMessage());
            }
        });
    }

    /**
     * Phase 4b — fires after {@link ReplicationService#processOne} reports it
     * processed a request. Decrements active and increments completed on the
     * council. No-op when institutions or the council flag is off.
     */
    public void recordReplicationCompleted(UUID roomId) {
        if (!enabled || !replicationCouncilEnabled || roomId == null) return;
        councils.findFirstByRoomId(roomId).ifPresent(c -> {
            c.setActiveReplicationCount(c.getActiveReplicationCount() - 1);
            c.setCompletedCount(c.getCompletedCount() + 1);
            try { councils.save(c); }
            catch (Exception e) {
                log.warn("institution: council completion counter save failed roomId={}: {}",
                        roomId, e.getMessage());
            }
        });
    }

    /**
     * Phase 4b — recompute {@code University.dominantMethodology} as the modal
     * {@code BotGenome.methodology} across the room's qualified researcher pool.
     * Ties are broken by alphabetic order of the methodology name (deterministic).
     *
     * When {@code app.research.dominant-methodology.reputation-weighted} is on,
     * each bot's vote is its {@code reputation} (clamped to {@code [0, 1]})
     * rather than a flat 1. High-reputation bots influence the room's dominant
     * methodology more — a small group of established researchers can sway it
     * even when newer bots are more numerous. When the flag is off, the count
     * is the pre-carve plain-mode.
     *
     * Idempotent: only saves when the computed mode differs from the value
     * currently on the entity. No-op when institutions are disabled, the
     * dominant-methodology flag is off, the pool is empty, or no University
     * exists for the room.
     *
     * Returns the new value (or the existing one when unchanged); null when
     * the operation was a no-op.
     */
    public String maybeRecomputeDominantMethodology(UUID roomId, java.util.List<Researcher> pool) {
        if (!enabled || !dominantMethodologyEnabled || roomId == null
                || pool == null || pool.isEmpty()) return null;
        University u = universities.findFirstByRoomId(roomId).orElse(null);
        if (u == null) return null;

        java.util.Map<String, Double> counts = new java.util.HashMap<>();
        for (Researcher r : pool) {
            String m = r.getMethodology();
            if (m == null || m.isBlank()) continue;
            double vote = dominantMethodologyReputationWeighted
                    ? Math.max(0.0, Math.min(1.0, r.getReputation()))
                    : 1.0;
            // A 0-reputation bot has no influence under reputation-weighted counts.
            // Skip the merge so a methodology held only by 0-rep bots doesn't
            // accumulate a phantom 0.0 entry that the tie-break could pick.
            if (vote <= 0.0) continue;
            counts.merge(m, vote, Double::sum);
        }
        if (counts.isEmpty()) return null;
        String mode = counts.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(java.util.Map.Entry.comparingByKey()))
                .findFirst().map(java.util.Map.Entry::getKey).orElse(null);
        if (mode == null) return null;

        if (mode.equals(u.getDominantMethodology())) return mode;
        u.setDominantMethodology(mode);
        try {
            universities.save(u);
            log.info("institution: dominant methodology updated roomId={} methodology={}",
                    roomId, mode);
        } catch (Exception e) {
            log.warn("institution: dominantMethodology save failed roomId={}: {}",
                    roomId, e.getMessage());
        }
        return mode;
    }

    private void ensureFundingAgency(UUID roomId) {
        if (fundingAgencies.findFirstByRoomId(roomId).isPresent()) return;
        fundingAgencies.save(new FundingAgency(
                roomId, "Research Funding Agency of Room " + shortId(roomId),
                /* parentGovernmentNodeId = */ null, Instant.now()));
    }

    private void ensureReplicationCouncil(UUID roomId) {
        if (councils.findFirstByRoomId(roomId).isPresent()) return;
        councils.save(new ReplicationCouncil(
                roomId, "Replication Council of Room " + shortId(roomId), Instant.now()));
    }

    private static String shortId(UUID id) {
        return id != null ? id.toString().substring(0, 8) : "?";
    }
}
