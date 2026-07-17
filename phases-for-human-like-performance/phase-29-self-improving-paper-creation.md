# Phase 29 — Self-Improving Paper Creation (patterns ported from DGM / HGM / STOP / Live-SWE)

**Status (2026-05-28):** **Tier 1.1 + 1.2 implemented and ROLLED OUT live
(candidates=3); three log-review fixes shipped (§Rollout log).** This phase
catalogs feature/mechanism patterns mined from four self-improving agent
frameworks and ports the highest-leverage ones into EmergentTerrain's
paper-creation pipeline. The patterns are organised into three implementation
tiers by leverage and dependency.

- **Tier 1.1 (best-of-N narration)** behind `app.research.best-of-n.*`. Winner
  selection confirmed deterministically via the static `selectBest` /
  `scoreCandidate` tests. **Now LIVE: `enabled=true, candidates=3`** (2026-05-27).
- **Tier 1.2 (eval cascade)** behind `app.research.eval-cascade.*`. **Key
  as-built finding:** the review panel runs *inside* candidate production
  (`maybeCritiqueAndRevise` → `runPanelAndStamp`), so naive best-of-N pays N
  full panels. Rather than refactor the shared every-paper panel/revision flow
  (high regression risk), 1.2 splits candidate production into a cheap
  `produceFirstPass` half and an expensive `finishCandidate` half (critique/
  revise + panel), screens the N first-passes, and finishes only the top-M —
  bounding panel cost to M while leaving the panel flow itself untouched. v1
  screens on first-pass self-confidence (free); a fast-tier Ollama screen grade
  is the documented follow-up. **Now LIVE: `enabled=true, screen-top-m=2`**.

Flag flips and config changes from the rollout are tracked in `phase-flags.md`
("Recently flipped — Phase 29"). Tiers 1.3 + 2.x + 3.x are scoped below; 2.B
(schema-pinned reviewers) shipped early as a log-review fix (§Rollout log).

**Source repos** (clones under
`/opt/apps/ide/vscode/ai/reference-from-github/self-improving-frameworks/`,
non-security mechanics; the security posture of each is a separate doc,
`SECURITY_REVIEW.md`, and is out of scope here):

| Repo | One-line mechanism | What we borrow |
|---|---|---|
| **DGM** (Darwin Gödel Machine) | flat archive of self-edited agents, parent selection = sigmoid-accuracy × child-count discount, `keep_all` stepping stones, staged eval cascade, diagnose→propose-ONE→self-edit loop | best-of-N, eval cascade, directed prompt self-improvement, child-count discount, stepping stones, did-it-help judge |
| **HGM** (Huxley Gödel Machine) | tree search; value a node by its **descendant clade's** pooled Beta posterior (Thompson sampling), not its own score; best-of-N `forward()` with an LLM tie-breaker | clade credit assignment, Thompson sampling + cool-down, LLM tie-breaker |
| **STOP** (Self-Taught Optimizer) | same-signature improver that improves itself; code-visible fitness (`utility.str` in the prompt); budget + val/test split + **rollback** on regression | rollback ratchet, code-visible rubric, recursion on the reviser |
| **Live-SWE-agent** | minimal scaffold; agent writes/saves/reuses its own Python tools mid-task; per-step "what reusable tool would help?" nudge | dynamic tool synthesis + persistent skill library |

---

## Framing — what ET already has, so we only build the deltas

EmergentTerrain **already implements the evolutionary skeleton these repos
pioneered** — but it evolves *bots / genomes / prompt-variants*, not
*papers*. Existing, do-not-rebuild:

- Evolutionary population of bot-researchers with tournament selection,
  reputation decay, genome `recordSuccess`/`recordRejection`
  (`research/UniversityService.java`, `BotLifecycleService`).
- Multi-stage narration pipeline: refine → statistical framing → first-pass
  → quality audit → professor tool-calling → student revision → multi-
  reviewer panel → publish (`service/ResearchNarrationService.java`).
- Multi-reviewer panel grading honesty/completeness/rigor/novelty + an
  agreement score, median-aggregated (`service/ReviewPanelService.java`,
  `research/PanelOutcome.java`).
- Pre-registration (hypothesis locked before compute), real sidecar compute
  (Julia/PySR/SymPy/OEIS), a long validator ladder in `publishPaper`.
- ε-greedy prompt-template A/B variants with a rollback judge
  (`research/PromptTemplateRegistry.java`, `PromptTemplateVariantStatsService`,
  `PromptTemplateRollbackJudge`).

