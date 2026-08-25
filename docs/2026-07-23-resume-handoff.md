# Resume handoff — 2026-07-23

Read this file before resuming the post-reboot validation work. Treat the
runtime lines below as a verified starting point, not a substitute for a fresh
status check.

## Completed and pushed

- `7867df19 Harden cross-run research promotion`
  - Cross-run conjecture and verified-results promotion now requires a final
    `PUBLISHED` paper, an artifact-backed selected trace, and successful source
    persistence.
  - `TIGHT_BOUND` evidence cannot borrow a nearby refutation from a different
    grid/window size.
- `97019ec6 Harden titleless literature retrieval`
  - Phase 35 safely handles a title-less full-text-only Dirichlet source,
    rejects generic comparative framing, and uses connection-local read cache
    settings without modifying the immutable literature index.
  - `Phase34SqliteLiteratureTest` passed 22/22 and the opt-in completed-index
    `Phase34RealIndexAcceptanceTest` passed 7/7.

The repository was clean and synchronized with `origin/main` after the second
commit. Do not commit `target/`, Surefire reports, or `sample-output/`.

## Host configuration and verified runtime state

The user ran the local full-access helper. Their user-level Codex configuration
now contains:

```toml
sandbox_mode = "danger-full-access"
approval_policy = "never"
```

Those settings apply only to a **new** Codex session and may still be superseded
by managed policy. The local helper scripts used for that host setup are intentionally outside this
repository and are not required for a clone.

On 2026-07-23, host-context checks found PostgreSQL accepting connections for
both `emergentterrain` and `emergentterrain_perf`. The prescribed
`./scripts/status-related-apps.sh` check found the Julia sidecar (8088),
PyCall/grounding sidecar (8089), and release GPU engine (50051) ready. The
Spring application (8081) and its managed local AI gateway (8788) were
down. Claude remains intentionally dormant.

## Resume sequence

1. Start with a fresh host-context status check; do not infer runtime state
   from this handoff:

   ```bash
   cd "$(git rev-parse --show-toplevel)"
   ./scripts/status-related-apps.sh
   ```

2. If the Spring app and local AI gateway remain down, start them through the
   lifecycle script:

   ```bash
   cd "$(git rev-parse --show-toplevel)"
   ./scripts/start.sh
   ```

3. Repeat `./scripts/status-related-apps.sh`. Keep the existing Julia,
   grounding, and GPU processes when they are healthy; do not restart them
   merely because the Java app was restarted.

4. With PostgreSQL and all sidecars ready, resume the pending real Phase 36
   validation. Check `application.yml` for live flags before changing behavior,
   preserve the 15-second Julia conjecture timeout, and use actual recorded
   queue/compute/transport timing before considering deadline or concurrency
   changes. See `docs/2026-07-22-live-validation-blockers.md` for the earlier
   validation record and its Phase 35 resolution.

## Guardrails

- Use `./scripts/status-related-apps.sh` for application-related runtime state;
  do not substitute ad-hoc port/process checks.
- Do not initialize a new PostgreSQL database while auditing historical data.
- Keep Phase 35's relevance checks fail-closed; do not relax them to satisfy a
  fixture.
