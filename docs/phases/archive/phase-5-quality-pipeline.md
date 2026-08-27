# Phase 5 — Quality Pipeline (scoping)

**Scope:** Lift the simulation's paper-quality plateau. Across 91 papers and 13 refreshes of the automated peer review ([REVIEW_REPORT.md](../research/a3289005-archive/REVIEW_REPORT.md)), acceptance rate has held at 27% for six consecutive refreshes; the report attributes this to a small number of identifiable pipeline bugs that repeat across most papers. Phase 5 lands fixes for each, grouped by effort.

**Status:** Scoping document. No code in this phase yet.

**Prerequisite:** Phases 0 through 4 + Phase 4b shipped (see [STATUS.md](STATUS.md)). Phase 4c Piece 2 (`PromptTemplateRegistry` owning all 11 production templates) is also already shipped — Group A's template-text edits land through the registry, not as inline rewrites.

**Goal:** Convert the failure-mode catalogue in REVIEW_REPORT.md into concrete service-level fixes. Each piece names the exact failure mode it addresses (paper IDs in the corpus), the service it edits, and a pinning-test sketch. Behaviour-changing pieces are flag-gated per the project convention; correctness-class fixes are not.

**Why this is risky:** Less risky than Phase 4c Piece 3 (no LLM-text drift), but Phase 5 changes *what gets published* — papers that would have shipped as `PUBLISHED` may now be downgraded to `NEEDS_REVISION`, and the visible acceptance rate may *fall* before it recovers. The denominator changes, not the work being done. Operators should expect a temporary acceptance-rate trough as the publication gates tighten.

**Relationship to Phase 4c Piece 3:** Phase 5 should land *before* the prompt-template evolution loop fires. Otherwise the A/B baseline includes the very template bugs Phase 5 fixes (the boilerplate "no prior theoretical bound" closer, the pendulum-template 5° offset, the harness-parameter leaks), and the evolution loop closes a feedback loop on a known-broken baseline. Phase 4c Piece 3's "2–4 weeks of observed runtime" pre-condition should explicitly include "with Phase 5 Group A landed."

---

## What the review report identified

Concretised from [REVIEW_REPORT.md](../research/a3289005-archive/REVIEW_REPORT.md) §"Recommendations to raise the batch to human quality" (23 numbered items, May 2026). The top failure modes by frequency:

| Failure mode | Frequency | Worst offenders |
|---|---|---|
| Mid-sentence truncation in Findings / Conclusion | ~55% of all papers (53 of 91) | P-a328-1, 2, 9, 11, 15, 23 … |
| `critique:skip:incomplete_firstpass` published as `Status: PUBLISHED` | ~85% of new papers | P-a328-23 … 42 |
| Conclusion contradicts the Findings table | ≥ 5 confirmed | P-a328-13, 14, 41, 60, 87 |
| Near-byte-identical reruns with no citation | 5 clusters | 15/49, 33/58, 9/32/53, 68/79, 69/73/85 |
| Cross-domain hypothesis with no physical mechanism | 5 confirmed clusters | prime→CTE (×5), happy→packing, VdW→stress |
| Magnitudes outside physical bounds | 7 in composite cluster, 1 in VdW | 12 GPa composite stress; 7.8 GPa VdW |
| Harness parameters leaked into prose | ≥ 11 papers | "starting at position 10 and uses a scale of 7.0" |
| "Validator supports" overclaim for 29–35% deviation | 4 confirmed | P-a328-70, 71, 74, 78 |
| Q-vs-R label inversion in RLC papers | 4 confirmed | P-a328-47, 49, 57, 59 |
| Canned "no prior theoretical bound" closer on textbook-bound problems | ≥ 5 papers | P-a328-9, 11, 15, 22 |
| Pendulum table shifted 5° from physics | 2 (template-level) | P-a328-60, 87 |
| Sandbox 4b rejects Math.PI / cos / sin / sqrt | 18 occurrences in current run | many — causes vague Findings |

Two of the three top failure modes (truncation, sandbox-reject) trace directly to log-level constraints surfaced by the report's §"Operational logs" section — fixable in code without touching the LLM provider.

---

## Four groups of work, in order

### Group A — Quick wins (single-service edits)

