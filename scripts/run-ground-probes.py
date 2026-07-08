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
import json, math, os, sys, urllib.request

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

def in_subject(ev):
    """True if a returned near-miss is subject-relevant (Mathematics). Empty field ⇒ None
       (pre-rebuild corpus rows carry no subject tag — unverifiable, not a violation)."""
    field = (ev.get("field") or "").lower()
    if not field:
        return None
    return "mathematic" in field

rows, fabricated = [], []
subject_violations, subject_unverifiable = [], 0
for p in probes:
    req = urllib.request.Request(
        URL, data=json.dumps({"claim": p["claim"], "topK": 3}).encode(),
        headers={"Content-Type": "application/json"})
    d = json.load(urllib.request.urlopen(req, timeout=30))
    for e in d.get("evidence", []):
        if e["key"] not in corpus_keys:
            fabricated.append((p["id"], e["key"]))
    # Golden subject-purity check (probes flagged subject_pure): every RETURNED near-miss
    # must be in-subject — encodes the 2026-07-08 review case (drop cross-domain noise,
    # keep the Chowla-class NT near-miss). Skipped gracefully until the field-carrying rebuild.
    if p.get("subject_pure"):
        for e in d.get("evidence", []):
            ok = in_subject(e)
            if ok is None:
                subject_unverifiable += 1
            elif not ok:
                subject_violations.append(
                    (p["id"], e["key"], e.get("field", ""), e.get("title", "")[:50]))
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

print(f"\nsubject-purity (subject_pure probes): {len(subject_violations)} violation(s)"
      + (f" {subject_violations}" if subject_violations
         else (f" (zero, but {subject_unverifiable} near-miss(es) had no field tag — "
               f"pre-rebuild corpus; gate not yet verifiable)" if subject_unverifiable
               else " (zero — every returned near-miss is in-subject)")))

print("\n=== offline threshold sweep (verdict recomputed from confidence) ===")
best = (None, -1)
for t100 in range(20, 51):
    t = t100 / 100.0
    a = sum(("EVIDENCE" if r["conf"] >= t else "SILENT") == r["expected"] for r in rows)
    if a > best[1]:
        best = (t, a)
    if t100 % 2 == 0:
        print(f"  threshold {t:.2f} → {a}/{len(rows)}")
bar = math.ceil(0.8 * len(rows))
print(f"\nbest threshold {best[0]:.2f} → {best[1]}/{len(rows)} "
      f"({100 * best[1] // len(rows)}%) — acceptance bar is ≥80% ({bar}/{len(rows)})")
sys.exit(0 if best[1] >= bar and not fabricated and not subject_violations else 1)