So the adoptable value is **not** "add evolution." It is the specific
selection / credit-assignment / self-improvement mechanics ET is missing
**at the paper and generator layer**:

| ET evolves today | These repos also evolve | Phase 29 closes the gap |
|---|---|---|
| bots, genomes, prompt variants | the **artifact** (one agent / one solution) via best-of-N + selection | Tier 1 — paper-level best-of-N + cascade |
| immediate `peerScore` per paper | a node by its **descendant clade** | Tier 3.1 — lineage/clade credit |
| ε-greedy random prompt mutation | **diagnosed**, directed self-edits | Tier 2.3 — directed prompt improvement |
| revision assumed-better | accept only if it **beats** prior, else rollback | Tier 2.1 — rollback ratchet |
| graded against a hidden rubric | the optimizer **sees the scorer** | Tier 2.2 — code-visible rubric |
| fixed 4-tool professor suite | agent **writes its own tools** | Tier 3.4 — tool synthesis |

**The throughline for "beyond human":** a human writes one draft, cannot
read the reviewer's rubric, cannot statistically track which research line
pays off across hundreds of descendants, and does not tirelessly verify
each revision improved the score. Tiers 1.1, 3.1, 2.1, and 2.2 each remove
one of those human limits directly.

---

## Tier 1 — Paper-level selection & budget efficiency

**Why first:** highest leverage on existing machinery, no new services, and
1.1 + 1.2 pay for each other inside the hard run budget
(`app.ai.budget.global.max-cost-usd=$5`, `max-calls=300`). The review
report says runs are **call-cap-bound**, so spend efficiency is the lever.

### 29.1.1 — Best-of-N narration with panel-select  *(code-started)*

**Source:** STOP's `best_solution = max(new_solutions, key=utility)`
(`tasks/meta_optimization/seed_algorithm.py:21`) and HGM's emergent
best-of-N `forward()` (`hgm/best_agent/self_evo.md:29-60`). DGM uses the
same generate-many / keep-best shape.

**ET today:** selection is entirely on bots/genomes/prompt-variants. Each
accepted `PaperDraft` runs **exactly one** narration trajectory; the panel
grades that single artifact and it publishes.
`ResearchNarrationService.runNarration` produces one `result` and calls
`narrationPublisher.accept(draft, result)` once (`ResearchNarrationService.java:851-853`).

**Change:** when enabled, run the narration+panel cycle **N times** for the
same draft (the panel already runs inside `maybeCritiqueAndRevise` and is
carried on `finalResult.panelOutcome()`), score each candidate by its
panel grades, and publish the **argmax**. The losers are discarded.

- New `@Value` flags on the `ResearchNarrationService` constructor (matches
  the existing config-param style, `:402-414`):
  - `app.research.best-of-n.enabled` (default `false`)
  - `app.research.best-of-n.candidates` (default `1`)
- Extract the candidate-production body of `runNarration` (the block at
  `:747-850` that builds `result` and emits envelope metrics) into
  `produceNarrationCandidate(draft, orchestrationStartedAt)` returning the
  built `NarrationResult` **without** publishing.
- `runNarration` after the two early guards: produce 1 (default) or N
  candidates, select the best via `scoreCandidate`, publish the winner,
  then the existing `gateRetryQueue.markCompleted` path (`:862-864`).
- `scoreCandidate(NarrationResult)`: composite = mean of the four panel
  median grades (`PanelOutcome.aggregateGrades()` →
  `honesty+completeness+rigor+novelty / 4`), plus a `0.001 ×
  agreementScore` consensus tiebreak. Suppressed candidates (no panel)
  score `-∞`; a candidate whose panel did not run (panel disabled / empty)
  falls back to `confidenceScore()` so best-of-N still discriminates when
  the panel is off.
- Between candidates, stop early if `router.primaryFullyExhausted()` — keep
  the best-so-far rather than burning budget on a doomed cycle.

**Flag-off guarantee:** `enabled=false` **or** `candidates<=1` ⇒ exactly
one `produceNarrationCandidate` call then publish — same call order, same
envelope, same metrics emit, same queue handling as today. Bit-identical.

