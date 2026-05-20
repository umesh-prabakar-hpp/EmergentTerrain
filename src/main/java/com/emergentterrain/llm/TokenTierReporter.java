package com.emergentterrain.llm;

import com.emergentterrain.service.FastModelRegistry;
import com.emergentterrain.service.ModelHandle;
import com.emergentterrain.service.PrimaryModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Periodic heartbeat that breaks the token spend into PRIMARY vs FAST tiers
 * by classifying each completed call's model against
 * {@link PrimaryModelRegistry} and {@link FastModelRegistry}. Companion to
 * {@link TokenBudgetReporter}, which prints per-provider budget state but
 * does not distinguish tier.
 *
 * <p>Default cadence is 5 minutes
 * ({@code app.tokens.tierReportIntervalMs:300000}). Window matches the
 * cadence — each line summarises the most recent 5 minutes of spend, so
 * sequential lines together form a non-overlapping trend (modulo the
 * 1-minute drift the {@link Scheduled fixedDelay} accumulates relative to
 * wall clock).
 *
 * <p>One log line per fire:
 * <pre>
 *   token-budget-by-tier window=5m primary={tokens=12345, calls=23} \
 *       fast={tokens=2000, calls=18} unknown={tokens=0, calls=0} \
 *       total={tokens=14345, calls=41}
 * </pre>
 *
 * <p>"Unknown" covers two cases: (a) calls whose model isn't in either
 * registry (rare — happens if a pool registers a model that was dropped
 * from a registry's availability filter); (b) adjustment entries from
 * {@link TokenAccountant#recordDelta} that carry no model identifier
 * (pre-debit reservations and post-call corrections). Both are reported
 * for transparency.
 *
 * <p>No-op if both registries are absent (test contexts) — the bean
 * registers anyway so the scheduler dependency graph stays well-formed,
 * but {@link #report()} early-exits without logging.
 */
@Component
public class TokenTierReporter {

    private static final Logger log = LoggerFactory.getLogger(TokenTierReporter.class);

    private final TokenAccountant accountant;
    private final PrimaryModelRegistry primaryRegistry;
    private final FastModelRegistry fastRegistry;
    private final long windowMs;

    @Autowired
    public TokenTierReporter(TokenAccountant accountant,
                              @Autowired(required = false) PrimaryModelRegistry primaryRegistry,
                              @Autowired(required = false) FastModelRegistry fastRegistry,
                              @Value("${app.tokens.tierReportIntervalMs:300000}") long intervalMs) {
        this.accountant = accountant;
        this.primaryRegistry = primaryRegistry;
        this.fastRegistry = fastRegistry;
        this.windowMs = intervalMs;
        log.info("TokenTierReporter: initialized intervalMs={} primaryRegistry={} fastRegistry={}",
                intervalMs,
                primaryRegistry == null ? "absent" : "present",
                fastRegistry == null ? "absent" : "present");
    }

    @Scheduled(fixedDelayString = "${app.tokens.tierReportIntervalMs:300000}")
    public void report() {
        if (primaryRegistry == null && fastRegistry == null) return;
        TierSnapshot snap = snapshot();
        log.info("token-budget-by-tier window={}m primary={} fast={} unknown={} total={}",
                windowMs / 60_000L,
                snap.primary().toLogString(),
                snap.fast().toLogString(),
                snap.unknown().toLogString(),
                snap.total().toLogString());
    }

    /**
     * Snapshot the tier breakdown over the configured window. Extracted from
     * {@link #report()} so unit tests can assert the arithmetic without
     * scraping log output.
     */
    public TierSnapshot snapshot() {
        Set<String> primaryIds = idsFromRegistry(primaryRegistry);
        Set<String> fastIds    = idsFromRegistry(fastRegistry);
        // A model that appears in BOTH registries gets attributed to PRIMARY
        // (the more constrained classification). This avoids double-counting
        // and matches the deployment pattern where FastModelRegistry is the
        // overlap-superset of PrimaryModelRegistry for some providers.
        Predicate<String> isPrimary = m -> m != null && primaryIds.contains(m);
        Predicate<String> isFast    = m -> m != null && !primaryIds.contains(m) && fastIds.contains(m);
        Predicate<String> isUnknown = m -> m == null || (!primaryIds.contains(m) && !fastIds.contains(m));

        TierStats primary = new TierStats(
                accountant.tokensByModel(windowMs, isPrimary),
                accountant.callsByModel(windowMs, isPrimary));
        TierStats fast = new TierStats(
                accountant.tokensByModel(windowMs, isFast),
                accountant.callsByModel(windowMs, isFast));
        TierStats unknown = new TierStats(
                accountant.tokensByModel(windowMs, isUnknown),
                accountant.callsByModel(windowMs, isUnknown));
        TierStats total = new TierStats(
                primary.tokens() + fast.tokens() + unknown.tokens(),
                primary.calls()  + fast.calls()  + unknown.calls());
        return new TierSnapshot(primary, fast, unknown, total);
    }

    private static Set<String> idsFromRegistry(Object registry) {
        if (registry == null) return Set.of();
        try {
            if (registry instanceof PrimaryModelRegistry p) {
                return p.allModels().stream().map(ModelHandle::modelId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
            if (registry instanceof FastModelRegistry f) {
                return f.allModels().stream().map(ModelHandle::modelId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
            return Set.of();
        } catch (RuntimeException e) {
            log.debug("TokenTierReporter: registry.allModels() threw: {}", e.toString());
            return Set.of();
        }
    }

    /** Per-tier (tokens, calls) tuple. */
    public record TierStats(long tokens, long calls) {
        String toLogString() {
            return "{tokens=" + tokens + ", calls=" + calls + "}";
        }
    }

    /** Aggregate of the four tier rows logged each cycle. */
    public record TierSnapshot(TierStats primary, TierStats fast,
                                TierStats unknown, TierStats total) {}
}
