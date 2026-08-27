#!/usr/bin/env bash
# Regenerate the durable paper-to-literature-key map from final artifact evidence.
# A key is retained only if it was issued by search_literature, successfully returned by
# read_reference, and cited in the paper's Related Work section. The raw artifact trace and
# markdown remain authoritative; this document is a reviewer index.
set -euo pipefail

repo_root=$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel)
output_path=${1:-"$repo_root/docs/review/paper-literature-key-map.md"}
output_dir=$(dirname "$output_path")
research_roots=(
  "$repo_root/apps/research-service/research"
)

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to build the paper literature-key map." >&2
  exit 2
fi

mkdir -p "$(dirname "$output_path")"
temporary_path=$(mktemp "${TMPDIR:-/tmp}/paper-literature-key-map.XXXXXX")
trap 'rm -f "$temporary_path"' EXIT

{
  cat <<'EOF'
# Paper-to-Literature Key Map

This generated reviewer index retains only literature keys that completed the full evidence
chain for a paper: issued by `search_literature`, successfully returned by `read_reference`, and
cited in that paper's `## Related Work` section. It is intentionally stricter than raw search
output: a retrieval hit, unread key, failed read, or uncited source is not retained here.

The paper markdown and `evidence/tool-trace.json` in the linked artifact are the source of truth.
The generator scans artifacts under `apps/research-service/research/`, including
the migrated legacy-root and archive trees.
Regenerate after each paper-review pass with:

```bash
./scripts/update-paper-literature-key-map.sh
```

| Paper | Literature key | Relevance basis | Artifact |
|---|---|---|---|
EOF

  found=0
  while IFS= read -r trace_path; do
    artifact_dir=$(dirname "$(dirname "$trace_path")")
    paper_path="$artifact_dir/paper.md"
    [[ -f "$paper_path" ]] || continue
    paper_id=$(basename "$artifact_dir")

    search_keys=$(jq -r '.[] | select(.functionName == "search_literature" and (.resultJson | contains("EVIDENCE"))) | .resultJson' "$trace_path" \
      | grep -oE '\[[A-Za-z][A-Za-z0-9]*\]' | tr -d '[]' | sort -u || true)
    read_keys=$(jq -r '.[] | select(.functionName == "read_reference") | .resultJson' "$trace_path" \
      | sed -nE 's/^read_reference[[:space:]]+([A-Za-z][A-Za-z0-9]*).*/\1/p' | sort -u || true)
    cited_keys=$(awk '
      /^## Related Work[[:space:]]*$/ { active=1; next }
      active && /^## / { exit }
      active { print }
    ' "$paper_path" | grep -oE '\[[A-Za-z][A-Za-z0-9]*\]' | tr -d '[]' | sort -u || true)
    assessed_keys=$(jq -r '.[] | select(.functionName == "assess_reference_relevance" and (.resultJson | test("^reference_relevance_assessment[[:space:]]+[A-Za-z0-9]+[[:space:]]+— ACCEPTED RELEVANT:"))) | .argsJson' "$trace_path" \
      | jq -r '.key // empty' | sort -u || true)

    while IFS= read -r key; do
      [[ -n "$key" ]] || continue
      grep -Fxq "$key" <<<"$read_keys" || continue
      grep -Fxq "$key" <<<"$cited_keys" || continue
      basis="issued + read + cited"
      if grep -Fxq "$key" <<<"$assessed_keys"; then
        basis="issued + read + cited + assessed relevant"
      fi
      artifact_rel=${artifact_dir#"$repo_root"/}
      artifact_link=$(realpath --relative-to="$output_dir" "$artifact_dir")
      printf '| `%s` | `%s` | %s | [%s](%s) |\n' \
        "$paper_id" "$key" "$basis" "$artifact_rel" "$artifact_link"
      found=1
    done <<<"$search_keys"
  done < <(
    for research_root in "${research_roots[@]}"; do
      [[ -d "$research_root" ]] || continue
      find "$research_root" -type f -path '*/artifacts/*/evidence/tool-trace.json' -print
    done | sort
  )

  if [[ "$found" -eq 0 ]]; then
    echo '| _None yet_ | — | No reviewed artifact currently has an issued, read, and cited key. | — |'
  fi

  cat <<'EOF'

## Review protocol

The paper-review skill regenerates this map after reviewing new papers. It does not infer source
relevance from titles, snippets, model prose, or an `EVIDENCE` token alone. If an artifact has a
`literature-audit.json`, reviewers should also inspect its `citedReadKeys` and, when available,
`citedReadReviewedKeys`; a disagreement is a review finding, not something this index may repair.
EOF
} > "$temporary_path"

mv "$temporary_path" "$output_path"
trap - EXIT
echo "Updated ${output_path#$repo_root/}"