**Known imprecision (flag-on only):** `runNarration` runs inside one
`LlmCallContext.withPaperId` scope (`:647-649`), so the per-paper token
counters (`currentInputTokens()`, `:809`) accumulate across candidates; the
published winner's envelope therefore reports cumulative best-of-N spend,
not its own slice. The `RunBudgetTracker` accounts *all* spend at the pool
layer regardless, so budget enforcement is unaffected — only the
per-paper envelope attribution is coarse. Per-candidate token attribution
is a documented follow-up, not a Tier-1 blocker. Likewise
`emitNarrationEnvelopeMetrics` fires per candidate (each candidate is real
narration work); the activity distribution counts N narration outcomes for
one published paper when N>1.

**Files:**
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — two constructor `@Value` params + fields; extract
  `produceNarrationCandidate`; add `scoreCandidate`; best-of-N loop in
  `runNarration`.
- `src/main/resources/application.yml` — `app.research.best-of-n.{enabled,
  candidates}` (default off / 1).
- `src/test/java/com/emergentterrain/service/ResearchNarrationBestOfNTest.java`
  — `scoreCandidate` ranking (higher composite wins; agreement tiebreak;
  suppressed scores lowest; panel-off falls back to confidence).

**Acceptance:** flag-off path bit-identical (existing narration tests stay
green); `scoreCandidate` unit tests pass; a manual flag-on run with
`candidates=3` shows three narration cycles logged per paper and the
published paper carrying the highest-graded panel of the three.

**Effort:** ~1.5 days.

### 29.1.2 — Staged evaluation cascade (cheap → expensive)  *(IMPLEMENTED, default OFF)*

**Source:** DGM evaluates on `small.json` first and only pays for
`medium/big.json` if a node clears `test_more_threshold`
(`dgm/self_improve_step.py:152-178`).

**ET today:** every paper gets the full multi-reviewer **frontier** panel
regardless of promise. With best-of-N this multiplies — N candidates × a
full panel each is the budget worst case.

**As-built finding — the panel is embedded in candidate production.** The
panel runs only through `runPanelAndStamp` (`ResearchNarrationService.java`),
which is called *inside* `maybeCritiqueAndRevise`. So a candidate is already
fully paneled by the time it is produced; there is no "pre-panel" seam to
screen at without either (a) suppressing the in-flow panel (which is tangled
with `panelRevisionEnabled`, panel-degradation-demote, grade-floor, and the
validators that read `panelOutcome`) or (b) splitting production so the panel
is simply *run fewer times*. (a) is a high-risk refactor of the shared
every-paper flow; **(b) is what shipped.**

**Change (as built):** split candidate production into a cheap
`produceFirstPass` half (primary stream + parse) and an expensive
`finishCandidate` half (critique/revise + panel). The cascade (`produceWithCascade`):
1. produce N cheap first-passes (early-stop on primary exhaustion);
2. **screen** them with `topM(firstPasses, scoreCandidate, M)` — with no panel
   yet, `scoreCandidate` ranks on the narrator's first-pass self-confidence, a
   free signal (no extra LLM call);
3. run `finishCandidate` (→ the panel) on only the **top-M** (default 2);
4. `selectBest` among those M panel-graded candidates.

Panel runs M× instead of N× per paper; the panel flow itself is **unchanged**.
`produceNarrationCandidate` (non-cascade) = `finishCandidate(produceFirstPass())`,
so the default path is bit-identical.

**v1 limitation / follow-up:** the screen signal is first-pass self-confidence,
not a dedicated fast-tier grade. A fast-tier (Ollama) screen call
(`router.callFast`) is the richer signal — deferred so v1 adds zero LLM calls
and stays deterministically testable. The `single-paper gate` mode (publish on
a reduced review below threshold) is also deferred — it changes the every-paper
review path and deserves its own slice.

**Files (as built):**
`ResearchNarrationService.java` — `FirstPass` record, `produceFirstPass`,
`finishCandidate`, `produceWithCascade`, static `topM`; constructor `@Value`
flags. `application.yml` — `app.research.eval-cascade.{enabled,screen-top-m}`
(default off / 2). `ResearchNarrationBestOfNTest` — `topM` ranking/ties/edges
+ a cascade-screen-by-confidence test.

**Acceptance:** flag-off bit-identical (orchestration tests green); `topM`
unit tests pass; with cascade on, the cascade log line reports the panel
running M× not N×. The corpus check (frontier panel calls/paper drop while the
published panel-grade distribution holds over 20 papers) remains an operator
task once a live run with `candidates>1, eval-cascade.enabled=true` ships.
**Effort (actual):** ~0.5 day. **Depends on:** 1.1.

### 29.1.3 — LLM tie-breaker on near-ties

