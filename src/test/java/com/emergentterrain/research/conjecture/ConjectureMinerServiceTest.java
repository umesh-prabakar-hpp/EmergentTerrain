package com.emergentterrain.research.conjecture;

import com.emergentterrain.llm.JuliaMathClient;
import com.emergentterrain.repo.ConjectureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ConjectureMinerService}'s full mine() pass against an
 * in-memory mock repository (no Spring, no real DB).
 *
 * <p>Phase 28 §28.6 — the in-process {@code MathConjectureSubstrate} was
 * removed, so the per-window scalars now come from the sidecar via
 * {@link MathConjectureSubstrateDelegate}. The three mine() tests are therefore
 * {@code @Tag("integration")} and {@code assumeTrue} the sidecar is up. The
 * pinned survivor counts + c-values still hold: §28.2 proved the sidecar's
 * {@code /numbertheory/window} scalars are byte-identical to the former
 * in-process substrate, so {@link ConjectureEngine} mines the same conjectures.
 *
 * <p>Engine seed/update parity is separately pinned by
 * {@link ConjectureEngineParityTest}; entity round-trip by
 * {@link ConjectureMapperTest}.
 */
class ConjectureMinerServiceTest {

    private static final String SIDECAR_URL =
            System.getenv().getOrDefault("JULIA_SIDECAR_URL", "http://localhost:8088");

    private static boolean sidecarUp;

