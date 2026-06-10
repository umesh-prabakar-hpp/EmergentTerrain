package com.emergentterrain.service;

import com.emergentterrain.research.FollowUpParams;
import com.emergentterrain.research.LiteratureReference;
import com.emergentterrain.research.LiteratureSearchService;
import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.PreRegistration;
import com.emergentterrain.research.PromptSanitizer;
import com.emergentterrain.research.PromptTemplateIds;
import com.emergentterrain.research.PromptTemplateRegistry;
import com.emergentterrain.research.ResultClassification;
import com.emergentterrain.research.UniversityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Paper narration pipeline: refinement → statistical framing → first-pass narration →
 * audit/critique → professor-directed experiments → student revision → publish.
 *
 * Carved out of LlmService in Phase 0 Task 0.2c so the narration concerns can evolve
 * independently of digest, consultation, and streaming. Owns its own narration and
 * refinement semaphores plus a virtual-thread executor; nothing here can starve the
 * digest or streaming paths.
 */
@Service
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true", matchIfMissing = false)
public class ResearchNarrationService {

    private static final Logger log = LoggerFactory.getLogger(ResearchNarrationService.class);

    private final LlmRouter router;
    private final MathToolSuite toolSuite;
    /**
     * Phase 30 fork 2b Phase 4 — optional runtime-discovery service, field-injected
     * (required=false) and wired into {@link #toolSuite} in {@link #wireDiscoveryTool()}
     * after autowiring. Null when the discovery beans aren't present; the
     * discover_mertens_value tool is then absent. Gated further by runtimeExecEnabled.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.research.discovery.CandidateDiscoveryService discoveryService;
    /**
     * Phase 30 fork 2b §8b — optional accumulated-knowledge service, field-injected
     * and wired into {@link #toolSuite} in {@link #wireDiscoveryTool()}. Null when the
     * knowledge beans aren't present; the recall_frontier_knowledge tool is then absent.
     * Gated further by frontierRecallEnabled.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.research.discovery.FrontierKnowledgeService frontierKnowledge;
    /** Lever 2 — read-only check_* tools for the revision self-check loop. */
    private final RevisionSelfCheckTools selfCheckTools;
    private final ObjectMapper objectMapper;
    /**
     * Phase 24 §24.4 — held for {@link #buildSymbolicRegressHint} so the
     * professor-system prompt can conditionally include an SR nudge.
     * Null when the bean wasn't wired (test contexts); the hint builder
     * treats null as "no hint" and renders the slot empty.
     */
    private final com.emergentterrain.llm.JuliaMathClient juliaMathClient;
    /**
     * Phase 26 §26.4 — optional constant-recognition orchestrator.
     * Stored for future use by the §26.5 conjecture-miner integration
     * and a planned {@code maybeRefineComputation} hook that emits
     * {@code cite-recognised:*} handles in prose. v1 stores the
     * reference but doesn't call recognise() — the narration side of
     * the integration deserves its own product review before changing
     * paper output.
     */
    @SuppressWarnings("unused")
    private final com.emergentterrain.research.recognise.ConstantRecognitionService
            constantRecognitionService;
    private final PromptTemplateRegistry promptRegistry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Wall-clock cap for a whole narration task (refinement → framing → first-pass
     * → audit → professor tools → panel). A stage that hangs on an unresponsive
     * provider (observed in the professor-tools tool loop) otherwise parks the
     * draft until the stuck-draft reaper reclaims it ~10 min later — and the
     * reaper only frees the backlog slot, it never interrupts the hung thread or
     * its HTTP connection. On timeout we cancel(true) the work, which interrupts
     * the in-flight (interruptible) HTTP call so the thread + connection unwind,
     * then release the slot. Set below the reaper TTL so it fires first; the
     * reaper remains the backstop for a truly un-interruptible hang. A healthy
     * pipeline runs ~3.5 min on the congested fast tier, so the default leaves
     * roughly 2× headroom.
     */
    @org.springframework.beans.factory.annotation.Value("${app.research.narrationTimeoutMs:420000}")
    private long narrationTimeoutMs;
    private final Semaphore narrationSlot;

    /**
     * 2026-06-06 review rec #3 — bounded resample on a professor empty trace.
     * ~2/3 of professor invocations decline tools (temperature-0.7 stochastic:
     * the same topics succeed on other draws), and the D3 suppression then
     * discards the 4–6 upstream calls already spent on the draft — roughly
     * half a session's call budget. A pure resample (identical prompts — NOT
     * coercion: the prompt's "you do not need to use tools" opt-out stays, and
     * a draft whose retries all decline still suppresses honestly) salvages
     * the sunk pipeline for one extra call per attempt. 0 disables (today's
     * behavior, bit-identical).
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.professor.empty-trace-retries:0}")
    private int professorEmptyTraceRetries;

    /**
     * Phase 32 Wave 2 (32.3) — off-target corrective re-run ("pivot", the
     * deterministic arm). When a conjecture draft's professor trace never
     * invoked {@code verify_conjecture} with the PRE-REGISTERED lhs
     * ({@code ScoreCalibration.hypothesisLhsUncovered} — P-b72c-210/215/217's
     * shape), re-run the professor once with an explicit corrective note
     * naming the preregistered pair, instead of letting the draft sail into
     * the publish gate's NEEDS_REVISION. The hypothesis is NOT amended — it
     * was correctly pre-registered; the trace went off-target — so this is
     * the corrective-re-run arm of 32.3. The amend-hypothesis arm (genuine
     * mis-specification discovered mid-investigation) is deferred until a
     * real fixture exists. Drain-aware: a pivot is discretionary spend and
     * never starts while the run budget is draining. false = today's
     * behavior, bit-identical.
     */
    @org.springframework.beans.factory.annotation.Value("${app.research.pivot.enabled:false}")
    private boolean pivotEnabled;
    @org.springframework.beans.factory.annotation.Value("${app.research.pivot.max-refinements:1}")
    private int pivotMaxRefinements;

    /**
     * Phase 32 Wave 1 enforce (32.2) — mirrored mode flag so the REVISION
     * stage can pre-check the working material against the manifest and name
     * the unprovenanced values in the revision prompt, instead of letting the
     * publish gate bounce the finished paper. Catch-at-generation; the gate
     * stays as the backstop.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.manifest.validator-mode:off}")
    private String manifestValidatorMode;

    /**
     * Dynamic revision rule (enforce mode only): run the manifest validator
     * over the pre-revision material (professor synthesis + first-pass prose)
     * and, if values miss, append a rule NAMING them with the three honest
     * outs (cite / show arithmetic / remove). Empty-miss or any failure
     * returns the prompt unchanged — this is best-effort steering, never a
     * narration blocker.
     */
    private String appendManifestMissRule(String prompt, PaperDraft draft,
            NarrationResult firstPass, String professorSynthesis,
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace) {
        if (!"enforce".equals(manifestValidatorMode)) return prompt;
        try {
            com.emergentterrain.research.manifest.ValueManifest manifest =
                    com.emergentterrain.research.manifest.ValueManifest.capture(
                            firstPass != null ? firstPass.hypothesis() : null, trace);
            java.util.Map<Integer, String> blocks = new java.util.TreeMap<>();
            if (professorSynthesis != null) blocks.put(1, professorSynthesis);
            if (firstPass != null && firstPass.findings() != null) {
                blocks.put(2, firstPass.findings());
            }
            com.emergentterrain.research.manifest.ManifestTraceabilityValidator.Stats stats =
                    com.emergentterrain.research.manifest.ManifestTraceabilityValidator
                            .validate(manifest, blocks,
                                    firstPass != null ? firstPass.conclusion() : null);
            if (stats.misses().isEmpty()) return prompt;
            StringBuilder tokens = new StringBuilder();
            for (var miss : stats.misses()) {
                if (tokens.length() > 0) tokens.append(", ");
                tokens.append('\'').append(miss.token()).append('\'');
            }
            // Logged so the publish-side enforce line can be diffed against
            // this pre-revision count — the catch-at-generation efficacy
            // metric (named misses the reviser actually fixed).
            log.info("manifest-revision-rule: paperId={} preRevisionMisses={} tokens=[{}]",
                    draft.paperId(), stats.misses().size(), tokens);
            return prompt + "\n\nVALUE PROVENANCE (the publish gate routes the paper to "
                    + "NEEDS_REVISION on violation): these numbers in the working material "
                    + "trace to NO tool result, tool argument, or one-step derivation: "
                    + tokens + ". For each, either cite its exact source from the trace, "
                    + "show its arithmetic from cited values in the same sentence, or "
                    + "REMOVE the claim.";
        } catch (Exception e) {
            return prompt;
        }
    }

    /** Phase 31 §31.3 — optional recall (read-only; narration never depends on it). */
    private volatile com.emergentterrain.research.memory.MemoryRecallService memoryRecallService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMemoryRecallService(
            com.emergentterrain.research.memory.MemoryRecallService memoryRecallService) {
        this.memoryRecallService = memoryRecallService;
    }

    /** Drain gate for discretionary salvage spend (mirrors LlmService's optional setter). */
    private volatile com.emergentterrain.llm.RunBudgetTracker runBudgetTracker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRunBudgetTracker(com.emergentterrain.llm.RunBudgetTracker tracker) {
        this.runBudgetTracker = tracker;
    }

    private boolean pivotSpendAllowed() {
        com.emergentterrain.llm.RunBudgetTracker t = runBudgetTracker;
        return t == null || t.allowDiscretionarySpend();
    }

    /**
     * The corrective instruction appended to the professor user prompt on an
     * off-target re-run. Names the preregistered lhs and mandates at least one
     * on-target verify call — without forbidding additional exploration (the
     * P-b72c-222 regime-probe behavior stays welcome).
     */
    static String buildPivotNote(String preregisteredLhs) {
        return "\n\nPIVOT NOTE — your previous investigation never invoked "
                + "verify_conjecture with the PRE-REGISTERED lhs '" + preregisteredLhs
                + "'. The hypothesis under test is the pre-registered inequality: at "
                + "least one verify_conjecture call MUST use lhs=\"" + preregisteredLhs
                + "\" (additional calls on other scalars or regimes remain welcome). "
                + "Re-run the investigation accordingly.";
    }

