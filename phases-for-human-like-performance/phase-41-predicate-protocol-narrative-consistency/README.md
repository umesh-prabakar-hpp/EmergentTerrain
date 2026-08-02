# Phase 41 — Predicate-protocol narrative consistency

## Status: IMPLEMENTED — awaiting a normal-run observation

## Problem

`P-b72c-77628006` executed its three locked abundant-or-perfect predicate censuses exactly, but
its final Findings still called the two confirmation windows “post-hoc descriptive checks.”  That
contradicts the artifact's pre-registration and makes a properly planned finite experiment appear
weaker and less reproducible than it is.  The existing predicate gate checked execution only, so
it could not catch the prose error.

## Change

`PredicateStudyPreRegistration.narrativeConsistencyFailures` now detects only a narrow case:
prose labels a registered confirmation/location/width window post-hoc or exploratory **after**
the complete locked predicate plan is present in the selected trace. The existing enabled
predicate-study pre-registration gate routes that contradiction to `NEEDS_REVISION` and records a
specific call-path stamp. It does not prohibit genuinely extra scans from being described as
exploratory.

## Success criteria

1. A fully executed locked predicate protocol cannot publish cleanly while calling its registered
   confirmation windows post-hoc or exploratory.
2. A genuinely additional scan remains eligible to be described as exploratory.
3. Missing or altered registered scans remain governed by the existing execution gate.

## Validation and resolution

- **IMPLEMENTED:** `PredicateStudyPreRegistrationTest` covers both the contradiction and a
  genuinely additional exploratory scan under Java 21.
- **Pending normal-run observation:** a later dynamic predicate paper must either avoid the
  contradictory wording or persist `publish:predicate_study_narrative_contradiction`.
- **Historical artifact:** `P-b72c-77628006` remains immutable and is not retroactively edited.
