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

Reviews LLM-generated research papers (markdown files matching `P-*.md` or whatever pattern the user provides) in a research directory and maintains a persistent quality report.

## Invocation

```
/paper-review <research_dir> [--report <path>] [--pattern <glob>] [--logs <path>]
```

- `<research_dir>` — required; directory containing the paper `.md` files.
- `--report <path>` — optional; defaults to `<research_dir>/REVIEW_REPORT.md`.
- `--pattern <glob>` — optional; defaults to `P-*.md`.
- `--logs <path>` — optional; if provided, parse this log file's *current run only* and include an "Operational logs" section in the report.

If no arguments are given, prompt the user for the research directory.

## Pair with /loop for periodic refresh

```
/loop 15m /paper-review /opt/path/to/research_dir --logs /tmp/app.log
```

Each cron firing only reads papers NOT already in the per-paper table — no re-review.

## Workflow

1. **Locate and inventory.** List paper files. Read the existing `REVIEW_REPORT.md` if present and parse the per-paper table to get already-reviewed paper IDs. The new-paper set is the difference. If empty, just bump the timestamp and report "no new papers".

2. **Assess each new paper against this rubric:**
   - **Numerical correctness** — verify against closed-form expectations where one exists (Reuss bound `Em/((1−Vf)+Vf/(Ef/Em))`, Arrhenius `e^(Ea·10/RT²)`, σ=E·α·ΔT, RLC `f₀=1/(2π√LC)` and `R_crit=2√(L/C)`, Hardy-Littlewood twin density `2C₂/ln(N)²`, diffraction `floor(d/λ)` and `sin θ = λ/d`, pendulum `T(θ)=T₀(1+θ²/16)`, etc.).
   - **Internal consistency** — does the Conclusion match the Findings table? Does the parameter mentioned in the Conclusion fall within the Hypothesis's stated range?
   - **Completeness** — does the text end mid-sentence, mid-table, or at a "Computation 2" stub?
   - **Coherent hypothesis-topic mapping** — is the Hypothesis text actually about the topic in the title? Are cross-domain mappings physically motivated?
   - **Plausible magnitudes** — composite stresses above ~3 GPa, dimensionless ratios reported with metre units, Arrhenius multipliers far below the closed-form value at high Ea — flag.
   - **Complete LLM call path** — does the path end with `rev:*:ok`, or does it cut at `critique:skip:incomplete_firstpass`, `prof:error→fp`, or `rev:parse_failed`?

3. **Per-paper verdict.** Assign exactly one of:
   - **Acceptable** — math right, text complete, coherent.
   - **Minor revision** — math right, but truncated or boilerplate.
   - **Major revision** — partial issues; needs structural fixes.
   - **Reject** — internally contradictory, unphysical, byte-clone of an earlier paper, broken simulator, or pseudo-physical premise.

4. **Update `REVIEW_REPORT.md` in place. Eight standing sections, in this order:**

   1. **Header** — Last updated, source path, reviewer, cadence.
   2. **Recommendations to raise the batch to human quality** — numbered, growing list. Add new recommendations only when a *new* failure mode appears.
   3. **Strongest papers** — ranked top 10–15 table. Re-rank when a new paper genuinely outperforms an entry. Mark new entries with **NEW**.
   4. **Worst papers** — ranked table. Cluster duplicate failure modes under one rank with sub-letters (7a/7b/7c…).
   5. **Per-paper ratings** — single table, monotonically appended. Mark sequence gaps as `*(missing — sequence gap)*` so the next refresh notices when they're backfilled.
   6. **Batch summary** — counts, acceptance rate, mean/top peer score, notable trends, continuing regressions. Include a small **trajectory table** that adds one row per refresh.
   7. **Quality and novelty section** — refreshed each cycle.
   8. **Operational logs** — only when `--logs` is provided.

5. **Operational logs section (when `--logs` is given):**
   - Sample only the *current run* of the log (assume single continuous process).
   - Total log lines, distribution of TRACE/DEBUG/INFO/WARN/ERROR/FATAL.
   - Group WARN/ERROR by root cause with counts.
   - **Correlate warnings to specific paper IDs** — e.g. which `prof:error→fp` paper IDs hit Groq token-per-day quotas. This is the highest-value content of the section.
   - Connection to acceptance-rate plateau.

## Rubric notes — known failure modes

Flag each in the per-paper rating's "Key issue" column when present:

- **Truncation** — Findings or Conclusion ends mid-sentence; common signature is a cut at "Computation 2 — Exploring …".
- **Boilerplate conclusion** — *"Computed values were independently reproduced by peer reviewers. These findings represent an observational baseline; no prior theoretical bound constrains the expected value in this region."* Flag wherever a textbook bound clearly exists.
- **`critique:skip:incomplete_firstpass`** — peer-review stage didn't run; treat as `NEEDS_REVISION`, not `PUBLISHED`.
- **`prof:error→fp`** — professor stage hit Groq 429 token-per-day quota.
- **Conclusion contradicts Findings** — e.g. claiming "1.07× is close to 2×", or Conclusion correctly states the right answer while the Findings table shows a different number.
- **Hypothesis-vs-Conclusion parameter mismatch** — Hypothesis says "near 380", Conclusion says "starting 710".
- **Right values, wrong labels** — Q-factor values correct but paired with the wrong R-multiplier.
- **Byte-near-identical reruns** — same numbers, same truncation point, no citation.
- **Cross-domain non-sequiturs** — e.g. prime gaps → CTE (no physical mechanism).
- **Unphysical magnitudes** — composite stress >3 GPa; projectile drag claims that violate energy conservation.
- **Harness-internal leaks in Hypothesis** — "starting at position 10 and uses a scale of 7.0", or peer score quoted in Findings.
- **Dimensionless quantity with length units** — e.g. radius ratio "r ≈ 0.41 m".
- **Tautological "validation"** — "the constant function f(n)=17 has mean 17" used as independent support.
- **Validator support-band overclaim** — "validator supports the hypothesis" for a 30%+ deviation from a leading-order asymptotic.
- **`Status: DISPUTED` + `[REPLICATION NOTE: ...]`** — positive system-integrity signal; surface in the rating row.
- **`⚑ anomaly z=…` peer-score annotation** — system-level outlier flag; surface in the rating row.

## Closing-line requirement

End every report regeneration with:

```
*This report is regenerated by an automated 15-minute loop. New papers added to <dir>/ will appear in the next refresh.*
```

## Caveats

- **Do not re-review papers already in the per-paper table.** Primary correctness invariant.
- **Sequence gaps** stay as placeholder rows so the next refresh notices when the gap is backfilled.
- If the report grows beyond ~1500 lines, ask the user whether to roll over.
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
at the same invocation, plus the live application log so the operational-
logs section gets populated:

```
/loop 15m /paper-review research/<room-id> --logs /tmp/et-app.log
```

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
