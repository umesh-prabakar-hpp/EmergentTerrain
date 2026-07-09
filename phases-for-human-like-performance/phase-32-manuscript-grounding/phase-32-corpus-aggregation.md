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

## Design constraint — NO AFFILIATION-GATED / SUBSCRIPTION CREDENTIALS (open-source reproducibility)

**Added 2026-07-08; corrected 2026-07-08.** This repo is headed for open source; any user must be
able to build the corpus without credentials that require an institutional affiliation or a paid
subscription. Sources fall in three tiers:

- **Zero account:** unarXive (Zenodo), arXiv OAI-PMH, zbMATH Open, OEIS, DLMF, OpenAlex-anonymous,
  **peS2o on HuggingFace** (`allenai/peS2o` / `common-pile/peS2o` — ODC-BY, public, no S2 key; this is
  how you get S2ORC full text without any Semantic Scholar credential).
- **Self-serve-free credential (any individual, no affiliation):** OpenAlex key, Semantic Scholar /
  S2ORC Datasets-API key (only needed for the *raw* records, not for peS2o), CORE key. Same barrier as
  the OpenAlex key `NtCorpusBuilder` already uses.
- **Truly gated (excluded as defaults):** MathSciNet, Scopus, Web of Science (affiliation /
  subscription).

**Correction of an earlier draft:** a prior version of this doc claimed the Semantic Scholar / S2ORC
key "requires an academic or corporate email." That is **wrong** — the official key request states no
affiliation requirement and issues keys to individuals; there is also a keyless public tier (standard
Graph API endpoints, ~100 req/5 min) and a free individual key (~1 RPS). Bulk S2ORC download via the
Datasets API does need the free key (verified: no S3 links without it), but that key is self-serve,
not affiliation-gated. So **S2ORC is a legitimate default option** (middle tier), not an excluded one.
The only real hard stops for OSS reproducibility are the subscription sources above.

The remaining distinction is friction, not openness: unarXive/arXiv need **no account at all**, while
S2ORC needs **one free key**. Pick by coverage-vs-zero-friction, below.

## Recommended build

**Best for the grounding use case: keep OpenAlex for metadata + pull full text (S2ORC for the richest
coverage with one free self-serve key, OR the unarXive open subset for a strictly no-account build),
then apply a math filter and attach MSC via a zbMATH join (open API, no key). The only new code is the
math/MSC gate + the domain sources (OEIS / NIST DLMF) no aggregator includes.** Both full-text options
are OSS-reproducible; see "Full-text source options" below for the coverage-vs-friction call.

```
OpenAlex (have)        → metadata spine (title/abstract/venue/authors/topic/cited_by)
   ⊕ unarXive/arXiv    → structured full text + annotated citations   (join by arXiv id / DOI; no account)
   ⊕ zbMATH join       → MSC codes (11-XX), math-authoritative        (join by DOI/arXiv/zbl)
   → math/MSC FILTER   → keep 11-XX (+ chosen adjacencies); this is the ET_GROUND_SUBJECTS gate,
                          upgraded from OpenAlex field → MSC prefix (resolves the intra-math
                          elliptic-curve edge case §32.5c couldn't crack with subfield 2602)
   ⊕ OEIS + DLMF       → structured domain knowledge no general aggregator has
```

### Reused vs. new code
- **Reused / free (no gated credentials):** OpenAlex (`NtCorpusBuilder`, live; anonymous or optional
  free key), the **unarXive open subset** (Zenodo download, no account), **arXiv** OAI-PMH metadata
  (no account), zbMATH OAI-PMH for the MSC join (open API). The aggregates ship deduped — no harvester
  or dedup engine to write.
- **New (small):** the **math/MSC filter** (the generalized subject gate — allowlist MSC `11`), the
  **zbMATH MSC-join** pass (DOI/arXiv → MSC, analogous to the OpenAlex field enrichment written
  2026-07-08), an **arXiv/unarXive full-text adapter** (parse the Zenodo dump, key by arXiv id/DOI),
  and the **OEIS + DLMF adapters** (domain-specific, high value for NT, not in any aggregate).
  `LgDoc` gains `msc` (+ `fulltext_url`, `cited_by`, `source[]`).

