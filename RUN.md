# Run — copy-paste commands

Terse companion to [STARTUP.md](STARTUP.md). Each step in its own
terminal. Order: Ollama → GPU engine → Spring Boot → paper-review.

## 1. Ollama (fast LLM pool)

```bash
OLLAMA_NUM_PARALLEL=3 ollama serve
```

## 2. GPU engine

```bash
cd /opt/apps/ide/eclipse/workspace-web/EmergentTerrainGPUEngine && cargo run --release
```

## 3. Spring Boot app

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

## 4. Paper-review loop (Claude Code)

```
/loop 30m /paper-review research/a3289005 --logs logs/et-app.log
```
