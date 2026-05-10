# Phase 2 — Active Experimentation

**Scope:** Iterations 1, 5

**Prerequisite:** Phase 1 complete (requires `ResearchProgram` for the open-questions queue, `ResearchIdentity` for prior bootstrapping, `PaperRelationship` for contradiction targeting)

**Goal:** Move from LLM-narrated experiments to self-directed, expectation-driven experimentation.
This is where the system starts behaving like a scientist rather than a paper generator.

**Estimated effort:** 3–4 weeks (AI-assisted). Per-iteration breakdown: Iter 1 ≈ 1 week (reuses `AnomalyTracker` Welford code, structured residuals, ~2–3x compression), Iter 5 ≈ 2–3 weeks (novel algorithmic work; design dominates over typing, only ~1.5x compression).

**Implementation strategy:** **Two separate PRs, one per iteration.** Do not bundle. Iter 1 (surprise engine) is observable on its own — bots produce visible surprise events and replication priorities shift. Ship it first and validate the signal before stacking the planner on top. Iter 5 is behavior-changing — `ExperimentPlannerService` replaces LLM-suggested follow-ups with algorithmic parameter selection — so ship it behind a feature flag (e.g., `app.research.planner.enabled`) and roll out per room. Within Iter 5, ship strategies incrementally: `ADAPTIVE_SWEEP` first, validate on 10+ papers, then add `CONTRADICTION_TARGET`, `REPLICATION`, `EXPLORATION` as follow-up commits. Defer `BAYESIAN_OPTIMIZATION` to a Phase 2b PR.

---

# Iteration 1 — Surprise-Driven Science Engine

## What already exists

This iteration has more groundwork than the original phase document assumed:

- **`PreRegistration`** (record on `PaperDraft`) — LLM writes `hypothesis`, `prediction`, `falsificationCondition`, `mappingNotes` **before** computation runs. Post-hoc rationalization is already prevented.
- **`DynamicProblem.baseline`** — for dynamic LLM-generated problems, `DynamicComputeEngine` already compares actual vs predicted and produces `SUPPORTED/PARTIAL/REFUTED` based on deviation thresholds (15% / 40%).
- **`NarrationResult.confidenceScore`** (0.0–1.0) — already applied as a multiplier on `peerScore`.
- **`AnomalyTracker`** — Welford's online algorithm already tracks per-(domain, topic) z-score on `peerScore`. Triggers replication at `|z| ≥ 3.0`.

What's missing is the bot's **personal expectation distribution** — `AnomalyTracker` is global per-(domain, topic), not per-bot. And the surprise score isn't computed or propagated.

## What to add

```java
public class ExpectationModelService {
    // per-bot, per-domain priors
    Map<BotDomainKey, ExpectationPrior> priors;

    record ExpectationPrior(
        double expectedMean,         // running mean of observed primary values
        double expectedVariance,     // Welford-style running variance
        Monotonicity expectedShape,  // INCREASING, DECREASING, NONLINEAR, UNKNOWN
        int sampleCount
    ) {}

    record SurpriseEvent(
        UUID paperId,
        UUID botId,
        double residual,             // observed - predicted
        double surpriseScore,        // residual / sqrt(variance), normalized
        String surpriseType          // "magnitude", "direction", "shape"
    ) {}

    SurpriseEvent scoreExperiment(UUID botId, ResearchPaper paper);
    void updatePrior(UUID botId, ResearchPaper paper);
    List<SurpriseEvent> recentHighSurprise(UUID roomId, double minScore);
}
```

## Where this plugs in

- `PreRegistration.prediction` is the structured prediction. After `MathPhysicsEngine.compute()` returns, `ExpectationModelService.scoreExperiment()` computes the residual against both: (a) the bot's prior distribution, and (b) the explicit prediction in `PreRegistration`.
- High-surprise events feed three downstream consumers:
  - `ReplicationService` (post-Phase-0): bumps replication priority above the existing `|z| ≥ 3.0` global threshold.
  - `ResearchProgram.openQuestions` (Phase 1): the surprise becomes a new open question on the program.
  - Cross-bot broadcast: bots with overlapping domains receive a "X just observed something unexpected in your area" signal that influences their next formulation.
- `AnomalyTracker`'s Welford code is directly reusable for `ExpectationPrior` updates. Don't reimplement.

## Critical insight

The original phase document was right that "novelty is unexpected structure, not new topic." But the system already has structured predictions via `PreRegistration` — the gap is just *acting on residuals*. This is much smaller than building expectation tracking from scratch.

## Implementation notes

