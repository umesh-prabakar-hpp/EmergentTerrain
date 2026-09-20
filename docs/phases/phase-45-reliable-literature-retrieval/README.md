# Phase 45 — Reliable, evidence-safe literature retrieval

**Status: IN PROGRESS — offline selectivity metadata, specificity-first candidate ordering, and
versioned title-anchor theorem narrowing implemented. Fair scheduling, routed cancellation, and
durable recovery remain.**

## Purpose

Phase 35 made local SQLite/FTS5 retrieval bounded, title-gated, and observable. The latest
standalone research run showed that two valid searches can still behave very differently under
the same 15-second request budget: `prime gap`/`maximum prime` issued evidence, while
`divisor`/`omega` spent its deadline in broad posting-list scans before reaching its more
specific alternatives. A timeout is unresolved retrieval, not evidence of absence.

Phase 45 makes the local literature path predictable across materially different FTS
posting-list sizes, cold-cache conditions, and concurrent papers. It must reach one of three
honest outcomes:

```text
relevance-safe evidence issued
no match after the complete bounded plan
pending retrieval with durable retry evidence
```

It must never turn a timeout, queue delay, unavailable shard, or partial candidate scan into
`no match`, an issued reference key, a citation, or a publication-quality claim.

## Non-negotiable boundaries

- The corpus remains local, immutable while serving, and accessed read-only by the application.
  Index construction, optimization, and selectivity materialization run only in the offline
  preparation/maintenance workflow.
- Existing Phase 35 validation remains authoritative: model input is safe structured intent;
  the deterministic planner owns executable expressions; title/theorem evidence, process-scoped
  issued keys, `read_reference`, relevance assessment, and related-work citation remain required.
- Candidate ordering improves latency, not scientific meaning or recall. A specific candidate
  that yields no evidence cannot suppress the rest of the complete bounded candidate plan.
- Request-level single-flight remains an optimization for duplicate semantic searches. It does
  not cache results, invent evidence, or substitute for fair scheduling across distinct queries.
- No external search, vector service, request-time index mutation, raw FTS syntax, or relaxed
  evidence/title-anchor rule is introduced by this phase.
- A recovered result must be attached only to the same canonical request and immutable index
  generation that produced it. Changed generation, invalid evidence chain, or cancellation leaves
  the request unresolved.

## Reliability layers

### 1. Offline selectivity and anchor metadata

The index builder will materialize a versioned generation manifest and compact lookup tables for
normalized title/theorem anchors and their document frequencies. The serving adapter reads only
the bounded counts needed by a proposed plan; it must not scan `fts5vocab`, corpus text, or a
posting list to estimate selectivity on the request path.

The manifest records the index format, source generation, build timestamp, and materialization
version. Missing, stale, or unsupported metadata is a safe fallback to the existing deterministic
ordering; it is never a reason to weaken evidence validation.

### 2. Specificity-first, recall-preserving planning

The planner ranks only already-valid candidates. Exact named subjects and low-frequency
anchor intersections run before broad pairs such as `divisor AND omega`. The original complete,
bounded candidate set remains available in deterministic order after the preferred candidates,
and query/evidence/title-anchor semantics do not change.

Structured model proposals remain audited, claim-grounded routes. The complete valid candidate set
is ordered by offline selectivity and deterministic stable tie-breakers, not by model prose or
live FTS timing; this prevents one broad valid proposal from consuming the whole request before a
narrow valid fallback is attempted.

### 3. Narrow candidate retrieval

For a selected two-title-anchor candidate, retrieve the offline materialized title-anchor paper-id
intersection before ranking the theorem surface. The complete version marker is mandatory. A
zero-result narrowed attempt immediately retries the unchanged theorem query before the existing
full-text fallback, so incomplete or stale offline mappings cannot reduce recall. Each path still
passes the ordinary record, title, full-text, and read gates.

The implementation must measure and preserve the current candidate result order for equivalent
queries. Any query plan that cannot prove an indexed narrowing path falls back to the current
bounded FTS path rather than guessing.

### 4. Generation-scoped concurrency scheduling

The implemented fair dispatcher bounds distinct primary-index leaders per immutable generation at
`max-concurrent-primary-searches` (default 2). An identical semantic request remains a
single-flight follower and consumes no additional permit. It separately records queue wait and
execution duration, without logging claim text or passages. Routed primary fan-out receives only
the remaining request budget.

Queue waiting counts against the same request deadline as SQLite work. Queue-budget expiry returns
the established Phase 35 timeout marker, so the existing durable recovery lane treats it as
unresolved rather than no match. The dispatcher is not a cache, does not change supplemental-index
work, and does not yet cancel routed shards early.

### 5. Routed-shard early success and cancellation

The default-off implementation may cancel only an unfinished suffix after every earlier selected
shard has completed and their distinct issued keys already fill the three-result deterministic
response. This is stronger than “first evidence wins”: an incomplete earlier shard or fewer than
three keys prohibits cancellation. Cancellation is recorded by shard and reason.

`early-success-cancellation-enabled` remains false until a controlled routed-index observation
captures the `early_sufficient_evidence` condition and verifies response parity. Deadline,
interrupt, and failure cancellations remain unresolved timeout outcomes. It cannot cancel the
selected source's required read/citation chain, reorder issued keys, or claim that uncompleted
shards had no evidence.

### 6. Durable serialized fallback

