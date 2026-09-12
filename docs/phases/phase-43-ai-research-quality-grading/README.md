# Phase 43 — AI-led research quality grading and protocol reuse

**Status: IN PROGRESS — durable AI-assessment history and quality-aware duplicate admission are
implemented; AI adjudication invocation, legacy backfill, and UI/API presentation remain pending.**

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

It returns structured JSON containing the proposed grade, rationale, cited evidence references,
unmet requirements, contradictions, and a rubric version.  The adjudicator must be separate from
the narrator and must not use its own prose as sufficient evidence.  A multi-reviewer AI panel
may challenge the adjudication; unresolved material disagreement prevents promotion above
`RESEARCH_DRAFT`.

Deterministic validators still have an important but narrower role: they verify that evidence
references exist, prevent malformed assessments, preserve nulls, and reject impossible state
transitions.  They do **not** assign the human-quality or beyond-human grade.

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
   evidence references and disagreement outcome.
5. Replace the duplicate-protocol guard with the quality-aware admission policy above and record
   each reuse rationale.
6. Surface workflow status, current quality grade, rubric version, evidence links, and blockers
   separately in the UI and research API.
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
