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
import re
import shutil
import sqlite3
import sys
import time

DEFAULT_SOURCE = Path.home() / ".emergentterrain" / "indexes" / "math-literature"
DEFAULT_DEST = Path.home() / ".emergentterrain" / "indexes" / "math-literature-shards"
NAMED_SHARDS = {
    "number-theory": ("math.NT",), "analysis": ("math.CA", "math.FA", "math.AP", "math.CV"),
    "algebra": ("math.RA", "math.GR", "math.RT", "math.AT", "math.KT"),
    "combinatorics": ("math.CO",), "geometry": ("math.GT", "math.DG", "math.AG"),
    "probability": ("math.PR", "stat."),
}
MATH_COMPLEMENT = ("__math_complement__",)
SHARDS = {**NAMED_SHARDS, "math-complement": MATH_COMPLEMENT}
CATALOG_SCHEMA_VERSION = 3
PHASE45_TITLE_ANCHOR_DOCUMENTS_VERSION = "phase45-title-anchor-documents-v1"
ASSIGNED_CATEGORY_PREFIXES = tuple(prefix for categories in NAMED_SHARDS.values() for prefix in categories)

def args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-index", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--shards-dir", type=Path, default=DEFAULT_DEST)
    parser.add_argument("--upgrade-from-v1", type=Path,
                        help="derive v2 from a complete v1 shard directory, preserving its theorem summaries")
    parser.add_argument("--force", action="store_true", help="replace an existing derived shard directory")
    return parser.parse_args()

def complete(conn: sqlite3.Connection) -> bool:
    row = conn.execute("SELECT value FROM metadata WHERE key='fulltext_fts_complete'").fetchone()
    return row is not None and row[0].lower() == "true"

def rebuild_fulltext_fts(out: sqlite3.Connection) -> None:
    """Recreate FTS from this shard's own durable rows, never from primary FTS row IDs."""
    out.execute("DELETE FROM fulltext_fts")
    out.execute("""INSERT INTO fulltext_fts(rowid,paper_id,title,text)
                   SELECT f.rowid,f.paper_id,p.title,f.text
                   FROM fulltext f JOIN papers p ON p.paper_id=f.paper_id""")
    out.execute("INSERT INTO fulltext_fts(fulltext_fts) VALUES ('optimize')")

def normalize_anchor(value: str) -> str:
    value = value.lower()
    if len(value) <= 3 or value.endswith("ss"): return value
    if value.endswith("ies") and len(value) > 4: return value[:-3] + "y"
    if value.endswith(("ches", "shes", "sses", "xes", "zes")): return value[:-2]
    return value[:-1] if value.endswith("s") else value

def prepare_shard_title_anchors(out: sqlite3.Connection) -> None:
    """Materialize the existing Phase 45 title-gated theorem narrowing data per shard."""
    out.execute("DROP TABLE IF EXISTS literature_title_anchor_documents")
    out.execute("""CREATE TABLE literature_title_anchor_documents (
                       anchor TEXT NOT NULL,
                       paper_id TEXT NOT NULL,
                       PRIMARY KEY (anchor, paper_id)
                   ) WITHOUT ROWID""")
    pending: list[tuple[str, str]] = []
    for paper_id, title in out.execute("SELECT paper_id, title FROM papers"):
        pending.extend((normalize_anchor(anchor), paper_id)
                       for anchor in re.findall(r"[^\W\d_]{2,}", (title or "").lower()))
        if len(pending) >= 20_000:
            out.executemany("INSERT OR IGNORE INTO literature_title_anchor_documents VALUES (?, ?)", pending)
            pending.clear()
    if pending:
        out.executemany("INSERT OR IGNORE INTO literature_title_anchor_documents VALUES (?, ?)", pending)
    rows = out.execute("SELECT COUNT(*) FROM literature_title_anchor_documents").fetchone()[0]
    out.execute("INSERT OR REPLACE INTO metadata VALUES (?, ?)",
                ("phase45_title_anchor_documents_version", PHASE45_TITLE_ANCHOR_DOCUMENTS_VERSION))
    out.execute("INSERT OR REPLACE INTO metadata VALUES (?, ?)",
                ("phase45_title_anchor_documents_rows", str(rows)))

