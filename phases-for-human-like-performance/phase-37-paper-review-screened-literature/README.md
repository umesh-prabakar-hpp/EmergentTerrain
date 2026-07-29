# Phase 37 — Paper-review-screened literature index

## Goal

Make a relevant, text-only source found during a paper-review pass available to normal literature
retrieval without rebuilding the large Phase 34 primary FTS index for every source.

The new **paper-review-screened literature index** is a separate SQLite/FTS5 database. Its name
records both provenance (found and screened during paper review) and intended use (literature
retrieval), without implying that a source is already evidence or publication-ready.

## Data lifecycle

```text
paper-review web discovery
  → provenance manifest + SHA-256-validated text
  → RETAINED_FOR_IMPORT
  → paper-review-screened literature index (incremental FTS insert)
  → merged Phase 35 search result
  → issued HF key → read_reference → matching Related Work citation
  → ordinary publication gate
```

The first two arrows are offline operator work. The database is read-only on the application
request path; no web download, source mutation, or index write happens during a paper run.

## Import

Run this only after the paper-review manifest contains one or more `RETAINED_FOR_IMPORT` sources.
It does not require or rebuild the primary Phase 34 corpus index.

```bash
python3 scripts/prepare-paper-review-screened-literature-index.py \
  --staged-text-manifest research/<room>/literature-staging/staging-manifest.json

stat -c '%n:%s:%Y' \
  /home/fedorauser/.emergentterrain/indexes/math-literature-paper-review-screened/literature.db
```

The importer accepts only source files recorded by the manifest, validates each SHA-256, refuses
text replacement for an existing paper ID, stores manifest/source provenance, and inserts exactly
one matching FTS row per new source. It is idempotent for matching source text.

## Retrieval and merge contract

Set `app.research.literature.paper-review-screened.enabled=true` only after the importer has
created a complete screened index and the acceptance checks pass. The Phase 34 adapter then queries
the primary index and the screened index with the same deterministic query plan and relevance-safe
title/record gates.

The two result lists are merged. Raw SQLite FTS rank values are not compared across databases,
because each corpus has different document-frequency statistics. Each index retains its own rank
order; the adapter interleaves equal local ordinals with primary-index precedence only on a tie,
then deduplicates paper IDs. Search output states the producing index for every issued opaque
`HF...` key. `read_reference` resolves the key back to that same index.

There is no evidence shortcut: a screened-index search result still needs a successful read and a
matching Related Work citation to satisfy source-grounded publication requirements. Disabling the
flag restores primary-index-only retrieval.

## Rollout checks

1. Run the targeted `Phase34SqliteLiteratureTest` tests, including the merged-result/read test.
2. Import a real retained source into the screened index.
3. Start one normal app run with the flag enabled and verify a screened hit is labeled
   `index: paper-review-screened`.
4. Verify that a search-only trace cannot reach an artifact-backed publication label; the source
   must still be returned by `read_reference` and cited in Related Work.
5. Run `./scripts/run-phase35-artifact-smoke.sh` before treating the path as publication-ready.
