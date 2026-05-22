# Phase 27 — Conjecturing (Sage) Batch Generalisation

**Status (2026-05-22):** DRAFT. Tier-2 / **gated on Phase 23.8
calibration**. Do not start until the Phase 23.8 operator
calibration check on a 20-paper math-conjecture sample reports
median novelty > 0.3 AND OEIS-rediscovery rate < 10%. If the
in-process Java miner clears that bar, this phase becomes the
right next step. If it doesn't, the problem is elsewhere and
adding a richer engine won't help.

**Motivation.** The Phase 23 Java miner (`ConjectureMinerService` +
`MathConjectureSubstrate` + `MathInvariants`) implements the
Dalmatian heuristic over a curated set of number-theoretic
invariants. It works and it shipped, but its invariant pool is
hand-coded and narrow — about a dozen scalars (`tau`, `sigma`,
`phi`, prime-counting, etc.). The **Conjecturing** system by Larson
& Van Cleemput (github.com/nvcleemp/conjecturing) generalises the
same Dalmatian heuristic to arbitrary `(object, invariant)` tuples
in SageMath, and ships with the companion
`math1um/objects-invariants-properties` data repo containing
hundreds of pre-defined invariants on graphs, integers, polytopes,
and more. Per `reference-conjecture-engines.md`, this is "the
closest spiritual cousin that can be linked against and studied"
to our miner.

Phase 27 wires Conjecturing in as a **nightly batch generator**
that runs offline in Sage, dumps candidate inequalities into the
existing `conjectures` Postgres table, and lets the live Java
miner serve them through the existing queue. The hot path stays
Java; the wider invariant pool comes from Sage's catalog.

This is also the natural home for the **graph-theory** corner that
Phase 23 deferred — Graffiti.pc and AutoGraphiX are spiritually
close but closed-source. Conjecturing in Sage gives a working
graph-conjecture pipeline immediately.

---

## Scope

What Phase 27 adds:

| Today | After Phase 27 |
|---|---|
| Java miner runs continuously, mines `MathConjectureSubstrate` inequalities with ~12 hand-coded invariants | Java miner unchanged; **plus** a nightly Sage batch produces inequalities over the Conjecturing-shipped invariant pool (hundreds of invariants) and the graph-theory substrate |
| Conjecture queue serves math-domain inequalities only (number theory) | Queue serves math + graph-theory + (optionally) materials inequalities; PaperKind.CONJECTURE_INVESTIGATION can route to GRAPH papers |
| Adding a new invariant requires a Java class + redeploy | Adding an invariant on the Sage side requires a Python edit and the next nightly batch picks it up |
| The Python prototype in `scripts/conjecture-miner/math/` is reference-only | Replaced by a thin Sage-wrapping `scripts/conjecturing-batch/` directory; the math prototype either moves to `archive/` or stays as historical reference |

What Phase 27 explicitly does **not** add:

- A live Sage process running alongside the Spring app. Sage is
  heavy (~1.5 GB install, multi-second cold start); it runs as a
  cron-scheduled batch, not as a hot-path service.
- A new conjecture engine. Conjecturing **is** the Dalmatian
  heuristic; this is the same algorithm running over a wider pool,
  not a different algorithm.
- Custom-invariant authoring on the EmergentTerrain side. The
  invariant pool comes from `objects-invariants-properties`; we
  configure which subsets to include, not how they're computed.
- AutoGraphiX integration. AGX is closed binary-only. Conjecturing
  covers most of the graph-theory ground; AGX adds extremal-graph
  optimisation which is a separate (cluster-shaped) lane.

---

## Architecture

