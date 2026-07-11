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
- `--pattern`: `P-*.md`
- Logs: auto-discover unless `--no-logs` is present

## Core Rule

Review incrementally. Never re-review paper IDs already present in the per-paper table.
Read the existing report first, parse reviewed IDs, then review only new papers. If no new
papers exist, update the timestamp and report "no new papers".

## Discovery

1. Resolve `<research_dir>` to an absolute path.
2. List paper markdown files matching the pattern.
3. Read the existing report if present.
4. Parse the per-paper ratings table for reviewed paper IDs.
5. Identify sequence gaps and add `*(missing - sequence gap)*` rows when appropriate.

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

