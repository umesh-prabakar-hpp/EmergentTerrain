#!/usr/bin/env python3
"""Incrementally build the paper-review-screened literature index.

This deliberately small index contains only source text that a paper-review pass downloaded,
hash-validated, and marked RETAINED_FOR_IMPORT. It is independent of the large Phase 34 corpus:
each source adds one FTS row rather than rebuilding the primary corpus-wide FTS table.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
from pathlib import Path


DEFAULT_INDEX_DIR = Path.home() / ".emergentterrain" / "indexes" / "math-literature-paper-review-screened"
TEXT_EXTENSIONS = {".tex", ".txt", ".bib", ".sty"}
INDEXABLE_EXTENSIONS = {".tex", ".txt", ".bib"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged-text-manifest", required=True, type=Path,
                        help="paper-review provenance manifest containing RETAINED_FOR_IMPORT sources")
    parser.add_argument("--index-dir", type=Path, default=DEFAULT_INDEX_DIR,
                        help=f"separate incremental SQLite/FTS5 index (default: {DEFAULT_INDEX_DIR})")
    return parser.parse_args()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def normalized_text(value: str) -> str:
    return "\n".join(line.rstrip() for line in value.replace("\r\n", "\n").split("\n")).strip()


def title_from_text(text: str, fallback: str) -> str:
    for line in text.splitlines()[:80]:
        candidate = line.strip()
        if candidate.startswith("\\title{") and candidate.endswith("}"):
            return candidate[len("\\title{"):-1].strip()[:500]
        if candidate.startswith("#"):
            candidate = candidate.lstrip("#").strip()
        if candidate and not candidate.startswith(("%", "\\documentclass", "\\usepackage")):
            return candidate[:500]
    return fallback


def read_retained_sources(manifest_path: Path) -> list[dict]:
    manifest_path = manifest_path.expanduser().resolve()
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise SystemExit(f"staged text manifest is missing: {manifest_path}") from exc
    except json.JSONDecodeError as exc:
        raise SystemExit(f"staged text manifest is invalid JSON: {manifest_path}") from exc
    if "provenance-recorded text-source staging" not in manifest.get("purpose", ""):
        raise SystemExit("staged text manifest does not declare the expected provenance purpose")

    base = manifest_path.parent
    sources: list[dict] = []
    for subject in manifest.get("subjects", []):
        for source in subject.get("sources", []):
            if source.get("status") != "RETAINED_FOR_IMPORT":
                continue
            arxiv_id = source.get("arxiv_id")
            canonical_url = source.get("canonical_source_url")
            file_names = source.get("text_files")
            expected_hashes = source.get("text_sha256")
            if not isinstance(arxiv_id, str) or not arxiv_id or ".." in arxiv_id:
                raise SystemExit("retained source has an invalid arXiv ID")
            if not isinstance(canonical_url, str) or not isinstance(file_names, list) or not isinstance(expected_hashes, dict):
                raise SystemExit(f"retained source {arxiv_id} lacks provenance fields")
            verified_files: list[str] = []
            text_blocks: list[str] = []
            for name in file_names:
                relative = Path(name)
                candidate = (base / relative).resolve()
                if (not isinstance(name, str) or candidate == base or base not in candidate.parents
                        or candidate.suffix.lower() not in TEXT_EXTENSIONS):
                    raise SystemExit(f"retained source {arxiv_id} has an unsafe source path: {name}")
                if sha256_file(candidate) != expected_hashes.get(name):
                    raise SystemExit(f"retained source {arxiv_id} failed SHA-256 validation: {name}")
                verified_files.append(name)
                if candidate.suffix.lower() in INDEXABLE_EXTENSIONS:
                    text_blocks.append(candidate.read_text(encoding="utf-8", errors="replace"))
            text = normalized_text("\n\n".join(text_blocks))
            if not text:
                raise SystemExit(f"retained source {arxiv_id} has no indexable text")
            sources.append({
                "arxiv_id": arxiv_id,
                "canonical_source_url": canonical_url,
                "paper_ids": subject.get("paper_ids", []),
                "reason": source.get("reason", ""),
                "verified_files": verified_files,
                "text": text,
                "text_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
            })
    if not sources:
        raise SystemExit("staged text manifest has no RETAINED_FOR_IMPORT sources")
    return sources


def connect(index_dir: Path) -> sqlite3.Connection:
    index_dir.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(index_dir / "literature.db")
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA busy_timeout=60000")
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS papers (
          paper_id TEXT PRIMARY KEY, title TEXT NOT NULL, authors TEXT NOT NULL,
          link TEXT NOT NULL, primary_category TEXT NOT NULL, source TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS fulltext (
          paper_id TEXT PRIMARY KEY, source_key TEXT NOT NULL, text TEXT NOT NULL,
          text_sha256 TEXT NOT NULL, char_count INTEGER NOT NULL, source_scope TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS paper_review_imports (
          source_key TEXT PRIMARY KEY, manifest_sha256 TEXT NOT NULL, arxiv_id TEXT NOT NULL,
          paper_id TEXT NOT NULL, canonical_source_url TEXT NOT NULL, content_sha256 TEXT NOT NULL,
          source_files_json TEXT NOT NULL, paper_ids_json TEXT NOT NULL, imported_at TEXT NOT NULL
        );
        CREATE VIRTUAL TABLE IF NOT EXISTS fulltext_fts USING fts5(
          paper_id UNINDEXED, title, text, tokenize='unicode61 remove_diacritics 2'
        );
    """)
    return conn


