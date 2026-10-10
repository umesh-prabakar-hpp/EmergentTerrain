# Binary release distribution

The source repository is Apache-2.0. The game-service binary release deliberately uses a
plain application JAR plus a `lib/` directory rather than a shaded or nested dependency archive.
That makes every runtime library, including LGPL libraries, plainly replaceable.

## Build a release

First regenerate the inventories, review the changes, and commit them:

```bash
cd "$(git rev-parse --show-toplevel)"
./scripts/generate-sbom.sh
git add docs/release/sbom
git commit -m "chore: refresh release SBOMs"
```

Then assemble from that clean, committed checkout:

```bash
./scripts/build-game-release.sh
```

The generated, Git-ignored artifact is:

```text
apps/game-service/target/release/EmergentTerrain-<version>-distribution.zip
```

It contains a matching Git source archive, `app/emergent-terrain-app.jar`, unmodified runtime
dependencies under `lib/`, `bin/start`, SBOMs, notices, and license texts. It also includes the
exact source JARs and SHA-256 file for every runtime component using the selected LGPL treatment
under `sources/third-party/`; see `licenses/THIRD_PARTY_SOURCE_MANIFEST.md`. Build from a clean
tag for publication so the included source archive exactly matches the binaries.

## Run and replace a library

```bash
unzip EmergentTerrain-<version>-distribution.zip
cd EmergentTerrain-<version>
./bin/start
```

The launcher runs the plain Java classpath:

```text
app/emergent-terrain-app.jar:lib/*
```

To use a compatible modified library, replace its JAR in `lib/` and rerun `./bin/start`. Do not
shade, relocate, merge, or otherwise conceal third-party classes in the application JAR.

## Release review

Before publishing, regenerate the SBOMs and review:

- `licenses/THIRD_PARTY_NOTICES.md`
- `licenses/THIRD_PARTY_SOURCE_MANIFEST.md` and `sources/third-party/SHA256SUMS`
- `licenses/DEPENDENCY_LICENSE_CHOICES.md`
- `licenses/from-jars/`
- `docs/release/sbom/maven.cdx.json` and `docs/release/sbom/cargo.cdx.json`

The release mechanism supports replacement and collects evidence; it does not make an independent
legal conclusion about a changed dependency set.
