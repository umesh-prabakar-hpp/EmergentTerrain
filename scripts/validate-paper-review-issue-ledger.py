#!/usr/bin/env python3
"""Validate the explicit lifecycle ledger used by split paper-review reports."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


STATES = {"NEW", "CARRIED_OVER", "RESOLVED", "REGRESSED", "NOT_RECHECKED"}
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}(?:T[0-2]\d:[0-5]\d(?::[0-5]\d)?(?:Z|[+-]\d\d:\d\d)?)?$")
ISSUE_ID = re.compile(r"^PR-\d{8}-\d{2}$")


def cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ledger", nargs="?", type=Path,
                        default=Path("research/reviews/issue-ledger.md"))
    args = parser.parse_args()
    path = args.ledger.expanduser().resolve()
    if not path.is_file():
        raise SystemExit(f"missing issue ledger: {path}")

    rows: list[list[str]] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.startswith("| PR-"):
            row = cells(raw)
            if len(row) != 7:
                raise SystemExit(f"{path}: expected 7 lifecycle columns: {raw}")
            rows.append(row)
    if not rows:
        raise SystemExit(f"{path}: no lifecycle rows")

    seen: set[str] = set()
    resolved = 0
    for issue, state, first_seen, last_checked, resolved_in, evidence, summary in rows:
        if not ISSUE_ID.fullmatch(issue):
            raise SystemExit(f"{path}: malformed issue id {issue!r}")
        if issue in seen:
            raise SystemExit(f"{path}: duplicate issue id {issue}")
        seen.add(issue)
        if state not in STATES:
            raise SystemExit(f"{path}: {issue} has invalid state {state!r}")
        if not DATE.fullmatch(first_seen) or not DATE.fullmatch(last_checked):
            raise SystemExit(f"{path}: {issue} needs ISO first/last checked dates")
        if state == "RESOLVED":
            resolved += 1
            if resolved_in == "—" or evidence == "—":
                raise SystemExit(f"{path}: resolved {issue} requires resolution and verification evidence")
        elif resolved_in != "—":
            raise SystemExit(f"{path}: unresolved {issue} must use — in Resolved in")
        if not summary or summary == "—":
            raise SystemExit(f"{path}: {issue} requires a concise summary")

    print(f"paper-review-issue-ledger: valid rows={len(rows)} resolved={resolved} path={path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
