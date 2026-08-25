# Third-party source manifest

This manifest applies to the supported game-service binary ZIP. It records the
exact LGPL-3.0 Symja artifacts carried as replaceable JARs in `lib/` and the
matching source JARs packaged under `sources/third-party/`.

| Runtime JAR | Version | License | Packaged source JAR | Upstream project |
| --- | ---: | --- | --- | --- |
| `matheclipse-core` | 3.2.0 | LGPL-3.0 | `matheclipse-core-3.2.0-sources.jar` | `https://github.com/axkr/symja_android_library` |
| `matheclipse-external` | 3.2.0 | LGPL-3.0 | `matheclipse-external-3.2.0-sources.jar` | `https://github.com/axkr/symja_android_library` |
| `matheclipse-parser` | 3.2.0 | LGPL-3.0 | `matheclipse-parser-3.2.0-sources.jar` | `https://github.com/axkr/symja_android_library` |

`scripts/build-game-release.sh` resolves these source artifacts from Maven
Central, fails if any cannot be obtained, and writes their SHA-256 values to
`sources/third-party/SHA256SUMS`. Do not substitute a different Symja version,
shade these libraries, or omit their source JARs without a fresh distribution
review.
