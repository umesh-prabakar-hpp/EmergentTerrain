# Predicate-study pre-registration — 2026-07-23

Dynamic predicate papers previously had a baseline and an initial finite census, but the professor
could select width/offset follow-ups after seeing that result. The baseline-sensitivity publication
gate could reject an inadequate final trace, yet it did not establish that the successful design had
been chosen before evidence was available.

`PredicateStudyPreRegistration` now creates a versioned, machine-checkable plan from a
`DynamicProblem` immediately before `DynamicComputeEngine.compute` runs. For a valid initial
window `[a,b)`, it locks:

- the predicate, metric, numeric baseline, and expected relation to that baseline
  (`ABOVE_BASELINE`, `BELOW_BASELINE`, or `APPROXIMATELY_EQUAL`); and
- for `APPROXIMATELY_EQUAL`, one positive absolute `comparisonTolerance` chosen before the
  first census.
- the original exact census `[a,b)`;
- a same-width overlapping offset census; and
- a narrower census anchored at `a`.

All registered spans are at most `DynamicComputeEngine.MAX_RANGE` (500). The initial width must
contain at least four integers; otherwise no sensitivity plan is claimed.

## Publication-state separation

The exact census result is a semantic outcome, not a proxy for publication
readiness. `PublicationStateResolver` evaluates that outcome independently of
three auditable deficits: an acquired source that was not cited, missing
sensitivity evidence, and missing independent replication. A supported
above/below-baseline result with any of those deficits is `NEEDS_REVISION`; it
is never converted to `NULL_RESULT`. Conversely, a genuine `NULL_RESULT`
remains a null finding even when the artifact records outstanding publication
work.

The professor receives the exact calls as a pre-registration note. With
`app.research.predicate-study-preregistration-gate.enabled=true`, a paper with this registration
becomes `NEEDS_REVISION` unless its selected successful `run_predicate_scan` trace executes every
registered predicate/metric/baseline/range tuple. Extra scans remain exploratory and cannot replace a missing
registered one. The existing baseline-sensitivity gate still evaluates the observed design, and
the new rule does not license persistence or asymptotic claims from finite censuses.

The relation is executable: `DynamicComputeEngine` evaluates an above/below study by the locked
inequality, while approximate-equality studies use their locked absolute tolerance. A new
approximate-equality directive without a positive tolerance cannot allocate a locked study; its
legacy fallback band is retained only to render older stored directives and cannot satisfy the
new registration gate. The selected `run_predicate_scan` trace must repeat that exact
`comparison_tolerance`, so a later tool call cannot quietly widen the comparison band. It must not
classify a directional exceedance as `REFUTED` merely because it is far from the baseline; that
would turn a confirmed finite census into a false `NULL_RESULT`. Older stored directives infer a
relation from their hypothesis as a compatibility bridge, but new mentor output declares it
explicitly and records it in the pre-registration mapping notes.

For a new approximate-equality study, the declared band is the whole acceptance criterion: an
observed deviation inside it is `SUPPORTED`, and one outside it is `REFUTED`. There is no wider
post-hoc `PARTIAL` band that can turn a failed declared comparison into a positive result.

The pre-registration is stored in the ordinary artifact pre-registration section and its
computational-study plan, so the before-result design and after-result trace are auditable together.

## Verification

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export PATH="$JAVA_HOME/bin:$PATH"
mvn test -Dtest=DynamicDirectionalClassificationTest,PredicateStudyPreRegistrationTest,PredicateBaselineSensitivityValidatorTest,PivotNoteTest,PublishPaperTest \
  -DargLine=-javaagent:$HOME/.m2/repository/net/bytebuddy/byte-buddy-agent/1.15.11/byte-buddy-agent-1.15.11.jar
```
