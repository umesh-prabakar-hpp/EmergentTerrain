# Phase 4c Piece 3 — Persistence + Epsilon-Greedy Selector (design doc)

**Status (2026-05-20):** Design doc, no code yet. Re-evaluation of the
eight cross-cutting decisions locked in `archive/phase-4c-piece-1-memo.md`
(2026-05-11) against current code state, followed by a 4-PR
implementation plan. Created in response to phase-18 §18.3.

The phase-18 doc said: "When Piece 3 implementation starts, re-read
the memo's eight locked decisions and judge whether they still fit."
That's what this doc does. Specifically two of the locked numbers
(ε=0.10 and the 1000-paper threshold) need adjustment.

**Bigger issue surfaced before any code:** there are no genuine A/B
candidate variants registered today. The mechanism will be theatre
without operator-authored alternative prompts.

---

## Current state — what Piece 2 left behind

Verified 2026-05-20 against `main` (commit `798ad5b`):

| Surface | State |
|---|---|
| `PromptTemplateRegistry` | Exists; 22 `register(new PromptTemplate(...))` calls in `seedDefaults()`. |
| `PromptTemplateIds` | 12 distinct template ids declared. |
| `pickActive(id)` | Returns the **last-registered** variant for the id (placeholder; Piece 3 was supposed to replace this with an epsilon-greedy pick). |
| Multi-variant ids | Only `NARRATION_REVISION_ID` (two: `v1-from-critique` + `v1-from-synthesis`), and those are role-disambiguated by callsite — not A/B candidates. |
| Per-paper provenance | Persisted on `ResearchPaper.narrationTemplateId` + `narrationTemplateVersion` (Phase 4c Piece 2 gate 3 shipped). |
| Outcome side | `ResearchPaper.status` ∈ {SUPPORTED, PUBLISHED, NULL_RESULT, REJECTED, NEEDS_REVISION, DISPUTED, DISPUTE_ABANDONED, PUBLISHED_NO_PROFESSOR}. `ReplicationService` writes DISPUTED + the new `DISPUTE_ABANDONED` (Phase 21 §21.2). |

**Nothing in the current codebase actually needs the epsilon-greedy
selector.** All 12 template ids resolve to exactly one variant in
production. Piece 3 builds the machinery; **a follow-up needs operator
work to author at least one alternative variant.**

---

## Re-evaluation of the eight locked decisions

Table from the memo §8 followed by the current verdict:

