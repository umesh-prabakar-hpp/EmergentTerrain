# Live validation findings — 2026-07-22

## Last completed run's literature requests

The selected artifact traces recorded these exact `search_literature` claims:

1. `The maximum prime gap in high-scale windows tracks the squared logarithm of the window start, with a slowly drifting normalized coefficient.`
2. `The density of integers n satisfying d(n) > omega(n)^2 is bounded above by twice the twin-prime density plus twice the Erdos-Kac variance ratio minus an empirically fitted intercept.`

Both returned the same fail-closed result: the Phase 35 SQLite lookup exceeded
its 15-second budget and issued no evidence.

## Acceptance result

`Phase34RealIndexAcceptanceTest` was run against the completed local index.
It still fails under the current host load: known fixtures and the concurrent
smoke check time out before evidence is issued. The attempted supported
`prepare-hf-math-index.py --stage optimize` maintenance could not proceed
because SQLite reported `database is locked`; no index mutation occurred.

Do not weaken relevance/title-anchor gates or manufacture a no-evidence result
as a citation merely to make this acceptance suite pass. First identify the
process holding the immutable index lock, then run the supported optimize stage
and repeat the fixed real-index acceptance suite.

## Phase 36

`RangePreservingChunkExecutorTest` passes the deliberate unchanged 500,000
integer density range: it covers ten contiguous 50,000-integer chunks, uses
the exact count-weighted reducer, preserves queue/compute/transport timing,
and resumes only the failed chunk. A live sidecar run remains pending because
the Julia sidecar, application, and PostgreSQL listener are down on this host.

## Ledger remediation

The historical PostgreSQL listener is unavailable, so the P80
`verified_results` row could not be audited or deleted here. Do not initialize
a fresh database as a substitute: it would not contain the historical stale
row. Once the original database is available, audit `TIGHT_BOUND` rows and
remove only the P80 row whose selected trace lacks the required same-grid
nearby refutation.
