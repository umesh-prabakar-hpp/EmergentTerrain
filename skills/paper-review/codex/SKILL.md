---
name: paper-review
description: Review LLM-generated research-paper markdown directories for human quality in Codex. Use when the user asks to load paper-review, run a paper review, update REVIEW_REPORT.md, inspect generated papers, evaluate novelty/quality, or correlate paper defects with app/Julia/GPU logs.
---

# paper-review for Codex

Use this skill to review LLM-generated research papers and maintain a persistent
`REVIEW_REPORT.md`. This is the Codex-portable version of the Claude Code
`/paper-review` skill.

## Inputs

Expected user request shape:

```text
paper-review <research_dir> [--report <path>] [--pattern <glob>]
             [--logs <path>] [--julia-log <path>] [--gpu-log <path>]
             [--no-logs]
```

If the user says "load paper-review" only, load this skill and wait for the target
research directory. If they provide a directory, execute the review.

Defaults:

- `--report`: `<research_dir>/REVIEW_REPORT.md`
- `--pattern`: `*P-*.md` (supports both `P-<id>.md` and timestamp-prefixed
  `<timestamp>_..._P-<id>.md` output filenames)
- Logs: auto-discover unless `--no-logs` is present

## Core Rule

Review incrementally. Never re-review paper IDs already present in the per-paper table.
Read the existing report first, parse reviewed IDs, then review only new papers. If no new
papers exist, update the timestamp and report "no new papers".

## Discovery

1. Resolve `<research_dir>` to an absolute path.
2. List paper markdown files matching the pattern. Extract the stable `P-…`
   identifier from each filename rather than assuming the filename begins with it.
3. Read the existing report if present.
4. Parse the per-paper ratings table for reviewed paper IDs.
5. Identify sequence gaps and add `*(missing - sequence gap)*` rows when appropriate.

## Literature-Key Map

After every review pass, regenerate `docs/paper-literature-key-map.md` from the project root:

```bash
./scripts/update-paper-literature-key-map.sh
```

It is a compact index, not a new source of evidence. Retain a key for a paper only when all three
traceable conditions hold in that paper's final artifact:

1. `search_literature` issued the key in an `EVIDENCE` result;
2. `read_reference` successfully returned a passage for that same key; and
3. the paper's `## Related Work` section cites that key.

If an artifact has `evidence/literature-audit.json`, compare its `citedReadKeys` and, when
available, `citedReadReviewedKeys` with the generated row. Treat any disagreement as a review
finding. Do not retain raw search hits, unread keys, failed reads, snippets, or a model's
asserted relevance alone. The generator scans both the current `research/` room and archived
artifacts, and writes a `None yet` row when no paper satisfies the full evidence chain.

## Default literature-backfill discovery

Run this preparation on every review pass; it is part of the default workflow and does **not**
require a `--prepare-literature-backfill` switch. It is a discovery and offline-import preparation
lane, not a new evidence tool.

1. Inspect only the application run that produced the review batch. Extract each Phase 35
   literature plan, its virtual-thread paper ID, and its logged query terms. Preserve that raw
   pairing in a provenance manifest. A multi-run or seven-day sweep is an explicit one-time
   backfill activity, never the recurring review default.
2. Infer the mathematical subject from the paper's locked registration, title, and trace. Build a
   bounded **subject map**, retaining the Phase 35 terms as provenance but never letting those
   terms be the whole search. Use at most four lanes and five candidates per lane:

   | Lane | Constant/known-target study | Predicate or affine-density study |
   | :--- | :--- | :--- |
   | Target | exact named target/theorem or conjecture | exact predicate in quoted notation |
   | Quantitative | counting function or asymptotic form | distribution of the underlying arithmetic functions |
   | Finite method | numerical verification or finite-range measurement | finite-window/census methodology |
   | Caveat | bias, normalization, or exceptional cases | structural relation only if independently meaningful |

   For example, a twin-prime target may search the Hardy--Littlewood prime-pair conjecture,
   numerical twin-prime counting, and residue-class bias separately. A predicate such as
   `d(n) > omega(n)^2` must first search the divisor/prime-factor definitions and its exact quoted
   predicate; do not search an arbitrary mined affine coefficient as though it were literature.
   Pair every query with the locked mathematical subject. Never search narration fragments such as
   `held out` or `composite inequality` alone.
