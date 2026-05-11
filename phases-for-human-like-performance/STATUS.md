# Phases for Human-Like Performance — Implementation Status

This file is the rolling index of what's been shipped against the original
design docs in this directory. The phase docs ([phase-0](phase-0-prerequisites.md)
through [phase-4](phase-4-formal-knowledge-and-meta-science.md)) describe the
intended architecture; `STATUS.md` tracks what's actually in `main`.

The original 10-iteration design lives in [readme.md](readme.md) for
historical reference — that doc has not been edited since the design
phase. Named `STATUS.md` rather than `README.md` because git's
`core.ignorecase=true` on this checkout would collide `README.md` with the
existing lowercase `readme.md` and effectively hide one of the two files.

---

## Snapshot

| Metric | Value |
|--------|-------|
| Phases shipped | 6 of 6 (0, 1, 2, 2b, 3, 4) |
| Iterations shipped | 10 of 10 (every Iter from the design docs) |
| Commits | 24 |
| Net new code | ~10,000 lines |
| Tests | 135 → 234 (+99) |
| Behaviour-changing iterations | 6, all behind feature flags |

Every behaviour change is opt-in per room via `app.research.*.enabled`
flags. With every flag off, the system behaves exactly as it did before
Phase 0 began.

---

## Phase tree

### Phase 0 — Refactor foundation ✅

The doc that justified Phase 0's atomic-PR rule: even though `LlmService`
and `UniversityService` were both being carved at the same time, the
state-machine invariants couldn't tolerate half-extractions in `main`.
Ten commits landed in a tight series.

```
0.1   ResearchPaper → JPA + PaperCitation table        5dfb3e7
0.2a  Extract GenomeMentoringService                   030c5da
0.2b  Extract FormulationService                       9e6eeb8
0.2c  Extract ResearchNarrationService                 f902cb3
0.3a  Extract PopulationEcologyService                 72a4fc2
0.3b  Extract ReplicationService                       f0d4211
0.3c  Extract BotLifecycleService                      96df316
0.3d  Extract ResearchCycleService                     1f821d4
0.4   ReplicationServiceTest gap                       616e0af
```

`LlmService`: 1988 → 209 lines.
`UniversityService`: 1326 → 858 lines.

See [phase-0-prerequisites.md](phase-0-prerequisites.md).

---

### Phase 1 — Identity + graph + programs ✅

```
Iter 2  ResearchIdentity view + skepticismLevel + ReputationHistory   ffa80d0
Iter 3  Typed PaperCitation graph + PaperGraphService                 686d210
Iter 4  ResearchProgram + ProgramOpenQuestion + ProgramFailedApproach c07aa17
```

- **Iter 2** wired skepticism into peer-review tolerance and sampled
  reputation trajectory per bot every 10 ticks or on phase transition.
- **Iter 3** added `RelationshipType` (SUPPORTS / CONTRADICTS / EXTENDS /
  REPLICATES / CRITICIZES / DERIVES_FROM) plus `PaperGraphService` with
  union-find contradiction-cluster detection. All new edges default to
  `EXTENDS` until Phase 2b.2's LLM classifier upgrades them.
- **Iter 4** introduced longitudinal programs behind
  `app.research.programs.enabled`. A program accumulates evidenceFor /
  evidenceAgainst and closes as CONVERGED when the margin crosses 5.0.

See [phase-1-foundation.md](phase-1-foundation.md).

---

### Phase 2 — Surprise + planner ✅

```
Iter 1  ExpectationModelService + SurpriseEvent (Welford prior)       f2af789
Iter 5  ExperimentPlannerService (4 strategies, flag-gated)           03bdcf3
```

- **Iter 1** wires Welford-style priors per (bot, domain). Surprise
  events fire when an observed peer score is more than 2σ from the prior
  mean (after a 5-paper bootstrap). High-surprise events become new
  `ProgramOpenQuestion` entries on the bot's research program.
