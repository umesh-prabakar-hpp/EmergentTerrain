# Phase 17 — Analyses (4b rejections, hallucination determinism, model patterns)

Three audits performed after the 2026-05-17 host-thrash incident:

- §1 — 4b safety-review rejection patterns
- §2 — Hallucination determinism opportunities
- §3 — Model-specific hallucination attribution

Sources: `logs/et-app.log` (current run, 2115 lines), `logs/archive/et-app-20260517-074143.log` (1340k lines), `logs/archive/et-app-20260516-082514.log` (740k lines).

---

## 1. 4b safety-review rejection patterns

**Aggregate across the three logs (≈18 hours of running):**

| Log                  | 4b rejects | static-parse fails | semantic 4b rejects |
|----------------------|------------|--------------------|---------------------|
| 20260516 archive     |    61      |    48 (79%)        |    13 (21%)         |
| 20260517 archive     |    44      |    32 (73%)        |    12 (27%)         |
| current (post-hang)  |     7      |     3 (43%)        |     4 (57%)         |
| **total**            | **112**    | **83 (74%)**       | **29 (26%)**        |

The static parser carries the majority of the load — which is the *correct* outcome, since the deterministic check is the cheap one. The 4b semantic reviewer is reserved for the harder "does this code do what the description says" question.

### 1.1 Static-parse failure breakdown

Top failure messages across all logs (normalised — `line N` masked):

|  Count | Message                                              | Root cause                                              |
|-------:|------------------------------------------------------|---------------------------------------------------------|
|   36   | `Unexpected char '.' line N`                         | Member-access attempts (`.push`, `.length`, `.toFixed`) |
|   18   | `Unexpected token '{' line N`                        | Object literals `{a: b}` or malformed function bodies   |
|    5   | `Expected LBRACE got 'continue' line N`              | `continue` outside `for` (banned in safe grammar)       |
|    4   | `Expected LBRACE got 'return'/identifier line N`     | Early `return` / mid-block declarations                 |
|   13   | Various `Expected LBRACE got <ident>`                | Mid-block `var X = …` after a control-flow keyword      |

**Critical finding:** **43% of all static-parse fails are `.push` / `.length` / member-access attempts** (the `Unexpected char '.' line N` cluster). The LLM keeps trying JavaScript-array idioms even though the grammar reminder text spells out "no `.push`/`.pop`/`.length`" on every rejection.

**Second cluster (22%):** object literals `{a: b}`. The LLM wants to return multiple correlated statistics in one shot (`return {min, max, mean}`); the grammar's intended replacement is `recordSample(v)` calls but the LLM doesn't reach for it.

### 1.2 Semantic 4b rejection patterns

Top semantic rejections (each unique, but the *shape* clusters):

- "code calculates X but description specifies Y" — 8 of 29 (LLM mis-translates problem→code)
- "doesn't use SPEC value of X" — 4 of 29 (LLM ignores a stated parameter)
- "uses arbitrary range Y instead of specified Z" — 3 of 29 (LLM picks its own bounds)
- "computes a sum but description asks for an average" / "ratio but description asks for fraction" — 6 of 29 (off-by-aggregation errors)
- Other one-offs — 8 of 29

These are **real bugs**, not 4b false-positives. The semantic reviewer is doing its job. The 4b false-positive cluster the prior `SandboxStaticChecker` javadoc names ("hallucinating constraints, denied `sigmaSum` despite it being whitelisted, rejected bisection as non-convergent") has been replaced by the deterministic checker — and the residual semantic rejections all read as legitimate.

### 1.3 Recommendations

1. **Pre-scan for member-access in the static checker.** Before the parser runs, regex-detect tokens like `\.\w+\(?` and reject with a specific message:
   > Member access (`.push`, `.length`, `.toFixed`, etc.) is not supported. Arrays cannot grow — use `var arr = [0, 0, …]` with a fixed initial size and index into it. Use `len(arr)` for length. To collect statistics across iterations, call `recordSample(value)` at each step; the harness returns n/mean/std/min/max alongside your return value.
   
   This converts what's currently 36 retry-wasting opaque rejections into 36 *instructive* rejections. Expected impact: 30–50% reduction in sandbox-call retries.

