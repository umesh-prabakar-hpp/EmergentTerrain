#!/usr/bin/env python3
# =====================================================================
# scripts/build-html-cores.py — Phase 33 L1b: ar5iv HTML cores (arXiv works)
# =====================================================================
# For every arXiv-hosted work in cores/MANIFEST.json, fetches the ar5iv
# (LaTeXML) HTML rendering and extracts text with the ORIGINAL LaTeX
# preserved for every formula (the <math alttext="..."> attribute), fixing
# the two losses of the PDF path: dropped symbol glyphs (no ToUnicode) and
# flattened math structure (fractions/exponents). Writes cores-html/<id>.txt
# + cores-html/MANIFEST.json. Idempotent; polite pacing; failure-soft.
#
# Python (not Java) so it can run IN PARALLEL with the PDF builder: the
# builder's exec:java JVM loads lazily from target/classes, so compiling new
# Java mid-run risks the NoClassDefFoundError class-corruption failure mode.
#
# Usage: OPENALEX_MAILTO=you@x ./scripts/build-html-cores.py
# Env: CORPUS_DIR (default ~/.emergentterrain/corpus-nt), PACING_S (1.2)
# =====================================================================
import html as h
import json
import os
import pathlib
import re
import sys
import time
import urllib.request

CORPUS = pathlib.Path(os.environ.get("CORPUS_DIR",
                      str(pathlib.Path.home() / ".emergentterrain/corpus-nt")))
OUT = CORPUS / "cores-html"
PACING = float(os.environ.get("PACING_S", "1.2"))
MAILTO = os.environ.get("OPENALEX_MAILTO", "et@example.org")
MIN_CHARS = 2000
MAX_CHARS = 200_000
ARXIV_ID = re.compile(r"arxiv\.org/(?:pdf|abs)/([a-z-]+/\d{7}|\d{4}\.\d{4,5})(?:v\d+)?", re.I)


def extract(raw: str) -> str:
    """ar5iv HTML → text with $<original LaTeX>$ for every formula.

    Order matters: math LaTeX is sentinel-protected BEFORE tag-stripping
    (unescaping first would reintroduce '<' from '&lt;' inside formulas and
    the tag-stripper would eat the rest of the equation — the bug the first
    throwaway extractor had), and entity-unescape runs LAST on prose only.
    """
    maths = []

    def math_repl(m):
        alt = re.search(r'alttext="([^"]*)"', m.group(0))
        maths.append(h.unescape(alt.group(1)) if alt else "")
        return f" \x00{len(maths) - 1}\x00 "

    s = re.sub(r"<math\b.*?</math>", math_repl, raw, flags=re.S)
    # ar5iv boilerplate: nav header/footer + the report/logo block.
    s = re.sub(r"<(script|style|head|nav|footer)\b.*?</\1>", " ", s, flags=re.S)
    s = re.sub(r"<[^>]+>", " ", s)
    s = h.unescape(s)
    s = re.sub(r"\x00(\d+)\x00", lambda m: "$" + maths[int(m.group(1))] + "$", s)
    s = re.sub(r"[ \t\x0b\f]+", " ", s)
    s = re.sub(r"\n\s*\n+", "\n\n", s).strip()
    return s[:MAX_CHARS]


def fetch(arxiv_id: str) -> str | None:
    url = f"https://ar5iv.labs.arxiv.org/html/{arxiv_id}"
    req = urllib.request.Request(url, headers={
        "User-Agent": f"EmergentTerrain-corpus/1.0 (mailto:{MAILTO})"})
    with urllib.request.urlopen(req, timeout=90) as r:
        if r.status != 200:
            return None
        return r.read().decode("utf-8", "replace")


def main() -> int:
    manifest_in = CORPUS / "cores" / "MANIFEST.json"
    if not manifest_in.is_file():
        print(f"no {manifest_in} — run the PDF cores build first", file=sys.stderr)
        return 1
    OUT.mkdir(parents=True, exist_ok=True)
    out_manifest_file = OUT / "MANIFEST.json"
    out_manifest = (json.loads(out_manifest_file.read_text())
                    if out_manifest_file.is_file() else {"works": []})
    have = {w["id"] for w in out_manifest["works"]}

    works = json.loads(manifest_in.read_text())["works"]   # snapshot; builder may append later
    arxiv = [(w, ARXIV_ID.search(w.get("pdf_url") or "")) for w in works]
    arxiv = [(w, m.group(1)) for w, m in arxiv if m]
    todo = [(w, aid) for w, aid in arxiv if w["id"] not in have]
    print(f"html-cores — manifest works={len(works)} arxiv={len(arxiv)} "
          f"existing={len(have)} todo={len(todo)}")

    fetched = failed = 0
    for n, (w, aid) in enumerate(todo, 1):
        try:
            time.sleep(PACING)
            raw = fetch(aid)
            text = extract(raw) if raw else None
            if not text or len(text) < MIN_CHARS:
                failed += 1
                continue
            (OUT / (w["id"] + ".txt")).write_text(text)
            out_manifest["works"].append({
                "id": w["id"], "arxiv_id": aid, "title": w.get("title"),
                "year": w.get("year"), "chars": len(text), "query": w.get("query")})
            have.add(w["id"])
            fetched += 1
            if fetched % 20 == 0:
                out_manifest_file.write_text(json.dumps(out_manifest, indent=1))
                print(f"  … {len(have)} html cores ({failed} failed) [{n}/{len(todo)}]")
        except KeyboardInterrupt:
            break
        except Exception:
            failed += 1   # one bad fetch/parse never stops the sweep
    out_manifest["builtAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    out_manifest["totalCores"] = len(have)
    out_manifest["failedFetches"] = failed
    out_manifest_file.write_text(json.dumps(out_manifest, indent=1))
    print(f"html-cores done — cores={len(have)} failed={failed} dir={OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
