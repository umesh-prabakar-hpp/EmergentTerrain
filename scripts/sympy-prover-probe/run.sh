#!/usr/bin/env bash
# phase-25 §25.5 — one-shot wrapper for the SymPy coverage probe.
#
# Assumes:
#   - the julia-math-sidecar is up on http://localhost:8088 (or override
#     via SYMPY_PROBE_BASE_URL).
#   - PyCall.jl + SymPy 1.14+ are installed on the sidecar host (see
#     phase-25 §25.1 — `dnf install python3-sympy` is the standard
#     install).
#   - sidecar binary includes db2b649 (the commit that registers
#     /prove_identity). Earlier sidecar versions will surface every
#     fixture as transport: 404.
#
# Exit code:
#   0 — at least 8/10 fixtures proved cleanly.
#   1 — below the acceptance bar; don't flip sympy.enabled=true yet.
#   2 — invalid filter argument.
#   3 — sidecar unreachable / unhealthy.
#
# Override knobs:
#   SYMPY_PROBE_BASE_URL    override sidecar base URL
#   SYMPY_PROBE_TIMEOUT_MS  per-fixture sidecar wall-clock cap (ms)

set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

BASE_URL="${SYMPY_PROBE_BASE_URL:-http://localhost:8088}"
TIMEOUT_MS="${SYMPY_PROBE_TIMEOUT_MS:-30000}"

exec python3 "$here/probe.py" \
    --base-url   "$BASE_URL" \
    --timeout-ms "$TIMEOUT_MS" \
    "$@"
