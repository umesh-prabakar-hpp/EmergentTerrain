#!/usr/bin/env bash
#
# check-secondorder-gate.sh — acceptance check for the second-order engagement
# gate + in-flight corrective re-run (commits 9beea64 / e0a44ab / 5fc3c06).
#
# Replaces the manual log inspection after a validation run: reads a finished
# run's app log and ASSERTS the safety guarantees of the in-flight
# reclassification, then REPORTS the gate-effectiveness observations.
#
#   HARD checks (set exit code; these are the broad-blast-radius guarantees):
#     A. no in-flight paper lost — the run ended on the clean quiescent stop
#        (draftQueue=0), not a hard-cap kill with drafts still in flight
#     B. total cost stayed within the hard cap (max-cost-usd)
#     C. corrective re-runs that fired WHILE DRAINING stayed within the
#        per-run reserve cap (pivot.max-reruns-per-run)
#
#   OBSERVATIONS (reported, never fail the run — a run with no eligible paper
#   legitimately fires the gate zero times):
#     - second-order re-run fires + per-paper outcome (on_target vs
#       still_leadingorder), off-target re-run fires
#     - drain timing + reserve headroom actually consumed
#     - leading-order topics that appeared, write-back tight/revived bounds
#
# Thresholds are read from application.yml (single source of truth), not
# hardcoded, so the check tracks config changes automatically.
#
# Usage:   scripts/check-secondorder-gate.sh [APP_LOG] [APPLICATION_YML]
# Default: scripts/check-secondorder-gate.sh logs/et-app.log src/main/resources/application.yml
#
# Exit: 0 = all HARD checks passed   1 = a HARD check FAILED
#       2 = cannot assess (no finished run in the log)

set -uo pipefail

LOG="${1:-logs/et-app.log}"
YAML="${2:-src/main/resources/application.yml}"
ORIG_LOG="$LOG"

red()   { printf '\033[31m%s\033[0m' "$1"; }
grn()   { printf '\033[32m%s\033[0m' "$1"; }
ylw()   { printf '\033[33m%s\033[0m' "$1"; }
pass()  { echo "[$(grn PASS)] $1"; }
fail()  { echo "[$(red FAIL)] $1"; HARD_FAIL=1; }
warn()  { echo "[$(ylw WARN)] $1"; }
info()  { echo "[INFO] $1"; }

HARD_FAIL=0

[ -f "$LOG" ]  || { echo "log not found: $LOG"   >&2; exit 2; }
[ -f "$YAML" ] || { echo "yaml not found: $YAML" >&2; exit 2; }

# A finished run has a final budget rollup; bail cleanly if the log has none.
if ! grep -q 'run-budget TOTAL' "$LOG"; then
    echo "no 'run-budget TOTAL' lines in $LOG — not a completed run; cannot assess." >&2
    exit 2
fi

# Scope to the LATEST run only (a run = one JVM; logs may concatenate several).
# Slice from the last application-start banner so first-DRAINING / last-TOTAL
# can't be read from two different runs.
START=$(grep -nE 'Starting EmergentTerrainApplication' "$LOG" | tail -1 | cut -d: -f1)
if [ -n "$START" ]; then
    SLICE=$(mktemp)
    trap 'rm -f "$SLICE"' EXIT
    tail -n +"$START" "$LOG" > "$SLICE"
    LOG="$SLICE"
fi

# ── thresholds from yaml ──────────────────────────────────────────────────────
MAX_COST=$(grep -m1 'max-cost-usd:' "$YAML" | grep -oE '[0-9]+\.[0-9]+' | head -1)
MAX_RERUNS=$(grep -m1 'max-reruns-per-run:' "$YAML" | grep -oE '[0-9]+' | head -1)
COMPLETE_IN_FLIGHT=$(grep -m1 'complete-in-flight:' "$YAML" | grep -oE 'true|false' | head -1)
: "${MAX_COST:=0}" ; : "${MAX_RERUNS:=0}" ; : "${COMPLETE_IN_FLIGHT:=false}"

echo "=============================================================="
echo " second-order gate + in-flight re-run — acceptance check"
echo " log:   $ORIG_LOG"
echo " yaml:  $YAML  (max-cost-usd=\$$MAX_COST  max-reruns-per-run=$MAX_RERUNS"
echo "        complete-in-flight=$COMPLETE_IN_FLIGHT)"
echo "=============================================================="