**Source:** HGM's `score_tie_breaker` (`hgm/utils/eval_utils.py:72-127`) —
an LLM ranks candidate diffs when numeric scores are within a margin.

**ET today:** none (no paper-level selection exists yet).

**Change:** when the top best-of-N candidates are within
`tie-margin` composite grade, a single fast/primary call ranks them
(reusing the panel's rubric) instead of an arbitrary argmax. **Files:**
`ResearchNarrationService.scoreCandidate` caller + a `breakTie(...)` helper.
**Acceptance:** tie-breaker only fires within the margin; deterministic
argmax outside it. **Effort:** ~0.5 day. **Depends on:** 1.1.

---

## Rollout log & operational fixes (2026-05-27 / 28)

The candidates=3 rollout and a review of `logs/et-app.log` surfaced a timeout
interaction and four findings; three of the four were fixed. Flag flips are
also recorded in `phase-flags.md`.

### Rollout config (flags + timeouts)

- `best-of-n.enabled=true, candidates=3`; `eval-cascade.enabled=true,
  screen-top-m=2` — so each paper produces 3 first-passes, screens them, and
  panels only the top 2.
- **Timeout interaction (found + fixed):** the first candidates=3 run abandoned
  **100%** of papers — the 420 s per-paper `narrationTimeoutMs` can't wrap 3
  first-passes + 2 `finishCandidate` halves, and the professor tool loop alone
  ran 176–329 s (it dominates ~58% of per-paper time). Fix: `narrationTimeoutMs
  420000→1080000` (18 min), `stuckDraftTtlMs 600000→1320000` (22 min, stays >
  timeout), `budget.global.max-calls 300→1000`. `max-cost-usd` left at $5
  (operator: calls-only) — so cost, not calls, is now the binding cap, and
  best-of-N ~2× $/paper means ~half the papers per run.
- **Open follow-up (§29.1.4):** a per-candidate / per-professor-stage timeout
  would let the 18-min per-paper blanket shrink back and cap the 5.5-min tail.
- **2026-05-28 timeout re-tune + root cause (a3289005 review rec 2/3).** The
  18-min cap was still firing: professor tool turns on the `legacy-ai`
  path hit the proxy's per-turn cap and surfaced a 500 "turn timeout", forcing a
  fallover to OpenRouter (one observed paper ran ~35 min across two best-of-N
  candidates). Two distinct issues were root-caused from the logs:
  - **The narration timeout was not enforced.** `cancel(true)` interrupts the
    worker, but `GenericOpenAICompatPool.syncCall` swallows the `InterruptedException`
    (generic `catch (Exception)`, flag not re-set) and `LlmRouter` treats it as a
    provider failure and fails over — so the worker runs to completion regardless.
    The proxy hang itself is the Claude Agent SDK's *SDK-executes-tools* loop not
    converging within the bridge's turn cap. (Fix pending — see REVIEW_REPORT rec 2.)
  - **Stopgap (this change):** give heavy turns room + instrument them, rather
    than relax a cap that isn't enforced. New values, which MUST move together:

    | Setting | Old | New | Constraint |
    |---|---|---|---|
    | proxy `LEGACY_AI_GATEWAY_TURN_TIMEOUT_MS` | 300 s | 1000 s | < ET legacy-ai HTTP timeout |
    | proxy `LEGACY_AI_GATEWAY_SESSION_TTL_MS` | 600 s | 2400 s | > turn cap |
    | ET `provider-timeout-seconds.legacy-ai` | 420 s (global) | 1100 s | > proxy turn cap (so ET sees the clean 500) |
    | ET `narrationTimeoutMs` | 18 min | 40 min | < stuckDraftTtlMs |
    | ET `stuckDraftTtlMs` | 22 min | 48 min | > narrationTimeoutMs |
    | proxy `LEGACY_AI_GATEWAY_SDK_DEBUG` (→`ANTHROPIC_LOG`) | off | `debug` | verbose; disable after diagnosis |

    Because cancel(true) is still a no-op, these larger caps also lengthen how long
    a genuinely-hung worker ties up a narration slot — fixing the swallowed
    interrupt (rec 2) is the real fix; this only buys room + visibility.

- **2026-07-17 timeout re-tune (Phase 35 incident).** A Phase 34 FTS5 search
  delayed two concurrent best-of-N cascades. One paper completed just before
  the 40-minute deadline while the other timed out during its final candidate.
  `narrationTimeoutMs` is therefore 90 minutes and `stuckDraftTtlMs` is 100
  minutes, preserving the ordering in which the narration timeout fires first.
  The dormant legacy AI gateway session TTL is 95 minutes for the same reason. This
  is capacity for the current path, not a substitute for the Phase 35 bounded
  retrieval implementation.

### §1.A — grok `/`-in-enum tool-schema rejection *(SHIPPED, always-on)*

xAI/grok rejected the `symbolic_regress` tool **schema** ("'/' in 'enum' string
value is currently not supported"), silently dropping grok (cheapest-frontier
OpenRouter slot) from the professor-tools rotation. Fix: drop the `enum` on
`operators` (`MathToolSuite.java`) — the allowlist already lives in the
description and `JuliaSafetyFilter` enforces it sidecar-side. Provider-agnostic,
no flag. Guard test asserts the schema ships no `/`-bearing enum.

### §2.B — schema-pinned reviewers *(SHIPPED, flag ON 2026-05-28)*

deepseek-v4-pro reviewers returned prose, not JSON; `PanelReview.parse` requires
a whole-response JSON object and the retry re-sent the same prompt, so the
reviewer was dropped → panel degraded → D4 demotions. Best-of-N (panel runs 2×/
paper) amplified it. This is wishlist **P3**, pulled forward. Fix
(`ReviewPanelService`): tool-capable reviewers submit via a `submit_review`
tool (provider-enforced shape — parse can't fail; args round-trip through
`PanelReview.parse`); non-tool models / tool-path misses / flag-off use the text
path, whose retry is now a **repair prompt** (parse error + exact schema), not a
verbatim re-send. The `submit_review` schema uses **no enum** (the §1.A grok
lesson). Flag `app.research.review-panel.tool-call-schema.enabled` — **flipped
true 2026-05-28, operator-directed ahead of the smoke check**; watch the next
refresh for `rev:parse_failed`→~0 on tool-capable reviewers and panel
call-count. Costs 1–2 LLM rounds/reviewer vs 1.

### §4.a — run_symbolic syntax-correction hint *(SHIPPED, always-on)*

A parse/syntax rejection (unbalanced parens) returned the misleading "only
calculus/algebra/trig heads are allowed" message, so the professor bounced
through budget_fallback retries. Fix (`MathToolSuite.runSymbolic`): detect
parse-error reasons (`SymbolicStaticChecker` "parse failed: …") and return a
targeted "SYNTAX error … balance the parentheses … re-send" correction. No flag.

### Finding 3 (dispute orphans) — NO fix, working as designed

`scanOrphanedDisputes` already abandons at `dispute-max-attempts` (6) →
`DISPUTE_ABANDONED`, and the orphan re-fire is cheap (`recordOpenQuestion` DB
upserts, no LLM). Only nit: up to 36 h carry before abandonment. Optional config
(`dispute-max-attempts`/`disputeOrphanAgeHours` ↓) or a fast-abandon for
deterministic divergences — deferred, not urgent.

### legacy-ai tool-loop io error — NO fix, deferred

`GenericOpenAICompatPool` wraps a proxy `IOException` and fails over fine. A
single same-handle retry before failover is the optional fix; low priority.

---

## Tier 2 — Self-improving generator (improve the writer, not just the output)

### 29.2.1 — Rollback ratchet on revision + did-it-help judge

**Source:** STOP accepts a change only if it beats the prior on held-out
utility, else **reverts** (`run_improver.py:141-147`); DGM's
`diagnose_improvement` LLM-judges before/after on a −2..+2 scale
(`dgm/prompts/diagnose_improvement_prompt.py`).

**ET today:** the student-revision stage (and `panelRevisionEnabled`)
rewrites the paper but does **not** verify the revision out-grades the
pre-revision draft — it is assumed-better.

**Change:** panel-score both pre- and post-revision drafts; keep the
revision only if it improves the median composite, else roll back to the
pre-revision draft. Same gate for `panelRevisionEnabled`. **Files:**
`ResearchNarrationService.maybeCritiqueAndRevise` /
`maybeCritiqueAndReviseWithTools`. **Acceptance:** a revision that lowers
the panel grade is discarded; no paper publishes a worse-than-pre-revision
body. **Effort:** ~2 days. **Composes with:** 1.1 (both reuse `scoreCandidate`).

### 29.2.2 — Code-visible fitness (show the optimizer the scorer)

**Source:** STOP injects the literal scoring source into the improver
prompt (`{utility.str}`, `seed_algorithm.py:11-14`).

**ET today:** the narrator/professor/student are graded against the
`ReviewPanelService` rubric and the validator ladder
(Premise/Citation/NumericalConsistency/…) they **cannot read**.

**Change:** surface the panel's four-axis rubric and the active validator
checks verbatim into the narration & revision prompt templates. **Keep a
held-out subset of gates** un-surfaced (mirroring STOP's val/test split,
`secret_utility.py:25`) so the generator can't Goodhart the whole rubric.
**Files:** `PromptTemplateRegistry` (narration/revision templates),
`application.yml` (`app.research.code-visible-rubric.enabled`, default off).
**Acceptance:** rubric text appears in the prompt; held-out gate FP rate
does not rise (no overfitting to the disclosed gates) over a refresh.
**Effort:** ~1.5 days.

### 29.2.3 — Directed prompt self-improvement (diagnose → propose ONE → A/B)

**Source:** DGM feeds the generator its own source + a transcript of how it
just failed and forces a structured `log_summarization →
potential_improvements → pick ONE general improvement` proposal
(`dgm/prompts/self_improvement_prompt.py:64-120`); STOP makes it recursive
via the same-signature improver (`run_improver.py:152-179`).

**ET today:** prompt templates self-improve only via ε-greedy A/B variant
stats — *random mutation + selection*, not *diagnosed, directed* change.

**Change:** a periodic offline job reads recent rejected / NEEDS_REVISION
narration+panel transcripts plus the current template, asks a frontier
model for **ONE general** improvement to that template, and registers the
result as a new `PromptTemplateRegistry` variant gated through the existing
A/B + `PromptTemplateRollbackJudge`. The proposer must generalise (not
patch one paper) and pick exactly one change — DGM's two key prompt levers.
**Files:** new `service/PromptSelfImprovementService.java`,
`PromptTemplateRegistry` (register-variant API), `application.yml` flag +
cadence. **Acceptance:** proposed variants are well-formed and survive (or
are rolled back by) the existing A/B machinery; net acceptance-rate lift vs.
random-mutation baseline over several refreshes. **Effort:** ~1 week.
**Depends on:** the A/B + rollback infra (exists).

> **ARC addendum (2026-06-04 — still PENDING).** AutoResearchClaw's
> `evolution.py` runs the *continuous* complement of this item: every run
> auto-extracts categorized lessons (SYSTEM/LLM/DATA/CODE/RESEARCH) from
> stage failures into a JSONL store, and future runs inject the top-k
> lessons per stage as a **time-decayed (30-day) prompt overlay** — no
> template mutation, no A/B needed, trivially revertible (delete the
> JSONL). Their measured effect: −24.8% stage retries, −40% refine cycles.
> Complementary, not a substitute: the overlay handles the long tail
> cheaply between 29.2.3's periodic structural changes. ET lesson sources
> are ready-made: `ReviewPanel` verdicts, `NARRATION_AUDIT` categories, and
> the W4 shadow would-retire log. Proposed flag
> `app.research.lessonOverlay.enabled`; full sketch in
> [`phase-30-conjecture/phase-30-arc-patterns.md`](phase-30-conjecture/phase-30-arc-patterns.md) §B4.

### 29.2.4 — Failure-class routers → specialized fix prompts

**Source:** DGM hard-codes named failure modes (empty-patch, stochasticity,
context-length), each with a dedicated diagnose prompt
(`self_improvement_prompt.py:305-336`).

**ET today:** `NARRATION_AUDIT` already *detects* categories
(physical_mechanism, degenerate_result, overclaiming, threshold_drift,
duplicate_computation, …) but routes them all into one generic critique.

**Change:** route each detected audit category to a purpose-built revision
sub-prompt — especially the recurring `run_param_sweep` knob↔hypothesis
mismatch the review report flags on nearly every paper. **Files:**
`PromptTemplateRegistry` (per-category revision templates),
`ResearchNarrationService` (route by `auditGapDimensions`). **Acceptance:**
category-specific revisions measurably reduce the matching audit flag on
re-narration. **Effort:** ~3 days.

---

## Tier 3 — Open-ended credit & exploration (the genuine "beyond human" levers)

### 29.3.1 — Clade / lineage credit assignment (HGM headline)

**Source:** HGM values a node by the **pooled performance of its descendant
clade** (`hgm/tree.py:45-54`, `get_decendant_evals`), explicitly to counter
"metastagnation" — abandoning a currently-mediocre lineage whose offspring
are promising.

**ET today:** panel feedback is largely **terminal**; `recordPublished(peerScore)`
credits only the immediate paper. A topic/program/genome is valued by its
latest score, not by the papers its line of inquiry later seeds.

**Change:** credit a topic / `ResearchProgram` / genome by the *clade* of
papers descended from it (via the existing citation lineage +
`isFollowUp`/`priorMethodology` links), not just the latest score; feed the
pooled estimate into `tournamentSelect` topic-weighting and
`ExplorationTuningService`. **Files:** `PaperGraphService` (clade pooling),
`UniversityService`/`ResearchProgramService` (credit propagation),
`BotGenome` (topic-weight update from clade value). **Acceptance:** a
low-immediate-score topic that seeds high-grade follow-ups retains
selection weight instead of decaying out. **Effort:** ~1.5 weeks.

### 29.3.2 — Thompson-sampling selection + budget cool-down

**Source:** HGM selects via Beta(1+successes, 1+failures) posteriors with
one sampled draw per candidate (`hgm/hgm.py:360-377`) and sharpens the
posteriors as the budget depletes (cool-down, `:363-375`).

**ET today:** topic selection is genome-weighted + `TopicCloneBackoff`; no
principled bandit, no explore→exploit schedule tied to the run budget.

**Change:** Thompson sampling over each topic/author's accept-rate
posterior for selection; tie posterior sharpening to `RunBudgetTracker`
remaining budget — broad early, greedy late. **Files:** new
`service/ThompsonSelectionService.java`, wired into `ResearchCycleService`
author/topic selection; reads `RunBudgetTracker`. **Acceptance:** under a
fixed call cap, the late-run published-grade mean rises vs. the
genome-weighted baseline. **Effort:** ~1 week. **Composes with:** 3.1
(clade pools feed the posteriors).

### 29.3.3 — Stepping-stone archive + child-count diversity discount

**Source:** DGM's `keep_all` archive retains worse-than-parent nodes
(`DGM_outer.py:174-190`) and discounts over-explored parents via
`1/(1+children_count)` (`:96-100`).

**ET today:** `NULL_RESULT`/`DEGENERATE` outcomes are largely terminal;
`TopicCloneBackoff` is the only diversity pressure.

**Change:** keep an archive of high-novelty `NULL_RESULT`s as explicit
stepping-stones that can seed future hypotheses, and add a child-count
discount to topic selection so heavily-mined topics yield to under-explored
ones. **Files:** `ResearchProgramService` (stepping-stone store),
selection weighting in `ResearchCycleService`. **Acceptance:** a surprising
null result is later cited/extended by a follow-up rather than dropped.
**Effort:** ~4 days. **Composes with:** 3.1/3.2.

### 29.3.4 — Dynamic tool synthesis + persistent skill library

**Source:** Live-SWE's thesis — the agent writes/saves/reuses its own
Python tools mid-task (`config/livesweagent.yaml:105-141`) with a per-step
"what reusable tool would help?" nudge (`:148-149`); the filesystem becomes
accumulating skill memory.

**ET today:** the tool-calling professor has a **fixed** 4-tool suite and
cannot synthesise new analysis tools; nothing persists across papers.

**Change:** let the professor compose & persist reusable analysis helpers
under the existing `SandboxEngine` guardrails (no-I/O, no-imports, 5000-iter
cap), keyed by domain in a cross-paper tool registry, so later papers reuse
earlier papers' tools. **Files:** `MathToolSuite` (register-synthesised-tool
path), new `service/SynthesizedToolRegistry.java`, `SandboxEngine` reuse.
**Acceptance:** a tool built on paper N is invoked on paper N+k of the same
domain; sandbox guardrails hold. **Effort:** ~2 weeks. **Highest risk** —
build last; carries the most surface area (see `phase-19-sandbox-friction.md`).

