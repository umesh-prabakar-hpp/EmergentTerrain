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

Focused unit tests cover same-grid versus unrelated refutations, the publication claim gate, and
passing/failing predicate-window designs. Run them with:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export PATH="$JAVA_HOME/bin:$PATH"
mvn test -Dtest=BindingExtensionPolicyTest,AffineBindingClaimValidatorTest,PredicateBaselineSensitivityValidatorTest,ResearchNarrationServicePivotNoteTest
```
