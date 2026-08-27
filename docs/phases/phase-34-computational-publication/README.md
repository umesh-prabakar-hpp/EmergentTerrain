# Phase 34 — Computational publication quality

This phase makes the app's publication language match its actual evidence.
Emergent Terrain currently performs non-physical, computational research. A
clean `PUBLISHED` workflow result is therefore not automatically described as a
human-quality paper.

## Labels

`status` remains the workflow disposition (`PUBLISHED`, `NEEDS_REVISION`,
`REJECTED`, and so on). `publicationLabel` is a separate reader-facing tier:

- `INTERNAL_RESEARCH_NOTE`: missing a locked claim or executed trace.
- `COMPUTATIONAL_RESEARCH_NOTE`: has a pre-registration and trace, but does
  not yet meet every computational-paper gate.
- `ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE`: has all gates below **and**
  a clean `PUBLISHED` workflow status. This is a candidate for human review,
  not a claim of external replication or acceptance.

The final paper's `publicationLabel` is authoritative. A paper that otherwise
meets every computational gate but remains in `NEEDS_REVISION`, `REJECTED`, or
another non-`PUBLISHED` workflow state is a `COMPUTATIONAL_RESEARCH_NOTE`.

## Gates

The formulation stage may emit `computational_study_plan` in the locked
pre-registration. It must declare distinct `discovery_scope` and
`confirmation_scope`, a fixed `selection_rule`, a `sensitivity_plan`, and a
`reproduction_command`.

The publication phase also requires an executed tool trace and source-grounded
related work. A candidate must have a successful `read_reference` result for a
source key returned by `search_literature`, and the final related-work text must
cite that same key. Failed, empty, unrelated, or uncited tool results do not
satisfy this gate.

The phase intentionally does **not** treat an automated panel or a negative
corpus search as a novelty proof.

## Artifact bundle

Each publication writes `research/<room>/artifacts/<paper-id>/` with:

- rendered paper, results, pre-registration, and raw tool trace;
- a SHA-256 manifest and recorded source revision (`GIT_COMMIT` when supplied);
- a reproduction-command wrapper that prints the pre-registered command but
  never executes LLM-supplied shell text on the application server.

The bundle's `README.md` and `manifest.json` are written from the final paper
label, including a workflow-status downgrade, so they remain aligned with the
reader-facing result.

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

The corpus downloads and resulting indexes are local data, not AGPL project content. The UW
dataset is CC BY-SA 4.0; Marin's Markdownified ar5iv data is C-UDA-1.0 and is restricted to
computational use. Do not copy either corpus or its derived SQLite index into Git or a source
release. See [the literature data policy](../../archive/literature-data-licenses.md).

## Import and index preparation

The downloaded files are raw corpus inputs, not a live application search
index. Do not copy them into Git or present a retrieval hit as a citation.

`scripts/prepare-hf-math-index.py` is the first operational preparation step.
It creates a resumable SQLite FTS5 index outside the repository at
`~/.emergentterrain/indexes/math-literature/literature.db`; its SQLite page
cache is bounded to about 128 MiB. It requires PyArrow only for reading the
three downloaded Parquet files:

```bash
python3 -m pip install --user pyarrow
./scripts/prepare-hf-math-index.py --stage metadata
./scripts/prepare-hf-math-index.py --stage theorems
# Scan a controlled number of compressed Marin shards; re-run to resume.
./scripts/prepare-hf-math-index.py --stage fulltext --fulltext-shard-limit 20
```

Use `--stage all` only when the host can give the streaming full-text pass the
required I/O time. The script records completed Marin shards in the database,
so a stopped full-text pass resumes without rereading completed shards. The
shard pass writes only the `fulltext` source rows; after every available shard
is committed it bulk-builds `fulltext_fts` in one transaction. This avoids
rewriting an ever-larger FTS index for every imported record. The adapter
considers the index unavailable until the `fulltext_fts_complete=true` marker
is written. If an interrupted completed shard pass needs only that final step,
run `./scripts/prepare-hf-math-index.py --stage fulltext-fts`. The script also
writes `MANIFEST.json` next to the database with counts, selection rule, and
stage completion state. The raw downloads remain immutable.

### Initial build baseline (2026-07-14)

The initial UW import and theorem FTS build completed successfully: the local
manifest records 209,777 papers and 1,341,083 theorem statements. The derived
SQLite database is about 3.4 GiB. At that point, the broad Marin input was
present locally as 392 compressed JSONL shards (about 3.4 GiB), but no Marin
shard had yet been imported into `fulltext`/`fulltext_fts`.

This was an intentional, resumable baseline rather than a completed
multi-subject rebuild. Resume it with a controlled batch such as
`./scripts/prepare-hf-math-index.py --stage fulltext --fulltext-shard-limit 20`;
repeat the command until all available shards are recorded, or omit the limit
when an uninterrupted full pass is appropriate. The observed host has about
375 GiB free disk, so capacity does not currently block retaining the broad
source corpus and its derived index.

### Import-performance checkpoint (2026-07-15)

The first whole-corpus pass reached a durable 167/392-shard checkpoint with
148,252 stored full-text documents. Its per-record delete/insert operations on
the growing FTS table made later shards take roughly 26–32 minutes, so it was
safely stopped between committed shards. The resumable importer now stores
each remaining shard's source rows first, then performs one final FTS rebuild
only after all source shards are present. The optimized worker resumes from
the 167-shard ledger; use SQLite committed-shard counts rather than an old
manifest for live progress.

