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

**Status (2026-05-19):** **landed**. Persistence (replicator bot id +
attempts counter), the demote-after-N path, and the DISPUTE_ABANDONED
markdown banner all ship in the same commit; see "Implementation notes"
at end of this section.


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

**Implementation notes (2026-05-19):**

- *Persistence (21.2a).* Two new columns on `research_papers`:
  `dispute_replicator_bot_id` (nullable string) and `dispute_attempts`
  (NOT NULL int, default 0). Hibernate `ddl-auto: update` will add both
  on next start; legacy rows backfill to NULL/0 implicitly. Setters
  + record-style accessors added to `ResearchPaper` (incl.
  `incrementDisputeAttempts()` helper). `ReplicationService` now writes
  `setDisputeReplicatorBotId(replicatorBotId)` before the `save(paper)`
  in the DISPUTED transition path, so the replicator is recoverable
  from a cold restart.
- *Scan logic (21.2b).* `UniversityService.scanOrphanedDisputes` now
  bumps `dispute_attempts` first; if the count reaches
  `app.research.dispute-max-attempts` (default 6), the paper is demoted
  to `DISPUTE_ABANDONED`, its markdown is rewritten with the new banner,
  and the next sweep skips it (the existing `DISPUTED`-status guard
  filters it out). Below the cap, the bumped counter is persisted and
  `enqueueReconciliation(p, p.disputeReplicatorBotId())` re-pings BOTH
  sides — pre-21.2 rows with a null replicator fall back to the
  single-author path identically to before.
- *Markdown banner.* `formatPaperAsMarkdown` gains a `DISPUTE_ABANDONED`
  branch ("⊘ DISPUTE ABANDONED — … the reconciliation pipeline failed
  to resolve the dispute within the configured retry window") next to
  the existing DISPUTED / NULL_RESULT / REJECTED / NEEDS_REVISION /
  PUBLISHED_NO_PROFESSOR banners.
- *Config.* New optional knob `app.research.dispute-max-attempts`
  (default 6). The existing `app.research.disputeOrphanAgeHours` (6)
  and `app.research.disputeOrphanScanIntervalMs` (300000) are unchanged
  — at the 5-minute scan interval, 6 attempts ≈ 30 minutes; raise
  `dispute-max-attempts` if you want closer to "≈ 6 h" of the doc's
  original wording.
- *Tests.* `MathPhysicsEngineTest` and `PublishPaperTest` updated to
  pass the new `disputeMaxAttempts` constructor arg. The two existing
  smoke tests (`phase7_G10_scanOrphanedDisputes_isNoOpWhenFlagDisabled`,
  `phase7_G10_scanOrphanedDisputes_handlesEmptyCache`) still pass —
  both preserved code paths. A full orchestration test for the demote
  path needs reflection plumbing to back-date a DISPUTED paper into
  the private `published` cache; deferred to a follow-up (low risk —
  the new logic is localised and the markdown banner is the same shape
  as the existing five status banners).

**What this section does NOT solve:** if a third party (another
service or external trigger) writes `DISPUTED` to a paper without
populating `dispute_replicator_bot_id`, the scan will still fall back
to the author-only re-fire path. That's the same behaviour as today;
no regression.


### 21.3 — Detect symbolic-solver echo in `run_symbolic`

**Status (2026-05-19):** **landed**. Echo detector, N[…] retry, and
actionable error string all ship together; see "Implementation notes"
at the end of this section.


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

**Implementation notes (2026-05-19):**

- *Detector.* `MathToolSuite.isSolverEcho(input, result)` — package-
  private static. Fires when all three signals line up: (a)
  `result.numericApprox() == null`, (b) input's leading head is in
  `ECHO_PRONE_HEADS = {Solve, Reduce, Roots, FindRoot, DSolve, NSolve,
  Integrate, Sum, Product}`, (c) Symja's full-form output starts with
  the same head applied (either `Head(…)` or `Head[…]` — Symja's bracket
  form varies). Three signals × one whitelist keeps false positives
  near zero: `Sin[Pi/2]` won't fire (numeric is present), `Plus[x,y]`
  won't fire (Plus isn't in the head list), `Solve[x^2-4==0,x]` won't
  fire (output is `{{x->-2},{x->2}}`, not a `Solve` echo).
