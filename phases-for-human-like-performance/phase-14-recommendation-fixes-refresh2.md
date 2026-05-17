# Phase 14 — Recommendation Fixes #2 (scoping)

**Scope:** Six concrete fixes drawn from REVIEW_REPORT refresh #2
(`research/a3289005/REVIEW_REPORT.md`, 2026-05-16 08:25 EDT,
papers P-a328-476 – P-a328-512, n=20). Each item is tied to a specific
Recommendation entry in the report's §1 (recs #1–#6) and to a specific
file and line range in the code. Total work ~12 hours across six pieces
(F1–F6) plus this scoping doc. Every item closes a *named* failure mode
visible in the corpus — not a hypothetical quality lever.

A seventh piece (F7 — sandbox grammar relaxation, ~3 days) is the
upstream pressure relief mentioned by recommendation #8. It's large
enough to warrant its own scoping pass and is deliberately *excluded*
from Phase 14; see "What Phase 14 deliberately does NOT change" below.

**Status:** Scoping document. No code in this phase yet. Implementation
lands across six commits (F1–F6) plus this doc.

**Prerequisite:** Phase 13 shipped (six commits `11ca4c4 → dab1724`).
The review skill confirms E1's coolest-spend gate, E2's LenientJsonParser
recovery (`rev:parse_recovered:from=lenient` visible on P-482 + P-512),
and E4's header-only block guard are visibly active. Phase 14 fills the
gaps refresh #2 surfaced — distinct from Phase 13's targets.

**Why now:** Phase 13 closed five of its seven acceptance gates but the
**headline acceptance rate dropped to 0%** in refresh #2 (cumulative
0% Acceptable + 0% Minor + 25% Major + 75% Reject across n=20). The
miss is *not* a Phase 13 regression — the rate is dominated by two
**new** failure modes Phase 13 didn't target:

1. **Five-Computation stub block fabrication** (rec #1, 6 of 20 papers:
   P-480, P-485, P-489, P-492, P-502, P-503). Pattern: five identically-
   shaped `### Computation N` headers each citing one equation; prose
   narrates five independent runs with specific statistics; the actual
   sandbox log shows ≤2 successful calls. This is F1.
