# Durable draft-ID allocation

## Problem

Published papers persist in the database, but a draft suppressed before publication has neither a
paper row nor markdown output. With archived per-run output, the former `nextPaperId` bootstrap
could therefore reuse the suffix of a suppressed draft after restart. That invalidates the stable
paper-ID assumption used by shutdown reconciliation, artifacts, and incremental paper review.

## Design

`PaperIdAllocationLedger` reserves an ID before a draft enters narration. It appends the ID and
allocation timestamp to a room-specific file under `simulation-history/research-paper-ids/`, takes
a file lock while calculating the next suffix, and forces the append to storage before returning.
The next suffix is above both this durable high-water mark and the legacy DB/markdown high-water
mark, so existing installations migrate without resetting their sequence.

The allocation log is deliberately outside `research/`: that directory can be archived or reset at
startup. The log contains no findings, verdicts, or tool output. It is audit metadata only and must
not enter publication, promotion, or scientific evidence paths.

## Verification

`PaperIdAllocationLedgerTest` covers a restart after an unpersisted/suppressed allocation, a legacy
published-floor migration, and a malformed append-only log line.
