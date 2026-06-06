# Phase 32 — per-repo survey: adopted / already-covered / rejected

Disposition table for every notable mechanism found in the four 2026-06-06 clones, so nothing is
re-litigated from scratch later (the `arc-feature-considerations.md` convention). File refs point
into `/opt/apps/ide/vscode/ai/reference-from-github/<repo>/`.

Legend: **→ 32.x** adopted into a phase-32 item · **covered** an ET mechanism or pending phase
item already owns it · **rejected** reviewed and deliberately not ported.

---

## A. AI-Scientist (`AI-Scientist/`, SakanaAI v1)

| Mechanism | Key files | Disposition |
|---|---|---|
| Automated reviewer: NeurIPS review form (16 fields, 1–10 overall, binary decision), reflection rounds with "I am done" convergence, N-review ensemble + meta-reviewer aggregation, optional few-shot examples | `ai_scientist/perform_review.py:13-243`, `246-264`, `151-209`, `361-383` | **→ 32.4** — the *shape* (ensemble + reflection + meta-review + absolute decision head); the NeurIPS rubric itself does not transfer to deterministic-math papers |
| Reviewer calibration harness: grade against ICLR 2022 OpenReview human accept/reject, compute accuracy/F1/AUC/FPR/FNR, hyperparameter sweep over reflections / few-shot / ensemble / temperature | `review_iclr_bench/iclr_analysis.py:126-489` | **→ 32.4** — the headline adoption. Swap ICLR labels for ET's own operator verdicts (Acceptable/Minor/Major/Reject per `/paper-review` session) |
| Citation gathering loop: LLM proposes missing-citation location + query → Semantic Scholar top-10 → LLM selects → BibTeX inserted; hard rule "DO NOT ADD ANY NEW CITATIONS" (API-results-only) | `ai_scientist/perform_writeup.py:220-397`, rule at `:237` | **→ 32.5 / 32.6** — the API-only grounding rule is the citation-side twin of the value manifest; loop shape feeds the 32.5 sidecar contract |
| Per-section writeup with section-specific tips dict + two-pass refinement (second pass: clarity, dedup, space) | `perform_writeup.py:130-217` | **→ 32.6** (cheap narration-side adoption) |
| Manuscript error-checking: \cite↔bib validation, figure-file existence binding, duplicate-header removal, iterative chktex + LLM fix | `perform_writeup.py:20-91` | **→ 32.6** in spirit (ET's analog: section-contract + citation validators already exist; the *figure-file binding* is parked with the figure agent) |
| Result grounding by instruction only: error-list prompt forbids "numerical results that do not come from explicit experiments and logs"; no post-hoc enforcement | `perform_writeup.py:182-195` | **covered — ET is already stronger** (validator ladder + [call_N] provenance); noted as the cautionary tale 32.1 exists to avoid |
| Idea generation with seed ideas + reflection loop + implicit dedup via prev-ideas context | `ai_scientist/generate_ideas.py:75-175` | covered — conjecture miner + QD archive + clade selection own ET's idea layer |
| Novelty check via Semantic Scholar query loop (LLM decides query-vs-finalize, ≤10 iterations) | `generate_ideas.py:405-493` | **→ 32.5** (as the *grounding-call shape* for promotion-track papers; the engine is PaperQA2, not raw S2 queries) |

## B. AI-Scientist-v2 (`AI-Scientist-v2/`)

| Mechanism | Key files | Disposition |
|---|---|---|
| Best-first tree search over experiment nodes: Node{plan, code, exec result, metric, is_buggy, analysis, parent/children}; journal of all nodes | `ai_scientist/treesearch/journal.py:23-168` | **→ 32.3** — the investigation-as-tree representation (ET's professor stage is a linear 5-call trace today) |
| Debug-vs-improve branching: buggy node → debug child (probabilistic, `debug_prob`, capped by `max_debug_depth`); good node → improve child | `journal.py:159-168`, `bfts_config.yaml` | **→ 32.3** (the pivot policy: off-target/refuted-as-stated ⇒ "debug" the hypothesis via re-registration; on-target ⇒ deepen) |
| Persistent-error-pattern detection surfaced to the next reflection round | `treesearch/agent_manager.py:1095-1104` | **→ 32.3** (repeated same-failure feeds the pivot decision, mirrors frontier-loop `frontierMaxConsecutiveFailures`) |
| Staged goals injected per stage (initial → tuning → creative → ablation) with LLM stage-progression evaluation | `agent_manager.py:49-167`, `420-502` | **→ 32.3** in reduced form (ET stages: verify-as-stated → probe-binding-regime → refine-or-pivot); full 4-stage ML shape rejected |
| LLM-based best-node selection with metric+analysis context, max-metric fallback | `journal.py:420-502` | rejected for v1 — ET investigations are ~5 calls; selection pressure is at the conjecture-docket layer (clade/QD), not within a trace |
| VLM figure critique (caption accuracy, selection-for-page-limit, duplicate detection) | `ai_scientist/perform_vlm_review.py:15-483` | rejected (parked) — ET papers have no figures yet; re-survey when the ARC §B1 figure agent is scheduled |
| Token tracker: per-model pricing, cached-vs-full prompt token split, per-interaction log | `ai_scientist/utils/token_tracker.py:1-223` | covered — `TokenAccountant` + `RunBudgetTracker` (drain, reserve, auth-class disable) are strictly stronger |
| Reviewer (v2) | `ai_scientist/perform_llm_review.py:13-233` | same as v1 → 32.4; v2 adds no new calibration |

## C. data-to-paper (`data-to-paper/`, Technion-Kishony-lab)

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

## D. paper-qa (`paper-qa/`, Future-House PaperQA2)

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

The four repos partition cleanly into the four thesis gaps: data-to-paper owns **construction-time
number provenance** (32.1/32.2), v2 + data-to-paper jointly own **investigation control flow**
(32.3), v1's calibration harness owns **absolute review** (32.4), PaperQA2 owns **literature
grounding** (32.5). No mechanism was found in two repos with conflicting shapes — the only overlap
(citation grounding: v1's API-only rule vs PaperQA2's key-fidelity) is complementary and both land
in 32.5/32.6.
