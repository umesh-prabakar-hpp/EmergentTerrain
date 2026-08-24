#!/usr/bin/env python3
"""Focused regression checks for literature release guardrails."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("check-literature-release.py")
SPEC = importlib.util.spec_from_file_location("literature_release_check", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class LiteratureReleaseCheckTest(unittest.TestCase):
    def test_literature_payload_paths_are_prohibited_but_other_runtime_data_is_not(self) -> None:
        self.assertTrue(MODULE.prohibited_tracked_path("apps/research-service/research/run/literature-staging/sources/1/a.tex"))
        self.assertTrue(MODULE.prohibited_tracked_path("cache/literature.db"))
        self.assertFalse(MODULE.prohibited_tracked_path("apps/game-service/research/llm-traces/old.jsonl"))
        self.assertFalse(MODULE.prohibited_tracked_path("apps/research-service/src/main/java/App.java"))

    def test_export_manifest_rejects_arxiv_nonexclusive_source(self) -> None:
        manifest = {
            "schema_version": 3,
            "subjects": [{"sources": [{
                "status": "RETAINED_FOR_IMPORT", "arxiv_id": "1603.05809",
                "license_kind": "ARXIV-NONEXCLUSIVE-1.0",
                "license_url": "https://arxiv.org/licenses/nonexclusive-distrib/1.0/",
                "redistribution_allowed": False,
            }]}],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "staging-manifest.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual(
                ["1603.05809: source is not approved for redistribution (ARXIV-NONEXCLUSIVE-1.0)"],
                MODULE.exportable_sources(path),
            )

    def test_export_manifest_accepts_cc0_source(self) -> None:
        manifest = {
            "schema_version": 3,
            "subjects": [{"sources": [{
                "status": "RETAINED_FOR_IMPORT", "arxiv_id": "2209.14573",
                "license_kind": "CC0-1.0",
                "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
                "redistribution_allowed": True,
            }]}],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "staging-manifest.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual([], MODULE.exportable_sources(path))


if __name__ == "__main__":
    unittest.main()
