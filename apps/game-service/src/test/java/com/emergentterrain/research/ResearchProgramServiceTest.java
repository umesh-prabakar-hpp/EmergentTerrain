package com.emergentterrain.research;

import com.emergentterrain.repo.ProgramFailedApproachRepository;
import com.emergentterrain.repo.ProgramOpenQuestionRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.ResearchProgramRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
 * Tests for the Phase 1 Iter 4 ResearchProgramService.
 *
 * The service is gated by {@code app.research.programs.enabled}. With the flag
 * off, every entry point is a no-op. With it on, the service finds-or-creates
 * a program for the (bot, domain) pair, attaches the paper, and updates
 * evidence counters from the paper's classification.
 */
class ResearchProgramServiceTest {

    private ResearchProgramRepository       programs;
    private ProgramOpenQuestionRepository   openQuestions;
    private ProgramFailedApproachRepository failedApproaches;
    private ResearchPaperRepository         papers;

    @BeforeEach
    void setUp() {
        programs         = mock(ResearchProgramRepository.class);
        openQuestions    = mock(ProgramOpenQuestionRepository.class);
        failedApproaches = mock(ProgramFailedApproachRepository.class);
        papers           = mock(ResearchPaperRepository.class);
    }

    @Test
    void disabledServiceWritesNothing() {
        ResearchProgramService svc = new ResearchProgramService(
                false, programs, openQuestions, failedApproaches, papers);

        ResearchPaper paper = paper(UUID.randomUUID(), UUID.randomUUID(), "MATHEMATICS", 0.6);
        svc.onPaperPublished(paper, ResultClassification.SUPPORTED, "h");
        svc.recordFailedApproach(UUID.randomUUID(), "MATHEMATICS", "approach", "why", "P-x");
        svc.recordOpenQuestion(UUID.randomUUID(), "MATHEMATICS", "q?");

        verify(programs, never()).save(any());
        verify(openQuestions, never()).save(any());
        verify(failedApproaches, never()).save(any());
        verify(papers, never()).save(any());
    }

    @Test
    void enabledCreatesProgramOnFirstPaperInDomain() {
        ResearchProgramService svc = new ResearchProgramService(
                true, programs, openQuestions, failedApproaches, papers);
        UUID author = UUID.randomUUID();
        UUID room   = UUID.randomUUID();

        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(
                eq(author), eq("MATHEMATICS"), eq(ResearchProgram.Status.ACTIVE)))
                .thenReturn(Optional.empty());
        AtomicReference<ResearchProgram> savedNew = new AtomicReference<>();
        when(programs.save(any(ResearchProgram.class))).thenAnswer(inv -> {
            ResearchProgram pr = inv.getArgument(0);
            savedNew.set(pr);
            return pr;
        });

        ResearchPaper paper = paper(author, room, "MATHEMATICS", 0.6);
        svc.onPaperPublished(paper, ResultClassification.SUPPORTED, "first hypothesis");

