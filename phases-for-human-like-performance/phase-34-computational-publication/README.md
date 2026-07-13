# Phase 34 — Computational publication quality

This phase makes the app's publication language match its actual evidence.
Emergent Terrain currently performs non-physical, computational research. A
clean `PUBLISHED` workflow result is therefore not automatically described as a
human-quality paper.

## Labels

`status` remains the workflow disposition (`PUBLISHED`, `NEEDS_REVISION`,
`REJECTED`, and so on). `publicationLabel` is an independent reader-facing
tier:

- `INTERNAL_RESEARCH_NOTE`: missing a locked claim or executed trace.
- `COMPUTATIONAL_RESEARCH_NOTE`: has a pre-registration and trace, but does
  not yet meet every computational-paper gate.
- `ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE`: has all gates below. This
  is a candidate for human review, not a claim of external replication or
  acceptance.

## Gates

The formulation stage may emit `computational_study_plan` in the locked
pre-registration. It must declare distinct `discovery_scope` and
`confirmation_scope`, a fixed `selection_rule`, a `sensitivity_plan`, and a
`reproduction_command`.

The publication phase also requires an executed tool trace and source-grounded
related work: both `search_literature` and `read_reference` must have run and
the final paper must contain a related-work section. The phase intentionally
does **not** treat an automated panel or a negative corpus search as a novelty
proof.

## Artifact bundle

Each publication writes `research/<room>/artifacts/<paper-id>/` with:

- rendered paper, results, pre-registration, and raw tool trace;
- a SHA-256 manifest and recorded source revision (`GIT_COMMIT` when supplied);
- a reproduction-command wrapper that prints the pre-registered command but
  never executes LLM-supplied shell text on the application server.

The bundle is an audit package now. A future domain adapter can supply a
reviewed executable runner for its allowed computation engine; only then
should the reproduction command be run automatically in a sandbox.

## Mathematics literature bootstrap

Phase 34 owns the operational Hugging Face download path for two complementary
sources, each placed in its own dataset-specific directory by
`scripts/bootstrap-hf-math-literature.sh`:

- `uw-math-ai/theorem-search-dataset` is the default. It supports fast
  theorem/claim-overlap retrieval, but is not full paper text.
- `marin-community/ar5iv-no-problem-markdown` is the full-text companion. It
  contains clean ar5iv Markdown conversions and is the preferred source for
  reading and positioning a claim against a primary paper.

Download the companion with:

```bash
HF_DATASET=marin-community/ar5iv-no-problem-markdown \
  ./scripts/bootstrap-hf-math-literature.sh
```

Both sources are public and tokenless; pin `HF_REVISION=<commit>` for a
reproducible corpus snapshot. The theorem corpus finds candidates; the
no-problem corpus supports careful reading. Neither is evidence of novelty by
itself, and the final paper must still use `read_reference` before making a
load-bearing literature claim.
