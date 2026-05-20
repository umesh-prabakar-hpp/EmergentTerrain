# Phase 21 — Run-Quality Triage from research/a3289005 (scoping doc)

**Status:** Scoping doc, not a single-PR phase. Created 2026-05-19 by
walking the operational log of the live JVM (PID 6446, started
06:53:37 EDT) against the REVIEW_REPORT.md refreshes for
`research/a3289005/`. Seven items, each with a named root-cause
WARN/finding in `logs/et-app.log` (4,095 lines through 07:38 EDT) or a
specific failing paper (P-a328-684, 685, 686, 690, 693). Nothing here
ships in a single PR; treat each numbered item as a candidate that
would graduate to its own phase doc (or fold into an existing tracker)
before any code lands.

**Source evidence:**

| Signal | Count / Detail | Source |
|--------|----------------|--------|
| Groq pre-debit budget WARN | 110 in 45 min run | `logs/et-app.log` |
| Actual Groq TPM 429s | 2 | same |
| Dispute-orphan WARN | 68 (17 unique IDs, repeating ×4) | same |
| `numerical-consistency` demotions | 3 (5/5 papers triggered some citation gate) | same + REVIEW_REPORT |
| Cherry-pick audit fires | 1 (P-a328-693) | same |
| Schema migration failures (now fixed) | 2 (`narration_input_tokens`, `narration_output_tokens` NOT NULL on existing rows) | same |
| Physically impossible hypothesis published | P-a328-693 (peer 0.989; arcsin>1) | REVIEW_REPORT |
| Symbolic-solver echo accepted as result | P-a328-693 (`Solve[...]` unevaluated, 1.49 fabricated) | REVIEW_REPORT + paper file |
| Sandbox grammar rejects (still) | 3 (`.push`, `Module[…]`, `^` in evaluate_symbolic / run_gpu_eval) | papers 684/685/690 |
| Replication divergence | 2/5 papers (684: 0.961 → 0.535; 690: 0.728 → 0.640) | paper files |

**What this phase deliberately does NOT track:**

- **Phase 19 sandbox-friction items already covered.** P-a328-684's
  `.push` and `Module[]` rejects are in the JS sandbox surface that
  Phase 19 Layers 1a–1f and Layer 2 already address; the failures in
  this run came from an older paper-generation batch that predates the
  Tier 5 flag flip taking effect. Re-running the same papers under the
  current binary may resolve them without code. The new items below
  cover only the **symbolic-tool** path that Phase 19 did not touch.
- **The Tier 5 flag rollback paths in `phase-flags.md`.** D2, D4, F7.3,
  gate-retry all flipped 2026-05-18. F7.3 demonstrably *worked* on
  P-a328-693 (audit fired, omitted_calls=[1] detected) — no rollback
  signal. Two of the items below propose *additions* to F7.3 detection
  rather than rollbacks.
- **Peer-score recalibration as a standalone project.** P-a328-693 got
  the highest peer score (0.989) on the worst physics in the batch.
  The fix likely needs prompt + rubric rework on `ReviewPanelService`,
  which is a wider scope than this triage. Noted in §21.7 as a deferred
  item with a named trigger (a second high-peer/low-physics paper).

---

## Items targeted at the run-time path

### 21.1 — Loosen Groq pre-debit budget gate (or add hysteresis)

**Status (2026-05-19):** code + telemetry portions **landed**; YAML
one-line drop **intentionally skipped**. See "Implementation notes" at
end of this section.


**The signal:** `app.tokens.budget` for groq is configured at
`minuteLimit: 8000` (`src/main/resources/application.yml:864`),
`pauseThresholdPct: 95` → `pauseAt = 7600`. In a 45-minute window the
gate fired **110** `BudgetExceededException`s vs. **2** actual Groq
`429 TPM` responses. The gate is firing ~55× more often than the
upstream limit warrants and is forcing every professor stage through a
2–3-deep fallback chain (`llama-3.3-70b` → `gpt-oss-120b` → qwen →
NVIDIA 503), which degrades narration quality.

