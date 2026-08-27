# Literature data and redistribution policy

EmergentTerrain's AGPL license covers this repository's code. It does not license downloaded
papers, literature datasets, derived indexes, or third-party notices. Keep all such inputs local
and outside Git unless a separately reviewed distribution decision says otherwise.

## Local corpora

The Phase 34 literature workflow has two user-downloaded inputs:

| Dataset | Local role | Terms | Repository rule |
| --- | --- | --- | --- |
| `uw-math-ai/theorem-search-dataset` | Theorem/metadata retrieval | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | Keep raw data and indexes local; preserve dataset and source-paper attribution and license metadata for any approved reuse. |
| `marin-community/ar5iv-no-problem-markdown` | Full-text retrieval | [C-UDA-1.0](https://spdx.org/licenses/C-UDA-1.0.html) | Computational use only. Do not commit or package raw text or derived indexes. A result may contain no more than a de minimis portion of source data. |

Download provenance belongs in the local `SOURCE.txt` and SQLite manifest, not in Git. The
bootstrap script and importer write those records below `~/.emergentterrain/`.

## Paper-review staging manifests

New manifests use `schema_version: 3`. Every `RETAINED_FOR_IMPORT` source must record:

```json
{
  "license_kind": "CC0-1.0",
  "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
  "redistribution_allowed": true
}
```

Allowed `license_kind` values are `CC0-1.0`, `CC-BY-4.0`, `CC-BY-SA-4.0`, `C-UDA-1.0`,
`ARXIV-NONEXCLUSIVE-1.0`, and `UNKNOWN`. A schema-2-or-earlier source remains indexable for
local review but is treated as `UNKNOWN` and `redistribution_allowed: false`.

`ARXIV-NONEXCLUSIVE-1.0` and `UNKNOWN` must always set `redistribution_allowed: false`. An arXiv
download being publicly readable does not itself grant downstream redistribution rights. Check the
specific version's rights link and the [arXiv license policy](https://info.arxiv.org/help/license/index.html).

`redistribution_allowed: true` is a reviewed distribution decision, not an assertion that source
text becomes AGPL. Preserve the source's notice, attribution, and its own license conditions.

## Release check

Run this before tagging or publishing a source archive:

```bash
python3 scripts/check-literature-release.py
```

It fails if Git tracks screened staged source text, a local corpus directory, or a SQLite
literature index. When an export would include staged source text, also
validate each manifest:

```bash
python3 scripts/check-literature-release.py \
  --export-manifest /path/to/staging-manifest.json
```

That mode rejects legacy manifests and sources without explicit redistribution permission.
