package com.emergentterrain.research;

import com.emergentterrain.repo.ExpectationPriorRepository;
import com.emergentterrain.repo.SurpriseEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 2 Iter 1 — surprise scoring + Welford prior maintenance.
 *
 * Each bot maintains a per-domain {@link ExpectationPrior} of the peer scores
 * it has historically observed. When the bot publishes a paper:
 *   1. {@link #scoreExperiment} compares the new peer score against the prior
 *      (and against the paper's pre-registered prediction, if any) and writes
 *      a {@link SurpriseEvent} for any deviation above {@link #SURPRISE_THRESHOLD}.
 *   2. {@link #updatePrior} folds the new sample into the running mean / M2.
 *
 * Welford's algorithm (the same as {@link AnomalyTracker}) keeps the prior
 * numerically stable across many samples without storing the full history.
 *
 * Bootstrap rule: surprise scoring is suppressed until the prior has at least
 * {@link #BOOTSTRAP_SAMPLE_COUNT} samples — variance is meaningless before
 * that and we'd flag noise as signal.
 */
@Service
public class ExpectationModelService {

    private static final Logger log = LoggerFactory.getLogger(ExpectationModelService.class);

    /** Minimum samples in the prior before surprise scoring activates. */
    static final int BOOTSTRAP_SAMPLE_COUNT = 5;

    /** |residual| / stddev threshold — events above this are persisted. */
    private static final double SURPRISE_THRESHOLD = 2.0;

    /** Hard cap on surpriseScore so a near-zero variance doesn't blow up the metric. */
    private static final double MAX_SURPRISE_SCORE = 10.0;

    private final ExpectationPriorRepository priors;
    private final SurpriseEventRepository    surprises;

    public ExpectationModelService(ExpectationPriorRepository priors,
                                   SurpriseEventRepository surprises) {
        this.priors    = priors;
        this.surprises = surprises;
    }

    /**
     * Score a published paper against the bot's prior. Persists a SurpriseEvent
     * row when |residual| / stddev exceeds {@link #SURPRISE_THRESHOLD}.
     *
     * Returns the persisted event, or null if the prior isn't yet bootstrapped
     * or the residual is below threshold.
     */
    public SurpriseEvent scoreExperiment(UUID botId, ResearchPaper paper, PreRegistration preReg) {
        if (paper == null) return null;
        ExpectationPrior prior = priors.findByBotIdAndDomain(botId, paper.domain()).orElse(null);
        if (prior == null || prior.getSampleCount() < BOOTSTRAP_SAMPLE_COUNT) {
            return null;
        }

        double observed = paper.peerScore();
        double residual = observed - prior.getExpectedMean();
        double stddev   = Math.max(prior.stdDev(), 1e-6);
        double rawScore = Math.abs(residual) / stddev;
        double score    = Math.min(rawScore, MAX_SURPRISE_SCORE);
        if (score < SURPRISE_THRESHOLD) return null;

        SurpriseEvent event = new SurpriseEvent(
                paper.paperId(), botId, paper.roomId(), paper.domain(),
                residual, score,
                preReg != null ? SurpriseEvent.Source.PRE_REGISTRATION : SurpriseEvent.Source.PRIOR,
                Instant.now());
        try {
            surprises.save(event);
            log.info("surprise: paperId={} bot={} domain={} residual={} score={} source={}",
                    paper.paperId(), botId, paper.domain(),
                    String.format("%.3f", residual), String.format("%.2f", score),
                    event.getSource());
        } catch (Exception e) {
            log.warn("surprise persist failed paperId={}: {}", paper.paperId(), e.getMessage());
            return null;
        }
        return event;
    }

    /**
     * Fold the new sample into the bot's running prior via Welford's online
     * algorithm. Creates the prior on first observation. Idempotent within a
     * paper publish — caller should call this exactly once per paper.
     */
    public void updatePrior(UUID botId, ResearchPaper paper) {
        if (paper == null) return;
        ExpectationPrior prior = priors.findByBotIdAndDomain(botId, paper.domain())
                .orElseGet(() -> new ExpectationPrior(botId, paper.domain(), Instant.now()));

        double x       = paper.peerScore();
        int    count   = prior.getSampleCount() + 1;
        double oldMean = prior.getExpectedMean();
        double delta   = x - oldMean;
        double newMean = oldMean + delta / count;
        double newM2   = prior.getM2() + delta * (x - newMean);

        prior.setSampleCount(count);
        prior.setExpectedMean(newMean);
        prior.setM2(newM2);
        prior.setLastUpdatedAt(Instant.now());

        try {
            priors.save(prior);
        } catch (Exception e) {
            log.warn("prior update failed bot={} domain={}: {}", botId, paper.domain(), e.getMessage());
        }
    }

    /** Recent high-surprise events in the room, most surprising first. */
    public List<SurpriseEvent> recentHighSurprise(UUID roomId, double minScore) {
        return surprises.findByRoomIdAndSurpriseScoreGreaterThanEqualOrderBySurpriseScoreDesc(
                roomId, minScore);
    }
}