Each item is a one-commit change, hours-to-half-a-day of work, addresses a top-frequency failure mode in REVIEW_REPORT.md.

#### A1. Truncation guard before publish

**Addresses:** Recommendation #1 in REVIEW_REPORT.md (~55% of papers).
**Where:** [ResearchNarrationService.java](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java) — after `parseNarrationResult` returns a non-null `NarrationResult`.
**What:** Add `endsWithSentenceTerminator(String)` — accept `.`, `!`, `?`, optionally followed by whitespace, closing markdown, or a trailing emoji. If `findings()` or `conclusion()` fails the check, *one* of:
  - retry with a continuation prompt seeded with the last ~80 chars and `Continue from where the previous response ended.`;
  - split the `Findings` generation into two sequential calls and concatenate (the "Computation 2" cut-off signature in 53 papers suggests one specific branch is exhausting the token budget mid-table);
  - move the secondary computation into its own narration field.

Pick the approach in implementation; the memo doesn't need to commit yet.

**Flag:** `app.research.truncation-guard.enabled` — default `false` initially so operators can compare acceptance rate with/without.
**Pinning test:** new `ResearchNarrationServiceTest`: hand-construct a `NarrationResult` ending at "Computation 2 produced approximately 4.91", assert the guard either rejects or invokes the continuation path; with the flag off, the result passes through unchanged.

#### A2. Status downgrade on `critique:skip:incomplete_firstpass`