### Full-text source options, by credential barrier (all OSS-compatible except the last group)
- **Zero account / no S2 key — RECOMMENDED, gets S2ORC coverage anyway:**
  - **peS2o on HuggingFace** — the AllenAI-official cleaned S2ORC full text, **ODC-BY, fully open, NO
    Semantic Scholar key** (HF-hosted; `load_dataset("allenai/peS2o", "v2")` or direct `.json.gz`
    shards). `allenai/peS2o` v2 ≈ 39M docs (broad); **`common-pile/peS2o` ≈ 6.3M docs, openly-licensed
    articles ONLY** — the cleanest for OSS redistribution (per-doc license in metadata). This is the
    best of both: S2ORC-quality cross-source full text without the S2 API key OR arXiv's coverage gap.
  - **unarXive open subset** (Zenodo, no account) — structured arXiv full text + annotated citations,
    LaTeX-derived. Cleaner citation structure than peS2o, but arXiv-only (thin on economics).
  - **arXiv S3 bulk** (requester-pays; AWS account, no approval/gating) — fuller arXiv coverage.
  - **OpenAlex `oa_url` + Unpaywall** (email-as-param, not a gated key) — OA PDF locations to fetch.
- **One free self-serve key (no affiliation) — only if you need the raw structured records:**
  - **S2ORC via Semantic Scholar Datasets API** — the *raw* S2ORC records (resolved references broken
    out, SPECTER2 join, continuously rebuilt). Needs the free individual key (same tier as our OpenAlex
    key). Use only if peS2o's cleaned format is insufficient. Ingester: `scratchpad/s2orc-ingest.py`.
- **Supplementary text only (not a record store):** math token-corpora **MathPile** (~9.5B tok,
  CC-BY-NC-SA) and **Nemotron-CC-Math** (~133B tok) — LM-pretraining streams, not per-paper records.
- **Truly gated (excluded):** MathSciNet, Scopus, Web of Science — affiliation / subscription.

Choice between the two peS2o mirrors (both ODC-BY, public, no S2 key — the difference is per-document
license cleanliness, NOT open-vs-closed):
- **`allenai/peS2o`** (v2 ≈39M, v1 ≈68M) — all S2ORC papers, *mixed* per-doc licenses (open-access
  readable, not all open-licensed). **Default when the repo ships the BUILDER and each user downloads
  locally** (the normal OSS pattern; corpus lives in `~/.emergentterrain`, never in git) — more
  coverage, and per-paper use is the user's own research/TDM.
- **`common-pile/peS2o`** (≈6.3M) — filtered to openly-licensed articles ONLY, per-doc license in
  metadata. **Choose when you intend to redistribute/bundle the built corpus** (release artifact) or
  want maximum legal caution — clean licenses, at the cost of ~6× less coverage.

Either obsoletes the earlier "arXiv-only vs S2-key" trade: broad cross-source full text while staying
credential-light. peS2o is Grobid-cleaned and LM-formatted (title + full text + CorpusID to rejoin
metadata) — fine for BM25 + `read_reference`; reach for the raw S2ORC Datasets API only if you need
the broken-out resolved-reference structure. unarXive stays useful for LaTeX-quality inline citations
on the arXiv slice.

## Sequencing
1. **zbMATH MSC-join** onto the existing OpenAlex corpus — cheapest lever, upgrades the gate key from
   field to MSC. (The §32.5c OpenAlex-field gate is the interim; MSC is the target.)
