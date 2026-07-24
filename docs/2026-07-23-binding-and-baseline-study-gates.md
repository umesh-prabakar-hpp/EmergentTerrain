# Binding and baseline-study gates — 2026-07-23

The end-of-run review for room `b72c4e10` found two ways that finite computation could be
described more strongly than its selected evidence supports. These guards turn the review
criteria into deterministic publication checks.

## Affine binding claims

P85 described a tight affine relation as a binding regime. Its selected trace contained a
same-grid `HELD` result, and the verifier named the counterexample intercept needed to complete
the demonstration. It also contained refutations for unrelated relations. The old extension
policy treated any refutation in the trace as completion, so it did not issue the named call.

`BindingExtensionPolicy` now considers a binding pair complete only when a `verify_conjecture`
refutation has the same lhs, rhs term(s), and tested grid as the tight hold. The intercept may
differ, as it must for a counterexample. The policy can therefore force the verifier-named call
despite unrelated refutations elsewhere in the trace.

`AffineBindingClaimValidator`, enabled by
`app.research.affine-binding-claim-gate.enabled`, independently prevents publication of prose
that calls a tight affine result a binding regime unless the selected artifact trace contains the
same exact/nearby `HELD`/`REFUTED` pair accepted by the cross-run promotion rule. This applies to
measurement-framed papers too: their scale-ladder pre-registration is not an affine
pre-registration, but affine prose still needs affine evidence.

## Locked independent-grid replication

The same-grid pair establishes that a chosen finite-grid bound is locally sharp; it does not show
that the coefficients travel to another grid. A zero held intercept also exposed a parser edge:
the verifier-directed refuting intercept `-0.32185` is not within five percent of zero, although
it is the instrument's exact requested counterpart.

`ConjectureInvestigationWriteBackService` now accepts that verifier-named intercept when the
relation slopes and grid match exactly. The generic tool loop then locks two follow-ups before
executing either: a deterministic independent-grid HOLD using the unchanged affine parameters,
and the verifier-named same-grid REFUTED call. The second start is derived only from the original
grid, so the model cannot choose it after seeing the local binding result.

`AffineBindingIndependentGridValidator`, enabled by
`app.research.affine-binding-independent-grid-gate.enabled`, routes a paper to `NEEDS_REVISION`
when a tight documented affine pair lacks a HOLD on that policy-locked grid. A manually selected
second window does not satisfy this gate; the trace must contain the deterministic follow-up with
the same relation, coefficients, intercept, width, and window count.

## Predicate baseline comparisons

P86 reported three exact finite predicate censuses against an external baseline. The windows
were adjacent replicas of the same width, so they could show local variation but could not test
whether the conclusion depended on that arbitrary width or window alignment.

`PredicateBaselineSensitivityValidator`, enabled by
`app.research.predicate-baseline-sensitivity-gate.enabled`, routes a baseline-comparison paper
to `NEEDS_REVISION` unless its selected `run_predicate_scan` trace contains:

- at least three valid scans;
- one unchanged numeric baseline across those scans;
- at least two window widths; and
- two non-identical windows with positive overlap, providing an offset check.

The narration prompt gives the professor the same requirement before synthesis. These checks do
not turn finite exact censuses into asymptotic evidence; they only require a minimally robust
finite comparison design before that finite evidence may be published as a baseline result.

## Verification

Focused unit tests cover verifier-named refutations, locked independent replication, publication
gates, and passing/failing predicate-window designs. Run them with:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export PATH="$JAVA_HOME/bin:$PATH"
mvn test -Dtest=ConjectureInvestigationWriteBackServiceTest,BindingExtensionPolicyTest,AffineBindingClaimValidatorTest,AffineBindingIndependentGridValidatorTest,PublishPaperTest
```
