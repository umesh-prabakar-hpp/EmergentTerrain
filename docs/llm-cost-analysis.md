# LLM Cost Analysis

Analysis performed 2026-05-21. Source data: trace archive `research/llm-traces/archive/20260521-183906/` and log archive `logs/archive/et-app-*.log`.

> **Note (2026-05-23) — provider set has since changed.** Acting on this analysis, the **nvidia** and **cerebras** slots were removed entirely; the `openrouter`/`groq` named blocks were folded into `app.ai.providers.<name>`, and the `groq-fast` tier was renamed `fast`. The tables below are preserved as the run looked at analysis time (they reference the now-removed providers). Current provider slots: `groq`, `fast`, `openrouter`.

## Per-run LLM activity (last full simulation)

Run window: 2026-05-21 16:12–17:16 EDT (64 min 20 s of LLM-active trace time). 27 papers, 142 total calls.

### Calls by model

| Provider | Model | Calls | ~Input tok | ~Output tok |
|---|---|---:|---:|---:|
| groq | openai/gpt-oss-120b | 34 | 46,117 | 16,747 |
| nvidia | meta/llama-3.3-70b-instruct | 31 | 73,352 | 13,216 |
| openrouter | openai/gpt-oss-120b:free | 21 | 71,910 | 13,320 |
| nvidia | nvidia/nemotron-3-super-120b-a12b | 15 | 52,293 | 22,472 |
| nvidia | openai/gpt-oss-120b | 13 | 43,637 | 10,066 |
| nvidia | qwen/qwen3-coder-480b-a35b-instruct | 11 | 20,638 | 3,259 |
| groq | llama-3.3-70b-versatile | 11 | 17,107 | 5,249 |
| groq-fast | allam-2-7b | 3 | 1,016 | 547 |
| openrouter | nvidia/nemotron-3-super-120b-a12b:free | 1 | 3,269 | 79 |
| groq-fast | llama-3.1-8b-instant | 1 | 342 | 182 |
| groq-fast | llama-4-scout-17b-16e-instruct | 1 | 348 | 141 |

Token estimate = JSON-envelope chars / 4. Real billable tokens ~10–20% lower.

### Cost at public list prices

| Model | $/M in | $/M out | Cost |
|---|---:|---:|---:|
| groq · openai/gpt-oss-120b | 0.15 | 0.75 | $0.0195 |
| nvidia · llama-3.3-70b-instruct | 0.88 | 0.88 | $0.0762 |
| openrouter · gpt-oss-120b:free* | 0.15 | 0.60 | $0.0188 |
| nvidia · nemotron-3-super-120b-a12b | 0.60 | 1.80 | $0.0718 |
| nvidia · gpt-oss-120b | 0.15 | 0.75 | $0.0141 |
| nvidia · qwen3-coder-480b-a35b | 2.00 | 2.00 | $0.0478 |
| groq · llama-3.3-70b-versatile | 0.59 | 0.79 | $0.0142 |
| groq-fast · allam-2-7b | 0.05 | 0.08 | $0.0001 |
| openrouter · nemotron-3:free* | 0.30 | 0.90 | $0.0011 |
| groq-fast · llama-3.1-8b-instant | 0.05 | 0.08 | <$0.0001 |
| groq-fast · llama-4-scout | 0.11 | 0.34 | <$0.0001 |

*currently routed to a free OpenRouter tier; rates shown are what they would cost if paid.

**Total per run: $0.264** → **$0.246/hr, $0.0098/paper, $0.00186/call**.

NVIDIA NIM dev tier and OpenRouter `:free` returned $0 actual spend; only Groq paid models would have billed (~$0.03 for this run).

## Per-hour cost breakdown

| Model | Calls/hr | $/hr | Share |
|---|---:|---:|---:|
| nvidia · llama-3.3-70b-instruct | 29 | $0.071 | 29% |
| nvidia · nemotron-3-super-120b-a12b | 14 | $0.067 | 27% |
| nvidia · qwen3-coder-480b-a35b | 10 | $0.045 | 18% |
| groq · gpt-oss-120b | 32 | $0.018 | 7% |
| openrouter · gpt-oss-120b | 20 | $0.018 | 7% |
| groq · llama-3.3-70b-versatile | 10 | $0.013 | 5% |
| nvidia · gpt-oss-120b | 12 | $0.013 | 5% |
| groq-fast · others | 5 | <$0.001 | <1% |

**74% of cost concentrates on NVIDIA-hosted models.** Mitigation paths:
- Llama-3.3-70B and gpt-oss-120b are ~50% cheaper on Groq paid → moving those would cut total ≈$0.04/hr.
- qwen3-coder-480b ($2.00/M) is the highest per-token cost. 11 calls/run, but 18% of cost. Cheaper coder alternatives (qwen3-coder on Cerebras, DeepSeek-V3) would save another large slice.

## Actual usage pattern (5-day sample)

Window: 2026-05-16 07:24 → 2026-05-21 18:52 (5.48 days, 131.5 hr calendar).

- **21 distinct runs**, total active runtime **31.51 hr**
- **Utilization: 24.0%** of wall-clock
- Mean run length 90 min, but skewed: one 15.7 hr overnight stretch (2026-05-21 00:00 → 15:43) accounts for ~50% of total runtime; many runs <10 min are dev restarts (selection-tests then exit).

## Cost projections (paid-everywhere, current usage cadence)

At $0.246/hr × 5.75 hr/day average:

| Period | Active hours | Cost |
|---|---:|---:|
| 24 h | 5.75 | $1.41 |
| 1 week | 40 | $9.90 |
| 1 month (30 d) | 173 | $42.45 |
| 1 year | 2,100 | **~$515** |

### Bounding scenarios

| Scenario | Annual cost |
|---|---:|
| 24/7 (worst case) | $2,154 |
| Current cadence (24% utilization) | ~$515 |
| Without the May 21 overnight outlier (~12% utilization) | ~$260 |
| Current cadence after migrating NVIDIA workloads to Groq paid | ~$310 |

## Methodology

- Call counts and char totals: `jq` over JSONL traces in `research/llm-traces/archive/20260521-183906/` (paper-attributed + `_no-paper.jsonl`).
- Token estimate: request/response chars ÷ 4.
- Pricing: public list rates as of late 2025 / early 2026; NVIDIA NIM dev tier and OpenRouter `:free` treated as priced-equivalent for the "what if I were paying" projection.
- Runtime: first/last log-line timestamps per `logs/archive/et-app-*.log`, plus the live log.
- Utilization = sum(run durations) / (last_end − first_start).

Sample size is small (5 days, 21 runs). Re-run after a few more weeks for a tighter estimate.
