package com.emergentterrain.research.discovery;

import com.emergentterrain.research.discovery.FrontierKnowledgeStore.ConvergenceState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 fork 2b — keep-the-best + frontier ratchet + convergence, with an
 * in-memory fake store (no DB).
 */
class FrontierKnowledgeServiceTest {

    private static final class FakeStore implements FrontierKnowledgeStore {
        final Map<String, FrontierRecord> recs = new HashMap<>();
        final Map<String, ConvergenceState> conv = new HashMap<>();
        private static String k(String b, long x) { return b + "|" + x; }
        public Optional<FrontierRecord> find(String b, long x) { return Optional.ofNullable(recs.get(k(b, x))); }
        public void put(FrontierRecord r) { recs.put(k(r.bound(), r.x()), r); }
        public long frontierOf(String b) {
            return recs.values().stream().filter(r -> r.bound().equals(b)).mapToLong(FrontierRecord::x).max().orElse(0);
        }
        public ConvergenceState convergence(String b) { return conv.getOrDefault(b, ConvergenceState.initial()); }
        public void saveConvergence(String b, ConvergenceState s) { conv.put(b, s); }
    }

    private static EscalationCheckpoint cp(long x, Long v) {
        return new EscalationCheckpoint(x, v, EscalationVerdict.HOLDS, "discovery", 1);
    }

    private static EscalationOutcome run(long frontier, EscalationCheckpoint... cps) {
        return EscalationOutcome.exhausted(List.of(cps), frontier, null);
    }

    @Test
    void persistsValidatedCheckpointsAndRatchetsFrontier() {
        FakeStore store = new FakeStore();
        FrontierKnowledgeService svc = new FrontierKnowledgeService(store, 2);
        FrontierKnowledgeService.RunRecord rr =
                svc.record("b", run(1_000_000_000L, cp(1_000_000L, 212L), cp(1_000_000_000L, -222L)));
        assertThat(rr.recordsKept()).isEqualTo(2);
        assertThat(svc.frontier("b")).isEqualTo(1_000_000_000L);
        assertThat(store.find("b", 1_000_000L)).get().extracting(FrontierRecord::value).isEqualTo(212L);
    }

    @Test
    void keepBestPrefersHigherTrustAndDoesNotDowngrade() {
        FakeStore store = new FakeStore();
        store.put(new FrontierRecord("b", 100L, 999L, "model", TrustLevel.MODEL_FIT));   // weak prior
        FrontierKnowledgeService svc = new FrontierKnowledgeService(store, 2);

        // a FULL value at x=100 overwrites the MODEL_FIT one
        svc.record("b", run(100L, cp(100L, 50L)));
        assertThat(store.find("b", 100L)).get().extracting(FrontierRecord::trust).isEqualTo(TrustLevel.FULL);
        assertThat(store.find("b", 100L)).get().extracting(FrontierRecord::value).isEqualTo(50L);

        // re-recording the same FULL value keeps the existing one (no change)
        FrontierKnowledgeService.RunRecord rr = svc.record("b", run(100L, cp(100L, 50L)));
        assertThat(rr.recordsKept()).isZero();
    }

    @Test
    void convergesAfterStableRunsAndProgressResets() {
        FakeStore store = new FakeStore();
        FrontierKnowledgeService svc = new FrontierKnowledgeService(store, 2);

        svc.record("b", run(1_000_000_000L, cp(1_000_000_000L, 1L)));    // progress -> stable 0
        assertThat(svc.isConverged("b")).isFalse();
        svc.record("b", run(1_000_000_000L, cp(1_000_000_000L, 1L)));    // no progress -> stable 1
        assertThat(svc.isConverged("b")).isFalse();
        assertThat(svc.record("b", run(1_000_000_000L, cp(1_000_000_000L, 1L))).converged()).isTrue();  // stable 2 -> converged

        // a later run that extends the frontier resets stability (un-converges progress-wise)
        FrontierKnowledgeService.RunRecord rr =
                svc.record("b", run(1_000_000_000_000L, cp(1_000_000_000_000L, 2L)));
        assertThat(rr.stableRuns()).isZero();
        assertThat(rr.frontier()).isEqualTo(1_000_000_000_000L);
    }

    @Test
    void skippedRunDoesNotCountTowardConvergence() {
        FakeStore store = new FakeStore();
        FrontierKnowledgeService svc = new FrontierKnowledgeService(store, 1);
        FrontierKnowledgeService.RunRecord rr = svc.record("b", EscalationOutcome.disabled());  // ran=false
        assertThat(rr.converged()).isFalse();
        assertThat(rr.frontier()).isZero();
        assertThat(store.frontierOf("b")).isZero();
    }
}
