# Phase 46 — Publication-eligibility preflight

**Status: PLANNED — preserve the existing evidence and publication gates; move their decisive,
deterministic checks before expensive narration and review.**

## Purpose

The research worker can produce traceable computational research notes, but a draft can still
spend substantial model budget on narration and panel review when its retained record is already
unable to meet the publication contract. Phase 46 introduces a fail-closed preflight for that
condition.

The purpose is not to make publication easier or to relabel notes as papers. It is to allocate
expensive work only to candidates with a feasible path to `PUBLISHED`, while retaining every
failed, null, and incomplete result honestly as research evidence.

The preflight runs after deterministic study/protocol selection and before first-pass narration.
It may defer or reject a draft with structured unmet requirements; it must never invent a source,
computation, sensitivity result, citation, replication, or AI-quality grade.

## Evidence motivating the phase

The 2026-10-10 research-only run reached its configured $10 budget and created three outputs:

| Paper | Terminal workflow state | Decisive unmet requirements |
|---|---|---|
| `P-b72c-77628030` | `REJECTED` | Constant-limit preregistration/sensitivity coverage was not retained. |
| `P-b72c-77628031` | `NEEDS_REVISION` | The selected source key had no usable `read_reference` passage; citation grounding and revision parsing were incomplete. |
| `P-b72c-77628032` | `REJECTED` | The locked max-prime-gap protocol lacked its required fit/refutation stages; an uncited duplicate and citation/sensitivity deficits also remained. |

The same run demonstrated that a longer bounded local retrieval path can obtain evidence for slow
queries: observed evidence searches completed in 56.6 s and 88.7 s, with theorem batches up to
about 35 s. Literature timeouts were not the cause of those publication failures. Phase 46 must
improve protocol, readable-evidence, sensitivity, and duplicate readiness rather than weaken
evidence rules or raise timeouts further.

## Publication eligibility contract

Before narration, a candidate is eligible only when all applicable requirements are true:

```text
locked protocol is complete and executable
  AND every mandatory protocol stage has a retained plan
  AND a readable, relevant local source key is reserved when literature is required
  AND the reserved key can be cited in Related Work
  AND required sensitivity/reproduction work has an executable deterministic plan
  AND no uncited equivalent prior paper would make the draft a duplicate
```

Eligibility means only that a paper may proceed. It is not evidence, publication approval, a
quality grade, or permission to bypass later narration, review, source, sensitivity, replication,
deduplication, consistency, or publication-state gates.

The existing final publication criteria remain authoritative: a complete locked plan, executed
trace, source-grounded related work, applicable sensitivity and reproduction evidence, and no
independent validation failure. `RESEARCH_NOTE`, `RESEARCH_DRAFT`, `HUMAN_QUALITY_PAPER`, and
`BEYOND_HUMAN_BENCHMARK` remain AI-led quality assessments from Phase 43, separate from workflow
status. A preflight must never award or infer a quality grade.

## Required work

### 1. Protocol-completeness preflight

Add a protocol-owned completeness contract that enumerates the exact mandatory stages and binds
them to the frozen registration. For the locked max-prime-gap protocol, that includes the tight
fit, changed-intercept refutation, and every ordered holdout. A trace containing only later
holdouts must fail before narration, with the missing stages recorded durably.

The executor remains the only authority that produces computation records. The preflight may
verify registered stages and inputs; it must not fabricate a missing computation or treat a
successful later holdout as proof that earlier steps occurred.

### 2. Readable-evidence reservation

For a literature-required study, the deterministic local search/read path must establish a
readable and relevant source before narration:

1. Run the ordinary `search_literature` path.
2. Attempt `read_reference` only for issued local keys.
3. Select a readable relevant result under the existing title/evidence policy.
4. Persist its key as a bounded reservation for the draft.
5. Bind Related Work validation to that same key.

If a returned key has no usable passage, try the remaining evidence-safe results from the same
query result before allowing narration. If none is readable, defer the draft as
`AWAITING_READABLE_LITERATURE`; do not create a prose-only citation or treat a search result as a
read source.

### 3. Deterministic sensitivity and reproduction planning

For every protocol that requires sensitivity, scale/parameter robustness, or reproduction, bind
the required scans and tolerances before narration. The protocol executor must produce the
corresponding trace or explicitly record the unmet requirement. The LLM can explain the evidence
but cannot satisfy the requirement through prose.

This work should reuse existing deterministic executors and verified trace formats rather than add
a second computation engine or a paper-specific heuristic.

### 4. Duplicate-aware admission

Before draft allocation, compare the frozen protocol fingerprint and scientific question with
prior room papers. An equivalent prior paper must either be cited and extended by a materially
distinct, registered follow-up, or prevent allocation of a duplicate draft.

The check must preserve Phase 43's settled-protocol policy: a prior `HUMAN_QUALITY_PAPER` or
`BEYOND_HUMAN_BENCHMARK` settles an identical protocol, while a note/draft may support an
evidence-improving follow-up only with retained provenance. It must not erase prior work or claim
novelty from a rerun.

### 5. Structured revision reliability

When the self-review response cannot be parsed, perform one bounded schema-constrained retry or
persist a deterministic review-template result. A parse fallback must remain visible to final
review, but it should not leave otherwise eligible evidence unrepresented. This retry is not
permission to retry failed computation, literature retrieval, or publication validation.

## Boundaries

- Do not relax source, title-anchor, `read_reference`, citation, sensitivity, reproduction,
  protocol, deduplication, or review gates.
- Do not add external web/AI search as evidence. The local immutable literature index remains the
  evidence authority.
- Do not increase model budgets simply to let ineligible drafts reach a known final rejection.
- Do not turn a preflight deferral into a null result, no-match, publication, or quality claim.
- Do not allow the preflight to mutate game state, modify the immutable literature index, or alter
  Phase 42 service-ownership boundaries.

## Delivery sequence and acceptance criteria

1. Define the durable, versioned preflight outcome and unmet-requirements record.
2. Implement locked-protocol stage validation with max-prime-gap fixtures proving a missing fit or
   refutation fails before narration.
3. Implement readable-key reservation and fixtures proving unreadable keys cannot reach Related
   Work as citations.
4. Bind applicable sensitivity/reproduction plans to deterministic execution and prove incomplete
   traces are deferred before narration.
5. Implement duplicate-aware allocation with cited follow-up and uncited-equivalent rejection
   fixtures.
6. Add the single bounded structured revision retry/template path.
7. Run a controlled research-only session and show, for every narrated candidate, retained
   preflight eligibility, a readable cited key when required, complete protocol-stage coverage,
   and final workflow/AI-quality state.

Success is fewer avoidable narration/review calls and a clear audit trail for every deferred
candidate. It is not measured by a higher publication count alone: a correct preflight may defer
more work while preserving stronger evidence standards.
