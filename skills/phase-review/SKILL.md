---
name: phase-review
description: Audit EmergentTerrain phase-tracking documentation and summarize genuinely open work. Use when asked to review phases, phase status, pending phase items, flag flips, acceptance evidence, or documentation drift under docs/phases, including nested phase directories such as Phase 42.
---

# Phase Review

Review phase-tracking documents, distinguish real pending work from stale presentation, and
optionally archive only fully closed documents.

## Invocation

```text
/phase-review [<phase_dir>] [--out <path>] [--no-archive]
```

- Default `<phase_dir>`: `docs/phases/`.
- Walk every `*.md` recursively, including nested phase directories. Skip every `archive/` subtree
  unless explicitly requested.
- `--out` writes the terminal summary to a markdown file.
- `--no-archive` is the read-only audit mode. Use it when the phase tree has uncommitted changes
  or when an audit should not move files.

## Workflow

1. **Inventory.** List every non-archived markdown document recursively. Read its header and its
   `Status`, `Snapshot`, `What landed`, `What remains`, closing-condition, and acceptance sections.

2. **Collect candidate items.** Record explicit `pending`, `deferred`, `not shipped`,
   `CLOSED PENDING REFRESH`, operator-triggered, and live-acceptance items, plus unchecked boxes.
   Prefer a document's closing-condition or remaining-work table over incidental historical prose.

3. **Cross-check unchecked boxes.** For any item naming a path, class, symbol, or YAML flag,
   verify that artifact in the current tree. Classify a live implementation as
   **DONE-despite-unchecked**; do not report it as pending. Check `application.yml` rather than a
   Java `@Value` fallback for live flag values.

4. **Categorize surviving work** in this order:

   1. Code lanes still open — item, document, blocker, effort estimate.
   2. Flag flips queued — flag, tier when present, trigger condition.
   3. Live-run acceptance pending — code shipped but a future log/artifact observation is required.
   4. Operational follow-ups — burn-in or external-confirmation work.
   5. Deferred by design — item, document, explicit trigger.
   6. Doc hygiene — contradictory status/body text or stale links.

5. **Archive only when safe** (unless `--no-archive`). A document is eligible only when its status
   is terminal, every closing condition is met, it is not a rolling registry (`STATUS`, `README`,
   `INDEX`, flags, wishlist, tracking), and no active document still depends on it. Before moving:

   - Skip archive when there are unstaged changes below `<phase_dir>`.
   - Skip a document changed in the last seven days unless the latest change explicitly closed it.
   - Archive at most three documents per run; ask before moving four or more.
   - Preserve the relative directory under `<phase_dir>/archive/` with `git mv`.
   - Update an existing rolling status index only when it lists the moved document as active.

6. **Render the summary.** Hard-wrap tables near 100 characters and include:

   - headline and document count;
   - all six buckets, omitting empty tables with a clear `None`;
   - archived and archive-blocked documents, if any; and
   - the exact closing line:

     ```text
     Walked <N> docs; <M> items pending across <K> buckets; <X> unchecked boxes audited as DONE-despite-unchecked; <Y> docs archived; <Z> docs blocked from archive by hygiene.
     ```

## Boundaries

- Treat this as a tracking-document audit, not a code-quality review or a paper review.
- Do not edit document content to repair hygiene findings; report them for the user.
- Do not re-run acceptance audits already documented in a phase file. Spot-check only when stale
  presentation makes the documented result ambiguous.
- Do not commit archive moves. Leave any safe `git mv` changes staged and report them for review.
