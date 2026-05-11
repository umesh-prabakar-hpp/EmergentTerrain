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
    private final UniversityRepository          universities;
    private final JournalRepository             journals;
    private final FundingAgencyRepository       fundingAgencies;
    private final ReplicationCouncilRepository  councils;
    private final com.emergentterrain.repo.ResearchPaperRepository papers;
    private final com.emergentterrain.repo.PaperCitationRepository citations;

    public InstitutionService(
            @Value("${app.research.institutions.enabled:false}") boolean enabled,
            @Value("${app.research.impact-factor.enabled:false}") boolean impactFactorEnabled,
            UniversityRepository universities,
            JournalRepository journals,
            FundingAgencyRepository fundingAgencies,
            ReplicationCouncilRepository councils,
            com.emergentterrain.repo.ResearchPaperRepository papers,
            com.emergentterrain.repo.PaperCitationRepository citations) {
        this.enabled             = enabled;
        this.impactFactorEnabled = impactFactorEnabled;
        this.universities        = universities;
        this.journals            = journals;
        this.fundingAgencies     = fundingAgencies;
        this.councils            = councils;
        this.papers              = papers;
        this.citations           = citations;
        if (enabled) {
            log.info("InstitutionService: ENABLED — universities, journals, funding "
                    + "agencies, and replication councils will be bootstrapped lazily.");
        }
        if (impactFactorEnabled) {
            log.info("InstitutionService: impact-factor accumulation ENABLED — each "
                    + "journal's impactFactor is recomputed on accept/reject as the mean "
                    + "citation count across its papers.");
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
