# Third-party provenance

This register distinguishes project-authored components from external code, data, and reference
material. The repository-wide default license is AGPL-3.0-or-later; an entry here never changes an
upstream license.

## Project-authored imported histories

The Rust GPU engine and Julia math sidecar were created in project-controlled local repositories,
then their histories were imported into this monorepo. They are not third-party code and are not
Git submodules.

| Monorepo path | Project-owned predecessor source | First standalone commit | First monorepo commit | Evidence |
|---|---|---|---|---|
| `engines/gpu-engine/` | EmergentTerrain GPU engine — imported project-owned source; no external remote required | `79ed6eecd8c7418f4268c67891f602f9ca56faa5` — 2026-02-15, `Initialize GPU engine gRPC service` | `4ce341c816916e202669ec6534a9efb5a9224e62` — same date and subject | Matching chronological history through the 2026-05-30 timestamped-logging commit |
| `services/julia-math-sidecar/` | Julia math sidecar — imported project-owned source; no external remote required | `1b4efba74fe06136985fec6802ffa9904d30d1b1` — 2026-05-20, `Phase 22.1 — bootstrap julia-math-sidecar` | `455ad7e5398220aa45f555a8807505278cf4769c` — same date and subject | Matching chronological history through the 2026-07-09 grounding-performance commit |

The project operator represents that they hold the right to license these project-authored sources
under this repository's AGPL-3.0-or-later license. Their dependencies remain separately licensed;
see [`sbom/README.md`](sbom/README.md).

## Adapted external material

| Material | Upstream revision and license | Use in this repository | Required treatment |
|---|---|---|---|
| `data-to-paper` `check_numeric_values.py` | [`Technion-Kishony-lab/data-to-paper`](https://github.com/Technion-Kishony-lab/data-to-paper), `81df14c4b9600466e645c3b2b336cc54daa3df3a`, MIT | Four numeric-tolerance policy rules adapted in `ManifestTraceabilityValidator` | Keep the MIT notice in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and this source/revision record. |

## Reference-only material

Phase documents may link to or summarize external work. A link, a short factual description, or
an independently implemented general idea does not copy source code. Before copying code, tests,
documentation, figures, datasets, or model weights, add an entry above with the immutable source
revision, license, scope, and required notices.

The phase plans no longer retain full-text paper PDFs or raw corpus text. They link to the original
source instead. Literature/download rules are in [`docs/literature-data-licenses.md`](docs/literature-data-licenses.md).

## Release checklist

Before a release, verify that:

1. every copied or adapted item is listed here and has its required notice;
2. generated research, LLM traces, logs, and corpus downloads are absent from Git;
3. [`sbom/README.md`](sbom/README.md) inventories have been regenerated, reviewed, and committed;
4. `./scripts/build-game-release.sh` was run from that clean commit, preserving the matching source
   archive and separate replaceable runtime JARs; and
5. license terms are reviewed for the exact binaries, containers, data, and model weights being distributed.
