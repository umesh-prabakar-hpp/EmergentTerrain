# Installing the `paper-review` Claude Code skill

The `paper-review` skill audits a directory of LLM-generated research-paper
markdown files (e.g. anything published into `research/<room-id>/`) and
maintains a single persistent `REVIEW_REPORT.md` that grows incrementally per
refresh. It pairs with `/loop` for periodic re-scans.

This document is a self-contained recreation guide. It does not require any
other file from this repo. Paste-and-go.

---

## 1. Create the skill directory

Skills live under `~/.claude/skills/<name>/SKILL.md`. The directory name and
the `name:` in frontmatter must match.

```bash
mkdir -p ~/.claude/skills/paper-review
```

## 2. Create `~/.claude/skills/paper-review/SKILL.md`

Paste the entire block below into that file (it is a markdown file with
YAML frontmatter — keep the `---` lines exactly as shown).

````markdown
---
name: paper-review
description: Review LLM-generated research papers in a directory for human quality. Maintains a single persistent markdown report with per-paper ratings, Strongest/Worst lists, recommendations, batch summary, novelty assessment, and operational-log findings. Re-runs incrementally — only reads papers not yet listed in the report's per-paper table. Pair with /loop for periodic refreshes (e.g. `/loop 15m /paper-review /path/to/dir`).
---

# paper-review

Reviews LLM-generated research papers (markdown files matching `P-a328-*.md`, `P-*.md`, or whatever pattern the user provides) in a research directory and maintains a persistent quality report.

## Invocation

```
/paper-review <research_dir> [--report <path>] [--pattern <glob>]
              [--logs <path>] [--julia-log <path>] [--gpu-log <path>]
```

- `<research_dir>` — required; directory containing the paper `.md` files.
- `--report <path>` — optional; defaults to `<research_dir>/REVIEW_REPORT.md`.
- `--pattern <glob>` — optional; defaults to `P-*.md`. Use this if the paper-ID convention differs.
- `--logs <path>` — optional **override**; main application log. By default this is **auto-discovered** (see Workflow step 1b). Pass it only to point at a non-standard location. Drives the "Operational logs" section.
- `--julia-log <path>` — optional **override**; Julia math sidecar log. Auto-discovered by default. When found, adds a "Julia sidecar" subsection that captures Julia-side stack traces, undefined-var errors, parse errors, and EADDRINUSE startups. Cross-references back to the main app's `JuliaSidecarReporter` rollups.
- `--gpu-log <path>` — optional **override**; GPU engine log (and its `.err.log` peer if present). Auto-discovered by default. When found, adds a "GPU engine" subsection with device-bring-up state (Vulkan adapter, gRPC bind port), kernel/shader compile failures, OOM, and gRPC disconnects.
- `--no-logs` — optional; **skip** log discovery entirely (papers-only review). Use when you only want the per-paper assessment and not the operational sections.

**Logs are discovered automatically — you do not need to pass the three `--*-log` flags.** The flags
exist only to override a non-standard path or (via `--no-logs`) to suppress discovery. Step 1b below
specifies exactly where the skill looks.

If no `<research_dir>` is given, prompt the user for it.

## Pair with /loop for periodic refresh

To run every 15 minutes — logs are found automatically, so no flags are needed:

```
/loop 15m /paper-review /opt/path/to/research_dir
```

Step 1b auto-discovers the main app, Julia sidecar, and GPU engine logs each firing. Pass a
`--logs`/`--julia-log`/`--gpu-log` override only for a non-standard path, or `--no-logs` to skip the
operational sections. Each firing only reads papers NOT already in the per-paper table — no
re-review of papers already assessed.

## Workflow

1. **Locate and inventory.**
   - List all paper files in `<research_dir>` matching the pattern (default `P-*.md`).
   - Read the existing `REVIEW_REPORT.md` (if present) and parse the per-paper table to get the set of already-reviewed paper IDs.
   - Identify the *new* paper IDs as the set difference. If none, just bump the timestamp and report "no new papers".

