# P75 promotion quarantine — 2026-07-21

## Reason

Run `b72c4e10` produced `P-b72c-77627875` with terminal status
`NEEDS_REVISION`: its selected bound had only confirmatory hold evidence and
did not execute the required binding/refutation counterpart.  Before the
terminal-state promotion gate was added, that run nevertheless wrote bounds
to the conjecture pool and a `TIGHT_BOUND` to `verified_results`.

## Repair performed

With all related application services down, the following three exact
`conjectures` rows were given this reversible value in `dropped_by_knob`:

`quarantine:binding_regime_uncovered:P-b72c-77627875`

| ID | Relation |
|---|---|
| `1219d2ea-eb1d-492b-9e58-90fa2c731b59` | `twin_prime_density <= 1.997 * mean_divisor_excess + 2 * max_divisor_count - 362.2958` |
| `1a90a8be-a4d8-4ad7-a4e9-80e31b9c5d0e` | `twin_prime_density <= 2 * richly_divisible_density + 2 * omega_ek_ratio - 3.4263` |
| `6e8f97be-c7a2-445f-a7b7-5d651e5482ac` | `twin_prime_density <= 2 * richly_divisible_density - 0.9700363769531251` |

The miner only serves rows with `dropped_by_knob IS NULL` (apart from its
separate counterexample-retired pathway), so these rows remain inspectable but
cannot seed a future investigation.

The one `verified_results` row for paper `P-b72c-77627875`
(`f0b892ac-5563-4197-8b84-62b1e8b36ef2`) was deleted because that table has no
quarantine state.  Its evidence remains in the paper artifact and run log; it
can be recreated only after a clean, binding-regime-complete rerun.

## Prevention

`UniversityService.permitsFindingsPromotion` now permits conjecture-pool
write-back and ledger recording only for terminal `PUBLISHED` papers.  Every
other terminal state retains its local trace for revision but cannot promote
findings across runs.
