#!/usr/bin/env bash
set -euo pipefail

CHROME_BIN="${CHROME_BIN:-google-chrome-stable}"
PROFILE_DIR="${PROFILE_DIR:-/tmp/chrome-amd-x11-webgpu2}"
RENDER_NODE="${RENDER_NODE:-/dev/dri/renderD129}"
OZONE_PLATFORM="${OZONE_PLATFORM:-x11}"

exec env DRI_PRIME=1 "$CHROME_BIN" \
  --user-data-dir="$PROFILE_DIR" \
  --ozone-platform="$OZONE_PLATFORM" \
  --enable-features=Vulkan \
  --enable-unsafe-webgpu \
  --render-node-override="$RENDER_NODE" \
  "$@"
