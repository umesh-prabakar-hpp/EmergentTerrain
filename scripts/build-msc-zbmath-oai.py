#!/usr/bin/env python3
# =====================================================================
# build-msc-zbmath-oai.py — §32.5d seq #1 (BULK): MSC via zbMATH OAI-PMH
# =====================================================================
# Replaces the per-DOI API grind (105,855 sequential calls, ~days, throttled)
# with a bulk OAI-PMH harvest of zbMATH's Number-Theory set (setSpec=11,
# completeListSize ~188k), then a LOCAL join — minutes, credential-free, no key.
#
# WHY oai_dc + title join: oai_dc is lean and carries MSC (dc:subject) + title +
# year, but NOT the DOI (only zbMATH's internal id). The DOI-bearing
# oai_zb_preview format is ~35 h (heavy review-text records). So we join by
# normalized title (+ year to disambiguate). We ALSO keep the higher-confidence
# DOI-joined MSC already gathered by the API run (msc-tags.json) — DOI wins.
#
# Harvest is cached to msc-oai-index.json (skip re-harvest on re-run). Merges MSC
# into abstracts.jsonl. Then: restart py-sidecar with ET_GROUND_SUBJECTS=11.
#
# Usage: python3 scripts/build-msc-zbmath-oai.py
# =====================================================================
import json, os, re, time, urllib.request
from collections import Counter

OAI = "https://oai.zbmath.org/v1/"
SET = "11"                                   # MSC 11-XX = Number Theory
CORPUS = os.path.expanduser("~/.emergentterrain/corpus-nt/abstracts.jsonl")
CDIR = os.path.dirname(CORPUS)
INDEX = os.path.join(CDIR, "msc-oai-index.json")     # cached harvest: norm_title -> {msc, year}
DOI_TAGS = os.path.join(CDIR, "msc-tags.json")       # DOI-joined MSC from the API run (kept)
UA = {"User-Agent": "EmergentTerrain-corpus-builder/1.0 (research)"}

def norm(t):
    return re.sub(r"[^a-z0-9]+", "", (t or "").lower())

def fetch(url):
    for attempt in range(5):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read().decode("utf-8", "replace")
        except Exception:
            if attempt == 4:
                raise
            time.sleep(3 * (attempt + 1))

_REC = re.compile(r"<record>(.*?)</record>", re.S)
_SUBJ = re.compile(r"<dc:subject>([^<]+)</dc:subject>")
_TITLE = re.compile(r"<dc:title>([^<]+)</dc:title>")
_DATE = re.compile(r"<dc:date>(\d{4})</dc:date>")
_TOKEN = re.compile(r"<resumptionToken[^>]*>([^<]*)</resumptionToken>")
_SIZE = re.compile(r'completeListSize="(\d+)"')

def harvest():
    """OAI-PMH ListRecords over set=11; return {norm_title: {"msc":[...], "year":int}}."""
    idx, ambiguous, n = {}, set(), 0
    url = f"{OAI}?verb=ListRecords&metadataPrefix=oai_dc&set={SET}"
    total = None
    while url:
        xml = fetch(url)
        if total is None:
            m = _SIZE.search(xml); total = int(m.group(1)) if m else "?"
        for rec in _REC.findall(xml):
            ts = _TITLE.search(rec)
            ss = _SUBJ.search(rec)
            if not ts or not ss:
                continue
            key = norm(ts.group(1))
            if len(key) < 12 or key in ambiguous:
                continue
            msc = [c.strip() for c in ss.group(1).split(";") if c.strip()]
            yr = _DATE.search(rec)
            entry = {"msc": msc, "year": int(yr.group(1)) if yr else 0}
            if key in idx and idx[key]["msc"] != msc:   # same title, different paper → unusable
                ambiguous.add(key); idx.pop(key, None)
            else:
                idx[key] = entry
            n += 1
        tok = _TOKEN.search(xml)
        tok = tok.group(1).strip() if tok else ""
        url = f"{OAI}?verb=ListRecords&resumptionToken={urllib.request.quote(tok)}" if tok else None
        if n % 5000 < 100:
            print(f"  harvested ~{n}/{total} records | title-index {len(idx)} | ambiguous {len(ambiguous)}", flush=True)
        time.sleep(0.15)
    print(f"harvest done: {n} records, {len(idx)} usable titles ({len(ambiguous)} ambiguous dropped)", flush=True)
    return idx

def main():
    if os.path.exists(INDEX):
        idx = json.load(open(INDEX))
        print(f"using cached harvest: {len(idx)} titles", flush=True)
    else:
        idx = harvest()
        json.dump(idx, open(INDEX, "w"))

    doi_msc = json.load(open(DOI_TAGS)) if os.path.exists(DOI_TAGS) else {}
    doi_hits = sum(1 for v in doi_msc.values() if v)
    print(f"DOI-joined MSC kept from API run: {doi_hits} rows", flush=True)

    rows = [json.loads(l) for l in open(CORPUS) if l.strip()]
    by_doi = by_title = 0
    fam = Counter()
    tmp = CORPUS + ".tmp"
    with open(tmp, "w") as out:
        for r in rows:
            rid = r["id"].rsplit("/", 1)[-1]
            msc = doi_msc.get(rid) or []          # DOI-join wins (higher confidence)
            src = "doi" if msc else None
            if not msc:
                hit = idx.get(norm(r.get("title")))
                if hit and (not hit["year"] or not r.get("year") or abs(hit["year"] - r["year"]) <= 1):
                    msc = hit["msc"]; src = "title"
            if msc:
                r["msc"] = msc
                by_doi += src == "doi"; by_title += src == "title"
                for c in msc:
                    fam[c[:2]] += 1
            out.write(json.dumps(r) + "\n")
    os.replace(tmp, CORPUS)
    print(f"\nDONE: MSC attached to {by_doi + by_title} rows ({by_doi} by DOI, {by_title} by title) "
          f"of {len(rows)} ({100*(by_doi+by_title)//len(rows)}%)", flush=True)
    print("top MSC families:", dict(fam.most_common(10)), flush=True)
    print("Next: restart py-sidecar with ET_GROUND_SUBJECTS=11 to gate on MSC Number Theory.", flush=True)

if __name__ == "__main__":
    main()
