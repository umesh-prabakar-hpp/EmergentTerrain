# Paper creation + discovery/frontier flow (room b72c4e10, math substrate)

Two paths produce papers: the **conjecture-investigation path** (left) and the **discovery /
frontier path** (right). They share the narration → validation → publish tail, and are bridged by
the `discover_*` professor tools and frontier-framing of conjecture investigations.

```mermaid
flowchart TD
    %% ───────────────── Conjecture-investigation path ─────────────────
    subgraph Mining["ConjectureMinerService.mine() — every 5 min"]
        seed["seed / update conjectures<br/>lhs ≤ k·rhs + c over scalar vocabulary"]
        gates["gates: triviality (geometry-LHS drop), dimension,<br/>unit, FIM, stochastic-null, private-holdout, replication"]
        db[("conjecture DB")]
        seed --> gates --> db
    end

    subgraph Select["ResearchCycleService cycle"]
        topic["ConjectureTopicProvider.selectConjecture<br/>(paperShare roll, math substrate)"]
        queue["ConjectureQueue.nextN<br/>tier1 = counterexample-retired · tier2 = live<br/>(window-geometry excluded)"]
        dispatch["publishConjectureInvestigation<br/>topic = conjecture/⟨lhs⟩ + synthesized hypothesis"]
        topic --> queue --> dispatch
    end
    db --> queue

    %% ───────────────── Discovery / frontier path ─────────────────
    subgraph Frontier["Frontier services — Mertens / Prime / Divisor (@ 6h)"]
        sched["scheduledFrontierRun()<br/>run-until-converged (skip if converged)"]
        esc["FrontierEscalationService.escalate(bound)<br/>scale ×scaleFactor per round; classify rung vs bound;<br/>accumulate checkpoint corpus"]
        compute{"x ≤ NT_MAX_N ?"}
        sieve["sieve value source<br/>(trusted oracle)"]
        runtime["runtime discovery (x beyond sieve)"]
        term["terminal: counterexample /<br/>CANNOT_COMPUTE stall / round cap"]
        sched --> esc --> compute
        compute -- yes --> sieve --> esc
        compute -- "no" --> runtime --> esc
        esc --> term
    end

    subgraph Discover["Runtime self-verifying discovery (ET_DISCOVER_EXEC=1)"]
        gen["LlmCandidateGenerator<br/>propose method (Julia)"]
        sandbox["sidecar sandboxed exec"]
        moat["SidecarCandidateValidator — moat:<br/>reproduce N checkpoints ≤ NT_MAX_N"]
        vm[("VerifiedMethod store")]
        scale["NextScalePolicy (adaptive / LLM)<br/>+ StallPredictor"]
        gen --> sandbox --> moat -->|pass| vm
        moat -->|fail| gen
        scale -.next rung.-> gen
    end
    runtime --> gen
    vm --> runtime

    fk[("FrontierKnowledgeService<br/>(persisted frontier knowledge)")]
    term --> fk
    fr["FrontierFindingsRenderer → FrontierPaper"]
    term --> fr

    %% ───────────────── Bridges ─────────────────
    dispatch -. scalar maps to discovery domain<br/>(frontierDomainFor) .-> ffram["frontier-framed investigation<br/>synthesizeFrontierHypothesis/Findings"]
    ffram --> fp

    %% ───────────────── Shared narration → validation → publish ─────────────────
    subgraph Narrate["Narration pipeline (LLM, role-tagged)"]
        fp["fp — first-pass<br/>(metric + prose-constant grounding guard)"]
        audit["audit:4b — gap detection"]
        prof["prof — professor tool loop:<br/>verify_conjecture · run_sandboxed_code · run_symbolic ·<br/>search_literature · discover_{mertens,divisor,prime}_value ·<br/>recall_frontier_knowledge"]
        rev["rev — self-check + lenient JSON parse<br/>+ revision (trace-grounding)"]
        fp --> audit --> prof --> rev
    end
    dispatch --> fp
    fr --> fp
    prof -. discover_* tool .-> vm
    fk -. recall_frontier_knowledge .-> prof

    subgraph Validate["Publish-stage validation"]
        cite["CitationValidator<br/>(reconcile · sentence-quarantine · downgrade)"]
        panel["ReviewPanel (2 reviewers) + panel-revision"]
        checks["numerical-consistency · unsourced-claim<br/>binding-regime · direction-of-finding"]
        cite --> panel --> checks
    end
    rev --> cite

    checks --> status{status}
    status --> PUB["PUBLISHED"]
    status --> NR["NEEDS_REVISION"]
    status --> NUL["NULL_RESULT<br/>(coherent abstention)"]
    status --> REJ["REJECTED"]

    %% ───────────────── Shared LLM concurrency ─────────────────
    fp -. LLM call .-> pool["GenericOpenAICompatPool<br/>LlmConcurrencyLimiter:<br/>priority reservation + bounded wait"]
    prof -. LLM call .-> pool
    rev -. LLM call .-> pool
    panel -. LLM call .-> pool
    gen -. LLM call .-> pool
    esc -. per-rung scale call .-> pool
```

## Notes

- **Two discovery → paper triggers.** (1) Per-paper: the professor tool loop can call
  `discover_{mertens,divisor,prime}_value` (a moat-validated method computes the quantity past the
  sieve) and `recall_frontier_knowledge`. (2) Autonomous: the 6-hourly frontier services run the
  escalation loop and emit a `FrontierPaper`.
- **Frontier-framing bridge.** When a mined conjecture's scalar maps to a discovery domain
  (`frontierDomainFor`), `publishConjectureInvestigation` frames it as a frontier paper before
  narration — otherwise it stays in-range.
- **Gates.** Runtime discovery only executes when the sidecar is started with `ET_DISCOVER_EXEC=1`;
  otherwise frontier runs skip (zero tokens). Escalation stops on a counterexample, a
  `CANNOT_COMPUTE` stall, or the round cap.
- **Shared concurrency.** Every LLM call (narration, professor tools, candidate generation, per-rung
  scale decisions) acquires a permit from `GenericOpenAICompatPool` via the priority-aware,
  bounded-wait `LlmConcurrencyLimiter` (research roles reserved ahead of civic-simulation load).
