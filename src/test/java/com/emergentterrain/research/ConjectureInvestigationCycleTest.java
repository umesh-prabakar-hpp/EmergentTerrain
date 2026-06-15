package com.emergentterrain.research;

import com.emergentterrain.llm.JuliaMathClient;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.conjecture.ConjectureQueue;
import com.emergentterrain.research.conjecture.ConjectureTopicProvider;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 23.4b — exercises the conjecture-driven path in
 * {@link ResearchCycleService}.
 *
 * <p>Two tests:
 * <ul>
 *   <li>{@code publishConjectureInvestigation} builds a draft with the
 *       right fields (kind=CONJECTURE_INVESTIGATION, topic prefix,
 *       hypothesis = inequality, no peer reviewers, etc.) when invoked
 *       directly.</li>
 *   <li>{@code runCycle} short-circuits to that path when a topic
 *       provider with paperShare=1.0 returns a conjecture — the catalog
 *       computation is never invoked.</li>
 * </ul>
 *
 * <p>The Context implementation is a hand-rolled stub that captures the
 * dispatched draft so assertions can inspect it.
 */
class ConjectureInvestigationCycleTest {

    @Test
    void publishConjectureInvestigationBuildsRightDraft() {
        ResearchCycleService svc = new ResearchCycleService();
        Researcher author = researcher();
        CapturingContext ctx = new CapturingContext();
        Conjecture c = buildConjecture("abundant_density", "prime_density", 0.0, 0.2515,
                /* confirmedRuns */ 8, /* p99Slack */ -0.0042);

        svc.publishConjectureInvestigation(UUID.randomUUID(), author, c, 42L, ctx);

        PaperDraft draft = ctx.lastDraft.get();
        assertNotNull(draft, "publish path should have queued a draft");
        assertEquals(PaperKind.CONJECTURE_INVESTIGATION, draft.kind());
        assertTrue(draft.topicName().startsWith("conjecture/"),
                "topicName should be prefixed with conjecture/ — got " + draft.topicName());
        assertTrue(draft.topicName().contains("abundant_density"));
        assertTrue(draft.templateHypothesis().contains("abundant_density"));
        assertTrue(draft.templateHypothesis().contains("prime_density"));
        assertTrue(draft.templateHypothesis().contains("0.2515"),
                "hypothesis should cite the constant c verbatim: " + draft.templateHypothesis());
        assertTrue(draft.rawFindings().contains("Mining trace"),
                "findings should mention the mining trace: " + draft.rawFindings());
        assertEquals("EMPIRICAL", draft.methodology());
        assertTrue(draft.reviewerBotIds().isEmpty(),
                "conjecture papers ship without a peer panel for v1");
        assertNull(draft.param1());
        // param2 carries the fitted constant c for §26.4-style recognition (ResearchCycleService
        // line ~728) — the prior assertNull was stale vs that documented behavior (pre-existing).
        assertEquals(0.2515, draft.param2());
        assertFalse(draft.isFollowUp());
    }

    @Test
    void runCycleShortCircuitsWhenConjectureProviderFires() {
        ResearchCycleService svc = new ResearchCycleService();
        Conjecture c = buildConjecture("happy_density", "prime_density", 0.0, 0.156, 5, -0.001);
        svc.setConjectureTopicProvider(providerThatAlwaysReturns(c));

        Researcher author = researcher();
        CapturingContext ctx = new CapturingContext();
        // Single-element pool — tournamentSelect will pick this bot.
        List<Researcher> pool = List.of(author);
        PopulationEcologyService.PopulationParams pp =
                new PopulationEcologyService.PopulationParams(
                        10, 100L, 100L, 0.1, 0.5, 1.0);

        svc.runCycle(UUID.randomUUID(), pool, new Random(0), 7L, pp,
                /* peerReviewEnabled */ true, ctx);

        PaperDraft draft = ctx.lastDraft.get();
        assertNotNull(draft, "runCycle should have produced a conjecture-driven draft");
        assertEquals(PaperKind.CONJECTURE_INVESTIGATION, draft.kind());
        assertTrue(draft.templateHypothesis().contains("happy_density"),
                "hypothesis should reference the conjecture's lhs: " + draft.templateHypothesis());
        // No catalog topic name should appear — the catalog path never ran.
        assertFalse(draft.topicName().toLowerCase().contains("collatz"),
                "topicName should NOT come from MathPhysicsEngine catalog: " + draft.topicName());
    }

