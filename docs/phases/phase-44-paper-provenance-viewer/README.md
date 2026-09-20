# Phase 44 — Paper-level provenance viewer

**Status: IMPLEMENTED — read-only research-detail projection and UI. No research execution,
publication, quality-grade, or game-state behavior changes in this phase.**

## Purpose

The Research Lab currently lists a paper's title, workflow status, quality summary, protocol-reuse
summary, hypothesis, findings, and conclusion. It can separately load immutable quality and
remediation assessment histories. That makes a paper visible, but a reader cannot inspect the
bounded evidence that supports its displayed state without locating machine-local artifact files.

This phase adds a paper-level provenance viewer. For one paper in one room, it explains:

- which retained artifact bundle was found and whether its manifest is intact;
- the locked study/protocol summary used for the paper;
- which bounded computation and literature evidence categories were retained;
- the existing workflow, quality, and remediation records; and
- what evidence is absent, unreadable, or intentionally not displayed.

The viewer is an audit surface. It must never reinterpret a paper, mark evidence valid, run a
reproduction command, start a sidecar, call an LLM, publish a result, alter a quality assessment,
or mutate game state.

## Current implementation and gap

ET already has the necessary evidence sources, but they are not one display-safe read model:

| Current source | Existing content | Viewer use |
|---|---|---|
| `ResearchPaper` and the v1 `ResearchPaperSummary` | paper identity, workflow state, rendered result fields, quality projection, protocol-reuse summary | retain as the identity and workflow section; do not expose the persistence entity |
| artifact package | `paper.md`, locked pre-registration, selected tool trace, literature audit, rendered findings/conclusion, `manifest.json`, and declared `reproduce.sh` | derive a bounded provenance projection after validating the artifact belongs to the requested paper |
| immutable quality-assessment API | grade, rubric/model provenance, unmet requirements, contradictions, bounded evidence references | keep the existing on-demand history and link it from the viewer |
| immutable remediation-assessment API | remediation outcome and bounded evidence-reference identifiers | keep the existing on-demand history and link it from the viewer |
| LLM trace directory | raw call logging, potentially including prompts, completions, provider data, and machine-local context | explicitly out of scope; never return it through the viewer |

`ArtifactPackageWriter` already writes a self-contained bundle at paper persistence. Its location is
derived from a room prefix and paper ID, and output directories can later be archived. The raw
location therefore is not a stable browser contract. The viewer needs a research-owned catalog of
validated artifact references and must return only a stable projection, not file paths or a generic
file-download route.

## User experience

Each Research Lab paper card receives a **Provenance** button next to its existing assessment
history controls. Selecting it loads one compact, read-only detail panel.

```text
Paper P-…  [Provenance]
  Artifact
    retained · manifest schema 2 · integrity: recorded files verified

  Study
    locked protocol fingerprint · selected domain/topic · declared computation plan status

  Evidence
    pre-registration retained
    6 selected tool calls: 5 completed, 1 failed
    literature: 2 issued keys · 1 read key · 1 cited key
    rendered findings and conclusion retained

  Review state
    workflow · quality grade · blockers · protocol reuse · remediation outcome

  Limits
    no independent replication record retained
    raw model prompts and responses are intentionally unavailable
```

The panel uses progressive disclosure:

1. the first response contains only counts, status, identifiers, and short display-safe summaries;
2. existing quality and remediation history controls remain separate on-demand reads;
3. an unavailable, pending, or incomplete artifact renders an explicit state, never an invented
   summary.

All new browser rendering must create text nodes or use a vetted escaping helper. It must not insert
artifact content, tool arguments, or model-produced strings through interpolated `innerHTML`.

## Read boundary and contract

Add Spring-free records to `libs/research-contract`. These are the only browser-facing provenance
shapes shared across the game/research boundary.

```text
ResearchPaperProvenanceSummary
  paperId
  artifact                     ResearchArtifactIntegritySummary
  study                        ResearchStudyProvenanceSummary
  evidence                     ResearchEvidenceProvenanceSummary

ResearchArtifactIntegritySummary
  available
  integrityStatus              PENDING | MISSING | VERIFIED | MISMATCHED | NOT_VERIFIED |
                               UNSUPPORTED_SCHEMA
  schemaVersion, createdAt
  publicationLabel, paperStatus, sourceRevision
  declaredFileCount

ResearchStudyProvenanceSummary
  preregistrationAvailable
  hypothesisDeclared, predictionDeclared, falsificationConditionDeclared
  computationalStudyPlanDeclared, protocolReuseRecorded

ResearchEvidenceProvenanceSummary
  recordedToolCallCount
  literature                   ResearchLiteratureEvidenceSummary
  findingsRetained
  conclusionRetained

ResearchLiteratureEvidenceSummary
  available
  issuedReferenceCount, readReferenceCount
  citedReferenceCount, citedReadReferenceCount
  sourceGroundedRelatedWork
```

Do not add `Path`, raw JSON, prompt, completion, exception text, command text, environment value,
token count, provider credential, or arbitrary artifact filename to this contract. A declared
reproduction command remains a retained artifact fact, but this UI exposes only whether it was
declared; it never displays or executes it in Phase 44.

Add one authenticated, read-only v1 resource:

