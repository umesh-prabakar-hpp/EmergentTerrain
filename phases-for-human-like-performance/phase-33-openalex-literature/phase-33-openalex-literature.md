# Phase 33 — item detail

Scoping for the OpenAlex-backed external-literature layer. Companion to
[`README.md`](README.md) (thesis + API survey) and
[`IMPLEMENTATION-ORDER.md`](IMPLEMENTATION-ORDER.md) (sequencing + flags). Parent designs:
[30.4](../phase-30-conjecture/phase-30-genuinely-novel-claims.md) (layered citation resolution,
deferred) and [32.5](../phase-32-manuscript-grounding/phase-32-manuscript-grounding.md)
(PaperQA2 grounding sidecar, corpus unscoped).

---

## API facts of record (2026-06-06)

Pinned here so later items argue against a dated snapshot, not a moving target.

| Fact | Value | Source |
|---|---|---|
| Base URL | `https://api.openalex.org` | developers.openalex.org |
| Docs | `developers.openalex.org` (docs.openalex.org 301s there) | — |
| Auth | API key **mandatory** since 2026-02-13; free at `openalex.org/settings/api` | users-group announcement |
| Free tier | **$1/day usage credit** per key | 2026-02-24 blog |
| Per-call cost | singleton ≈ free · list/filter $0.0001 · search $0.001 · content (PDF/TEI) $0.01 · semantic search = top tier | blog pricing table |
| Free-tier equivalents | ~10K list/filter · ~1K search · ~100 full-text per day | blog |
| Hard limits | ~100K calls/day, 10/s; spend reported in response headers | docs |
| Entities | 21 endpoints — Works, Authors, Sources, Institutions, Topics, Keywords, Publishers, Funders, Awards, Domains/Fields/Subfields, SDGs, Countries, Continents, Languages, Work/Source/Institution Types, Licenses (Concepts deprecated → Topics) | api-reference |
| Query surface | `filter` (AND/OR/NOT), `search` (+ proximity/exact/wildcard, ≤8KB), `group_by`, `sample`, `select`, `sort`, cursor pagination (`per_page` ≤100), external-ID lookup (DOI/ORCID/ROR/PMID/ISSN), autocomplete | api-reference |
| Semantic search | beta; abstract-sized inputs; meaning-matched works | blog |
| Full text | hosted PDF + TEI XML for ~60M OA works; bulk via API or their CLI | blog |
| Snapshot | full DB: quarterly (free) / monthly + daily deltas (premium) | docs |

## 33.1 — OpenAlex client, key custody, credit ledger  *(substrate; build-dormant anytime)*

