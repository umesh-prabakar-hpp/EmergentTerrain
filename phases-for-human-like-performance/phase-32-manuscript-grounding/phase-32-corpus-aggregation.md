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
2. **S2ORC bulk** for full text + resolved refs → real `read_reference` content; pull ALL available
   full text for the in-scope fields up front (eager, offline), not a demand-driven cited-core subset.
3. **OEIS + DLMF** adapters — NT-specific structured grounding.
4. Broader metadata mirrors (Crossref/S2AG) only if a recall gap appears; MathSciNet/Scopus only with
   institutional access.

## Domain menu — what's downloadable beyond "all math"

The aggregates are NOT math-only; they are the whole academic corpus. OpenAlex exposes **26 fields**
(each a downloadable slice; S2ORC/S2AG carry the same fields-of-study), so the expansion into
"non-math subjects with math research opportunities" is a filter choice, not a new data source. Work
counts (OpenAlex, 2026-07-08), for scale — note math is only 3.6M, so pulling an adjacent field
often *exceeds* all-math:

| Field | Works | Math-research relevance / MSC applied family |
|---|---|---|
| Mathematics | 3.6M | the spine (`11-XX` for NT) |
| Computer Science | 15.2M | cryptography (`11T`, `94`), algorithms/complexity (`68`), coding theory — closest NT-adjacent |
| Physics & Astronomy | 8.3M | mathematical physics (`81`, `82`), dynamical systems, random matrices (↔ RH) |
| Economics/Econometrics/Finance | 9.0M | game theory / math-econ (`91`), optimization, stochastics |
| Decision Sciences | 2.5M | operations research / optimization (`90`) |
| Engineering | 50.8M | control (`93`), signal processing, PDEs (`35`), info theory (`94`) — large but mostly applied |
| Earth & Planetary | 4.8M | geophysics, fluid dynamics (`76`) |
| Environmental Science | 11.7M | population/ecology models (`92`), spatial statistics |
| Neuroscience | 3.7M | computational/mathematical neuroscience (`92`) |
| Biochem/Genetics/Mol Bio | 13.3M | mathematical & systems biology (`92`) |

Priority order for expansion (math-content density × adjacency to the existing NT engine):
**Computer Science → Physics → Economics/Decision Sciences**, then the biology/earth cluster. Each is
opted in by adding its MSC applied family to the gate allowlist (see §32.5c scope direction) — NOT by
loosening the gate to "keep all of field X", which would re-admit the vocabulary-only noise the gate
exists to drop. The discriminator stays "genuine math content" (MSC / topic), never field membership.
Storage/RAM caveat: these fields are 2–14× the size of all-math, so domain expansion multiplies the
corpus fast — another reason the disk-backed index below is the enabling piece.

## Storage footprint — all sources, not just OpenAlex

Sizing the full stack for the realistic expansion (math + CS + Physics + Econ ≈ 36.1M works),
compressed (~4.5×), anchored on our measured 1.18 KB/work OpenAlex schema:

| Source | Compressed | Note |
|---|---|---|
| OpenAlex metadata + abstracts | **~10 GB** | the base layer |
| **S2ORC full text — all available (~15% OA)** | **~123 GB** | 5.4M docs; the plan — pulled in full up front |
| S2ORC full text — cited-core subset (~3%) | ~25 GB | 1.1M docs; reference only, NOT the chosen policy |
| zbMATH MSC join | ~0.5 GB | tags the 3.6M math slice only — CS/Physics/Econ aren't in zbMATH; they take subject from OpenAlex topic |
| OEIS | ~1 GB | fixed domain source (already have it) |
| DLMF | <0.2 GB | fixed, tiny |

**Plan: bulk-pull ALL available full text for the four fields up front → ~120–140 GB compressed**
(OpenAlex metadata ~10 GB + S2ORC full text ~123 GB + the small fixed sources). This maximizes
coverage — the project's standing preference — over a demand-driven cited-core policy (~15–20 GB) that
would leave uncited works without full text. Both fit this box's 389 GB free. Caveat: a naive pull of
whole S2ORC shards spikes *transient* disk above the stored size — stream-and-filter to keep the peak
near the final footprint.

**Build eager, but still fully offline — full text is NEVER fetched on the request path.** Two
independent rules, don't conflate them:
1. *Coverage policy (this project's choice): EAGER.* Pull all available full text for the four fields
   up front in the offline corpus build, not lazily/demand-driven. Maximize what's on disk.
2. *Request-path rule (§32.5 hard invariant, unchanged): OFFLINE.* Grounding at request time reads
   only on-disk data — a work with no stored full text falls back to its abstract/snippet; zero
   network during a run. Live full-text fetch would put latency/429s on the critical path (exactly
   what throttled the 2026-07-08 enrichment) and break overnight autonomy.

So: the full-text corpus is built ahead of runs by the offline builder (`build-nt-corpus.sh` +
an S2ORC full-text pass), pulling everything available — then runs consume it offline. "Eager" is
about *how much* you pre-fetch, not *when relative to the request path*.

## Index backend & memory sizing — the disk-backed index (the real scaling decision)

The current grounding index (`literature_grounding.jl`) is **in-memory BM25**: the corpus, postings
map, and doclen vector all live in RAM, built at py-sidecar startup. On this box (7.6 GB total,
~4.6 GB free with the ET app + main sidecar up) that is the binding constraint for scaling — NOT
disk (389 GB free) and NOT the corpus download.

Memory scales with *text volume*, not doc count:
- **Abstracts** (today, ~105k docs): index ≈ a few hundred MB — fits comfortably.
- **NT full text** (~50–100k docs, S2ORC): index ≈ 0.5–1.5 GB — fits, but snug.
- **All-math or multi-domain full text** (100k–1M+ docs): index ≈ several GB — **exceeds free RAM**,
  spills into swap, and a swapped BM25 index means brutal per-query latency on the single-threaded
  py-sidecar. This is where "buy more RAM / provision a cloud box" temptation appears.

**The durable fix is a disk-backed index, not a bigger machine.** The §32.5 design already names
**Tantivy** (Rust BM25, the same engine PaperQA2 uses) as the target backend. A disk-backed index is
mmap'd: the OS pages in only the postings actually touched per query, so **resident RAM stays roughly
flat regardless of corpus size**. Consequences:
- The build (index construction) is a one-time offline step; it can run wherever there's RAM/CPU
  headroom (a transient cloud box for an all-math/multi-domain build) and the resulting on-disk index
  ships back here.
- **Serving** any corpus that fits on disk (up to the 389 GB free) runs on this 7.6 GB box, because
  the mmap'd index doesn't load wholesale into RAM.
- Migration shape: `literature_grounding.jl` swaps its in-RAM `postings::Dict` + `docs::Vector` for a
  Tantivy index handle (via a thin FFI/CLI wrapper, or a Julia disk-KV alternative); `lg_search`
  becomes a Tantivy query; the subject gate (`ET_GROUND_SUBJECTS` / MSC) still filters candidates
  post-retrieval. The HTTP contract (`/ground/claim`, verdict/evidence shape) is unchanged.

Bottom line for provisioning: **disk-backed index → this machine serves any on-disk corpus; a cloud
box is only ever a transient *build* accelerator (RAM + in-region S3 bandwidth), never a serving
requirement.** In-memory BM25 → RAM caps you at ~NT-scale full text on this box.

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
