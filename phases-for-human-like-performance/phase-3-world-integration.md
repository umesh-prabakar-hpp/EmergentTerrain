# Phase 3 — World Integration and Social Dynamics

**Scope:** Iterations 6, 8, 7 (note: re-ordered from the original 6, 7, 8)

**Prerequisite:** Phase 2 complete. The reordering matters: institutions (8) must exist before markets (7) can wager on grant funding and publication access.

**Goal:** Turn EmergentTerrain's existing world simulation into the primary experimental substrate,
and evolve science itself into an emergent social phenomenon with institutions, markets, and incentive structures.

**Estimated effort:** 5–7 weeks (AI-assisted). Per-iteration breakdown: Iter 6 ≈ 1.5–2 weeks (greenfield bridge over existing data, ~2–3x compression), Iter 8 ≈ 2–3 weeks (institution refactor against stateful `UniversityService`, only ~1.5–2x compression), Iter 7 ≈ 1.5–2 weeks (greenfield market service with clear contracts, ~2–3x compression).

**Implementation strategy:** **Three separate PRs — never bundle this phase.** A 5–7 week branch will diverge from main and become unmergeable, especially against the moving `UniversityService`. The institution refactor (Iter 8) alone is a multi-week effort and must not be entangled with world-bridge or market work. Ship in the order specified (6 → 8 → 7), not the document's original ordering: Iter 6 is the highest-leverage demo moment (papers about the simulation itself); Iter 8 establishes the wagering venues that Iter 7 needs; Iter 7 closes the loop. Within Iter 8, prefer multiple small commits — `University` first, then `Journal`, then `FundingAgency`, then `ReplicationCouncil` — rather than one big institution commit. All three iterations include behavior changes affecting existing rooms; ship each behind its own feature flag (`app.world.intervention.enabled`, `app.research.institutions.enabled`, `app.research.markets.enabled`) and enable per-room.

---

# Iteration 6 — World-Integrated Science

**This is the highest-leverage iteration in Phase 3. Do it first.**

## What already exists

The world data the original phase document wanted to expose is **already structured and queryable internally**:

- `WorldService` — tiles, highways (with traffic pressure → emergent highway formation), 6 named regions, water/industry placement.
- `GovernmentState` — `BudgetState` (education, health, infrastructure, defense, welfare), `GovernmentNode` (federal/state/district hierarchy), per-node `publicTrust`, `serviceAccess`.
- `BotCivicState` — per-bot `rightsSatisfaction`, `institutionalTrust`, `safetyPerception`, `serviceAccess`, `civicParticipation`, `partyId`, four ideology axes.
- `GovernmentSimulationService` — running political pressure, conflicts, executive orders, court cases, with a constitutional process model.
- `tileNudges: Map<String, Double>` — already a queryable map of governance effects on world tiles.

What's missing is the **bridge** that makes this experimentally accessible to the science layer.

## What to add

```java
public class WorldDataQueryService {
    // Read-only views of simulation state, framed as experimental variables

    record WorldVariable(
        String name,                  // "civic_trust", "highway_density", "education_budget"
        Scope scope,                  // GLOBAL, REGION(id), DISTRICT(id), BOT(id)
        double value,
        long observedAtTick
    ) {}

    List<WorldVariable> queryVariables(VariableQuery query);
    List<WorldVariable> historicalSeries(String name, Scope scope, int tickWindow);
}

public class WorldInterventionService {
    // Bots propose interventions as hypotheses. Service applies them carefully.

    record InterventionProposal(
        UUID proposingBotId,
        UUID researchProgramId,
        String hypothesis,            // e.g., "highway_density → civic_trust"
        Map<String, Double> changes,  // proposed parameter deltas
        long durationTicks,
        Scope scope
    ) {}

    record InterventionOutcome(
        UUID proposalId,
        Map<String, Double> beforeValues,
        Map<String, Double> afterValues,
        double residualVsHypothesis
    ) {}

    InterventionOutcome execute(InterventionProposal proposal);
}
```

## Where this plugs in

- `ExperimentPlannerService` (Phase 2) gains a new strategy: `WORLD_INTERVENTION`. When a program's hypothesis involves world variables, the planner generates an `InterventionProposal` instead of math/physics parameters.
- `ExpectationModelService` (Phase 2) plugs in directly: `PreRegistration.prediction` is a forecast of world variable change, and `surpriseScore` is the residual.
- `MathPhysicsEngine` and `WorldInterventionService` are siblings — both produce experimental outcomes that flow into the same paper pipeline.
- The forecasting capability appears for free: a bot can pre-register "civic_trust will rise to 0.7 within 50 ticks if we increase education budget by 20%," and the simulation will produce a deterministic answer.

## Why this is qualitatively different

