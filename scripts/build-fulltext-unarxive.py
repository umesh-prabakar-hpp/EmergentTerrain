#!/usr/bin/env python3
# =====================================================================
# build-fulltext-unarxive.py — §32.5d full-text builder (credential-free)
# =====================================================================
# Attaches full text to the local corpus (corpus-nt/abstracts.jsonl) from the
# unarXive open subset — arXiv full text + citations, LaTeX-derived, downloadable
# from Zenodo with NO account and NO API key (record 7752615, ODC-BY). This is
# the OSS-default full-text source; see phase-32-corpus-aggregation.md §32.5d.
#
# WHY unarXive (not peS2o / S2ORC-API): peS2o records carry only an S2 CorpusID
# (no DOI/arXiv) so they can't join our DOI/arXiv-keyed corpus without the S2
# metadata key; the S2 Datasets API needs a free key. unarXive is zero-account,
# permanently archived on Zenodo, and joins by arXiv id. (The S2 key path is the
# optional coverage upgrade — see scripts/s2orc-ingest.py.)
#
# JOIN: unarXive papers are keyed by arXiv id (`paper_id`); their `metadata.doi`
# is usually null. Our corpus rows are OpenAlex/DOI-keyed and do NOT store an
# arXiv id, so we (a) derive arXiv ids from rows whose DOI is an arXiv DOI
# (10.48550/arXiv.<id> or arxiv.org/abs/<id>), and (b) fall back to normalized
# title. Expect a partial join — only the arXiv-hosted slice of the corpus (good
# for modern NT, thin for pre-arXiv classics); that coverage limit is accepted.
#
# OUTPUT: corpus-nt/fulltext/<openalex_id>.txt (one file per matched row) +
# fulltext-manifest.jsonl (row id -> {arxiv_id, chars, source}). The grounding
# sidecar can index abstract+full text; unmatched rows keep abstract-only.
#
# Usage:
#   python3 scripts/build-fulltext-unarxive.py --sample <dir-or-jsonl>   # validate parser/join
#   python3 scripts/build-fulltext-unarxive.py --zenodo                  # full open-subset build
# Env: CORPUS (default ~/.emergentterrain/corpus-nt/abstracts.jsonl)
#      FIELDS  (default "math" — arXiv category prefixes to keep, comma-sep,
#               e.g. "math,cs,physics,econ,q-fin" for the multi-field expansion)
# =====================================================================
import argparse, glob, gzip, json, os, re, sys, urllib.request

CORPUS = os.path.expanduser(os.environ.get(
    "CORPUS", "~/.emergentterrain/corpus-nt/abstracts.jsonl"))
CORPUS_DIR = os.path.dirname(CORPUS)
FT_DIR = os.path.join(CORPUS_DIR, "fulltext")
MANIFEST = os.path.join(CORPUS_DIR, "fulltext-manifest.jsonl")
ZENODO_RECORD = "7752615"                      # unarXive 2022 open (permissive) subset
FIELDS = tuple(f.strip() for f in os.environ.get("FIELDS", "math").split(",") if f.strip())

_ARXIV_DOI = re.compile(r"10\.48550/arxiv\.([a-z0-9.\-/]+)", re.I)
_ARXIV_URL = re.compile(r"arxiv\.org/abs/([a-z0-9.\-/]+)", re.I)

def norm_title(t):
    return re.sub(r"[^a-z0-9]+", "", (t or "").lower())

def arxiv_id_from_doi(doi):
    if not doi:
        return None
    for rx in (_ARXIV_DOI, _ARXIV_URL):
        m = rx.search(doi)
        if m:
            return m.group(1).lower().rstrip("/")
    return None

def load_corpus_index():
    """Return (by_arxiv, by_title, rows) — rows keyed by OpenAlex id."""
    by_arxiv, by_title, rows = {}, {}, {}
    n = 0
    with open(CORPUS) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            rid = r["id"].rsplit("/", 1)[-1]
            rows[rid] = r
            ax = arxiv_id_from_doi(r.get("doi", ""))
            if ax:
                by_arxiv[ax] = rid
            t = norm_title(r.get("title"))
            if len(t) >= 12:                    # avoid trivial-title collisions
                by_title.setdefault(t, rid)
            n += 1
    print(f"corpus: {n} rows | {len(by_arxiv)} have a derivable arXiv id | "
          f"{len(by_title)} title-indexed", flush=True)
    return by_arxiv, by_title, rows

