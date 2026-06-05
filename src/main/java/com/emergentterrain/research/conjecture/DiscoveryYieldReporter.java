package com.emergentterrain.research.conjecture;

import com.emergentterrain.repo.ConjectureRepository;
import com.emergentterrain.repo.DerivedScalarRepository;
import com.emergentterrain.repo.ProgramOpenQuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 30 Wave 7 — the discovery-yield health metric (sim #13, 30.6's last
 * leftover). One periodic log line tracking the generative funnel end to end,
 * so "is the cascade accumulating?" is answerable from any session's log
 * instead of ad-hoc DB queries:
 *
 * <pre>
 * seeds → live survivors → confirmedRuns high-water → anomaly-eligible (≥ minConfirmed)
 *       → promoted (gate survivors) → vocab admissions → open questions
 * </pre>
 *
 * All counts are cheap repository scans over small tables (~hundreds of rows).
 * Read-only; same cadence default as the run-budget reporter.
 */
@Component
public class DiscoveryYieldReporter {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryYieldReporter.class);

    private final ConjectureRepository conjectures;
    private final DerivedScalarRepository derivedScalars;
    private final ProgramOpenQuestionRepository openQuestions;
    private final int anomalyMinConfirmed;

    public DiscoveryYieldReporter(ConjectureRepository conjectures,
                                  DerivedScalarRepository derivedScalars,
                                  ProgramOpenQuestionRepository openQuestions,
                                  ConjectureMinerProperties minerProps) {
        this.conjectures = conjectures;
        this.derivedScalars = derivedScalars;
        this.openQuestions = openQuestions;
        this.anomalyMinConfirmed = minerProps.getAnomalyMinConfirmed();
    }

    @Scheduled(fixedDelayString = "${app.research.conjectureMiner.yieldReportIntervalMs:300000}")
    public void report() {
        try {
            long live = 0;
            long retired = 0;
            int maxConfirmed = 0;
            long anomalyEligible = 0;
            long promoted = 0;
            for (Conjecture c : conjectures.findAll()) {
                if (c.droppedByKnob() == null) {
                    live++;
                } else {
                    retired++;
                }
                if (c.confirmedRuns() > maxConfirmed) {
                    maxConfirmed = c.confirmedRuns();
                }
                if (ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE.equals(c.droppedByKnob())
                        && c.confirmedRuns() >= anomalyMinConfirmed) {
                    anomalyEligible++;
                }
                if (c.promotedAnomalyAt() != null) {
                    promoted++;
                }
            }
            long vocabAdmitted = derivedScalars.count();
            long questions = openQuestions.count();
            log.info("discovery-yield: live={} retired={} maxConfirmedRuns={} "
                            + "anomalyEligible(>={})={} promoted={} vocabAdmitted={} openQuestions={}",
                    live, retired, maxConfirmed,
                    anomalyMinConfirmed, anomalyEligible, promoted, vocabAdmitted, questions);
        } catch (Exception e) {
            // Observability must never disturb the pipeline.
            log.debug("discovery-yield report skipped: {}", e.getMessage());
        }
    }
}
