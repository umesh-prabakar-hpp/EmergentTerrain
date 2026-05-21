# Run — copy-paste commands

Terse companion to [STARTUP.md](STARTUP.md). Each step in its own
terminal. Order: Ollama → GPU engine → Julia math sidecar (optional) →
Spring Boot → paper-review.

## 1. Ollama (fast LLM pool)

```bash
OLLAMA_NUM_PARALLEL=3 ollama serve
```

## 2. GPU engine

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine && cargo run --release
```

## 3. Julia math sidecar (optional — phase-22, opt-in)

```bash
cd /opt/apps/ide/eclipse/workspace-web/julia-math-sidecar && ./scripts/start.sh &
```

## 4. Spring Boot app

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrain && JAVA_HOME=/usr/lib/jvm/java-25-openjdk mvn clean spring-boot:run
```

App listens on http://localhost:8081.

> Fedora 44 dropped the JDK 21 package; JDK 25 is the only `dnf`-installable JDK
> on this dev shell. The pom targets `release 21` so cross-compile is fine, but
> Maven still needs `JAVA_HOME` exported (env-only `java -version` works because
> `/usr/bin/java` symlinks to JDK 25, but Maven reads `JAVA_HOME` directly).
> `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk` once per shell to skip the
> inline prefix.

## 5. Paper-review loop (Claude Code)

```
/loop 30m /paper-review research/a3289005 --logs logs/et-app.log
```

## 6. Phase-doc pending-items review + archive (Claude Code)

```
/phase-review
```

Walks `phases-for-human-like-performance/` and prints a categorized list of
genuinely-open items (code lanes, flag flips queued, live-run acceptance
pending, operational follow-ups, deferred-by-design, doc hygiene). **Also
archives completed docs** via `git mv` to `archive/` when their Status is
terminal (`CLOSED` / `SHIPPED` / `COMPLETE`) and every closing condition is
marked done. Archive moves are staged but not committed.

Add `--out PENDING_REVIEW.md` to also write a markdown artifact. Add
`--no-archive` to suppress the archive step (audit-only mode). Pair with
`/loop 1d /phase-review` for a daily walk, or `/loop 1d /phase-review
--no-archive` if you only want archive moves during focused human-driven
sessions.
