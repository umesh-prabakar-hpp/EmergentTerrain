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
| `P-b72c-77627970` | `HF20A94C6E8F45DBCAF99E` | issued + read + cited | [research/archive/b72c4e10-20260730-3/artifacts/P-b72c-77627970](../research/archive/b72c4e10-20260730-3/artifacts/P-b72c-77627970) |
| `P-b72c-77627977` | `HF69B6FDC948553E193947` | issued + read + cited | [research/archive/b72c4e10-20260731-2/artifacts/P-b72c-77627977](../research/archive/b72c4e10-20260731-2/artifacts/P-b72c-77627977) |
| `P-b72c-77627947` | `HF7B1003353198E40842E5` | issued + read + cited | [research/archive/b72c4e10-20260802/artifacts/P-b72c-77627947](../research/archive/b72c4e10-20260802/artifacts/P-b72c-77627947) |
| `P-b72c-77627982` | `HF7B1003353198E40842E5` | issued + read + cited | [research/archive/b72c4e10-20260802/artifacts/P-b72c-77627982](../research/archive/b72c4e10-20260802/artifacts/P-b72c-77627982) |
| `P-b72c-77627985` | `HF7B1003353198E40842E5` | issued + read + cited | [research/b72c4e10/artifacts/P-b72c-77627985](../research/b72c4e10/artifacts/P-b72c-77627985) |
| `P-b72c-77627986` | `HF20A94C6E8F45DBCAF99E` | issued + read + cited | [research/b72c4e10/artifacts/P-b72c-77627986](../research/b72c4e10/artifacts/P-b72c-77627986) |

## Review protocol

The paper-review skill regenerates this map after reviewing new papers. It does not infer source
relevance from titles, snippets, model prose, or an `EVIDENCE` token alone. If an artifact has a
`literature-audit.json`, reviewers should also inspect its `citedReadKeys` and, when available,
`citedReadReviewedKeys`; a disagreement is a review finding, not something this index may repair.
