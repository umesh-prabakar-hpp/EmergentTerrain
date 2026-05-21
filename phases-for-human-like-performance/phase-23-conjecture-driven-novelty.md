# Phase 23 — Conjecture-Driven Novelty for Paper Writing

**Status (2026-05-21):** Scoping + Python prototype shipped (23.1 in
`scripts/conjecture-miner/math/`, commit `ddfe262`). Architecture pivot
2026-05-21 mid-implementation: the runtime moves into the Spring app
(in-process Java miner, DB-backed conjecture store), not the
Python+JSON-roundtrip path originally sketched. The Python prototype
stays in the tree as a working reference for the seed/refute/tighten
mechanics but is not on the runtime path. See "Architecture (post-pivot)"
section below.

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

### 23.4 — `PaperKind.CONJECTURE_INVESTIGATION` [P1, foundation shipped 2026-05-21]

**Status:** Foundation shipped (`PaperKind.CONJECTURE_INVESTIGATION`
enum, `ConjectureQueue` interface, `ConjectureTopicProvider` bean +
`app.research.conjectureMiner.paperShare` flag). Upstream wiring into
`ResearchCycleService.runStandardCycle()` is deferred to 23.5 — the
full acceptance (hypothesis = conjecture verbatim, Mechanism section,
Adversarial Check citing `verify_conjecture`) needs the tool that 23.5
ships. The bean wiring lets 23.5 land as a one-line caller.

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

### 23.5 — `verify_conjecture` tool [P1]

- New tool in `MathToolSuite` (and physics/materials equivalents in their
  respective tool suites once those exist) that takes a conjecture
  identifier and a held-out parameter slice, runs the substrate
  evaluator, returns hold/refute + worst-margin witness.
- Reuses the substrate code from 23.1, not the miner — verification is
  cheaper than mining.
- **Acceptance:** tool returns deterministic results on a fixed seed.
  Held-out slice is genuinely held out (no overlap with the slice the
  miner saw).

### 23.6 — Label discipline in the reviewer panel [P1]

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

## Acceptance for Phase 23 (rollup)

- 23.1 (Python prototype) ✅ shipped 2026-05-21 as reference; commit
  `ddfe262`. Not on the runtime path post-pivot.
- 23.1J + 23.2 (Java miner + OEIS catalog) shipped; produces a queue
  in the DB.
- 23.3 + 23.4 + 23.5 (queue API + new PaperKind + verify tool)
  shipped; one bot configured with `w5=1.0` writes at least one paper
  from the queue, with non-empty Mechanism and Adversarial Check.
- 23.6 (label discipline) shipped; spot-check confirms reviewers respect
  the labels.
- 23.8 (calibration) executed; result recorded here.

Phase 23 closes when a 20-paper sample produced under it has *both*
median novelty > 0.3 (vs. ~0 today) AND OEIS-rediscovery rate < 10%.
Either failure mode keeps the phase open.

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
