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

## Import and index plan

The downloaded files are raw corpus inputs, not a live application search
index. Do not copy them into Git or present a retrieval hit as a citation.

### Input handling

- The UW download supplies theorem statements, slogans, and paper metadata in
  Parquet files. Import it as the fast claim-overlap index: “has this theorem,
  constant, or statement already appeared?”
- Marin no-problem is compressed JSONL full text. Each record carries an ar5iv
  identifier, source, format, and rendered Markdown text. Stream its shards;
  do not load the corpus into memory.
- Marin covers arXiv broadly, not only mathematics. Join each arXiv identifier
  to authoritative metadata and retain mathematics records before building the
  math index. The no-problem corpus must not be indexed wholesale for a
  mathematics-only retrieval path.

### Linked retrieval layers

Build two linked, versioned indexes:

1. **Theorem/claim index** over the UW records for high-recall novelty and
   overlap screening.
2. **Full-text passage index** over filtered Marin papers for `read_reference`,
   source-grounded related work, and citation verification.

A theorem hit should resolve to its source paper and relevant full-text
passage. The final paper must distinguish an established result, an empirical
observation, and a new conjecture; a retrieval hit alone is not enough to
establish any of those labels.

### Derived-data provenance and rollout

Keep raw downloads immutable under `~/.emergentterrain/corpus-hf-math/`. Write
derived indexes separately and attach a manifest containing dataset/revision,
input checksums, importer version, retained-document count, and index build
time.

Wire the new indexes into `search_literature` and `read_reference` behind a
feature flag. Before enabling them for publication-quality labels, evaluate a
fixed mathematics claim set for correct theorem matches, correct source
passages, and no fabricated citation keys. This import/index integration is a
subsequent Phase 34 implementation task; the current download script only
stages the inputs.

## Host capacity and index placement

Measured after the initial downloads (2026-07-13), this host has 7.6 GiB RAM
(about 4.4 GiB available during the app session), 15 GiB unused swap, four CPU
cores, and about 382 GiB free disk. The staged Hugging Face corpora occupy
about 3.37 GiB compressed. Parsed records, rendered full text, and postings
will be materially larger than the downloads.

Use a **storage-backed inverted index**, not a full in-memory corpus. Keep raw
downloads immutable under `~/.emergentterrain/corpus-hf-math/` and write
derived text, metadata, and index segments under a separate location such as
`~/.emergentterrain/indexes/math-literature/`.

- Prefer disk/memory-mapped postings with a bounded hot-query cache of roughly
  256–512 MiB.
- Do not treat the 15 GiB swap allocation as usable working memory for routine
  retrieval; swap is only a safety buffer.
- Reserve an initial 20–30 GiB disk budget for derived artifacts, then measure
  a mathematics-only pilot build before scaling to the full corpus.
- Build in resumable batches with low parallelism so the Spring JVM and local
  sidecars remain responsive on the four-core host.

The theorem corpus can be compacted aggressively, but its parsed form plus a
full-text corpus would still compete with the JVM and sidecars. One
storage-backed retrieval implementation for both sources is the required
baseline.
