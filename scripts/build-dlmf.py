#!/usr/bin/env python3
# =====================================================================
# build-dlmf.py — §32.5d seq #3: NIST DLMF domain-reference adapter
# =====================================================================
# Builds a local reference index from the NIST Digital Library of Mathematical
# Functions (dlmf.nist.gov) — authoritative special-functions knowledge (zeta,
# gamma, L-functions, ...) useful for grounding analytic-NT claims. See §32.5d.
#
# DLMF has NO bulk dump, so this fetches chapter/section pages respectfully
# (gentle pacing, resumable). It extracts each section's title + prose text
# (formulas are MathML — approximated to text; good enough for retrieval, not for
# exact equations). Public NIST (US-gov) resource; keep the request rate low.
#
# Output: ~/.emergentterrain/corpus-nt/domain-refs/dlmf.jsonl
#   {"id":"25.2","title":"Riemann Zeta Function ...","text":"..."}
#
# Usage: python3 scripts/build-dlmf.py [--chapters 1-36] [--delay 0.7]
# =====================================================================
import argparse, json, os, re, time, urllib.request

BASE = "https://dlmf.nist.gov"
OUTDIR = os.path.expanduser("~/.emergentterrain/corpus-nt/domain-refs")
OUT = os.path.join(OUTDIR, "dlmf.jsonl")
CKPT = os.path.join(OUTDIR, ".dlmf-seen")
UA = {"User-Agent": "EmergentTerrain-corpus-builder/1.0 (research; contact via repo)"}

def get(path):
    req = urllib.request.Request(f"{BASE}/{path}", headers=UA)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode("utf-8", errors="replace")
        except Exception:
            if attempt == 2:
                return None
            time.sleep(2 * (attempt + 1))

_TAG = re.compile(r"<[^>]+>")
_WS = re.compile(r"\s+")
_TITLE = re.compile(r"<title>DLMF:\s*(.*?)</title>", re.S)
# section links on a chapter page look like href="./25.2" or href="/25.2"
_SEC = re.compile(r'href="\.?/?(\d+\.\d+)"')

_NAV = re.compile(r"^.*?UnAnnotate\s*", re.S)   # constant DLMF top-nav chrome

def strip_html(html):
    # drop script/style, then tags, collapse whitespace
    html = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", html, flags=re.S | re.I)
    body = re.search(r"<body.*?>(.*)</body>", html, flags=re.S | re.I)
    text = _TAG.sub(" ", body.group(1) if body else html)
    text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#x2061;", "")
    text = _WS.sub(" ", text).strip()
    return _NAV.sub("", text, count=1)          # strip the leading nav so math content leads

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chapters", default="1-36", help="chapter range, e.g. 25 or 1-36")
    ap.add_argument("--delay", type=float, default=0.7, help="seconds between requests")
    args = ap.parse_args()
    lo, _, hi = args.chapters.partition("-")
    lo, hi = int(lo), int(hi or lo)
    os.makedirs(OUTDIR, exist_ok=True)
    seen = set(open(CKPT).read().split()) if os.path.exists(CKPT) else set()

    sections = []
    for ch in range(lo, hi + 1):
        page = get(str(ch))
        if not page:
            print(f"  chapter {ch}: fetch failed", flush=True); continue
        secs = sorted(set(_SEC.findall(page)), key=lambda s: [int(x) for x in s.split(".")])
        secs = [s for s in secs if s.startswith(f"{ch}.")]
        sections += secs
        print(f"  chapter {ch}: {len(secs)} sections", flush=True)
        time.sleep(args.delay)

    written = 0
    mode = "a" if seen else "w"
    with open(OUT, mode) as w, open(CKPT, "a") as ck:
        for sec in sections:
            if sec in seen:
                continue
            html = get(sec)
            if not html:
                continue
            m = _TITLE.search(html)
            title = _WS.sub(" ", (m.group(1) if m else "")).strip()
            text = strip_html(html)
            if len(text) > 100:
                w.write(json.dumps({"id": sec, "title": title, "text": text[:20000]}) + "\n")
                written += 1
            ck.write(sec + "\n"); ck.flush()
            time.sleep(args.delay)
    print(f"DONE: wrote {written} DLMF sections to {OUT} (chapters {lo}-{hi})", flush=True)

if __name__ == "__main__":
    main()
