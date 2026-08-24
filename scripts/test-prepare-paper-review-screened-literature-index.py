#!/usr/bin/env python3
"""Focused regression checks for paper-review-screened index metadata extraction."""

from __future__ import annotations

import importlib.util
import hashlib
import json
import unittest
from pathlib import Path
import tempfile


SCRIPT = Path(__file__).with_name("prepare-paper-review-screened-literature-index.py")
SPEC = importlib.util.spec_from_file_location("paper_review_screened_index", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ScreenedIndexTitleExtractionTest(unittest.TestCase):
    def test_indented_tex_title_is_display_metadata(self) -> None:
        text = "  \\title{Computations of the Mertens Function}\n\\begin{document}"
        self.assertEqual(
            "Computations of the Mertens Function",
            MODULE.title_from_text(text, "fallback"),
        )

    def test_commented_tex_title_does_not_become_display_metadata(self) -> None:
        text = "% \\title{Discarded title}\nA readable fallback title"
        self.assertEqual("A readable fallback title", MODULE.title_from_text(text, "fallback"))

    def test_schema_v3_retains_explicit_license_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source_path = root / "sources" / "2209.14573" / "paper.tex"
            source_path.parent.mkdir(parents=True)
            source_path.write_text("\\title{Licensed source}\n", encoding="utf-8")
            relative = "sources/2209.14573/paper.tex"
            manifest = {
                "schema_version": 3,
                "purpose": "paper-review provenance-recorded text-source staging; separate review index only",
                "subjects": [{"paper_ids": ["P-1"], "sources": [{
                    "status": "RETAINED_FOR_IMPORT", "arxiv_id": "2209.14573",
                    "canonical_source_url": "https://arxiv.org/abs/2209.14573",
                    "discovery_lane": "target", "relevance_tier": "DIRECT_TARGET",
                    "text_files": [relative],
                    "text_sha256": {relative: hashlib.sha256(source_path.read_bytes()).hexdigest()},
                    "license_kind": "CC0-1.0",
                    "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
                    "redistribution_allowed": True,
                }]}],
            }
            manifest_path = root / "staging-manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            source = MODULE.read_retained_sources(manifest_path)[0]
            self.assertEqual("CC0-1.0", source["license_kind"])
            self.assertTrue(source["redistribution_allowed"])
            conn = MODULE.connect(root / "index")
            try:
                self.assertEqual((1, 0), MODULE.import_sources(conn, manifest_path, [source]))
                self.assertEqual(
                    ("CC0-1.0", "https://creativecommons.org/publicdomain/zero/1.0/", 1),
                    conn.execute(
                        "SELECT license_kind, license_url, redistribution_allowed FROM paper_review_imports"
                    ).fetchone(),
                )
            finally:
                conn.close()

    def test_schema_v3_rejects_nonexclusive_export_flag(self) -> None:
        source = {
            "license_kind": "ARXIV-NONEXCLUSIVE-1.0",
            "license_url": "https://arxiv.org/licenses/nonexclusive-distrib/1.0/",
            "redistribution_allowed": True,
        }
        with self.assertRaisesRegex(SystemExit, "cannot permit redistribution"):
            MODULE.source_license_metadata(source, 3, "1603.05809")


if __name__ == "__main__":
    unittest.main()