| # | Decision area | Memo locked | 2026-05-20 verdict |
|---|---|---|---|
| 1 | Template identity | `(id, version)`; record, not entity | **HOLDS.** Records still adequate; persistence sidecar adds counters without touching the in-memory shape. |
| 2 | Slot syntax | `{{ name }}`; in-house renderer | **HOLDS.** No Spring-EL leakage observed; renderer works. |
| 3 | Seeding | Java initialiser in `@PostConstruct` | **HOLDS.** Already the case. Piece 3 idempotent-upserts these to DB on startup; in-memory map stays the runtime read path. |
| 4 | Mutation rights | Humans only (4c.1 + 4c.2) | **HOLDS.** No reason to revisit. |
| 5 | A/B granularity | Per-paper, stratified by bot | **HOLDS** but with a caveat — see "open questions" below. The doc's "per-bot stratification" was lock for the *rollback test*, not the allocation; that still applies. Allocation stays per-paper. |
| 6 | Allocation | epsilon-greedy, ε = 0.10 | **REVISE** ε = 0.10 → ε = 0.20 OR keep ε = 0.10 with explicit revisit-at-first-rollback. See "Decision 6 revision" below. |
| 7 | Min sample for rollback | 1000 papers per variant per domain | **REVISE** to 200 papers per variant **aggregated across domains** (cross-domain stratified test instead of per-domain isolation). See "Decision 7 revision" below. |
| 8 | Rollback test | Two-proportion z, α = 0.01, MDE 5pp | **REVISE MDE** 5pp → 10pp. Direct consequence of the smaller sample budget in #7. |
| 9 | Window | Rolling K = 1000 outcomes | **REVISE** to K = 200 outcomes, matching #7. |
| 10 | Per-domain isolation | yes | **REVISE** to "no, aggregate cross-domain with domain as covariate". Same root cause: corpus rate. |
| 11 | Storage (Piece 2) | in-memory | HOLDS (Piece 2 trait, not Piece 3's call). |

### Decision 6 revision — ε from 0.10 to 0.20

Memo's reasoning for ε = 0.10: "90% of papers route to the current
best variant, 10% explore … per-template sample sizes here are
small — 10–50 papers/hour, 4–8 hours to first signal".

**What changed.** The observed corpus rate is ≈ 38 papers/day across
ALL templates (529 papers / 14 days, May 6–May 20 archived corpus),
not 10–50 papers/hour. The memo's hourly estimate predates the more
expensive Phase 17 panel-revision pipeline. With 38 papers/day:

- ε = 0.10 → ~4 exploration papers/day across all 12 templates.
- A new variant collects ~4/12 ≈ 0.3 papers/day to its arm if it shares
  template with one default. **Time to 200 samples: ~600 days.**
- ε = 0.20 doubles exploration to ~8 papers/day, halves convergence
  time to ~300 days.

Even ε = 0.20 is glacially slow. The real lever is corpus rate, not
ε. **Recommendation: ε = 0.20**, with a named trigger to revisit if
corpus throughput rises above ~200 papers/day (then go back to 0.10
to bias more toward exploitation).

### Decision 7 revision — threshold from 1000 to 200, drop per-domain

The original power calculation:

```
n ≈ 2 × (z_α/2 + z_β)² × p̄(1-p̄) / Δ²
  = 2 × (1.96 + 0.84)² × 0.20 × 0.80 / 0.05²
  ≈ 1004 papers per arm
```

Two inputs need reset against current state:

1. **Baseline failure rate** assumed 20%. Phase 21 §§21.3, 21.4, 21.6
   add three new failure-mode gates (symbolic-solver echo, premise
   preflight, citation-validator sweep whitelist). Their net effect
   on REJECTED + DISPUTED rate is unmeasured against the rebuilt
   binary, but Phase 21's §21.2 alone removes 28% of the WARN volume
   (dispute-orphan retire). A defensible re-estimate is closer to
   10–15% baseline failure rate. With `p̄ = 0.12`:

   ```
   n ≈ 2 × 7.84 × 0.12 × 0.88 / 0.05²
     ≈ 662 papers per arm
   ```

   With **MDE relaxed to 10pp** (the doc's δ = 0.10):

   ```
   n ≈ 2 × 7.84 × 0.12 × 0.88 / 0.10²
     ≈ 166 papers per arm
   ```

   That's the **200 threshold**.

2. **Per-domain isolation** was justified by the prior that prompt
   quality varies across MATHEMATICS / PHYSICS / MATERIALS /
   INTERDISCIPLINARY. The corpus has only 2026-05-06 → 2026-05-20
   of post-Phase-4c data (529 papers), distributed across 4–5
   domains; per-domain isolation gives ~130 papers per (domain ×
   default arm). Even at the relaxed 200-per-arm threshold, no
   per-domain bucket fills within a year.

   **Drop per-domain isolation.** Aggregate across domains; include
   `domain` as a stratification covariate in the z-test (Mantel-
   Haenszel style) so a domain-specific regression is still
   detectable, but the rollback decision uses the aggregate.

   When (or if) corpus rate climbs to ~200 papers/day, re-enable
   per-domain. Named trigger: average over a 14-day window.

### Decision 8 revision — MDE from 5pp to 10pp

Falls out of #7 algebraically. The system tolerates a wider
detection window in exchange for actually-reachable sample sizes.
A new variant with a 5pp regression now stays unnoticed for longer;
mitigation is that the operator can manually roll back any variant
at any time via an explicit `status: ROLLED_BACK` flip.

---

## Open questions that still are open

The memo §9 listed three. Updated for 2026-05-20:

1. **Baseline-rate seed.** Memo still right that this needs Phase 4b
   runtime data. Now we have it — 529 papers across recent rooms.
   *Action:* compute `failure_rate(narration.primary v1)` from the
   archived corpus before the Piece 3 selector goes live.
   Stash as the initial `baseline_rate` constant in `PromptTemplateVariantStats`.
2. **Cross-template interference.** Memo's concern: when
   `narration.primary` and `narration.refinement` both have multiple
   variants, the rollback test on one is confounded by the other.
   Still real. *Mitigation:* until two templates simultaneously have
   2+ variants, the interference question is hypothetical. Defer.
3. **Failure-rate denominator drift over time.** Bots accumulate
   experience and the same prompt may produce different failure rates
   from a year-old bot vs a fresh one. *Mitigation:* the rolling
   K-window already handles this (drops stale samples). Acceptable.

**One NEW open question (2026-05-20):**

4. **No A/B candidates today.** The registry has only one revision-
   role pair under `NARRATION_REVISION_ID`, and those are role-
   disambiguated, not alternative phrasings. Piece 3's mechanism is
   useless until at least one operator-authored alternative variant
   exists for a single-variant template. *Action:* operator writes
   `v2` of (e.g.) `narration.critique` simultaneously with Piece 3
   landing, OR the order is reversed and Piece 3 sits dormant until
   the alternative variant lands.

---

## Implementation plan — four PRs

Sized so each is reviewable in one session.

### PR1 — Entity + repo + idempotent seed

**Status (2026-05-20):** ✅ **LANDED**.

**Files shipped:**
- `src/main/java/com/emergentterrain/research/PromptTemplateVariant.java`
  — `@Entity` with `(template_id, version_tag)` unique constraint,
  aggregated counters (`papers_narrated`, `replications_confirmed`,
  `replications_failed`), Status enum ({ACTIVE, ROLLED_BACK, RETIRED}),
  setters that auto-stamp `rolled_back_at` on non-ACTIVE transitions.
- `src/main/java/com/emergentterrain/repo/PromptTemplateVariantRepository.java`
  — JpaRepository with `findByTemplateIdAndVersionTag` (idempotency
  lookup), `findByTemplateId`, `findByTemplateIdAndStatus`.
- `src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java`
  — new `@Autowired(required=false) variantRepo` field + new
  `@PostConstruct public void syncVariantsToDatabase()` that
  idempotently upserts every in-memory variant. Null-safe for the
  unit-test path that constructs the registry directly.

**Acceptance criteria met:**
- All 33 existing registry tests pass.
- New `PromptTemplateVariantTest` (10 cases) pins:
  - Entity shape (constructor, accessors, default ACTIVE status).
  - `setStatus` semantics (ROLLED_BACK / RETIRED both stamp
    `rolledBackAt`; flipping back to ACTIVE preserves the original
    timestamp for forensics).
  - Counter increments.
  - `syncVariantsToDatabase` no-ops when `variantRepo` is null.
  - Empty repo → insert-all path.
  - Existing row with unchanged body → no save (counters preserved).
  - Existing row with changed body → save called once, counters
    preserved on the in-place mutation.
  - **The acceptance criterion verbatim**: "two startups of the
    same registry don't duplicate rows" — explicit test via a
    mock-backed simulated store; `save` count stays at the
    initial-insert number across two calls.
- `mvn test-compile` clean across all sources.

**Flag:** none. Hibernate `ddl-auto: update` creates the table on
next start; legacy installs without the rows still work because
runtime reads come from the in-memory map.

### PR2 — Epsilon-greedy selector

**Files:**
- Modify: `PromptTemplateRegistry.pickActive(String id)` — replaces
  "last-registered wins" with: read all ACTIVE variants for the id;
  if ≤ 1, return it; if ≥ 2, `rng.nextDouble() < ε` → pick a random
  exploration arm, else pick the highest-success-rate variant. Tie-
  break on `papersNarrated` descending so newer variants get a chance.
- Flag: `app.research.prompt-template-epsilon-greedy.enabled`
  (default false). When false, fall back to last-registered.
- Config: `app.research.prompt-template-epsilon-greedy.epsilon`
  (default 0.20 per Decision 6 revision).

**Acceptance:** unit test on `pickActive` with a 2-variant id and
seeded RNG; over 1000 picks, ε = 0.20 produces 18–22% pick rate for
the non-default arm (within ±2pp tolerance). Existing
single-variant ids still return their sole variant.

### PR3 — Stats writer on terminal outcome

**Files:**
- New: `PromptTemplateVariantStatsService` — single method
  `record(paperId, terminalStatus)`. Wired from
  `ReplicationService.processOne` (and `markPaperDisputed`).
- Maps `terminalStatus`:
  - CONFIRMED → `confirmedAggregated++`
  - FAILED / DISPUTED / DISPUTE_ABANDONED → `failedAggregated++`
  - Other (REJECTED, NULL_RESULT, NEEDS_REVISION at publish time) →
    `narratedAggregated++` only (counted as a sample but with no
    success/failure attribution).
- All increments resolve the template via
  `paper.narrationTemplateId + narrationTemplateVersion`.

**Acceptance:** integration test (the only one in this batch);
publish a paper with template `narration.critique v1`, transition
it to DISPUTED, assert the variant's
`failedAggregated` incremented by 1.

### PR4 — Rollback judge (scheduled)

**Files:**
- New: `PromptTemplateRollbackJudge` — `@Scheduled` every 5 minutes
  (matches dispute-orphan scan cadence). Iterates ACTIVE variants
  with `narratedAggregated ≥ 200`; computes failure rate; runs a
  two-proportion z-test with domain stratification against the
  default variant; on rejection at α = 0.01 with absolute
  difference ≥ 0.10, flips status to `ROLLED_BACK`.
- Flag: `app.research.prompt-template-rollback.enabled` (default
  false). Both PR4 + PR2 flags must be ON for the loop to close.

**Acceptance:** unit test on the judge against a synthetic stats
distribution; explicit assertion that a 0.30 failure rate vs 0.10
baseline at n=300 each triggers rollback; n=100 does not.

---

## Cross-cutting: how the four PRs interact

PR1 ships before any of the others — entity + idempotent upsert.
Default behavior unchanged (no flag).

PR2 + PR3 + PR4 are independently flag-gated and can ship in any
order. The full loop closes only when all three flags are ON.

Operator-side preparation (NOT a PR):
- Write at least one alternative variant for ONE template id.
  Candidate templates ranked by sensitivity to phrasing:
  `narration.critique` (rubric drift), `narration.statistical_framing`
  (sample-size leak), `formulation.main` (param-shape drift).
- Pick `narration.critique` first — smallest blast radius if
  rolled back (critique-skip path is well-handled by other audits).

---

## Recommended next step

**Do not start PR1 yet.** The mechanism is theatre without an
alternative variant. Three pre-conditions for the PR1 commit:

1. Operator authors at least one alternative variant for one
   template id (any of the three candidate templates above).
2. The eight locked decisions in this doc are accepted (or
   re-revised) — specifically the ε = 0.20 and 200-paper threshold.
3. Phase 21's binary is in production for ≥ 1 week so the baseline
   failure rate reflects the new gate set (otherwise PR3's stats
   inherit pre-21 numbers and the rollback judge fires false
   positives).

If all three are met, PR1 → PR2 → PR3 → PR4 lands in ~4 sessions.

**If the operator doesn't want to author a variant**: this whole
piece can be deferred indefinitely — write the entity + repo (PR1)
to lock the schema, then close the §18.3 ticket with "Piece 3
mechanism shipped; A/B activation deferred until an alternative
variant lands." This is the minimum closure of phase-18 §18.3.
