# Phase 23 — Conjecture-Driven Novelty for Paper Writing

**Status (2026-05-21):** All Phase 23 code slices shipped — 23.1J
(Java miner + persistence + scheduler), 23.2 (OEIS gate), 23.3
(priority queue), 23.4/4b (PaperKind.CONJECTURE_INVESTIGATION
foundation + wiring + cycle branch), 23.5 (`verify_conjecture`
tool), and 23.6 (EvidenceLabel + LabelFloor + ProposedLabelParser +
reviewer-panel emission + conjecture-paper prompt template).
Architecture pivot 2026-05-21 mid-implementation moved the runtime
into the Spring app (in-process Java miner, DB-backed conjecture
store), not the Python+JSON-roundtrip path originally sketched. The
Python prototype stays in the tree as a working reference for the
seed/refute/tighten mechanics but is not on the runtime path. See
"Architecture (post-pivot)" section below.

**Remaining to close Phase 23:** 23.7 (physics + materials miners,
P2 — deferred until math path produces papers worth showing) and
23.8 (operator calibration check — one-afternoon human task on 20
high-novelty papers). The acceptance bar (20-paper sample with
median novelty > 0.3 AND OEIS-rediscovery rate < 10%) is observable
only via a corpus run + 23.8 calibration; all *code* needed to
produce such a sample is now in place.

Motivated by the persistent `novelty=0` ratings in `research/a3289005/`
paper outputs: the panel is correctly identifying that hypotheses pulled from the fixed
`(domain, topic)` catalog in `BotGenome.topicWeights` are not novel. The
catalog is the bottleneck — not the LLM, not the reviewers, not the
prompts. Fix the catalog and novelty has a chance to move off zero.

The mechanism: feed paper writing from a **conjecture miner** that surfaces
candidate hypotheses by mining the bots' existing research substrates
(number-theoretic predicates, physical-law parameter sweeps, materials
property tables). The miner's machinery is the same seed/refute/tighten
pattern shipped under `scripts/graffiti/` (commits `c383a0c`, `414d3f5`)
but pointed at **external domain data**, not at room/government state.

**This is a paper-system extension. Room state, simulation tick, government
trend, bots-as-citizens — none of that is involved.** A separate,
independent capability (call it "ET as instrument," self-referential
papers about the simulation itself) is *not* in scope for Phase 23 and is
not a feature reuse — it would be a new product category with different
prompts, different reviewers, and a different evaluation rubric.

---

## Summary of the direction

The arc, as we landed it:

1. **The real bottleneck isn't the LLM, it's the catalog.** Papers come
   up with `novelty=0` because `BotGenome.topicWeights[0..4]` samples
   from a fixed `(domain, topic)` catalog. The reviewers correctly
   recognize the catalog topics as not novel. No prompt tuning or
   reviewer rewrite fixes this — the input is the problem.

2. **Conjecture mining is the fix.** Reuse the Graffiti pattern shipped
   at `scripts/graffiti/` — seed inequalities, refute on counterexamples,
   tighten on confirmations, drop knob-restatements. The engine
   (`conjectures.py`) is generic; only the *substrate* changes per
   domain.

3. **Mine domain data, not room state.** Paper writing is independent of
   ET's simulation tick. The miner consumes number-theoretic predicates,
   physics parameter sweeps, materials property tables — the same
   external world the paper system already operates on. The existing
   `scripts/graffiti/` harness (which mines room state for ET-self-study)
   stays separate; conflating the two paths was an earlier confusion.

4. **Novelty checking ≠ proof; both matter but differently.** Novelty
   is cheap to start (OEIS auto-check for math, costs nothing). Full
   literature retrieval (arxiv vector search) is deferrable until a
   one-afternoon calibration check shows whether the implicit-LLM
   novelty judgment is actually failing. Proof is much harder — no
   current automated theorem prover can handle novel Graffiti-style
   conjectures — so the bar stays "empirical conjecture with mechanism
   + adversarial check" and the `THEOREM` label is reserved but
   unreachable until a separate proof slice is built.