2. **Zero-tool-call publishing** (rec #2, P-496 + P-505). Each has 0
   successful sandbox calls yet publishes with fabricated Findings prose.
   P-505's Conclusion even reads *"Computed values were independently
   reproduced by peer reviewers"* — the canned line — alongside a
   fabricated `0.0005 at s=2.026` deviation. The Phase 10
   `all_tools_failed→suppressed` and Phase 12 D3 `zero_successes→suppressed`
   gates should fire here but don't. This is F2.

Four smaller items round out the named refresh-#2 failures:

3. **Conclusion-vs-Findings inversion** (rec #3, 3 papers across
   refreshes: P-491, P-496, P-511). This is F3.
4. **Single-point sweep claim** (rec #4, P-494, P-507, P-512). Prose
   claims *"sixteen sampled cases"* backed by 1 successful call. This
   is F4.
5. **Missing Methods/Panel section** (rec #5, NEW failure mode; P-503
   publishes Hypothesis → Findings → Conclusion → Discussion → Related
   Work with **no Methods and no Panel** section at all). This is F5.
6. **Cross-domain non-sequitur in non-INTERDISCIPLINARY papers**
   (rec #6, P-484: MATHEMATICS paper whose Discussion drifts into Van
   der Waals + Schrödinger). Phase 7 G8 caught the Hypothesis but not
   the Discussion. This is F6.

Combined projection: F1 + F2 directly close 8 of 20 papers' worth of
fabrication (the dominant cluster); F3–F6 close 5 more. Acceptance rate
projected to move from refresh #2's 0% to ~30–40% on the next
25-paper window.

**Why this is risky:**

- **F1 is a publishing gate, not a validator.** Today the harness
  renders whatever the narrator produces. F1 truncates or stamps blocks
  the narrator wrote — operators who relied on "what the LLM emitted is
  what we publish" lose that property. Mitigation: ship F1 in
  `stamp-with-call-id` mode first (which preserves all blocks but adds
  `(no data — call_N missing)` markers); flag-gate the harder `truncate`
  mode separately so one REVIEW_REPORT refresh runs with the stamp-only
  variant before truncation.
- **F2 is a diagnosis-first piece.** The Phase 10 + Phase 12 D3 gates
  *appear* to already test `metrics.totalSuccesses() == 0` (per a survey
  of `ResearchNarrationService.java:490`), so the report's
  one-line-fix recommendation is incomplete. P-496/P-505 must be
  slipping past via a different code path — possibly the
  `rev:parse_failed→fp` fallback that bypasses the professor stage
  entirely, or a panel-degradation path that re-enters publish. F2's
  first commit is *find the leak* before patching it. Risk: the leak
  could be in three different places and the fix is non-local.
- **F4 changes a narration prompt.** Direction-of-finding requires the
  narrator to emit polarity tags (`within` / `outside` / `confirmed` /
  `refuted`) the validator can check. A flag-gated rollout
  (`direction-of-finding.enabled` default off) lets one refresh observe
  the new field before enforcement.
- **F6 extends the audit-4b prompt to Discussion + Conclusion.** That
  doubles the audit cost per paper (the 4B reviewer now reads three
  sections instead of one). At refresh #2's ~30 papers/hour rate that's
  cheap; at 10× the rate the budget impact would matter. Cap the audit
  with a 200-token slice of each section, not the full text.

---

## Architecture

```
              Existing pipeline (post-Phase-13 baseline)
              ─────────────────────────────────────────
  SimulationGate.isOpen()                    (Phase 13 E1)
        │
        ▼
  ResearchNarrationService.synthesizeNarration
        │
        ├─ Phase 10:  all_tools_failed→suppressed       ← F2 audits both gates
        ├─ Phase 12 D3: zero_successes→suppressed       ←    for the leak
        └─ Phase 13 E2: rev:parse_recovered:from=lenient

  PromptTemplateRegistry                                ← F4 adds polarity slot
        │                                                  to NARRATION_PRIMARY
        │                                              ← F6 extends audit-4b
        │                                                  scan to Disc + Conc
        ▼
  professor synthesis ─▶ student revision ─▶ panel

  publishPaper
        │
        ├─ block-count guard         ← F1 (NEW: blocks > successes → stamp/truncate)
        ├─ truncation guard          ← Phase 13 E4 (header-only)
        ├─ Methods-section gate      ← F5 (NEW: NEEDS_REVISION if Methods absent)
        ├─ topic-specific validator
        ├─ uncertainty-disclosure    ← Phase 13 E6
        ├─ numerical-consistency
        ├─ single-point-sweep        ← F3 (NEW: validates "N cases" claims)
        ├─ direction-of-finding      ← F4 (NEW: Conclusion polarity vs Findings)
        ├─ cross-domain audit        ← F6 (extends Phase 7 G8 to Disc + Conc)
        ├─ PromptSanitizer
        └─ prose template
```

**The atomic unit:** F1, F2, F5, F6 extend existing code paths. F3 and
F4 add two new validators following the same shape as
`UncertaintyDisclosureValidator` (Phase 13 E6). No new services, no new
schemas beyond a Findings polarity tag (F4) and a Methods-presence
boolean (F5, derivable from the tool-call trace).

---

## F1 — Computation-block-count guard

**Effort:** ~3 hours. **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/research/UniversityService.java:1651`
  (`renderFindingsSection` — emits `### Computation N — <topic>` per
  block, line range 1663–1668) and `:2085` (`firstTruncatedBlock`).
- `src/main/java/com/emergentterrain/research/NarrationResult.java`
  — uses `findingsMap` (Phase 7 F2.2) keyed by 1-based computation index.
- `src/test/java/com/emergentterrain/research/PublishPaperTest.java`
  — three new cases.

**The bug:** REVIEW_REPORT refresh #2 §1 rec #1 names 6 of 20 papers
(P-480, P-485, P-489, P-492, P-502, P-503) where the narrator emits five
`### Computation N` blocks each citing one equation and narrating five
independent runs with specific statistics, but the actual sandbox log
shows ≤2 successful calls. Per-paper successful-call counts: P-480 = 1,
P-485 = 1, P-489 = 1, P-492 = 2, P-502 = 2, P-503 = 0.

Phase 13 E4 (`isHeaderOnlyBlock`, `UniversityService.java:2106`) catches
blocks with *just* a header + equation and no prose. The refresh-#2
failure is different: the prose body **is present and long**, but
**fabricated** — describing runs that never executed.

**Fix — two layers, each flag-gated:**

### F1a — stamp-with-call-id (default mode)

Render each block with the `[call_N]` it draws from in the H3 line. For
blocks beyond the successful-call count, render with
`(no data — call_N missing)` instead of the LLM's prose body. Preserves
all five blocks but makes the absence visible:

```java
// In renderFindingsSection, around line 1663:
int successCount = paper.toolCallTrace() == null
        ? 0
        : (int) paper.toolCallTrace().stream()
            .filter(ToolInvocation::successful).count();
for (Map.Entry<Integer, String> e : findingsMap.entrySet()) {
    int blockIdx = e.getKey();
    String body = e.getValue();
    sb.append("### Computation ").append(blockIdx);
    sb.append(" — ").append(topicFor(blockIdx));
    if (blockIdx > successCount) {
        sb.append(" `(no data — call_").append(blockIdx).append(" missing)`");
        sb.append("\n\n*[Block elided — narrator emitted ").append(blockIdx)
          .append(" blocks but trace shows only ").append(successCount)
          .append(" successful call(s).]*\n\n");
        continue;
    }
    sb.append(" `[call_").append(blockIdx).append("]`\n\n");
    sb.append(body).append("\n\n");
}
```

### F1b — block-count enforcement (flag-gated, ships behind F1a)

When `app.research.block-count-guard.enabled=true`, route papers whose
block count exceeds the successful-call count to NEEDS_REVISION instead
of publishing the stamped variant.

**Why this is safe:** F1a preserves the existing publish path — every
paper still publishes; the prose is annotated, not removed. F1b only
gates `successful_call_count < block_count` papers, the exact failure
mode the report names. The narrator's freedom is unchanged; only the
*rendering* and *gate routing* are extended.

**Acceptance:**
- Unit test: paper with 5 findingsMap entries + 2 successful tool calls
  → rendered markdown carries `(no data — call_3 missing)` markers on
  blocks 3, 4, 5. Today: all five render with fabricated prose.
- Unit test: paper with 3 findingsMap entries + 3 successful tool calls
  → rendered markdown unchanged from today's output (golden file).
- Integration test in PublishPaperTest: with `block-count-guard.enabled=true`,
  the 5-block / 2-success case publishes as NEEDS_REVISION.
- After F1 ships in stamp-only mode, replay over the 6 papers in
  refresh #2 that carry the Five-Computation pattern: each renders with
  visible `(no data)` markers on the fabricated blocks. After F1b's
  flag flips on, the same 6 papers route to NEEDS_REVISION.

**Open question for F1:** the report (rec #1) also notes P-503 has the
pattern *"with no Methods section at all"* — the Methods section absent
question is handled by F5; F1 handles the block-count question
orthogonally.

---

## F2 — Zero-successes gate condition fix

**Effort:** ~3 hours (diagnosis + fix). **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/service/ResearchNarrationService.java`
  — `synthesizeNarration` (gate at line 490–500) and the
  `rev:parse_failed→fp` fallback path (around line 573, the
  `all_tools_failed` site).
- Possibly `src/main/java/com/emergentterrain/research/UniversityService.java`
  — if the leak is post-narration (panel-degradation path, fallback
  publish, …).
- `src/test/java/com/emergentterrain/service/ResearchNarrationServiceTest.java`
  — new cases for the zero-successes path.

**The bug — and what the report's diagnosis missed:** REVIEW_REPORT
refresh #2 §1 rec #2 reports P-496 (call_path ends at
`gate:closed:revision`) and P-505 (call_path ends at
`rev:parse_failed→fp`) each published with 0 successful sandbox calls
yet visible fabricated Findings prose. The report's recommended fix
("gate should test `successful_call_count == 0`") is **incomplete** —
a survey of `ResearchNarrationService.java:490` shows the gate
**already tests `metrics.totalSuccesses() == 0`**, not
`tool_call_count`. So the bug is upstream or downstream of that gate,
not at the gate itself.

The investigation surface:

1. **`rev:parse_failed→fp` fallback path (P-505).** When the revision
   stage fails to parse, the code falls back to `firstPass` narration
   — but firstPass may *also* lack successful tool calls. Does the
   fallback re-check `zero_successes`? Likely not.
2. **`gate:closed:revision` path (P-496).** Indicates the
   SimulationGate closed mid-revision; the partial narration that gets
   persisted may bypass the suppression gate. The retry logic on gate
   reopen may produce a paper without re-checking the success count.
3. **Panel-degradation re-entry (Phase 12 D4).** A degraded panel
   demotes PUBLISHED → NEEDS_REVISION, but if the input was already
   `zero_successes→suppressed`, the demotion target should be
   SUPPRESSED, not NEEDS_REVISION. Verify the demotion preserves the
   suppress state.

**Fix — investigate-then-patch, three commits:**

### F2a — diagnosis commit

Add structured logging to every fallback branch that could result in a
paper persisting with `zero_successes`:

```java
// In the rev:parse_failed→fp branch:
log.warn("FALLBACK: rev:parse_failed→fp paperId={} firstPassSuccesses={}",
        draft.paperId(), firstPassMetrics.totalSuccesses());

// In the gate:closed:revision branch:
log.warn("FALLBACK: gate:closed:revision paperId={} partialSuccesses={}",
        draft.paperId(), partialMetrics.totalSuccesses());
```

Run for one refresh; the WARN lines name the leaking path.

### F2b — patch the named path

Add a `successful_call_count == 0 → suppressed` check at every fallback
exit. The simplest implementation is a `requireSuccesses(metrics, callPath)`
helper called before every publish-producing return from
`synthesizeNarration` and from `publishPaper`'s fallback branches.

### F2c — regression coverage

Unit tests for each fallback path verifying that a 0-successful-calls
input produces `*:zero_successes→suppressed` on the call path and
returns null narration.

**Why this is safe:** F2a is pure logging — no behaviour change. F2b
adds a check that mirrors existing Phase 10 + Phase 12 D3 semantics;
papers it newly suppresses were already structurally invalid (no math
behind the prose).

**Acceptance:**
- Replay over P-496 and P-505: both stamp `zero_successes→suppressed`
  on the call path and never reach `publishPaper`. Today both publish.
- After F2 ships, the next REVIEW_REPORT refresh shows zero
  zero-tool-call publishes (was 2 of 20 in refresh #2).
- Unit tests cover each named fallback branch.

**Open question for F2:** if the diagnosis turns up *three* leaking
paths, F2 may grow to three commits. Treat F2a as a time-box: if the
leak isn't named within an hour of WARN-line review, split the
remaining work into F2-followon.

---

## F3 — Single-point sweep claim validator

**Effort:** ~2 hours. **Pre-condition:** none.

**Files touched:**
- New validator
  `src/main/java/com/emergentterrain/research/SinglePointSweepValidator.java`
  — follows the shape of `UncertaintyDisclosureValidator` (Phase 13 E6):
  one public static `validate(String findings)` + Map overload, returns
  `List<String>` of reasons.
- `src/main/java/com/emergentterrain/research/UniversityService.java`
  — wire into the validator chain at line 577–605.
- `src/test/java/com/emergentterrain/research/SinglePointSweepValidatorTest.java`.

**The bug:** REVIEW_REPORT refresh #2 §1 rec #4 names two refresh-#2
papers (P-507, P-512) and one from refresh #1 (P-494) whose prose
asserts a sample-size or window-count that the tool-call trace doesn't
support:

- P-507 prose: *"sixteen sampled cases"* with `std ≈ 0.02`; trace has
  one successful call (`Ea=167, T=1081 → 1.18568`).
- P-512 prose: 16 measured Ea values; trace has 2 successful calls.
- P-494 prose: sweep over [591,650]; trace has one call (`collatzSteps(649)=144`).

**Fix:** scan Findings for sample-size assertions and verify each against
the persisted tool-call trace count:

```java
private static final Pattern SAMPLE_SIZE_CLAIM = Pattern.compile(
        "\\b(?:n\\s*=\\s*(\\d+)|(\\d+)\\s*(?:sampled?|measured|"
        + "tested|cases?|windows?|samples?|runs?|trials?|points?))\\b",
        Pattern.CASE_INSENSITIVE);

private static final Pattern WORD_NUMBER = Pattern.compile(
        "\\b(?:two|three|four|five|six|seven|eight|nine|ten|"
        + "eleven|twelve|thirteen|fourteen|fifteen|sixteen|"
        + "seventeen|eighteen|nineteen|twenty|thirty|forty|"
        + "fifty|sixty|seventy|eighty|ninety|hundred)\\s+"
        + "(?:sampled?|measured|tested|cases?|windows?|samples?|"
        + "runs?|trials?|points?)\\b",
        Pattern.CASE_INSENSITIVE);

public static List<String> validate(String findings,
                                     List<ToolInvocation> trace) {
    int actualSuccesses = (int) trace.stream()
            .filter(ToolInvocation::successful).count();
    List<String> reasons = new ArrayList<>();
    Matcher m = SAMPLE_SIZE_CLAIM.matcher(findings);
    while (m.find()) {
        int claimed = parseDigits(m.group(1), m.group(2));
        if (claimed > Math.max(1, actualSuccesses * 2)) {
            reasons.add("Findings claims " + claimed + " samples; trace has "
                    + actualSuccesses + " successful call(s).");
        }
    }
    // Also scan WORD_NUMBER for "sixteen sampled cases" → 16, etc.
    // ...
    return reasons;
}
```

The `* 2` slack tolerates legitimate "each call computed 4 values" cases
without false-positiving — a 16-claim against a 1-success call is
clearly fabrication; a 4-claim against a 2-success call could be
legitimate sweep output and is allowed.

**Acceptance:**
- Unit test: `"sixteen sampled cases"` + 1-success trace →
  one reason. Today: silent.
- Unit test: `"4 measured cases"` + 2-success trace → empty list
  (within slack).
- Unit test: `"n=40 windows"` + 5-success trace → one reason.
- After F3 ships, P-494, P-507, P-512 each route to NEEDS_REVISION.

---

## F4 — Conclusion-vs-Findings direction validator

**Effort:** ~2 hours. **Pre-condition:** none. Independent of F1–F3.

**Files touched:**
- `src/main/java/com/emergentterrain/research/NumericalConsistencyValidator.java`
  — extend `findRangeMismatch` (existing pattern at line 64–82 already
  extracts `HYPOTHESIS_NEAR` and `CONCLUSION_COORD`) with a polarity
  check.
- `src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java`
  — extend `NARRATION_PRIMARY` schema to require a `claim_polarity`
  field in the conclusion JSON: `"within" | "outside" | "confirmed" |
  "refuted"`.
- `src/main/java/com/emergentterrain/research/NarrationResult.java`
  — add `String claimPolarity` field; parser extracts from JSON.
- New flag `app.research.direction-of-finding.enabled` (default off).

**The bug:** REVIEW_REPORT refresh #2 §1 rec #3 names three papers that
invert their verdict between Findings and Conclusion:

- P-491 Findings: max deviation 0.694%. Hypothesis bound: 5%. Conclusion:
  *"hypothesis ruled out"* — but 0.694% is **within** 5%, not outside.
- P-496 Conclusion: *"pre-registered prediction of a quality score
  around 0.30 was dramatically under-estimated; observed score of 0.80"*
  — but the pre-registered hypothesis is about a ratio bound, not a
  quality score. The Conclusion talks about a different variable than
  Findings does.
- P-511 Conclusion: cites efficiency 0.753 at `Tc=353K, Th=1430K`; the
  only successful call used `Tc=339K` and returned 0.762937.

The unifying pattern: Conclusion makes a directional claim
(`confirmed`, `refuted`, `within`, `outside`) that doesn't match the
Findings number's position relative to the Hypothesis bound.

**Fix:** require the narrator to emit `claim_polarity` in the JSON
response, and validate it against the Findings number + Hypothesis
bound:

```java
// In NumericalConsistencyValidator, new check after findRangeMismatch:
public static List<String> validateDirection(String hypothesis,
                                              String findings,
                                              String conclusion,
                                              String claimPolarity) {
    Matcher h = HYPOTHESIS_NEAR.matcher(hypothesis);
    Matcher f = FINDINGS_NUMERIC.matcher(findings);
    if (!h.find() || !f.find()) return List.of();
    double bound  = parseNumber(h.group(1));
    double actual = parseNumber(f.group(1));
    boolean within = Math.abs(actual) <= Math.abs(bound);
    String expectedPolarity = within ? "within" : "outside";
    if (!expectedPolarity.equalsIgnoreCase(claimPolarity)) {
        return List.of(String.format(
                "Conclusion polarity '%s' contradicts Findings %s "
                + "vs Hypothesis bound %s (expected '%s').",
                claimPolarity, actual, bound, expectedPolarity));
    }
    return List.of();
}
```

**Why this is safe:** the polarity field is optional in the first
rollout (narrator may omit it; validator skips when null). The flag
gate controls enforcement. The `claim_polarity` slot itself is a
narrator hint — operators can still read the Conclusion prose as-is
even if the polarity field is wrong.

**Acceptance:**
- Unit test: hypothesis "within 5%" + findings "0.694%" + polarity
  "outside" → one reason (polarity mismatch).
- Unit test: hypothesis "within 5%" + findings "0.694%" + polarity
  "within" → empty.
- Unit test: hypothesis "within 5%" + findings "12.4%" + polarity
  "within" → one reason.
- After F4 ships behind the flag (default off), narrator emits
  `claim_polarity` on ≥80% of papers across a 20-paper batch — tells us
  the template change took effect.
- After `direction-of-finding.enabled=true`, replay P-491: routes to
  NEEDS_REVISION on Conclusion-polarity mismatch.

---

## F5 — Missing Methods section publish gate

**Effort:** ~1 hour. **Pre-condition:** none.

**Files touched:**
- `src/main/java/com/emergentterrain/research/UniversityService.java`
  — `publishPaper` validator chain (~line 548–650); add a check that
  `narration.toolCallTrace() != null && !narration.toolCallTrace().isEmpty()`.
- `src/test/java/com/emergentterrain/research/PublishPaperTest.java`
  — two new cases.

**The bug:** REVIEW_REPORT refresh #2 §1 rec #5 names P-503 as a **NEW
failure mode**: published with Hypothesis → Findings → Conclusion →
Discussion → Related Work — Methods **and** Panel sections **entirely
absent**. Call path ends at `rev:parse_failed→fp` with no `panel:n=*`
stamp. The publish step should refuse to write a paper that lacks a
Methods section, because "what computations support the claims" is the
most-asked panel-review recommendation in the corpus.

`renderMethodsSection` (UniversityService.java:1686) currently returns
`""` when `paper.toolCallTrace()` is null/empty. The renderer's silence
becomes the publish path's silence — there is no pre-publish check that
the section will render content.

**Fix:** add a pre-publish gate:

```java
if (narration.toolCallTrace() == null
        || narration.toolCallTrace().isEmpty()) {
    callPath.add("publish:no_methods→needs_revision");
    log.warn("publishPaper: no Methods section (empty toolCallTrace) "
            + "paperId={}", draft.paperId());
    return persistAsNeedsRevision(draft, narration, callPath,
            "Missing Methods section");
}
```

**Why this is safe:** "no Methods" is structurally a stronger version
of `zero_successes` — already a NEEDS_REVISION condition under Phase 10.
F5 just catches the case where the tool-call trace is null because
*nothing* ran (vs. ran-and-failed). Strictly tighter than the existing
zero-successes gate.

**Acceptance:**
- Unit test: paper with null toolCallTrace → published as NEEDS_REVISION
  with `publish:no_methods→needs_revision` on the call path.
- Unit test: paper with non-empty toolCallTrace (any successes) →
  unchanged.
- After F5 ships, P-503-class papers route to NEEDS_REVISION.

---

## F6 — Cross-domain non-sequitur scan extension

**Effort:** ~1 hour. **Pre-condition:** Phase 7 G8 wired (it is — commit
`622a85a`).

**Files touched:**
- `src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java`
  — extend the audit-4b prompt at lines 561, 589, 626 to scan Discussion
  + Conclusion in addition to Hypothesis (200-token slice per section).
- `src/main/java/com/emergentterrain/research/UniversityService.java`
  — Phase 7 G8 site at line 639–663 already routes
  `auditGapDimensions.contains("physical_mechanism")` to REJECTED; no
  change needed if the audit returns the same dimension key for
  Discussion/Conclusion drift.
- `src/test/java/com/emergentterrain/research/PromptTemplateRegistryTest.java`
  — verify the prompt change doesn't regress on the four currently-
  caught Hypothesis cases.

**The bug:** REVIEW_REPORT refresh #2 §1 rec #6 names P-484 (refresh #1
entry, still listed): MATHEMATICS paper whose Discussion drifts into
Van der Waals + Schrödinger references with no physical bridge. Phase 7
G8's `physical_mechanism→REJECTED` gate caught the Hypothesis variant
(P-a328-443 Happy-Number → Packing) but only scans the Hypothesis.
Discussion + Conclusion drift slips through.

**Fix:** widen the audit-4b prompt's input scope:

```
Current scope:
  HYPOTHESIS: {{ hypothesis }}

  Audit-4b: does the hypothesis carry a physical mechanism?

After F6:
  HYPOTHESIS: {{ hypothesis }}
  DISCUSSION (first 200 tokens): {{ discussion200 }}
  CONCLUSION (first 200 tokens): {{ conclusion200 }}

  Audit-4b: does any of HYPOTHESIS, DISCUSSION, or CONCLUSION drift into
  a domain that doesn't match {{ paperDomain }}? Flag with
  `physical_mechanism` dimension if so.
```

The downstream G8 gate already routes the dimension; no Java change is
needed beyond the template edit and a 200-token slicing helper.

**Why this is safe:** the audit-4b reviewer's verdict already feeds an
existing gate; F6 only widens the *input*, not the routing. The
200-token cap keeps audit cost bounded.

**Acceptance:**
- Unit test: paperDomain=MATHEMATICS, hypothesis clean, Discussion
  carries "Van der Waals" — audit returns `physical_mechanism` dimension.
  Today: not checked.
- Unit test: paperDomain=INTERDISCIPLINARY, Discussion bridges domains
  — empty dimension list (INTERDISCIPLINARY exempt from G8 per existing
  logic).
- Replay over P-484: audit catches the Van der Waals drift, routes to
  REJECTED.

---

## What Phase 14 deliberately does NOT change

- **F7 — sandbox grammar relaxation (wishlist P2 Layer 1, ~3 days).**
  REVIEW_REPORT refresh #2 §1 rec #8 names 60 `MathToolSuite: sandbox
  code rejected by 4b review` WARN entries / 55 minutes — same rate as
  refresh #1. This is the *upstream cause* of the Five-Computation
  fabrications: authors run out of parse-fail retries on round 4 and
  the prose still describes the intended sweep. Relaxing
  `SandboxStaticChecker` to accept `Math.*`, object/array literal
  returns, `let`, ternary in expression contexts, and `+=` compound
  assignment would cut WARN rate ≥50%. Excluded from Phase 14 because:
  (a) it's a larger change touching the sandbox AST, (b) Phase 14 F1
  closes the symptom (fabricated blocks visible) without depending on
  upstream relief, (c) F7 deserves its own scoping pass with a Layer
  1 / Layer 2 / Layer 3 plan. Queued as a candidate Phase 15.
- **No new services.** F3 and F4 add validators following the
  established static-validate pattern; no new orchestrators.
- **No model-size change for the audit-4b reviewer.** A 4B → 70B swap
  for the audit reviewer was considered as an alternative to F7 but
  ruled out: the rejections are mostly deterministic-static-checker
  rejections, not LLM judgment rejections. Bigger model on the
  audit-side wouldn't reduce the static-checker rejection count.
- **No `numerical-consistency` mode flip.** D2's mode stays on `warn`
  for this rollout window.
- **No reviewer-stage tool-call enforcement.** Phase wishlist P3
  (schema-pinned reviewer prompts) still queued separately.
- **No change to Phase 13 E6 enforcement.** `uncertainty-disclosure`
  flag stays off until the verification refresh completes.

---

## Sequencing summary

```
F5 (Methods gate) ─┐
F6 (cross-domain) ─┼─→ Independent; ship in any order.
F3 (single-sweep) ─┘

F4 (direction-of-finding) ─→ Independent; template change so ship
                              alongside F3 or in a separate PR.

F1 (block-count guard) ───→ Larger change; ship in stamp-only mode
                              first, then flag-gate the truncate mode.

F2 (zero-successes leak) ──→ Diagnosis-first. F2a (logging) first;
                              F2b (patch) after the WARN lines name the
                              leaking path. Independent of F1 + F3–F6.
```

**Suggested PR shape:**

| PR | Pieces | Effort | Risk |
|----|--------|--------|------|
| #1 | F5 + F6 | ~2 hours | Low. F5 is a one-condition gate; F6 is a prompt edit reusing existing G8 routing. |
| #2 | F3 + F4 | ~4 hours | Low–Medium. Two new validators following the F13-E6 shape; F4 adds an optional polarity field behind a flag. |
| #3 | F1 | ~3 hours | Medium. Block-count guard ships in stamp-only mode; flag-gated truncate mode is a follow-on flip. |
| #4 | F2 | ~3 hours | Medium. Diagnosis-first; F2a (logging) lands first, F2b (patch) lands after the leaking branch is named. |

---

## Acceptance gates

| Piece | Headline acceptance test |
|-------|--------------------------|
| **F1** | After F1a ships, the 6 Five-Computation refresh-#2 papers render with `(no data — call_N missing)` markers on blocks beyond the successful-call count. After F1b flag flips on, the same 6 papers route to NEEDS_REVISION. |
| **F2** | Replay over P-496 + P-505: both stamp `*:zero_successes→suppressed` on the call path and never reach `publishPaper`. Today both publish. After F2 ships, next REVIEW_REPORT refresh shows zero zero-tool-call publishes. |
| **F3** | Unit tests cover "sixteen sampled cases" + 1-success trace → one reason, "4 measured cases" + 2-success trace → empty (within slack), "n=40 windows" + 5-success trace → one reason. After ship, P-494, P-507, P-512 route to NEEDS_REVISION. |
| **F4** | After flag-on, narrator emits `claim_polarity` on ≥80% of papers across a 20-paper batch; replay P-491 → polarity mismatch detected, routes to NEEDS_REVISION. |
| **F5** | Unit test: paper with null toolCallTrace publishes as NEEDS_REVISION with `publish:no_methods→needs_revision` on the call path. P-503-class papers route to NEEDS_REVISION after ship. |
| **F6** | Replay over P-484: audit-4b returns `physical_mechanism` dimension on the Van der Waals drift in Discussion; routes to REJECTED via Phase 7 G8's existing gate. |

**Phase 14 complete when:**

- The next REVIEW_REPORT refresh (post-rollout) shows acceptance rate
  ≥ 30% over a 25-paper window. Today (refresh #2): 0%. Combined with
  Phase 13's measured-but-unverified gains the projection is ~30–40%.
- Zero published papers carry the Five-Computation stub-block pattern
  (block count > successful-call count) with un-stamped blocks.
- Zero published papers with 0 successful tool calls reach
  `publishPaper`. Currently 2 of 20 in refresh #2.
- Zero published papers with a sample-size assertion (`N samples`,
  `N cases`, `N windows`) exceeding `2 × successful_call_count` in
  Findings prose.
- Zero published papers with a Conclusion-Findings polarity
  inversion against the Hypothesis bound (measured against the
  `claim_polarity` field).
- Zero published papers without a Methods section.
- Zero published non-INTERDISCIPLINARY papers with a Discussion or
  Conclusion that drifts into a different domain's mechanism vocabulary.
- F7 (sandbox grammar relaxation) is scoped as a separate phase and
  queued.

That last bullet is the demarcation: Phase 14 closes the named
**publishing-layer** failure modes; the **sandbox-grammar pressure**
that's *producing* the underlying fabrications is left to a future
phase. F1's block-count guard makes the fabrications visible (and
flag-gated, blockable) so refresh #3 can observe what the upstream
pressure actually looks like once the symptom is contained.

---

*This is a queued phase, not a wishlist. Each item is named in
REVIEW_REPORT refresh #2's §1 recommendations and has a concrete
acceptance test against the corpus the refresh observed.*
