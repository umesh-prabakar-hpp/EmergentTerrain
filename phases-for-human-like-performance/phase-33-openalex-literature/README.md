# Phase 33 — OpenAlex Literature Acquisition & External Citation Resolution

**Status: scoping proposal (2026-06-06) — nothing built.** Scopes the *external-data layer* that
two standing designs assume but neither specifies: the deferred **30.4 literature grounding**
([`../phase-30-conjecture/phase-30-genuinely-novel-claims.md`](../phase-30-conjecture/phase-30-genuinely-novel-claims.md)
§30.4, layered-resolution design updated 2026-06-04 per the ARC review) and the **32.5
grounding sidecar** ([`../phase-32-manuscript-grounding/phase-32-manuscript-grounding.md`](../phase-32-manuscript-grounding/phase-32-manuscript-grounding.md)
§32.5, which selected the PaperQA2 engine for 30.4 but left its *corpus* as "a local directory of
curated number-theory references — built once, enriched offline via the metadata cascade" without
scoping where that corpus comes from). OpenAlex is the named primary source in both; this phase
scopes the client, the corpus build, and the citation resolver against the **API as it actually is
in June 2026** — which has changed materially since those designs were written.

Every adopted item ships flag-gated **OFF** (`@Value … = false`) per house convention; the
30.4/32.5 deferral discipline is **unchanged** — nothing here flips until the standing
first-promotion trigger fires. What *can* proceed anytime: the client and corpus are dormant
assets (zero LLM calls, zero request-path coupling).

**Source surveyed (2026-06-06):** the OpenAlex API
([developers.openalex.org](https://developers.openalex.org/) — docs relocated from
docs.openalex.org) plus the 2026-02-24 pricing/features announcement
([blog.openalex.org](https://blog.openalex.org/openalex-api-new-features-and-usage-based-pricing/)).

---

## The thesis (what changed and why it needs scoping now)

The 30.4 design update (2026-06-04) budgets "OpenAlex primary **~10K/day**" — the *old* keyless
polite-pool model. That model no longer exists:

1. **API keys are mandatory since 2026-02-13** (free, `openalex.org/settings/api`). Every call
   ET makes needs a key → the secrets-file convention applies
   (`~/.emergentterrain/secrets.enc`, `${...}` property references, no literals — the auto-mode
   classifier blocks committed key strings).
2. **Usage-based pricing replaced flat rate limits.** Each key gets **$1/day free**: single-entity
   lookups ≈ free, list/filter $0.0001/call (~10K/day), search $0.001/call (~1K/day), full-text
   content $0.01/call (~100/day), semantic/vector search the most expensive tier. Responses carry
   spend headers. Hard ceiling ~100K calls/day, 10/s. The 30.4 per-source budget must be
   re-denominated in **credits, not calls** — a search-heavy resolver pass burns the day's budget
   10× faster than the old design assumed.
3. **New capabilities the designs predate, which simplify them:**
   - **Hosted full-text (PDF + TEI XML) for ~60M OA works**, bulk-downloadable — the 32.5 corpus
     can be *bought* rather than scraped (TEI XML is exactly the chunkable shape PaperQA2 wants).
   - **Extended search** (proximity, exact, wildcards, 8KB queries) — strengthens the resolver's
     title-search layer beyond the fuzzy-matching ARC sketch.
   - **Semantic search (beta)** — paste an abstract, get meaning-matched works; a candidate
     *replacement* for the self-hosted embedding-NN layer of the original 30.4 design
     (evaluated as a probe here, **not** adopted — see 33.4).
   - **Group-by/sample/select** — corpus sizing and stratified probe-set construction become
     single calls instead of bulk pulls.

Items: **33.1** client + key + credit ledger → **33.2** offline corpus build (the 32.5 asset) →
**33.3** layered citation resolver (implements the 30.4 design update on the JVM) →
**33.4** semantic-search novelty probe (record-only, stays deferred with 30.4).

Item detail: [`phase-33-openalex-literature.md`](phase-33-openalex-literature.md).
Sequencing + flags: [`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md).

---

## Relationship to pending work

| Pending item | How phase 33 treats it |
|---|---|
| 30.4 literature grounding (deferred, design updated 2026-06-04) | **33.3 implements its layered resolver**; rate budgets re-denominated in credits; deferral trigger untouched (first promotion paper / first surviving recognition hit) |
| 32.5 grounding sidecar (engine selected, corpus unscoped) | **33.2 builds its corpus** (abstracts + OA full-text via the content endpoint); request path stays offline per 32.5's discipline — OpenAlex is build-time only |
| 32.6 API-results-only citation rule | unchanged; 33.3's VERIFIED keys become the "provided keys" that rule constrains narration to |
| 23.8 calibration ("generation is the blocker") | respected — nothing in this phase spends on live papers until the 30.6/32.5 trigger; 33.1/33.2 are dormant assets |
| `CitationValidator` (internal-only reconciliation) | 33.3 doubles as its external twin — fabricated-but-plausible references get the HALLUCINATED verdict (the planted-reference acceptance test from 30.4) |
| Secrets migration (`secrets.enc`, 2026-06) | 33.1 follows it from birth — the OpenAlex key never exists as a committed literal |
| OpenAlex full **database snapshot** (quarterly free / monthly premium) | **rejected** — hundreds of GB to self-host for a corpus that targeted filter pulls assemble for well under the daily free credit; revisit only if 33.2's filter coverage proves inadequate |
