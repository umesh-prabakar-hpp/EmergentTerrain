package com.emergentterrain.research;

import com.emergentterrain.repo.PromptTemplateVariantRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4c Piece 3 PR3 — pins
 * {@link PromptTemplateVariantStatsService} terminal-outcome writes.
 */
class PromptTemplateVariantStatsServiceTest {

    private static final Instant T0 = Instant.parse("2026-05-20T00:00:00Z");

    @Test
    void disabled_isSilentNoop() throws Exception {
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        PromptTemplateVariantStatsService svc = service(repo, /* enabled */ false);
        svc.record("x", "v1", PromptTemplateVariantStatsService.Terminal.CONFIRMED);
        verify(repo, never()).findByTemplateIdAndVersionTag(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void unknownTemplate_logsWarnAndSkips() throws Exception {
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findByTemplateIdAndVersionTag(any(), any())).thenReturn(Optional.empty());
        PromptTemplateVariantStatsService svc = service(repo, true);
        svc.record("nope", "v1", PromptTemplateVariantStatsService.Terminal.FAILED);
        verify(repo, never()).save(any());
    }

    @Test
    void confirmed_incrementsBothPapersNarratedAndConfirmed() throws Exception {
        PromptTemplateVariant row = new PromptTemplateVariant("x", "v1", "b", T0);
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findByTemplateIdAndVersionTag("x", "v1")).thenReturn(Optional.of(row));
        PromptTemplateVariantStatsService svc = service(repo, true);

        svc.record("x", "v1", PromptTemplateVariantStatsService.Terminal.CONFIRMED);

        assertThat(row.getPapersNarrated()).isEqualTo(1);
        assertThat(row.getReplicationsConfirmed()).isEqualTo(1);
        assertThat(row.getReplicationsFailed()).isZero();
        verify(repo, times(1)).save(row);
    }

    @Test
    void failed_incrementsBothPapersNarratedAndFailed() throws Exception {
        PromptTemplateVariant row = new PromptTemplateVariant("x", "v1", "b", T0);
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findByTemplateIdAndVersionTag("x", "v1")).thenReturn(Optional.of(row));
        PromptTemplateVariantStatsService svc = service(repo, true);

        svc.record("x", "v1", PromptTemplateVariantStatsService.Terminal.FAILED);

        assertThat(row.getPapersNarrated()).isEqualTo(1);
        assertThat(row.getReplicationsConfirmed()).isZero();
        assertThat(row.getReplicationsFailed()).isEqualTo(1);
    }

    @Test
    void published_incrementsOnlyPapersNarrated() throws Exception {
        // Papers that reach terminal status REJECTED / NULL_RESULT /
        // NEEDS_REVISION at publish time never reach replication. The
        // PUBLISHED terminal bumps papersNarrated only — denominator
        // grows, no numerator attribution.
        PromptTemplateVariant row = new PromptTemplateVariant("x", "v1", "b", T0);
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findByTemplateIdAndVersionTag("x", "v1")).thenReturn(Optional.of(row));
        PromptTemplateVariantStatsService svc = service(repo, true);

        svc.recordPublished("x", "v1");

        assertThat(row.getPapersNarrated()).isEqualTo(1);
        assertThat(row.getReplicationsConfirmed()).isZero();
        assertThat(row.getReplicationsFailed()).isZero();
    }

    @Test
    void nullOrBlankTemplate_skipsWithoutThrowing() throws Exception {
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        PromptTemplateVariantStatsService svc = service(repo, true);
        svc.record(null, "v1", PromptTemplateVariantStatsService.Terminal.CONFIRMED);
        svc.record("", "v1",  PromptTemplateVariantStatsService.Terminal.CONFIRMED);
        svc.record("x", null, PromptTemplateVariantStatsService.Terminal.CONFIRMED);
        svc.record("x", "",   PromptTemplateVariantStatsService.Terminal.CONFIRMED);
        svc.record("x", "v1", null);
        verify(repo, never()).findByTemplateIdAndVersionTag(any(), any());
    }

    @Test
    void saveFailureIsSwallowed() throws Exception {
        // Stats-write failures must not poison the narration / replication
        // path; defensive try-catch.
        PromptTemplateVariant row = new PromptTemplateVariant("x", "v1", "b", T0);
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findByTemplateIdAndVersionTag("x", "v1")).thenReturn(Optional.of(row));
        when(repo.save(any(PromptTemplateVariant.class)))
                .thenThrow(new RuntimeException("simulated DB outage"));
        PromptTemplateVariantStatsService svc = service(repo, true);
        // Does not throw — that's the contract.
        svc.record("x", "v1", PromptTemplateVariantStatsService.Terminal.CONFIRMED);
    }

    @Test
    void multipleConfirms_accumulate() throws Exception {
        PromptTemplateVariant row = new PromptTemplateVariant("x", "v1", "b", T0);
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findByTemplateIdAndVersionTag("x", "v1")).thenReturn(Optional.of(row));
        PromptTemplateVariantStatsService svc = service(repo, true);

        for (int i = 0; i < 5; i++) {
            svc.record("x", "v1", PromptTemplateVariantStatsService.Terminal.CONFIRMED);
        }
        for (int i = 0; i < 3; i++) {
            svc.record("x", "v1", PromptTemplateVariantStatsService.Terminal.FAILED);
        }
        svc.recordPublished("x", "v1");

        assertThat(row.getPapersNarrated()).isEqualTo(9);
        assertThat(row.getReplicationsConfirmed()).isEqualTo(5);
        assertThat(row.getReplicationsFailed()).isEqualTo(3);
    }

    // ────────────── helpers ──────────────

    private static PromptTemplateVariantStatsService service(
            PromptTemplateVariantRepository repo, boolean enabled) throws Exception {
        PromptTemplateVariantStatsService svc = new PromptTemplateVariantStatsService(repo);
        Field f = PromptTemplateVariantStatsService.class.getDeclaredField("enabled");
        f.setAccessible(true);
        f.set(svc, enabled);
        return svc;
    }
}
