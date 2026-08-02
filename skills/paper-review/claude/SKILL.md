---
name: paper-review
description: Review LLM-generated research papers in a directory for human quality. Maintains a split current summary, immutable run records, paper index, and issue lifecycle ledger. Re-runs incrementally by reading only paper IDs absent from the index.
---

# paper-review

Reviews LLM-generated research papers, usually markdown files under `research/<room-id>/`,
and maintains split review records.

## Invocation

```bash
/paper-review <research_dir> [--report <path>] [--pattern <glob>]
              [--logs <path>] [--julia-log <path>] [--gpu-log <path>]
              [--no-logs]
```

- `<research_dir>` is required.
- `--report` is a legacy override. By default use `<research_dir>/reviews/`.
- `--pattern` defaults to `P-*.md`.
- `--logs`, `--julia-log`, and `--gpu-log` override auto-discovered logs.
- `--no-logs` skips operational-log discovery and creates a papers-only review.

If no research directory is provided, ask the user for it.

## Incremental Invariant

Do not re-review papers already present in `reviews/paper-index.md`. If it does not exist, migrate
the legacy `REVIEW_REPORT.md` once with `python3 scripts/migrate-paper-review-layout.py
<research_dir>`. Collect IDs from the index and review only new paper files. If no new papers
exist, update `reviews/CURRENT.md` and say there were no new papers.

## Workflow

1. Inventory paper files in `<research_dir>` matching the glob.
2. Parse `reviews/paper-index.md`, falling back to the legacy report only before migration.
3. Auto-discover logs unless `--no-logs` was supplied.
4. Review each new paper using the rubric below.
5. Write `reviews/runs/<run-id>.md`, replace `reviews/CURRENT.md`, append compact rows to
   `reviews/paper-index.md`, and update `reviews/issue-ledger.md`.
6. Regenerate `docs/paper-literature-key-map.md` with
   `./scripts/update-paper-literature-key-map.sh` from the project root.
7. Validate the lifecycle ledger and end with the closing line.

## Split layout and lifecycle

Use `<research_dir>/reviews/` as the durable review surface:

- `CURRENT.md` is a replace-on-each-pass latest-run summary.
- `runs/<run-id>.md` is an immutable full record for exactly one app run.
- `paper-index.md` is the compact monotonic inventory used to identify new paper IDs.
- `issue-ledger.md` is the compact cross-run lifecycle authority.

After migration, leave the root `REVIEW_REPORT.md` as a frozen historical snapshot; never append
new reports to it. The ledger columns are `Issue ID`, `State`, `First seen`, `Last checked`,
`Resolved in`, `Evidence`, and `Summary`; allowed states are `NEW`, `CARRIED_OVER`, `RESOLVED`,
`REGRESSED`, and `NOT_RECHECKED`.

An item is `RESOLVED` only when a concrete change and a relevant test, smoke, or post-fix artifact
prove it. An unchanged current defect is `CARRIED_OVER`; a previously resolved defect that returns
is `REGRESSED`; changed-but-not-observed behavior is `NOT_RECHECKED`. Every recommendation must
name its ledger ID, and resolved recommendations must remain visible in the ledger rather than
silently disappearing. Run:

```bash
python3 scripts/validate-paper-review-issue-ledger.py \
  <research_dir>/reviews/issue-ledger.md
```

## Literature-Key Map

Retain a key for a paper only when `search_literature` issued it in an `EVIDENCE` result,
`read_reference` successfully returned a passage for the same key, and the paper's
`## Related Work` section cites it. The map is a reviewer index, not a source of evidence.
Do not retain raw search hits, unread keys, failed reads, snippets, or asserted relevance alone.
Compare generated rows with `literature-audit.json` `citedReadKeys` and, when available,
`citedReadReviewedKeys`; any disagreement is a review finding.

## Default literature-backfill discovery

Run this preparation on every review pass; it is part of the default workflow and does **not**
require a `--prepare-literature-backfill` switch. It is discovery and offline-import preparation,
not a new evidence tool.

1. Inspect only the app run that produced the review batch and preserve every Phase 35 plan,
   associated virtual-thread paper ID, and raw query terms in a provenance manifest. A multi-run
   or seven-day sweep is an explicit one-time backfill activity, never the recurring default.
2. Infer the paper's mathematical subject from its locked registration, title, and trace. Build a
   bounded **subject map**. Preserve the logged Phase 35 terms as provenance but use no more than
   four lanes, each capped at five candidates: exact target/predicate; quantitative asymptotic or
   distribution; finite-range method; and bias/normalization caveat. For an affine-density study,
   search the exact quoted predicate and its underlying arithmetic functions before considering
   the arbitrary mined relation. Pair every query with the locked subject; never search a weak
   narration fragment such as `held out` or `composite inequality` alone.
