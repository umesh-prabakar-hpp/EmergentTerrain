package com.emergentterrain.research;

import com.emergentterrain.repo.FundingAgencyRepository;
import com.emergentterrain.repo.JournalRepository;
import com.emergentterrain.repo.PaperCitationRepository;
import com.emergentterrain.repo.ReplicationCouncilRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.UniversityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the Phase 3 Iter 8 InstitutionService (MVP scaffold).
 *
 * Verifies the disabled-no-op invariant, find-or-create semantics for each
 * institution kind, and the journal acceptance / rejection accounting.
 */
class InstitutionServiceTest {

    private UniversityRepository         universities;
    private JournalRepository            journals;
    private FundingAgencyRepository      fundingAgencies;
    private ReplicationCouncilRepository councils;
    private ResearchPaperRepository      papers;
    private PaperCitationRepository      citations;

    @BeforeEach
    void setUp() {
        universities    = mock(UniversityRepository.class);
        journals        = mock(JournalRepository.class);
        fundingAgencies = mock(FundingAgencyRepository.class);
        councils        = mock(ReplicationCouncilRepository.class);
        papers          = mock(ResearchPaperRepository.class);
        citations       = mock(PaperCitationRepository.class);
    }

    @Test
    void disabledServiceWritesNothing() {
        InstitutionService svc = new InstitutionService(
                false, false, false, universities, journals, fundingAgencies, councils,
                papers, citations);

        InstitutionService.BootstrapResult result =
                svc.bootstrapForPaper(UUID.randomUUID(), "MATHEMATICS");

        assertThat(result).isNull();
        verify(universities,    never()).save(any());
        verify(journals,        never()).save(any());
        verify(fundingAgencies, never()).save(any());
        verify(councils,        never()).save(any());
    }

    @Test
    void enabledBootstrapCreatesAllFourInstitutionsOnFirstCall() {
        InstitutionService svc = enabled();
        UUID room = UUID.randomUUID();

        when(universities.findFirstByRoomId(room)).thenReturn(Optional.empty());
        when(journals.findFirstByRoomIdAndFocusDomain(eq(room), eq("MATHEMATICS")))
                .thenReturn(Optional.empty());
        when(fundingAgencies.findFirstByRoomId(room)).thenReturn(Optional.empty());
        when(councils.findFirstByRoomId(room)).thenReturn(Optional.empty());

        AtomicReference<University> savedUni = new AtomicReference<>();
        AtomicReference<Journal>    savedJournal = new AtomicReference<>();
        when(universities.save(any(University.class))).thenAnswer(inv -> {
            savedUni.set(inv.getArgument(0)); return inv.getArgument(0);
        });
        when(journals.save(any(Journal.class))).thenAnswer(inv -> {
            savedJournal.set(inv.getArgument(0)); return inv.getArgument(0);
        });

        InstitutionService.BootstrapResult r = svc.bootstrapForPaper(room, "MATHEMATICS");

        assertThat(r).isNotNull();
        assertThat(r.university()).isSameAs(savedUni.get());
        assertThat(r.journal()).isSameAs(savedJournal.get());
        assertThat(savedJournal.get().getFocusDomain()).isEqualTo("MATHEMATICS");
        verify(fundingAgencies).save(any());
        verify(councils).save(any());
    }

    @Test
    void existingInstitutionsAreReusedRatherThanRecreated() {
        InstitutionService svc = enabled();
        UUID room = UUID.randomUUID();

        University existingUni = new University(room, "Existing U", null, java.time.Instant.now());
        Journal existingJournal = new Journal(room, "Existing J", "MATHEMATICS", java.time.Instant.now());
        FundingAgency existingAgency = new FundingAgency(room, "Existing A", null, java.time.Instant.now());
        ReplicationCouncil existingCouncil = new ReplicationCouncil(room, "Existing C", java.time.Instant.now());

        when(universities.findFirstByRoomId(room)).thenReturn(Optional.of(existingUni));
        when(journals.findFirstByRoomIdAndFocusDomain(eq(room), eq("MATHEMATICS")))
                .thenReturn(Optional.of(existingJournal));
        when(fundingAgencies.findFirstByRoomId(room)).thenReturn(Optional.of(existingAgency));
        when(councils.findFirstByRoomId(room)).thenReturn(Optional.of(existingCouncil));

        InstitutionService.BootstrapResult r = svc.bootstrapForPaper(room, "MATHEMATICS");

        assertThat(r.university()).isSameAs(existingUni);
        assertThat(r.journal()).isSameAs(existingJournal);
        verify(universities,    never()).save(any());
        verify(journals,        never()).save(any());
        verify(fundingAgencies, never()).save(any());
        verify(councils,        never()).save(any());
    }

