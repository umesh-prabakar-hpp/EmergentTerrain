# Phase 6 D1.1 — Literature-Corpus Scoping Memo

**Status:** Design memo. No code. Deliverable for D1.1 of
[phase-6-novelty-levers.md](phase-6-novelty-levers.md). Authored 2026-05-12.
Stops short of implementation; locks decisions that D1.2 (corpus + read-only
service) will execute against.

**Reading order:** Skim §1 to see the per-topic references the corpus needs to
hold. §§2–7 lock the storage shape, prompt integration, coverage policy,
update cadence, lookup strategy, and out-of-scope items. Each section ends
with a one-line **Decision** that D1.2 inherits without further debate.

---

## 1. Per-topic bibliography

Survey of [MathPhysicsEngine](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java)'s 15 deterministic `*At` methods (the 5 INTERDISCIPLINARY topics compose these and need no separate reference). For each method we name the textbook closed-form the engine is computing against, plus the magnitude-estimate region the narrator should be able to quote back at the LLM.

### MATHEMATICS (5)

| Method | Closed-form | Reference | Magnitude estimate |
|---|---|---|---|
| `primeConstellationsAt` ([476](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L476)) | Hardy-Littlewood twin-prime: π₂(N) ~ 2C₂·N/ln²(N), C₂ ≈ 0.66016 | Hardy & Littlewood 1923 ("Some Problems of Partitio Numerorum III") | Twin density ≈ 1.32/ln²(N); deviation ≤ 15% is the Phase 5 B5 strong-support band |
| `collatzStoppingTimesAt` ([529](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L529)) | Open conjecture (no closed form). Empirical: max stopping time grows ~O(log² N) | Lagarias 1985 ("The 3x+1 problem and its generalizations") | Stopping times up to ~10²-10³ typical for n < 10⁶; conjecture holds for all n < 2⁶⁸ (Barina 2020) |
| `goldbachPartitionsAt` ([581](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L581)) | r₂(n) ~ 2C₂·n / ln²(n) by analogy with Hardy-Littlewood | OEIS A002375 (lookup table for n ≤ 10⁵). Hardy-Littlewood 1923 | Partition counts in [400, 600) average ~15-17; the engine's regime is well-tabulated |
| `aliquotSequencesAt` ([642](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L642)) | Catalan-Dickson conjecture (open). Aliquot termination empirically dominant | Guy 1981 ("Unsolved Problems in Number Theory" §B6) | Termination rates ≈ 80-95% in [500, 600); the five "Lehmer five" exceptions known |
| `happyNumbersAt` ([703](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L703)) | Density ≈ 14.3% asymptotic (no published proof; empirical convergence) | Guy 2004 ("Unsolved Problems in Number Theory" §E34); Honaker conjecture | Density 0.14–0.16 for windows of width ≥ 200 |

### PHYSICS (5)

| Method | Closed-form | Reference | Magnitude estimate |
|---|---|---|---|
| `projectileWithDragAt` ([760](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L760)) | No closed form for quadratic drag. Optimum angle < 45° always; falls to ~30° at moderate k, ~10° at high k | Parker 1977 ("What is the capture cross section of two charged particles?" — drag terms in projectile motion); textbook in Marion & Thornton 5e §4.4 | Optimum 35°–42° at k ≤ 0.01; 20°–30° at k ≈ 0.05; below 15° at k > 0.1 |
| `pendulumBreakdownAt` ([802](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L802)) | T(θ) = T₀·(1 + sin²(θ/2)/4 + 9sin⁴(θ/2)/64 + …) (elliptic integral expansion) | Huygens 1673 (*Horologium Oscillatorium*); modern: Beléndez 2007 ("Exact solution for the nonlinear pendulum") | 1% threshold at θ ≈ 22.85°; 10% at ≈ 70°; small-angle expansion sufficient through ≈ 30° |
| `vanDerWaalsDeviationAt` ([848](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L848)) | (P + a/V²)(V − b) = RT with CO₂: a = 3.640, b = 0.04267 | Van der Waals 1873 (PhD thesis). Standard thermodynamics texts (e.g. Atkins 11e §1C) | Deviation > 1% above P ≈ 5–10 atm at T = 300K; > 50% at P = 100 atm below T_c ≈ 304K |
| `diffractionSurveyAt` ([897](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L897)) | d·sin(θₘ) = mλ → max order = floor(d/λ) | Fraunhofer 1821 ("Bestimmung des Brechungs- und des Farbenzerstreuungs-Vermögens"); Hecht *Optics* 5e Ch 10 | For d = 2 µm: 5 orders at 400 nm, 2 at 700 nm |
| `rlcDampingTransitionAt` ([932](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L932)) | R_crit = 2√(L/C); ω₀ = 1/√(LC); Q = ω₀L/R = ½ at R = R_crit | Maxwell 1873 (*Treatise on Electricity and Magnetism* Vol I §784); textbook EE | For L = 10 mH, C = 1 µF: f₀ ≈ 1.59 kHz, R_crit ≈ 200 Ω, Q ≤ 0.5 above R_crit |