### Input handling

- The UW download supplies theorem statements, slogans, and paper metadata in
  Parquet files. Import it as the fast claim-overlap index: “has this theorem,
  constant, or statement already appeared?”
- Marin no-problem is compressed JSONL full text. Each record carries an ar5iv
  identifier, source, format, and rendered Markdown text. Stream its shards;
  do not load the corpus into memory.
- Marin covers arXiv broadly, not only mathematics. The preparer retains the
  full broad source because the product target includes future computational
  physics, chemistry, and biology work. Where an arXiv identifier occurs in UW,
  it carries UW's primary category; the remaining Marin rows are explicitly
  marked `ar5iv-unclassified` until a subject-metadata enrichment pass is
  available. Mathematics is the current query-time activation scope, not a
  destructive corpus filter.

### Linked retrieval layers

Build two linked, versioned indexes:

1. **Theorem/claim index** over the UW records for high-recall novelty and
   overlap screening.
2. **Full-text passage index** over broad Marin records for future
   multi-subject retrieval. Mathematics is selected at query time; records
   without UW category metadata remain explicitly `ar5iv-unclassified` until
   metadata enrichment. This index is the planned input to `read_reference`,
   source-grounded related work, and citation verification.

A theorem hit should resolve to its source paper and relevant full-text
passage. The final paper must distinguish an established result, an empirical
observation, and a new conjecture; a retrieval hit alone is not enough to
establish any of those labels.

### Derived-data provenance and rollout

Keep raw downloads immutable under `~/.emergentterrain/corpus-hf-math/`. The
bootstrap records each dataset's requested revision and the resolved commit
from the Hugging Face download cache. The preparer writes a manifest containing
dataset revisions, SHA-256 values for raw inputs, importer version and script
digest, retained-document count, and index build state. It caches input hashes
only while every raw-file path, size, and modification time remains unchanged;
any changed input triggers a fresh hash pass. A long-running full-text stage
writes `MANIFEST.json` only after the stage returns, so query SQLite directly
for live committed-shard progress.

### Implementation checkpoint (2026-07-16)

Completed during the current Phase 34 pass:

- Publication classification now requires a clean `PUBLISHED` workflow status
  and source-grounded related work: a successful `read_reference` result must
  correspond to a key returned by `search_literature` and cited in the paper.
- Artifact `README.md` and `manifest.json` take their publication label from
  the final paper state, eliminating the former pre-downgrade label mismatch.
- The Hugging Face bootstrap records requested and resolved revisions; the
  importer records immutable raw-input SHA-256 values, revision metadata, and
  an importer/script digest in its v2 manifest.
- The completed 392-shard Marin import contains 366,232 full-text documents;
  its one-shot `fulltext_fts` build completed successfully.
- The read-only Java SQLite adapter is enabled through
  `app.research.literature.phase34-sqlite.enabled=true`. It searches
  `theorem_fts`, reads `fulltext_fts`, and issues bounded opaque `HF...` keys
  that cannot be fabricated into a readable source id.
- The active Marin full-text import remains a single external writer. It first
  checkpoints raw full-text rows per shard and builds the full-text FTS table
  once at completion; until the resulting completion marker is present, the
  adapter is not advertised. The adapter opens independent read-only
  connections and does not alter that import or its raw corpus.

The adapter is operationally enabled. Before treating its output as accepted
for live publication labels:

1. Let the Marin import and its final one-shot `fulltext_fts` build complete,
   then verify the v2 provenance manifest for the finished corpus.
2. Run a fixed, real-mathematics claim set against the completed index and
   record expected theorem sources, matching full-text passages, and rejection
   of fabricated keys.
3. Run a paper-generation smoke test and verify that the related-work citation,
   tool trace, artifact package, and final publication label agree. Roll the
   flag back to `false` if the claim set or smoke test fails.

#### Real-index acceptance progress

`Phase34RealIndexAcceptanceTest` is opt-in and exercises the Java adapter
against the completed local database when
`PHASE34_REAL_INDEX_DIR=~/.emergentterrain/indexes/math-literature` is set.
Its fixed five-claim set spans number theory, graph/combinatorics, partition
theory, commutative algebra, and functional analysis. Each fixture verifies an
expected theorem source, its own issued key, and an expected full-text passage;
the test also verifies fabricated-key rejection. **Passed on 2026-07-16**
against the recorded 366,232-document local index (five fixtures; about
59 seconds). Re-run it with:

```bash
PHASE34_REAL_INDEX_DIR=~/.emergentterrain/indexes/math-literature \
  mvn test -Dtest=Phase34RealIndexAcceptanceTest
```

The paper-generation smoke test remains required before declaring the live
adapter publication-ready.

The read-only SQLite adapter is enabled through
`app.research.literature.phase34-sqlite.enabled`. It routes
`search_literature` through `theorem_fts` and
`read_reference` through `fulltext_fts`, issuing opaque `HF...` keys that can
only be read after the corresponding search returned them. Before enabling it
for publication-quality labels, evaluate a fixed mathematics claim set for
correct theorem matches, correct source passages, and no fabricated citation
keys. The completed import is a prerequisite, not a substitute, for that
acceptance suite.

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