def import_sources(conn: sqlite3.Connection, manifest_path: Path, sources: list[dict]) -> tuple[int, int]:
    manifest_sha = sha256_file(manifest_path)
    imported = 0
    already_present = 0
    for source in sources:
        paper_id = source["arxiv_id"]
        source_key = f"paper-review-screened:{manifest_sha}:{paper_id}"
        existing = conn.execute("SELECT source_key, text_sha256 FROM fulltext WHERE paper_id = ?", (paper_id,)).fetchone()
        if existing:
            if existing[1] == source["text_sha256"]:
                already_present += 1
                continue
            raise SystemExit(f"screened index refuses source-text replacement for {paper_id}")
        title = title_from_text(source["text"], paper_id)
        conn.execute("""INSERT INTO papers(paper_id, title, authors, link, primary_category, source)
                        VALUES (?, ?, '', ?, 'math.NT', 'paper-review-screened')""",
                     (paper_id, title, source["canonical_source_url"]))
        conn.execute("""INSERT INTO fulltext(paper_id, source_key, text, text_sha256, char_count, source_scope)
                        VALUES (?, ?, ?, ?, ?, 'paper-review-screened-provenance')""",
                     (paper_id, source_key, source["text"], source["text_sha256"], len(source["text"])))
        rowid = conn.execute("SELECT rowid FROM fulltext WHERE paper_id = ?", (paper_id,)).fetchone()[0]
        conn.execute("INSERT INTO fulltext_fts(rowid, paper_id, title, text) VALUES (?, ?, ?, ?)",
                     (rowid, paper_id, title, source["text"]))
        conn.execute("""INSERT INTO paper_review_imports(
                         source_key, manifest_sha256, arxiv_id, paper_id, canonical_source_url,
                         content_sha256, source_files_json, paper_ids_json, imported_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))""",
                     (source_key, manifest_sha, source["arxiv_id"], paper_id, source["canonical_source_url"],
                      source["text_sha256"], json.dumps(source["verified_files"], sort_keys=True),
                      json.dumps(source["paper_ids"], sort_keys=True)))
        imported += 1
    conn.execute("INSERT OR REPLACE INTO metadata VALUES ('index_format', 'paper-review-screened-v1')")
    conn.execute("INSERT OR REPLACE INTO metadata VALUES ('fulltext_fts_complete', 'true')")
    conn.execute("INSERT OR REPLACE INTO metadata VALUES ('last_manifest_sha256', ?)", (manifest_sha,))
    conn.execute("INSERT OR REPLACE INTO metadata VALUES ('last_imported', ?)", (str(imported),))
    conn.commit()
    return imported, already_present


def write_manifest(conn: sqlite3.Connection, index_dir: Path) -> None:
    payload = {
        "format": "paper-review-screened-v1",
        "index_db": str(index_dir / "literature.db"),
        "scope": "hash-validated paper-review web discoveries; separate from the primary Phase 34 corpus",
        "counts": {
            "papers": conn.execute("SELECT COUNT(*) FROM papers").fetchone()[0],
            "fulltext_documents": conn.execute("SELECT COUNT(*) FROM fulltext").fetchone()[0],
            "fulltext_fts_documents": conn.execute("SELECT COUNT(*) FROM fulltext_fts").fetchone()[0],
            "imports": conn.execute("SELECT COUNT(*) FROM paper_review_imports").fetchone()[0],
        },
        "stages": dict(conn.execute("SELECT key, value FROM metadata")),
    }
    (index_dir / "MANIFEST.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    manifest_path = args.staged_text_manifest.expanduser().resolve()
    index_dir = args.index_dir.expanduser().resolve()
    sources = read_retained_sources(manifest_path)
    conn = connect(index_dir)
    try:
        imported, already_present = import_sources(conn, manifest_path, sources)
        write_manifest(conn, index_dir)
    finally:
        conn.close()
    print(f"paper-review-screened-index: imported={imported} already_present={already_present} index={index_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