### MATERIALS (5)

| Method | Closed-form | Reference | Magnitude estimate |
|---|---|---|---|
| `compositeFailureModeAt` ([979](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L979)) | Voigt rule of mixtures: σ_L = Vf·Ef·ε_y + (1−Vf)·σ_ym (longitudinal). Fibre yield ≈ 2–3 GPa for steel | Voigt 1889; Hashin & Shtrikman 1963 ("A variational approach to elastic behavior of multiphase materials") | σ_L ≤ 3 GPa for any real polymer-matrix composite (matches Phase 5 B2 bound) |
| `binarySpherePacking` ([1002](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L1002)) | FCC base η = π/(3√2) ≈ 0.7405; binary peak ≈ 0.793 at r ≈ 0.414 (octahedral void) | Hudson 1949 ("Density and packing in an aggregate of mixed spheres"); Hales 2005 proof of Kepler conjecture | Peak η in [0.78, 0.83]; r_optimal in [0.40, 0.45] |
| `compositeStiffnessCrossoverAt` ([1040](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L1040)) | Reuss bound: 1/E_T = Vf/Ef + (1−Vf)/Em | Reuss 1929 ("Berechnung der Fließgrenze von Mischkristallen…") | For Ef/Em = 10: 2× crossover at Vf ≈ 0.56; for Ef/Em = 100: Vf ≈ 0.51 |
| `arrheniusRuleValidityAt` ([1074](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L1074)) | D = D₀·exp(−Ea/RT); +10K ratio = exp(Ea/R · (1/T − 1/(T+10))) | Arrhenius 1889 ("Über die Reaktionsgeschwindigkeit bei der Inversion von Rohrzucker durch Säuren") | Van't Hoff "doubling per 10K" holds within ±10% only for narrow Ea bands; >10× ratios at Ea > 200 kJ/mol, T < 300K |
| `thermalStressSafetyAt` ([1126](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java#L1126)) | σ_thermal = E·α·ΔT (fully constrained). Critical α = σ_Y / (E·ΔT) | Timoshenko *Strength of Materials* 3e Ch 14; first-year mechanics | For steel E = 200 GPa: α_c ≈ 5 µm/(m·K) at ΔT = 100K, σ_Y = 100 MPa |

### INTERDISCIPLINARY (5)

The engine's interdisciplinary topics compose pairs of the methods above with **engine-fabricated bridges**:

- `collatzRlcDepthResonance` → Collatz depth at n=k drives RLC inductance L
- `primeGapDefectThermalLoading` → prime gap g drives crystal CTE α
- `resonantThermalFatigueWindow` → RLC f₀ drives composite fatigue cycle
- `happyNumberPackingAnomaly` → happy-density Δ drives sphere packing seed
- `vdwCompositeCrossover` → VdW pressure P drives composite stress σ

None has a real-world literature analogue — the bridges are constructed by the simulation, not derived from physics. Adding "literature" for these would either be empty or fabricated.

**Decision (§1):** The corpus carries one entry per non-INTERDISCIPLINARY topic (15 total in v1, room for more entries per topic later). INTERDISCIPLINARY topics get a single sentinel entry per topic stating *"This bridge is engine-constructed; no real-world literature applies."* — explicit rather than empty so the lookup is unambiguous.

---

## 2. Reference-corpus shape

### 2.1 Storage

YAML resource at `src/main/resources/literature-corpus.yml`. Loaded at startup by D1.2's `LiteratureSearchService` via `@PostConstruct`. In-memory only after load; reload requires restart.

Reasons over JPA:
- The corpus is small (≤ 200 entries) and changes infrequently. A migration per reference would be disproportionate.
- YAML is human-editable in any editor. JPA gates curation behind a SQL session.
- No query patterns beyond `findFor(domain, topic)` need indexing.

If the corpus grows past ~500 entries or operators start adding tens of references per week, the memo should be revisited and promotion to a `LiteratureReference` JPA entity considered.

**Decision (§2.1):** YAML resource. Single file, in-memory load at startup, restart to reload.

### 2.2 Per-entry record shape

```yaml
- domain: MATHEMATICS
  topic: Prime Constellation Search
  citation: "Hardy & Littlewood (1923), 'Some Problems of Partitio Numerorum III'"
  closed_form: "π₂(N) ~ 2·C₂·N / ln²(N), C₂ ≈ 0.66016"
  valid_region: "Asymptotic; finite-interval deviations up to ±35% in [10², 10⁴]"
  magnitude_estimate: "Twin density ≈ 1.32/ln²(N); ±15% strong-support band per Phase 5 B5"
  notes: "Hardy-Littlewood prime k-tuple conjecture; open in general"
```

Six fields, all strings. `domain` and `topic` are the lookup key. `citation`, `closed_form`, `valid_region`, and `magnitude_estimate` get spliced into the prompt context. `notes` is operator commentary that never reaches the LLM.

Rationale for structured record over freeform YAML:
- The Phase 5 magnitude-bound table ([MagnitudeRangeValidator.BOUNDS](../src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java)) is also structured. Consistent shape across validators reduces operator confusion.
- The narrator prompt has limited tokens; structured fields let D1.3 select which fields to include rather than dumping the whole entry.
- D2 (Discussion / Related-Work section) can read the same fields without renegotiating the schema.

**Decision (§2.2):** Six-field structured record. `domain` + `topic` are the key; `citation`, `closed_form`, `valid_region`, `magnitude_estimate` are prompt-visible; `notes` is operator-only.

### 2.3 Curation policy

Humans only. The LLM cannot propose new references autonomously, ever. This is a stronger position than Phase 4c Piece 1 took on prompt templates because:

- Phase 4c's failure mode for an LLM-proposed template is "narration regresses" — observable in replication rates, recoverable by rolling the template back.
- A wrong literature reference *poisons every future paper on that topic* with the same hallucinated authority. The corpus is read by the LLM as ground truth.

Operators can ingest references from external sources (curated reading lists, textbook citations) but every entry crosses a human review step before landing in `literature-corpus.yml`.

**Decision (§2.3):** Humans only. The LLM does not write or amend the corpus. Operator review precedes every commit that touches `literature-corpus.yml`.

---

## 3. Prompt integration shape

### 3.1 Where in the prompt the literature lands

Three options:

a) **System-prompt block.** Adds "You have access to the following prior literature: …" to the system prompt. Concise; ambient; the LLM may forget it across long turns.

b) **User-prompt block.** Prepends the reference to the user prompt right before the narration request. Most prominent; consumes the most tokens.

