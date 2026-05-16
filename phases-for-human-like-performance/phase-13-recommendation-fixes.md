# Phase 13 — Recommendation Fixes (scoping)

**Scope:** Six concrete fixes drawn from REVIEW_REPORT refresh #1
(post-rotation, 2026-05-15 18:30, papers 434 – 471) and from a
panel-recommendation pattern audit across the same corpus's 289 reviewer
comments. Each item is tied to a specific Recommendation entry in the
report's §1 or to a recurring panel-reviewer ask, and to a specific
file and line range in the code. Total work is modest (~9 hours across
six pieces) but every item closes a *named* failure mode currently
visible in the corpus or in the running process — not a hypothetical
quality lever.

**Status:** Scoping document. No code in this phase yet. Implementation
lands across six commits (E1–E6) plus this doc.

**Prerequisite:** Phase 12 shipped (4 commits `363b966 → 9b88c66`). The
review skill confirms D3's `zero_successes→suppressed` gate, D7's
additive call-path, D4's panel-degradation demotion, D5's truncation
patterns, and D6's TextbookBoundRegistry are all visibly active. Phase
13 fills the gaps that the same refresh surfaced.

**Why now:** Phase 12 cleared the fabrication baseline; the residual
failures the refresh names are mostly mechanical:

1. The `SimulationGate` paused the room for ~85 minutes because Groq hit
   95% daily, even though three other vendors had headroom (E1).
2. `rev:parse_failed→fp` is the dominant residual failure (9 of 23 =
   39% of published papers), and the recovery path silently drops the
   revision content (E2).
3. Three RLC papers carry an identical Q-factor label swap from the
   same prose template (E3).
4. Three papers cut at a `### Computation 2 — …` header with only the
   equation line as body (E4).
5. One paper carries the literal `X` placeholder in `87.65% ± X%` —
   one sanitizer pattern would catch the class (E5).
6. **NEW from panel-recommendation audit:** ~38 panel comments across
   ~18 of the 23 published papers ask for "uncertainty / error bounds /
   sensitivity analysis." Bots almost never put ± uncertainties on
   reported values even when the engine computes σ over a sweep. The
   narrator drops them. One template clause + one engine-output
   passthrough would close the most-repeated panel ask in the corpus (E6).

