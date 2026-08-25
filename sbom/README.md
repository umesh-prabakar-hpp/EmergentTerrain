# Dependency inventory and SBOM

This directory publishes dependency inventories for the source release. They are generated from
the lockfiles and build metadata in this repository, not from a developer's globally installed
packages.

| File | Scope | Generator |
|---|---|---|
| `maven.cdx.json` | all Maven modules and transitive Java dependencies | CycloneDX Maven plugin |
| `cargo.cdx.json` | Rust GPU engine and transitive Cargo dependencies | Cargo metadata converted to CycloneDX |
| `julia-dependencies.toml` | Julia sidecar resolved dependencies | `Pkg.dependencies()` |
| `node-legacy-ai-gateway.json` | legacy AI gateway Node dependencies | `npm ls --package-lock-only` |

Regenerate after changing any dependency descriptor:

```bash
./scripts/generate-sbom.sh
```

The inventories identify components and versions without publishing local filesystem paths. They
are not a legal conclusion: before distributing a binary, container, dataset, or model weight,
review the transitive license fields, ship every required notice/source offer, and add
non-dependency copied material to
[`../THIRD_PARTY_PROVENANCE.md`](../THIRD_PARTY_PROVENANCE.md).

Read [`license-review.md`](license-review.md) before distributing a binary or container. The
supported game-service ZIP is made by [`../scripts/build-game-release.sh`](../scripts/build-game-release.sh):
it carries the Maven and Cargo SBOMs, notices, a matching source archive, and ordinary replaceable
runtime JARs under `lib/`. Its generated output remains under `apps/game-service/target/release/`
and is never committed. In particular, the optional legacy-ai-gateway resolves an SDK
governed by Anthropic commercial terms rather than an open-source package license; do not bundle
its installed `node_modules` unless those terms permit the intended distribution.
