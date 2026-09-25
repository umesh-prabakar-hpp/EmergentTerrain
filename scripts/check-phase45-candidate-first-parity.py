#!/usr/bin/env python3
"""Compare a bounded Phase 45 candidate-first theorem surface with existing FTS membership.

This is a read-only operator diagnostic. It deliberately reports membership only: FTS rank remains
query-dependent and must be compared separately before any runtime result ordering can change.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sqlite3


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index-dir", type=Path, required=True)
    parser.add_argument("--title-anchor", action="append", required=True)
    parser.add_argument("--name-anchor", action="append", required=True)
    return parser.parse_args()


def main() -> int:
    args = arguments()
    if len(args.title_anchor) != 2 or len(args.name_anchor) != 2:
        raise SystemExit("exactly two --title-anchor and two --name-anchor values are required")
    database = args.index_dir.expanduser().resolve() / "literature.db"
    if not database.is_file():
        raise SystemExit(f"index database is missing: {database}")
    expression = " AND ".join(f"name : {anchor.lower()}" for anchor in args.name_anchor)
    with sqlite3.connect(f"file:{database}?mode=ro", uri=True) as connection:
        required = {
            row[0]: row[1] for row in connection.execute(
                "SELECT key, value FROM metadata WHERE key IN (?, ?)",
                ("phase45_title_anchor_documents_version",
                 "phase45_theorem_name_token_documents_version"))
        }
        if len(required) != 2:
            raise SystemExit("index lacks completed Phase 45 title/token metadata")
        candidate_rows = {row[0] for row in connection.execute("""
            WITH title_candidates AS (
                SELECT paper_id FROM literature_title_anchor_documents
                 WHERE anchor IN (?, ?) GROUP BY paper_id HAVING COUNT(DISTINCT anchor) = 2
            )
            SELECT tokens.theorem_rowid
              FROM literature_theorem_name_token_documents tokens
              JOIN theorem_fts fts ON fts.rowid = tokens.theorem_rowid
              JOIN theorems theorem ON theorem.theorem_id = fts.theorem_id
              JOIN title_candidates title ON title.paper_id = theorem.paper_id
             WHERE tokens.token IN (?, ?)
             GROUP BY tokens.theorem_rowid HAVING COUNT(DISTINCT tokens.token) = 2
        """, (*map(str.lower, args.title_anchor), *map(str.lower, args.name_anchor)))}
        fts_rows = {row[0] for row in connection.execute("""
            WITH title_candidates AS (
                SELECT paper_id FROM literature_title_anchor_documents
                 WHERE anchor IN (?, ?) GROUP BY paper_id HAVING COUNT(DISTINCT anchor) = 2
            )
            SELECT fts.rowid FROM theorem_fts fts
              JOIN theorems theorem ON theorem.theorem_id = fts.theorem_id
              JOIN title_candidates title ON title.paper_id = theorem.paper_id
             WHERE theorem_fts MATCH ?
        """, (*map(str.lower, args.title_anchor), expression))}
    print(json.dumps({
        "candidate_first_rows": len(candidate_rows),
        "fts_rows": len(fts_rows),
        "membership_parity": candidate_rows == fts_rows,
        "candidate_only_rows": len(candidate_rows - fts_rows),
        "fts_only_rows": len(fts_rows - candidate_rows),
    }, indent=2))
    return 0 if candidate_rows == fts_rows else 1


if __name__ == "__main__":
    raise SystemExit(main())
