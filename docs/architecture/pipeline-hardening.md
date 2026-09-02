# Pipeline hardening — 2026-06-04 (room b72c4e10 review-driven fixes)

A session of `/paper-review`-driven fixes to the conjecture/discovery → paper pipeline. Each was
root-caused from the live `.runtime/logs/et-app.log` + the published papers, fixed with a unit test, and
verified live across successive app restarts. The pipeline went from **0 papers/run** (LLM-pool
starvation) and majority **Reject/NULL** artifacts to **~80% publishing** with coherent abstentions
on the rest.

See `paper-creation-flow.md` for the end-to-end diagram these changes touch.

## Changes

| Commit    | Area                     | Fix                                                                                 |
|-----------|--------------------------|-------------------------------------------------------------------------------------|
| `50fe725` | CitationValidator + LenientJsonParser | Number tokenizer split comma-grouped integers (`1,450,007` → `1`/`450`/`007`) into phantom prose numbers that forced false `NULL_RESULT`; and leading-`+` JSON numbers (`+0.52630`) failed strict Jackson → `rev:parse_failed→fp`. Grouped-integer regex + comma-stripping; 6th lenient-parser repair rule. |
| `4fbd8e0` | ConjectureTrivialityFilter | Drop conjectures whose **LHS** is a window-geometry scalar (`log_window_start`/`_size`, `sqrt_window_size`) — a free harness coordinate can't be the *subject* of a bound. Geometry-as-RHS still allowed (binding-regime case). |
| `34c1a62` | ConjectureMinerService.nextN | The triviality `reduce()` only sees *live* conjectures; degenerate geometry bounds get counterexample-retired first and served from tier-1. Filter the investigation queue (both tiers) through `dropReason` — the decisive gate for "becomes a paper". |
| `d240001` | UniversityService + ResearchNarrationService | NULL_RESULT downgrade rewrote findings/conclusion to "the hypothesis remains untested" — false against a *populated* trace, and self-contradictory (panels scored honesty ~0.03). Reword to abstain around the untraceable prose numbers ("no result is claimed; computation in Methods trace"), clear of every `CONCLUSION_NULL` phrase. Demotion decision unchanged. |
| `e56851b` | GenericOpenAICompatPool + LlmConcurrencyLimiter | The pool gated every LLM call on an unbounded `Semaphore.acquire()` over `parallelism=2` permits, shared with the civic simulation (~1–2 calls/sec). A sandbox intent-review blocked ~38 min → synchronous research cycle stalled → 0 papers. New limiter: **(A)** bounded wait (≤ requestTimeout, then throw) and **(B)** reserve ≥1 permit for role-tagged research calls so a non-priority flood can't lock the pipeline out. |
| `e60e7eb` | ResearchNarrationService (FIRST_PASS_METRIC_GUARD_RULE) | The narrator injected untraceable prose constants (textbook values like 1/ln 2, round-number "close to 5") that `NumericalConsistencyValidator` nulled — 4 of 7 papers one batch. The already-active first-pass guard was too narrow (quality-score/optimum only); broadened to bar any figure not in the tool output. |
| `82645e9` | docs                     | `paper-creation-flow.md` — combined conjecture-investigation + discovery/frontier flow diagram. |

## Verified outcomes (across restarts)

- **Throughput:** 0 papers/run → research-cycle cadence restored (~6–8 min, no 39-min stall), 0
  `acquiring slot` hangs.
- **NULL contradiction:** NULL_RESULTs now read the coherent wording; panel honesty on them rose from
  ~0.03 to 0.86–0.93; **0 Rejects** in the batches since.
- **Geometry-LHS:** 0 `conjecture/log_window_*` investigations (was the dominant degenerate shape).
- **Fabrication NULLs:** dropped 4/7 → 1/5; publish rate rose to ~80%.
- **Validator regressions:** comma-phantom / leading-`+` events = 0 on live papers.

## Known operational gotchas (surfaced this session)

- **`@Value` defaults drift from `application.yml`.** Several flags read `…:false` in the `@Value`
  annotation but are set `true` in YAML (`first-pass-metric-guard`, `revision-trace-grounding`,
  the discovery flags). Always check the YAML value, not the Java default, for live state.
- **Room reset on restart.** `research/<room>/` papers **and** `REVIEW_REPORT.md` are wiped on every
  restart; paper-ID counter keeps climbing (gaps = generated-but-not-persisted).
- **Pool starvation is shared-resource.** `legacy-ai parallelism=2` is shared between the
  civic simulation and the research pipeline; the limiter's reservation is what keeps papers flowing
  under civic load.

## Remaining frontier (not a defect)

Correctness is largely solved; the open lever is **generation quality**. The miner produces
correct-but-low-novelty affine threshold studies (`content ≤ k·scalar + c`, REFUTED small / HELD
large) wrapping known constants (divisor average-order ∼ log x, abundant−deficient ≈ −0.5, Ω−ω mean
≈ 0.77). Making the output *interesting* rather than *correct* is a generation-objective change, not a
validator/harness one.
