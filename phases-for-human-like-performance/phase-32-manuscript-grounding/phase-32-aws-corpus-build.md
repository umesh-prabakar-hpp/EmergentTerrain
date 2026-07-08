# Phase 32 §32.5e — AWS transient build for bulk full text (ar5iv + jedibear/S2ORC)

**Status:** runbook (2026-07-08). Scripts written + validated (`scripts/aws/`); not yet executed.
Builds the broad, math-fidelity full-text layer that the credential-free local path (unarXive open
subset → 1.7%) can't reach, on a throwaway cloud box, then ships a compact result home.

## Why AWS (and only for the build)

The final filtered corpus is small (~2–4 GB) and hosts fine on the local box (§32.5d). What doesn't
fit locally is the **one-time download + filter** of the two bulk sources:
- **ar5iv** HTML5 dataset — ~318 GB zip / 2.9 TB unpacked — the **math-fidelity** layer (verbatim
  LaTeX via MathML `alttext`, same approach as `ArxivHtmlCoreBuilder`). arXiv-only.
- **jedibear/s2orc_full** — 1.40 TB Parquet, 14.5M papers, `external_ids` for the join — the **broad
  coverage** layer (arXiv + PMC + OA), but PDF-Grobid text (math lossy).

At the local box's ~1 MB/s external rate these are days-to-weeks; on a well-connected EC2 box they
download in hours. Stream-and-filter keeps peak disk far below the raw size.

**Merge policy:** prefer **ar5iv** for any paper it covers (good math); fall back to **jedibear** for
the rest (coverage). One `fulltext/<id>.txt` per corpus row + manifest.

## Instance & cost

| Item | Spec | Why |
|---|---|---|
| Instance | **m6i.2xlarge** (8 vCPU, 32 GB), us-east-1 | download + Parquet/HTML parse; RAM is not the constraint |
| Storage | **500 GB gp3 EBS** | ar5iv zip (318 GB) + jedibear stream-and-delete (~one 260 MB shard at a time) + outputs |
| Network | up to 12.5 Gbps burst | 1.7 TB ingress in ~1–4 h |
| Est. cost | **~$5–10 total** | instance ~$0.38/h × ~6 h ≈ $2.3; 500 GB gp3 ~1 day ≈ $1.3; **ingress free**, egress of ~4 GB result ≈ $0.36 |

RAM stays low because both scripts stream (jedibear: download shard → filter → delete; ar5iv: read
HTML members straight from the zip, decompress only the ~30k joinable ids). No need for a big-RAM box.

## Access prep (do once, before provisioning)

- **ar5iv**: submit the SIGMathLing License Agreement (https://sigmathling.kwarc.info/resources/ar5iv-dataset-2024/,
  C-UDA-1.0) → download link for `ar5iv-04-2024-*.zip`. Manual, ~minutes.
- **jedibear**: none — public HF dataset (`huggingface_hub`, no token).

## Runbook

```bash
# 0. LOCAL: export the join-keys (done — 13 MB, 105,855 rows, 31,698 with arXiv id)
#    ~/.emergentterrain/corpus-nt/corpus-join-keys.jsonl

# 1. Provision: m6i.2xlarge + 500 GB gp3, us-east-1, Amazon Linux 2023
# 2. Copy keys + scripts up
scp corpus-join-keys.jsonl scripts/aws/*.py  ec2-user@$BOX:~/
ssh ec2-user@$BOX
pip install huggingface_hub pyarrow

# 3. Broad layer — jedibear (1.4 TB streamed, filtered)
python3 build-fulltext-jedibear.py --keys corpus-join-keys.jsonl   # → jedibear-fulltext.jsonl

# 4. Math layer — ar5iv (download the zip from the License-Agreement link first)
curl -o ar5iv.zip "<license-agreement download URL>"
python3 build-fulltext-ar5iv-bulk.py --keys corpus-join-keys.jsonl --zips ar5iv.zip  # → ar5iv-fulltext.jsonl

# 5. Merge (ar5iv preferred, jedibear fills) → shippable store
python3 merge-fulltext.py --ar5iv ar5iv-fulltext.jsonl --s2orc jedibear-fulltext.jsonl --outdir fulltext

# 6. Ship home (~2-4 GB), then TERMINATE the instance + delete the EBS volume
tar czf fulltext.tgz fulltext fulltext-manifest.jsonl
scp fulltext.tgz  you@localbox:~/.emergentterrain/corpus-nt/
```

## Local integration (after ship-back)

Extract `fulltext/` + `fulltext-manifest.jsonl` under `~/.emergentterrain/corpus-nt/`. The
consumption wiring is now **built** (2026-07-08):
- `read_reference` serves the `fulltext/` tier automatically (ReferenceCoreReader: cores-html →
  **fulltext** → cores) — no config; active on next app restart.
- Retrieval over full text is flag-gated: set `ET_GROUND_INDEX_FULLTEXT=1` on the py-sidecar to fold
  full text into the BM25 index (capped 40k chars/doc; RAM-heavy → pair with the disk-backed index).
  Default OFF keeps retrieval on title+abstract.

So after ship-back, `read_reference` immediately positions results against the bulk full text; enable
the index flag only when RAM/disk-backed-index allows.

## Scripts (all in `scripts/aws/`, credential-free except ar5iv's one-time license form)

- `build-fulltext-jedibear.py` — HF Parquet stream-filter by `external_ids` → `jedibear-fulltext.jsonl`.
- `build-fulltext-ar5iv-bulk.py` — ar5iv zip → text with `$<LaTeX>$` from MathML `alttext`
  (**validated live**: recovered e.g. `$O(10^{6})$` from a real ar5iv page).
- `merge-fulltext.py` — ar5iv-preferred merge → `fulltext/` + manifest.

## Coverage expectation

arXiv-joinable rows ≈ 31,698 (30%) get ar5iv math-fidelity text; jedibear extends to non-arXiv OA
(DOI join) — realistically pushing total full-text coverage from the 1.7% (unarXive open) toward
~30–50% of the corpus, with the arXiv slice carrying usable equations. Exact numbers land when the
build runs. See §32.5d for the source-tier rationale (credential barriers / OSS reproducibility).
