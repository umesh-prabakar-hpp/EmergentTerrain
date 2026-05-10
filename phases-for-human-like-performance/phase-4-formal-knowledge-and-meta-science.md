# Phase 4 — Formal Knowledge and Meta-Science

**Scope:** Iterations 9, 10

**Prerequisite:** Phases 0–3 complete. This phase is the capstone — it requires a mature body of findings,
an active citation graph, functioning institutions, and surprise/calibration data before it becomes meaningful.

**Goal:** Make the knowledge base machine-operable and let the system evolve its own scientific methodology.
This is where the architecture transitions from a sophisticated simulation to a self-improving epistemic system.

**Estimated effort:** 3–5 weeks (AI-assisted). Per-iteration breakdown: Iter 9 ≈ 1.5–2 weeks (clear JSON schemas, structured LLM output, ~3–4x compression), Iter 10 ≈ 2–3 weeks (mostly observation code with ~3x compression, but the prompt-evolution step is risky and requires careful human review at ~1.5x compression).

---

# Iteration 9 — Formal Knowledge Substrate

## What already exists

- **`PreRegistration`** (record on `PaperDraft`) — already contains `hypothesis`, `prediction`, `falsificationCondition`, `mappingNotes` as structured pre-narration data. This is the closest thing to a `FormalFinding` the codebase has.
- **`DynamicProblem`** — already structured: `predicate`, `metric`, `baseline`, `hypothesis`, `[rangeStart, rangeEnd)`. Demonstrates the system can work with formal problem specifications.
- **`MathPhysicsEngine.ResearchResult`** — already contains `primaryValue`, `secondaryValue`, `tertiaryValue`, `claimStatus` (SUPPORTED/PARTIAL/REFUTED/OBSERVATIONAL).
- **`NarrationResult`** — already JSON-parsed via `LlmService.parseNarrationResult()` (post-Phase-0: `ResearchNarrationService.parseResult()`). Adding more structured fields is straightforward.
- **`PaperRelationship` of type `CONTRADICTS`** (Phase 1) — provides edge-level contradiction signals.

What's missing is **post-narration extraction of structured findings into a queryable knowledge base**, and **automatic contradiction detection across findings** (rather than just paper-level relationships).

## What to add

```java
@Entity
public class FormalFinding {
    UUID id;
    UUID paperId;                       // FK to ResearchPaper
    UUID extractedByBotId;              // null if extracted by post-process LLM
    RelationType relationType;          // PROPORTIONAL, LOGARITHMIC, INVERSE, THRESHOLD, CATEGORICAL, NULL
    String dependentVariable;
    String[] independentVariables;
    String equationForm;                // LaTeX or symbolic form
    double confidence;                  // 0.0–1.0
    double validRegionMin;
    double validRegionMax;
    String domain;
    Instant extractedAt;
}

public class KnowledgeBaseService {
    List<FormalFinding> findingsForVariable(String variable);
    List<FormalFinding> findingsInDomain(String domain);
    List<ContradictionPair> detectContradictions();
    List<DerivedHypothesis> composeFindings();   // A~f(B) ∧ B~g(C) → A~h(C)
}
```

## Extraction pipeline

After `ResearchNarrationService.publishPaper()` finalizes a paper, run a post-process step:

1. Build an extraction prompt: "Given this narration and these computational results, emit zero or more `FormalFinding` records as strict JSON conforming to schema X."
2. The LLM returns structured findings or an empty list (some papers — narrative reviews, null results — yield no formal findings).
3. Validate against schema. Reject malformed output and retry once. If retry fails, log and skip (do not block the paper).
4. Persist to `FormalFinding` with FK to the paper.

This reuses the existing structured-output pattern used for `NarrationResult.citedPaperIds` and `BotGenome.applyDelta()` mentoring deltas. The plumbing is familiar.

## Where this plugs in

- `PaperRelationship` contradiction edges (Phase 1) become **automatically populated** by `KnowledgeBaseService.detectContradictions()` when two findings have the same `dependentVariable + independentVariables` but incompatible `relationType` or inverted directions. The Phase 1 LLM-classified version remains as a fallback for edges the algorithm misses.
- `ExperimentPlannerService.CONTRADICTION_TARGET` (Phase 2) reads from `KnowledgeBaseService.detectContradictions()` instead of just `PaperRelationship`, getting more precise targets.
- `composeFindings()` produces derived hypotheses that become `ProgramOpenQuestion` entries in active research programs.
- `MetaResearchService` (Iteration 10) measures the rate at which extracted findings hold up under replication, scoring extraction quality.

## Implementation notes

