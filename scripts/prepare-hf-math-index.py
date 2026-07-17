#!/usr/bin/env python3
"""Build the Phase 34 local, disk-backed mathematics literature index.

The raw Hugging Face downloads deliberately remain outside the repository.  This
tool turns them into a SQLite/FTS5 index below ``~/.emergentterrain`` and is
safe to re-run: completed stages are recorded in the database metadata and
completed ar5iv shards are recorded individually.

It has two complementary inputs:
* uw-math-ai/theorem-search-dataset: authoritative mathematical paper metadata,
  theorem statements, and theorem slogans;
* marin-community/ar5iv-no-problem-markdown: broad arXiv full text.  It is
  retained as a multi-subject source; only the records represented in UW carry
  category metadata at this stage.

The index is intentionally storage-backed.  It does not construct an embedding
matrix or retain corpus text in the application heap.  A later sidecar adapter
can query SQLite FTS5 while the present grounding path remains unchanged.

Requires Python 3 and PyArrow (``python3 -m pip install --user pyarrow``).
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import sys
import time
from typing import Iterable

try:
    import pyarrow.parquet as pq
except ImportError as exc:  # pragma: no cover - exercised by operators
    raise SystemExit(
        "PyArrow is required. Install it with: python3 -m pip install --user pyarrow"
    ) from exc


DEFAULT_CORPUS_DIR = Path.home() / ".emergentterrain" / "corpus-hf-math"
DEFAULT_INDEX_DIR = Path.home() / ".emergentterrain" / "indexes" / "math-literature"
UW_DIRNAME = "uw-math-ai--theorem-search-dataset"
MARIN_DIRNAME = "marin-community--ar5iv-no-problem-markdown"
INDEX_FORMAT = "phase34-hf-math-sqlite-v2"
IMPORTER_VERSION = "2026-07-15"
VERSION_RE = re.compile(r"v\d+$", re.IGNORECASE)
AR5IV_SUFFIX_RE = re.compile(r"(?:\.html)?$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--corpus-dir", type=Path, default=DEFAULT_CORPUS_DIR,
        help=f"parent containing downloaded datasets (default: {DEFAULT_CORPUS_DIR})",
    )
    parser.add_argument(
        "--index-dir", type=Path, default=DEFAULT_INDEX_DIR,
        help=f"destination for derived SQLite index (default: {DEFAULT_INDEX_DIR})",
    )
    parser.add_argument(
        "--stage", default="all", choices=("all", "metadata", "theorems", "fulltext", "fulltext-fts", "optimize"),
        help="stage to prepare; all runs metadata, theorems, then fulltext and its final FTS rebuild",
    )
    parser.add_argument(
        "--fulltext-shard-limit", type=int, default=0,
        help="process at most this many unprocessed Marin shards (0 = all); useful for staged runs",
    )
    parser.add_argument(
        "--force", action="store_true",
        help="rebuild a completed metadata/theorems stage; fulltext remains shard-resumable",
    )
    return parser.parse_args()


def connect(index_dir: Path) -> sqlite3.Connection:
    index_dir.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(index_dir / "literature.db")
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA temp_store=FILE")
    conn.execute("PRAGMA cache_size=-131072")  # bounded ~128 MiB page cache
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS papers (
          paper_id TEXT PRIMARY KEY, title TEXT NOT NULL, authors TEXT, link TEXT,
          last_updated TEXT, summary TEXT, journal_ref TEXT, primary_category TEXT,
          categories TEXT, citations INTEGER, source TEXT, license TEXT
        );
        CREATE TABLE IF NOT EXISTS theorems (
          theorem_id INTEGER PRIMARY KEY, paper_id TEXT NOT NULL, name TEXT,
          body TEXT, label TEXT, link TEXT, parsing_method TEXT,
          FOREIGN KEY (paper_id) REFERENCES papers(paper_id)
        );
        CREATE TABLE IF NOT EXISTS fulltext (
          paper_id TEXT PRIMARY KEY, source_key TEXT NOT NULL, text TEXT NOT NULL,
          text_sha256 TEXT NOT NULL, char_count INTEGER NOT NULL,
          primary_category TEXT, source_scope TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS processed_shards (
          shard TEXT PRIMARY KEY, source_bytes INTEGER NOT NULL, completed_at TEXT NOT NULL,
          matched_records INTEGER NOT NULL
        );
        CREATE VIRTUAL TABLE IF NOT EXISTS theorem_fts USING fts5(
          theorem_id UNINDEXED, paper_id UNINDEXED, name, slogan, body,
          tokenize='unicode61 remove_diacritics 2'
        );
        CREATE VIRTUAL TABLE IF NOT EXISTS fulltext_fts USING fts5(
          paper_id UNINDEXED, title, text,
          tokenize='unicode61 remove_diacritics 2'
        );
        """
    )
    # Keep a partially prepared v1 index forward-compatible after the corpus
    # scope widened from math-only to multi-subject.  SQLite cannot add these
    # fields through CREATE TABLE IF NOT EXISTS.
    fulltext_columns = {row[1] for row in conn.execute("PRAGMA table_info(fulltext)")}
    if "primary_category" not in fulltext_columns:
        conn.execute("ALTER TABLE fulltext ADD COLUMN primary_category TEXT")
    if "source_scope" not in fulltext_columns:
        conn.execute("ALTER TABLE fulltext ADD COLUMN source_scope TEXT NOT NULL DEFAULT ''")
    return conn