**Addresses:** Recommendation #2 in REVIEW_REPORT.md (~85% of new papers).
**Where:** [ResearchNarrationService.java:199](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java#L199) emits the marker; `UniversityService.publishPaper` reads `callPath` before stamping status on [ResearchPaper.java:61](../src/main/java/com/emergentterrain/research/ResearchPaper.java#L61).
**What:** If `callPath` contains `critique:skip:incomplete_firstpass`, status becomes `NEEDS_REVISION` instead of `PUBLISHED`. The peer-review pipeline is what catches the other failure modes; bypassing it and still publishing is the single most damaging signal in the corpus.

Distinguish carefully from `prof:error→fp`: that's Groq token-per-day exhaustion (12 papers in the current run), an *upstream* quota issue rather than a skipped review. Give it its own status — `PUBLISHED_NO_PROFESSOR` — so telemetry can split algorithmic from infrastructure problems. The DISPUTED flow in `UniversityService.markPaperDisputed` already shows the pattern.

**Flag:** none — this is a correctness fix. The previous behaviour was lying about its own quality gating.
**Pinning test:** integration-style test that primes `ResearchNarrationService` to return an incomplete first pass, runs `publishPaper`, and asserts the resulting `ResearchPaper.status() == "NEEDS_REVISION"`.

#### A3. Expand 4b sandbox whitelist for trig + constants

**Addresses:** Recommendation #22 in REVIEW_REPORT.md (18 sandbox rejections in the current run).
**Where:** [MathToolSuite.java:273](../src/main/java/com/emergentterrain/service/MathToolSuite.java#L273) lists the whitelisted functions in the 4b review prompt; `SandboxEngine` enforces the same list at the interpreter level.
**What:** Add `sin, cos, tan, asin, acos, atan, atan2, PI, E` to both lists. These are bounded, deterministic, side-effect-free; the existing termination-guard already covers loops. The 18 current-run rejections forced the narrator to fall back to crude approximations on at least P-a328-45 (projectile drag) and the entire composite-failure cluster — exactly the papers that produce unphysical magnitudes.

**Flag:** none — pure whitelist expansion, no behavioural risk if the interpreter enforces the contract.
**Pinning test:** new `SandboxEngineTest` cases exercising `Math.PI`, `Math.sin(0.5)`, `Math.cos(Math.PI)`, `Math.sqrt(2)`; existing rejection tests for `String`, `new Object()`, network calls, etc. must continue to pass.

#### A4. Harness-parameter leak filter

**Addresses:** Recommendation #5 in REVIEW_REPORT.md (≥ 11 papers, including the newer P-a328-28 internal-score leak).
**Where:** [FormulationService.java](../src/main/java/com/emergentterrain/service/FormulationService.java) (hypothesis generation) and [ResearchNarrationService.java](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java) (findings/conclusion). Both already render through `PromptTemplateRegistry` after Phase 4c Piece 2.
**What:** Add a `PromptSanitizer` post-render pass keyed on regex patterns the report cites verbatim: `starting at position \d+`, `uses a scale of [\d.]+`, `quality score of [\d.]+`. Strip these phrases from user-visible Hypothesis text before commit. Internal numeric IDs continue to flow through to the simulator as opaque seeds via the existing `param1`/`param2` plumbing.

**Flag:** `app.research.harness-leak-filter.enabled`.
**Pinning test:** unit tests with each cited leak phrase, asserting the sanitizer removes the phrase while preserving the surrounding text.

#### A5. Replace canned boilerplate closer

**Addresses:** Recommendation #4 in REVIEW_REPORT.md ("no prior theoretical bound constrains this region" — ≥ 5 papers, all on textbook-bound problems).
**Where:** Templates `NARRATION_PRIMARY` and `NARRATION_REVISION` (both variants) in [PromptTemplateRegistry.java](../src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java). Phase 4c Piece 2 already made this a one-file edit.
**What:** Drop the boilerplate closer entirely. When the professor stage is unavailable, the fallback should be silence about novelty, not an unconditional novelty claim. If a closing sentence is needed for grammatical completion, replace with a domain-specific "see methods" pointer that doesn't make a claim.

**Flag:** none — but bump the template `version` per the registry's `(id, version)` keying convention. Phase 4c Piece 3 will then see the boilerplate-free version as the new baseline.
**Pinning test:** byte-for-byte equivalence test in `PromptTemplateRegistryTest` against the *new* template output for a fixed input; the old equivalence test is replaced (the registry retains the prior version under its prior version-tag so Piece 3 can A/B them if it wants).

---

### Group B — New pure-function validators (one new service each)

Each is a stateless predicate the paper must pass before `Status: PUBLISHED`. The pattern matches the Phase 2.5 carves (`TileValidatorService`, `OccupancyService`): small service, large pinning test suite, called from a single integration point.

#### B1. `NumericalConsistencyValidator`

**Addresses:** Recommendations #3, #16, #18 in REVIEW_REPORT.md.
**Surface:** `validate(Hypothesis hypothesis, Findings findings, Conclusion conclusion) → List<Inconsistency>`.
**Checks:**
  1. Qualitative direction in Conclusion matches sign/trend in Findings table (catches P-a328-41: data shows inverse trend, conclusion claims direct).
  2. Parameter values in Conclusion fall within Hypothesis-stated range (catches P-a328-50: hypothesis "near 380", conclusion "starting 710"; P-a328-61: "near 100" → "starting 348").
  3. Numerical claims in Conclusion match closest entry in Findings to within stated precision (catches P-a328-14: "close to 2×" for 1.07×; P-a328-13: 2×Em at Vf=0.55 *and* Vf=1.00 for the same model; P-a328-60: conclusion 25° vs table 30°).

**Implementation note:** Each check is a tiny numeric-extraction pass over the LLM's output text. Use a simple regex to pull `(quantity, value, unit)` triples; tolerate ±5% for "approximately" / "near"; flag everything else as a hard violation.

**Wiring:** called from `UniversityService.publishPaper` before status assignment. Failure → `NEEDS_REVISION` with structured `validation_failures` field on the paper row.
**Flag:** `app.research.numerical-consistency.enabled`.
**Pinning test:** one synthesised paper per cited failure (P-a328-13, 14, 41, 50, 60, 61); each asserts the validator returns a non-empty `List<Inconsistency>` with the expected `kind`.

#### B2. `MagnitudeRangeValidator`

**Addresses:** Recommendation #7 in REVIEW_REPORT.md.
**Surface:** `validate(String domain, String dependentVariable, double value, String unit) → Optional<RangeViolation>`.
**Data:** a small per-domain table of physically-reasonable bounds. Seed with the bounds the report cites:
  - Composite longitudinal stress ≤ 3 GPa (catches P-a328-2, 8, 12, 42, 63, 84, 86: all unphysical 12 GPa)
  - VdW stress ≤ 1 GPa for any plausible composite (catches P-a328-7: 7.8 GPa)
  - Arrhenius +10K multiplier ≤ 1.2× at high Ea (catches P-a328-19's broken 1.18×, but only flags — the *correct* answer of 5.8× would also exceed if Ea is misread; this is a soft warning, not a hard reject)
  - CTE for Cu, Al within published ranges (catches P-a328-22, 27, 30: wrong reference values)

Keep the table hand-curated initially (≤ 30 bounds in a `domain-magnitudes.yml` resource). Promote to a `MagnitudeBound` JPA entity only if it grows past ~100 entries.

**Wiring:** called from `MathToolSuite.runSandboxedCode` after the simulation result returns, *before* the result is fed into the narrator. Out-of-range → annotate the simulation result with a `magnitude_warning` field; narrator template must surface the warning in Findings. Two consecutive out-of-range results on the same topic + room → auto-`NULL_RESULT`.

**Flag:** `app.research.magnitude-range.enabled`.
**Pinning test:** one numeric assertion per cited bound; one integration test asserting two consecutive out-of-range results trigger NULL_RESULT.

#### B3. `ContentHashDeduplicator`

**Addresses:** Recommendations #8 and #13 in REVIEW_REPORT.md (5 confirmed near-clone clusters: 15/49, 33/58, 9/32/53, 68/79, 69/73/85).
**Surface:** `findClones(Findings findings, Conclusion conclusion, UUID roomId) → List<ResearchPaper>` returning prior room papers whose normalized-numeric-fingerprint matches within tolerance.
**Fingerprint:** sort numeric tokens (rounded to 2 sig-figs), concatenate with stemmed topic keywords. The report shows clones share identical Findings tables to the digit — fingerprinting `(domain, sorted(numeric_tokens), topic_stems)` would have caught every confirmed cluster.

**Wiring:** called from `UniversityService.publishPaper`. On near-clone hit:
  - If the new paper *cites* the prior, allow publication (the cross-paper citation behaviour from P-a328-34, 54, 73 is the goal).
  - If the new paper doesn't cite the prior, downgrade to `NEEDS_REVISION` with a `near_clone_of=<paperId>` field and a structured note for the narrator to either cite-and-reframe or pick a different range.

**Flag:** `app.research.dedup.enabled`.
**Pinning test:** synthesise two papers with the report's confirmed-clone fingerprints (e.g. matching P-a328-9 vs P-a328-32 numerics); assert `findClones` returns the prior.

#### B4. `CrossDomainHypothesisGate`

**Addresses:** Recommendation #6 in REVIEW_REPORT.md (5 unfixed cross-domain non-sequitur clusters).
**Surface:** `validate(Hypothesis hypothesis, String declaredDomain) → Optional<DomainMismatch>`.
**Logic:** extract dependent + independent variable tokens from the hypothesis; reject if dependent is in {materials, physics} and independent is in {number_theory} (or vice versa) and the pair is *not* in `KnowledgeBaseService.knownCrossDomainBridges()`. Seed the bridges set empty — populate it only when a legitimate cross-domain finding emerges from real experimentation, not from speculation.

**Wiring:** called from `FormulationService` before the hypothesis is committed to the `PaperDraft`. On mismatch → reject the hypothesis and re-formulate within the declared domain.

**Catches:** P-a328-1, 23, 44, 50, 61 (prime gaps → CTE, 5 unfixed repeats); P-a328-6, 41 (happy numbers → packing); P-a328-7 (VdW → composite stress); P-a328-16 (pendulum-breakdown angle → Collatz); P-a328-18 (divisor sums titled as math, hypothesised as materials).

**Flag:** `app.research.cross-domain-gate.enabled`.
**Pinning test:** synthesise hypotheses with each cited cross-domain pair; assert rejection. Synthesise a same-domain hypothesis; assert acceptance. Add one bridge to `knownCrossDomainBridges`; assert the corresponding pair now passes.

#### B5. Tighten `TopicValidator` support band

**Addresses:** Recommendation #19 in REVIEW_REPORT.md (4 confirmed overclaims: P-a328-70, 71, 74, 78 — "supports the hypothesis" for 29–35% deviations).
**Where:** wherever the per-topic validator currently emits the "the topic-specific validator supports the hypothesis" string. The report doesn't pin a file; the memo's first task is to locate it (likely in `MathPhysicsEngine` or a topic-specific scoring helper near `MathToolSuite`).
**What:** Replace the binary support/no-support flag with an explicit MDE band per claim-kind:
  - Asymptotic predictions (Hardy-Littlewood, prime-counting, etc.): ≤ 15% = `STRONG_SUPPORT`, 15–30% = `SUGGESTIVE`, ≥ 30% = `OUTSIDE_SUPPORT`.
  - Closed-form algebraic predictions (Reuss, Arrhenius, RLC critical R): ≤ 3% = `STRONG_SUPPORT`, 3–10% = `SUGGESTIVE`, ≥ 10% = `OUTSIDE_SUPPORT`.
  - Empirical lookup (OEIS-class): unchanged — binary.

Emit the specific deviation magnitude in the validator output, not just the band, so the narrator can quote it.

**Flag:** none initially — fix the band, ship the new strings. If wording-change risk is a concern, gate behind `app.research.tighter-support-band.enabled`.
**Pinning test:** one assertion per band threshold; one assertion that the 34.6% deviation from P-a328-70 maps to `OUTSIDE_SUPPORT`, not `STRONG_SUPPORT`.

---

### Group C — Routing + telemetry (cross-service wiring)

These pieces wire existing services together rather than carving new ones.

#### C1. Citation-aware peer-score weighting

**Addresses:** Recommendations #10, #15, #20 in REVIEW_REPORT.md (3 cross-paper citations observed, one to a Reject paper).
**Where:** wherever peer score is composed (probably in `UniversityService.computePeerScore` or `EpistemicMarketService` — confirm in memo). Plus `ExperimentPlannerService` for Gen-2 routing.
**What:**
  - Reward citations of `Status: PUBLISHED, peerScore > median` papers (positive weight on the citing paper's score).
  - Penalize citations of `critique:skip:incomplete_firstpass` papers (negative weight). Avoids the P-a328-54 → P-a328-49 failure-propagation pattern.
  - When `ContentHashDeduplicator` (B3) finds a near-clone without citation, down-rank the new paper's score.
  - `ExperimentPlannerService` Gen-2 routing: preferentially assign follow-ups to authors whose `RoomBot` has previously cited or read a prior paper on the topic. The first three cross-paper citations (34→30, 54→49, 73→69) show the room can do this; routing should amplify the behaviour.

**Flag:** `app.research.citation-aware-scoring.enabled`.
**Pinning test:** synthesise an authoring sequence (paper A published with peerScore 0.30, paper B cites A) and assert B's score reflects the bonus; flip A's status to `NEEDS_REVISION` and re-score B to assert the penalty.

#### C2. DISPUTED-paper reconciliation queue

**Addresses:** Recommendation #23 in REVIEW_REPORT.md (positive — amplify the new DISPUTED mechanism by routing to reconciliation).
**Where:** `UniversityService.markPaperDisputed` already exists (referenced in [ResearchPaper.java:20-21](../src/main/java/com/emergentterrain/research/ResearchPaper.java#L20-L21)). Add a `ReplicationCouncilService.enqueueReconciliation(Paper)` call from there.
**What:** When a paper transitions to `Status: DISPUTED`, auto-emit a typed `ProgramOpenQuestion` on both involved bots' programs (original author + replicating bot). The question text references both peer scores explicitly so the next paper from either bot must address the divergence. P-a328-88's `Δ = 0.204` between original (0.357) and replication (0.561) is the kind of signal that should never be a one-shot artefact.

**Flag:** `app.research.dispute-reconciliation.enabled`.
**Pinning test:** primes a paper into DISPUTED state via the existing test path, asserts a `ProgramOpenQuestion` row appears on each bot's `ResearchProgram`, with text matching the divergence template.

#### C3. Surface sandbox-reject + prof-status in `LLM Call Path`

**Addresses:** Recommendation #21 in REVIEW_REPORT.md.
**Where:** [MathToolSuite.java:242](../src/main/java/com/emergentterrain/service/MathToolSuite.java#L242) (sandbox reject WARN log) and [ResearchNarrationService.java:262](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java#L262) (`prof:error→fp`). Both currently log but don't surface to the persisted paper.
**What:** Extend the `callPath` accumulator in `ResearchNarrationService` (and the `MathToolSuite.buildExecutor` it threads through) to emit two structured fields onto the final `LLM Call Path` paper field:
  - `sandbox_reject=N` (count of 4b rejections during this paper's narration)
  - `prof_status=ok|ratelimit|tool_use_failed|blank|timeout`

The information already exists in the log lines; just thread it onto the persisted field. Lets reviewers (human and automated) distinguish a Findings claim that was *computed* from one that was *only narrated*.

**Flag:** none — pure telemetry expansion. The field is human-readable so length growth is fine.
**Pinning test:** assert `MathToolSuite.runSandboxedCode` increments the per-paper sandbox-reject counter on a 4b-rejected code call; assert `ResearchNarrationService` writes `prof_status=ratelimit` to the call path on Groq 429.

#### C4. Closed-form regression suite

**Addresses:** Recommendation #11 in REVIEW_REPORT.md (P-a328-19 vs P-a328-31 simulator flake — same author, same simulator, one wrong, one correct).
**Where:** new test class `MathPhysicsEngineRegressionTest` in `src/test/java/com/emergentterrain/research/`. No production code change.
**What:** Fixed-seed comparison of `MathPhysicsEngine` output to closed-form solutions for the eight topics that have one:
  - Arrhenius +10K ratio = e^(Ea·10/(R·T²)) — assert within 5% across {Ea, T} ∈ {100, 150, 200} × {300, 350, 400} K
  - Reuss bound for Ef/Em ∈ {2, 10, 100} across Vf ∈ {0.2, 0.4, 0.6, 0.8}
  - RLC: R_crit = 2√(L/C), Q = 1/(2α)
  - Hudson binary FCC packing peak ≈ 0.793 at r ≈ 0.414
  - σ_thermal = E·α·ΔT for Cu, Al at ΔT = 100 K
  - Projectile drag range maximum at k ∈ {0.01, 0.05, 0.1}
  - Diffraction floor(d/λ) at d ∈ {1, 2, 3} µm, λ ∈ {400, 500, 600, 700} nm
  - Small-angle pendulum: T(θ)/T₀ = 1 + θ²/16 at θ ∈ {5°, 10°, 15°, 20°, 25°}

Until this regression test pins behaviour, the rest of Phase 5 is fighting downstream symptoms of an upstream flake. Land it first.

**Flag:** none — it's a test.
**Pinning test:** the test class *is* the pinning test. Each topic asserts the simulator's output against the closed-form within a topic-specific tolerance.

#### C5. Pendulum template offset fix

**Addresses:** Recommendation #16 in REVIEW_REPORT.md (P-a328-60 and Gen-2 P-a328-87 both show the same 5°-shifted period-error series).
**Where:** locate the pendulum-breakdown-angle template (likely in `SandboxEngine`, `MathPhysicsEngine`, or a topic-specific scoring helper near `MathToolSuite`). The memo's first task on this piece is to find it.
**What:** Findings table shows 0.77% at 25° (true: 1.19%) and 1.20% at 30° (true: 1.72%) — series is shifted by exactly 5°. The conclusion ("1% threshold at ~25°") is correct because it appears to be templated independently of the actual computed data. Two possibilities:
  - The simulator is off-by-one in its theta-sampling loop. Fix the loop bounds.
  - The conclusion-text generator pulls the threshold from a *different* code path than the table generator. Unify them so both read from the same `(θ, error)` series.

After fix, regression test from C4 asserts `T(22.9°)/T₀ - 1 == 0.01` within tolerance.

**Flag:** none — correctness fix.
**Pinning test:** part of C4's regression suite.

---

### Group D — Structural (multi-week, separate scoping docs)

#### D1. `LiteratureSearchService` before hypothesis generation

**Addresses:** "How to approach novelty" §1 in REVIEW_REPORT.md ("the room keeps re-verifying 1820s/1929/1949 results because it doesn't know they exist").
**Status:** out of scope for Phase 5 *as code* but inside scope *as a scoping doc* — Phase 5 should leave a `phase-6-literature-search.md` placeholder analogous to how Phase 4 left `phase-4c-prompt-template-evolution.md`.
**The largest single novelty lever** in the report. Add a stage between Formulation and Narration that retrieves known closed-form bounds for the (domain, topic) pair from a small curated corpus (~200 textbook results, hand-loaded as a YAML resource initially). Narrator prompt then has access to: "Hudson 1949 reports peak 0.793 for FCC binary; this paper finds 0.789 — within 0.5% of the reference."

Crosses the boundary from "verify known results" to "extend known results" and is the single piece most likely to push the corpus toward genuine novelty.

#### D2. Discussion / Related-Work section in `NarrationResult`

**Addresses:** "How to approach novelty" §5 in REVIEW_REPORT.md.
**Status:** out of scope for Phase 5; placeholder only.
**What:** Add `relatedWork` and `discussion` fields to `NarrationResult` and the persisted `ResearchPaper`. Narrator template adds two sections. B1's numerical-consistency validator extends to check Discussion claims too. Roughly doubles narration cost; gated by `app.research.discussion-section.enabled` with slow rollout.

---

## Suggested execution order

The order matters because some pieces share preconditions and some hide each other's signal.

| Wave | Pieces | Rationale |
|---|---|---|
| 0 | C4 (closed-form regression), C5 (pendulum fix) | Diagnostics first. Until the simulator is pinned, Group B validators may keep failing for upstream reasons. |
| 1 | A2 (status downgrade), A3 (sandbox whitelist), A5 (boilerplate closer) | Three single-commit wins. Hit three of the top four failure modes. Should bend the 27% acceptance plateau within one refresh. |
| 2 | A1 (truncation guard), A4 (harness leak filter) | Half-day each. Round out Group A. |
| 3 | C3 (call-path surfacing) | Telemetry hook — needed before B1–B5 to validate their effects. |
| 4 | B1 → B5 (one carve at a time) | Phase 2.5 cadence. Each is a service + pinning test; expect one per session. |
| 5 | C1 (citation-aware scoring), C2 (dispute reconciliation) | Depend on B3 (dedup) and ≥ 1 week of Phase 4b runtime. |
| 6 | D1, D2 placeholder docs | Separate scoping per the Phase 4c precedent. |

After Wave 1 alone, expect a transient acceptance-rate trough (papers that previously published as `PUBLISHED` now stamp `NEEDS_REVISION`), followed by a recovery as the upstream pipeline produces more papers that pass the tightened gates. Don't measure improvement on raw acceptance rate during the transition — measure on the structured `validation_failures` field instead.

---

## Open design questions for the memo to answer

These are the decisions an implementation memo needs to lock before Group B/C work starts. None have committed answers yet.

- **A1 truncation strategy.** Retry-with-continuation, split-and-concatenate, or move-to-new-field? Each has different cost and quality trade-offs. The "Computation 2" cut-off signature in 53 papers strongly suggests the issue is token-budget-bound on one specific branch — measure before picking.
- **B1 numeric-extraction approach.** Regex pass over LLM prose vs structured `Findings` field. The latter requires changing `NarrationResult` schema; the former is fragile to LLM output drift. Memo picks.
- **B2 magnitude-bound storage.** YAML resource (simple, no migration) vs JPA entity (queryable, evolvable). Start YAML; promote when it grows.
- **B3 fingerprint sensitivity.** 2 sig-figs is probably right for the cited clusters (where digits matched exactly), but real near-duplicates may have slight numeric drift. Memo picks the bucket size.
- **B4 cross-domain bridge seed.** Start empty (strict — likely over-rejects), or seed with a few documented bridges (lenient — likely under-rejects)? Empty is safer; bridges are added only on operator review.
- **B5 band thresholds.** The 15% / 30% asymptotic bands and 3% / 10% closed-form bands are first-cut numbers from the report. Memo should validate against the corpus before locking.
- **C1 citation-aware score formula.** Linear bonus, multiplicative, or non-linear? Phase doc doesn't specify; same trade-offs as Phase 4b's funding-allocation weighting.
- **C2 reconciliation question text.** Templated or LLM-generated? Templated is cheaper and more legible; LLM-generated is more contextual.
- **Status enumeration.** Currently `String` on [ResearchPaper.java:61](../src/main/java/com/emergentterrain/research/ResearchPaper.java#L61); A2 + the new `PUBLISHED_NO_PROFESSOR` value argue for an enum. Memo decides whether to promote now (Phase 5) or defer (Phase 6).

---

## Out of scope for Phase 5

To keep this from sprawling:

- **No LLM-text edits beyond removing the boilerplate closer (A5).** Improving narration prose is a separate workstream that should follow Phase 4c Piece 3's evolution loop, not precede it.
- **No multi-LLM routing or model-selection changes.** Sandbox 4b stays Ollama-fast; narrator stays Groq-primary.
- **No literature-search corpus** (deferred to D1).
- **No new front-end surfaces** for reviewers or operators. Surface everything through existing fields (`status`, `LLM Call Path`, the per-room buffer in `SurpriseBroadcastService`-style services).
- **No persistent A/B testing for the new validators.** Each Group B service is either always-on (per its flag) or always-off; comparing effect sizes is a separate evaluation step, not part of the implementation.
- **No automatic flag-flipping.** Operators flip every Phase 5 flag manually. The closed-loop self-tuning pattern from `ExplorationTuningService` is *not* duplicated here.

---

## Recommended timing (as of 2026-05-12)

| Wave | Earliest start | Latest reasonable start |
|---|---|---|
| Wave 0 (diagnostics) | Next fresh session | This week |
| Wave 1 (A2 + A3 + A5) | After Wave 0 lands | Within two sessions of Wave 0 — the acceptance-rate plateau has signal to spend |
| Wave 2 (A1 + A4) | Same session as Wave 1 if time permits | Open-ended |
| Wave 3 (C3) | After Wave 1 | Open-ended |
| Wave 4 (B1 → B5) | After Wave 3 | One per session, pattern matches Phase 2.5 |
| Wave 5 (C1 + C2) | After ≥ 1 week of Phase 4b + Wave 4 runtime | Open-ended |
| Wave 6 (D1, D2 scoping docs) | After Wave 5 | Open-ended |

The cadence Phase 4b followed (small commits shipping in a single session) applies to Waves 0–4. Waves 5–6 deserve more deliberation and review.

---

## Pre-conditions for the validators (Group B)

By analogy with Phase 4c Piece 3, Group B should not ship until *all of*:

1. Wave 0 (C4, C5) shipped — the simulator is pinned by the closed-form regression suite.
2. Wave 1 (A2, A3, A5) has been on in a sandbox room for ≥ 1 week and the resulting acceptance-rate trough has stabilised.
3. C3 (call-path surfacing) shipped — so when a Group B validator rejects a paper, the structured reason is queryable.
4. A fresh refresh of [REVIEW_REPORT.md](../research/a3289005-archive/REVIEW_REPORT.md) confirms the top failure-mode counts have dropped on the corpus since Wave 1 landed.

If any of these is missing, defer Group B even if Group A is complete.

---

## Pointers for the next session

When picking this up, the things to re-read are:

- [STATUS.md](STATUS.md) — what's shipped vs what's deferred. Phase 5 sits alongside the open Phase 4c Piece 3.
- [REVIEW_REPORT.md](../research/a3289005-archive/REVIEW_REPORT.md) — the failure-mode source-of-truth. Refresh it before starting each wave to keep the catalogue current.
- [phase-4c-prompt-template-evolution.md](phase-4c-prompt-template-evolution.md) — the pattern this doc follows (memo → refactor → behaviour) and the reason its Piece 3 sits behind Phase 5 in the ordering.
- [src/main/java/com/emergentterrain/service/ResearchNarrationService.java](../src/main/java/com/emergentterrain/service/ResearchNarrationService.java) — the call-path emitter that A2, A4, C3 all touch.
- [src/main/java/com/emergentterrain/service/MathToolSuite.java](../src/main/java/com/emergentterrain/service/MathToolSuite.java) — the 4b sandbox whitelist (A3) and sandbox-reject log line (C3).
- [src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java](../src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java) — the `(id, version)` keying convention A5 uses.
- [src/main/java/com/emergentterrain/research/ResearchPaper.java](../src/main/java/com/emergentterrain/research/ResearchPaper.java) — the status-field setter the DISPUTED flow already uses; A2 + C2 reuse the same narrow-setter pattern.
- [src/main/java/com/emergentterrain/service/TileValidatorService.java](../src/main/java/com/emergentterrain/service/TileValidatorService.java) — the small-pure-service shape Group B should imitate.
- [src/main/java/com/emergentterrain/research/SurpriseBroadcastService.java](../src/main/java/com/emergentterrain/research/SurpriseBroadcastService.java) — the simplest flag-gated per-room service C2 can imitate.