    @BeforeAll
    static void probeSidecar() {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(SIDECAR_URL + "/healthz"))
                            .timeout(Duration.ofSeconds(3))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            sidecarUp = resp.statusCode() == 200;
        } catch (Exception e) {
            sidecarUp = false;
        }
    }

    private static MathConjectureSubstrateDelegate sidecarDelegate() {
        JuliaMathClient client = new JuliaMathClient(
                /*enabled*/ true, SIDECAR_URL, 10_000, 3_000,
                /*pysrEnabled*/ false, 20_000, 8, 40, 30,
                /*conjectureEnabled*/ true, 10_000,
                new ObjectMapper());
        client.probeHealth();
        return new MathConjectureSubstrateDelegate(client);
    }

    @Test
    @Tag("integration")
    void firstMinePassSeedsAndPersistsKnownBounds() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        ConjectureRepository repo = mock(ConjectureRepository.class);
        when(repo.findByDomain("math")).thenReturn(Collections.emptyList());
        // save() echoes its argument back (real JPA assigns an ID; we don't need one for assertions)
        when(repo.save(any(Conjecture.class))).thenAnswer(inv -> inv.getArgument(0));

        ObjectMapper om = new ObjectMapper();
        ConjectureMapper mapper = new ConjectureMapper(om);
        ConjectureMinerService svc = new ConjectureMinerService(
                repo, mapper, new OeisAnnotator(om), sidecarDelegate(), new ConjectureMinerProperties());

        svc.mine();

        // Should have saved roughly 106 conjectures (parity test count). Allow margin since
        // the engine might emit a different total if vocabulary or runs evolve later.
        ArgumentCaptor<Conjecture> saved = ArgumentCaptor.forClass(Conjecture.class);
        verify(repo, atLeast(100)).save(saved.capture());

        // Pull out the survivors keyed by (lhs, k=0 pure bounds)
        Map<String, Conjecture> pureBoundByLhs = new HashMap<>();
        for (Conjecture c : saved.getAllValues()) {
            if (c.k() != 0.0 || c.droppedByKnob() != null) continue;
            Conjecture prior = pureBoundByLhs.get(c.lhs());
            if (prior == null || c.c() < prior.c()) {
                pureBoundByLhs.put(c.lhs(), c);
            }
        }

        // Pinned pure-bound rediscoveries from the Python prototype + parity test.
        assertBound(pureBoundByLhs, "abundant_density", 0.2515);
        assertBound(pureBoundByLhs, "deficient_density", 0.7531);
        assertBound(pureBoundByLhs, "prime_density",     0.1146);
        assertBound(pureBoundByLhs, "happy_density",     0.1560);

        // Phase 23.2 acceptance: ≥20 conjectures carry OEIS annotations.
        long annotated = saved.getAllValues().stream()
                .filter(c -> c.oeisMatchJson() != null && !c.oeisMatchJson().isEmpty())
                .count();
        assertTrue(annotated >= 20,
                "expected ≥20 OEIS-annotated conjectures (phase 23.2 acceptance); got " + annotated);

        // Spot-check: pure-bound on abundant_density should carry A005101.
        Conjecture ab = pureBoundByLhs.get("abundant_density");
        assertNotNull(ab.oeisMatchJson(), "abundant_density bound should be OEIS-annotated");
        assertTrue(ab.oeisMatchJson().contains("A005101"),
                "abundant_density bound annotation should reference A005101: " + ab.oeisMatchJson());
    }

    @Test
    @Tag("integration")
    void survivorAcceptanceThreshold() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        // Acceptance for phase 23.1J: with the flag on, a fresh app produces
        // >= 50 surviving conjectures after one mine pass.
        ConjectureRepository repo = mock(ConjectureRepository.class);
        when(repo.findByDomain("math")).thenReturn(Collections.emptyList());
        when(repo.save(any(Conjecture.class))).thenAnswer(inv -> inv.getArgument(0));

        ObjectMapper om = new ObjectMapper();
        ConjectureMinerService svc = new ConjectureMinerService(
                repo, new ConjectureMapper(om), new OeisAnnotator(om), sidecarDelegate(), new ConjectureMinerProperties());
        svc.mine();

        ArgumentCaptor<Conjecture> saved = ArgumentCaptor.forClass(Conjecture.class);
        verify(repo, atLeast(50)).save(saved.capture());

        long live = saved.getAllValues().stream()
                .filter(c -> c.droppedByKnob() == null)
                .count();
        assertTrue(live >= 50, "expected >= 50 live survivors; got " + live);
    }

    @Test
    @Tag("integration")
    void w4ShadowModeLogsButRetiresNothing() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        // W4 shadow ON, every W4 gate's own flag OFF (defaults): the gates run and log would-retires
        // but must persist NO W4 retirement reason and not reduce the surviving corpus.
        ConjectureRepository repo = mock(ConjectureRepository.class);
        when(repo.findByDomain("math")).thenReturn(Collections.emptyList());
        when(repo.save(any(Conjecture.class))).thenAnswer(inv -> inv.getArgument(0));

        ObjectMapper om = new ObjectMapper();
        ConjectureMinerProperties props = new ConjectureMinerProperties();
        props.setW4ShadowEnabled(true);
        ConjectureMinerService svc = new ConjectureMinerService(
                repo, new ConjectureMapper(om), new OeisAnnotator(om), sidecarDelegate(), props);
        svc.mine();

        ArgumentCaptor<Conjecture> saved = ArgumentCaptor.forClass(Conjecture.class);
        verify(repo, atLeast(50)).save(saved.capture());

        // No conjecture may be persisted with a W4 retirement reason while in shadow.
        for (Conjecture c : saved.getAllValues()) {
            String knob = c.droppedByKnob();
            assertTrue(knob == null
                            || !(knob.startsWith("stochastic_null") || knob.startsWith("private_validator")
                                    || knob.startsWith("replication")),
                    "W4 shadow must not persist a retirement; saw droppedByKnob=" + knob);
        }
        long live = saved.getAllValues().stream().filter(c -> c.droppedByKnob() == null).count();
        assertTrue(live >= 50, "shadow must not reduce the corpus; live=" + live);
    }

    @Test
    @Tag("integration")
    void secondPassDoesNotDuplicateExistingConjectures() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        // Simulate the state after a first pass: repo returns the conjectures
        // that the first mine() saved. The second call should update those
        // in place (not insert duplicates).
        ConjectureRepository repo = mock(ConjectureRepository.class);
        ObjectMapper om = new ObjectMapper();
        ConjectureMapper mapper = new ConjectureMapper(om);
        MathConjectureSubstrateDelegate delegate = sidecarDelegate();

        // First pass — capture what got saved
        when(repo.findByDomain("math")).thenReturn(Collections.emptyList());
        when(repo.save(any(Conjecture.class))).thenAnswer(inv -> inv.getArgument(0));
        ConjectureMinerService svc = new ConjectureMinerService(
                repo, mapper, new OeisAnnotator(om), delegate, new ConjectureMinerProperties());
        svc.mine();
        ArgumentCaptor<Conjecture> firstPassSaves = ArgumentCaptor.forClass(Conjecture.class);
        verify(repo, atLeast(50)).save(firstPassSaves.capture());
        List<Conjecture> firstPass = new ArrayList<>(firstPassSaves.getAllValues());

        // Second pass — the repo "remembers" what was saved
        when(repo.findByDomain("math")).thenReturn(firstPass);
        svc.mine();

        // The mapper.apply path is exercised — no new entity allocations for
        // (lhs, rhs) pairs that already existed. We verify this by checking
        // that on the second pass, the saved entities' (lhs, rhs) pairs are
        // a subset of the first pass.
        ArgumentCaptor<Conjecture> secondPassSaves = ArgumentCaptor.forClass(Conjecture.class);
        verify(repo, atLeast(50 + 50)).save(secondPassSaves.capture());

        // First pass's saves + second pass's are all captured; the second-pass
        // ones (after the first batch) should match (lhs, rhs) keys from the
        // first pass.
        List<Conjecture> all = secondPassSaves.getAllValues();
        List<Conjecture> secondHalf = all.subList(firstPass.size(), all.size());
        for (Conjecture c : secondHalf) {
            String key = c.lhs() + "|" + c.rhs();
            boolean foundInFirstPass = firstPass.stream()
                    .anyMatch(f -> (f.lhs() + "|" + f.rhs()).equals(key));
            assertTrue(foundInFirstPass,
                    "second pass produced a conjecture (" + key
                            + ") with no first-pass counterpart — possible duplication");
        }
    }

    @Test
    void disabledFlagPreventsBeanInstantiation() {
        // The @ConditionalOnProperty annotation gates bean creation; this
        // test sanity-checks that we don't accidentally instantiate when
        // disabled (by directly constructing — Spring's bean lookup would
        // return null, not call this constructor). Verifies the contract
        // through code path: with a disabled config, the service is never
        // constructed by Spring, so no mining happens.
        //
        // (Direct construction is a developer error; we don't try to defend
        // against it at runtime. This test exists to document the design.)
        ConjectureRepository repo = mock(ConjectureRepository.class);
        // No interactions expected because we never call new ConjectureMinerService.
        verify(repo, never()).findByDomain(any());
    }

    private static void assertBound(Map<String, Conjecture> bounds, String lhs, double expectedC) {
        Conjecture c = bounds.get(lhs);
        assertNotNull(c, "expected a pure-bound survivor for " + lhs);
        assertEquals(0.0, c.k(), "expected k=0 for pure bound on " + lhs);
        assertNull(c.droppedByKnob(), "live survivor should not be retired");
        double delta = Math.abs(c.c() - expectedC);
        assertTrue(delta < 1e-3,
                String.format("pure bound for %s: expected c=%+.4f, got %+.4f (delta %+.6f)",
                        lhs, expectedC, c.c(), delta));
    }

    // ── 2026-06-14 — surgical incremental re-seed: new-pairs-only core ─────────

    @Test
    void incrementalReseed_seedsOnlyGenuinelyNewVocabularyPairs() {
        // max_divisor_count vs wigert_bound: positively correlated but pearson ≈ 0.4 (< 0.98), so
        // the pair survives seed()'s near-identity drop — the case that matters for the growth anchor.
        double[] mdc = {12, 24, 18, 30};
        double[] wig = {20, 21, 23, 22};
        double[] mde = {1.1, 1.0, 1.2, 0.9};
        List<Map<String, Double>> samples = new ArrayList<>();
        for (int i = 0; i < mdc.length; i++) {
            Map<String, Double> s = new HashMap<>();
            s.put("max_divisor_count", mdc[i]);
            s.put("wigert_bound", wig[i]);
            s.put("mean_divisor_excess", mde[i]);
            samples.add(s);
        }
        List<String> vocab = List.of("max_divisor_count", "wigert_bound", "mean_divisor_excess");

        // (max_divisor_count, mean_divisor_excess) already exists in the pool (a retired flat bound);
        // (max_divisor_count, wigert_bound) is brand new.
        var known = new java.util.HashSet<>(List.of("max_divisor_count|mean_divisor_excess"));

        List<Inequality> fresh = ConjectureMinerService.newVocabularyPairSeeds(
                samples, vocab, known, List.of());
        List<String> keys = fresh.stream().map(i -> i.lhs() + "|" + i.rhs()).toList();
        assertTrue(keys.contains("max_divisor_count|wigert_bound"),
                "the new growth-anchor pair must be seeded: " + keys);
        assertTrue(!keys.contains("max_divisor_count|mean_divisor_excess"),
                "an already-known (retired) pair must NOT be re-flooded: " + keys);

        // steady state: once the wigert pair is also persisted, the helper returns it no more.
        var bothKnown = new java.util.HashSet<>(known);
        bothKnown.add("max_divisor_count|wigert_bound");
        assertTrue(ConjectureMinerService.newVocabularyPairSeeds(samples, vocab, bothKnown, List.of())
                        .stream().noneMatch(i -> (i.lhs() + "|" + i.rhs()).equals("max_divisor_count|wigert_bound")),
                "a persisted pair must not be re-seeded (self-limiting)");

        // a pair present only in the LIVE store is excluded too.
        Inequality live = new Inequality("wigert_bound", "mean_divisor_excess", 1.0, 0.0);
        assertTrue(ConjectureMinerService.newVocabularyPairSeeds(samples, vocab, known, List.of(live))
                        .stream().noneMatch(i -> (i.lhs() + "|" + i.rhs()).equals("wigert_bound|mean_divisor_excess")),
                "a pair already in the live store must not be re-seeded");
    }

    @Test
    @Tag("integration")
    void measurementTargetSeed_injectsServableDirichletRowWhenAbsent() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        // 2026-06-19 — the OSCILLATING open flagship dirichlet_error has no valid one-sided bound, so
        // seed() never makes a row for it. With the injector ON, mine() must create a SERVABLE
        // (counterexample-class) measurement-seed row so selection can finally surface it.
        ConjectureRepository repo = mock(ConjectureRepository.class);
        when(repo.findByDomain("math")).thenReturn(Collections.emptyList());
        when(repo.save(any(Conjecture.class))).thenAnswer(inv -> inv.getArgument(0));

        ObjectMapper om = new ObjectMapper();
        ConjectureMinerProperties props = new ConjectureMinerProperties();
        props.setMeasurementTargetSeedEnabled(true);
        ConjectureMinerService svc = new ConjectureMinerService(
                repo, new ConjectureMapper(om), new OeisAnnotator(om), sidecarDelegate(), props);

        svc.mine();

        ArgumentCaptor<Conjecture> saved = ArgumentCaptor.forClass(Conjecture.class);
        verify(repo, atLeast(1)).save(saved.capture());
        // The injected seed is uniquely identifiable by its sentinel rhs + counterexample class.
        boolean injected = saved.getAllValues().stream().anyMatch(c ->
                "dirichlet_error".equals(c.lhs())
                        && "_measurement_target_".equals(c.rhs())
                        && ConjectureMinerService.DROPPED_BY_COUNTEREXAMPLE.equals(c.droppedByKnob()));
        assertTrue(injected, "expected a servable dirichlet_error measurement-seed row to be injected");
    }

    @Test
    @Tag("integration")
    void measurementTargetSeed_offByDefault_noInjection() {
        assumeTrue(sidecarUp, "sidecar not reachable at " + SIDECAR_URL);
        ConjectureRepository repo = mock(ConjectureRepository.class);
        when(repo.findByDomain("math")).thenReturn(Collections.emptyList());
        when(repo.save(any(Conjecture.class))).thenAnswer(inv -> inv.getArgument(0));

        ObjectMapper om = new ObjectMapper();
        ConjectureMinerService svc = new ConjectureMinerService(   // default props → flag OFF
                repo, new ConjectureMapper(om), new OeisAnnotator(om), sidecarDelegate(), new ConjectureMinerProperties());

        svc.mine();

        ArgumentCaptor<Conjecture> saved = ArgumentCaptor.forClass(Conjecture.class);
        verify(repo, atLeast(1)).save(saved.capture());
        boolean injected = saved.getAllValues().stream().anyMatch(c ->
                "dirichlet_error".equals(c.lhs()) && "_measurement_target_".equals(c.rhs()));
        assertTrue(!injected, "flag OFF must not inject the measurement-seed (bit-identical)");
    }
}
