# Phase 43 — AI-led research quality grading and protocol reuse

**Status: IN PROGRESS — durable AI-assessment history, quality-aware duplicate admission, default-on
note/draft adjudication, finite legacy backfill, and a read-only v1 quality projection are
implemented. The Research Lab now renders workflow and quality state separately. Independent-
replication promotion is explicitly deferred. The Research Lab can also display the retained
read-only assessment history for each paper. Exact-protocol reuse now has durable provenance.**

## Purpose

EmergentTerrain's aim is to produce human-quality research and, eventually, work that exceeds
defined human benchmarks.  Current persisted `ResearchPaper` records are useful, traceable
research outputs, but their workflow status (`PUBLISHED`, `NULL_RESULT`, `NEEDS_REVISION`, or
`REJECTED`) does not establish scholarly quality.  In particular, a fluent narration or a
successful computation must not be represented as a human-quality paper by default.

This phase introduces a separate, AI-led assessment of research quality.  The AI assesses the
complete retained evidence record; computation supplies evidence, but does not determine the
quality grade mechanically.  The same distinction allows a protocol previously used for a note
or draft to be rerun with stronger evidence rather than being permanently blocked as a duplicate.

## Quality model

Each output has two independent descriptions:

```text
workflow status       what happened operationally
quality assessment    what the AI can justify from the retained evidence
```

The initial quality grades are:

| Grade | Meaning |
|---|---|
| `UNASSESSED` | No complete AI assessment exists, including historical outputs with incomplete retained evidence. |
| `RESEARCH_NOTE` | A traceable internal result: hypothesis/protocol, computation trace, and result are retained. A null result remains a valid note. |
| `RESEARCH_DRAFT` | A coherent candidate: methods, narrated claim, limitations, literature grounding, and AI review are present, but publication-quality gates remain open. |
| `HUMAN_QUALITY_PAPER` | The AI assessment concludes that the defined scholarly rubric is met, including independent replication and complete evidence support. |
| `BEYOND_HUMAN_BENCHMARK` | The AI assessment concludes that the human-quality rubric is met and that a separately declared, independently evaluated expert benchmark has been exceeded. |

`HUMAN_QUALITY_PAPER` and `BEYOND_HUMAN_BENCHMARK` are evidence claims, not rewards for good
prose or successful runs.  A result may move upward only after additional evidence is retained;
new contradictory or failed-replication evidence may lower an earlier assessment.

## AI-led assessment

The grade is decided by a dedicated AI adjudication step, not by a numerical score threshold or
by computation alone.  The adjudicator receives a bounded, versioned evidence bundle:

- locked hypothesis and protocol;
- computation inputs, traces, outputs, failures, and null findings;
- narration, cited passages, and citation-validation results;
- independent replication record and its declared acceptance tolerance;
- independent AI review findings, including limitations and unresolved objections;
- a declared benchmark record when the proposed grade is `BEYOND_HUMAN_BENCHMARK`.

It returns structured JSON containing the proposed grade, rationale, unmet requirements,
contradictions, and a rubric version. The service—not the model—binds the exact bounded evidence
bundle it supplied to the durable assessment, so an AI response cannot introduce an external
paper, replication, or reviewer as support. The adjudicator must be separate from the narrator
and must not use its own prose as sufficient evidence. A multi-reviewer AI panel
may challenge the adjudication; unresolved material disagreement prevents promotion above
`RESEARCH_DRAFT`.

Deterministic validators still have an important but narrower role: they verify that evidence
references exist, prevent malformed assessments, preserve nulls, and reject impossible state
transitions.  They do **not** assign the human-quality or beyond-human grade.

### Initial implemented scope

The v1 adjudicator is enabled by default after a paper is durably persisted. It receives paper,
protocol, narration, and panel evidence and may assign only `RESEARCH_NOTE` or `RESEARCH_DRAFT`.
It binds that exact bundle server-side and rejects a malformed response or any attempt to award a
settled grade. The paper remains persisted if the AI call or structured response fails.
Human-quality and beyond-human promotion stays unavailable until independent replication and
benchmark records are present in the evidence bundle.

### Deferred: immutable quality-grade replication evidence

The game already has a legacy anomaly-replication path (`ReplicationService`). It chooses a
different bot, reruns a deterministic computation, and marks a paper `DISPUTED` when a fixed
score-gap threshold is exceeded. That remains useful operational research evidence, but it is
not a Phase 43 independent-replication record: its queue is in memory, it does not preserve an
append-only paper-linked evidence bundle, it has no protocol-fingerprint/tolerance record, and it
does not participate in AI quality promotion.

Creating the separate immutable, promotion-grade replication record and its fail-closed validator
is **deferred**. Do not infer `HUMAN_QUALITY_PAPER` or `BEYOND_HUMAN_BENCHMARK` from the legacy
replication workflow. The v1 adjudicator remains deliberately limited to `RESEARCH_NOTE` and
`RESEARCH_DRAFT`.

`./scripts/backfill-research-quality.sh [1..25]` is the separate, finite legacy path. It starts
no web server, scheduler, research worker, or provider-selection probe; selects only `UNASSESSED`
outputs; never rewrites an existing assessment; and exits after the requested batch (default one).
It uses the same v1 note/draft-only adjudicator, so it cannot inflate historical results into
human-quality claims.

The existing `GET /api/v1/research/rooms/{roomId}/papers` response now includes a required
`quality` object. It is the latest immutable assessment, or an explicit `UNASSESSED` projection
when no assessment exists. The response exposes the grade, rubric/model provenance, assessment
revision/time, unmet requirements, and contradictions; it does not expose mutable entities,
prompt text, or any ability to alter an assessment.

