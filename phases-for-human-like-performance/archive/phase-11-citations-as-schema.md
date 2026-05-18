# Phase 11 — Citations-as-Schema (scoping)

**Scope:** Make numeric fabrication structurally unrepresentable in published
papers. Every numeric value in `findings` and `conclusion` prose must carry an
inline `[call_N]` marker that resolves to a real tool-call result. A parallel
`numeric_claims` array in the JSON output is validated against the persisted
tool-call trace before publish. Papers whose numbers cannot be traced to a
computation are routed to NULL_RESULT or suppressed.

**Status:** Scoping document. No code in this phase yet. Implementation lands
across five commits (C1–C5) plus this doc.

**Prerequisite:** Phase 10 shipped (commits `5d87a57` REVIEW_REPORT refresh #3
fixes + `f3f340d` PromptSanitizer bare-phrasing patterns). The current
architecture — `all_tools_failed→suppressed` gate, audit v3 with
non-sequitur exemplars, `MANUAL_REVIEW` quorum gate, sandbox grammar template
in REJECTED responses, PromptSanitizer leak scrubbing — is the baseline this
phase extends.

**Why now:** Three signals from the May 2026 corpus made this the next
high-leverage move:

1. **Gate fatigue is real.** Phase 10 added three suppression gates and audit
   v3 exemplars. They caught 4 of 14 papers in the 2026-05-15 01:35 ET refresh
   (P-380/381/384/387) and pulled acceptance from 0% → 5.9%. But the remaining
   ceiling is fabrication-of-numbers within published prose — 7 of 10 surviving
   papers had at least one number with no matching tool-call result. No
   additional gate catches *content-level* number fabrication; gates only catch
   structural conditions (all-tools-failed, single-reviewer panel, known
   non-sequitur topic).
2. **The pattern is consistent across domains.** P-374 (math), P-377 (math),
   P-379 (physics), P-385 (chemistry), P-386 (math), P-388 (physics), P-389
   (materials) all fabricate specific numeric values: "56 windows" when the
   tool ran 40, "9471 K" when the sweep ended at 6100 K, "ε=0.117 prose vs
   ε=0.139 tool", "max deviation 0.0229 at start=3602" (no tool call ever ran),
   "Ea=10 to 10 kJ/mol" from five failed calls. The failure mode is
   domain-agnostic and concentrated on the same surface: free-text prose
   citing a number that doesn't exist in any persisted tool result.
3. **The corpus's first Minor revision (P-378, Van't Hoff) is the existence
   proof.** All three sandbox calls succeeded and the prose's numbers match
   the tool output to 4 sig figs. The *recipe* — produce a number, cite the
   call that produced it, never name a number that wasn't computed — is the
   only path that has yielded an Acceptable-shaped paper in the new corpus.
   Phase 11 promotes that recipe from happy-path coincidence to the only
   representable shape.

**Goal:** Make the schema enforce honest provenance.

- **C-series** — five commits delivering tool-call IDs, JSON schema extension,
  the validator, gate integration, and rollout-mode flips. Sizing below.
- **Outside scope:** qualitative claim audit, cherry-picking detection,
  truncation handling. Those are addressed by audit v3 / panel review /
  separate refinements. Phase 11 is exclusively about numeric provenance.

**Why this is risky:**

- The schema changes the contract for **every** professor-stage synthesis. A
  validator bug rejects every paper; a too-loose validator catches nothing.
  Three rollout modes (`warn` / `downgrade` / `suppress`) are mandatory so a
  config flip is the rollback.
- The validator runs against PromptSanitizer's *output*, not the raw LLM
  response. If sanitization strips a citation marker (`[call_2]`), a valid
  number becomes uncited. C2's sanitizer audit must verify that PromptSanitizer
  patterns at `PromptSanitizer.java:45-191` are citation-marker-safe.
- C1 stamps stable IDs in `GroqPool.callWithTools` (line 536). Other pools'
  tool-call loops (NVIDIA, OpenRouter, Cerebras) must receive the same
  treatment or the cross-provider path produces traces without IDs and the
  validator rejects every paper from those pools.

---

## Architecture

