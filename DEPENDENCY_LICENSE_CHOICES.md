# Dependency license choices

This register records the selected option when a runtime dependency offers more than one license.
It applies to the replaceable-JAR release made by `scripts/build-game-release.sh`; it is not a
substitute for the upstream license text, notices, or source obligations bundled with that release.

| Runtime component | Version | Available expression | Selected treatment | Reason |
|---|---:|---|---|---|
| Logback Classic/Core | 1.5.6 | EPL-1.0 OR LGPL | LGPL-2.1-or-later route | Avoid relying on the EPL-1.0 option in the AGPL application distribution; retain LGPL notices and replacement path. |
| JGraphT Core/IO | 1.5.3 | LGPL-2.1-only OR EPL-2.0 | LGPL-2.1 route | It is carried by the Symja runtime closure and is included as an unmodified, replaceable JAR. |
| Paguro | 3.10.3 | EPL-1.0 OR Apache-2.0 | Apache-2.0 | Use the permissive alternative. |
| `r-efi` | 5.3.0 | MIT OR Apache-2.0 OR LGPL-2.1-or-later | MIT or Apache-2.0 | The Cargo package may be redistributed under either permissive alternative. |
| Jakarta Transaction / Annotation APIs | recorded in SBOM | EPL-2.0 OR GPL-2.0-with-classpath-exception | GPL-2.0-with-classpath-exception where offered | Preserve the exception notice; do not claim plain GPL-2.0. |

## LGPL runtime set

The release keeps all runtime libraries as distinct files under `lib/`. In particular, Symja
`matheclipse-core`, `matheclipse-external`, and `matheclipse-parser` are LGPL-3.0; Hibernate,
Trove4j, Logback (selected option), and JGraphT (selected option) carry LGPL-2.1 terms. Do not
shade, relocate, merge, or block replacement of any of these JARs.

For the three Symja LGPL-3.0 JARs, the release builder also packages the exact matching Maven source
JARs under `sources/third-party/`, a SHA-256 file, and
`licenses/THIRD_PARTY_SOURCE_MANIFEST.md`. Keep those artifacts matched to the runtime JAR version.

The exact versions and hashes are in `sbom/maven.cdx.json` packaged with each release. Before a
release, review whether its runtime closure has changed and update this table rather than assuming
the choice applies to a new version.
