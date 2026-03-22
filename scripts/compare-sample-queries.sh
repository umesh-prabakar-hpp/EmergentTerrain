#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: compare-sample-queries.sh --run-a PATH --run-b PATH

Options:
  --run-a PATH           Path to summary.csv for run A
  --run-b PATH           Path to summary.csv for run B
  -h, --help             Show this help
EOF
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

run_a=""
run_b=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-a)
      [[ $# -ge 2 ]] || fail "--run-a requires a value"
      run_a="$2"
      shift 2
      ;;
    --run-b)
      [[ $# -ge 2 ]] || fail "--run-b requires a value"
      run_b="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done

[[ -n "$run_a" ]] || fail "--run-a is required"
[[ -n "$run_b" ]] || fail "--run-b is required"
[[ -f "$run_a" ]] || fail "summary.csv not found: $run_a"
[[ -f "$run_b" ]] || fail "summary.csv not found: $run_b"

python3 - "$run_a" "$run_b" <<'PY'
import csv
import sys
from collections import defaultdict

run_a, run_b = sys.argv[1], sys.argv[2]

def average(values):
    return round(sum(values) / len(values), 2) if values else 0.0

def maximum(values):
    return round(max(values), 2) if values else 0.0

def total(values):
    return sum(values) if values else 0

def read_summary(path):
    grouped = defaultdict(list)
    with open(path, newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
          grouped[row["label"]].append(row)

    summary = {}
    for label, rows in grouped.items():
        def numeric(field):
            vals = []
            for row in rows:
                value = row.get(field, "")
                if value != "":
                    vals.append(float(value))
            return vals

        summary[label] = {
            "Label": label,
            "AvgGapSeconds": average(numeric("avg_gap_seconds")),
            "MaxGapSeconds": maximum(numeric("max_gap_seconds")),
            "TotalChanges": total(numeric("total_changes")),
            "AvgChangesPerFlush": average(numeric("avg_changes_per_flush")),
            "AvgChangesPerMinute": average(numeric("avg_changes_per_minute")),
        }
    return summary

summary_a = read_summary(run_a)
summary_b = read_summary(run_b)

print("Comparison (A vs B)")
print()
for label in ("gapStats", "avgChangesPerMinute"):
    if label in summary_a and label in summary_b:
        a = summary_a[label]
        b = summary_b[label]
        print(f"Label: {label}")
        print(
            "A: "
            f"AvgGap={a['AvgGapSeconds']} "
            f"MaxGap={a['MaxGapSeconds']} "
            f"TotalChanges={a['TotalChanges']} "
            f"AvgChangesPerFlush={a['AvgChangesPerFlush']} "
            f"AvgChangesPerMinute={a['AvgChangesPerMinute']}"
        )
        print(
            "B: "
            f"AvgGap={b['AvgGapSeconds']} "
            f"MaxGap={b['MaxGapSeconds']} "
            f"TotalChanges={b['TotalChanges']} "
            f"AvgChangesPerFlush={b['AvgChangesPerFlush']} "
            f"AvgChangesPerMinute={b['AvgChangesPerMinute']}"
        )
        print()
PY
