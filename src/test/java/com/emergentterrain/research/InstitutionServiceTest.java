package com.emergentterrain.research;

import com.emergentterrain.repo.FundingAgencyRepository;
import com.emergentterrain.repo.JournalRepository;
import com.emergentterrain.repo.ReplicationCouncilRepository;
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

    @BeforeEach
    void setUp() {
        universities    = mock(UniversityRepository.class);
        journals        = mock(JournalRepository.class);
        fundingAgencies = mock(FundingAgencyRepository.class);
        councils        = mock(ReplicationCouncilRepository.class);
    }

    @Test
    void disabledServiceWritesNothing() {
        InstitutionService svc = new InstitutionService(
                false, universities, journals, fundingAgencies, councils);

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
    void rejectionRateClampedWhenAllRejected() {
        Journal j = new Journal(UUID.randomUUID(), "J", "MATHEMATICS", java.time.Instant.now());
        j.recordRejection();
        j.recordRejection();
        assertThat(j.getRejectionRate()).isEqualTo(1.0);
    }

    // ---- helpers ----

    private InstitutionService enabled() {
        return new InstitutionService(true, universities, journals, fundingAgencies, councils);
    }

    private static org.assertj.core.data.Offset<Double> within(double delta) {
        return org.assertj.core.data.Offset.offset(delta);
    }
}
