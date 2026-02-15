# AGENTS.md - EmergentTerrain

This file captures practical repo guidance learned while working on the app.

## How to run / test / build
### Required environment
- `JAVA_HOME` should point to JDK 21 (the app runs on Java 21).
- Postgres must be running and reachable at the configured URL.

### Run the app (dev)
```powershell
setx JAVA_HOME "C:\dev\lang\java\amazon-corretto-21.0.6.7.1-windows-x64-jdk\jdk21.0.6_7"
setx PATH "%JAVA_HOME%\bin;%PATH%"
```

Note: `setx` updates future shells only. For the current shell, use:
```powershell
$env:JAVA_HOME = "C:\dev\lang\java\amazon-corretto-21.0.6.7.1-windows-x64-jdk\jdk21.0.6_7"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

```powershell
C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd spring-boot:run
```

### Build
```powershell
C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd -DskipTests package
```

### Tests
```powershell
C:\dev\build\maven\apache-maven-3.8.6\bin\mvn.cmd test -DtrimStackTrace=false
```

If tests need a real DB, ensure Postgres is running and the app datasource properties are correct in `src/main/resources/application.yml` (or via env vars).

## Repo conventions
- Branching: work on feature branches and merge to `main`.
- Commit style: imperative, concise (e.g., "Tune flush thresholds and bot proposal cap").
- Formatting/linting: standard Java/Spring conventions; no auto-format tool enforced in repo.

## Architecture notes / critical modules
- `src/main/java/com/emergentterrain/service/RoomService.java` is the core gameplay loop:
  - bot maintenance, proposals, snapshots/flush logic, and state caching.
- `room_state` is the live persisted snapshot; `room_state_history` is the snapshot history.
- Flush cadence is controlled by `app.world.flush*` settings in `application.yml`.
- Bot proposal throughput is controlled by `app.consensus.*` (e.g., `maxBotProposalsPerMaintenance`, `botProposalsPerMinute`).

## Constraints
- Do not commit local outputs under `sample-output/` (already in `.gitignore`).
- Avoid touching `target/` or generated test reports in commits.
- Keep secrets out of git:
  - Don't commit real database passwords or API keys.
  - Use env vars or local overrides for sensitive values.
- If you change persistence behavior, update relevant docs in `README.md` if user-facing behavior changes.

## Tooling preferences
- Prefer `rg` for code search.
- Prefer PowerShell commands for consistency on Windows.
- Avoid running ad-hoc scripts unless needed; use existing scripts in `scripts/` when available.

## Data access / secrets guidance
- Do not log credentials or full connection strings.
- Avoid logging full payloads of user data or bot profiles.
- When running locally, use a dedicated dev database (not production).