def state(conn: sqlite3.Connection, key: str) -> str | None:
    row = conn.execute("SELECT value FROM metadata WHERE key = ?", (key,)).fetchone()
    return row[0] if row else None


def set_state(conn: sqlite3.Connection, key: str, value: str) -> None:
    conn.execute("INSERT OR REPLACE INTO metadata(key, value) VALUES (?, ?)", (key, value))


def required(path: Path) -> Path:
    if not path.is_file():
        raise SystemExit(f"Required downloaded source is missing: {path}")
    return path


def normalize_arxiv_id(value: str) -> str:
    value = VERSION_RE.sub("", (value or "").strip())
    return value.removeprefix("arXiv:").removeprefix("arxiv:").lower()


def ar5iv_to_arxiv_id(source_key: str) -> str:
    # e.g. no-problem/2301/2301.01234.html -> 2301.01234.  A few historic
    # entries have category paths; retaining the final component is still the
    # only safe match because UW uses canonical arXiv paper IDs.
    last = (source_key or "").rsplit("/", 1)[-1]
    return AR5IV_SUFFIX_RE.sub("", last).lower()


def iter_parquet(path: Path, columns: list[str], batch_size: int = 4096) -> Iterable[dict]:
    for batch in pq.ParquetFile(path).iter_batches(columns=columns, batch_size=batch_size):
        yield from batch.to_pylist()


