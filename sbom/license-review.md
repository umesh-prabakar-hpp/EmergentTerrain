# Transitive license review

Generated from the inventories on 2026-08-24. This is a release gate, not legal advice.

## Automated results

| Ecosystem | Components checked | Result |
|---|---:|---|
| Maven | 191 | 188 external components carry CycloneDX license metadata. The three without metadata are EmergentTerrain's own `research-contract`, `game-service`, and `research-service` modules, which inherit the repository AGPL license. |
| Cargo | 245 | 244 external components declare a Cargo license expression. The one without an expression is EmergentTerrain's own GPU-engine crate, which inherits the repository AGPL license. |
| Julia | 162 | The inventory records a conventional top-level license/notice file when one is present in the resolved package source. Julia standard-library and binary-wrapper packages require a distribution-specific review before packaging them in a container. |
| Node (optional legacy AI gateway) | 4 installed components | `@img/sharp-linux-x64` is Apache-2.0, `@img/sharp-libvips-linux-x64` is LGPL-3.0-or-later, and `zod` is MIT. `@anthropic-ai/claude-agent-sdk` says `SEE LICENSE IN README.md`; its README directs users to Anthropic Commercial Terms of Service, not an open-source license. |

## Distribution decision

The source release may include lockfiles and the inventories, but it must not include installed
dependency directories, corpora, model weights, LLM traces, or generated research output.

Do **not** distribute a container or binary until its exact dependency closure is reviewed and its
required notices are bundled. The optional legacy AI gateway is especially gated: consumers may install
and use it under Anthropic's terms, but release artifacts must not bundle that SDK or represent it
as AGPL-licensed.

For Julia, review every resolved standard-library/binary-wrapper dependency in the target image,
including its transitive native libraries. For Cargo, honor every expression in `cargo.cdx.json`,
including LGPL alternatives and bundled native-library terms. For Maven, retain any notice files
identified by the dependency's own license.

## Re-run procedure

```bash
./scripts/generate-sbom.sh
```

Then review the changed inventories, this report, and
[`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) before producing any distributable image.
