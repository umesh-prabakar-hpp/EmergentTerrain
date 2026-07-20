# Trace-grounded revision fallback — 2026-07-20

## Incident

`P-b72c-77627871` executed two `verify_conjecture` calls that refuted its proposed relationship,
but the student revision returned malformed JSON. The old tool-path behavior logged
`rev:parse_failed→fp` and published the earlier first-pass prose, which said that no adversarial
check had run. The manifest validator correctly routed the paper to `NEEDS_REVISION`, but that was
too late to make its reader-facing findings honest.

## Change

For a malformed revision with a non-empty professor trace,
`ResearchNarrationService.traceGroundedRevisionFallback` now replaces Findings, Discussion, and
Conclusion with trace-only prose. Each invocation is rendered with its real `call_N` identifier.
If any tool result contains `REFUTED`, the conclusion explicitly says the relationship is refuted
on the executed scope and that no positive result is claimed. A trace without `REFUTED` receives an
abstaining conclusion, not an inferred positive result.

The no-trace path remains `rev:parse_failed→fp`: there is no evidence to render and this change
must not manufacture a result. The new call-path stamp is
`rev:parse_failed→trace_fallback` for operational correlation.

## Regression coverage

`TraceGroundedRevisionFallbackTest` feeds malformed revision JSON plus a refuted
`verify_conjecture` result and asserts that the output contains the real call id and counterexample,
omits stale first-pass prose, and concludes `REFUTED` with no positive claim.

## Follow-up

This removes the immediate contradiction failure. It does not diagnose why a given Phase 35 lookup
was interrupted; preserve its 15-second bounded no-evidence behavior while reproducing the logged
FTS plan against the local SQLite index.