`GET /api/v1/research/rooms/{roomId}/papers/{paperId}/quality-assessments` exposes the immutable
history newest-first. It returns bounded evidence-reference, unmet-requirement, and contradiction
lists only for a paper in that room; an absent, cross-room, or unassessed paper yields an empty
list. The Research Lab loads this read-only history on demand. It does not expose prompts,
research payloads, mutable entities, or a mutation route.

## Durable assessment record

The research service owns an append-only `research_paper_quality_assessments` record keyed by
paper ID and assessment revision.  It stores at least:

```text
paperId, assessmentRevision, rubricVersion, assessedAt,
grade, rationale, evidenceReferences,
unmetRequirements, contradictions, reviewerSummary,
replicationSummary, benchmarkSummary
```

An assessment is historical evidence, not a mutable paper field.  The current grade is the latest
valid assessment under the active rubric, while prior assessments remain auditable.  Historical
records begin `UNASSESSED`; a conservative backfill may award only `RESEARCH_NOTE` when the
minimum traceable evidence is actually present.  It must never infer a higher grade from existing
status, peer score, or narration alone.

## Protocol reuse policy

The current duplicate-protocol guard runs before draft allocation.  It protects against repeated
identical studies, but it is too broad for a system that is expected to improve a research note
into a human-quality result.

Replace the blanket rule with this policy:

```text
matching prior protocol
  ├── tied to HUMAN_QUALITY_PAPER or BEYOND_HUMAN_BENCHMARK
  │     → decline as a settled duplicate, unless a later phase defines a distinct replication
  │       or challenge protocol
  └── tied only to UNASSESSED, RESEARCH_NOTE, or RESEARCH_DRAFT outputs
        → allow computation, narration, review, and publication to proceed
```

Allowed reuse must retain the full provenance of the earlier protocol and identify why it is being
rerun: new evidence range, independent replication, improved literature grounding, reviewer
remediation, changed model/provider, or a strengthened protocol revision.  Exact reuse without a
stated improvement remains visible to the AI adjudicator and may be graded as a note or rejected
by review; it is not silently presented as novel.

### Implemented v1 reuse provenance

For the currently implemented conjecture-investigation allocation path, an allowed exact reuse is
locked into the new pre-registration and persisted with the resulting paper as structured JSON:
the source paper ID, source protocol fingerprint, a system-derived reason, and whether the
protocol changed. The v1 reasons are `OPEN_QUESTION_CONTINUATION`,
`REVIEWER_REMEDIATION_REQUESTED`, and `UNSETTLED_IDENTICAL_PROTOCOL`.
`REVIEWER_REMEDIATION_REQUESTED` is emitted only when the source panel retained a consensus
contradiction or at least two recommendations; it records a request for remediation, never a
claim that the later run resolved it. None asserts a stronger evidence range, new literature, or
novelty that the allocation path cannot prove. The provenance is included as a server-bound input
to the AI quality assessment. It is evidence only and must not change selection, computation,
narration, publication, protocol-settlement policy, or quality promotion.

When that reviewer-remediation reason is used, the provenance retains at most five source-panel
requests (each capped at 400 characters). The Research Lab displays them as requested remediation,
not as completed work.

The standard paper read model exposes this provenance when present, and the Research Lab renders
the source and reason separately from workflow status and quality grade. The remote adapter reads
the optional database field through PostgreSQL's row-to-JSON projection, so an older transition
database that does not yet have the field remains readable and simply reports no provenance.

The broader user- or reviewer-declared improvement reasons listed above remain future work. They
must be separately captured and validated before they are represented as facts.

This rule is about **admission**, not automatic promotion.  It deliberately permits the full
cycle—computation, narration, review, and persistence—when no human-quality-or-beyond result
settles the protocol.  It does not permit a cycle to overwrite prior evidence, suppress null
results, or claim novelty merely because it reran.

## Delivery plan

1. Define the rubric and structured AI adjudication contract, including prompt/version ownership
   and adversarial fixtures.
2. Add append-only assessment storage and read APIs without changing generation behavior.
3. Backfill legacy outputs conservatively to `UNASSESSED` or evidence-supported
   `RESEARCH_NOTE` only.
4. Add the AI adjudication after review/replication evidence is available; persist its complete
   evidence references and disagreement outcome. **The note/draft-only v1 adjudicator is
   implemented; promotion-grade independent replication is deferred.**
5. Replace the duplicate-protocol guard with the quality-aware admission policy above and record
   each reuse rationale. **The implemented conjecture-investigation exact-reuse path persists
   source/fingerprint and its limited system-derived reason; broader declared-improvement reasons
   remain future work.**
6. Surface workflow status, current quality grade, rubric version, evidence links, and blockers
   separately in the UI and research API. **The v1 API projection and Research Lab workflow/
   quality display are implemented, including an on-demand immutable assessment-history detail
   view.**
7. Define and independently evaluate a public expert benchmark before allowing
   `BEYOND_HUMAN_BENCHMARK`.

## Acceptance criteria

- A valid null result can be a `RESEARCH_NOTE`; it is never discarded simply for being null.
- Neither computation success, peer score, narration fluency, nor `PUBLISHED` status alone can
  assign `HUMAN_QUALITY_PAPER`.
- Only the dedicated AI adjudicator can assign or change a quality grade; deterministic code only
  validates evidence shape and state-transition safety.
- A malformed or unsupported AI assessment is rejected and cannot promote an output.
- A protocol associated only with a note, draft, or unassessed output reaches computation,
  narration, review, and persistence again, with reuse provenance retained.
- A protocol associated with a human-quality or beyond-human result is declined as a duplicate,
  except for a separately specified replication or challenge pathway.
- A historical record cannot be relabeled as human-quality without an assessment evidence bundle.