        assertThat(savedNew.get()).isNotNull();
        assertThat(savedNew.get().getDomain()).isEqualTo("MATHEMATICS");
        assertThat(savedNew.get().getOwnerBotId()).isEqualTo(author);
        assertThat(savedNew.get().getRoomId()).isEqualTo(room);
        assertThat(savedNew.get().getLongTermObjective()).isEqualTo("first hypothesis");
    }

    @Test
    void supportedPaperIncreasesEvidenceForByPeerScore() {
        ResearchProgramService svc = new ResearchProgramService(
                true, programs, openQuestions, failedApproaches, papers);
        UUID author = UUID.randomUUID();
        ResearchProgram existing = activeProgram(author, "MATHEMATICS");

        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(existing));
        when(programs.save(any(ResearchProgram.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.onPaperPublished(paper(author, existing.getRoomId(), "MATHEMATICS", 0.7),
                ResultClassification.SUPPORTED, "h");

        assertThat(existing.getPaperCount()).isEqualTo(1);
        assertThat(existing.getEvidenceFor()).isEqualTo(0.7);
        assertThat(existing.getEvidenceAgainst()).isEqualTo(0.0);
    }

    @Test
    void partiallySupportedAddsHalfEvidence() {
        ResearchProgramService svc = new ResearchProgramService(
                true, programs, openQuestions, failedApproaches, papers);
        UUID author = UUID.randomUUID();
        ResearchProgram existing = activeProgram(author, "MATHEMATICS");

        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(existing));
        when(programs.save(any(ResearchProgram.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.onPaperPublished(paper(author, existing.getRoomId(), "MATHEMATICS", 0.6),
                ResultClassification.PARTIALLY_SUPPORTED, "h");

        assertThat(existing.getEvidenceFor()).isEqualTo(0.3);
    }

    @Test
    void nullResultIncrementsEvidenceAgainst() {
        ResearchProgramService svc = new ResearchProgramService(
                true, programs, openQuestions, failedApproaches, papers);
        UUID author = UUID.randomUUID();
        ResearchProgram existing = activeProgram(author, "MATHEMATICS");

        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(existing));
        when(programs.save(any(ResearchProgram.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.onPaperPublished(paper(author, existing.getRoomId(), "MATHEMATICS", 0.0),
                ResultClassification.NULL_RESULT, "h");

        assertThat(existing.getEvidenceAgainst()).isEqualTo(0.5);
    }

    @Test
    void programConvergesWhenEvidenceMarginCrossesThreshold() {
        ResearchProgramService svc = new ResearchProgramService(
                true, programs, openQuestions, failedApproaches, papers);
        UUID author = UUID.randomUUID();
        ResearchProgram existing = activeProgram(author, "MATHEMATICS");
        existing.setEvidenceFor(4.5);  // one strong paper away from threshold (5.0)

        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(existing));
        when(programs.save(any(ResearchProgram.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.onPaperPublished(paper(author, existing.getRoomId(), "MATHEMATICS", 0.8),
                ResultClassification.SUPPORTED, "h");

        assertThat(existing.getStatus()).isEqualTo(ResearchProgram.Status.CONVERGED);
    }

    @Test
    void recordFailedApproachWritesRowWhenProgramExists() {
        ResearchProgramService svc = new ResearchProgramService(
                true, programs, openQuestions, failedApproaches, papers);
        UUID author = UUID.randomUUID();
        ResearchProgram existing = activeProgram(author, "MATHEMATICS");

        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(existing));

        svc.recordFailedApproach(author, "MATHEMATICS", "tried 10/5", "score below threshold", "P-1");

        verify(failedApproaches).save(any(ProgramFailedApproach.class));
    }

    @Test
    void recordOpenQuestionWritesRowWhenProgramExists() {
        ResearchProgramService svc = new ResearchProgramService(
                true, programs, openQuestions, failedApproaches, papers);
        UUID author = UUID.randomUUID();
        ResearchProgram existing = activeProgram(author, "MATHEMATICS");

        when(programs.findFirstByOwnerBotIdAndDomainAndStatus(any(), any(), any()))
                .thenReturn(Optional.of(existing));

        svc.recordOpenQuestion(author, "MATHEMATICS", "What is the asymptotic density?");

        verify(openQuestions).save(any(ProgramOpenQuestion.class));
    }

    @Test
    void recordOpenQuestionIgnoresBlankInput() {
        ResearchProgramService svc = new ResearchProgramService(
                true, programs, openQuestions, failedApproaches, papers);
        svc.recordOpenQuestion(UUID.randomUUID(), "MATHEMATICS", "   ");
        verify(openQuestions, never()).save(any());
    }

    // ---- helpers ----

    private static ResearchProgram activeProgram(UUID owner, String domain) {
        ResearchProgram p = new ResearchProgram(
                owner, UUID.randomUUID(), domain,
                "Test Program", "objective",
                Instant.now());
        // Simulate persistence assigning an ID.
        try {
            java.lang.reflect.Field f = ResearchProgram.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return p;
    }

    private static ResearchPaper paper(UUID authorBotId, UUID roomId, String domain, double peerScore) {
        return new ResearchPaper(
                "P-test-1", roomId, authorBotId.toString(), domain, "topic-x",
                "Test Title", "test hypothesis", "THEORETICAL",
                "test findings", "test conclusion",
                List.of(), peerScore, "PUBLISHED",
                100L, Instant.now(), 1, 0.0, null, null);
    }
}