- **Iter 5** lets the planner replace LLM-suggested follow-ups with
  algorithmic parameter selection, behind `app.research.planner.enabled`.
  Strategy priority: CONTRADICTION_TARGET → REPLICATION → EXPLORATION →
  ADAPTIVE_SWEEP. BAYESIAN_OPTIMIZATION is the deferred Phase 2b strategy.

See [phase-2-active-experimentation.md](phase-2-active-experimentation.md).

---

### Phase 2b — Cleanup ✅

```
2b.1  Planner recordOutcome wired through publishPaper                f471fdf
2b.2  LLM-driven typed edge classification for PaperCitation          f510c77
```

Closes two explicit deferrals from Phase 1/2:

- **2b.1** threaded the planner's targeted question id through
  `PaperDraft` → `publishPaper` → `recordOutcome`, so question
  uncertainty decays automatically when a planned paper publishes.
  High-surprise outcomes decay less (the model was wrong, so less was
  learned); calm outcomes decay more.
- **2b.2** extended `NarrationResult` with a `typedCitations` list and
  taught the narration prompt to emit
  `cited_papers:[{id,type,confidence}]`. Now non-EXTENDS edges actually
  appear in the graph; PaperGraphService's contradiction-cluster
  detection has meaningful input.

---

### Phase 2.5 — RoomService refactor ⏳ deferred

Pre-Phase-3 refactor placeholder. Recommended before the Phase 3 Iter 6
write side ships. See [phase-2-5-roomservice-refactor.md](phase-2-5-roomservice-refactor.md)
for the design.

`RoomService` at 4,746 lines is the largest service in the codebase.
Carving it cleanly requires Phase 3 design pressure to identify the right
seams — premature extraction would create the wrong abstractions.
`BotArchetypeBehaviorService` is the one extraction that is almost
certainly correct regardless of how Phase 3 design lands.

---

### Phase 3 — World + institutions + markets ✅

Re-ordered from the doc's `6 → 7 → 8` to `6 → 8 → 7`: epistemic markets
(Iter 7) need institutions (Iter 8) to exist as wagering venues.

```
Iter 6 (read-only)  WorldDataQueryService                              ff6abf2
Iter 8 (MVP)        University/Journal/FundingAgency/ReplicationCouncil b481d3f
Iter 7              EpistemicMarketService                              0309225
```

- **Iter 6 (read-only)** — bots can query the running simulation as
  experimental variables (`federal.public_trust`, budgets, mean state
  trust). The write side (`WorldInterventionService`, routing bot
  proposals through the constitutional process) is deferred until
  Phase 2.5 ships.
- **Iter 8 (MVP)** — four institution entities + lazy bootstrap on
  first publish per room. Deeper behaviours (journal-mediated review,
  impact-factor accumulation from citations, FundingAgency grant
  allocation, ReplicationCouncil triggering campaigns, dominantMethodology
  emergence) are deferred to Phase 3b.
- **Iter 7** — prediction markets staked on `PreRegistration` outcomes.
  Resolution is automatic when a later paper publishes after the close
  window; `SurpriseEvent` score determines which side won. Calibration
  scores accumulate per bot so the FundingAgency.calibrationWeight has
  something to consume (currently 0.33 default; the allocation loop that
  reads it is Phase 3b).

See [phase-3-world-integration.md](phase-3-world-integration.md).

---

### Phase 4 — Formal knowledge + meta-science ✅

```
Iter 9   FormalFinding + KnowledgeBaseService                         eb323d8
Iter 10  MetaResearchService                                          ab42534
```

- **Iter 9** — paper prose becomes queryable structure. The narration
  LLM is asked for an optional `formal_findings:[{relation_type,
  dependent_variable, independent_variables, equation_form, claim_status,
  confidence, valid_region_min, valid_region_max}]` array; valid
  entries persist as `FormalFinding` rows. `KnowledgeBaseService`
  exposes by-variable / by-domain / by-paper queries and detects direct
  contradictions (PROPORTIONAL ↔ INVERSE on the same variables, or NULL
  vs. any relationship). Theory composition (A→B, B→C ⇒ A→C) is the
  Phase 4b deferral.