    @Test
    void runCycleFallsThroughWhenProviderReturnsEmpty() {
        ResearchCycleService svc = new ResearchCycleService();
        svc.setConjectureTopicProvider(
                new ConjectureTopicProvider(Optional.empty(), 1.0, "math"));  // queue absent → always empty
        // The catalog fallthrough computes a physics/math topic, which needs the sidecar-backed
        // delegate (in-process engine removed in §28.6). Wire a deterministic stub so this stays
        // hermetic — we assert the fallthrough does NOT publish a conjecture-investigation, not the
        // compute result itself.
        svc.setPhysicsEngineDelegate(new StubDelegate());

        Researcher author = researcher();
        CapturingContext ctx = new CapturingContext();
        List<Researcher> pool = List.of(author);
        PopulationEcologyService.PopulationParams pp =
                new PopulationEcologyService.PopulationParams(
                        10, 100L, 100L, 0.1, 0.5, 1.0);

        svc.runCycle(UUID.randomUUID(), pool, new Random(0), 7L, pp,
                /* peerReviewEnabled */ false, ctx);

        // The catalog cycle should have run. Either a paper was queued
        // (DOMAIN kind) or none was (if review rejected, etc.) — what
        // matters is we did NOT publish a conjecture-investigation.
        PaperDraft draft = ctx.lastDraft.get();
        if (draft != null) {
            assertEquals(PaperKind.DOMAIN, draft.kind(),
                    "fallthrough path must produce a catalog DOMAIN paper, not CONJECTURE");
        }
    }

    // ---------- Path B: frontier-framed conjecture investigation ----------

    @Test
    void frontierDomainForClassifiesByScalar() {
        // mertens-, divisor- and prime-class scalars map to a discovery domain; other ω/density do not.
        assertNotNull(ResearchCycleService.frontierDomainFor("mertens_delta", "log_window_size"),
                "a mertens scalar should map to a frontier domain");
        assertNotNull(ResearchCycleService.frontierDomainFor("mean_divisor_count", "log_window_size"),
                "a divisor scalar should map to a frontier domain");
        assertNotNull(ResearchCycleService.frontierDomainFor("prime_density", "log_window_size"),
                "a prime scalar should map to a frontier domain (Path A)");
        assertNotNull(ResearchCycleService.frontierDomainFor("log_window_size", "mertens_per_sqrt_W"),
                "the rhs is checked too");
        assertNull(ResearchCycleService.frontierDomainFor("abundant_density", "happy_density"),
                "an ω/density bound with no oracle → no frontier framing");
    }

    @Test
    void frontierFramingPosesBeyondSieveQuestionForMertens() {
        ResearchCycleService svc = new ResearchCycleService();
        svc.setConjectureFrontierFramingEnabled(true);
        CapturingContext ctx = new CapturingContext();
        Conjecture c = buildConjecture("mertens_delta", "log_window_size", 1.0, 0.5, 6, -0.01);

        svc.publishConjectureInvestigation(UUID.randomUUID(), researcher(), c, 1L, ctx);

        PaperDraft draft = ctx.lastDraft.get();
        assertNotNull(draft);
        assertTrue(draft.templateHypothesis().contains("asymptotic"),
                "mertens hypothesis should pose the asymptotic question: " + draft.templateHypothesis());
        assertTrue(draft.rawFindings().contains("discover_mertens_value"),
                "mertens findings should steer to the per-paper compute tool: " + draft.rawFindings());
        assertFalse(draft.rawFindings().contains("Mining trace"),
                "frontier framing should replace the in-range mining-trace text");
    }

    @Test
    void frontierFramingForDivisorUsesDivisorTools() {
        ResearchCycleService svc = new ResearchCycleService();
        svc.setConjectureFrontierFramingEnabled(true);
        CapturingContext ctx = new CapturingContext();
        Conjecture c = buildConjecture("mean_divisor_count", "log_window_size", 1.0, 0.5, 6, -0.01);

        svc.publishConjectureInvestigation(UUID.randomUUID(), researcher(), c, 1L, ctx);

        PaperDraft draft = ctx.lastDraft.get();
        assertTrue(draft.rawFindings().contains("discover_divisor_value"),
                "divisor findings should steer to the divisor compute tool: " + draft.rawFindings());
        assertTrue(draft.rawFindings().contains("recall_frontier_knowledge")
                        && draft.rawFindings().contains("divisor_D"),
                "divisor findings should also offer recall of the divisor_D corpus: " + draft.rawFindings());
        assertFalse(draft.rawFindings().contains("discover_mertens_value"),
                "the divisor domain must not point at the Mertens compute tool");
    }

