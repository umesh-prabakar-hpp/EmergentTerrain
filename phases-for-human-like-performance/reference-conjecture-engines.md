# Reference — Internal Research Engine Decisions

This document records the project-owned architecture decisions that govern
conjecture, recognition, symbolic-regression, and identity-proving work. It is
not a backlog or a catalogue of third-party projects.

## Current compute boundary

- The Java services own work selection, durable lifecycle state, validation,
  narration, review, and publication policy.
- The Julia math sidecar owns bounded numerical and symbolic execution behind
  explicit HTTP endpoints. It returns evidence, never publication or gameplay
  decisions.
- The sidecar is the sole compute path for physics topics and number-theory
  windows. Callers use its stable client interfaces rather than reintroducing
  in-process or per-call subprocess implementations.
- CPU-capable symbolic regression is available through the sidecar and returns
  an accuracy/complexity Pareto front. Its candidates remain evidence until
  they pass the normal validation and review gates.
- The optional identity-proving route embeds the established Python symbolic
  library through PyCall in the sidecar. Inputs are constrained and validated
  before execution.
- Constant and sequence recognition use bounded sidecar work, cached lookup,
  and explicit citations. An unrecognized value remains an unrecognized value;
  it must not be promoted to a claim.

## Research safeguards

- Every proposed relation needs a bounded execution plan, evidence record,
  counterexample or holdout attempt where applicable, and deterministic result
  classification before narration.
- A null result is retained as a null result. Missing replication, malformed
  output, weak evidence, or unresolved citation support blocks publication.
- Literature retrieval is bounded and read-only. A retrieved source is
  evidence only after the required reference-reading and validation steps.
- Discovery tools may propose candidates, but they cannot consume a live game
  queue, publish a paper, or create gameplay effects.

## Source and dependency policy

External projects are evaluated only when a concrete capability is adopted.
Before code, data, or a runtime dependency enters this repository, record its
origin, revision, and applicable license; include required notices and
dependency inventory material. Reference material alone is not retained as a
development dependency or source basis.

See [`THIRD_PARTY_PROVENANCE.md`](../THIRD_PARTY_PROVENANCE.md) for
import provenance and [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)
for distributed notices.