- Bootstrap `ExpectationPrior` from the first 5 papers a bot publishes in a domain (don't compute surprise until then). After bootstrap, every new paper updates the prior via Welford.
- The "shape" component (monotonicity) is harder than the magnitude component. Implement magnitude/direction first; defer shape detection to a Phase 2b refinement.
- Cross-bot broadcast should be rate-limited. A single high-surprise event shouldn't flood every bot's prompt context.

---

# Iteration 5 — Autonomous Experimental Planning

## What already exists

- `BotLifecycleState.pendingFollowUp` (`FollowUpParams`) provides single-step chaining: the LLM suggests `param1, param2, predictedScore` for the next paper.
- `ResearchProgram.openQuestions` (Phase 1) provides multi-step queue.
- `BotGenome.paramCenter1/2` and `paramRadius1/2` provide an adaptive search region that shifts toward successful parameters via `recordSuccess()`.
- `PaperRelationship` (Phase 1, type `CONTRADICTS`) identifies contradiction edges in the citation graph.

The follow-up chaining is **LLM-suggested**, not algorithmic. There is no uncertainty-driven question selection, no parameter sweep, no Bayesian planner.

## What to add

```java
public class ExperimentPlannerService {
    // Operates per ResearchProgram. Replaces single-step pendingFollowUp.

    record ExperimentPlan(
        UUID programId,
        UUID targetQuestionId,         // from ProgramOpenQuestion
        PlanStrategy strategy,         // ADAPTIVE_SWEEP, CONTRADICTION_TARGET, REPLICATION, EXPLORATION
        double param1, double param2,
        double expectedUncertaintyReduction
    ) {}

    ExperimentPlan planNext(UUID programId);
    void recordOutcome(UUID programId, UUID paperId, double surpriseScore);
}
```

Strategies, in implementation order:

1. **`ADAPTIVE_SWEEP`** — pick the highest-uncertainty open question, vary 1–2 parameters across a defined range, narrow on each iteration.
2. **`CONTRADICTION_TARGET`** — query `PaperGraphService.findContradictionClusters()` for the program's domain. Design parameters to disambiguate.
3. **`REPLICATION`** — for high-surprise papers from this program (or program's domain peers), schedule a replication.
4. **`EXPLORATION`** — when uncertainty is uniformly low (program nearly converged), occasionally sample outside the comfortable region. Driven by `BotGenome.explorationRate`.
5. **`BAYESIAN_OPTIMIZATION`** — deferred. Implement only after the above four are stable. Use a simple Gaussian-process surrogate over (param1, param2) → score.

## Flow

```text
ResearchCycleService picks the next author bot
    → checks pendingFollowUp (legacy single-step)
    → if no follow-up, asks ExperimentPlannerService for the program's next plan
    → ExperimentPlannerService selects strategy based on program state
    → returns (param1, param2, framing notes for the LLM)
    → bot's PreRegistration prompt includes the strategy as context
```

## Where this plugs in

- Replaces `BotLifecycleState.pendingFollowUp` as the source of next-experiment parameters. Keep `pendingFollowUp` as a one-tick cache; the source of truth is the planner.
- Surprise events from `ExpectationModelService` (Iteration 1) feed `recordOutcome()` to update uncertainty rankings.
- Contradiction edges from `PaperRelationship` feed strategy selection.
- The LLM no longer decides "what to investigate next" via `FollowUpParams`. It decides *how to frame* what the planner has chosen.

## Result

Experiments become:

> self-directed

instead of:

> LLM-suggested narration artifacts.

## Implementation notes

- Start with `ADAPTIVE_SWEEP` only. Get one program running through 10+ papers with this strategy and validate that the convergence behavior is sane before adding the others.
- The planner does NOT replace the LLM's role. The LLM still writes hypotheses, narration, and conclusions — but the parameters are now algorithmic.
- For `CONTRADICTION_TARGET`, the planner needs to know which parameters discriminate between contradictory findings. Initially, just sample at the midpoint of the disputed range. This is a heuristic — refining it is Phase 2b work.
- Don't let the planner produce parameters outside `BotGenome.paramRadius` for routine sweeps. The genome's learned region is a useful prior. Only `EXPLORATION` mode should escape it.

---

# Phase 2 Completion Criteria

- [ ] `ExpectationModelService` persists per-bot, per-domain priors (mean, variance, sample count)
- [ ] Priors bootstrapped from first 5 papers per bot/domain via Welford
- [ ] `SurpriseEvent` computed against both bot prior and `PreRegistration.prediction`
- [ ] High-surprise events bump replication priority and create `ProgramOpenQuestion` entries
- [ ] Cross-bot surprise broadcast (rate-limited) influences neighboring bots' formulation
- [ ] `ExperimentPlannerService` operates per `ResearchProgram`
- [ ] `ADAPTIVE_SWEEP` strategy implemented and validated on 10+ papers in one program
- [ ] `CONTRADICTION_TARGET` reads from `PaperRelationship` contradiction clusters
- [ ] `REPLICATION` strategy schedules replications for high-surprise papers
- [ ] `EXPLORATION` strategy occasionally escapes `BotGenome.paramRadius`
- [ ] `pendingFollowUp` becomes a one-tick cache of the planner's next plan
- [ ] LLM role narrowed to framing/narration; parameters are algorithmic
