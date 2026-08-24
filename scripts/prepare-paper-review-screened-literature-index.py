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
import re
import sqlite3
from pathlib import Path


DEFAULT_INDEX_DIR = Path.home() / ".emergentterrain" / "indexes" / "math-literature-paper-review-screened"
TEXT_EXTENSIONS = {".tex", ".txt", ".bib", ".sty"}
INDEXABLE_EXTENSIONS = {".tex", ".txt", ".bib"}
SUBJECT_MAP_LANES = {"target", "quantitative", "finite_method", "caveat"}
RELEVANCE_TIERS = {"DIRECT_TARGET", "METHOD_CONTEXT", "BACKGROUND_ONLY", "REJECTED_LOW_RELEVANCE"}
IMPORTABLE_RELEVANCE_TIERS = {"DIRECT_TARGET", "METHOD_CONTEXT"}
LICENSE_KINDS = {
    "CC0-1.0",
    "CC-BY-4.0",
    "CC-BY-SA-4.0",
    "C-UDA-1.0",
    "ARXIV-NONEXCLUSIVE-1.0",
    "UNKNOWN",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged-text-manifest", type=Path,
                        help="paper-review provenance manifest containing RETAINED_FOR_IMPORT sources")
    parser.add_argument("--index-dir", type=Path, default=DEFAULT_INDEX_DIR,
                        help=f"separate incremental SQLite/FTS5 index (default: {DEFAULT_INDEX_DIR})")
    parser.add_argument("--repair-titles", action="store_true",
                        help="repair TeX-derived display titles already present in the screened index")
    args = parser.parse_args()
    if args.staged_text_manifest is None and not args.repair_titles:
        parser.error("--staged-text-manifest is required unless --repair-titles is supplied")
    return args


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def normalized_text(value: str) -> str:
    return "\n".join(line.rstrip() for line in value.replace("\r\n", "\n").split("\n")).strip()


def title_from_text(text: str, fallback: str) -> str:
    prefix = "\n".join(text.splitlines()[:500])
    title = extract_tex_title(prefix)
    if title:
        cleaned = clean_title(title)
        if cleaned:
            return cleaned[:500]
    for line in prefix.splitlines():
        candidate = line.strip()
        if candidate.startswith("#"):
            candidate = candidate.lstrip("#").strip()
        # TeX commands are never a useful fallback title.  A real \title was handled above;
        # skipping every other command prevents metadata such as \@namedef or \DeclarePairedDelimiter.
        if candidate.startswith("\\"):
            continue
        if candidate and not candidate.startswith("%"):
            cleaned = clean_title(candidate)
            if cleaned and not cleaned.startswith("\\"):
                return cleaned[:500]
    return fallback


def extract_tex_title(text: str) -> str | None:
    """Extract one balanced TeX title declaration or common display-title block.

    TeX sources commonly split the title over several lines or place braces inside
    math/macros.  A line regex therefore falls through to preamble declarations.
    This scanner is deliberately small but balanced-brace aware; malformed source
    simply returns no title and never makes a command a fallback title.
    """
    # TeX preambles commonly indent declarations. Anchor after optional horizontal
    # whitespace so an actual ``  \\title{...}`` is found, while deliberately not
    # allowing a leading ``%``: commented-out declarations are not metadata.
    match = re.search(r"(?m)^[ \t]*\\(?:title|Title)(?:\[[^]]*])?\s*\{", text)
    if match:
        return extract_balanced_block(text, match.end())
    # Some journal templates render a title directly instead of declaring \title.
    # Keep this fallback intentionally narrow so preamble macros cannot become titles.
    match = re.search(r"(?m)^[ \t]*\\(?:LARGE|Large|large)\s*\{", text)
    return extract_balanced_block(text, match.end()) if match else None


def extract_balanced_block(text: str, start: int) -> str | None:
    """Return the content of the brace block that begins immediately before ``start``."""
    depth = 1
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if escaped:
            escaped = False
            continue
        if char == "\\\\":
            escaped = True
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:index]
    return None


def clean_title(value: str) -> str:
    """Return readable metadata, never a TeX declaration or macro definition."""
    cleaned = re.sub(r"\\(?:textit|textbf|emph|mathrm|mathit)\s*\{([^{}]*)}", r"\1", value)
    cleaned = re.sub(r"\\[A-Za-z@]+(?:\[[^]]*])?", "", cleaned)
    cleaned = cleaned.replace("\\\\", " ")
    cleaned = re.sub(r"\\['`\^\"~=.]\s*\{?([A-Za-z])\}?", r"\1", cleaned)
    cleaned = cleaned.replace("{", "").replace("}", "")
    return re.sub(r"\s+", " ", cleaned).strip()


