#!/usr/bin/env bash
# phase-26 §26.6 — one-shot wrapper for the recognition coverage probe.
#
# Assumes:
#   - the julia-math-sidecar is up on http://localhost:8088 (or override
#     via RECOGNITION_PROBE_BASE_URL).
#   - sidecar binary includes both /recognise/constant and
#     /recognise/sequence routes (committed in 0bb4546 and d142dad
#     respectively).
#   - mpmath is reachable under PyCall on the sidecar host.
#
# Exit codes:
#   0 — both acceptance bars cleared (≥25/30 constants, ≥27/30 sequences).
#   1 — below at least one bar; don't flip recognise.enabled=true yet.
#   3 — sidecar unreachable / unhealthy.
#
# Override knobs:
#   RECOGNITION_PROBE_BASE_URL       sidecar base URL
#   RECOGNITION_PROBE_TIMEOUT_MS     per-fixture cap (ms)
#   RECOGNITION_PROBE_RATE_LIMIT_S   sleep between sequence calls (≥1s per OEIS TOS)

set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"

BASE_URL="${RECOGNITION_PROBE_BASE_URL:-http://localhost:8088}"
TIMEOUT_MS="${RECOGNITION_PROBE_TIMEOUT_MS:-15000}"
RATE_LIMIT="${RECOGNITION_PROBE_RATE_LIMIT_S:-1.0}"

exec python3 "$here/probe.py" \
    --base-url           "$BASE_URL" \
    --timeout-ms         "$TIMEOUT_MS" \
    --oeis-rate-limit-s  "$RATE_LIMIT" \
    "$@"