- This is the hardest iteration in the whole roadmap. The schema enforcement is critical — bad findings poison the knowledge base.
- Start with a narrow extraction schema. `RelationType` should initially support only `PROPORTIONAL`, `INVERSE`, `THRESHOLD`, and `NULL`. Add `LOGARITHMIC` and `CATEGORICAL` only after the simpler types are stable.
- Theory composition (`A~f(B) ∧ B~g(C) → A~h(C)`) is genuinely hard and easy to get wrong. Implement contradiction detection first; defer composition to Phase 4b.
- Don't try to verify the equation algebraically. The simulation is the verifier — derived hypotheses become experimental targets, not asserted truths.

---

# Iteration 10 — Recursive Meta-Science

## What already exists

- **`callPath`** on `ResearchPaper` — already an audit trail like `"audit:4b:2gaps → prof:llama-70b:ok"`. Captures which prompt path produced each paper. **This is the seed of meta-science already.**
- **`AnomalyTracker`** — Welford's online algorithm for tracking distributions. Directly reusable for tracking per-prompt replication rates, per-bot calibration trajectories, etc.
- **`calibrationScore(botId)`** (Phase 3 Iteration 7) — already tracking per-bot calibration.
- **`surpriseScore`** (Phase 2) — already tracking per-paper surprise.
- **`PaperRelationship` of type `CONTRADICTS`** (Phase 1) and `FormalFinding` contradictions (Iteration 9) — provide truth-pressure signals.
- **`ResearchIdentity.favoredMethod`** (Phase 1) — categorizes methodology so per-method analysis is possible.

A surprising amount of the *data* needed for meta-science already exists. What's missing is **systematic analysis** and **feedback into prompt/policy selection**.

## What to add

```java
public class MetaResearchService {
    record PromptPathStats(
        String callPath,                // e.g., "prof:llama-70b:ok"
        int paperCount,
        double meanReplicationRate,
        double meanCalibration,
        double meanSurprise,
        double meanCitationCount
    ) {}

    record BotMetaStats(
        UUID botId,
        double hallucinationRate,       // findings later contradicted by world
        double calibrationScore,
        double mentoringEffectiveness   // mentee performance delta
    ) {}

    record MethodMetaStats(
        Methodology method,
        double meanLongTermStability,   // findings holding up after N replications
        double meanCitationCount
    ) {}

    PromptPathStats statsForPath(String callPath);
    BotMetaStats statsForBot(UUID botId);
    MethodMetaStats statsForMethod(Methodology method);
    void runAnalysisCycle();           // scheduled, weekly sim-time
}
```

## Evolution targets, in implementation order

The original phase document listed these in increasing risk order. That ordering is correct and unchanged:

1. **Reviewer assignment** — route papers to bots whose `ideologicalBias` (from `ResearchIdentity`, Phase 1) is most orthogonal to the author's. Maximizes adversarial pressure. Low risk because it's a routing change, not a behavior change.

2. **Funding allocation** — `FundingAgency.GrantPolicy` (Phase 3 Iteration 8) already weights calibration. Extend it to also weight `hallucinationRate` and `meanLongTermStability`. Low risk, immediate benefit.

3. **Exploration heuristics** — `ExperimentPlannerService` `EXPLORATION` strategy threshold and `surpriseScore` replication trigger (currently `|z| ≥ 3.0`) become tunable. `MetaResearchService` measures false-positive rates and adjusts thresholds. Medium risk because it changes simulation dynamics.

4. **Prompt evolution** — select and mutate prompt templates based on downstream replication rates. **Highest risk.** Requires:
   - Prompt templates as versioned, registered objects (not inline strings).
   - A/B testing infrastructure that splits papers across template variants.
   - Guardrails so a degraded template can't dominate.
   - Implement only after the first three are stable.

## Where this plugs in