def prepare_metadata(conn: sqlite3.Connection, paper_file: Path, force: bool) -> None:
    if state(conn, "metadata_complete") and not force:
        print("metadata: already complete")
        return
    print("metadata: importing all labeled theorem-search papers")
    if force:
        conn.execute("DELETE FROM metadata WHERE key IN "
                     "('metadata_selected_papers', 'theorems_complete', 'theorems_selected', "
                     "'theorems_indexed')")
    conn.execute("DELETE FROM papers")
    columns = [
        "paper_id", "title", "authors", "link", "last_updated", "summary", "journal_ref",
        "primary_category", "categories", "citations", "source", "license",
    ]
    imported = 0
    for row in iter_parquet(paper_file, columns):
        conn.execute(
            """INSERT INTO papers VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            tuple(row.get(c) for c in columns),
        )
        imported += 1
        if imported % 10_000 == 0:
            conn.commit()
            print(f"metadata: {imported:,} imported")
    set_state(conn, "metadata_complete", "true")
    set_state(conn, "metadata_imported_papers", str(imported))
    set_state(conn, "index_format", INDEX_FORMAT)
    conn.commit()
    print(f"metadata: complete ({imported:,} labeled papers; query-time category filtering applies)")


def load_slogans(conn: sqlite3.Connection, slogan_file: Path, force: bool) -> None:
    if state(conn, "slogans_complete") and not force:
        return
    print("theorems: staging theorem slogans on disk")
    conn.execute("DROP TABLE IF EXISTS slogan_staging")
    conn.execute("CREATE TABLE slogan_staging (theorem_id INTEGER PRIMARY KEY, slogan TEXT)")
    inserted = 0
    for row in iter_parquet(slogan_file, ["theorem_id", "slogan"]):
        conn.execute("INSERT INTO slogan_staging VALUES (?, ?)",
                     (row["theorem_id"], row.get("slogan") or ""))
        inserted += 1
        if inserted % 25_000 == 0:
            conn.commit()
            print(f"theorems: {inserted:,} slogans staged")
    set_state(conn, "slogans_complete", "true")
    conn.commit()


def load_slogan_map(conn: sqlite3.Connection, theorem_ids: list[int]) -> dict[int, str]:
    out: dict[int, str] = {}
    for i in range(0, len(theorem_ids), 900):
        part = theorem_ids[i:i + 900]
        marks = ",".join("?" for _ in part)
        out.update(conn.execute(
            f"SELECT theorem_id, slogan FROM slogan_staging WHERE theorem_id IN ({marks})", part
        ).fetchall())
    return out


def prepare_theorems(conn: sqlite3.Connection, theorem_file: Path, slogan_file: Path,
                     force: bool) -> None:
    if state(conn, "theorems_complete") and not force:
        print("theorems: already complete")
        return
    if not state(conn, "metadata_complete"):
        raise SystemExit("metadata stage must complete before theorem preparation")
    load_slogans(conn, slogan_file, force)
    print("theorems: building storage-backed theorem table and FTS index")
    conn.execute("DELETE FROM theorem_fts")
    conn.execute("DELETE FROM theorems")
    paper_ids = {row[0] for row in conn.execute("SELECT paper_id FROM papers")}
    columns = ["theorem_id", "paper_id", "name", "body", "label", "link", "parsing_method"]
    indexed = 0
    pending: list[dict] = []

    def flush() -> None:
        nonlocal indexed
        if not pending:
            return
        slogans = load_slogan_map(conn, [row["theorem_id"] for row in pending])
        theorem_rows = []
        fts_rows = []
        for row in pending:
            theorem_rows.append(tuple(row.get(c) for c in columns))
            fts_rows.append((row["theorem_id"], row["paper_id"], row.get("name") or "",
                             slogans.get(row["theorem_id"], ""), row.get("body") or ""))
        conn.executemany("INSERT INTO theorems VALUES (?, ?, ?, ?, ?, ?, ?)", theorem_rows)
        conn.executemany("INSERT INTO theorem_fts VALUES (?, ?, ?, ?, ?)", fts_rows)
        indexed += len(pending)
        pending.clear()
        conn.commit()
        if indexed % 25_000 == 0:
            print(f"theorems: {indexed:,} indexed")

    for row in iter_parquet(theorem_file, columns):
        if row["paper_id"] in paper_ids:
            pending.append(row)
        if len(pending) >= 4_000:
            flush()
    flush()
    conn.execute("DROP TABLE IF EXISTS slogan_staging")
    conn.execute("INSERT INTO theorem_fts(theorem_fts) VALUES ('optimize')")
    set_state(conn, "theorems_complete", "true")
    set_state(conn, "theorems_indexed", str(indexed))
    set_state(conn, "theorem_fts_optimized_at", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    conn.commit()
    print(f"theorems: complete ({indexed:,} indexed theorem statements)")


def optimize_fts(conn: sqlite3.Connection) -> None:
    """Controlled maintenance for an already-complete immutable index."""
    if state(conn, "theorems_complete") != "true" or state(conn, "fulltext_fts_complete") != "true":
        raise SystemExit("optimize requires completed theorem and full-text FTS stages")
    print("fts: optimizing completed theorem and full-text indexes")
    conn.execute("INSERT INTO theorem_fts(theorem_fts) VALUES ('optimize')")
    conn.execute("INSERT INTO fulltext_fts(fulltext_fts) VALUES ('optimize')")
    optimized_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    set_state(conn, "theorem_fts_optimized_at", optimized_at)
    set_state(conn, "fulltext_fts_optimized_at", optimized_at)
    conn.commit()
    print("fts: optimization complete")


def rebuild_fulltext_fts(conn: sqlite3.Connection) -> None:
    """Build the large full-text FTS index once, after all resumable imports have finished."""
    print("fulltext-fts: rebuilding from stored full text in one SQLite transaction")
    conn.execute("DELETE FROM fulltext_fts")
    conn.execute(
        """INSERT INTO fulltext_fts(rowid, paper_id, title, text)
           SELECT f.rowid, f.paper_id, COALESCE(p.title, ''), f.text
             FROM fulltext f LEFT JOIN papers p ON p.paper_id = f.paper_id"""
    )
    indexed = conn.execute("SELECT COUNT(*) FROM fulltext_fts").fetchone()[0]
    # Controlled, one-time maintenance after the immutable bulk build. This merges FTS segments
    # without changing any document or query semantics. Never run it from the serving app.
    conn.execute("INSERT INTO fulltext_fts(fulltext_fts) VALUES ('optimize')")
    set_state(conn, "fulltext_fts_complete", "true")
    set_state(conn, "fulltext_fts_documents", str(indexed))
    set_state(conn, "fulltext_fts_optimized_at", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    conn.commit()
    print(f"fulltext-fts: complete ({indexed:,} documents)")


def prepare_fulltext(conn: sqlite3.Connection, marin_dir: Path, limit: int) -> None:
    if not state(conn, "metadata_complete"):
        raise SystemExit("metadata stage must complete before full-text preparation")
    shards = sorted(marin_dir.glob("*.jsonl.gz"))
    if not shards:
        raise SystemExit(f"No Marin .jsonl.gz shards found in {marin_dir}")
    paper_rows = conn.execute(
        "SELECT paper_id, title, primary_category FROM papers WHERE lower(source) = 'arxiv'"
    ).fetchall()
    arxiv_to_paper = {normalize_arxiv_id(paper_id): (paper_id, title or "", category or "")
                      for paper_id, title, category in paper_rows}
    processed = {row[0] for row in conn.execute("SELECT shard FROM processed_shards")}
    remaining = [shard for shard in shards if shard.name not in processed]
    if limit > 0:
        remaining = remaining[:limit]
    if not remaining:
        if len(processed) == len(shards) and state(conn, "fulltext_fts_complete") != "true":
            rebuild_fulltext_fts(conn)
        else:
            print("fulltext: all available shards are already complete")
        return
    print(f"fulltext: scanning {len(remaining):,} shard(s); {len(arxiv_to_paper):,} records have UW category metadata")
    for number, shard in enumerate(remaining, 1):
        matches: list[tuple[str, str, str, str, int, str, str, str]] = []
        with gzip.open(shard, "rt", encoding="utf-8") as handle:
            for line in handle:
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                arxiv_id = ar5iv_to_arxiv_id(row.get("id", ""))
                found = arxiv_to_paper.get(arxiv_id)
                text = row.get("text")
                if not arxiv_id or not isinstance(text, str) or not text.strip():
                    continue
                paper_id, title, category = found or (arxiv_id, title_from_text(text), "")
                scope = "uw-categorized" if found else "ar5iv-unclassified"
                digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
                matches.append((paper_id, row["id"], text, digest, len(text), category, scope, title))
        # Full-text FTS updates are intentionally deferred until every shard is present.  The old
        # per-record DELETE/INSERT into an ever-larger FTS table made later shards take ~30 minutes
        # each.  The import ledger makes these rows naturally resumable; a single bulk rebuild at
        # completion is both faster and keeps the reader feature unavailable until complete.
        conn.executemany(
            """INSERT INTO fulltext(paper_id, source_key, text, text_sha256, char_count,
                                     primary_category, source_scope)
               VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(paper_id) DO UPDATE SET
                 source_key = excluded.source_key,
                 text = excluded.text,
                 text_sha256 = excluded.text_sha256,
                 char_count = excluded.char_count,
                 primary_category = excluded.primary_category,
                 source_scope = excluded.source_scope""",
            [row[:7] for row in matches],
        )
        conn.execute("INSERT OR REPLACE INTO processed_shards VALUES (?, ?, datetime('now'), ?)",
                     (shard.name, shard.stat().st_size, len(matches)))
        conn.commit()
        print(f"fulltext: shard {number:,}/{len(remaining):,} {shard.name} — {len(matches):,} matched")
    done = conn.execute("SELECT COUNT(*) FROM processed_shards").fetchone()[0]
    complete = done == len(shards)
    set_state(conn, "fulltext_complete", str(complete).lower())
    set_state(conn, "fulltext_data_complete", str(complete).lower())
    set_state(conn, "fulltext_fts_complete", "false")
    set_state(conn, "fulltext_processed_shards", str(done))
    set_state(conn, "fulltext_total_shards", str(len(shards)))
    conn.commit()
    if complete:
        rebuild_fulltext_fts(conn)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def source_record(dataset_dir: Path) -> dict[str, str]:
    """Read the requested and resolved Hugging Face revisions without network access."""
    out: dict[str, str] = {"requested_revision": "unrecorded", "resolved_revision": "unrecorded"}
    source = dataset_dir / "SOURCE.txt"
    if source.is_file():
        for line in source.read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if not separator:
                continue
            if key == "revision":  # Backward compatibility with the original bootstrap format.
                out["requested_revision"] = value or "unrecorded"
            elif key in out:
                out[key] = value or "unrecorded"
    metadata_files = sorted((dataset_dir / ".cache" / "huggingface" / "download").glob("*.metadata"))
    commits = set()
    for metadata in metadata_files:
        lines = metadata.read_text(encoding="utf-8").splitlines()
        if lines and lines[0].strip():
            commits.add(lines[0].strip())
    if len(commits) == 1:
        out["resolved_revision"] = commits.pop()
    elif commits:
        out["resolved_revision"] = "inconsistent-cache-metadata"
    return out


def input_files(args: argparse.Namespace) -> list[Path]:
    source_dir = args.corpus_dir.expanduser().resolve()
    uw_dir = source_dir / UW_DIRNAME
    marin_dir = source_dir / MARIN_DIRNAME
    return [
        required(uw_dir / "paper.parquet"),
        required(uw_dir / "theorem.parquet"),
        required(uw_dir / "theorem_slogan.parquet"),
        *sorted(marin_dir.glob("*.jsonl.gz")),
    ]


def input_provenance(conn: sqlite3.Connection, args: argparse.Namespace) -> dict:
    """Reuse content hashes only while every immutable raw-input fingerprint matches."""
    files = input_files(args)
    source_dir = args.corpus_dir.expanduser().resolve()
    fingerprints = [
        {
            "path": str(path.relative_to(source_dir)),
            "size_bytes": path.stat().st_size,
            "mtime_ns": path.stat().st_mtime_ns,
        }
        for path in files
    ]
    cached = state(conn, "input_provenance")
    if cached:
        try:
            parsed = json.loads(cached)
            if parsed.get("fingerprints") == fingerprints and parsed.get("sha256"):
                return parsed
        except json.JSONDecodeError:
            pass
    print(f"provenance: hashing {len(files):,} immutable raw input file(s)")
    provenance = {
        "fingerprints": fingerprints,
        "sha256": {str(path.relative_to(source_dir)): sha256_file(path) for path in files},
        "computed_at_epoch_seconds": int(time.time()),
    }
    set_state(conn, "input_provenance", json.dumps(provenance, sort_keys=True))
    conn.commit()
    return provenance


def write_manifest(conn: sqlite3.Connection, index_dir: Path, args: argparse.Namespace) -> None:
    counts = {
        "papers": conn.execute("SELECT COUNT(*) FROM papers").fetchone()[0],
        "theorems": conn.execute("SELECT COUNT(*) FROM theorems").fetchone()[0],
        "fulltext_documents": conn.execute("SELECT COUNT(*) FROM fulltext").fetchone()[0],
        "processed_fulltext_shards": conn.execute("SELECT COUNT(*) FROM processed_shards").fetchone()[0],
        "fulltext_fts_documents": conn.execute("SELECT COUNT(*) FROM fulltext_fts").fetchone()[0],
    }
    provenance = input_provenance(conn, args)
    set_state(conn, "index_format", INDEX_FORMAT)
    conn.commit()
    states = dict(conn.execute("SELECT key, value FROM metadata"))
    source_dir = args.corpus_dir.expanduser().resolve()
    manifest = {
        "format": INDEX_FORMAT,
        "updated_at_epoch_seconds": int(time.time()),
        "index_db": str(index_dir / "literature.db"),
        "source_corpus_dir": str(args.corpus_dir),
        "selection": "all UW labeled theorem records and all available Marin arXiv full text; category filters are query-time policy",
        "counts": counts,
        "stages": states,
        "query_contract": {
            "theorem": "SELECT ... FROM theorem_fts JOIN theorems USING(theorem_id) WHERE theorem_fts MATCH ?",
            "fulltext": "SELECT ... FROM fulltext_fts JOIN fulltext USING(paper_id) WHERE fulltext_fts MATCH ?",
        },
        "provenance": {
            "importer": {
                "version": IMPORTER_VERSION,
                "script_sha256": sha256_file(Path(__file__).resolve()),
            },
            "datasets": {
                "uw-math-ai/theorem-search-dataset": source_record(source_dir / UW_DIRNAME),
                "marin-community/ar5iv-no-problem-markdown": source_record(source_dir / MARIN_DIRNAME),
            },
            "inputs": provenance,
        },
    }
    (index_dir / "MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n")


def title_from_text(text: str) -> str:
    """Best-effort title for broad Marin records that lack downloaded metadata."""
    for line in text.splitlines()[:20]:
        candidate = line.strip().lstrip("#").strip()
        if candidate:
            return candidate[:500]
    return ""


def main() -> int:
    args = parse_args()
    source_dir = args.corpus_dir.expanduser().resolve()
    index_dir = args.index_dir.expanduser().resolve()
    uw_dir = source_dir / UW_DIRNAME
    marin_dir = source_dir / MARIN_DIRNAME
    conn = connect(index_dir)
    try:
        if args.stage in ("all", "metadata"):
            prepare_metadata(conn, required(uw_dir / "paper.parquet"), args.force)
        if args.stage in ("all", "theorems"):
            prepare_theorems(conn, required(uw_dir / "theorem.parquet"),
                             required(uw_dir / "theorem_slogan.parquet"), args.force)
        if args.stage in ("all", "fulltext"):
            prepare_fulltext(conn, marin_dir, args.fulltext_shard_limit)
        if args.stage == "fulltext-fts":
            total_shards = len(list(marin_dir.glob("*.jsonl.gz")))
            done_shards = conn.execute("SELECT COUNT(*) FROM processed_shards").fetchone()[0]
            if done_shards != total_shards:
                raise SystemExit(
                    f"fulltext-fts requires all Marin shards first ({done_shards}/{total_shards} complete)"
                )
            rebuild_fulltext_fts(conn)
        if args.stage == "optimize":
            optimize_fts(conn)
        write_manifest(conn, index_dir, args)
    finally:
        conn.close()
    print(f"index manifest: {index_dir / 'MANIFEST.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
