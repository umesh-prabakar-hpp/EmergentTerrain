#!/usr/bin/env python3
"""Focused regression checks for paper-review-screened index metadata extraction."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


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


if __name__ == "__main__":
    unittest.main()
