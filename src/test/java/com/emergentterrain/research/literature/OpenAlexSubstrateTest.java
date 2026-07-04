package com.emergentterrain.research.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 32 §32.5 step 1 (openalex addendum item A) — the pure substrate pieces: daily credit
 * ledger (ceiling, actual-cost recording, day rollover, persistence across instances), JSONL
 * response cache (miss → put → hit, reload from disk), and abstract reconstruction from
 * {@code abstract_inverted_index}. The HTTP layer is deliberately thin and exercised live by
 * the corpus builder, not mocked here.
 */
class OpenAlexSubstrateTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @TempDir
    Path tmp;

    @Test
    void ledger_refusesPastCeiling_andRecordsActualHeaderCost() {
        OpenAlexCreditLedger ledger = new OpenAlexCreditLedger(
                tmp.resolve("ledger.json"), 0.01, () -> LocalDate.of(2026, 7, 4));
        ledger.assertBudget(OpenAlexCreditLedger.COST_SEARCH);     // 0.001 fits under 0.01
        for (int i = 0; i < 9; i++) {
            ledger.record(0.001);
        }
        assertThat(ledger.spentTodayUsd()).isCloseTo(0.009, org.assertj.core.data.Offset.offset(1e-9));
        ledger.assertBudget(0.001);                                // exactly reaches the ceiling
        assertThatThrownBy(() -> ledger.assertBudget(0.0011))
                .isInstanceOf(OpenAlexCreditLedger.BudgetExceededException.class)
                .hasMessageContaining("ceiling");
    }

    @Test
    void ledger_persistsWithinADay_andRollsOverAtMidnight() {
        Path state = tmp.resolve("ledger.json");
        LocalDate[] today = { LocalDate.of(2026, 7, 4) };
        OpenAlexCreditLedger first = new OpenAlexCreditLedger(state, 0.50, () -> today[0]);
        first.record(0.123);
        // A second invocation the same day (trickle build) shares the budget…
        OpenAlexCreditLedger second = new OpenAlexCreditLedger(state, 0.50, () -> today[0]);
        assertThat(second.spentTodayUsd()).isCloseTo(0.123, org.assertj.core.data.Offset.offset(1e-9));
        // …and the next day starts at zero.
        today[0] = LocalDate.of(2026, 7, 5);
        assertThat(second.spentTodayUsd()).isZero();
    }

    @Test
    void cache_missPutHit_andReloadsFromDisk() {
        Path file = tmp.resolve("cache.jsonl");
        OpenAlexResponseCache cache = new OpenAlexResponseCache(file);
        String key = "https://api.openalex.org/works?search=mertens";   // key-free by contract
        assertThat(cache.get(key)).isEmpty();
        cache.put(key, "{\"results\":[]}");
        assertThat(cache.get(key)).contains("{\"results\":[]}");
        // A fresh instance (new build invocation) reloads the entry from the JSONL file.
        OpenAlexResponseCache reloaded = new OpenAlexResponseCache(file);
        assertThat(reloaded.get(key)).contains("{\"results\":[]}");
        assertThat(reloaded.size()).isEqualTo(1);
    }

    @Test
    void reconstructAbstract_ordersWordsByPosition() throws Exception {
        // OpenAlex inverts abstracts as word -> [positions]; reconstruction must re-order by
        // position including repeated words at multiple positions.
        var inverted = OM.readTree(
                "{\"the\":[0,4],\"Mertens\":[1],\"function\":[2],\"oscillates\":[3],\"line\":[5]}");
        assertThat(OpenAlexClient.reconstructAbstract(inverted))
                .isEqualTo("the Mertens function oscillates the line");
        assertThat(OpenAlexClient.reconstructAbstract(null)).isEmpty();
        assertThat(OpenAlexClient.reconstructAbstract(OM.readTree("{}"))).isEmpty();
    }
}
