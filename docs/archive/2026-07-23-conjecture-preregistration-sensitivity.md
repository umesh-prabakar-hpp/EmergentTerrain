# Conjecture pre-registration and binding sensitivity — 2026-07-23

## Incident

`P-b72c-77627884` registered the two-term relation with intercept `-362.2958`.
The two-term binding corrective flow then used the verifier's suggested intercept
`-386.2461` as a replacement and retained only that corrective trace. The paper
honestly reported the executed refutation, but did not answer the registered claim.

## Decision

Affine conjecture investigations now create a versioned, machine-checkable
pre-registration containing the exact lhs, rhs term(s), coefficients, and
intercept. The professor prompt requires an exact held-out `verify_conjecture`
call before exploratory work. A selected trace without that call routes the paper
to `NEEDS_REVISION`.

Binding probes remain valuable, but are explicitly sensitivity analyses. They may
change an intercept to locate a binding/refuting pair; they cannot replace the
registered test. Corrective and scope-recovery calls are appended to the selected
trace, so the artifact shows the original registered call and every later probe.

Measurement-framed investigations are deliberately excluded from this affine-bound
contract: their registered object is a scale-ladder measurement, not the miner's
placeholder inequality.