2. **Pre-scan for object-literal patterns.** Detect `\{\s*\w+\s*:` and reject with:
   > Object literals `{key: value}` are not supported. To return multiple correlated values, call `recordSample(v)` at each computation step. The harness sees n/mean/std/min/max for free; the return statement can be a single representative scalar.
   
   Expected impact: 18 of 83 static fails (22%) become first-retry fixes.

3. **No change to the semantic 4b reviewer** — it's catching real bugs (LLM mis-translating descriptions). The 26% semantic-rejection rate is fine.

These two improvements would lift the *first-call sandbox success rate* materially without touching the LLM. Cost: ~40 lines of pre-scan code + one new test class. (Not implemented in this commit; flagged as a follow-on item.)

---

## 2. Hallucination determinism opportunities

The principle: any LLM call whose output is mechanically derivable (or can be constrained to a finite enumeration) should be replaced or pinned with code. The LLM keeps its judgement for tasks that genuinely require it.

### 2.1 Already deterministic (good — no action needed)

- **`SandboxStaticChecker`** (Phase 8 Tier 4 baseline) — replaced the 4b LLM safety reviewer for the whitelist+syntax half. Documented in its javadoc: prior 4b "rejected 100% of legitimate sandbox attempts by hallucinating constraints." Deterministic check fully eliminated that.
- **`PanelOutcome.aggregate`** — median + intersection + stddev are all deterministic. No meta-reviewer LLM.
- **`SystemResourcesGate.classify`** — pure threshold comparisons on `/proc/meminfo` + `/proc/loadavg` + JVM heap. No LLM.
- **`SimulationGate`, `TpmBudgetGate`** — pure counter comparisons.
- **`buildPanelCallPathStamp`, `shouldSuppressOnProfessorException`, `conclusionHasQuantitativeClaim`, `isPanelFeedbackActionable`, `isNoChangeRevision`** — static helpers, unit-tested directly.

### 2.2 Candidate deterministic replacements (worth doing)

Each entry: where the LLM is called today + what could replace it.

#### 2.2.1 `paperStatus` decision in `UniversityService.publishPaper` (lines 822–924)

This is already deterministic — a long if/else ladder on validator outputs. **No action.**

#### 2.2.2 `audit:4b:Ngaps=[dim1(c),dim2(m)]` — the post-firstPass quality audit

The audit reads firstPass narration and asks an LLM "does this lack physical_mechanism / cherry_picked_evidence / etc.?" Then the result becomes an `auditGapDimensions` list.

**Determinism opportunity:** the `physical_mechanism` audit dimension reads the Hypothesis text and Findings prose. For INTERDISCIPLINARY domain papers, the prompt could be replaced with a regex/keyword check ("does the hypothesis mention a physical coupling term between the two domains?"). This wouldn't catch every case, but it would catch the prime-gap → CTE cluster reliably (no `µm·K⁻¹` is present in the prose if the LLM didn't invent it, so we can pre-screen).

**Recommendation:** skip for now. The audit-4b LLM is fast (Ollama, ~200ms) and the false-positive rate is observable; replacing it would be a large prompt-engineering exercise.

#### 2.2.3 Citation validation (Phase 11 C4) — `CitationValidator`

Already deterministic — parses `[call_N]` tokens out of prose, compares against the trace's `call_id` list. **No action; this is the model.**

#### 2.2.4 "Generate followUp params" — the `followUp` field of NarrationResult

The LLM emits a `followUp: {param1, param2, predictedScore}` object suggesting the next experiment. This drives the next generation's draft params.

**Determinism opportunity:** for several methodology types, the next-experiment parameters are mechanically derivable (e.g. for `REPLICATION`, the next param1/param2 should be the same; for `EXTENSION`, monotonically larger/smaller). A switch on `methodology` could pick a deterministic default; the LLM's suggestion becomes an override only.

**Recommendation:** medium-priority. Today the LLM's followUp suggestion is the only knob; deterministic defaults would give the planner a fallback when the LLM omits the field.

#### 2.2.5 "Pick a topic" — `ResearchCycleService` weighted selection

Already deterministic with the bot's weighted-genome roll. **No action.**

