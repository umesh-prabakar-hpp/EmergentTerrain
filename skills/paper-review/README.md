# Installing the `paper-review` skill

`paper-review` evaluates generated research papers after an application run. It keeps review
history readable by separating the latest summary, immutable per-run records, reviewed-paper
index, and issue lifecycle; it does not keep appending every run to one massive report.

The canonical Claude implementation is [claude/SKILL.md](claude/SKILL.md). Codex uses the
equivalent [codex/SKILL.md](codex/SKILL.md).

## Install for Claude Code

```bash
mkdir -p ~/.claude/skills/paper-review
cp skills/paper-review/claude/SKILL.md ~/.claude/skills/paper-review/SKILL.md
```

Restart Claude Code after installation so it discovers the skill. Then run:

```text
/paper-review research/<room-id>
```

The skill auto-discovers the app, Julia, and GPU logs unless `--no-logs` is supplied. Use a
`--logs`, `--julia-log`, or `--gpu-log` argument only to override an unusual location.

## Review layout

For a new room, the skill writes under `research/<room-id>/reviews/`:

- `CURRENT.md` — replace-on-each-pass latest-run verdict, active/resolved issue IDs, and links.
- `runs/<run-id>.md` — immutable full review of one application run.
- `paper-index.md` — compact monotonic reviewed-ID inventory used for incremental deduplication.
- `issue-ledger.md` — compact cross-run issue lifecycle and resolution evidence.

For a room that already has a cumulative `REVIEW_REPORT.md`, migrate once:

```bash
python3 scripts/migrate-paper-review-layout.py research/<room-id>
```

This retains the old report as a frozen historical snapshot. New review passes must not append to
it. `paper-index.md` becomes the authority for deciding which paper IDs are new.

## Issue lifecycle

Every issue ledger row has these fields:

| Issue ID | State | First seen | Last checked | Resolved in | Evidence | Summary |
| :------- | :---- | :--------- | :----------- | :---------- | :------- | :------ |

The only allowed states are `NEW`, `CARRIED_OVER`, `RESOLVED`, `REGRESSED`, and
`NOT_RECHECKED`.

- `RESOLVED` requires both the implementing commit/configuration and a relevant test, deterministic
  smoke, or post-fix artifact/log observation.
- `CARRIED_OVER` means the defect remains in the current batch.
- `REGRESSED` means a resolved defect returned; retain both the original resolution and new evidence.
- `NOT_RECHECKED` means code changed but no applicable post-fix path has been reviewed.

Do not silently remove a recommendation when it stops appearing. Move it to `RESOLVED` only with
the required evidence, otherwise preserve its appropriate non-resolved state.

Validate the ledger after each pass:

```bash
python3 scripts/validate-paper-review-issue-ledger.py \
  research/<room-id>/reviews/issue-ledger.md
```

## Required review workflow

1. Read `reviews/paper-index.md` and review only new IDs.
2. Inspect the app run that produced the batch and correlate current logs only.
3. Write the immutable run record, then replace `CURRENT.md` and update the paper index/ledger.
4. Regenerate `docs/paper-literature-key-map.md` with
   `./scripts/update-paper-literature-key-map.sh`; it covers `research/` (including archive) and
   standalone `apps/research-service/research/` artifacts.
5. Run bounded staged-text discovery/import as defined by the skill. A staged source is not paper
   evidence and cannot change a label until a normal `search_literature → read_reference → Related
   Work` chain succeeds.

For periodic use:

```text
/loop 15m /paper-review research/<room-id>
```

The run record retains the standard eight sections: header, human-quality recommendations,
strongest papers, worst papers, per-paper ratings, batch summary, quality/novelty, and operational
logs. `CURRENT.md` remains concise and links to that record instead of duplicating history.
