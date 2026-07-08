# Phase 32 addendum — OpenAlex corpus & external citation resolver (un-defers the §32.5 / 30.4 data layer)

Scopes the **external-data layer** that two standing designs assume but neither specifies:
[§32.5](phase-32-manuscript-grounding.md) (the PaperQA2 grounding sidecar — engine selected, but
its corpus left as "a local directory of curated number-theory references… built once, enriched
offline" with no source named) and the deferred **30.4** layered citation resolver
([`../phase-30-conjecture/phase-30-genuinely-novel-claims.md`](../phase-30-conjecture/phase-30-genuinely-novel-claims.md)
§30.4). OpenAlex is the named primary source in both. Originally drafted as a standalone "phase 33"
folder; folded here because it is precisely the §32.5 corpus build plus the 30.4 resolver, not a
separate concern.

Every item ships flag-gated **OFF**; the **30.4/§32.5 deferral discipline is unchanged** — nothing
flips until the standing first-promotion trigger. What can proceed anytime: the client and corpus
are dormant assets (zero LLM calls, zero request-path coupling). Pipeline-wide code stays
domain-neutral ([[feedback_common_code_domain_neutral]]); the API key follows the secrets-file
convention ([[project_secrets_file_migration]]).

**Source surveyed (2026-06-06):** the OpenAlex API ([developers.openalex.org](https://developers.openalex.org/),
relocated from docs.openalex.org) + the 2026-02-24 pricing/features announcement
([blog.openalex.org](https://blog.openalex.org/openalex-api-new-features-and-usage-based-pricing/)).

---

## Why this needs scoping now

The 30.4 design update (2026-06-04) budgets "OpenAlex primary **~10K/day**" — the *old keyless
polite-pool model*, which no longer exists:

1. **API keys mandatory since 2026-02-13** (free, `openalex.org/settings/api`). Every call needs a
   key → secrets-file convention applies (`~/.emergentterrain/secrets.enc`, `${...}` references, no
   literals — the auto-mode classifier blocks committed key strings).
2. **Usage-based pricing replaced flat limits.** Each key gets **$1/day free**: singleton ≈ free ·
   list/filter $0.0001 · search $0.001 · full-text content $0.01 · semantic/vector search top tier.
   Responses carry spend headers; hard ceiling ~100K calls/day, 10/s. The 30.4 per-source budget
   must be re-denominated in **credits, not calls**.
3. **New capabilities the designs predate, which simplify them:** hosted **full-text (PDF + TEI XML)
   for ~60M OA works** (the §32.5 corpus can be *bought*, TEI XML being exactly PaperQA2's
   chunk-ready shape); **extended search** (proximity/exact/wildcard, ≤8KB) strengthening the
   resolver's title layer; **semantic search (beta)** as a probe-only candidate; **group-by /
   sample / select** making corpus sizing single calls.

## API facts of record (2026-06-06)

| Fact | Value |
|---|---|
| Base URL / docs | `https://api.openalex.org` · `developers.openalex.org` |
| Auth | API key **mandatory** since 2026-02-13; free |
| Free tier | **$1/day usage credit** per key (~10K list/filter · ~1K search · ~100 full-text) |
| Per-call cost | singleton ≈ free · list/filter $0.0001 · search $0.001 · content $0.01 · semantic = top tier |
| Hard limits | ~100K calls/day, 10/s; spend in response headers |
| Entities | 21 endpoints (Works, Authors, Sources, Institutions, Topics, …; Concepts deprecated → Topics) |
| Query | `filter` (AND/OR/NOT), `search` (+proximity/exact/wildcard, ≤8KB), `group_by`, `sample`, `select`, `sort`, cursor (`per_page`≤100), external-ID lookup (DOI/ORCID/ROR/PMID/ISSN), autocomplete |
| Full text | hosted PDF + TEI XML for ~60M OA works; bulk via API or CLI |
| Snapshot | full DB: quarterly (free) / monthly + daily deltas (premium) — **rejected** (hundreds of GB to self-host; targeted filter pulls assemble the corpus for well under the daily free credit) |

---

## Scoped items

### A — OpenAlex client, key custody, credit ledger *(substrate; build-dormant anytime)*

Pure-HTTP JVM client (the ARC zero-heavy-deps precedent — no SDK), domain-neutral:

- **Key custody:** in `secrets.enc` alongside the four provider keys, wired via `${...}`. Never a
  literal in `application.yml`.
- **Credit ledger:** parse spend headers into a daily counter (a `RunBudgetTracker` analog
  denominated in OpenAlex credits); per-operation-class budgets with a daily ceiling defaulting
  **below** the free $1 so an unattended overnight room can never incur spend.
- **Circuit breaker + cascade hooks:** the 30.4 per-source budgets + graceful degradation,
  re-denominated in credits. OpenAlex primary; Semantic Scholar / arXiv / CrossRef are interface
  stubs only here (separate scoping if ever needed).
- **JSONL response cache** (the ARC retrieval-cache pattern) so resumed builds / repeat probes don't
  re-pay credits.

*Acceptance:* key resolves from the secrets file (never committed); ledger matches headers within 1
credit-class; ceiling guard trips before the free tier is exhausted; byte-identical repeat = cache hit.

### B — Offline corpus build *(the §32.5 asset; operational tooling, no runtime flag)*

OpenAlex is **build-time only**; per §32.5's discipline the grounding request path stays fully
offline (Tantivy + local embeddings), so an overnight room never touches the network.

- **Selection:** `filter=primary_topic.subfield:…` for number theory + a hand-curated seed list
  (known-laws registry citations, OEIS cross-refs, textbook bibliographies). `group_by` sizes strata
  before pulling. **2026-07-04 refinement (run-16 reframe, see §32.5):** seed the targeted searches
  from the actual `ScalarOpenness`/`MEASUREMENT_FRAMES` object list — Dirichlet divisor problem,
  Cramér gaps, Hardy–Littlewood 2C₂, Erdős–Kac, Mertens, 2γ divisor mean, Wigert maximal order,
  Deléglise abundant density, 6/π² squarefree — those ~15 objects are what every measurement paper
  must situate against; a targeted corpus beats a broad subfield pull at this scale.
- **Abstracts at scale:** list calls with `select=…,abstract_inverted_index`, cursor-paginated at
  100/page → ~10⁵ abstracts ≈ 1,000 filter calls ≈ **$0.10**. Reconstruct from the inverted index.
- **Full text for the core:** the content endpoint ($0.01) for ~300–500 OA works, TEI XML preferred
  (chunk-ready). Free tier ~100/day ⇒ **trickle build by default** (no schedule pressure while 30.4
  is deferred), or a deliberate ~$5 one-shot.
- **Dedup + enrichment:** DOI → arXiv ID → fuzzy title (ARC order); metadata cascade fills
  venue/year/authors for citation-key fidelity. Build manifest records query/date/credit spend →
  reproducible.
- **Subject tags (2026-07-08, see §32.5c):** `SELECT_FIELDS` now pulls `primary_topic`; each row
  stores `field` (display name, e.g. *Mathematics*), `subfield` (id, e.g. `2602`), and `topic` (id,
  e.g. `T11166`) for the retrieval-time subject gate. The seed pull is subfield-constrained
  (`SEED_SUBFIELD_FILTER`, default `primary_topic.subfield.id:2602`) so field-unconstrained
  full-text search no longer ingests cross-domain papers sharing a word. **Corpora built before this
  carry no tags** — backfill via a non-destructive `primary_topic` enrichment over the existing ids
  (batched `filter=openalex_id:W…|W…&select=id,primary_topic`, ~1,000 read calls; anonymous tier
  429-throttles near the tail, so pace ≤ ~5 req/s or supply `OPENALEX_API_KEY`), not a
  delete-and-rebuild that risks not reproducing a multi-day corpus.

*Acceptance:* ≥10⁵ abstracts + ≥300 full-text cores with manifest; total spend ≤ $10 logged;
§32.5's 20-claim probe set passes against it (that bar stays owned by §32.5, now incl. the #21
subject-purity probe); warm re-run ≈ 0 credits.

### C — Layered external citation resolver *(implements the 30.4 design update on the JVM)*

- **Layer 1 — exact-ID:** DOI / arXiv-ID / PMID via OpenAlex external-ID lookup (singleton, ≈ free).
  Hit ⇒ VERIFIED without paying for search.
- **Layer 2 — title search:** OpenAlex `search` with exact-match + proximity (replaces the ARC
  fuzzy-title workaround); $0.001/call.
- **Layer 3 — embedding NN:** against the **local** corpus (free, offline) — not the paid semantic
  endpoint.
- **Layer 4 — LLM relevance scoring**, survivors only.
- **Verdicts:** VERIFIED / SUSPICIOUS / HALLUCINATED at the 30.4 thresholds (≥0.80 / 0.50–0.80 /
  <0.50 title similarity).
- **Caller policy** mirrors §32.5: promotion-track papers + recognition hits only; ordinary-corpus
  per-paper cost stays flat at zero. Doubles as the external twin of `CitationValidator` (which
  reconciles internal citations but can't catch a fabricated-but-plausible external reference).

*Acceptance:* the 30.4 planted-reference test (fabricated ref ⇒ HALLUCINATED); 10 real DOIs ⇒
VERIFIED at Layer 1 alone (zero search spend); a 30-reference paper resolves within the per-paper
credit budget or degrades to SUSPICIOUS-by-budget (never silent pass).

### D — Semantic-search novelty probe *(record-only; stays deferred with 30.4)*

A 20-claim offline probe comparing OpenAlex semantic-endpoint top-K against local-embedding top-K
(record overlap/cost/latency). **No pipeline wiring** — an input to whichever future phase
un-defers the novelty gate, respecting the 23.8 finding that *generation, not retrieval, is the
blocker*. *Acceptance:* a one-page recorded comparison; zero recurring spend.

---

## Sequencing & flags (all default OFF)

```
secrets.enc (shipped) → A client+ledger → B corpus build (asset) → §32.5 probe passes
                              └→ C resolver (dormant) → flip rides 30.6 first-promotion trigger
                                        └→ D probe (record-only, anytime after B)
```

```yaml
app.research.literature.openalex.enabled              # A client
app.research.literature.openalex.daily-credit-ceiling # A config; default < free tier
app.research.literature.citation-resolver.enabled     # C (supersedes the 30.4 placeholder app.research.literature-retrieval.enabled)
# B corpus build + D probe are operational tooling — no runtime flags
# §32.5's app.research.literature.grounding.enabled is unchanged
```

**Shape:** A is pure substrate, costs nothing. B produces the one artifact §32.5 is waiting on — the
corpus — for under ten dollars on a free-tier trickle, no deadline pressure (the consuming flag is
itself deferred). C turns the 30.4 design update into a dormant, testable resolver whose flip is
§32.5's trigger. D is a recorded measurement, not a feature. Dominant risk is **policy drift on
OpenAlex's side** (the Feb-2026 change happened once; it can recur) — which is why the request path
stays offline and OpenAlex remains a build-time/slow-path dependency only.
