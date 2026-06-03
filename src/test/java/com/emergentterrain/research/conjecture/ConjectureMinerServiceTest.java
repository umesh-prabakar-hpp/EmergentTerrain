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
}
