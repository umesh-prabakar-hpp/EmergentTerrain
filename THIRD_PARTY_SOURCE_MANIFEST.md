# Third-party source manifest

This manifest applies to the supported game-service binary ZIP. It records every
runtime component for which the selected distribution treatment is LGPL, plus
the matching source JAR packaged under `sources/third-party/`.

| Runtime JAR | Version | License | Packaged source JAR | Upstream project |
| --- | ---: | --- | --- | --- |
| `matheclipse-core` | 3.2.0 | LGPL-3.0 | `matheclipse-core-3.2.0-sources.jar` | `https://github.com/axkr/symja_android_library` |
| `matheclipse-external` | 3.2.0 | LGPL-3.0 | `matheclipse-external-3.2.0-sources.jar` | `https://github.com/axkr/symja_android_library` |
| `matheclipse-parser` | 3.2.0 | LGPL-3.0 | `matheclipse-parser-3.2.0-sources.jar` | `https://github.com/axkr/symja_android_library` |
| `logback-classic` | 1.5.6 | LGPL-2.1-or-later (selected) | `logback-classic-1.5.6-sources.jar` | `https://github.com/qos-ch/logback` |
| `logback-core` | 1.5.6 | LGPL-2.1-or-later (selected) | `logback-core-1.5.6-sources.jar` | `https://github.com/qos-ch/logback` |
| `trove4j` | 3.0.3 | LGPL-2.1-only | `trove4j-3.0.3-sources.jar` | `https://bitbucket.org/trove4j/trove4j` |
| `hibernate-commons-annotations` | 6.0.6.Final | LGPL-2.1-only | `hibernate-commons-annotations-6.0.6.Final-sources.jar` | `https://github.com/hibernate/hibernate-commons-annotations` |
| `hibernate-core` | 6.5.2.Final | LGPL-2.1-only | `hibernate-core-6.5.2.Final-sources.jar` | `https://github.com/hibernate/hibernate-orm` |
| `jgrapht-core` | 1.5.3 | LGPL-2.1 (selected) | `jgrapht-core-1.5.3-sources.jar` | `https://github.com/jgrapht/jgrapht` |
| `jgrapht-io` | 1.5.3 | LGPL-2.1 (selected) | `jgrapht-io-1.5.3-sources.jar` | `https://github.com/jgrapht/jgrapht` |

`scripts/build-game-release.sh` resolves these source artifacts from Maven
Central, fails if any cannot be obtained, and writes their SHA-256 values to
`sources/third-party/SHA256SUMS`. Do not substitute a listed version, shade
these libraries, or omit their source JARs without a fresh distribution review.
