# Phase 4c — Prompt-Template Evolution (scoping)

**Scope:** Land the fourth and final Phase 4 Iter 10 evolution target — versioned prompt templates with A/B testing and automatic rollback on regression — without destabilising the simulation's narration pipeline.

**Status:** Scoping document. No code in this phase yet. Three discrete pieces of work are planned (memo → refactor → evolution loop); only the first can start immediately.

**Prerequisite:** Phase 4b shipped (10 iterations landed in the session ending 2026-05-11; see [STATUS.md](STATUS.md)). The first three Iter 10 evolution targets — reviewer routing, funding allocation, exploration tuning — must additionally be **observed running in a real simulation** before the A/B-and-rollback piece ships. "Tests pass" is not the same as "stable."

**Goal:** Replace the inline prompt strings scattered across `ResearchNarrationService`, `FormulationService`, and `GenomeMentoringService` with versioned, registry-resolved templates; split papers across template variants; and roll back variants whose downstream replication rates degrade below baseline.

**Why this is risky:** Every other Phase 4b iteration changed *routing* or *weighting*. This one changes *what the LLM actually says*. A regressed template can degrade narration quality, citation extraction quality, and theory composition quality simultaneously — and the failure mode is hard to detect because the bot keeps producing papers, they're just *worse* ones.

---

## Why this is a placeholder

The Phase 4 doc ([phase-4-formal-knowledge-and-meta-science.md](phase-4-formal-knowledge-and-meta-science.md)) is explicit:

> Within Iter 10, ship the four evolution targets as separate commits in the documented order (reviewer assignment → funding allocation → exploration heuristics → prompt evolution); the first three are routing/weighting changes, but the fourth (prompt evolution) is genuinely behavior-changing and can degrade narration quality if templates regress. Ship prompt evolution behind a feature flag (e.g., `app.research.prompt.evolution.enabled`) with A/B testing and an automatic rollback trigger if downstream replication rates drop below baseline.

And:

> **Highest risk.** Requires:
> - Prompt templates as versioned, registered objects (not inline strings).
> - A/B testing infrastructure that splits papers across template variants.
> - Guardrails so a degraded template can't dominate.
> - Implement only after the first three are stable.

The first three are shipped but not observed running. Starting the evolution loop before that has data leads to closing a feedback loop on an unproven baseline — which is exactly the kind of move the Ramsdell framing ("you have to have a long-term perspective… very little immediate gratification in this business") argues against. The simulation that's supposed to embody scientific patience would itself be built impatiently.

---

## Three pieces of work, in order

### Piece 1 — Scoping memo (no code)

**Effort:** ~1 day.
**Pre-condition:** None beyond fresh session focus.

The memo identifies, file by file:

1. **What inline prompt strings currently exist** — survey [src/main/java/com/emergentterrain/service/ResearchNarrationService.java](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java), [src/main/java/com/emergentterrain/research/FormulationService.java](../src/main/java/com/emergentterrain/research/FormulationService.java), [src/main/java/com/emergentterrain/service/GenomeMentoringService.java](../src/main/java/com/emergentterrain/service/GenomeMentoringService.java). Catalog each prompt, the parameters that get spliced in, and which downstream signal would notice if it degraded (replication rate, contradiction count, calibration score).

2. **Template registry shape.** Concrete questions to answer:
   - Is a template a `record` with `(id, version, body, parameterKeys)` or a JPA `@Entity` with persistence?
   - Where do parameter slots live — Jinja-style `{{ name }}`, Java `String.format` markers, something else?
   - How are templates seeded — Java initialiser, YAML resource, DB migration?
   - Who is allowed to *write* a new template variant — only humans, or can the LLM propose mutations?

3. **A/B split mathematics.**
   - Split granularity: per paper / per bot / per room. Recommendation TBD — per-paper has the highest statistical power but mixes signals; per-bot is interpretable but slow; per-room is observable but expensive to run.
   - Allocation: pure random, multi-armed bandit (UCB or Thompson sampling), or epsilon-greedy?
   - Minimum sample size before declaring a winner: needs a power calculation against the expected effect size. Phase doc didn't specify; the memo must.

