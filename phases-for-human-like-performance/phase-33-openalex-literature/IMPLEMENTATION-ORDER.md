# Phase 33 — implementation order

Sequencing for items 33.1–33.4 ([`phase-33-openalex-literature.md`](phase-33-openalex-literature.md)).
House convention: flag-gated **OFF**, domain-neutral pipeline code, no external API on the 32.5
request path. Overriding discipline: **the 30.4 deferral stands** — first promotion paper (or
first surviving recognition hit) is the only flip trigger; everything below builds dormant.

---

## Dependency DAG (text)

```
secrets.enc convention (shipped) ──→ 33.1 client + ledger ──→ 33.2 corpus build (asset)
                                            │                       │
                                            │                       └──→ 32.5 probe set passes (owned by 32.5)
                                            │
                                            └──→ 33.3 resolver (dormant) ──→ flip rides 30.6 first-promotion trigger
                                                        │                     (same trigger as 32.5)
                                                        └──→ 33.4 probe (record-only, anytime after 33.2)
```

## Wave 0 — Client + credit ledger (33.1) — *substrate, do first*

| Piece | Flag | Acceptance |
|---|---|---|
| Pure-HTTP OpenAlex client; key via `${...}` from secrets.enc | `app.research.literature.openalex.enabled` (default false) | key never committed; probe call succeeds |
| Credit ledger from spend headers + per-class daily ceiling (default below $1 free tier) | `…openalex.daily-credit-ceiling` (config) | ledger matches headers; guard trips in test |
| JSONL request cache | same flag | repeat request = cache hit, zero spend |

## Wave 1 — Corpus build (33.2) — *operational tooling; no runtime flag*

| Piece | Flag | Acceptance |
|---|---|---|
| Stratum sizing via `group_by`; abstract pull (filter+select+cursor) | n/a (ops script) | ≥10⁵ abstracts, ≈$0.10 logged |
| Full-text core via content endpoint, free-tier trickle (~100/day) | n/a | ≥300 TEI/PDF cores, ≤$10 total |
| Dedup (DOI→arXiv→fuzzy title), metadata enrichment, build manifest | n/a | corpus reproducible from manifest; warm re-run ≈ zero credits |

## Wave 2 — Citation resolver (33.3) — *build dormant; flip on the 30.6 trigger*

| Piece | Flag | Acceptance |
|---|---|---|
| Layered resolution: exact-ID → title search → local-corpus NN → LLM scoring | `app.research.literature.citation-resolver.enabled` (supersedes the 30.4 placeholder `literature-retrieval.enabled`) | planted fabricated ref ⇒ HALLUCINATED; 10 real DOIs ⇒ VERIFIED at Layer 1, zero search spend |
| Verdict trichotomy + per-paper credit budget + circuit breaker | same flag | worst-case 30-ref paper stays in budget or degrades to SUSPICIOUS-by-budget |
| Caller policy: promotion-track + recognition hits only | same flag | ordinary-corpus papers: zero resolver calls |

## Wave 3 — Semantic-search probe (33.4) — *record-only*

| Piece | Flag | Acceptance |
|---|---|---|
| 20-claim semantic-endpoint vs local-embedding comparison | none (one bounded ops run) | one-page recorded result; no recurring spend; **no pipeline wiring** |

## Flag inventory (all default OFF)

```yaml
app.research.literature.openalex.enabled              # 33.1 client
app.research.literature.openalex.daily-credit-ceiling # 33.1 config; default < free tier
app.research.literature.citation-resolver.enabled     # 33.3 (supersedes app.research.literature-retrieval.enabled from the 30.4 sketch)
# 33.2 corpus build + 33.4 probe are operational tooling — no runtime flags
# 32.5's app.research.literature.grounding.enabled is owned by phase 32, unchanged here
```

## The shape of it

Wave 0 is pure substrate and costs nothing. Wave 1 produces the one artifact other phases are
waiting on — the 32.5 corpus — for under ten dollars, on a free-tier trickle, with no deadline
pressure because the consuming flag is itself deferred. Wave 2 turns the 30.4 design update from
prose into a dormant, testable resolver whose flip is someone else's trigger. Wave 3 is a
recorded measurement, not a feature. If only one wave ever ships, ship Wave 0+1: the corpus is
useful to 32.5 the day it exists, and everything else here can be rebuilt from the facts-of-record
table when the trigger finally fires.
