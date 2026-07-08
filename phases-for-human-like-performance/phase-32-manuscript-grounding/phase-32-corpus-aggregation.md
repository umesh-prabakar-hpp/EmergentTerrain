# Phase 32 §32.5d — corpus aggregation: reuse packaged open datasets, add only the math/MSC layer

**Status:** design note (2026-07-08). Not built. Supersedes the "build a multi-source `CorpusMerger`
from scratch" instinct — a review of the packaged-dataset landscape found the harvest + dedup +
full-text work is already done and open-sourced; the only bespoke piece left is the domain
(number-theory / MSC) layer. This is a *coverage/quality* follow-on to §32.5c, independent of the
subject gate shipping now.

## The reframe

"Collect from as many sources as possible" does **not** mean building a harvester+dedup pipeline.
Two facts collapse the work:

1. **OpenAlex is already a multi-source deduped aggregate.** It entity-resolved Crossref, the
   retired Microsoft Academic Graph, PubMed, arXiv, DOAJ, and ORCID into one graph with topic
   classification + a citation network. The metadata merge is done; our existing 105k-abstract pull
   rides on it.
2. **The full-text aggregate is packaged too.** AI2's **S2ORC** (81.1M papers, structured full text
   for 8.1M OA papers, resolved bibliographic references, cross-domain, deduped) is served as a
   continuously-rebuilt **Bulk Dataset via the Semantic Scholar Datasets API**. Its cleaned
   derivative **peS2o** (~40M docs, `s2orc` full-text + `s2ag` abstract-only subsets) is a single
   HuggingFace download, each doc carrying an S2 Corpus ID to rejoin fields-of-study/authors.

So "many sources → dedup → full text → classification" becomes **download an existing aggregate +
filter to number theory**. Adding more raw metadata mirrors (Crossref, S2AG) on top of OpenAlex buys
~1.1–1.3× unique papers; the real return is *facets* — full text, MSC classification, citations,
embeddings — which the packaged datasets already carry.

## Recommended build

**Best for the grounding use case: keep OpenAlex for metadata + pull S2ORC bulk for full text and
resolved references, then apply a math filter and attach MSC via a zbMATH join. The only new code is
the math/MSC gate + the domain sources (OEIS / NIST DLMF) no aggregator includes.**

```
OpenAlex (have)        → metadata spine (title/abstract/venue/authors/topic/cited_by)
   ⊕ S2ORC bulk        → structured full text + resolved references  (join by DOI/arXiv/S2 CorpusID)
   ⊕ zbMATH join       → MSC codes (11-XX), math-authoritative        (join by DOI/arXiv/zbl)
   → math/MSC FILTER   → keep 11-XX (+ chosen adjacencies); this is the ET_GROUND_SUBJECTS gate,
                          upgraded from OpenAlex field → MSC prefix (resolves the intra-math
                          elliptic-curve edge case §32.5c couldn't crack with subfield 2602)
   ⊕ OEIS + DLMF       → structured domain knowledge no general aggregator has
```

### Reused vs. new code
- **Reused / free:** OpenAlex (`NtCorpusBuilder`, live), S2ORC/peS2o download + join, zbMATH OAI-PMH
  harvest for the MSC join. No harvester or dedup engine to write — the aggregates ship deduped.
- **New (small):** the **math/MSC filter** (the generalized subject gate — allowlist MSC `11`), the
  **zbMATH MSC-join** pass (DOI/arXiv → MSC, analogous to the OpenAlex field enrichment written
  2026-07-08), and the **OEIS + DLMF adapters** (domain-specific, high value for NT, not in any
  aggregate). `LgDoc` gains `msc` (+ `fulltext_url`, `cited_by`, `source[]`).

### Alternative (fastest to clean full text)
Grab **peS2o** from HuggingFace and filter to math by S2 field-of-study — skips even the OpenAlex
step if a from-scratch corpus is acceptable. Math token-corpora **MathPile** (~9.5B tok) and
**Nemotron-CC-Math** (~133B tok) isolate math text but are LM-pretraining streams (not per-paper
records) and MathPile is CC-BY-NC-SA — use as supplementary text, not the record store.

## Sequencing
1. **zbMATH MSC-join** onto the existing OpenAlex corpus — cheapest lever, upgrades the gate key from
   field to MSC. (The §32.5c OpenAlex-field gate is the interim; MSC is the target.)
2. **S2ORC bulk** for full text + resolved refs → real `read_reference` content on the core cited set.
3. **OEIS + DLMF** adapters — NT-specific structured grounding.
4. Broader metadata mirrors (Crossref/S2AG) only if a recall gap appears; MathSciNet/Scopus only with
   institutional access.

## Caveats
- **Bulk datasets over live APIs, always.** The 2026-07-08 OpenAlex enrichment 429-throttled twice at
  ~1 req/s on the anonymous tier; S2ORC/peS2o/arXiv/CORE all publish bulk dumps you join locally —
  faster and rate-limit-free. Supply `OPENALEX_API_KEY` for any residual live calls.
- **License + provenance are heterogeneous** (OpenAlex CC0; S2ORC/peS2o research-friendly but
  attributed; MathPile CC-BY-NC-SA; zbMATH CC-BY-SA). Fine for an internal single-user grounding
  corpus; keep `source[]` per record so any redistribution can honor attribution.
- **Diminishing returns:** after OpenAlex + zbMATH(MSC) + S2ORC(full text) + OEIS/DLMF, each extra
  metadata mirror adds little. Spend effort on the MSC filter quality and the domain sources.

See §32.5c in phase-32-manuscript-grounding.md (the shipped subject gate this generalizes — its
MSC allowlist is the target this addendum builds toward) and phase-32-openalex-corpus.md item B
(the OpenAlex build + subject-tag backfill).
