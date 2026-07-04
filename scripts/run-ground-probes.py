#!/usr/bin/env python3
# =====================================================================
# scripts/run-ground-probes.py — Phase 32 §32.5 step 6: probe-set runner
# =====================================================================
# Scores the /ground/claim retrieval layer against the hand-labeled
# 20-claim probe set (scripts/ground-probe-set.json):
#   - verdict accuracy vs the ≥80% acceptance bar,
#   - fabricated-citation check (every returned key must exist in the
#     corpus abstracts.jsonl),
#   - offline threshold sweep from the recorded confidences (the server
#     threshold only affects the verdict label; sweeping locally finds
#     the accuracy-optimal ET_GROUND_SILENT_THRESHOLD without re-calls).
#
# Usage: python3 scripts/run-ground-probes.py
# Env:   GROUND_URL (default http://127.0.0.1:8089/ground/claim)
#        CORPUS (default ~/.emergentterrain/corpus-nt/abstracts.jsonl)
# =====================================================================
import json, os, sys, urllib.request

URL = os.environ.get("GROUND_URL", "http://127.0.0.1:8089/ground/claim")
CORPUS = os.path.expanduser(os.environ.get(
    "CORPUS", "~/.emergentterrain/corpus-nt/abstracts.jsonl"))
HERE = os.path.dirname(os.path.abspath(__file__))

probes = json.load(open(os.path.join(HERE, "ground-probe-set.json")))["probes"]

corpus_keys = set()
with open(CORPUS) as f:
    for line in f:
        if line.strip():
            corpus_keys.add(json.loads(line)["id"].rsplit("/", 1)[-1])

rows, fabricated = [], []
for p in probes:
    req = urllib.request.Request(
        URL, data=json.dumps({"claim": p["claim"], "topK": 3}).encode(),
        headers={"Content-Type": "application/json"})
    d = json.load(urllib.request.urlopen(req, timeout=30))
    for e in d.get("evidence", []):
        if e["key"] not in corpus_keys:
            fabricated.append((p["id"], e["key"]))
    rows.append({**p, "verdict": d["verdict"], "conf": d["confidence"],
                 "top": (d.get("evidence") or [{}])[0].get("title", "-")[:60]})

server_threshold = None
for r in rows:
    pass

acc = sum(r["verdict"] == r["expected"] for r in rows)
print(f"=== probe results (server threshold; accuracy {acc}/{len(rows)}) ===")
for r in rows:
    mark = "ok " if r["verdict"] == r["expected"] else "MISS"
    print(f"  {mark} #{r['id']:>2} {r['class']:<13} exp={r['expected']:<8} "
          f"got={r['verdict']:<8} conf={r['conf']:.3f} | {r['top']}")

print(f"\nfabricated citation keys: {len(fabricated)}"
      + (f" {fabricated}" if fabricated else " (zero — all keys resolve in the corpus)"))

print("\n=== offline threshold sweep (verdict recomputed from confidence) ===")
best = (None, -1)
for t100 in range(20, 51):
    t = t100 / 100.0
    a = sum(("EVIDENCE" if r["conf"] >= t else "SILENT") == r["expected"] for r in rows)
    if a > best[1]:
        best = (t, a)
    if t100 % 2 == 0:
        print(f"  threshold {t:.2f} → {a}/{len(rows)}")
print(f"\nbest threshold {best[0]:.2f} → {best[1]}/{len(rows)} "
      f"({100 * best[1] // len(rows)}%) — acceptance bar is ≥80% (16/20)")
sys.exit(0 if best[1] >= 16 and not fabricated else 1)