2. **unarXive open subset (+ arXiv S3)** for full text + annotated citations → real `read_reference`
   content; pull ALL available full text for the in-scope fields up front (eager, offline), not a
   demand-driven cited-core subset. No gated credentials (S2ORC is the optional keyed upgrade).
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
| **arXiv full text (4 fields, S3 bulk)** | **~45 GB** | ~2M papers; open, requester-pays AWS (no approval gate) |
| unarXive open subset (permissive only) | ~10–15 GB | **zero-credential default**; a subset of the above |
| zbMATH MSC join | ~0.5 GB | tags the 3.6M math slice only — CS/Physics/Econ aren't in zbMATH; they take subject from OpenAlex topic |
| OEIS | ~1 GB | fixed domain source (already have it) |
| DLMF | <0.2 GB | fixed, tiny |
| *(optional, gated) S2ORC full text — all OA* | *~123 GB* | *5.4M docs; broader than arXiv but needs the academic-email key* |

**Plan: bulk-pull ALL available full text for the four fields up front (credential-free path):**
- **Zero-credential (unarXive open subset): ~20–25 GB compressed** total (OpenAlex ~10 GB + unarXive
  ~10–15 GB + small fixed sources) — the OSS default.
- **arXiv S3 bulk (AWS account, no gating): ~55–60 GB compressed** — fuller arXiv coverage.
- *Optional gated (S2ORC): ~120–140 GB* — broader OA coverage, but excluded as a default.

All fit this box's 389 GB free. Eager (pull everything available) beats a demand-driven cited-core
policy (~15–20 GB) that would leave uncited works without full text. Caveat: a naive pull of whole
bulk shards spikes *transient* disk above the stored size — stream-and-filter to keep the peak near
the final footprint. Coverage note: arXiv is preprint-centric (strong math/CS/physics, thin econ),
which is why the credential-free totals are smaller than the gated S2ORC figure — an honest
coverage-for-openness trade.

