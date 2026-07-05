package com.emergentterrain.research.conjecture;

import com.emergentterrain.repo.ConjectureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 23.3 — priority-ordered queue API. Constructs a controlled mix
 * of conjectures (refuted, knob-dropped, live with varying confirmation
 * counts and slack) and verifies {@link ConjectureMinerService#next}
 * follows the two-tier priority documented in the phase doc.
 *
 * <p>Doesn't run the actual mining engine; uses fabricated entities so
 * the test is fast and pins the ordering logic in isolation.
 */
class ConjectureQueueTest {

    @Test
    void refutedConjecturesAreReturnedFirst() {
        ConjectureRepository repo = mock(ConjectureRepository.class);
        Conjecture refutedOld = build("a", "b", 0.0, 0.5, 3, 0.001, null);
        markRetired(refutedOld, ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE,
                Instant.parse("2026-05-01T00:00:00Z"));
        Conjecture refutedNew = build("c", "d", 0.0, 0.7, 5, 0.002, null);
        markRetired(refutedNew, ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE,
                Instant.parse("2026-05-15T00:00:00Z"));
        Conjecture liveHighConfirmation = build("e", "f", 0.0, 0.3, 50, 0.0001, null);
        when(repo.findByDomain(eq("math"))).thenReturn(
                List.of(liveHighConfirmation, refutedOld, refutedNew)
        );

        ConjectureMinerService svc = buildService(repo);

        Optional<Conjecture> picked = svc.next("math");
        assertTrue(picked.isPresent(), "queue should return something");
        assertEquals("c", picked.get().lhs(),
                "most-recently-refuted conjecture should be returned first (tier 1 trumps tier 2)");
    }

    @Test
    void windowGeometryConjecturesAreNeverInvestigated() {
        // Room b72c4e10 Rec 3: a geometry-LHS bound refuted by counterexample lands in tier 1 and
        // bypasses the live-store triviality reduce(). The investigation queue must still exclude it
        // — even though here it is the NEWEST refutation (would otherwise be served first).
        ConjectureRepository repo = mock(ConjectureRepository.class);
        Conjecture geomRefuted =
                build("log_window_start", "max_divisor_count", 2.0, -168.2258, 0, 0.5, null);
        markRetired(geomRefuted, ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE,
                Instant.parse("2026-06-04T00:00:00Z"));   // newest
        Conjecture contentRefuted =
                build("mertens_per_sqrt_W", "prime_density", 2.0, 0.2431, 0, 0.7, null);
        markRetired(contentRefuted, ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE,
                Instant.parse("2026-05-01T00:00:00Z"));   // older
        when(repo.findByDomain(eq("math"))).thenReturn(List.of(geomRefuted, contentRefuted));

        ConjectureMinerService svc = buildService(repo);

        List<Conjecture> served = svc.nextN("math", 10);
        assertEquals(1, served.size(), "only the content conjecture is investigable");
        assertEquals("mertens_per_sqrt_W", served.get(0).lhs(),
                "geometry-LHS bound excluded despite being the newest tier-1 refutation");
    }

    @Test
    void liveConjecturesSortByConfirmationThenTightness() {
        ConjectureRepository repo = mock(ConjectureRepository.class);
        Conjecture live10runs = build("a", "b", 0.0, 0.5, 10, 0.001, null);
        Conjecture live50runs = build("c", "d", 0.0, 0.3, 50, 0.005, null);   // most confirmed
        Conjecture live50runsTight = build("e", "f", 0.0, 0.7, 50, 0.0001, null); // also 50 but tighter
        when(repo.findByDomain(eq("math"))).thenReturn(
                List.of(live10runs, live50runs, live50runsTight)
        );

        ConjectureMinerService svc = buildService(repo);

        List<Conjecture> top3 = svc.nextN("math", 3);
        assertEquals(3, top3.size());
        // confirmation tie-break: tighter slack wins
        assertEquals("e", top3.get(0).lhs(), "tightest at max confirmation should come first");
        assertEquals("c", top3.get(1).lhs(), "next at max confirmation");
        assertEquals("a", top3.get(2).lhs(), "lowest confirmation last");
    }

    @Test
    void knobDroppedConjecturesAreSkipped() {
        ConjectureRepository repo = mock(ConjectureRepository.class);
        Conjecture liveOne = build("a", "b", 0.0, 0.5, 7, 0.001, null);
        Conjecture knobDropped = build("c", "d", 0.0, 0.3, 50, 0.0001, null);
        markRetired(knobDropped, "window_size", Instant.now());
        when(repo.findByDomain(eq("math"))).thenReturn(List.of(knobDropped, liveOne));

        ConjectureMinerService svc = buildService(repo);

        Optional<Conjecture> picked = svc.next("math");
        assertTrue(picked.isPresent());
        assertEquals("a", picked.get().lhs(),
                "knob-dropped conjectures shouldn't enter the queue (not refuted, not live)");
        assertNull(picked.get().droppedByKnob());
    }

    @Test
    void emptyDomainReturnsEmpty() {
        ConjectureRepository repo = mock(ConjectureRepository.class);
        when(repo.findByDomain(eq("math"))).thenReturn(List.of());

        ConjectureMinerService svc = buildService(repo);

        assertTrue(svc.next("math").isEmpty());
        assertTrue(svc.nextN("math", 5).isEmpty());
    }

    @Test
    void mixedQueueRespectsBothTiers() {
        ConjectureRepository repo = mock(ConjectureRepository.class);
        // 2 refuted, 3 live, 1 knob-dropped — total 6 entities
        Conjecture refutedA = retired("ra", "x", ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE, "2026-05-10T00:00:00Z");
        Conjecture refutedB = retired("rb", "x", ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE, "2026-05-15T00:00:00Z");
        Conjecture knob     = retired("kn", "x", "window_size",                                       "2026-05-12T00:00:00Z");
        Conjecture live1    = build("la", "x", 0.0, 0.5, 20, 0.0010, null);
        Conjecture live2    = build("lb", "x", 0.0, 0.3, 30, 0.0005, null);
        Conjecture live3    = build("lc", "x", 0.0, 0.7, 10, 0.0001, null);
        when(repo.findByDomain(eq("math"))).thenReturn(
                List.of(knob, live1, refutedA, live2, refutedB, live3)
        );

        ConjectureMinerService svc = buildService(repo);

        List<Conjecture> top6 = svc.nextN("math", 6);
        // 2 refuted (tier 1, newest first), 3 live (tier 2, by priority), no knob entries.
        assertEquals(5, top6.size());
        assertEquals("rb", top6.get(0).lhs(), "tier 1 (refuted, newest first)");
        assertEquals("ra", top6.get(1).lhs(), "tier 1 (older refutation)");
        assertEquals("lb", top6.get(2).lhs(), "tier 2: highest confirmedRuns");
        assertEquals("la", top6.get(3).lhs(), "tier 2: next highest");
        assertEquals("lc", top6.get(4).lhs(), "tier 2: lowest confirmedRuns");
        // 'kn' (knob-dropped) intentionally absent
    }

    // ---------- Phase 30 Wave 0 — investigation rotation ----------

    @Test
    void withoutRotationSameConjectureIsReServed() {
        // Documents the bug: next() is a stateless peek, so marking a conjecture
        // investigated does NOT change the order — the same top refuted conjecture
        // comes back every cycle.
        ConjectureRepository repo = mock(ConjectureRepository.class);
        Conjecture refutedOld = retired("ra", "x", ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE, "2026-05-01T00:00:00Z");
        Conjecture refutedNew = retired("rb", "x", ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE, "2026-05-15T00:00:00Z");
        when(repo.findByDomain(eq("math"))).thenReturn(List.of(refutedOld, refutedNew));
        when(repo.save(any(Conjecture.class))).thenAnswer(inv -> inv.getArgument(0));

        ConjectureMinerService svc = buildService(repo);   // rotation OFF
        Conjecture first = svc.next("math").orElseThrow();
        assertEquals("rb", first.lhs());
        svc.markInvestigated(first);
        Conjecture second = svc.next("math").orElseThrow();
        assertEquals("rb", second.lhs(), "without rotation the same conjecture is re-served");
    }

    @Test
    void rotationServesLeastRecentlyInvestigatedNext() {
        ConjectureRepository repo = mock(ConjectureRepository.class);
        Conjecture refutedOld = retired("ra", "x", ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE, "2026-05-01T00:00:00Z");
        Conjecture refutedNew = retired("rb", "x", ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE, "2026-05-15T00:00:00Z");
        when(repo.findByDomain(eq("math"))).thenReturn(List.of(refutedOld, refutedNew));
        when(repo.save(any(Conjecture.class))).thenAnswer(inv -> inv.getArgument(0));

        ConjectureMinerService svc = buildServiceWithRotation(repo);

        // both never investigated -> tie -> most-recently-refuted (rb) first
        Conjecture first = svc.next("math").orElseThrow();
        assertEquals("rb", first.lhs());
        // consume it, then the never-investigated 'ra' rotates to the front
        svc.markInvestigated(first);
        Conjecture second = svc.next("math").orElseThrow();
        assertEquals("ra", second.lhs(),
                "rotation should serve the least-recently-investigated conjecture next");
    }

    // ---------- helpers ----------

    private static ConjectureMinerService buildServiceWithRotation(ConjectureRepository repo) {
        ObjectMapper om = new ObjectMapper();
        return new ConjectureMinerService(repo, new ConjectureMapper(om), new OeisAnnotator(om),
                new MathConjectureSubstrateDelegate((com.emergentterrain.llm.JuliaMathClient) null),
                minerProps(/*investigationRotation*/ true));
    }

    private static ConjectureMinerService buildService(ConjectureRepository repo) {
        ObjectMapper om = new ObjectMapper();
        // §28.6: the miner takes a sidecar-only substrate delegate. These tests
        // exercise only the queue (next/nextN), never mine(), so the delegate's
        // evaluateWindow is never called — a null-client delegate is inert here.
        return new ConjectureMinerService(repo, new ConjectureMapper(om), new OeisAnnotator(om),
                new MathConjectureSubstrateDelegate((com.emergentterrain.llm.JuliaMathClient) null),
                minerProps(/*investigationRotation*/ false));
    }

    /** Default miner flags (all OFF) with investigation rotation overridable. */
    private static ConjectureMinerProperties minerProps(boolean investigationRotation) {
        ConjectureMinerProperties p = new ConjectureMinerProperties();
        p.setInvestigationRotationEnabled(investigationRotation);
        return p;
    }

    private static Conjecture build(String lhs, String rhs, double k, double c,
                                    int confirmedRuns, double p99Slack, String droppedByKnob) {
        Conjecture e = new Conjecture("math", lhs, rhs, k, c);
        e.setConfirmedRuns(confirmedRuns);
        e.setP99Slack(p99Slack);
        if (droppedByKnob != null) {
            e.setDroppedByKnob(droppedByKnob);
        }
        return e;
    }

    // ── 2026-07-04 (run-19) — served-descriptor recency gate ─────────────────

    @Test
    void servedDescriptor_isNotReservedWithinTheRecencyWindow() {
        // P-768/772: the same cramer bound became two papers in one run. After consumption
        // (markInvestigated), the SAME descriptor must not be served again within the window;
        // the pool's other relation is served instead.
        ConjectureRepository repo = mock(ConjectureRepository.class);
        Conjecture boundA = build("cramer_gap_ratio", "deficient_density", 2.0, -1.4763, 5, 0.01,
                ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE);
        Conjecture boundB = build("cramer_gap_ratio", "omega_Omega_gap", 2.0, -0.9, 3, 0.01,
                ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE);
        when(repo.findByDomain(eq("math"))).thenReturn(List.of(boundA, boundB));

        ConjectureMinerProperties p = minerProps(true);
        p.setServedDescriptorRecencyEnabled(true);
        p.setServedDescriptorRecencyHours(24);
        ObjectMapper om = new ObjectMapper();
        ConjectureMinerService svc = new ConjectureMinerService(repo, new ConjectureMapper(om),
                new OeisAnnotator(om),
                new MathConjectureSubstrateDelegate((com.emergentterrain.llm.JuliaMathClient) null), p);

        Conjecture first = svc.next("math").orElseThrow();
        svc.markInvestigated(first);
        Conjecture second = svc.next("math").orElseThrow();
        assertTrue(!first.rhs().equals(second.rhs()),
                "the just-consumed descriptor must not be re-served within the recency window");
    }

    @Test
    void servedDescriptorRecency_neverStarvesATinyPool() {
        // Never-starve guard: when the filter would empty the pool (every servable descriptor was
        // recently consumed), selection falls back to the unfiltered pool rather than serving nothing.
        ConjectureRepository repo = mock(ConjectureRepository.class);
        Conjecture only = build("cramer_gap_ratio", "deficient_density", 2.0, -1.4763, 5, 0.01,
                ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE);
        when(repo.findByDomain(eq("math"))).thenReturn(List.of(only));

        ConjectureMinerProperties p = minerProps(true);
        p.setServedDescriptorRecencyEnabled(true);
        ObjectMapper om = new ObjectMapper();
        ConjectureMinerService svc = new ConjectureMinerService(repo, new ConjectureMapper(om),
                new OeisAnnotator(om),
                new MathConjectureSubstrateDelegate((com.emergentterrain.llm.JuliaMathClient) null), p);

        svc.markInvestigated(svc.next("math").orElseThrow());
        assertTrue(svc.next("math").isPresent(),
                "a pool whose only descriptor was just served must still serve it (never starve)");
    }

    private static Conjecture retired(String lhs, String rhs, String reason, String updatedIso) {
        Conjecture e = build(lhs, rhs, 0.0, 0.5, 5, 0.001, reason);
        e.setUpdatedAt(Instant.parse(updatedIso));
        return e;
    }

    private static void markRetired(Conjecture e, String reason, Instant updated) {
        e.setDroppedByKnob(reason);
        e.setUpdatedAt(updated);
    }
}
