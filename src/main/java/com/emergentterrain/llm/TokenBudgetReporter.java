package com.emergentterrain.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Periodic heartbeat that prints the current token-budget picture so an operator
 * can see — without grep-piecing the log — how much room is left before
 * {@link SimulationGate} will pause the simulation, and whether that pause is
 * imminent at the current burn rate.
 *
 * <p>One line per configured provider, every {@code app.tokens.reportIntervalMs}
 * (default 60s). The line carries the daily and minute totals against their
 * limits, tokens remaining before the pause threshold, the current burn rate
 * (tokens-per-minute from the rolling 60s window), and a pause-likelihood ETA.
 *
 * <p>"Pause likely" is derived from the daily axis: at the current per-minute
 * burn rate, project forward and ask whether the daily pause threshold would be
 * crossed within the next 60 minutes. The minute axis is reported as a raw
 * snapshot — it would already be paused-or-not by the time a 60s reporter
 * could call it out.
 */
@Component
public class TokenBudgetReporter {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetReporter.class);

    /** Forecast horizon for "pause likely": daily pause threshold hit within this many minutes. */
    static final long PAUSE_LIKELIHOOD_HORIZON_MIN = 60;

    private final TokenAccountant accountant;
    private final TokenBudgetProperties props;
    private final SimulationGate gate;

    @Autowired
    public TokenBudgetReporter(TokenAccountant accountant,
                                TokenBudgetProperties props,
                                @Autowired(required = false) SimulationGate gate,
                                @Value("${app.tokens.reportIntervalMs:60000}") long intervalMs) {
        this.accountant = accountant;
        this.props      = props;
        this.gate       = gate;
        log.info("TokenBudgetReporter: initialized intervalMs={} providers={}",
                intervalMs, props.getProviders().keySet());
    }

    @Scheduled(fixedDelayString = "${app.tokens.reportIntervalMs:60000}")
    public void report() {
        if (props.getProviders().isEmpty()) {
            return;
        }
        boolean gateOpen = (gate == null) || gate.isOpen();
        String gateState = gateOpen ? "OPEN" : "CLOSED";
        for (var entry : props.getProviders().entrySet()) {
            ProviderSnapshot s = snapshot(entry.getKey());
            log.info("token-budget provider={} gate={} {} pauseLikely={}",
                    s.provider(), gateState, s.toLogFields(), s.pauseLikely());
        }
    }

    /**
     * Compute a snapshot for one provider. Extracted from the scheduler so
     * arithmetic can be tested without scraping log output.
     */
    public ProviderSnapshot snapshot(String provider) {
        TokenBudgetProperties.Provider limits = props.getProviders().get(provider);
        if (limits == null) {
            return new ProviderSnapshot(provider, 0, 0, 0, 0, 0, 0, 0, null, false);
        }
        long dailyTotal  = accountant.dailyTotal(provider);
        long minuteTotal = accountant.minuteTotal(provider);

        long dailyPauseAt = (limits.getDailyLimit() > 0)
                ? limits.getDailyLimit() * props.getPauseThresholdPct() / 100L
                : 0L;
        long minutePauseAt = (limits.getMinuteLimit() > 0)
                ? limits.getMinuteLimit() * props.getPauseThresholdPct() / 100L
                : 0L;

        long dailyRemaining  = (limits.getDailyLimit() > 0)  ? Math.max(0, dailyPauseAt  - dailyTotal)  : -1L;
        long minuteRemaining = (limits.getMinuteLimit() > 0) ? Math.max(0, minutePauseAt - minuteTotal) : -1L;

        Long etaToPauseSec = null;
        boolean pauseLikely = false;
        if (limits.getDailyLimit() > 0 && minuteTotal > 0 && dailyRemaining > 0) {
            // burn rate = minuteTotal tokens / 60s → tokens-per-second
            // eta_seconds = dailyRemaining / (minuteTotal / 60)
            etaToPauseSec = (long) Math.ceil(dailyRemaining * 60.0 / minuteTotal);
            pauseLikely = etaToPauseSec < PAUSE_LIKELIHOOD_HORIZON_MIN * 60;
        } else if (limits.getDailyLimit() > 0 && dailyRemaining == 0) {
            // Already at or past pause threshold.
            etaToPauseSec = 0L;
            pauseLikely = true;
        }

        return new ProviderSnapshot(
                provider,
                dailyTotal, limits.getDailyLimit(), dailyRemaining,
                minuteTotal, limits.getMinuteLimit(), minuteRemaining,
                minuteTotal,
                etaToPauseSec,
                pauseLikely);
    }

    /**
     * Per-provider snapshot. {@code dailyRemaining} / {@code minuteRemaining} of
     * {@code -1} means unmetered (limit = 0). {@code etaToPauseSec} is null when
     * not applicable (unmetered, or idle with zero burn).
     */
    public record ProviderSnapshot(
            String provider,
            long dailyTotal,   long dailyLimit,   long dailyRemainingBeforePause,
            long minuteTotal,  long minuteLimit,  long minuteRemainingBeforePause,
            long burnRatePerMin,
            Long etaToPauseSec,
            boolean pauseLikely) {

        String toLogFields() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", dailyTotal + "/" + (dailyLimit > 0 ? dailyLimit : "unmetered"));
            m.put("dayRemaining", dailyRemainingBeforePause < 0 ? "unmetered" : dailyRemainingBeforePause);
            m.put("min", minuteTotal + "/" + (minuteLimit > 0 ? minuteLimit : "unmetered"));
            m.put("minRemaining", minuteRemainingBeforePause < 0 ? "unmetered" : minuteRemainingBeforePause);
            m.put("burnRatePerMin", burnRatePerMin);
            m.put("etaToPauseSec", etaToPauseSec == null ? "n/a" : etaToPauseSec);
            return m.toString();
        }
    }
}