**Build eager, but still fully offline — full text is NEVER fetched on the request path.** Two
independent rules, don't conflate them:
1. *Coverage policy (this project's choice): EAGER.* Pull all available full text for the four fields
   up front in the offline corpus build, not lazily/demand-driven. Maximize what's on disk.
2. *Request-path rule (§32.5 hard invariant, unchanged): OFFLINE.* Grounding at request time reads
   only on-disk data — a work with no stored full text falls back to its abstract/snippet; zero
   network during a run. Live full-text fetch would put latency/429s on the critical path (exactly
   what throttled the 2026-07-08 enrichment) and break overnight autonomy.

So: the full-text corpus is built ahead of runs by the offline builder (`build-nt-corpus.sh` +
an arXiv/unarXive full-text pass), pulling everything available — then runs consume it offline. "Eager" is
about *how much* you pre-fetch, not *when relative to the request path*.

## Index backend & memory sizing — the disk-backed index (the real scaling decision)

The current grounding index (`literature_grounding.jl`) is **in-memory BM25**: the corpus, postings
map, and doclen vector all live in RAM, built at py-sidecar startup. On this box (7.6 GB total,
~4.6 GB free with the ET app + main sidecar up) that is the binding constraint for scaling — NOT
disk (389 GB free) and NOT the corpus download.

Memory scales with *text volume*, not doc count:
- **Abstracts** (today, ~105k docs): index ≈ a few hundred MB — fits comfortably.
- **NT full text** (~50–100k docs, arXiv/unarXive): index ≈ 0.5–1.5 GB — fits, but snug.
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
- **Diminishing returns:** after OpenAlex + zbMATH(MSC) + arXiv/unarXive(full text) + OEIS/DLMF, each extra
  metadata mirror adds little. Spend effort on the MSC filter quality and the domain sources.

## MSC-join findings — zbMATH API grind (killed) → OAI-PMH bulk (2026-07-08)

Attaching MSC codes (to upgrade the gate from field-level to `11`-XX Number Theory) went through two
approaches. Preserved here for reference.

**Approach 1 — per-DOI API (`build-msc-zbmath.py`): KILLED as impractical.** Looks each corpus row up
in the zbMATH REST API (`?search_string=doi: <doi>`). A 30-row sample was 0.6 s/call and 77% hit
(all 11-XX, e.g. 11N37/11M06/11P21) — but at corpus scale (105,855 sequential calls) it degraded to a
**9.4 s/row average over 18 h (only 7,000 done → ~11-day ETA)**, almost certainly zbMATH throttling
the retries. NOTE the query field is **`doi:`** (not `dr:`, which 404s). Killed 2026-07-08; its
checkpoint `msc-tags.json` (7,000 looked up, **2,085 with MSC**) is preserved and reused below.
`abstracts.jsonl` is only rewritten at completion, so nothing was corrupted.

**Approach 2 — OAI-PMH bulk (`build-msc-zbmath-oai.py`): the chosen path.** zbMATH Open exposes an
OAI-PMH endpoint at **`https://oai.zbmath.org/v1/`** with:
- **Sets by MSC top-level class** — `setSpec=11` = "11-XX Number theory", **`completeListSize=188,219`**
  records. So we harvest ONLY the NT set, not all ~4.5M zbMATH records.
- **Two metadata formats**, and the trade between them is the crux:
  - `oai_dc` — lean, fast (~100 records/batch, quick). Carries MSC as `dc:subject` (e.g.
    `"11F46; 11F30"`), `dc:title`, `dc:date` (year) — but **NO DOI** (`dc:identifier` is zbMATH's
    internal id). → must join by **title (+ year)**.
  - `oai_zb_preview` — rich: `zbmath:doi` + `zbmath:classifications` + review text/references, so it
    CAN join by DOI — but records are heavy (**~0.68 s/record → ~35 h** for 188k). Impractical.
- **Chosen**: harvest `oai_dc` set=11 → build `norm_title → {msc, year}` index (drop titles that
  collide across different papers) → join corpus by normalized title with year±1 → cache to
  `msc-oai-index.json`. **Keep the DOI-joined MSC from Approach 1 (DOI wins — higher confidence);
  title-join only fills the gaps.** Then restart py-sidecar with `ET_GROUND_SUBJECTS=msc:11`.

**Harvest speed — MEASURED 2026-07-08 (monitored run):** the endpoint is throttled to **~9.4 s per
request** (an IP-level rate-limit — identical to the REST API's 9.4 s/row, so it's the same throttle),
but each OAI batch returns **100 records** → **~0.094 s/record → ~5 h for all 188,219 NT records**.
That is **~100× more efficient per record than the per-DOI API** (which was 1 record/request → ~11
days), so OAI is the right call even under the throttle. The earlier 3-page validation "timeout" was
just this slowness × the 60 s-timeout+retry fetch on a cold start; the real run progresses fine (first
progress line at 5,000 records ≈ 8 min). Sequential (resumptionToken chain can't be parallelized), so
~5 h is the floor. Acceptable for a background job; MSC is an enhancement, not a blocker.

**No usable HuggingFace alternative (checked 2026-07-08):** arXiv metadata snapshots
(`librarian-bots/arxiv-metadata-snapshot`, 28.9k downloads) join by arXiv id but carry only
`categories` (arXiv classes like math.NT) — **no MSC**. zbMATH-derived HF sets (`AnkitSatpute/
zbMath_mscref` = text+label 82k; `math-similarity/class-zbmath-identifier` = zbMATH `an`+MSC, 439k)
DO have MSC but are keyed by zbMATH accession ids or shaped as text→label classification — **not
joinable** to our DOI/arXiv-keyed corpus. So the OAI harvest remains the path.

**Other caveats:**
- **Title-join is fuzzier than DOI** — mitigated by exact normalized-title match + year±1 + dropping
  ambiguous (same-title-different-paper) entries, but expect some misses and a small false-positive
  risk. DOI-joined rows (Approach 1) are exact and take precedence.
- MSC is an **enhancement**, not a blocker: `ET_GROUND_SUBJECTS=mathematics` (field-level) already
  gates correctly (§32.5c). MSC only adds finer intra-math resolution.

See §32.5c in phase-32-manuscript-grounding.md (the shipped subject gate this generalizes — its
MSC allowlist is the target this addendum builds toward) and phase-32-openalex-corpus.md item B
(the OpenAlex build + subject-tag backfill).
