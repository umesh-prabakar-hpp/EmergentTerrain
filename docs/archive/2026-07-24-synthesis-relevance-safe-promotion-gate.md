# Synthesis relevance-safe promotion gate

Full-paper promotion previously accepted a legacy-grounding read as if it were
claim-relevant literature evidence. A readable source can still be unrelated to
the program's claim, so that allowed a full paper to position its result against
irrelevant work.

With `app.research.synthesis.full-paper.relevance-safe-promotion-gate.enabled`
on, a `PAPER-*` export requires all three links in one provenance chain:

1. Phase 35 retrieval returned a relevance-safe `HF...` key for the headline.
2. `read_reference` successfully read that exact key.
3. The generated Introduction and Related Work cites that same key as `[HF...]`.

Legacy grounding may still supply context to a synthesis, but it cannot satisfy
this full-paper promotion gate. When any link is absent, the synthesis remains
available as a revision artifact and no full-paper markdown export is written.
Set the flag to `false` for rollback.