3. Run every lane against the UW theorem index. Record title candidates with indexed full text
   separately from body-only matches. A missing companion, no title match, or rejected body match
   is coverage state, not a literature-absence claim.
4. Deduplicate across all lanes and earlier manifests by canonical URL, arXiv ID, and SHA-256.
   Reuse matching validated text rather than downloading it again. Download only new source text
   (TeX, plain text, BibTeX, and styles needed to read TeX) into an idempotent staging directory;
   no PDFs. Record lane, canonical URL, query, paper mapping, timestamp, size, and SHA-256.
5. Screen after download with a stated tier: `DIRECT_TARGET`, `METHOD_CONTEXT`,
   `BACKGROUND_ONLY`, or `REJECTED_LOW_RELEVANCE`. Only direct-target and method-context sources
   may be `RETAINED_FOR_IMPORT`; background sources remain provenance and cannot satisfy paper
   grounding. A generic keyword match or asserted relevance is not enough.
6. Validate the manifest, then import `RETAINED_FOR_IMPORT` sources into the separate incremental
   paper-review-screened index before ending the review. First confirm no primary or screened
   index writer is active; do not run either importer concurrently.

   ```bash
   pgrep -af 'prepare-hf-math-index.py|prepare-paper-review-screened-literature-index.py' || true
   python3 scripts/prepare-paper-review-screened-literature-index.py \
     --staged-text-manifest <manifest>
   stat -c '%n:%s:%Y' \
     /home/fedorauser/.emergentterrain/indexes/math-literature-paper-review-screened/literature.db
   ```

   If a writer is active, leave the validated manifest staged and pending safe import; do not wait,
   retry, or invoke the primary importer. The separate import never mutates the primary index,
   issues no `HF...` key, cites no paper, and upgrades no label. A source becomes evidence only
   after a later normal indexed search/read/Related Work chain.

Write new subject-map manifests as `schema_version: 2`. The manifest must distinguish `DISCOVERED`, `RETAINED_FOR_IMPORT`,
`REJECTED_LOW_RELEVANCE`, and `UNRESOLVED_RETRIEVAL`, and preserve each candidate's subject-map
lane and relevance tier. A network/source/index failure is unresolved retrieval, not a
literature-absence claim.

## End-of-review screened-index result

When the review created one or more `RETAINED_FOR_IMPORT` entries, end the review response with
an explicit **Staged and indexed review literature** summary. List each source, identifier,
matched paper ID(s), manifest, import result, and index path. State that it is in the separate
review index only, has no `HF...` key, is not paper evidence, and cannot change a publication label
until a later normal `search_literature → read_reference → Related Work` chain. If an active writer
deferred import, instead say **Staged literature awaiting safe import**, name it, and provide the
exact import command for after it exits.

After the next normal paper run, show the verification command too; source grounding is complete
only when the resulting artifact records the normal issued-key/read/citation chain.

```bash
jq '.gate | {sourceGroundedRelatedWork, workflowStatus, publicationLabel, citedReadKeys}' \
  research/<room>/artifacts/<paper-id>/evidence/literature-audit.json
```

If no source was retained, end the review response with: `No relevant web-discovered source was
staged or imported in this review.` Do not turn that result into a claim that the literature is
absent. A source or network failure must instead name the affected subject and say
`UNRESOLVED_RETRIEVAL`.

## Log Discovery

Resolve paths against the project root, not the paper directory. Walk up from `<research_dir>`
until a project marker is found: `logs/`, `pom.xml`, `.git`, or `build.gradle`.

Standard locations:

- Main app: `<root>/logs/et-app.log`, or the newest non-archive `*.log` under `<root>/logs/`.
- Julia sidecar: `<root>/../julia-math-sidecar/logs/sidecar.log`.
- GPU engine: `<root>/../EmergentTerrainGPUEngine/logs/gpu-engine.log`, plus `.err.log` if present.
- Also list sibling app logs whose mtimes overlap the batch publish window.

Confirm time overlap. A stale log is not evidence for the current batch. Do not infer a process was
down from log mtime alone; check `ps` or an explicit crash/exit line. Do not blame a backend
fallback unless an explicit `julia-fallback` or `run_gpu_eval static-rejected` line exists and the
source path confirms the tool actually routes through that backend.

## Review Rubric

Assess each paper for:

