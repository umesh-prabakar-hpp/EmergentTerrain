#!/usr/bin/env python3
# =====================================================================
# build-msc-zbmath.py — §32.5d seq #1: attach MSC codes via zbMATH Open
# =====================================================================
# Adds Mathematics Subject Classification (MSC) codes to the local corpus by
# looking each row up in zbMATH Open — the math-authoritative database — via its
# free REST API (NO key, NO affiliation; https://api.zbmath.org/v1). This upgrades
# the grounding subject gate from OpenAlex field-level ("Mathematics") to MSC-prefix
# ("11" = Number Theory), which is finer and math-native — see §32.5c/§32.5d.
#
# JOIN: primary key is DOI (`doi:` query — verified ~77% hit on DOI'd NT rows, all
# returning 11-XX). Optional title fallback (`ti:`) for rows without a DOI (fuzzier;
# off by default). Adds `msc` (list of codes, e.g. ["11N37","11M06"]) to each row.
#
# Rate: zbMATH is a public academic API — pace gently (~3 req/s). Resumable via a
# checkpoint; safe to Ctrl-C and re-run. ~30k DOI'd NT rows ≈ a few hours.
#
# Usage:
#   python3 scripts/build-msc-zbmath.py [--limit N] [--title-fallback]
# Env: CORPUS (default ~/.emergentterrain/corpus-nt/abstracts.jsonl)
# =====================================================================
import argparse, json, os, time, urllib.request, urllib.parse
from collections import Counter

CORPUS = os.path.expanduser(os.environ.get(
    "CORPUS", "~/.emergentterrain/corpus-nt/abstracts.jsonl"))
CKPT = os.path.join(os.path.dirname(CORPUS), "msc-tags.json")

def zb_search(search_string):
    url = "https://api.zbmath.org/v1/document/_search?" + urllib.parse.urlencode(
        {"search_string": search_string, "results_per_page": 1, "page": 0})
    for attempt in range(4):
        try:
            return json.load(urllib.request.urlopen(url, timeout=20)).get("result") or []
        except Exception as e:
            if attempt == 3:
                return None
            time.sleep(2 * (attempt + 1))

def msc_of(res):
    return [m.get("code") for m in (res[0].get("msc") or []) if m.get("code")] if res else []

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="cap rows processed (0 = all)")
    ap.add_argument("--title-fallback", action="store_true", help="try ti: for rows without a DOI/DOI-miss")
    args = ap.parse_args()

    rows = [json.loads(l) for l in open(CORPUS) if l.strip()]
    tags = json.load(open(CKPT)) if os.path.exists(CKPT) else {}   # id -> [msc...]  ([] = looked up, none found)
    if tags:
        print(f"resuming — {len(tags)} rows already looked up", flush=True)

    todo = [r for r in rows if r["id"].rsplit("/", 1)[-1] not in tags]
    if args.limit:
        todo = todo[:args.limit]
    print(f"corpus {len(rows)} | to look up {len(todo)}", flush=True)

    done = 0
    for r in todo:
        rid = r["id"].rsplit("/", 1)[-1]
        codes = []
        doi = (r.get("doi") or "").replace("https://doi.org/", "").strip()
        if doi:
            codes = msc_of(zb_search(f"doi: {doi}"))
        if not codes and args.title_fallback and r.get("title"):
            t = r["title"].replace('"', " ").strip()
            if len(t) >= 12:
                codes = msc_of(zb_search(f'ti: "{t}"'))
        tags[rid] = codes
        done += 1
        if done % 100 == 0:
            json.dump(tags, open(CKPT, "w"))
            hit = sum(1 for v in tags.values() if v)
            print(f"  {done}/{len(todo)} looked up | {hit} with MSC", flush=True)
        time.sleep(0.33)
    json.dump(tags, open(CKPT, "w"))

    # merge into corpus + rewrite
    hit = 0
    fam = Counter()
    tmp = CORPUS + ".tmp"
    with open(tmp, "w") as out:
        for r in rows:
            rid = r["id"].rsplit("/", 1)[-1]
            codes = tags.get(rid)
            if codes:
                r["msc"] = codes
                hit += 1
                for c in codes:
                    fam[c[:2]] += 1
            out.write(json.dumps(r) + "\n")
    os.replace(tmp, CORPUS)
    looked = sum(1 for r in rows if r["id"].rsplit("/", 1)[-1] in tags)
    print(f"\nDONE: {looked} looked up | {hit} rows now carry MSC "
          f"({100*hit//max(looked,1)}% of looked-up)", flush=True)
    print("top MSC families:", dict(fam.most_common(10)), flush=True)
    print("Gate: set ET_GROUND_SUBJECTS=11 (MSC Number Theory) once "
          "literature_grounding.jl indexes the msc field.", flush=True)

if __name__ == "__main__":
    main()