> **ARC addendum (2026-06-04 — still PENDING).** AutoResearchClaw's
> `metaclaw_bridge` ships the same lesson→skill loop but at the **prompt
> level only**: recurring lessons are promoted to reusable SKILL.md files
> injected into stage prompts — no code synthesis, no execution surface
> (+18.3% robustness in their controlled A/B). That is a lower-risk first
> slice of this item: ship the *prompt-skill registry* (persist + inject
> per-domain prompt skills) first, and add the sandboxed code-tool
> synthesis as the second slice once 29.2.3's lesson plumbing exists. See
> [`phase-30-conjecture/phase-30-arc-patterns.md`](phase-30-conjecture/phase-30-arc-patterns.md) §B4.

---

## Dependency / sequencing graph

```
Tier 1  ── 1.1 best-of-N ──┬── 1.2 eval cascade (pays for 1.1's N× panel cost)
                           ├── 1.3 LLM tie-breaker
                           └── reused by 2.1 (scoreCandidate)

Tier 2  ── 2.1 rollback ratchet      (independent; reuses scoreCandidate)
        ── 2.2 code-visible rubric   (independent; prompt-only)
        ── 2.3 directed prompt-improve (needs A/B infra — exists)
        ── 2.4 failure-class routers (independent; prompt routing)

Tier 3  ── 3.1 clade credit ──┬── feeds 3.2 Thompson posteriors
                              └── composes with 3.3 stepping stones
        ── 3.2 Thompson + cool-down  (needs RunBudgetTracker — exists)
        ── 3.3 stepping stones + child-count discount
        ── 3.4 tool synthesis        (independent, highest risk — last)
```