```
                  Professor synthesis (Phase 10 baseline)
                  ──────────────────────────────────────
                  ┌──────────────────────┐
                  │   ResearchNarration  │
                  │   .maybeCritique...  │
                  └──────────┬───────────┘
                             │
       callPrimaryWithTools  │  (sys, user, tools, executor)
                             ▼
                  ┌──────────────────────┐
                  │     GroqPool (etc.)  │
                  │   runToolLoop        │
                  └──────────┬───────────┘
                             │
                  trace: List<ToolInvocation>
                             │
                             ▼
                  ┌──────────────────────┐
                  │   NarrationResult    │
                  │  + toolCallTrace     │
                  │  + typedCitations    │
                  └──────────┬───────────┘
                             │
                  Phase 11 additions
                  ──────────────────
                             ▼
                  ┌──────────────────────┐
                  │  CitationValidator   │     reads numericClaims + trace
                  │  - completeness      │     against the prose
                  │  - claim coverage    │
                  │  - trace conformance │
                  └──────────┬───────────┘
                             │
                  validation result stamped on callPath
                             │
                             ▼
                  ┌──────────────────────┐
                  │   audit v3 → panel   │     existing gates unchanged
                  │   PromptSanitizer    │
                  │   UniversityService  │
                  │   .publishPaper      │     consults mode flag:
                  └──────────────────────┘       warn  | downgrade | suppress
```

**The atomic unit:** `ToolInvocation` gains a `callId` field. It's stamped
once at executor-entry time as `"call_" + (1-based position in trace)`. The ID
is portable across pools (we don't reuse provider-specific IDs like Groq's
`tc.path("id")`) and is visible to the LLM through prefixed tool results
(`[call_N] <result>`). The same ID is what the LLM emits in prose markers and
what the validator looks up in the trace.

**The redundancy is intentional:** prose carries `[call_N]` markers (human
readers see them, LLM is forced to commit during generation) AND a parallel
`numeric_claims` array (machine-checkable, normalized). Each side catches a
different failure: missing inline markers indicate the LLM didn't actually
attribute; missing array entries indicate the LLM skipped the schema; trace
mismatches indicate the LLM cited a call that didn't produce the value.

---

## C1 — Stable tool-call IDs

**Effort:** ~1 hour. **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/llm/ToolInvocation.java:18-22` — add
  `callId` field.
- `src/main/java/com/emergentterrain/service/GroqPool.java:476-555` — stamp
  the ID inside the tool loop.
- Mirror in any other pool whose `callWithTools` reaches a tool-result
  collection point (audit before this PR — current count is GroqPool only as
  the production tool-calling path, but Phase 9 U3 opened tool calling to
  other providers).

**Data shape:**

```java
public record ToolInvocation(
    String functionName,
    String argsJson,
    String resultJson,
    int round,
    String callId          // NEW — "call_1", "call_2", ...
) {}
```

Stamp at trace-append time:

```java
String callId = "call_" + (trace.size() + 1);
trace.add(new ToolInvocation(fnName, fnArgs, result, round, callId));
```

**LLM-side visibility:** prefix the tool result content the LLM sees on its
next turn with `[call_N] `:

```java
// when adding the tool result back to the chat history
chatHistory.add(toolMessage("[" + callId + "] " + result));
```

This is what makes the LLM able to write `[call_2]` markers without a
separate prompt explanation: it sees the marker on every result it reads.

**Backward compatibility:** old `ToolInvocation` deserialization with no
`callId` field falls back to `null`. Validator treats null-`callId` traces as
"pre-Phase-11" and exits early in warn mode.

**Acceptance:**
- Every existing test that constructs `ToolInvocation` compiles after the
  new field is added (verify with a tracker or just `mvn compile`).
- A unit test asserts that after a 3-tool synthesis, the trace contains
  `call_1, call_2, call_3` in order.
- A persisted paper's `tool_call_trace` JSON blob shows the new field
  populated.

---

## C2 — NarrationResult.numericClaims + schema extension

**Effort:** ~2 hours. **Pre-condition:** C1.

**Files touched:**
- `src/main/java/com/emergentterrain/research/NarrationResult.java` — add
  `NumericClaim` nested record and `numericClaims` field.
- `src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java:140-202`
  (NARRATION_PRIMARY) — extend the schema block.
- Professor synthesis prompt template (same file, separate `register(...)`
  block) — add the inline-marker rule and the same schema extension.
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  `parseNarrationResult` (lines 1647-1737) — parse `numeric_claims` when
  present, default to empty list when absent.

**Data shape:**

```java
public record NumericClaim(
    double value,
    String units,           // "K", "kJ/mol", "MPa", "" for dimensionless
    String toolCallId,      // "call_2"
    String context,         // free text: "ln(Kc/Kc₀) at T=792 K"
    String sourceLocation   // "hypothesis" | "findings:1" | "conclusion"
) {}