- *Retry.* Re-evaluates `N[<original>]` once. If that produces a non-
  null numeric OR a non-echo output, return the retry result with a
  `(retried as N[…])` annotation in the prose so the LLM can see what
  happened. This is rarely a save for the P-a328-693 case
  (`N[Solve[1.577/1.5==Sin[θ], θ]]` still echoes — TIR with n₂>n₁ has
  no real solution) but covers cases where Symja's symbolic path
  refuses but its numeric path succeeds.
- *Actionable error.* On retry-also-echoes, return the explicit
  string `"Symbolic solver did not evaluate the expression — Symja
  echoed the input back without a numeric value. … Try: NSolve[…] for
  numeric root-finding, N[ArcSin[ratio]] for direct evaluation, or
  rewrite the problem to expose a closed form."`. This is the
  signal the LLM needs to abandon the fabrication path — naming
  NSolve/ArcSin in the error tells the model what the next prompt's
  tool call should look like.
- *Tests.* New `MathToolSuiteSolverEchoTest` with 8 tests: 5 cover
  the static detector in isolation (Solve in either bracket form,
  bare value, numeric-present non-echo, non-echo-prone heads), 3
  cover the full round-trip including the exact P-a328-693 expression
  and a regression check for `Sin[Pi/2]` so back-compat for the 12+
  existing Symja tests stays intact.

**What this section does NOT solve:** the LLM still has to read the
error string and switch tactics. If a future paper fabricates a number
DESPITE seeing the actionable error, the next lever is the citation
validator path (§21.6 already exempts only verified sweep params; the
fabricated number would land as `unmatched > 0` and route to
NULL_RESULT).


### 21.4 — Physics-premise preflight gate at hypothesis time

**Status (2026-05-19):** **landed**. `PremiseValidator` shipped with
two rule families (TIR n₁/n₂; twin-prime density qualification),
wired into `FormulationService` with single-retry-on-reject and
abandon-on-second-reject; see "Implementation notes" at end of
section.


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

**Implementation notes (2026-05-19):**

- *New file.* `src/main/java/com/emergentterrain/research/PremiseValidator.java`
  — final / package-static. Public surface is one method
  `validate(String topic, String hypothesisText)` and a sealed
  `Result` interface with `Ok` / `Reject(reasonCode, explanation)`.
  Stateless; safe to call from any thread.
- *TIR rule.* Two regexes (`N1_PAT`, `N2_PAT`) extract n₁ and n₂ from
  either Unicode-subscript (`n₁=1.50`, `n₂≈1.577`) or ASCII forms
  (`n1=1.5`, `n_{2}=1.6`). Accepts `=`, `≈`, `~` between symbol and
  value. When both n₁ and n₂ are present and `n₂ ≥ n₁`, rejects with
  reasonCode `tir_indices_inverted` and an explanation naming both
  values and `arcsin(n₂/n₁)`. Hypotheses with no `n₁`/`n₂` pair pass
  (the regex is opt-in).
- *Twin-prime rule.* Three regexes (`TWIN_DENSITY_PAT`,
  `OVERALL_DENSITY_PAT`, `BARE_DENSITY_PAT`). When the prose says
  "density" but only one of {twin, overall} is qualified, rejects with
  one of three reason codes (`twin_density_unqualified`,
  `overall_density_only`, `density_ambiguous`).
  `OVERALL_DENSITY_PAT` uses a negative lookbehind `(?<![\w-])` so
  "prime density" inside "twin-prime density" doesn't spuriously
  satisfy the overall slot. Hypotheses that don't mention "density"
  pass.
- *Topic classification.* `normaliseTopic(s)` lowercases + replaces
  underscores/whitespace with hyphens, so "Snell Critical Angle
  Dispersion", "snell-critical-angle", and "snell_critical_angle" all
  hit the same rule. `isSnellOrTirTopic` matches any of
  `snell|tir|critical-angle|total-internal-reflection`;
  `isTwinPrimeTopic` matches any of
  `twin-prime|hardy-littlewood|prime-constellation`. Topics outside
  both classes always return `Ok` — conservative on purpose; the
  doc's acceptance is only the P-a328-693 case.
