#!/usr/bin/env bash
# phase-24 §24.5 — one-shot wrapper for the PySR recovery probe.
#
# Assumes:
#   - the julia-math-sidecar is up on http://localhost:8088 (or override
#     via PYSR_PROBE_BASE_URL).
#   - SymbolicRegression.jl has been instantiated on the sidecar — first
#     run includes ~5-10 min of precompilation; subsequent calls are
#     sub-second-to-warm.
#
# Exit code:
#   0 — at least 4/5 fixtures recovered their law within the per-fixture
#       mse threshold.
#   1 — below the acceptance bar; don't flip pysr.enabled=true yet.
#   2 — invalid filter argument.
#   3 — sidecar unreachable / unhealthy.
#
# Override knobs via env vars before invocation:
#   PYSR_PROBE_BASE_URL    override sidecar base URL
#   PYSR_PROBE_TIMEOUT_MS  per-fixture sidecar wall-clock cap (ms)
#   PYSR_PROBE_POPULATIONS GP island count
#   PYSR_PROBE_ITERATIONS  iterations per population

set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

BASE_URL="${PYSR_PROBE_BASE_URL:-http://localhost:8088}"
TIMEOUT_MS="${PYSR_PROBE_TIMEOUT_MS:-30000}"
POPULATIONS="${PYSR_PROBE_POPULATIONS:-16}"
ITERATIONS="${PYSR_PROBE_ITERATIONS:-80}"

exec python3 "$here/probe.py" \
    --base-url     "$BASE_URL" \
    --timeout-ms   "$TIMEOUT_MS" \
    --populations  "$POPULATIONS" \
    --iterations   "$ITERATIONS" \
    "$@"
