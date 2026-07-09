# Corpus & grounding data — inventory and store structures

The literature-grounding corpus and its auxiliary stores. Data lives **outside the repo** under
`~/.emergentterrain/corpus-nt/` (built by `scripts/*`, see RUN.md §0); the repo ships the *builders*,
plus one committed derived asset (`src/main/resources/msc-map.jsonl.gz`). §-refs point to
`phases-for-human-like-performance/phase-32-manuscript-grounding/`.

## 1. Download inventory

| # | Source | Role | Status | In RUN? | Storage | How used |
|---|---|---|---|---|---|---|
| Meta | OpenAlex NT abstracts | metadata spine | ✅ | 0a | 123 MB | **BM25 grounding index** (title+abstract) — every `/ground/claim` searches it |
| FT | jedibear/S2ORC (AWS) | broad full text | ✅ | ptr →§32.5e | 2.3 GB | `read_reference` **`fulltext/`** tier (paraphrase-safe; PDF-Grobid math) |
| FT | ar5iv bulk HTML (AWS) | math-fidelity full text | ⏳ planned | ptr →§32.5e | 318 GB dl → few GB | `read_reference` — verbatim LaTeX, quote exact prior equations |
| FT | Phase-33 ar5iv HTML cores | full text (curated) | ✅ | 0c | 61 MB | `read_reference` **`cores-html/`** tier (top: quotable LaTeX) |
| FT | Phase-33 PDF cores | full text (curated) | ✅ | 0b | 65 MB | `read_reference` **`cores/`** tier (PDF, paraphrase) |
| FT | unarXive open subset | full text (stopgap) | ✅ superseded | 0d | ~0 | replaced by jedibear |
| MSC | zbMATH MSC (apply) | classification | 🔄 | 0e | ~0.3 MB gz | **subject gate** `ET_GROUND_SUBJECTS=msc:11` |
| MSC | zbMATH MSC (harvest) | classification (regen) | 🔄 | 0e maint. | ~19 MB transient | generates the committed map |
| Dom | OEIS | domain reference | ✅ | 0f | 140 MB | `/lookup/oeis` — integer-sequence identification |
| Dom | DLMF | domain reference | ✅ | 0g | 7.7 MB | `/lookup/dlmf` — special-function sections |

Read-reference tier preference (best math fidelity first): `cores-html` → `fulltext` → `cores`.
Subject-gate entry kinds: plain token (`mathematics`) = substring on OpenAlex field/subfield/topic;
`msc:`-prefixed (`msc:11`) = anchored MSC-code prefix (see RUN.md subject-gate note).

## 2. Store structures (schemas)

All are JSONL under `~/.emergentterrain/corpus-nt/` unless noted. `id` = OpenAlex work id (`W…`).

```jsonc
// abstracts.jsonl — the corpus (one work per line)
{"id","doi","title","year","cited_by_count","venue","authors","abstract","query",
 "field","subfield","topic",   // OpenAlex primary_topic tags (subject gate)
 "msc"}                          // ← added by msc-map --apply / the zbMATH join (list, e.g. ["11N37"])

// fulltext/<id>.txt          — plain full-text body (jedibear/S2ORC or ar5iv), one file per work
// fulltext-manifest.jsonl    — {"id","source","chars"}   source ∈ {s2orc, ar5iv, unarxive}
// cores/<id>.txt, cores-html/<id>.txt + MANIFEST.json  — phase-33 curated cores

// domain-refs/oeis.jsonl     — {"id":"A000045","name":"Fibonacci…","terms":["0","1","1",…]}  (terms are STRINGS)
// domain-refs/dlmf.jsonl     — {"id":"25.10","title":"§25.10 Zeros ‣ …","text":"…"}

// src/main/resources/msc-map.jsonl.gz  (COMMITTED, gzipped; zbMATH CC-BY-SA — see msc-map.README.md)
{"_meta":{"generated":"YYYY-MM-DD","source":"zbMATH … set=11","license":"CC-BY-SA-4.0","records":N}}
{"id":"W2007557094","msc":["11N37","11M06"]}
```

## 3. Grounding + lookup endpoints (py-sidecar, `:8089`, offline)

| Endpoint | In | Out | Wired |
|---|---|---|---|
| `POST /ground/claim` | `{claim, topK}` | `{verdict, confidence, evidence:[…]}` | ✅ tool `search_literature` / `read_reference` |
| `POST /lookup/oeis` | `{terms:[…], topK}` | `{matches:[{id,name,terms_preview,url}]}` | ⚠️ endpoint live; **JVM tool pending** |
| `POST /lookup/dlmf` | `{query, topK}` | `{sections:[{id,title,snippet,url}]}` | ⚠️ endpoint live; **JVM tool pending** |

`/lookup/oeis` identifies an integer sequence (from-start signature + bounded scan; validated
`[0,1,1,2,3,5,8,13]→A000045`). `/lookup/dlmf` is BM25 over the DLMF sections (validated
`"zeta zeros"→§25.10`). Both load at py-sidecar startup via `dr_init_from_env!` (env `ET_DOMAIN_REFS`,
default `corpus-nt/domain-refs`), degrade to `not_configured` when the store is absent.

## 4. Remaining wiring — professor-facing OEIS/DLMF tool (spec)

The sidecar lookups are live; the JVM tool that lets the professor CALL them is not yet wired (it's a
multi-point change into `ResearchNarrationService`'s tool loop and needs a compilable app — do it in a
git worktree or with the app stopped). To add it, mirror `search_literature`/`read_reference`:
1. **`DomainRefClient`** (new class, mirror `GroundingClient`): `lookupOeis(terms,topK)` /
   `lookupDlmf(query,topK)` POSTing to `app.julia.pySidecarUrl`.
2. **`@Bean`** in `LiteratureGroundingConfig` (like `groundingClient`).
3. **`MathToolSuite`**: a `domainRefClient` field + setter; `LOOKUP_OEIS_TOOL_JSON` /
   `LOOKUP_DLMF_TOOL_JSON` schemas; append them in `toolsJson()` when `domainRefClient != null`;
   dispatch cases in the executor `switch`.
4. **`ResearchNarrationService`**: inject the bean onto the constructed `MathToolSuite` (where the
   other clients are set) — for MATHEMATICS papers only. Gate DORMANT (null client ⇒ tools unadvertised).
5. Prompt: OEIS for "is this computed sequence known?"; DLMF for special-function/zeta claims.
