# Verified-results ledger binding-pair gate — 2026-07-22

## Incident

The 2026-07-21 completed-run review found that an unpaired tight hold from
`P-b72c-77627880` was rejected by conjecture-pool write-back but still entered
the verified-results ledger. Terminal `PUBLISHED` status and low relative slack
were insufficient: later research could consume the ledger row despite the
paper artifact lacking the binding-regime evidence.

## Repair

`VerifiedResultLedgerService` now delegates tight-bound eligibility to the
same selected-trace rule used by conjecture-pool write-back. A `TIGHT_BOUND`
requires the exact HELD verification plus a nearby REFUTED parameter variant
on the identical relation and window grid. The structured tool arguments are
required to establish that scope; an unrelated or differently gridded refute
does not qualify.

Ladders and converged constants keep their existing ledger rules. The terminal
`PUBLISHED` gate in `UniversityService` remains the first cross-run gate.

## Regression coverage

The ledger tests cover an eligible same-grid pair, an unpaired tight hold, and
a refutation on a different grid. This keeps the ledger and conjecture-pool
evidence criteria aligned.
