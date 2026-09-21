#!/usr/bin/env python3
"""Build coarse immutable read-only shards from a completed Phase 34 primary index.

This is an offline, single-writer operation. It never changes the source index and writes a
catalog plus category-based SQLite/FTS5 shards that the optional routed runtime can query in
parallel under one request deadline.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import sqlite3
import sys
import time

DEFAULT_SOURCE = Path.home() / ".emergentterrain" / "indexes" / "math-literature"
DEFAULT_DEST = Path.home() / ".emergentterrain" / "indexes" / "math-literature-shards"
SHARDS = {
    "number-theory": ("math.NT",), "analysis": ("math.CA", "math.FA", "math.AP", "math.CV"),
    "algebra": ("math.RA", "math.GR", "math.RT", "math.AT", "math.KT"),
    "combinatorics": ("math.CO",), "geometry": ("math.GT", "math.DG", "math.AG"),
    "probability": ("math.PR", "stat."),
}
CATALOG_SCHEMA_VERSION = 2
ASSIGNED_CATEGORY_PREFIXES = tuple(prefix for categories in SHARDS.values() for prefix in categories)

def args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-index", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--shards-dir", type=Path, default=DEFAULT_DEST)
    parser.add_argument("--force", action="store_true", help="replace an existing derived shard directory")
    return parser.parse_args()

def complete(conn: sqlite3.Connection) -> bool:
    row = conn.execute("SELECT value FROM metadata WHERE key='fulltext_fts_complete'").fetchone()
    return row is not None and row[0].lower() == "true"

def build_one(source: Path, destination: Path, categories: tuple[str, ...] | None) -> int:
    destination.mkdir(parents=True, exist_ok=True)
    out = sqlite3.connect(destination / "literature.db")
    try:
        out.execute("ATTACH DATABASE ? AS source", (str(source),))
        if categories is None:
            # Cross-list is the complement of the six coarse subject lanes, not merely non-math
            # records. This retains e.g. math.GM and math.HO sources that can carry valid
            # evidence for a routed number-theory query without duplicating named-lane records.
            assigned = " OR ".join("p.primary_category LIKE ?" for _ in ASSIGNED_CATEGORY_PREFIXES)
            where = f"(p.primary_category IS NULL OR p.primary_category='' OR NOT ({assigned}))"
            params = [f"{category}%" for category in ASSIGNED_CATEGORY_PREFIXES]
        else:
            where, params = "(" + " OR ".join("p.primary_category LIKE ?" for _ in categories) + ")", [f"{category}%" for category in categories]
        out.executescript("""
            CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE papers AS SELECT * FROM source.papers WHERE 0;
            CREATE TABLE theorems AS SELECT * FROM source.theorems WHERE 0;
            CREATE TABLE fulltext AS SELECT * FROM source.fulltext WHERE 0;
            CREATE VIRTUAL TABLE theorem_fts USING fts5(theorem_id UNINDEXED, paper_id UNINDEXED, name, slogan, body, tokenize='unicode61 remove_diacritics 2');
            CREATE VIRTUAL TABLE fulltext_fts USING fts5(paper_id UNINDEXED, title, text, tokenize='unicode61 remove_diacritics 2');
        """)
        out.execute(f"INSERT INTO papers SELECT p.* FROM source.papers p WHERE {where}", params)
        out.execute("INSERT INTO theorems SELECT t.* FROM source.theorems t JOIN papers p ON p.paper_id=t.paper_id")
        out.execute("INSERT INTO fulltext SELECT f.* FROM source.fulltext f JOIN papers p ON p.paper_id=f.paper_id")
        out.execute("""INSERT INTO theorem_fts(rowid,theorem_id,paper_id,name,slogan,body)
                       SELECT st.rowid,st.theorem_id,st.paper_id,st.name,st.slogan,st.body
                       FROM source.theorem_fts st JOIN theorems t ON t.theorem_id=st.theorem_id""")
        # Rebuild from the already-filtered derived rows. Scanning the 30 GB source FTS table for
        # each shard is unnecessary, and source FTS row IDs are not identities after filtering.
        out.execute("""INSERT INTO fulltext_fts(rowid,paper_id,title,text)
                       SELECT f.rowid,f.paper_id,p.title,f.text
                       FROM fulltext f JOIN papers p ON p.paper_id=f.paper_id""")
        out.execute("INSERT INTO theorem_fts(theorem_fts) VALUES ('optimize')")
        out.execute("INSERT INTO fulltext_fts(fulltext_fts) VALUES ('optimize')")
        count = out.execute("SELECT COUNT(*) FROM fulltext").fetchone()[0]
        out.executemany("INSERT INTO metadata VALUES (?,?)", [
            ("fulltext_fts_complete", "true"), ("shard_documents", str(count)),
            ("derived_from", str(source)), ("built_at_epoch_seconds", str(int(time.time())))])
        out.commit()
        return count
    finally:
        out.close()

def main() -> int:
    parsed = args(); source = parsed.source_index.expanduser().resolve() / "literature.db"; dest = parsed.shards_dir.expanduser().resolve()
    if not source.is_file(): raise SystemExit(f"source index is missing: {source}")
    with sqlite3.connect(source) as source_conn:
        if not complete(source_conn): raise SystemExit("source index lacks fulltext_fts_complete=true")
    if dest.exists():
        if not parsed.force: raise SystemExit(f"shards directory exists: {dest} (use --force after verifying no reader uses it)")
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    counts = {name: build_one(source, dest / name, categories) for name, categories in SHARDS.items()}
    counts["cross-list"] = build_one(source, dest / "cross-list", None)
    (dest / "catalog.json").write_text(json.dumps({"schema_version": CATALOG_SCHEMA_VERSION, "source_index": str(source), "shards": counts}, indent=2) + "\n")
    print(json.dumps({"shards_dir": str(dest), "counts": counts}, indent=2))
    return 0

if __name__ == "__main__": raise SystemExit(main())