c) **Named slot in the existing template.** Adds `{{ literatureContext }}` to `NARRATION_PROFESSOR_SYSTEM` / `NARRATION_PROFESSOR_USER` / `NARRATION_REVISION` and renders it conditionally. Fits the Phase 4c Piece 2 [PromptTemplateRegistry](../src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java) discipline.

Option (c) wins on three counts: (1) it preserves the registry's template-versioning so D1.3's prompts can A/B against the unaugmented variants once Phase 4c Piece 3 is ready; (2) it does not duplicate the system/user split per template; (3) it keeps every prompt-text change inside the registry.

**Decision (§3.1):** Named slot. `{{ literatureContext }}` is added to `NARRATION_PROFESSOR_SYSTEM`, `NARRATION_PROFESSOR_USER`, and `NARRATION_REVISION` templates as a new bump to v2.

### 3.2 Excerpt length

The four prompt-visible fields combined are typically ≤ 500 chars per reference. With a 1-reference-per-topic v1 corpus, the cost is bounded at ~500 chars per professor invocation — well under 1% of the typical 50–100k prompt token budget.

D1.3 may emit up to **3 references** per (domain, topic) lookup. Selection rule: prefer the highest-priority entry (operator-assigned `priority: int` field, default 0). If multiple entries tie, pick deterministically by alphabetical citation.