**Why it's too aggressive:** `TpmBudgetGate` (line 70-85) compares
`accountant.minuteTotal(provider) + estimate > pauseAt`. The estimate
is a conservative chars-per-token figure (`GenericOpenAICompatPool.java:620`).
The combination of (a) a tight pauseAt margin of only 400 tokens below
the limit, (b) a conservative-overestimate token figure, and (c)
optimistic minute-window accounting that doesn't yet pre-decay the
reservation, means the gate is permanently throttling on phantom load.

**Files (proposed):**
- `src/main/java/com/emergentterrain/llm/TpmBudgetGate.java` — split
  `pauseAt` into a soft threshold (e.g. 85%) that gates *new tool
  rounds* and a hard threshold (95%, today's behaviour) that gates
  *all calls*. Today's "all-or-nothing at 95%" pinballs between fully
  open and fully closed. Telemetry to add: per-minute count of `REJECT`
  decisions vs. real 429s, exposed at `/actuator/metrics`.
- `src/main/resources/application.yml` — drop `pauseThresholdPct` for
  groq from 95 → 90 as an interim mitigation while the soft/hard split
  lands. Keep the existing rollback path (set back to 95).

**Acceptance:** budget-gate WARN/429 ratio drops below 5:1 (today 55:1)
over a 45-minute corpus run.

**Implementation notes (2026-05-19):**

- *Soft/hard split, code path.* Landed in `TpmBudgetGate`
  (`GateLevel` enum + 4-arg `tryAcquire` / `tryAcquireOrThrow` overloads;
  single-arg overloads kept as back-compat for HARD). Wired into both
  tool-loop call sites — `GroqPool.runToolLoop:530` and
  `GenericOpenAICompatPool.runToolLoop:401` now pass HARD on round 0 and
  SOFT on round ≥ 1. Single-shot and stream paths still use HARD. New
  global config `app.tokens.budget.softPauseThresholdPct` (default 85);
  per-provider override under `providers.<key>.softPauseThresholdPct`.
- *Telemetry.* New `BudgetGateMetrics` interface (Micrometer-backed
  `Default`, plus a `NOOP` for tests). Counters
  `tpm.budget.reject{provider,level}` and
  `tpm.budget.upstream_429{provider}` are exposed at
  `/actuator/metrics`. REJECT is bumped from inside the gate; the 429
  counter is bumped from the existing GroqPool 429 handlers
  (`recordGroqRateLimitBlock` + `syncCallWithBackoff` inline path) and
  from both 429 branches in `GenericOpenAICompatPool` (`syncCall` + the
  tool-loop catch). The phantom-throttle ratio is the quotient of the
  two scrape values.
- *YAML one-line drop (95 → 90) skipped on purpose.* The doc proposed it
  as an interim mitigation, but lowering `pauseThresholdPct` lowers
  `pauseAt` from 7600 → 7200 — every estimate now overflows sooner, so
  the gate fires MORE often, not less. Re-read the gate logic at
  `TpmBudgetGate.tryAcquire` line 109 (`current + estimate > pauseAt`)
  — the direction is unambiguous. The real fixes for the 122:1 phantom
  ratio are reservation TTL and a smaller estimate factor (chars/4 vs
  chars/3 in `estimateTokens`), neither of which §21.1 commits to.
  Leaving the hard threshold at 95% until the soak run shows whether
  the soft split alone narrows the ratio.

**What this section does NOT solve:** the soft/hard split *redistributes*
budget pressure (so single-shot work isn't starved by long tool loops on
the last 10% of budget) but doesn't directly reduce the total REJECT
count. If the soak run shows the ratio still > 5:1, the next lever is
either reservation TTL or estimate-factor tuning — both narrower changes
to `TpmBudgetGate` / `GroqPool.estimateTokens` than the soft split was.


### 21.2 — Dispute-orphan retire / suppress

**The signal:** 68 WARN lines across 17 unique paper IDs
(`UniversityService.java:1811`). Same 17 IDs cycle every reconciliation
pass — each pass re-emits one WARN per orphan, never resolves. Net
effect: 28% of all WARN volume in this run is one stuck scheduler.

**Why they stay orphaned:** the comment at line 1820 says "We don't
know the replicator bot's id from the cached paper (it wasn't
persisted)" — so `enqueueReconciliation(p, null)` refreshes only the
original author. The replicator never gets re-pinged, so the DISPUTE
status never resolves, and the paper re-appears as an orphan on the
next sweep.

