package com.emergentterrain.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;

/**
 * Bounded, fail-closed hand-off between a run-budget stop and Spring context teardown.
 *
 * <p>The normal drain path stops only when {@link ResearchCycleService}'s in-flight queue is
 * empty. A hard cap can arrive while an expensive narration is still in progress, however. This
 * coordinator first gives that tracked work a fixed window to settle. On expiry it records every
 * remaining durable draft ID and last phase as an intentional shutdown suppression, closes the
 * publication gate, and reports whether it is safe to close JPA/Hikari.
 */
@Component
public class ResearchShutdownReconciler {

    private static final Logger log = LoggerFactory.getLogger(ResearchShutdownReconciler.class);
    private static final long POLL_MS = 25L;

    private final ResearchCycleService researchCycleService;
    private final ObjectProvider<UniversityService> universityService;
    /** Focused test seam; production resolves UniversityService lazily to avoid a bean cycle. */
    private final BiFunction<List<ResearchCycleService.InFlightDraft>, Long,
            UniversityService.ShutdownReconciliation> testReconciliation;

    @Value("${app.research.shutdown-reconciliation.timeout-ms:30000}")
    private long timeoutMs = 30_000L;

    @Value("${app.research.shutdown-reconciliation.publication-lock-ms:5000}")
    private long publicationLockMs = 5_000L;

    @Autowired
    public ResearchShutdownReconciler(ResearchCycleService researchCycleService,
                                      ObjectProvider<UniversityService> universityService) {
        this.researchCycleService = researchCycleService;
        this.universityService = universityService;
        this.testReconciliation = null;
    }

    ResearchShutdownReconciler(ResearchCycleService researchCycleService,
                               BiFunction<List<ResearchCycleService.InFlightDraft>, Long,
                                       UniversityService.ShutdownReconciliation> testReconciliation) {
        this.researchCycleService = researchCycleService;
        this.universityService = null;
        this.testReconciliation = testReconciliation;
    }

    /**
     * Reconcile before Spring's context closes. The return value is deliberately explicit: callers
     * must not close persistence while {@link Result#safeToClose()} is false.
     */
    public Result reconcileBeforeContextClose() {
        UniversityService university = universityService == null ? null : universityService.getIfAvailable();
        if (university == null && testReconciliation == null) {
            return new Result(true, true, List.of());
        }

        long startedNs = System.nanoTime();
        long timeoutNs = Math.max(0L, timeoutMs) * 1_000_000L;
        List<ResearchCycleService.InFlightDraft> snapshots = researchCycleService.inFlightDraftSnapshots();
        if (!snapshots.isEmpty()) {
            log.warn("Shutdown reconciliation: waiting up to {}ms for {} in-flight draft(s) to settle.",
                    timeoutMs, snapshots.size());
        }
        while (!snapshots.isEmpty() && System.nanoTime() - startedNs < timeoutNs) {
            long remainingNs = timeoutNs - (System.nanoTime() - startedNs);
            LockSupport.parkNanos(Math.min(POLL_MS * 1_000_000L, Math.max(1L, remainingNs)));
            if (Thread.currentThread().isInterrupted()) {
                log.warn("Shutdown reconciliation interrupted while draining {} in-flight draft(s).",
                        snapshots.size());
                break;
            }
            snapshots = researchCycleService.inFlightDraftSnapshots();
        }
        if (snapshots.isEmpty()) {
            log.info("Shutdown reconciliation: all in-flight drafts settled in {}ms; context close is safe.",
                    (System.nanoTime() - startedNs) / 1_000_000L);
            return new Result(true, true, List.of());
        }

        UniversityService.ShutdownReconciliation publication = testReconciliation != null
                ? testReconciliation.apply(snapshots, publicationLockMs)
                : university.reconcileInFlightDrafts(snapshots, publicationLockMs);
        return new Result(false, publication.publicationIdle(), publication.unresolved());
    }

    /** Test seam for focused unit tests without a Spring property binder. */
    void setTimeouts(long drainTimeoutMs, long publicationGateTimeoutMs) {
        this.timeoutMs = drainTimeoutMs;
        this.publicationLockMs = publicationGateTimeoutMs;
    }

    public record Result(boolean drained, boolean safeToClose,
                         List<ResearchCycleService.InFlightDraft> unresolved) {}
}
