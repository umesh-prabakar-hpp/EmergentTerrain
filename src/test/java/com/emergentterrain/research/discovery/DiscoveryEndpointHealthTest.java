package com.emergentterrain.research.discovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 fork 2b — the discovery pre-flight breaker: the enabled gate + sticky
 * caching. The HTTP probe is overridden, so no server is needed.
 */
class DiscoveryEndpointHealthTest {

    private static final class FakeHealth extends DiscoveryEndpointHealth {
        final int[] probes;
        final boolean result;
        FakeHealth(boolean enabled, long cooldownMs, boolean result, int[] probes) {
            super(enabled, "http://localhost:8088", cooldownMs);
            this.result = result;
            this.probes = probes;
        }
        @Override
        protected boolean doProbe() {
            probes[0]++;
            return result;
        }
    }

    @Test
    void breakerOffIsAlwaysAvailableAndNeverProbes() {
        int[] p = {0};
        FakeHealth h = new FakeHealth(false, 60_000, false, p);
        assertThat(h.available()).isTrue();
        assertThat(h.available()).isTrue();
        assertThat(p[0]).isZero();
    }

    @Test
    void stickyCachesUnavailableWithinCooldown() {
        int[] p = {0};
        FakeHealth h = new FakeHealth(true, 60_000, false, p);
        assertThat(h.available()).isFalse();
        assertThat(h.available()).isFalse();    // cached — no re-probe
        assertThat(p[0]).isEqualTo(1);
    }

    @Test
    void reportsAvailableWhenProbeSucceeds() {
        int[] p = {0};
        assertThat(new FakeHealth(true, 60_000, true, p).available()).isTrue();
        assertThat(p[0]).isEqualTo(1);
    }

    @Test
    void reprobesEachCallWithZeroCooldown() {
        int[] p = {0};
        FakeHealth h = new FakeHealth(true, 0, false, p);
        h.available();
        h.available();
        assertThat(p[0]).isEqualTo(2);
    }
}