**Files (proposed):**
- `src/main/java/com/emergentterrain/research/UniversityService.java`
  (~line 1810) — add a per-paper attempt counter; after N attempts
  (e.g. 6, ≈ 6 hours at the current cadence), demote to
  `DISPUTE_ABANDONED` and stop re-firing. Persist the replicator bot
  id on dispute creation so the re-fire can hit both sides.
- Schema migration (Hibernate, like the recent narration_* columns):
  `research_papers.dispute_replicator_bot_id` and
  `research_papers.dispute_attempts` (both nullable; backfill 0).

**Acceptance:** dispute-orphan WARN drops to 0 after one full sweep
cycle on a corpus that has 17 known orphans.


### 21.3 — Detect symbolic-solver echo in `run_symbolic`

**The signal:** P-a328-693's only tool call was
`Solve[n2/n1 == Sin[theta_c] && n1 == 1.50 && n2 == 1.577, theta_c]`.
Symja returned `output=Solve(n2/n1==Sin(theta_c)&&n1==1.5&&n2==1.577,theta_c)`
— the input expression unevaluated, with **no `numeric=`** field. The
narration stage then fabricated "61.04°" and "1.49" to fill the gap.

**Why it slipped through:** `MathToolSuite.runSymbolic`
(`MathToolSuite.java:458`) returns whatever Symja produces. There is
no check that the `output=` string differs structurally from the input
expression, and no requirement that a `numeric=` field accompany
`Solve[...]` whose RHS contains only numeric coefficients.

**Files (proposed):**
- `src/main/java/com/emergentterrain/service/MathToolSuite.java`
  `runSymbolic` (~line 487, where the result string is assembled) —
  after Symja returns, if `output` equals the normalised input
  expression *and* the input was a `Solve[...]` with all-numeric
  coefficients, retry once with an `N[...]` wrapper. If the retry
  also echoes, return `"Symbolic solver did not evaluate — try a
  numeric form like NSolve[...] or N[ArcSin[ratio]]"` so the LLM gets
  an actionable error instead of an opaque echo.
- Unit test: `MathToolSuiteTest` — feed the exact P-a328-693
  expression and assert (a) first attempt returns `Solve(...)` echo,
  (b) the retry path emits `numeric=` or the error string.

**Acceptance:** P-a328-693 replay produces either a numeric critical
angle or a failed-tool-call marker, never an LLM-fabricated number.


### 21.4 — Physics-premise preflight gate at hypothesis time

**The signal:** P-a328-693 declared "glass–air" with n₁=1.50, n₂=1.577
and asked for TIR critical angle. Three independent contradictions:
(a) TIR requires n₂<n₁, (b) "glass–air" by name pins n₂=1.00, (c)
n₂/n₁=1.0513 → arcsin undefined. None caught before professor stage.

**Why nothing caught it:** hypothesis generation in
`ResearchNarrationService` and the upstream `FormulationService` has
no domain-aware sanity layer. Statistical-framing, citation, and
cherry-pick audits all fire *after* the tool call.