def source_license_metadata(source: dict, schema_version: int, arxiv_id: str) -> tuple[str, str, bool]:
    """Return explicit export metadata, defaulting legacy records to non-exportable."""
    if schema_version < 3:
        return "UNKNOWN", "", False
    license_kind = source.get("license_kind")
    license_url = source.get("license_url")
    redistribution_allowed = source.get("redistribution_allowed")
    if license_kind not in LICENSE_KINDS:
        raise SystemExit(f"retained source {arxiv_id} has an invalid license_kind: {license_kind!r}")
    if not isinstance(license_url, str) or not license_url.startswith("https://"):
        raise SystemExit(f"retained source {arxiv_id} has an invalid license_url")
    if not isinstance(redistribution_allowed, bool):
        raise SystemExit(f"retained source {arxiv_id} has a non-boolean redistribution_allowed")
    if license_kind in {"UNKNOWN", "ARXIV-NONEXCLUSIVE-1.0"} and redistribution_allowed:
        raise SystemExit(f"retained source {arxiv_id} cannot permit redistribution under {license_kind}")
    return license_kind, license_url, redistribution_allowed


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
    schema_version = manifest.get("schema_version", 1)
    if not isinstance(schema_version, int) or schema_version < 1:
        raise SystemExit("staged text manifest has an invalid schema_version")

    base = manifest_path.parent
    sources: list[dict] = []
    for subject in manifest.get("subjects", []):
        for source in subject.get("sources", []):
            if schema_version >= 2:
                lane = source.get("discovery_lane")
                tier = source.get("relevance_tier")
                if lane not in SUBJECT_MAP_LANES:
                    raise SystemExit(f"source has an invalid subject-map discovery_lane: {lane!r}")
                if tier not in RELEVANCE_TIERS:
                    raise SystemExit(f"source has an invalid relevance_tier: {tier!r}")
            if source.get("status") != "RETAINED_FOR_IMPORT":
                continue
            if schema_version >= 2 and source.get("relevance_tier") not in IMPORTABLE_RELEVANCE_TIERS:
                raise SystemExit("only DIRECT_TARGET or METHOD_CONTEXT sources may be retained for import")
            arxiv_id = source.get("arxiv_id")
            canonical_url = source.get("canonical_source_url")
            file_names = source.get("text_files")
            expected_hashes = source.get("text_sha256")
            if not isinstance(arxiv_id, str) or not arxiv_id or ".." in arxiv_id:
                raise SystemExit("retained source has an invalid arXiv ID")
            if not isinstance(canonical_url, str) or not isinstance(file_names, list) or not isinstance(expected_hashes, dict):
                raise SystemExit(f"retained source {arxiv_id} lacks provenance fields")
            license_kind, license_url, redistribution_allowed = source_license_metadata(
                source, schema_version, arxiv_id
            )
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
                "license_kind": license_kind,
                "license_url": license_url,
                "redistribution_allowed": redistribution_allowed,
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
          link TEXT NOT NULL, primary_category TEXT NOT NULL, source TEXT NOT NULL,
          license TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE IF NOT EXISTS fulltext (
          paper_id TEXT PRIMARY KEY, source_key TEXT NOT NULL, text TEXT NOT NULL,
          text_sha256 TEXT NOT NULL, char_count INTEGER NOT NULL, source_scope TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS paper_review_imports (
          source_key TEXT PRIMARY KEY, manifest_sha256 TEXT NOT NULL, arxiv_id TEXT NOT NULL,
          paper_id TEXT NOT NULL, canonical_source_url TEXT NOT NULL, content_sha256 TEXT NOT NULL,
          source_files_json TEXT NOT NULL, paper_ids_json TEXT NOT NULL,
          license_kind TEXT NOT NULL DEFAULT 'UNKNOWN', license_url TEXT NOT NULL DEFAULT '',
          redistribution_allowed INTEGER NOT NULL DEFAULT 0, imported_at TEXT NOT NULL
        );
        CREATE VIRTUAL TABLE IF NOT EXISTS fulltext_fts USING fts5(
          paper_id UNINDEXED, title, text, tokenize='unicode61 remove_diacritics 2'
        );
    """)
    paper_columns = {row[1] for row in conn.execute("PRAGMA table_info(papers)")}
    if "license" not in paper_columns:
        conn.execute("ALTER TABLE papers ADD COLUMN license TEXT NOT NULL DEFAULT ''")
    import_columns = {row[1] for row in conn.execute("PRAGMA table_info(paper_review_imports)")}
    if "license_kind" not in import_columns:
        conn.execute("ALTER TABLE paper_review_imports ADD COLUMN license_kind TEXT NOT NULL DEFAULT 'UNKNOWN'")
    if "license_url" not in import_columns:
        conn.execute("ALTER TABLE paper_review_imports ADD COLUMN license_url TEXT NOT NULL DEFAULT ''")
    if "redistribution_allowed" not in import_columns:
        conn.execute("ALTER TABLE paper_review_imports ADD COLUMN redistribution_allowed INTEGER NOT NULL DEFAULT 0")
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
        conn.execute("""INSERT INTO papers(paper_id, title, authors, link, primary_category, source, license)
                        VALUES (?, ?, '', ?, 'math.NT', 'paper-review-screened', ?)""",
                     (paper_id, title, source["canonical_source_url"], source["license_kind"]))
        conn.execute("""INSERT INTO fulltext(paper_id, source_key, text, text_sha256, char_count, source_scope)
                        VALUES (?, ?, ?, ?, ?, 'paper-review-screened-provenance')""",
                     (paper_id, source_key, source["text"], source["text_sha256"], len(source["text"])))
        rowid = conn.execute("SELECT rowid FROM fulltext WHERE paper_id = ?", (paper_id,)).fetchone()[0]
        conn.execute("INSERT INTO fulltext_fts(rowid, paper_id, title, text) VALUES (?, ?, ?, ?)",
                     (rowid, paper_id, title, source["text"]))
        conn.execute("""INSERT INTO paper_review_imports(
                         source_key, manifest_sha256, arxiv_id, paper_id, canonical_source_url,
                         content_sha256, source_files_json, paper_ids_json, license_kind, license_url,
                         redistribution_allowed, imported_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))""",
                     (source_key, manifest_sha, source["arxiv_id"], paper_id, source["canonical_source_url"],
                      source["text_sha256"], json.dumps(source["verified_files"], sort_keys=True),
                      json.dumps(source["paper_ids"], sort_keys=True), source["license_kind"],
                      source["license_url"], int(source["redistribution_allowed"])))
        imported += 1
    conn.execute("INSERT OR REPLACE INTO metadata VALUES ('index_format', 'paper-review-screened-v1')")
    conn.execute("INSERT OR REPLACE INTO metadata VALUES ('fulltext_fts_complete', 'true')")
    conn.execute("INSERT OR REPLACE INTO metadata VALUES ('last_manifest_sha256', ?)", (manifest_sha,))
    conn.execute("INSERT OR REPLACE INTO metadata VALUES ('last_imported', ?)", (str(imported),))
    conn.commit()
    return imported, already_present


def repair_titles(conn: sqlite3.Connection) -> int:
    """Refresh historical TeX-derived display titles without replacing source text."""
    repaired = 0
    rows = conn.execute("""SELECT f.rowid, f.paper_id, f.text, p.title
                           FROM fulltext f JOIN papers p ON p.paper_id = f.paper_id""").fetchall()
    for rowid, paper_id, text, old_title in rows:
        new_title = title_from_text(text, paper_id)
        if new_title == old_title:
            continue
        conn.execute("UPDATE papers SET title = ? WHERE paper_id = ?", (new_title, paper_id))
        conn.execute("DELETE FROM fulltext_fts WHERE rowid = ?", (rowid,))
        conn.execute("INSERT INTO fulltext_fts(rowid, paper_id, title, text) VALUES (?, ?, ?, ?)",
                     (rowid, paper_id, new_title, text))
        repaired += 1
    return repaired


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
    index_dir = args.index_dir.expanduser().resolve()
    conn = connect(index_dir)
    try:
        repaired = repair_titles(conn) if args.repair_titles else 0
        imported = already_present = 0
        if args.staged_text_manifest is not None:
            manifest_path = args.staged_text_manifest.expanduser().resolve()
            sources = read_retained_sources(manifest_path)
            imported, already_present = import_sources(conn, manifest_path, sources)
        # A repair-only run mutates SQLite rows without passing through import_sources().
        # Commit it explicitly so title-gated retrieval never continues to see stale metadata.
        conn.commit()
        write_manifest(conn, index_dir)
    finally:
        conn.close()
    print("paper-review-screened-index: "
          f"imported={imported} already_present={already_present} repaired_titles={repaired} index={index_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
