package com.emergentterrain.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rec #9 reviewer-acquisition outcome — pins {@link ReviewPanelService#unfilledSeats},
 * which names the picked reviewer seats that produced no review when a panel
 * comes back short. That shortfall is what trips the downstream D4 demotion (and
 * its rec #7 relaxation), so attributing it to specific provider seats is what
 * sizes the acquisition fix.
 */
class ReviewPanelServiceUnfilledSeatsTest {

    private static LinkedHashMap<String, String> picks(String... ids) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (String id : ids) m.put(id, id + "@prov");
        return m;
    }

    @Test
    void namesUnfilledSeatsInPickedOrder() {
        var picked = picks("r1", "r2", "r3", "r4");
        // Only r1 and r3 produced a review → r2 and r4 are the unfilled seats.
        assertThat(ReviewPanelService.unfilledSeats(picked, Set.of("r1", "r3")))
                .containsExactly("r2@prov", "r4@prov");
    }

    @Test
    void fullPanel_noUnfilled() {
        var picked = picks("r1", "r2");
        assertThat(ReviewPanelService.unfilledSeats(picked, Set.of("r1", "r2"))).isEmpty();
    }

    @Test
    void nullOrEmptyFilled_allUnfilled() {
        var picked = picks("r1", "r2");
        assertThat(ReviewPanelService.unfilledSeats(picked, null))
                .containsExactly("r1@prov", "r2@prov");
        assertThat(ReviewPanelService.unfilledSeats(picked, Set.of()))
                .containsExactly("r1@prov", "r2@prov");
    }

    @Test
    void emptyOrNullPicks_empty() {
        assertThat(ReviewPanelService.unfilledSeats(new LinkedHashMap<>(), Set.of("r1"))).isEmpty();
        assertThat(ReviewPanelService.unfilledSeats(null, Set.of("r1"))).isEmpty();
    }
}
