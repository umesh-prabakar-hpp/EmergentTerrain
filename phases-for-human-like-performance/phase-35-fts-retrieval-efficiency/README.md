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

## Tool-schema regression guard

On 2026-07-25, a live run suppressed twelve drafts before their professor
tool loops could begin because the optional `search_literature` description
embedded a JSON array example with quotes that were escaped for Java but not
for the emitted JSON. `ResearchNarrationService.validateProfessorToolsJson`
correctly rejected that schema, and the empty traces then triggered the
`missing-methods` safety gate. This was a schema-construction failure, not a
retrieval, sidecar, or provider failure.

Any conditionally advertised literature tool must therefore be covered by a
wired parse test. `MathToolSuiteSearchLiteratureTest` calls
`toolsJson(true, true)` with a corpus wired and parses the complete result with
Jackson; it must remain green whenever either the literature schema or tool
assembly changes.

## Affine-binding classification boundary

On 2026-07-25, a completed `richly_divisible_density` trace reported a 5.49%
relative-slack HELD result as `TIGHT HOLD`, then executed its same-grid
refutation. The publication gate correctly requires relative slack at most 5%,
so the manuscript's binding-regime wording was rejected despite the trace
being otherwise complete. `BindingExtensionPolicy.TIGHT_RELATIVE_SLACK` is now
the shared inclusive 5% boundary for verifier instructions and both affine
publication validators. `MathToolSuiteVerifyConjectureTest` pins 5.00% as
`TIGHT HOLD` and the 5.49% live-run shape as `LOOSE`; preserve those boundary
tests when changing affine evidence policy.

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

For a common but valid pair such as `graph isomorphism`, even a ranked
64-result body/theorem search can spend the budget scoring a very large posting
list. Phase 35 therefore tries the same validated pair in the theorem
statement `name` field first. This is a bounded latency optimization only: if
the name-field attempt does not issue evidence, the unchanged general
theorem-record candidate follows under the same aggregate budget and complete
512-row eligibility range. No result key is granted by the name-field match
until the ordinary record-evidence and full-text-presence checks pass.

This deliberately favors a no-match over a plausible-looking but irrelevant
citation. The selected expression and rejected candidate count are diagnostic
data, not paper evidence.

The boundary is `LiteratureQueryPlanner` → `LiteratureQueryPlan`. The model may
now supply exactly two semantic anchors through `search_literature`; the
planner validates them and emits only a bounded `AND_PAIR`. It cannot supply
raw FTS syntax or bypass the record-evidence and title-anchor policy before the
adapter queries or issues a key. The deterministic claim extractor remains a
failure-compatible fallback for callers that predate the structured tool
contract.

### 3.1 AI-first semantic judgment, deterministic publication invariants

Phase 35 is deliberately not a claim that deterministic token policies can
replace research judgment. The app is AI-first: a frontier model should
eventually identify a claim's actual subject, distinguish a meaningful
literature connection from lexical coincidence, assess novelty, and propose
the next discriminating computation or holdout.

The durable boundary is therefore **AI-led decision, deterministic
verification**:

- The `search_literature` tool now requires the AI to propose a structured
  research query: exactly two subject anchors, a claimed relation, and a short
  relevance rationale. The anchors must be single domain-bearing tokens found
  in the supplied specific claim; deterministic validation turns only that
  safe pair into a bounded `AND_PAIR`. Relation and rationale are retained for
  audit but never become executable FTS syntax. Malformed, procedural, or
  ungrounded AI anchors fail closed with no evidence issued. The query should
  operate on the hypothesis and registered prediction rather than generic
  narration prose.
- After `read_reference`, the AI can call `assess_reference_relevance` to
  record a `RELEVANT`, `UNCERTAIN`, or `NOT_RELEVANT` judgment. Its conclusion
  must name the issued `HF...` key and include a short exact excerpt returned
  by `read_reference`; the adapter rejects an unissued key or an excerpt that
  was not returned. The judgment is review metadata, not evidence by itself:
  fluent ungrounded relevance prose cannot issue a key, bypass the trace, or
  satisfy the artifact-backed gate.
- Deterministic code continues to own non-negotiable invariants: no raw FTS
  syntax, no key without an indexed match, no citation without an issued key,
  no artifact-backed label without a successful read and matching related-work
  citation, and no numeric research claim without an executed trace.
- An AI audit label is a review signal, not by itself a publication verdict.
  Its factual predicates must be independently checkable; this prevents a
  pure-mathematics finite census from being rejected solely because a model
  emitted `physical_mechanism`.

### 3.2 Literature evidence audit bundle

Every artifact now contains `evidence/literature-audit.json`, a derived,
checksummed index over the immutable raw `tool-trace.json`. It records, for
each literature search, the requested claim, structured AI proposal and its
argument-parse status, outcome, issued relevance-safe keys, and bounded result summary. For each
`read_reference` it records the requested and returned key, focused query,
whether that key was issued by an earlier safe search, and whether a matching
passage was actually read.

The final gate section then makes the complete evidence lifecycle inspectable:

`search claim → issued HF key → successful reference read → related-work citation → publication label`.

It also names issued-but-unread sources, read-but-uncited sources, malformed
arguments, unavailable corpus results, and missing publication requirements.
Accepted relevance-review calls and the cited/read/reviewed-key intersection
are recorded beside that chain, so a reviewer can distinguish a source merely
retrieved from one the research model actually inspected.
The report is an audit index, not a second source of truth: the raw trace and
the returned reference passage remain the evidentiary records. A malformed or
incomplete audit chain cannot manufacture evidence or satisfy the
artifact-backed gate.

The current procedural-token exclusions are tactical fail-closed protection
for demonstrated false-positive paths. If repeated reviews reveal a wider
family, replace lists of individual words with a more general, tested notion of
a domain-bearing subject anchor. The target is fewer special cases over time,
not an ever-growing denylist.

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

The 2026-07-25 P-b72c-77627904 review found a narrower title-less fallback
gap. A finite-census claim produced the procedural phrases `exact census` and
`itself establish`; because those ordinary reporting words occurred in
unrelated title-less astronomy records, the adapter issued false evidence. The
planner now excludes `exact`, `census`, `establish`, and `itself` from anchor
and pair generation, and the same terms are weak at the title-less acceptance
boundary. A legitimate title-less source still needs a three-anchor exact
subject match, such as `Dirichlet divisor error`; a procedural body overlap is
an honest no-evidence result.

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

## Deterministic post-search read

`app.research.literature.phase34-sqlite.deterministic-post-search-read.enabled` is enabled in
the live configuration. After the professor loop but before student revision, it appends exactly
one `read_reference` call only when the trace has an `EVIDENCE` result with an issued `HF...` key,
the originating search has a structured nonblank claim, and no earlier read was attempted. The
first issued key in trace order and that exact claim are the fixed selection/query rule.

It does not search again, retry a failed read, make an AI or deterministic relevance judgment,
write Related Work, or change a label. The purpose is only to expose already validated source
text to the student revision. The normal successful-read plus matching-`[HF...]` citation gate
remains mandatory for an artifact-backed paper candidate. Set the flag false for rollback.

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
