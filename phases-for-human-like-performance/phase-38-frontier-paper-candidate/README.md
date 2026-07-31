# Phase 38 — Frontier-Paper Candidate Protocol

## Decision

The application must not manufacture a “frontier paper” from a high score, a large finite range,
an LLM novelty assessment, or a new affine coefficient. Those are useful computational
observations, but they do not establish mathematical novelty. This phase therefore adds a
stricter *candidate protocol*, not a reader-facing frontier label.

A paper can become an artifact-backed computational-paper candidate under the existing Phase 34
rules. A genuine human-quality frontier paper remains an external scholarly judgment: it needs a
new result relative to the relevant literature, clear mathematical significance, independently
reproducible computation or proof, and expert review. The application can make that judgment
auditable and conservative; it cannot certify it by itself.

## Review findings (2026-07-30)

The paper pipeline already has several strong pieces:

- `PreRegistration`, deterministic `ResultClassification`, selected-trace artifacts, and
  `PublicationStateResolver` separate computation from prose and publication readiness.
- Phase 34/35 literature retrieval requires an issued local `HF...` key, `read_reference`, and
  matching Related Work citation before an artifact-backed candidate label can be earned.
- `CramerGapEnvelopePreRegistration`, `MaxPrimeGapMultiScalePreRegistration`, and
  `MaxDivisorCountMeasurementPreRegistration` show that finite-window claims need fixed designs,
  non-collapsing holdouts, and source grounding.
- `MertensFrontierService` / `FrontierEscalationService` can extend a validated computation past
  the direct sieve, but their numerical reach is evidence of computation—not novelty or proof.

The material failure was in the handoff between the conjecture miner and the paper pipeline.
`ResearchCycleService.frontierDomainFor` treated a name containing `mertens` as eligible for
Path-B frontier framing. A mined relation such as `mertens_delta <= k * omega_excess_density + c`
could therefore be described as an asymptotic frontier question even though `mertens_delta` is a
signed *window increment* and the fitted affine relation is not the classical mathematical
object. The resulting paper is correctly only a computational note, but the prompt was spending
the research budget on the wrong claim.

## Implemented vertical slice

`MertensEnvelopePreRegistration` now replaces an affine Mertens-window investigation with
`locked-mertens-window-envelope:v1`:

| Check | Fixed design |
|---|---|
| Target | signed `mertens_delta`, never a mined affine RHS |
| Normalizer | `sqrt_window_size`, matching the local instrument definition |
| Primary | start 100,000,000; 5,000 × 20 windows |
| Location holdout | disjoint start 900,000,000; 5,000 × 20 windows |
| Width holdout | disjoint start 1,500,000,000; 10,000 × 10 windows |
| Tool | `verify_oscillation`, `scale_ladder=false` on every exact grid |
| Literature | normal `search_literature → read_reference → [HF...]` Related Work chain |

The allocation path registers `mertens_delta` as `OSCILLATION` and supplies this protocol before
draft-ID allocation. Thus the measurement frame wins over generic frontier framing. The narrator
receives an exact directive and prohibition on affine, RH, or asymptotic-proof language. With
`app.research.mertens-envelope-preregistration-gate.enabled=true`, a missing primary, location,
or width call is `NEEDS_REVISION` and produces an auditable call-path stamp. The existing
computational-plan citation deficit still prevents promotion without a readable, cited source.

This establishes a credible finite measurement lane. It does **not** make the Mertens-window
study frontier work: the Mertens function and its computation are classical, and this local
normalization is a descriptive instrument choice.

## Frontier-candidate standard

Before any future paper is described to a human as a frontier candidate, require all of the
following in addition to ordinary publication gates:

1. **A source-positioned question.** Lock the exact mathematical object and the known result or
   gap from a successfully read primary source before computation. A search miss is coverage
   state, not evidence that the literature is absent.
2. **A discriminating pre-registration.** State the predicted departure, competing explanation,
   primary range, and at least two genuinely independent holdouts before seeing results. Holdouts
   must differ in placement and effective width/method, not merely repeat adjacent samples.
3. **A result that survives the protocol.** Keep the complete selected trace, all negative calls,
   scope/definition provenance, and uncertainty or finite-window limitations. A calibration,
   refitted relation, or no-mechanism result is a computational note.
4. **Independent computational corroboration.** Reproduce the decisive result with an
   independently implemented algorithm or a separately validated external artifact. The current
   shared Julia/Java verifier is not independent replication by itself.
5. **Novelty adjudication outside generation.** Compare the final claim against the read sources
   and seek expert review. The automated pipeline may route failures conservatively; it must not
   infer field novelty from a bounded local search.

## Next implementation phases

1. Add a versioned `FrontierCandidateProtocol` schema that can represent source claim, competing
   hypothesis, two independent methods, and predeclared decision rule. Keep it unlabelled until
   both methods finish.
2. Add a second engine/artifact adapter for independent reproduction. Require agreement within a
   predeclared tolerance and preserve both executable environments and hashes.
3. Add a reviewer-facing novelty dossier: exact claim, read-source passages, search coverage,
   novelty differences, all holdouts, negative results, and reproduction hashes. It must say
   `NOT ESTABLISHED` when the source chain is incomplete.
4. Pilot on a question whose literature explicitly identifies a quantitative open gap. Do not use
   a mined cross-scalar affine relation as the candidate question.
5. Measure false-promotion rate before allowing a new reader-facing tier. A failed independent
   reproduction or source challenge must demote, never be narrated away.

## Acceptance checks

Run:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-temurin-jdk
export PATH="$JAVA_HOME/bin:$PATH"
mvn test -Dtest=ConjectureInvestigationProtocolTest,MertensEnvelopePreRegistrationTest,ScalarOpennessTest,ResearchNarrationServiceCallPathTest
```

Then run the normal Phase 35 artifact smoke against the completed local index before relying on
the literature route:

```bash
./scripts/run-phase35-artifact-smoke.sh
```

Rollback is `app.research.mertens-envelope-preregistration-gate.enabled=false`; that disables
the publication backstop only. The allocation and narration route remain intentionally
measurement-first, because reverting to a mined affine relation would reintroduce the reviewed
scientific-framing defect.
