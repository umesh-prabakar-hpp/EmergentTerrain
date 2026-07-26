# Paper-to-Literature Key Map

This generated reviewer index retains only literature keys that completed the full evidence
chain for a paper: issued by `search_literature`, successfully returned by `read_reference`, and
cited in that paper's `## Related Work` section. It is intentionally stricter than raw search
output: a retrieval hit, unread key, failed read, or uncited source is not retained here.

The paper markdown and `evidence/tool-trace.json` in the linked artifact are the source of truth.
Regenerate after each paper-review pass with:

```bash
./scripts/update-paper-literature-key-map.sh
```

| Paper | Literature key | Relevance basis | Artifact |
|---|---|---|---|
| _None yet_ | — | No reviewed artifact currently has an issued, read, and cited key. | — |

## Review protocol

The paper-review skill regenerates this map after reviewing new papers. It does not infer source
relevance from titles, snippets, model prose, or an `EVIDENCE` token alone. If an artifact has a
`literature-audit.json`, reviewers should also inspect its `citedReadKeys` and, when available,
`citedReadReviewedKeys`; a disagreement is a review finding, not something this index may repair.
