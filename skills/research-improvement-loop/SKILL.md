---
name: research-improvement-loop
description: "Run a guarded EmergentTerrain research-improvement cycle: start or verify an app research run, review its new papers, turn root-caused review findings into tested fixes, update review records, then automatically commit and push the verified cycle before optionally beginning the next run. Use when the user asks to run the app-review-improve loop, continuously improve research-paper quality, or automate the post-run paper-review and implementation cycle."
---

# Research Improvement Loop

Run one complete cycle by default. Repeat only when the user explicitly asks for a loop, a count,
or a terminal condition. The objective is source-grounded, reproducible research quality—not a
cosmetic `PUBLISHED` status or a paper count.

## North star

Build an autonomous research system that can produce genuinely novel, human-quality frontier
research: source-grounded claims, pre-registered and independently reproducible computation,
credible holdouts, and an auditable path from evidence to conclusion. Treat every cycle as progress
toward that standard; never mistake fluent prose, a positive score, or a workflow label for it.

## Preconditions

1. Work from the EmergentTerrain repository root.
2. Read `AGENTS.md` and the current `application.yml` before changing runtime behavior.
3. Use `./scripts/status-related-apps.sh` for every runtime-state check. Do not substitute
   ad-hoc process or port probes.
4. Preserve unrelated dirty-worktree changes. Never commit generated `target/`, test reports, or
   `sample-output/` outputs.
5. Before any literature import, confirm no primary or screened-index writer is active. Never run
   two SQLite/FTS importers concurrently.

## One cycle

### 1. Establish the run boundary

- If the user says the app run is complete, identify that run's log window and new paper IDs.
- If the user asks to begin a run, use the repository-supported start path (`./scripts/start.sh`),
  verify runtime state with `./scripts/status-related-apps.sh`, and wait for the research run's
  explicit completion before reviewing it.
- For an active run, derive the expected completion delay from the elapsed time of the most recent
  three completed application-run boundaries (use the available completed runs if fewer than three
  exist). Sleep until that expected boundary rather than polling every 30 seconds. Inspect the log
  and runtime state at that boundary, then use only proportionate follow-up waits if the run has not
  completed. Record the current elapsed time so the rolling three-run average improves next cycle.
- Before every new `./scripts/start.sh`, run `./scripts/stop.sh` and use
  `./scripts/status-related-apps.sh` to confirm that both the app and local AI gateway
  are down. Export the supported Java 21 runtime before starting:

  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
  export PATH="$JAVA_HOME/bin:$PATH"
  java -version
  ```

  Then start the run so `start.sh` launches a fresh managed proxy. Never run `stop.sh` while a
  research run is active. Record and treat any other runtime version in the startup log as an
  operational defect, not a valid research-cycle environment.
- Once the run's completion boundary is recorded, run `./scripts/stop.sh` before review and
  confirm the app and local AI gateway are down with the status script. This is idempotent and prevents
  a completed run's managed proxy from leaking into the next cycle.
- Do not treat a stale Julia log as a sidecar failure. Correlate application, Julia, and GPU logs
  only within the run boundary.

### 2. Review new papers

- Read and follow `skills/paper-review/codex/SKILL.md` before reviewing.
- Review only paper IDs absent from `research/<room>/reviews/paper-index.md`.
- Maintain the split review layout (`CURRENT.md`, immutable run record, compact paper index, and
  issue ledger); never append the frozen root `REVIEW_REPORT.md`.
- Regenerate `docs/paper-literature-key-map.md` after every review.
- Complete bounded current-run literature discovery, provenance staging, deduplication, and safe
  separate-index import as required by `paper-review`. A staged source is not paper evidence until
  a later normal search → read → accepted assessment → Related Work chain uses it.
- After the review, copy a paper to `research/notable-papers/<paper-id>/` only when it is a
  concrete milestone toward the north star. Require a final `PUBLISHED` or artifact-backed
  candidate state, a complete locked computational protocol (including its required independent
  checks), and a source-grounded evidence chain. A rigorously executed negative result may qualify
  only when it rules out a previously viable research direction. Never copy a `NEEDS_REVISION`,
  `NULL_RESULT`, or `REJECTED` paper merely for fluent prose, a high score, or a first occurrence.
  Copy the final markdown and its final artifact directory when present; retain the original paper
  ID and do not alter the source artifact. State the milestone rationale and the copied path in the
  immutable run review. The directory is a curated local showcase, not an evidence source or a
  publication-label input.

### 3. Classify findings before changing code

For every recommendation, compare it with the issue ledger and classify it as one of:

- **New root-caused defect:** implementation is authorized after inspecting the relevant code and
  trace.
- **Hypothesis:** add observability or a narrow regression test first; do not invent a root cause.
- **Carried-over issue:** do not duplicate it; implement only if the evidence now supports a fix.
- **Not rechecked or policy decision:** preserve it and request direction if it changes scientific
  objectives, acceptance criteria, external corpus scope, or budget.

Prefer fixes that lock protocols before computation, preserve selected traces, require independent
holdouts, and keep literature evidence auditable. Do not claim novelty from a finite measurement.

### 4. Implement and verify

- Use `apply_patch` for repository edits.
- Add focused unit/regression tests for each behavioral change.
- Run the smallest relevant Maven tests first. Use Java 21.
- Run `./scripts/run-phase35-artifact-smoke.sh` after changes to retrieval, evidence lifecycle,
  Related Work, publication labels, or artifact-writing behavior.
- Update `README.md` and `AGENTS.md` whenever setup, runtime behavior, or performance workflow
  changes.
- Create a new `phases-for-human-like-performance/phase-<n>-<slug>/README.md` when a change
  introduces a durable research-quality capability, protocol, architecture, or acceptance policy.
  Otherwise update the applicable existing phase document. Record the motivation, scoped design,
  success criteria, validation evidence, and explicit resolution status (`PROPOSED`, `IMPLEMENTED`,
  `VALIDATED`, `CARRIED_OVER`, or `BLOCKED`). Update that status and unresolved-item list as later
  cycles supply evidence; do not silently leave a completed or superseded phase open.
- Validate changed review issue ledgers. Mark an item `RESOLVED` only with an implementation and
  test, smoke, or post-fix artifact observation; otherwise retain `NEW`, `CARRIED_OVER`, or
  `NOT_RECHECKED`.

### 5. Ship the cycle

- Inspect `git status`, `git diff --check`, and the staged file list.
- Commit and push every completed, verified cycle by default. Use a concise,
  behavior-oriented commit message and push `main` only after successful verification. Preserve
  unrelated dirty-worktree changes: stage and commit only files created or changed for the cycle.
- If verification fails, do not commit or push the failing implementation; report the failure and
  leave the worktree intact for correction. If the push fails after a successful commit, preserve
  the commit and report the exact push failure.
- Report the commit SHA, push result, exact tests/smokes run, unresolved issues, and whether the
  worktree is clean.

### 6. Continue or stop

- For an explicitly requested multi-cycle loop, begin the next run only after the prior cycle is
  reviewed, verified, and pushed.
- Stop and ask for direction when a new research-policy choice, destructive action, external
  authorization, unresolved index writer, repeated non-progress, or budget/availability limit
  blocks sound progress.
- Never create a self-perpetuating loop merely because a run completed. Respect the user's stated
  cycle count, stop condition, or instruction to stop.

## Completion standard

End each cycle by stating whether it produced: (1) a verified implementation, (2) a review-only
finding, or (3) a blocked decision. Keep the ultimate objective explicit: genuine, reproducible,
source-grounded frontier research rather than plausible computational prose.