**Files (proposed):**
- New: `src/main/java/com/emergentterrain/research/PremiseValidator.java`
  — single static `validate(String topic, JsonNode hypothesisParams)`.
  Topic-keyed rules (start minimal): for `snell` / `tir` /
  `critical-angle` topics, require `n1 > n2` and warn if a "<material1>
  -<material2>" name pins indices to non-matching values. For
  `hardy-littlewood` / `twin-prime`, require named slots for both
  `twin_density` and `total_density` so the prose can't conflate.
- `src/main/java/com/emergentterrain/research/FormulationService.java`
  — wire `PremiseValidator.validate` into the publish-hypothesis path;
  on REJECT, route to a single retry with the validation error fed
  back into the prompt; on second REJECT, abandon the paper.
- New WARN tag `premise:rejected:<reason>` so the operational-logs
  section of REVIEW_REPORT can track adoption.

**Acceptance:** P-a328-693's hypothesis would never have reached a
tool call; a second TIR paper with n₂>n₁ never appears in
`research/a3289005/` after the gate is flipped on.


### 21.5 — Symbolic-tool grammar relaxation (`^` and `Module[...]`)

**The signal:** P-a328-685 wasted two rounds on `^` rejected by
`evaluate_symbolic` (line 1) and `run_gpu_eval` (parse failed:
`Unexpected char '^' line 1 — run_gpu_eval accepts a scalar arithmetic
expression in n only`). P-a328-690's round-0 used `Module[{...}, ...]`
which `run_symbolic` rejected as "non-whitelisted heads: Module,
CompoundExpression". Both are repeat offenders — Phase 19 fixed the JS
sandbox surface, not the symbolic surface.

**Why the existing gate is brittle:** `^` is universally readable as
power in mathematical notation; `Module[]` is the Mathematica idiom
for "let me define locals" and the LLM emits it reflexively. Rejecting
both forces 1–3 wasted rounds per paper before the LLM gives up and
emits the canonical form.

**Files (proposed):**
- `src/main/java/com/emergentterrain/service/MathToolSuite.java`
  `runSymbolic` and `evaluateSymbolic` paths — pre-rewrite layer
  symmetric to Phase 19's sandbox rewrites:
  - `x^n` → `x**n` (or `Power[x, n]` for the Symja path), unless
    inside a string literal.
  - `Module[{var1=val1, var2=val2}, expr1; expr2; result]` → inline
    substitute var bindings into `expr` and drop the trailing
    `; result` to a top-level evaluation.
- Telemetry hint: emit one INFO line per rewrite hit so we can
  measure the prevalence and decide whether the LLM ever produces
  these forms again.

**Acceptance:** 0 rejections on `^` or `Module[…]` across a 1-hour
corpus run after the rewrite lands; existing sandbox-friction unit
tests still pass.


### 21.6 — Citation validator: whitelist sweep parameters from substring match

**The signal:** 5/5 papers in `research/a3289005/` triggered
`CitationValidator: ... mode=downgrade citations:uncited=N` — and 3 of
those 5 had numerically *correct* underlying computations (P-a328-686
sweep matched `floor(d/λ)`; P-a328-690 series matched the standard
small-angle formula at four amplitudes; P-a328-684 sandbox returned
the correct best angle). The validator demoted all three to
NULL_RESULT or REJECTED because Hypothesis sweep parameters
("starting position of 17", "scale of 7.4") don't appear verbatim in
the tool-call output strings.

**Why the substring match is too strict:** hypothesis params and tool
inputs share a numeric identity through the *prompt template*, not
through the tool's text output. The validator is enforcing a property
the system never guaranteed.

**Files (proposed):**
- `src/main/java/com/emergentterrain/research/CitationValidator.java` —
  accept an additional `sweepParamWhitelist: Set<Double>` argument
  populated from the upstream FormulationService's `range_start`,
  `range_end`, `param1`, `param2`, `starting_position`, `scale` keys.
  Any numeric value in Hypothesis that appears in this whitelist is
  exempt from the "must appear in call output" check.
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  (~line 1124, where `citationValidator` is called) — thread the
  sweep-param whitelist through from the hypothesis-generation step.