The 3-reference cap accommodates D2's Related-Work section (which wants more than one citation) while keeping the budget bounded.

**Decision (§3.2):** Up to 3 references per lookup. Six-field record gains an optional `priority` integer (default 0). Tie-break alphabetically by `citation`.

### 3.3 Empty-lookup behaviour

When the (domain, topic) pair has no references in the corpus (legitimate for an INTERDISCIPLINARY topic with a sentinel entry, or for a topic the corpus hasn't been curated for yet), the slot renders empty. The narrator prompt is identical to the pre-D1.3 version.

Explicit "no prior literature found" lines invite the LLM to confabulate novelty claims — exactly the failure mode Phase 5 A5 already removed. Better to stay silent.

**Decision (§3.3):** Empty lookup → empty slot → unchanged prompt. No "no literature found" annotation.

---

## 4. Coverage policy

Before D1.3 (the narrator-prompt change) can ship:

- **MATHEMATICS, PHYSICS, MATERIALS:** ≥ 1 reference per topic (15 / 15 covered).
- **INTERDISCIPLINARY:** ≥ 1 sentinel entry per topic (5 / 5 covered with the "engine-constructed bridge" sentinel).

Total minimum corpus: **20 entries**. Each one curated by a human operator.

Reasons for full coverage rather than partial:

- Partial coverage means the literature stage fires for some topics and not others. The peer-review pipeline can't easily tell whether a paper had literature context — it would need to query the corpus to know.
- 15 known closed-forms is small enough that a single sitting can curate them with citations from standard textbooks (the references in §1 above provide the starting list).
- INTERDISCIPLINARY sentinels avoid making the lookup fire-or-not-fire ambiguous.

**Decision (§4):** D1.3 does not ship until the corpus has at least one entry per non-INTERDISCIPLINARY topic and one sentinel per INTERDISCIPLINARY topic — 20 entries minimum.

---

## 5. Update cadence

### 5.1 Existing papers do not re-narrate

When new references are added to the corpus, papers already in the database keep their existing narrations. The literature stage is read-once-at-narration-time. Reasons:

- Re-narrating would require an LLM call per existing paper — currently 500+ rows. At Groq quota limits, the room would freeze for days.
- Phase 4c Piece 2 already established per-paper template provenance ([ResearchPaper.narrationTemplateId](../src/main/java/com/emergentterrain/research/ResearchPaper.java)). The same provenance pattern means each paper documents which literature corpus version it was narrated against.

### 5.2 Provenance field

Add a new `literatureCorpusVersion` string column on `ResearchPaper`, populated at narration time. Nullable; pre-D1.3 papers stamp null. The value is a simple counter or git-style hash of the corpus file at narration time.

**Decision (§5):** No re-narration. Add `ResearchPaper.literatureCorpusVersion` so future analysis can distinguish papers that saw a stale corpus from papers that saw a current one.

---

## 6. Lookup strategy

### 6.1 Exact (domain, topic) match

D1.3's lookup is keyed exactly on `(domain, topic)` strings as provided by `PaperDraft`. Case-insensitive equality (matching the existing `MagnitudeRangeValidator` and `CrossDomainHypothesisGate` patterns). No fuzzy matching, no hypothesis-text extraction, no LLM mediation.

Reasons:

- Exact match is fast and unambiguous. The corpus is small enough that the worst-case scan is microseconds.
- Fuzzy match invites the LLM to pull a barely-related reference and assert relevance. The error mode resembles the Phase 5 B4 cross-domain non-sequitur class — exactly what the gate was built to stop.
- The engine controls both `domain` and `topic` strings. They are stable across releases.

### 6.2 Multiple references per (domain, topic)

Per §3.2 the corpus can hold > 1 entry per key. Selection: sort by `priority` descending, then by `citation` ascending, then take up to 3.

**Decision (§6):** Exact case-insensitive `(domain, topic)` lookup. No fuzzy match. Multi-entry selection is deterministic via `priority` then alphabetical `citation`.

---

## 7. Out of scope for D1

To keep this from sprawling:

- **No external corpus ingestion.** No arXiv, MathOverflow, OEIS API calls. The corpus is hand-curated.
- **No LLM-generated references.** Operators write the corpus; the LLM never adds entries autonomously.
- **No reference dependency graph.** A "see also" cross-reference field is tempting but adds complexity for unclear gain. Defer.
- **No corpus hot-reload.** Restart to pick up corpus changes.
- **No per-bot literature filtering.** Every bot in a room sees the same references for the same (domain, topic) pair. Bot-specific filtering belongs in a future "personalised literature" phase if it ever ships.
- **No multilingual corpus.** English only.

---

## 8. Open questions D1.1 does *not* answer

These belong to D1.2 / D1.3 implementation and remain unresolved:

- The exact wording of any one reference (operators pick).
- Whether to include an `equation_form` field as a separate slot from `closed_form` (probably no — `closed_form` already carries the equation).
- The `priority` field's semantics — strict integer ordering or banded ("primary" / "secondary" / "tertiary"). Memo recommends integer for simplicity; D1.2 picks.
- The narrator-prompt wording around the literature block — "You have access to the following prior literature: …" vs "Known closed-forms for this region:" vs structured JSON. Memo recommends prose; D1.3 picks.
- How D2's Discussion section consumes the corpus — same `findFor` API, or a richer API that filters by `valid_region`. Memo recommends same API.

---

## 9. Pre-conditions for D1.2

By analogy with Phase 4c Piece 2:

1. This memo merged.
2. Operator who will curate the v1 corpus identified and committed to ≥ 1 day of curation time.
3. Phase 5 Wave 4 + Wave 5 flags all observed running ≥ 2 weeks per [phase-6-novelty-levers.md](phase-6-novelty-levers.md) pre-conditions.
4. The starting bibliography in §1 reviewed for accuracy — a wrong reference here propagates into the corpus and into every paper that touches the topic.

If any of these is missing, defer D1.2 even if this memo is approved.

---

## 10. Recommended timing (as of 2026-05-12)

| Piece | Earliest start | Latest reasonable start |
|---|---|---|
| D1.1 (this memo) | Now | This month |
| D1.2 (corpus + service) | After D1.1 merges AND a curating operator is named | Open-ended |
| D1.3 (prompt integration) | After D1.2 ships AND 2+ weeks of Phase 5 Wave 5 runtime | Open-ended |

D1.1 is intentionally low-effort (~2 days). The curation work in §1 is where the real diligence lives — every closed-form needs to be checked against the engine's actual computation by someone who can read the math.

---

## 11. Pointers for the next session

When picking this up:

- [phase-6-novelty-levers.md](phase-6-novelty-levers.md) — the parent phase doc this memo executes against.
- [phase-4c-piece-1-memo.md](phase-4c-piece-1-memo.md) — the precedent this memo's style mirrors (one-line **Decision** per section).
- [src/main/java/com/emergentterrain/research/MathPhysicsEngine.java](../src/main/java/com/emergentterrain/research/MathPhysicsEngine.java) — the 15 `*At` methods §1 enumerates. Read each carefully before curating the matching reference.
- [src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java](../src/main/java/com/emergentterrain/research/MagnitudeRangeValidator.java) — Phase 5 B2's hand-curated bound table is the closest existing precedent for §2's record-shape decision.
- [src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java](../src/main/java/com/emergentterrain/research/PromptTemplateRegistry.java) — Phase 4c Piece 2's `(id, version)` keying convention §3.1's slot decision builds on.
