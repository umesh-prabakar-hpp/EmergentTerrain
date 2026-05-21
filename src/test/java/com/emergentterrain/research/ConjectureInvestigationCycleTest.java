package com.emergentterrain.research;

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
        assertNull(draft.param2());
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
                new ConjectureTopicProvider(Optional.empty(), 1.0));  // queue absent → always empty

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
        return new ConjectureTopicProvider(Optional.of(queue), 1.0);
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
}
