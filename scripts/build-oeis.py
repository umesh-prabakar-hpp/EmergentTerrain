#!/usr/bin/env python3
# =====================================================================
# build-oeis.py — §32.5d seq #3: OEIS domain-reference adapter
# =====================================================================
# Downloads the OEIS bulk files (free, no key: oeis.org/stripped.gz + names.gz)
# and builds a local reference store the grounding layer can consult — structured
# integer-sequence knowledge no general aggregator carries. See §32.5d.
#
# Output: ~/.emergentterrain/corpus-nt/domain-refs/oeis.jsonl
#   one line per sequence: {"id":"A000045","name":"Fibonacci numbers...","terms":[0,1,1,2,3,5,...]}
# Downstream (separate wiring): a sequence-lookup / citation tool over this store.
#
# Usage: python3 scripts/build-oeis.py
# =====================================================================
import gzip, json, os, urllib.request

BASE = "https://oeis.org"
OUTDIR = os.path.expanduser("~/.emergentterrain/corpus-nt/domain-refs")
os.makedirs(OUTDIR, exist_ok=True)

_UA = {"User-Agent": "EmergentTerrain-corpus-builder/1.0 (research; contact via repo)"}

def fetch(name):
    dest = os.path.join(OUTDIR, name)
    if not os.path.exists(dest):
        print(f"  downloading {name} ...", flush=True)
        req = urllib.request.Request(f"{BASE}/{name}", headers=_UA)  # OEIS 403s the default UA
        with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as out:
            while chunk := resp.read(1 << 20):
                out.write(chunk)
    return dest

def parse_terms(line):
    # "A000045 ,0,1,1,2,3,5,8,..."  -> ("A000045", [0,1,1,2,3,5,8,...])
    aid, _, rest = line.partition(" ")
    terms = []
    for tok in rest.strip().strip(",").split(","):
        tok = tok.strip()
        if tok:
            try:
                terms.append(int(tok))
            except ValueError:
                pass
    return aid, terms

def parse_name(line):
    aid, _, name = line.partition(" ")
    return aid, name.strip()

print("OEIS adapter — fetching bulk files", flush=True)
stripped, names = fetch("stripped.gz"), fetch("names.gz")

name_of = {}
with gzip.open(names, "rt", encoding="utf-8", errors="replace") as f:
    for line in f:
        line = line.rstrip("\n")
        if line and not line.startswith("#"):
            aid, nm = parse_name(line)
            name_of[aid] = nm
print(f"  names: {len(name_of):,}", flush=True)

out = os.path.join(OUTDIR, "oeis.jsonl")
n = 0
with gzip.open(stripped, "rt", encoding="utf-8", errors="replace") as f, open(out, "w") as w:
    for line in f:
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        aid, terms = parse_terms(line)
        w.write(json.dumps({"id": aid, "name": name_of.get(aid, ""), "terms": terms}) + "\n")
        n += 1
print(f"DONE: wrote {n:,} sequences to {out}", flush=True)
