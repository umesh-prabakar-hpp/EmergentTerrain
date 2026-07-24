# In-flight draft shutdown reconciliation

## Problem

The 2026-07-24 run assigned `P-b72c-77627894`, reached an empty professor-tool
trace, and began a second cascade finish. The global budget stop then closed the
JVM before the draft reached `UniversityService.publishPaper`. The paper had no
markdown export and was absent from both the created-paper and intentionally
suppressed-draft summaries.

## Behavior

`ResearchCycleService` now stores a JVM-scoped lifecycle snapshot for every
queued draft: paper ID, domain, topic, enqueue time, and most recent phase.
`ResearchNarrationService` advances the phase through queueing, refinement,
statistical framing, first pass, cascade finish, audit, professor tools and
resamples, student revision, and publish.

Normal publication and intentional suppression still remove the snapshot. At
context shutdown, `UniversityService` logs remaining snapshots as
`Run in-flight draft summary`, explicitly labelled as interrupted with their
last phase. This is audit telemetry only: it does not create a paper, alter the
budget drain, or treat an interrupted draft as deliberately suppressed.

## Verification

`ResearchCycleServiceDraftQueueTest` verifies phase capture and removal after a
normal settle. A fresh budget-limited app run should show any unfinished draft
under `Run in-flight draft summary` with a stable paper ID and phase.