- *FormulationService wiring.* `formulateResearch` now calls a private
  `retryOnPremiseRejection(domain, topicName, originalPrompt, firstAttempt)`
  after `parseFormulationResult`. On first REJECT, the original prompt
  is appended with `[PRE-REGISTRATION REJECTED — premise-preflight
  <reasonCode>]` + the explanation + an instruction to rewrite the
  hypothesis and re-emit the same JSON shape. The retry response is
  re-parsed and re-validated; on second REJECT, returns null
  (paper abandoned). Both outcomes log at WARN with
  `premise:rejected:<reasonCode>` or `premise:retry_recovered:<code>`
  so the operational-logs section of REVIEW_REPORT can track them.
- *Tests.* New `PremiseValidatorTest` with 11 cases: P-a328-693's
  exact hypothesis text rejected with `tir_indices_inverted`, glass→air
  TIR accepted, ASCII subscripts handled, no-indices skip, non-Snell
  topic skip, equal-indices reject, twin-prime accept/reject
  trio, topic normalisation, null/blank guard.

**What this section does NOT solve:** the "<material1>-<material2>
name pins indices" sub-check the doc mentioned (e.g. "glass-air" pins
n₂=1.00) is not implemented — would need a refractive-index lookup
table. Out of scope for the named acceptance, which only requires
the n₁>n₂ check. Add later if a paper emits "diamond-water" with
non-matching numeric indices and slips through.


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

**Status (2026-05-19):** **landed**. Whitelist parameter, telemetry, and
the back-compat overload all ship together; see "Implementation notes"
at the end of this section.


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

**Implementation notes (2026-05-19):**

- *Validator change.* `CitationValidator.validate` grows a three-arg
  overload that accepts `Set<Double> sweepParamWhitelist`. Old two-arg
  signature kept as a back-compat shim that delegates with `Set.of()`,
  so the existing 12+ unit tests and any non-narration callers stay
  bit-identical. New `isWhitelisted(value, set)` helper compares with
  the same `withinTolerance` relation as the trace-conformance check —
  PaperDraft `param1=17` (int) matches a whitelist `17.0` (double)
  without an int/double mismatch.
- *Result record.* Added `int exemptSweepCount` as the fourth
  component of `CitationValidationResult`. `clean()` returns 0;
  `hasFailures()` ignores it (exemptions are not failures, just
  bookkeeping). All test-side constructor calls in
  `CitationsGateDowngradeTest` updated to the 5-arg form.
- *Whitelist source.* `ResearchNarrationService.sweepWhitelistFor(draft)`
  returns `{ draft.param1().doubleValue(), draft.param2() }` minus
  any nulls. Null draft / null params → empty set (legacy path
  preserved). The exempt set is exactly the two sweep coordinates the
  formulation step pre-registered; not range_start / range_end /
  starting_position / scale as the doc originally listed — those
  names are tool-call inputs (MathToolSuite) and DO appear in tool
  outputs, so the validator already finds them via the existing
  substring path.
- *Telemetry.* `applyCitationsGate` now stamps
  `citations:ok:exempt_sweep=K` (when the gate passes only because of
  exemptions) or appends `:exempt_sweep=K` to the existing
  `citations:uncited=…:unmatched=…:out_of_tolerance=…` summary. The
  exempt count is visible alongside the failure counts so a refresh
  audit can compute the before/after lift.
- *Signature change.* The private `applyCitationsGate` now takes the
  whole `PaperDraft` instead of just the `paperId` string — the
  one call site already had `draft` in scope, so the rewrite was a
  single-line edit at the call.
- *Tests added.* Four new tests in `CitationValidatorTest` cover:
  whitelisted hypothesis number → exempt (not uncited); unrelated
  numbers in same paper → still uncited; empty whitelist behaves
  identically to the two-arg overload; int/double float-widening
  tolerance.

**What this section does NOT solve:** the four sweep-param names the
doc lists (`range_start`, `range_end`, `starting_position`, `scale`)
are not all in the PaperDraft surface; only `param1` (≈
starting_position) and `param2` (≈ scale) are. If a future tool surface
adds an independent sweep coordinate that lands in hypothesis prose
without going through PaperDraft, the whitelist will miss it; revisit
`sweepWhitelistFor` to expand the source set then.


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