Most "AI scientist" systems study **prose corpora** or **fixed datasets**. EmergentTerrain bots can study **a running causal system that responds to interventions**. This is closer to actual experimental science than anything an LLM-only system can do.

Falsifiability is automatic. The system either produces the predicted change or it doesn't.

## Implementation notes

- Start read-only. Implement `WorldDataQueryService` first, let bots study correlations across history, and only after that's stable add `WorldInterventionService`.
- Interventions need guardrails. Bots cannot directly mutate `GovernmentState` — proposals route through the existing legislative/executive pathways in `GovernmentSimulationService`. A bot proposing "increase education budget" actually files a `GovernmentBill` through the constitutional process. This means some interventions fail to enact (court block, low pressure, ideology mismatch) — which is itself useful experimental data.
- For variables that don't route through government (e.g., terrain regeneration parameters), provide a separate "research sandbox" room flag so live player rooms aren't disrupted by experiments.

---

# Iteration 8 — Dynamic Scientific Institutions

**Done before Iteration 7 because epistemic markets need institutions to be the wagering venues.**

## What already exists

- `GovernmentSimulationService` already models institutions: `InstitutionProfile` for legislative/executive/judicial branches with capacity, workload, morale.
- `GovernmentNode` is a recursive authority unit with budget, public trust, service access — the same pattern can model universities and journals.
- `UniversityService` already acts as a de facto research institution at the room level — but it's a service singleton, not an emergent entity.
- `BudgetState.education` already exists and feeds into bot population params.
- `PoliticalParty`, `PoliticalPressure`, `LegalPrecedent` show that the codebase can already model emergent group identities and norms.

## What's missing

The science institutions are not first-class entities. The architecture exists; it just hasn't been instantiated for science.

## What to add

```java
@Entity
public class University {
    UUID id;
    UUID roomId;
    String name;
    UUID hostingDistrictId;        // FK to GovernmentNode
    Set<UUID> memberBotIds;
    double computeBudget;          // currency for ExperimentPlanner runs
    Methodology dominantMethodology; // emerges from member ResearchIdentity distribution
    double prestige;               // accumulated from member paper citation counts
}

@Entity
public class Journal {
    UUID id;
    String name;
    String[] focusDomains;
    double rejectionRate;          // emerges from review behavior
    double impactFactor;           // emerges from citation graph
    Set<UUID> editorBotIds;        // bots with high reputation in focus domains
}

@Entity
public class FundingAgency {
    UUID id;
    UUID parentGovernmentNodeId;   // funded from BudgetState.education
    double availableFunds;
    GrantPolicy policy;            // weights for track record, calibration, novelty
}

@Entity
public class ReplicationCouncil {
    UUID id;
    Set<UUID> memberBotIds;
    Set<UUID> activeReplicationTargets;  // paper IDs being replicated
}
```

## Where this plugs in

- `UniversityService` is renamed to `UniversityOrchestrator` (or kept) and now coordinates **multiple `University` entities per room** rather than acting as the single university. Initially, instantiate one `University` per room to preserve current behavior, then allow new ones to be founded.
- `Journal` accepts/rejects papers. The existing `peerReviewEnabled` gate becomes journal-mediated: a paper is submitted to a journal, the journal's editor bots review it (using existing peer review mechanics, but now journal-scoped).
- `FundingAgency` allocates compute budget to universities, which allocate to bots/programs. Existing `BudgetState.education` feeds the agency's `availableFunds`.
- `ReplicationCouncil` is triggered by high-surprise events (Phase 2) or contradiction clusters (Phase 1 graph). Picks bots from across universities to coordinate replication campaigns.
- Institutional bias emerges naturally: a `University` with mostly empiricist members develops empiricist `dominantMethodology`, biasing its hires and grant priorities. Reform movements happen when dissenting bots break off and found new universities.

## Result

Scientific progress becomes:

```text
emergent social dynamics
```

instead of:

```text
prompt orchestration
```

## Implementation notes

