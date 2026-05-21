package com.emergentterrain.research.conjecture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Random;

/**
 * Phase 23.4 — decides whether a paper-writing cycle should be sourced
 * from the mined conjecture queue (vs. the catalog topic-weight roll).
 *
 * <p>Sits between {@link ConjectureQueue} (the queue) and the
 * upstream paper-generation pipeline (which lives in
 * {@code ResearchCycleService}). The wiring on the upstream side comes
 * with 23.5 — the {@code verify_conjecture} tool — because the full
 * acceptance (mechanism section + adversarial check) requires that tool
 * to exist. This bean ships now so the wiring change in 23.5 is
 * a one-line call rather than a feature flag plus a new bean.
 *
 * <p>Config flag {@code app.research.conjectureMiner.paperShare}
 * (default {@code 0.0}) is the probability that a given cycle pulls
 * from the queue. {@code 1.0} = every paper from the queue (test mode);
 * {@code 0.0} = never (default, current behavior).
 *
 * <p>{@link ConjectureQueue} is consumed as an {@link Optional} so the
 * bean stays available when the miner's own {@code @ConditionalOnProperty}
 * gate is off. Spring auto-injects {@code Optional.empty()} when no bean
 * matches; in that case {@link #selectConjecture} short-circuits regardless
 * of {@code paperShare}.
 */
@Component
public class ConjectureTopicProvider {

    private final Optional<ConjectureQueue> queue;
    private final double paperShare;

    public ConjectureTopicProvider(
            Optional<ConjectureQueue> queue,
            @Value("${app.research.conjectureMiner.paperShare:0.0}") double paperShare) {
        this.queue = queue;
        if (paperShare < 0.0 || paperShare > 1.0) {
            throw new IllegalArgumentException(
                    "app.research.conjectureMiner.paperShare must be in [0,1]; got " + paperShare);
        }
        this.paperShare = paperShare;
    }

    /**
     * Roll the paperShare probability; if it fires AND the queue is
     * present AND non-empty, return the next conjecture. Otherwise return
     * empty (caller falls back to the catalog path).
     */
    public Optional<Conjecture> selectConjecture(String domain, Random rng) {
        if (paperShare <= 0.0 || queue.isEmpty()) {
            return Optional.empty();
        }
        if (rng.nextDouble() >= paperShare) {
            return Optional.empty();
        }
        return queue.get().next(domain);
    }

    public double paperShare() {
        return paperShare;
    }
}
