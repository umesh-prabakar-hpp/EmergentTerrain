package com.emergentterrain.research;

import com.emergentterrain.repo.PromptTemplateVariantRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4c Piece 3 PR4 — pins
 * {@link PromptTemplateRollbackJudge#shouldRollBack} and the
 * {@link PromptTemplateRollbackJudge#sweep} batch contract.
 *
 * <p>Acceptance per the piece-3 design doc: "a 0.30 failure rate vs
 * 0.10 baseline at n=300 each triggers rollback; n=100 does not."
 * Both cases land below in
 * {@link #shouldRollBack_significantWorseAtN300_rollsBack} and
 * {@link #shouldRollBack_significantWorseAtN100_doesNotRollBack}.
 */
class PromptTemplateRollbackJudgeTest {

    private static final Instant T0 = Instant.parse("2026-05-20T00:00:00Z");

    // ────────────── shouldRollBack static decision ──────────────

    @Test
    void shouldRollBack_significantWorseAtN300_rollsBack() {
        PromptTemplateVariant challenger = variant("x", "v2", /* conf */ 210, /* fail */ 90);   // 30% fail rate, n=300
        PromptTemplateVariant incumbent  = variant("x", "v1", /* conf */ 270, /* fail */ 30);   // 10% fail rate, n=300
        assertThat(PromptTemplateRollbackJudge.shouldRollBack(challenger, incumbent)).isTrue();
    }

    @Test
    void shouldRollBack_significantWorseAtN100_doesNotRollBack() {
        // Sample size below MIN_TERMINAL_OUTCOMES_PER_ARM (=200) → skip
        // regardless of how bad the failure rate looks. Protects against
        // noisy early-life decisions.
        PromptTemplateVariant challenger = variant("x", "v2", /* conf */ 70, /* fail */ 30);    // 30%, n=100
        PromptTemplateVariant incumbent  = variant("x", "v1", /* conf */ 90, /* fail */ 10);    // 10%, n=100
        assertThat(PromptTemplateRollbackJudge.shouldRollBack(challenger, incumbent)).isFalse();
    }

    @Test
    void shouldRollBack_marginalDifferenceUnderMDE_doesNotRollBack() {
        // MDE is 10pp. Challenger 14% vs incumbent 10% = 4pp gap; even
        // with n=1000 each, the absolute-margin check fails.
        PromptTemplateVariant challenger = variant("x", "v2", 860, 140);  // 14%
        PromptTemplateVariant incumbent  = variant("x", "v1", 900, 100);  // 10%
        assertThat(PromptTemplateRollbackJudge.shouldRollBack(challenger, incumbent)).isFalse();
    }

    @Test
    void shouldRollBack_challengerBetter_neverRollsBack() {
        // One-sided test: a challenger BETTER than incumbent (lower
        // failure rate) is never rolled back. Promotion is operator
        // action, not scheduler action.
        PromptTemplateVariant challenger = variant("x", "v2", 270, 30);   // 10%
        PromptTemplateVariant incumbent  = variant("x", "v1", 210, 90);   // 30%
        assertThat(PromptTemplateRollbackJudge.shouldRollBack(challenger, incumbent)).isFalse();
    }

    @Test
    void shouldRollBack_zeroSampleArm_skips() {
        PromptTemplateVariant challenger = variant("x", "v2", 0, 0);
        PromptTemplateVariant incumbent  = variant("x", "v1", 270, 30);
        assertThat(PromptTemplateRollbackJudge.shouldRollBack(challenger, incumbent)).isFalse();
    }

    // ────────────── sweep() contract ──────────────

    @Test
    void sweep_disabled_isNoop() throws Exception {
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        PromptTemplateRollbackJudge judge = judge(repo, /* enabled */ false);
        judge.scanForRollbacks();
        verify(repo, never()).findAll();
    }

    @Test
    void sweep_singleVariantPerId_skipsTheZTest() throws Exception {
        PromptTemplateVariant solo = variant("x", "v1", 1000, 100);
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findAll()).thenReturn(List.of(solo));
        PromptTemplateRollbackJudge judge = judge(repo, true);
        int rolled = judge.sweep();
        assertThat(rolled).isZero();
        verify(repo, never()).save(any(PromptTemplateVariant.class));
        assertThat(solo.getStatus()).isEqualTo(PromptTemplateVariant.Status.ACTIVE);
    }

    @Test
    void sweep_incumbentVsLosingChallenger_rollsBackChallenger() throws Exception {
        PromptTemplateVariant incumbent  = variant("x", "v1", 270, 30);   // 10%, n=300 — incumbent by samples
        PromptTemplateVariant challenger = variant("x", "v2", 210, 90);   // 30%, n=300 — meaningfully worse
        // Make incumbent dominate by papersNarrated so the judge picks
        // v1 as the incumbent of the (id) group.
        for (int i = 0; i < 350; i++) incumbent.incrementPapersNarrated();
        for (int i = 0; i < 300; i++) challenger.incrementPapersNarrated();

        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findAll()).thenReturn(List.of(incumbent, challenger));
        PromptTemplateRollbackJudge judge = judge(repo, true);
        int rolled = judge.sweep();
        assertThat(rolled).isEqualTo(1);
        assertThat(challenger.getStatus()).isEqualTo(PromptTemplateVariant.Status.ROLLED_BACK);
        assertThat(incumbent.getStatus()).isEqualTo(PromptTemplateVariant.Status.ACTIVE);
        verify(repo, times(1)).save(challenger);
    }

    @Test
    void sweep_ignoresAlreadyRolledBack() throws Exception {
        // ROLLED_BACK variants don't participate — the judge only
        // looks at ACTIVE ones. A previously-rolled-back challenger
        // can't get re-evaluated by this job.
        PromptTemplateVariant active = variant("x", "v1", 270, 30);
        PromptTemplateVariant rolled = variant("x", "v2", 210, 90);
        rolled.setStatus(PromptTemplateVariant.Status.ROLLED_BACK);

        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findAll()).thenReturn(List.of(active, rolled));
        PromptTemplateRollbackJudge judge = judge(repo, true);
        int rolled1 = judge.sweep();
        assertThat(rolled1).isZero();
    }

    @Test
    void sweep_twoChallengersOneLoses_rollsBackOnlyTheLoser() throws Exception {
        PromptTemplateVariant incumbent      = variant("x", "v1", 270, 30);   // 10%, n=300
        PromptTemplateVariant goodChallenger = variant("x", "v2", 260, 40);   // ~13%, n=300 — within MDE
        PromptTemplateVariant badChallenger  = variant("x", "v3", 210, 90);   // 30%, n=300 — over MDE
        for (int i = 0; i < 320; i++) incumbent.incrementPapersNarrated();
        for (int i = 0; i < 300; i++) goodChallenger.incrementPapersNarrated();
        for (int i = 0; i < 300; i++) badChallenger.incrementPapersNarrated();

        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findAll()).thenReturn(List.of(incumbent, goodChallenger, badChallenger));
        PromptTemplateRollbackJudge judge = judge(repo, true);
        int rolled = judge.sweep();
        assertThat(rolled).isEqualTo(1);
        assertThat(goodChallenger.getStatus()).isEqualTo(PromptTemplateVariant.Status.ACTIVE);
        assertThat(badChallenger.getStatus()).isEqualTo(PromptTemplateVariant.Status.ROLLED_BACK);
    }

    @Test
    void sweep_groupsByTemplateId_independentRollbacks() throws Exception {
        // Two distinct templates, each with its own incumbent +
        // challenger. Rollback decisions are per-template-id.
        PromptTemplateVariant aInc = variant("a", "v1", 270, 30);
        PromptTemplateVariant aChl = variant("a", "v2", 210, 90);
        PromptTemplateVariant bInc = variant("b", "v1", 270, 30);
        PromptTemplateVariant bChl = variant("b", "v2", 260, 40);   // within MDE
        for (int i = 0; i < 320; i++) aInc.incrementPapersNarrated();
        for (int i = 0; i < 300; i++) aChl.incrementPapersNarrated();
        for (int i = 0; i < 320; i++) bInc.incrementPapersNarrated();
        for (int i = 0; i < 300; i++) bChl.incrementPapersNarrated();

        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findAll()).thenReturn(List.of(aInc, aChl, bInc, bChl));
        PromptTemplateRollbackJudge judge = judge(repo, true);
        int rolled = judge.sweep();
        assertThat(rolled).isEqualTo(1);
        assertThat(aChl.getStatus()).isEqualTo(PromptTemplateVariant.Status.ROLLED_BACK);
        assertThat(bChl.getStatus()).isEqualTo(PromptTemplateVariant.Status.ACTIVE);
    }

    // ────────────── helpers ──────────────

    private static PromptTemplateVariant variant(String id, String version,
                                                  long confirmed, long failed) {
        PromptTemplateVariant v = new PromptTemplateVariant(id, version, "body", T0);
        for (long i = 0; i < confirmed; i++) v.incrementReplicationsConfirmed();
        for (long i = 0; i < failed; i++)    v.incrementReplicationsFailed();
        return v;
    }

    private static PromptTemplateRollbackJudge judge(PromptTemplateVariantRepository repo,
                                                      boolean enabled) throws Exception {
        PromptTemplateRollbackJudge j = new PromptTemplateRollbackJudge(repo);
        Field f = PromptTemplateRollbackJudge.class.getDeclaredField("enabled");
        f.setAccessible(true);
        f.set(j, enabled);
        return j;
    }
}