    @Test
    void newDomainTriggersNewJournalButReusesUniversity() {
        InstitutionService svc = enabled();
        UUID room = UUID.randomUUID();
        University existingUni = new University(room, "U", null, java.time.Instant.now());

        when(universities.findFirstByRoomId(room)).thenReturn(Optional.of(existingUni));
        when(journals.findFirstByRoomIdAndFocusDomain(eq(room), eq("PHYSICS")))
                .thenReturn(Optional.empty());
        when(fundingAgencies.findFirstByRoomId(room))
                .thenReturn(Optional.of(new FundingAgency(room, "A", null, java.time.Instant.now())));
        when(councils.findFirstByRoomId(room))
                .thenReturn(Optional.of(new ReplicationCouncil(room, "C", java.time.Instant.now())));
        when(journals.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

        InstitutionService.BootstrapResult r = svc.bootstrapForPaper(room, "PHYSICS");

        assertThat(r.university()).isSameAs(existingUni);
        verify(universities, never()).save(any());
        verify(journals).save(any(Journal.class));
    }

    @Test
    void recordJournalAcceptanceUpdatesCountersAndRejectionRate() {
        InstitutionService svc = enabled();
        UUID journalId = UUID.randomUUID();
        Journal j = new Journal(UUID.randomUUID(), "J", "MATHEMATICS", java.time.Instant.now());
        when(journals.findById(journalId)).thenReturn(Optional.of(j));
        when(journals.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.recordJournalAcceptance(journalId);
        svc.recordJournalAcceptance(journalId);
        svc.recordJournalRejection(journalId);

        assertThat(j.getPapersAccepted()).isEqualTo(2);
        assertThat(j.getPapersRejected()).isEqualTo(1);
        assertThat(j.getRejectionRate()).isCloseTo(1.0 / 3.0, within(1e-9));
    }

    @Test
    void journalEditorsForReturnsEmptyWhenDisabled() {
        InstitutionService svc = new InstitutionService(
                false, false, false, universities, journals, fundingAgencies, councils,
                papers, citations);
        UUID room = UUID.randomUUID();

        java.util.List<Researcher> editors = svc.journalEditorsFor(
                room, "MATHEMATICS", java.util.List.of(researcher("bot-1", "MATHEMATICS", 0.7)),
                5);

        assertThat(editors).isEmpty();
    }

    @Test
    void journalEditorsForReturnsEmptyWhenJournalMissing() {
        InstitutionService svc = enabled();
        UUID room = UUID.randomUUID();
        when(journals.findFirstByRoomIdAndFocusDomain(eq(room), eq("MATHEMATICS")))
                .thenReturn(Optional.empty());

        assertThat(svc.journalEditorsFor(
                room, "MATHEMATICS", java.util.List.of(researcher("bot-1", "MATHEMATICS", 0.7)),
                5)).isEmpty();
    }

    @Test
    void journalEditorsForRanksByReputationDescending() {
        InstitutionService svc = enabled();
        UUID room = UUID.randomUUID();
        when(journals.findFirstByRoomIdAndFocusDomain(eq(room), eq("MATHEMATICS")))
                .thenReturn(Optional.of(new Journal(
                        room, "Math Journal", "MATHEMATICS", java.time.Instant.now())));

        java.util.List<Researcher> pool = java.util.List.of(
                researcher("bot-low",   "MATHEMATICS", 0.3),
                researcher("bot-high",  "MATHEMATICS", 0.9),
                researcher("bot-mid",   "MATHEMATICS", 0.6),
                researcher("bot-other", "PHYSICS",     0.95));

        java.util.List<Researcher> editors = svc.journalEditorsFor(
                room, "MATHEMATICS", pool, 5);

        // PHYSICS bot is filtered out; remaining sorted desc by reputation.
        assertThat(editors).extracting(Researcher::getBotId)
                .containsExactly("bot-high", "bot-mid", "bot-low");
    }

    @Test
    void journalEditorsForRespectsLimit() {
        InstitutionService svc = enabled();
        UUID room = UUID.randomUUID();
        when(journals.findFirstByRoomIdAndFocusDomain(eq(room), eq("MATHEMATICS")))
                .thenReturn(Optional.of(new Journal(
                        room, "Math Journal", "MATHEMATICS", java.time.Instant.now())));

        java.util.List<Researcher> pool = java.util.List.of(
                researcher("bot-a", "MATHEMATICS", 0.9),
                researcher("bot-b", "MATHEMATICS", 0.8),
                researcher("bot-c", "MATHEMATICS", 0.7),
                researcher("bot-d", "MATHEMATICS", 0.6));

        assertThat(svc.journalEditorsFor(room, "MATHEMATICS", pool, 2))
                .extracting(Researcher::getBotId)
                .containsExactly("bot-a", "bot-b");
    }

    @Test
    void recordAcceptanceDoesNotRecomputeImpactFactorWhenFlagOff() {
        InstitutionService svc = enabled();  // institutions on, impact-factor off
        UUID journalId = UUID.randomUUID();
        Journal j = new Journal(UUID.randomUUID(), "J", "MATHEMATICS", java.time.Instant.now());
        j.setImpactFactor(7.5);  // pre-existing value should be preserved
        when(journals.findById(journalId)).thenReturn(Optional.of(j));
        when(journals.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.recordJournalAcceptance(journalId);

        verify(papers, never()).findByJournalId(any());
        assertThat(j.getImpactFactor()).isEqualTo(7.5);
    }

    @Test
    void recordAcceptanceWithImpactFactorOnAndEmptyJournalSetsZero() {
        InstitutionService svc = enabledWithImpactFactor();
        UUID journalId = UUID.randomUUID();
        Journal j = new Journal(UUID.randomUUID(), "J", "MATHEMATICS", java.time.Instant.now());
        j.setImpactFactor(3.0);
        when(journals.findById(journalId)).thenReturn(Optional.of(j));
        when(journals.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(papers.findByJournalId(any())).thenReturn(java.util.List.of());

        svc.recordJournalAcceptance(journalId);

        assertThat(j.getImpactFactor()).isEqualTo(0.0);
    }

    @Test
    void recordAcceptanceWithImpactFactorOnComputesMeanCitationCount() {
        InstitutionService svc = enabledWithImpactFactor();
        UUID journalId = UUID.randomUUID();
        Journal j = new Journal(UUID.randomUUID(), "J", "MATHEMATICS", java.time.Instant.now());
        when(journals.findById(journalId)).thenReturn(Optional.of(j));
        when(journals.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

        ResearchPaper p1 = mock(ResearchPaper.class);
        ResearchPaper p2 = mock(ResearchPaper.class);
        ResearchPaper p3 = mock(ResearchPaper.class);
        when(p1.paperId()).thenReturn("P-aaaa-1");
        when(p2.paperId()).thenReturn("P-aaaa-2");
        when(p3.paperId()).thenReturn("P-aaaa-3");
        when(papers.findByJournalId(any())).thenReturn(java.util.List.of(p1, p2, p3));
        when(citations.countByCitedPaperId("P-aaaa-1")).thenReturn(4L);
        when(citations.countByCitedPaperId("P-aaaa-2")).thenReturn(2L);
        when(citations.countByCitedPaperId("P-aaaa-3")).thenReturn(0L);

        svc.recordJournalAcceptance(journalId);

        // (4 + 2 + 0) / 3 = 2.0
        assertThat(j.getImpactFactor()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void recordRejectionAlsoRecomputesImpactFactorWhenFlagOn() {
        InstitutionService svc = enabledWithImpactFactor();
        UUID journalId = UUID.randomUUID();
        Journal j = new Journal(UUID.randomUUID(), "J", "MATHEMATICS", java.time.Instant.now());
        when(journals.findById(journalId)).thenReturn(Optional.of(j));
        when(journals.save(any(Journal.class))).thenAnswer(inv -> inv.getArgument(0));

        ResearchPaper p1 = mock(ResearchPaper.class);
        when(p1.paperId()).thenReturn("P-bbbb-1");
        when(papers.findByJournalId(any())).thenReturn(java.util.List.of(p1));
        when(citations.countByCitedPaperId("P-bbbb-1")).thenReturn(5L);

        svc.recordJournalRejection(journalId);

        assertThat(j.getImpactFactor()).isCloseTo(5.0, within(1e-9));
    }

    // ---- Phase 4b: ReplicationCouncil triggering ----

    @Test
    void councilDoesNotWantReplicationWhenFlagOff() {
        InstitutionService svc = enabled();  // institutions on, council off
        NarrationResult n = narrationWithTypedCitation(RelationshipType.CONTRADICTS);
        assertThat(svc.councilWantsReplication(n)).isFalse();
    }

    @Test
    void councilWantsReplicationOnContradictsCitation() {
        InstitutionService svc = enabledWithCouncil();
        NarrationResult n = narrationWithTypedCitation(RelationshipType.CONTRADICTS);
        assertThat(svc.councilWantsReplication(n)).isTrue();
    }

    @Test
    void councilDoesNotWantReplicationOnSupportsCitation() {
        InstitutionService svc = enabledWithCouncil();
        NarrationResult n = narrationWithTypedCitation(RelationshipType.SUPPORTS);
        assertThat(svc.councilWantsReplication(n)).isFalse();
    }

    @Test
    void councilDoesNotWantReplicationOnNullOrEmptyNarration() {
        InstitutionService svc = enabledWithCouncil();
        assertThat(svc.councilWantsReplication(null)).isFalse();
        NarrationResult empty = new NarrationResult(
                "hyp", "find", "conc", null, "model", 0.5,
                java.util.List.of(), "callpath", java.util.List.of());
        assertThat(svc.councilWantsReplication(empty)).isFalse();
    }

    @Test
    void recordReplicationEnqueuedIsNoOpWhenCouncilFlagOff() {
        InstitutionService svc = enabled();  // council flag off
        UUID room = UUID.randomUUID();
        svc.recordReplicationEnqueued(room);
        verify(councils, never()).findFirstByRoomId(any());
        verify(councils, never()).save(any());
    }

    @Test
    void recordReplicationEnqueuedIncrementsActiveCount() {
        InstitutionService svc = enabledWithCouncil();
        UUID room = UUID.randomUUID();
        ReplicationCouncil c = new ReplicationCouncil(room, "C", java.time.Instant.now());
        when(councils.findFirstByRoomId(room)).thenReturn(Optional.of(c));
        when(councils.save(any(ReplicationCouncil.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.recordReplicationEnqueued(room);
        svc.recordReplicationEnqueued(room);

        assertThat(c.getActiveReplicationCount()).isEqualTo(2);
        assertThat(c.getCompletedCount()).isZero();
    }

    @Test
    void recordReplicationCompletedDecrementsActiveAndIncrementsCompleted() {
        InstitutionService svc = enabledWithCouncil();
        UUID room = UUID.randomUUID();
        ReplicationCouncil c = new ReplicationCouncil(room, "C", java.time.Instant.now());
        c.setActiveReplicationCount(3);
        when(councils.findFirstByRoomId(room)).thenReturn(Optional.of(c));
        when(councils.save(any(ReplicationCouncil.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.recordReplicationCompleted(room);

        assertThat(c.getActiveReplicationCount()).isEqualTo(2);
        assertThat(c.getCompletedCount()).isEqualTo(1);
    }

    @Test
    void rejectionRateClampedWhenAllRejected() {
        Journal j = new Journal(UUID.randomUUID(), "J", "MATHEMATICS", java.time.Instant.now());
        j.recordRejection();
        j.recordRejection();
        assertThat(j.getRejectionRate()).isEqualTo(1.0);
    }

    // ---- helpers ----

    private InstitutionService enabled() {
        return new InstitutionService(true, false, false, universities, journals,
                fundingAgencies, councils, papers, citations);
    }

    private InstitutionService enabledWithImpactFactor() {
        return new InstitutionService(true, true, false, universities, journals,
                fundingAgencies, councils, papers, citations);
    }

    private InstitutionService enabledWithCouncil() {
        return new InstitutionService(true, false, true, universities, journals,
                fundingAgencies, councils, papers, citations);
    }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }

    private static NarrationResult narrationWithTypedCitation(RelationshipType type) {
        return new NarrationResult(
                "hyp", "find", "conc",
                /* followUp = */ null, /* aiModel = */ "model",
                /* confidenceScore = */ 0.5,
                java.util.List.of("P-zzzz-1"),
                /* callPath = */ "callpath",
                java.util.List.of(new NarrationResult.TypedCitation("P-zzzz-1", type, 0.9)));
    }

    private static Researcher researcher(String botId, String domain, double reputation) {
        BotLifecycleState ls = new BotLifecycleState();
        BotGenome g = BotGenome.initialise(UUID.randomUUID(), 0);
        g.setDomain(domain);
        ls.setGenome(g);
        ls.setReputation(reputation);
        return new Researcher(botId, ls);
    }
}
