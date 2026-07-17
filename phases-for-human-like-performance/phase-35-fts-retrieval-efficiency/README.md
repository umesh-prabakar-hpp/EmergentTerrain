# Phase 35 — FTS5 retrieval efficiency

Phase 35 improves the latency of the existing Phase 34 local
SQLite/FTS5 literature adapter without weakening its grounding, provenance,
or publication-label gates.

## Trigger and diagnosis

On 2026-07-17 a live mathematics run had no publishable paper after the first
two narration slots were occupied by long professor-tool loops. The run was
not budget-gated and the app was not deadlocked. A live thread dump showed an
active narration virtual thread executing:

```
Phase34SqliteLiterature.search()
  -> SQLite NativeDB.step()
  -> MathToolSuite.searchLiterature()
  -> GenericOpenAICompatPool.runToolLoop()
```

Two `search_literature` calls eventually returned after roughly twelve
minutes. The derived local index was about 27 GiB. Because narration has two
slots, a slow retrieval in each slot prevents further paper work until a slot
is released.

The immediate cause is the current broad fallback query. It keeps up to eight
raw prompt tokens, tries their `AND` form, then retries them all with `OR` and
ranks matches using `ORDER BY bm25(theorem_fts)`. Generic instruction words
and common terms can make that fallback rank a very large part of the corpus.
This is an efficiency problem, not evidence that the index, sidecar, provider,
or run budget is unavailable.

## Non-negotiable invariants

Phase 35 must preserve all of the following:

- The current source corpus, raw-input hashes, revision provenance, and
  read-only Java access model.
- FTS5/BM25 relevance semantics for a given accepted query; no vector model
  or external service is introduced.
- The opaque `HF...` key capability: `read_reference` may only read a key
  issued by `search_literature` during the current process lifetime.
- The requirement for a successful `read_reference` result and matching
  related-work citation before the artifact-backed publication-candidate
  label is available.
- Mathematics remains a query-time activation choice, not a destructive
  corpus filter. `ar5iv-unclassified` records must remain eligible where the
  Phase 34 policy permits them.
- Failure-soft behavior: an unavailable, empty, or bounded-out retrieval must
  not fabricate a citation or block the entire paper pipeline indefinitely.
- The existing global narration timeout remains a final guard. The solution
  must reduce ordinary query time rather than simply shorten that timeout.

## Implementation plan

### 1. Preserve ranking while enabling FTS5 early termination

In `Phase34SqliteLiterature.search`, replace:

```sql
ORDER BY bm25(theorem_fts)
LIMIT ?
```

with:

```sql
ORDER BY rank
LIMIT ?
```

FTS5's default `rank` is the same default BM25 ordering, but it is optimized
for ordered, limited result delivery. This is therefore a semantics-preserving
change for the current adapter result order. See the official FTS5 reference:
<https://www.sqlite.org/fts5.html#sorting_by_auxiliary_function_results>.

### 2. Limit FTS candidates before metadata work

Perform the FTS match, relevance ordering, and `LIMIT 12` in a CTE/subquery.
Only then join `theorems`, `papers`, and the full-text-presence relation and
only then call `snippet()`. This leaves the selected theorem IDs and their
ranking unchanged while avoiding metadata joins and passage rendering across
every broad-query candidate.

The full-text presence check must remain: a returned source must still be
eligible for later `read_reference`.

### 3. Replace the all-token OR fallback with a deterministic relaxation ladder

Normalize model-supplied text before constructing the FTS expression:

- remove instruction/stop words and duplicate tokens;
- retain domain identifiers, mathematical symbols rendered as tokens, and
  numeric/technical anchors;
- keep a small ordered set of discriminative terms;
- try an all-anchor `AND` query first;
- if empty, relax one lowest-value anchor at a time while retaining at least
  two or three anchors;
- return an honest no-match result rather than issue an unbounded OR search.

This is not a recall shortcut. It prevents generic prompt framing from
dominating a claim search while preserving exact all-term matching first. The
chosen query and relaxation step should be logged for diagnosis, but never
shown as evidence in the paper.

### 4. Bound retrieval separately from paper narration

Add a small, configurable per-search execution budget (for example 10–20
seconds) at the SQLite operation layer. On expiry, cancel/close the query and
return a descriptive, non-evidentiary `search_literature` result. The existing
literature fallback rules may proceed only as they do for an unavailable or
no-hit adapter; they must not manufacture a Phase 34 key or satisfy the
artifact-backed gate.

This guard is defense in depth. It protects the two narration slots from a
pathological query while the ranking and query-shaping changes address the
normal case.

### 5. One-time index maintenance

After a completed immutable import, run FTS5's `optimize` command as a
controlled index-maintenance step and record it in the Phase 34 manifest.
This merges FTS segments, reducing query-side merge work without changing
indexed documents or query semantics. It must never run concurrently with the
external importer and must not be performed by the application at request
time.

## Acceptance suite

Before enabling the changes live:

1. Run the fixed Phase 34 real-claim suite against the pre-change and
   post-change index. The same expected sources, key issuance restrictions,
   and accepted/rejected fabricated-key cases must hold.
2. Add adapter tests for normalization, deterministic relaxation, no-match
   behavior, result-key issuance, and result ordering.
3. Record query latency, candidate count before limiting, relaxation level,
   timeout/cancellation count, and result count. Do not log source passage
   text or model prompts unnecessarily.
4. Run two concurrent professor-tool searches against the completed 27 GiB
   index. Both must finish inside the configured search budget and neither may
   prevent the next narration slot from starting.
5. Run a paper-generation smoke test. A paper may become an
   `ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE` only if its issued key,
   `read_reference` result, related-work citation, artifact, and workflow
   status still agree.

## Rollout and rollback

Ship the query changes behind a Phase 35 retrieval flag, defaulting to the
current Phase 34 implementation until the acceptance suite passes. Roll back
by disabling that flag; do not alter the external corpus or delete the index.

Do not restart an active research run merely to apply this work: research room
output is reset on application restart. Finish or safely stop the run first,
then restart with the verified implementation.