#### 2.2.6 Conclusion → researchHypothesis carryover (`UniversityService` line 1098)

`ls.setResearchHypothesis(newHypothesis)` where `newHypothesis = paper.conclusion`. The bot's next paper uses this as its prior hypothesis to revise.

**Determinism opportunity:** when the paper landed as NEEDS_REVISION with `gate:resources:professor` (no computation backed the conclusion), propagating the conclusion as the next hypothesis carries the fabricated content forward. We could gate: only carry over the conclusion when paper status is PUBLISHED or NULL_RESULT (validated outcomes).

**Recommendation:** **HIGH priority follow-on.** This is a known propagation channel for fabricated content. ~5 lines of code in `UniversityService.publishPaper`.

### 2.3 Prompt-level determinism — schema-pinned outputs

The student-revision and panel-revision prompts both ask for JSON output. The narration variants (v1/v2/v3) all emit hypothesis/findings/conclusion in JSON. The router accepts free-text streaming.

**Opportunity:** **structured-output tool-calling** for compatible providers. Groq + NVIDIA NIM both support a `response_format=json_schema` or `tools=[{schema: ...}]` parameter that *forces* the LLM to emit JSON conforming to a pinned schema. Switching the narration calls to use this would:
- Eliminate `rev:parse_failed→fp` events (~10–15% of papers in some refreshes)
- Eliminate hallucinated extra fields
- Eliminate the `LenientJsonParser` repair pass entirely

**Recommendation:** **HIGH priority follow-on.** Requires router changes (new `callPrimaryWithSchema` method) and per-provider feature detection. Worth the effort — `rev:parse_failed→fp` is one of the top three failure modes named in every recent REVIEW_REPORT.

### 2.4 Temperature determinism

Looking at how the router is configured: no temperature is explicitly set on most narration calls. Default temperatures for these models are 0.7–1.0 (creativity-tuned). For:
- **Hypothesis paraphrasing** — temperature 0.0 would help
- **Citation insertion** — temperature 0.0 (we want the model to deterministically point at the right call_N)
- **Numerical claim extraction** — temperature 0.0 (numbers should match the trace bit-for-bit)
- **Prose synthesis** — temperature 0.3–0.5 (some natural-language variation is OK)
- **Topic selection / brainstorming** — temperature 0.7+ (we WANT diversity)

**Recommendation:** medium-priority follow-on. Plumb a `temperature` parameter through `router.streamPrimary` and apply 0.0 to revision-class calls.

---

## 3. Model-specific hallucination attribution

Cross-referencing the call paths of the 12 papers in `research/a3289005/` with the failure modes named in `REVIEW_REPORT.md`:

| Paper       | Status         | Narrator               | Failure mode                                      |
|-------------|----------------|------------------------|---------------------------------------------------|
| P-557       | REJECTED       | llama-3.3-70b-vers     | Citation to rejected call_2 (math right)          |
| P-559       | NULL_RESULT    | openai/gpt-oss-120b    | Fabricated 25° (no computation)                   |
| P-565       | NULL_RESULT    | llama-3.3-70b-vers     | "Five independent runs" — only one scan           |
| P-568       | NEEDS_REVISION | llama-3.3-70b-vers     | Fabricated 1480 kN at 25.8 m³                     |
| P-569       | NEEDS_REVISION | openai/gpt-oss-120b    | Real numerics; 5-block prose inflation            |
| P-570       | NEEDS_REVISION | llama-3.3-70b-vers     | Truncated hypothesis + fabricated conclusion       |
| P-571       | REJECTED       | llama-3.3-70b-vers     | Phantom [call_2][call_3]; η drift 0.788→0.793     |
| P-573       | NEEDS_REVISION | llama-3.3-70b-vers     | Canned "validator supports"; fabricated 3.00% / 4121K |
| P-575       | NULL_RESULT    | llama-3.3-70b-vers     | Internal contradiction (NULL_RESULT but "confirmed") |
| P-576       | REJECTED       | llama-3.3-70b-vers     | Cross-domain non-sequitur (prime gap → CTE)        |
| P-577       | PUBLISHED      | llama-3.3-70b-vers     | "Trend" claim contradicts subgroup data           |
| P-580       | NULL_RESULT    | llama-3.3-70b-vers     | Fabricated "actual score 0.70"                    |

