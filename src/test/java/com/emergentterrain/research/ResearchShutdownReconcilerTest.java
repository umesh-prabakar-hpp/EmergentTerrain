package com.emergentterrain.research;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins bounded hard-stop reconciliation independently of Spring/JPA. */
class ResearchShutdownReconcilerTest {

    @Test
    void marksTheProductionConstructorForSpringInjection() throws Exception {
        assertThat(ResearchShutdownReconciler.class.getConstructor(
                ResearchCycleService.class, ObjectProvider.class).getAnnotation(Autowired.class)).isNotNull();
    }

    @Test
    void settlesNormallyBeforeDeadline_withoutSuppressingAnything() throws Exception {
        ResearchCycleService cycle = new ResearchCycleService();
        cycle.markDraftInFlight("P-1");
        AtomicBoolean reconciliationCalled = new AtomicBoolean();
        ResearchShutdownReconciler reconciler = new ResearchShutdownReconciler(cycle,
                (drafts, lockMs) -> {
                    reconciliationCalled.set(true);
                    return new UniversityService.ShutdownReconciliation(drafts, true);
                });
        reconciler.setTimeouts(500L, 10L);

        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(25L);
                cycle.decrementDraftQueue("P-1");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ResearchShutdownReconciler.Result result = reconciler.reconcileBeforeContextClose();
        worker.join();

        assertThat(result.drained()).isTrue();
        assertThat(result.safeToClose()).isTrue();
        assertThat(result.unresolved()).isEmpty();
        assertThat(reconciliationCalled).isFalse();
    }

    @Test
    void timeoutForwardsEveryDraftIdAndLastPhase_toFailClosedReconciliation() {
        ResearchCycleService cycle = new ResearchCycleService();
        cycle.markDraftInFlight("P-1");
        cycle.markDraftInFlight("P-2");
        cycle.updateDraftPhase("P-1", "publish");
        cycle.updateDraftPhase("P-2", "narration:professor_tools");
        AtomicReference<List<ResearchCycleService.InFlightDraft>> observed = new AtomicReference<>();
        ResearchShutdownReconciler reconciler = new ResearchShutdownReconciler(cycle,
                (drafts, lockMs) -> {
                    observed.set(List.copyOf(drafts));
                    return new UniversityService.ShutdownReconciliation(drafts, true);
                });
        reconciler.setTimeouts(0L, 10L);

        ResearchShutdownReconciler.Result result = reconciler.reconcileBeforeContextClose();

        assertThat(result.drained()).isFalse();
        assertThat(result.safeToClose()).isTrue();
        assertThat(observed.get()).extracting(ResearchCycleService.InFlightDraft::paperId)
                .containsExactly("P-1", "P-2");
        assertThat(observed.get()).extracting(ResearchCycleService.InFlightDraft::phase)
                .containsExactly("publish", "narration:professor_tools");
    }
}
