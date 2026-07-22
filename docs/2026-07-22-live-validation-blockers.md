# Live validation findings — 2026-07-22

## Last completed run's literature requests

The selected artifact traces recorded these exact `search_literature` claims:

1. `The maximum prime gap in high-scale windows tracks the squared logarithm of the window start, with a slowly drifting normalized coefficient.`
2. `The density of integers n satisfying d(n) > omega(n)^2 is bounded above by twice the twin-prime density plus twice the Erdos-Kac variance ratio minus an empirically fitted intercept.`

Both returned the same fail-closed result: the Phase 35 SQLite lookup exceeded
its 15-second budget and issued no evidence.

## Acceptance result

The supported `prepare-hf-math-index.py --stage optimize` maintenance completed
against the completed local index. It did not solve the acceptance problem:
`Phase34RealIndexAcceptanceTest` still fails 4 of 7 checks. The two-concurrent
search smoke check returns fail-closed timeouts, one formerly slow claim exceeds
the strict 15-second deadline by 56ms, and two fixed expected-evidence fixtures
do not return their required evidence. No relevance or title-anchor gate was
weakened to hide those failures.

Re-running the exact completed-run claims after optimization produced a mixed
result. The max-prime-gap claim returned two title-anchored sources in 9.2s;
they require `read_reference` review before they can support the proposed growth
law. The richly-divisible-density claim still exhausted the 15-second budget
without issuing evidence.

Do not weaken relevance/title-anchor gates or manufacture a no-evidence result
as a citation merely to make this acceptance suite pass. Profile the expensive
theorem/full-text queries and concurrent connection behavior, then repeat the
fixed real-index acceptance suite after a retrieval fix.

## Phase 36

`RangePreservingChunkExecutorTest` passes the deliberate unchanged 500,000
integer density range: it covers ten contiguous 50,000-integer chunks, uses
the exact count-weighted reducer, preserves queue/compute/transport timing,
and resumes only the failed chunk. A live sidecar run remains pending:
PostgreSQL is down and requires an interactive sudo password to start; the
default Julia port 8088 is occupied by an unhealthy listener; and an isolated
port-8090 sidecar did not become healthy during bounded cold-start checks.

## Ledger remediation

The historical PostgreSQL listener is unavailable, so the P80
`verified_results` row could not be audited or deleted here. Do not initialize
a fresh database as a substitute: it would not contain the historical stale
row. Once the original database is available, audit `TIGHT_BOUND` rows and
remove only the P80 row whose selected trace lacks the required same-grid
nearby refutation.

## Focused regression verification

After the ledger gate repair, `ConjectureInvestigationWriteBackServiceTest` and
`VerifiedResultLedgerServiceTest` passed together: 30 tests, 0 failures. The
first exercises the selected-trace binding-pair gate; the latter verifies that
an eligible same-grid pair is retained while an unpaired or different-grid
refutation cannot enter the verified-results ledger.
