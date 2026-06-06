#!/usr/bin/env bash
# Phase 32 Wave 3 substrate (32.4, ARC §B7 slot) — durable operator-verdict store.
#
# Every /paper-review session assigns each paper a verdict (Acceptable / Minor
# revision / Major revision / Reject) in REVIEW_REPORT.md's "Per-paper ratings"
# table — and the table dies with the room reset. This script harvests every
# report (archived room snapshots + the live room) into one append-safe CSV:
# the labeled calibration set for the 32.4 review head (target >= 100 rows).
#
# Idempotent: rows are keyed (paperId,source); re-runs replace the file from
# scratch (sources are immutable snapshots, so a full rebuild IS the safe
# append). Read-only over research/; writes only the CSV.
#
# Usage: scripts/backfill-review-verdicts.sh [research_root] [out_csv]
set -euo pipefail

ROOT="${1:-research}"
OUT="${2:-$ROOT/review-verdicts.csv}"

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

echo 'paperId,topic,peer,status,verdict,keyIssue,reviewedAt,source' > "$tmp"

harvest() {
    local report="$1" source="$2"
    local reviewed
    reviewed="$(grep -m1 -oE '\*\*Last updated:\*\* [0-9-]+' "$report" | grep -oE '[0-9-]+$' || true)"
    [ -n "$reviewed" ] || reviewed="$(date -r "$report" +%F)"
    # Per-paper ratings rows: | P-xxxx-N | topic | peer | status | verdict | key issue |
    awk -v rev="$reviewed" -v src="$source" '
        /^## Per-paper ratings/ { inTable=1; next }
        /^## / { inTable=0 }
        inTable && /^\| *P-[0-9a-f]+-[0-9]+ *\|/ {
            n = split($0, f, /\|/)
            if (n < 7) next
            for (i = 2; i <= 7; i++) { gsub(/^ +| +$/, "", f[i]) }
            # CSV-escape commas/quotes in free-text fields.
            for (i in f) { gsub(/"/, "\"\"", f[i]) }
            printf "%s,\"%s\",%s,%s,\"%s\",\"%s\",%s,%s\n",
                   f[2], f[3], f[4], f[5], f[6], f[7], rev, src
        }' "$report" >> "$tmp"
}

count=0
# Archived room snapshots (immutable), oldest first for stable ordering.
while IFS= read -r report; do
    src="$(basename "$(dirname "$report")")"
    harvest "$report" "$src"
    count=$((count+1))
done < <(find "$ROOT/archive" -maxdepth 2 -name REVIEW_REPORT.md 2>/dev/null | sort)

# Live room reports (will be re-harvested from archive after the next reset).
while IFS= read -r report; do
    src="live-$(basename "$(dirname "$report")")"
    harvest "$report" "$src"
    count=$((count+1))
done < <(find "$ROOT" -maxdepth 2 -not -path "$ROOT/archive/*" -name REVIEW_REPORT.md 2>/dev/null | sort)

mv "$tmp" "$OUT"
rows=$(( $(wc -l < "$OUT") - 1 ))
echo "review-verdicts: harvested $rows rows from $count report(s) -> $OUT"