**Recommended order:** 1.1 + 1.2 together (fastest quality jump within
budget) → 2.1 + 2.2 (small, high-reliability) → 3.1 + 3.2 (the open-ended
lever) → 2.3 + 2.4 + 3.3 → 3.4 last.

---

## Per-tier acceptance gates

| Tier | Headline acceptance |
|---|---|
| **1** | Flag-off bit-identical; flag-on best-of-N publishes the highest-panel-graded of N candidates; eval cascade holds frontier-panel calls per paper flat or lower while panel-grade distribution does not regress over 20 papers. |
| **2** | No paper publishes a worse-than-pre-revision body (ratchet); disclosed-rubric prompts don't raise held-out gate FP rate; directed prompt variants beat random-mutation baseline. |
| **3** | A low-immediate-score topic that seeds high-grade follow-ups keeps selection weight (clade); late-run published-grade mean rises under a fixed call cap (Thompson + cool-down). |

**Phase-level success:** the published-paper **panel-grade distribution**
shifts up (not just its mean) and topic selection demonstrably keeps
investing in slow-burn-but-fertile research lines — i.e. the corpus reads
less like isolated paper generation and more like a research program that
compounds.

---

## Open architecture questions

1. **best-of-N N value under the $5/300-call cap.** N=3 triples narration+
   panel cost per paper. 1.2's cascade is the mitigation, but the right
   default N is an operator/budget call — start N=1 (off), measure cost per
   candidate, pick N from the call-cap headroom.
