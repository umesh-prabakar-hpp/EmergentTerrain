#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
setup_script="$script_dir/setup-fedora-prereqs.sh"
gpu_repo="${GPU_REPO:-$repo_root/engines/gpu-engine}"

[[ -f "$setup_script" ]] || {
  echo "ERROR: setup script not found at $setup_script" >&2
  exit 1
}

[[ -d "$repo_root" ]] || {
  echo "ERROR: repo root not found at $repo_root" >&2
  exit 1
}

[[ -d "$gpu_repo" ]] || {
  echo "ERROR: GPU repo not found at $gpu_repo" >&2
  exit 1
}

help_output="$(bash "$setup_script" --help)"
[[ "$help_output" == *"Usage: setup-fedora-prereqs.sh [options]"* ]] || {
  echo "ERROR: help output did not contain usage text" >&2
  exit 1
}

# This is the supported smoke test path for the helper: it parses arguments,
# resolves both repos, and exits without mutating packages, databases, or npm state.
bash "$setup_script" \
  --gpu-repo "$gpu_repo" \
  --skip-packages \
  --skip-postgres \
  --skip-node-deps \
  --skip-playwright \
  --skip-verify \
  >/dev/null

echo "setup-fedora-prereqs smoke test passed"