```
nightly cron / systemd timer
  └── scripts/conjecturing-batch/run.sh
         │
         ├── sage -python scripts/conjecturing-batch/run.py
         │      │
         │      ├── load invariants from objects-invariants-properties
         │      │   (subsets configured per domain — INTEGER, GRAPH, …)
         │      ├── load corpus (integers 1..N for math, graphs of
         │      │   order ≤ k for graph theory, …)
         │      ├── invoke nvcleemp/conjecturing's Dalmatian loop
         │      ├── emit JSON list of surviving inequalities:
         │      │     [{ domain, lhs, rhs, op, novelty_score,
         │      │        witness_corpus, invariant_pool_version,
         │      │        sage_version, ran_at }, ...]
         │      └── write to /tmp/conjecturing-batch-<date>.json
         │
         └── psql or a Spring admin endpoint ingests the JSON into
             the existing `conjectures` table with source=SAGE_BATCH
             and the same schema the Java miner uses.

EmergentTerrain (Spring Boot, unchanged hot path)
  └── ConjectureMinerService.next(domain)
         └── reads from `conjectures` table; both JAVA_MINER and
             SAGE_BATCH rows are eligible; the priority queue
             sorts by novelty_score regardless of source.
```

**Why a JSON dropoff, not direct DB write from Sage.** A Sage script
writing to Postgres is possible but couples the batch to the live
schema. JSON dropoff + a small Spring ingestion endpoint (or a
psql `\copy` call in the shell script) keeps the schema migration
authority in one place (Flyway in the Spring app). If the schema
changes, the ingestion endpoint translates; the Sage script
doesn't need to know.

**Conjecturing's invariant pool.** The companion data repo
(`math1um/objects-invariants-properties`, GPL-3.0) ships:

- ~200 integer invariants (divisor functions, prime-counting,
  multiplicative arithmetic functions, classical number-theoretic
  predicates)
- ~150 graph invariants (independence number, chromatic number,
  spectral radius, treewidth, matching number, ...)
- ~50 polytope invariants