// new field on NarrationResult, alongside typedCitations and toolCallTrace
List<NumericClaim> numericClaims
```

**Prompt extension** (added to NARRATION_PRIMARY and the professor-revision
template):

```
Every numeric value in `findings` or `conclusion` (any number with or without
units, except citation indices `[1]`, equation numbers `(eq. 3)`, and year
mentions `1884`) must be IMMEDIATELY followed by a citation marker `[call_N]`
referring to the tool call that produced or directly uses that value. Numbers
without citations will reject the paper.

For each `[call_N]` citation in your prose, add a corresponding entry to
`numeric_claims`. Example:

    "findings": {
      "1": "At T=792 K [call_2], ln(Kc/Kc₀) = 7.553 [call_2], matching Van't
            Hoff [call_2] to 4 significant figures."
    },
    "numeric_claims": [
      {"value": 792, "units": "K", "tool_call_id": "call_2",
       "context": "swept temperature", "source_location": "findings:1"},
      {"value": 7.553, "units": "", "tool_call_id": "call_2",
       "context": "ln(Kc/Kc₀) at T=792 K", "source_location": "findings:1"}
    ]

The `numeric_claims` array is checked against the tool trace. A value that
does not appear in its cited call's output rejects the paper.
```

**Persistence:** `numeric_claims` becomes a JSON blob column on the same
table that already persists `tool_call_trace`. Schema migration in C2 adds
the column with `DEFAULT NULL` so existing rows are unaffected.

**Acceptance:**
- New unit tests on `parseNarrationResult` cover: numeric_claims present and
  well-formed (parses correctly); absent (null/empty list); malformed
  (logged + skipped, paper still parses).
- A canned LLM response with three numeric claims round-trips through parse →
  persist → reload with all five fields intact.
- The professor synthesis prompt template, when shown to a real LLM in a
  scratch test, produces a JSON response with the new `numeric_claims` array
  ≥80% of the time across 10 synthetic topics. (Calibration target —
  tune the prompt if first run is <50%.)

---

## C3 — CitationValidator

**Effort:** ~4 hours. **Pre-condition:** C1, C2.

**Files added:**
- `src/main/java/com/emergentterrain/research/CitationValidator.java` (new
  class, Spring `@Service`).
- `src/test/java/com/emergentterrain/research/CitationValidatorTest.java`
  (new test class).

**API:**

```java
public record CitationValidationResult(
    int uncitedCount,
    int unmatchedClaimCount,
    int outOfToleranceCount,
    List<String> issues          // human-readable reasons, one per failure
) {
    public boolean hasFailures() {
        return uncitedCount + unmatchedClaimCount + outOfToleranceCount > 0;
    }
}

public CitationValidationResult validate(
    NarrationResult narration,
    List<ToolInvocation> trace);
