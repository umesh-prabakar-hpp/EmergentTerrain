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
    # 2026-06-06 commit-review fix — HEADER-DRIVEN column mapping. Report eras
    # vary their column order (some put Status before Peer; one era added a
    # Label column); the old hardcoded f[2..7] transposed peer/status across
    # the whole harvest. Now the table's own header row maps name -> index.
    # All free-text fields are uniformly quoted with "" escaping.
    awk -v rev="$reviewed" -v src="$source" '
        function clean(s) { gsub(/^ +| +$/, "", s); gsub(/\*/, "", s); return s }
        function csv(s)   { gsub(/"/, "\"\"", s); return "\"" s "\"" }
        /^## Per-paper ratings/ { inSection=1; next }
        /^## / { inSection=0 }
        inSection && /^\|/ && !haveHeader {
            n = split($0, h, /\|/)
            for (i = 2; i <= n; i++) {
                col = tolower(clean(h[i]))
                if (col ~ /^paper/)        idP = i
                else if (col ~ /^topic/)   idT = i
                else if (col ~ /^peer/)    idPe = i
                else if (col ~ /^status/)  idS = i
                else if (col ~ /^verdict/) idV = i
                else if (col ~ /^key/)     idK = i
            }
            if (idP && idV) haveHeader = 1
            next
        }
        inSection && haveHeader && /^\| *P-[0-9a-f]+-[0-9]+ *\|/ {
            n = split($0, f, /\|/)
            paperId = clean(f[idP])
            topic   = idT  ? clean(f[idT])  : ""
            peer    = idPe ? clean(f[idPe]) : ""
            status  = idS  ? clean(f[idS])  : ""
            verdict = idV  ? clean(f[idV])  : ""
            key     = idK  ? clean(f[idK])  : ""
            printf "%s,%s,%s,%s,%s,%s,%s,%s\n",
                   paperId, csv(topic), csv(peer), csv(status),
                   csv(verdict), csv(key), rev, src
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