- **Iter 10** — the system observes itself. `MetaResearchService` slices
  history three ways: by `llmCallPath` (which prompt produced what), by
  bot (calibration + hallucination rate), by methodology (long-term
  stability). `PaperKind` (DOMAIN, META) keeps meta-research from
  polluting domain stats. Write-back evolution targets (reviewer
  routing, funding weights, prompt evolution) are Phase 4b.

See [phase-4-formal-knowledge-and-meta-science.md](phase-4-formal-knowledge-and-meta-science.md).

---

## Feature flag inventory

Every behaviour-changing iteration is gated. Default value for all is
`false` — flipping a flag enables the iteration for the rooms that
process under it.

```yaml
app.research.programs.enabled       # Phase 1 Iter 4 — longitudinal programs
app.research.planner.enabled        # Phase 2 Iter 5 — algorithmic experiment plans
app.research.institutions.enabled   # Phase 3 Iter 8 — universities/journals/funding/council
app.research.markets.enabled        # Phase 3 Iter 7 — reputation-staked markets
app.research.findings.enabled       # Phase 4 Iter 9 — formal-finding persistence
app.research.meta.enabled           # Phase 4 Iter 10 — self-observation stats
app.research.reviewer-routing.enabled  # Phase 4b — adversarial reviewer picking
app.research.funding-allocation.enabled # Phase 4b — periodic grants from agency → university
app.research.journal-review.enabled  # Phase 4b — reviewers drawn from journal editor pool
```

Phase 0 (refactor), Phase 1 Iter 2 (identity view), Phase 1 Iter 3 (typed
edge schema), Phase 2 Iter 1 (surprise scoring), Phase 2b (cleanup), and
Phase 3 Iter 6 read-side queries are not flag-gated — they're either
pure refactors or new read-only surfaces that don't change existing
behaviour.

### Rollout recipe

To enable an iteration per room, set the corresponding flag in
`application.yml` (or via env var) and restart. Each flag's service
performs lazy bootstrap on the first paper publish after the flag is on,
so existing rooms pick up the feature without a data migration.

---

## Service map

After Phase 0 + four behavioural phases, the research package is 15
specialist services + 14 entities. Sizes as of `ab42534`:

| Service | Lines | Responsibility |
|---------|-------|----------------|
| ResearchNarrationService | 1273 | narration pipeline + critique + audit + professor experiments |
| UniversityService | 880 | orchestrator + citation tracking + bridging + classification + paper persistence |
| GenomeMentoringService | 427 | LLM genome deltas + dynamic directives |
| ResearchCycleService | 425 | author selection + computation + peer review + drafts |
| BotLifecycleService | 324 | aging + competency + dating + elder merges |
| FormulationService | 288 | pre-computation hypothesis + novelty assessment |
| ExperimentPlannerService | 264 | algorithmic param selection (4 strategies, flag-gated) |
| EpistemicMarketService | 263 | reputation-staked prediction markets |
| LlmService | 209 | digest + consultation + streaming |
| MetaResearchService | 192 | self-observation stats (path/bot/method) |
| KnowledgeBaseService | 169 | formal finding persistence + contradiction detection |
| ReplicationService | 168 | replication queue + dispute marking |
| ResearchProgramService | 167 | longitudinal programs (flag-gated) |
| InstitutionService | 158 | University/Journal/FundingAgency/Council bootstrap |
| PaperGraphService | 129 | citation queries + contradiction clusters |
| ExpectationModelService | 126 | Welford priors + surprise scoring |
| WorldDataQueryService | 106 | read-only simulation variables |
| PopulationEcologyService | 73 | K + lifespan + dating cooldown |

The Phase 0 carve dropped `LlmService` from 1988 lines and
`UniversityService` from 1326 lines. Together those two services account
for over 70% of the line count reduction across the project.

---

## What's deferred (Phase 4b)

Everything below was explicitly called out in a phase doc as "deferred"
— not blockers, not omissions. They fall in roughly five categories:

### State-machine deferrals

These need stateful integration with existing services:

- **`WorldInterventionService`** (Phase 3 Iter 6 write side) — bots
  propose world changes via `GovernmentBill`; needs Phase 2.5 first.
