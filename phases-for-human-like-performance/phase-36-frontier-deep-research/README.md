# Phase 36 — Frontier Deep-Research Campaigns

## Goal

Turn each paper pipeline from a short, local tool loop into a durable, evidence-led research
campaign. Frontier models should be able to propose materially different algorithms, execute
them against real instruments, interpret failures, and choose the next discriminating experiment.
The outcome remains a reproducible computational result, an honest null result, or an explicit
failure—not persuasive prose unsupported by a trace.

The first vertical slice is implemented: every count-weighted density CONSTANT scale-ladder path
preserves each original logical window while executing it as restart-safe 50,000-integer chunks.
It does **not** change the current monetary budget, provider parallelism, or the 15-second Julia
fail-soft deadline. The durable proposal/critic/hypothesis-tree loop remains later Phase 36 work.

## Implemented vertical slice (2026-07-20)

`RangePreservingChunkExecutor` journals exact density jobs under
`simulation-history/research-experiments/`, outside the restart-cleared `research/<room>/` tree.
For each supported count-weighted density ladder window wider than
`app.research.experiments.range-chunking.chunk-size` (currently 50,000), it records the immutable
logical interval, ordered chunks, every terminal chunk result, attempt count, and queue / Julia
compute / transport timings. On a sidecar failure the tool returns `PARTIAL` or `UNAVAILABLE` and
names the completed coverage; it does not report a full-range density. Re-running the same logical
interval and chunk size uses the same deterministic job id and runs only unfinished chunks.

The reducer is deliberately narrow: chunk densities are combined as
`sum(chunk_density × chunk_length) / logical_length`. No other ladder metric is chunked yet, since
window extrema, correlations, quantiles, and path-dependent statistics require their own exact
reducer or sufficient boundary state. This preserves equal treatment by applying the same rule to
every paper that requests this supported metric, rather than creating a paper-specific exception.
The eligible metrics are the literal per-integer density scalars in `MathInvariants`, including
`richly_divisible_density`.

Before a scale ladder schedules a broad logical request, it checks the Julia conjecture-sidecar
health state. If the sidecar is unavailable, the tool returns `UNAVAILABLE` before any chunk is
created. `queued_ms` is actual dispatch wait only; it is no longer inferred from synchronous wall
time, which previously counted Julia computation as queueing. A direct caller has no local queue
and therefore records zero; `compute_ms` and `transport_ms` remain separate.

The toggle is `app.research.experiments.range-chunking.enabled`; set it to `false` for a quick
rollback to the previous one-shot behavior. Keep `app.julia.conjecture.timeoutMs` at 15 seconds
while timing evidence is collected. Number-theory-window and conjecture-verification HTTP requests
use that operation deadline instead of the general Julia timeout. A single response timeout is
reported for that operation but does not mark a previously healthy sidecar globally unavailable;
the scheduled miner defers a failed anomaly-promotion hearing to its next pass. The focused acceptance tests are
`RangePreservingChunkExecutorTest`: they pin exact coverage of
`[1_000_000_000, 1_000_500_000)`, count-weighted reduction, and resume-after-failure semantics.

Implementation note: the executor has a production Spring constructor and a package-visible
test constructor. The production constructor is explicitly `@Autowired`; without that annotation
Spring sees multiple constructors and incorrectly attempts a no-argument constructor during
context creation. Keep the annotation if another test-oriented constructor is added.

## Why this phase is needed

The current professor loop is deliberately bounded: `GenericOpenAICompatPool` normally permits
five tool rounds and only adds two rounds for a narrowly defined near-binding result. The global
run budget is currently `$5.00` and 350 calls. Those limits protect the service, but a research
cycle can exhaust them before trying a genuinely different algorithm or completing a useful
counterexample search.

There is also a specific scale-ladder failure mode. `MathToolSuite.verifyConstantLadder()` uses a
ladder-specific sample budget instead of the interactive `maxWork` guard. It can therefore widen
a rung substantially to make sparse signals measurable. In the reviewed abundant-density run,
the logical high-scale rung covered 500,000 integers near `10^9`; the synchronous sidecar request
exceeded the 15-second conjecture deadline.

The intended correction is not to reduce the scientific request to a smaller range. It is to
execute the **same logical request** as bounded, resumable subranges and return one traceable
result for the complete original range.

## Non-negotiable principles

1. **Logical range preservation.** If the researcher requests `[1_000_000_000,
   1_000_500_000)`, Phase 36 records that exact interval as the experiment. Chunking is execution
   sub-logic; it must never silently shrink, shift, sample, or substitute the requested range.
2. **Metric-safe aggregation.** A result is combined only by a metric-specific reducer with a
   documented proof or invariant. Equal-size density chunks, for example, can be weighted by
   count. A maximum, correlation, quantile, or path-dependent statistic must retain enough state
   to combine exactly, or be reported as per-chunk evidence rather than falsely averaged.
