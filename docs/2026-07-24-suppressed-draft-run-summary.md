# Suppressed-draft end-of-run summary

A draft identifier is allocated before narration, while several safety paths can
intentionally suppress the draft before it becomes a persisted paper. Previously
the shutdown summary listed only created papers, so the skipped identifiers looked
like unexplained sequence gaps to post-run review.

`UniversityService` now keeps a JVM-scoped list of intentionally suppressed drafts.
At shutdown it emits `Run suppressed draft summary` after the existing created-paper
summary. Each entry includes the paper ID, domain, topic, and reason:

- `blank_narration`
- `narration_<status>` for an explicit narration suppression sentinel
- `numeric_inconsistent`
- `missing_methods`

The list is diagnostic only. It does not persist a paper, change the suppression
decision, or consult historical database rows; it makes the completed application's
own terminal state auditable.