- **Reviewer-orthogonality routing** (Phase 4 Iter 10 evolution target
  #1) — needs `BotCivicState.ideologyVector` threaded through
  `ResearchCycleService.pickReviewer`.
- **Auto-staking heuristic** (Phase 3 Iter 7) — every bot currently
  expects a manual `placeStake` call; the auto-stake loop is the
  natural behaviour to add next.
- **Cross-bot surprise broadcast** (Phase 2 Iter 1) — needs rate-limit
  infrastructure to avoid flooding neighbours' prompts.

### Service-deepening deferrals

The Phase 3 Iter 8 institutions MVP ships the entities + bootstrap but
not their full behaviour:

- ✅ **Journal-mediated review** — shipped (Phase 4b third commit).
  `ResearchCycleService.pickReviewer` now consults
  `InstitutionService.journalEditorsFor` to narrow the candidate pool
  to the top-5 reputation same-domain bots when
  `app.research.journal-review.enabled` is set. Falls back to the
  existing pool when no journal exists for the (room, domain).
- **Impact-factor accumulation** — `Journal.impactFactor` is a column
  the `PaperGraphService` citation counts can drive.
- **FundingAgency grant allocation** — the policy weights are stored;
  the allocation loop that reads them and credits universities is the
  next step.
- **ReplicationCouncil triggering** — should fire on high-surprise
  events or contradiction clusters; currently passive.
- **DominantMethodology emergence** — should aggregate from member
  bots' `ResearchIdentity.favoredMethod` over time.

### Knowledge-substrate deferrals

- **Theory composition** (Phase 4 Iter 9) — A→B and B→C imply A→C; this
  generates derived hypotheses that become `ProgramOpenQuestion` entries.
  Genuinely hard and easy to get wrong; deferred per the phase doc.
- **LOGARITHMIC / CATEGORICAL relation types** (Phase 4 Iter 9) — narrow
  to PROPORTIONAL / INVERSE / THRESHOLD / NULL initially.

### Meta-science evolution targets

Per the phase doc's ordering (ascending risk):

1. ✅ **Reviewer-orthogonality routing** — shipped (Phase 4b first commit).
   `ResearchCycleService.pickReviewer` now picks the candidate with the
   highest topic-weight orthogonality to the author instead of a random
   same-domain pick, gated by `app.research.reviewer-routing.enabled`.
2. ✅ **Funding allocation weighting** — shipped (Phase 4b second commit).
   `FundingAllocationService` self-paces every 30 ticks per room: tops
   up the agency from `BudgetState.education`, scores the room's bots
   using `GrantPolicy.{trackRecord, calibration, novelty}Weight ×
   {reputation, calibrationScore, 1 - hallucinationRate}`, and credits
   `University.computeBudget`. Persists a `Grant` row capturing the
   score breakdown. Gated by `app.research.funding-allocation.enabled`.
3. **Exploration-heuristic auto-tuning** — `ExperimentPlannerService`
   thresholds become dynamic, driven by `MetaResearchService`'s false-
   positive rate observation.
4. **Prompt-template evolution** — versioned templates, A/B testing,
   automatic rollback if downstream replication rates degrade. Highest
   risk; needs guardrails.

### Refactoring deferral

- **Phase 2.5 RoomService refactor** — 4,746-line carve. Unlocks Phase
  3 Iter 6 write side. See its dedicated design doc.

---

## How to read this directory

```
STATUS.md                                    ← you are here (shipped vs deferred)
readme.md                                    ← original 10-iteration design doc
phase-0-prerequisites.md                     ← JPA migration + service carves
phase-1-foundation.md                        ← identity + graph + programs
phase-2-active-experimentation.md            ← surprise + planner
phase-2-5-roomservice-refactor.md            ← deferred RoomService refactor
phase-3-world-integration.md                 ← world + institutions + markets
phase-4-formal-knowledge-and-meta-science.md ← formal findings + meta-science
```

Each phase doc retains its full design content. `STATUS.md` is the live
tracker of what's been shipped; the per-phase docs are the shape of
intent against which to evaluate the shipped state.
