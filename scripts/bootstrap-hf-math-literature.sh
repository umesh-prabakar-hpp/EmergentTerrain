#!/usr/bin/env bash
# =====================================================================
# bootstrap-hf-math-literature.sh — Phase 34 public math-literature seed
# =====================================================================
# Downloads public Hugging Face mathematics corpora without an API key,
# approval workflow, or OpenAlex credit budget. The default is the UW Math AI
# theorem-search corpus (theorem-level records from arXiv papers). The Marin
# no-problem ar5iv Markdown corpus is the complementary full-text option.
#
# Usage:
#   ./scripts/bootstrap-hf-math-literature.sh
#   HF_DATASET=marin-community/ar5iv-no-problem-markdown \
#     ./scripts/bootstrap-hf-math-literature.sh
#   HF_REVISION=<commit> HF_DATASET=uw-math-ai/theorem-search-dataset \
#     ./scripts/bootstrap-hf-math-literature.sh
#
# Output defaults to a dataset-specific subdirectory of
# ~/.emergentterrain/corpus-hf-math. The download is public and tokenless; pin
# HF_REVISION for a reproducible corpus snapshot. This script deliberately only
# downloads data. Import/index it with the sidecar's corpus tooling before
# enabling it as a search source.
# =====================================================================
set -euo pipefail

DATASET="${HF_DATASET:-uw-math-ai/theorem-search-dataset}"
DATASET_SLUG="${DATASET//\//--}"
DESTINATION="${HF_MATH_CORPUS_DIR:-$HOME/.emergentterrain/corpus-hf-math/$DATASET_SLUG}"
REVISION="${HF_REVISION:-main}"

if ! command -v hf >/dev/null 2>&1; then
  echo "The Hugging Face CLI ('hf') is required. Install huggingface_hub, then rerun." >&2
  echo "Example: python3 -m pip install --user -U huggingface_hub" >&2
  exit 127
fi

mkdir -p "$DESTINATION"
export HF_XET_HIGH_PERFORMANCE="${HF_XET_HIGH_PERFORMANCE:-1}"

echo "Downloading public dataset $DATASET at revision $REVISION"
echo "Destination: $DESTINATION"
hf download "$DATASET" \
  --repo-type dataset \
  --revision "$REVISION" \
  --include '*.parquet' \
  --include '*.jsonl' \
  --include '*.jsonl.gz' \
  --include 'README.md' \
  --local-dir "$DESTINATION"

RESOLVED_REVISION=""
if [ -d "$DESTINATION/.cache/huggingface/download" ]; then
  RESOLVED_REVISION="$(find "$DESTINATION/.cache/huggingface/download" -name '*.metadata' -type f \
    -exec sed -n '1p' {} \; 2>/dev/null | sed -n '1p')"
fi
printf 'dataset=%s\nrequested_revision=%s\nresolved_revision=%s\ndownloaded_at=%s\n' \
  "$DATASET" "$REVISION" "${RESOLVED_REVISION:-unrecorded}" "$(date --iso-8601=seconds)" > "$DESTINATION/SOURCE.txt"
echo "Done. SOURCE.txt records both the requested revision and the resolved dataset commit. Pin a commit hash for a paper artifact."