3. Run every lane's subject query against the local UW theorem index and record title candidates
   with indexed full text separately from body-only matches. This is a diagnostic only; a missing
   companion, no title match, or a rejected body match is coverage state, never literature absence.
4. Deduplicate candidates across all lanes and earlier manifests by canonical URL, arXiv ID, and
   SHA-256. Reuse an already validated matching text source instead of downloading it again.
   Download source text only (TeX, plain text, BibTeX, and styles needed to read TeX) for each
   new candidate into an idempotent temporary staging directory. Do not download PDFs. Record the
   lane, canonical URL, query, paper-ID mapping, timestamp, size, and SHA-256.
5. Screen after download using a stated tier: `DIRECT_TARGET` (the exact target/predicate),
   `METHOD_CONTEXT` (a valid method or finite-range caveat), `BACKGROUND_ONLY`, or
   `REJECTED_LOW_RELEVANCE`. Only the first two may be `RETAINED_FOR_IMPORT`; background sources
   remain provenance and cannot satisfy paper grounding. A weak keyword match, generic prime or
   divisor paper, or model assertion is not enough. State why each retained source bears on the
   locked subject and why each rejected source does not.
6. Validate the staging manifest, then import its retained sources into the separate incremental
   paper-review-screened index before ending the review. First confirm that no primary or screened
   index writer is active; do not run either importer concurrently. Run:

   ```bash
   pgrep -af 'prepare-hf-math-index.py|prepare-paper-review-screened-literature-index.py' || true
   python3 scripts/prepare-paper-review-screened-literature-index.py \
     --staged-text-manifest <manifest>
   stat -c '%n:%s:%Y' \
     /home/fedorauser/.emergentterrain/indexes/math-literature-paper-review-screened/literature.db
   ```

   If the preflight finds an active index writer, do not wait, retry, or start an import; record
   the manifest as staged and pending safe import. Never invoke `prepare-hf-math-index.py` here.
   The separate import must validate hashes and contain only `RETAINED_FOR_IMPORT` entries. It does
   not mutate the primary index, expose a downloaded source through `search_literature`, issue an
   `HF...` key, cite it in a paper, or upgrade a publication label. Those actions remain possible
   only after a later normal run completes indexed `search_literature → read_reference → Related
   Work`.

Write new subject-map manifests as `schema_version: 2`. The staging manifest must distinguish `DISCOVERED`, `RETAINED_FOR_IMPORT`,
`REJECTED_LOW_RELEVANCE`, and `UNRESOLVED_RETRIEVAL`, and preserve each candidate's subject-map
lane and relevance tier. A network, source, or index failure is `UNRESOLVED_RETRIEVAL`, never
evidence that the literature is absent.

## End-of-review screened-index result

When the review created one or more `RETAINED_FOR_IMPORT` entries, end the review response with
an explicit **Staged and indexed review literature** summary. List every retained source by title
and stable identifier, mapped paper ID(s), manifest path, import result, and screened-index path.
State plainly that the source is in the separate review index only: it has no `HF...` key, is not
paper evidence, and cannot change a publication label until a later normal run completes the
indexed `search_literature → read_reference → Related Work` chain. If import was deferred because
an index writer was active, instead say **Staged literature awaiting safe import**, name the active
writer, and give the exact command to run after it exits.

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

## Log Auto-Discovery

Unless `--no-logs` is set, find logs before reviewing papers.

Find the project root by walking up from `<research_dir>` until a marker appears:
`logs/`, `pom.xml`, `.git`, or `build.gradle`.

Standard log locations:

- Main app: `<root>/logs/et-app.log`, or newest non-archive `*.log` in `<root>/logs/`.
- Julia: `<root>/../julia-math-sidecar/logs/sidecar.log`.
- GPU: `<root>/../EmergentTerrainGPUEngine/logs/gpu-engine.log` and `.err.log` peer.
- Candidate sibling logs: `<root>/../*/logs/*.log` with mtime overlapping the batch window.

Check content timestamp overlap with paper `Published` timestamps. A stale log can be reported
as stale, but must not be mined as evidence for the current batch.