### 3.1 Distribution by narrator

|  Narrator              | Papers | Reject | Needs-rev | Null | Published |
|------------------------|--------|--------|-----------|------|-----------|
| `llama-3.3-70b-vers`   |   10   |   3    |    3      |  3   |    1      |
| `openai/gpt-oss-120b`  |    2   |   0    |    1      |  1   |    0      |

**Caveat:** the sample is small and the narrator was selected by the router (typically `llama-3.3-70b-versatile` first because it's the cheapest of the high-quality Groq tier; `openai/gpt-oss-120b` ran only when Groq was budget-fallback'd). So narrator distribution is mostly a *router-policy* artifact, not a quality signal.

### 3.2 Failure modes — narrator-specific?

- **Phantom citations** (P-557 `[call_2]` rejected, P-571 `[call_2][call_3]` non-existent): both **llama-3.3-70b-versatile**. Not enough data to claim this is a model-specific issue, but the prior REVIEW_REPORT archives show the same pattern.
- **Fabricated quantitative conclusion under gate-skip** (P-559, P-568, P-570, P-573, P-575, P-580): 5 of 6 from **llama-3.3-70b-versatile**, 1 from **openai/gpt-oss-120b** (P-559). This is the first-pass narrator's behavior — both models exhibit it. The Phase 17 D3-ext gate covers both.
- **5-block prose inflation** (P-569, P-577): one from each model. Not model-specific.
- **Cross-domain non-sequitur** (P-576): **llama-3.3-70b-versatile**. Single sample.

### 3.3 Looking at the audit-4b reviewer

The audit reviewer is consistently `gemma3:4b` (local Ollama). Its grade signal (`audit:4b:Ngaps=[…]`) caught the physical_mechanism issue on P-576 reliably (and the gate routed it to REJECTED). The 4b reviewer is not the source of fabrication — it's a filter, and it's working as designed.

### 3.4 Looking at the panel reviewers

The panel pulls from 4 providers (groq, nvidia, openrouter, cerebras). When the panel ran (P-557, 565, 569, 571, 577, 576), it surfaced the right issues — phantom citations, fabricated trends, n=6 vs claimed-5. The panel's recommendations were specific. **The panel reviewer pool is not the source of hallucination; it's the corrector.** Phase 17 P2 (panel-revision) wires this correction back into the pipeline.

### 3.5 Recommendation: model assignment

**No evidence to switch narrators.** llama-3.3-70b-versatile and openai/gpt-oss-120b exhibit the same fabrication modes when the resources gate kills the professor; the difference between them is within noise at this sample size. The Phase 17 gates (D3-ext, gate-retry, panel-revision) address the failure modes at the *pipeline* level, which is correct — model choice is downstream.

**If we wanted to differentiate models**, the right experiment is a per-narrator A/B over 50+ papers, measured on: phantom-citation rate, gate-skip→fabricated-conclusion rate, peer score median. That's a Phase 18-tier piece of work. **Skip for now.**

---

## Summary

| Audit            | Key finding                                                   | Recommended action                                    |
|------------------|---------------------------------------------------------------|-------------------------------------------------------|
| 4b rejections    | 43% of static-parse fails are `.push`/`.length` member-access | Pre-scan member-access patterns; specific reject text |
| 4b rejections    | 22% of static-parse fails are object literals `{a:b}`         | Pre-scan; point to `recordSample()` as replacement    |
| Determinism      | Conclusion → researchHypothesis carryover propagates fabrication | Gate carryover on status ∈ {PUBLISHED, NULL_RESULT}   |
| Determinism      | JSON output not schema-pinned; `rev:parse_failed→fp` recurs   | Use `response_format=json_schema` for revision calls  |
| Determinism      | Temperatures default to 0.7+ on revision/citation calls       | Plumb `temperature=0.0` for deterministic calls       |
| Model attribution| No model-specific failure mode (sample too small)             | No model change; pipeline gates are the right level   |

The five recommended actions are all follow-on items, not in this commit.
