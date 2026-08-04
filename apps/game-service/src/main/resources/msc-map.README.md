# `msc-map.jsonl.gz` — committed MSC lookup for the number-theory corpus

## What this is
A compact, gzipped lookup from **OpenAlex work id → MSC codes** (Mathematics Subject
Classification), e.g.:

```json
{"_meta": {"generated": "YYYY-MM-DD", "source": "zbMATH Open, OAI-PMH set=11 (Number Theory)", "license": "CC-BY-SA-4.0", "records": 40000, ...}}
{"id": "W2007557094", "msc": ["11N37", "11M06"]}
{"id": "W1199646596", "msc": ["11M06"]}
```

The **first line is a `_meta` header** recording the **generation date**, source, license, and record
count — so you always know how current this data is and where it came from. `scripts/msc-map.py
--apply` prints it.

## Why it's committed (and not just built)
These codes upgrade the literature-grounding subject gate from field-level (`Mathematics`) to
MSC-prefix (`ET_GROUND_SUBJECTS=msc:11` = Number Theory — see §32.5c/§32.5d). Generating them requires a
**~5-hour harvest of the zbMATH OAI-PMH endpoint** (188,219 Number-Theory records, throttled to
~9.4 s/request and prone to transient HTTP 500s). Rather than make every open-source user endure that
flaky harvest, the finished result (~0.3 MB gzipped) is committed here.

## ⚠️ Attribution / license (NOTICE — required)
This file is **derived from [zbMATH Open](https://zbmath.org)** and is redistributed under
**Creative Commons Attribution-ShareAlike 4.0 (CC-BY-SA-4.0)**.

- Data source: zbMATH Open, harvested via its OAI-PMH endpoint (`https://oai.zbmath.org/v1/`), set 11
  (Number Theory), `oai_dc` metadata.
- The **MSC scheme** (Mathematics Subject Classification) is maintained by zbMATH and Mathematical
  Reviews (American Mathematical Society).
- Per CC-BY-SA-4.0: retain this attribution, and share any redistribution/derivative of *this file*
  under the same license. (This NOTICE applies to `msc-map.jsonl.gz` specifically; it does not change
  the license of the rest of the repository.)

## How to use it

**Consume (open-source users — the fast path, no harvest, no network):**
```bash
scripts/build-nt-corpus.sh          # build the corpus (abstracts.jsonl) first
python3 scripts/msc-map.py --apply  # inject `msc` into abstracts.jsonl from this committed map
# then start the py-sidecar with ET_GROUND_SUBJECTS=msc:11 to gate on MSC Number Theory
```

**Regenerate (maintainers — only when refreshing the corpus):**
```bash
python3 scripts/build-msc-zbmath-oai.py   # ~5 h zbMATH OAI-PMH harvest + join (writes msc into abstracts.jsonl)
python3 scripts/msc-map.py --extract      # rewrite this file (stamps today's date into _meta)
git add src/main/resources/msc-map.jsonl.gz && git commit -m "data: refresh MSC map (zbMATH CC-BY-SA)"
```

## Coverage & caveats
- Covers only the corpus rows that matched zbMATH (by DOI, then by title+year). MSC is an
  *enhancement*: `ET_GROUND_SUBJECTS=mathematics` (field-level) already gates correctly without it.
- Number theory only (`set=11`). Multi-domain expansion would harvest additional MSC sets.
- Title-joined rows carry a small false-positive risk; DOI-joined rows are exact and take precedence.
