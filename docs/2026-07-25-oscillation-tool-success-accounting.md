# Oscillation verifier success accounting — 2026-07-25

The D3 zero-success suppression gate intentionally withholds a professor-stage draft when no
real computation completed. During the 2026-07-25 run, P-b72c-77627903 invoked
`verify_oscillation` successfully, but `ToolMetrics.totalSuccesses()` did not include that tool.
The pipeline therefore treated three valid verification traces as empty and intentionally
suppressed the draft.

`verify_oscillation` now increments its own ToolMetrics success counter, which contributes to
the D3 aggregate and is reset with the other per-handle metrics. Literature retrieval still does
not count as a computational success: a literature-only trace remains correctly suppressible.
The regression test exercises the dispatcher with a real verifier result, not merely the counter.
