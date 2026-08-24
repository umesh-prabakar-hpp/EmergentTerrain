# Phase 32 — retained reference survey: adopted / already-covered / rejected

Disposition table for the retained, permissively licensed 2026-06-06 reference material. File refs
point into `/opt/apps/ide/vscode/ai/reference-from-github/<repo>/`.

Legend: **→ 32.x** adopted into a phase-32 item · **covered** an ET mechanism or pending phase
item already owns it · **rejected** reviewed and deliberately not ported.

---

## A. data-to-paper (`data-to-paper/`, Technion-Kishony-lab)

| Mechanism | Key files | Disposition |
|---|---|---|
| `ReferencedValue` + LaTeX hypertarget/hyperlink pair: every computed value rendered with a provenance label; writer must cite `\hyperlink{label}{value}`; label↔target validated, values matched within rounding tolerance | `src/data_to_paper/code_and_output_files/ref_numeric_values.py:44-125`, `referencable_text.py:13-162`, `base_products_conversers.py:328-446` | **→ 32.1 / 32.2** — the phase's headline adoption. ET shape: tool results compose a **verified-values manifest**; prose numbers resolve against it |
| Untraceable-number detection: any numeric in prose not hyperlinked is flagged; LLM forced to link or mark `[unknown]` | `base_products_conversers.py:412-446`, `utils/check_numeric_values.py:5-64` | **→ 32.2** — replaces grep-side guessing with manifest lookup; the `[unknown]` escape hatch is the honest-abstention path |
| Tolerance rules worth porting verbatim: allowlist of trivially-legit numbers; significant-figure rounding match (not float equality); 100× percent ambiguity; numbers after `<` exempt | `check_numeric_values.py` (allowlist `:204`, `<`-exemption `:250`) | **→ 32.2** — directly generalizes four of ET's hand-fixed validator FP classes |
| `PValue` constrained value type: forbidden ops raise, creation tracked to producing function, display floors (`<1e-6`), formatting enum | `run_gpt_code/overrides/pvalue.py:15-175`, `utils/operator_value.py:6-99` | **→ 32.1** (reduced) — ET analog: manifest entries carry `producedBy=call_N`, kind, and a canonical rendering; the operator-override machinery is Python-exec-specific, rejected |
| Hypothesis locking + bounded re-goal loop: goal locked before plan, plan before code; novelty failure loops back to GOAL ≤ `max_goal_refinement_iterations` (3) | `research_types/hypothesis_testing/steps_runner.py:97-200`, `scientific_stage.py` | **→ 32.3** — the fresh-pre-registration shape (ET already pre-registers; the *bounded re-registration loop* is the missing piece) |
| `RunIssue` severity hierarchy feeding regeneration (NoCode → StaticCheck → Runtime → MissingOutputFiles → content issues), most-severe-first messaging | `run_gpt_code/run_issues.py` | covered — ET's gate-retry queue + revision-prompt rules own this; severity-ordering noted as a refinement input |
| Dual-converser performer↔reviewer dialog with exact termination phrase, ambiguity retry, max rounds | `base_steps/dual_converser.py:154-437` | covered — professor–student critique + panel_revision already equivalent |
| Human-in-the-loop modes (NONE / LLM_FIRST / LLM_UPON_REQUEST) at stage boundaries | `types.py:4-11`, `dual_converser.py:320-331` | covered — ARC §B6 publish-approval gate (pending, low priority) already holds this slot |
| P-value star conversion, p-hacking guards via planned-analysis capture | `pvalue.py:293-320` | rejected — frequentist-stats-specific; ET's W4 stochastic-null + MCC own the analogous risk |

## B. paper-qa (`paper-qa/`, Future-House PaperQA2)

| Mechanism | Key files | Disposition |
|---|---|---|
| Evidence pipeline: MMR retrieval → per-chunk LLM summary with JSON `relevance_score` 0–10 → keep score>0 → answer over scored contexts | `src/paperqa/docs.py:456-586`, `core.py:178-350`, `prompts.py:3-23,108-136` | **→ 32.5** — the gather-evidence contract for the grounding sidecar |
| Citation-key fidelity: contexts get `pqac-{hash}` ids; answer prompt may cite only listed keys; bibliography built from *used* keys only; LLM-generated citations stripped from summaries | `types.py:238-317`, `474-490`, `prompts.py:38-69`, `core.py:359` | **→ 32.5** — the faithfulness guard set, adopted wholesale |
| "I cannot answer" honesty path when evidence insufficient | `prompts.py:25-28`, `docs.py:649-653` | **→ 32.5** — maps onto ET's house norm (CANNOT_COMPUTE feeds back, never terminates); a grounding miss is an honest "literature silent", not a fabricated citation |
| Contradiction-detection prompt variant (contracrow): claim vs evidence, 11-level label from explicit-contradiction to explicit-agreement | `configs/contracrow.json` | **→ 32.5** — the *supported / contradicted / silent* trichotomy for promotion-track claims |
| Agentic loop: paper_search / gather_evidence / gen_answer / complete tools; fake-agent deterministic mode; timeout with forced partial answer | `agents/tools.py:109-441`, `agents/main.py:54-323` | **→ 32.5** (reduced) — v1 uses the deterministic fake-agent shape (search → gather → classify); LLM-driven tool selection deferred |
| Offline operation: Tantivy full-text index (local, BM25), local embeddings (sentence-transformers/Ollama), NumpyVectorStore, metadata APIs optional | `agents/search.py:113-719`, `settings.py` | **→ 32.5** — decisive for ET: the corpus is a local directory; no external API on the request path |
| Metadata cascade (Crossref → Semantic Scholar → journal-quality post-processor), citation counts | `clients/__init__.py:84-263`, `clients/crossref.py:40-77`, `clients/semantic_scholar.py:61-116` | **→ 32.5** deferred tier — corpus-build-time enrichment only (offline at serve time) |
| LitQA eval harness (multiple-choice grading prompts) | `prompts.py:152-162` | covered — 32.4's calibration harness is the ET-shaped evaluator; noted as prior art |
| Config profiles (fast / high_quality / contracrow JSON presets) | `configs/*.json` | **→ 32.5** (sidecar ships with `et-grounding.json` preset) |

---

## Cross-repo synthesis

The retained sources cover construction-time number provenance (32.1/32.2), bounded
re-registration (32.3), and literature grounding (32.5). The absolute review head (32.4) is
grounded in EmergentTerrain's own labeled operator verdicts and calibration acceptance bar.