def build_one(source: Path, destination: Path, categories: tuple[str, ...] | None) -> int:
    destination.mkdir(parents=True, exist_ok=True)
    out = sqlite3.connect(destination / "literature.db")
    try:
        out.execute("ATTACH DATABASE ? AS source", (str(source),))
        if categories == MATH_COMPLEMENT:
            assigned = " OR ".join("p.primary_category LIKE ?" for _ in ASSIGNED_CATEGORY_PREFIXES)
            where = f"(p.primary_category LIKE 'math.%' AND NOT ({assigned}))"
            params = [f"{category}%" for category in ASSIGNED_CATEGORY_PREFIXES]
        elif categories is None:
            # Broad non-mathematics fallback only. Mathematics outside named lanes belongs in the
            # focused math-complement shard, so it cannot make number-theory fan-out unbounded.
            where, params = "(p.primary_category IS NULL OR p.primary_category='' OR (p.primary_category NOT LIKE 'math.%' AND p.primary_category NOT LIKE 'stat.%'))", []
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
        rebuild_fulltext_fts(out)
        prepare_shard_title_anchors(out)
        out.execute("INSERT INTO theorem_fts(theorem_fts) VALUES ('optimize')")
        count = out.execute("SELECT COUNT(*) FROM fulltext").fetchone()[0]
        out.executemany("INSERT INTO metadata VALUES (?,?)", [
            ("fulltext_fts_complete", "true"), ("shard_documents", str(count)),
            ("derived_from", str(source)), ("built_at_epoch_seconds", str(int(time.time())))])
        out.commit()
        return count
    finally:
        out.close()

def v1_catalog(source: Path) -> dict:
    catalog = source / "catalog.json"
    try:
        parsed = json.loads(catalog.read_text())
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"v1 shard catalog is unreadable: {catalog}") from exc
    if parsed.get("schema_version") != 1:
        raise SystemExit("--upgrade-from-v1 requires a complete schema_version 1 catalog")
    for name in (*NAMED_SHARDS, "cross-list"):
        if not (source / name / "literature.db").is_file():
            raise SystemExit(f"v1 shard is missing: {source / name / 'literature.db'}")
    return parsed

def upgrade_v1(source: Path, v1: Path, destination: Path) -> dict[str, int]:
    """Copy v1 subject summaries, repair local FTS, and derive the focused math complement once."""
    v1_catalog(v1)
    for name in (*NAMED_SHARDS, "cross-list"):
        shutil.copytree(v1 / name, destination / name)
    for name in (*NAMED_SHARDS, "cross-list"):
        with sqlite3.connect(destination / name / "literature.db") as out:
            rebuild_fulltext_fts(out)
            prepare_shard_title_anchors(out)
            out.commit()
    # The one unavoidable primary theorem-FTS pass carries generated slogan text absent from base rows.
    build_one(source, destination / "math-complement", MATH_COMPLEMENT)
    counts = {}
    for name in (*SHARDS, "cross-list"):
        with sqlite3.connect(destination / name / "literature.db") as shard:
            counts[name] = shard.execute("SELECT COUNT(*) FROM fulltext").fetchone()[0]
    return counts

def main() -> int:
    parsed = args(); source = parsed.source_index.expanduser().resolve() / "literature.db"; dest = parsed.shards_dir.expanduser().resolve()
    if not source.is_file(): raise SystemExit(f"source index is missing: {source}")
    with sqlite3.connect(source) as source_conn:
        if not complete(source_conn): raise SystemExit("source index lacks fulltext_fts_complete=true")
    if dest.exists():
        if not parsed.force: raise SystemExit(f"shards directory exists: {dest} (use --force after verifying no reader uses it)")
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    if parsed.upgrade_from_v1:
        counts = upgrade_v1(source, parsed.upgrade_from_v1.expanduser().resolve(), dest)
    else:
        counts = {name: build_one(source, dest / name, categories) for name, categories in SHARDS.items()}
        counts["cross-list"] = build_one(source, dest / "cross-list", None)
    (dest / "catalog.json").write_text(json.dumps({"schema_version": CATALOG_SCHEMA_VERSION, "source_index": str(source), "shards": counts}, indent=2) + "\n")
    print(json.dumps({"shards_dir": str(dest), "counts": counts}, indent=2))
    return 0

if __name__ == "__main__": raise SystemExit(main())