- This is the most invasive change in Phase 3. `UniversityService` is 1327 lines and tightly coupled to room lifecycle (post-Phase-0 it's smaller, but still substantial). Plan for a multi-week refactor.
- Don't try to model "corruption" explicitly at first. Let it emerge from journals with biased editor pools rejecting contradictory findings. If it doesn't emerge, *then* add explicit corruption mechanics.
- Reuse `GovernmentNode` patterns for `University`. Don't invent new abstractions for things that already work.
- Institutional legitimacy already feeds the existing pressure propagation system. Connect `University.prestige` and `Journal.impactFactor` to this same pressure system so that institutional decline is visible in the simulation.

---

# Iteration 7 — Epistemic Markets

**Done last in Phase 3 because it depends on institutions as wagering venues.**

## What already exists

- Reputation system with bounded scores `[0.0, 1.0]`, baseline 0.2.
- `BotCivicState` has `wageBand`, `costBurdenBand`, `savingsPressureBand`, `debtPressureBand` — but these are **structural placeholders**, not an active economic simulation.
- `BudgetState` allocates currency at the government level, but bots don't have personal currency.

## What's missing — and the workaround

The original phase document mentions wagering "compute budget" and "grant funding." But:

- There is no active per-bot economic currency in the simulation.
- "Compute budget" exists conceptually but isn't a tracked resource.
- Grants don't exist until Iteration 8.

**Workaround: implement markets on reputation as currency, plus institution-mediated grant access (now that Iteration 8 has shipped).** Treat economic currency as a Phase 3b deferral.

## What to add

```java
public class EpistemicMarketService {
    record MarketStake(
        UUID marketId,
        UUID stakingBotId,
        StakeSide side,                // CONFIRMS, REFUTES
        double reputationStaked,       // bounded by current reputation
        double oddsAtStake
    ) {}

    record Market(
        UUID id,
        UUID hypothesisPaperId,        // paper containing the hypothesis being wagered on
        UUID resolvingPaperId,         // paper whose result resolves the market (null until resolved)
        Instant opensAt;
        Instant closesAt;              // when staking is locked
        Instant resolvesAt;             // when actual experiment completes
        MarketStatus status;
        List<MarketStake> stakes;
    ) {}

    Market openMarketFor(ResearchPaper hypothesisPaper);
    void placeStake(UUID marketId, UUID botId, StakeSide side, double reputationFraction);
    void resolveMarket(UUID marketId, ResearchPaper resolvingPaper);
    double calibrationScore(UUID botId);   // running calibration metric per bot
}
```

## Where this plugs in

- When a bot publishes a paper containing a `PreRegistration` and a falsifiable prediction, `EpistemicMarketService.openMarketFor()` opens a market on the prediction.
- Other bots stake reputation on whether the prediction will be confirmed or refuted.
- When the resolving experiment runs (via `ExperimentPlannerService` Phase 2 or `WorldInterventionService` Iteration 6), the market resolves automatically based on `surpriseScore`.
- Winning bots gain reputation; losing bots lose it. Stakes are bounded so a single bad bet doesn't destroy a researcher.
- `FundingAgency` (Iteration 8) reads `calibrationScore(botId)` and weights it in grant allocation.
- Calibration scores also feed `MetaResearchService` (Phase 4) for tracking which bots/methodologies produce well-calibrated predictions.

## Result

Bots become incentivized to:

* be correct,
* not merely persuasive.

## Implementation notes

- Reputation as currency works because the existing system already treats reputation as a bounded, slowly-decaying resource. The market just adds discrete event-driven adjustments.
- Markets need a closing time before the resolving experiment runs, otherwise the resolving bot could place a winning stake themselves. Use the `PreRegistration` write timestamp as market open, and the next planning cycle as market close.
- Defer multi-resolution markets (e.g., "will this hypothesis hold in regime X but not regime Y?"). Start with binary CONFIRMS/REFUTES.
- Bound the maximum stake fraction (e.g., 20% of current reputation) to prevent reputation collapse from a single bad bet.

---

# Phase 3 Completion Criteria

## Iteration 6
- [ ] `WorldDataQueryService` exposes `BudgetState`, `GovernmentNode`, `BotCivicState`, world tiles as queryable variables
- [ ] Historical series available with tick-window slicing
- [ ] `WorldInterventionService` accepts `InterventionProposal` from `ExperimentPlannerService`
- [ ] Government-routed interventions go through legislative/executive process (with possibility of rejection)
- [ ] Sandbox-room flag isolates experimental rooms from live player rooms
- [ ] `ExperimentPlannerService` gains `WORLD_INTERVENTION` strategy

## Iteration 8
- [ ] `University`, `Journal`, `FundingAgency`, `ReplicationCouncil` entities persisted
- [ ] One `University` per room instantiated as initial state (preserves current behavior)
- [ ] Papers route through `Journal` for review; existing peer review becomes journal-scoped
- [ ] `FundingAgency` allocates from `BudgetState.education`
- [ ] `ReplicationCouncil` triggers from high-surprise events and contradiction clusters
- [ ] Universities develop `dominantMethodology` from member `ResearchIdentity` distribution
- [ ] Institutional `prestige`/`impactFactor` connect to existing pressure propagation

## Iteration 7
- [ ] `EpistemicMarketService` opens markets on `PreRegistration` predictions
- [ ] Bots stake bounded reputation fractions; stakes lock before resolution
- [ ] Markets resolve automatically based on `surpriseScore` from Phase 2
- [ ] `calibrationScore(botId)` tracked and queryable
- [ ] `FundingAgency.GrantPolicy` weights calibration in allocation
- [ ] Reputation collapse bounded by maximum stake fraction
