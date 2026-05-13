# Phase 6 — Novelty Levers (scoping)

**Scope:** Close the two structural gaps that [REVIEW_REPORT.md](../research/a3289005-archive/REVIEW_REPORT.md) §"What would need to happen for these to approach novelty" identifies as the biggest unaddressed levers — the absence of a literature-search stage before hypothesis generation, and the absence of a Discussion / Related-Work section in narrated papers. Phase 5 lifted *correctness*; Phase 6 attempts to lift *novelty*.

**Status:** Scoping document. No code in this phase yet.

**Prerequisite:** Phase 5 Waves 0–5 shipped (see [STATUS.md](STATUS.md)). The validators (B1–B5), telemetry (C3), citation-aware scoring (C1), and dispute reconciliation (C2) must be **observed running with their flags on for ≥ 2 weeks** before Phase 6 starts. Phase 5's purpose was to make the corpus output trustworthy; Phase 6 builds on that trust to ask new questions, and the trust signal needs runtime to establish itself.

**Goal:** Two pieces, sized differently:

- **D1 — Literature-Search Stage.** Multi-week. The single largest novelty lever in the report. Add a stage between [FormulationService](../src/main/java/com/emergentterrain/service/FormulationService.java) and [ResearchNarrationService](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java) that retrieves known closed-form bounds for the (domain, topic) pair from a small curated corpus (~200 textbook results, hand-loaded as a YAML resource initially). The narrator prompt then has access to: *"Hudson 1949 reports peak 0.793 for FCC binary; this paper finds 0.789 — within 0.5% of the reference."*

  Crosses the boundary from "verify known results" to "extend known results" and is the single piece most likely to push the corpus toward genuine novelty.

- **D2 — Discussion / Related-Work Section.** Smaller, ~1 week. Add `relatedWork` and `discussion` fields to [NarrationResult](../src/main/java/com/emergentterrain/research/NarrationResult.java) and the persisted [ResearchPaper](../src/main/java/com/emergentterrain/research/ResearchPaper.java). Narrator template adds two sections. Phase 5 B1's [NumericalConsistencyValidator](../src/main/java/com/emergentterrain/research/NumericalConsistencyValidator.java) extends to check Discussion claims too. Roughly doubles narration cost; gated by `app.research.discussion-section.enabled` with slow rollout.

**Why this is risky:**

D1 in particular is a category change. Phase 5 added gates that flagged bad output; D1 changes what the LLM *sees* before it writes. If the curated corpus is wrong, biased, or stale, every paper inherits the bias. Mistakes in the literature corpus are amplified across every paper that touches a (domain, topic) pair, so the curation step needs the same care Phase 4c Piece 3's evolution loop needed before it could ship.

D2 is less risky but doubles narration token spend. The May 2026 corpus already hits Groq token-per-day caps after ~35 papers/day (Phase 5 A2 shipped the `PUBLISHED_NO_PROFESSOR` status precisely because of this); doubling narration size accelerates the quota burn.

---

## What the review report identified

From [REVIEW_REPORT.md](../research/a3289005-archive/REVIEW_REPORT.md) §"What would need to happen for these to approach novelty":

> 1. **Add a literature-search stage before hypothesis generation** — biggest single lever. The room keeps re-verifying 1820s/1929/1949 results because it doesn't know they exist.
> 2. Larger regimes / open questions — Reuss with Ef/Em → 10⁴; ternary sphere packing (still open); H-L fluctuations on 10⁹ rather than 10³ scale.
> 3. Statistical rigour at scale — n=40 aliquot sequences → 10⁷; 70 Goldbach evens → 10⁹.
> 4. Cross-checking against multiple simulators / closed-forms with disagreement analysis. This is exactly the lever that exposed the broken P-a328-19.
> 5. **Discussion / related-work section** in the narrator template — required for papers to read as research rather than as exercises.

