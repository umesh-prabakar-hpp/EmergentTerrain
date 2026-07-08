#!/usr/bin/env python3
# =====================================================================
# aws/build-fulltext-ar5iv-bulk.py — RUNS ON THE AWS BUILD BOX
# =====================================================================
# Filters the ar5iv bulk HTML5 dataset (KWARC/SIGMathLing, ~318 GB zip / 2.9 TB
# unpacked, C-UDA-1.0, License-Agreement gate) to our corpus's arXiv slice,
# extracting text with each formula preserved as $<LaTeX>$ from the MathML
# `alttext` attribute — the SAME math-fidelity approach as the repo's
# ArxivHtmlCoreBuilder. This is the MATH-FIDELITY full-text layer (verbatim
# equations), vs jedibear/S2ORC's PDF-Grobid text. See §32.5e.
#
# INPUT:  corpus-join-keys.jsonl ; one or more ar5iv .zip files (--zips)
# OUTPUT: ar5iv-fulltext.jsonl  ({id, source:"ar5iv", text, chars})  (ship home)
#
# Reads HTML members straight from the zip (no full 2.9 TB unpack) — random
# access by member, so we only decompress the ~30k arXiv-joinable papers.
#
# Usage: python3 build-fulltext-ar5iv-bulk.py --keys corpus-join-keys.jsonl --zips ar5iv-*.zip
# =====================================================================
import argparse, glob, json, re, sys, zipfile

def load_arxiv_keys(path):
    by_arxiv = {}
    with open(path) as f:
        for line in f:
            r = json.loads(line)
            if r.get("arxiv"):
                by_arxiv[r["arxiv"]] = r["id"]
                by_arxiv[r["arxiv"].split("v")[0]] = r["id"]   # tolerate version suffixes
    print(f"arXiv keys: {len(by_arxiv)}", flush=True)
    return by_arxiv

# arXiv id from an ar5iv HTML path, e.g. ".../2212/2212.11867/2212.11867.html" or "1905.00075.html"
_IDPAT = re.compile(r"(\d{4}\.\d{4,5}|[a-z\-]+/\d{7})(?:v\d+)?", re.I)
_MATH = re.compile(r'<math\b[^>]*\balttext="([^"]*)"[^>]*>.*?</math>', re.S | re.I)
_TAG = re.compile(r"<[^>]+>")
_WS = re.compile(r"\s+")
_STYLE = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.S | re.I)

def html_to_text(html):
    html = _STYLE.sub(" ", html)
    html = _MATH.sub(lambda m: " $" + m.group(1).strip() + "$ ", html)   # formula → verbatim LaTeX
    body = re.search(r"<body.*?>(.*)</body>", html, re.S | re.I)
    text = _TAG.sub(" ", body.group(1) if body else html)
    text = (text.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">"))
    return _WS.sub(" ", text).strip()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keys", required=True)
    ap.add_argument("--zips", nargs="+", required=True, help="ar5iv .zip file(s) or glob")
    ap.add_argument("--out", default="ar5iv-fulltext.jsonl")
    args = ap.parse_args()
    by_arxiv = load_arxiv_keys(args.keys)
    zips = [z for pat in args.zips for z in glob.glob(pat)]
    if not zips:
        sys.exit("no ar5iv zips found")

    matched = 0
    with open(args.out, "w") as out:
        for zp in zips:
            with zipfile.ZipFile(zp) as zf:
                for name in zf.namelist():
                    if not name.endswith(".html"):
                        continue
                    m = _IDPAT.search(name.rsplit("/", 1)[-1])
                    if not m:
                        continue
                    axid = m.group(1).lower()
                    rid = by_arxiv.get(axid) or by_arxiv.get(axid.split("v")[0])
                    if not rid:
                        continue
                    text = html_to_text(zf.read(name).decode("utf-8", errors="replace"))
                    if len(text) > 200:
                        out.write(json.dumps({"id": rid, "source": "ar5iv",
                                              "text": text, "chars": len(text)}) + "\n")
                        matched += 1
                        if matched % 500 == 0:
                            print(f"  matched {matched}", flush=True)
            print(f"  finished {zp} — matched {matched}", flush=True)
    print(f"DONE: {matched} matched → {args.out}", flush=True)

if __name__ == "__main__":
    main()