def iter_unarxive(paths):
    """Yield paper records from unarXive .jsonl / .jsonl.gz files."""
    for p in paths:
        op = gzip.open if p.endswith(".gz") else open
        with op(p, "rt") as fh:
            for line in fh:
                line = line.strip()
                if line:
                    try:
                        yield json.loads(line)
                    except Exception:
                        continue

def keep_field(rec):
    cats = (rec.get("metadata") or {}).get("categories") or ""
    return any(c.split(".")[0] in FIELDS for c in cats.split())

def body_to_text(rec):
    parts = []
    if rec.get("abstract"):
        a = rec["abstract"]
        parts.append(a.get("text", "") if isinstance(a, dict) else str(a))
    for b in rec.get("body_text") or []:
        if b.get("text"):
            parts.append(b["text"])
    return "\n\n".join(p for p in parts if p).strip()

def build(paths):
    by_arxiv, by_title, rows = load_corpus_index()
    os.makedirs(FT_DIR, exist_ok=True)
    seen, matched, by_ax, by_ti = 0, 0, 0, 0
    with open(MANIFEST, "w") as man:
        for rec in iter_unarxive(paths):
            seen += 1
            if not keep_field(rec):
                continue
            ax = (rec.get("paper_id") or "").lower().rstrip("/")
            rid = by_arxiv.get(ax)
            how = "arxiv_id"
            if not rid:
                rid = by_title.get(norm_title((rec.get("metadata") or {}).get("title")))
                how = "title"
            if not rid:
                continue
            text = body_to_text(rec)
            if len(text) < 200:                 # skip stubs
                continue
            with open(os.path.join(FT_DIR, rid + ".txt"), "w") as out:
                out.write(text)
            man.write(json.dumps({"id": rid, "arxiv_id": ax, "chars": len(text),
                                  "source": "unarxive", "matched_by": how}) + "\n")
            matched += 1
            by_ax += how == "arxiv_id"
            by_ti += how == "title"
            if matched % 500 == 0:
                print(f"  matched {matched} (seen {seen})", flush=True)
    print(f"\nDONE: scanned {seen} unarXive papers → {matched} matched "
          f"({by_ax} by arXiv id, {by_ti} by title). Full text in {FT_DIR}", flush=True)

def download_zenodo():
    """Download + extract the unarXive open-subset (single .tar.xz) from Zenodo;
    return the list of extracted .jsonl paths. Idempotent: skips existing files."""
    import tarfile
    api = f"https://zenodo.org/api/records/{ZENODO_RECORD}"
    meta = json.load(urllib.request.urlopen(api, timeout=60))
    staging = os.path.join(CORPUS_DIR, "unarxive-open")
    os.makedirs(staging, exist_ok=True)
    for f in meta.get("files", []):
        if not f["key"].endswith((".tar.xz", ".tar.gz", ".tgz")):
            continue
        dest = os.path.join(staging, f["key"])
        if not os.path.exists(dest):
            print(f"  downloading {f['key']} ({f.get('size',0)/1e9:.1f} GB) ...", flush=True)
            urllib.request.urlretrieve(f["links"]["self"], dest)
        marker = dest + ".extracted"
        if not os.path.exists(marker):
            print(f"  extracting {f['key']} (tar.xz → jsonl) ...", flush=True)
            with tarfile.open(dest, "r:*") as tf:
                tf.extractall(staging)
            open(marker, "w").close()
    return sorted(glob.glob(os.path.join(staging, "**", "*.jsonl*"), recursive=True))

def main():
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--sample", help="dir or .jsonl(.gz) of unarXive records (validation / local build)")
    g.add_argument("--zenodo", action="store_true", help="download the open subset from Zenodo")
    args = ap.parse_args()
    if args.zenodo:
        paths = download_zenodo()
    elif os.path.isdir(args.sample):
        paths = sorted(glob.glob(os.path.join(args.sample, "**", "*.jsonl*"), recursive=True))
    else:
        paths = [args.sample]
    if not paths:
        sys.exit("no unarXive .jsonl files found")
    print(f"fields kept: {FIELDS} | inputs: {len(paths)} file(s)", flush=True)
    build(paths)

if __name__ == "__main__":
    main()