2. **scoreCandidate composite weights.** Equal weight on the four panel axes
   is the v1 default; honesty/rigor may deserve higher weight than novelty
   for "human quality." Tunable, deferred until the corpus shows the axis
   that best predicts human-review verdicts.
3. **Token attribution under best-of-N** (the 1.1 known-imprecision) — keep
   cumulative-envelope (simple) or thread a per-candidate token snapshot
   through `produceNarrationCandidate`? Cumulative is fine until per-paper
   cost reporting needs the precision.
4. **Where clade pooling caps a node's own weight** — HGM's `num_pseudo`
   (`tree.py:46`) stops a strong root drowning its subtree's signal; ET
   needs an analogous cap so a single high-score paper doesn't dominate its
   program's clade estimate.
5. **Goodhart surface for 2.2** — which gates stay held-out? At minimum the
   premise/coupling structural gates (un-gameable by prose) should be
   disclosed; the stylistic/length gates should stay hidden.

---

## Cost / benefit summary

**Total effort (all tiers):** ~9–10 weeks end-to-end; Tier 1 alone ~4 days.

**Operational tax:** Tier 1 adds N× narration+panel LLM spend per paper
(mitigated by 1.2's cascade); no new service, port, or runtime. Tiers 2–3
add two small services (`PromptSelfImprovementService`,
`ThompsonSelectionService`) and one registry; 3.4 is the only piece that
widens the sandbox surface and is gated behind a security review per the
`phase-19-sandbox-friction.md` precedent.

**Expected impact.** Tier 1 raises the **top end** of the panel-grade
distribution (best-of-N selects the best draft a human-style single pass
would have missed). Tier 3.1/3.2 convert ET from "isolated paper
generation" to a research program that keeps funding fertile slow-burn
lines — the open-endedness that produces genuinely novel results rather
than local optima.

**Recommendation.** Ship 29.1.1 default-OFF now (this commit), flip after a
manual `candidates=3` smoke run confirms the winner-selection works on a
real paper, then land 29.1.2 before raising N in production — per the
`feedback_flag_gated_dormant` pattern.
