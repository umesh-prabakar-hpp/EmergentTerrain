package com.emergentterrain.research.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Phase 32 §32.5 step 1 (phase-32-openalex-corpus.md item A) — daily OpenAlex credit ledger.
 * OpenAlex bills usage in USD credits with a free daily allowance ($1/day keyed, $0.10/day
 * anonymous — probed live 2026-07-04); every response carries authoritative spend headers
 * ({@code x-ratelimit-cost-usd}, {@code x-ratelimit-remaining-usd}). This ledger is the
 * {@code RunBudgetTracker} analog denominated in those credits: it refuses a call whose estimated
 * cost would push today's spend past the configured ceiling (which defaults BELOW the free tier,
 * so an unattended overnight room can never incur billable spend), and records the ACTUAL cost
 * from the response header (falling back to the static per-class estimate when absent).
 *
 * <p>State ({@code {date, spentUsd, calls}}) persists to a small JSON file so trickle corpus
 * builds spread over several invocations of the same day share one budget. Day rollover resets
 * the counter. The clock is injected for testability; production uses {@link LocalDate#now()}.
 */
public class OpenAlexCreditLedger {

    /** Static per-call estimates (USD) from the 2026-02 pricing model, used for the pre-call
     *  budget check and as fallback when a response carries no cost header. */
    public static final double COST_SINGLETON = 0.0;
    public static final double COST_LIST = 0.0001;
    public static final double COST_SEARCH = 0.001;
    public static final double COST_CONTENT = 0.01;

    /** Thrown when a call would exceed the daily ceiling; callers degrade, never retry blind. */
    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String msg) { super(msg); }
    }

    private static final ObjectMapper OM = new ObjectMapper();

    private final Path stateFile;
    private final double dailyCeilingUsd;
    private final Supplier<LocalDate> clock;

    private LocalDate day;
    private double spentUsd;
    private long calls;

    public OpenAlexCreditLedger(Path stateFile, double dailyCeilingUsd) {
        this(stateFile, dailyCeilingUsd, LocalDate::now);
    }

    OpenAlexCreditLedger(Path stateFile, double dailyCeilingUsd, Supplier<LocalDate> clock) {
        this.stateFile = stateFile;
        this.dailyCeilingUsd = dailyCeilingUsd;
        this.clock = clock;
        load();
    }

    /** Refuse (throw) when today's spend plus the estimated cost would exceed the ceiling.
     *  Epsilon absorbs float accumulation so a call landing EXACTLY on the ceiling passes. */
    public synchronized void assertBudget(double estimatedCostUsd) {
        rolloverIfNeeded();
        if (spentUsd + estimatedCostUsd > dailyCeilingUsd + 1e-9) {
            throw new BudgetExceededException(String.format(
                    "OpenAlex daily credit ceiling would be exceeded: spent $%.4f + est $%.4f > "
                            + "ceiling $%.4f (resets at local midnight; raise "
                            + "app.research.literature.openalex.daily-credit-ceiling deliberately "
                            + "if this build should spend more)",
                    spentUsd, estimatedCostUsd, dailyCeilingUsd));
        }
    }

    /** Record the ACTUAL cost of a completed call (header value preferred; estimate fallback). */
    public synchronized void record(double actualCostUsd) {
        rolloverIfNeeded();
        spentUsd += Math.max(0.0, actualCostUsd);
        calls++;
        persist();
    }

    public synchronized double spentTodayUsd() {
        rolloverIfNeeded();
        return spentUsd;
    }

    public synchronized long callsToday() {
        rolloverIfNeeded();
        return calls;
    }

    public double dailyCeilingUsd() {
        return dailyCeilingUsd;
    }

    private void rolloverIfNeeded() {
        LocalDate today = clock.get();
        if (!today.equals(day)) {
            day = today;
            spentUsd = 0.0;
            calls = 0;
            persist();
        }
    }

    private void load() {
        day = clock.get();
        spentUsd = 0.0;
        calls = 0;
        if (stateFile == null || !Files.exists(stateFile)) {
            return;
        }
        try {
            var node = OM.readTree(Files.readString(stateFile));
            LocalDate persisted = LocalDate.parse(node.path("date").asText(day.toString()));
            if (persisted.equals(day)) {
                spentUsd = node.path("spentUsd").asDouble(0.0);
                calls = node.path("calls").asLong(0);
            }
        } catch (Exception e) {
            // A corrupt ledger file must never block the build; start the day at zero
            // (conservative direction: the ceiling still caps total spend).
        }
    }

    private void persist() {
        if (stateFile == null) {
            return;
        }
        try {
            ObjectNode node = OM.createObjectNode();
            node.put("date", day.toString());
            node.put("spentUsd", spentUsd);
            node.put("calls", calls);
            Files.createDirectories(stateFile.toAbsolutePath().getParent());
            Files.writeString(stateFile, node.toPrettyString());
        } catch (IOException e) {
            // Persistence is best-effort; in-memory accounting still enforces the ceiling.
        }
    }
}