    @Test
    void frontierFramingForPrimeUsesPrimeTools() {
        ResearchCycleService svc = new ResearchCycleService();
        svc.setConjectureFrontierFramingEnabled(true);
        CapturingContext ctx = new CapturingContext();
        Conjecture c = buildConjecture("prime_density", "log_window_size", 1.0, 0.5, 6, -0.01);

        svc.publishConjectureInvestigation(UUID.randomUUID(), researcher(), c, 1L, ctx);

        PaperDraft draft = ctx.lastDraft.get();
        assertTrue(draft.rawFindings().contains("discover_prime_value"),
                "prime findings should steer to the prime compute tool: " + draft.rawFindings());
        assertTrue(draft.rawFindings().contains("prime_pi"),
                "prime findings should offer recall of the prime_pi corpus: " + draft.rawFindings());
        assertFalse(draft.rawFindings().contains("Mining trace"),
                "frontier framing should replace the in-range mining-trace text");
    }

    @Test
    void densityConjectureStaysInRangeEvenWhenFramingOn() {
        ResearchCycleService svc = new ResearchCycleService();
        svc.setConjectureFrontierFramingEnabled(true);
        CapturingContext ctx = new CapturingContext();
        Conjecture c = buildConjecture("abundant_density", "happy_density", 0.0, 0.2515, 8, -0.004);

        svc.publishConjectureInvestigation(UUID.randomUUID(), researcher(), c, 1L, ctx);

        PaperDraft draft = ctx.lastDraft.get();
        assertTrue(draft.rawFindings().contains("Mining trace"),
                "ω/density bounds with no oracle keep the in-range framing even with the flag on: " + draft.rawFindings());
    }

    @Test
    void framingOffKeepsInRangeForMertens() {
        ResearchCycleService svc = new ResearchCycleService();   // flag defaults OFF
        CapturingContext ctx = new CapturingContext();
        Conjecture c = buildConjecture("mertens_delta", "log_window_size", 1.0, 0.5, 6, -0.01);

        svc.publishConjectureInvestigation(UUID.randomUUID(), researcher(), c, 1L, ctx);

        PaperDraft draft = ctx.lastDraft.get();
        assertTrue(draft.rawFindings().contains("Mining trace"),
                "with the flag off, even a mertens conjecture stays in-range: " + draft.rawFindings());
    }

    // ---------- helpers ----------

    private static Researcher researcher() {
        BotLifecycleState ls = new BotLifecycleState();
        BotGenome g = BotGenome.initialise(UUID.randomUUID(), 0);
        ls.setGenome(g);
        return new Researcher(UUID.randomUUID().toString(), ls);
    }

    private static Conjecture buildConjecture(
            String lhs, String rhs, double k, double c, int confirmedRuns, double p99Slack) {
        Conjecture e = new Conjecture("math", lhs, rhs, k, c);
        e.setConfirmedRuns(confirmedRuns);
        e.setP99Slack(p99Slack);
        return e;
    }

    private static ConjectureTopicProvider providerThatAlwaysReturns(Conjecture c) {
        ConjectureQueue queue = new ConjectureQueue() {
            @Override public Optional<Conjecture> next(String domain) { return Optional.of(c); }
            @Override public List<Conjecture> nextN(String domain, int n) { return List.of(c); }
        };
        return new ConjectureTopicProvider(Optional.of(queue), 1.0, "math");
    }

    /** Captures the most recent dispatched draft for assertion. */
    private static final class CapturingContext implements ResearchCycleService.Context {
        final AtomicReference<PaperDraft> lastDraft = new AtomicReference<>();

        @Override public String nextPaperId(UUID roomId) { return "P-test-" + roomId.toString().substring(0, 4); }
        @Override public List<ResearchPaper> getPublishedPapers(UUID roomId, int limit) { return new ArrayList<>(); }
        @Override public void publishPaper(PaperDraft draft, NarrationResult narration) {
            lastDraft.set(draft);
        }
        @Override public Consumer<PaperDraft> narratingCallback() {
            // Capture via the template-fallback publishPaper path; mirrors what happens
            // in deployments without an LLM narrator wired.
            return null;
        }
        @Override public Function<Object[], Object> formulationCallback() { return null; }
    }

    /**
     * Sidecar-free {@link MathPhysicsEngineDelegate} stand-in for the catalog fallthrough —
     * returns a fixed deterministic result so the cycle runs without a live sidecar (the
     * in-process engine was removed in §28.6). Mirrors {@code ReplicationServiceTest.StubDelegate}.
     */
    private static final class StubDelegate extends MathPhysicsEngineDelegate {
        StubDelegate() {
            super((JuliaMathClient) null);
        }

        @Override
        public MathPhysicsEngine.ResearchResult compute(String domain, int topicIndex,
                int param1, double param2, MathPhysicsEngine.SearchParams sp) {
            // Fixed MATHEMATICS:0 (prime-constellation) result — same canned value the
            // ReplicationServiceTest stub serves.
            return MathPhysicsEngine.composePrimeConstellations(
                    2, 100, 25, 8, 7, 11, 8, 7.6, 0.18, 0.25, 0.08);
        }
    }
}