3. **Equal treatment.** Every paper receives the same proposal, experiment, evidence, and
   promotion rules. There is no privileged topic, author, model, or “frontier lane.” Scheduling is
   FIFO within the existing resource limits; per-paper exploration caps are uniform and explicit.
4. **Evidence over narration.** Models may propose algorithms and interpret outcomes, but only
   executed artifacts, exact tool results, and validated literature keys can support a claim.
5. **Failure is information.** Timeout, refutation, unavailable literature, and invalid
   aggregation are persisted as structured evidence that changes the next proposal; they are not
   silently discarded or retried indefinitely.
6. **Budgets remain safety controls.** More budget is a later operator option, not a prerequisite
   or a substitute for a bounded experiment design.

## Target architecture

```text
research question
      |
      v
proposal set (same allowance for every paper)
      |
      +--> novelty / feasibility / falsifiability screen
      |
      v
durable hypothesis tree ------> branch selection
      |                              |
      |                              v
      |                     experiment plan
      |                              |
      v                              v
evidence + insights <---- resumable chunk executor
      |                              |
      +---- counterexample / holdout / literature checks
      |
      v
classification and publication gates
```

### 1. Structured algorithm proposals

Replace an open-ended request to “try more tools” with a schema-validated `ExperimentPlan`.
The model proposes several alternatives, each with:

- a hypothesis and the exact result that would discriminate it;
- an algorithm family, such as multiscale measurement, counterexample search, symbolic fitting,
  adaptive partitioning, parameter sweep, or literature-guided reformulation;
- a logical input range and an allowed aggregation/reduction strategy;
- an estimated work class and expected artifacts;
- a falsification condition and a required held-out or adversarial check.

The system rejects a plan before execution when it cannot preserve scope, lacks a valid reducer,
duplicates a completed branch, exceeds the uniform paper allowance, or proposes ungrounded
citations. A separate critic pass ranks plans by novelty, discriminating value, feasibility, and
overlap with existing evidence. It does not decide truth.

### 2. Durable hypothesis tree

Persist a per-room hypothesis tree outside the restart-cleared `research/<room>/` paper folder.
Each node stores the parent, hypothesis, exact experiment plan, status, executed artifacts,
result classification, counterexamples, insight, and promotion decision. Suggested statuses are:

`PENDING`, `RUNNING`, `PARTIAL`, `COMPLETE`, `REFUTED`, `UNAVAILABLE`, `PRUNED`, and `PROMOTED`.

When a branch completes, a concise evidence-backed lesson is propagated to its parent. Later
proposals must inspect ancestor outcomes and sibling failures before selecting another algorithm.
This makes a timeout or refutation useful search state rather than another forgotten draft.

### 3. Range-preserving chunk execution

Introduce an `ExperimentJob` with immutable request scope and an ordered `ChunkPlan`:

```text
logical request: [1_000_000_000, 1_000_500_000), metric=abundant_density
chunk plan:      [1_000_000_000, 1_000_050_000), ...,
                 [1_000_450_000, 1_000_500_000)
```

The executor persists every chunk before starting the next. A timeout marks the job `PARTIAL`;
resume runs only missing chunks. The final response must say one of:

- `COMPLETE`: every integer in the original logical range is covered exactly once;
- `PARTIAL`: it names completed and missing subranges and cannot support the full-range claim;
- `UNAVAILABLE`: no usable chunk completed.

The chunk planner is adaptive only in execution shape: it may reduce later chunk sizes after a
slow result, but the union of planned chunks must remain exactly the original range. Chunks may
not be parallelized beyond measured sidecar capacity; queue delay is an input to that decision.

Each chunk records `queued_ms`, `compute_ms`, `transport_ms`, attempt number, start, end, and
terminal status. This distinguishes sidecar serialization from intrinsically expensive
number-theory computation.

### 4. Algorithmic research loop

For each paper, use the same bounded loop:

1. Propose 3–5 diverse, schema-valid experiment plans.
2. Screen out clones, untestable plans, unsupported aggregations, and plans outside the uniform
   paper allowance.
3. Execute the highest-value plan in bounded jobs.
4. Run the plan’s declared adversarial check: counterexample probe, held-out scale/window, or
   independent method.
5. Backpropagate the observed result into the hypothesis tree.
6. Choose a distinct next branch only when the new evidence leaves a meaningful uncertainty.
7. End in `SUPPORTED`, `PARTIALLY_SUPPORTED`, `NULL_RESULT`, or `DEGENERATE` using the existing
   deterministic classification path before narration.

The current near-binding extra-round mechanism remains useful, but becomes one completion rule
among several. It must not be the only way a paper earns additional experiments.

### 5. Promotion discipline

An empirical claim requires all applicable evidence:

