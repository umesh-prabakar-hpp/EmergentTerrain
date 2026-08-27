# Phase 40 — Growth-protocol admission

**Status: IMPLEMENTED — pending a normal-run observation.**

## Motivation

The 2026-08-02 17:32–17:40 EDT run allocated `P-b72c-77628005` as a
`mean_omega` growth study without a `PreRegistration`.  Its artifact consequently recorded
`evidence/pre-registration.json` as `null` and could never meet the computational-paper
candidate requirements.  A fluent finite calibration is not an acceptable substitute for a locked
study design.

## Design

`ConjectureInvestigationProtocol` now admits a GROWTH-frame investigation only when its family
has a concrete, locked computational protocol.  The existing `max_divisor_count`,
`max_prime_gap`, and mean-divisor second-order routes keep their established registrations.
Unregistered leading-order `mean_omega` and `mean_Omega` growth frames are rejected before paper
ID allocation, narration, or artifact creation.

This is deliberately an admission boundary, not a claim that leading-order arithmetic functions
are uninteresting.  A later protocol may re-enable them only after it locks the primary measurement,
selection rule, disjoint location/width confirmation, and literature-grounding requirement.

## Success criteria

- No admitted GROWTH-frame draft has a null pre-registration.
- An unregistered mean-omega/Omega growth frame is rejected before draft allocation.
- Existing registered GROWTH families retain their locked contracts.
- A later normal run logs the rejection or exercises an admitted registered family without a null
  artifact contract.

## Validation

- `ConjectureInvestigationProtocolTest` covers rejection of `mean_omega` and retention of the
  registered `max_divisor_count` growth family.

## Resolution status

- Implementation and focused regression coverage are complete.
- Normal-run validation is **NOT_RECHECKED** until a subsequent app run reaches the allocation path.