1b. **Auto-discover the operational logs (unless `--no-logs`).** Do this every run, before assessing
    papers — never assume "no logs available." A common mistake is to `test -f logs/et-app.log` from
    inside the *paper subdirectory* and conclude there are no logs; resolve paths against the
    **project root**, not the research dir.
    - **Find the project root.** Walk up from `<research_dir>` until you hit the directory containing
      a `logs/` folder and/or a build marker (`pom.xml`, `.git`, `build.gradle`). For the canonical
      layout the research dir is `<root>/research/<room>/`, so the root is two levels up.
    - **Glob the standard locations** (each used if it exists; a `--*-log` flag overrides its slot):
      - main app  → `<root>/logs/et-app.log`  (newest non-archive `*.log` in `<root>/logs/` if that
        exact name is absent; ignore `<root>/logs/archive/`).
      - Julia     → `<root>/../julia-math-sidecar/logs/sidecar.log`
      - GPU       → `<root>/../EmergentTerrainGPUEngine/logs/gpu-engine.log` (+ `.err.log` peer)
      - Also scan for sibling apps generically: any `<root>/../*/logs/*.log` whose mtime overlaps the
        batch's publish-time window is a candidate worth listing.
    - **Confirm time overlap, not just existence.** A log only describes this batch if its content
      time-range brackets the papers' `Published` timestamps. Check the first/last line timestamps
      (mind the UTC-vs-local offset: paper `Published` is `…Z`, logs are usually local). A log whose
      lines predate the batch is stale *for this batch* — say so; don't mine it for this run's events.
    - **Liveness ≠ log mtime (hard-won lesson).** Do **not** infer "the sidecar/engine was down" from
      a stale log-file mtime. A process can be alive and simply not have written to that file recently
      (it wasn't called, or it logs elsewhere). To claim a process was down, check `ps` for it
      (`ps aux | grep -iE 'julia|sidecar|gpu-engine|start\.sh'`) — or find an explicit
      crash/`EADDRINUSE`/exit line in the log. Mtime alone proves nothing.
    - **Verify the code path before blaming a backend.** Before attributing a Findings number (e.g. a
      precision artifact) to a sidecar/GPU fallback, confirm the computation *actually routes through*
      that backend. Some tools compute in-process in Java despite sidecar-flavored class names — e.g.
      `verify_conjecture`'s Mertens math runs via `SidecarResultComposer → MathPhysicsEngine`
      (in-process), so the Julia sidecar is irrelevant to it whether up or down. Trace the
      tool→engine mapping in source first.
    - **Report what you found.** In the report header, list each log: path, whether found, in-window
      or stale, and process-liveness if you checked it. If discovery finds nothing, write "no logs
      found under `<root>` — papers-only review," not "no logs available."

2. **For each new paper, assess against this rubric:**
   - **Numerical correctness** — verify reported numbers against closed-form expectations where one exists (Reuss bound, Arrhenius e^(Ea·10/RT²), σ=E·α·ΔT, RLC f₀ and R_crit, Hardy-Littlewood twin density, sin θ = λ/d for diffraction, T(θ)=T₀(1+θ²/16) for pendulum, etc.).
   - **Internal consistency** — does the Conclusion match what the Findings table actually shows? Does the parameter mentioned in the Conclusion fall within the Hypothesis's stated range?
   - **Completeness** — does the text end mid-sentence? Mid-table? At a "Computation 2" stub?
   - **Coherent hypothesis-topic mapping** — is the Hypothesis text actually about the topic in the title? Are cross-domain mappings (e.g. prime gaps → CTE) physically motivated?
   - **Plausible magnitudes** — composite stresses above ~3 GPa, gas deviations near 100% at low pressure, dimensionless ratios with metre units — flag.
   - **Complete LLM call path** — does `LLM Call Path` end with `rev:*:ok`, or does it cut at `critique:skip:incomplete_firstpass`, `prof:error→fp`, or `rev:parse_failed`?

3. **Per-paper verdict.** Assign exactly one of:
   - **Acceptable** — math right, text complete, coherent.
   - **Minor revision** — math right, but truncated or boilerplate.
   - **Major revision** — partial issues; needs structural fixes.
   - **Reject** — internally contradictory, unphysical, byte-clone of an earlier paper, broken simulator, or pseudo-physical premise.

4. **Update `REVIEW_REPORT.md` in place** (apply the table-formatting rules below to every table). **The report has eight standing sections, in this order:**

   1. **Header** — Last updated, source path, reviewer, cadence.
   2. **Recommendations to raise the batch to human quality** — numbered list. Add a new recommendation only when a *new* failure mode appears that isn't already covered. Update existing recommendations with new occurrence counts and paper IDs.
   3. **Strongest papers** — ranked table, Top 10–15. Re-rank when a new paper genuinely outperforms an existing entry on math, completeness, or methodology (error bars, multi-window validation, cross-paper citation, working Gen 2). Mark new entries with **NEW**.
   4. **Worst papers** — ranked table. Cluster duplicate failure modes (e.g. "Q-R label confusion in RLC" → cluster all RLC papers with that issue under one rank with sub-letters 7a/7b/7c…).
   5. **Per-paper ratings** — single table appended to monotonically. One row per paper ever reviewed. Mark sequence gaps as `*(missing — sequence gap)*`.
   6. **Batch summary** — counts per verdict, acceptance rate, mean/top peer score, notable trends in latest batch, continuing regressions. Include a small **trajectory table** that adds one row per refresh: papers, acceptable count, acceptance %, top peer score, notable methodological first.
   7. **Quality-and-novelty section** — refreshed each cycle. Includes (a) quality-level table with the current ceiling, (b) per-paper novelty assessment of the Strongest list, (c) "How far from novel?" pointer to the closest-to-interesting paper, (d) recommended next steps.
   8. **Operational logs (whenever a main app log is found — auto-discovered or via `--logs`; omit only under `--no-logs` or if nothing is found)** — see section below. Includes Julia sidecar + GPU engine subsections whenever their logs are discovered.

5. **Operational logs section (whenever a main app log was discovered in step 1b, or `--logs` was passed):**
   - Sample only the *current run* of the log (whatever is in the file as of this refresh — assume a single continuous process; do not parse rotated history).
   - Total log lines, distribution of TRACE/DEBUG/INFO/WARN/ERROR/FATAL.
   - Group WARN/ERROR by root cause (rate-limit, sandbox-rejection, parse failure, etc.) with counts.
   - **Correlate warnings to specific paper IDs** — e.g. "rate-limited paper IDs that hit Groq 429 → produced `prof:error→fp`". This is the highest-value content of the section.
   - Recurring patterns that would not be visible in the Findings text but explain the Findings quality.
   - Connection to the acceptance-rate plateau.
   - **Sidecar fallback rollups.** Lines from `JuliaSidecarReporter` (`julia-sidecar window=5m calls={{ok=N, safety_reject=N, eval_error=N, transport_fail=N}}`) compress neatly into a small table showing call mix per window. Surface periods where `transport_fail > 0` (sidecar was down — math fell back to Symja, which loses precision) and periods where `eval_error` spiked (LLM emitted Julia syntax the sidecar's evaluator rejected).
   - **Math-fallback footprint.** Count `MathToolSuite: run_symbolic julia-fallback (transport|eval_error|parse_error)` lines and `MathToolSuite: run_gpu_eval static-rejected` lines. Each fallback means the paper's computation ran on a degraded backend (Symja instead of Julia; Sandbox instead of GPU) — that's load-bearing context for any Findings-quality regression.

6. **Julia sidecar subsection (whenever a sidecar log was discovered in step 1b, or `--julia-log` was passed).** The sidecar process logs in a different format (`┌ Info: …`, `│ exception = …`, `└ stacktrace`) and surfaces failures that never make it into the Java app log. Capture:
   - **Startup state.** Last `julia-math-sidecar starting host=… port=… julia_version=…` block, and whether it was followed by `Listening on:` (success) or `IOError: listen: address already in use (EADDRINUSE)` (port collision — sidecar is dead until manually restarted).
   - **Evaluator-threw warnings** (`┌ Warning: evaluator threw`). Group by exception class:
     - `UndefVarError: \`X\` not defined in \`Main\`` — the LLM referenced an unbound symbol. Common offenders: physical constants (`PLANCK_H`, `PI`, `σ_F`, `ε_0`) — the LLM expects Mathematica-style globals that Julia doesn't ship. Track which symbols recur; recurring ones are a prompt-engineering hint, not a sidecar bug.
     - `ParseError: …` — the LLM emitted syntax the Julia parser can't read (usually Mathematica `Power[x,y]` / `Sqrt[…]` / `Module[…]` forms leaking through, or `^` with non-numeric operands).
     - `MethodError: no method matching …` — type/domain mismatch (e.g. `asin` on a value outside [-1,1]).
     - `DomainError` — math-valid expression with an out-of-domain argument (negative under a real `sqrt`, etc.).
     - `LoadError: ArgumentError: Package … not found` — a precompile that didn't run; treat as sidecar-broken, not a paper issue.
   - **Safety-filter rejections** (the Java side logs them as `julia-fallback (safety_reject)` and the sidecar logs the matched pattern). If a real paper-relevant call gets blocked by the safety filter, surface the regex/pattern so the filter can be tightened or relaxed.
   - **Cross-reference rollup totals.** The Java app's 5-minute `JuliaSidecarReporter` rollup gives counts; the sidecar log gives the actual exception messages. Pair them: "5m window @ 19:34 had eval_error=4; sidecar log shows 2× `UndefVarError: PI`, 1× `UndefVarError: PLANCK_H`, 1× `ParseError`."

7. **GPU engine subsection (whenever a GPU engine log was discovered in step 1b, or `--gpu-log` was passed).** The GPU engine is a separate Rust+Vulkan process speaking gRPC on `127.0.0.1:50051`. Most failures are infrastructural, not paper-specific, but they explain `run_gpu_eval` non-availability. Capture:
   - **Device bring-up.** The opening line — `GPU engine gRPC server listening on 127.0.0.1:50051 using <adapter> via Vulkan` — names the active adapter. Surface it once per refresh so a degraded fallback (CPU adapter or LLVMpipe) is visible.
   - **Vulkan validation errors** / `VK_ERROR_*` codes / `DEVICE_LOST` — device-level failures that took down GPU eval until the next restart. Pair with the Java-side stretch where `run_gpu_eval` calls were declined.
   - **Shader/kernel compile failures** — the LLM occasionally emits a formula that compiles to GPU but exceeds register pressure, hits unsupported intrinsics, or contains NaN-producing branches. Group by failure class.
   - **OOM on device** (`vkAllocateMemory failed`, `out of device memory`) — usually means the sweep size requested is too large for the adapter; flag the paper IDs whose `run_gpu_eval` calls were in flight at the time.
   - **gRPC transport** — `connection refused` from the Java side maps to either "engine wasn't started" (no `gpu-engine.log` at all) or "engine crashed mid-session" (log has `listening` but stops before this refresh). Disambiguate.
   - **Companion stderr.** If a `.err.log` peer exists alongside the named log (e.g. `gpu-engine.err.log` next to `gpu-engine.log`), read it too — the engine emits Rust panic messages to stderr, not stdout.
   - **Java-side mirror.** `MathToolSuite: run_gpu_eval static-rejected: parse failed: …` lines are the Java app's *pre-flight* reject — the formula never crossed the wire. Count these separately from in-flight GPU failures, because they're a sandbox-parser issue, not a GPU issue.

8. **Root-cause each identified issue before recording it.** For every issue that will become a recommendation or a WARN/ERROR cluster in the report, do NOT settle for the surface-symptom inference — **trace the actual log timestamps when available and follow the code path to the real mechanism.** A plausible-sounding cause is not a verified one.
   - **Timestamps first.** Pull the per-event timeline from `--logs` (and the sidecar/GPU logs): when did the paper publish, when did the gate fire, when did the timeout/error hit? Two events that *look* simultaneous are often minutes apart — which can falsify the obvious guess.
   - **Then the code.** Follow the call path in the source to the exact line that produces the behaviour (a swallowed exception, an unchecked loop bound, a scale-naive comparison) and name it (`file:method`) in the recommendation's fix.
   - **Worked example (why this matters).** A double-publish was first reported as a "boundary race" (worker finishing at the timeout instant). The log timestamps showed the two publishes were 17 min apart — *not* a race — and the code path revealed the real cause: `cancel(true)`'s `InterruptedException` swallowed in `syncCall` (interrupt flag never re-set) → the router fails over to the next provider → the timeout never stops the worker. The symptom-level guess would have produced the wrong fix.
   - **State confidence honestly.** Mark a recommendation **[root-caused]** only when both the timeline and the code line are confirmed; otherwise mark it **[hypothesis]** and say what remains unverified, so a later refresh knows to finish the trace.

## Rubric notes — failure modes seen in the wild

These are *known* failure modes; flag each in the per-paper rating's "Key issue" column when present. The list grows with experience.

- **Truncation** — Findings or Conclusion ends mid-sentence; common signature is a cut at "Computation 2 — Exploring …".
- **Boilerplate conclusion** — the canned line *"Computed values were independently reproduced by peer reviewers. These findings represent an observational baseline; no prior theoretical bound constrains the expected value in this region."* — flag wherever a textbook bound clearly exists.
- **`critique:skip:incomplete_firstpass`** — peer-review stage didn't run; treat as `NEEDS_REVISION`, not `PUBLISHED`.
- **Conclusion contradicts Findings** — e.g. "1.07× is close to 2×" (P-a328-14), or Conclusion correctly states the right answer while the Findings table shows a different number (P-a328-60).
- **Hypothesis-vs-Conclusion parameter mismatch** — Hypothesis says "near 380", Conclusion says "starting 710" (P-a328-50, 61).
- **Right values, wrong labels** — Q-factor values correct but paired with the wrong R-multiplier (P-a328-47, 49, 57, 59).
- **Byte-near-identical reruns** — same numbers, same truncation point, no citation (P-a328-15/49, 33/58, 9/32/53, 68/79, 69/73/85).
- **Cross-domain non-sequiturs** — e.g. prime gaps → CTE (no physical mechanism).
- **Unphysical magnitudes** — composite stress >3 GPa, projectile drag claims that violate energy conservation.
- **Harness-internal leaks in Hypothesis** — phrases like "starting at position 10 and uses a scale of 7.0", or peer score quoted in Findings.
- **Dimensionless quantity with length units** — e.g. radius ratio "r ≈ 0.41 m".
- **Tautological "validation"** — "the constant function f(n)=17 has mean 17" used as independent support.
- **Validator support-band overclaim** — "validator supports the hypothesis" for a 30%+ deviation from a leading-order asymptotic.
- **Julia-fallback degradation** — for any paper whose `LLM Call Path` shows symbolic computation, check the main app log in the same minute for `MathToolSuite: run_symbolic julia-fallback (…)`. A `transport` fallback means the precision-rich Julia result was unavailable and Symja was used instead — review the Findings table for precision artifacts (rounded-too-early intermediate values, decimal mantissas that don't match the closed-form). A `eval_error` fallback means the LLM emitted Julia syntax the sidecar couldn't parse; the paper's Findings text may show stale or hand-computed numbers. **Require the explicit `julia-fallback` log line before claiming degradation** — do not infer it from a stale sidecar-log mtime, and confirm the tool actually routes through the sidecar (some run in-process Java; see step 1b).
- **Benign rounding mistaken for a fallback** — round-looking sweep values are often pure arithmetic, not precision loss. Before flagging "suspiciously round mean/std," reconstruct the raw quantity: e.g. `mertens_per_sqrt_W = (integer window Möbius sum)/√W`, so a perfect-square window (W=10,000 → √W=100) yields exact hundredths while W=500/2000 (irrational √W) yields messy 5-decimals — same backend, no degradation. Recover the integer (value × √W) and check it lands on a whole number before blaming an engine.
- **GPU-eval pre-flight reject** — `run_gpu_eval static-rejected` in the main app log means the LLM's formula failed the sandbox parser before reaching the GPU engine. Common signature: a paper with a sweep table whose `mean`/`std` columns are present but suspiciously round, because the fallback path is a single-point Symja evaluation, not a population sweep.
- **Sidecar-down session window** — if `--julia-log` shows `EADDRINUSE` at startup or no `Listening on:` line at all, every paper produced in that session ran without the Julia path. Tag those papers in the per-paper notes; do not penalize their authors for math the harness took away.

## Table formatting (console/notepad readability)

Reports are read in plain-text viewers (`notepad`, `cat`, `less`) as well as markdown renderers. Apply these rules to every table in the report:

1. **Pad cells with trailing spaces so the `|` separators line up vertically.** For each table, compute the max width per column (across header, separator row, and all data rows), then pad each cell to that width. Markdown renderers ignore the extra whitespace; plain-text viewers gain column alignment.

2. **Cap any free-form prose cell at ~80 characters.** Applies to the "Key issue" column in Per-paper ratings. If a finding truly needs more space, write it short in the table and append a long-form note in a "Per-paper details" appendix at the end of the report; reference it from the cell as `…see appendix`. Use the appendix sparingly — most issues compress to 80 chars.

3. **For Strongest papers and Worst papers, do NOT put long rationales inside the table.** Keep those tables to four narrow columns — `Rank | Paper ID | Topic | Peer` — and render rationales below the table as a numbered list keyed to rank, hard-wrapped at ~100 chars per line:

   ```
   | Rank | Paper ID   | Topic              | Peer  |
   |------|------------|--------------------|-------|
   | 1    | P-a328-279 | Pendulum Breakdown | 0.298 |
   | 2    | P-a328-310 | Prime Constellation| 0.198 |

   **1. P-a328-279 — Pendulum Breakdown.** Math is exact: T₀=2.375 s = 2π√(L/g)
   for L=1.40 m; period error matches T(θ)=T₀(1+θ²/16) across 14 sampled angles
   (0.05% at 5° → 11.69% at 75°). NULL_RESULT correctly stamped because the
   1% threshold falls at ~25°, not <20° as hypothesized. Q3.
   ```

4. **Hard-wrap all prose at ~100 chars per line, breaking on word boundaries.** Applies to recommendations, rationale blocks, batch-summary narrative — everything outside tables. Bullet continuation lines indent two spaces to align with the bullet text.

5. **When updating an existing report whose tables aren't aligned, rewrite the whole table in the same pass.** Pad to the new max widths in one go; do not leave a mix of padded and unpadded rows.

## Closing-line requirement

End every report regeneration with:

```
*This report is regenerated by an automated 15-minute loop. New papers added to <dir>/ will appear in the next refresh.*
```

(Or whatever the user's actual cadence is, if not 15 min.)

## Caveats

- **Do not re-review papers already in the per-paper table.** This is the primary correctness invariant — re-reviewing burns tokens and can flicker verdicts on borderline cases.
- **Root-cause, don't surface-report (see Workflow step 8).** Every recommendation must be backed by the actual log timeline and the named code line, not a plausible inference from the symptom. A guess baked into a recommendation produces the wrong fix and misleads the next refresh.
- **Always run log auto-discovery (Workflow step 1b); never claim "no logs available" without it.** Resolve log paths against the project root, not the paper subdirectory. Don't infer a process is "down" from a stale log mtime — check `ps` or an explicit crash line. Don't attribute a number to a backend fallback without (a) the explicit `julia-fallback`/`static-rejected` log line and (b) confirming the tool routes through that backend rather than computing in-process.
- **Sequence gaps** — note them as `*(missing — sequence gap)*` rows so the next refresh notices when the gap is backfilled.
- If a paper is large or the report has grown beyond 1500 lines, consider asking the user whether to roll over (start a new report file with a summary header).
````

## 3. Verify and restart Claude Code

```bash
test -f ~/.claude/skills/paper-review/SKILL.md && echo "installed"
```

**Skills are loaded at session start.** Quit and relaunch `claude` for
`/paper-review` to appear in the slash-command list. The Skill tool also
indexes them at startup, so a running session won't see the new skill.

## 4. Smoke test

Once back in Claude Code:

```
/paper-review research/<room-id>
```

On first run, the skill reads every paper in the directory and creates
`REVIEW_REPORT.md` with all eight sections populated. On subsequent runs,
only newly-added paper IDs are read.

## 5. Wire up periodic refresh

For an ongoing room generating papers continuously, point the loop skill
at the same invocation. The operational-logs section populates itself — the
skill auto-discovers the main app, Julia sidecar, and GPU engine logs each
firing (Workflow step 1b), so no `--logs` flag is needed:

```
/loop 15m /paper-review research/<room-id>
```

Pass `--logs`/`--julia-log`/`--gpu-log` only to override a non-standard path,
or `--no-logs` to skip the operational sections entirely.

The cron job is session-only and auto-expires after 7 days. To stop sooner,
use `CronList` to find the job ID, then `CronDelete <id>`. For durable
cloud-based loops that survive Claude Code restarts, use `/schedule` instead.

---

## Verdict guidelines (quick reference)

| Verdict | When to assign |
|---|---|
| **Acceptable** | Math verified against a closed form; complete text; coherent hypothesis→findings→conclusion chain; no boilerplate filler. |
| **Minor revision** | Math correct but the paper is truncated, or the conclusion is the standard "no prior theoretical bound" boilerplate, or both. |
| **Major revision** | Partial physical issue (e.g. coherent narrative but unphysical magnitudes), or one section internally contradicts another, or the topic premise is contrived but execution is honest. |
| **Reject** | Conclusion contradicts its own data, byte-near-identical to an earlier paper without citation, pseudo-physical premise (e.g. prime gaps → CTE), broken simulator masquerading as null result, or hypothesis and title describe different papers. |

## Trajectory of an example run (EmergentTerrain room a3289005)

For calibration: across 13 refreshes (22 → 91 papers), acceptance rate went
from 23% → 27% (plateau). Top peer score climbed 0.236 → 0.357 as the room
acquired error bars (refresh 8), multi-window validation (refresh 9), Gen-2
quality wins (refresh 10), and finally a `Status: DISPUTED` system-integrity
mechanism (refresh 13). The skill caught all of these as "Strongest list"
entries automatically — the rubric does not need to be tuned per-room.