4. **Rollback metric.** Phase doc says "downstream replication rates." Concretise:
   - Numerator: replications of papers narrated by template variant X that ended in DISPUTED status (via the existing `ReplicationService` path).
   - Denominator: replications of papers narrated by variant X, total.
   - Comparison window: rolling K papers, or fixed wall-clock window?
   - Trigger threshold: variant rolled back if its rate exceeds the baseline + some margin. What margin? What statistical test to avoid rolling back on noise?
   - Per-domain vs aggregate: a template that works for MATHEMATICS may fail for MATERIALS. Probably per-domain.

5. **Storage / state.** Where do variant statistics live? A new `PromptTemplateVariant` entity with `id`, `templateId`, `versionTag`, `body`, `papersNarrated`, `replicationsConfirmed`, `replicationsFailed`, `status` (ACTIVE/ROLLED_BACK)? Or in-memory like the other Phase 4b state? Phase doc hints persistent.

6. **Mutation strategy** (if any). The phase doc names "prompt evolution" — i.e., *selecting and mutating* templates. Three reasonable interpretations, increasing complexity:
   - **Hand-curated variant pool.** Operators write 2–3 variants per template; system picks among them. Simplest. Not really "evolution" — call this Phase 4c.1.
   - **LLM rewrites.** A meta-LLM call mutates a winning template; the new variant joins the pool. Real evolution. Phase 4c.2.
   - **Genetic / crossover from the bot genome system.** Reuse `BotGenome` machinery for prompt mutation. Most ambitious, highest tar-pit risk. Defer indefinitely.

7. **Out-of-scope decisions.** Things the memo deliberately *doesn't* answer — they belong to implementation:
   - Exact wording of any specific template.
   - Migration plan for in-flight papers when a variant is rolled back.
   - UI for human operators to inspect the template registry.

**Deliverable:** A markdown design doc that names all the open questions above and proposes recommendations. Stops short of code.

### Piece 2 — `PromptTemplateRegistry` refactor (pure refactor, no behaviour change)

**Effort:** ~1 week.
**Pre-condition:** Piece 1 done; design choices locked.

Extract the inline strings cataloged in Piece 1 into a registry. *No A/B yet. No mutation yet. No rollback yet.* The simulation behaves identically — every callsite just resolves its template through `promptTemplateRegistry.get(id, version)` instead of inlining the string.

Scope:

- New service `PromptTemplateRegistry` (location TBD; probably `com.emergentterrain.research` to keep it near the rest of Phase 4).
- New entity or in-memory store, per the memo's storage decision.
- Refactor every callsite. Each one is mechanical but the prompts are long; this is where the wall-clock time goes.
- Tests: a unit test per template confirming the resolved string matches the previous inline string byte-for-byte (regression protection).

**Flag**: probably not needed — the refactor doesn't change behaviour, so it's just a safer code shape. If callers can't tolerate any risk, gate behind `app.research.prompt-registry.enabled` with the disabled branch returning the same inline string.

**Why this can ship independently:** It doesn't touch the LLM behaviour at all. The benefit is that the *next* piece becomes a one-line config change instead of a multi-file edit.

### Piece 3 — A/B + auto-rollback evolution loop

**Effort:** Multi-week. Hard to estimate without Piece 1's decisions.
**Pre-condition:** Piece 2 shipped AND at least 2–4 weeks of real simulation runtime with the existing Phase 4b flags on, with the `ExplorationTuningService` failure-rate signal reaching steady state. See [STATUS.md](STATUS.md) §Phase 4b shipped list.

Adds the actual evolution machinery:

- A/B split: when a paper publishes, the narration pipeline asks the registry which variant to use; the registry picks one per the chosen allocation policy.
- Statistics collection: every replication outcome (from `ReplicationService.processOne` and `markPaperDisputed`) tags the originating paper's template variant; the registry updates its per-variant counters.
- Rollback trigger: when a variant's failure rate crosses the threshold defined in Piece 1, mark it ROLLED_BACK and stop allocating to it.
- (Optional Piece 4) Mutation step: per Piece 1's chosen mutation strategy.

