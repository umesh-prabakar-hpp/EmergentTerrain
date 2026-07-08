#!/usr/bin/env python3
# =====================================================================
# aws/build-fulltext-jedibear.py — RUNS ON THE AWS BUILD BOX
# =====================================================================
# Stream-filters the jedibear/s2orc_full HuggingFace mirror (1.4 TB Parquet,
# 14.5M papers, ODC-BY, NO key) down to the works in our corpus, keeping full
# text + resolved references. Broad cross-source coverage (arXiv + PMC + OA).
# Math note: S2ORC text is Grobid-from-PDF (math lossy) — use ar5iv for the
# arXiv slice's math fidelity; this is the coverage layer. See §32.5e.
#
# INPUT:  corpus-join-keys.jsonl (copied from the local box; {id,arxiv,doi,t})
# OUTPUT: jedibear-fulltext.jsonl  ({id, source:"s2orc", text, chars})  (~GB, ship home)
#
# Stream-and-delete per shard → peak disk ≈ one shard (~260 MB) + output. The 1.4 TB
# is BANDWIDTH, not stored. Requires: pip install huggingface_hub pyarrow
#
# Usage: python3 build-fulltext-jedibear.py --keys corpus-join-keys.jsonl [--workers 4]
# =====================================================================
import argparse, json, os, sys
try:
    import pyarrow.parquet as pq
    from huggingface_hub import HfApi, hf_hub_download
except ImportError:
    sys.exit("pip install huggingface_hub pyarrow")

REPO = "jedibear/s2orc_full"

def load_keys(path):
    by_arxiv, by_doi = {}, {}
    with open(path) as f:
        for line in f:
            r = json.loads(line)
            if r.get("arxiv"):
                by_arxiv[r["arxiv"]] = r["id"]
            if r.get("doi"):
                by_doi[r["doi"]] = r["id"]
    print(f"keys: {len(by_arxiv)} arXiv, {len(by_doi)} DOI", flush=True)
    return by_arxiv, by_doi

def ext_id(exts, kind):
    # external_ids may be a dict {"ArXiv":..,"DOI":..} or list of {"type","id"} — handle both
    if isinstance(exts, dict):
        for k, v in exts.items():
            if k.lower() == kind and v:
                return str(v).lower().replace("https://doi.org/", "").strip()
    elif isinstance(exts, list):
        for e in exts:
            if isinstance(e, dict) and str(e.get("type", "")).lower() == kind and e.get("id"):
                return str(e["id"]).lower().replace("https://doi.org/", "").strip()
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keys", required=True)
    ap.add_argument("--out", default="jedibear-fulltext.jsonl")
    args = ap.parse_args()
    by_arxiv, by_doi = load_keys(args.keys)

    files = [f for f in HfApi().list_repo_files(REPO, repo_type="dataset") if f.endswith(".parquet")]
    print(f"{len(files)} parquet shards", flush=True)
    matched = 0
    with open(args.out, "w") as out:
        for i, fn in enumerate(files):
            local = hf_hub_download(REPO, fn, repo_type="dataset")
            t = pq.read_table(local, columns=["corpus_id", "external_ids", "text"])
            for row in t.to_pylist():
                exts = row.get("external_ids")
                ax = ext_id(exts, "arxiv")
                doi = ext_id(exts, "doi")
                rid = (by_arxiv.get(ax) if ax else None) or (by_doi.get(doi) if doi else None)
                if rid and row.get("text") and len(row["text"]) > 200:
                    out.write(json.dumps({"id": rid, "source": "s2orc",
                                          "text": row["text"], "chars": len(row["text"])}) + "\n")
                    matched += 1
            os.remove(local)                       # stream-and-delete: keep peak disk low
            print(f"  shard {i+1}/{len(files)} — matched {matched}", flush=True)
    print(f"DONE: {matched} matched → {args.out}", flush=True)

if __name__ == "__main__":
    main()
