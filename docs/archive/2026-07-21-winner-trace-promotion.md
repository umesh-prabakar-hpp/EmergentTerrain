# Winner-trace-only conjecture promotion — 2026-07-21

## Incident

`P-b72c-77627879` was correctly written as an internal note: its selected
trace contained loose holds and explicitly reported that a tight binding pair
was unavailable.  The prior write-back path nevertheless combined all
best-of-N candidates and re-read the full per-paper run log.  It promoted a
tight hold found outside the selected manuscript without recording the nearby
refutation required to demonstrate the binding regime.

## Decision

`UniversityService` now passes only `NarrationResult.toolCallTrace()` from the
selected winner to `ConjectureInvestigationWriteBackService`.  The accumulator
and run-log-file supplement were removed.  The selected trace is serialized
into the paper artifact, so it is the only evidence cross-run state can use.

For each bound, write-back now requires:

1. the existing terminal `PUBLISHED`, failure-region, coverage, tightness, and
   triviality gates, including an emitted relative-slack value within the
   tightness ceiling;
2. the exact held verification in the selected trace; and
3. a nearby parameter variant that is `REFUTED` on the same relation, window
   start, window size, and number of windows.

The nearby check is bounded to five percent per affine parameter (with a
small absolute floor for zero-valued intercept probes).  The verifier remains
the authority on whether that nearby variant refutes; the gate only verifies
that the pair is comparably scoped and artifact-auditable.

This does not change the shared LLM budget or require per-paper budget credit.