**ET shape.** A pure-HTTP JVM client (the ARC zero-heavy-deps precedent — no SDK), pipeline-wide
and **domain-neutral** (it will eventually serve every domain's papers, not just number theory):

- **Key custody:** key lives in `~/.emergentterrain/secrets.enc` alongside the four provider
  keys; wired via `${...}` property reference. Never a literal in `application.yml`.
- **Credit ledger:** parse the spend headers on every response into a daily counter
  (`RunBudgetTracker` analog, but denominated in OpenAlex credits); per-operation-class budgets
  (filter / search / content) with a configurable daily ceiling defaulting **below** the free $1
  so an unattended overnight room can never incur spend.
- **Circuit breaker + cascade hooks:** the 30.4 design's per-source budgets and graceful
  degradation, re-denominated in credits. OpenAlex primary; Semantic Scholar / arXiv / CrossRef
  fallbacks are *interface stubs only* in this phase (each has its own auth/limit story —
  separate scoping if ever needed).
- **JSONL response cache** keyed by canonical request (the ARC retrieval-cache pattern) so
  resumed corpus builds and repeated resolver probes don't re-pay credits.

**Acceptance bar.** Key resolves from the secrets file (never committed); a scripted probe shows
the ledger matching the response headers within 1 credit-class; the ceiling guard trips in a test
before the free tier is exhausted; cache hit on byte-identical repeat request.

## 33.2 — Offline corpus build  *(the 32.5 asset; operational tooling, no runtime flag)*

**What 32.5 left open.** "Curated number-theory references… built once, enriched offline" — this
item is the build. OpenAlex is **build-time only**; per 32.5's discipline the grounding request
path stays fully offline (Tantivy + local embeddings), so an overnight room never touches the
network.

- **Selection:** `filter=primary_topic.subfield:…` for number theory plus a hand-curated seed
  list (the known-laws registry's citations, OEIS cross-references, textbook bibliographies).
  Use `group_by` to size strata *before* pulling (one call answers "how many OA NT works since
  1950 by decade").
- **Abstracts at scale:** list calls with `select=id,doi,title,abstract_inverted_index,…`,
  cursor-paginated at 100/page → ~10⁵ abstracts ≈ 1,000 filter calls ≈ **$0.10**. Reconstruct
  abstracts from the inverted index (documented quirk; trivial transform).
- **Full text for the core:** the content endpoint ($0.01/call) for a curated core —
  ~300–500 OA works with TEI XML preferred (chunk-ready for PaperQA2). Free tier: ~100/day ⇒
  a 3–5-day trickle build, or a deliberate ~$5 one-shot. **Decision: trickle by default** —
  there is no schedule pressure while 30.4 stays deferred.
- **Dedup + enrichment:** DOI → arXiv ID → fuzzy title (the ARC order); metadata cascade fills
  venue/year/authors for citation-key fidelity. Everything lands in the sidecar corpus dir with
  a build manifest (source query, date, credit spend) so the corpus is reproducible.
- **Refresh policy:** corpus is a snapshot asset; re-run the build manifest quarterly at most.
  No live-update path — staleness is acceptable for grounding *known* results (the 32.5 use),
  and the resolver (33.3) hits the live API for anything the corpus lacks.

**Acceptance bar.** Corpus dir holds ≥10⁵ abstracts + ≥300 full-text cores with build manifest;
total build spend ≤ $10 logged; 32.5's 20-claim probe set passes against it (that bar stays owned
by 32.5); re-running the build with the cache warm costs ~zero credits.

## 33.3 — Layered external citation resolver  *(implements the 30.4 design update on the JVM)*

The 2026-06-04 ARC-derived design, made concrete against the current API. Pipeline-wide code →
domain-neutral wording throughout.

- **Layer 1 — exact-ID:** DOI / arXiv-ID / PMID via OpenAlex external-ID lookup (singleton calls,
  ≈ free). Hit ⇒ VERIFIED without ever paying for search.
- **Layer 2 — title search:** OpenAlex `search` with the new exact-match + proximity operators
  (replaces the fuzzy-title workaround in the ARC sketch); $0.001/call, so the resolver budget
  in 33.1 is what actually constrains a pathological reference list.
- **Layer 3 — embedding NN:** against the **local** 33.2 corpus (free, offline) — not the paid
  semantic endpoint (see 33.4).
- **Layer 4 — LLM relevance scoring**, survivors only.
- **Verdicts:** VERIFIED / SUSPICIOUS / HALLUCINATED with the 30.4 thresholds (≥0.80 /
  0.50–0.80 / <0.50 title similarity).
- **Caller policy** mirrors 32.5: promotion-track papers and recognition hits only;
  ordinary-corpus per-paper cost stays flat at zero. The resolver also serves as the external
  twin of `CitationValidator` — today it reconciles internal citations but cannot catch a
  fabricated-but-plausible external reference.

**Acceptance bar.** The 30.4 planted-reference test: a fabricated citation is flagged
HALLUCINATED; 10 known-real DOIs all VERIFIED via Layer 1 alone (zero search spend); a
worst-case 30-reference paper resolves within the per-paper credit budget or degrades gracefully
(circuit breaker → SUSPICIOUS-by-budget, never silent pass).

## 33.4 — Semantic-search novelty probe  *(record-only; stays deferred with 30.4)*

The original 30.4 sketch wanted embedding NN over a pre-embedded snapshot at formulation and
publish time. OpenAlex's semantic search (beta) could replace the self-hosted half — paste the
claim/abstract, get meaning-matched works — but it is the most expensive call class, beta-stability
is unknown, and the 23.8 finding stands: **generation, not retrieval, is the blocker**.

- **Scope here:** a 20-claim offline probe (the 32.5 probe set doubles up) comparing semantic-
  endpoint top-K against local-embedding top-K from the 33.2 corpus; record overlap, cost, and
  latency. **No pipeline wiring.** The result is an input to whichever future phase un-defers
  the novelty gate, not a flip of anything.

**Acceptance bar.** A one-page recorded comparison; zero recurring spend afterwards.

---

## Cost & risk

| Item | Credits/spend | Risk | Mitigation |
|---|---|---|---|
| 33.1 client | ~0 (probes only) | key leakage; silent spend | secrets.enc from birth; ledger + sub-free-tier ceiling default |
| 33.2 corpus | ≤ $10 one-time (default: multi-day free trickle) | corpus skew (OA-only coverage) | seed list from known-laws registry; build manifest records the query for audit |
| 33.3 resolver | ≈ free per paper (Layer 1) up to budgeted search | API drift (pricing/beta churn) | facts-of-record table dated; circuit breaker; JSONL cache |
| 33.4 probe | one bounded probe run | beta endpoint instability | record-only, no wiring |

Dominant risk is **policy drift on OpenAlex's side** (the Feb-2026 change happened once; it can
happen again) — which is exactly why the request path (32.5) stays offline and OpenAlex remains
a build-time/slow-path dependency only.
