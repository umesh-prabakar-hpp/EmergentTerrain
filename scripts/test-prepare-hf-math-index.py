#!/usr/bin/env python3
"""Focused regression checks for Phase 45 offline literature selectivity metadata."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("prepare-hf-math-index.py")
SPEC = importlib.util.spec_from_file_location("hf_math_index", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AnchorStatisticsTest(unittest.TestCase):
    def test_materializes_normalized_title_and_theorem_name_document_counts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            conn = MODULE.connect(Path(directory))
            try:
                conn.execute("INSERT INTO metadata VALUES ('metadata_complete', 'true')")
                conn.execute("INSERT INTO metadata VALUES ('theorems_complete', 'true')")
                conn.execute("INSERT INTO papers(paper_id, title) VALUES ('p1', 'Prime Gaps')")
                conn.execute("INSERT INTO papers(paper_id, title) VALUES ('p2', 'Divisor Gap')")
                conn.execute("INSERT INTO theorems(theorem_id, paper_id, name) VALUES (1, 'p1', 'Maximum Prime Gap')")
                conn.execute("INSERT INTO theorems(theorem_id, paper_id, name) VALUES (2, 'p2', 'Divisor Gaps')")
                conn.execute("INSERT INTO fulltext_fts VALUES ('p1', 'Prime Gaps', '')")
                conn.execute("INSERT INTO fulltext_fts VALUES ('p2', 'Divisor Gap', '')")
                conn.execute("INSERT INTO theorem_fts VALUES (1, 'p1', 'Maximum Prime Gap', '', '')")
                conn.execute("INSERT INTO theorem_fts VALUES (2, 'p2', 'Divisor Gaps', '', '')")
                conn.commit()

                MODULE.prepare_anchor_statistics(conn, force=False)
                MODULE.prepare_title_anchor_documents(conn, force=False)

                rows = dict(conn.execute(
                    "SELECT anchor, title_document_count + theorem_name_document_count "
                    "FROM literature_anchor_document_counts"
                ))
                self.assertEqual(2, rows["prime"])
                self.assertEqual(4, rows["gap"])
                self.assertEqual(2, rows["divisor"])
                self.assertEqual(MODULE.PHASE45_ANCHOR_STATISTICS_VERSION,
                                 MODULE.state(conn, "phase45_anchor_statistics_version"))
                self.assertEqual(
                    [("gap", "p1"), ("gap", "p2"), ("prime", "p1")],
                    conn.execute("SELECT anchor, paper_id FROM literature_title_anchor_documents "
                                 "WHERE anchor IN ('gap', 'prime') ORDER BY anchor, paper_id").fetchall(),
                )
                self.assertEqual(MODULE.PHASE45_TITLE_ANCHOR_DOCUMENTS_VERSION,
                                 MODULE.state(conn, "phase45_title_anchor_documents_version"))
            finally:
                conn.close()


if __name__ == "__main__":
    unittest.main()