```

**Three checks, in order:**

1. **Citation completeness.** Regex-extract every numeric token from
   `hypothesis`, every `findings` block value, and `conclusion`. Exclusion
   list: citation indices (`\[\d+\]` not preceded by `call_`), equation
   numbers (`\(eq\. ?\d+\)`), years 1700-2099, percentage values in
   qualitative phrasing (heuristic: numbers followed by `%` with no preceding
   technical unit). For each remaining number, require a `[call_N]` marker
   within 20 characters following. Uncited numbers → `issues +1`.

2. **Claim coverage.** Every prose-extracted number must have a matching entry
   in `numericClaims` (`Math.abs(prose - claim.value()) <= 0.005 * |claim.value()|
   + 1e-9`). Missing entries → `unmatchedClaimCount +1`.

3. **Trace conformance.** For each `NumericClaim`, look up `toolCallId` in
   the trace. Parse `resultJson` as a string (already-stringified tool
   output) and search for the claimed value with the same tolerance, allowing
   common formatting (`7.55`, `7.5500`, `7.553e+00`). Absent → `outOfToleranceCount
   +1` with issue text `"call_2 result does not contain value 9471 (claimed
   in findings:1)"`.

**Regex toolkit:** reuse `PromptSanitizer.java:45-191` patterns where they
apply (anchored numeric-with-units), plus a generic catch-all
`[-+]?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?` for unanchored cases. The patterns must
also be safe against citation markers — `[call_2]` must not be parsed as a
number-with-bracket. Verified by adding `[call_\d+]` to PromptSanitizer's
exclusion patterns first (mini-fix shipped in C2).

**Permitted derivations — explicitly deferred to C4b.** First-pass validator
treats derived values (`mean_of`, `ratio_of`) as failures. C4b adds the
allowlist after telemetry shows whether the LLM is producing them at scale.

