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
#   JULIA_NUM_THREADS=4 ./scripts/start.sh   # parallel request handling
#
# Environment knobs:
#   HOST                — interface to bind (default 127.0.0.1)
#   PORT                — TCP port (default 8088)
#   JULIA_NUM_THREADS   — Julia worker threads (default 1; recommend 4)
#   JULIA_DEPOT_PATH    — where Julia installs deps (default ~/.julia)
# =====================================================================
set -euo pipefail

# Resolve repo root regardless of how this script is invoked.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8088}"
JULIA_NUM_THREADS="${JULIA_NUM_THREADS:-1}"
export JULIA_NUM_THREADS

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
exec julia --project=. -e "
    using JuliaMathSidecar
    start_server!(host=\"$HOST\", port=$PORT)
"
