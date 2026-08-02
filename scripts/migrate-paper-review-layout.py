#!/usr/bin/env python3
"""Create the compact paper-review index used by the split review layout.

The old REVIEW_REPORT.md is deliberately left untouched as a historical snapshot.
Future paper-review passes write their current summary, immutable per-run record,
and issue lifecycle under <research>/reviews/ instead of appending to it.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path


TABLE_ROW = re.compile(r"^\|.*\|\s*$")
PAPER_ID = re.compile(r"^P-[A-Za-z0-9-]+$")


def cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def index_of(header: list[str], name: str) -> int:
    lowered = [value.lower() for value in header]
    try:
        return lowered.index(name.lower())
    except ValueError as exc:
        raise ValueError(f"legacy report has no {name!r} column") from exc


def legacy_rows(report: Path) -> tuple[str, list[tuple[str, str, str, str]]]:
    lines = report.read_text(encoding="utf-8").splitlines()
    reviewed_at = "unrecorded"
    for line in lines:
        match = re.search(r"Last updated:\s*([^\n]+)", line)
        if match:
            reviewed_at = match.group(1).strip().strip("`")
            break

    try:
        start = next(index for index, line in enumerate(lines) if line.strip() == "## Per-paper ratings")
    except StopIteration as exc:
        raise ValueError("legacy report has no '## Per-paper ratings' section") from exc

    header_index = next(index for index in range(start + 1, len(lines)) if TABLE_ROW.match(lines[index]))
    header = cells(lines[header_index])
    paper_column = index_of(header, "Paper")
    status_column = index_of(header, "Status")
    method_column = index_of(header, "Method")
    verdict_column = index_of(header, "Overall")
    rows: list[tuple[str, str, str, str]] = []
    for line in lines[header_index + 2:]:
        if line.startswith("## "):
            break
        if not TABLE_ROW.match(line):
            continue
        row = cells(line)
        if len(row) != len(header) or not PAPER_ID.fullmatch(row[paper_column]):
            continue
        rows.append((row[paper_column], row[status_column], row[method_column], row[verdict_column]))
    if not rows:
        raise ValueError("legacy report has no paper rows")
    return reviewed_at, rows


def write_if_missing(path: Path, contents: str, overwrite: bool) -> None:
    if path.exists() and not overwrite:
        raise FileExistsError(f"refusing to overwrite {path}; pass --overwrite")
    path.write_text(contents, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("research_dir", type=Path)
    parser.add_argument("--legacy-report", type=Path)
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()

    research_dir = args.research_dir.expanduser().resolve()
    legacy_report = (args.legacy_report or research_dir / "REVIEW_REPORT.md").expanduser().resolve()
    reviewed_at, rows = legacy_rows(legacy_report)
    reviews = research_dir / "reviews"
    runs = reviews / "runs"
    runs.mkdir(parents=True, exist_ok=True)

    index_rows = "\n".join(
        f"| {paper} | {status} | {method} | {verdict} | {reviewed_at} | legacy consolidated report |"
        for paper, status, method, verdict in rows
    )
    write_if_missing(
        reviews / "paper-index.md",
        "# Paper Review Index\n\n"
        "Stable reviewed-paper inventory for incremental review. This is the authority for deciding\n"
        "whether a paper ID is new; it is intentionally compact and is not an evidence source.\n\n"
        "| Paper | Workflow status | Method | Verdict | Last reviewed | Source |\n"
        "| :---- | :-------------- | :----- | :------ | :------------ | :----- |\n"
        f"{index_rows}\n",
        args.overwrite,
    )
    write_if_missing(
        reviews / "CURRENT.md",
        "# Current Paper-Review Summary\n\n"
        f"- Initialized from the legacy consolidated report at `{reviewed_at}`.\n"
        f"- Reviewed-paper inventory: [paper-index.md](paper-index.md) ({len(rows)} rows).\n"
        "- Issue states and resolution evidence: [issue-ledger.md](issue-ledger.md).\n"
        "- Immutable review records: [runs/](runs/).\n\n"
        "The next paper-review pass replaces this file with the latest run's concise summary and\n"
        "adds a new immutable run record. The root `REVIEW_REPORT.md` is a frozen legacy snapshot.\n",
        args.overwrite,
    )
    print(f"paper-review-layout: indexed={len(rows)} reviews={reviews}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