After a request budget expires, the canonical request may be persisted for a low-concurrency,
generation-pinned retry with a bounded maintenance budget. Completion records candidate coverage,
index generation, timing, and ordinary issued-key evidence. The paper workflow may continue only
after valid evidence is present or a complete bounded plan establishes no match; otherwise it
retains an explicit pending-retrieval state.

This is a retrieval-recovery lane, not a hidden second search provider and not an authorization to
publish with missing related-work evidence.

### 7. Evidence and publication gates

Quality, narration, and publication gates remain fail-closed. They distinguish:

| Retrieval observation | Permitted meaning |
|---|---|
| relevance-safe key, read, and cited related work | ordinary evidence chain can proceed |
| complete bounded plan with no admissible source | explicit no-evidence positioning only |
| timeout, queue expiry, shard failure, cancelled attempt, changed generation | unresolved; publication evidence is missing |

No layer may convert an unresolved state into a negative literature assertion. Existing generated
paper labels, quality assessment, and title-anchor rules remain unchanged unless separately
reviewed.

## Initial benchmark baseline

On 2026-09-20, the read-only normal-cache harness ran against the existing completed index before
the new selectivity table had been materialized. `maximum-prime-gap-anchors` issued evidence in
6.009 seconds and `divisor-omega-anchors` returned the honest no-match outcome in 6.036 seconds.
After those reads warmed the relevant pages, the pair-concurrent observations were 127 ms and
72 ms respectively. The four-way mixed set retained evidence for maximum-prime-gap (102 ms),
Dirichlet error (5.302 s), and multiple zeta (10.443 s), while divisor/omega remained an honest
no-match (64 ms); no request timed out. The retained local JSON report and telemetry log are the
source of record. This is a baseline, not a claim that cold-cache or post-maintenance acceptance
has passed.

The initial production-index selectivity materialization was not accepted on the same date: both
attempts entered uninterruptible host disk I/O before the final marker could commit and were
stopped. The adapter therefore continues to use the safe historical ordering on that index. The
builder now uses SQLite FTS vocabulary aggregates rather than a Python row walk and passes its
focused fixture; retry only after the host's index-storage I/O condition is healthy, then record a
fresh selectivity-enabled benchmark. Do not infer a completed index generation from an interrupted
offline build.

## Implementation sequence

1. **Implemented:** add an offline-built, versioned anchor-frequency schema and a read-only,
   bounded generation reader. Its builder derives counts from SQLite's offline FTS vocabulary
   summaries rather than Python scanning every theorem row; absent and stale metadata retain the
   historical ordering.
2. **Implemented:** order the complete validated candidate plan specificity-first using that
   metadata, with deterministic stable tie-breakers and unchanged candidate coverage.
3. **Implemented:** benchmark representative prime-gap and divisor/omega shapes alone,
   concurrently, and mixed with Phase 35 claims through the read-only
   `run-phase45-literature-benchmark.sh` harness. It stores safe timing/outcome JSON and the
   existing plan/candidate/SQLite/shard telemetry log. Run it once in normal cache state and once
   after an operator-owned maintenance pass; cold-cache setup remains a separately labelled host
   operation because the harness must not change global caches.
4. **Implemented:** materialize a versioned normalized title-anchor-to-paper map and use it to
   make a first theorem-candidate narrowing attempt for exact two-title-anchor candidates. The
   unchanged theorem query remains an in-budget fallback before full text; older or incomplete
   indexes do not prepare or use the table. Completion telemetry records narrowing availability,
   narrowed candidates, and fallback count. Production-index materialization remains pending host
   storage I/O recovery, so this is not yet a production performance claim.
5. **Implemented in part:** fair, generation-scoped primary-index leader dispatch with bounded
   queue wait, remaining-budget propagation to routed primary work, and source-safe queue/execution
   telemetry. Distinct-query fairness and queue-expiry-as-timeout have focused regression coverage.
   The separately default-off routed-shard early-success policy is implemented with completed-prefix
   safety tests; capture controlled routed-index parity telemetry before enabling it.
6. Add generation-pinned serialized recovery and the explicit pending-retrieval workflow state.
7. Run cold/concurrent acceptance tests and one controlled research worker session before enabling
   any new default behavior.

## Acceptance criteria

- A broad valid pair cannot consume the entire request budget before its already-valid,
  more-selective fallback candidates are attempted when those candidates fit the remaining budget.
- Identical semantic requests share in-flight work only within one index generation; distinct
  requests receive observable fair queueing rather than uncontrolled contention.
- Every timeout or cancellation is represented as unresolved, with candidate coverage and index
  generation available for recovery diagnostics.
- The normal proof chain remains unchanged: no `HF...` key without indexed/title-safe evidence,
  no literature assertion without a successful read, and no artifact-backed publication label
  without the existing citation gate.
- Offline maintenance never runs from a paper request, and serving works safely against an index
  lacking Phase 45 metadata.
- Benchmark artifacts establish performance for the required alone, concurrent, mixed,
  post-maintenance, and normal-cache scenarios without exposing paper body or credentials.

## Explicit non-goals

Phase 45 does not add web search, semantic/vector retrieval, a new corpus, automatic citation
generation, a relaxed title-anchor policy, automatic publication retry, independent replication,
or a claim that a local index makes a paper human quality. Those require separate evidence,
quality, and ownership decisions.
