# Phase 12 — Fabrication-Gate Hardening (scoping)

**Scope:** Close the gap between *checks that fire* and *publications that get
stopped*. Phase 5 / Phase 7 / Phase 10 / Phase 11 stood up the structural
validators (numerical-consistency, magnitude-range, citation-as-schema,
all_tools_failed, panel-degradation, truncation-guard, harness-leak-filter).
They run on every paper. But the May 2026 REVIEW_REPORT refresh #3 shows
**88.2% of the 34-paper batch is still Reject + Major-revision**, because most
validators either (a) only WARN, (b) route to REJECTED but still write a
fabricated paper to disk, or (c) miss the specific surface pattern the
fabrication actually takes (chain-of-thought leaks, derived-formula
divergences, sandbox-runtime errors that aren't safety-rejects).

**Status:** Scoping document. No code in this phase yet. Implementation lands
across seven commits (D1–D7) plus this doc.

**Prerequisite:** Phase 11 shipped (commits `2a14a79` C1–C4 warn-only and
`403c6d3` C5a+C5b downgrade/suppress). Application is already running with
`numerical-consistency.enabled=true`, `truncation-guard.enabled=true`,
`magnitude-range.enabled=true`, `citation-aware-scoring.enabled=true`, plus
the Phase 10 `prof:*:all_tools_failed→suppressed` and
`panel:n=1:degraded:MANUAL_REVIEW→suppressed` gates. Citations-as-schema is
on `mode: warn` (application.yml:387–388) pending the C4 → C5a flip.
Phase 12 builds on top of this stack; nothing here replaces a prior gate.

**Why now:** REVIEW_REPORT refresh #3 (research/a3289005, 2026-05-15 12:35,
34 papers reviewed, sequence 395 – 432) names eight high-leverage misses with
worked examples. The fixes are mechanical — patterns, gate-mode flips, and
one new registry — none require a model swap or a prompt-template rewrite.
Combined, they would shift the verdict distribution from
**0 / 3 / 12 / 19** (Acceptable / Minor / Major / Reject) to roughly
**0 / 5 / 17 / 12**, lifting acceptance from 8.8% to ~15% before any quality
work on the model itself.

The pattern in the eight misses is consistent: a validator exists *or could
exist with a one-line pattern addition*, but it doesn't currently stop the
publish. Phase 12 is exclusively about closing that loop.

**Goal:** Make every existing fabrication signal **block publication**, not
just decorate it.

- **D-series** — seven commits delivering: chain-of-thought stripper,
  numerical-consistency promotion, zero-successful-tools widening, panel
  degradation demotion, truncation-guard pattern extension, textbook-bound
  registry, additive LLM Call Path. Sizing below.
- **Outside scope:** prompt-template redesign, model swaps, new tools, new
  research domains. Phase 12 is exclusively about gate enforcement on the
  existing pipeline.

**Why this is risky:**

