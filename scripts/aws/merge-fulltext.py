#!/usr/bin/env python3
# =====================================================================
# aws/merge-fulltext.py — RUNS ON THE AWS BUILD BOX (final step)
# =====================================================================
# Merges the two full-text layers into the shippable store, per §32.5e policy:
# PREFER ar5iv (verbatim-LaTeX math) for any paper it covers; fall back to
# jedibear/S2ORC (broad coverage, PDF-Grobid text) for the rest. One file per
# corpus row + a manifest. This ~few-GB output is what ships back to the local
# host box (the 1.4 TB + 318 GB inputs stay on the AWS box and are discarded).
#
# Usage: python3 merge-fulltext.py --ar5iv ar5iv-fulltext.jsonl \
#            --s2orc jedibear-fulltext.jsonl --outdir fulltext
# =====================================================================
import argparse, json, os
from collections import Counter

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ar5iv", required=True)
    ap.add_argument("--s2orc", required=True)
    ap.add_argument("--outdir", default="fulltext")
    args = ap.parse_args()
    os.makedirs(args.outdir, exist_ok=True)

    best = {}   # id -> (source, text)   ar5iv wins ties
    for path, src, rank in [(args.s2orc, "s2orc", 0), (args.ar5iv, "ar5iv", 1)]:
        if not os.path.exists(path):
            print(f"  (skip missing {path})", flush=True); continue
        for line in open(path):
            r = json.loads(line)
            cur = best.get(r["id"])
            if cur is None or rank >= cur[2]:      # ar5iv (rank 1) overrides s2orc (rank 0)
                best[r["id"]] = (src, r["text"], rank)

    src_counts = Counter()
    with open(os.path.join(args.outdir, "..", "fulltext-manifest.jsonl"), "w") as man:
        for rid, (src, text, _) in best.items():
            with open(os.path.join(args.outdir, rid + ".txt"), "w") as f:
                f.write(text)
            man.write(json.dumps({"id": rid, "source": src, "chars": len(text)}) + "\n")
            src_counts[src] += 1
    print(f"DONE: {len(best)} papers → {args.outdir}/  (by source: {dict(src_counts)})", flush=True)
    print("ship the fulltext/ dir + fulltext-manifest.jsonl back to "
          "~/.emergentterrain/corpus-nt/ on the host box.", flush=True)

if __name__ == "__main__":
    main()