- complete logical-range coverage or an explicitly limited partial claim;
- a metric-safe aggregation record;
- a pre-declared counterexample or holdout attempt;
- no unresolved trace, definition, or citation inconsistency;
- relevant literature evidence only when the bounded lookup actually issued a usable key and
  `read_reference` was completed.

No topic earns a relaxed standard. Computational novelty means a new, executed and differentiated
experiment—not merely a different prose description of a prior local measurement.

## Arbor reference assessment

The reference clone is at:

`/opt/apps/ide/vscode/ai/reference-from-github/Arbor`

Analyzed revision: `65ffcc8fdf23a64a781940e6a3cfb6369d6d887e` (2026-07-11).

Arbor is an autonomous-optimization system built around a persistent Idea Tree, a Coordinator
that chooses branches, and isolated Executors that return real evidence. Its most relevant ideas
for Phase 36 are:

| Arbor pattern | Phase 36 adaptation | Decision |
|---|---|---|
| Persisted hypothesis tree with status, result, insight, score, and retries | Per-room hypothesis tree with plans, chunks, evidence, and classifications | Adopt conceptually |
| Coordinator selects branches; executor runs one concrete experiment | Proposal/critic stage selects a plan; bounded executor performs only that plan | Adopt |
| Backpropagated insights and pruned branches | Persist failures and lessons to avoid repeated failed/clone experiments | Adopt |
| Checkpoint/resume and event stream | Resume incomplete chunk jobs and expose queue/compute timings | Adopt |
| Dev/test promotion discipline | Calibration/holdout-window and counterexample separation | Adopt, domain-adapted |
| Per-idea Git worktrees and guarded code merges | Immutable experiment artifacts and result records | Do not copy literally; most EmergentTerrain papers do not modify source code |
| External novelty search | Existing validated local Phase 35 retrieval plus `read_reference` gate | Keep local approach; do not bypass evidence policy |

Arbor is reference material, not a runtime dependency. Phase 36 must use the application’s own
Spring lifecycle, deterministic classification, literature guardrails, and sidecar interfaces.
Do not copy its benchmark claims or introduce an unchecked external search path.

## Budget option

The global run budget is `$5.00` and 350 calls. It enters drain at `$2.20`,
reserving `$2.80` for already-started papers while admitting no new research;
the call drain remains at 290. This is shared aggregate closeout headroom, not
per-paper escrow or a completion guarantee.

A further 2× or 3× budget increase remains deferred. If enabled, increase cost
and call ceilings together while retaining a drain policy and per-paper caps.
More money must buy additional falsification, replication, and algorithm
diversity for **every** paper—not more simultaneous drafts or preferential
treatment.

Provider parallelism and `maxDraftBacklog` remain unchanged initially because the Julia sidecar
can serialize expensive work. Telemetry must demonstrate spare capacity before any concurrency
increase is considered.

## Implementation sequence

1. Define `ExperimentPlan`, `ExperimentJob`, `ChunkPlan`, `ChunkResult`, and metric reducer
   interfaces; persist them with restart-safe room research state.
2. Refactor scale ladders and any other wide-range tool path to create a range-preserving chunk
   plan rather than one synchronous enlarged sidecar request.
3. Add sidecar responses or an application-side wrapper that returns the timings and sufficient
   state for exact metric-specific reduction.
4. Add the proposal/critic/selection loop with equal per-paper limits and durable hypothesis-tree
   updates.
5. Add holdout/counterexample completion rules and integrate them with existing publication gates.
6. Add the observability view: logical range, chunk coverage, job status, timings, branch tree,
   selected plan, and reason for pruning.
7. Run the acceptance suite below before considering a budget change.

## Acceptance suite

- A 500,000-wide logical request is represented unchanged and completes through multiple chunks
  without gaps or overlaps.
- Interrupting after any chunk and resuming executes only the remaining chunks; completed results
  are byte-identical to the uninterrupted run.
- Density-style aggregation equals an independent one-shot computation where one is feasible.
- A non-composable metric is rejected for unsafe aggregation or retains all required boundary
  state; it is never silently averaged.
- Injected queue delay and injected compute delay produce distinct telemetry and adaptive chunk
  responses.
- Every paper receives identical proposal count, tool-round ceiling, work allowance, and
  promotion requirements under the same configuration.
- A failed branch prevents an equivalent re-proposal unless its new plan explicitly changes the
  discriminating test.
- A paper with strong narration but incomplete chunk coverage cannot publish a full-range claim.
- A paper with a complete trace but a failed adversarial probe is classified honestly rather than
  promoted.
- Phase 35 query observability, bounded retrieval, and `read_reference` evidence requirements
  continue to hold for campaign literature work.

## References

- Arbor repository: <https://github.com/RUC-NLPIR/Arbor>
- Arbor paper: <https://arxiv.org/abs/2606.11926>
- Existing Phase 35 retrieval work:
  [`../phase-35-fts-retrieval-efficiency/README.md`](../phase-35-fts-retrieval-efficiency/README.md)
