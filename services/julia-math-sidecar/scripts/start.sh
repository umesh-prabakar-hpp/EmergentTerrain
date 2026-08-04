#!/usr/bin/env bash
# =====================================================================
# scripts/start.sh — convenience launcher for development
# =====================================================================
#
# Boots the sidecar on the configured host:port. Production deployments
# should use the systemd unit at systemd/julia-math-sidecar.service
# instead — this script is intentionally minimal (no auto-restart, no
# logging redirection, no resource limits).
#
# Usage:
#   ./scripts/start.sh                       # 127.0.0.1:8088, default threads
#   PORT=9000 ./scripts/start.sh             # override port
#   HOST=0.0.0.0 ./scripts/start.sh          # bind all interfaces (NOT for prod)
#   JULIA_NUM_THREADS=1 ./scripts/start.sh   # force single-thread (default is 4)
#
# Environment knobs:
#   HOST                — interface to bind (default 127.0.0.1)
#   PORT                — TCP port (default 8088)
#   JULIA_NUM_THREADS   — Julia worker threads (default 4; set 1 for single-thread dev)
#   JULIA_DEPOT_PATH    — where Julia installs deps (default ~/.julia)
# =====================================================================
set -euo pipefail

# Resolve repo root regardless of how this script is invoked.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8088}"
# Default 4, not 1: with a single thread a heavy compute or cold-JIT
# request monopolises the thread and starves /healthz, so the JVM's probe
# times out and marks the sidecar unreachable. 4 keeps liveness responsive
# while work runs (cpu_threads is 4 on this box). Override to 1 for fast
# single-thread dev.
JULIA_NUM_THREADS="${JULIA_NUM_THREADS:-4}"
export JULIA_NUM_THREADS

# Logging — by default tee stdout+stderr to logs/sidecar.log under the
# repo root. EmergentTerrain mirrors this pattern with logs/et-app.log,
# so an operator can `tail -f` both in adjacent terminals without
# hunting for ephemeral task-output files.
#
# Override LOG_FILE=- to disable file logging entirely (e.g. when
# running under systemd, which captures stdout into the journal).
LOG_FILE="${LOG_FILE:-$REPO_ROOT/logs/sidecar.log}"
if [ "$LOG_FILE" != "-" ]; then
    mkdir -p "$(dirname "$LOG_FILE")"
    echo "julia-math-sidecar: tee'ing log to $LOG_FILE"
fi

# Confirm `julia` is on PATH before doing anything else — gives a
# clearer error than a confused "command not found" deep in the
# `using Pkg` call below.
if ! command -v julia >/dev/null 2>&1; then
    cat >&2 <<'ERR'
julia-math-sidecar: `julia` not found on PATH.

Install Julia 1.10+ first:
  curl -fsSL https://install.julialang.org | sh
or via your package manager (Fedora: `dnf install julia`,
macOS Homebrew: `brew install julia`).

Then re-run this script.
ERR
    exit 1
fi

# Boot. `--project=.` makes Julia activate the Project.toml in this
# directory so package versions are pinned locally instead of
# polluting the user's default depot.
#
# `-e` runs the inline script: load the package, then call
# start_server!(host, port). The package directory must be on
# LOAD_PATH; Julia auto-finds it via `--project=.`.
# If a log file is configured, tee stdout+stderr so we get BOTH
# real-time console output AND a persistent file. `exec ... 2>&1 | tee -a`
# composes: redirect stderr to stdout, pipe to tee which appends to
# the file. `-a` so multiple restarts share one log; rotate externally
# (logrotate / systemd) when it gets too big.
if [ "$LOG_FILE" = "-" ]; then
    exec julia --project=. -e "
        using JuliaMathSidecar
        start_server!(host=\"$HOST\", port=$PORT)
    "
else
    exec julia --project=. -e "
        using JuliaMathSidecar
        start_server!(host=\"$HOST\", port=$PORT)
    " 2>&1 | tee -a "$LOG_FILE"
fi