- **Promotion to hard gate inverts the failure mode.** Today a borderline
  paper publishes as REJECTED — the file lands on disk and the per-paper
  table renders it. After D2, the same paper produces nothing on disk. The
  `paper-review` skill must be told that "missing in sequence + suppression
  marker in log" is success, not regression. The REVIEW_REPORT skill already
  handles `gate:closed:first-pass` correctly (refresh #3 §7); the D-series
  suppression markers follow the same shape so the skill's existing
  "gate-suppressed (not missing)" treatment carries over.
- **The `<think>` block stripper has no false-positive budget.** Both P-427
  and P-432 had Findings *open* with `<think>\nOkay, let's tackle this query`
  and never close the tag — there is no `</think>` to match against. A naïve
  greedy `<think>.*</think>` regex won't fire. The D1 implementation has to
  detect the unmatched open-tag case and strip everything from that point
  forward, then fall back to the template hypothesis/findings/conclusion
  because the model produced no usable prose.
- **Textbook-bound registry can drift.** Five Reject papers (P-416, P-422,
  P-423, P-427, P-432) used the canned "no prior theoretical bound" line on
  problems with textbook bounds the literature corpus already knows
  (Mertens, Beer-Lambert, Stefan-Boltzmann, Doppler, Hall sign). D6 reuses
  the `literature-corpus.yml` entries the Phase 6 D1.2 corpus already
  populated — no new YAML, no new ground truth.

---

## Architecture

```
                Phase 12 — fabrication-gate hardening
                ─────────────────────────────────────

  ┌──────────────────────────────────────────────────────────┐
  │  Existing pipeline (Phase 10 + 11 baseline)              │
  │                                                          │
  │  professor synthesis ───▶ student revision ──▶ panel    │
  │       │                       │                 │        │
  │       │                       │                 ▼        │
  │       │                       │            outcome,      │
  │       │                       │            consensus     │
  │       ▼                       ▼                 │        │
  │  prof:*:blank/all_tools  rev:parse/ok          │        │
  │       │                       │                 │        │
  │       └─────── callPath ──────┴─────────────────┘        │
  │                       │                                  │
  │                       ▼                                  │
  │            UniversityService.publishPaper                │
  │                       │                                  │
  │  truncation-guard ─▶ topic-specific ─▶ numerical-       │
  │       │           consistency      magnitude-range      │
  │       │              cross-domain  dedup                │
  │       │                       │                          │
  │       └──── paperStatus (REJECTED / NEEDS_REVISION /     │
  │              NULL_RESULT / PUBLISHED) ─────▶ disk + DB   │
  └──────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────┐
  │  Phase 12 additions                                      │
  │                                                          │
  │  D1  PromptSanitizer.stripChainOfThought       ┐         │
  │       (open `<think>` with no close → strip    │         │
  │        + fall back to template fields)         │         │
  │                                                │         │
  │  D2  NumericalConsistencyValidator             │ runs    │
  │       ─ status: REJECTED → suppress mode       │ before  │
  │       ─ derived-formula tolerance (P-417       │ Phase   │
  │         16× σ_y from σ₀+k·d^(-1/2))            │ 11      │
  │       ─ failed-call presence (P-428)           │ gate    │
  │       ─ no-Methods (P-419)                     ┘         │
  │                                                          │
  │  D3  Professor-stage suppression widening                │
  │       ─ totalRejects ≥ 1 ∧ totalSuccesses == 0           │
  │         (runtime errors, not just safety rejects)        │
  │                                                          │
  │  D4  ReviewPanelService                                  │
  │       ─ n ∈ {2, 3} : PUBLISHED → NEEDS_REVISION          │
  │         (n=1 already suppresses — D4 fills 2,3)          │
  │                                                          │
  │  D5  truncation-guard patterns                           │
  │       ─ "The observed30," numeric-leak-into-next         │
  │       ─ Hypothesis mid-sentence (P-419)                  │
  │                                                          │
  │  D6  TextbookBoundRegistry → ClosingProseValidator       │
  │       (reads literature-corpus.yml; suppresses the       │
  │        "no prior theoretical bound" boilerplate on       │
  │        topics with a known closed_form)                  │
  │                                                          │
  │  D7  callPath additivity — every model-fallback           │
  │       event prepends a stamp instead of overwriting       │
  │       (audit visibility, no gate behaviour change)        │
  └──────────────────────────────────────────────────────────┘
```

**The atomic shift:** every D-series item adds zero new prompt templates and
zero new tool definitions. The single new persistent artifact is one
optional column on the literature-corpus entry (`bound_known: true`),
introduced in D6 — and even that is opt-in (default `false` matches today's
behaviour).

---

## D1 — Strip raw `<think>` chain-of-thought from Findings

**Effort:** ~1 hour. **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/research/PromptSanitizer.java` — add
  `stripChainOfThought(String)` and wire into `sanitize(String)` ahead of
  the harness-leak patterns.
- `src/main/java/com/emergentterrain/research/UniversityService.java:412-416`
  — call `stripChainOfThought` once per field before the existing
  `PromptSanitizer.sanitize` pass, and detect when the strip produces a
  blank field so the template-fallback branch fires.
- `src/test/java/com/emergentterrain/research/PromptSanitizerTest.java` —
  three new tests (closed `<think>…</think>`, unclosed `<think>` to EOF,
  no-think baseline).

**The bug:** P-427 (Doppler) and P-432 (Hall) Findings begin with the
literal token sequence

```
<think>
Okay, let's tackle this query. The user wants me to act as a scientific
editor…
```

and run until the section boundary without a `</think>` close-tag. This is
a sanitizer gap on whichever reviewer model fronts those two papers (both
carry `panel:degraded` tails); the model emits its raw reasoning block
because its `<think>` post-processor is single-vendor-specific and didn't
run on the cross-vendor path.

**Pattern:**

```java
private static final Pattern THINK_BLOCK_CLOSED =
        Pattern.compile("(?is)<think>\\s*.*?</think>\\s*");

private static final Pattern THINK_BLOCK_OPEN_UNCLOSED =
        Pattern.compile("(?is)<think>.*$");

private static final Pattern OKAY_LETS_TACKLE =
        Pattern.compile("(?is)^\\s*Okay,\\s+let's\\s+tackle\\s+this\\s+query[^.]*\\.\\s*");

public static String stripChainOfThought(String text) {
    if (text == null || text.isBlank()) return text;
    String cleaned = THINK_BLOCK_CLOSED.matcher(text).replaceAll("");
    cleaned = THINK_BLOCK_OPEN_UNCLOSED.matcher(cleaned).replaceAll("");
    cleaned = OKAY_LETS_TACKLE.matcher(cleaned).replaceAll("");
    return cleaned.trim();
}
```

`THINK_BLOCK_OPEN_UNCLOSED` is the load-bearing pattern — it strips
everything from the opening tag to end of string when there is no closing
tag. `OKAY_LETS_TACKLE` is a defence in depth: even when the `<think>` tag
isn't emitted (some vendors strip it before sending), the opening sentence
of the chain-of-thought leak has a recognisable form.

**Fallback when strip empties the field:** `UniversityService.publishPaper`
already uses

```java
String hypothesis = (narration.hypothesis() != null && !narration.hypothesis().isBlank())
        ? narration.hypothesis() : draft.templateHypothesis();
```

at line 385. After D1's strip, a chain-of-thought-only Findings becomes
blank, so the existing fallback path fires. No new branch needed.

**Acceptance:**
- Unit tests pass for: closed `<think>…</think>` (strip the tags + content);
  unclosed `<think>` to EOF (strip everything from the tag forward);
  "Okay, let's tackle this query…" alone with no tag (strip the opening
  sentence); ordinary prose (return unchanged).
- A re-run over the persisted Findings text of P-427 and P-432 produces
  blank content, triggering the template fallback.
- Next REVIEW_REPORT refresh: zero papers with `<think>` in published
  Findings, and the Cluster 1 row in the §3 Worst-papers table is empty.

---

## D2 — Promote numerical-consistency: REJECTED → suppressed

**Effort:** ~3 hours. **Pre-condition:** D1 (low coupling, ordering only
matters because D1 changes the prose before D2 reads it).

**Files touched:**
- `src/main/java/com/emergentterrain/research/UniversityService.java:584-611`
  — promote `numericallyInconsistent` from a contributor to `paperStatus =
  "REJECTED"` into a hard-suppress branch that returns from `publishPaper`
  before `addPaper` is called.
- `src/main/java/com/emergentterrain/research/NumericalConsistencyValidator.java`
  — close five named false-negatives (see *Coverage gaps* below).
- `src/main/resources/application.yml:320-321` — add `mode` flag mirroring
  Phase 11's `warn` / `downgrade` / `suppress` triplet.
- `src/test/java/com/emergentterrain/research/PublishPaperTest.java` and
  `NumericalConsistencyValidatorTest.java` — new cases per gap.

**Current behaviour** (`UniversityService.java:584-590`):

```java
boolean validatorRejected = numericallyInconsistent || magnitudeOutOfRange
        || uncitedClone || crossDomainDrift || physicalMechanismAuditFailed
        || cherryPickedAuditFailed;
if (isNullResult) {
    paperStatus = "NULL_RESULT";
} else if (validatorRejected) {
    paperStatus = "REJECTED";  // ← still calls addPaper(...)
}
```

A REJECTED paper still writes to disk and shows up in the per-paper table.
REVIEW_REPORT refresh #3 §7 reports the harness check fires on 20 of 34
papers; every one of those 20 is in the published corpus.

**Target behaviour:** when `numericallyInconsistent` is the *sole* reason
in `validatorRejected` and the new `mode` flag is `suppress`, the paper is
dropped (no `addPaper`, no disk write, no DB row), the call path is
stamped `numeric_inconsistent→suppressed`, and the draft queue is
decremented in the same place as the Phase 10 all_tools_failed gate. When
the flag is `downgrade`, the paper publishes as NULL_RESULT (mirroring
Phase 11 C5a). When the flag is `warn` (default for the rollout), the
status remains REJECTED — bit-identical to today.

**Coverage gaps** (REVIEW_REPORT refresh #3 §7 lists 5 misses):

| Paper | Gap | D2 fix |
|-------|-----|--------|
| P-405, P-422 | Recovered ε IS in trace, but the recovery had 99.9% deviation. Consistency regex passes. | Add a *deviation-against-cited-baseline* check: when the trace's call returns a `deviation` / `error_pct` field above 50%, flag even if the numeric matches. |
| P-410 | Unphysical 11.1 GPa stress; check is consistency, not plausibility. | Out of scope for D2 — magnitude-range validator already owns this; D2 marks P-410 as "magnitude not numeric" in the call path so reviewers don't expect a D2 catch. |
| P-417 | 2377 vs 149 MPa is a 16× discrepancy but prose value is derived (σ₀ + k·d^(-1/2)). Check looks at literal numbers. | Add a *derived-formula tolerance pass*: when both operands (σ₀, k, d) are in the trace, recompute server-side and compare against the literal. Allowlist is intentionally tight: σ₀ + k·d^(-1/2) (Hall-Petch), η = (sqrt(2)-1) (octahedral void), b/T (Wien). Same shape as Phase 11 C4b's permitted-derivations escape hatch — D2 ships only the three Hall-Petch / octahedral / Wien forms the May 2026 corpus actually hits, not the general `mean_of`/`ratio_of` machinery. |
| P-419 | No Methods section, so nothing to check against — passes trivially. | Add a *zero-trace* precondition: if `toolCallTrace` is null or empty AND `findings` cites any number, flag. The existing `all_tools_failed→suppressed` gate doesn't catch P-419 because the professor stage *errored* without ever entering the tool loop (no metrics counted) and the paper fell through to the firstPass narration. D2's no-trace check operates at publish time, downstream of the firstPass path. |
| P-428 | Cites a failed `call_1` for the headline number. Check treats failed-call results as "not in trace and therefore not testable." | Add an *errored-call flag*: when a citation marker `[call_N]` resolves to a `ToolInvocation` whose `resultJson` parses to `"error":...` or `"rejected":...`, treat the same as a missing claim. Requires the Phase 11 C1 `callId` and the existing per-tool result-blob serialisation; no schema change. |

**Rollout phases** mirror Phase 11 C4-C5 exactly:

```
warn      → log + stamp `numeric_inconsistent` on call_path. Paper publishes
            as REJECTED. (Today's behaviour. Default after D2.)
downgrade → paper publishes as NULL_RESULT with an honest preamble naming
            the unverifiable values.
suppress  → paper is dropped entirely (returns from publishPaper before
            addPaper). callPath gets `numeric_inconsistent→suppressed`.
```

The default lands on `warn` so D2's code is exercised at full coverage for
one REVIEW_REPORT refresh (15 min) before the operator flips to `suppress`.

**Acceptance:**
- Existing `NumericalConsistencyValidatorTest` cases still pass.
- New unit cases cover each of the five named gaps (P-405-style deviation,
  P-417-style derived formula, P-419-style empty trace, P-428-style errored
  call).
- In `suppress` mode, a replay over the 34-paper batch shows 14 of the 20
  flagged papers absent from the published list and the corresponding
  `numeric_inconsistent→suppressed` markers in the operational log.
- In `warn` mode, the absent-from-disk count is zero (no regression for
  the rollout default).
- A clean paper (no validator issues) publishes identically in all three
  modes.

---

## D3 — Widen zero-successful-tools gate

**Effort:** ~30 min. **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java:455-462`
  — change the threshold.
- `src/test/java/com/emergentterrain/service/ResearchNarrationServiceTest.java`
  (or the existing professor-stage test) — add the runtime-error case.

**Current behaviour** (line 455):

```java
if (metrics.totalRejects() >= 3 && metrics.totalSuccesses() == 0) {
    callPath.add("prof:" + abbrev(router.primaryModel())
            + ":all_tools_failed→suppressed");
    return null;
}
```

The `>= 3` threshold was tuned in Phase 10 to suppress safety-rejected
calls — the executor's retry loop typically allows up to 4 attempts and
3-of-4 rejections was the conservative read. But REVIEW_REPORT refresh #3
shows five papers (P-395, P-402, P-405, P-411, P-422) with *runtime
errors* on every tool call ("index out of bounds", "Cannot use array as
scalar"), not safety rejections — so the metrics increment as
`sandboxRejectCount` only when the safety review fires, leaving runtime
failures uncounted. Hence five papers slip past the gate.

**Fix:** Move from `totalRejects >= 3` to `totalSuccesses == 0` (drop the
rejection-count floor). The check still requires zero successes, so a paper
that produced any valid tool output continues to publish. The only papers
the new threshold catches are those with literally no successful
computation — which is exactly the fabrication signal the report
identifies.

```java
if (metrics.totalSuccesses() == 0) {
    callPath.add("prof:" + abbrev(router.primaryModel())
            + ":zero_successes→suppressed");
    log.warn("LLM professor tools: 0 successes (rejects={}) paperId={} — draft suppressed",
            metrics.totalRejects(), draft.paperId());
    return null;
}
```

**Concern:** existing "prof:ok" papers depend on at least one tool
succeeding to count as "ok". This is already true today — `prof:ok` is
stamped after the synthesis-non-blank check, but the prose those papers
produce when *no* tools succeed is exactly the fabrication described in
the refresh #3 report. Suppressing them is the correct behaviour.

**Acceptance:**
- Unit test: a synthesis with 5 sandbox safety rejects + 0 successes →
  returns null. Today this passes via `totalRejects >= 3`.
- New unit test: a synthesis with 0 safety rejects, 3 runtime errors,
  0 successes → returns null. Today this slips through; D3 catches it.
- A synthesis with 1 success → returns the narration (no regression).
- After D3 lands, the next 5 REVIEW_REPORT refreshes show ≥ 0 papers in
  the "every tool call failed yet Findings prose proceeds" cluster.

---

## D4 — Demote PUBLISHED → NEEDS_REVISION when panel n ∈ {2, 3}

**Effort:** ~1 hour. **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java:740-770`
  — extend the existing `got == 1` branch to handle `got == 2` and `got == 3`
  by stamping a callPath marker (no `return null` — the paper still
  publishes, just demoted).
- `src/main/java/com/emergentterrain/research/UniversityService.java:597-599`
  — read the new marker in the status precedence ladder. The
  `panel:n=2:degraded` and `panel:n=3:degraded` markers route to
  NEEDS_REVISION instead of PUBLISHED.
- `src/main/resources/application.yml` — add config flag
  `app.research.panel-degradation-demote.enabled: false` (default off; turn
  on after one refresh).

**Current behaviour:** when `got == 1` (single reviewer), the paper is
suppressed outright. When `got ∈ {2, 3}` (REVIEW_REPORT refresh #3 reports
10 × n=2 + 24 × n=3 events in this run, 35 total), the paper publishes
cleanly. The report names four Reject papers (P-417, P-419, P-428, P-431)
that carry Status=PUBLISHED despite degraded panels.

**Target behaviour:** when `got ∈ {2, 3}`, the paper still publishes but
status is demoted from PUBLISHED → NEEDS_REVISION. The peer score and
panel outcome persist; the paper appears in the per-paper table; readers
see "NEEDS_REVISION (panel n=2)" instead of "PUBLISHED".

**Why demote not suppress:** n=2 / n=3 panels do contribute *some* signal
(unlike n=1, where agreementScore is trivially 1.00). Demoting captures
the lower confidence without throwing away the work. The four named
Reject papers also fail at least one other validator; their final status
under D2 + D4 combined is "REJECTED + degraded-panel" — fine, the publish
path takes the strongest signal.

**Patch sketch:**

```java
// after the existing got == 1 → suppress branch (line 769):
if (got >= 2 && got < configuredSize && panelDegradationDemoteEnabled) {
    callPath.add("panel:n=" + got + ":degraded:demoted_to_needs_revision");
    log.info("ReviewPanelService: paperId={} panel n={} of configured {} — "
                    + "demoting to NEEDS_REVISION per Phase 12 D4.",
            draft.paperId(), got, configuredSize);
}
```

`UniversityService.publishPaper` then reads the marker:

```java
} else if (callPath != null
        && callPath.contains(":degraded:demoted_to_needs_revision")) {
    paperStatus = "NEEDS_REVISION";
}
```

Insert above the existing `critique:skip:incomplete_firstpass` clause at
line 597 so the marker dominates the `prof:error→fp` PUBLISHED_NO_PROFESSOR
clause.

**Acceptance:**
- A paper with n=3 of 4 reviewers and otherwise clean Findings publishes
  as NEEDS_REVISION (not PUBLISHED) when the flag is on.
- The same paper with the flag off publishes as PUBLISHED.
- A paper with n=4 of 4 publishes as PUBLISHED in both modes.
- A paper with n=1 still suppresses (the existing branch wins).
- A paper failing both n=3 *and* D2's numerical-consistency check ends up
  REJECTED (the stronger status takes precedence in the ladder).

---

## D5 — Truncation-guard pattern extension

**Effort:** ~1 hour. **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/research/UniversityService.java:1769-1785`
  — extend `endsWithSentenceTerminator` with a numeric-leak-into-next-sentence
  precheck.
- `src/test/java/com/emergentterrain/research/UniversityServiceTest.java`
  (or whichever truncation-guard test exists) — add three cases.

**Current behaviour:** `endsWithSentenceTerminator` (line 1769) checks the
last non-whitespace, non-closing-markup character is `. ! ? …`. P-411 and
P-418 in REVIEW_REPORT refresh #3 §7 both contain the string
`"The observed30,"` — a numeric leaked into the next sentence after a
token-budget cut. The trailing comma satisfies "not a terminator" but
only because the string contains a comma at the end; the *real* failure
mode is "the last sentence has a bare numeric attached to no unit and no
verb."

P-419's Hypothesis is truncated mid-sentence ("…matrix-yields-first
V_f ≈ 10–20%") — a different signature, but the same root cause
(token-budget cut). Today the truncation guard runs on Findings blocks +
Conclusion; it does NOT run on Hypothesis.

**Fix 1** — numeric-leak signature:

```java
private static final Pattern NUMERIC_LEAK_TAIL =
        Pattern.compile("(?i)\\b(?:observed|computed|measured|sampled)\\d+,?\\s*$");

static boolean endsWithSentenceTerminator(String text) {
    if (text == null) return true;
    // ... existing whitespace strip ...
    // NEW: if the trailing token is e.g. "observed30," the paper is truncated
    if (NUMERIC_LEAK_TAIL.matcher(text).find()) return false;
    // ... existing terminator check ...
}
```

**Fix 2** — guard Hypothesis too:

In `UniversityService.publishPaper:475-483`, the truncation guard runs on
`findingsBlocks` + `conclusion`. Extend it to also fail the paper when
`endsWithSentenceTerminator(hypothesis) == false`. This adds one line and
one new log key.

**Acceptance:**
- Unit test: "The observed30," → `endsWithSentenceTerminator == false`.
- Unit test: "The temperature was 350 K." → `endsWithSentenceTerminator == true`
  (no false-positive on numbers followed by a proper terminator).
- Unit test: "V_f ≈ 10–20%" with no trailing period → `false`.
- Integration test: a paper with Hypothesis truncated mid-sentence
  publishes as NEEDS_REVISION (today it publishes as PUBLISHED).

---

## D6 — TextbookBoundRegistry → ClosingProseValidator

**Effort:** ~3 hours. **Pre-condition:** none. **Independent of D1-D5.**

**Files added:**
- `src/main/java/com/emergentterrain/research/TextbookBoundRegistry.java`
  — reads `literature-corpus.yml` at startup, exposes
  `hasKnownBound(domain, topic)`.
- `src/main/java/com/emergentterrain/research/ClosingProseValidator.java`
  — scans Conclusion for the boilerplate sentence and flags as
  NEEDS_REVISION when `hasKnownBound` is true.
- `src/test/java/com/emergentterrain/research/ClosingProseValidatorTest.java`.

**Files touched:**
- `src/main/resources/literature-corpus.yml` — add optional
  `bound_known: true` field on the 15 non-sentinel entries (Mertens,
  Beer-Lambert, Stefan-Boltzmann, Doppler, Hall, Wien, Bragg, Hardy-Weinberg,
  Carnot, Van't Hoff, Arrhenius, …). The sentinel INTERDISCIPLINARY entries
  stay `bound_known: false` (their bridges are engine-constructed).
- `src/main/java/com/emergentterrain/research/UniversityService.java`
  publishPaper — call the new validator after the existing topic-specific
  check, demote to NEEDS_REVISION when it fires.
- `src/main/resources/application.yml` — add
  `app.research.closing-prose-validator.enabled: false` (default off).

**The bug:** REVIEW_REPORT refresh #3 §1 rec #5 names 5 papers (P-416,
P-422, P-423, P-427, P-432) that use the canned line "*…no prior
theoretical bound constrains the expected value in this region*" on
problems with textbook bounds. The line is harmless boilerplate on
genuinely-open problems and a quality-killer on textbook problems.
`literature-corpus.yml` already has 15 curated entries with a `closed_form`
field — the Phase 6 D1.2 corpus knows the bound exists. D6 just connects
the two.

**Pattern:**

```java
private static final Pattern NO_PRIOR_BOUND_BOILERPLATE =
        Pattern.compile(
                "(?i)no\\s+prior\\s+theoretical\\s+bound\\s+constrains\\s+the\\s+"
                        + "expected\\s+value(?:\\s+in\\s+this\\s+region)?");

public List<String> validate(String domain, String topic, String conclusion) {
    if (conclusion == null || conclusion.isBlank()) return List.of();
    if (!NO_PRIOR_BOUND_BOILERPLATE.matcher(conclusion).find()) return List.of();
    if (!registry.hasKnownBound(domain, topic)) return List.of();
    return List.of("Conclusion claims 'no prior theoretical bound' on a topic with "
            + "a known textbook bound (" + registry.referenceFor(domain, topic) + ").");
}
```

**Why a separate validator (not a PromptSanitizer pattern):** the boilerplate
is *semantically wrong* on textbook problems but *correct* on genuinely-open
problems (e.g. Collatz, twin-prime distribution beyond the proved range).
A blanket strip would silently invert the prose's meaning. The
domain-aware check is the right shape.

**Acceptance:**
- A Conclusion containing the boilerplate on a (MATHEMATICS, Mertens
  Partial Sums) topic → returns one issue. (Mertens has a known bound
  M(x) = O(x^{1/2+ε}).)
- The same boilerplate on a (MATHEMATICS, Twin Prime Clustering) topic
  → returns zero issues. (Twin-prime distribution is genuinely open beyond
  Hardy-Littlewood's conjecture.)
- Conclusion without the boilerplate → returns zero issues regardless of
  topic.
- The flag-off path is bit-identical to today.

---

## D7 — Additive LLM Call Path

**Effort:** ~1 hour. **Pre-condition:** none. **Independent of D1-D6.**

**Files touched:**
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — every `callPath.add(...)` that follows a budget-driven model fallback
  prepends the fallback marker instead of overwriting downstream stamps.
- `src/main/java/com/emergentterrain/service/PrimaryModelRegistry.java`
  (or wherever the budget gate fires) — surface the fallback as a
  call-path stamp the next stage can read.

**The bug:** REVIEW_REPORT refresh #3 §1 rec #8 reports the
`prof:error→fp` and `prof:*:all_tools_failed` tail markers are emitted by
the pre-debit budget gate (272 firings this run), but most degraded
papers don't carry the fallback marker — instead they show
`prof:openai/gpt-oss-120:ok` because a later retry succeeded after the
first model was dropped. The path tail shows the *final* model that
succeeded, hiding that the first model rejected the request silently on
budget grounds.

**Fix:** when the budget gate forces a model downgrade, the stamp is
*prepended* to the call path (not overwritten). The downstream success
marker still lands at the tail. Reviewers see both signals.

Today (simplified):

```
prof:openai/gpt-oss-120:ok
```

After D7:

```
prof:budget_fallback:from=qwen-235b → prof:openai/gpt-oss-120:ok
```

The call path is already a list join (line 463: `callPath.add(...)`), so
the change is mechanical — add the fallback marker at the call site that
performs the model drop, instead of letting the next-model success
overwrite the slot.

**Acceptance:**
- A paper whose first model was budget-rejected and second model
  succeeded carries both stamps in the persisted callPath, in budget →
  success order.
- A paper that never hit the budget gate carries only the success stamp
  (no fallback marker emitted).
- The next REVIEW_REPORT refresh's §7 "Pre-debit budget exceeded" row
  correlates 1:1 with papers carrying the new stamp.

**Why this matters:** Phase 12 doesn't *fix* the budget gate; D7 just makes
the gate's effect visible. Without D7 the existing 272 firings/run are
invisible in published markdown. With D7, the next REVIEW_REPORT can
correlate "papers that were salvaged after a budget rejection" with their
verdicts — a precondition for any Phase 13 work on model-routing quality.

---

## What Phase 12 deliberately does NOT change

- **Sandbox grammar template / executor.** Refresh #3 §7 reports 28
  REJECTED-by-safety-review errors with recurring grammar mistakes (object
  literals, array-literal returns, dot operators on numbers). Phase 11's
  "sandbox grammar in REJECTED responses" already addresses this. Phase 12
  doesn't relax or expand the safety review.
- **Cross-domain coupling-equation requirement (refresh #3 rec #4).**
  P-410's vdW-pressure → composite-stress mapping is unphysical; the report
  suggests requiring an explicit coupling equation in the Hypothesis for
  INTERDISCIPLINARY papers. This needs a prompt-template change and is
  Phase 13 material — Phase 12 stays away from prompt edits to keep the
  rollback surface small. D2's no-trace check does catch P-410 indirectly
  (it had zero successful tool calls), but the *physical* implausibility
  belongs to a future cross-domain validator.
- **Peer-review parse failures (refresh #3 §7, 39 events).** The reviewer
  model returns prose instead of JSON; the parse falls back to a degraded
  panel. D4 demotes the degraded panel's papers but does not fix the parse
  failure. A parse-failure recovery loop (retry, second-pass JSON
  extraction, or schema-pinned reviewer prompt) is Phase 13.
- **NULL_RESULT preamble template.** Phase 11 C5a's NULL_RESULT routing
  uses a single canned preamble. Phase 12 reuses it for D2's `downgrade`
  mode — no new prose templates are introduced.
- **literature-corpus.yml content.** D6 reads the existing entries.
  Adding new bound entries for new topics is corpus work, not phase-12 work.
- **Phase 10 / Phase 11 gates.** `all_tools_failed→suppressed`,
  `MANUAL_REVIEW→suppressed`, blank-synthesis suppression, citations
  validator — all remain. D2/D3/D4 are new gates alongside, not
  replacements.
- **Acceptance threshold.** Today a paper publishes as PUBLISHED with
  peer score ≥ 0.5. Phase 12 doesn't change the threshold. The acceptance
  rate moves from 8.8% to ~15% because *fewer fabricated papers* publish,
  not because the bar moves.

---

## Sequencing summary

```
D1 ──┐
     ├──→ D2 ──→ D3 ──→ D4
D5 ──┤
D6 ──┤
D7 ──┘
```

D1 ships first because it is mechanical, single-file, and zero-risk — and
because both papers it lifts (P-427, P-432) are the most embarrassing to
read.

D2 follows D1 (and only D1) because D1 changes the prose the D2 validator
reads. D3 and D4 are independent of D1/D2 but should land after D2 so the
"suppression" signal accumulates in one rollout window, not three.

D5, D6, D7 are independent of D1-D4 and can ship in any order, even in the
same PR as D1. Suggested PR shape:

| PR | Commits | Risk |
|----|---------|------|
| #1 | D1 (chain-of-thought) + D5 (truncation patterns) + D7 (call path) | Low |
| #2 | D2 (numerical-consistency promotion, warn-mode default) | Medium |
| #3 | D3 (zero-successes widening) + D4 (panel demotion) | Low |
| #4 | D6 (textbook-bound validator) | Low |
| —  | D2 mode flip to `downgrade` (config-only, no commit) | Medium |
| —  | D2 mode flip to `suppress` (config-only, no commit) | Medium |

PR #1 has no rollback concerns — every change either strips garbage from
prose, fails a paper that would otherwise publish broken, or adds an
audit-only marker. PR #2 lands in `warn` mode (paper still publishes as
REJECTED), exercises the new code paths at full coverage, then the
operator flips to `downgrade` / `suppress` after one REVIEW_REPORT refresh
shows the expected hit count. PR #3 and #4 are similarly low-risk because
their default flags are off.

---

## Acceptance gates

| Piece | Acceptance test |
|-------|-----------------|
| **D1** | Unit tests cover closed `<think>…</think>`, unclosed `<think>` to EOF, and the "Okay, let's tackle this query" sentence stripper. After D1 ships, no published Findings contains the literal token `<think>`. P-427 / P-432 worked-example replays produce blank Findings and the template-fallback branch fires. |
| **D2 (warn)** | Existing `NumericalConsistencyValidatorTest` cases still pass. New unit cases cover the P-405 deviation, P-417 derived-formula, P-419 empty-trace, and P-428 errored-call gaps. Default `mode: warn` keeps published-paper count unchanged from today. |
| **D2 (downgrade)** | After flip, 14 of the 20 papers harness-flagged in the May 2026 batch publish as NULL_RESULT instead of REJECTED. A clean paper publishes unchanged. |
| **D2 (suppress)** | After flip, the same 14 papers are absent from the published list; each carries `numeric_inconsistent→suppressed` in the operational log. The REVIEW_REPORT skill's "Gate-suppressed (not missing)" column reports the 14 IDs (mirroring the existing 407/412/413/424/433 treatment). |
| **D3** | Unit test: a synthesis with 0 safety rejects + 3 runtime errors + 0 successes returns null. A synthesis with 1 success returns the narration unchanged. Operational log shows `zero_successes→suppressed` stamps replacing today's `all_tools_failed→suppressed` for the runtime-error cases. |
| **D4** | A paper with n=3 of 4 reviewers and otherwise clean Findings publishes as NEEDS_REVISION (not PUBLISHED) when the flag is on. A paper with n=4 of 4 publishes as PUBLISHED. A paper with n=1 still suppresses. Flag-off path is bit-identical to today. |
| **D5** | Unit test: "The observed30," → `endsWithSentenceTerminator == false`. Unit test: "The temperature was 350 K." → `true`. Integration: a paper with truncated Hypothesis publishes as NEEDS_REVISION. |
| **D6** | A Conclusion with "no prior theoretical bound" boilerplate on a Mertens topic → returns one issue. The same on a twin-prime topic → returns zero issues. Flag-off path is bit-identical to today. |
| **D7** | A paper whose first model was budget-rejected and second model succeeded carries both stamps in the persisted callPath, in budget→success order. Operational-log correlation: papers carrying the new stamp match 1:1 with papers logging "Pre-debit budget exceeded". |

The phase is **complete** when:

- The next REVIEW_REPORT refresh after PR #4 + the `suppress` flip shows
  acceptance rate (Acceptable + Minor revision / Total published) ≥ 15%
  over a 20-paper window.
- No published paper carries `<think>` tokens in any prose section.
- No published paper has zero successful tool calls AND non-blank
  Findings prose.
- No published paper carries the "no prior theoretical bound" boilerplate
  on a topic whose `literature-corpus.yml` entry has `bound_known: true`.
- Every PUBLISHED paper's panel had n=4 reviewers (n=2 / n=3 publish only
  as NEEDS_REVISION; n=1 still suppresses).
- Every published Hypothesis ends in a sentence terminator (Hypothesis-side
  truncation guard is live).
- The REVIEW_REPORT.md "Gate-suppressed (not missing)" row covers every
  D-series suppression stamp the operational log emits.

The bar is *enforcement of existing signals*, not new signals. Phase 12 is
complete when the validators that already know how to identify a fabricated
paper are also the validators that prevent that paper from publishing.
