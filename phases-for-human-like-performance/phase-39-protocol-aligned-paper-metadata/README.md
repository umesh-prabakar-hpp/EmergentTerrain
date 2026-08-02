# Phase 39 — Protocol-aligned paper metadata

## Status: IMPLEMENTED — protocol-boundary hardening awaiting a post-fix run

## Problem

P-b72c-77627995 completed the locked second-order protocol for `dirichlet_error`, but its
persisted title still named `mean_divisor_count`. The body and title therefore described different
scientific subjects, obscuring what was actually measured for a human reader.

## Change

When `SecondOrderPivotPreRegistration` has complete exact evidence (the primary ladder plus both
registered holdouts), publication replaces the provisional leading-order title with the fixed,
protocol-aligned title `Measurement of dirichlet_error (finite-range envelope across scales)`.
Incomplete pivot traces retain their original title, so metadata never claims an unexecuted pivot.

## Success criteria

1. A fully executed locked pivot persists a title naming `dirichlet_error`.
2. An incomplete pivot preserves the original title.
3. The title adjustment does not alter status, evidence, or publication-label gates.

## Validation and resolution

- **IMPLEMENTED:** `SecondOrderPivotPreRegistrationTest` covers complete and incomplete traces.
- **Pending VALIDATED:** review the next real completed pivot artifact and confirm its markdown
  title, pre-registration target, and executed trace agree.
- **Unresolved:** P-b72c-77627995 is historical output; it is not rewritten retroactively.

## Protocol-boundary hardening (2026-08-02)

The Java-21 run that produced P-b72c-77628000 and P-b72c-77628001 exposed a distinct boundary
failure: their ordinary measurement studies had no locked second-order protocol, but the broad
professor directive still encouraged `verify_constant`, `verify_oscillation`, or `verify_growth`.
The final gate safely demoted both outputs, but only after a post-hoc pivot had consumed the
experiment.

The prompt now states an explicit protocol boundary for textbook-leading-order conjecture topics
without `SecondOrderPivotPreRegistration`, and both rerun and publication checks require that exact
locked protocol rather than any non-null registration. This preserves finite calibration while
preventing an ordinary registration from being mistaken for authorization to change the study
subject.

**Success criterion:** a future ordinary `mean_omega`/`mean_Omega` run keeps its registered subject
and does not emit `second-order:engaged:*`; an explicitly locked `dirichlet_error` protocol remains
eligible for its ladder and independent holdouts.