```text
GET /api/v1/research/rooms/{roomId}/papers/{paperId}/provenance
```

It uses the existing research-query credential and room/paper ownership checks. Missing or
cross-room papers return no provenance details. For a known paper, an absent, unsupported,
malformed, unreadable, or not-yet-catalogued artifact returns an ordinary typed summary; it does
not disclose whether an arbitrary host path exists. Transport/authentication failure remains an
HTTP failure. There is no POST, download, artifact-path, or execution endpoint in this phase.

`ResearchQueryClient`, its HTTP implementation, the local compatibility implementation, and the
game controller each receive the same one-method extension. Remote mode consumes only the stable
v1 DTO. The local transition adapter uses the same bounded reader against its configured output
root, without exposing a persistence entity or artifact content.

## Artifact catalog and projection service

The implemented research-owned `research_paper_artifact_catalog` table stores a serialized,
display-safe summary rather than artifact paths:

```text
paper_id (unique), room_id, summary_json, updated_at
```

The catalog service is a bounded, read-only filesystem observer:

1. resolve only known paper IDs from the research-paper read model under the configured
   research-output root and its archive subtree;
2. consider only the fixed `{room-prefix}/artifacts/{paperId}/manifest.json` layout;
3. reject path traversal, symbolic-link escape, a manifest whose `paper_id` disagrees with the
   directory/request, an unsupported schema, or a manifest outside the configured root;
4. store only the compact derived summary in the research-owned catalog;
5. never write, move, repair, or delete an artifact package.

Catalog refresh is bounded and runs through the research-owned scheduler at a low fixed cadence.
A detail request uses its catalog entry; it does not walk the artifact tree. If the entry is absent,
it returns `PENDING` and lets the next refresh discover it. This avoids unbounded browser requests
and keeps archival layout changes out of the v1 contract.

`ResearchPaperProvenanceProjectionService` reads only the catalogued package. It parses exactly
the Phase-44 allowlisted files:

- `manifest.json` for schema, publication label, file inventory, and integrity state;
- `evidence/pre-registration.json` for the locked-study summary;
- `evidence/tool-trace.json` for the retained call count only;
- `evidence/literature-audit.json` for issued/read/cited counts only;
- `results/findings.md` and `results/conclusion.md` only to determine retained/not-retained state.

Unknown fields are ignored and future artifact schemas fail closed to `UNSUPPORTED_SCHEMA`; the
service must not attempt to deserialize arbitrary classes or fall back to raw text. Manifest checksum verification
is bounded by the manifest's declared files and configured byte/file ceilings. Its result is an
integrity observation, not approval of a paper's scientific claim.

## UI changes

Extend `game.js` with a `togglePaperProvenance` loader patterned after the existing quality and
remediation history loaders.

- Fetch the provenance endpoint only after a reader selects **Provenance**.
- Cache the response per `(roomId, paperId)` only for the active browser page.
- Render availability and integrity before all other sections.
- Render only tool-call and literature-audit counts; do not create a generic raw-trace viewer.
- Render literature evidence as counts, not keys or retrieved passages.
- Reuse the existing quality/remediation controls rather than duplicating their histories.
- On a `PENDING`, `MISSING`, `NOT_VERIFIED`, or `UNSUPPORTED_SCHEMA` response, show the returned
  state plainly and do not replace it with optimistic language.
- On a failed request, show “Provenance is unavailable” and preserve the existing paper card.

Add focused CSS for the compact provenance sections, status labels, and limited receipt list. The
view must remain usable in the Research Lab sidebar: no full-screen raw JSON, automatic downloads,
or expandable unbounded lists.

## Implementation order

1. Define the Spring-free DTOs and bounded fixed-layout artifact reader in `libs/research-contract`.
2. Add the research-owned catalog schema and scheduled projection refresh.
3. Add the research-service endpoint and dedicated query-key protection.
4. Extend `ResearchQueryClient`, HTTP/local clients, and the game controller.
5. Add the on-demand Research Lab panel using DOM text nodes for all provenance values.
6. Cover reader isolation, controller behavior, remote-client mapping, and query-key protection.

## Acceptance criteria

- A reader can open provenance for a paper and see retained study, artifact, tool-call-count, and
  literature-evidence-count summaries without shell access to research output.
- The game reaches the data only through the authenticated v1 query contract.
- A cross-room request returns no provenance details.
- A missing, archived, malformed, tampered, or unsupported artifact produces an explicit typed
  integrity state and never a partial invented reconstruction.
- No response contains raw LLM prompts/responses, credentials, absolute paths, reproduction-command
  text, arbitrary artifact files, or exception text.
- The endpoint, catalog scan, and UI are read-only: they cannot execute computation, sidecars,
  scripts, reproduction commands, quality assessment, publication, delivery, or game effects.
- The list endpoint remains compact; provenance is loaded only for the selected paper.
- Contract, controller, local/remote client, artifact-security, and query-key tests cover the
  display-safe schema.

## Explicit non-goals

Phase 44 does not add a reproduction executor, artifact upload/download system, arbitrary file
browser, new citation retrieval, quality-grade promotion, independent replication, researcher
workspace, remote compute, or any gameplay mutation. Those would require separate contracts,
ownership decisions, and safety reviews.