5. **Honest labeling earns back most of the credibility.**
   Distinguishing `THEOREM` / `EMPIRICAL_RESULT` / `CONJECTURE` /
   `UNEXPLAINED_OBSERVATION` and letting reviewers refuse the higher
   labels without the evidence is essentially free, and fixes most of
   the dishonest-packaging risk without solving the deeper proof gap.

6. **What's deliberately out of scope.** Formal proof, vocabulary
   expansion (bots proposing their own derived scalars), anomaly
   mining, and the "ET as instrument" self-referential papers. Each is
   a real open problem; none is bundled into Phase 23.

---

## Architecture (post-pivot, 2026-05-21)

The runtime is in-process Java/Spring, not Python+JSON. The Python
prototype at `scripts/conjecture-miner/math/` (commit `ddfe262`) stays
in the tree as a working reference for the seed/refute/tighten engine
but is not part of the runtime. No periodic Python jobs, no JSON files
on disk in `research/conjectures-{domain}.json`, no `ConjectureSource`
bean that reads JSON.

The Java implementation:

| Concern | Java placement |
|---|---|
| Engine — `Inequality`, `seed`, `update`, novelty/Spearman filters | New `com.emergentterrain.research.conjecture` package |
| Math substrate — number-theoretic predicates + per-window evaluator | New `MathConjectureSubstrate` bean; can reuse `MathToolSuite` predicates where they exist |
| Math vocabulary — named scalars + DERIVED combinations | New `MathInvariants` constant class |
| Periodic miner | New `ConjectureMinerService` (`@Scheduled` on `app.research.conjectureMiner.intervalMs`) |
| Persistence | New `conjectures` Postgres table + JPA `ConjectureRepository` (durable across restarts, unlike the Python store.json) |
| Conjecture queue exposed to paper writing | `ConjectureMinerService.next(domain)` — same API surface as the originally-planned `ConjectureSource`, just not reading JSON |
| OEIS annotation (was 23.2 Python `oeis_filter.py`) | Constant map `OeisCatalog` consulted at queue-build time; live verification deferred to an admin endpoint, not part of the hot path |
| Paper-writing integration (was 23.4/23.5) | New `PaperKind.CONJECTURE_INVESTIGATION`, new `verify_conjecture` tool in `MathToolSuite` |

**Why the pivot pays off**: no JSON round-trip means the miner can hand
out conjectures the same tick they're refuted/promoted; the audit trail
(witness records, knob-filter drops) lives in Postgres alongside papers
and government history, queryable by the existing infrastructure;
operator-side, there's no separate cron job to schedule outside Spring.

**Status of the Python prototype**:
- `scripts/conjecture-miner/math/substrate.py` — informs the Java
  predicate implementations but isn't called by the app
- `scripts/conjecture-miner/math/invariants.py` — informs the Java
  vocabulary; the BASE/DERIVED layout transfers directly
- `scripts/conjecture-miner/math/run.py` — exercise / smoke tool only
- `scripts/graffiti/conjectures.py` — same; the seed/update/refute
  logic is the spec the Java port mirrors

Phase 23.7's "Path 2 (in-process Java bean)" option from the earlier
deferrals table is now the chosen path. Path 1 (offline Python +
nightly cron + JSON dropoff) is retired.

---

## Scope

What Phase 23 adds to the paper-writing pipeline:

| Today | After Phase 23 |
|---|---|
| Topic selection samples a `(domain, topic)` pair from a fixed catalog via `BotGenome.topicWeights[0..4]` | Topic selection optionally pulls from a domain-specific conjecture queue produced by the miner |
| Hypothesis is LLM-generated from `(domain, topic)`, often a non-sequitur stitch | Hypothesis IS the mined conjecture (an inequality, identity, or ratio), with the substrate it survived as evidence |
| `run_predicate_scan` tool computes one-shot numerics on the LLM's chosen parameters | New `verify_conjecture` tool checks the mined conjecture on a *held-out* slice of the substrate, returning held/refuted + witness |
| Novelty axis judged by LLM panel implicitly against training-data prior | OEIS auto-check for math-domain conjectures before they enter the queue (kills the most embarrassing rediscoveries); panel novelty still LLM-judged for v1 |
| Paper labeling implicit (everything is a "paper") | Explicit labels: `THEOREM` / `EMPIRICAL_RESULT` / `CONJECTURE` / `UNEXPLAINED_OBSERVATION`; the panel can refuse a higher label without the evidence |

