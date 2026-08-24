#!/usr/bin/env python3
"""Fail a release check when local literature data or non-exportable sources are included."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys


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
    parser.add_argument("--repo", type=Path, default=Path.cwd(), help="repository root (default: current directory)")
    parser.add_argument(
        "--export-manifest", type=Path, action="append", default=[],
        help="staging manifest whose retained text would be included in an export; repeat as needed",
    )
    return parser.parse_args()


def prohibited_tracked_path(path: str) -> bool:
    normalized = path.replace("\\", "/")
    return (
        "/literature-staging/sources/" in normalized
        or "/corpus-hf-math/" in normalized
        or normalized.startswith("corpus-hf-math/")
        or normalized.endswith("/literature.db")
        or normalized == "literature.db"
        or normalized.endswith("/literature.db-wal")
        or normalized.endswith("/literature.db-shm")
    )


def tracked_files(repo: Path) -> list[str]:
    completed = subprocess.run(
        ["git", "-C", str(repo), "ls-files", "-z"],
        check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    return [path for path in completed.stdout.decode("utf-8").split("\0") if path]


def exportable_sources(manifest_path: Path) -> list[str]:
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ValueError(f"export manifest is missing: {manifest_path}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"export manifest is invalid JSON: {manifest_path}") from exc
    if manifest.get("schema_version") != 3:
        raise ValueError(f"export manifest must use schema_version 3: {manifest_path}")
    blocked: list[str] = []
    for subject in manifest.get("subjects", []):
        for source in subject.get("sources", []):
            if source.get("status") != "RETAINED_FOR_IMPORT":
                continue
            arxiv_id = source.get("arxiv_id", "<unknown>")
            license_kind = source.get("license_kind")
            license_url = source.get("license_url")
            redistribution_allowed = source.get("redistribution_allowed")
            if license_kind not in LICENSE_KINDS:
                blocked.append(f"{arxiv_id}: invalid license_kind {license_kind!r}")
            elif not isinstance(license_url, str) or not license_url.startswith("https://"):
                blocked.append(f"{arxiv_id}: missing HTTPS license_url")
            elif redistribution_allowed is not True:
                blocked.append(f"{arxiv_id}: source is not approved for redistribution ({license_kind})")
    return blocked


def main() -> int:
    args = parse_args()
    repo = args.repo.resolve()
    violations = [path for path in tracked_files(repo) if prohibited_tracked_path(path)]
    for manifest in args.export_manifest:
        try:
            violations.extend(exportable_sources(manifest.expanduser().resolve()))
        except ValueError as exc:
            violations.append(str(exc))
    if violations:
        print("literature release check failed:", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1
    print("literature release check passed: no tracked corpus/index/staged source data; export manifests are redistributable.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