Phase 6 takes the two starred items (#1 and #5). #2 and #3 are simulator-scale changes that touch `MathPhysicsEngine`'s per-topic methods individually — not a coherent phase. #4 (multi-simulator disagreement analysis) is interesting but speculative; it would belong in a Phase 7 if the room ever has a second simulator to compare against.

The current Strongest list — P-a328-29 (Fraunhofer 1820s), P-a328-31/82 (Arrhenius 1889), P-a328-20 (Hudson 1949), P-a328-51/52 (Reuss 1929), P-a328-83 (OEIS A002375) — all verify textbook closed-forms. Without a literature-search stage these are the ceiling.

---

## D1 — Literature-Search Stage

### Three pieces, in order

#### D1.1 — Scoping memo (no code)

**Effort:** ~2 days.
**Pre-condition:** None.

The memo identifies, file by file:

1. **What closed-form references currently exist in the engine.** Survey [MathPhysicsEngine](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java) for each `*At` method. Cite the textbook source (Reuss 1929 transverse stiffness, Hudson 1949 binary packing, Arrhenius 1889 Van't Hoff rule, etc.). The memo's first deliverable is the per-topic bibliography that D1.2 will encode.

2. **Reference-corpus shape.** Concrete questions to answer:
   - Is each reference a `(domain, topic, citation, equation_form, valid_region, magnitude_estimate)` record or a freeform YAML doc?
   - Where do references live — `src/main/resources/literature-corpus.yml`, a `LiteratureReference` JPA entity, a Markdown doc parsed on startup?
   - Who curates — humans only, or can the LLM propose references (with operator review)?
   - How is "known result" matched to the narrator's prompt — exact `(domain, topic)` lookup, fuzzy match on hypothesis text, or a hybrid?

3. **Prompt integration shape.** The narrator's first-pass prompt currently sees the engine's `findings`, `hypothesis`, and any prior peer-review feedback. The literature stage adds a third source. Concrete questions:
   - Does the reference text get injected as a system-prompt block, a user-prompt block, or a third "literature context" block in the registry's prompt template?
   - How long can the reference excerpt be? Tokens are limited.
   - When zero references match the (domain, topic), is the prompt unchanged or does it carry a "no prior literature found in this region" line?

4. **Coverage policy.** What fraction of the engine's 20 topics need a reference before the stage ships? Same coverage threshold for the INTERDISCIPLINARY topics (Prime-Gap Defect Thermal Loading etc. — most of which have no legitimate literature)?

5. **Update cadence.** When new references are added, do existing papers re-narrate? Memo recommends no — the literature stage is read-once-at-narration-time. Documented for explicitness.

6. **Out-of-scope decisions.** Things the memo deliberately doesn't answer:
   - The exact wording of any one reference.
   - Whether to ingest external corpora (arXiv, MathOverflow) — that's D1.4+, not part of D1.

**Deliverable:** Markdown design doc analogous to [phase-4c-piece-1-memo.md](phase-4c-piece-1-memo.md). Names all open questions, proposes recommendations, stops short of code.

#### D1.2 — Literature corpus + read-only service (no behaviour change)

**Effort:** ~1 week (mostly curation).
**Pre-condition:** D1.1 done; design choices locked.

Add the literature corpus and a `LiteratureSearchService` that reads it. *No prompt integration yet.* The narrator's prompts are unchanged. The service is exposed as a read-only surface for tests and future integration.

Scope:

- New `src/main/resources/literature-corpus.yml` seeded with ~200 entries — one per engine topic plus extras.
- New `LiteratureSearchService` in `com.emergentterrain.research`, loaded at startup via `@PostConstruct`. In-memory only. Reload requires restart.
- Methods: `findFor(domain, topic) → List<LiteratureReference>`, `findFor(domain, topic, predicate) → List<LiteratureReference>` (predicate filters by magnitude range or equation form).
- Tests: one assertion per topic that the service returns at least one reference for the engine's documented topics.

**Flag:** none — the service is read-only and not consumed yet.

**Why this can ship independently:** It doesn't change LLM behaviour. The benefit is that D1.3 becomes a one-prompt-edit change instead of a corpus-plus-integration sprint.

#### D1.3 — Narrator-prompt integration (behaviour change)

**Effort:** Multi-week.
**Pre-condition:** D1.2 shipped, observed running for ≥ 1 week without DB / startup issues. Plus a freshly refreshed [REVIEW_REPORT.md](../research/a3289005-archive/REVIEW_REPORT.md) that confirms Phase 5's quality lift has held — corrupted-output rates are not creeping back.

Adds the actual literature-search stage:

- New step in [ResearchNarrationService](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java) between the first-pass narration and the professor stage. Looks up references for the paper's (domain, topic) pair, builds a "literature context" block, prepends it to the professor prompt.
- New named-slot in the relevant prompt templates (`NARRATION_PROFESSOR_SYSTEM`, `NARRATION_PROFESSOR_USER`, possibly `NARRATION_REVISION`) per the [PromptTemplateRegistry](../src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java)'s slot convention.
- Empty reference list → empty slot → fallback to current behaviour. The stage is silent when no literature applies.

**Flag:** `app.research.literature-search.enabled` (default false).

**Hard requirement:** when the flag flips back off, the system must immediately revert to the registry's unaugmented prompts. The literature-augmented prompts retain version tags in the registry so Phase 4c Piece 3 can A/B them once both are stable.

---

## D2 — Discussion / Related-Work Section

### One piece — no scoping memo needed (small enough)

**Effort:** ~1 week.
**Pre-condition:** D1.2 shipped (so the related-work section has references to cite). Optional pre-condition: D1.3 shipped (so the narrator already has literature context to discuss).

Scope:

1. Add `relatedWork` and `discussion` fields to [NarrationResult](../src/main/java/com/emergentterrain/research/NarrationResult.java) and to the persisted [ResearchPaper](../src/main/java/com/emergentterrain/research/ResearchPaper.java) (JPA column additions, backwards-compatible — null is acceptable).
2. Extend the narrator template (probably `NARRATION_PRIMARY` and `NARRATION_REVISION` in [PromptTemplateRegistry](../src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java)) with two new structured sections. Bump template version per the registry's `(id, version)` convention.
3. Extend [NumericalConsistencyValidator](../src/main/java/com/emergentterrain/research/NumericalConsistencyValidator.java) (Phase 5 B1) to apply its checks to the Discussion section too — claims in the Discussion must still be consistent with the Findings table.
4. Markdown writer in [UniversityService](../src/main/java/com/emergentterrain/research/UniversityService.java).`formatPaperAsMarkdown` adds two new sections under the existing `## Conclusion`.

**Flag:** `app.research.discussion-section.enabled` (default false).

**Hard requirement:** with the flag off, `NarrationResult.relatedWork()` and `discussion()` return null and the markdown writer omits the sections. No persistent divergence.

**Token cost:** narration is roughly doubled. Operators should monitor the [Groq quota](../src/main/java/com/emergentterrain/service/LlmRouter.java) recovery times after enabling D2 and back off if `prof:ALL_TPD→suppressed` markers spike.

---

## Open design questions for the D1.1 memo

These need locking before D1.2 starts. None have committed answers yet.

- **Reference-corpus storage.** YAML resource (simple, no migration) vs JPA entity (queryable, evolvable, requires migration). Start YAML.
- **Reference granularity.** One entry per (domain, topic) → small corpus, blunt. One entry per (domain, topic, regime) → richer, much more curation. Memo picks.
- **Author / equation cited verbatim?** "Hudson (1949) reports …" vs "literature reports …". Operators should pick.
- **INTERDISCIPLINARY topic coverage.** Most have no legitimate literature; D1.2 should either explicitly mark them as "no literature applies" (cleaner) or omit them and let the service return an empty list (default behaviour). Memo picks.
- **Reference matching strategy.** Exact (domain, topic) lookup is robust but rigid. Fuzzy match on hypothesis text is flexible but adds an extraction step the LLM may game. Start exact.
- **Recovery on bad references.** When operators discover a reference is wrong (citation typo, wrong magnitude), how does it get fixed? Restart? Hot reload? Memo recommends restart.

---

## Out of scope for Phase 6

To keep this from sprawling:

- **No external corpus ingestion.** No arXiv, MathOverflow, OEIS lookups. The literature corpus is hand-curated.
- **No LLM-generated references.** Operators write the corpus; the LLM never adds entries autonomously.
- **No multi-simulator disagreement analysis** (review report novelty lever #4). Out of scope for this phase; would be a separate Phase 7.
- **No regime expansion** (review report novelty levers #2 and #3 — larger numerical regimes for the engine). Those touch the engine's per-topic methods individually and don't share enough structure to make a single phase.
- **No replacement of B1's numerical consistency validator.** D2 *extends* B1 to cover the Discussion section; the existing Findings vs Conclusion checks remain.

---

## Recommended timing (as of 2026-05-12)

| Piece | Earliest start | Latest reasonable start |
|---|---|---|
| D1.1 (memo) | After ≥ 2 weeks of Phase 5 Wave 5 runtime | Open-ended |
| D1.2 (corpus + service) | After D1.1 lands | Open-ended |
| D1.3 (prompt integration) | After D1.2 has been running ≥ 1 week | Open-ended; depends on Phase 5 quality lift holding |
| D2 (Discussion section) | Concurrent with D1.2 (no dependency); strict dependency only at D1.3 | Open-ended |

Phase 5's quick-win cadence (small commits in single sessions) does **not** apply to Phase 6. D1 in particular needs deliberation — a wrong reference corpus poisons every downstream paper. Plan and review before any code lands.

---

## Pre-conditions for D1.3 (the narrator-prompt change)

By analogy with Phase 4c Piece 3, D1.3 should not ship until *all of*:

1. D1.1 (memo) merged.
2. D1.2 (corpus + read-only service) merged and running for ≥ 1 week.
3. Phase 5 Wave 4–5 flags have all been on in a sandbox room for ≥ 2 weeks each. The validators must show stable warn-rates and the citation-aware scoring must produce sensible deltas in the room stats.
4. A fresh refresh of [REVIEW_REPORT.md](../research/a3289005-archive/REVIEW_REPORT.md) confirms the top failure-mode counts have *dropped* and stayed dropped — if Phase 5's quality lift is regressing, adding a literature stage on top will mask the regression rather than fix it.

If any of these is missing, defer D1.3 even if D1.1 and D1.2 are done.

---

## Pointers for the next session

When picking this up, the things to re-read are:

- [STATUS.md](STATUS.md) — what's shipped vs what's deferred.
- [REVIEW_REPORT.md](../research/a3289005-archive/REVIEW_REPORT.md) §"How to read this directory" — the novelty assessment that motivates Phase 6.
- [phase-5-quality-pipeline.md](phase-5-quality-pipeline.md) §"Group D — Structural (multi-week)" — the original D1/D2 sketches this doc expands on.
- [phase-4c-prompt-template-evolution.md](phase-4c-prompt-template-evolution.md) — the precedent for "memo → refactor → behaviour" cadence and the pre-condition discipline.
- [src/main/java/com/emergentterrain/service/ResearchNarrationService.java](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java) — the integration point for D1.3.
- [src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java](../src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java) — the `(id, version)` keying convention D1.3 and D2 both reuse.
- [src/main/java/com/emergentterrain/research/NumericalConsistencyValidator.java](../src/main/java/com/emergentterrain/research/NumericalConsistencyValidator.java) — D2 extends this to cover Discussion claims.