- Numerical correctness against closed forms or executed tool output.
- Internal consistency between hypothesis, findings, methods, and conclusion.
- Completeness: no mid-sentence, mid-table, or stub endings.
- Coherent topic mapping.
- Plausible magnitudes and dimensions.
- Complete LLM call path, especially whether revision ended in `rev:*:ok`.
- Adversarial coverage for supported claims.
- Honest NULL_RESULT handling: clean null papers are not failures.

## Verdicts

Assign exactly one:

- **Acceptable**: math right, complete text, coherent.
- **Minor revision**: math right but truncated, weakly presented, or boilerplate.
- **Major revision**: structural issues, wrong-target trace, contradictory conclusion, or degenerate premise.
- **Reject**: internally contradictory, unphysical/unmathematical, near-clone, broken computation, or pseudo-scientific premise.

Binary accept is Acceptable or Minor revision.

## Run-record Sections

Each immutable `reviews/runs/<run-id>.md` has eight sections, in this order:

1. Header: last updated, source path, reviewer, cadence, logs found.
2. Recommendations to raise the batch to human quality.
3. Strongest papers: this run's ranked papers, marking new entries with **NEW**.
4. Worst papers: ranked table, clustering duplicate failure modes.
5. Per-paper ratings: this run only; append the compact result to `paper-index.md`.
6. Batch summary: verdict counts, acceptance rate, mean/top peer score, trajectory table.
7. Quality-and-novelty section: current ceiling, novelty assessment, closest-to-interesting paper, next steps.
8. Operational logs: include only when logs are found or passed and `--no-logs` was not used.

Mark sequence gaps as `*(missing - sequence gap)*`.

`CURRENT.md` links to the latest run record and has only its verdict/count summary, active and
recently resolved issue IDs, plus navigation links. It must not accumulate history.

## Operational Logs Section

For the current run of the main app log:

- Count total lines and severity distribution.
- Group WARN/ERROR by root cause.
- Correlate warnings/errors to paper IDs where possible.
- Summarize recurring invisible-to-paper patterns.
- Connect operational failures to acceptance-rate or quality regressions.
- Include Julia sidecar and GPU subsections when those logs are found.

Julia sidecar subsection:

- Capture startup state: host, port, Julia version, `Listening on:`, or `EADDRINUSE`.
- Group evaluator exceptions: `UndefVarError`, `ParseError`, `MethodError`, `DomainError`,
  missing package/load errors, and safety-filter rejections.
- Pair Java rollups such as `julia-sidecar window=5m calls={...}` with sidecar exception messages.

GPU subsection:

- Capture adapter bring-up and bind state.
- Group Vulkan errors, shader/kernel compile failures, OOM, and gRPC disconnects.
- Read companion stderr logs when present.
- Separate Java pre-flight `run_gpu_eval static-rejected` from in-flight GPU failures.

## Root-Cause Rule

Root-cause each recommendation before recording it:

- Build the timestamp timeline from paper publish time, app log, sidecar log, and GPU log.
- Follow the source code path to the method or line responsible for the behavior.
- Mark a recommendation `[root-caused]` only when both timeline and code path are confirmed.
- Mark it `[hypothesis]` when either remains unverified.

## Known Failure Modes

Flag these in the key-issue column when present:

- Truncation.
- Boilerplate conclusion where a textbook/theoretical bound exists.
- `critique:skip:incomplete_firstpass`.
- Conclusion contradicts Findings.
- Hypothesis-vs-Conclusion parameter mismatch.
- Right values with wrong labels.
- Byte-near-identical reruns without citation.
- Cross-domain non-sequiturs.
- Unphysical magnitudes or units.
- Harness-internal leaks in Hypothesis.
- Tautological validation.
- Validator support-band overclaim.
- Julia fallback degradation, but only with explicit fallback logs and confirmed route.
- Benign rounding mistaken for fallback.
- GPU pre-flight reject.
- Sidecar-down session window, but only with process/log evidence.

## Table Formatting

- Pad cells so table separators align in plain text.
- Cap free-form table cells at about 80 characters; put long notes in an appendix.
- Strongest and Worst tables should stay narrow: `Rank | Paper ID | Topic | Peer`.
- Put long rationales below those tables, keyed by rank.
- Hard-wrap prose at about 100 characters.
- When updating an unaligned report, rewrite the whole table in aligned form.

## Closing Line

End every regeneration with:

```markdown
*This report is regenerated by an automated 15-minute loop. New papers added to <dir>/ will appear in the next refresh.*
```

Use the user's actual cadence if it is not 15 minutes.
