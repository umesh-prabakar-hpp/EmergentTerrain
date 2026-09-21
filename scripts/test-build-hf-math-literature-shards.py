#!/usr/bin/env python3
"""Focused regression tests for the offline Phase 45 routed-shard builder."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest

MODULE_PATH = Path(__file__).with_name("build-hf-math-literature-shards.py")
SPEC = importlib.util.spec_from_file_location("phase45_shard_builder", MODULE_PATH)
assert SPEC and SPEC.loader
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


class CrossListCoverageTest(unittest.TestCase):
    def test_cross_list_contains_categories_outside_named_subject_lanes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "literature.db"
            destination = Path(temporary) / "cross-list"
            with sqlite3.connect(source) as connection:
                connection.executescript("""
                    CREATE TABLE papers (paper_id TEXT PRIMARY KEY, primary_category TEXT);
                    CREATE TABLE theorems (theorem_id INTEGER PRIMARY KEY, paper_id TEXT);
                    CREATE TABLE fulltext (paper_id TEXT PRIMARY KEY, source_key TEXT);
                    CREATE VIRTUAL TABLE theorem_fts USING fts5(theorem_id UNINDEXED, paper_id UNINDEXED, name, slogan, body);
                    CREATE VIRTUAL TABLE fulltext_fts USING fts5(paper_id UNINDEXED, title, text);
                """)
                connection.executemany("INSERT INTO papers VALUES (?, ?)", [
                    ("nt", "math.NT"), ("gm", "math.GM"), ("cs", "cs.AI")])
                connection.executemany("INSERT INTO theorems VALUES (?, ?)", [(1, "nt"), (2, "gm"), (3, "cs")])
                connection.executemany("INSERT INTO fulltext VALUES (?, ?)", [("nt", "nt"), ("gm", "gm"), ("cs", "cs")])
                connection.executemany("INSERT INTO theorem_fts VALUES (?, ?, ?, ?, ?)", [
                    (1, "nt", "nt", "", ""), (2, "gm", "gm", "", ""), (3, "cs", "cs", "", "")])
                connection.executemany("INSERT INTO fulltext_fts VALUES (?, ?, ?)", [
                    ("nt", "NT", ""), ("gm", "GM", ""), ("cs", "CS", "")])

            BUILDER.build_one(source, destination, None)
            with sqlite3.connect(destination / "literature.db") as connection:
                paper_ids = {row[0] for row in connection.execute("SELECT paper_id FROM papers")}
                fts_paper_ids = {row[0] for row in connection.execute("""
                    SELECT f.paper_id
                      FROM fulltext_fts ff
                      JOIN fulltext f ON f.rowid = ff.rowid
                     WHERE fulltext_fts MATCH 'GM'
                """)}

            self.assertEqual({"gm", "cs"}, paper_ids)
            self.assertEqual({"gm"}, fts_paper_ids)


if __name__ == "__main__":
    unittest.main()