**Acceptance:**
- Unit tests cover: clean paper (3 numbers, 3 citations, all in trace) →
  zero failures; uncited number → `uncitedCount > 0`; cited but no claim
  entry → `unmatchedClaimCount > 0`; cited claim with value not in trace
  call's result → `outOfToleranceCount > 0`; cited claim with formatting
  variation (`7.55` vs trace's `7.553`) → passes within tolerance.
- Replay test: run the validator over the 10 published papers from the
  2026-05-15 01:35 ET refresh (assuming they get re-synthesized with the
  new prompt). Expected: 7 papers show failures (P-374, P-377, P-379, P-385,
  P-386, P-388, P-389) and the named numbers are surfaced in `issues`.

---

## C4 — Gate integration, warn-only mode (Phase 11a rollout)

**Effort:** ~2 hours. **Pre-condition:** C3.

**Files touched:**
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — new validator call after professor synthesis (lines 405-442 area), before
  audit v3.
- `src/main/resources/application.yml` — add config flag
  `app.research.citationsAsSchema.mode: warn` (default).

**Insertion point** (pseudocode, slotted between current line 442 and the
audit-v3 / panel calls):

```java
CitationValidationResult cv = citationValidator.validate(narration, profTrace);
if (cv.hasFailures()) {
    callPath.add("citations:uncited=" + cv.uncitedCount()
        + ":unmatched=" + cv.unmatchedClaimCount()
        + ":out_of_tolerance=" + cv.outOfToleranceCount());
    log.warn("CitationValidator: paperId={} uncited={} unmatched={} out_of_tolerance={} issues={}",
            draft.paperId(), cv.uncitedCount(), cv.unmatchedClaimCount(),
            cv.outOfToleranceCount(), cv.issues());
} else {
    callPath.add("citations:ok");
}

// Mode flag — warn-only in C4
String mode = config.getCitationsAsSchemaMode();
if (!"warn".equals(mode) && cv.hasFailures()) {
    // C5 fills this branch — for now, warn mode is the only behaviour
    // and the validator never suppresses
}
```

**Telemetry (visible from C4 onward):**
- New histogram metrics: `citation_validator.uncited_numerics_per_paper`,
  `citation_validator.unmatched_claims_per_paper`,
  `citation_validator.out_of_tolerance_per_paper`.
- Per-paper `callPath` stamp: `citations:ok` | `citations:uncited=N:unmatched=M:out_of_tolerance=K`.
- A new section in the next `REVIEW_REPORT.md` refresh: "Citation validator
  findings" — counts and example issues per paper.

**Acceptance:**
- After C4 lands, at least two `/loop` refreshes (~30 minutes) produce
  enough papers to populate the histograms.
- The next REVIEW_REPORT refresh shows the new section with per-paper counts.
- No regression in `PUBLISHED rev:*:ok` rate (warn mode never suppresses).
- The acceptance gate is observational, not behavioral — C4 is "stand up
  the validator and let it watch".

---

## C5 — Rollout-mode flips (downgrade → suppress)

**Effort:** ~1 hour + observation time. **Pre-condition:** C4 telemetry shows
the LLM adopts the new format with <20% false-positive rate on clean papers.

**Two flips, gated by config:**

### C5a — `downgrade` mode

`app.research.citationsAsSchema.mode: downgrade`.

When `unmatchedClaimCount > 0` OR `outOfToleranceCount > 0`, the paper is
routed to `NULL_RESULT` with a generated honest preamble:

```
NULL_RESULT — Citation validator flagged {N} numeric values in this draft
that could not be traced to tool-call output. The hypothesis remains
untested in this submission. Specifically: {issues[0..3]}.
```

`uncitedCount` alone (no schema/trace mismatch — just missing markers) is a
softer signal: at this stage it still publishes but stamps a `MANUAL_REVIEW`
flag.

### C5b — `suppress` mode

`app.research.citationsAsSchema.mode: suppress`.

Same checks as C5a, but instead of NULL_RESULT routing, the paper is
suppressed entirely (mirroring Phase 10's `all_tools_failed→suppressed`
mechanic). `callPath` adds `prof:<model>:citations_failed→suppressed`.

`uncitedCount` above a threshold (suggested initial value: 3) also triggers
suppression.

**Phasing recommendation:**
- Land C5a first.
- Observe one REVIEW_REPORT refresh (15 min) in `downgrade` mode.
- If NULL_RESULT proportion looks correct (matches the 7-of-10 prediction
  from C3 replay), flip to C5b.
- Keep `warn` available as a rollback target.

**Acceptance:**
- In `downgrade` mode, a paper with a known fabricated number (replay one
  of P-379's tool traces against a known-good prose template, then mutate to
  add "9471 K [call_2]") publishes as NULL_RESULT with the honest preamble.
- In `suppress` mode, the same paper is absent from the published-papers
  table; `callPath` contains the suppression marker.
- A clean paper (no validator issues) publishes identically in all three
  modes.

---

## C4b — Permitted derivations (optional, post-C5)

**Effort:** ~3 hours. **Pre-condition:** C5 telemetry shows the LLM
producing derived values (`mean_of`, `ratio_of`, `delta_of`) and getting
rejected for legitimate composition.

If the May 2026 corpus's prose pattern is representative, derivations are
rare enough to skip in the initial rollout. C4b is the escape hatch if real
papers need it.

**Mechanism:** extend `NumericClaim` with an optional `derivation` field:

```java
public record NumericClaim(
    double value,
    String units,
    String toolCallId,      // may be null when derivation is set
    String derivation,      // "mean_of:call_2,call_3,call_4" — optional
    String context,
    String sourceLocation
) {}
```

Validator's trace-conformance check, when `derivation` is non-null:
1. Parse the derivation expression (small allowlist: `mean_of`, `median_of`,
   `min_of`, `max_of`, `ratio_of`, `delta_of`).
2. Resolve every operand to a value in the trace.
3. Recompute server-side.
4. Accept if result matches `value` within tolerance.

Initial allowlist is intentionally short. Multiplication, exponentiation, and
custom expressions are out — those are what the sandbox is for, and the LLM
should call the sandbox rather than encode the math in `numeric_claims`.

**Acceptance:** a unit test covers each derivation operator with a synthetic
trace; an invalid operator (`integral_of:...`) is rejected with a clear error
message.

---

## What Phase 11 deliberately does NOT change

- **PromptSanitizer.** Leak scrubbing remains. Phase 11 only adds an
  exclusion pattern for `[call_\d+]` markers so they survive sanitization.
- **Audit v3.** The `physical_mechanism` exemplars, `threshold_drift`
  dimension, and `boundary_answer` checks all stay. CitationValidator runs
  *before* audit v3 so the audit sees the same prose either way.
- **Panel review.** Multi-reviewer grading, omitted-relevant-tool-call
  detection, cherry-picked-evidence flagging — all unchanged. The new
  `numericClaims` field is not exposed to reviewers in this phase.
- **Phase 10 suppression gates.** `all_tools_failed→suppressed`,
  `MANUAL_REVIEW→suppressed`, blank-synthesis suppression all remain. C5's
  `citations_failed→suppressed` is a new sibling, not a replacement.
- **NULL_RESULT classification.** The existing classifier logic stays. C5a
  routes through the same code path that produces today's NULL_RESULT papers;
  only the preamble text and trigger condition are new.
- **Tool definitions.** `run_param_sweep`, `run_predicate_scan`,
  `run_sandboxed_code`, `evaluate_symbolic`, `run_gpu_eval` — all unchanged.
  The validator reads their existing result strings.
- **Publish pipeline (`UniversityService.publishPaper`).** Sanitization,
  citation-aware scoring, findings join, persistence — all stay. C4-C5 hook
  in upstream at the narration service, not at publish.

---

## Sequencing summary

```
C1 ────→ C2 ────→ C3 ────→ C4 ────→ C5a ────→ C5b
                                                    ╲
                                                     C4b (optional)
```

C1 unlocks C2 (the `callId` field that `numericClaims.toolCallId` references).
C2 unlocks C3 (the validator needs the field to validate against).
C3 unlocks C4 (validator must exist before gate integration).
C4 unlocks C5 (telemetry must show the LLM adopts the format before flipping
to enforcement).
C4b is a follow-on only if telemetry shows legitimate derivations are getting
rejected.

C1–C2 can land in a single PR. C3 should land separately so its unit tests
get a clean review. C4 lands with the config flag defaulting to `warn`. C5a
and C5b are config-flag flips, not code changes — each can be a separate
deployment without a new commit (or, equivalently, one commit per default-flip
if the operator prefers an audit trail).

---

## Acceptance gates

| Piece | Acceptance test |
|-------|-----------------|
| **C1** | Trace from a 3-tool synthesis contains `call_1, call_2, call_3` in order. Persisted `tool_call_trace` JSON shows the new field. All existing tests compile. |
| **C2** | `parseNarrationResult` round-trips `numeric_claims` correctly; missing or malformed array is logged and skipped without breaking the paper. The narration prompt produces JSON containing `numeric_claims` ≥80% of the time on 10 synthetic topics. |
| **C3** | Unit tests cover all three failure modes plus the tolerance edge case. Replay over the 2026-05-15 01:35 ET refresh's 10 published papers (re-synthesized under the new prompt) flags 7 specific papers and surfaces the named fabricated values in `issues`. |
| **C4** | Two `/loop` refreshes produce histogram data. REVIEW_REPORT.md refresh shows a new "Citation validator findings" section. Zero regression in `PUBLISHED rev:*:ok` rate (warn mode never suppresses). |
| **C5a** | A paper with a deliberately-fabricated `[call_2]` citation (value absent from call_2's result) publishes as NULL_RESULT with the honest preamble naming the bad value. A clean paper publishes unchanged. |
| **C5b** | Same paper as C5a is absent from the published-papers list. `callPath` contains `prof:<model>:citations_failed→suppressed`. |
| **C4b** *(optional)* | `numeric_claims` entry with `"derivation": "mean_of:call_2,call_3"` validates when the computed mean matches `value` within tolerance, rejects when it doesn't. |

The phase is **complete** when:

- A REVIEW_REPORT refresh after C5b ships shows ≥3 successive papers
  with `citations:ok` in `callPath` AND zero papers with fabricated numbers
  in the worst-papers list.
- Acceptance rate (Acceptable + Minor revision / total) is above 20% across
  a 20-paper window, with the citation validator running in `suppress` mode.
- The next time a number appears in a published paper's Findings, it can be
  traced — bit-for-bit, with tolerance for rounding — to a specific tool call
  in the persisted trace, without exception.

That last bullet is the load-bearing definition of "human-quality numeric
honesty": every published number is either an output of an actual computation
or a transparent derivation from one. Phase 11 is complete when the
representational shape of a paper guarantees this property, rather than
relying on after-the-fact auditing to catch violations.