# ── drain timing ──────────────────────────────────────────────────────────────
DRAIN_LINE=$(grep -m1 'Run budget DRAINING' "$LOG" || true)
DRAIN_TS=$(echo "$DRAIN_LINE" | awk '{print $1}')
DRAIN_COST=$(echo "$DRAIN_LINE" | grep -oE 'cost \$[0-9.]+' | grep -oE '[0-9.]+' | head -1)

# ── final cost ────────────────────────────────────────────────────────────────
TOTAL_LINE=$(grep 'run-budget TOTAL' "$LOG" | tail -1)
FINAL_COST=$(echo "$TOTAL_LINE" | grep -oE 'cost=\$[0-9.]+' | grep -oE '[0-9.]+' | head -1)
: "${FINAL_COST:=0}"

echo
echo "── HARD checks ───────────────────────────────────────────────"

# A. no in-flight paper lost — clean quiescent stop (draftQueue=0).
TERM_LINE=$(grep -E 'Terminating application|Run budget reached' "$LOG" | tail -1)
if [ -z "$TERM_LINE" ]; then
    warn "A. no termination line yet — run not finished; completion not assessable"
elif echo "$TERM_LINE" | grep -q 'draftQueue=0'; then
    pass "A. clean quiescent stop (draftQueue=0) — no in-flight paper lost"
elif echo "$TERM_LINE" | grep -qE 'draftQueue=[1-9]'; then
    LOSTQ=$(echo "$TERM_LINE" | grep -oE 'draftQueue=[0-9]+')
    fail "A. terminated with $LOSTQ — in-flight paper(s) cut before completing"
else
    warn "A. termination line present but draftQueue not stated: $TERM_LINE"
fi

# B. total cost within the hard cap.
if awk -v c="$FINAL_COST" -v m="$MAX_COST" 'BEGIN{exit !(m>0 && c<=m+1e-9)}'; then
    pass "B. final cost \$$FINAL_COST within hard cap \$$MAX_COST"
else
    fail "B. final cost \$$FINAL_COST EXCEEDS hard cap \$$MAX_COST"
fi