What Phase 23 explicitly does **not** add:

- Formal proof. No Lean integration, no AlphaProof, no theorem prover. The
  bar for math papers stays "empirical conjecture with mechanism" — the
  `THEOREM` label is reserved but unreachable until a proof slice is built.
- Full literature retrieval (arxiv vector search, citation-graph crawl).
  OEIS only for v1; the rest is deferred until calibration shows it's
  load-bearing.
- ET-state mining for paper hypotheses (the "ET as instrument" path). The
  `scripts/graffiti/` harness keeps pointing at `/room/{id}/government-trend`;
  this phase doesn't touch that.

---

## Architecture

```
                  Phase 23 paper pipeline (domain-data path)
+--------------------------------------------------------------+
|                                                              |
|  scripts/conjecture-miner/{math,physics,materials}/          |
|    substrate.py    predicate / law / table evaluators        |
|    invariants.py   per-domain scalar vocabulary              |
|    conjectures.py  (shared) seed/refute/tighten engine ──┐   |
|    run.py          batch CLI: dumps JSON to research/    │   |
|                                                          ▼   |
|                                  research/conjectures-{d}.json
|                                                          │   |
|  +------------------+                                    │   |
|  | ConjectureSource | <─── reads JSON every researchIntervalMs
|  | (new Spring bean)|                                    │   |
|  +------------------+                                    │   |
|         │                                                │   |
|         ▼                                                │   |
|  +----------------------+                                │   |
|  | UniversityService    |                                │   |
|  | (existing, modified) |                                │   |
|  +----------------------+                                │   |
|         │                                                │   |
|         │ topic selection: w5 weight or override         │   |
|         ▼                                                │   |
|  +----------------------+                                │   |
|  | Researcher (LLM)     |                                │   |
|  | - PaperKind.CONJECTURE_INVESTIGATION                  │   |
|  | - tool: verify_conjecture                             │   |
|  | - prompt: mechanism + adversarial check required      │   |
|  +----------------------+                                │   |
|         │                                                │   |
|         ▼                                                │   |
|  +----------------------+                                │   |
|  | Reviewer panel       |                                │   |
|  | + label_floor logic  |                                │   |
|  +----------------------+                                │   |
|                                                              |
+--------------------------------------------------------------+
```

**Pattern reuse claim.** `conjectures.py` from `scripts/graffiti/`
(Inequality + seed/update/refute/promote + Spearman knob filter + novelty
filter) is **entirely reusable** — no changes. The engine is generic over
"sequence of named-scalar samples." Only `substrate.py` and
`invariants.py` change per domain. This means the *miner* slice is small
work; the bulk of Phase 23 is the *integration* slice (Java side).

---

## Pieces

### 23.1 — Math-domain miner — Python prototype ✅ shipped 2026-05-21

**Status: prototype reference only after the 2026-05-21 architecture
pivot.** Originally the runtime; now serves as a working spec the Java
port mirrors. Lives at `scripts/conjecture-miner/math/` (commit
`ddfe262`). Acceptance was met before the pivot: 106 surviving
conjectures, 5 of the top 6 pure-bound survivors correspond to known
results (abundant density ≈ 0.2474, happy density ≈ 0.150, etc.).

### 23.1J — Math-domain miner — Java port [P0, post-pivot]

The runtime version. Goes into `com.emergentterrain.research.conjecture`:

- `Inequality.java` — record (lhs, rhs, k, c, confirmedRuns,
  confirmedConfigs, refutedBy, p99Slack, slackByConfig, droppedByKnob).
  Mirror the Python dataclass.
- `ConjectureEngine.java` — port of `conjectures.py`: `seed()`,
  `update()`, novelty filter (|corr| > 0.98), Spearman knob filter.
