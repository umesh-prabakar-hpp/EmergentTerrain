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
    private final UniversityRepository          universities;
    private final JournalRepository             journals;
    private final FundingAgencyRepository       fundingAgencies;
    private final ReplicationCouncilRepository  councils;

    public InstitutionService(
            @Value("${app.research.institutions.enabled:false}") boolean enabled,
            UniversityRepository universities,
            JournalRepository journals,
            FundingAgencyRepository fundingAgencies,
            ReplicationCouncilRepository councils) {
        this.enabled         = enabled;
        this.universities    = universities;
        this.journals        = journals;
        this.fundingAgencies = fundingAgencies;
        this.councils        = councils;
        if (enabled) {
            log.info("InstitutionService: ENABLED — universities, journals, funding "
                    + "agencies, and replication councils will be bootstrapped lazily.");
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
            try { journals.save(j); }
            catch (Exception e) {
                log.warn("institution: journal acceptance save failed id={}: {}",
                        journalId, e.getMessage());
            }
        });
    }

    /** Record a journal rejection (deferred — caller path will wire this once review routes through journals). */
    public void recordJournalRejection(UUID journalId) {
        if (!enabled || journalId == null) return;
        journals.findById(journalId).ifPresent(j -> {
            j.recordRejection();
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