- Telemetry: emit `citations:exempt_sweep=K` alongside the existing
  `uncited=N:unmatched=M:out_of_tolerance=L` so we can see the
  before/after.

**Acceptance:** P-a328-686 (math-correct, REJECTED on citation gap)
graduates to NULL_RESULT or PUBLISHED on replay; corpus-level
`numerical-consistency` WARN drops by at least 50%.


### 21.7 — Peer-score premise-validity axis (DEFERRED)

**The signal:** P-a328-693 received peer score **0.989** — the highest
in this batch — on a paper whose Hypothesis is mathematically
impossible (arcsin>1) and whose only tool call was an unevaluated
symbolic echo. The four panel-review axes (Honesty, Completeness,
Rigor, Novelty) all came back 0.00 — yet peer score itself was high.
Peer scoring evaluates narrative polish independently of premise
validity; this is a wider rubric problem.

**Why it's deferred, not in scope:** the fix likely needs a new axis
in `ReviewPanelService`'s prompt template (something like "Could this
hypothesis, as stated, have a real solution?") plus a corresponding
weight rebalance. That is a quality-pipeline change of the same shape
as Phase 5 / Phase 14, not a triage item. Recorded here to make sure
the next refresh that catches a second high-peer / failed-physics
paper has a place to land.

**Named trigger:** if a second paper in `research/a3289005/` or any
other room ships with peer ≥ 0.95 AND a `panel:n=*:agreement=*` ≥ 0.90
AND a 0.00-median axis row AND no successful `run_symbolic` or
`run_sandboxed_code` call, promote this to an own-phase doc.


---

## Items NOT pending (verified done)

| Item | Source | Status |
|------|--------|--------|
| Schema NOT-NULL backfill for `narration_input_tokens` / `narration_output_tokens` | `logs/et-app.log:20-138` startup-time DDL failure | Fixed manually (this conversation, 2026-05-19) via `ALTER TABLE research_papers ADD COLUMN ... BIGINT NOT NULL DEFAULT 0` against the 518 existing rows. Hibernate `ddl-auto: update` now no-ops on next startup. |
| F7.3 cherry-picked-evidence audit | `logs/et-app.log:07:30:49 P-a328-693 omitted_calls=[1]` | Already shipped; fired correctly. No rollback signal. Item 21.4 builds on it by adding a pre-filter at hypothesis time. |
| Phase 19 sandbox-friction (`.push`, `len`, return-position objects) | Phase 19 doc | Shipped (Tier 5 flag flip 2026-05-18). The `.push` reject on P-a328-684 came from a paper-generation batch that predates the deployed flag taking effect. |
| Phase 9 G9 pre-debit gate itself | `GroqPool.java:163` | Shipped. Item 21.1 proposes tuning, not removing. |

---

## Suggested ordering

1. **21.1** (Groq pause threshold) — config-only first cut; one-line
   change; immediate measurable effect on the 110 budget-gate WARN
   storm. Low risk.
2. **21.2** (dispute orphan retire) — code + Hibernate migration; high
   noise reduction (28% of WARN volume).
3. **21.6** (citation validator whitelist) — would lift 3/5 papers in
   this batch from NULL_RESULT toward PUBLISHED without changing any
   science.
4. **21.3** (symbolic-solver echo) — narrow fix in `MathToolSuite`;
   single failing paper today but the failure mode is severe (silent
   fabrication).
5. **21.4** (premise preflight) — biggest user-visible quality win;
   needs `PremiseValidator` from scratch.
6. **21.5** (`^` / `Module[]` grammar) — symmetric to Phase 19; modest
   savings but well-understood pattern.
7. **21.7** — held until trigger fires.

**Closing:** items 21.1 + 21.2 alone would cut WARN volume by ~70%
and remove the dominant runtime noise source, making future refreshes
of REVIEW_REPORT.md cleaner to read.