Do not infer a backend was down from log mtime. To claim downtime, use `ps` evidence or an
explicit crash, `EADDRINUSE`, connection-refused, or exit line. Before blaming a backend, confirm
the tool path in source; some tools with sidecar-shaped names compute in-process.

## Paper Rubric

Assess each new paper for:

- Numerical correctness against executed tool output or known closed forms.
- Internal consistency across Hypothesis, Methods, Findings, and Conclusion.
- Completeness: no mid-sentence, mid-table, or placeholder sections.
- Coherent hypothesis-topic mapping.
- Plausible magnitudes and units.
- Complete LLM call path, especially `rev:*:ok`.
- Adversarial coverage for supported claims.
- Correct handling of honest NULL_RESULT papers.

## Verdicts

Assign exactly one:

- **Acceptable**: math right, text complete, coherent.
- **Minor revision**: math right, but truncated, weakly presented, or boilerplate.
- **Major revision**: needs structural fixes; wrong-target trace, contradiction, or degenerate premise.
- **Reject**: internally contradictory, unphysical/unmathematical, near-clone, broken computation, or pseudo-scientific premise.

Use Acceptable/Minor as the binary publishable group.

## Report Format

Maintain these eight sections, in order:

1. **Header**: last updated, source path, reviewer, cadence, discovered logs.
2. **Recommendations to raise the batch to human quality**: add only new failure modes; update counts.
3. **Strongest papers**: ranked top 10-15. Mark new entries with **NEW**.
4. **Worst papers**: ranked, clustering duplicate failure modes.
5. **Per-paper ratings**: monotonic table with one row per paper ever reviewed.
6. **Batch summary**: verdict counts, acceptance rate, mean/top peer score, trajectory table.
7. **Quality-and-novelty**: current ceiling, novelty assessment, closest-to-interesting paper, next steps.
8. **Operational logs**: include if logs were found or passed; omit only with `--no-logs` or no logs found.

## Operational Log Review

For the current main app log:

- Count total log lines and severities.
- Group WARN/ERROR by root cause.
- Correlate warnings/errors with paper IDs.
- Explain quality regressions that are invisible in the paper text.
- Include sidecar/GPU subsections when logs are present.

Julia subsection:

- Startup status and listen/bind state.
- Group `UndefVarError`, `ParseError`, `MethodError`, `DomainError`, missing package/load errors,
  and safety-filter rejections.
- Pair `JuliaSidecarReporter` windows with concrete exceptions from sidecar logs.

GPU subsection:

- Adapter and gRPC bind state.
- Vulkan/device errors, shader/kernel compile failures, OOM, transport disconnects.
- Read stderr peer logs for Rust panics.
- Separate Java pre-flight rejects from actual GPU engine failures.

## Root-Cause Requirement

Every recommendation must be root-caused before it is recorded as fact:

- Establish a timestamp timeline from paper publish time and logs.
- Follow the source code path to the method responsible for the behavior.
- Label findings `[root-caused]` only when timeline and code path are both confirmed.
- Label them `[hypothesis]` when either part is still unverified.

## Known Failure Modes

Track these explicitly:

- Truncation.
- Boilerplate conclusion.
- `critique:skip:incomplete_firstpass`.
- Conclusion contradicts Findings.
- Hypothesis/Conclusion parameter mismatch.
- Correct values with wrong labels.
- Byte-near-identical reruns.
- Cross-domain non-sequiturs.
- Unphysical magnitudes or units.
- Harness-internal leaks.
- Tautological validation.
- Validator support-band overclaim.
- Julia fallback degradation with explicit fallback evidence.
- Benign rounding falsely blamed on fallback.
- GPU pre-flight reject.
- Sidecar-down session window with real process/log evidence.

## Table Rules

- Align markdown tables with padded cells for plain-text readability.
- Keep free-form table cells around 80 characters.
- For Strongest/Worst, use narrow tables and put rationales below as prose.
- Hard-wrap prose around 100 characters.
- If an existing table is unaligned, rewrite the full table in the same pass.

## Closing Line

End each regenerated report with:

```markdown
*This report is regenerated by an automated 15-minute loop. New papers added to <dir>/ will appear in the next refresh.*
```

Use the actual cadence if different.
