# Phase 39 — Protocol-aligned paper metadata

## Status: IMPLEMENTED — awaiting a post-fix run

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
