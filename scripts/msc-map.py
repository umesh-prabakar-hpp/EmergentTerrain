#!/usr/bin/env python3
# =====================================================================
# msc-map.py — ship / consume the committed MSC lookup (§32.5d)
# =====================================================================
# WHAT THIS IS
#   MSC (Mathematics Subject Classification) codes for the number-theory corpus,
#   e.g. W2007557094 -> ["11N37","11M06"]. These upgrade the grounding subject
#   gate from field-level ("Mathematics") to MSC-prefix ("11" = Number Theory) —
#   see phase-32-corpus-aggregation.md §32.5c/§32.5d.
#
# WHY IT'S COMMITTED (the whole point of this file)
#   Generating the map requires a ~5 HOUR harvest of the zbMATH OAI-PMH endpoint
#   (188,219 Number-Theory records, server throttled to ~9.4 s/request and prone
#   to transient HTTP 500s — see build-msc-zbmath-oai.py). Rather than make every
#   open-source user endure that flaky harvest, the finished result is committed
#   to the repo as a compact, gzipped lookup: apps/game-service/src/main/resources/msc-map.jsonl.gz
#   (~0.3 MB gz / ~1.4 MB raw). A user just runs `--apply` and gets MSC instantly.
#
#   ⚠️ LICENSE / ATTRIBUTION: this data is DERIVED FROM zbMATH Open, licensed
#   CC-BY-SA 4.0. By committing it we accept that obligation for this one file —
#   see apps/game-service/src/main/resources/msc-map.README.md for the required NOTICE. The MSC
#   scheme itself is maintained by zbMATH + Mathematical Reviews (AMS).
#
# ---------------------------------------------------------------------
# TWO MODES
#
#   --apply   (OSS USERS — the fast path; NO harvest, NO network)
#       Reads the committed apps/game-service/src/main/resources/msc-map.jsonl.gz and injects the
#       `msc` field into your local corpus abstracts.jsonl by OpenAlex id. Run it
#       after build-nt-corpus.sh; then start the py-sidecar with
#       ET_GROUND_SUBJECTS=msc:11 to gate on MSC Number Theory. Idempotent.
#
#         python3 scripts/msc-map.py --apply
#
#   --extract (MAINTAINERS ONLY — regenerate the committed map)
#       After a fresh zbMATH harvest (build-msc-zbmath-oai.py) has written the
#       `msc` field into abstracts.jsonl, this pulls id->msc for the rows that got
#       one and (re)writes the committed apps/game-service/src/main/resources/msc-map.jsonl.gz.
#       Then `git add` + commit it. Do this only when refreshing the corpus.
#
#         python3 scripts/build-msc-zbmath-oai.py     # ~5 h, one time
#         python3 scripts/msc-map.py --extract        # -> apps/game-service/src/main/resources/msc-map.jsonl.gz
#         git add apps/game-service/src/main/resources/msc-map.jsonl.gz && git commit ...
#
# Env: CORPUS (default ~/.emergentterrain/corpus-nt/abstracts.jsonl)
# =====================================================================
import argparse, datetime, gzip, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
MAP = os.path.join(REPO, "apps", "game-service", "src", "main", "resources", "msc-map.jsonl.gz")
CORPUS = os.path.expanduser(os.environ.get(
    "CORPUS", "~/.emergentterrain/corpus-nt/abstracts.jsonl"))

def rid(r):
    return r["id"].rsplit("/", 1)[-1]

def extract():
    """corpus (with msc field) -> committed msc-map.jsonl.gz. Maintainers, post-harvest.
    The FIRST line is a `_meta` header recording the GENERATION DATE + provenance + license, so
    anyone consuming the committed map knows how current it is and where it came from."""
    if not os.path.isfile(CORPUS):
        sys.exit(f"no corpus at {CORPUS}")
    pairs = [(rid(r), r["msc"]) for r in (json.loads(l) for l in open(CORPUS) if l.strip())
             if r.get("msc")]                          # only rows that actually got MSC
    meta = {"_meta": {
        "generated": datetime.date.today().isoformat(),   # ← the date the user asked to record
        "source": "zbMATH Open, OAI-PMH set=11 (Number Theory)",
        "license": "CC-BY-SA-4.0",
        "scheme": "MSC — Mathematics Subject Classification (zbMATH + Mathematical Reviews/AMS)",
        "records": len(pairs),
        "note": "id = OpenAlex work id; join to corpus abstracts.jsonl. See msc-map.README.md.",
    }}
    os.makedirs(os.path.dirname(MAP), exist_ok=True)
    with gzip.open(MAP, "wt", encoding="utf-8") as out:
        out.write(json.dumps(meta) + "\n")
        for wid, msc in pairs:
            out.write(json.dumps({"id": wid, "msc": msc}) + "\n")
    print(f"wrote {MAP}", flush=True)
    print(f"  generated {meta['_meta']['generated']} | {len(pairs):,} works with MSC | "
          f"{os.path.getsize(MAP)/1e6:.2f} MB gzipped", flush=True)
    print("  next: git add apps/game-service/src/main/resources/msc-map.jsonl.gz && commit (keep the NOTICE).", flush=True)

def apply_():
    """committed msc-map.jsonl.gz -> inject `msc` into the local corpus. OSS users, no harvest."""
    if not os.path.isfile(MAP):
        sys.exit(f"no committed map at {MAP} — either it's not in this checkout, or run --extract "
                 f"after a harvest.")
    if not os.path.isfile(CORPUS):
        sys.exit(f"no corpus at {CORPUS} — run scripts/build-nt-corpus.sh first.")
    lookup = {}
    with gzip.open(MAP, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            if "_meta" in d:                          # provenance header — show it so the user knows currency
                m = d["_meta"]
                print(f"committed MSC map: generated {m.get('generated','?')} | "
                      f"{m.get('records','?')} records | source {m.get('source','?')} | "
                      f"license {m.get('license','?')}", flush=True)
                continue
            lookup[d["id"]] = d["msc"]
    print(f"loaded {len(lookup):,} MSC entries from the committed map", flush=True)
    hit = 0
    from collections import Counter
    fam = Counter()
    tmp = CORPUS + ".tmp"
    with open(CORPUS) as fin, open(tmp, "w") as out:
        for line in fin:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            msc = lookup.get(rid(r))
            if msc:
                r["msc"] = msc
                hit += 1
                for c in msc:
                    fam[c[:2]] += 1
            out.write(json.dumps(r) + "\n")
    os.replace(tmp, CORPUS)
    print(f"applied MSC to {hit:,} corpus rows. top families: {dict(fam.most_common(8))}", flush=True)
    print("  next: (re)start the py-sidecar with ET_GROUND_SUBJECTS=msc:11 to gate on MSC Number Theory.", flush=True)

def main():
    ap = argparse.ArgumentParser(description="ship (--extract) or consume (--apply) the committed MSC map")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--apply", action="store_true", help="OSS users: inject committed MSC into the corpus (no harvest)")
    g.add_argument("--extract", action="store_true", help="maintainers: (re)write the committed map from a harvested corpus")
    args = ap.parse_args()
    (extract if args.extract else apply_)()

if __name__ == "__main__":
    main()
