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
        final Map<String, VerifiedMethod> methods = new HashMap<>();
        final Map<String, FrontierEvent> events = new HashMap<>();
        private static String k(String b, long x) { return b + "|" + x; }
        public Optional<FrontierRecord> find(String b, long x) { return Optional.ofNullable(recs.get(k(b, x))); }
        public void put(FrontierRecord r) { recs.put(k(r.bound(), r.x()), r); }
        public long frontierOf(String b) {
            return recs.values().stream().filter(r -> r.bound().equals(b)).mapToLong(FrontierRecord::x).max().orElse(0);
        }
        public ConvergenceState convergence(String b) { return conv.getOrDefault(b, ConvergenceState.initial()); }
        public void saveConvergence(String b, ConvergenceState s) { conv.put(b, s); }
        public Optional<VerifiedMethod> findMethod(String b, String sha) { return Optional.ofNullable(methods.get(b + "|" + sha)); }
        public void putMethod(VerifiedMethod m) { methods.put(m.bound() + "|" + m.methodSha256(), m); }
        public List<FrontierRecord> recordsFor(String b) {
            return recs.values().stream().filter(r -> r.bound().equals(b))
                    .sorted(java.util.Comparator.comparingLong(FrontierRecord::x)).toList();
        }
        public Optional<VerifiedMethod> bestMethod(String b) {
            return methods.values().stream().filter(m -> m.bound().equals(b))
                    .max(java.util.Comparator.comparingLong(VerifiedMethod::maxScale)
                            .thenComparing(java.util.Comparator.comparingLong(VerifiedMethod::costMs).reversed()));
        }
        public void putEvent(FrontierEvent e) { events.put(e.bound() + "|" + e.x() + "|" + e.outcome(), e); }
        public List<FrontierEvent> eventsFor(String b) {
            return events.values().stream().filter(e -> e.bound().equals(b))
                    .sorted(java.util.Comparator.comparingLong(FrontierEvent::x)).toList();
        }
    }

    private static EscalationCheckpoint cp(long x, Long v) {
        return new EscalationCheckpoint(x, v, EscalationVerdict.HOLDS, "discovery", 1);
    }

    private static EscalationCheckpoint cpm(long x, Long v, String code, long ms) {
        return new EscalationCheckpoint(x, v, EscalationVerdict.HOLDS, "discovery", ms, code);
    }

    /** A CANNOT_COMPUTE rung carrying the failed candidate + sandbox status. */
    private static EscalationCheckpoint cpFail(long x, String code, String status) {
        return new EscalationCheckpoint(x, null, EscalationVerdict.CANNOT_COMPUTE, "discovery", 0, code, status);
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
    void persistsVerifiedMethodAndKeepsBestByReach() {
        FakeStore store = new FakeStore();
        FrontierKnowledgeService svc = new FrontierKnowledgeService(store, 2);

        // first discovery rung records the method at reach 1e9
        FrontierKnowledgeService.RunRecord r1 =
                svc.record("b", run(1_000_000_000L, cpm(1_000_000_000L, 1L, "f(x)=...", 500)));
        assertThat(r1.methodsKept()).isEqualTo(1);
        assertThat(store.bestMethod("b")).get().extracting(VerifiedMethod::maxScale).isEqualTo(1_000_000_000L);

        // same method re-validated further out updates reach; counts as kept
        FrontierKnowledgeService.RunRecord r2 =
                svc.record("b", run(1_000_000_000_000L, cpm(1_000_000_000_000L, 1L, "f(x)=...", 800)));
        assertThat(r2.methodsKept()).isEqualTo(1);
        assertThat(store.bestMethod("b")).get().extracting(VerifiedMethod::maxScale).isEqualTo(1_000_000_000_000L);

        // same method at an equal-or-lower reach is not re-kept
        FrontierKnowledgeService.RunRecord r3 =
                svc.record("b", run(1_000_000_000_000L, cpm(1_000_000_000L, 1L, "f(x)=...", 100)));
        assertThat(r3.methodsKept()).isZero();
    }

    @Test
    void persistsFailureEventsAndRecallSurfacesBoundary() {
        FakeStore store = new FakeStore();
        FrontierKnowledgeService svc = new FrontierKnowledgeService(store, 2);
        // a run that holds at 1e6 (sieve) then OOMs at 2e11 (discovery)
        svc.record("b", run(1_000_000L, cp(1_000_000L, 212L), cpFail(200_000_000_000L, "candidate(x)=ones(x)", "oom")));

        assertThat(store.events.values()).anySatisfy(e -> {
            assertThat(e.outcome()).isEqualTo("oom");
            assertThat(e.x()).isEqualTo(200_000_000_000L);
            assertThat(e.code()).isEqualTo("candidate(x)=ones(x)");
            assertThat(e.codeSha256()).isNotBlank();
        });
        String text = svc.recallText("b");
        assertThat(text).contains("Computational boundaries").contains("oom").contains("200000000000");
        assertThat(svc.recall("b").events()).hasSize(1);
    }

    @Test
    void recallTextCarriesTrustVerbatim() {
        FakeStore store = new FakeStore();
        FrontierKnowledgeService svc = new FrontierKnowledgeService(store, 2);
        svc.record("b", run(1_000_000_000L, cpm(1_000_000L, 212L, null, 0), cpm(1_000_000_000L, -222L, "g(x)=...", 700)));

        String text = svc.recallText("b");
        assertThat(text).contains("FULL").contains("1000000000").contains("not a proof");
        assertThat(svc.recall("b").bestMethod()).isPresent();
        assertThat(svc.recallText("absent")).contains("No accumulated frontier knowledge");
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
