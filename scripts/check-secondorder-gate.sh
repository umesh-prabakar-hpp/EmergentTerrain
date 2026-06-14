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
info "second-order re-run fires: $SO_FIRES   off-target re-run fires: $OT_FIRES"

if [ "$SO_FIRES" -gt 0 ]; then
    # Per paper: did the re-run reach on_target, or stay still_leadingorder?
    grep -E 'LLM call path paperId=.*secondorder_rerun' "$LOG" | while read -r line; do
        pid=$(echo "$line" | grep -oE 'paperId=P-[a-z0-9-]+' | head -1)
        if echo "$line" | grep -q 'still_leadingorder'; then
            echo "      $pid — fired, but re-run STILL leading-order (note may be weak)"
        elif echo "$line" | grep -q 'pivot:on_target'; then
            echo "      $pid — fired → on_target (engaged a second-order frame ✓)"
        else
            echo "      $pid — fired (outcome stamp not on this line)"
        fi
    done
else
    # Zero fires is only notable if leading-order topics actually appeared.
    LO_TOPICS=$(grep -oE 'conjecture/(mean_omega|mean_Omega|mean_divisor_count|max_divisor_count|abundant_density|deficient_density|happy_density|prime_density)' "$LOG" | sort -u | paste -sd, -)
    if [ -n "$LO_TOPICS" ]; then
        info "0 second-order fires, but leading-order topics appeared: $LO_TOPICS"
        info "  → either they self-engaged a frame (good), or every eligible paper"
        info "    landed after the cap/hard-stop. Inspect those papers' Methods."
    else
        info "0 second-order fires — no leading-order topic surfaced this run (gate N/A)"
    fi
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
