# Phase 45 — Reliable, evidence-safe literature retrieval

**Status: IN PROGRESS — design recorded; implementation starts with immutable-index selectivity
metadata and specificity-first candidate ordering.**

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

Structured model proposals remain the first audited route when valid. Claim-grounded fallback
candidates are then ordered by offline selectivity and deterministic tie-breakers, not by model
prose or live FTS timing.

### 3. Narrow candidate retrieval

For a selected candidate, retrieve a small, indexed anchor/title/theorem intersection before
ranking full text. FTS/BM25 is applied only to the narrow eligible row set where SQLite supports
that plan without changing relevance semantics. The existing bounded theorem-first and full-text
fallbacks stay intact; each still passes the ordinary record, title, full-text, and read gates.

The implementation must measure and preserve the current candidate result order for equivalent
queries. Any query plan that cannot prove an indexed narrowing path falls back to the current
bounded FTS path rather than guessing.

### 4. Generation-scoped concurrency scheduling

A small fair dispatcher bounds concurrent primary-index work per immutable index generation.
It accounts separately for queued time, SQLite execution time, routed-shard work, and cancelled
followers. It prevents multiple broad distinct searches from causing avoidable deadline loss while
allowing unrelated work and the screened supplemental index to make progress.

The scheduler is not a global cache and does not silently discard work. Queue-budget expiry is an
unresolved retrieval outcome with durable retry metadata.

### 5. Routed-shard early success and cancellation

When one routed shard produces relevance-safe evidence, remaining shard work may receive a short
grace period and then be cancelled if its result cannot change the bounded answer. Cancellation is
recorded by shard and reason. It cannot cancel the selected source's required read/citation chain,
reorder issued keys, or claim that uncompleted shards had no evidence.

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

## Implementation sequence

1. Add an offline-built, versioned anchor-frequency schema and a read-only, bounded generation
   reader; cover absent and stale metadata fallback.
2. Order validated fallback candidates specificity-first using that metadata, with deterministic
   tie-breakers and unchanged complete-plan coverage.
3. Benchmark representative prime-gap and divisor/omega shapes alone, concurrently, mixed with
   Phase 35 claims, after index maintenance, and from normal cache state. Record plan, candidate,
   queue, SQLite, shard, and evidence timings.
4. Add a proven indexed candidate-narrowing path; retain current FTS as the verified fallback.
5. Add generation-scoped fair dispatch and routed-shard cancellation telemetry.
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
