#!/usr/bin/env bash
set -euo pipefail

CHROME_BIN="${CHROME_BIN:-google-chrome-stable}"
PROFILE_DIR="${PROFILE_DIR:-/tmp/chrome-amd-x11-webgpu2}"
RENDER_NODE="${RENDER_NODE:-/dev/dri/renderD129}"
OZONE_PLATFORM="${OZONE_PLATFORM:-x11}"

if ! command -v "$CHROME_BIN" >/dev/null 2>&1; then
  echo "Chrome executable not found: $CHROME_BIN" >&2
  exit 1
fi

if [[ ! -e "$RENDER_NODE" ]]; then
  echo "WebGPU render node not found: $RENDER_NODE" >&2
  echo "Set RENDER_NODE to a usable /dev/dri/renderD* device before launching Chrome." >&2
  exit 1
fi

if [[ ! -r "$RENDER_NODE" || ! -w "$RENDER_NODE" ]]; then
  echo "WebGPU render node is not readable and writable: $RENDER_NODE" >&2
  echo "Grant the current user render-node access before launching Chrome." >&2
  exit 1
fi

exec env DRI_PRIME=1 "$CHROME_BIN" \
  --user-data-dir="$PROFILE_DIR" \
  --ozone-platform="$OZONE_PLATFORM" \
  --enable-features=Vulkan \
  --enable-unsafe-webgpu \
  --render-node-override="$RENDER_NODE" \
  "$@"