- `MathConjectureSubstrate.java` — port of `substrate.py`: SPF table +
  per-window evaluator. Where `MathToolSuite` already exposes a
  predicate (e.g., `isPrime`), reuse it; otherwise add to substrate.
- `MathInvariants.java` — BASE list + DERIVED lambdas (via
  `Function<Map<String,Double>,Double>` or similar).
- `Conjecture` JPA entity + `ConjectureRepository` — durable Postgres
  store (the prototype's `store.json` becomes the `conjectures` table).
- `ConjectureMinerService.java` — `@Scheduled` orchestrator;
  configurable interval via `app.research.conjectureMiner.intervalMs`,
  default off (enabled flag) so the JVM startup is unaffected until the
  operator opts in.
- **Acceptance:** with the flag on, a fresh app produces ≥ 50 surviving
  conjectures in the DB after one mine pass. Spot-check that the
  pure-bound survivors match the Python prototype's output to within
  rounding (validates the port).

### 23.2 — OEIS gate [P0, Java; post-pivot]

OEIS is the canonical reference for integer sequences. Each math
conjecture's lhs/rhs gets mapped to canonical OEIS A-numbers via a
hand-curated `OeisCatalog` constant inside the conjecture package. The
mapping is annotated onto the `Conjecture` entity (column
`oeis_match_json`) at queue-build time, not at mine time, so the
mapping table can be edited without re-mining.

The annotation makes A-numbers available to paper-writing prompts so
papers can cite OEIS references and the system can detect when a
conjecture's hypothesis plausibly restates a known OEIS formula.

- `OeisCatalog.java` — constant `Map<String, List<OeisEntry>>` from our
  scalar name (e.g., `abundant_density`) to A-numbers (e.g., `A005101`).
  Initial entries seeded from the Python prototype's `PREDICATE_OEIS`
  (verified offline 2026-05-21).
- Optional admin endpoint `/admin/oeis/verify` that hits oeis.org to
  confirm each cited A-number exists with the expected name — for
  catalog maintenance, not the hot path.
- **Acceptance:** with the math miner enabled and producing ≥ 50
  conjectures, at least 20 carry a non-empty `oeis_match_json`. Spot-
  check 5 — confirm the cited A-numbers correspond to the right
  sequences via the admin endpoint.

**APIs in scope for Phase 23 literature search:** OEIS only. The broader
candidate list was discussed and explicitly deferred — see table below.

| API | What it covers | Phase 23 status |
|---|---|---|
| **OEIS** (oeis.org) | Every published integer sequence with formulas, references, related sequences | ✅ In scope (this piece) |
| **arxiv API** | Abstract + metadata search across all of arxiv | 🚫 Deferred — full literature retrieval |
| **Semantic Scholar API** | Cross-publisher abstracts, citation graphs, OA fulltext | 🚫 Deferred |
| **OpenAlex** | Open metadata snapshot of all academic publishing | 🚫 Deferred |
| **Embedding-based local vector search** | Pre-embed arxiv abstracts once, then nearest-neighbor at paper-write time | 🚫 Deferred |

The gating decision for the deferred APIs is the **calibration check
(23.8)**: if a 20-paper sample under Phase 23 shows a novelty
false-positive rate < 30% using just OEIS + the implicit LLM judgment,
the rich-end APIs stay deferred indefinitely. If it's > 30%, those APIs
become Phase 24. The honest position is that Phase 23 ships OEIS as the
literature gate and decides empirically whether anything more is needed.

### 23.3 — Conjecture queue [P1, post-pivot: merged into miner]

The originally-planned `ConjectureSource` that reads JSON dropoffs is
no longer needed since the miner is in-process. The queue API surface
moves to `ConjectureMinerService.next(domain)`. Priorities unchanged:

- recently refuted (counterexample paper material)
- tightened-across-many-configs survivors (strong-claim paper material)
- high-confirmation but tight-slack (boundary paper material)

- **Acceptance:** end-to-end test with a seeded DB returns conjectures
  in the expected priority order via `ConjectureMinerService.next("math")`.

### 23.4 — `PaperKind.CONJECTURE_INVESTIGATION` [P1, foundation + infrastructure shipped 2026-05-21]

**Status (post-pivot, three slices):**

- **23.4 foundation** (commit `073f86b`, shipped) — enum value, queue
  interface, topic-provider bean, paperShare config flag.
- **23.4 infrastructure wiring** (this commit, shipped) — `PaperDraft.kind`
  threaded through with a 27-arg backwards-compat constructor so the
  18 existing call sites are untouched; `UniversityService.addPaper`
  copies `draft.kind()` onto `paper.setKind()`; `ResearchCycleService`
  gains a setter for `ConjectureTopicProvider`. **No behavioral change
  yet** — the topic provider isn't called from the cycle.
- **23.4b actual branch** (shipped 2026-05-21) — call
  `selectConjecture()` at the top of `runCycle`; when it fires,
  `publishConjectureInvestigation` synthesizes hypothesis from the
  conjecture's inequality, findings from the conjecture's mining
  history (confirmedRuns, p99Slack, OEIS annotation), and tags the
  PaperDraft with `PaperKind.CONJECTURE_INVESTIGATION`. Bypasses
  `MathPhysicsEngine.compute` and peer review entirely on the
  conjecture path — the conjecture's peer-review-equivalent is its
  existing miner track record. `peerScore` is derived from p99Slack
  (tighter = higher, saturated at [0.3, 1.0]). The empty reviewer list
  signals "miner-validated, no human panel" for v1. ConjectureTopicProvider
  setter is `@Autowired(required = false)` so Spring auto-wires it when
  the foundation bean exists; UniversityService is untouched.

Design departure from the original phase doc: instead of adding a 6th
genome weight `w5`, the conjecture-driven share is config-driven
(`paperShare` in [0,1]). Reasons: `BotGenome.topicWeights[5]` is
evolution-critical (crossover, mutation, serialization) and adding a
6th slot risks breaking the saved-genome migration. Config flag has
the same semantic effect (probability per cycle) without touching the
evolution path. If per-bot variance turns out to matter, the genome
weight can be added later as a multiplicative modifier on the flag.



- New enum value in `PaperKind.java`.
- New prompt template that frames the LLM's job as: "Given conjecture C
  with witnesses W, write an investigation. Mandatory sections:
  Hypothesis (state C verbatim), Mechanism (propose *why* C should hold —
  structural argument, not paraphrase), Adversarial Check (use
  `verify_conjecture` on a held-out slice), Conclusion (label your result
  with one of THEOREM/EMPIRICAL_RESULT/CONJECTURE/UNEXPLAINED_OBSERVATION
  and justify the label)."
- Bot topic selection adds an optional override: with probability `w5`
  (new genome weight, default 0.0 so existing bots are unchanged), the
  bot pulls from `ConjectureSource.next(domain)` instead of sampling the
  catalog.
- Genome serialization handles `w5` absent (treated as 0) for backward
  compat with bots saved before Phase 23.
- **Acceptance:** with `w5=1.0` for one bot, that bot writes one paper
  whose hypothesis matches a conjecture in `ConjectureSource`. Mechanism
  section is non-empty. Adversarial Check section cites at least one
  `verify_conjecture` call.

### 23.5 — `verify_conjecture` tool [P1, shipped 2026-05-21]

**Status:** shipped. Tool lives in
`com.emergentterrain.research.conjecture.ConjectureVerifier` (Spring
@Component, lazy-builds the SPF sieve on first call so deployments with
the miner disabled don't pay the startup cost). Wired into
`MathToolSuite` through a fifth optional constructor parameter; absent
by default so existing tests stay on the prior tool set and the JSON
schema is only advertised when a verifier is wired.

The dispatcher path:
`MathToolSuite.buildExecutor → "verify_conjecture" case → verifyConjecture(args) → ConjectureVerifier.verify`.

Acceptance (per the phase doc):
  - "Tool returns deterministic results on a fixed seed" — pinned by
    `ConjectureVerifierTest.deterministicForFixedWindow` (same input →
    bit-identical output, every per-window report equal).
  - "Held-out slice is genuinely held out (no overlap with the slice
    the miner saw)" — tests evaluate at offsets 200_000, 300_000,
    1_200_000, which are outside the Python prototype's set
    {100, 1k, 5k, 10k, 50k, 100k, 500k, 1M}.

Tests: 12 across `ConjectureVerifierTest` (6 unit) and
`MathToolSuiteVerifyConjectureTest` (6 dispatcher integration).
Verifier covers held bound, refuted bound, determinism, worst-margin
correctness, bad inputs, default num_windows. Dispatcher covers
absent-vs-wired advertising, held/refuted formatting, not-configured
error path, invalid-args clean error.

Original-doc body (preserved for reference):


- New tool in `MathToolSuite` (and physics/materials equivalents in their
  respective tool suites once those exist) that takes a conjecture
  identifier and a held-out parameter slice, runs the substrate
  evaluator, returns hold/refute + worst-margin witness.
- Reuses the substrate code from 23.1, not the miner — verification is
  cheaper than mining.
- **Acceptance:** tool returns deterministic results on a fixed seed.
  Held-out slice is genuinely held out (no overlap with the slice the
  miner saw).

### 23.6 — Label discipline in the reviewer panel [P1, foundation shipped 2026-05-21]

**Status (split into two slices):**

- **23.6 foundation** (this commit, shipped) — new `EvidenceLabel` enum
  (`THEOREM` / `EMPIRICAL_RESULT` / `CONJECTURE` / `UNEXPLAINED_OBSERVATION`)
  with persisted `evidenceLabel` field on `ResearchPaper`, defaulting to
  `CONJECTURE` so every paper starts at the honest floor. The reviewer
  panel can opt into a higher label only when supporting evidence is
  present; the field's `setEvidenceLabel(null)` coerces back to
  `CONJECTURE` so a row can never land NULL. Phase 23 acceptance "none
  are mis-labeled THEOREM without proof structure" is met by
  construction: the default is CONJECTURE, and reviewers must explicitly
  raise the label.

- **23.6 floor + persist-time wiring** (shipped 2026-05-21) — new
  `LabelFloor` utility examines the paper's full corpus
  (hypothesis + findings + conclusion + discussion) for proof markers
  ("Q.E.D.", "∎", "## Proof"), mechanism articulation, and adversarial
  check (the literal "verify_conjecture" tool-trace string counts).
  `LabelFloor.demote(proposed, paper)` returns the weaker of (proposed,
  ceiling) so a reviewer can't over-claim. Wired into
  `UniversityService.publishPaper` after all narration text is set; v1
  acts as a no-op pass-through because no upstream proposes labels above
  CONJECTURE yet. Test coverage: 10 cases pinning each branch + null
  inputs + ordinal ordering. The wiring is structural — the floor
  starts demoting the moment any future caller proposes a higher label.

- **23.6 parser** (shipped 2026-05-21) —
  `ProposedLabelParser.parse(llmResponse)` extracts an
  `Optional<EvidenceLabel>` from arbitrary LLM-output text. Recognized
  patterns: `EvidenceLabel: X`, `Label: X`, `evidence_label = X`,
  JSON-style `"label": "X"`, section-header `## Evidence Label\n\nX`.
  Hyphens normalized to underscores. Ambiguous (two distinct labels)
  returns empty so downstream falls back to the floor's CONJECTURE
  default. 9 tests pin every branch. Pure logic — no LLM dependency,
  no `ResearchNarrationService` change. The future prompt-emission
  slice uses this as: `parse(llmResponse).ifPresent(paper::setEvidenceLabel)`
  before {@code LabelFloor.demote} runs.

- **23.6d reviewer-panel emission + conjecture-paper prompt template**
  (shipped 2026-05-21, three commits) — three slices landed in sequence:

  - **Foundation** (`a9b60df`): `PanelReview` + `PanelOutcome` carry
    `Optional<EvidenceLabel> proposedLabel` / `proposedEvidenceLabel`.
    Per-reviewer parsing reads the JSON's new `evidence_label` field;
    the aggregator takes the median ordinal across voters with the
    weaker label winning even-N ties (the stronger claim must be earned
    by ≥⌈N/2⌉ reviewers, mirroring the LabelFloor's spirit). Legacy
    6-arg / 7-arg ctors preserved as convenience overloads so existing
    callers stay untouched.

  - **Panel emission** (`a1921f9`): `NARRATION_PANEL_REVIEW` template
    bumped v1 → `v2-with-evidence-label`; body adds per-label refusal
    rules (THEOREM only with Q.E.D./∎/## Proof; EMPIRICAL_RESULT only
    with mechanism + adversarial check) and the
    `"evidence_label":"CONJECTURE"` JSON field.
    `UniversityService.publishPaper` wires
    `outcome.proposedEvidenceLabel().ifPresent(paper::setEvidenceLabel)`
    BEFORE the existing `LabelFloor.demote` call, so the floor
    validates the panel's claim against the paper text. Empty proposal
    (no voter, panel disabled, legacy reviewer) leaves the paper's
    default `CONJECTURE` intact.

  - **Conjecture template** (`b6a04f8`): new
    `NARRATION_CONJECTURE_INVESTIGATION` prompt that instructs the
    narrator to emit `## Mechanism` + `## Adversarial Check` sections
    with the verbatim `verify_conjecture` token — the exact strings the
    LabelFloor's text heuristics look for, so an EMPIRICAL_RESULT label
    proposal from the panel can survive the floor on this path.
    `ResearchNarrationService.buildNarrationPrompt` branches on
    `draft.kind()` at the top; `activeNarrationTemplateId/Version(draft)`
    now report the conjecture id+version so `NarrationResult` records
    the right provenance.

  Tests: `PanelToFloorIntegrationTest` (7 cases pinning the
  panel→outcome→setEvidenceLabel→LabelFloor handshake), 11 new cases
  across `PanelReviewTest` + `PanelOutcomeTest`, 3 new cases in
  `PromptTemplateRegistryTest` (template registration + slot
  interpolation + marker-token presence). 132/132 green across all
  touched-area tests.

Original 23.6 body (preserved):


- Reviewers receive a new instruction: refuse `THEOREM` without formal
  proof structure; refuse `EMPIRICAL_RESULT` without mechanism + adversarial
  check; default to `CONJECTURE` or `UNEXPLAINED_OBSERVATION` otherwise.
- The audit chain (already extensible — see the `LLM Call Path` lines in
  existing papers) records the label decision and which evidence sections
  drove it.
- **Acceptance:** spot check 20 papers produced under Phase 23. At least
  16 carry a label other than `THEOREM`. None are mis-labeled `THEOREM`
  without proof structure.

### 23.7 — Physics + materials miners [P2, after 23.1–23.6 stabilize]

Replicate 23.1 for physics (parameter sweeps over RLC, Wien, Snell) and
materials (Hall-Petch, density-vs-atomic-mass, modulus scaling). Same
engine, different substrate. Defer until math path produces papers worth
showing.

### 23.8 — Calibration check (operational, not a code piece) [P0 after 23.1–23.6]

Pick 20 papers produced under Phase 23 with novelty ≥ 0.7 per the panel.
Spend an hour each Googling / arxiv-searching to verify whether the
hypothesis is actually novel. Record the false-positive rate.

- If FP rate < 30%: the implicit-LLM novelty judgment is acceptable; defer
  vector-search literature retrieval indefinitely.
- If FP rate > 30%: vector-search retrieval becomes Phase 24.

This is not a code task; it's a one-afternoon human task whose output
determines whether 24 is worth building.

---

## Explicit deferrals

| Item | Why deferred |
|---|---|
| Formal proof (Lean/AlphaProof) | Current SOTA can't prove novel Graffiti-style inequalities. Out of reach. |
| Full literature retrieval (arxiv vector search) | Wait for the calibration check (23.8) to show it's load-bearing. |
| ET state-mining for paper hypotheses ("ET as instrument") | Different product category; conflating it with Phase 23 was an earlier confusion. Tracked separately if ever pursued. |
| Vocabulary expansion (bots proposing new derived scalars) | Touches "beyond human-level" territory — out of scope. The vocabulary in each domain miner is human-curated for Phase 23. |
| Anomaly mining (single-point violations of strong conjectures) | Separate machinery from regularity mining; would be Phase 25+. |

---

## Open questions

1. **Where does the math miner get its window choices and predicate
   pairs?** Hand-curated for v1 (matches the existing catalog approach
   inside the bots). Long-term we could let bots propose mining
   parameters — but that's vocabulary expansion, which is deferred.
2. **How often does the miner run?** Nightly cron for v1; reactive (after
   the queue empties) for v2.
3. **What happens when a mined conjecture is consumed by paper writing —
   is it removed from the queue, or can multiple bots independently
   investigate the same one?** Default to removal-on-consumption to avoid
   redundant papers; allow override for high-confidence conjectures where
   independent investigation is itself interesting.
4. **Does the OEIS gate apply at miner time or at paper-write time?**
   Miner time. Annotate-and-demote rather than delete, so the panel still
   sees the OEIS match in the audit chain.

---

## Acceptance for Phase 23 (rollup, 2026-05-21)

All code slices shipped (16 commits + ~46 tests across the label +
conjecture + cycle + panel-emission dimensions):

| Slice | Status | Commit |
|---|---|---|
| 23.1 Python prototype | ✅ reference only post-pivot | `ddfe262` |
| 23.1J engine + substrate | ✅ | `7cb6031` |
| 23.1J persistence (Conjecture entity + repo + mapper) | ✅ | `dac6bbb` |
| 23.1J `ConjectureMinerService` @Scheduled | ✅ | `d8f27a8` |
| 23.2 OEIS catalog + annotator | ✅ | `fe88cf0` |
| 23.3 priority queue API | ✅ | `cf6bbdf` |
| 23.4 foundation (enum + topic provider) | ✅ | `073f86b` |
| 23.4 wiring infra (PaperDraft.kind threading) | ✅ | `1ca172d` |
| 23.4b cycle branch (`publishConjectureInvestigation`) | ✅ | `6995e4d` |
| 23.5 `verify_conjecture` tool | ✅ | `b29e011` |
| 23.6 foundation (`EvidenceLabel` + persisted column) | ✅ | `8e75695` |
| 23.6 floor + persist-time wiring | ✅ | `d8296a8` |
| 23.6c proposed-label parser | ✅ | `2449326` |
| 23.6d foundation (PanelReview/PanelOutcome carry proposed label) | ✅ | `a9b60df` |
| 23.6d panel emission (template v2 + `publishPaper` wiring) | ✅ | `a1921f9` |
| 23.6d conjecture template + narration kind-branch | ✅ | `b6a04f8` |

Deferred (clearly scoped, not started):

- 23.7 physics + materials substrates — P2 per the original phase
  doc; defer until the math path produces papers worth showing.
- 23.8 calibration check — operational human task (pick 20 high-
  novelty papers, hand-verify novelty), not a code piece.

Phase 23 closes when a 20-paper sample produced under it has *both*
median novelty > 0.3 (vs. ~0 today) AND OEIS-rediscovery rate < 10%.
Either failure mode keeps the phase open. As of this session, all
infrastructure to *produce* such a sample is in place — the label
flow runs end-to-end (panel proposes → outcome aggregates → publishPaper
sets → LabelFloor validates); the conjecture-investigation narration
path produces the Mechanism + Adversarial Check prose the floor needs
to admit EMPIRICAL_RESULT. The calibration itself (23.8) is the final
remaining step.

---

## What this is and isn't

**Is:** a structural fix to the catalog bottleneck. Reuses the
Graffiti pattern (already shipped in `scripts/graffiti/`) by changing
*what data the miner consumes*. Honest labeling means the system can
produce credible empirical research output without claiming to produce
theorems.

**Isn't:** a path to "human-level research" or "AI scientist." It moves
novelty from zero to something measurable; it doesn't solve mechanism,
vocabulary expansion, or proof. Those remain real open problems and are
honestly out of scope.