**Flag:** `app.research.prompt-evolution.enabled` (default false, per the phase doc's explicit guidance).

**Hard requirement:** when the flag flips back off, the system must immediately revert to the registry's "default" variant per template. No persistent behavioural divergence from a flag-off state.

---

## Pre-conditions for the evolution loop (Piece 3)

These come from the phase doc's "implement only after the first three are stable" rule. Concretise "stable" as **all of**:

1. Reviewer-routing flag (`app.research.reviewer-routing.enabled`) has been on in a sandbox room for ≥ 2 weeks.
2. Funding allocation flag (`app.research.funding-allocation.enabled`) has been on for ≥ 2 weeks and the `Grant` rows it writes have been spot-checked for plausible score breakdowns.
3. Exploration tuning flag (`app.research.exploration-tuning.enabled`) has been on for ≥ 2 weeks and `ExplorationTuningService`'s log lines show per-room z-thresholds settling rather than oscillating.
4. The integration smoke test ([src/test/java/com/emergentterrain/research/Phase4bIntegrationSmokeTest.java](../src/test/java/com/emergentterrain/research/Phase4bIntegrationSmokeTest.java)) has been re-run against the post-runtime codebase and still passes.

If any of these is missing, defer Piece 3 even if Pieces 1 and 2 are done.

---

## Open design questions for the memo to answer

Carried forward from the prior session's scoping discussion. None of these have committed answers yet.

- **Per-paper vs per-bot vs per-room A/B split.** Trade-offs: power, interpretability, infrastructure cost.
- **Baseline definition.** Pre-A/B snapshot of replication rate, rolling window, or per-domain prior? What sample size before declaring a regression?
- **Rollback latency tolerance.** Phase doc says "automatic rollback if rates drop below baseline." The faster the rollback, the more it reacts to noise; the slower, the more bad papers accumulate. Pick a number.
- **Mutation policy.** Hand-curated pool only (4c.1), or LLM-driven variant generation (4c.2)? The latter is more interesting and much more dangerous.
- **Per-template-type isolation.** A narration template degrading shouldn't pull a formulation template along with it; the rollback signal must be template-scoped, not global. Confirm in the memo.
- **Recovery.** Once a variant is rolled back, does it ever come back? Phase doc is silent. Recommendation: no automatic re-promotion — re-promotion requires an explicit operator action.

---

## Out of scope for Phase 4c

To keep this from sprawling:

- **No prompt-text changes for their own sake.** The refactor in Piece 2 preserves every inline string byte-for-byte. Improvements to prompt wording are a separate (manual) workstream.
- **No multi-LLM routing.** Variant ≠ model. Template variants here all run through the same LLM model; choosing models per template is a separate concern.
- **No user-facing template editor.** Operators interact with the registry through code or YAML for now.
- **No genetic crossover with `BotGenome`.** Tempting but explicitly tar-pit risk. If it happens, it's Phase 4d.

---

## Recommended timing (as of 2026-05-11)

| Piece | Earliest start | Latest reasonable start |
|---|---|---|
| Piece 1 (memo) | Next fresh session | This month |
| Piece 2 (registry refactor) | After Piece 1 lands | Open-ended; no external pressure |
| Piece 3 (evolution loop) | After 2–4 weeks of Phase 4b runtime data | Open-ended; depends on whether the Phase 4b signals stabilise |

The cadence the rest of Phase 4b followed — small commits shipping in a single session — does *not* apply to Piece 3. That piece is genuinely multi-week and should be planned and reviewed before any code lands.

---

## Pointers for the next session

When picking this up, the things to re-read are:

- [phase-4-formal-knowledge-and-meta-science.md](phase-4-formal-knowledge-and-meta-science.md) §"Iter 10 — Recursive Meta-Science" — the original spec.
- [STATUS.md](STATUS.md) §"What's deferred (Phase 4b)" — the current state of the broader Phase 4b deferral list, with this item explicitly called out as the remaining work.
- [src/main/java/com/emergentterrain/research/ExplorationTuningService.java](../src/main/java/com/emergentterrain/research/ExplorationTuningService.java) — the pattern for "MetaResearchService observes, downstream service adjusts" that the rollback trigger will likely reuse.
- [src/main/java/com/emergentterrain/research/SurpriseBroadcastService.java](../src/main/java/com/emergentterrain/research/SurpriseBroadcastService.java) — the simplest example of a flag-gated, per-room, in-memory-state service that the registry's runtime state can imitate.
