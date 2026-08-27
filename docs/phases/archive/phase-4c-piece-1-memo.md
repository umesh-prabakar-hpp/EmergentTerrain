# Phase 4c Piece 1 — Prompt-Template Scoping Memo

**Status:** Design memo. No code. Deliverable for Piece 1 of
[phase-4c-prompt-template-evolution.md](phase-4c-prompt-template-evolution.md).
Authored 2026-05-11. Stops short of implementation; locks decisions
that Piece 2 (registry refactor) will execute against.

**Reading order:** Skim §1 to see what we're refactoring against.
§§2–7 hold the design positions; each ends with a one-line **Decision**
that Piece 2 inherits without further debate.

---

## 1. Inline-prompt survey

A prompt is a Java text block (`"""…"""`) that gets `.formatted(…)`
into an LLM call. Twelve survive across three services. Splice
parameters are listed in `(…)` for each; downstream signal that
would degrade if the template regressed is in `→`.

### `com.emergentterrain.service.ResearchNarrationService`

| Builder | Splices | Downstream signal |
|---|---|---|
| `buildNarrationPrompt` ([942](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java#L942)) | domain, topic, params, prior peer scores, formal-finding stub | `cited_papers` count, `formal_findings` extraction rate, downstream replication rate |
| `buildRefinementPrompt` ([801](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java#L801)) | draft, accumulatedFindings, critique tail | secondary narration quality (rare path) |
| `buildCritiquePrompt` ([353](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java#L353)) | draft + first-pass narration | critique JSON schema compliance |
| `buildAuditPrompt` ([453](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java#L453)) | draft + first-pass narration | audit pass rate |
| `buildProfessorSystemPrompt` (2 overloads, [503](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java#L503)) | optional auditSummary | professor experiment hit-rate |
| `buildProfessorUserPrompt` ([528](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java#L528)) | draft + narration | same as above |
| `buildRevisionPrompt` (2 overloads, [584](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java#L584)) | draft + first-pass + critique/audit | revised narration quality |
| `buildStatisticalFramingPrompt` ([900](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java#L900)) | rawFindings, domain, topic | framing pass rate |

### `com.emergentterrain.service.FormulationService`

| Builder | Splices | Downstream signal |
|---|---|---|
| `buildFormulationPrompt` ([115](../src/main/java/com/emergentterrain/service/FormulationService.java#L115)) | domain, topic, genome region, prior papers, hypothesis, novelty, *identity (Phase 1 Iter 2)* | `pre_registration` parse rate, surprise score distribution, replication rate |
| `buildNoveltyPrompt` ([242](../src/main/java/com/emergentterrain/service/FormulationService.java#L242)) | domain, topic, recent paper findings | novelty-score variance |

### `com.emergentterrain.service.GenomeMentoringService`

| Builder | Splices | Downstream signal |
|---|---|---|
| `buildGenomeMentoringPrompt` ([161](../src/main/java/com/emergentterrain/service/GenomeMentoringService.java#L161)) | bot id, domain, methodology, genome, own/peer/cross-domain papers | genome-edit acceptance rate, directive parse rate, post-mentoring score trajectory |

**Total:** 12 templates if overloads count as one; 14 strings if each
overload is a separate variant. The overload-counting question is
itself a design choice — see §2.

---

## 2. Template registry shape

### Identity

A template is identified by `(id, version)` where `id` is a stable
string (`narration.primary`, `formulation.main`, etc.) and `version`
is monotone. The registry resolves `(id)` to the *active* version at
call-time; explicit version pinning is for tests and audit.

**Decision:** Templates are records, not entities, in Piece 2.
Persistence is added in Piece 3 only when variant statistics need to
survive restarts.

### Parameter slot syntax

Today's prompts use Java `String.format` markers (`%s`, `%d`, `%.2f`)
and rely on positional argument order. This is fragile under refactor
— renaming a parameter requires re-counting positional slots.

**Decision:** Migrate to **named slots** rendered through a tiny
in-house `render(template, Map<String,Object>)` helper. Syntax:
`{{ paramName }}`. Reasons:

- Removes positional-order coupling — adding a parameter is local.
- One-line parser; no Jinja/Mustache dependency.
- Variant diffing in Piece 3 becomes structural (slot-by-slot)
  instead of textual.

This means Piece 2 is **not** a byte-for-byte refactor — it is a
syntactic migration that produces the same *rendered output* for the
same input. Regression test asserts on rendered strings, not template
bodies.

### Seeding

**Decision:** Templates ship as **Java initialisers** in
`com.emergentterrain.research.PromptTemplateRegistry`'s
`@PostConstruct`. Not YAML, not DB migrations. Reasons:

- Java init keeps the prompt body next to the doc-comment that
  explains its purpose; YAML loses that pairing.
- DB migration is over-engineering until Piece 3 needs persistent
  variant counters.
- A future move to YAML or DB is straightforward; the constructor
  signature stays the same.

### Mutation rights

**Decision:** In Piece 2, only humans write templates (Java source
edit). In Piece 3 (4c.1), still only humans — operators add new
*variants* of existing templates by appending registered records.
Piece 3.5 (4c.2 if pursued) would add an LLM-driven mutation step;
that's deliberately deferred.

---

## 3. A/B split mathematics

### Granularity: **per-paper**

| Option | Power | Interpretability | Cost |
|---|---|---|---|
| per-paper | high | mixed signals across bots | none |
| per-bot | medium | clean per-bot story | slow |
| per-room | low | observable | expensive |

**Decision:** **Per-paper** with bot-id as a stratification covariate
in the rollback test. Mixed-signal worry is solved by stratification,
not by coarser splits. Per-room is genuinely too slow — the
simulation produces ~10–50 papers/room/hour; per-room A/B would take
days to reach significance.

### Allocation policy: **epsilon-greedy, ε = 0.10**

| Option | Convergence speed | Implementation cost | Risk |
|---|---|---|---|
| pure random (50/50) | slowest | trivial | none |
| epsilon-greedy | fast | trivial | parameter tuning |
| Thompson sampling | fastest | medium | over-confidence on early data |
| UCB | fast | medium | sensitive to scale of reward |

**Decision:** Epsilon-greedy with ε=0.10. 90% of papers route to the
current best variant, 10% explore. Thompson sampling is more
statistically elegant but the per-template sample sizes here are
small — 10–50 papers/hour, 4–8 hours to first signal — and Thompson
over-commits early. Epsilon-greedy degrades gracefully on noisy data.

### Minimum sample size before declaring a winner

Power calculation against the most-relevant rollback signal
(replication failure rate). Assume baseline failure rate ≈ 20%
(rough number; refine after Phase 4b runtime data lands), MDE
(minimum detectable effect) of +5pp absolute. Two-proportion z-test,
α=0.05, power=0.80:

```
n ≈ 2 × (z_α/2 + z_β)² × p̄(1-p̄) / Δ²
  = 2 × (1.96 + 0.84)² × 0.20 × 0.80 / 0.05²
  = 2 × 7.84 × 0.16 / 0.0025
  ≈ 1004 papers per arm
```

**Decision:** Variant needs **≥ 1000 narrated papers** in a rolling
window before the rollback comparison runs. Below that, the variant
stays in the active pool regardless of current rate.

This is conservative — it means a freshly added variant won't be
rolled back by a noisy first 200 papers, which is the failure mode
to avoid. It also means rollback is slow: ~10–100 hours of room
runtime before the threshold can fire. That latency is acceptable
because the *worst* a bad variant does in 1000 papers is widen the
replication queue, not corrupt the simulation state.

---

## 4. Rollback metric

### Numerator and denominator

- **Numerator:** count of papers narrated by variant *V* that were
  later marked DISPUTED via `ReplicationService.markPaperDisputed` OR
  whose replication outcome (via `ReplicationService.processOne`)
  resolved as a failure (`ReplicationOutcome.FAILED`).
- **Denominator:** count of papers narrated by variant *V* that
  reached a terminal replication state (CONFIRMED or FAILED). Papers
  still in the replication queue do **not** count.

### Window

**Decision:** **Rolling K = 1000 papers per variant.** Drops the
oldest paper outcome each time a new outcome arrives. Wall-clock
windows have the wrong invariant — a quiet room would let an old
sample dominate forever.

### Threshold

**Decision:** Variant V is rolled back when both hold:

1. `failure_rate(V) ≥ baseline_rate + 0.05` (5pp absolute margin), AND
2. Two-proportion z-test, α=0.01, rejects the null that V matches
   baseline.

The α=0.01 (rather than 0.05) gives a multiple-comparison cushion
since the system runs the test continuously as new outcomes arrive.

Baseline = the active default variant's failure rate over its own
last K outcomes.

### Per-domain vs aggregate

**Decision:** **Per-domain.** A narration template that performs
well on MATHEMATICS may fail on MATERIALS — they have different
prior densities, different result vocabularies. Each
(template_id, domain) pair has its own counters; rollback fires
per-domain.

The downside: a variant might be active for MATHEMATICS but
rolled-back for MATERIALS. The registry must support that — it can,
with `(template_id, domain) → variant_id` as the resolution key.

---

## 5. Storage / state

**Decision:** Two-stage.

- **Piece 2 (registry refactor):** in-memory only. The registry
  holds the active variant per template id; counters are not yet
  needed.
- **Piece 3 (evolution loop):** persistent. New entity:

  ```java
  @Entity
  class PromptTemplateVariant {
      UUID id;
      String templateId;       // e.g. "narration.primary"
      String versionTag;       // e.g. "v3-2026-06-12"
      String body;             // the rendered template
      int    papersNarrated;   // counter, incremented on render
      int    replicationsConfirmed;
      int    replicationsFailed;
      Status status;           // ACTIVE, ROLLED_BACK, RETIRED
      Instant createdAt;
      Instant rolledBackAt;    // null while ACTIVE
  }
  ```

  Per-domain counters live on a sibling table
  `PromptTemplateVariantDomainStats(variantId, domain, narrated,
  confirmed, failed)`. This avoids growing the variant row each time a
  domain is added.

The Piece-2 registry reads its initial entries from Java
initialisers. Piece 3 syncs them into the database on startup if
absent (idempotent upsert keyed on `(templateId, versionTag)`).

---

## 6. Mutation strategy

The phase doc names three options. The memo's position:

- **Piece 3 (4c.1) — hand-curated variant pool.** Operators write
  2–3 variants per template; system A/Bs and rolls back. This *is*
  the evolution loop's MVP. Ship this first.
- **Piece 3.5 (4c.2) — LLM-driven variant generation.** A meta-LLM
  prompt mutates the current best variant; the new variant joins
  the pool. **Defer** until 4c.1 has been observed running for
  ≥ 2 weeks and the rollback machinery has fired at least once on a
  real (not synthetic) regression.
- **4c.3 — Genetic crossover with `BotGenome`.** The Phase 4c
  scoping doc calls this "tar-pit risk." The memo concurs: do not
  pursue. If it ever happens, it's Phase 4d, not 4c.

**Decision:** Piece 3 ships 4c.1 only. 4c.2 is a deliberate later
step, gated on observed 4c.1 stability. 4c.3 is dropped from the
roadmap.

---

## 7. Out-of-scope decisions (deliberately not answered here)

These belong to implementation, not design:

- Exact wording of any specific template variant (operator work).
- Migration plan for in-flight papers when a variant is rolled back
  — likely "no migration; in-flight papers complete on their original
  variant" but this is a Piece-3 implementation detail.
- UI for inspecting the registry — defer until an operator complains.
- Whether rolled-back variants ever get re-promoted automatically.
  Memo's recommendation echoes the scoping doc: **no auto re-promotion;
  re-promotion requires explicit operator action.**

---

## 8. Cross-cutting decisions (summary)

For Piece 2 to inherit verbatim:

| Decision area | Choice |
|---|---|
| Template identity | `(id, version)`; record, not entity |
| Slot syntax | `{{ name }}`; in-house renderer |
| Seeding | Java initialiser in registry's `@PostConstruct` |
| Mutation rights | Humans only (4c.1 and 4c.2 alike) |
| A/B granularity | per-paper, stratified by bot |
| Allocation | epsilon-greedy, ε = 0.10 |
| Min sample for rollback | 1000 papers per variant per domain |
| Rollback test | two-proportion z, α = 0.01, MDE 5pp |
| Window | rolling K = 1000 outcomes |
| Per-domain isolation | yes |
| Storage (Piece 2) | in-memory |
| Storage (Piece 3) | `PromptTemplateVariant` + per-domain stats |
| Mutation strategy | 4c.1 (hand-curated) only initially |
| Re-promotion | manual only |

---

## 9. Open questions still unresolved

These do **not** block Piece 2 — Piece 2 ships with sensible defaults
that Piece 3 can refine.

1. **Baseline-rate seed.** Pre-A/B, what's the failure rate of the
   current production prompts? Need to instrument the existing
   replication path to log per-paper variant id (which doesn't exist
   yet) and accumulate rates over ≥ 1 week of real runtime. Without
   that, the 0.20 baseline assumption in §3 is a guess.
2. **Cross-template interference.** If `narration.primary` and
   `formulation.main` both have variants and both roll back at the
   same time, the room's output quality drops in two places at once.
   Probably fine — each rollback fires independently — but the
   integration smoke test should cover the simultaneous-rollback
   case before Piece 3 ships.
3. **Failure-rate denominator drift over time.** As bots accumulate
   reputation, the population of papers being replicated changes
   composition. The "failure rate" is not stationary; the rollback
   test assumes near-stationarity over the rolling K. This is a
   known limitation; revisit after first month of Piece 3 runtime.

---

## 10. What this memo does NOT lock in

- Any prompt body. The active prompt strings remain unchanged through
  Piece 2. Variant authoring is operator work that happens *after*
  Piece 2 lands.
- The exact `PromptTemplateRegistry` API surface — Piece 2 will
  produce that with normal code review.
- Whether the registry lives in `com.emergentterrain.research` or a
  new `…research.prompts` subpackage. The scoping doc suggests the
  former; Piece 2 can move it if there's a strong reason.

---

## 11. Pre-conditions check before starting Piece 2

Per [phase-4c-prompt-template-evolution.md](phase-4c-prompt-template-evolution.md)
§"Pre-conditions for the evolution loop" — those gates apply to Piece
3, not Piece 2. Piece 2 is a pure refactor that ships independently.

The minimum gates for Piece 2 to start:

1. ✅ Phase 4b shipped (this is the case as of 2026-05-11).
2. ✅ This memo exists and its decisions are accepted.
3. ✅ Per-paper template provenance instrumented (2026-05-11). New
   `narration_template_id` / `narration_template_version` columns on
   [ResearchPaper](../src/main/java/com/emergentterrain/research/ResearchPaper.java);
   constants for all twelve template ids declared in
   [PromptTemplateIds](../src/main/java/com/emergentterrain/research/PromptTemplateIds.java);
   [NarrationResult](../src/main/java/com/emergentterrain/research/NarrationResult.java)
   carries `templateId` / `templateVersion` and the publish path
   ([UniversityService.publishPaper](../src/main/java/com/emergentterrain/research/UniversityService.java))
   copies them onto the persisted row. Today every persisted paper
   carries `("narration.primary", "v1")`; when Piece 3 introduces
   variants the registry decides which version flows through.
   Pinned by
   [PromptTemplateProvenanceTest](../src/test/java/com/emergentterrain/research/PromptTemplateProvenanceTest.java).
   Backfill is no longer required when Piece 3 starts.

---

## 12. Pointer for whoever picks up Piece 2

Start with `buildNarrationPrompt` ([ResearchNarrationService.java:942](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java#L942))
— it's the longest, has the highest blast radius (cited paper count,
formal-finding extraction, replication rate all hang off it), and
will surface every refactor pain point first. If the migration of
that one prompt to the registry is ergonomic, the other 11 follow
the same shape. If it's not, redesign the registry API before doing
the rest.
