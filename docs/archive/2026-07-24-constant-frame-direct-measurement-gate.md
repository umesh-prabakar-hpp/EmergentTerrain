# Constant-frame direct-measurement gate

The completed 2026-07-23 run published an affine ceiling for
`cramer_gap_ratio` against `deficient_density`. The verifier itself identified
`cramer_gap_ratio` as a constant-convergence quantity and instructed the
investigation to call `verify_constant`, but the selected trace never did.

`app.research.constant-frame-direct-measurement-gate.enabled` is therefore on.
For a `conjecture/<lhs>` topic listed in `ScoreCalibration.CONSTANT_FRAMED_LHS`,
a selected trace that runs `verify_conjecture` on that same LHS must also have a
usable same-LHS `verify_constant` result. Both a converged and a not-yet-
converged direct result satisfy the gate: each is real two-sided measurement
evidence. An unavailable, failed, or off-target call does not.

The gate routes the paper to `NEEDS_REVISION` and appends
`publish:constant_frame_direct_measurement_missing→needs_revision` to its call
path. It does not forbid exploratory affine calls; it prevents those calls from
being the paper's only load-bearing evidence. Set the flag to `false` for
rollback.