- `MetaResearchService` is an observer layer. It does not produce papers itself, but it does produce **meta-papers** (analyses of the system's own behavior) that go into the same paper pipeline. These meta-papers are subject to the citation graph, formal findings extraction, and epistemic markets — closing the recursive loop.
- Reviewer routing: `Journal` editor selection (Phase 3 Iteration 8) consults `MetaResearchService` for orthogonality scores.
- Funding routing: `FundingAgency` reads `BotMetaStats.hallucinationRate` alongside `calibrationScore`.
- Exploration tuning: `ExperimentPlannerService` reads dynamic thresholds from `MetaResearchService` instead of hardcoded constants.
- Prompt evolution: `FormulationService`, `ResearchNarrationService` etc. (post-Phase-0) consult a `PromptTemplateRegistry` whose template selection is driven by `MetaResearchService`.

## Implementation notes

- Most of this is observation and analysis — low-risk to add. The risk concentrates in the *evolution* steps that close the feedback loop.
- The Welford pattern in `AnomalyTracker` is the right template. Don't reimplement; subclass or extract.
- Meta-papers should be marked clearly (`paper.kind = META`) so they don't dominate the citation graph or skew domain stats.
- The recursion is real: `MetaResearchService` analyzing its own meta-papers is fine. Bound the depth (e.g., reject meta-meta-meta-papers) to prevent runaway recursion.

---

# Phase 4 Completion Criteria

## Iteration 9
- [ ] `FormalFinding` entity persisted with FK to `ResearchPaper`
- [ ] Post-narration extraction pipeline emits `FormalFinding` JSON via strict schema
- [ ] Schema validation rejects and retries malformed output; never blocks paper publication
- [ ] `KnowledgeBaseService` queryable by variable, domain, relation type
- [ ] Automatic contradiction detection populates `PaperRelationship` `CONTRADICTS` edges
- [ ] `ExperimentPlannerService.CONTRADICTION_TARGET` reads from `KnowledgeBaseService`
- [ ] Theory composition (deferred to Phase 4b) flagged as outstanding work

## Iteration 10
- [ ] `MetaResearchService` collects `PromptPathStats`, `BotMetaStats`, `MethodMetaStats`
- [ ] Welford pattern reused from `AnomalyTracker`, not reimplemented
- [ ] Reviewer assignment routes by ideological orthogonality (`Journal` editor selection)
- [ ] `FundingAgency.GrantPolicy` weights `hallucinationRate` and `longTermStability`
- [ ] `ExperimentPlannerService` thresholds become dynamic, driven by `MetaResearchService`
- [ ] `PromptTemplateRegistry` introduced; templates versioned and selectable
- [ ] A/B testing infrastructure splits papers across template variants with guardrails
- [ ] Meta-papers marked `paper.kind = META`; subject to same paper pipeline
- [ ] Recursion bound enforced (no meta-meta-meta papers)

---

# Revised Roadmap Summary

All estimates assume AI-assisted development (Claude Code or equivalent) with one developer reviewing and integrating. Compression rates vary: greenfield code with clear schemas compresses ~3–4x vs. traditional development; refactoring stateful subsystems and novel algorithmic work compresses ~1.5–2x.

| Phase | Iterations | AI-assisted | Key Driver |
|-------|-----------|-------------|------------|
| 0 | Refactor | 3–7 days | Migrate `ResearchPaper` to JPA; split `LlmService` and `UniversityService` |
| 1 | 2, 3, 4 | 2–3 weeks | Mostly formalizing existing primitives; many fields already exist on `BotGenome`/`BotLifecycleState`/`BotCivicState` |
| 2 | 1, 5 | 3–4 weeks | Surprise engine reuses Welford code; experiment planner is novel algorithmic work and compresses less |
| 3 | 6, 8, 7 | 5–7 weeks | Reordered: world bridge first, institutions next, markets last (depend on institutions). Institution refactor is the slowest item. |
| 4 | 9, 10 | 3–5 weeks | Structured extraction is fast; prompt-evolution step needs careful human review |

**Total AI-assisted range:** ~13–20 weeks of active development.

For reference, traditional single-developer estimates (no AI assistance) would be roughly 32–46 weeks. The compression is highest in Phases 1 and 4 (greenfield + clear schemas) and lowest in Phase 3 Iteration 8 (refactoring subtle stateful invariants in `UniversityService` and the bot lifecycle state machine).

## Key transitions

- **Phase 0 → Phase 1**: Code is structurally ready to absorb new domain models without ballooning existing services.
- **Phase 1 → Phase 2**: Bots have persistent identity, longitudinal memory, and a queryable graph — but still react to LLM suggestions.
- **Phase 2 → Phase 3**: First observable behavioral shift. Bots are *surprised*, plan their own experiments, and converge on programs.
- **Phase 3 → Phase 4**: First social emergence. Institutions form, markets resolve, the world becomes the lab.
- **Phase 4**: The system begins to study and improve itself.

## What you can ship before the full sequence completes

You don't need to finish all 4 phases to see value. Useful intermediate ship points:

- **End of Phase 1**: User-visible citation graph view; per-bot research history view. Demoable.
- **End of Phase 2**: Bots produce visibly more focused longitudinal research with measurable surprise events. Newsworthy.
- **End of Phase 3 Iteration 6**: World-integrated experiments produce papers about the simulation itself. Strongest demo moment.
- **End of Phase 3**: Institutional dynamics and prediction markets visible in UI.
- **End of Phase 4**: Self-improving system. Long-term value but harder to demo.

Ship between phases, not just at the end.