    /**
     * rec #8 — when on, a narration that hits the wall-clock timeout publishes its
     * already-produced first-pass (routed to NEEDS_REVISION) instead of abandoning
     * the paper. The a3289005 batch lost papers (981, 986, 988, 990–993) this way:
     * the timeout cancelled the thread after a usable first pass existed, so the
     * content was discarded and the draft sat for the reaper. Default off, matching
     * the rollout discipline of the other behaviour-changing gates — flip after a
     * refresh confirms the salvaged first-pass papers are worth persisting.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.narration-firstpass-checkpoint.enabled:false}")
    private boolean narrationFirstPassCheckpointEnabled;

    /**
     * rec #8 — bounded LRU of the latest usable first-pass per in-flight paper, so
     * {@link #publishFirstPassCheckpoint} can salvage it on timeout. Bounded well
     * above the narration concurrency cap; eldest-eviction is the only cleanup for
     * the rare non-timeout exception path (a finally-remove would race the
     * cross-thread timeout publish, since the interrupt flag is unreliable once an
     * interruptible call has thrown). The synchronized map makes {@code remove}
     * atomic.
     */
    private static final int FIRSTPASS_CHECKPOINT_CAP = 512;
    private final java.util.Map<String, NarrationResult> firstPassCheckpoints =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, NarrationResult>(64, 0.75f, false) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, NarrationResult> eldest) {
                            return size() > FIRSTPASS_CHECKPOINT_CAP;
                        }
                    });

    /**
     * rec #2c (2026-05-28) — at-most-once publish claim shared by the normal
     * narration publish ({@link #runNarration}) and the rec #8 timeout-checkpoint
     * publish ({@link #publishFirstPassCheckpoint}). The first caller to claim a
     * paperId publishes; the loser skips. This closes the double-publish the
     * review traced (P-a328-993: the timeout hook published a checkpoint at the
     * cap, then the still-running worker published the full version 17 min later
     * — because the normal path removed the checkpoint but published
     * unconditionally, never checking that it won the race).
     *
     * <p>Crucially the timeout hook only takes the claim when it actually has a
     * checkpoint to publish (see {@link #publishFirstPassCheckpoint}), so a
     * timeout that fires with no checkpoint stored does NOT block a worker that
     * later completes — that paper is still published normally. Only contended
     * when the checkpoint feature is on (the timeout hook is wired only then).
     * Bounded-LRU so it can't grow without bound; eldest-eviction is the only
     * cleanup (an in-flight claim is never evicted at this cap).
     */
    private static final int PUBLISH_CLAIM_CAP = 512;
    private final java.util.Set<String> publishedPaperIds =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(
                            new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, false) {
                                @Override
                                protected boolean removeEldestEntry(
                                        java.util.Map.Entry<String, Boolean> eldest) {
                                    return size() > PUBLISH_CLAIM_CAP;
                                }
                            }));

    /**
     * rec #2c — atomically claim the right to publish {@code paperId}. Returns
     * true exactly once per paperId; subsequent callers get false. {@link
     * java.util.Set#add} on the synchronized backing map is the atomic
     * compare-and-set.
     */
    private boolean claimPublish(String paperId) {
        return publishedPaperIds.add(paperId);
    }
    private final Semaphore refinementSlot = new Semaphore(2);
    /** Phase 6 D1.3 — optional. Null when the literature service bean isn't on the context. */
    private final LiteratureSearchService literatureSearch;
    private final boolean literatureSearchEnabled;
    /** Phase 6 D2 — when on, the narrator emits two extra JSON keys via the v2 template. */
    private final boolean discussionSectionEnabled;

    /** Phase 7 E3 — when on, the router falls back to Ollama critique+revise (no tools) when both
     *  the primary tools path and the primary JSON path are unavailable. */
    private final boolean ollamaFallbackEnabled;

    /** Phase 7 F2 — when on, the narrator emits one H3 prose block per computation
     *  in the findings section. Off → behaviour bit-identical to F1-baseline. */
    private final boolean structuredFindingsEnabled;

    /** Phase 7 F2 — number of independent computation prose blocks the narrator emits.
     *  Force-clamped to 1 when structuredFindingsEnabled is false. */
    private final int maxComputations;

    /**
     * Phase 12 D4 — when on, a panel that returns {@code got ∈ {2, 3}} of a
     * configured 4 stamps a {@code panel:n=N:degraded:demoted_to_needs_revision}
     * marker. {@link com.emergentterrain.research.UniversityService#publishPaper}
     * reads the marker and routes status PUBLISHED → NEEDS_REVISION. The n=1
     * suppression branch above this is unchanged (single-reviewer agreement is
     * non-informative, suppress outright).
     *
     * <p>Default off; the operator flips it on after one REVIEW_REPORT refresh
     * exercises the D-series suppressions so the demotion signal lands in a
     * known baseline.
     */
    private final boolean panelDegradationDemoteEnabled;

    /**
     * Phase 29 §29.1.1 — best-of-N narration. When {@code bestOfNEnabled} and
     * {@code bestOfNCandidates > 1}, {@link #runNarration} produces N
     * panel-scored candidate narrations for the same draft and publishes the
     * highest-graded one (DGM/STOP generate-many-keep-best). Default off /
     * candidates=1 ⇒ a single candidate is produced and published, identical
     * to the pre-29 behaviour. The loser candidates are discarded; only the
     * winner reaches {@code narrationPublisher}.
     */
    private final boolean bestOfNEnabled;
    private final int bestOfNCandidates;

    /**
     * Phase 29 §29.1.2 — eval cascade. When {@code evalCascadeEnabled} and
     * best-of-N is producing {@code >1} candidates, the candidates' cheap
     * first-passes are screened and only the top-{@code evalCascadeScreenTopM}
     * are finished through the expensive critique/revise + panel half. This
     * bounds the per-paper panel cost to M instead of N. Default off ⇒ every
     * candidate is finished (the §29.1.1 behaviour).
     */
    private final boolean evalCascadeEnabled;
    private final int evalCascadeScreenTopM;

    /**
     * Phase 11 C4 — citations-as-schema validator. Runs after the student-revision
     * stage produces a NarrationResult with {@code numericClaims} and a tool-call
     * trace with stable {@code call_N} IDs. Operates in three modes via
     * {@code app.research.citations-as-schema.mode}:
     * <ul>
     *   <li>{@code warn} (default) — stamp call_path, never suppress</li>
     *   <li>{@code downgrade} — route papers with mismatched/out-of-tolerance claims
     *       to NULL_RESULT (C5a, future)</li>
     *   <li>{@code suppress} — drop those papers entirely (C5b, future)</li>
     * </ul>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.research.CitationValidator citationValidator;

    /** Phase 11 C4 — see {@link #citationValidator}. */
    @org.springframework.beans.factory.annotation.Value("${app.research.citations-as-schema.mode:warn}")
    private String citationsAsSchemaMode;

    /**
     * Paper-review 2026-05-22 — threshold for the result-bearing
     * exemption to the downgrade path. See
     * {@link #MODERATE_MISMATCH_THRESHOLD_DEFAULT}. Set to 0 to disable
     * the exemption; raise to widen the rescue band.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.citations.moderate-mismatch.threshold:5}")
    private int moderateMismatchThreshold = MODERATE_MISMATCH_THRESHOLD_DEFAULT;

    /**
     * Paper-review 2026-05-26 recommendation #1 — reconcile traceable numbers
     * before downgrading to NULL_RESULT. When true, a findings/conclusion
     * number (or a mis-attributed claim) whose value is present somewhere in
     * the tool-call trace is treated as traceable rather than as a citation
     * failure, so a correct-but-mis-cited computation no longer forces the
     * "hypothesis remains untested" rewrite that contradicts the trace
     * (P-a328-952/957/960/968/969). Default on; set false to restore the
     * strict pre-2026-05-26 marker-only behaviour.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.citations.reconcile-before-null:true}")
    private boolean citationsReconcileBeforeNull = true;

    /**
     * Room b72c4e10 fix (Rec 5) — sentence-level citation quarantine. When a
     * citation downgrade is driven entirely by prose-side failures (uncited /
     * unmatched-claim numbers the narrator injected, e.g. P-b72c-12's 0.143 /
     * 0.025 / 0.034), null-ing the whole paper discards an otherwise-correct
     * computation. With this on, the offending <i>sentences</i> are excised and
     * the paper publishes with the surviving prose — provided substantive
     * findings remain. Out-of-tolerance (Check-3) failures still null the paper:
     * those are claim-side fabrications, not stray prose. Default OFF (it edits
     * published prose); flip after a refresh confirms the excision quality.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.citations.sentence-quarantine.enabled:false}")
    private boolean citationsSentenceQuarantineEnabled;

    /**
     * Phase 16 P6a — cross-citation verdict propagation. Controls how the
     * narration prompt assembles the {@code priorPapers} context block for
     * papers in the room with the same domain + topic as the draft.
     *
     * <p>Default (false): disclaimer mode — REJECTED priors appear in the
     * context with a {@code [REJECTED — do not propagate]} tag prepended;
     * DISPUTED priors with {@code [DISPUTED — under replication review]}.
     * The narrator LLM still sees the paper and can choose to cite it, but
     * with appropriate care.
     *
     * <p>Set true to enable suppression mode — REJECTED priors are dropped
     * from the context entirely; the narrator never sees them. DISPUTED
     * priors still appear with the disclaimer tag.
     *
     * <p>Targets the P-a328-539 → P-a328-534 failure where P-539 cited P-534
     * (REJECTED for fabricated convergence) in Related Work and re-committed
     * the same fabrication.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.corpus-citations.suppress-rejected:false}")
    private boolean corpusCitationsSuppressRejected;

    /**
     * Phase 17 — extension of D3's zero-successes suppression to cover the
     * gate-killed-professor failure mode surfaced in {@code REVIEW_REPORT.md}
     * post-hang refresh #1 (papers P-559/568/570/573/575/580). D3's
     * precondition is {@code prof:llama:zero_successes}, which requires the
     * professor stage to have *run* and produced zero successes — so it
     * misses papers where {@link SystemResourcesGate} killed the professor
     * stage before any tool call was issued. In those cases the first-pass
     * narrator's conclusion is preserved verbatim and, for ~60% of papers in
     * the named refresh, contains a fabricated quantitative claim
     * (e.g. "maximum buoyant force of 1480 kN at a volume of 25.8 m³",
     * "worst-case deviation 3.00% at T=4121 K") that no computation backs.
     *
     * <p>When this flag is on, a paper that hits {@code gate:resources:professor}
     * AND carries a quantitative first-pass conclusion (per
     * {@link #conclusionHasQuantitativeClaim(String)}) is suppressed
     * pre-publish, the same way D3 suppresses {@code zero_successes} papers.
     * Off by default so the operator can soak the gate against one refresh
     * before flipping in production.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.gate-resources-quantitative-suppress.enabled:false}")
    private boolean gateResourcesQuantitativeSuppressEnabled;

    /**
     * Phase 17 P2 — panel-recommendations-as-feedback. When the
     * multi-reviewer panel produces actionable feedback
     * ({@link com.emergentterrain.research.PanelOutcome#consensusContradictions}
     * non-empty OR {@code recommendations.size() >= 2}), the student model
     * runs a focused second-revision pass that asks it to address the
     * panel's specific concerns: rewrite prose to match the trace, drop
     * citations the panel flagged as broken, etc.
     *
     * <p>Targets the P-571-class failure where the panel said exactly what
     * to fix ("Remove references to call_2/call_3 — only call_1 exists",
     * "Peak η=0.788 at r=0.40, not 0.793 at 0.414") but no code path
     * picked up the feedback. Today it lives only in the rendered
     * markdown — humans read it; the pipeline doesn't.
     *
     * <p>Guardrails: revision prompt forbids new numeric values not in
     * the trace, forbids inventing new tool calls, preserves the
     * hypothesis. Runs only when SystemResourcesGate is open and the
     * panel's feedback is non-vague. Default off; flip after one corpus
     * refresh confirms revision text quality is on-par with the
     * pre-revision text.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.panel-revision.enabled:false}")
    private boolean panelRevisionEnabled;

    /**
     * Lever 1 — provider-enforced structured output. When on, every
     * NarrationResult-shaped LLM call (first-pass narration + the three
     * revision sites) is wrapped with
     * {@link com.emergentterrain.llm.LlmCallContext#withJsonSchema} so a
     * json_schema-capable provider returns output already matching
     * {@link #NARRATION_RESPONSE_SCHEMA}. Closes the {@code rev:parse_failed→fp}
     * fallback (≈39% of papers — see {@link com.emergentterrain.research.LenientJsonParser}).
     * Default off; per-provider {@code supports-json-schema} also gates emission,
     * and {@code LenientJsonParser} stays the post-parse safety net for flag-off
     * / non-supporting providers.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.narration.structured-output.enabled:false}")
    private boolean narrationStructuredOutputEnabled;

    /**
     * Lever 2 — citation grounding. When on (and a literature corpus is wired),
     * the professor stage advertises the {@code search_literature} tool and is
     * instructed to cite prior work ONLY via that tool, so citations are
     * retrieved (and land in the tool-call trace) rather than invented. Default
     * off. See [[project_tool_calling_levers]].
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.citation-tool.enabled:false}")
    private boolean citationToolEnabled;

    /**
     * Lever 2 — revision self-check tools. When on, the prof→student revision
     * runs as a tool loop ({@link RevisionSelfCheckTools}) so the reviser checks
     * its draft against the direction-of-finding / uncertainty-disclosure /
     * single-point-sweep validators and self-corrects BEFORE publish, instead of
     * being demoted to NEEDS_REVISION after the fact. Default off; when off the
     * revision uses the unchanged streamPrimary path.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.revision-self-check.enabled:false}")
    private boolean revisionSelfCheckEnabled;

    /**
     * rec #4 follow-up (2026-05-29) — revision-prompt trace grounding. ROOT
     * CAUSE of the phantom {@code [call_N]} citations (P-a328-993 cited
     * call_18/19; 994 cited the FAILED call_1/5): the revision templates inject
     * the professor's PROSE synthesis (which carries no {@code [call_N]}
     * prefixes), yet {@link #CITATION_RULE_ADDENDUM} tells the reviser to "reuse
     * those exact markers" — so the student-revision stage auto-numbered
     * citations blind and overran the real trace. When ON, both the
     * student-revision and panel-revision prompts get the actual
     * {@code [call_N]}-tagged trace ({@link #renderCitableTraceBlock}) appended,
     * making the addendum's premise true: the reviser cites real IDs and SEES
     * the ceiling. Default off (A/B against the prompt-instruction-only state
     * shipped in 59c8efb). See [[project_hallucination_stage_rootcause]].
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.revision-trace-grounding.enabled:false}")
    private boolean revisionTraceGroundingEnabled;

    /**
     * rec #4 follow-up (2026-05-29) — first-pass metric-fabrication guard. The
     * second hallucination class (distinct from the phantom-{@code [call_N]}
     * citations the trace-grounding fix targets): the first-pass narrator runs
     * with NO tools, yet asserts specific measured-looking numbers in prose —
     * P-a328-995 stated a "quality score of 0.89" present in no tool call and
     * locked in d=2.41µm as the optimum before the professor sweep found the
     * equal-or-better d=2.62µm. The harness itself seeds the phrase "quality
     * score" into follow-up prompts and emits {@code predictedScore}/
     * {@code confidenceScore}, so the narrator conflates those meta-assessments
     * with computed results. When ON, {@link #FIRST_PASS_METRIC_GUARD_RULE} is
     * appended to the first-pass prompt forbidding that conflation. Default off.
     * See [[project_hallucination_stage_rootcause]].
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.first-pass-metric-guard.enabled:false}")
    private boolean firstPassMetricGuardEnabled;

    /**
     * Novelty-lens follow-up (2026-05-29) — revision-stage novelty preservation.
     * The critique→revise loop is a one-way SUBTRACTIVE ratchet: every standing
     * instruction ("qualify over-claims", "do not introduce new claims", "keep
     * correct content unchanged", "address the weaknesses") removes or hedges,
     * and the critique it consumes surfaces "overclaiming / missing
     * qualifications". But this room's stated goal (phase-30) is genuinely novel
     * structure — the reproducible RESIDUAL after subtracting a known reference
     * relation, the exception to a regularity — which a naive honesty pass reads
     * as the boldest, most "over-claimed" sentence and sands into "broadly
     * consistent with expectations". Revision can thus destroy the contribution
     * while passing every honesty gate. When ON, {@link #NOVELTY_PRESERVATION_RULE}
     * is appended to the revision prompt: it reframes "qualify" as calibrating the
     * claim's STRENGTH to its honest evidence label (CONJECTURE /
     * UNEXPLAINED_OBSERVATION) rather than deleting a supported surprise. All the
     * existing honesty guards stay verbatim — this only stops the ratchet from
     * over-cutting. Default off; flip after an A/B against the subtractive-only
     * state (cf. [[feedback_flag_gated_dormant]]).
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.revision-novelty-preservation.enabled:false}")
    private boolean revisionNoveltyPreservationEnabled;

    /**
     * REVIEW_REPORT b72c4e10 §1 rec #4 — executed-scope grounding for the
     * revision stage. P-b72c-19 stated results over ranges [100,500) and
     * [700,1100) that NO tool call executed (only [300,700) was scanned), and
     * reported "+0.52" where call_5 computed z=-0.523 (sign flipped, magnitude
     * truncated). The numerical-consistency gate caught it post-hoc and demoted
     * the paper to NULL_RESULT, but the reviser should not emit unexecuted
     * ranges or sign-flipped values in the first place. When ON,
     * {@link #EXECUTED_SCOPE_RULE} is appended to the revision prompt, confining
     * results to ranges a call actually swept and pinning each value to the
     * sign+magnitude the tool returned. Adds no output field (schema-compatible).
     * Default off (cf. [[feedback_flag_gated_dormant]]).
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.executed-scope-grounding.enabled:false}")
    private boolean executedScopeGroundingEnabled;

    /**
     * REVIEW_REPORT b72c4e10 §1 rec #5 — definition grounding for the revision
     * stage. P-b72c-18's Mechanism defined the central quantity as
     * "mertens_per_sqrt_W = M/sqrt(W) where W denotes window_start" while the
     * tool's own sandbox normalized by sqrt(window_size). The definition
     * contradicted the computation even though the refutation conclusion was
     * unaffected. When ON, {@link #DEFINITION_GROUNDING_RULE} is appended to the
     * revision prompt, requiring the Mechanism/Methods prose to define each
     * computed quantity exactly as the tools computed it (same normalizer,
     * window, variables). Adds no output field. Default off
     * (cf. [[feedback_flag_gated_dormant]]).
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.definition-grounding.enabled:false}")
    private boolean definitionGroundingEnabled;

    /**
     * Phase 17 P8a — diagnostic capture for {@code rev:parse_failed→fp}.
     * When on, the FALLBACK log line is followed by a separate WARN log
     * line carrying the first {@link #revParseFailedByteCaptureChars}
     * characters of the raw revision-stream output. The goal is to
     * distinguish two hypotheses for why `rev:parse_failed→fp` keeps
     * firing despite Phase 17 JSON-output mode:
     * <ol>
     *   <li>Provider-side: Groq's {@code llama-3.3-70b-versatile} is not
     *       honoring {@code response_format: json_object} when the
     *       request includes a v3 structured-findings template — the
     *       raw bytes would contain markdown code-fences, prose
     *       preamble, or trailing comments.</li>
     *   <li>Parser-side: provider returns valid JSON but with a quirk
     *       {@link com.emergentterrain.research.LenientJsonParser}
     *       doesn't repair (e.g. double-encoded strings, trailing
     *       commas in arrays).</li>
     * </ol>
     *
     * <p>One refresh's worth of captured bytes (~10-30 samples) should
     * suffice to name the dominant failure shape; the fix then ships
     * in either the parser or the request-shape. Default off so the
     * diagnostic doesn't bloat logs in normal operation; flip during
     * a deliberate observation window then flip back.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.rev-parse-failed-byte-capture.enabled:false}")
    private boolean revParseFailedByteCaptureEnabled;

    @org.springframework.beans.factory.annotation.Value(
            "${app.research.rev-parse-failed-byte-capture.chars:500}")
    private int revParseFailedByteCaptureChars;

    /**
     * Phase 17 panel-grade-floor demote — parallel to Phase 12 D4 (panel-
     * degradation-demote which fires on reviewer-count) but fires on
     * grade-floor. When the panel's median across honesty + completeness +
     * rigor (excluding novelty, which is often legitimately 0 on textbook
     * topics) is below {@link #panelGradeFloorThreshold} AND there's at
     * least one consensus contradiction (≥2 reviewers agreed on a
     * prose-vs-trace conflict), the paper is demoted PUBLISHED →
     * NEEDS_REVISION.
     *
     * <p>Targets the P-a328-593 failure surfaced in post-Phase-17 refresh #1:
     * panel honesty=0.10, completeness=0.00, rigor=0.10, novelty=0.00,
     * 2 contradictions agreed by ≥2 reviewers (1147× unit error in
     * VdW→stress conversion), yet status PUBLISHED. The existing D4
     * cannot catch it because the panel was full (n=4 of 4) — D4 fires
     * only on n ∈ {2, 3} of configured 4.
     *
     * <p>Default off; flip after one refresh confirms the gate has zero
     * false positives on the corpus.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.panel-grade-floor.enabled:false}")
    private boolean panelGradeFloorEnabled;

    /**
     * Phase 17 panel-grade-floor demote — threshold for the median(honesty,
     * completeness, rigor) average. A median of 0.30 corresponds to all
     * three axes at "weak but present" — below that, the reviewers are
     * essentially telling us the paper is unsupported by its own trace.
     * The contradiction requirement (≥1 consensus contradiction) is the
     * hard quality signal; the grade floor is a redundancy check that
     * keeps the gate from firing on quiet-disagreement papers.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.panel-grade-floor.threshold:0.30}")
    private double panelGradeFloorThreshold;

    /**
     * Phase 12 D4 relaxation (research/a3289005 review rec #7) — when enabled, a
     * degraded panel (n ∈ {2, 3} of configured 4) keeps PUBLISHED instead of being
     * demoted purely on reviewer count, provided it converged strongly
     * (agreement ≥ {@link #panelDegradationAgreementFloor}) AND clears
     * {@link #panelGradeFloorThreshold} on its grades. The batch showed nearly
     * every paper demoted by D4 because the panel could not seat 4 reviewers
     * (LlmRouter slot contention) — an infrastructure artifact, not a quality
     * signal — so demoting discarded correct results.
     *
     * <p>Default off, matching the rollout discipline of {@link #panelGradeFloorEnabled}
     * and the D4 gate itself: ship the code dormant and flip after one refresh
     * confirms the kept-PUBLISHED papers are genuinely sound. Low-agreement or
     * low-grade degraded panels still demote, and the orthogonal grade-floor gate
     * is unaffected, so enabling this cannot publish a paper the panel judged weak.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.panel-degradation-demote.agreement-relax.enabled:false}")
    private boolean panelDegradationAgreementRelaxEnabled;

    /**
     * Agreement floor for the rec #7 relaxation above. A degraded panel must
     * converge at least this tightly to be trusted without a full reviewer count.
     * Only consulted when {@link #panelDegradationAgreementRelaxEnabled} is true.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.research.panel-degradation-demote.agreement-floor:0.80}")
    private double panelDegradationAgreementFloor;

    /**
     * SimulationGate consult before each Groq-spending stage (first-pass, professor
     * tools, student revision, refinement-loop iteration). Optional: null in tests
     * that don't wire it. When closed, the affected stage short-circuits to a
     * cheaper fallback (first-pass narration or null-suppress) rather than throwing.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.llm.SimulationGate simulationGate;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.llm.SystemResourcesGate systemResourcesGate;

    /**
     * Phase 17 — when wired, papers whose professor or revision stage is
     * killed by {@link com.emergentterrain.llm.SystemResourcesGate} can be
     * deferred for retry instead of publishing as NEEDS_REVISION on the
     * first attempt. See {@link com.emergentterrain.research.GateRetryQueue}
     * for the lifecycle (enqueue → tick when gate recovers → markCompleted
     * on success / give-up after max-attempts).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.research.GateRetryQueue gateRetryQueue;

    /**
     * Phase 7 F7.2 — multi-reviewer panel. Runs after the professor stage's
     * synthesis and stamps a {@link com.emergentterrain.research.PanelOutcome}
     * onto the returned {@link NarrationResult}. Optional dependency so tests
     * that don't wire it get a no-op pipeline.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ReviewPanelService reviewPanelService;

    /**
     * Phase 20.4 step 3 — narration-envelope metrics emission. Field-injected
     * (required=false) so unit tests that don't wire the activity-metrics
     * bean construct cleanly; the emission helper no-ops when null. Rolled
     * up by {@link ActivityReporter}'s 30-second activity rows so a future
     * Phase-18-style incident is diagnosable from the activity log alone
     * (e.g. {@code narration.status.ALL_MODELS_BLOCKED} delta jumps).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ActivityMetrics activityMetrics;

    /**
     * Lever F (2026-06-10) — accumulates every best-of-N candidate's professor trace by paperId so the
     * discovery→pool write-back in {@code publishPaper} sees verified bounds from LOSING candidates, not
     * only the winner's {@code toolCallTrace()}. Field-injected (optional) so test call sites of the
     * constructor are untouched. See {@link com.emergentterrain.research.conjecture.InvestigationTraceAccumulator}.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.emergentterrain.research.conjecture.InvestigationTraceAccumulator investigationTraceAccumulator;

    /** Set during wireUniversityCallback — calls university.publishPaper on completion. */
    private volatile java.util.function.BiConsumer<PaperDraft, NarrationResult> narrationPublisher;

    /**
     * Set during wireUniversityCallback — releases a draft's backlog slot when its
     * narration task settles. Invoked from {@link #refineAndNarrate}'s {@code finally}
     * so a throw or hang before {@code publishPaper} can't leak the slot and
     * dead-lock the research backlog. Idempotent with publishPaper's own release.
     */
    private volatile java.util.function.Consumer<String> draftSettledCallback;

    public ResearchNarrationService(
            LlmRouter router,
            ObjectMapper objectMapper,
            PromptTemplateRegistry promptRegistry,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            LiteratureSearchService literatureSearch,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.emergentterrain.gpu.GpuEngineAdapter gpuEngineAdapter,
            // Phase 22 §22.2 — optional Julia math sidecar client.
            // When the bean is wired (always is, since @Service) AND
            // its `app.julia.enabled` is true AND /healthz responded
            // at startup, MathToolSuite delegates run_symbolic and
            // evaluate_symbolic calls to the sidecar before falling
            // back to Symja / SandboxEngine.
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.emergentterrain.llm.JuliaMathClient juliaMathClient,
            // Phase 25 §25.3 — optional SymPy identity prover. Same
            // sidecar as juliaMathClient (PyCall.jl bridge); the
            // separate bean keeps failure surfaces decoupled. When
            // null (or app.julia.sympy.enabled=false), MathToolSuite
            // omits prove_identity from the tool schema.
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.emergentterrain.llm.SympyIdentityClient sympyIdentityClient,
            // Phase 26 §26.4 — optional constant-recognition orchestrator
            // (OEIS + PSLQ + LLL). When null OR
            // app.julia.recognise.enabled is false, no recognise() calls
            // fire; narration prints naked decimals. The narration-side
            // hook that actually invokes recognise() lands in a later
            // slice (§26.5 conjecture-miner integration or a follow-on)
            // because emitting cite-recognised:* handles changes paper
            // output and deserves its own product review.
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.emergentterrain.research.recognise.ConstantRecognitionService
                    constantRecognitionService,
            // 2026-05-29 — optional conjecture verifier; wired into MathToolSuite so the
            // verify_conjecture tool is available for CONJECTURE_INVESTIGATION adversarial checks.
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.emergentterrain.research.conjecture.ConjectureVerifier conjectureVerifier,
            @org.springframework.beans.factory.annotation.Value("${app.research.literature-search.enabled:false}")
            boolean literatureSearchEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.research.discussion-section.enabled:false}")
            boolean discussionSectionEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.research.ollama-fallback.enabled:true}")
            boolean ollamaFallbackEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.research.structured-findings.enabled:false}")
            boolean structuredFindingsEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.research.max-computations:2}")
            int maxComputationsCfg,
            @org.springframework.beans.factory.annotation.Value("${app.research.panel-degradation-demote.enabled:false}")
            boolean panelDegradationDemoteEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.research.narration-concurrency-cap:0}")
            int narrationConcurrencyCap,
            // Phase 29 §29.1.1 — best-of-N narration. When enabled with
            // candidates>1, runNarration produces N panel-scored candidate
            // narrations for the same draft and publishes the highest-graded
            // one (DGM/STOP generate-many-keep-best + HGM tie-breaker). Default
            // OFF / candidates=1 ⇒ exactly one candidate, bit-identical to the
            // pre-29 single-trajectory behaviour.
            @org.springframework.beans.factory.annotation.Value("${app.research.best-of-n.enabled:false}")
            boolean bestOfNEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.research.best-of-n.candidates:1}")
            int bestOfNCandidates,
            // Phase 29 §29.1.2 — eval cascade. When enabled alongside best-of-N,
            // the N candidates' cheap first-passes are screened and only the
            // top-M get the expensive critique/revise + panel half — bounding the
            // N× panel cost (DGM cheap→expensive eval). Default OFF; with it off
            // every candidate is finished (the §29.1.1 behaviour).
            @org.springframework.beans.factory.annotation.Value("${app.research.eval-cascade.enabled:false}")
            boolean evalCascadeEnabled,
            @org.springframework.beans.factory.annotation.Value("${app.research.eval-cascade.screen-top-m:2}")
            int evalCascadeScreenTopM) {
        this.router                    = router;
        // Phase 8 Tier 4 — GpuEngineAdapter is @Autowired(required=false) so
        // tests and configurations without the GPU engine wired still construct
        // cleanly; run_gpu_eval surfaces a "not configured" error in that case.
        // Phase 22 §22.2 — JuliaMathClient is similarly optional. When
        // null (or disabled, or unreachable), the sidecar delegation in
        // MathToolSuite short-circuits and Symja stays the default.
        this.toolSuite                 = new MathToolSuite(router, objectMapper,
                gpuEngineAdapter, juliaMathClient, conjectureVerifier,
                sympyIdentityClient);
        // Lever 2 — wire the literature corpus into the tool suite (post-construction
        // setter; MathToolSuite isn't a Spring bean). literatureSearch may be null;
        // the search_literature tool is then omitted regardless of the flag.
        this.toolSuite.setLiteratureSearch(literatureSearch);
        // discoveryService is field-injected (after construction); wired in wireDiscoveryTool().
        this.selfCheckTools            = new RevisionSelfCheckTools(objectMapper);
        this.objectMapper              = objectMapper;
        this.juliaMathClient           = juliaMathClient;
        this.constantRecognitionService = constantRecognitionService;
        this.promptRegistry            = promptRegistry;
        this.literatureSearch          = literatureSearch;
        this.literatureSearchEnabled   = literatureSearchEnabled;
        this.discussionSectionEnabled  = discussionSectionEnabled;
        this.ollamaFallbackEnabled     = ollamaFallbackEnabled;
        this.structuredFindingsEnabled = structuredFindingsEnabled;
        // Phase 7 F2 — flag-off path force-clamps maxComputations to 1 so the
        // entire pipeline (parser, prompt, truncation guard, validators, markdown)
        // stays bit-identical to the F1-baseline single-block behaviour.
        this.maxComputations           = structuredFindingsEnabled
                ? Math.max(1, maxComputationsCfg)
                : 1;
        this.panelDegradationDemoteEnabled = panelDegradationDemoteEnabled;
        // Phase 29 §29.1.1 — clamp to 1 when disabled so every downstream read
        // of bestOfNCandidates is the single-candidate path regardless of the
        // configured value; flag-off is then bit-identical to pre-29.
        this.bestOfNEnabled    = bestOfNEnabled;
        this.bestOfNCandidates = bestOfNEnabled ? Math.max(1, bestOfNCandidates) : 1;
        // Phase 29 §29.1.2 — the cascade is meaningful only when best-of-N
        // produces >1 candidate; clamp top-M to >=1.
        this.evalCascadeEnabled   = evalCascadeEnabled;
        this.evalCascadeScreenTopM = Math.max(1, evalCascadeScreenTopM);
        // Narration parallelism defaults to the primary LLM pool size, but that
        // can outrun the most-constrained provider's token-per-minute ceiling
        // (Groq: 12k TPM on llama-3.3-70b, 8k on gpt-oss-120b). When parallel
        // narrations collide with that floor the batch starves: budget_fallback
        // chains exhaust and papers publish with empty traces or hit the
        // narration timeout — the 2026-05-26 batch lost 7 papers (955-964) this
        // way. A positive narration-concurrency-cap throttles below the pool
        // size; 0 (default) preserves the historical pool-sized behaviour.
        int narrationPoolSize = router.primaryPoolSize();
        int narrationSlots = narrationConcurrencyCap > 0
                ? Math.min(narrationPoolSize, narrationConcurrencyCap)
                : narrationPoolSize;
        this.narrationSlot             = new Semaphore(narrationSlots);
        log.info("ResearchNarrationService: narrationSlots={} literatureSearchEnabled={} "
                + "discussionSectionEnabled={} ollamaFallbackEnabled={} "
                + "structuredFindingsEnabled={} maxComputations={} (cfg={}) "
                + "panelDegradationDemoteEnabled={} bestOfNEnabled={} bestOfNCandidates={} "
                + "evalCascadeEnabled={} evalCascadeScreenTopM={}",
                narrationSlot.availablePermits(), literatureSearchEnabled, discussionSectionEnabled,
                ollamaFallbackEnabled, structuredFindingsEnabled, maxComputations, maxComputationsCfg,
                panelDegradationDemoteEnabled, this.bestOfNEnabled, this.bestOfNCandidates,
                this.evalCascadeEnabled, this.evalCascadeScreenTopM);
        if (literatureSearchEnabled && literatureSearch == null) {
            log.warn("ResearchNarrationService: app.research.literature-search.enabled=true "
                    + "but no LiteratureSearchService bean on the context; degrading to off");
        }
    }

    /**
     * Phase 30 fork 2b Phase 4 — wire the (field-injected) discovery service into
     * the tool suite after autowiring. {@code discoveryService} is null unless the
     * discovery beans are present; the {@code discover_mertens_value} tool stays
     * absent then, and (when present) only advertises itself if runtimeExecEnabled.
     */
    @org.springframework.beans.factory.annotation.Value("${app.research.narration.proseIntegrityEnabled:false}")
    private boolean proseIntegrityFlag;

    /** Phase 30 Wave 6 — 30.7 P3: gate for the propose_mechanism professor tool. OFF by default. */
    @org.springframework.beans.factory.annotation.Value("${app.research.conjectureInvestigation.mechanismProposalEnabled:false}")
    private boolean mechanismProposalFlag;

    /** 2026-06-05 — gate for the scalar_window_stats professor tool. OFF by default. */
    @org.springframework.beans.factory.annotation.Value("${app.research.conjectureInvestigation.scalarStatsToolEnabled:false}")
    private boolean scalarStatsToolFlag;

    @jakarta.annotation.PostConstruct
    void wireDiscoveryTool() {
        if (toolSuite != null) {
            toolSuite.setDiscoveryService(discoveryService);
            toolSuite.setFrontierKnowledge(frontierKnowledge);
            toolSuite.setMechanismProposalEnabled(mechanismProposalFlag);
            toolSuite.setScalarStatsToolEnabled(scalarStatsToolFlag);
        }
        // §review Rec 2/3 — flag-gated prose-integrity addendum (static mirror so the
        // static revision-prompt builders can read it). Domain-neutral; OFF by default.
        proseIntegrityEnabled = proseIntegrityFlag;
        log.info("ResearchNarrationService — proseIntegrityAddendum={}", proseIntegrityFlag ? "ON" : "OFF");
    }

    /**
     * Phase 28 §28.5 — optional sidecar shadow-read wrapper. Spring
     * resolves the {@link com.emergentterrain.research.MathPhysicsEngineDelegate}
     * bean (always present in production); tests that construct this
     * service directly skip the setter and the legacy in-process
     * compute path is preserved.
     *
     * <p>Propagates to the embedded {@link MathToolSuite} so its
     * {@code run_param_sweep} tool also runs through the delegate.
     */
    private volatile com.emergentterrain.research.MathPhysicsEngineDelegate physicsEngineDelegate;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPhysicsEngineDelegate(
            com.emergentterrain.research.MathPhysicsEngineDelegate delegate) {
        this.physicsEngineDelegate = delegate;
        if (this.toolSuite != null) {
            this.toolSuite.setPhysicsEngineDelegate(delegate);
        }
    }

    /**
     * Centralised dispatch for the two MathPhysicsEngine.compute call
     * sites in this class — delegate when wired, static when not.
     */
    private MathPhysicsEngine.ResearchResult dispatchCompute(
            String domain, int topicIndex, int param1, double param2,
            MathPhysicsEngine.SearchParams sp) {
        com.emergentterrain.research.MathPhysicsEngineDelegate delegate = this.physicsEngineDelegate;
        if (delegate == null) {
            throw new IllegalStateException("physics-topic compute requires the sidecar-backed "
                    + "MathPhysicsEngineDelegate; none wired (in-process engine removed in §28.6).");
        }
        return delegate.compute(domain, topicIndex, param1, param2, sp);
    }

    /**
     * Returns true when the simulation gate is wired and currently closed, meaning
     * the next Groq-spending stage for this paper should short-circuit. Stamps the
     * callPath with a stage-specific marker so the persisted Call Path explains why
     * the paper skipped that stage.
     */
    private boolean gateClosed(String stage, PaperDraft draft, List<String> callPath) {
        if (systemResourcesGate != null && !systemResourcesGate.isOpen()) {
            callPath.add("gate:resources:" + stage);
            log.warn("LLM stage skipped: resources gate CRITICAL paperId={} stage={} reason={}",
                    draft.paperId(), stage, systemResourcesGate.reason());
            return true;
        }
        if (simulationGate == null || simulationGate.isOpen()) return false;
        String reason = simulationGate.reason();
        callPath.add("gate:closed:" + stage);
        log.warn("LLM stage skipped: gate closed paperId={} stage={} reason={}",
                draft.paperId(), stage, reason);
        return true;
    }

    /** Wire the narration callback into UniversityService once the context is ready. */
    @EventListener(ContextRefreshedEvent.class)
    @ConditionalOnBean(UniversityService.class)
    public void wireUniversityCallback(ContextRefreshedEvent event) {
        UniversityService university = event.getApplicationContext()
                .getBeanProvider(UniversityService.class).getIfAvailable();
        if (university != null) {
            university.setNarratingCallback(this::enqueueNarration);
            this.narrationPublisher = university::publishPaper;
            this.draftSettledCallback = university::releaseDraftSlot;
            log.info("ResearchNarrationService: narration callback wired to UniversityService");
        }
        // Phase 17 — wire the gate-retry queue. retryDispatch goes through the
        // standard enqueueNarration path (fresh narration on a virtual thread);
        // giveUpPublisher forwards to the same narrationPublisher the normal
        // path uses, so the cached first-pass lands on disk via the existing
        // publishPaper validation ladder.
        if (gateRetryQueue != null) {
            gateRetryQueue.wireCallbacks(
                    this::enqueueNarration,
                    (draft, narration) -> {
                        if (narrationPublisher != null) {
                            narrationPublisher.accept(draft, narration);
                        }
                    });
        }
    }

    // --- Paper narration ---

    /**
     * Submits a passed paper for LLM narrative synthesis on a virtual thread.
     * Refinement and narration run on separate slots so a slow GPU refinement loop
     * does not block other papers from starting their Groq narration call.
     */
    public void enqueueNarration(PaperDraft draft) {
        executor.execute(() -> refineAndNarrate(draft));
    }

    /**
     * Supervises {@link #doRefineAndNarrate} with a wall-clock timeout
     * ({@code app.research.narrationTimeoutMs}). The work runs on its own virtual
     * thread; if it exceeds the budget we cancel(true) it — interrupting the hung
     * (interruptible) HTTP call so the thread and connection unwind — rather than
     * letting the draft sit until the stuck-draft reaper reclaims it. The backlog
     * slot is released in the finally on every outcome (completion, timeout, or
     * failure), keyed by the original draft's paperId (idempotent with
     * publishPaper's own release).
     */
    private void refineAndNarrate(PaperDraft draft) {
        // Acquire the narration concurrency permit BEFORE the wall-clock
        // supervisor starts. Time spent queueing for a slot must NOT count
        // against narrationTimeoutMs: otherwise a low narration-concurrency-cap
        // would let a queued paper exhaust its 420s budget and be abandoned
        // before any work ran (and acquireUninterruptibly would leave it a
        // zombie that narrates after the supervisor gave up and released the
        // backlog slot). Acquiring here parks the cheap enqueue virtual thread
        // until a permit is free; the permit is held for the whole supervised
        // task and released on every outcome. The 10-min stuck-draft reaper
        // remains the only backstop for a paper parked here under sustained
        // overload — 14× the narration budget, and reclaiming a long-queued
        // draft at a deliberately throttled cap is the correct overload signal.
        narrationSlot.acquireUninterruptibly();
        try {
            runSupervised(executor, () -> doRefineAndNarrate(draft), narrationTimeoutMs,
                    draft.paperId(), draftSettledCallback,
                    narrationFirstPassCheckpointEnabled
                            ? () -> publishFirstPassCheckpoint(draft) : null);
        } finally {
            narrationSlot.release();
        }
    }

    /**
     * rec #8 — on a narration wall-clock timeout, salvage the paper by publishing
     * the latest checkpointed first-pass instead of abandoning it. The first-pass
     * lacks the professor/critique stage, so it is stamped
     * {@code critique:skip:incomplete_firstpass} (which UniversityService routes to
     * NEEDS_REVISION) — the content is surfaced for revision rather than discarded
     * and left for the stuck-draft reaper. {@link java.util.Map#remove} is atomic,
     * so if the (interrupted) worker also reached its normal publish, only one of
     * the two claims the checkpoint and publishes.
     */
    private void publishFirstPassCheckpoint(PaperDraft draft) {
        NarrationResult cp = firstPassCheckpoints.remove(draft.paperId());
        // No checkpoint to salvage → take NO publish claim, so a worker that
        // later completes still publishes normally (rec #2c — don't strand the
        // paper).
        if (cp == null || narrationPublisher == null) return;
        // rec #2c — only publish the checkpoint if we win the at-most-once claim.
        // If the normal publish already won (worker completed concurrently), skip
        // rather than double-publish the partial over the full result.
        if (!claimPublish(draft.paperId())) {
            log.info("narration timeout-checkpoint paperId={} — normal publish already "
                    + "claimed this paper; skipping first-pass salvage (rec #2c)", draft.paperId());
            return;
        }
        String base = (cp.callPath() == null || cp.callPath().isBlank()) ? "fp:?" : cp.callPath();
        NarrationResult stamped = cp.withCallPath(base + " → critique:skip:incomplete_firstpass");
        log.warn("narration timeout-checkpoint paperId={} — publishing first-pass as "
                + "NEEDS_REVISION instead of abandoning (rec #8)", draft.paperId());
        narrationPublisher.accept(draft, stamped);
    }

    /**
     * Run {@code work} on {@code exec} with a wall-clock {@code timeoutMs}. On
     * timeout we cancel(true) it — interrupting the in-flight (interruptible) HTTP
     * call so the thread and connection unwind — instead of letting the draft hang
     * until the stuck-draft reaper reclaims it. {@code onSettle} is invoked exactly
     * once with {@code paperId} on <em>every</em> outcome (completion, timeout, or
     * failure) to release the backlog slot — keyed by the original draft's paperId,
     * idempotent with publishPaper's own release. Package-private + static so the
     * supervision is unit-testable without standing up the full service.
     */
    static void runSupervised(ExecutorService exec, Runnable work, long timeoutMs,
                              String paperId, java.util.function.Consumer<String> onSettle) {
        runSupervised(exec, work, timeoutMs, paperId, onSettle, null);
    }

    /**
     * As {@link #runSupervised(ExecutorService, Runnable, long, String, java.util.function.Consumer)},
     * plus an {@code onTimeout} hook invoked on the supervisor thread in the
     * timeout branch only (rec #8 — publishing the first-pass checkpoint instead
     * of abandoning the paper). It runs after the cancel and before {@code onSettle};
     * any throw from it is logged and swallowed so the slot is still released.
     */
    static void runSupervised(ExecutorService exec, Runnable work, long timeoutMs,
                              String paperId, java.util.function.Consumer<String> onSettle,
                              Runnable onTimeout) {
        Future<?> f = exec.submit(work);
        try {
            if (timeoutMs <= 0) {
                f.get();                 // disabled: wait indefinitely (reaper remains the backstop)
            } else {
                f.get(timeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException te) {
            f.cancel(true); // interrupt the hung call; the stuck-draft reaper is the backstop
            log.warn("narration timed out after {}ms — paperId={} (interrupted; slot released)",
                    timeoutMs, paperId);
            if (onTimeout != null) {
                try {
                    onTimeout.run();
                } catch (RuntimeException ex) {
                    log.warn("narration timeout hook failed paperId={}: {}", paperId, ex.toString());
                }
            }
        } catch (ExecutionException ee) {
            log.warn("narration task failed paperId={}: {}", paperId,
                    ee.getCause() != null ? ee.getCause().toString() : ee.toString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            f.cancel(true);
        } finally {
            if (onSettle != null) onSettle.accept(paperId);
        }
    }

    /** The narration pipeline proper; supervised by {@link #refineAndNarrate}. */
    private void doRefineAndNarrate(PaperDraft draft) {
        refinementSlot.acquireUninterruptibly();
        PaperDraft enrichedDraft;
        try {
            enrichedDraft = maybeRefineComputation(draft);
        } finally {
            refinementSlot.release();
        }
        // Statistical framing — fast call between refinement and narration; best-effort
        PaperDraft framedDraft = applyStatisticalFraming(enrichedDraft);
        // The narration concurrency permit is acquired by refineAndNarrate
        // BEFORE the wall-clock supervisor starts (so queue-wait doesn't burn
        // narrationTimeoutMs); it is held across this whole supervised task.
        // Scope the paperId so any LLM call made during narration (primary
        // stream, critique tool loop, MathToolSuite sandbox safety review on
        // the same virtual thread) is captured per-paper by LlmTraceWriter.
        com.emergentterrain.llm.LlmCallContext.withPaperId(
                framedDraft.paperId(),
                () -> runNarration(framedDraft));
    }

    record RefinementDecision(int param1, double param2, String rationale) {}

    /**
     * An experiment prescribed by the professor (critique LLM) to address a specific weakness.
     * type="param"    → run MathPhysicsEngine.compute at param1/param2
     * type="dynamic"  → run DynamicComputeEngine with predicate/range (MATHEMATICS / INTERDISCIPLINARY only)
     */
    record ExperimentalGap(
            String type, String description, String rationale,
            Integer param1, Double param2,               // param path
            String predicate, Integer rangeStart, Integer rangeEnd,  // dynamic path
            String metric, double baseline, String hypothesis) {}

    /** Full structured result from the professor critique call. */
    record CritiqueResult(List<String> weaknesses, ExperimentalGap experimentalGap) {
        boolean hasActionableFeedback() {
            return !weaknesses.isEmpty() || experimentalGap != null;
        }
    }

    /**
     * Phase 20.4 step 3 — emit the narration-envelope rollups to ActivityMetrics
     * if a metrics bean is wired. One counter per status enum value plus the
     * two token-total accumulators. The activity-row delta-snapshot logic
     * surfaces both per-interval increments and running totals; consumers
     * (ActivityReporter, future dashboards) read the per-status counters to
     * see status distribution over time without parsing call_path strings.
     */
    private void emitNarrationEnvelopeMetrics(NarrationResult.OutcomeStatus status,
                                                long inputTokens, long outputTokens) {
        if (activityMetrics == null) return;
        activityMetrics.increment("narration.status." + status.name());
        activityMetrics.add("narration.input_tokens", inputTokens);
        activityMetrics.add("narration.output_tokens", outputTokens);
    }

    private void runNarration(PaperDraft draft) {
        // Phase 20.4 — orchestration wall-clock bracket. Captured at entry so
        // every publish path (suppression, gate-closed, normal completion) can
        // stamp the same startedAt onto its NarrationResult envelope.
        java.time.Instant orchestrationStartedAt = java.time.Instant.now();

        // End-game: all Groq models TPD-exhausted — suppress and log recovery time
        if (router.primaryFullyExhausted()) {
            java.time.Instant recovery = router.earliestPrimaryRecovery();
            log.error("LLM RESEARCH PAUSED — all Groq models TPD-exhausted. " +
                    "Earliest recovery: {}. paperId={} suppressed.", recovery, draft.paperId());
            // Phase 20.4 — ALL_MODELS_BLOCKED stamped via timing-only log line.
            // TODO step 2b: switch the publisher contract from null-publish to a
            // sentinel NarrationResult so the envelope rides through.
            java.time.Instant suppressedAt = java.time.Instant.now();
            log.info("narration envelope paperId={} status=ALL_MODELS_BLOCKED " +
                    "startedAt={} completedAt={} durationMs={}",
                    draft.paperId(), orchestrationStartedAt, suppressedAt,
                    java.time.Duration.between(orchestrationStartedAt, suppressedAt).toMillis());
            emitNarrationEnvelopeMetrics(NarrationResult.OutcomeStatus.ALL_MODELS_BLOCKED, 0L, 0L);
            // Phase 20.4 step 2b-3 — emit a suppression sentinel instead of
            // raw null so UniversityService.publishPaper can read the envelope.
            if (narrationPublisher != null) {
                narrationPublisher.accept(draft, NarrationResult.suppressed(
                        NarrationResult.OutcomeStatus.ALL_MODELS_BLOCKED,
                        orchestrationStartedAt, suppressedAt, 0L, 0L));
            }
            return;
        }

        // Pre-emptive: SimulationGate budget tripped. Suppress before spending any
        // primary tokens on first-pass narration. Same publisher contract as
        // primaryFullyExhausted (null result → queue counter decrements). This
        // gate is checked ONCE before any best-of-N candidate is produced.
        List<String> gateCallPath = new java.util.ArrayList<>();
        if (gateClosed("first-pass", draft, gateCallPath)) {
            log.info("LLM call path paperId={}: {}", draft.paperId(), String.join(" → ", gateCallPath));
            // Phase 20.4 — BUDGET_EXHAUSTED stamped via timing-only log line
            // (same null-publish contract as ALL_MODELS_BLOCKED above; the
            // step-2b sentinel work will carry the envelope here too).
            java.time.Instant suppressedAt = java.time.Instant.now();
            log.info("narration envelope paperId={} status=BUDGET_EXHAUSTED " +
                    "startedAt={} completedAt={} durationMs={}",
                    draft.paperId(), orchestrationStartedAt, suppressedAt,
                    java.time.Duration.between(orchestrationStartedAt, suppressedAt).toMillis());
            emitNarrationEnvelopeMetrics(NarrationResult.OutcomeStatus.BUDGET_EXHAUSTED, 0L, 0L);
            // Phase 20.4 step 2b-3 — suppression sentinel (see ALL_MODELS_BLOCKED above).
            if (narrationPublisher != null) {
                narrationPublisher.accept(draft, NarrationResult.suppressed(
                        NarrationResult.OutcomeStatus.BUDGET_EXHAUSTED,
                        orchestrationStartedAt, suppressedAt, 0L, 0L));
            }
            return;
        }

        // Phase 29 §29.1.1 — produce one (default) or N panel-scored candidate
        // narrations for this draft and publish the highest-graded one. With
        // best-of-N off (bestOfNCandidates==1) this is a single
        // produceNarrationCandidate call — same call order, envelope, and
        // metrics emission as pre-29.
        NarrationResult result = produceBestCandidate(draft, orchestrationStartedAt);

        // rec #8 — the full result supersedes any first-pass checkpoint; drop it
        // before publishing so a racing timeout hook can't re-publish the partial.
        firstPassCheckpoints.remove(draft.paperId());
        // rec #2c — gate the normal publish on winning the at-most-once claim, so
        // a worker that survives past the timeout (e.g. interrupt landed between
        // calls) can't overwrite a checkpoint the timeout hook already published.
        // When the checkpoint feature is off there is no timeout hook and hence
        // no race — publish unconditionally, preserving pre-rec#8 behaviour.
        if (narrationPublisher != null
                && (!narrationFirstPassCheckpointEnabled || claimPublish(draft.paperId()))) {
            narrationPublisher.accept(draft, result);
        }
        // Phase 17 — clear the retry queue entry on a successful retry publish.
        // No-op for non-retry papers (the common case) and for retries that
        // re-hit the gate (result is a suppression sentinel then; the queue
        // entry stays for the next tick). markCompleted itself is null-safe
        // on missing entries.
        // Phase 20.4 step 2b-3 — `result` is now never null (suppression
        // emits a sentinel), so the null check shifts to isSuppressed()
        // to preserve the "re-hit gate keeps queue entry" semantics.
        if (gateRetryQueue != null && !result.isSuppressed()) {
            gateRetryQueue.markCompleted(draft.paperId());
        }
    }

    /**
     * Phase 29 §29.1.1 — best-of-N driver. Produces {@code bestOfNCandidates}
     * panel-scored candidate narrations for the same draft and returns the
     * highest-graded one (DGM/STOP generate-many-keep-best). With best-of-N off
     * the field is clamped to 1, so this is a single {@link
     * #produceNarrationCandidate} call — bit-identical to pre-29. Loser
     * candidates are discarded; only the returned winner reaches the publisher.
     *
     * <p>Each candidate is a full first-pass + critique/revise + panel cycle, so
     * they run serially inside the one {@code withPaperId} scope. Between
     * candidates we stop early if the primary pool has fully exhausted rather
     * than burning a doomed cycle; the first candidate always runs (the
     * exhaustion guard at the top of {@link #runNarration} already covered the
     * pre-first-candidate case).
     */
    private NarrationResult produceBestCandidate(PaperDraft draft,
                                                 java.time.Instant orchestrationStartedAt) {
        if (bestOfNCandidates <= 1) {
            return produceNarrationCandidate(draft, orchestrationStartedAt);
        }
        if (evalCascadeEnabled) {
            return produceWithCascade(draft, orchestrationStartedAt);
        }
        // No cascade: produce N full candidates serially (each is a full
        // narration+panel cycle with side effects), stopping early if the
        // primary pool fully exhausts. The first candidate always runs — the
        // exhaustion guard at the top of runNarration covers the pre-first case.
        List<NarrationResult> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < bestOfNCandidates; i++) {
            // rec #2b (2026-05-28) — abort best-of-N the moment the worker is
            // interrupted (narration timeout). A candidate's inner stages catch
            // Exception and may degrade-but-continue, so an interrupt could
            // otherwise be swallowed and the loop would start the next candidate,
            // keeping the zombie narration alive past its timeout.
            if (Thread.currentThread().isInterrupted()) {
                log.warn("best-of-n paperId={} interrupted after {}/{} candidates — aborting",
                        draft.paperId(), i, bestOfNCandidates);
                break;
            }
            if (i > 0 && router.primaryFullyExhausted()) {
                log.info("best-of-n paperId={} stopping after {}/{} candidates — primary exhausted",
                        draft.paperId(), i, bestOfNCandidates);
                break;
            }
            NarrationResult candidate = produceNarrationCandidate(draft, orchestrationStartedAt);
            log.info("best-of-n paperId={} candidate {}/{} score={} suppressed={}",
                    draft.paperId(), i + 1, bestOfNCandidates,
                    String.format("%.4f", scoreCandidate(candidate)), candidate.isSuppressed());
            candidates.add(candidate);
        }
        NarrationResult best = selectBest(candidates);
        log.info("best-of-n paperId={} selected winner score={}",
                draft.paperId(), String.format("%.4f", scoreCandidate(best)));
        return best;
    }

    /**
     * Phase 29 §29.1.2 — eval-cascade best-of-N. Produce N cheap first-passes,
     * screen them by {@link #scoreCandidate} (which, with no panel yet, scores
     * on the narrator's first-pass self-confidence — a free signal, no extra LLM
     * call), then run the expensive {@link #finishCandidate} half (critique/
     * revise + panel) on only the top-{@code evalCascadeScreenTopM}. The winner
     * is selected among those finished, panel-graded candidates. This bounds the
     * per-paper panel cost to M instead of N while keeping the panel flow itself
     * unchanged. Early-stops first-pass production on primary exhaustion.
     *
     * <p>v1 screen signal is first-pass self-confidence; a dedicated fast-tier
     * (Ollama) screen grade is the documented richer follow-up (§29.1.2).
     */
    private NarrationResult produceWithCascade(PaperDraft draft,
                                               java.time.Instant orchestrationStartedAt) {
        List<FirstPass> firstPasses = new java.util.ArrayList<>();
        for (int i = 0; i < bestOfNCandidates; i++) {
            // rec #2b — abort on interrupt (see produceBestCandidate).
            if (Thread.currentThread().isInterrupted()) {
                log.warn("cascade paperId={} interrupted after {}/{} first-passes — aborting",
                        draft.paperId(), i, bestOfNCandidates);
                break;
            }
            if (i > 0 && router.primaryFullyExhausted()) {
                log.info("cascade paperId={} stopping first-pass production after {}/{} — primary exhausted",
                        draft.paperId(), i, bestOfNCandidates);
                break;
            }
            firstPasses.add(produceFirstPass(draft));
        }
        int m = Math.min(evalCascadeScreenTopM, firstPasses.size());
        List<FirstPass> screened = topM(firstPasses, fp -> scoreCandidate(fp.firstPass()), m);
        log.info("cascade paperId={} screened {} first-passes → finishing top {} (panel runs {}x not {}x)",
                draft.paperId(), firstPasses.size(), screened.size(), screened.size(), bestOfNCandidates);

        List<NarrationResult> finished = new java.util.ArrayList<>();
        for (FirstPass fp : screened) {
            // rec #2b — abort on interrupt (see produceBestCandidate).
            if (Thread.currentThread().isInterrupted()) {
                log.warn("cascade paperId={} interrupted during finish stage — aborting", draft.paperId());
                break;
            }
            NarrationResult candidate = finishCandidate(draft, orchestrationStartedAt, fp);
            log.info("cascade paperId={} finished candidate score={} suppressed={}",
                    draft.paperId(), String.format("%.4f", scoreCandidate(candidate)),
                    candidate.isSuppressed());
            finished.add(candidate);
        }
        NarrationResult best = selectBest(finished);
        log.info("cascade paperId={} selected winner score={}",
                draft.paperId(), String.format("%.4f", scoreCandidate(best)));
        return best;
    }

    /**
     * Phase 29 §29.1.2 — return the {@code m} highest-scored items (descending),
     * stable on ties (original order preserved among equal scores). Used to pick
     * which screened first-passes get the expensive finish. Static + package-
     * private for unit testing. {@code m <= 0} → empty; {@code m >= size} → a
     * score-sorted copy of all items.
     */
    static <T> List<T> topM(List<T> items, java.util.function.ToDoubleFunction<T> score, int m) {
        if (items == null || items.isEmpty() || m <= 0) {
            return List.of();
        }
        List<T> sorted = new java.util.ArrayList<>(items);
        // Stable sort by score descending; Collections.sort is stable, so equal
        // scores keep their original relative order.
        sorted.sort(java.util.Comparator.comparingDouble(score).reversed());
        return new java.util.ArrayList<>(sorted.subList(0, Math.min(m, sorted.size())));
    }

    /**
     * Phase 29 §29.1.1 — winner selection: the highest-{@link
     * #scoreCandidate(NarrationResult) scored} candidate (argmax, first wins on
     * exact ties via strict {@code >}). The single source of truth for "which
     * candidate gets published"; {@link #produceBestCandidate} only adds the
     * produce-N loop + early-stop around it. Static + package-private so the
     * selection contract is unit-testable on real {@link NarrationResult}
     * objects without standing up the full service. An all-suppressed list
     * returns the first element (a suppression sentinel), preserving the pre-29
     * publish-the-sentinel behaviour. Empty/null input returns null.
     */
    static NarrationResult selectBest(List<NarrationResult> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        NarrationResult best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (NarrationResult candidate : candidates) {
            double score = scoreCandidate(candidate);
            if (best == null || score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Phase 29 §29.1.1 — best-of-N selection key; higher is better.
     *
     * <ul>
     *   <li>Suppressed candidate (professor blank / all-TPD sentinel) → {@code
     *       -∞}, so a real candidate always wins over a suppressed one.</li>
     *   <li>Panel ran → composite = mean of the four median grades (honesty,
     *       completeness, rigor, novelty) plus a small {@code 0.001 ×
     *       agreementScore} consensus tiebreak so a unanimous panel beats a
     *       contested one at equal grades.</li>
     *   <li>Panel did not run (disabled / empty outcome) → fall back to the
     *       narrator's self-assessed {@code confidenceScore} so best-of-N still
     *       discriminates when the panel is off.</li>
     * </ul>
     *
     * Static + package-private: pure function of the candidate, unit-testable
     * without constructing the service.
     */
    static double scoreCandidate(NarrationResult candidate) {
        if (candidate == null || candidate.isSuppressed()) {
            return Double.NEGATIVE_INFINITY;
        }
        com.emergentterrain.research.PanelOutcome panel = candidate.panelOutcome();
        if (panel == null || panel.isEmpty()) {
            return candidate.confidenceScore();
        }
        com.emergentterrain.research.PanelReview.Grades g = panel.aggregateGrades();
        double composite = (g.honesty() + g.completeness() + g.rigor() + g.novelty()) / 4.0;
        return composite + 0.001 * panel.agreementScore();
    }

    /**
     * Phase 29 §29.1.1 — produce one complete candidate narration: first-pass
     * stream → critique/revise (which runs the professor tool loop and the
     * review panel) → terminal-status classification → built {@link
     * NarrationResult} with its envelope, plus the per-candidate envelope
     * metric. Does NOT publish and does NOT touch the gate-retry queue — the
     * caller ({@link #produceBestCandidate}) selects the winner and {@link
     * #runNarration} publishes it once.
     *
     * <p>This is the pre-29 body of {@code runNarration} extracted verbatim;
     * {@code callPath} and {@code firstPassFailed} are now per-candidate so each
     * best-of-N attempt carries its own call path.
     */
    /**
     * Phase 29 §29.1.2 — the cheap half of candidate production: the first-pass
     * primary stream + parse, with its call path and failure flag. Split out so
     * the eval cascade can produce N cheap first-passes, screen them, and pay
     * for the expensive {@link #finishCandidate} half (critique/revise + panel)
     * only on the top-M.
     */
    private record FirstPass(NarrationResult firstPass, List<String> callPath,
                             boolean firstPassFailed) {}

    /**
     * Lever 1 — the strict json_schema the NarrationResult-shaped calls
     * constrain their output to when {@link #narrationStructuredOutputEnabled}
     * is on and the provider advertises {@code supports-json-schema}. Mirrors
     * exactly the fields {@link #parseNarrationResult(ObjectMapper, String)}
     * consumes, so the parser is the contract.
     *
     * <p>Design notes:
     * <ul>
     *   <li><b>OpenAI strict mode</b> requires every property in {@code required}
     *       and {@code additionalProperties:false} at every object level —
     *       optional fields are therefore expressed as nullable unions
     *       ({@code ["string","null"]}), not by omission.</li>
     *   <li><b>No enums</b> — xAI/grok rejects schema enums whose values contain
     *       {@code '/'} (see {@code ReviewPanelService}); allowed values live in
     *       descriptions.</li>
     *   <li><b>{@code findings} is a string</b> — the F1 baseline shape. The
     *       per-computation map ({@code structured-findings}) and typed
     *       {@code cited_papers} / {@code formal_findings} variants stay on the
     *       {@code json_object} path; combining them with structured-output is a
     *       documented follow-on. {@code numeric_claims} and {@code followUp}
     *       ARE included so {@code additionalProperties:false} doesn't silently
     *       disable them.</li>
     * </ul>
     */
    static final String NARRATION_RESPONSE_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["hypothesis","findings","conclusion","confidenceScore","cited_paper_ids","discussion","related_work","numeric_claims","followUp"],
              "properties": {
                "hypothesis": {"type":"string","description":"The hypothesis under test, one or two sentences."},
                "findings": {"type":"string","description":"What the computations showed, grounded in the tool-call results."},
                "conclusion": {"type":"string","description":"Whether the hypothesis was supported and the takeaway."},
                "confidenceScore": {"type":"number","description":"Confidence in [0,1] that the conclusion follows from the evidence."},
                "discussion": {"type":["string","null"],"description":"Optional Discussion-section prose; null if none."},
                "related_work": {"type":["string","null"],"description":"Optional Related-Work-section prose; null if none."},
                "cited_paper_ids": {"type":"array","description":"IDs of prior papers cited; empty array if none.","items":{"type":"string"}},
                "numeric_claims": {
                  "type":"array",
                  "description":"Every numeric value asserted in the prose, each tied to the tool call that produced it. Empty array if none.",
                  "items":{
                    "type":"object",
                    "additionalProperties": false,
                    "required":["value","tool_call_id","units","context","source_location"],
                    "properties":{
                      "value":{"type":"number","description":"The numeric value as stated."},
                      "tool_call_id":{"type":"string","description":"The call_N id of the tool result this value came from."},
                      "units":{"type":["string","null"],"description":"Units, or null if dimensionless."},
                      "context":{"type":["string","null"],"description":"Short phrase describing what the value measures."},
                      "source_location":{"type":["string","null"],"description":"Where in the prose it appears, e.g. findings:1."}
                    }
                  }
                },
                "followUp": {
                  "type":["object","null"],
                  "description":"Optional next-experiment parameters, or null. param1 is an integer in [10,99]; param2 a number in [1.0,9.0].",
                  "additionalProperties": false,
                  "required":["param1","param2","predictedScore"],
                  "properties":{
                    "param1":{"type":"integer","description":"First sweep parameter, integer in [10,99]."},
                    "param2":{"type":"number","description":"Second sweep parameter, number in [1.0,9.0]."},
                    "predictedScore":{"type":["number","null"],"description":"Predicted peer score in [0,1], or null."}
                  }
                }
              }
            }""";

    /**
     * Lever 1 — run {@code body} with {@link #NARRATION_RESPONSE_SCHEMA} pinned
     * as the structured-output contract when the feature flag is on; otherwise
     * run it unchanged. The provider-capability gate
     * ({@code ProviderConfig.supportsJsonSchema}) is applied downstream in the
     * pool, so flag-on + non-supporting-provider still falls back to today's
     * {@code json_object} + {@code LenientJsonParser} path.
     */
    private void withNarrationSchema(Runnable body) {
        if (narrationStructuredOutputEnabled) {
            com.emergentterrain.llm.LlmCallContext.withJsonSchema(NARRATION_RESPONSE_SCHEMA, body);
        } else {
            body.run();
        }
    }

    private FirstPass produceFirstPass(PaperDraft draft) {
        List<String> callPath = new java.util.ArrayList<>();
        // Phase 20.4 — first-pass success tracker drives the OK vs PARTIAL split
        // at the normal-completion emission site. PARTIAL = first-pass threw but
        // the critique/revise path salvaged a usable result; OK = clean success.
        boolean firstPassFailed = false;

        // First-pass narration (Groq/primary stream)
        // rec #4 follow-up — guard against fp asserting uncomputed metrics / a
        // premature optimum (P-a328-995). Flag-gated; no-op when off.
        String prompt = appendFirstPassMetricGuard(
                buildNarrationPrompt(draft), firstPassMetricGuardEnabled);
        long start = System.currentTimeMillis();
        StringBuilder response = new StringBuilder();
        try {
            // Phase 20.5 — tag the first-pass narration with role="narration" so
            // RoleRouterPolicy can apply per-role overrides (e.g., avoid-models
            // for a specific role without affecting fast-audit / professor-tools).
            // Lever 1 — pin the narration json_schema when structured-output is on.
            withNarrationSchema(() ->
                    com.emergentterrain.llm.LlmCallContext.withRole("narration", () ->
                            router.streamPrimary(prompt, response::append)));
            callPath.add("fp:" + abbrev(router.primaryModel()));
            log.info("LLM narration first-pass done paperId={} ms={}", draft.paperId(),
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            firstPassFailed = true;
            callPath.add("fp:FAILED");
            log.warn("LLM narration failed paperId={} ms={}: {} — publishing with template text",
                    draft.paperId(), System.currentTimeMillis() - start, e.getMessage());
        }

        NarrationResult firstPass = parseNarrationResult(response.toString());
        // rec #8 — checkpoint the latest usable first pass so a wall-clock timeout
        // can salvage it (see publishFirstPassCheckpoint). Skip empty parses: a
        // checkpoint with no hypothesis AND no findings would publish junk.
        if (narrationFirstPassCheckpointEnabled
                && (firstPass.hypothesis() != null || firstPass.findings() != null)) {
            firstPassCheckpoints.put(draft.paperId(),
                    firstPass.withCallPath(String.join(" → ", callPath)));
        }
        return new FirstPass(firstPass, callPath, firstPassFailed);
    }

    /**
     * Phase 29 §29.1.1 — produce one complete candidate narration: cheap
     * first-pass then the expensive finish. Bit-identical to the pre-29 inline
     * body. Does NOT publish — see {@link #runNarration}.
     */
    private NarrationResult produceNarrationCandidate(PaperDraft draft,
                                                      java.time.Instant orchestrationStartedAt) {
        return finishCandidate(draft, orchestrationStartedAt, produceFirstPass(draft));
    }

    /**
     * Phase 29 §29.1.2 — the expensive half: critique/revise (professor tool
     * loop + review panel) onward, then terminal-status classification and the
     * built {@link NarrationResult} with envelope + per-candidate metric. Run on
     * every candidate without the cascade; only on the top-M screened first-
     * passes with it. The panel flow inside {@code maybeCritiqueAndRevise} is
     * unchanged — the cascade bounds cost by calling this fewer times, not by
     * altering the panel.
     */
    private NarrationResult finishCandidate(PaperDraft draft,
                                            java.time.Instant orchestrationStartedAt,
                                            FirstPass fp) {
        List<String> callPath = fp.callPath();
        boolean firstPassFailed = fp.firstPassFailed();
        List<String> auditGapDimensions = new java.util.ArrayList<>();
        NarrationResult finalResult = maybeCritiqueAndRevise(draft, fp.firstPass(), callPath, auditGapDimensions);

        String pathStr = String.join(" → ", callPath);
        log.info("LLM call path paperId={}: {}", draft.paperId(), pathStr);

        // Phase 20.4 — classify the terminal status before stamping the envelope.
        // Precedence (most specific wins):
        //   1. finalResult.status() carries a non-UNKNOWN signal from maybeCritique-
        //      AndRevise (PARTIAL on template fill, ERROR on incomplete-firstpass +
        //      template-fail, etc.). Honor it.
        //   2. finalResult == null → ERROR (TODO step 2b-3: sentinel publish lets
        //      the envelope ride on a non-null result so we don't lose the timing
        //      here either).
        //   3. firstPass threw a streamPrimary exception but a result was salvaged
        //      → PARTIAL.
        //   4. Default → OK.
        NarrationResult.OutcomeStatus terminalStatus;
        if (finalResult != null
                && finalResult.status() != null
                && finalResult.status() != NarrationResult.OutcomeStatus.UNKNOWN) {
            terminalStatus = finalResult.status();
        } else if (finalResult == null) {
            terminalStatus = NarrationResult.OutcomeStatus.ERROR;
        } else if (firstPassFailed) {
            terminalStatus = NarrationResult.OutcomeStatus.PARTIAL;
        } else {
            terminalStatus = NarrationResult.OutcomeStatus.OK;
        }

        // null means professor blank or all-TPD — pass null so queue counter is decremented.
        // Phase 4c Piece 2 gate 3: every emitted result is stamped with the primary
        // narration template id so the persisted ResearchPaper carries provenance.
        // Refinement / critique / revision steps are tracked separately via callPath.
        // Phase 7 F2 — stamp the ACTUAL variant version that ran (v1 / v2-with-discussion /
        // v3-structured-findings*) so per-variant rollback statistics see the variant
        // that produced the paper rather than the constant "v1".
        // Phase 20.4 step 2b-2 — read the per-paper cumulative token totals
        // accumulated by the pools during this paper's withPaperId scope.
        // Zero when no pool recorded usage (e.g. all suppressed branches —
        // those publish null and don't reach here anyway).
        // Phase 29 §29.1.1 — under best-of-N the withPaperId scope spans all
        // candidates, so these totals are cumulative across candidates produced
        // so far; the published winner's envelope therefore reports cumulative
        // best-of-N spend (RunBudgetTracker accounts all spend at the pool layer
        // regardless). Per-candidate token attribution is a documented follow-up.
        long paperInputTokens = com.emergentterrain.llm.LlmCallContext.currentInputTokens();
        long paperOutputTokens = com.emergentterrain.llm.LlmCallContext.currentOutputTokens();

        // Phase 20.4 — build the published NarrationResult. Non-null
        // finalResult: wrap with template provenance + chain withEnvelope.
        // Null finalResult: emit a suppression sentinel (step 2b-3) so the
        // publisher contract stays uniform — UniversityService always
        // receives a NarrationResult, never null, and can detect the
        // suppression via isSuppressed().
        java.time.Instant completedAt = java.time.Instant.now();
        NarrationResult result;
        if (finalResult == null) {
            result = NarrationResult.suppressed(terminalStatus,
                    orchestrationStartedAt, completedAt,
                    paperInputTokens, paperOutputTokens);
            // Mirror the suppression log line used by the ALL_MODELS_BLOCKED /
            // BUDGET_EXHAUSTED branches above so observability is uniform.
            log.info("narration envelope paperId={} status={} " +
                    "startedAt={} completedAt={} durationMs={} inputTokens={} outputTokens={}",
                    draft.paperId(), terminalStatus, orchestrationStartedAt, completedAt,
                    java.time.Duration.between(orchestrationStartedAt, completedAt).toMillis(),
                    paperInputTokens, paperOutputTokens);
        } else {
            result = new NarrationResult(
                    finalResult.hypothesis(), finalResult.findingsMap(), finalResult.conclusion(),
                    finalResult.followUp(), router.primaryModel(),
                    finalResult.confidenceScore(), finalResult.citedPaperIds(), pathStr,
                    finalResult.typedCitations(), finalResult.formalFindings(),
                    activeNarrationTemplateId(draft),
                    activeNarrationTemplateVersion(draft),
                    finalResult.discussion(), finalResult.relatedWork(),
                    List.copyOf(auditGapDimensions),
                    finalResult.toolCallTrace(),
                    finalResult.panelOutcome(),
                    finalResult.numericClaims())
                    .withEnvelope(orchestrationStartedAt, completedAt,
                            paperInputTokens, paperOutputTokens, terminalStatus);
        }
        // Phase 20.4 step 3 — rollup metric. Fires for every reachable
        // terminal status, so the 30s activity row sees a moving
        // distribution of narration outcomes. Under best-of-N this fires once
        // per candidate (each candidate is real narration work).
        emitNarrationEnvelopeMetrics(terminalStatus, paperInputTokens, paperOutputTokens);
        return result;
    }

    // --- Self-critique + revision ---

    /**
     * Professor–student collaboration loop.
     *
     * Tool-calling path (Groq available): the professor is a Groq model with four
     * mathematical tools it can call freely — param sweep, predicate scan, symbolic
     * evaluation, and sandboxed code (reviewed by 4b before execution). After calling
     * any tools it deems necessary, it writes a synthesis. The student (another Groq
     * call) then revises the paper incorporating the professor's findings.
     *
     * Fallback path (Ollama only): uses the existing JSON-based ExperimentalGap approach
     * (fast Ollama critique → prescribed experiment → Groq revision).
     *
     * Falls back to firstPass on any failure.
     */
    private NarrationResult maybeCritiqueAndRevise(PaperDraft draft, NarrationResult firstPass,
                                                    List<String> callPath, List<String> auditGapDimensions) {
        // Phase 20.4 step 2b-1 — accumulate the most-degraded status seen on
        // the path to the returned result. Stamped on the final result before
        // return so runNarration's envelope picks it up. UNKNOWN means "no
        // degradation observed" — runNarration's default (OK) wins.
        NarrationResult.OutcomeStatus pathStatus = NarrationResult.OutcomeStatus.UNKNOWN;

        if (firstPass.hypothesis() == null || firstPass.findings() == null || firstPass.conclusion() == null) {
            // G1 — fill null h/f/c fields from the draft's template fallbacks
            // (designed as the "LLM-failure" fallback per PaperDraft javadoc)
            // and proceed to critique rather than skipping. Falls through to
            // the original skip if even the templates can't close the gaps.
            NarrationResult merged = fillFromTemplates(draft, firstPass);
            if (merged == null) {
                callPath.add("critique:skip:incomplete_firstpass");
                // Phase 20.4 step 2b-1 — firstPass has null h/f/c AND draft
                // templates couldn't close them. Downstream publish will
                // either drop or fall back entirely to template text. ERROR
                // distinguishes this from PARTIAL: nothing usable came out
                // of either the LLM or the templates.
                return firstPass.withStatus(NarrationResult.OutcomeStatus.ERROR);
            }
            int filled = (firstPass.hypothesis() == null ? 1 : 0)
                       + (firstPass.findings()   == null ? 1 : 0)
                       + (firstPass.conclusion() == null ? 1 : 0);
            callPath.add("critique:partial:" + filled + "_filled");
            log.info("LLM partial-revision merge: filled {} null field(s) from PaperDraft templates paperId={}",
                    filled, draft.paperId());
            firstPass = merged;
            // Phase 20.4 step 2b-1 — template fill happened; mark PARTIAL so
            // even a clean downstream critique-and-revise won't erase the
            // signal that this paper used PaperDraft fallback text.
            pathStatus = NarrationResult.OutcomeStatus.PARTIAL;
        }
        // Phase 7 E3 — three-way routing. Tools first (any tool-capable primary
        // unblocked), then JSON path (primary chain has any unblocked pool — the
        // revision streams from a primary model), then Ollama (no tools; both
        // critique and revise on the fast pool). When all three fail, fall back
        // to firstPass with a skip log so telemetry sees a hard miss.
        NarrationResult routed;
        if (router.supportsProfessorTools()) {
            routed = maybeCritiqueAndReviseWithTools(draft, firstPass, callPath, auditGapDimensions);
        } else if (router.hasJsonPathPrimary()) {
            routed = maybeCritiqueAndReviseJsonPath(draft, firstPass, callPath);
        } else if (ollamaFallbackEnabled && router.hasFastPool()) {
            routed = maybeCritiqueAndReviseOllama(draft, firstPass, callPath);
        } else {
            callPath.add("critique:skip:no_path_available");
            log.warn("LLM no critique path available paperId={} ollamaFallbackEnabled={}",
                    draft.paperId(), ollamaFallbackEnabled);
            // Phase 20.4 step 2b-1 — usable firstPass but no critique path
            // available. The PARTIAL signal already on pathStatus (if a
            // template-fill happened) is at-least-as-degraded as this skip,
            // so prefer pathStatus when set; otherwise stamp PARTIAL fresh.
            return firstPass.withStatus(
                    pathStatus == NarrationResult.OutcomeStatus.UNKNOWN
                            ? NarrationResult.OutcomeStatus.PARTIAL
                            : pathStatus);
        }
        // Phase 20.4 step 2b-1 — stamp the accumulated path status onto the
        // routed result IF the routed result didn't classify itself with a
        // more-specific status (UNKNOWN / null = no opinion). Preserves any
        // future TOOL_LOOP_LIMIT / TEMPLATE_FALLBACK stamps the routed
        // variants may set themselves.
        if (routed != null
                && pathStatus != NarrationResult.OutcomeStatus.UNKNOWN
                && (routed.status() == null
                    || routed.status() == NarrationResult.OutcomeStatus.UNKNOWN)) {
            return routed.withStatus(pathStatus);
        }
        return routed;
    }

    /**
     * Phase 7 G1 — fill null hypothesis/findings/conclusion fields from the
     * PaperDraft's template fallbacks. Returns a new NarrationResult with the
     * gaps closed, or {@code null} when even the templates are null on the
     * matching draft fields. Package-private for unit testing.
     */
    static NarrationResult fillFromTemplates(PaperDraft draft, NarrationResult firstPass) {
        String hypothesis = firstPass.hypothesis();
        String findings   = firstPass.findings();
        String conclusion = firstPass.conclusion();
        if (hypothesis == null) hypothesis = draft.templateHypothesis();
        if (findings   == null) findings   = draft.rawFindings();
        if (conclusion == null) conclusion = draft.templateConclusion();
        if (hypothesis == null || findings == null || conclusion == null) {
            return null;
        }
        return new NarrationResult(
                hypothesis, findings, conclusion,
                firstPass.followUp(), firstPass.aiModel(),
                firstPass.confidenceScore(), firstPass.citedPaperIds(),
                firstPass.callPath(), firstPass.typedCitations(),
                firstPass.formalFindings(),
                firstPass.templateId(), firstPass.templateVersion(),
                firstPass.discussion(), firstPass.relatedWork());
    }

    /** Tool-calling professor path: audit → professor (tool-calling) → student revision. */
    private NarrationResult maybeCritiqueAndReviseWithTools(PaperDraft draft, NarrationResult firstPass,
                                                             List<String> callPath,
                                                             List<String> auditGapDimensions) {
        // Phase 7 F5.3 — accumulates tool-call trace from the professor stage so it
        // can be stamped onto whichever NarrationResult this function ultimately
        // returns. Stays empty if the professor stage never ran or failed early.
        java.util.List<com.emergentterrain.llm.ToolInvocation> profTrace = java.util.List.of();
        try {
            // Phase 1 — Quality audit (fast Ollama): identify gaps before professor is invoked
            String auditSummary = runQualityAudit(draft, firstPass, callPath, auditGapDimensions);

            // Pre-emptive gate check before the heaviest call in the pipeline:
            // professor tools can spend 5+ rounds × toolMaxTokens. Fall back to
            // first-pass narration if the budget tripped during audit.
            //
            // Phase 17 D3-ext — when the gate kills the professor stage AND the
            // first-pass narrator already committed a quantitative conclusion,
            // suppress the paper instead of publishing the unverified claim.
            // The fall-through to firstPass remains the default so honest
            // gate-skipped papers (no quantitative claim in the conclusion)
            // still land on disk with the no_methods stamp.
            //
            // Phase 17 gate-retry — when the queue is enabled and accepts the
            // draft, defer the publish entirely: return null so the existing
            // publishPaper(draft, null) handler discards cleanly (no DB row,
            // no disk write, no side-effects). The queue holds the cached
            // first-pass and re-dispatches when the resources gate recovers;
            // on give-up after max-attempts the queue publishes the cached
            // first-pass as NEEDS_REVISION with a gate-retry stamp.
            // Ordering note: the D3-ext suppression check wins over the
            // retry-defer when both apply, because a quantitatively
            // fabricated first-pass shouldn't be retried — the same
            // narrator on a healthy host would produce the same prose.
            if (gateClosed("professor", draft, callPath)) {
                if (gateResourcesQuantitativeSuppressEnabled
                        && conclusionHasQuantitativeClaim(firstPass.conclusion())) {
                    callPath.add("quantitative_fp→suppressed");
                    log.warn("LLM gate-skipped professor with quantitative first-pass "
                                    + "conclusion paperId={} — draft suppressed (Phase 17 "
                                    + "D3-ext; fabrication pattern from REVIEW_REPORT.md "
                                    + "post-hang refresh #1 P-559/568/570/573/575/580)",
                            draft.paperId());
                    return null;
                }
                if (gateRetryQueue != null
                        && gateRetryQueue.enqueue(draft, firstPass, callPath, "professor")) {
                    callPath.add("gate-retry:deferred");
                    log.info("LLM gate:resources:professor deferred to retry queue "
                                    + "paperId={} — publish suppressed pending retry",
                            draft.paperId());
                    return null;
                }
                return firstPass;
            }

            // Phase 2 — Professor (Groq tool-calling), directed by audit gaps
            long start = System.currentTimeMillis();
            boolean includeDynamic = "MATHEMATICS".equalsIgnoreCase(draft.domain())
                    || "INTERDISCIPLINARY".equalsIgnoreCase(draft.domain());
            // Lever 2 — advertise the search_literature citation tool when the flag
            // is on (the suite still omits it unless a corpus was wired).
            String toolsJson    = toolSuite.toolsJson(includeDynamic, citationToolEnabled);
            validateProfessorToolsJson(draft, toolsJson, includeDynamic, citationToolEnabled);
            // Phase 6 D1.3 — fetch curated literature for this (domain, topic).
            // Empty / null context means the prompts render their v1 variants
            // verbatim; non-empty context switches to v2-with-literature.
            String literatureContext = renderLiteratureContext(draft);
            // Phase 24 §24.4 — conditional SR nudge. Built only when the
            // pysr path is up AND the paper's domain is one where a
            // closed-form law plausibly underlies the sweep (physics,
            // materials, terrestrial, underwater). Null → slot renders
            // empty and the template reads as it did pre-phase-24.
            String srHint = buildSymbolicRegressHint(juliaMathClient, draft.domain());
            // Path B (2026-06-05) — frontier nudge for the tool-bearing stage. The seed
            // findings' "gather evidence with discover_*" steering only ever reaches the
            // tool-less first-pass narrator, which narrates the imperative into a passive
            // caveat ("any frontier value ... WOULD be computed") — trace-verified on
            // P-b72c-128: 6/6 frontier-framed papers, zero discover_* calls, because the
            // professor saw only that caveat while its audit gaps pushed it toward
            // in-range modesty. Same suggestive-not-prescriptive contract as srHint.
            String frontierHint = buildFrontierHint(draft);
            String hints = joinHints(srHint, frontierHint);
            String systemPrompt = buildProfessorSystemPrompt(
                    promptRegistry, auditSummary, literatureContext, hints)
                    + ((citationToolEnabled && literatureSearch != null) ? CITATION_GROUNDING_RULE : "");
            // Phase 31 §31.3 — index-first recall: append the compiled
            // cross-run lessons for this topic (context, not instructions).
            // The convergence case: a cramer_gap_ratio investigation recalls
            // the consolidated lesson over its prior dressings. Best-effort,
            // budget-capped, flag-gated OFF; empty block when nothing recalls.
            String recallBlock = (memoryRecallService != null && memoryRecallService.enabled())
                    ? memoryRecallService.recallBlock(draft.topicName()) : "";
            String userPrompt   = buildProfessorUserPrompt(
                    promptRegistry, draft, firstPass, literatureContext) + recallBlock;

            // Phase 5 C3 — collect per-paper sandbox-reject counts and surface them on
            // the persisted LLM Call Path. Reviewers can then tell whether a Findings
            // claim was actually computed or hand-waved after the sandbox denied the
            // narrator's code attempts.
            MathToolSuite.ToolMetrics metrics = new MathToolSuite.ToolMetrics();
            // Phase 20.5 — tag the professor tool-loop with role="professor-tools"
            // so RoleRouterPolicy can route per-role (e.g., avoid Groq llama for
            // this role specifically when daily-token cap is the active concern).
            // 2026-05-22 — wire metrics::reset as the router's onRetry hook so a
            // tool call that succeeds on handle A, then a network failure on
            // the same handle, then a retry on handle B doesn't leave the
            // success counter inflated from A's discarded attempt. Root cause
            // of the P-865/880/890 prof:ok-but-empty-trace divergence.
            com.emergentterrain.llm.ToolCallResponse profResponse =
                    com.emergentterrain.llm.LlmCallContext.withRole("professor-tools", () ->
                            router.callPrimaryWithTools(
                                    systemPrompt, userPrompt, toolsJson,
                                    toolSuite.buildExecutor(draft, metrics),
                                    metrics::reset));
            String professorSynthesis = profResponse.synthesis();
            // Phase 7 F5.3 — capture trace immediately so subsequent return paths
            // (gate closed, parse failed, exception) can still stamp the trace
            // onto the returned NarrationResult.
            profTrace = profResponse.trace();
            // Lever F (2026-06-10) — feed THIS candidate's professor trace into the per-paper
            // accumulator (union across all best-of-N candidates), so a verify_conjecture bound a
            // losing candidate proved HELD still reaches the discovery→pool write-back in publishPaper.
            // Only conjecture-investigation papers run that tool; gate to keep the accumulator lean.
            if (investigationTraceAccumulator != null
                    && draft.kind() == com.emergentterrain.research.PaperKind.CONJECTURE_INVESTIGATION) {
                investigationTraceAccumulator.add(draft.paperId(), profTrace);
            }
            // Phase 12 D7 — surface budget / TPD / tool-call-failure model
            // rejections that drove fallback. The router records each handle it
            // tried and rejected before reaching the one whose synthesis is in
            // hand. Stamp them BEFORE the prof:ok marker so reviewers reading
            // the persisted callPath see budget → success in left-to-right order
            // (matching the actual sequence in time).
            for (String skipped : profResponse.skippedModels()) {
                callPath.add("prof:budget_fallback:from=" + abbrev(skipped));
            }
            if (metrics.sandboxRejectCount() > 0) {
                callPath.add("sandbox_reject=" + metrics.sandboxRejectCount());
            }

            if (professorSynthesis == null || professorSynthesis.isBlank()) {
                callPath.add("prof:" + abbrev(router.primaryModel()) + ":blank→suppressed");
                log.warn("LLM professor tools: blank synthesis paperId={} ms={} — draft suppressed",
                        draft.paperId(), System.currentTimeMillis() - start);
                return null;
            }
            // 2026-06-06 review rec #3 — resample-on-empty (see the field javadoc).
            // Sits between the blank check and the D3 suppression so a rescued
            // draft flows through the unchanged D3 gate; an unrescued one reaches
            // it with the retries stamped for the call-path audit trail. Runs
            // within the draft's existing narration slot/budget; drain semantics
            // unchanged (in-flight drafts are expected to complete on the reserve).
            int emptyTraceResamples = 0;
            while ((metrics.totalSuccesses() == 0 || profTrace == null || profTrace.isEmpty())
                    && emptyTraceResamples < professorEmptyTraceRetries) {
                emptyTraceResamples++;
                callPath.add("prof:empty_trace_resample:n=" + emptyTraceResamples);
                log.info("LLM professor tools: empty trace — resampling ({}/{}) paperId={}",
                        emptyTraceResamples, professorEmptyTraceRetries, draft.paperId());
                metrics.reset();
                com.emergentterrain.llm.ToolCallResponse resample =
                        com.emergentterrain.llm.LlmCallContext.withRole("professor-tools", () ->
                                router.callPrimaryWithTools(
                                        systemPrompt, userPrompt, toolsJson,
                                        toolSuite.buildExecutor(draft, metrics),
                                        metrics::reset));
                if (resample.synthesis() != null && !resample.synthesis().isBlank()) {
                    professorSynthesis = resample.synthesis();
                    profTrace = resample.trace();
                    for (String skipped : resample.skippedModels()) {
                        callPath.add("prof:budget_fallback:from=" + abbrev(skipped));
                    }
                    // 2026-06-06 commit-review fix: the sandbox_reject stamp
                    // above described the DISCARDED draft's metrics; re-stamp
                    // the resample's count so the persisted call path matches
                    // the trace that actually published.
                    if (metrics.sandboxRejectCount() > 0) {
                        callPath.add("sandbox_reject:resample=" + metrics.sandboxRejectCount());
                    }
                }
                // A blank resample keeps the original synthesis; the loop exits
                // via the retry budget and the D3 gate below decides.
            }
            // Phase 12 D3 — widen the suppression gate. Phase 10's `>= 3 rejects
            // AND 0 successes` threshold only caught safety-rejected calls; it
            // missed the May 2026 refresh-#3 papers (P-a328-395, 402, 405, 411,
            // 422) where every tool call threw a *runtime* error ("index out of
            // bounds", "Cannot use array as scalar") rather than failing the
            // safety review. ToolMetrics increments `sandboxRejectCount` only on
            // safety rejections, so runtime-error papers accumulated zero rejects
            // AND zero successes — slipping past `totalRejects >= 3`.
            //
            // The new threshold is just `totalSuccesses == 0`. A paper that
            // produced any valid tool output still publishes; the only papers
            // suppressed are those with literally no successful computation —
            // which is exactly the fabrication signal refresh #3 named.
            // 2026-05-21 strengthening — P-a328-865/880/890 reached publish with
            // empty profTrace despite prof:ok being stamped. ToolMetrics and
            // profTrace are evidently diverging on some path (fallback chain
            // retaining metrics from a discarded handle?). Belt-and-suspenders:
            // suppress if EITHER counter is zero. Root cause for the divergence
            // is a separate follow-up; this defensive AND prevents the leak now.
            if (metrics.totalSuccesses() == 0 || profTrace == null || profTrace.isEmpty()) {
                callPath.add("prof:" + abbrev(router.primaryModel())
                        + ":empty_trace→suppressed");
                log.warn("LLM professor tools: 0 successes (rejects={}) traceSize={} paperId={} — "
                                + "draft suppressed (Phase 12 D3 + 2026-05-21 strengthening; "
                                + "patterns P-395/402/405/411/422, P-865/880/890)",
                        metrics.totalRejects(),
                        profTrace == null ? 0 : profTrace.size(),
                        draft.paperId());
                return null;
            }
            callPath.add("prof:" + abbrev(router.primaryModel()) + ":ok");
            log.info("LLM professor tools: synthesis_len={} paperId={} ms={}",
                    professorSynthesis.length(), draft.paperId(), System.currentTimeMillis() - start);

            // Phase 32 Wave 2 (32.3) — off-target corrective re-run. Sits AFTER
            // the D3 gate (trace verified non-empty) so a failed re-run can fall
            // back to the original synthesis without re-suppression risk; the
            // re-run uses its OWN ToolMetrics so the original's counters stay
            // intact on the keep-original path. If the re-run is still
            // off-target, the publish gate routes to NEEDS_REVISION exactly as
            // today — the gate stops being the last line and becomes the
            // pivot's backstop.
            int pivotReruns = 0;
            while (pivotEnabled && pivotReruns < pivotMaxRefinements
                    && draft.topicName() != null
                    && draft.topicName().startsWith("conjecture/")
                    && com.emergentterrain.research.ScoreCalibration
                            .hypothesisLhsUncovered(draft.topicName(), profTrace)
                    && pivotSpendAllowed()) {
                pivotReruns++;
                String preregLhs = draft.topicName()
                        .substring("conjecture/".length()).trim();
                callPath.add("prereg:pivot:offtarget_rerun:n=" + pivotReruns);
                log.info("LLM professor tools: off-target trace (preregistered lhs '{}' "
                                + "never verified) — corrective re-run {}/{} paperId={}",
                        preregLhs, pivotReruns, pivotMaxRefinements, draft.paperId());
                MathToolSuite.ToolMetrics pivotMetrics = new MathToolSuite.ToolMetrics();
                String pivotPrompt = userPrompt + buildPivotNote(preregLhs);
                com.emergentterrain.llm.ToolCallResponse pivotResponse =
                        com.emergentterrain.llm.LlmCallContext.withRole("professor-tools", () ->
                                router.callPrimaryWithTools(
                                        systemPrompt, pivotPrompt, toolsJson,
                                        toolSuite.buildExecutor(draft, pivotMetrics),
                                        pivotMetrics::reset));
                if (pivotResponse.synthesis() != null && !pivotResponse.synthesis().isBlank()
                        && pivotResponse.trace() != null && !pivotResponse.trace().isEmpty()
                        && pivotMetrics.totalSuccesses() > 0) {
                    professorSynthesis = pivotResponse.synthesis();
                    profTrace = pivotResponse.trace();
                    callPath.add(com.emergentterrain.research.ScoreCalibration
                            .hypothesisLhsUncovered(draft.topicName(), profTrace)
                            ? "prereg:pivot:still_offtarget"
                            : "prereg:pivot:on_target");
                } else {
                    // Keep the original synthesis/trace; the gate decides.
                    callPath.add("prereg:pivot:rerun_unusable");
                    break;
                }
            }
            // Lever F (2026-06-10 fix) — capture the FINAL professor trace, after the resample/pivot
            // passes may have REPLACED profTrace (lines above). The pivot re-run is exactly the
            // preregistered-LHS growth-anchor verification — where the tight bound is proven — so the
            // first-pass capture (right after the initial profResponse) misses it for a LOSING candidate
            // whose pivot found the bound. add() is additive + parse de-dups, so capturing both passes
            // is safe and gives the write-back the complete per-candidate verify set.
            if (investigationTraceAccumulator != null
                    && draft.kind() == com.emergentterrain.research.PaperKind.CONJECTURE_INVESTIGATION) {
                investigationTraceAccumulator.add(draft.paperId(), profTrace);
            }

            // 2026-06-05 review rec #1 — recognize a conjecture investigation's FITTED
            // CONSTANT (threaded via draft.param2). P-b72c-177 mined the ceiling 1.3128
            // on twin_vs_prime_sq — 0.6% from the Hardy–Littlewood 2C₂ = 1.32032 — and
            // published it as an arbitrary number because §26.4 recognition only ran on
            // dynamic-path refinement values. Same best-effort contract: a PrimaryHit
            // appends its citation line to the synthesis (the revision narrates it; the
            // cite-recognised handle stays mechanically verifiable); any failure passes
            // through silently.
            professorSynthesis = appendConjectureConstantRecognition(
                    professorSynthesis, draft);

            // Gate may have tripped during the professor tool loop. The professor's
            // synthesis is already complete and the tokens already spent; suppress
            // only the revision stream, keep firstPass as the published narrative.
            if (gateClosed("revision", draft, callPath)) {
                return firstPass.withToolCallTrace(profTrace);
            }

            // Phase 3 — Student revision incorporating professor's findings.
            // Phase 17 (analyses §2.4) — pin temperature to 0.0 for the
            // revision pass. The professor synthesis is already in hand and
            // the revision's job is to reformat it into the schema'd JSON,
            // not to introduce creative variation. Lower temperature ↓
            // rev:parse_failed rate AND ↓ chance the LLM invents new
            // numerics absent from the professor synthesis.
            //
            // Phase 17 (analyses §2.3) — also engage JSON-output mode so the
            // provider enforces response_format: json_object. The prompt
            // template's "Output ONLY valid JSON" instruction was being
            // violated by markdown code-fence wrappers, prose preamble,
            // and trailing comments — 9 of 23 papers in refresh #1 hit
            // rev:parse_failed→fp. Provider-side JSON mode shuts that
            // class of failure off entirely on OpenAI-compatible pools.
            long revStart = System.currentTimeMillis();
            StringBuilder revised = new StringBuilder();
            // Grounding rules (REVIEW_REPORT b72c4e10 §1 rec #4/#5) wrap the
            // base revision prompt; each is independently flag-gated and OFF by
            // default, so this is a no-op until flipped.
            // Phase 32 enforce — the manifest pre-check wraps OUTERMOST so its
            // dynamic VALUE PROVENANCE block (naming the specific unprovenanced
            // numbers in the working material) lands last and most salient.
            String revisionPromptBase = appendManifestMissRule(
                    appendDefinitionGrounding(
                            appendExecutedUnion(
                                    appendExecutedScopeGrounding(
                                            appendNoveltyPreservation(
                                                    buildRevisionPromptFromSynthesis(
                                                            promptRegistry, draft, firstPass, professorSynthesis,
                                                            literatureContext),
                                                    revisionNoveltyPreservationEnabled),
                                            executedScopeGroundingEnabled),
                                    executedScopeGroundingEnabled, profTrace),
                            definitionGroundingEnabled),
                    draft, firstPass, professorSynthesis, profTrace);
            // rec #4 follow-up — ground the [call_N] contract by appending the real
            // tagged trace, so the reviser cites real IDs instead of inventing them
            // (P-a328-993/994). Flag-gated for A/B against the instruction-only state.
            final String revisionUserPrompt = revisionTraceGroundingEnabled
                    ? revisionPromptBase + renderCitableTraceBlock(profTrace)
                    : revisionPromptBase;
            // Lever 2 — snapshot the professor trace as effectively-final for the
            // self-check executor; the revision's OWN check calls are not part of
            // this trace and must never be appended to it.
            final java.util.List<com.emergentterrain.llm.ToolInvocation> selfCheckTrace = profTrace;
            withNarrationSchema(() ->
                    com.emergentterrain.llm.LlmCallContext.withTemperature(0.0, () ->
                            com.emergentterrain.llm.LlmCallContext.withJsonOutputMode(() -> {
                                if (revisionSelfCheckEnabled) {
                                    // Revision self-check tool loop: the reviser calls the
                                    // read-only check_* tools on its draft and fixes FAILs
                                    // before emitting final JSON. Only the synthesis is used;
                                    // the loop's tool trace is discarded so the professor
                                    // trace that feeds publish-stage validators stays clean.
                                    com.emergentterrain.llm.ToolCallResponse revResp =
                                            com.emergentterrain.llm.LlmCallContext.withRole("revision-self-check", () ->
                                                    router.callPrimaryWithTools(
                                                            RevisionSelfCheckTools.SELF_CHECK_SYSTEM_PROMPT,
                                                            revisionUserPrompt,
                                                            selfCheckTools.toolsJson(),
                                                            selfCheckTools.buildExecutor(selfCheckTrace)));
                                    revised.append(revResp.synthesis() == null ? "" : revResp.synthesis());
                                    callPath.add("rev:self-check:n="
                                            + (revResp.trace() == null ? 0 : revResp.trace().size()));
                                } else {
                                    router.streamPrimary(revisionUserPrompt, revised::append);
                                }
                            })));
            // Phase 13 E2 — pre-parse repair of common malformed-JSON cases
            // (code-fence wrappers, prose preamble, trailing commas, unclosed
            // strings, unbalanced braces). REVIEW_REPORT refresh #1 §1 rec #3
            // reported `rev:parse_failed→fp` on 9 of 23 published papers
            // (39%); the lenient pre-parser repairs the majority of those
            // before Jackson sees the input. When the repair fires AND parse
            // succeeds, a `rev:parse_recovered:from=lenient` call-path stamp
            // marks the recovered subset for operator correlation.
            com.emergentterrain.research.LenientJsonParser.RepairResult repair =
                    com.emergentterrain.research.LenientJsonParser.repair(revised.toString());
            NarrationResult revParsed = parseNarrationResult(repair.text());
            if (repair.repaired() && revParsed.hypothesis() != null) {
                callPath.add("rev:parse_recovered:from=lenient");
            }
            // Merge revision with firstPass field-by-field rather than requiring all-or-nothing.
            // The student-revision LLM under the v2 Discussion+Related-Work template sometimes
            // emits only the changed sections (discussion, related_work) and omits hypothesis /
            // findings / conclusion when they're unchanged. Under the prior all-or-nothing check
            // this dropped the entire revision and the paper fell back to firstPass with a
            // `rev:parse_failed→fp` marker — 2/2 of the run's prof:ok papers hit it. The fix:
            // for each field, take the revision's value if present, otherwise the firstPass's.
            // A revision with NO recoverable content (everything null) is still treated as
            // parse_failed and falls back to firstPass.
            boolean revHasContent = revParsed.hypothesis()  != null
                                 || revParsed.findings()    != null
                                 || revParsed.conclusion()  != null
                                 || revParsed.discussion()  != null
                                 || revParsed.relatedWork() != null;
            if (revHasContent) {
                String hypothesis  = revParsed.hypothesis()  != null ? revParsed.hypothesis()  : firstPass.hypothesis();
                String findings    = revParsed.findings()    != null ? revParsed.findings()    : firstPass.findings();
                String conclusion  = revParsed.conclusion()  != null ? revParsed.conclusion()  : firstPass.conclusion();
                String discussion  = revParsed.discussion()  != null ? revParsed.discussion()  : firstPass.discussion();
                String relatedWork = revParsed.relatedWork() != null ? revParsed.relatedWork() : firstPass.relatedWork();
                com.emergentterrain.research.FollowUpParams followUp =
                        revParsed.followUp() != null ? revParsed.followUp() : firstPass.followUp();
                boolean revFullySupplied = revParsed.hypothesis() != null
                        && revParsed.findings() != null && revParsed.conclusion() != null;
                callPath.add(revFullySupplied
                        ? "rev:" + abbrev(router.primaryModel()) + ":ok"
                        : "rev:" + abbrev(router.primaryModel()) + ":partial→merged");
                log.info("LLM student revision done paperId={} ms={} mode={}", draft.paperId(),
                        System.currentTimeMillis() - revStart, revFullySupplied ? "full" : "partial-merge");
                NarrationResult revisedResult = new NarrationResult(
                        hypothesis, findings, conclusion,
                        followUp, router.primaryModel(),
                        revParsed.confidenceScore(), revParsed.citedPaperIds(),
                        null, List.of(), List.of(), null, null, discussion, relatedWork)
                        .withToolCallTrace(profTrace)
                        .withNumericClaims(revParsed.numericClaims());
                // Phase 11 C4-C5 — citation validator gate. Warn mode (default)
                // returns the input unchanged but stamps call_path. Downgrade
                // mode rewrites findings+conclusion to a NULL_RESULT preamble
                // when claims don't trace. Suppress mode returns null which
                // drops the paper via the same return-null pattern as the
                // Phase 10 all_tools_failed→suppressed gate.
                NarrationResult gated = applyCitationsGate(
                        revisedResult, profTrace, callPath, draft);
                if (gated == null) {
                    log.warn("LLM citation gate: paperId={} suppressed by citations-as-schema",
                            draft.paperId());
                    return null;
                }
                // Phase 7 F7.2 — run the multi-reviewer panel against the revised
                // narration + the trace, stamp the aggregated outcome on the result
                // so UniversityService.publishPaper can persist it. The panel
                // short-circuits to empty when disabled / no trace / no providers
                // resolved — the publish path is bit-identical to pre-F7.2 in
                // those cases.
                NarrationResult paneled =
                        runPanelAndStamp(draft, gated, callPath, auditGapDimensions);
                // Phase 17 P2 — when the panel produced actionable feedback
                // (consensus contradictions or specific recommendations),
                // run a focused second revision so the published paper
                // incorporates the panel's corrections. No-op when the
                // flag is off, the panel was empty, or the feedback is
                // too vague to act on. Validators in publishPaper still
                // run on the revised content as a backstop.
                return maybeApplyPanelRevision(draft, paneled, profTrace, callPath);
            }
            // Phase 14 F2a — diagnostic logging for the rev:parse_failed→fp
            // path. Refresh #2 §1 rec #2 named this stamp as one of the leaks
            // through which P-505 published with "0 successful sandbox calls"
            // despite the line 492 gate. Future refreshes can correlate the
            // WARN against published-paper trace size to confirm whether
            // additional suppression is needed here.
            callPath.add("rev:parse_failed→fp");
            log.warn("FALLBACK rev:parse_failed→fp paperId={} profTraceSize={} "
                            + "firstPassBlocks={} (Phase 14 F2a)",
                    draft.paperId(),
                    profTrace != null ? profTrace.size() : 0,
                    firstPass.findingsMap() != null ? firstPass.findingsMap().size() : 0);
            // Phase 17 P8a — raw-byte capture diagnostic. When the
            // operator flips `rev-parse-failed-byte-capture.enabled=true`,
            // a separate WARN line carries the first N chars of the
            // unrepaired revision stream so the dominant failure shape
            // (provider-side response_format ignored vs parser repair
            // gap) can be named. Default off; flip for one refresh, gather
            // ~10-30 samples, flip back. See REVIEW_REPORT.md refresh #3
            // §1 rec #6 — 33 occurrences in the latest run, JSON-output
            // mode (commit 2ac020d) didn't close it.
            if (revParseFailedByteCaptureEnabled) {
                String raw = revised.toString();
                int cap = Math.max(0, revParseFailedByteCaptureChars);
                String preview = raw.length() <= cap ? raw
                        : raw.substring(0, cap);
                log.warn("rev:parse_failed→fp BYTE_CAPTURE paperId={} rawLen={} "
                                + "preview={} (Phase 17 P8a)",
                        draft.paperId(), raw.length(),
                        // Sanitize newlines so the WARN remains single-line.
                        preview.replace('\n', '⏎').replace('\r', '¶'));
            }
        } catch (Exception e) {
            if (router.primaryFullyExhausted()) {
                java.time.Instant recovery = router.earliestPrimaryRecovery();
                callPath.add("prof:ALL_TPD→suppressed:recovery=" + recovery);
                log.error("LLM ALL GROQ MODELS TPD-EXHAUSTED — research paused. " +
                        "Earliest recovery: {}. paperId={} suppressed.", recovery, draft.paperId());
                return null;
            }
            // Phase 14 F2b — when the professor stage threw before producing
            // any successful tool calls, the metrics gate at line 492 is
            // bypassed. The exception leaves profTrace empty AND firstPass
            // carries un-validated multi-block fabricated prose. Suppress
            // when both conditions hold; otherwise fall through to the
            // legacy firstPass-fallback path.
            int profSuccesses = profTrace != null ? profTrace.size() : 0;
            int firstPassBlocks = firstPass.findingsMap() != null
                    ? firstPass.findingsMap().size() : 0;
            if (shouldSuppressOnProfessorException(profSuccesses, firstPassBlocks)) {
                callPath.add("prof:error→zero_successes→suppressed");
                log.warn("LLM professor exception with 0 successes AND multi-block firstPass "
                                + "paperId={} blocks={} cause={} — suppressed (Phase 14 F2b)",
                        draft.paperId(), firstPassBlocks, e.getMessage());
                return null;
            }
            callPath.add("prof:error→fp");
            log.warn("LLM professor tools path failed paperId={} profTraceSize={} "
                            + "firstPassBlocks={} cause={} — using first-pass narration (Phase 14 F2a)",
                    draft.paperId(), profSuccesses, firstPassBlocks, e.getMessage());
        }
        // Phase 7 F5.3 — stamp whatever trace accumulated before the failure so
        // the partial professor work survives in the audit record.
        NarrationResult fallback = firstPass.withToolCallTrace(profTrace);
        // Phase 7 F7.2 — even on the fallback path, run the panel if a trace
        // exists. A paper that hit an exception after the professor produced
        // useful tool results still benefits from honesty/completeness grading.
        return runPanelAndStamp(draft, fallback, callPath, auditGapDimensions);
    }

    /**
     * Fail fast on malformed professor-tool schemas so the logs carry a
     * paper-scoped root cause instead of only downstream retry warnings.
     */
    private void validateProfessorToolsJson(PaperDraft draft,
                                            String toolsJson,
                                            boolean includeDynamic,
                                            boolean includeLiterature) {
        try {
            JsonNode root = objectMapper.readTree(toolsJson);
            if (!root.isArray()) {
                throw new IllegalArgumentException("root is not a JSON array");
            }
        } catch (Exception e) {
            log.error("invalid tools schema for professor path paperId={} includeDynamic={} "
                            + "includeLiterature={} cause={}",
                    draft.paperId(), includeDynamic, includeLiterature, e.getMessage(), e);
            throw new IllegalArgumentException(
                    "invalid tools schema for professor path paperId=" + draft.paperId(), e);
        }
    }

    /**
     * Phase 7 F7.2 — run the multi-reviewer panel and stamp the outcome on the
     * narration. No-op when the panel service isn't wired, when the narration
     * carries no tool-call trace, or when the panel returns an empty outcome.
     * Failures are swallowed and logged so a panel-side bug never blocks a
     * paper from publishing.
     *
     * <p>Phase 7 F7.3 — when the panel's aggregated outcome shows ≥2 reviewers
     * agreed the LLM omitted a relevant tool result, the
     * {@code cherry_picked_evidence} audit-gap dimension is appended to
     * {@code auditGapDimensions}. UniversityService.publishPaper consumes that
     * dimension under the {@code audit-cherry-picked-rejects} flag.
     */
    /**
     * Phase 11 C5 — number of {@code uncited} numbers above which {@code suppress}
     * mode drops the paper even without any trace-mismatch. Three is the threshold
     * because below that the LLM is plausibly missing markers for derived /
     * meta-precision values that the validator's exclusion patterns didn't catch;
     * three or more is strong signal that the inline-citation rule isn't being
     * honoured at all.
     */
    private static final int CITATIONS_UNCITED_SUPPRESS_THRESHOLD = 3;

    /**
     * Paper-review 2026-05-22 — moderate-mismatch threshold for the
     * "result-bearing trace" exemption. When the citation validator
     * fires with {@code traceMismatch=true} BUT the total
     * unmatched+outOfTolerance count is at or below this threshold AND
     * the tool-call trace has ≥1 successful entry, the paper is routed
     * to {@code MANUAL_REVIEW} instead of being downgraded to
     * {@code NULL_RESULT}.
     *
     * <p>Drawn from the 2026-05-22 /paper-review on research/a3289005:
     * four of six papers were downgraded to NULL_RESULT despite their
     * tool calls producing hypothesis-answering results (P-a328-905
     * confirmation within CI; P-a328-900 single-λ partial sweep;
     * P-a328-901 cited a derived deviation; P-a328-902 refuted with
     * max=170 but had 7 unmatched claims — over threshold). Threshold
     * 5 keeps the genuine-fabrication cases (7+ unmatched) on the
     * NULL_RESULT path while rescuing papers with a few stray
     * prose-vs-trace gaps.
     *
     * <p>The exemption stamps {@code citations:moderate_mismatch:manual_review}
     * in the call path so operators can grep for papers rescued from
     * the downgrade. Tunable via
     * {@code app.research.citations.moderate-mismatch.threshold} — set to 0 to
     * disable the exemption entirely.
     */
    private static final int MODERATE_MISMATCH_THRESHOLD_DEFAULT = 5;

    /**
     * Paper-review 2026-05-22 — routing decisions emitted by the
     * citation gate. Pulled into its own enum so the decision function
     * (which contains the threshold logic) can be unit-tested in
     * isolation without standing up the full ResearchNarrationService.
     */
    enum CitationGateDecision {
        /** Publish unchanged (no failures, or "warn" mode). */
        PUBLISH,
        /** Publish with a {@code citations:manual_review} marker (uncited-only fail). */
        MANUAL_REVIEW,
        /** Publish with a {@code citations:moderate_mismatch:manual_review} marker
         *  (rescue: traceMismatch fired but mismatch count ≤ threshold). */
        MODERATE_MISMATCH_RESCUE,
        /** Rewrite findings + conclusion as NULL_RESULT and publish. */
        NULL_RESULT_DOWNGRADE,
        /** Drop the paper (return null). */
        SUPPRESS
    }

    /**
     * Paper-review 2026-05-22 — pure-function citation-gate router.
     * Given the validator outputs and the gate's configuration, decide
     * which routing path to take. Extracted from
     * {@link #applyCitationsGate} so the decision is unit-testable
     * without instantiating the full service.
     *
     * @param uncited                    {@code cv.uncitedCount()}
     * @param unmatched                  {@code cv.unmatchedClaimCount()}
     * @param outOfTolerance             {@code cv.outOfToleranceCount()}
     * @param traceSize                  {@code trace.size()} — 0 if null/empty
     * @param moderateMismatchThreshold  {@code app.research.citations.moderate-mismatch.threshold},
     *                                   0 disables the rescue branch
     * @param mode                       "warn" | "downgrade" | "suppress"
     */
    static CitationGateDecision decideCitationGate(
            int uncited, int unmatched, int outOfTolerance, int traceSize,
            int moderateMismatchThreshold, String mode) {
        boolean traceMismatch = unmatched > 0 || outOfTolerance > 0;
        boolean uncitedHeavy  = uncited >= CITATIONS_UNCITED_SUPPRESS_THRESHOLD;
        String normalized     = mode == null ? "warn" : mode.toLowerCase();

        switch (normalized) {
            case "suppress":
                if (traceMismatch || uncitedHeavy) return CitationGateDecision.SUPPRESS;
                return CitationGateDecision.PUBLISH;
            case "downgrade":
                if (traceMismatch) {
                    int total = unmatched + outOfTolerance;
                    boolean traceHasEvidence = traceSize > 0;
                    if (moderateMismatchThreshold > 0
                            && total <= moderateMismatchThreshold
                            && traceHasEvidence) {
                        return CitationGateDecision.MODERATE_MISMATCH_RESCUE;
                    }
                    return CitationGateDecision.NULL_RESULT_DOWNGRADE;
                }
                return CitationGateDecision.MANUAL_REVIEW;
            default:  // "warn" or unknown
                return CitationGateDecision.PUBLISH;
        }
    }

    /**
     * Phase 11 C4 — run the citation validator and apply the configured rollout
     * mode. Returns the (possibly downgraded) narration, or null when {@code
     * suppress} mode drops the paper.
     *
     * <p>Modes:
     * <ul>
     *   <li>{@code warn} (default) — stamp call_path, return narration unchanged
     *       regardless of failures.</li>
     *   <li>{@code downgrade} — when {@code unmatched > 0} OR {@code out_of_tolerance > 0},
     *       rewrite findings + conclusion to an honest NULL_RESULT preamble naming
     *       the unverifiable values. When only {@code uncited > 0}, stamp a
     *       {@code citations:manual_review} marker and keep publishing.</li>
     *   <li>{@code suppress} — same trigger condition as downgrade, but the paper
     *       is dropped (return null). Additionally drops papers with {@code uncited
     *       >= CITATIONS_UNCITED_SUPPRESS_THRESHOLD}.</li>
     * </ul>
     *
     * <p>No-op (return narration unchanged) when the validator bean isn't on
     * the context, when the trace is empty, or when every trace entry has a
     * null callId (pre-Phase-11 papers).
     */
    private NarrationResult applyCitationsGate(NarrationResult narration,
                                                java.util.List<com.emergentterrain.llm.ToolInvocation> trace,
                                                List<String> callPath,
                                                com.emergentterrain.research.PaperDraft draft) {
        if (citationValidator == null) return narration;
        if (trace == null || trace.isEmpty()) return narration;
        String paperId = draft == null ? null : draft.paperId();
        // Phase 21 §21.6 — sweep-param whitelist: PaperDraft.param1 and
        // param2 are the formulation-step's pre-registered sweep
        // coordinates. They land in the hypothesis prose verbatim ("starting
        // position of 17, scale of 7.4") via the prompt templates, so the
        // validator's substring-match-on-tool-output check was demoting
        // numerically-correct papers (3 of 5 in research/a3289005 refresh
        // #3) on a property the system never guaranteed. Exempting both
        // from the uncited check while leaving the trace-conformance checks
        // unchanged is the targeted fix.
        java.util.Set<Double> sweepWhitelist = sweepWhitelistFor(draft);
        com.emergentterrain.research.CitationValidator.CitationValidationResult cv;
        try {
            cv = citationValidator.validate(narration, trace, sweepWhitelist,
                    citationsReconcileBeforeNull);
        } catch (RuntimeException ex) {
            // Defensive — the validator must never break the pipeline. If a
            // regex or numeric parse blows up, stamp it and continue.
            callPath.add("citations:error");
            log.warn("CitationValidator: paperId={} failed with {}; continuing",
                    paperId, ex.getMessage());
            return narration;
        }

        if (!cv.hasFailures()) {
            // Phase 21 §21.6 / 2026-05-20 hypothesis exemption — when the
            // clean path was reached only because sweep params or hypothesis
            // values were exempted, surface the exemption counts so refresh
            // audits can distinguish "really clean" from "clean thanks to
            // the whitelist".
            StringBuilder ok = new StringBuilder("citations:ok");
            if (cv.exemptSweepCount() > 0) {
                ok.append(":exempt_sweep=").append(cv.exemptSweepCount());
            }
            if (cv.hypothesisParamExemptCount() > 0) {
                ok.append(":exempt_hyp=").append(cv.hypothesisParamExemptCount());
            }
            callPath.add(ok.toString());
            log.debug("CitationValidator: paperId={} clean exempt_sweep={} exempt_hyp={}",
                    paperId, cv.exemptSweepCount(), cv.hypothesisParamExemptCount());
            return narration;
        }

        String summary = "citations:uncited=" + cv.uncitedCount()
                + ":unmatched=" + cv.unmatchedClaimCount()
                + ":out_of_tolerance=" + cv.outOfToleranceCount()
                // Phase 21 §21.6 / 2026-05-20 — published alongside the
                // failure counts so operators can see how many would-be-uncited
                // values were exempted by the sweep-param whitelist
                // (exempt_sweep) or by the hypothesis-source rule (exempt_hyp)
                // on the same paper.
                + ":exempt_sweep=" + cv.exemptSweepCount()
                + ":exempt_hyp=" + cv.hypothesisParamExemptCount();
        // Paper-review 2026-05-22 — route through the pure decideCitationGate
        // function so the threshold/exemption logic is unit-testable in
        // isolation. Each branch below stamps its call-path marker and
        // logs at the appropriate level; the return value is the
        // (possibly transformed) narration or null on suppress.
        int traceSize = trace == null ? 0 : trace.size();
        String mode = citationsAsSchemaMode == null ? "warn" : citationsAsSchemaMode.toLowerCase();
        CitationGateDecision decision = decideCitationGate(
                cv.uncitedCount(), cv.unmatchedClaimCount(), cv.outOfToleranceCount(),
                traceSize, moderateMismatchThreshold, mode);
        callPath.add(summary);

        switch (decision) {
            case SUPPRESS -> {
                callPath.add("prof:" + abbrev(router.primaryModel())
                        + ":citations_failed→suppressed");
                log.warn("CitationValidator: paperId={} mode=suppress {} — paper "
                                + "dropped issues={}", paperId, summary, cv.issues());
                return null;
            }
            case NULL_RESULT_DOWNGRADE -> {
                // Room b72c4e10 Rec 5 — when the downgrade is driven entirely by
                // prose-side failures, try excising just the offending sentences
                // instead of nulling the whole paper. Only when substantive
                // findings survive; otherwise fall through to the whole-paper null.
                if (citationsSentenceQuarantineEnabled && cv.allFailuresAreProseSide()) {
                    NarrationResult quarantined = quarantineNarration(narration, cv);
                    if (quarantined != null) {
                        callPath.add("citations:sentence_quarantine");
                        log.warn("CitationValidator: paperId={} mode=downgrade {} — "
                                        + "sentence-quarantined {} prose-side value(s), publishing "
                                        + "surviving prose issues={}",
                                paperId, summary, cv.rejectedProseNumbers().size(), cv.issues());
                        return quarantined;
                    }
                    log.warn("CitationValidator: paperId={} sentence-quarantine left no "
                                    + "substantive findings — falling through to NULL_RESULT", paperId);
                }
                callPath.add("citations:downgrade→null_result");
                log.warn("CitationValidator: paperId={} mode=downgrade {} — routing to "
                                + "NULL_RESULT issues={}", paperId, summary, cv.issues());
                return downgradeNarrationToNull(narration, cv);
            }
            case MODERATE_MISMATCH_RESCUE -> {
                int total = cv.unmatchedClaimCount() + cv.outOfToleranceCount();
                callPath.add("citations:moderate_mismatch:manual_review");
                log.warn("CitationValidator: paperId={} mode=downgrade {} — "
                                + "moderate-mismatch exemption fired (unmatched+oot={} "
                                + "≤ threshold={}, trace n={}) — MANUAL_REVIEW stamped, "
                                + "publishing", paperId, summary, total,
                        moderateMismatchThreshold, traceSize);
                return narration;
            }
            case MANUAL_REVIEW -> {
                callPath.add("citations:manual_review");
                log.warn("CitationValidator: paperId={} mode=downgrade {} uncited-only — "
                                + "MANUAL_REVIEW stamped, publishing", paperId, summary);
                return narration;
            }
            default -> {  // PUBLISH (warn mode or below-threshold suppress)
                log.warn("CitationValidator: paperId={} mode={} {} issues={}",
                        paperId, mode, summary, cv.issues());
                return narration;
            }
        }
    }

    /**
     * Phase 21 §21.6 — build the sweep-parameter whitelist fed to
     * {@link com.emergentterrain.research.CitationValidator#validate(NarrationResult,
     * java.util.List, java.util.Set)}. Returns an empty set when the draft
     * is null or its params are unset (legacy / first-pass-only paths) so
     * the validator behaviour stays bit-identical to pre-21.6.
     *
     * <p>Both values are wrapped to {@code double} so the validator's
     * tolerance comparison works regardless of whether {@code param1} was
     * an int and {@code param2} a double on the draft.
     */
    static java.util.Set<Double> sweepWhitelistFor(com.emergentterrain.research.PaperDraft draft) {
        if (draft == null) return java.util.Set.of();
        java.util.Set<Double> out = new java.util.HashSet<>();
        if (draft.param1() != null) out.add(draft.param1().doubleValue());
        if (draft.param2() != null) out.add(draft.param2());
        return out;
    }

    /**
     * Phase 11 C5a — rewrite a paper's findings + conclusion to an honest
     * NULL_RESULT preamble that names the citation-validator failures.
     * Hypothesis is preserved (it's the question the paper set out to answer),
     * but findings and conclusion are replaced so the published prose
     * explicitly reports that direct computational evidence is missing.
     *
     * <p>Up to three example issues are included in the preamble; more would
     * bloat the prose without adding signal.
     */
    /**
     * Room b72c4e10 Rec 5 — sentence-level citation quarantine. Excise from the
     * findings/conclusion only the sentences carrying a prose-side rejected
     * number ({@link com.emergentterrain.research.CitationValidator.CitationValidationResult#rejectedProseNumbers()}),
     * leaving the rest of the paper intact, and stamp a short quarantine note on
     * the first findings block. Returns the edited narration, or {@code null} if
     * excision leaves no substantive findings (caller then falls through to the
     * whole-paper null). Hypothesis-sourced rejections are not expected here (the
     * validator exempts hypothesis numbers), so only findings/conclusion are edited.
     */
    static NarrationResult quarantineNarration(
            NarrationResult narration,
            com.emergentterrain.research.CitationValidator.CitationValidationResult cv) {
        // Group the verbatim rejected tokens by their source block.
        Map<String, java.util.List<String>> byBlock = new java.util.LinkedHashMap<>();
        for (var r : cv.rejectedProseNumbers()) {
            if (r.sourceLocation() == null) continue;
            byBlock.computeIfAbsent(r.sourceLocation(), k -> new java.util.ArrayList<>())
                    .add(r.text());
        }

        Map<Integer, String> srcFindings = NarrationResult.orderedFindingsMap(narration.findingsMap());
        Map<Integer, String> newFindings = new java.util.LinkedHashMap<>();
        boolean anySubstantiveFinding = false;
        for (Map.Entry<Integer, String> e : srcFindings.entrySet()) {
            String loc = "findings:" + e.getKey();
            String block = e.getValue();
            java.util.List<String> toks = byBlock.get(loc);
            String kept = (toks == null || toks.isEmpty())
                    ? block
                    : com.emergentterrain.research.CitationValidator.quarantineSentences(block, toks);
            if (kept != null && !kept.isBlank()) {
                newFindings.put(e.getKey(), kept);
                anySubstantiveFinding = true;
            }
        }
        if (!anySubstantiveFinding) {
            return null;   // nothing of substance survived — let the caller null the paper
        }

        // Excise the conclusion's offending sentences too (may end up blank).
        java.util.List<String> concToks = byBlock.get("conclusion");
        String newConclusion = (concToks == null || concToks.isEmpty())
                ? narration.conclusion()
                : com.emergentterrain.research.CitationValidator.quarantineSentences(
                        narration.conclusion(), concToks);

        // Stamp a transparency note on the lowest-keyed findings block so a
        // reader knows prose was removed and why.
        Integer firstKey = newFindings.keySet().iterator().next();
        String note = "[Note: " + cv.totalFailureCount()
                + (cv.totalFailureCount() == 1 ? " sentence" : " sentences")
                + " citing untraceable value(s) "
                + cv.rejectedProseNumbers().stream()
                        .map(com.emergentterrain.research.CitationValidator.RejectedProseNumber::text)
                        .distinct().limit(5)
                        .collect(java.util.stream.Collectors.joining(", "))
                + " removed in review; remaining findings are tool-traced.]";
        newFindings.put(firstKey, newFindings.get(firstKey) + " " + note);

        return new NarrationResult(
                narration.hypothesis(),
                newFindings,
                newConclusion,
                narration.followUp(),
                narration.aiModel(),
                narration.confidenceScore(),
                narration.citedPaperIds(),
                narration.callPath(),
                narration.typedCitations(),
                narration.formalFindings(),
                narration.templateId(),
                narration.templateVersion(),
                narration.discussion(),
                narration.relatedWork(),
                narration.auditGapDimensions(),
                narration.toolCallTrace(),
                narration.panelOutcome(),
                narration.numericClaims());
    }

    static NarrationResult downgradeNarrationToNull(
            NarrationResult narration,
            com.emergentterrain.research.CitationValidator.CitationValidationResult cv) {
        StringBuilder preamble = new StringBuilder();
        preamble.append("NULL_RESULT — citation validator flagged ")
                .append(cv.totalFailureCount())
                .append(cv.totalFailureCount() == 1 ? " numeric value" : " numeric values")
                .append(" in this draft that could not be traced to direct tool-call output. ")
                .append("No result is claimed on the basis of those values; the computation itself "
                        + "is recorded in the Methods trace below.");
        if (!cv.issues().isEmpty()) {
            preamble.append(" Specifically: ");
            preamble.append(cv.issues().stream()
                    .limit(3)
                    .collect(java.util.stream.Collectors.joining("; ")));
            preamble.append(".");
        }
        // Rec 2 (room b72c4e10): this downgrade runs on a POPULATED trace (citation validation
        // compares prose against the tool output), so a "not verified / null result" stamp would
        // contradict the Methods trace and trip DirectionOfFindingValidator's null-with-populated-
        // trace check. Frame the abstention around the untraceable PROSE numbers, not around the
        // computation having not run — keep the wording clear of CONCLUSION_NULL phrases.
        String conclusion = "Some reported numbers could not be traced to the tool-call output "
                + "(see findings preamble), so no result is claimed here; the underlying "
                + "computation is recorded in the Methods trace.";
        return new NarrationResult(
                narration.hypothesis(),
                Map.of(1, preamble.toString()),
                conclusion,
                narration.followUp(),
                narration.aiModel(),
                narration.confidenceScore(),
                narration.citedPaperIds(),
                narration.callPath(),
                narration.typedCitations(),
                narration.formalFindings(),
                narration.templateId(),
                narration.templateVersion(),
                narration.discussion(),
                narration.relatedWork(),
                narration.auditGapDimensions(),
                narration.toolCallTrace(),
                narration.panelOutcome(),
                narration.numericClaims());
    }

    private NarrationResult runPanelAndStamp(PaperDraft draft, NarrationResult narration,
                                              List<String> callPath,
                                              List<String> auditGapDimensions) {
        if (reviewPanelService == null || narration == null) return narration;
        if (!reviewPanelService.isActive()) return narration;
        if (narration.toolCallTrace() == null || narration.toolCallTrace().isEmpty()) {
            return narration;
        }
        try {
            int configuredSize = reviewPanelService.panelSize();
            com.emergentterrain.research.PanelOutcome outcome =
                    reviewPanelService.runPanel(draft, narration);
            int got = outcome.reviewerCount();
            if (got == 0) {
                // Phase 7 F7.4 — no reviewers responded (all quota-blocked, all dropped
                // on parse fail, or all RPC-errored). Stamp `panel:skip:unavailable`
                // and continue — the paper still publishes, just without the panel
                // signal. No new failure mode.
                callPath.add("panel:skip:unavailable");
                return narration;
            }
            callPath.add(buildPanelCallPathStamp(got, configuredSize, outcome.agreementScore()));
            // Phase 7 F7.3 — cherry-picked-evidence audit gap when consensus exists.
            if (auditGapDimensions != null
                    && !outcome.consensusOmittedRelevant().isEmpty()
                    && !auditGapDimensions.contains("cherry_picked_evidence")) {
                auditGapDimensions.add("cherry_picked_evidence");
                log.info("F7.3 cherry_picked_evidence: paperId={} omitted_calls={}",
                        draft != null ? draft.paperId() : "?",
                        outcome.consensusOmittedRelevant());
            }
            // Phase 10 — suppress papers that limp through with a single
            // reviewer when the panel was configured for ≥2. P-a328-360 went
            // out with `panel:n=1:degraded:from=4` after `rev:parse_failed→fp`
            // and contained five blocks of fabricated Reuss-ratio output that
            // the single reviewer happily co-signed. One reviewer cannot
            // produce useful inter-rater agreement — `agreementScore` defaults
            // to 1.00 trivially — so the panel signal is non-informative.
            // Treat this as MANUAL_REVIEW: suppress the paper, log loudly,
            // let the upstream draft queue see the rejection.
            if (got == 1 && configuredSize >= 2) {
                callPath.add("panel:n=1:degraded:MANUAL_REVIEW→suppressed");
                log.warn("ReviewPanelService: paperId={} panel n=1 of configured {} — "
                                + "single-reviewer agreement is non-informative; draft "
                                + "suppressed for MANUAL_REVIEW per REVIEW_REPORT.md "
                                + "refresh #3 (P-360 fabrication pattern).",
                        draft != null ? draft.paperId() : "?", configuredSize);
                return null;
            }
            // Phase 12 D4 — n ∈ {2, 3} of a configured 4 carries lower confidence
            // than a full panel but is not non-informative the way n=1 is.
            // REVIEW_REPORT refresh #3 §1 rec #6 named four Reject papers
            // (P-417, P-419, P-428, P-431) that carried PUBLISHED despite
            // degraded panels; D4 demotes them to NEEDS_REVISION so the lower
            // confidence is surfaced. The paper still publishes — peer score,
            // call path, and panel outcome are preserved — but the status
            // reflects what actually happened in review.
            if (panelDegradationDemoteEnabled
                    && got >= 2 && got < configuredSize) {
                if (panelDegradationAgreementRelaxEnabled
                        && degradedPanelClearedForPublish(
                            outcome, panelDegradationAgreementFloor, panelGradeFloorThreshold)) {
                    // rec #7 — a degraded panel that converged strongly and
                    // clears the grade floor carries a real signal; the shortfall
                    // is reviewer-acquisition infra, not quality. Keep PUBLISHED;
                    // the orthogonal grade-floor gate below still guards quality.
                    callPath.add("panel:n=" + got + ":degraded:agreement_ok→published");
                    log.info("ReviewPanelService: paperId={} panel n={} of configured {} but "
                                    + "agreement={} ≥ {} and grades clear floor — keeping PUBLISHED "
                                    + "(Phase 12 D4 rec#7 relaxation).",
                            draft != null ? draft.paperId() : "?", got, configuredSize,
                            String.format("%.2f", outcome.agreementScore()),
                            String.format("%.2f", panelDegradationAgreementFloor));
                } else {
                    callPath.add("panel:n=" + got + ":degraded:demoted_to_needs_revision");
                    log.info("ReviewPanelService: paperId={} panel n={} of configured {} — "
                                    + "demoting PUBLISHED → NEEDS_REVISION (Phase 12 D4).",
                            draft != null ? draft.paperId() : "?", got, configuredSize);
                }
            }
            // Phase 17 panel-grade-floor — orthogonal to D4. Even a full panel
            // can return rock-bottom grades AND specific consensus
            // contradictions (P-a328-593 in refresh #1: honesty=0.10,
            // completeness=0.00, rigor=0.10, 2 reviewer-agreed
            // contradictions on a 1147× unit error). D4 cannot catch those
            // because the panel was full (n = configured); the grade-floor
            // gate fires on the grades themselves. Both gates can fire on
            // the same paper; both produce the same demote semantics and
            // both stamps end in `:demoted_to_needs_revision` so the
            // publishPaper ladder catches either.
            if (panelGradeFloorEnabled
                    && shouldDemoteByPanelGradeFloor(outcome, panelGradeFloorThreshold)) {
                double avg = honestyCompletenessRigorAverage(outcome);
                callPath.add(String.format("panel:grade_floor=%.2f:demoted_to_needs_revision", avg));
                log.info("ReviewPanelService: paperId={} panel grade-floor avg(h,c,r)={} < {} "
                                + "with {} contradiction(s) — demoting PUBLISHED → NEEDS_REVISION "
                                + "(Phase 17 panel-grade-floor).",
                        draft != null ? draft.paperId() : "?",
                        String.format("%.2f", avg),
                        String.format("%.2f", panelGradeFloorThreshold),
                        outcome.consensusContradictions().size());
            }
            return narration.withPanelOutcome(outcome);
        } catch (Exception e) {
            log.warn("LLM panel review path failed paperId={}: {}",
                    draft != null ? draft.paperId() : "?", e.getMessage());
            callPath.add("panel:error");
            return narration;
        }
    }

    /**
     * Phase 17 P2 — panel-recommendations-as-feedback. When the panel returned
     * actionable signal (consensus contradictions or specific recommendations),
     * run a focused second revision through the student model with the panel's
     * feedback in the prompt. Replace the narration's findings/conclusion with
     * the revised content, preserving hypothesis + toolCallTrace + panelOutcome.
     *
     * <p>Returns the input narration unchanged when:
     * <ul>
     *   <li>flag is off ({@code app.research.panel-revision.enabled=false})</li>
     *   <li>narration or its panelOutcome is null/empty</li>
     *   <li>feedback is not actionable
     *       (per {@link #isPanelFeedbackActionable})</li>
     *   <li>SystemResourcesGate is closed (avoid token spend during host
     *       pressure)</li>
     *   <li>parse failure, blank stream, or no-change revision</li>
     * </ul>
     */
    private NarrationResult maybeApplyPanelRevision(PaperDraft draft,
                                                     NarrationResult narration,
                                                     List<com.emergentterrain.llm.ToolInvocation> profTrace,
                                                     List<String> callPath) {
        if (!panelRevisionEnabled || narration == null) return narration;
        com.emergentterrain.research.PanelOutcome outcome = narration.panelOutcome();
        if (outcome == null || outcome.isEmpty()) return narration;
        if (!isPanelFeedbackActionable(outcome)) {
            callPath.add("panel_revision:skip:not_actionable");
            return narration;
        }
        if (systemResourcesGate != null && !systemResourcesGate.isOpen()) {
            callPath.add("panel_revision:gate_skipped");
            log.info("LLM panel-revision: paperId={} skipped (resources gate not open)",
                    draft != null ? draft.paperId() : "?");
            return narration;
        }
        long start = System.currentTimeMillis();
        try {
            // rec #4 follow-up — ground the citation constraints with the real
            // tagged trace when the flag is on (profTrace is authoritative here).
            String traceBlock = revisionTraceGroundingEnabled
                    ? renderCitableTraceBlock(profTrace) : "";
            String prompt = buildPanelRevisionPrompt(narration, outcome, traceBlock);
            StringBuilder buf = new StringBuilder();
            // Phase 17 (analyses §2.4) — temperature 0.0 for the panel
            // revision: the constraints (no new numerics, fix citations,
            // pin hypothesis) leave the model no creative slack worth
            // exercising, and a lower temperature shrinks the chance of
            // schema-violating output.
            //
            // Phase 17 (analyses §2.3) — also engage JSON-output mode so
            // the provider enforces response_format: json_object. The
            // prompt's "Return ONLY valid JSON" line was sometimes
            // ignored by the previous-temperature default; JSON mode
            // makes the provider drop any non-JSON prose.
            withNarrationSchema(() ->
                    com.emergentterrain.llm.LlmCallContext.withTemperature(0.0, () ->
                            com.emergentterrain.llm.LlmCallContext.withJsonOutputMode(() ->
                                    router.streamPrimary(prompt, buf::append))));
            com.emergentterrain.research.LenientJsonParser.RepairResult repair =
                    com.emergentterrain.research.LenientJsonParser.repair(buf.toString());
            NarrationResult parsed = parseNarrationResult(repair.text());
            if (parsed.hypothesis() == null && parsed.findings() == null
                    && parsed.conclusion() == null) {
                callPath.add("panel_revision:parse_failed");
                log.warn("LLM panel-revision: paperId={} parse_failed ms={}",
                        draft != null ? draft.paperId() : "?",
                        System.currentTimeMillis() - start);
                return narration;
            }
            // Hypothesis is pinned to the original (pre-registration). Findings
            // and conclusion may be revised. Discussion / related-work optional.
            String newFindings   = parsed.findings()   != null ? parsed.findings()   : narration.findings();
            String newConclusion = parsed.conclusion() != null ? parsed.conclusion() : narration.conclusion();
            if (isNoChangeRevision(narration.findings(), newFindings,
                    narration.conclusion(), newConclusion)) {
                callPath.add("panel_revision:no_change");
                log.info("LLM panel-revision: paperId={} produced no_change ms={}",
                        draft != null ? draft.paperId() : "?",
                        System.currentTimeMillis() - start);
                return narration;
            }
            callPath.add("panel_revision:ok");
            log.info("LLM panel-revision: paperId={} ms={} contradictions={} recs={}",
                    draft != null ? draft.paperId() : "?",
                    System.currentTimeMillis() - start,
                    outcome.consensusContradictions().size(),
                    outcome.recommendations().size());
            // Preserve hypothesis (pre-registration), trace, panel outcome,
            // audit-gap dimensions, and template provenance. Replace
            // findings/conclusion + optionally discussion/related-work.
            NarrationResult revised = new NarrationResult(
                    narration.hypothesis(),
                    newFindings,
                    newConclusion,
                    narration.followUp(),
                    narration.aiModel(),
                    narration.confidenceScore(),
                    narration.citedPaperIds(),
                    null, List.of(), List.of(), null, null,
                    parsed.discussion()  != null ? parsed.discussion()  : narration.discussion(),
                    parsed.relatedWork() != null ? parsed.relatedWork() : narration.relatedWork())
                    .withToolCallTrace(profTrace)
                    .withPanelOutcome(outcome)
                    .withAuditGapDimensions(narration.auditGapDimensions())
                    .withNumericClaims(narration.numericClaims());
            return revised;
        } catch (Exception e) {
            callPath.add("panel_revision:error");
            log.warn("LLM panel-revision: paperId={} error ms={} cause={} — keeping original",
                    draft != null ? draft.paperId() : "?",
                    System.currentTimeMillis() - start, e.getMessage());
            return narration;
        }
    }

    /**
     * Phase 17 P2 — is the panel's feedback specific enough to act on?
     * Triggers when ≥1 consensus contradiction (≥2 reviewers agreed) OR
     * ≥2 deduplicated recommendations exist. Vague panels (only grades,
     * one wishy-washy recommendation) don't fire.
     *
     * <p>Visibility is package-private so tests can pin the trigger
     * without standing up a full service.
     */
    static boolean isPanelFeedbackActionable(com.emergentterrain.research.PanelOutcome outcome) {
        if (outcome == null || outcome.isEmpty()) return false;
        int contradictions = outcome.consensusContradictions() != null
                ? outcome.consensusContradictions().size() : 0;
        int recommendations = outcome.recommendations() != null
                ? outcome.recommendations().size() : 0;
        return contradictions >= 1 || recommendations >= 2;
    }

    /**
     * Phase 17 P2 — heuristic "did the revision actually change anything?"
     * check. A panel-revision that returns text identical to (or trivially
     * close to) the input wastes the validator's time downstream and
     * should be marked {@code no_change} rather than {@code ok}. We use a
     * normalized-content comparison: if findings AND conclusion are
     * unchanged when whitespace-collapsed, treat as no_change. Otherwise
     * accept the revision (downstream validators catch quality regressions).
     *
     * <p>Package-private for tests.
     */
    static boolean isNoChangeRevision(String oldFindings, String newFindings,
                                       String oldConclusion, String newConclusion) {
        return safeEquals(normalize(oldFindings), normalize(newFindings))
                && safeEquals(normalize(oldConclusion), normalize(newConclusion));
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Phase 17 P2 — build the panel-revision prompt. Static + package-private
     * for unit testing without standing up a router. The prompt explicitly
     * forbids new numerics not in the trace, forbids inventing new tool
     * calls, and pins the hypothesis to the original.
     */
    static String buildPanelRevisionPrompt(NarrationResult narration,
                                            com.emergentterrain.research.PanelOutcome outcome) {
        return buildPanelRevisionPrompt(narration, outcome, "");
    }

    /**
     * rec #4 follow-up (2026-05-29) — {@code traceBlock} is the pre-rendered
     * {@code [call_N]}-tagged trace (see {@link #renderCitableTraceBlock}),
     * appended before the OUTPUT FORMAT section so the citation constraints
     * below actually have the trace to reference. Pass {@code ""} (or use the
     * 2-arg overload) to retain the prior instruction-only behaviour.
     */
    static String buildPanelRevisionPrompt(NarrationResult narration,
                                            com.emergentterrain.research.PanelOutcome outcome,
                                            String traceBlock) {
        StringBuilder p = new StringBuilder();
        p.append("You wrote a research paper that a multi-reviewer panel has just evaluated. ")
                .append("The panel agreed on specific issues. Your task is a FOCUSED REVISION ")
                .append("that addresses ONLY the panel's findings — not a rewrite.\n\n");

        p.append("=== ORIGINAL HYPOTHESIS (pin — do not change) ===\n")
                .append(safeText(narration.hypothesis())).append("\n\n");

        p.append("=== ORIGINAL FINDINGS ===\n")
                .append(safeText(narration.findings())).append("\n\n");

        p.append("=== ORIGINAL CONCLUSION ===\n")
                .append(safeText(narration.conclusion())).append("\n\n");

        List<com.emergentterrain.research.PanelReview.Contradiction> contradictions =
                outcome.consensusContradictions();
        if (contradictions != null && !contradictions.isEmpty()) {
            p.append("=== PANEL CONSENSUS CONTRADICTIONS (≥2 reviewers agreed) ===\n");
            for (com.emergentterrain.research.PanelReview.Contradiction c : contradictions) {
                p.append("- At tool call ").append(c.traceIdx()).append(": ")
                        .append(safeText(c.vsProse())).append("\n");
            }
            p.append("\n");
        }

        List<String> recs = outcome.recommendations();
        if (recs != null && !recs.isEmpty()) {
            p.append("=== PANEL RECOMMENDATIONS ===\n");
            for (String r : recs) {
                p.append("- ").append(safeText(r)).append("\n");
            }
            p.append("\n");
        }

        p.append("=== REVISION CONSTRAINTS (binding) ===\n")
                .append("1. The Hypothesis is pinned: emit it unchanged in the output.\n")
                .append("2. Do NOT introduce numeric values that are not in the original tool-call trace.\n")
                .append("3. Do NOT invent new tool calls or new computation results.\n")
                .append("4. Where the panel said a [call_N] citation doesn't exist or contradicts ")
                .append("the prose, either replace it with the correct citation from the trace or ")
                .append("remove the unsupported claim entirely.\n")
                // rec #4 (2026-05-28) — the narrator routinely invented [call_N]
                // indices beyond the trace (P-a328-993 cited call_3/4/5/18/19) and
                // cited FAILED calls as data (P-a328-994 cited the errored call_1/
                // call_5). Spell out the citation contract explicitly so the
                // revision doesn't reintroduce hallucinated or failed-call markers.
                .append("5. Cite a tool result ONLY as [call_N] where call_N is a call you actually ")
                .append("made in this session AND it returned a usable result. Never cite a call ")
                .append("index higher than the number of calls in the trace, and never cite a call ")
                .append("that errored or was REJECTED by safety review — treat those as absent.\n")
                .append("6. Keep prose factual and concise; do not pad with boilerplate.\n\n");

        if (traceBlock != null && !traceBlock.isEmpty()) {
            p.append(traceBlock).append("\n");
        }

        p.append("=== OUTPUT FORMAT ===\n")
                .append("Return ONLY valid JSON with this exact schema:\n")
                .append("{\"hypothesis\": \"...\", \"findings\": \"...\", \"conclusion\": \"...\"}\n")
                .append("No code fences, no preamble, no trailing prose.\n");

        return p.toString();
    }

    private static String safeText(String s) {
        return s == null ? "" : s;
    }

    /**
     * Phase 7 F7.4 — build the panel call-path stamp from observed vs configured
     * reviewer counts. Visibility is package-private so tests can verify the
     * exact string format without standing up a full {@link ResearchNarrationService}.
     */
    static String buildPanelCallPathStamp(int got, int configuredSize, double agreementScore) {
        String agreement = String.format("%.2f", agreementScore);
        if (got < configuredSize) {
            return "panel:n=" + got + ":degraded:from=" + configuredSize
                    + ":agreement=" + agreement;
        }
        return "panel:n=" + got + ":agreement=" + agreement;
    }

    /**
     * Phase 17 panel-grade-floor demote — average of honesty +
     * completeness + rigor from the panel's aggregated (median) grades.
     * Novelty is excluded because textbook topics legitimately score
     * novelty=0 without indicating poor quality (P-595 Basquin is the
     * canonical example: honesty=1.00, completeness=1.00, rigor=1.00,
     * novelty=0.00 — clearly a good-quality paper). The grade-floor
     * gate operates on the three quality axes that signal "the paper
     * is supported by its trace."
     *
     * <p>Package-private for unit tests.
     */
    static double honestyCompletenessRigorAverage(
            com.emergentterrain.research.PanelOutcome outcome) {
        if (outcome == null || outcome.aggregateGrades() == null) return 0.0;
        com.emergentterrain.research.PanelReview.Grades g = outcome.aggregateGrades();
        return (g.honesty() + g.completeness() + g.rigor()) / 3.0;
    }

    /**
     * Phase 17 panel-grade-floor demote — should the paper be demoted
     * PUBLISHED → NEEDS_REVISION based on panel grades? Fires when:
     * <ol>
     *   <li>The panel's median(honesty, completeness, rigor) is strictly
     *       below {@code threshold} (default 0.30).</li>
     *   <li>AND the panel surfaced at least one consensus contradiction
     *       (≥2 reviewers agreed on a specific prose-vs-trace conflict
     *       at a trace index).</li>
     * </ol>
     *
     * <p>Both clauses are required to avoid false-positives:
     * <ul>
     *   <li>Low grades alone could be a noisy or harsh panel on a
     *       legitimate paper.</li>
     *   <li>One contradiction alone might be a minor issue the
     *       panel-revision pass can fix.</li>
     *   <li>Both together signal "the reviewers agreed the paper is
     *       weak <em>and</em> have specific evidence why" — the
     *       P-a328-593 shape.</li>
     * </ul>
     *
     * <p>Package-private for unit tests.
     */
    static boolean shouldDemoteByPanelGradeFloor(
            com.emergentterrain.research.PanelOutcome outcome, double threshold) {
        if (outcome == null || outcome.isEmpty()) return false;
        double avg = honestyCompletenessRigorAverage(outcome);
        if (avg >= threshold) return false;
        return outcome.consensusContradictions() != null
                && !outcome.consensusContradictions().isEmpty();
    }

    /**
     * Phase 12 D4 relaxation (research/a3289005 review rec #7) — may a degraded
     * panel (n ∈ {2, 3} of configured) keep its PUBLISHED status instead of being
     * demoted on reviewer count alone? Yes, when both hold:
     * <ol>
     *   <li>agreement ≥ {@code agreementFloor} — the small panel converged, so
     *       its signal is informative (unlike n=1, whose agreement is trivially
     *       1.00 and which is suppressed separately); and</li>
     *   <li>median(honesty, completeness, rigor) ≥ {@code gradeFloorThreshold} —
     *       the reviewers did not judge the paper weak.</li>
     * </ol>
     *
     * <p>This shifts the demotion rationale for degraded panels from reviewer
     * <em>count</em> (an infrastructure artifact when providers fail to seat
     * reviewers) to <em>quality</em>. A low-agreement or low-grade degraded panel
     * still demotes here, and the orthogonal grade-floor gate still fires
     * independently, so no quality hole opens.
     *
     * <p>Package-private for unit tests.
     */
    static boolean degradedPanelClearedForPublish(
            com.emergentterrain.research.PanelOutcome outcome,
            double agreementFloor, double gradeFloorThreshold) {
        if (outcome == null || outcome.isEmpty()) return false;
        if (outcome.agreementScore() < agreementFloor) return false;
        return honestyCompletenessRigorAverage(outcome) >= gradeFloorThreshold;
    }

    /**
     * Phase 14 F2b — decide whether to suppress the professor-stage exception
     * fallback. Returns true when the professor produced zero successful tool
     * calls AND the first-pass narration carries multi-block findings (the
     * "the narrator emitted N Computation blocks for computations that never
     * ran" pattern). Visibility is package-private so tests can pin the
     * decision logic without standing up a full
     * {@link ResearchNarrationService}.
     *
     * <p>Why the multi-block guard: single-block fabricated firstPass is
     * structurally indistinguishable from a legitimate single-computation
     * narration that lost its trace to an exception, so suppressing on a
     * 1-block paper would over-fire. The Five-Computation cluster the report
     * names is multi-block by definition.
     */
    static boolean shouldSuppressOnProfessorException(int profSuccesses,
                                                       int firstPassBlocks) {
        return profSuccesses == 0 && firstPassBlocks > 1;
    }

    private static final java.util.regex.Pattern CALL_CITATION_PATTERN =
            java.util.regex.Pattern.compile("\\[call_\\d+\\]");
    private static final java.util.regex.Pattern COMPUTATION_REF_PATTERN =
            java.util.regex.Pattern.compile("(?i)\\bComputation\\s+\\d+\\b");
    private static final java.util.regex.Pattern DIGIT_PATTERN =
            java.util.regex.Pattern.compile("\\d");

    /**
     * Phase 17 D3-ext — does the first-pass conclusion contain a quantitative
     * claim that requires a tool-call result to back it? Returns true when,
     * after stripping {@code [call_N]} citation markers and "Computation N"
     * structural references, any decimal digit remains in the conclusion.
     *
     * <p>Rationale: a paper whose professor stage was killed by the resources
     * gate has no methods trace. An honest first-pass conclusion in that case
     * is qualitative — e.g. "the hypothesis remains untested in this
     * submission." Any specific number (percentage, magnitude with unit,
     * threshold, ratio) in the conclusion is by definition not derivable
     * from the (empty) trace and is the fabrication signal D3-ext targets.
     *
     * <p>Visibility is package-private so tests can pin the detector without
     * standing up a full {@link ResearchNarrationService} (parallel to
     * {@link #shouldSuppressOnProfessorException(int, int)}).
     */
    static boolean conclusionHasQuantitativeClaim(String conclusion) {
        if (conclusion == null || conclusion.isBlank()) return false;
        String stripped = CALL_CITATION_PATTERN.matcher(conclusion).replaceAll("");
        stripped = COMPUTATION_REF_PATTERN.matcher(stripped).replaceAll("");
        return DIGIT_PATTERN.matcher(stripped).find();
    }

    /** Legacy JSON-based path — fast Ollama critique + prescribed experiment + Groq revision. */
    private NarrationResult maybeCritiqueAndReviseJsonPath(PaperDraft draft, NarrationResult firstPass,
                                                            List<String> callPath) {
        try {
            long critiqueStart = System.currentTimeMillis();
            String critiqueResponse = router.callFast(buildCritiquePrompt(promptRegistry, draft, firstPass));
            CritiqueResult critique = parseCritiqueResult(critiqueResponse);

            if (!critique.hasActionableFeedback()) {
                callPath.add("json_critique:4b:clean→fp");
                log.info("LLM professor review (json): clean draft paperId={} ms={}",
                        draft.paperId(), System.currentTimeMillis() - critiqueStart);
                return firstPass;
            }
            callPath.add("json_critique:4b:" + critique.weaknesses().size() + "gaps");
            log.info("LLM professor review (json): {} weakness(es) experiment={} paperId={} ms={}",
                    critique.weaknesses().size(), critique.experimentalGap() != null,
                    draft.paperId(), System.currentTimeMillis() - critiqueStart);

            String professorExperiment = null;
            if (critique.experimentalGap() != null) {
                professorExperiment = runProfessorDirectedExperiment(draft, critique.experimentalGap());
            }

            // Pre-emptive gate check before the primary-stream revision call —
            // the only remaining Groq-spending stage on this path.
            if (gateClosed("revision", draft, callPath)) {
                return firstPass;
            }

            long revisionStart = System.currentTimeMillis();
            StringBuilder revised = new StringBuilder();
            final String profExp = professorExperiment;
            withNarrationSchema(() -> router.streamPrimary(
                    appendNoveltyPreservation(
                            buildRevisionPrompt(promptRegistry, draft, firstPass, critique.weaknesses(), profExp),
                            revisionNoveltyPreservationEnabled),
                    revised::append));
            NarrationResult revParsed = parseNarrationResult(revised.toString());
            if (revParsed.hypothesis() != null && revParsed.findings() != null && revParsed.conclusion() != null) {
                callPath.add("json_rev:" + abbrev(router.primaryModel()) + ":ok");
                log.info("LLM student revision (json) done paperId={} ms={}",
                        draft.paperId(), System.currentTimeMillis() - revisionStart);
                com.emergentterrain.research.FollowUpParams followUp =
                        revParsed.followUp() != null ? revParsed.followUp() : firstPass.followUp();
                return new NarrationResult(
                        revParsed.hypothesis(), revParsed.findings(), revParsed.conclusion(),
                        followUp, router.primaryModel(),
                        revParsed.confidenceScore(), revParsed.citedPaperIds());
            }
            callPath.add("json_rev:parse_failed→fp");
            // Phase 14 F2a — diagnostic for the JSON path's parse-failed
            // fallback. JSON path runs without tool calls (firstPass has no
            // trace), so a multi-block firstPass surviving here means a
            // narrator emitted multiple Computation N blocks with no
            // computation supporting them.
            log.warn("FALLBACK json_rev:parse_failed→fp paperId={} firstPassBlocks={} (Phase 14 F2a)",
                    draft.paperId(),
                    firstPass.findingsMap() != null ? firstPass.findingsMap().size() : 0);
        } catch (Exception e) {
            callPath.add("json_critique:error→fp");
            log.warn("LLM professor+revision (json) failed paperId={} firstPassBlocks={}: {} "
                            + "— using first-pass narration (Phase 14 F2a)",
                    draft.paperId(),
                    firstPass.findingsMap() != null ? firstPass.findingsMap().size() : 0,
                    e.getMessage());
        }
        return firstPass;
    }

    /**
     * Phase 7 E3 — Ollama-only critique+revise loop. Mirrors the JSON path but routes
     * the revision stream through the fast pool too, since the primary chain is
     * exhausted or unavailable in this branch. No tool calls (Ollama's tool-calling
     * is unreliable). Stamps {@code rev:ollama:ok} so the publish path treats the
     * paper as fully revised (not PUBLISHED_NO_PROFESSOR).
     */
    private NarrationResult maybeCritiqueAndReviseOllama(PaperDraft draft, NarrationResult firstPass,
                                                          List<String> callPath) {
        try {
            long critiqueStart = System.currentTimeMillis();
            String critiqueResponse = router.callFast(buildCritiquePrompt(promptRegistry, draft, firstPass));
            CritiqueResult critique = parseCritiqueResult(critiqueResponse);

            if (!critique.hasActionableFeedback()) {
                callPath.add("ollama_critique:4b:clean→fp");
                log.info("LLM professor review (ollama): clean draft paperId={} ms={}",
                        draft.paperId(), System.currentTimeMillis() - critiqueStart);
                return firstPass;
            }
            callPath.add("ollama_critique:4b:" + critique.weaknesses().size() + "gaps");
            log.info("LLM professor review (ollama): {} weakness(es) experiment={} paperId={} ms={}",
                    critique.weaknesses().size(), critique.experimentalGap() != null,
                    draft.paperId(), System.currentTimeMillis() - critiqueStart);

            String professorExperiment = null;
            if (critique.experimentalGap() != null) {
                professorExperiment = runProfessorDirectedExperiment(draft, critique.experimentalGap());
            }

            // Gate check is still meaningful — keeps any other Groq-spending stage
            // (e.g. statistical framing inside runProfessorDirectedExperiment) within
            // the simulation's budget posture.
            if (gateClosed("revision", draft, callPath)) {
                return firstPass;
            }

            long revisionStart = System.currentTimeMillis();
            StringBuilder revised = new StringBuilder();
            final String profExp = professorExperiment;
            withNarrationSchema(() -> router.streamFast(
                    appendNoveltyPreservation(
                            buildRevisionPrompt(promptRegistry, draft, firstPass, critique.weaknesses(), profExp),
                            revisionNoveltyPreservationEnabled),
                    revised::append));
            NarrationResult revParsed = parseNarrationResult(revised.toString());
            if (revParsed.hypothesis() != null && revParsed.findings() != null && revParsed.conclusion() != null) {
                callPath.add("rev:ollama:ok");
                log.info("LLM student revision (ollama) done paperId={} ms={}",
                        draft.paperId(), System.currentTimeMillis() - revisionStart);
                com.emergentterrain.research.FollowUpParams followUp =
                        revParsed.followUp() != null ? revParsed.followUp() : firstPass.followUp();
                return new NarrationResult(
                        revParsed.hypothesis(), revParsed.findings(), revParsed.conclusion(),
                        followUp, router.fastModel(),
                        revParsed.confidenceScore(), revParsed.citedPaperIds());
            }
            callPath.add("rev:ollama:parse_failed→fp");
            // Phase 14 F2a — diagnostic for the Ollama path's parse-failed
            // fallback. Like the JSON path, Ollama runs without tool calls
            // so firstPass has no trace; a multi-block firstPass surviving
            // here means the same fabrication pattern.
            log.warn("FALLBACK rev:ollama:parse_failed→fp paperId={} firstPassBlocks={} (Phase 14 F2a)",
                    draft.paperId(),
                    firstPass.findingsMap() != null ? firstPass.findingsMap().size() : 0);
        } catch (Exception e) {
            callPath.add("ollama_critique:error→fp");
            log.warn("LLM professor+revision (ollama) failed paperId={} firstPassBlocks={}: {} "
                            + "— using first-pass narration (Phase 14 F2a)",
                    draft.paperId(),
                    firstPass.findingsMap() != null ? firstPass.findingsMap().size() : 0,
                    e.getMessage());
        }
        return firstPass;
    }

    /**
     * Executes the professor-prescribed experiment synchronously.
     * Returns a formatted summary string for the revision prompt, or null on failure.
     */
    private String runProfessorDirectedExperiment(PaperDraft draft, ExperimentalGap gap) {
        try {
            long start = System.currentTimeMillis();
            MathPhysicsEngine.ResearchResult result;

            if ("dynamic".equals(gap.type()) && gap.predicate() != null && gap.rangeStart() != null) {
                String id = "prof-" + draft.paperId() + "-" + System.currentTimeMillis();
                com.emergentterrain.research.DynamicProblem problem =
                        new com.emergentterrain.research.DynamicProblem(
                                id, gap.description(), gap.rangeStart(), gap.rangeEnd(),
                                gap.predicate(), gap.metric(), gap.baseline(), gap.hypothesis());
                result = com.emergentterrain.research.DynamicComputeEngine.compute(problem);
            } else if ("param".equals(gap.type()) && gap.param1() != null && gap.param2() != null) {
                int topicIdx = MathPhysicsEngine.topicIndex(draft.domain(), draft.topicName());
                if (topicIdx < 0) return null;
                MathPhysicsEngine.SearchParams focusSp =
                        new MathPhysicsEngine.SearchParams(0.1, 0.0, 10, 0.5, 0.0);
                result = dispatchCompute(
                        draft.domain(), topicIdx, gap.param1(), gap.param2(), focusSp);
            } else {
                return null;
            }

            // Best-effort statistical framing of the new result
            String findings = result.findings();
            try {
                String framed = parseStatisticalFraming(router.callFast(
                        buildStatisticalFramingPrompt(promptRegistry, findings, draft.domain(), draft.topicName())));
                if (framed != null) findings = framed;
            } catch (Exception ignored) {}

            log.info("LLM professor experiment done paperId={} type={} ms=\"{}\" findings=\"{}\"",
                    draft.paperId(), gap.type(), System.currentTimeMillis() - start,
                    findings.length() > 100 ? findings.substring(0, 100) + "…" : findings);

            return "Professor-directed experiment — \"%s\":\n%s\n(Rationale: %s)"
                    .formatted(gap.description(), findings, gap.rationale());
        } catch (Exception e) {
            log.warn("LLM professor experiment failed paperId={}: {}", draft.paperId(), e.getMessage());
            return null;
        }
    }

    static String buildCritiquePrompt(PromptTemplateRegistry registry,
                                       PaperDraft draft, NarrationResult narration) {
        String preRegLine = draft.preRegistration() != null
                ? "Pre-registered prediction: " + draft.preRegistration().prediction() + "\n" : "";
        String classLine  = draft.resultClassification() != null
                ? "Result classification: " + draft.resultClassification() + "\n" : "";

        boolean canParam   = draft.param1() != null
                && MathPhysicsEngine.topicIndex(draft.domain(), draft.topicName()) >= 0;
        boolean canDynamic = canParam
                && ("MATHEMATICS".equalsIgnoreCase(draft.domain())
                    || "INTERDISCIPLINARY".equalsIgnoreCase(draft.domain()));

        String experimentSpec;
        if (canDynamic) {
            experimentSpec = """
                    You may prescribe ONE of:
                    • Param-based (any topic): \
                    {"type":"param","param1":75,"param2":5.0,"description":"...","rationale":"..."}
                    • Number-theoretic (MATHEMATICS/INTERDISCIPLINARY): \
                    {"type":"dynamic","predicate":"isPrime AND isHappy","range_start":200,"range_end":500,\
                    "metric":"fraction_satisfying","hypothesis":"...","baseline":0.04,\
                    "description":"...","rationale":"..."}
                    Valid predicates: isPrime isHappy collatzTerminates isAbundant isPerfect (AND/OR)
                    Valid metrics: fraction_satisfying mean_of:collatzSteps max_of:collatzSteps mean_of:sigmaSum
                    param1 int 10–99, param2 decimal 1.0–9.0. range_end − range_start ≤ 500.
                    """;
        } else if (canParam) {
            experimentSpec = """
                    You may prescribe ONE param-based computation: \
                    {"type":"param","param1":75,"param2":5.0,"description":"...","rationale":"..."}
                    param1 int 10–99, param2 decimal 1.0–9.0.
                    """;
        } else {
            experimentSpec = "No additional computation is possible for this paper type.\n";
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",              draft.domain());
        params.put("topicName",           draft.topicName());
        params.put("preRegAndClassLines", preRegLine + classLine);
        params.put("hypothesis",          narration.hypothesis());
        // Phase 7 F2 — multi-block findings render as a labeled per-computation
        // layout so the critique LLM can address each block separately.
        params.put("findings",            formatFindingsForPrompt(narration));
        params.put("conclusion",          narration.conclusion());
        params.put("experimentSpec",      experimentSpec);
        return registry.render(PromptTemplateIds.NARRATION_CRITIQUE_ID, params)
                + conjectureDirective(draft);
    }

    /**
     * 2026-05-29 — appended to every refine-stage prompt (professor / critique /
     * revision / refinement) for CONJECTURE_INVESTIGATION drafts so the catalog-
     * oriented templates don't restructure the paper away from its conjecture
     * form (the first-pass uses the conjecture template; without this the refine
     * stages overwrite the ## Mechanism / ## Adversarial Check sections). Empty
     * for every other kind — no behaviour change to the catalog path.
     */
    private static String conjectureDirective(PaperDraft draft) {
        if (draft == null
                || draft.kind() != com.emergentterrain.research.PaperKind.CONJECTURE_INVESTIGATION) {
            return "";
        }
        return "\n\n=== CONJECTURE INVESTIGATION — STRUCTURE CONTRACT (mandatory) ===\n"
            + "This paper investigates a mined mathematical conjecture (a number-theoretic "
            + "inequality), NOT a physics/materials experiment. You MUST:\n"
            + "1. Keep a '## Mechanism' section that BEGINS with a line "
            + "'Mechanism kind: <heuristic_derivation | asymptotic_intuition | "
            + "probabilistic_model | extremal_construction | analogy_to_known_theory | none>' "
            + "then gives a structural argument IN THAT MODE for why the bound should hold "
            + "(not a paraphrase of the inequality). If a 'Mechanism kind:' line is already "
            + "present in the draft, preserve it.\n"
            + "2. Keep a '## Adversarial Check' section that invokes the verify_conjecture "
            + "tool on a held-out slice and reports held/refuted + the worst-margin witness. "
            + "Quote the observed LHS from the SAME per-window row (same start=) as the worst "
            + "margin — never pair the worst margin with a different window's lhs value.\n"
            + "3. NOT restructure into generic physics sections, and NOT invent a physical "
            + "mechanism — there is none; this is pure number theory (so do not claim, nor "
            + "let a reviewer demand, a physical mechanism).\n"
            + "4. Keep every numeric claim traceable to the conjecture's mining history or "
            + "to verify_conjecture output.\n"
            + "5. GROWTH-ANCHOR STEP (2026-06-08): if the LHS is a quantity that GROWS with x — "
            + "e.g. max_prime_gap (~ln^2 x by Cramer), mean_divisor_count / max_divisor_count "
            + "(~log x) — then a flat per-window feature makes k*rhs+c near-constant and the "
            + "seeded bound WILL refute. In that case you MUST also propose-and-verify (a real "
            + "verify_conjecture call, not just a correlation) at least one ALTERNATIVE bound "
            + "whose rhs is a GROWTH ANCHOR that can track that growth: log_sq_window_start (the "
            + "ln^2 x / Cramer scale — use this for max_prime_gap), or log_window_start / "
            + "sqrt_window_start (for log- / sqrt-growing LHS). To COUNT as a survived bound (not a "
            + "lucky easy-region hold) you MUST: (a) test the growth-anchor bound on the SAME "
            + "held-out windows where the flat seeded bound was REFUTED — and at the LARGEST x you "
            + "reached — never only a low-x region; a bound that holds near x=200,000 but is "
            + "untested where the flat bound failed has NOT survived. (b) Fit a TIGHT (k, c) PAIR: "
            + "set k to MATCH the LHS's growth rate and c to the SMALLEST intercept that makes it "
            + "hold across the failure windows. Do NOT force c=0 — a quantity growing like "
            + "growth_term + O(1) (e.g. mean_divisor_count ~ log x + O(1)) needs a small constant to "
            + "absorb that offset, and forcing c=0 makes a near-tight bound graze and refute by a "
            + "hair. Minimize SLACK, not c: iterate k toward the growth rate AND c toward the "
            + "smallest holding value via repeated verify_conjecture calls — ALL on the SAME high-x "
            + "FAILURE windows where the flat bound was refuted, NOT low-x windows where any loose "
            + "bound holds easily: a tight fit at x=260,000 proves nothing if you never tightened at "
            + "the x=1,500,000 failure region, so run your k/c iteration THERE — until the worst-margin "
            + "is small (near 0) but still HOLDS — a bound that holds only with huge slack (e.g. k=1 "
            + "leaving margin -89) is trivially loose and does not count. Report the tight (k, c) and the "
            + "worst-margin AT THE FAILURE REGION. A growth-anchor bound that HOLDS TIGHTLY ACROSS "
            + "THE WINDOWS WHERE THE FLAT BOUND FAILED is THE result of the paper — a bound that "
            + "SURVIVED adversarial testing — not the flat-feature refutation. Each HELD "
            + "verify_conjecture now reports a 'relative slack' (|margin|/|bound| at the binding "
            + "window): treat it as your tightness gauge and keep iterating k/c at the failure region "
            + "until it is SMALL (aim <= 0.25) — a HELD bound whose relative slack is still large is "
            + "loose and will not be retained as a survivor, so report the (k, c) at your SMALLEST "
            + "achieved relative slack.\n"
            + "6. CONSTANT-CONVERGENCE STEP (2026-06-10): some LHS quantities are NOT bounds at all — "
            + "their real content is a LIMIT they converge to. A normalized RATIO that plateaus is the "
            + "signature: twin_vs_prime_sq → the Hardy-Littlewood twin constant 2C2 ≈ 1.32032, "
            + "cramer_gap_ratio → a Cramer-type constant. For such an LHS, BOTH a flat-feature bound AND "
            + "a growth-anchor bound are the wrong object (the quantity neither grows nor sits under a "
            + "moving ceiling — it SETTLES). Do NOT force a `lhs <= k*rhs + c` inequality on it. Instead "
            + "use the verify_constant tool on the high-x failure-region windows to MEASURE the converged "
            + "value and its deviation (supply the known target where one exists, e.g. 1.32032 for "
            + "twin_vs_prime_sq; omit it to estimate the limit empirically). The RESULT of such a paper "
            + "is the measured constant + how tightly it has converged (the relative spread), reported "
            + "from verify_constant output — NOT a refuted inequality. A ratio that REFUTES every bound "
            + "you try but CONVERGES under verify_constant is a successful measurement, not a null.\n"
            + "7. OSCILLATION STEP (2026-06-10): if the LHS swings around ZERO and changes sign (the "
            + "signature: mertens_delta = the Mertens function M(x), which is negative as often as "
            + "positive), then NO one-sided `lhs <= k*rhs + c` bound can hold — it refutes the moment M "
            + "dips the other way. Use the verify_oscillation tool on the high-x windows: it reports the "
            + "sign changes, amplitude, near-zero mean, and — pass scale_rhs=sqrt_window_start — the "
            + "bounded ENVELOPE max|M|/√x (the Riemann-Hypothesis-relevant quantity; the Mertens "
            + "conjecture |M|<√x is false, but |M|=O(√x·...) is the real object). The result is the "
            + "envelope + sign-change structure, not a refuted bound.\n"
            + "8. SUPER-LOG GROWTH STEP (2026-06-10): if the LHS grows FASTER than any power of log x so "
            + "even a log_sq_window_start anchor refutes (the signature: max_divisor_count, the maximal "
            + "divisor count, ~ exp(ln2·ln x/ln ln x) by Wigert), use the verify_growth tool: it "
            + "transforms log(lhs)·ln ln x/ln x and tests convergence to the Wigert growth constant "
            + "ln2 ≈ 0.69315 (approached slowly, so a wide tol is expected). The result is the measured "
            + "growth law (the Wigert ratio approaching ln2), not a refuted bound. "
            + "In ALL of steps 5-8: pick the ONE frame that matches the LHS's actual behaviour — a "
            + "growing bound (5), a converging constant (6), a centred oscillation (7), or super-log "
            + "growth (8) — and report THAT measurement as the paper's result; do not keep forcing the "
            + "default inequality on a quantity it does not fit.";
    }

    // ── Tool-calling professor prompts ────────────────────────────────────────

    // ── Audit gap record ─────────────────────────────────────────────────────
    private record AuditGap(String dimension, String severity, String description) {}

    // ── Quality audit (Cerebras when available, else fast Ollama) ────────────

    private String runQualityAudit(PaperDraft draft, NarrationResult firstPass,
                                    List<String> callPath, List<String> auditGapDimensions) {
        try {
            long t = System.currentTimeMillis();
            // Phase 7 E1.6 — direct audit at the "fast-audit" role pool (Cerebras WSE-3
            // delivers ~14 ms inference vs Ollama's ~100-500 ms). Falls back to the
            // configured fast pool when Cerebras is disabled, RPM-blocked, or throws.
            String raw = router.callForRole("fast-audit",
                    buildAuditPrompt(promptRegistry, draft, firstPass));
            List<AuditGap> gaps = parseAuditGaps(objectMapper, raw);
            if (gaps.isEmpty()) {
                callPath.add("audit:4b:0gaps");
                log.info("LLM quality audit paperId={} ms={} 0 gaps", draft.paperId(),
                        System.currentTimeMillis() - t);
                return "";
            }
            String pathEntry = gaps.stream()
                    .map(g -> g.dimension() + "(" + g.severity().charAt(0) + ")")
                    .collect(java.util.stream.Collectors.joining(","));
            callPath.add("audit:4b:" + gaps.size() + "gaps=[" + pathEntry + "]");
            log.info("LLM quality audit paperId={} ms={} gaps={}", draft.paperId(),
                    System.currentTimeMillis() - t, gaps.size());
            // Phase 7 G8 — accumulate unique dimension names for the publish-path validator.
            for (AuditGap g : gaps) {
                if (!auditGapDimensions.contains(g.dimension())) auditGapDimensions.add(g.dimension());
            }
            return gaps.stream()
                    .map(g -> "- [" + g.severity().toUpperCase() + "] " + g.dimension() + ": " + g.description())
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (Exception e) {
            callPath.add("audit:4b:failed");
            log.warn("LLM quality audit failed paperId={}: {}", draft.paperId(), e.getMessage());
            return "";
        }
    }

    static String buildAuditPrompt(PromptTemplateRegistry registry,
                                    PaperDraft draft, NarrationResult narration) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",         draft.domain());
        params.put("topicName",      draft.topicName());
        params.put("classification", draft.resultClassification() != null
                ? draft.resultClassification() : "UNKNOWN");
        params.put("hypothesis",     narration.hypothesis());
        // Phase 7 F2 — multi-block findings render as a labeled per-computation
        // layout so the audit LLM can flag gaps PER block. Single-block layout
        // is bit-identical to the legacy single-string findings.
        params.put("findings",       formatFindingsForPrompt(narration));
        params.put("conclusion",     narration.conclusion());
        // Phase 14 F6 — Discussion sliced to ~200 tokens (~150 words) so the
        // audit-4b reviewer can flag cross-domain drift in non-INTERDISCIPLINARY
        // papers. Refresh #2 P-484 (MATHEMATICS paper whose Discussion drifts
        // into Van der Waals + Schrödinger) was caught by recommendation #6 as
        // a Discussion-only failure that the Hypothesis-only audit input
        // missed.
        params.put("discussion",     truncateToWords(narration.discussion(), 150));
        return registry.render(PromptTemplateIds.NARRATION_AUDIT_ID, params);
    }

    /**
     * Phase 14 F6 — return the first {@code maxWords} whitespace-separated
     * tokens of {@code text}, with an ellipsis if truncated. Empty / null
     * input returns the empty string so the prompt slot renders cleanly.
     */
    static String truncateToWords(String text, int maxWords) {
        if (text == null || text.isBlank()) return "";
        String[] words = text.trim().split("\\s+");
        if (words.length <= maxWords) return text.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        sb.append(" …");
        return sb.toString();
    }

    /**
     * Phase 7 F2 — render the findings field for a downstream prompt slot. Single-
     * block (flag-off) maps return the prose verbatim — the legacy joined-string
     * shape every audit / professor / revision template already expects. Multi-
     * block maps emit a {@code [Computation N]\n<prose>} labelled layout in
     * ascending key order so the LLM can address each block independently.
     */
    static String formatFindingsForPrompt(NarrationResult narration) {
        if (narration == null) return null;
        java.util.Map<Integer, String> map = narration.findingsMap();
        if (map == null || map.isEmpty()) return narration.findings();
        if (map.size() == 1) return narration.findings();
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (java.util.Map.Entry<Integer, String> e :
                new java.util.TreeMap<>(map).entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            if (!first) sb.append("\n\n");
            first = false;
            sb.append("[Computation ").append(e.getKey()).append("]\n")
                    .append(e.getValue());
        }
        return sb.toString();
    }

    static List<AuditGap> parseAuditGaps(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank()) return List.of();
        int s = response.indexOf('{'), e = response.lastIndexOf('}');
        if (s < 0 || e <= s) return List.of();
        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.substring(s, e + 1));
            List<AuditGap> out = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode g : root.path("gaps")) {
                String dim  = g.path("dimension").asText("").trim();
                String sev  = g.path("severity").asText("moderate").trim();
                String desc = g.path("description").asText("").trim();
                if (!dim.isBlank() && !desc.isBlank()) out.add(new AuditGap(dim, sev, desc));
            }
            return List.copyOf(out);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String abbrev(String model) {
        if (model == null) return "?";
        if (model.length() <= 18) return model;
        // Keep the first 18 chars (e.g. "llama-3.3-70b-vers" → "llama-3.3-70b-ver")
        return model.substring(0, 18);
    }

    /**
     * Phase 6 D1.3 — fetches the curated references for this draft's (domain, topic)
     * and renders them as a slot-ready block. Returns the empty string when the
     * literature flag is off, when no service is wired, or when the corpus has
     * no entries for the key. Empty input → empty slot → v1 template renders
     * verbatim (memo §3.3 — no "no literature found" annotation).
     */
    String renderLiteratureContext(PaperDraft draft) {
        if (!literatureSearchEnabled || literatureSearch == null) return "";
        java.util.List<LiteratureReference> refs =
                literatureSearch.findFor(draft.domain(), draft.topicName());
        if (refs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nPRIOR LITERATURE FOR THIS REGION:\n");
        for (LiteratureReference r : refs) {
            sb.append("- ").append(r.citation()).append(": ").append(r.closedForm());
            if (r.validRegion() != null && !r.validRegion().isBlank()) {
                sb.append(" Region: ").append(r.validRegion()).append('.');
            }
            if (r.magnitudeEstimate() != null && !r.magnitudeEstimate().isBlank()) {
                sb.append(" Magnitude: ").append(r.magnitudeEstimate()).append('.');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static String buildProfessorSystemPrompt(PromptTemplateRegistry registry) {
        return buildProfessorSystemPrompt(registry, "", null);
    }

    static String buildProfessorSystemPrompt(PromptTemplateRegistry registry, String auditSummary) {
        return buildProfessorSystemPrompt(registry, auditSummary, null, null);
    }

    /**
     * Lever 2 — appended to the professor system prompt when the
     * {@code search_literature} citation tool is active. Instructs the model to
     * ground every prior-work citation in a tool result rather than inventing
     * author-year references.
     */
    /**
     * Path B (2026-06-05) — one suggestive frontier line for the professor system
     * prompt, rendered only for frontier-framed CONJECTURE_INVESTIGATION drafts
     * ({@link com.emergentterrain.research.PaperDraft#frontierDomain} non-null).
     * Phrased to coexist with the overclaiming audit rather than fight it:
     * compute-and-label-honestly is not an overclaim. Suggestive, not
     * prescriptive — the LLM still decides whether to call (srHint contract).
     * Null for every other draft, so the template renders exactly as before.
     */
    static String buildFrontierHint(com.emergentterrain.research.PaperDraft draft) {
        if (draft == null || draft.frontierDomain() == null) {
            return null;
        }
        com.emergentterrain.research.FrontierDomain fd = draft.frontierDomain();
        String compute = fd.discoverTool != null
                ? "compute it at one x > 2,000,000 via `" + fd.discoverTool + "` and/or consult "
                : "consult ";
        return "FRONTIER: this bound concerns " + fd.quantity
                + ", a quantity computable past the 2,000,000 sieve ceiling. Once the in-range "
                + "evidence is settled, also " + compute
                + "`recall_frontier_knowledge` (bound `" + fd.boundKey + "`) for the accumulated "
                + "frontier record. Report any such value as COMPUTED via a moat-validated method, "
                + "never as an established fact — one honestly-labelled beyond-sieve data point is "
                + "stronger evidence than another in-range window.";
    }

    /** Join optional one-line prompt hints (srHint, frontierHint) — null/blank-safe. */
    static String joinHints(String... hints) {
        StringBuilder sb = new StringBuilder();
        for (String h : hints) {
            if (h != null && !h.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(h);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static final String CITATION_GROUNDING_RULE =
            "\n\nCITATION GROUNDING: To cite prior work, you MUST first call the "
            + "search_literature tool and cite ONLY the exact citation strings it "
            + "returns. Never invent author-year references. If search_literature "
            + "returns no references for this topic, make no prior-work citations.";

    static String buildProfessorSystemPrompt(PromptTemplateRegistry registry,
                                              String auditSummary, String literatureContext) {
        return buildProfessorSystemPrompt(registry, auditSummary, literatureContext, null);
    }

    /**
     * Phase 24 §24.4 — {@code srHint} is a one-line nudge populated when
     * the symbolic_regress tool is in the schema AND the paper's domain
     * is one where SR is likely to be useful (physics, materials,
     * terrestrial, underwater). Empty / null for other domains; the
     * LLM never sees it then. The hint is intentionally suggestive,
     * not prescriptive — the LLM still decides whether to call.
     *
     * <p>Phase 6 D1.3 — {@code literatureContext} is a rendered block
     * of curated references (or null/empty when the flag is off or
     * the lookup found nothing). Non-null/non-empty value switches to
     * the {@code v2-with-literature} template variant.
     */
    static String buildProfessorSystemPrompt(PromptTemplateRegistry registry,
                                              String auditSummary,
                                              String literatureContext,
                                              String srHint) {
        String auditSection = (auditSummary != null && !auditSummary.isBlank())
                ? "\nA quality audit identified these specific gaps. Address them with your tools:\n"
                  + auditSummary + "\n"
                : "";
        String srHintRendered = (srHint != null && !srHint.isBlank())
                ? "\n" + srHint + "\n"
                : "";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("auditSection", auditSection);
        params.put("srHint", srHintRendered);
        if (literatureContext != null && !literatureContext.isEmpty()) {
            params.put("literatureContext", literatureContext);
            return registry.render(PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,
                    "v2-with-literature", params);
        }
        return registry.render(PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_ID,
                PromptTemplateIds.NARRATION_PROFESSOR_SYSTEM_VERSION, params);
    }

    /**
     * Phase 24 §24.4 — build the SR-hint sentence when conditions are
     * right. Returns null otherwise; the caller's three-arg / four-arg
     * overload of {@link #buildProfessorSystemPrompt} treats null as
     * "no hint, render the slot empty."
     *
     * <p>Conditions for the hint:
     * <ul>
     *   <li>The Julia sidecar's pysr path is available.</li>
     *   <li>The paper's domain is one where parameter sweeps over
     *       continuous variables are the norm: PHYSICS, MATERIALS,
     *       TERRESTRIAL, UNDERWATER (broadly the "scaling-law"
     *       domains identified in phase-24.md §"Scope").</li>
     * </ul>
     * Math, interdisciplinary, and unknown domains: no hint — the SR
     * tool isn't a natural match there.
     */
    static String buildSymbolicRegressHint(
            com.emergentterrain.llm.JuliaMathClient juliaMathClient, String domain) {
        if (juliaMathClient == null || !juliaMathClient.isPysrAvailable()) return null;
        if (domain == null) return null;
        String d = domain.toUpperCase(java.util.Locale.ROOT);
        if (!("PHYSICS".equals(d) || "MATERIALS".equals(d)
                || "TERRESTRIAL".equals(d) || "UNDERWATER".equals(d))) {
            return null;
        }
        return "Symbolic-regression hint: if the paper's parameter sweep suggests a closed-form "
                + "relationship between inputs (e.g. an Arrhenius / inverse-square / exponential-decay "
                + "shape), consider calling `symbolic_regress` with the sweep rows. It returns a "
                + "Pareto front of (expression, mse, complexity) — the simplest entry is often the "
                + "law the paper should be citing. No need to call it for incremental or descriptive "
                + "papers; only when there's reason to believe a clean functional law underlies "
                + "the data.";
    }

    static String buildProfessorUserPrompt(PromptTemplateRegistry registry,
                                            PaperDraft draft, NarrationResult narration) {
        return buildProfessorUserPrompt(registry, draft, narration, null);
    }

    /** Phase 6 D1.3 — see {@link #buildProfessorSystemPrompt(PromptTemplateRegistry, String, String)}. */
    static String buildProfessorUserPrompt(PromptTemplateRegistry registry,
                                            PaperDraft draft, NarrationResult narration,
                                            String literatureContext) {
        String preRegLine = draft.preRegistration() != null
                ? "Pre-registered prediction: " + draft.preRegistration().prediction() + "\n" : "";
        String classLine  = draft.resultClassification() != null
                ? "Result classification: " + draft.resultClassification() + "\n" : "";
        // preRegLine + classLine concatenated into a single composite slot; both
        // may be empty independently. Pre-carve used positional %s%s for the same.
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",                draft.domain());
        params.put("topicName",             draft.topicName());
        params.put("preRegAndClassLines",   preRegLine + classLine);
        params.put("hypothesis",            narration.hypothesis());
        // Phase 7 F2 — multi-block findings render as a labeled per-computation
        // layout so the professor can address each block when prescribing experiments.
        params.put("findings",              formatFindingsForPrompt(narration));
        params.put("conclusion",            narration.conclusion());
        if (literatureContext != null && !literatureContext.isEmpty()) {
            params.put("literatureContext", literatureContext);
            return registry.render(PromptTemplateIds.NARRATION_PROFESSOR_USER_ID,
                    "v2-with-literature", params) + conjectureDirective(draft);
        }
        return registry.render(PromptTemplateIds.NARRATION_PROFESSOR_USER_ID,
                PromptTemplateIds.NARRATION_PROFESSOR_USER_VERSION, params)
                + conjectureDirective(draft);
    }

    /**
     * Phase 11 C2 — addendum appended to the student-revision prompt after the
     * professor's tool calls have produced a trace with stable {@code call_N}
     * IDs. Two things this asks the LLM to do:
     * <ol>
     *   <li>Place an inline {@code [call_N]} marker immediately after every
     *       numeric value cited in findings or conclusion.</li>
     *   <li>Emit a parallel {@code numeric_claims} array with one entry per
     *       cited value, carrying the same {@code call_N} ID for cross-check.</li>
     * </ol>
     * The CitationValidator (C3) checks both shapes against the persisted
     * {@link com.emergentterrain.llm.ToolInvocation} trace. In C4 warn-only
     * mode the validator only stamps the call_path; in later modes it routes
     * unattributable papers to NULL_RESULT or suppresses them.
     *
     * <p>Skipped on the first-pass and critique-loop prompts because no tool
     * calls have run yet at those points — there are no {@code call_N} IDs to
     * cite.
     */
    /**
     * rec #4 follow-up (2026-05-29) — render the actual {@code [call_N]}-tagged
     * tool trace for injection into a revision prompt. This makes
     * {@link #CITATION_RULE_ADDENDUM}'s premise ("tool results ... already
     * prefixed with their [call_N] IDs — reuse those exact markers") TRUE: the
     * revision templates otherwise inject only the professor's PROSE synthesis,
     * so the reviser invented {@code [call_N]} indices and overran the trace
     * (P-a328-993 cited call_18/19; 994 cited the FAILED call_1/5).
     *
     * <p>Every invocation is listed with its real harness-assigned
     * {@code callId} (falling back to its 1-based position for pre-Phase-11
     * traces). Errored / safety-rejected calls are rendered but flagged
     * DO-NOT-CITE (via {@link com.emergentterrain.research.NumericalConsistencyValidator#isErroredResult})
     * — the harness numbers {@code call_N} positionally over the WHOLE trace, so
     * a failed call still occupies an index that must not be cited as data.
     * Args/results are clipped to bound prompt tokens.
     *
     * <p>Returns {@code ""} for a null/empty trace so callers can append
     * unconditionally. Package-private + static for unit testing without a router.
     */
    static String renderCitableTraceBlock(List<com.emergentterrain.llm.ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        b.append("\n\n=== TOOL-CALL TRACE — the ONLY valid [call_N] citations ===\n")
                .append("These are the exact tool calls made for this paper, with their real ")
                .append("[call_N] IDs. Cite ONLY these markers; never cite a [call_N] not listed ")
                .append("here and never cite a call marked FAILED.\n");
        int idx = 0;
        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            idx++;
            if (inv == null) continue;
            String id = (inv.callId() != null && !inv.callId().isBlank())
                    ? inv.callId() : "call_" + idx;
            String fn = inv.functionName() == null ? "?" : inv.functionName();
            b.append('[').append(id).append("] ").append(fn)
                    .append('(').append(clip(inv.argsJson(), 120)).append(") → ");
            if (com.emergentterrain.research.NumericalConsistencyValidator
                    .isErroredResult(inv.resultJson())) {
                b.append("FAILED (errored or safety-rejected) — DO NOT CITE\n");
            } else {
                b.append(clip(inv.resultJson(), 240)).append('\n');
            }
        }
        b.append("Valid citation indices: call_1 … call_").append(trace.size())
                .append(". Never cite an index above call_").append(trace.size())
                .append(", and never cite a FAILED call.\n");
        return b.toString();
    }

    /** Whitespace-collapse and length-clip a raw JSON field for prompt embedding. */
    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /**
     * rec #4 follow-up (2026-05-29) — appended to the first-pass prompt when
     * {@code firstPassMetricGuardEnabled}. Targets P-a328-995's two fp failures:
     * a fabricated "quality score" presented as a result, and committing to an
     * "optimum" before the verification tools ran. {@code confidenceScore} /
     * {@code predictedScore} stay valid as JSON meta-fields; the rule only bars
     * stating them (or any uncomputed metric) as measured results in prose.
     *
     * <p>Clause (c) added for room b72c4e10: the dominant non-publication cause this batch was the
     * narrator injecting numbers absent from the trace — textbook/theoretical constants it "knows"
     * (1/ln 2, a known density) and round-number characterizations ("close to 5",
     * "approximately 0.14") — which {@code NumericalConsistencyValidator} then nulls (P-b72c-96/98/
     * 99/102). The clause bars introducing any figure not in the tool output, covering that class.
     */
    static final String FIRST_PASS_METRIC_GUARD_RULE = """


            FIRST-PASS GROUNDING: no verification tools have run at this stage — you are \
            interpreting the raw computation output above, nothing more. In `findings` and \
            `conclusion`: (a) do NOT state a specific numeric "quality score", "accuracy", or \
            any measured metric the computation did not itself produce — `confidenceScore` and \
            `predictedScore` are your own meta-assessments and belong ONLY in those JSON fields, \
            never in prose as a computed result; (b) do NOT declare one parameter value the \
            definitive optimum unless the raw output above already shows it beating the \
            alternatives — describe the qualitative trend and leave the precise optimum to the \
            verified revision; (c) do NOT introduce any numeric value that is not present in the \
            computation output above — this includes textbook or theoretical constants you happen \
            to know (a known density, 1/ln 2, an asymptotic mean, and the like), hand-computed \
            figures, and round-number characterizations such as "close to 5", "approximately \
            0.14", or "≈ X". Every number in `findings` and `conclusion` must be one the tools \
            actually returned; to invoke a known constant, name it qualitatively without stating \
            a figure.""";

    /**
     * Append {@link #FIRST_PASS_METRIC_GUARD_RULE} to a first-pass prompt iff the
     * guard is enabled. Package-private + static so the flag-gating is unit-testable
     * without standing up a router or the prompt registry.
     */
    static String appendFirstPassMetricGuard(String prompt, boolean enabled) {
        return enabled ? prompt + FIRST_PASS_METRIC_GUARD_RULE : prompt;
    }

    /**
     * Novelty-lens follow-up (2026-05-29) — appended to a revision prompt when
     * {@code revisionNoveltyPreservationEnabled}. Counterweights the revision
     * stage's subtractive bias so qualification calibrates a claim's strength to
     * its honest evidence label instead of erasing the genuinely novel residual
     * the room (phase-30) exists to surface. Adds NO output field, so it is
     * compatible with the pinned {@link #NARRATION_RESPONSE_SCHEMA}
     * ({@code additionalProperties:false}). See
     * {@link #revisionNoveltyPreservationEnabled}.
     */
    static final String NOVELTY_PRESERVATION_RULE = """


            NOVELTY PRESERVATION: this room's goal is genuinely novel structure, not safe \
            prose. Before you cut or soften anything, name the paper's single most surprising \
            result — the part that is NOT a restatement of a known relationship, but the \
            residual left after subtracting what was already expected (an exception to a \
            regularity, an unexplained deviation, reproducible structure the baseline does \
            not predict). That residual is the contribution: keep it, and state it as plainly \
            as the evidence allows. "Qualifying an over-claim" means lowering its STRENGTH to \
            the honest evidence label (a calibrated CONJECTURE or UNEXPLAINED_OBSERVATION) — \
            it does NOT mean deleting a supported surprise or hedging it into "broadly \
            consistent with expectations". Cut a claim only when it merely restates known work \
            or the data does not support it; a correctly-labeled bold conjecture beats a timid \
            non-claim.""";

    /**
     * Append {@link #NOVELTY_PRESERVATION_RULE} to a revision prompt iff the
     * guard is enabled. Package-private + static to mirror
     * {@link #appendFirstPassMetricGuard} and stay unit-testable without a router.
     */
    static String appendNoveltyPreservation(String prompt, boolean enabled) {
        return enabled ? prompt + NOVELTY_PRESERVATION_RULE : prompt;
    }

    /**
     * REVIEW_REPORT b72c4e10 §1 rec #4 — appended to a revision prompt when
     * {@code executedScopeGroundingEnabled}. Bars the two P-b72c-19 failures:
     * describing results over an interval no tool call swept, and reporting a
     * value with a flipped sign / truncated magnitude. Adds NO output field, so
     * it stays compatible with the pinned {@link #NARRATION_RESPONSE_SCHEMA}
     * ({@code additionalProperties:false}). See {@link #executedScopeGroundingEnabled}.
     */
    static final String EXECUTED_SCOPE_RULE = """


            EXECUTED-SCOPE GROUNDING: state results ONLY over numeric ranges or \
            intervals that a tool call in the professor's synthesis above actually \
            executed. If a call swept [300,700), you may discuss [300,700); you may \
            NOT report a density, trend, or result over an interval like [100,500) or \
            [700,1100) that no call covered. To mention an untested region, say plainly \
            it was "not tested" — never imply a computed result there. Report every \
            value with the exact sign and magnitude the tool returned (e.g. z=-0.523, \
            not +0.52): do not flip a sign, round a magnitude away, or restate a \
            computed statistic as "untested".""";

    /**
     * Append {@link #EXECUTED_SCOPE_RULE} to a revision prompt iff enabled.
     * Package-private + static to mirror {@link #appendNoveltyPreservation} and
     * stay unit-testable without a router.
     */
    static String appendExecutedScopeGrounding(String prompt, boolean enabled) {
        return enabled ? prompt + EXECUTED_SCOPE_RULE : prompt;
    }

    /**
     * 2026-06-06 review rec #1 — the DYNAMIC half of executed-scope grounding
     * (the VALUE PROVENANCE pattern applied to ranges). The static
     * {@link #EXECUTED_SCOPE_RULE} says "stay inside the executed ranges";
     * this names them — computed by the SAME
     * {@code NumericalConsistencyValidator.executedRangeUnion} the publish
     * gate tests against, so the prompt and the gate can never disagree.
     * Scope overreach was the dominant residual defect class when this landed
     * (P-b72c-212/229/244/249/250 — the density family chronically claims
     * adjacent unexecuted intervals). Empty union or any failure returns the
     * prompt unchanged.
     */
    static String appendExecutedUnion(String prompt, boolean enabled,
            java.util.List<com.emergentterrain.llm.ToolInvocation> trace) {
        if (!enabled) return prompt;
        try {
            java.util.List<double[]> union = com.emergentterrain.research
                    .NumericalConsistencyValidator.executedRangeUnion(trace);
            if (union.isEmpty()) return prompt;
            StringBuilder ranges = new StringBuilder();
            for (double[] r : union) {
                if (ranges.length() > 0) ranges.append(" ∪ ");
                ranges.append('[').append(trimNum(r[0])).append(", ")
                        .append(trimNum(r[1])).append(')');
            }
            return prompt + "\nEXECUTED COVERAGE — your tool calls covered exactly: "
                    + ranges + ". Every interval you state results over must lie INSIDE "
                    + "this union; never extrapolate to adjacent, wider, or finer ranges "
                    + "no call swept.";
        } catch (Exception e) {
            return prompt;
        }
    }

    private static String trimNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /**
     * REVIEW_REPORT b72c4e10 §1 rec #5 — appended to a revision prompt when
     * {@code definitionGroundingEnabled}. Forces the Mechanism/Methods prose to
     * define a computed quantity exactly as the tools computed it (the P-b72c-18
     * sqrt(window_size)-vs-window_start drift). Adds NO output field, so it stays
     * compatible with the pinned {@link #NARRATION_RESPONSE_SCHEMA}. See
     * {@link #definitionGroundingEnabled}.
     */
    static final String DEFINITION_GROUNDING_RULE = """


            DEFINITION GROUNDING: when the Mechanism or Methods prose defines a \
            computed quantity, state its definition EXACTLY as the tools computed it — \
            the same normalizer, window, and variables shown in the tool args/results. \
            If a call normalized by sqrt(window_size), write sqrt(window_size); do NOT \
            assert it is sqrt(window_start) or any normalization the trace does not \
            show. A definition that contradicts the tool call is a defect even when the \
            conclusion is unaffected.""";

    /**
     * Append {@link #DEFINITION_GROUNDING_RULE} to a revision prompt iff enabled.
     * Package-private + static to mirror {@link #appendNoveltyPreservation}.
     */
    static String appendDefinitionGrounding(String prompt, boolean enabled) {
        return enabled ? prompt + DEFINITION_GROUNDING_RULE : prompt;
    }

    static final String CITATION_RULE_ADDENDUM = """


            CITATION RULE (Phase 11): every numeric value in `findings` or `conclusion` \
            (any number with or without units, except citation indices like [1], equation \
            numbers like (eq. 3), and year mentions like 1884) must be IMMEDIATELY followed \
            by a tool-call marker `[call_N]` referring to the tool call that produced or \
            directly uses that value. Tool results in the professor's synthesis above are \
            already prefixed with their `[call_N]` IDs — reuse those exact markers.

            ALSO emit a parallel `numeric_claims` JSON array. One entry per cited value, \
            shape: {"value": 7.553, "units": "", "tool_call_id": "call_2", \
            "context": "ln(Kc/Kc0) at T=792 K", "source_location": "findings:1"}. \
            `source_location` is one of "hypothesis", "findings:N" (1-based block index), or \
            "conclusion". A number cited in prose without a matching `numeric_claims` entry \
            (or with a `tool_call_id` whose value does not appear in that call's result) \
            will reject the paper. Omit the array entirely if there are zero numeric values \
            in your prose; do NOT emit fabricated entries to fill it.
            """;

    /**
     * Prose-integrity addendum (flag-gated, domain-neutral) — reinforces the citation
     * rule and adds the two checks the always-live numerical-consistency D2 gaps catch
     * that the citation rule alone does not: untraceable numbers, claims over un-executed
     * ranges, and citing a FAILED call. Wording is deliberately domain-neutral (this runs
     * on every domain's papers). Appended to the revision prompt when
     * {@code app.research.narration.proseIntegrityEnabled} is ON. Targets review Rec 2/3:
     * P-b72c-45 (phantom number), P-b72c-43 (scope overreach), P-b72c-46 (cited a rejected
     * call) — all SUPPORTED-classified but downgraded to NULL_RESULT by the gate.
     */
    static final String PROSE_INTEGRITY_ADDENDUM = """


            PROSE INTEGRITY (every claim must trace to the tool results above; the validator \
            downgrades the paper to a null result otherwise — so OMIT, do not assert):
            - State a number ONLY if it appears in a tool-call result or its arguments above \
            (preregistered baselines and bound parameters count), or you derive it from such \
            numbers and SHOW the arithmetic in the same sentence. Never introduce a value, \
            percentage, or margin that no tool call produced.
            - Describe results ONLY over the ranges/intervals the tool calls actually \
            evaluated. Do not claim a trend, statistic, or value over any interval — wider, \
            shifted, or finer — that no tool call covered; your stated range must lie within \
            the union of the executed ranges.
            - Never cite a `[call_N]` marked FAILED / errored / rejected in the trace, and \
            never reference a call index that does not exist — a failed call produced no data.
            - State an uncertainty, error bound, ± margin, or derivative ONLY as a tool result \
            gives it, with the `[call_N]` citation in the SAME sentence — copy the ± value \
            verbatim from the result string; never recompute, estimate, or invent one. If no \
            tool output quantifies the uncertainty, say nothing about it.
            """;

    /** Flag-gated activation of {@link #PROSE_INTEGRITY_ADDENDUM} (static mirror of the
     *  {@code app.research.narration.proseIntegrityEnabled} @Value; settable in tests). */
    static volatile boolean proseIntegrityEnabled = false;

    /** Appends the prose-integrity addendum when enabled; identity otherwise. */
    static String withProseIntegrity(String prompt) {
        return proseIntegrityEnabled ? prompt + PROSE_INTEGRITY_ADDENDUM : prompt;
    }

    static String buildRevisionPromptFromSynthesis(PromptTemplateRegistry registry,
                                                    PaperDraft draft, NarrationResult firstPass,
                                                    String professorSynthesis) {
        return buildRevisionPromptFromSynthesis(registry, draft, firstPass, professorSynthesis, null);
    }

    /** Phase 6 D1.3 — when {@code literatureContext} is non-null/non-empty the
     *  {@code v2-from-synthesis-with-literature} variant carries it through to the
     *  student-revision step so the revised text can reference the cited closed-form. */
    static String buildRevisionPromptFromSynthesis(PromptTemplateRegistry registry,
                                                    PaperDraft draft, NarrationResult firstPass,
                                                    String professorSynthesis,
                                                    String literatureContext) {
        boolean isNull = draft.resultClassification() == ResultClassification.NULL_RESULT;
        String conclusionSpec = isNull
                ? "\"conclusion\":\"explicitly state the pre-registered prediction was NOT confirmed; "
                  + "what was expected vs found; what this rules out\""
                : "\"conclusion\":\"1-2 sentences: what result implies; one open question it raises\"";
        // Phase 10 — same accumulator-format treatment as the refinement loop:
        // drop the `param1=N, param2=M.M` literal that the LLM was parroting
        // into the published Hypothesis (P-344, P-355, P-356, P-359, P-362)
        // and Findings (P-344, P-359). Use the existing natural-language form
        // already in use at line 1379-1381 so the two prompts stay aligned.
        String paramsLine = draft.param1() != null
                ? "Computation parameters: starting position %d, scale %.1f\n"
                        .formatted(draft.param1(), draft.param2())
                : "";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("paramsLine",         paramsLine);
        params.put("domain",             draft.domain());
        params.put("topicName",          draft.topicName());
        params.put("professorSynthesis", professorSynthesis);
        params.put("hypothesis",         firstPass.hypothesis());
        // Phase 7 F2 — labeled per-computation layout so the student-revision LLM
        // knows which block to revise when only one block needs touching.
        params.put("findings",           formatFindingsForPrompt(firstPass));
        params.put("conclusion",         firstPass.conclusion());
        params.put("conclusionSpec",     conclusionSpec);
        if (literatureContext != null && !literatureContext.isEmpty()) {
            params.put("literatureContext", literatureContext);
            return withProseIntegrity(registry.render(PromptTemplateIds.NARRATION_REVISION_ID,
                    "v2-from-synthesis-with-literature", params) + CITATION_RULE_ADDENDUM
                    + conjectureDirective(draft));
        }
        return withProseIntegrity(registry.render(PromptTemplateIds.NARRATION_REVISION_ID, "v1-from-synthesis", params)
                + CITATION_RULE_ADDENDUM + conjectureDirective(draft));
    }

    // ─────────────────────────────────────────────────────────────────────────

    static String buildRevisionPrompt(PromptTemplateRegistry registry,
                                      PaperDraft draft, NarrationResult firstPass,
                                      List<String> weaknesses) {
        return buildRevisionPrompt(registry, draft, firstPass, weaknesses, null);
    }

    static String buildRevisionPrompt(PromptTemplateRegistry registry,
                                      PaperDraft draft, NarrationResult firstPass,
                                      List<String> weaknesses, String professorExperiment) {
        String weaknessList = weaknesses.isEmpty() ? "  (no textual weaknesses — address the new experimental evidence only)"
                : weaknesses.stream()
                        .map(w -> "  - " + w)
                        .collect(java.util.stream.Collectors.joining("\n"));
        boolean isNull = draft.resultClassification() == ResultClassification.NULL_RESULT;
        String conclusionSpec = isNull
                ? "\"conclusion\":\"explicitly state the pre-registered prediction was NOT confirmed; "
                  + "what was expected vs found; what this rules out\""
                : "\"conclusion\":\"1-2 sentences: what result implies; one open question it raises\"";
        String experimentSection = professorExperiment == null ? ""
                : "\nPROFESSOR-PRESCRIBED EXPERIMENT (run after the first draft):\n"
                  + professorExperiment
                  + "\nIntegrate this new evidence naturally. In your findings, reference it as a "
                  + "follow-up test. In your conclusion, state whether it resolves the weakness "
                  + "or raises a new question.\n";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",             draft.domain());
        params.put("topicName",          draft.topicName());
        params.put("hypothesis",         firstPass.hypothesis());
        // Phase 7 F2 — labeled per-computation layout in the revision prompt's
        // current-findings slot.
        params.put("findings",           formatFindingsForPrompt(firstPass));
        params.put("conclusion",         firstPass.conclusion());
        params.put("weaknessList",       weaknessList);
        params.put("experimentSection",  experimentSection);
        params.put("conclusionSpec",     conclusionSpec);
        return registry.render(PromptTemplateIds.NARRATION_REVISION_ID, "v1-from-critique", params)
                + conjectureDirective(draft);
    }

    private List<String> parseCritiqueWeaknesses(String response) {
        return parseCritiqueWeaknesses(objectMapper, response);
    }

    static List<String> parseCritiqueWeaknesses(ObjectMapper mapper, String response) {
        return parseCritiqueResult(mapper, response).weaknesses();
    }

    private CritiqueResult parseCritiqueResult(String response) {
        return parseCritiqueResult(objectMapper, response);
    }

    static CritiqueResult parseCritiqueResult(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank()) return new CritiqueResult(List.of(), null);
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start) return new CritiqueResult(List.of(), null);
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    mapper.readTree(response.substring(start, end + 1));

            // Parse weaknesses
            List<String> weaknesses = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode arr = root.get("weaknesses");
            if (arr != null && arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                    String w = node.asText(null);
                    if (w != null && !w.isBlank()) weaknesses.add(w);
                }
            }

            // Parse experimental_gap
            ExperimentalGap gap = null;
            com.fasterxml.jackson.databind.JsonNode gapNode = root.get("experimental_gap");
            if (gapNode != null && !gapNode.isNull() && !gapNode.isMissingNode()) {
                gap = parseExperimentalGap(gapNode);
            }
            return new CritiqueResult(List.copyOf(weaknesses), gap);
        } catch (Exception e) {
            return new CritiqueResult(List.of(), null);
        }
    }

    private static ExperimentalGap parseExperimentalGap(
            com.fasterxml.jackson.databind.JsonNode node) {
        String type        = node.path("type").asText(null);
        String description = node.path("description").asText(null);
        String rationale   = node.path("rationale").asText("");
        if (description == null || description.isBlank() || type == null) return null;

        if ("param".equals(type)) {
            int    p1 = node.path("param1").asInt(-1);
            double p2 = node.path("param2").asDouble(-1.0);
            if (p1 < 10 || p1 > 99 || p2 < 1.0 || p2 > 9.0) return null;
            return new ExperimentalGap(type, description, rationale,
                    p1, p2, null, null, null, null, 0.0, null);
        }
        if ("dynamic".equals(type)) {
            String predicate  = node.path("predicate").asText(null);
            int    rangeStart = node.path("range_start").asInt(-1);
            int    rangeEnd   = node.path("range_end").asInt(-1);
            String metric     = node.path("metric").asText("fraction_satisfying");
            double baseline   = node.path("baseline").asDouble(0.0);
            String hypothesis = node.path("hypothesis").asText(null);
            if (predicate == null || rangeStart < 1 || rangeEnd <= rangeStart
                    || rangeEnd - rangeStart > 500) return null;
            for (String token : predicate.replace(" AND ", "|").replace(" OR ", "|").split("\\|")) {
                if (!VALID_PREDICATES.contains(token.trim())) return null;
            }
            if (!VALID_METRICS.contains(metric)) return null;
            return new ExperimentalGap(type, description, rationale,
                    null, null, predicate, rangeStart, rangeEnd, metric, baseline, hypothesis);
        }
        return null;
    }

    private static final int MAX_REFINEMENTS = 2;

    /**
     * Multi-step computation loop: runs up to MAX_REFINEMENTS additional focused
     * computations before narration. Each iteration shows the LLM all findings so far;
     * it decides "refine" or "done". Runs inside refinementSlot (permits=2), decoupled
     * from narrationSlot so concurrent narrations are not blocked by GPU compute.
     * Dynamic papers (param1==null) are always skipped.
     */
    private PaperDraft maybeRefineComputation(PaperDraft draft) {
        if (draft.param1() == null) return draft;
        int topicIdx = MathPhysicsEngine.topicIndex(draft.domain(), draft.topicName());
        if (topicIdx < 0) return draft;

        MathPhysicsEngine.SearchParams focusSp =
                new MathPhysicsEngine.SearchParams(0.2, 0.0, 20, 0.5, 0.0);

        String accumulatedFindings = draft.rawFindings();
        int completedRefinements = 0;
        // Phase 26 §26.4 — track the last refined value so we can attempt
        // recognition once the loop completes (firing per-iteration would
        // multiply latency unnecessarily; the final value carries the
        // signal we care about).
        MathPhysicsEngine.ResearchResult latestRefined = null;

        // Track visited (param1, param2) pairs to reject duplicate refinement proposals
        Set<String> visitedParams = new HashSet<>();
        if (draft.param1() != null) {
            visitedParams.add(draft.param1() + "_" + String.format("%.1f", draft.param2()));
        }

        for (int iteration = 1; iteration <= MAX_REFINEMENTS; iteration++) {
            // Break the refinement loop when the gate is closed — refinement is
            // optional (it improves findings, not paper validity), so spending
            // any further fast-pool tokens here is wasted under budget pressure.
            if (simulationGate != null && !simulationGate.isOpen()) {
                log.info("LLM refinement {}/{} skipped: gate closed paperId={} reason={}",
                        iteration, MAX_REFINEMENTS, draft.paperId(), simulationGate.reason());
                break;
            }
            long start = System.currentTimeMillis();
            try {
                String prompt = buildRefinementPrompt(
                        draft, accumulatedFindings, iteration, MAX_REFINEMENTS);
                String response = router.callFast(prompt);
                RefinementDecision decision = parseRefinementDecision(response);
                if (decision == null) {
                    log.debug("LLM refinement {}/{} done (model says stop) paperId={}",
                            iteration, MAX_REFINEMENTS, draft.paperId());
                    break;
                }

                // Reject duplicate parameter proposals — these add no new information
                String paramKey = decision.param1() + "_" + String.format("%.1f", decision.param2());
                if (visitedParams.contains(paramKey)) {
                    log.debug("LLM refinement {}/{} proposed duplicate params (param1={}, param2={}) — stopping paperId={}",
                            iteration, MAX_REFINEMENTS, decision.param1(), decision.param2(), draft.paperId());
                    break;
                }
                visitedParams.add(paramKey);

                MathPhysicsEngine.ResearchResult refined = dispatchCompute(
                        draft.domain(), topicIdx, decision.param1(), decision.param2(), focusSp);
                latestRefined = refined;

                // Phase 10 — drop the `(param1=N, param2=M.M)` annotation that
                // was leaking verbatim into the published prose of P-344,
                // P-347, P-355, P-356, P-359, P-362 (6 of 21 papers per
                // REVIEW_REPORT.md refresh #3). The rationale and refined
                // findings already carry the substantive information the
                // narrator LLM needs to differentiate computations; the
                // harness-internal token names contribute nothing scientifically
                // and only invite parrot-back into the user-visible Hypothesis
                // and Findings.
                accumulatedFindings += "\nComputation %d — %s: %s"
                        .formatted(iteration + 1, decision.rationale(), refined.findings());
                completedRefinements++;

                log.info("LLM refinement {}/{} done paperId={} ms={} rationale=\"{}\"",
                        iteration, MAX_REFINEMENTS, draft.paperId(),
                        System.currentTimeMillis() - start, decision.rationale());

                // Min-1 guarantee satisfied — stop early only if other papers are waiting for narration
                if (narrationSlot.getQueueLength() > 0) {
                    log.debug("LLM refinement stopping after {}/{} — narration backlog depth={}",
                            iteration, MAX_REFINEMENTS, narrationSlot.getQueueLength());
                    break;
                }

            } catch (Exception e) {
                log.warn("LLM refinement {}/{} failed paperId={} ms={}: {}",
                        iteration, MAX_REFINEMENTS, draft.paperId(),
                        System.currentTimeMillis() - start, e.getMessage());
                break;
            }
        }

        if (completedRefinements == 0) return draft;

        // Phase 26 §26.4 — try to recognise the final refined value as a
        // known constant. Best-effort: any failure (orchestrator null,
        // sidecar down, validator reject, transport timeout) silently
        // skips — recognition is informational, never a publication
        // blocker. A successful PrimaryHit appends a citation line that
        // the future RecognitionCitationValidator (§26.4b) will
        // mechanically verify against the audit chain.
        accumulatedFindings = appendRefinementRecognition(
                accumulatedFindings, latestRefined, draft.paperId());

        // 2026-06-05 — copy-rebuild must preserve kind + frontierDomain: the 27-arg
        // compat ctor silently reset kind to DOMAIN (latent) and would drop the
        // frontier threading the professor frontierHint depends on.
        return new PaperDraft(
                draft.roomId(), draft.authorBotId(), draft.domain(), draft.topicName(),
                draft.title(), accumulatedFindings,
                draft.templateHypothesis(), draft.templateConclusion(),
                draft.methodology(), draft.reviewerBotIds(),
                draft.peerScore(), draft.authorScore(), draft.anomalyZScore(),
                draft.gameTick(), draft.publishedAt(), draft.generation(),
                draft.paperId(), draft.param1(), draft.param2(),
                draft.isFollowUp(), draft.predictedScore(), draft.priorMethodology(),
                draft.recentPapers(), draft.researchHypothesis(),
                draft.preRegistration(), draft.resultClassification(),
                draft.plannerQuestionId(), draft.kind(), draft.frontierDomain());
    }

    /**
     * Phase 26 §26.4 — try to recognise the final refined value as a
     * known constant via {@link com.emergentterrain.research.recognise.ConstantRecognitionService}.
     * Best-effort: any failure path (orchestrator null, sidecar down,
     * non-finite value, transport timeout, validator reject) silently
     * returns the input findings unchanged. A successful
     * {@code PrimaryHit} appends a citation line built by
     * {@link com.emergentterrain.research.recognise.RecognitionResult.PrimaryHit#formatAsCitationLine()}
     * so the future
     * {@link com.emergentterrain.research.recognise.RecognitionCitationValidator}
     * (§26.4b) can mechanically verify the {@code cite-recognised:*}
     * handle against the audit chain.
     *
     * <p>Recognition fires once per paper (after the refinement loop)
     * rather than per iteration: each {@code recognise} call costs
     * ~50-200ms wall, and the final value carries the signal the
     * narrator actually surfaces in the published prose.
     *
     * <p>Skipped when the refined primaryValue is non-finite, very
     * close to zero (PSLQ has no chance to find a meaningful
     * relation), or the orchestrator bean is missing / unavailable.
     */
    /**
     * 2026-06-05 review rec #1 — recognize the fitted constant of a conjecture-class
     * paper (CONJECTURE_INVESTIGATION / ANOMALY_PROMOTION). The constant {@code c}
     * arrives via {@code draft.param2()} (set by the publish paths in
     * {@code ResearchCycleService}); domain MATHEMATICS. Same best-effort contract
     * as {@link #appendRefinementRecognition}: PrimaryHit → citation line appended;
     * anything else → input returned unchanged.
     */
    String appendConjectureConstantRecognition(String synthesis, PaperDraft draft) {
        if (synthesis == null || draft == null || draft.param2() == null) return synthesis;
        if (draft.kind() != com.emergentterrain.research.PaperKind.CONJECTURE_INVESTIGATION
                && draft.kind() != com.emergentterrain.research.PaperKind.ANOMALY_PROMOTION) {
            return synthesis;
        }
        double c = draft.param2();
        if (!Double.isFinite(c) || Math.abs(c) < 1e-10) return synthesis;
        // Near-match FIRST: PSLQ/LLL find exact relations and correctly never match
        // a mined (noisy) constant — the catalog match is what fires in practice.
        java.util.Optional<com.emergentterrain.research.recognise.NamedConstantNearMatch.Match>
                near = com.emergentterrain.research.recognise.NamedConstantNearMatch.match(c);
        if (near.isPresent()) {
            log.info("conjecture-constant near-match paperId={} c={} -> {} (relErr={}%)",
                    draft.paperId(), c, near.get().constant().symbol(),
                    String.format("%.2f", near.get().relErr() * 100));
            return synthesis + "\n\nCONSTANT RECOGNITION (near-match, not an identification) — "
                    + "the conjecture's fitted constant " + near.get().describe(c)
                    + ". If this identification is plausible given your evidence, NAME the "
                    + "constant in the findings and discuss the connection (as a conjecture, "
                    + "not a proof); if the numeric agreement looks coincidental, say why.";
        }
        if (constantRecognitionService == null || !constantRecognitionService.isAvailable()) {
            return synthesis;
        }
        try {
            // 2026-06-06 review rec #4 — the sidecar's /recognise/constant accepts
            // only basis BASIC|PHYSICS; passing the paper domain ("MATHEMATICS")
            // bad_request'd every call (10× in the 05:14 session). BASIC is the
            // number-theory/general basis.
            com.emergentterrain.research.recognise.RecognitionResult result =
                    constantRecognitionService.recogniseConstant(
                            Double.toString(c), "BASIC",
                            "conjecture-constant paper=" + draft.paperId());
            if (result instanceof
                    com.emergentterrain.research.recognise.RecognitionResult.PrimaryHit hit) {
                log.info("conjecture-constant recognition hit paperId={} c={} name={} handle={}",
                        draft.paperId(), c, hit.displayName(), hit.citationHandle());
                return synthesis + "\n\nCONSTANT RECOGNITION — the conjecture's fitted constant "
                        + c + " was recognised as: " + hit.formatAsCitationLine()
                        + "\nIf the identification is plausible given your evidence, NAME the "
                        + "constant in the findings and discuss the connection; if the numeric "
                        + "agreement looks coincidental, say why.";
            }
        } catch (Exception e) {
            log.debug("conjecture-constant recognition skipped paperId={}: {}",
                    draft.paperId(), e.getMessage());
        }
        return synthesis;
    }

    private String appendRefinementRecognition(String findings,
            MathPhysicsEngine.ResearchResult latestRefined,
            String paperId) {
        if (latestRefined == null) return findings;
        if (constantRecognitionService == null
                || !constantRecognitionService.isAvailable()) {
            return findings;
        }
        double v = latestRefined.primaryValue();
        if (!Double.isFinite(v) || Math.abs(v) < 1e-10) return findings;
        try {
            com.emergentterrain.research.recognise.RecognitionResult result =
                    constantRecognitionService.recogniseConstant(
                            Double.toString(v), "PHYSICS",
                            "paper=" + paperId);
            if (result instanceof
                    com.emergentterrain.research.recognise.RecognitionResult.PrimaryHit hit) {
                log.info("LLM refinement recognition hit paperId={} value={} name={} handle={}",
                        paperId, v, hit.displayName(), hit.citationHandle());
                return findings + "\n" + hit.formatAsCitationLine();
            }
        } catch (Exception e) {
            // Best-effort: any failure (transport, parse, safety, sidecar
            // down) silently passes through. Recognition is informational;
            // a failure here must not block paper publication.
            log.debug("LLM refinement recognition skipped paperId={}: {}",
                    paperId, e.getMessage());
        }
        return findings;
    }

    private String buildRefinementPrompt(PaperDraft draft, String accumulatedFindings,
                                          int iteration, int maxIterations) {
        String compact = compactRawFindings(accumulatedFindings);
        String iterContext = iteration == 1
                ? "initial result"
                : "results so far (%d computations completed)".formatted(iteration);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("iterContext",          iterContext);
        params.put("domain",               draft.domain());
        params.put("topicName",            draft.topicName());
        params.put("methodology",          draft.methodology());
        params.put("param1",               String.valueOf(draft.param1()));
        params.put("param2",               String.format("%.1f", draft.param2()));
        params.put("accumulatedFindings",  compact);
        params.put("remainingIterations",  String.valueOf(maxIterations - iteration + 1));
        params.put("maxIterations",        String.valueOf(maxIterations));
        return promptRegistry.render(PromptTemplateIds.NARRATION_REFINEMENT_ID, params)
                + conjectureDirective(draft);
    }

    private RefinementDecision parseRefinementDecision(String response) {
        return parseRefinementDecision(objectMapper, response);
    }

    static RefinementDecision parseRefinementDecision(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank()) return null;
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    mapper.readTree(response.substring(start, end + 1));
            if ("done".equals(root.path("action").asText(null))) return null;
            int    p1 = root.path("param1").asInt(-1);
            double p2 = root.path("param2").asDouble(-1.0);
            String rationale = root.path("rationale").asText("");
            if (p1 < 10 || p1 > 99 || p2 < 1.0 || p2 > 9.0) return null;
            return new RefinementDecision(p1, p2, rationale);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Statistical framing (Gaps 4 & 5) ---

    /**
     * Rewrites rawFindings with appropriate significant figures and uncertainty qualifiers.
     * Fast Ollama call; falls back to the unmodified draft on any failure.
     * Skipped for dynamic papers (param1 == null).
     */
    private PaperDraft applyStatisticalFraming(PaperDraft draft) {
        if (draft.param1() == null) return draft;
        String raw = draft.rawFindings();
        if (raw == null || raw.isBlank()) return draft;
        try {
            long start = System.currentTimeMillis();
            String response = router.callFast(buildStatisticalFramingPrompt(promptRegistry, raw, draft.domain(), draft.topicName()));
            String framed = parseStatisticalFraming(response);
            if (framed != null) {
                log.info("LLM statistical framing done paperId={} ms={}", draft.paperId(),
                        System.currentTimeMillis() - start);
                // 2026-06-05 — preserve kind + frontierDomain through the copy-rebuild
                // (27-arg compat ctor reset kind to DOMAIN and dropped the frontier flag).
                return new PaperDraft(
                        draft.roomId(), draft.authorBotId(), draft.domain(), draft.topicName(),
                        draft.title(), framed,
                        draft.templateHypothesis(), draft.templateConclusion(),
                        draft.methodology(), draft.reviewerBotIds(),
                        draft.peerScore(), draft.authorScore(), draft.anomalyZScore(),
                        draft.gameTick(), draft.publishedAt(), draft.generation(),
                        draft.paperId(), draft.param1(), draft.param2(),
                        draft.isFollowUp(), draft.predictedScore(), draft.priorMethodology(),
                        draft.recentPapers(), draft.researchHypothesis(),
                        draft.preRegistration(), draft.resultClassification(),
                        draft.plannerQuestionId(), draft.kind(), draft.frontierDomain());
            }
        } catch (Exception e) {
            log.warn("LLM statistical framing failed paperId={}: {} — using raw findings",
                    draft.paperId(), e.getMessage());
        }
        return draft;
    }

    static String buildStatisticalFramingPrompt(PromptTemplateRegistry registry,
                                                 String rawFindings, String domain, String topicName) {
        String trimmed = rawFindings.length() > 500 ? rawFindings.substring(0, 500) + "…" : rawFindings;
        return registry.render(PromptTemplateIds.NARRATION_STATISTICAL_FRAMING_ID, Map.of(
                "domain",      domain,
                "topicName",   topicName,
                "rawFindings", trimmed));
    }

    static String parseStatisticalFraming(String response) {
        if (response == null || response.isBlank()) return null;
        String cleaned = response.strip();
        // Reject JSON (model confused about output format), empty, or suspiciously short
        if (cleaned.startsWith("{") || cleaned.startsWith("[") || cleaned.length() < 20) return null;
        // Strip common preambles like "Here is the rewritten..." if the model ignores the instruction
        for (String preamble : new String[]{"Here is", "Here's", "Rewritten:", "Revised:", "Output:"}) {
            if (cleaned.startsWith(preamble)) {
                int nl = cleaned.indexOf('\n');
                if (nl > 0 && nl < 80) {
                    cleaned = cleaned.substring(nl + 1).strip();
                } else if (preamble.endsWith(":")) {
                    // Single-line colon-terminated preamble — strip just the prefix
                    cleaned = cleaned.substring(preamble.length()).stripLeading();
                }
                break;
            }
        }
        return cleaned.isBlank() ? null : cleaned;
    }

    private String buildNarrationPrompt(PaperDraft draft) {
        // Phase 23.6 — conjecture-investigation papers use a structurally
        // different prompt (Mechanism + Adversarial Check sections) that
        // produces prose the LabelFloor's text heuristics can admit for an
        // EMPIRICAL_RESULT proposal. Branch early — none of the
        // parameter-sweep slot prep below applies to the conjecture path.
        if (draft.kind() == com.emergentterrain.research.PaperKind.CONJECTURE_INVESTIGATION) {
            return buildConjectureInvestigationPrompt(draft);
        }
        StringBuilder priorWork = new StringBuilder();
        draft.recentPapers().stream()
                .filter(p -> p.domain().equalsIgnoreCase(draft.domain())
                          && p.topic().equalsIgnoreCase(draft.topicName()))
                // Phase 16 P6a — when corpus-citations.suppress-rejected is on,
                // drop REJECTED priors entirely so the narrator can't cite
                // them. Default false: include all priors with verdict tags
                // (see the .map step below).
                .filter(p -> !corpusCitationsSuppressRejected
                          || !PromptSanitizer.isRejectedStatus(p.status()))
                .limit(3)
                .forEach(p -> {
                    String snippet = p.findings().length() > 160
                            ? p.findings().substring(0, 160) + "…" : p.findings();
                    // Phase 16 P6a — prepend a verdict tag for REJECTED /
                    // DISPUTED priors so the narrator LLM sees the verdict
                    // alongside the paperId and adjusts its Related Work
                    // citation accordingly. Empty string for clean statuses.
                    String verdictTag = PromptSanitizer.verdictTagFor(p.status());
                    priorWork.append("  %s%s [score %.2f]: %s%n"
                            .formatted(verdictTag, p.paperId(), p.peerScore(), snippet));
                });
        String priorSection = priorWork.isEmpty()
                ? "  (no prior papers in this domain/topic yet)\n"
                : priorWork.toString();

        String paramsLine = (draft.param1() != null)
                ? "Computation parameters: starting position %d, scale %.1f\n"
                        .formatted(draft.param1(), draft.param2())
                : "";

        String followUpNote;
        if (draft.isFollowUp()) {
            String predNote = (draft.predictedScore() != null)
                    ? " The prior paper predicted a quality score of ~%.2f for this region; actual score is %.2f. Explicitly compare prediction to outcome."
                            .formatted(draft.predictedScore(), draft.peerScore())
                    : "";
            String methodChain = (draft.priorMethodology() != null)
                    ? " Prior paper used %s methodology; this paper uses %s — acknowledge the methodological progression if relevant."
                            .formatted(draft.priorMethodology(), draft.methodology())
                    : "";
            followUpNote = "IMPORTANT: This paper is a deliberate follow-up investigation — the author's "
                    + "prior conclusion suggested this exact parameter region. Acknowledge the chain: "
                    + "reference a prior paper by ID, state what was predicted, and report whether "
                    + "this result confirms, refines, or surprises that prediction."
                    + predNote + methodChain + "\n";
        } else {
            followUpNote = "";
        }

        String anomalyNote;
        double absZ = Math.abs(draft.anomalyZScore());
        if (absZ >= 2.5) {
            anomalyNote = (draft.anomalyZScore() > 0
                    ? "DISCOVERY CONTEXT: This result is anomalously strong (z=+%.1f σ above the room's historical distribution for this topic). Frame the conclusion as a notable discovery, not a routine survey. Explain what makes this region special."
                    : "NOTE: This result is anomalously weak (z=%.1f σ, unusually below the room average for this topic). The conclusion should acknowledge the unexpected underperformance and suggest why this region may be structurally less productive.")
                    .formatted(draft.anomalyZScore()) + "\n";
        } else if (absZ >= 1.5) {
            anomalyNote = "NOTE: This result sits at z=%.1f σ from the room baseline — moderately noteworthy. Mention whether the result is stronger or weaker than typical for this topic.\n"
                    .formatted(draft.anomalyZScore());
        } else {
            anomalyNote = "";
        }

        String scaffoldHyp = (draft.templateHypothesis() != null && !draft.templateHypothesis().isBlank())
                ? draft.templateHypothesis() : "(none)";
        String scaffoldCon = (draft.templateConclusion() != null && !draft.templateConclusion().isBlank())
                ? draft.templateConclusion() : "(none)";

        // Pre-registration section — present only when formulation captured a pre-reg
        String preRegSection = "";
        if (draft.preRegistration() != null) {
            PreRegistration pr = draft.preRegistration();
            String mapLine = (pr.mappingNotes() != null && !pr.mappingNotes().isBlank()
                    && !pr.mappingNotes().equals("null") && !pr.mappingNotes().isEmpty())
                    ? "Cross-domain mapping used: " + pr.mappingNotes() + "\n"
                    : "";
            String classLine = (draft.resultClassification() != null)
                    ? "Deterministic result classification: " + draft.resultClassification() + "\n"
                    : "";
            String nullWarn = (draft.resultClassification() == ResultClassification.NULL_RESULT)
                    ? "⚠ NULL RESULT: The computation does not support the pre-registered prediction. "
                      + "Your conclusion MUST explicitly state this. Do NOT reframe as partial support, "
                      + "\"selective\" confirmation, or attribute failure to material/domain properties. "
                      + "A null result is a valid finding — report it honestly.\n"
                    : "";
            preRegSection = """

                    Pre-registered hypothesis (locked before computation ran): %s
                    Pre-registered prediction: %s
                    Falsification condition: %s
                    %s%s%s""".formatted(
                            pr.hypothesis(), pr.prediction(),
                            pr.falsificationCondition() != null ? pr.falsificationCondition() : "(not specified)",
                            mapLine, classLine, nullWarn);
        }

        // Conclusion instruction varies by classification to prevent null-result reframing
        boolean isNull = draft.resultClassification() == ResultClassification.NULL_RESULT;
        String conclusionSpec = isNull
                ? "\"conclusion\":\"1-2 sentences: explicitly state the pre-registered prediction "
                  + "was NOT confirmed; what was expected, what was found, what this rules out\""
                : "\"conclusion\":\"1-2 sentences: assess whether the pre-registered prediction was "
                  + "met (if applicable); what this result implies; one concrete open question it raises\"";

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",         draft.domain());
        params.put("topicName",      draft.topicName());
        params.put("methodology",    draft.methodology());
        params.put("title",          draft.title());
        params.put("generation",     String.valueOf(draft.generation()));
        params.put("paramsLine",     paramsLine);
        params.put("followUpNote",   followUpNote);
        params.put("anomalyNote",    anomalyNote);
        params.put("rawFindings",    compactRawFindings(draft.rawFindings()));
        params.put("scaffoldHyp",    scaffoldHyp);
        params.put("scaffoldCon",    scaffoldCon);
        params.put("priorPapers",    priorSection.trim());
        params.put("preRegSection",  preRegSection);
        params.put("conclusionSpec", conclusionSpec);
        // Phase 7 F2 — when the structured-findings flag is on AND maxComputations > 1,
        // render a v3 variant that asks for one prose block per computation. When the
        // flag is off (or maxComputations==1), keep the v1 / v2-with-discussion path
        // exactly as before — flag-off bit-identical to F1-baseline.
        if (structuredFindingsEnabled && maxComputations > 1) {
            params.put("maxComputations",      String.valueOf(maxComputations));
            params.put("findingsSpec",         renderStructuredFindingsSpec(maxComputations));
            params.put("findingsInstructions", renderStructuredFindingsInstructions(maxComputations));
            String variant = discussionSectionEnabled
                    ? "v3-structured-findings-with-discussion"
                    : "v3-structured-findings";
            return promptRegistry.render(PromptTemplateIds.NARRATION_PRIMARY_ID, variant, params);
        }
        // Phase 6 D2 — pick v2-with-discussion when the flag is on; otherwise v1.
        // Both variants take identical slots.
        if (discussionSectionEnabled) {
            return promptRegistry.render(PromptTemplateIds.NARRATION_PRIMARY_ID,
                    "v2-with-discussion", params);
        }
        return promptRegistry.render(PromptTemplateIds.NARRATION_PRIMARY_ID,
                PromptTemplateIds.NARRATION_PRIMARY_VERSION, params);
    }

    /**
     * Phase 7 F2 / Phase 23.6 — returns the active narration template version
     * that buildNarrationPrompt selects, given the current flag state and the
     * paper's {@link com.emergentterrain.research.PaperKind}. Stamped onto the
     * persisted ResearchPaper so per-variant rollback statistics see the actual
     * variant used rather than the constant "v1".
     */
    String activeNarrationTemplateVersion(PaperDraft draft) {
        if (draft != null
                && draft.kind() == com.emergentterrain.research.PaperKind.CONJECTURE_INVESTIGATION) {
            return PromptTemplateIds.NARRATION_CONJECTURE_INVESTIGATION_VERSION;
        }
        if (structuredFindingsEnabled && maxComputations > 1) {
            return discussionSectionEnabled
                    ? "v3-structured-findings-with-discussion"
                    : "v3-structured-findings";
        }
        if (discussionSectionEnabled) return "v2-with-discussion";
        return PromptTemplateIds.NARRATION_PRIMARY_VERSION;
    }

    /**
     * Phase 23.6 — returns the active narration template id, kind-aware. The
     * conjecture-investigation path reports its own template id so the
     * persisted ResearchPaper's narrationTemplateId is unambiguous.
     */
    String activeNarrationTemplateId(PaperDraft draft) {
        if (draft != null
                && draft.kind() == com.emergentterrain.research.PaperKind.CONJECTURE_INVESTIGATION) {
            return PromptTemplateIds.NARRATION_CONJECTURE_INVESTIGATION_ID;
        }
        return PromptTemplateIds.NARRATION_PRIMARY_ID;
    }

    /**
     * Phase 23.6 — render the {@link PromptTemplateIds#NARRATION_CONJECTURE_INVESTIGATION_ID}
     * template against the conjecture draft. The miner pre-fills hypothesis /
     * findings / conclusion; the narrator's job is to add Mechanism +
     * Adversarial Check sections so the LabelFloor will admit the panel's
     * EMPIRICAL_RESULT proposal when the panel earns it.
     */
    private String buildConjectureInvestigationPrompt(PaperDraft draft) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("domain",     draft.domain() != null ? draft.domain() : "");
        params.put("topicName",  draft.topicName() != null ? draft.topicName() : "");
        params.put("hypothesis", draft.templateHypothesis() != null ? draft.templateHypothesis() : "");
        params.put("findings",   draft.rawFindings() != null ? draft.rawFindings() : "");
        params.put("conclusion", draft.templateConclusion() != null ? draft.templateConclusion() : "");
        return promptRegistry.render(
                PromptTemplateIds.NARRATION_CONJECTURE_INVESTIGATION_ID, params);
    }

    /**
     * Phase 7 F2 — render the {@code findingsSpec} slot for the v3 templates as a
     * comma-separated list of {@code "computation_N":"<paragraph N>"} pairs. The
     * narrator must emit exactly maxComputations keyed paragraphs; the parser
     * silently drops blanks so a missing final block degrades to NEEDS_REVISION
     * (via the per-block truncation guard) rather than crashing.
     */
    static String renderStructuredFindingsSpec(int maxComputations) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= maxComputations; i++) {
            if (i > 1) sb.append(',');
            sb.append('"').append("computation_").append(i).append("\":");
            sb.append('"').append("<paragraph ").append(i).append('>').append('"');
        }
        return sb.toString();
    }

    /**
     * Phase 7 F2 — instructions paragraph telling the narrator how to write each
     * computation block. Lives in its own slot so v3 variants stay readable
     * regardless of how maxComputations grows.
     */
    static String renderStructuredFindingsInstructions(int maxComputations) {
        return ("Findings rules: emit exactly %d non-empty paragraphs, keyed computation_1 "
                + "through computation_%d. Each paragraph is 2-3 plain-prose sentences "
                + "interpreting the corresponding raw-output block above — its pattern, "
                + "trend, or notable numeric. Do NOT reproduce tables. Reference the "
                + "closed-form equation governing this topic when relevant. Cite prior "
                + "papers by ID where they bear on the block's interpretation.")
                .formatted(maxComputations, maxComputations);
    }

    private static String compactRawFindings(String raw) {
        if (raw == null || raw.isBlank()) return "(none)";
        if (raw.length() <= 350) return raw;
        // Count Unicode arrow data points (e.g. Vf=10%→1513MPa)
        int arrowCount = 0;
        for (int i = raw.indexOf('→'); i >= 0; i = raw.indexOf('→', i + 1)) arrowCount++;
        if (arrowCount >= 5) {
            // Preamble: text before the first key→value token
            int firstArrow = raw.indexOf('→');
            int blockStart = raw.lastIndexOf(' ', firstArrow - 2) + 1;
            String preamble = blockStart > 0 ? raw.substring(0, blockStart).trim() : "";
            // Sample: keep first two key→value tokens to show the pattern and scale
            StringBuilder sample = new StringBuilder();
            int pos = blockStart, kv = 0;
            while (pos < raw.length() && kv < 2) {
                int next = raw.indexOf(' ', pos + 1);
                if (next < 0) next = raw.length();
                String token = raw.substring(pos, next).trim();
                if (token.contains("→")) { sample.append(token).append(" "); kv++; }
                pos = next + 1;
            }
            // Extract all numeric values after arrows for range summary
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            int n = 0;
            pos = 0;
            while ((pos = raw.indexOf('→', pos)) >= 0) {
                int end = pos + 1;
                while (end < raw.length()
                        && (Character.isDigit(raw.charAt(end)) || raw.charAt(end) == '.')) end++;
                if (end > pos + 1) {
                    try {
                        double v = Double.parseDouble(raw.substring(pos + 1, end));
                        min = Math.min(min, v); max = Math.max(max, v); n++;
                    } catch (NumberFormatException ignored) {}
                }
                pos = end;
            }
            String stats = n > 0
                    ? "… [%d total; min=%.0f, max=%.0f]".formatted(n, min, max)
                    : "… [%d data points]".formatted(arrowCount);
            // Tail: summary sentence(s) after the last data point
            int lastArrow = raw.lastIndexOf('→');
            int tailStart = raw.indexOf('.', lastArrow + 5);
            String tail   = (tailStart > 0 && tailStart + 1 < raw.length())
                    ? raw.substring(tailStart + 1).trim() : "";
            return (preamble.isEmpty() ? "" : preamble + "\n")
                    + sample.toString().trim() + " " + stats
                    + (tail.isEmpty() ? "" : "\n" + tail);
        }
        return raw.substring(0, 350) + "…";
    }

    private static final java.util.regex.Pattern PAPER_ID_PATTERN =
            java.util.regex.Pattern.compile("P-[0-9a-fA-F]{4}-\\d+");

    private NarrationResult parseNarrationResult(String response) {
        return parseNarrationResult(objectMapper, response);
    }

    static NarrationResult parseNarrationResult(ObjectMapper mapper, String response) {
        if (response == null || response.isBlank())
            return new NarrationResult(null, null, null, null, null, 0.5, List.of());
        int start = response.indexOf('{');
        int end   = response.lastIndexOf('}');
        if (start < 0 || end <= start)
            return new NarrationResult(null, null, null, null, null, 0.5, List.of());
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    mapper.readTree(response.substring(start, end + 1));
            FollowUpParams followUp = null;
            com.fasterxml.jackson.databind.JsonNode fuNode = root.get("followUp");
            if (fuNode != null && !fuNode.isNull() && !fuNode.isMissingNode()) {
                int    p1 = fuNode.path("param1").asInt(-1);
                double p2 = fuNode.path("param2").asDouble(-1.0);
                if (p1 >= 10 && p1 <= 99 && p2 >= 1.0 && p2 <= 9.0) {
                    double ps = fuNode.path("predictedScore").asDouble(-1.0);
                    Double predictedScore = (ps >= 0.0 && ps <= 1.0) ? ps : null;
                    followUp = new FollowUpParams(p1, p2, predictedScore, null);
                }
            }
            double confidenceScore = Math.max(0.0, Math.min(1.0,
                    root.path("confidenceScore").asDouble(0.5)));

            // Phase 2b — typed citations override the flat cited_paper_ids when present.
            // The LLM may emit either shape; we accept both and prefer the richer one.
            List<NarrationResult.TypedCitation> typedCitations = new java.util.ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode typedNode = root.get("cited_papers");
            if (typedNode != null && typedNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode e : typedNode) {
                    String id = e.path("id").asText(null);
                    // 2026-05-22 — accept unicode-dashed IDs (U+2010 / U+2011 /
                    // U+2012 / U+2013); they're a typographic accident, not a
                    // semantic difference. Normalise to ASCII so downstream
                    // dedup / lookup / publish flow treats them the same.
                    id = com.emergentterrain.research.UniversityService
                            .normalizeUnicodeDashes(id);
                    if (id == null || !PAPER_ID_PATTERN.matcher(id).matches()) continue;
                    String typeStr = e.path("type").asText("EXTENDS").toUpperCase();
                    com.emergentterrain.research.RelationshipType type;
                    try {
                        type = com.emergentterrain.research.RelationshipType.valueOf(typeStr);
                    } catch (IllegalArgumentException iae) {
                        type = com.emergentterrain.research.RelationshipType.EXTENDS;
                    }
                    double conf = Math.max(0.0, Math.min(1.0, e.path("confidence").asDouble(0.7)));
                    typedCitations.add(new NarrationResult.TypedCitation(id, type, conf));
                }
            }

            List<String> citedPaperIds = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode citedNode = root.get("cited_paper_ids");
            if (citedNode != null && citedNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode idNode : citedNode) {
                    String id = com.emergentterrain.research.UniversityService
                            .normalizeUnicodeDashes(idNode.asText(null));
                    if (id != null && PAPER_ID_PATTERN.matcher(id).matches()) citedPaperIds.add(id);
                }
            }
            // When typed citations are present, the flat ID list mirrors them so legacy
            // consumers (regex fallback, audit logs) still see every cited paper.
            if (!typedCitations.isEmpty() && citedPaperIds.isEmpty()) {
                for (NarrationResult.TypedCitation t : typedCitations) citedPaperIds.add(t.paperId());
            }

            // Phase 4 Iter 9 — optional formal_findings array. Each entry maps to a
            // FormalFinding row when the findings flag is on; malformed entries
            // are silently skipped (don't fail the whole paper for one bad finding).
            List<NarrationResult.ExtractedFinding> formalFindings = new java.util.ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode findingsNode = root.get("formal_findings");
            if (findingsNode != null && findingsNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode f : findingsNode) {
                    NarrationResult.ExtractedFinding ef = parseFormalFinding(f);
                    if (ef != null) formalFindings.add(ef);
                }
            }

            // Phase 6 D2 — optional Discussion + Related-Work fields. Null when the
            // narrator did not emit the keys (D2 flag off, or v1 template); the
            // markdown writer omits the sections in that case.
            String discussion = optionalText(root, "discussion");
            String relatedWork = optionalText(root, "related_work");
            // Phase 7 F2 — findings may be either a flat string (legacy) or a map
            // keyed by computation_N. Either shape funnels into the canonical
            // Map<Integer, String> on NarrationResult.
            java.util.Map<Integer, String> findingsMap = parseFindingsShape(root.get("findings"));
            // Phase 11 C2 — parse numeric_claims if present; null when absent (pre-Phase-11
            // papers or runs where the prompt's new schema rule did not yet reach the LLM).
            java.util.List<NarrationResult.NumericClaim> numericClaims =
                    parseNumericClaims(root.get("numeric_claims"));
            return new NarrationResult(
                    root.path("hypothesis").asText(null),
                    findingsMap,
                    root.path("conclusion").asText(null),
                    followUp, null, confidenceScore, List.copyOf(citedPaperIds),
                    null, List.copyOf(typedCitations), List.copyOf(formalFindings),
                    null, null, discussion, relatedWork, null, null, null, numericClaims);
        } catch (Exception e) {
            log.warn("LLM narration: JSON parse failed: {}", e.getMessage());
            return new NarrationResult(null, null, null, null, null, 0.5, List.of());
        }
    }

    /**
     * Phase 7 F2 — parse the {@code findings} JSON node into the canonical
     * Map&lt;Integer, String&gt; shape. Accepts three node shapes:
     * <ul>
     *   <li>Text — wrapped as {1: prose}, the F1-baseline shape.</li>
     *   <li>Object with {@code computation_N} or {@code N} keys — parsed to a
     *       sorted map keyed by the parsed integer; blank / null values dropped.</li>
     *   <li>Anything else (array, number, null, missing) — null.</li>
     * </ul>
     */
    static java.util.Map<Integer, String> parseFindingsShape(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) {
            String s = node.asText();
            return (s == null || s.isBlank()) ? null : java.util.Map.of(1, s);
        }
        if (node.isObject()) {
            java.util.Map<Integer, String> raw = new java.util.LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                Integer idx = parseComputationKey(entry.getKey());
                if (idx == null) return;
                com.fasterxml.jackson.databind.JsonNode v = entry.getValue();
                if (v == null || !v.isTextual()) return;
                String prose = v.asText();
                if (prose != null && !prose.isBlank()) raw.put(idx, prose);
            });
            return NarrationResult.orderedFindingsMap(raw);
        }
        return null;
    }

    /**
     * Phase 7 F2 — parse a findings-map key into the 1-based computation index.
     * Accepts {@code "computation_1"}, {@code "computation 1"}, {@code "1"}, or
     * {@code "comp1"} forms. Returns null when the key cannot be mapped to a
     * positive integer (silently skipped by the caller).
     */
    static Integer parseComputationKey(String key) {
        if (key == null) return null;
        String trimmed = key.trim();
        // Strip a leading "computation" / "comp" / "block" prefix plus any separator.
        String body = trimmed
                .replaceFirst("(?i)^(computation|comp|block)[\\s_:#]*", "");
        if (body.isEmpty()) return null;
        try {
            int n = Integer.parseInt(body);
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Phase 11 C2 — parse the {@code numeric_claims} array on the LLM output.
     * Returns null when the field is absent (pre-Phase-11 papers, or runs where
     * the prompt's new schema rule hasn't reached the LLM yet). Returns an
     * empty list when the array is present but empty. Skips malformed entries
     * silently — the validator treats a too-short array as separate coverage
     * failures, but a single bad entry must not poison the whole list.
     *
     * <p>Expected entry shape:
     * <pre>{"value": 7.553, "units": "", "tool_call_id": "call_2",
     *      "context": "ln(Kc/Kc0) at T=792 K", "source_location": "findings:1"}</pre>
     */
    static java.util.List<NarrationResult.NumericClaim> parseNumericClaims(
            com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (!node.isArray()) {
            log.warn("LLM narration: numeric_claims is not an array (got {}); ignoring.",
                    node.getNodeType());
            return java.util.List.of();
        }
        java.util.List<NarrationResult.NumericClaim> out = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode entry : node) {
            if (entry == null || !entry.isObject()) continue;
            com.fasterxml.jackson.databind.JsonNode valueNode = entry.get("value");
            if (valueNode == null || !valueNode.isNumber()) continue;
            String toolCallId = optionalText(entry, "tool_call_id");
            if (toolCallId == null) continue;
            String units = optionalText(entry, "units");
            String context = optionalText(entry, "context");
            String sourceLocation = optionalText(entry, "source_location");
            out.add(new NarrationResult.NumericClaim(
                    valueNode.asDouble(),
                    units == null ? "" : units,
                    toolCallId,
                    context == null ? "" : context,
                    sourceLocation == null ? "" : sourceLocation));
        }
        return java.util.List.copyOf(out);
    }

    /** Phase 6 D2 — returns the JSON string at {@code key}, or null when absent / blank / non-string. */
    private static String optionalText(com.fasterxml.jackson.databind.JsonNode root, String key) {
        com.fasterxml.jackson.databind.JsonNode node = root.get(key);
        if (node == null || node.isNull() || node.isMissingNode() || !node.isTextual()) return null;
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    /**
     * Phase 4 Iter 9 — parse one {@code formal_findings[]} entry. Returns null on
     * malformed input rather than throwing, so a single bad finding doesn't
     * disqualify the rest of the narration. Confidence is clamped to [0,1];
     * unknown enum values silently fall back to NULL / OBSERVATIONAL.
     */
    static NarrationResult.ExtractedFinding parseFormalFinding(
            com.fasterxml.jackson.databind.JsonNode f) {
        if (f == null || !f.isObject()) return null;
        String relStr = f.path("relation_type").asText("NULL").toUpperCase();
        com.emergentterrain.research.RelationType relation;
        try {
            relation = com.emergentterrain.research.RelationType.valueOf(relStr);
        } catch (IllegalArgumentException e) {
            relation = com.emergentterrain.research.RelationType.NULL;
        }
        String depVar = f.path("dependent_variable").asText(null);
        if (depVar == null || depVar.isBlank()) return null;

        java.util.List<String> indepVars = new java.util.ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode indepNode = f.get("independent_variables");
        if (indepNode != null && indepNode.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode v : indepNode) {
                String name = v.asText(null);
                if (name != null && !name.isBlank()) indepVars.add(name);
            }
        }

        String equationForm = f.path("equation_form").asText(null);

        String claimStr = f.path("claim_status").asText("OBSERVATIONAL").toUpperCase();
        com.emergentterrain.research.MathPhysicsEngine.ClaimStatus claim;
        try {
            claim = com.emergentterrain.research.MathPhysicsEngine.ClaimStatus.valueOf(claimStr);
        } catch (IllegalArgumentException e) {
            claim = com.emergentterrain.research.MathPhysicsEngine.ClaimStatus.OBSERVATIONAL;
        }

        double confidence = Math.max(0.0, Math.min(1.0, f.path("confidence").asDouble(0.5)));
        double validMin = f.path("valid_region_min").asDouble(Double.NEGATIVE_INFINITY);
        double validMax = f.path("valid_region_max").asDouble(Double.POSITIVE_INFINITY);

        return new NarrationResult.ExtractedFinding(
                relation, depVar, indepVars, equationForm,
                claim, confidence, validMin, validMax);
    }

    // Genome mentoring moved to GenomeMentoringService in Phase 0 Task 0.2.

    // --- Parsing helpers ---

    private static final java.util.Set<String> VALID_PREDICATES = java.util.Set.of(
            "isPrime", "isHappy", "collatzTerminates", "isAbundant", "isPerfect");

    private static final java.util.Set<String> VALID_METRICS = java.util.Set.of(
            "fraction_satisfying", "mean_of:collatzSteps", "max_of:collatzSteps", "mean_of:sigmaSum");

}
