# Predicate-study pre-registration — 2026-07-23

Dynamic predicate papers previously had a baseline and an initial finite census, but the professor
could select width/offset follow-ups after seeing that result. The baseline-sensitivity publication
gate could reject an inadequate final trace, yet it did not establish that the successful design had
been chosen before evidence was available.

`PredicateStudyPreRegistration` now creates a versioned, machine-checkable plan from a
`DynamicProblem` immediately before `DynamicComputeEngine.compute` runs. For a valid initial
window `[a,b)`, it locks:

- the predicate, metric, and numeric baseline;
- the original exact census `[a,b)`;
- a same-width overlapping offset census; and
- a narrower census anchored at `a`.

All registered spans are at most `DynamicComputeEngine.MAX_RANGE` (500). The initial width must
contain at least four integers; otherwise no sensitivity plan is claimed.

The professor receives the exact calls as a pre-registration note. With
`app.research.predicate-study-preregistration-gate.enabled=true`, a paper with this registration
becomes `NEEDS_REVISION` unless its selected successful `run_predicate_scan` trace executes every
registered predicate/metric/baseline/range tuple. Extra scans remain exploratory and cannot replace a missing
registered one. The existing baseline-sensitivity gate still evaluates the observed design, and
the new rule does not license persistence or asymptotic claims from finite censuses.

The pre-registration is stored in the ordinary artifact pre-registration section and its
computational-study plan, so the before-result design and after-result trace are auditable together.

## Verification

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export PATH="$JAVA_HOME/bin:$PATH"
mvn test -Dtest=PredicateStudyPreRegistrationTest,PredicateBaselineSensitivityValidatorTest,PivotNoteTest,PublishPaperTest \
  -DargLine=-javaagent:$HOME/.m2/repository/net/bytebuddy/byte-buddy-agent/1.15.11/byte-buddy-agent-1.15.11.jar
```