Phase 27 configures **per-domain invariant subsets** in a YAML
file under `scripts/conjecturing-batch/configs/`. v1 ships two
configs: `integer-arithmetic.yml` (mirroring the existing Java
miner's pool, expanded to ~40 invariants) and
`graph-basic.yml` (~30 graph invariants covering the classical
domination/chromatic/spectral set). Materials/polytope configs are
parked behind a v2 flag pending Phase 23.7's materials substrate.

**Compute budget.** Conjecturing on the order-7 graph corpus
(~1044 graphs) with 30 invariants takes ~10 minutes on a single
core. Same engine on integers 1..10000 with 40 invariants takes
~30 minutes. The cron schedule is **once daily** by default; a
manual trigger via Spring admin endpoint exists for impatient
operators.

**Determinism.** Conjecturing is deterministic given a fixed corpus
and invariant ordering. The Sage script seeds the random
permutations explicitly so the same invariant pool produces the
same inequalities night to night. Changes appear only when the
invariant pool, the corpus, or the version pins change. This
matters because the conjecture queue's priority ordering relies on
stable conjecture identity across runs.

---

## Phasing

### 27.1 — `scripts/conjecturing-batch/` standalone Sage harness

Bring up Conjecturing as a standalone batch outside the Spring app.
Verify it produces conjectures end-to-end. **No EmergentTerrain
integration in this slice — pure batch acceptance.**

**Files (new directory `scripts/conjecturing-batch/`):**
- `requirements.md` — install instructions: SageMath ≥ 10.2,
  conjecturing repo cloned, `objects-invariants-properties` cloned,
  Python deps in the Sage environment.
- `run.py` — main Sage script: load invariants, load corpus, run
  Dalmatian, emit JSON.
- `configs/integer-arithmetic.yml` — invariant subset for the
  INTEGER substrate.
- `configs/graph-basic.yml` — invariant subset for GRAPH.
- `configs/schema.yml` — config-file schema (invariants list,
  corpus spec, operator list, novelty threshold, output path).
- `run.sh` — wrapper script that picks a config and runs the
  Sage entry point.
- `README.md` — install, run, output format.
- `test/` — golden inequalities (known true statements like
  `n + tau(n) ≥ phi(n) + 2` for n ≥ 1) that the batch should
  rediscover; pass = each appears in the output.

**Acceptance:** running `./run.sh configs/integer-arithmetic.yml`
produces a JSON file with ≥20 inequalities, ≥3 of which are the
known-true golden cases.

**Effort:** ~3 days. Most of the work is configuring Sage
correctly on Fedora (see `project_dev_shell_jdk_state` — Fedora 44
sometimes makes installs fiddly) and getting the Dalmatian
parameters tuned.

### 27.2 — Spring ingestion endpoint

`POST /admin/conjectures/ingest` (already-secured admin surface)
accepts the JSON output from 27.1 and inserts rows into the
existing `conjectures` table with `source = 'SAGE_BATCH'` and the
invariant-pool version pinned. De-duplicates against existing
SAGE_BATCH rows on a hash of `(domain, lhs, rhs, op,
invariant_pool_version)` so re-running the batch the next day
doesn't double-insert unchanged conjectures.

**Files:**
- `db/migration/V33__conjecture_source_column.sql` — add
  `source enum('JAVA_MINER', 'SAGE_BATCH')` column with default
  `JAVA_MINER` for back-compat.
- `src/main/java/com/emergentterrain/research/conjecture/ConjectureIngestionController.java`
  — admin REST endpoint. Auth via the existing admin guard.
- `src/main/java/com/emergentterrain/research/conjecture/SageBatchConjecture.java`
  — DTO matching the JSON dropoff.
- Tests: ingest happy path, dedup on second run, schema rejection
  on malformed input, auth required.

**Acceptance:** posting the 27.1 fixture produces N rows on first
call, 0 new rows on second call. Existing Java-miner rows are
untouched.

**Effort:** ~1.5 days.

### 27.3 — Queue + paper-kind wiring

`ConjectureMinerService.next(domain)` already reads from the
`conjectures` table. Update its priority comparator to weight
SAGE_BATCH and JAVA_MINER rows on the same novelty axis (no
source bias). Add `PaperKind.CONJECTURE_INVESTIGATION` routing
support for the GRAPH domain (which today doesn't exist in
`BotGenome.topicWeights`); seed a small set of graph-theory
topics for the topic-consistency validator.

**Files:**
- `src/main/java/com/emergentterrain/research/conjecture/ConjectureMinerService.java`
  — comparator update (no behavioural change for math; queues
  GRAPH rows when present).
- `src/main/java/com/emergentterrain/topic/TopicCatalog.java` (or
  equivalent location of the fixed catalog) — add GRAPH domain
  with topics like "graph chromatic bounds", "independence number
  inequalities", "spectral graph theory".
- `src/main/java/com/emergentterrain/service/validation/TopicConsistencyValidator.java`
  — extend coverage to GRAPH topics.
- `src/main/java/com/emergentterrain/conjecture/ConjectureTopicProvider.java`
  — extend to surface GRAPH-domain conjectures.
- Tests for each.

**Acceptance:** a SAGE_BATCH row in the GRAPH domain routes
through `ConjectureTopicProvider` into the existing publish
pipeline, producing a paper that the validators don't flag as
off-topic.

**Effort:** ~2 days.

### 27.4 — Scheduling + operator runbook

`systemd` timer (production) or cron (dev) that runs the 27.1
script nightly, then calls the 27.2 ingestion endpoint. Failure
modes:
- Sage script timeout (default cap 60 min): logged, no JSON
  written, ingestion skipped this cycle; existing queue keeps
  serving prior rows.
- Ingestion HTTP failure: JSON retained for manual retry; runbook
  step documents the curl invocation.
- Invariant pool version bump: documented as an operator-conscious
  change; bumping requires regenerating cached novelty scores.

**Files:**
- `systemd/conjecturing-batch.service` — oneshot unit.
- `systemd/conjecturing-batch.timer` — daily 02:00 timer.
- `STARTUP.md` — new §X documenting Sage install + timer enable.
- `RUN.md` — terse copy-paste for dev.

**Acceptance:** timer fires; logs show successful run and
ingestion; the next paper run picks up at least one SAGE_BATCH
conjecture from the queue.

**Effort:** ~1 day.

### 27.5 — Acceptance + rollout

Two checks:

1. **Invariant-pool coverage.** After 14 nightly runs, the
   `conjectures` table should hold ≥200 SAGE_BATCH rows across
   the two configured domains, with the invariant-pool diversity
   visible in a query (group by `(lhs_invariant, rhs_invariant)`
   should show ≥40 distinct pairs).

2. **Novelty axis didn't regress.** Re-run Phase 23.8 calibration
   on a 20-paper sample drawn from the post-27 queue. Median
   novelty must remain > 0.3 AND OEIS-rediscovery rate < 10%. The
   wider pool should help, not hurt; if it hurts, the likely cause
   is invariant pool size making the panel less confident — tune
   the per-domain config down to a smaller curated subset.

**Acceptance bar:** both. The phase closes when at least 5 papers
have shipped citing SAGE_BATCH conjectures and the calibration
re-check passes.

**Effort:** ~1 day acceptance scripts; corpus run is unattended.

---

## Open architecture questions

1. **Sage as a peer service vs. one-shot batch.** Phase 22's Julia
   sidecar is a long-running peer service because it's on the hot
   path. Sage is **not** on the hot path; one-shot is correct here.
   Revisit only if the LLM ever needs to consult Sage live for
   theorem provers like Otter/MACE — and even then, that's a
   separate proof-tool phase, not a Conjecturing phase.

2. **Where do Conjecturing and the Java miner overlap?** The Java
   miner has ~12 hand-coded number-theoretic invariants. The Sage
   integer config has ~40, most of which are supersets. Duplicates
   on `(lhs, rhs)` get de-duped via the ingestion hash; the Java
   miner's rows take precedence (they have witness records). If
   the Java miner becomes pure noise vs the Sage batch in the
   actual queue, the v1.5 work is to **retire the Java miner** and
   let SAGE_BATCH be the sole source. Hold off on that until 27.5
   passes.

3. **GPL-3.0 of `objects-invariants-properties`.** EmergentTerrain
   is internal but the invariant catalog is GPL. The Sage batch
   runs as a separate process and writes JSON; no GPL code links
   into the Spring JVM. If a public distribution of EmergentTerrain
   ever happens, this needs legal review; until then, the
   subprocess boundary is the practical answer.

4. **AutoGraphiX as a v2 add.** AGX is closed but the binary is
   freely downloadable for academic use. A v2 phase could wrap it
   the same subprocess way: feed it (invariant, n), get back an
   extremal graph, feed the graph back into Conjecturing for a
   matching inequality. Out of scope here; flagged in
   `reference-conjecture-engines.md` Tier-3.

5. **Materials substrate.** Phase 23.7 is gated on the math lane
   producing publishable papers. When it lands, a third config
   file `materials-property-tables.yml` joins the Sage batch with
   a different invariant family (heat capacity scaling, lattice
   parameters, etc.). Out of scope until 23.7 ships.

---

## Cost / benefit summary

**Total effort:** ~8.5 days end-to-end (3 standalone + 1.5
ingestion + 2 routing + 1 scheduling + 1 acceptance).

**Operational tax:** Sage on the deployment host (~1.5 GB install,
slow updates). One more systemd unit + timer. One more nightly
job to monitor (`journalctl -u conjecturing-batch.service`). The
Sage dependency is the heaviest of any phase in this batch — only
worth paying if Phase 23 already proved the in-process miner's
ceiling.

**Expected impact.** Where Phase 23 took novelty off zero for the
math domain via 12 invariants, Phase 27 broadens the invariant
pool by ~3× and opens the GRAPH domain with another ~30 invariants.
The expectation is **more conjecture variety**, not more conjecture
volume — the Dalmatian heuristic only keeps inequalities that
earn their spot, so a larger pool produces a larger but still
non-redundant front.

**Why this is Tier-2 / gated.** If Phase 23.8 calibration shows
the Java miner already produces novel-enough conjectures, adding
Conjecturing's wider pool is over-engineering — invariant pool
size isn't the bottleneck. If 23.8 misses, the right diagnostic
is *why*: maybe the corpus is too small, maybe the prompt is
nudging the LLM away from the mined conjecture, maybe novelty
scoring is over-confident. Only the "invariant pool too narrow"
diagnosis triggers this phase. Run the calibration first;
revisit this doc after.

**Recommendation.** Park this phase until Phase 23.8 calibration
data is in hand. If/when it's the indicated next step, ship 27.1
first as a pure-batch acceptance (no Spring changes); 27.2–27.5
follow. Default flag `app.conjecturingBatch.enabled: false` per
the dormant-flag pattern; flip after 27.5's two checks pass.
