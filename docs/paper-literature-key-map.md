# Paper-to-Literature Key Map

This generated reviewer index retains only literature keys that completed the full evidence
chain for a paper: issued by `search_literature`, successfully returned by `read_reference`, and
cited in that paper's `## Related Work` section. It is intentionally stricter than raw search
output: a retrieval hit, unread key, failed read, or uncited source is not retained here.

The paper markdown and `evidence/tool-trace.json` in the linked artifact are the source of truth.
The generator scans artifacts under `research/` (including its archive) and
`apps/research-service/research/`.
Regenerate after each paper-review pass with:

```bash
./scripts/update-paper-literature-key-map.sh
```

| Paper | Literature key | Relevance basis | Artifact |
|---|---|---|---|
| `P-b72c-77628007` | `HF1511802BB2106E451608` | issued + read + cited | [apps/research-service/research/b72c4e10/artifacts/P-b72c-77628007](../apps/research-service/research/b72c4e10/artifacts/P-b72c-77628007) |
| `P-b72c-77627970` | `HF20A94C6E8F45DBCAF99E` | issued + read + cited | [research/archive/b72c4e10-20260730-3/artifacts/P-b72c-77627970](../research/archive/b72c4e10-20260730-3/artifacts/P-b72c-77627970) |
| `P-b72c-77627977` | `HF69B6FDC948553E193947` | issued + read + cited | [research/archive/b72c4e10-20260731-2/artifacts/P-b72c-77627977](../research/archive/b72c4e10-20260731-2/artifacts/P-b72c-77627977) |
| `P-b72c-77628001` | `HFB64828E8D2A41E621DA0` | issued + read + cited + assessed relevant | [research/archive/b72c4e10-20260802-10/artifacts/P-b72c-77628001](../research/archive/b72c4e10-20260802-10/artifacts/P-b72c-77628001) |
| `P-b72c-77628002` | `HFB783EC5887C2AF943B10` | issued + read + cited + assessed relevant | [research/archive/b72c4e10-20260802-11/artifacts/P-b72c-77628002](../research/archive/b72c4e10-20260802-11/artifacts/P-b72c-77628002) |
| `P-b72c-77628003` | `HFB64828E8D2A41E621DA0` | issued + read + cited | [research/archive/b72c4e10-20260802-11/artifacts/P-b72c-77628003](../research/archive/b72c4e10-20260802-11/artifacts/P-b72c-77628003) |
| `P-b72c-77627985` | `HF7B1003353198E40842E5` | issued + read + cited | [research/archive/b72c4e10-20260802-2/artifacts/P-b72c-77627985](../research/archive/b72c4e10-20260802-2/artifacts/P-b72c-77627985) |
| `P-b72c-77627986` | `HF20A94C6E8F45DBCAF99E` | issued + read + cited | [research/archive/b72c4e10-20260802-2/artifacts/P-b72c-77627986](../research/archive/b72c4e10-20260802-2/artifacts/P-b72c-77627986) |
| `P-b72c-77627979` | `HF7B1003353198E40842E5` | issued + read + cited | [research/archive/b72c4e10-20260802-5/artifacts/P-b72c-77627979](../research/archive/b72c4e10-20260802-5/artifacts/P-b72c-77627979) |
| `P-b72c-77627990` | `HF359DC259AA5E645382C7` | issued + read + cited | [research/archive/b72c4e10-20260802-6/artifacts/P-b72c-77627990](../research/archive/b72c4e10-20260802-6/artifacts/P-b72c-77627990) |
| `P-b72c-77627994` | `HF1511802BB2106E451608` | issued + read + cited + assessed relevant | [research/archive/b72c4e10-20260802-7/artifacts/P-b72c-77627994](../research/archive/b72c4e10-20260802-7/artifacts/P-b72c-77627994) |
| `P-b72c-77627995` | `HF7B1003353198E40842E5` | issued + read + cited + assessed relevant | [research/archive/b72c4e10-20260802-8/artifacts/P-b72c-77627995](../research/archive/b72c4e10-20260802-8/artifacts/P-b72c-77627995) |
| `P-b72c-77627998` | `HF7B1003353198E40842E5` | issued + read + cited + assessed relevant | [research/archive/b72c4e10-20260802-9/artifacts/P-b72c-77627998](../research/archive/b72c4e10-20260802-9/artifacts/P-b72c-77627998) |
| `P-b72c-77627999` | `HFB64828E8D2A41E621DA0` | issued + read + cited + assessed relevant | [research/archive/b72c4e10-20260802-9/artifacts/P-b72c-77627999](../research/archive/b72c4e10-20260802-9/artifacts/P-b72c-77627999) |
| `P-b72c-77627947` | `HF7B1003353198E40842E5` | issued + read + cited | [research/archive/b72c4e10-20260802/artifacts/P-b72c-77627947](../research/archive/b72c4e10-20260802/artifacts/P-b72c-77627947) |
| `P-b72c-77627982` | `HF7B1003353198E40842E5` | issued + read + cited | [research/archive/b72c4e10-20260802/artifacts/P-b72c-77627982](../research/archive/b72c4e10-20260802/artifacts/P-b72c-77627982) |

## Review protocol

The paper-review skill regenerates this map after reviewing new papers. It does not infer source
relevance from titles, snippets, model prose, or an `EVIDENCE` token alone. If an artifact has a
`literature-audit.json`, reviewers should also inspect its `citedReadKeys` and, when available,
`citedReadReviewedKeys`; a disagreement is a review finding, not something this index may repair.
