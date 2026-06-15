package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 integration step 2.5 — unit tests for
 * {@link ConjectureMinerService#knobFilterExempting}. A derived (composed)
 * conjecture whose per-config slack is monotonic in window_size is a knob
 * restatement by the Spearman heuristic and would be dropped; the exemption
 * keeps it (it still gets counterexample-checked elsewhere). Pure — no sidecar,
 * no Spring context.
 */
class ConjectureKnobExemptionTest {

    /** window_size knob axis matching the miner's config hashes (W&lt;size&gt; -&gt; size). */
    private static final List<ConjectureEngine.KnobAxis> WINDOW_AXIS = List.of(
            new ConjectureEngine.KnobAxis("window_size", orderedMap(
                    "W500", 500.0, "W5000", 5000.0, "W50000", 50000.0)));

    private static Map<String, Double> orderedMap(String k1, double v1, String k2, double v2,
                                                  String k3, double v3) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return m;
    }

    /** Inequality with slack monotonically increasing in window_size (Spearman = +1 -> knob-flagged). */
    private static Inequality monotonicSlack(String lhs, String rhs) {
        Map<String, Double> slack = orderedMap("W500", 0.10, "W5000", 0.20, "W50000", 0.30);
        return Inequality.fromFields(lhs, rhs, 4.0, 0.0, 3, List.of(), List.of(), 0.30, slack, null);
    }

    @Test
    void withoutExemptBothMinedAndDerivedAreDropped() {
        List<Inequality> store = new ArrayList<>(List.of(
                monotonicSlack("a", "b"),                 // mined
                monotonicSlack("deficient_density", "abundant_density")));  // derived
        Set<String> derivedKeys = Set.of("deficient_density|abundant_density");

        ConjectureEngine.FilterResult fr = ConjectureMinerService.knobFilterExempting(
                store, derivedKeys, /*exempt*/ false, WINDOW_AXIS, 0.95, 3);

        assertThat(fr.dropped()).isEqualTo(2);   // legacy: both monotonic-slack items dropped
        assertThat(store).isEmpty();
    }

    @Test
    void withExemptDerivedSurvivesMinedDropped() {
        Inequality mined = monotonicSlack("a", "b");
        Inequality derived = monotonicSlack("deficient_density", "abundant_density");
        List<Inequality> store = new ArrayList<>(List.of(mined, derived));
        Set<String> derivedKeys = Set.of("deficient_density|abundant_density");

        ConjectureEngine.FilterResult fr = ConjectureMinerService.knobFilterExempting(
                store, derivedKeys, /*exempt*/ true, WINDOW_AXIS, 0.95, 3);

        assertThat(fr.dropped()).isEqualTo(1);                         // only the mined one
        assertThat(fr.droppedItems()).singleElement()
                .extracting(Inequality::lhs).isEqualTo("a");
        assertThat(store).containsExactly(derived);                    // derived kept
        assertThat(derived.droppedByKnob()).isNull();                  // not tagged
    }

    @Test
    void exemptIsNoOpWhenNoDerivedKeys() {
        List<Inequality> store = new ArrayList<>(List.of(monotonicSlack("a", "b")));
        // exempt flag on, but empty derivedKeys -> falls through to plain filter
        ConjectureEngine.FilterResult fr = ConjectureMinerService.knobFilterExempting(
                store, Set.of(), /*exempt*/ true, WINDOW_AXIS, 0.95, 3);
        assertThat(fr.dropped()).isEqualTo(1);
        assertThat(store).isEmpty();
    }

    // ── 2026-06-14 — verified growth-anchor exemption (review-c Rec 1) ─────────

    @Test
    void verifiedGrowthAnchorIsExemptFromKnobFilter() {
        // max_prime_gap ≤ k·log_sq_window_start: a growth anchor with window-monotonic slack — the
        // restatement heuristic flags it, but as a verified anchor it must be held aside (the regression
        // that retired the 0.649 Cramér survivor).
        Inequality anchor = monotonicSlack("max_prime_gap", "log_sq_window_start");
        Inequality minedFlat = monotonicSlack("a", "b");
        List<Inequality> store = new ArrayList<>(List.of(anchor, minedFlat));

        ConjectureEngine.FilterResult fr = ConjectureMinerService.knobFilterExempting(
                store, Set.of(), /*compositionExempt*/ false,
                Set.of("max_prime_gap|log_sq_window_start"),     // the verified-anchor key set
                WINDOW_AXIS, 0.95, 3);

        assertThat(fr.dropped()).isEqualTo(1);                    // only the non-anchor mined bound
        assertThat(fr.droppedItems()).singleElement()
                .extracting(Inequality::lhs).isEqualTo("a");
        assertThat(store).containsExactly(anchor);                // the verified anchor survives
        assertThat(anchor.droppedByKnob()).isNull();
    }

    @Test
    void unverifiedAnchorIsStillFilteredWhenNotInExemptSet() {
        // An anchor that is NOT in the verified-anchor key set (e.g. an unverified spurious seed) is
        // still subject to the knob filter — the exemption is gated on verification, not on the RHS alone.
        Inequality anchor = monotonicSlack("max_prime_gap", "log_sq_window_start");
        List<Inequality> store = new ArrayList<>(List.of(anchor));

        ConjectureEngine.FilterResult fr = ConjectureMinerService.knobFilterExempting(
                store, Set.of(), false, Set.of() /*nothing verified*/, WINDOW_AXIS, 0.95, 3);

        assertThat(fr.dropped()).isEqualTo(1);
        assertThat(store).isEmpty();
        assertThat(anchor.droppedByKnob()).isEqualTo("window_size");
    }

    @Test
    void derivedWithFlatSlackSurvivesEitherWay() {
        // A derived relation whose slack does NOT trend with the knob isn't flagged
        // at all — exemption changes nothing for it.
        Map<String, Double> flat = orderedMap("W500", 0.20, "W5000", 0.20, "W50000", 0.20);
        Inequality derived = Inequality.fromFields(
                "deficient_density", "abundant_density", 4.0, 0.0, 3, List.of(), List.of(), 0.20, flat, null);
        List<Inequality> store = new ArrayList<>(List.of(derived));

        ConjectureEngine.FilterResult fr = ConjectureMinerService.knobFilterExempting(
                store, Set.of("deficient_density|abundant_density"), /*exempt*/ false, WINDOW_AXIS, 0.95, 3);
        assertThat(fr.dropped()).isZero();
        assertThat(store).containsExactly(derived);
    }
}