# C. re-runs that fired while draining stayed within the per-run cap.
#    Counts both arms (off-target + second-order); a re-run is "while draining"
#    if its log timestamp is at/after the first DRAINING line (ISO ts sort
#    lexicographically within one run).
if [ -n "$DRAIN_TS" ]; then
    DRAIN_RERUNS=$(awk -v d="$DRAIN_TS" '
        /re-run [0-9]+\/[0-9]+ paperId=/ { if ($1 >= d) c++ }
        END { print c+0 }' "$LOG")
else
    DRAIN_RERUNS=0
fi
if [ "$MAX_RERUNS" -le 0 ]; then
    info "C. drain re-run cap disabled (max-reruns-per-run=$MAX_RERUNS); $DRAIN_RERUNS fired while draining"
elif [ "$DRAIN_RERUNS" -le "$MAX_RERUNS" ]; then
    pass "C. $DRAIN_RERUNS re-run(s) while draining ≤ cap $MAX_RERUNS"
else
    fail "C. $DRAIN_RERUNS re-run(s) while draining EXCEEDS cap $MAX_RERUNS"
fi

echo
echo "── observations (do not affect exit code) ────────────────────"

# Gate fires + outcome, read from the per-paper call-path lines.
SO_FIRES=$(grep -c 'secondorder_rerun' "$LOG" 2>/dev/null); SO_FIRES=${SO_FIRES:-0}
OT_FIRES=$(grep -c 'offtarget_rerun' "$LOG" 2>/dev/null); OT_FIRES=${OT_FIRES:-0}
TI_FIRES=$(grep -c 'tighten_rerun' "$LOG" 2>/dev/null); TI_FIRES=${TI_FIRES:-0}
TI_STILL=$(grep -c 'pivot:still_loose' "$LOG" 2>/dev/null); TI_STILL=${TI_STILL:-0}
info "corrective re-run fires — second-order: $SO_FIRES  off-target: $OT_FIRES  tighten: $TI_FIRES (still_loose: $TI_STILL)"
[ "$TI_FIRES" -gt 0 ] && info "  tight-k captures (tighten fired → not still_loose): $((TI_FIRES - TI_STILL)) — a captured tight growth-anchor bound is a Q4 novelty candidate"

# ── second-order COVERAGE (authoritative; from the per-paper engaged/uncovered
#    stamps — distinguishes self-engaged / gate-forced / the silent miss, which
#    fire-count alone cannot. Pre-stamp build logs show "no stamps").
ENG_IDS=$(grep -E 'second-order: paperId=.*engaged second-order' "$LOG" | grep -oE 'P-[A-Za-z0-9-]+' | sort -u)
# best-of-N emits one second-order line per CANDIDATE, so a paper can have both an engaged (winning
# candidate) and a leading-order-only (losing candidate) line. The published outcome is engaged, so a
# paper in ENG_IDS is NOT uncovered — subtract it (prevents the P-570-style false "uncovered" flag).
UNC_IDS=$(comm -23 \
    <(grep -E 'second-order: paperId=.*leading-order-only' "$LOG" | grep -oE 'P-[A-Za-z0-9-]+' | sort -u) \
    <(echo "$ENG_IDS"))
RERUN_IDS=$(grep -E 'secondorder_rerun|second-order re-run' "$LOG" | grep -oE 'P-[A-Za-z0-9-]+' | sort -u)
SO_ENGAGED=$(echo -n "$ENG_IDS" | grep -c 'P-' || true)
SO_UNCOV=$(echo -n "$UNC_IDS" | grep -c 'P-' || true)
LO_TOTAL=$((SO_ENGAGED + SO_UNCOV))
if [ "$LO_TOTAL" -gt 0 ]; then
    GF=$(comm -12 <(echo "$ENG_IDS") <(echo "$RERUN_IDS") | grep -c 'P-' || true)
    SELF=$((SO_ENGAGED - GF))
    info "second-order coverage: $SO_ENGAGED/$LO_TOTAL leading-order papers reached a target ($SELF self-engaged, $GF gate-forced)"
    SCALARS=$(grep -oE 'engaged second-order frame on \[[^]]+\]' "$LOG" \
        | sed -E 's/.*\[([^]]+)\]/\1/' | tr '+' '\n' | grep -v '^$' | sort | uniq -c \
        | awk '{printf "%s×%s  ", $1, $2}')
    [ -n "$SCALARS" ] && info "  scalars reached: $SCALARS"
    if [ "$SO_UNCOV" -gt 0 ]; then
        STILL=$(comm -12 <(echo "$UNC_IDS") <(echo "$RERUN_IDS") | grep -c 'P-' || true)
        warn "  $SO_UNCOV uncovered — the miss the gate exists to prevent ($STILL fired-but-still-leading-order, $((SO_UNCOV - STILL)) never-fired/blocked)"
        echo "$UNC_IDS" | sed 's/^/      uncovered: /'
    fi
else
    info "second-order coverage: no engaged/uncovered stamps (pre-stamp build, or no leading-order topic this run)"
fi

# Drain / reserve headroom actually consumed.
if [ -n "$DRAIN_TS" ] && [ -n "$DRAIN_COST" ]; then
    USED=$(awk -v f="$FINAL_COST" -v d="$DRAIN_COST" 'BEGIN{printf "%.2f", f-d}')
    RESERVE=$(awk -v m="$MAX_COST" -v d="$DRAIN_COST" 'BEGIN{printf "%.2f", m-d}')
    info "drain began at \$$DRAIN_COST ($DRAIN_TS); reserve used \$$USED of \$$RESERVE (cap \$$MAX_COST)"
else
    info "run did not reach drain-start this session (no DRAINING line)"
fi

# Write-back tight / revived bounds (the novelty payoff a re-run can unlock).
WB_WRITABLE=$(grep -c 'investigation-write-back:.*WRITABLE' "$LOG" 2>/dev/null); WB_WRITABLE=${WB_WRITABLE:-0}
WB_REVIVED=$(grep -c 'investigation-write-back:.*revived from retired' "$LOG" 2>/dev/null); WB_REVIVED=${WB_REVIVED:-0}
info "write-back: $WB_WRITABLE WRITABLE bound(s), $WB_REVIVED revived-from-retired"

echo
echo "=============================================================="
if [ "$HARD_FAIL" -eq 0 ]; then
    echo " RESULT: $(grn PASS) — all hard checks passed"
    echo "=============================================================="
    exit 0
else
    echo " RESULT: $(red FAIL) — a hard check failed (see [FAIL] above)"
    echo "=============================================================="
    exit 1
fi
