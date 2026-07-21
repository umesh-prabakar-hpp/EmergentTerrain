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

Perform the FTS match, relevance ordering, and a bounded `LIMIT 512` in a
CTE/subquery. The adapter then emits at most three distinct full-text-backed
sources. The oversampling is deliberate: the theorem corpus is broader than
the Marin full-text companion, so a `LIMIT 12` candidate set can contain no
readable source even when a well-ranked eligible source exists slightly later.
Only then join `theorems`, `papers`, and the full-text-presence relation and
only then call `snippet()`. This leaves the selected theorem IDs and their
ranking unchanged while avoiding metadata joins and passage rendering across
every broad-query candidate.

The full-text presence check must remain: a returned source must still be
eligible for later `read_reference`.

### 3. Use validated subject phrases instead of broad fallback

The adapter extracts non-generic subject terms from the claim and considers
only adjacent subject phrases that actually occur in its wording. It never
combines distant rare words and never runs an all-terms OR fallback.

- A claim with three or more subject anchors uses an escaped exact FTS phrase.
  Each candidate keeps its subject pair plus one independent early anchor,
  separating core subject/result evidence from later interpretive qualifiers.
  The theorem-record path requires both phrase terms in the source title and
  all candidate evidence in the returned title/theorem record before issuing
  an `HF...` key.
- A two-anchor claim uses one bounded `AND` pair so sources with descriptive
  rather than topical titles can still be found; both terms must occur in the
  returned theorem record.
- Instructional and generic framing terms (`finite`, `scale`, `normalized`,
  and similar) are not eligible anchors.
- A match that lacks the required record evidence is an honest no-match, never
  evidence.

The theorem dataset and full-text companion do not completely overlap. If all
theorem-record candidates fail, the adapter retries the same safe pair against
`fulltext_fts`, adding the candidate's independent corroborating anchor to the
FTS expression. This is a bounded fallback, not broad full-text search: it
uses no raw model syntax, returns only indexed sources, and still grants only a
process-scoped `HF...` key whose source must be checked through
`read_reference`. It permits a source such as an error-term paper whose full
text is indexed but whose theorem record is absent; it does not weaken the
theorem path's title gate.

This deliberately favors a no-match over a plausible-looking but irrelevant
citation. The selected expression and rejected candidate count are diagnostic
data, not paper evidence.

The boundary is `LiteratureQueryPlanner` → `LiteratureQueryPlan`. It is
deterministic today. A future AI tool may propose only the validated
`EXACT_PHRASE` or `AND_PAIR` query kinds and two safe anchors; it cannot supply
raw FTS syntax or bypass the record-evidence and title-anchor policy before the
adapter queries or issues a key.

Every enabled lookup writes its exact FTS expressions plus per-candidate
evidence and required-title anchors to `logs/et-app.log` at INFO level. This
supports manual reproduction without logging the paper body. Repeated
identifier tokens (for example `omega_Omega_gap` → `omega`, `omega`, `gap`)
are skipped instead of being treated as an invalid two-anchor query.

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

### 4.1 Range-preserving staged ranked retrieval (2026-07-20)

Live logs showed that a cold or concurrently read 27 GiB index can make an
otherwise valid 512-result ranked search consume the complete 15-second
fail-soft budget, while the same plan is fast once its pages are warm. The
adapter therefore first requests the highest-ranked `initial-candidates` rows
(64 by default). If that tranche produces no admissible full-text-backed
evidence, it reruns the same validated expression with the existing 512-row
limit, subject to the unchanged aggregate budget. The second stage keeps the
full established eligibility range: it is not a reduced-recall mode and is
applied identically to every paper.

The deterministic planner also rejects clearly generic connector phrases
(`two term`, `vs`, `empirical inequality`, relation/ordinal phrases such as
`order one`, and measurement framing such as `high location`) before they can
occupy the first ranked tranche. It still accepts adjacent, domain-bearing
phrases such as `twin prime` or any other validated subject pair. This
domain-neutral filter was extended after the 2026-07-20 logs showed generic
pairs consuming a full bounded search despite the technical phrases in the
same claim being available.

The 2026-07-21 review added numeric/coefficient fragments and remaining
relation prose to that rejection boundary. Tokens containing digits and terms
such as `requiring`, `feature`, and `minus` cannot form an FTS anchor or a
subject pair; they produced broad expressions such as `density 0000` without
adding source-identifying meaning. The planner still preserves adjacent lexical
subject pairs such as `richly divisible`.

The later P76/P77 review found a second relevance gap: full-text fallback
could issue an `HF...` key solely because an unrelated paper's body contained a
generic phrase such as `abundant number` or `density`. The planner now drops
comparative/trajectory prose (`elevated relative`, `local baseline`,
`asymptotically like`, and similar) and rejects pairs made only of weak
measurement words such as `number density`. Full-text fallback also requires
at least one distinctive candidate subject anchor in the indexed source title,
in addition to all candidate evidence anchors in the full record. This keeps
the Dirichlet error-term fallback available through its `divisor` title anchor
while rejecting an unrelated density paper whose title lacks `abundant`.

Every completed lookup logs `outcome`, total elapsed time, candidate and batch
counts, rows inspected, results issued, the final search surface
(`theorem`/`fulltext`), final batch duration/limit, and the last FTS expression.
It never logs source passages or full paper prompts.

### 5. One-time index maintenance

After a completed immutable import, run
`./scripts/prepare-hf-math-index.py --stage optimize` once. It runs FTS5's
`optimize` command for both indexes and records the completion time in the
Phase 34 manifest.
This merges FTS segments, reducing query-side merge work without changing
indexed documents or query semantics. It must never run concurrently with the
external importer and must not be performed by the application at request
time.

## Acceptance suite

Before enabling the changes live:

1. Run the fixed Phase 34 real-claim suite against the pre-change and
   post-change index. The same expected sources, key issuance restrictions,
   and accepted/rejected fabricated-key cases must hold.
2. Add adapter tests for phrase planning, generic-anchor rejection,
   false-positive no-match behavior, result-key issuance, and result ordering.
3. Record query latency, candidate count before limiting, relaxation level,
   timeout/cancellation count, and result count. Do not log source passage
   text or model prompts unnecessarily.
4. Run `Phase34RealIndexAcceptanceTest` with `PHASE34_REAL_INDEX_DIR` set.
   Its two concurrent representative searches must finish inside the configured
   search budget and neither may prevent the next narration slot from starting.
5. Run a paper-generation smoke test. A paper may become an
   `ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE` only if its issued key,
   `read_reference` result, related-work citation, artifact, and workflow
   status still agree.

## Rollout and rollback

The path is controlled by the explicit application property:

```yaml
app.research.literature.phase34-sqlite.phase35-retrieval:
  enabled: true
  search-timeout-ms: 15000
```

It is enabled in the checked-in `application.yml`. Set `enabled: false` to
restore the former Phase 34 all-terms-then-OR query without altering the
external corpus or deleting the index. `search-timeout-ms` is the aggregate
budget for the search's complete relaxation ladder, not a new narration
deadline. Run the fixed real-claim acceptance suite and concurrent-search
smoke test after changing either value.

Do not restart an active research run merely to apply this work: research room
output is reset on application restart. Finish or safely stop the run first,
then restart with the verified implementation.