Each fix is independent, narrow, and acceptance-testable. Combined
projection: acceptance rate moves from this refresh's 26.1% to ~50% on
the same corpus shape (assumes E2 + E3 + E4 lift 7 papers from
Reject/Major to Minor-or-better; E6 reduces the recommendations-load on
otherwise-clean papers but doesn't directly shift verdicts).

**Why this is risky:**

- **E1 changes a load-bearing safety signal.** Today the
  `SimulationGate` pauses *too eagerly*; flipping to `coolestSpend`
  semantics could pause *too leniently* and let the simulation drive
  past every provider's daily cap if the rolling-window accountants
  drift apart. Mitigation: keep the `pauseThresholdPct=95` and
  `resumeThresholdPct=80` envelope unchanged; only the across-provider
  aggregation changes.
- **E2 retries the revision stage.** A naive retry doubles the
  rev-stage spend on every parse failure. Mitigation: bounded retry
  (1 attempt), and the lenient parser passes most cases without an
  LLM round-trip.
- **E3 is a prompt-template edit.** The Q-factor label fix is one
  string change but the template runs on every RLC paper. Verify the
  fix doesn't regress the n=4 panels' validation on the four prior
  RLC papers (P-438, P-442, P-458 + any pre-rotation entries).
- **E6 changes the narration prompt for every paper.** The
  uncertainty-disclosure slot is a new required field in the
  NARRATION_PRIMARY template. A flag-gated rollout (`uncertainty-
  disclosure.enabled` default off) lets the operator observe one
  REVIEW_REPORT refresh before enforcing.

---

## Architecture

```
                  Existing pipeline (post-Phase-12 baseline)
                  ─────────────────────────────────────────
  SimulationGate.isOpen()      ← E1 changes aggregation here
        │
        ▼
  PromptTemplateRegistry       ← E6 adds Uncertainty slot to NARRATION_PRIMARY
        │                         (and E3 corrects RLC Q-factor labels)
        ▼
  professor synthesis ─▶ student revision ─▶ panel
                                  │
                                  ▼
                          parseNarrationResult
                          (rev:parse_failed→fp)  ← E2 inserts retry + lenient parser

  MathPhysicsEngine.compute()  ← E6 also passes stddev/range through
        │                         ResearchResult so the narrator sees it
        ▼

  publishPaper
        │
        ├─ truncation guard       ← E4 extends to "header-only block"
        ├─ topic-specific check
        ├─ uncertainty-disclosure ← E6 validator (NEW, flag-gated)
        ├─ numerical-consistency
        ├─ PromptSanitizer        ← E5 adds X-placeholder pattern
        └─ prose template (RLC)   ← E3 corrects Q-factor label parens
```

**The atomic unit:** all five fixes touch existing code paths. No new
services, no new validators, no new schemas. The TextbookBoundRegistry
(Phase 12 D6) and CitationValidator (Phase 11) carry forward unchanged.

---

## E1 — SimulationGate fallback-aware aggregation

**Effort:** ~1 hour. **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/llm/SimulationGate.java:111-127`
  (`hottestSpend()` method).
- `src/test/java/com/emergentterrain/llm/SimulationGateTest.java`
  (existing — verify, extend).
- `src/main/resources/application.yml:481+` — optional, document the new
  semantics in the existing comment block.

**The bug:** REVIEW_REPORT refresh #1 §1 rec #9 names the failure:
Groq's daily quota crossed 95% (257,446 / 270,000 tokens) and the gate
closed the *entire simulation*, even though `tool-calling.providers:
"groq,nvidia,openrouter,cerebras"` (`application.yml:220`) and
`review-panel.providers: "groq,nvidia,openrouter,cerebras"`
(`application.yml:434`) both wire the pipeline to three other vendors
that had budget headroom.

The reactive `router.primaryFullyExhausted()` check at
`SimulationGate.java:73` already encodes the correct semantics ("every
primary model is TPD-blocked"). The pre-emptive `hottestSpend() >= 95%`
check at line 96 should mirror it — pause only when **every**
budget-tracked provider crosses the pause threshold, not when any one
does.

**Fix:**

```java
// Replace hottestSpend() with coolestSpend() — the min-pct across
// providers — so one provider exhausting doesn't pause the simulation
// if others have headroom. Mirror the primaryFullyExhausted() reactive
// signal which already encodes "every model is blocked".
private CoolestSpend coolestSpend() {
    double minPct = Double.POSITIVE_INFINITY;
    String which  = "(none)";
    String hottest = "(none)";
    double hottestPct = 0;
    for (var entry : props.getProviders().entrySet()) {
        String name = entry.getKey();
        TokenBudgetProperties.Provider p = entry.getValue();
        double pct = Double.POSITIVE_INFINITY;
        if (p.getDailyLimit() > 0) {
            pct = Math.min(pct,
                    100.0 * accountant.dailyTotal(name) / p.getDailyLimit());
        }
        if (p.getMinuteLimit() > 0) {
            pct = Math.min(pct,
                    100.0 * accountant.minuteTotal(name) / p.getMinuteLimit());
        }
        if (pct < minPct) { minPct = pct; which = name; }
        if (pct > hottestPct) { hottestPct = pct; hottest = name; }
    }
    return new CoolestSpend(which, minPct, hottest, hottestPct);
}
```

The `lastReason` string keeps reporting the *hottest* provider for
operator visibility (so the log line still names Groq as the binding
constraint), but the **gate-close decision** uses the coolest:

```java
if (coolest.minPct >= props.getPauseThresholdPct()) {
    closed.set(true);
    lastReason = String.format(
        "paused: every provider >= %d%% (hottest=%s @ %.0f%%, coolest=%s @ %.0f%%)",
        props.getPauseThresholdPct(),
        coolest.hottestProvider, coolest.hottestPct,
        coolest.coolestProvider, coolest.minPct);
    return false;
}
```

**Why this is safe:** the reactive `primaryFullyExhausted()` check (line
73) already pauses the simulation when every primary model is blocked
— so even if the coolest-pct check is wrong, the reactive backstop
fires. The pre-emptive check is now strictly a tighter pre-warning
within the same envelope.

**Acceptance:**
- A unit test where Groq is at 95% but Ollama is at 0% (unmetered, no
  daily limit) returns `isOpen() == true`. Today this returns false.
- A unit test where every configured provider is at 95% returns
  `isOpen() == false`.
- A unit test where Groq is at 95% and NVIDIA (newly added to the
  config) is at 30% returns `isOpen() == true`.
- The `lastReason` string still names Groq as the binding hottest
  provider when one provider is hot, so operator log lines look the
  same shape.
- Existing `SimulationGateTest` cases pass without modification.

---

## E2 — rev:parse_failed→fp recovery

**Effort:** ~3 hours. **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — `parseNarrationResult` (around line 1647) and the revision-call
  block (around line 480).
- New helper class
  `src/main/java/com/emergentterrain/research/LenientJsonParser.java`
  — pre-parser fixes that try to repair common malformed-JSON cases
  before delegating to Jackson.
- `src/test/java/com/emergentterrain/research/LenientJsonParserTest.java`.

**The bug:** REVIEW_REPORT refresh #1 §1 rec #3 reports
`rev:parse_failed→fp` fires on 9 of 23 published papers (39%). The
student-revision stage's JSON response fails Jackson's strict parser,
the path falls back to first-pass narration (which doesn't carry the
prof stage's numerics), and the paper publishes as NEEDS_REVISION with
substandard prose. Median peer score 0.27 (parse-failed) vs 0.42 (clean
revision).

The current code is in `ResearchNarrationService.java`:

```java
NarrationResult revParsed = parseNarrationResult(revised.toString());
boolean revHasContent = revParsed.hypothesis()  != null
                     || revParsed.findings()    != null
                     || revParsed.conclusion()  != null
                     // ...
if (revHasContent) {
    // merge revision with firstPass and continue
} else {
    callPath.add("rev:parse_failed→fp");  // ← 39% of papers land here
}
```

**Fix — two layers:**

### E2a — Lenient JSON pre-parser

Before calling Jackson, run the response through a small allowlist of
fix-up rules:

```java
public static String repair(String raw) {
    String s = raw;
    // 1. Strip code fences (```json … ```).
    s = s.replaceAll("(?s)^\\s*```(?:json)?\\s*", "")
         .replaceAll("\\s*```\\s*$", "");
    // 2. Trim leading/trailing prose before/after the outermost JSON object.
    int firstBrace = s.indexOf('{');
    int lastBrace  = s.lastIndexOf('}');
    if (firstBrace > 0 && lastBrace > firstBrace) {
        s = s.substring(firstBrace, lastBrace + 1);
    }
    // 3. Drop trailing commas before } or ].
    s = s.replaceAll(",(\\s*[}\\]])", "$1");
    // 4. Close any unclosed string at EOF by counting unescaped quotes.
    if (countUnescapedQuotes(s) % 2 == 1) s = s + "\"";
    // 5. Balance braces by appending `}` until the count matches.
    s = balanceBraces(s);
    return s;
}
```

`parseNarrationResult` calls `LenientJsonParser.repair(raw)` once before
Jackson; if Jackson still fails after the repair, fall through to
firstPass as today.

The fixes catch the common failure modes observed in the corpus:
code-fence wrappers, mid-response truncation, missing closing braces,
trailing commas, dangling open strings.

### E2b — Stamp the recovery on the call path

When `LenientJsonParser.repair` produces a different string from its
input AND the parse subsequently succeeds, stamp
`rev:parse_recovered:from=lenient` on the call path. Operators can
correlate the new stamp against published paper quality to validate the
recovery isn't laundering bad output.

```java
String maybeRepaired = LenientJsonParser.repair(revised.toString());
boolean repaired = !maybeRepaired.equals(revised.toString());
NarrationResult revParsed = parseNarrationResult(maybeRepaired);
if (revParsed.hypothesis() != null && repaired) {
    callPath.add("rev:parse_recovered:from=lenient");
}
```

**Acceptance:**
- Replay over the 9 papers in this refresh that carry
  `rev:parse_failed→fp`: at least 5 of them parse cleanly under the
  lenient pre-parser. Verifies the heuristics catch real corpus
  signatures, not just synthetic test cases.
- Unit tests cover: code-fence wrapper, trailing comma, missing closing
  brace, dangling open string, prose preamble before `{`. Each repair
  case asserts the structural fields parse correctly after repair.
- Clean JSON (no repair needed) passes through unchanged — verified
  by byte equality of `repair(input) == input` on a known-good
  response.
- No `rev:parse_recovered` stamp is emitted when the input was already
  valid JSON.

**Open question for E2:** does a bounded LLM-retry on persistent parse
failure also make sense? The phase-wishlist P3 (schema-pinned reviewer
prompts) covers that for the reviewer stage; the same shape could
extend to the revision stage. Treat as E2-followon, not initial scope.

---

## E3 — Q-factor label template fix

**Effort:** ~30 minutes. **Pre-condition:** none.

**Files touched:**
- The prose template emitting the RLC Q-factor rows (location TBD;
  most likely `PromptTemplateRegistry.java` or a topic-specific
  template constant in `MathPhysicsEngine.java`). Audit with
  `grep -rn 'underdamped\|overdamped\|Q=0.13\|Q=5.00'` before the PR.
- `src/test/java/com/emergentterrain/research/MathPhysicsEngineTest.java`
  — add a regression test that the RLC compute output's
  Q-factor-with-label rows have correct (under/crit/over) parens.

**The bug:** REVIEW_REPORT refresh #1 §1 rec #4 names three papers
(P-438, P-442, P-458) that all carry the identical mis-labelled
Q-factor rows in Findings:

```
Underdamped: Q=0.25(over)        ← parenthetical says "over"
Critical:    Q=0.50(crit)
Overdamped:  Q=0.13(under)       ← parenthetical says "under"
```

The parentheticals are swapped relative to the leading noun. Q=0.13 is
the *overdamped* case (lower Q → more damping → no oscillation), but
the row labels it "(under)". Q=5.00 is *underdamped*, not "(over)".

The labels appear to be emitted by a prose template — the same byte
sequence shows up in three different papers from three different
authors with three different L/C parameters, so it's not LLM-generated.

**Fix:** correct the parenthetical hints OR drop them entirely. The
leading noun ("Underdamped:", "Critical:", "Overdamped:") is
self-explanatory; the parenthetical adds nothing and only adds a
failure surface.

**Two options:**

1. **Drop parens:** simplest. The leading noun is unambiguous.
2. **Correct parens:** if the parens are there for accessibility (some
   downstream consumer parses them), correct the assignments:
   `Underdamped: Q=5.00(under), Critical: Q=0.50(crit), Overdamped: Q=0.13(over)`.

Recommend Option 1 — parens add complexity without payoff.

**Acceptance:**
- After E3 ships, no published RLC paper carries the mis-labelled
  parenthetical pattern.
- A regression test fires on the engine's compute output: if a future
  template change reintroduces the parens, the test catches the swap
  before the next REVIEW_REPORT refresh.
- P-438, P-442, P-458 would all re-render with corrected (or absent)
  parens. Each would shift from Reject to Minor revision because the
  underlying math is right.

---

## E4 — Truncation guard: header-only block

**Effort:** ~1 hour. **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/research/UniversityService.java`
  — `endsWithSentenceTerminator` (line 1769) AND `firstTruncatedBlock`
  (line 1850); add a new check for "block has only header + equation
  line, no prose body".
- `src/test/java/com/emergentterrain/research/PublishPaperTest.java`
  — three new cases for the header-only signature.

**The bug:** REVIEW_REPORT refresh #1 §1 rec #5 names three papers
(P-453, P-466, P-468) where Findings contains a `### Computation 2 —
…` header followed by `**Equation:** f(x) = …` and then the section
ends. The current `endsWithSentenceTerminator` check passes because the
equation line ends with `)` or `.` — there IS a terminator, just no
prose body discussing the result.

The signature:

```markdown
### Computation 2 — Carnot Efficiency

**Equation:** η_Carnot = 1 − T_c/T_h   (Carnot reversible-engine efficiency)
                                                                ← block ends here
```

`firstTruncatedBlock` walks each block and returns the index of the
first one failing `endsWithSentenceTerminator`. With the equation line
present and well-terminated, every block passes.

**Fix:** extend `firstTruncatedBlock` to also flag a block whose body
(after stripping the equation line) has fewer than `MIN_PROSE_TOKENS`
tokens — e.g. 10 words.

```java
private static final int MIN_PROSE_TOKENS_PER_BLOCK = 10;

static Integer firstTruncatedBlock(Map<Integer, String> blocks) {
    if (blocks == null || blocks.isEmpty()) return null;
    for (var e : new TreeMap<>(blocks).entrySet()) {
        String body = e.getValue();
        if (!endsWithSentenceTerminator(body)) return e.getKey();
        // Phase 13 E4 — also flag header-only blocks where the body is
        // just the equation line. Strip the equation row and count
        // remaining word tokens.
        String prose = body.replaceAll("(?m)^\\*\\*Equation:\\*\\*.*$", "")
                           .replaceAll("(?m)^###.*$", "")
                           .trim();
        long tokens = prose.split("\\s+").length;
        if (prose.isBlank() || tokens < MIN_PROSE_TOKENS_PER_BLOCK) {
            return e.getKey();
        }
    }
    return null;
}
```

**Acceptance:**
- Unit test: block containing only `### Computation 2 — Topic` +
  `**Equation:** f = …` → `firstTruncatedBlock` returns the block
  index. Today returns null.
- Unit test: block with `**Equation:**` row + 2 sentences of prose
  discussing the result → passes (returns null).
- Integration test in PublishPaperTest: a paper with a header-only
  Computation 2 block publishes as NEEDS_REVISION (not PUBLISHED) when
  truncation guard is on.
- After E4, P-453 / P-466 / P-468 would all route to NEEDS_REVISION
  cleanly — currently P-453 is mis-classified as PUBLISHED.

---

## E5 — Sanitizer: literal `X` placeholder

**Effort:** ~30 minutes. **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/research/PromptSanitizer.java`
  — add a new pattern + wire it into `sanitize()`.
- `src/test/java/com/emergentterrain/research/PromptSanitizerTest.java`
  — three test cases.

**The bug:** REVIEW_REPORT refresh #1 §1 rec #6 names P-a328-467 (Van
der Waals Crossover), whose Findings closes with:

```
The maximum deviation observed across the pressure range of 1 to 100
atm was 87.65% ± X%.
```

Literal `X` placeholder where the uncertainty value should have been
substituted. The LLM emitted the template marker verbatim. The
sanitizer's existing patterns don't catch single-character placeholders
because they target named harness-leak phrasings ("starting position",
"scale factor", etc.).

**Fix:** add a pattern to `PromptSanitizer`:

```java
/**
 * Phase 13 E5 — literal X placeholder for an unsubstituted uncertainty
 * value, e.g. "87.65% ± X%". The closing-prose template's ± slot uses
 * `X` as a placeholder; when the renderer fails to substitute, the
 * literal "X" lands in the published prose. Strip the bare `± X%`
 * suffix; the value before it is the recovered measurement.
 */
private static final Pattern UNSUBSTITUTED_UNCERTAINTY_X = Pattern.compile(
        "\\s*±\\s*X\\s*%?", Pattern.CASE_INSENSITIVE);

// Wire into sanitize() after the chain-of-thought strip:
cleaned = UNSUBSTITUTED_UNCERTAINTY_X.matcher(cleaned).replaceAll("");
```

The replacement is empty — the "± X%" tail is stripped, leaving e.g.
`The maximum deviation observed … was 87.65%.` (the period from the
sentence terminator survives).

**Acceptance:**
- Unit test: `"87.65% ± X%"` → `"87.65%"`.
- Unit test: `"87.65% ± 2.3%"` (real value substituted) → unchanged.
- Unit test: `"The uncertainty was X."` (X in unrelated context) →
  unchanged (the pattern requires `± X` prefix).
- After E5, P-467 re-renders with the placeholder stripped. Verdict
  doesn't change (still Reject due to mid-sentence truncation
  upstream) but the prose is cleaner.

---

## E6 — Uncertainty-disclosure requirement

**Effort:** ~2 hours. **Pre-condition:** none. Independent of E1–E5.

**The bug:** A panel-recommendation pattern audit across the 23
published papers in this refresh found ~38 reviewer comments asking
for some form of uncertainty/error/sensitivity quantification —
appearing on ~18 of the 23 papers (~80% of the corpus). Worked examples:

- P-a328-438 panel: *"Include a proper error analysis (tolerance of L,
  C, and R, measurement noise, ESR) to substantiate the claim that
  observed deviations are within expected limits."*
- P-a328-442 panel: *"Include error bounds or sensitivity analysis for
  component tolerances. … Explicitly compute and report uncertainty
  bounds for all measured and derived quantities, especially R_crit
  and Q-factor."*
- P-a328-440 panel: *"Include sensitivity analyses and controls to
  strengthen the methodology and increase the rigor of the study."*
- P-a328-443 panel: *"Proper error propagation for the 4.04%
  improvement claim. … Authors should derive the relationship through
  first-principles analysis or empirical fitting with error bounds."*

The root cause: the narrator template has no `**Uncertainty:**` slot
and the engine's `ResearchResult` (`MathPhysicsEngine.java:27-35`) has
no dedicated stddev / range field, so even when the sweep produces
variance information (some compute branches do — e.g. Mertens, Bragg,
Hall-Petch), the data is stringified into prose and the narrator drops
it. The corpus's two highest-quality NULL_RESULTS (P-461 Pendulum, P-455
Mertens) report exact closed-form recoveries — exactly the case where
"uncertainty = none, exact closed form" should be stated explicitly but
the prose stays silent.

**Files touched:**
- `src/main/java/com/emergentterrain/research/MathPhysicsEngine.java:27-35`
  — extend `ResearchResult` with two optional fields:
  `Double uncertaintyStddev` (null when not applicable), `String
  uncertaintyKind` ("stddev" | "range" | "exact_closed_form" | "n/a").
  Backward-compat constructors default both to null.
- `src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java`
  (around line 146, the NARRATION_PRIMARY register call) — extend the
  JSON schema block with a required `"uncertainty": "<string>"` slot.
  Acceptable values: a `± N` numeric clause, the literal string
  `"exact closed form"`, or `"not quantified"` with a one-sentence
  reason. The prose findings template gains an instruction:
  *"For each headline numeric, state its uncertainty inline using
  `±` syntax OR mark as 'exact closed form' OR explain why none is
  quantified."*
- `src/main/java/com/emergentterrain/research/NarrationResult.java`
  — add `String uncertainty` field; parser extracts it from the JSON
  response.
- `src/main/java/com/emergentterrain/research/UncertaintyDisclosureValidator.java`
  (NEW) — `validate(findings)` flags Findings where the primary
  numeric claim lacks any uncertainty indicator. Pattern: extract every
  unit-anchored numeric (reuse `SALIENT_INTEGER_WITH_UNIT` from D2d)
  and require that within 80 characters of it appears one of: `±`,
  the word `approximately`/`exact`/`exactly`/`to within`, or an
  explicit `[exact closed form]` marker.
- `src/main/java/com/emergentterrain/research/UniversityService.java`
  — wire the new validator after the closing-prose check; route
  failures to NEEDS_REVISION (same tier as G6 / D6). New config flag
  `app.research.uncertainty-disclosure.enabled` defaults OFF.

**Engine-side passthrough — the load-bearing piece.** Today's compute
branches that produce variance information drop it on the floor.
Three concrete cases the audit named:

| Topic | Engine variance source | Today's pass-through |
|-------|------------------------|----------------------|
| Mertens Partial Sums | `findings` string includes "worst |M|/√x ≈ X across N windows of width 200" — implicit sample size and range | Narrator quotes the worst value, loses the sample-size context |
| Bragg X-ray Diffraction | Per-sample θ values computed across the sweep | Narrator quotes peak θ only; spread is in `findings` string but unstructured |
| Hall-Petch Grain Size | σ_y computed at multiple d values | Narrator quotes max σ_y, no sample stddev |

For E6's initial scope, surface the data the engine *already computes*
via the new `uncertaintyStddev` field. A future phase can extend the
engine to compute additional variance metrics for branches that
currently don't.

**Prompt template extension** (concrete diff to NARRATION_PRIMARY):

```
Existing schema slot:
  "findings":"2-3 sentences …"

After E6:
  "findings":"2-3 sentences. Each headline numeric must carry an
   inline uncertainty: either `± N` syntax with the same unit, the
   literal phrase `exact closed form` when the result is a deterministic
   closed-form recovery, or `not quantified — <one-line reason>` when
   neither applies. Do not omit this clause."
  "uncertainty":"<one-line summary of how uncertainty was determined
   for the primary numeric; references the tool call by [call_N] if
   the variance was sampled>"
```

**Acceptance:**
- Unit test: a Findings string `"max σ_y ≈ 2377 MPa"` (no uncertainty)
  → `UncertaintyDisclosureValidator.validate()` returns one reason.
- Unit test: `"max σ_y ≈ 2377 MPa ± 12 MPa"` → empty list.
- Unit test: `"E₁/kT = 0.118 [exact closed form]"` → empty list.
- Unit test: `"η_Carnot = 1 − T_c/T_h, exact"` → empty list (the
  trailing `, exact` qualifier counts).
- After E6 ships in `warn` mode (flag off), the narrator template
  produces an `uncertainty:` field on ≥80% of papers across a 20-paper
  batch. Tells us the template change took effect.
- After flipping `uncertainty-disclosure.enabled=true`, replay over the
  ~18 panel-flagged papers: ≥12 now carry inline uncertainty clauses
  on their primary numeric. Tells us the validator routes correctly.
- The next REVIEW_REPORT refresh shows panel-recommendation lines
  asking for "uncertainty" / "error bounds" / "sensitivity analysis"
  drop by ≥40%. Tells us the panels actually credit the new disclosure.

**Risk:**
- A `± N` slot that the LLM populates with fabricated uncertainty
  values is worse than no uncertainty at all. Mitigation: the
  `uncertainty:` field's one-line summary should reference a tool call
  ID (`[call_N]`); the Phase 11 citation validator can verify that the
  cited call's resultJson actually carries the stated variance. If the
  citation doesn't trace, the existing
  `citations:out_of_tolerance` flag fires.
- A template change affects every paper. The flag-off default ensures
  one REVIEW_REPORT refresh exercises the new path before enforcement.

---

## What Phase 13 deliberately does NOT change

- **No new validators.** ~~TextbookBoundRegistry, ClosingProseValidator,
  NumericalConsistencyValidator, MagnitudeRangeValidator,
  TopicSpecificFindingsValidator — all unchanged. Phase 13 is
  exclusively about fixing named bugs in existing paths.~~
  *(Updated for E6: one new validator,
  `UncertaintyDisclosureValidator`, follows the same shape as the
  existing topic-specific / closing-prose validators — single static
  `validate()` method returning textual reasons.)*
- **No prompt-template rewrites.** E3 corrects one specific
  parenthetical pattern; it does not redesign the RLC prose. Wishlist
  P1/P2 cover broader prompt-engineering work if/when scheduled.
- **No reviewer-stage tool-call enforcement.** That's the phase
  wishlist's P3, scoped separately. E2's lenient JSON parser handles
  the *revision* stage parse failures; the panel-review stage's parse
  failures are a different problem with a different fix.
- **No `numerical-consistency` mode flip.** D2's mode stays on `warn`
  for this rollout window. Phase 13 doesn't accelerate any Phase 12
  flag flips.
- **No SimulationGate hysteresis change.** E1 changes aggregation only.
  `pauseThresholdPct=95` and `resumeThresholdPct=80` remain.
- **No fix for cross-domain INTERDISCIPLINARY non-sequiturs.** Phase 7
  G8's `audit:physical_mechanism→REJECTED` already caught P-a328-443
  (Happy-Number → Packing) in this refresh. Phase wishlist's P4
  covers the proactive coupling-equation gate; not in scope here.

---

## Sequencing summary

```
E1 (SimulationGate) ─┐
E3 (Q-factor labels)─┼─→ Independent; ship in any order.
E5 (X placeholder)  ─┘

E4 (header-only block) ─→ Independent.

E6 (uncertainty)    ───→ Independent of E1/E3/E4/E5. Touches the same
                          NarrationResult parse path as E2, so ship E6
                          after E2 lands or in a separate PR with a
                          merge resolution plan if parallel.

E2 (rev:parse_failed) ──→ Larger change; ship before E6 (its lenient parser
                          touches the same code path as E4's truncation
                          guard and E6's NarrationResult extension via
                          UniversityService.publishPaper).
```

**Suggested PR shape:**

| PR | Pieces | Effort | Risk |
|----|--------|--------|------|
| #1 | E1 + E3 + E5 | ~2 hours | Low. Three narrow fixes; each closes a named refresh item. |
| #2 | E4 | ~1 hour | Low. One-method extension. |
| #3 | E2 | ~3 hours | Medium. Lenient parser is new code; verify against the 9 failing papers in this refresh. |
| #4 | E6 | ~2 hours | Medium. Template change affects every paper; flag-gated rollout. Ship after PR #3 to avoid a NarrationResult-shape merge with E2. |

---

## Acceptance gates

| Piece | Headline acceptance test |
|-------|--------------------------|
| **E1** | Unit test: Groq at 95%, Ollama at 0%, no other provider → `isOpen() == true` (today: false). Three other unit cases cover all-providers-hot, partial-budget, and the reactive backstop firing. |
| **E2** | Replay over the 9 `rev:parse_failed→fp` papers in this refresh: ≥5 parse cleanly with the lenient pre-parser. New `rev:parse_recovered:from=lenient` callPath stamp appears on the recovered subset. |
| **E3** | No published RLC paper carries `(over)` / `(crit)` / `(under)` parens after E3 ships. Regression test on the engine's RLC compute output. |
| **E4** | A paper with a header-only Computation 2 block publishes as NEEDS_REVISION (not PUBLISHED). Three unit tests cover header-only, equation-only, and "block with adequate prose body" pass-through. |
| **E5** | Unit tests cover stripping `± X%`, preserving `± 2.3%`, and not touching `X` in unrelated context. After ship, no published paper contains `± X%` literal. |
| **E6** | After flag-on, ≥80% of papers carry an inline uncertainty clause on their primary numeric (± syntax, "exact closed form", or "not quantified — reason"). Next REVIEW_REPORT refresh shows panel "uncertainty/error bounds/sensitivity" recommendation count drops ≥40%. |

**Phase 13 complete when:**

- The next REVIEW_REPORT refresh (post-rollout) shows acceptance rate
  ≥ 40% over a 25-paper window. Today: 26.1%.
- Zero published RLC papers carry the Q-factor label-swap pattern.
- Zero published papers carry a header-only `### Computation N — …`
  block as Findings content.
- Zero published papers contain `± X%` literal placeholder.
- `rev:parse_failed→fp` rate drops from 39% to ≤ 20% across a 25-paper
  window. The remainder is genuine parse failure (LLM emitted prose),
  not formatting drift the lenient parser could repair.
- SimulationGate stays open when only one of multiple budget-tracked
  providers crosses the pause threshold.
- Panel "uncertainty/error bounds/sensitivity" recommendation count
  drops ≥40% from this refresh's ~38 mentions across ~18 papers
  (the corpus-wide signal that E6 actually moved the needle on the
  most-repeated panel ask).

That last-but-one bullet is the operational success criterion. Today
the review skill's `/loop` couldn't refresh because the room went silent
for 85 minutes from a single-provider exhaustion; after E1, that
silence becomes a per-provider warning, not a simulation-wide pause.

---

*This is a queued phase, not a wishlist. Each item is named in
REVIEW_REPORT refresh #1's §1 recommendations OR in the panel-
recommendation pattern audit across the same corpus (E6), and has a
concrete acceptance test against the corpus the refresh observed.*
