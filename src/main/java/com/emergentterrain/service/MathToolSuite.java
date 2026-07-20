package com.emergentterrain.service;

import com.emergentterrain.gpu.GpuEngineAdapter;
import com.emergentterrain.gpu.v1.EvalExprRequest;
import com.emergentterrain.gpu.v1.EvalExprResponse;
import com.emergentterrain.research.DynamicComputeEngine;
import com.emergentterrain.research.DynamicProblem;
import com.emergentterrain.research.GpuEvalStaticChecker;
import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.SandboxEngine;
import com.emergentterrain.research.SymbolicEngine;
import com.emergentterrain.research.SymbolicStaticChecker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.Function;

/**
 * Professor tool suite — six mathematical tools the Groq professor can call
 * during critique to gather experimental evidence before writing its synthesis.
 *
 * Tools:
 *   run_param_sweep      — MathPhysicsEngine at professor-chosen param1/param2
 *   run_predicate_scan   — DynamicComputeEngine number-theoretic predicate scan
 *   evaluate_symbolic    — evaluate f(n) over a range, return statistics (numeric)
 *   run_sandboxed_code   — run LLM pseudocode (static check + primary-model intent review) before execution
 *   run_symbolic         — Phase 8 Tier 3: Symja symbolic evaluation
 *                          (Integrate, D, Solve, Simplify, Series, ...)
 *   run_gpu_eval         — Phase 8 Tier 4: dispatch f(n) over a large range
 *                          to the Rust GPU engine; aggregate on the CPU.
 *                          Only available when a {@link GpuEngineAdapter}
 *                          was injected at construction.
 */
public class MathToolSuite {

    private static final Logger log = LoggerFactory.getLogger(MathToolSuite.class);

    /**
     * Phase 5 C3 — per-paper telemetry collected during professor tool execution
     * so {@link com.emergentterrain.service.ResearchNarrationService} can surface
     * the counts onto the persisted LLM Call Path.
     *
     * <p>Until this commit, sandbox rejections only emitted WARN log lines — a
     * reviewer reading the published paper could not tell whether a Findings
     * claim was computed or hand-waved. 18 sandbox rejections in the May 2026
     * corpus run mapped 1:1 to vague Findings sections.
     */
    /**
     * Phase 17 P8b — known result-prefix set for "a real computational
     * result was produced." Anything not starting with one of these is
     * either a safety rejection (REJECTED by safety review), a runtime
     * failure (Sandbox/Symbolic/GPU/Predicate scan failed), an
     * invalid-argument refusal (Missing, Invalid, Range too large,
     * Topic not computable, etc.), or an unknown-tool error — none of
     * which should increment the success counter that D3
     * (zero_successes→suppressed) reads.
     *
     * <p>Visibility is package-private so unit tests pin the contract
     * without standing up the full executor.
     */
    /** 2026-06-11 (rec 2) — above this binding-window relative slack, verify_conjecture suggests the
     *  tightest holding k so the professor stops leaving a bound at a loose textbook constant. Matches the
     *  write-back's MAX_REL_SLACK (a bound the pool would reject as too-loose gets a tighten suggestion). */
    static final double MAX_REL_SLACK_SUGGEST = 0.25;

    static final java.util.List<String> SUCCESS_PREFIXES = java.util.List.of(
            "Param sweep (",
            "Predicate scan:",
            "Symbolic result for",
            "Symbolic evaluation of",
            "GPU eval of",
            "Sandboxed computation —",
            "verify_conjecture:",
            "verify_constant:",
            "verify_oscillation:",
            "verify_growth:",
            "symbolic_regress:",
            "scalar_window_stats:");

    /**
     * Phase 17 P8b — is this tool-call result a real computational
     * success? Returns true iff the result string starts with one of
     * {@link #SUCCESS_PREFIXES}. Visibility is package-private for
     * unit tests.
     */
    static boolean isSuccessResult(String result) {
        if (result == null || result.isEmpty()) return false;
        for (String prefix : SUCCESS_PREFIXES) {
            if (result.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * 2026-06-11 (rec 2) — the tightest k for which {@code lhs ≤ k*rhs + c} still holds every window:
     * {@code max_i (lhs_i − c)/rhs_i} (the k at which the worst window is exactly tight). Windows with a
     * ~0 rhs are skipped. Returns {@link Double#NEGATIVE_INFINITY} if none usable. Package-private + pure.
     */
    static double tightestHoldingK(double[] lhs, double[] rhs, double c) {
        double k = Double.NEGATIVE_INFINITY;
        if (lhs == null || rhs == null || lhs.length != rhs.length) return k;
        for (int i = 0; i < lhs.length; i++) {
            if (Math.abs(rhs[i]) > 1e-9) {
                k = Math.max(k, (lhs[i] - c) / rhs[i]);
            }
        }
        return k;
    }

    /**
     * rec #6 (2026-05-28) — the sandbox end index after clamping an over-cap span
     * to {@code cap}. Within-cap ranges pass through unchanged; an over-cap range
     * is truncated to {@code [start, start+cap)}. Package-private + pure so the
     * clamp logic is unit-testable without standing up the Mockito-mocked
     * executor (which can't instantiate on this dev shell's JDK).
     */
    static int clampSandboxEnd(int rangeStart, int rangeEnd, int cap) {
        return (rangeEnd - rangeStart > cap) ? rangeStart + cap : rangeEnd;
    }

    /**
     * rec #6 — the visible note appended to the sandbox result when a span was
     * clamped, or "" when no clamp occurred. Tells the narrator to cite the
     * computed sub-range rather than the wider range it requested.
     */
    static String sandboxRangeClampNote(int rangeStart, int rangeEnd, int cap) {
        if (rangeEnd - rangeStart <= cap) return "";
        int clampedEnd = rangeStart + cap;
        return (" [range auto-clamped: requested [%d,%d) span %d exceeds the %d cap; "
                + "computed only the first %d values, [%d,%d) — cite this range, not the requested one]")
                .formatted(rangeStart, rangeEnd, rangeEnd - rangeStart, cap, cap, rangeStart, clampedEnd);
    }

    /**
     * rec #5 (2026-05-28) — outcome of resolving a run_param_sweep's
     * {@code (param1, param2)} args into the engine's abstract sweep knobs:
     * either an accepted (possibly transpose-corrected) pair, or a rejection
     * with a clear error.
     */
    record SweepParams(boolean ok, int param1, double param2, boolean swapped, String error) {}

    /**
     * Epsilon within which a transposed param1-candidate counts as integer-like.
     * Sweep positions are integers 10–99, so a clean transpose has an integer in
     * the param2 slot; a materially-fractional value there is a physical quantity,
     * not a transposed knob.
     */
    private static final double SWEEP_INTEGER_EPSILON = 0.05;

    /**
     * rec #5 — resolve run_param_sweep's two args. param1 is an abstract integer
     * sweep position (10–99); param2 is an abstract scale (1.0–9.0). The engine
     * maps the knob pair to a physical configuration internally — they are NOT
     * physical inputs.
     *
     * <p>The model frequently transposes the two (scale in param1, position in
     * param2): that is auto-oriented (P-a328-961 sent 4.9/53). But it ALSO passes
     * the hypothesis's physical values straight in (P-a328-1000 sent AU 3.5/15.8),
     * which the prior guard silently swapped to param1=16/param2=3.5 and swept
     * a=1.30–2.05 AU — a domain the paper never intended. The distinguishing
     * signal: a legitimate transposed position is an INTEGER, so we auto-orient
     * only a clean integer transpose; a fractional param1-candidate (15.8) is a
     * physical value and is rejected with a clear error rather than mangled.
     */
    static SweepParams resolveSweepParams(double raw1, double raw2) {
        int    param1 = (int) Math.round(raw1);
        double param2 = raw2;
        if (param1 >= 10 && param1 <= 99 && param2 >= 1.0 && param2 <= 9.0) {
            return new SweepParams(true, param1, param2, false, null);
        }
        int    swap1 = (int) Math.round(raw2);
        double swap2 = raw1;
        boolean swapInRange = swap1 >= 10 && swap1 <= 99 && swap2 >= 1.0 && swap2 <= 9.0;
        boolean cleanIntegerTranspose = swapInRange
                && Math.abs(raw2 - swap1) < SWEEP_INTEGER_EPSILON;
        if (cleanIntegerTranspose) {
            return new SweepParams(true, swap1, swap2, true, null);
        }
        return new SweepParams(false, 0, 0.0, false,
                ("Invalid params: param1 (sweep position) must be an integer 10–99 and "
                + "param2 (scale) must be 1.0–9.0. These are ABSTRACT sweep knobs, NOT "
                + "physical quantities — do not pass physical values (AU, K, nm, etc.) here; "
                + "the engine maps the knobs to a physical configuration internally. "
                + "Got param1=%s, param2=%s.").formatted(trimNum(raw1), trimNum(raw2)));
    }

    /** Format a sweep arg for the error message: drop a trailing {@code .0}. */
    private static String trimNum(double v) {
        return (v == Math.rint(v) && !Double.isInfinite(v))
                ? Long.toString((long) v) : Double.toString(v);
    }

    public static final class ToolMetrics {
        private int sandboxRejectCount  = 0;
        private int symbolicRejectCount = 0;
        private int gpuEvalRejectCount  = 0;
        // Phase 10 — track per-tool success counts so the professor stage can
        // detect the "all tools failed but model wrote prose anyway" pattern
        // visible on P-a328-352 and P-a328-353. Each successful tool execution
        // increments its counter; the gate at the end of the professor loop
        // compares totalRejects() vs totalSuccesses() to decide suppression.
        private int sandboxSuccessCount    = 0;
        private int symbolicSuccessCount   = 0;
        private int gpuEvalSuccessCount    = 0;
        private int predicateScanSuccessCount = 0;
        private int verifyConjectureSuccessCount = 0;
        public int sandboxRejectCount()  { return sandboxRejectCount;  }
        public int symbolicRejectCount() { return symbolicRejectCount; }
        public int gpuEvalRejectCount()  { return gpuEvalRejectCount;  }
        public int sandboxSuccessCount() { return sandboxSuccessCount; }
        public int symbolicSuccessCount(){ return symbolicSuccessCount;}
        public int gpuEvalSuccessCount() { return gpuEvalSuccessCount; }
        public int predicateScanSuccessCount() { return predicateScanSuccessCount; }
        public int verifyConjectureSuccessCount() { return verifyConjectureSuccessCount; }
        public int totalSuccesses() {
            return sandboxSuccessCount + symbolicSuccessCount
                    + gpuEvalSuccessCount + predicateScanSuccessCount
                    + verifyConjectureSuccessCount;
        }
        public int totalRejects() {
            return sandboxRejectCount + symbolicRejectCount + gpuEvalRejectCount;
        }
        void incrementSandboxRejects()   { sandboxRejectCount++;  }
        void incrementSymbolicRejects()  { symbolicRejectCount++; }
        void incrementGpuEvalRejects()   { gpuEvalRejectCount++;  }
        void incrementSandboxSuccesses()    { sandboxSuccessCount++;    }
        void incrementSymbolicSuccesses()   { symbolicSuccessCount++;   }

        /**
         * 2026-05-22 — clears every counter. Called by
         * {@link com.emergentterrain.service.LlmRouter#callPrimaryWithTools}
         * when the router falls through from a failed handle attempt to
         * the next handle. The trace is naturally per-handle (local to
         * each pool's tool-call loop, lost on exception), but ToolMetrics
         * is shared via the executor closure — without this reset, a
         * successful tool call on a handle that subsequently throws
         * leaves the success counter inflated for whichever handle
         * eventually returns. Surfaced root cause for the P-a328-865 /
         * 880 / 890 trace-empty-yet-prof:ok divergence; the
         * trace.isEmpty() defensive check in ResearchNarrationService
         * still protects against future leaks of this shape.
         */
        public void reset() {
            sandboxRejectCount = 0;
            symbolicRejectCount = 0;
            gpuEvalRejectCount = 0;
            sandboxSuccessCount = 0;
            symbolicSuccessCount = 0;
            gpuEvalSuccessCount = 0;
            predicateScanSuccessCount = 0;
            verifyConjectureSuccessCount = 0;
        }
        void incrementGpuEvalSuccesses()    { gpuEvalSuccessCount++;    }
        void incrementPredicateScanSuccesses() { predicateScanSuccessCount++; }
        void incrementVerifyConjectureSuccesses() { verifyConjectureSuccessCount++; }
    }

    private static final Set<String> VALID_PREDICATES = Set.of(
            "isPrime", "isHappy", "collatzTerminates", "isAbundant", "isPerfect");
    private static final Set<String> VALID_METRICS = Set.of(
            "fraction_satisfying", "mean_of:collatzSteps", "max_of:collatzSteps", "mean_of:sigmaSum");

    private static final int MAX_SANDBOX_RANGE = 5_000;
    private static final int MAX_SYMBOLIC_RANGE = 1_000;
    /**
     * Phase 22 §22.5 — sandbox-limit relaxation for the Julia path.
     * SandboxEngine's 1000-element cap is sized for its
     * per-iteration interpretation cost (a single sweep over 1000
     * floats takes ~50-200ms inside the JVM bytecode interpreter,
     * which competes with LLM-streaming wall-clock budgets).
     *
     * <p>Julia evaluates the same sweep in JIT-compiled native code
     * — sub-millisecond per 1000 samples for typical math
     * expressions. The 100_000 ceiling is what the sidecar's
     * {@code /sweep} endpoint accepts; we mirror it here so the
     * LLM can confidently ask for a fine-grained sweep when it's
     * routing through Julia.
     *
     * <p>Sized empirically: a 100_000-sample sweep of
     * {@code sin(n*π/180)} on Julia 1.12 takes ~3ms after JIT
     * warmup. Well under the 5s default {@code app.julia.timeoutMs}
     * even with 30× safety margin.
     */
    static final int MAX_SYMBOLIC_RANGE_JULIA = 100_000;

    private final LlmRouter router;
    private final ObjectMapper mapper;
    /**
     * Optional Phase 8 Tier 4 dependency. When non-null, the tool list
     * advertises run_gpu_eval and the executor dispatches it; when null,
     * run_gpu_eval calls surface a clear "not configured" error so the
     * LLM falls back to run_sandboxed_code instead of getting silently
     * dropped. Existing tests construct MathToolSuite without an adapter.
     */
    private final GpuEngineAdapter gpuEngineAdapter;
    /**
     * Phase 22 §22.2 — optional Julia math sidecar client. When
     * non-null AND {@link com.emergentterrain.llm.JuliaMathClient#isAvailable()}
     * is true, {@link #runSymbolic} and {@link #evaluateSymbolic}
     * delegate to the sidecar before falling back to the in-process
     * Symja path. Default null (every existing test stays on Symja);
     * production wiring lives in {@link com.emergentterrain.service.ResearchNarrationService}.
     */
    private final com.emergentterrain.llm.JuliaMathClient juliaMathClient;
    /**
     * Phase 23.5 — optional conjecture verifier. When non-null,
     * {@code toolsJson} advertises {@code verify_conjecture} and the
     * dispatcher routes calls into {@link com.emergentterrain.research.conjecture.ConjectureVerifier};
     * when null, calls return a clear "not configured" error and the LLM
     * sees the tool was unavailable. Existing tests construct
     * MathToolSuite without a verifier and stay on the prior tool set.
     */
    private final com.emergentterrain.research.conjecture.ConjectureVerifier conjectureVerifier;

    /**
     * Phase 25 §25.3 — optional SymPy identity prover. When non-null
     * AND its sidecar path is reachable, {@code toolsJson()} advertises
     * {@code prove_identity} and the dispatcher routes calls into
     * {@link com.emergentterrain.llm.SympyIdentityClient}. When null
     * (the test default), the tool stays absent from the schema so
     * the LLM never sees a feature it can't call.
     */
    private final com.emergentterrain.llm.SympyIdentityClient sympyIdentityClient;

    public MathToolSuite(LlmRouter router, ObjectMapper mapper) {
        this(router, mapper, null, null, null, null);
    }

    public MathToolSuite(LlmRouter router, ObjectMapper mapper, GpuEngineAdapter gpuEngineAdapter) {
        this(router, mapper, gpuEngineAdapter, null, null, null);
    }

    public MathToolSuite(LlmRouter router, ObjectMapper mapper,
                          GpuEngineAdapter gpuEngineAdapter,
                          com.emergentterrain.llm.JuliaMathClient juliaMathClient) {
        this(router, mapper, gpuEngineAdapter, juliaMathClient, null, null);
    }

    public MathToolSuite(LlmRouter router, ObjectMapper mapper,
                          GpuEngineAdapter gpuEngineAdapter,
                          com.emergentterrain.llm.JuliaMathClient juliaMathClient,
                          com.emergentterrain.research.conjecture.ConjectureVerifier conjectureVerifier) {
        this(router, mapper, gpuEngineAdapter, juliaMathClient, conjectureVerifier, null);
    }

    /**
     * Canonical constructor. Tests and production code paths funnel
     * through here; the smaller overloads cascade with nulls.
     */
    public MathToolSuite(LlmRouter router, ObjectMapper mapper,
                          GpuEngineAdapter gpuEngineAdapter,
                          com.emergentterrain.llm.JuliaMathClient juliaMathClient,
                          com.emergentterrain.research.conjecture.ConjectureVerifier conjectureVerifier,
                          com.emergentterrain.llm.SympyIdentityClient sympyIdentityClient) {
        this.router = router;
        this.mapper = mapper;
        this.gpuEngineAdapter = gpuEngineAdapter;
        this.juliaMathClient = juliaMathClient;
        this.conjectureVerifier = conjectureVerifier;
        this.sympyIdentityClient = sympyIdentityClient;
    }

    /**
     * Phase 28 §28.5 — optional sidecar shadow-read wrapper. Passed
     * in by {@link ResearchNarrationService} after Spring autowires
     * its own delegate setter. MathToolSuite is not a Spring bean
     * (constructed manually inside RNS), so we can't @Autowired here;
     * RNS bridges the wiring.
     */
    private volatile com.emergentterrain.research.MathPhysicsEngineDelegate physicsEngineDelegate;

    public void setPhysicsEngineDelegate(
            com.emergentterrain.research.MathPhysicsEngineDelegate delegate) {
        this.physicsEngineDelegate = delegate;
    }

    /**
     * Lever 2 — curated-literature corpus for the {@code search_literature}
     * tool. Wired post-construction by {@link ResearchNarrationService} (same
     * pattern as {@link #physicsEngineDelegate}; MathToolSuite isn't a Spring
     * bean). Null when no corpus is available — the tool is then omitted from
     * {@link #toolsJson(boolean, boolean)} and the executor case short-circuits.
     */
    private volatile com.emergentterrain.research.LiteratureSearchService literatureSearch;

    public void setLiteratureSearch(
            com.emergentterrain.research.LiteratureSearchService literatureSearch) {
        this.literatureSearch = literatureSearch;
    }

    /**
     * Phase 32 §32.5 step 4 — optional offline grounding client (py-sidecar BM25 over the local
     * corpus). Set post-construction like {@link #literatureSearch}; null = grounding dormant
     * (the bean only exists when {@code app.research.literature.grounding.enabled=true}) and
     * search_literature keeps the curated-corpus path byte-identically. When present, it applies
     * only to the papers in {@link #groundingApplies} (promotion-track + measurement-frame /
     * priority-target conjecture papers — the §32.5 2026-07-04 widened caller policy), keeping
     * per-paper cost flat for the ordinary corpus.
     */
    private volatile com.emergentterrain.research.literature.GroundingClient groundingClient;

    public void setLiteratureGrounding(
            com.emergentterrain.research.literature.GroundingClient groundingClient) {
        this.groundingClient = groundingClient;
    }

    /** Phase 33 L2 — full-text core reader for read_reference. Null-safe (off ⇒ not advertised). */
    private volatile com.emergentterrain.research.literature.ReferenceCoreReader referenceReader;

    public void setReferenceReader(
            com.emergentterrain.research.literature.ReferenceCoreReader reader) {
        this.referenceReader = reader;
    }

    /** Phase 34 — optional read-only SQLite/FTS5 literature adapter. */
    private volatile com.emergentterrain.research.literature.Phase34SqliteLiterature phase34SqliteLiterature;

    public void setPhase34SqliteLiterature(
            com.emergentterrain.research.literature.Phase34SqliteLiterature adapter) {
        this.phase34SqliteLiterature = adapter != null && adapter.available() ? adapter : null;
    }

    /** Phase 32 §32.5d — OEIS/DLMF domain-reference lookups. Null-safe (off ⇒ tools not advertised). */
    private volatile com.emergentterrain.research.literature.DomainRefClient domainRefClient;

    public void setDomainRefClient(
            com.emergentterrain.research.literature.DomainRefClient client) {
        this.domainRefClient = client;
    }

    /**
     * Phase 30 fork 2b Phase 4 — optional runtime-discovery service. Set by
     * {@link ResearchNarrationService} after Spring autowires it. The
     * {@code discover_mertens_value} tool is advertised + executable only when
     * this is non-null AND {@code isEnabled()} (the {@code runtimeExecEnabled}
     * flag) — so it is absent by default. The tool computes M(x) only, so it is
     * inherently mertens-class; it is offered only during a deliberate discovery
     * experiment. See phase-30-conjecture/runtime-self-verifying-execution.md.
     */
    private volatile com.emergentterrain.research.discovery.CandidateDiscoveryService discoveryService;

    public void setDiscoveryService(
            com.emergentterrain.research.discovery.CandidateDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    private boolean discoveryAvailable() {
        return discoveryService != null && discoveryService.isEnabled();
    }

    /** Phase 30 30.7 P3 — gate for the propose_mechanism tool (set by ResearchNarrationService). */
    private volatile boolean mechanismProposalEnabled = false;

    public void setMechanismProposalEnabled(boolean enabled) {
        this.mechanismProposalEnabled = enabled;
    }

    /** 2026-06-05 — gate for the scalar_window_stats tool (set by ResearchNarrationService). */
    private volatile boolean scalarStatsToolEnabled = false;

    public void setScalarStatsToolEnabled(boolean enabled) {
        this.scalarStatsToolEnabled = enabled;
    }

    /** 2026-06-18 (REVIEW_REPORT b72c4e10 §1 rec 2/3) — inform-don't-suppress caveats on the
     *  verify_conjecture result: a constant-framed LHS (use verify_constant) and a tolerance-dominated
     *  small-LHS HOLD. Pure additions to the result text; OFF = bit-identical. Set by ResearchNarrationService. */
    private volatile boolean verifyInformCaveatsEnabled = false;

    public void setVerifyInformCaveatsEnabled(boolean enabled) {
        this.verifyInformCaveatsEnabled = enabled;
    }

    /**
     * 2026-07-05 (run-25 rec 1, P-b72c-77627793) — per-rung sample budget for the CONSTANT
     * scale-ladder. Second-order deficit signals are count-noise-limited: a rung sampling 100k
     * integers at x=10⁹ holds ~300 twin pairs (~6% noise) against a c/ln x deficit signal of the
     * same order, so the registered twin_vs deficit question produced sign-flipping fits. With a
     * budget B, each rung WIDENS its windows to ~B/num_windows integers (statistically identical
     * for CONSTANT-frame means — they average over the window — and 20 large sidecar calls beat
     * 1,000 small ones); B=5e6 → ~0.8% twin-noise at 10⁹. Deliberately NOT applied to the
     * oscillation/growth ladders: their per-window statistics (amplitude, window max) are
     * window-size-DEPENDENT, so widening would silently change the measured quantity.
     * 0 = legacy shape (caller's window_size). Set by ResearchNarrationService.
     */
    private volatile long ladderRungBudget = 0;

    public void setLadderRungBudget(long budget) {
        this.ladderRungBudget = budget;
    }

    /**
     * Phase 36 vertical slice — optional restart-safe executor for metric-safe, range-preserving
     * scale-ladder chunks.  It is setter-wired because this suite is assembled manually by
     * {@link ResearchNarrationService}, not by Spring.
     */
    private volatile com.emergentterrain.research.conjecture.RangePreservingChunkExecutor rangeChunkExecutor;

    public void setRangeChunkExecutor(
            com.emergentterrain.research.conjecture.RangePreservingChunkExecutor executor) {
        this.rangeChunkExecutor = executor;
    }

    /** Widened per-rung window size under the ladder budget; never below the caller's ask. */
    static int ladderRungWindowSize(int windowSize, int numWindows, long budget) {
        if (budget <= 0) {
            return windowSize;
        }
        long perWindow = budget / Math.max(1, numWindows);
        return (int) Math.min(Math.max(windowSize, perWindow), 50_000_000L);
    }

    /** scalar_window_stats needs the verifier (its sampleWindows substrate) AND its gate. */
    private boolean scalarStatsAvailable() {
        return conjectureVerifier != null && scalarStatsToolEnabled;
    }

    /** The propose_mechanism tool needs the conjecture verifier (for windows) AND its own gate on. */
    private boolean mechanismProposalAvailable() {
        return conjectureVerifier != null && mechanismProposalEnabled;
    }

    /** The divisor-domain discover tool needs discovery wired+enabled AND its own gate on. */
    private boolean divisorDiscoveryAvailable() {
        return discoveryAvailable() && discoveryService.isDivisorToolEnabled();
    }

    /** The prime-domain discover tool needs discovery wired+enabled AND its own gate on. */
    private boolean primeDiscoveryAvailable() {
        return discoveryAvailable() && discoveryService.isPrimeToolEnabled();
    }

    /**
     * Phase 30 fork 2b §8b — optional accumulated-knowledge service. Set by
     * {@link ResearchNarrationService} after Spring autowires it. The
     * {@code recall_frontier_knowledge} tool is advertised + executable only when
     * this is non-null AND {@code isRecallEnabled()} (the {@code frontierRecallEnabled}
     * flag). It lets a paper READ the persisted, independently re-verified frontier
     * corpus (records + best method) with trust carried verbatim — never re-running
     * a discovery and never laundering a moat-validated value into a proof.
     */
    private volatile com.emergentterrain.research.discovery.FrontierKnowledgeService frontierKnowledge;

    public void setFrontierKnowledge(
            com.emergentterrain.research.discovery.FrontierKnowledgeService frontierKnowledge) {
        this.frontierKnowledge = frontierKnowledge;
    }

    private boolean recallAvailable() {
        return frontierKnowledge != null && frontierKnowledge.isRecallEnabled();
    }

    /** Default bound key for the recall tool — the Mertens-class corpus. */
    private static final String DEFAULT_RECALL_BOUND = "mertens_M";
    /** Bound key for the Dirichlet divisor-sum corpus (papers on divisor-count topics). */
    private static final String DIVISOR_RECALL_BOUND = "divisor_D";
    /** Bound key for the prime-counting corpus (papers on prime-density / π(x) topics). */
    private static final String PRIME_RECALL_BOUND = "prime_pi";

    /**
     * Pick the recall corpus that matches the paper's topic so a divisor-count paper
     * recalls the {@code divisor_D} frontier (and a prime/π(x) paper the {@code prime_pi}
     * one) without the model having to know the key exists. Falls back to the Mertens
     * corpus. The LLM can still override via the tool's {@code bound} argument.
     */
    private String defaultRecallBound(PaperDraft draft) {
        String topic = (draft == null || draft.topicName() == null) ? "" : draft.topicName().toLowerCase();
        if (topic.contains("divisor")) {
            return DIVISOR_RECALL_BOUND;
        }
        if (topic.contains("prime")) {
            return PRIME_RECALL_BOUND;
        }
        return DEFAULT_RECALL_BOUND;
    }

    /** Standard moat checkpoints (≤ NT_MAX_N, judged vs the oracle) + OEIS anchor. */
    private static final java.util.List<Long> DISCOVER_CHECKPOINTS =
            java.util.List.of(1L, 2L, 10L, 1_000L, 100_000L, 999_983L, 1_000_000L, 2_000_000L);
    private static final java.util.Map<Long, Long> DISCOVER_ANCHORS =
            java.util.Map.of(10_000_000L, 1037L);   // M(10^7), OEIS A084237

    /** Divisor-sum (Dirichlet D(x)) discovery spec + moat checkpoints (≤ NT_MAX_N, judged vs
     *  the D(x) oracle) + a beyond-range external anchor D(10^7) (parity with Mertens' M(10^7)
     *  anchor), so a candidate that passes the in-sieve checkpoints but diverges past it is caught. */
    private static final String DISCOVER_DIVISOR_SPEC =
            "D(x) = sum of the number-of-divisors function d(n) for n = 1..x (the Dirichlet divisor summatory function)";
    private static final java.util.List<Long> DISCOVER_DIVISOR_CHECKPOINTS =
            java.util.List.of(1L, 2L, 10L, 1_000L, 100_000L, 999_983L, 1_000_000L, 2_000_000L);
    private static final java.util.Map<Long, Long> DISCOVER_DIVISOR_ANCHORS =
            java.util.Map.of(10_000_000L, 162_725_364L);   // D(10^7), OEIS A006218

    // ── Tool definitions JSON ─────────────────────────────────────────────────

    /**
     * Phase 22 §22.4 — Mathematica-syntax description for the
     * {@code run_symbolic} tool. Used when the Julia sidecar is NOT
     * available (or {@code app.julia.enabled=false}). Symja is the
     * back-end; the LLM emits {@code Solve[…]} / {@code Integrate[…]}
     * / {@code arcsin(…)} etc.
     */
    private static final String RUN_SYMBOLIC_DESC_SYMJA =
            "Evaluate a SYMBOLIC math expression using Symja (Mathematica-like syntax). Use to "
                    + "(a) verify a claimed derivative or integral has the closed form the paper "
                    + "states, (b) check that two expressions are equivalent after simplification, "
                    + "(c) compute a Series expansion at a point, (d) Solve an equation for its "
                    + "symbolic roots, (e) Simplify a long algebraic form. Returns the simplified "
                    + "output AND a numeric approximation if the result is a finite number. "
                    + "Examples: 'Integrate[Sin[x]^2, {x, 0, Pi}]' (→ Pi/2, numeric 1.5708), "
                    + "'D[x^3 + 2*x, x]' (→ 2 + 3*x^2), 'Solve[x^2 - 5*x + 6 == 0, x]' "
                    + "(→ {{x->2}, {x->3}}), 'Simplify[Sin[x]^2 + Cos[x]^2]' (→ 1), "
                    + "'Series[Exp[x], {x, 0, 4}]'. Allowed heads include arithmetic, calculus "
                    + "(Integrate, D, Limit, Series, Sum, Product), algebra (Solve, Simplify, "
                    + "Expand, Factor), trig, exp/log, special functions (Gamma, Erf, BesselJ, "
                    + "Zeta), number theory (PrimeQ, MoebiusMu, EulerPhi). REJECTED: I/O (Import, "
                    + "Export, Get, Put), network (URLFetch), process (Run), state mutation (Set, "
                    + "Clear), JVM escape (Java, JavaClass), ToExpression, Compile. Wall-clock "
                    + "cap 3s; iteration cap 100k.";

    /**
     * Phase 22 §22.4 — native-Julia-syntax description for
     * {@code run_symbolic}. Used when the Julia sidecar is available
     * AND opt-in (`app.julia.enabled=true`). The LLM should emit
     * native Julia: {@code solve(...)} not {@code Solve[...]},
     * {@code asin(x)} not {@code ArcSin[x]}, {@code floor(x)} not
     * {@code Floor[x]}, {@code x^2} (same in both).
     *
     * <p>Why switch instead of translate: the LLM is at least as
     * fluent in Julia as in Mathematica (academic + ML representation
     * in training data); having one syntactic surface eliminates the
     * `^→**` / `=→==` / `Solve→symbolic_solve` rewriters that
     * Phase 21 §21.5 needed when the back-end was Symja.
     */
    private static final String RUN_SYMBOLIC_DESC_JULIA =
            "Evaluate a SYMBOLIC math expression using Julia's Symbolics.jl back-end (NATIVE "
                    + "JULIA SYNTAX, not Mathematica). Use to (a) verify a claimed derivative or "
                    + "integral has the closed form the paper states, (b) check that two "
                    + "expressions are equivalent after simplification, (c) Solve an equation for "
                    + "its symbolic roots, (d) compute a Float64 approximation of any expression. "
                    + "Returns the stringified value and Julia type. Examples: "
                    + "'floor(2.04e-6 / 400e-9)' (→ \"5.0\", Float64), "
                    + "'asin(0.154/(2*0.112)) * 180/π' (→ ~43.43, Float64), "
                    + "'(let θ = 25π/180, s = sin(θ/2)^2; (1 + s/4) * 100 end)' "
                    + "(→ ~1.20, period-error % at 25°), "
                    + "'sum(1/k^2 for k in 1:1000)' (→ ~1.6439, partial Basel sum), "
                    + "'(let σ(n) = sum(d for d in 1:n if n%d==0); count(n -> σ(n)>2n, 200:399)/200 end)' "
                    + "(→ density of abundant numbers in [200,400)). Allowed: arithmetic, "
                    + "elementary functions (sin, cos, tan, asin, acos, atan, exp, log, sqrt, "
                    + "cbrt, abs, floor, ceil, round, sign), special constants π, ℯ (e), Inf, "
                    + "NaN, BigInt / BigFloat for arbitrary precision, list comprehensions "
                    + "(`[expr for x in 1:N]`), let/begin/end scoping, anonymous functions "
                    + "(`x -> expr`), reductions (sum, prod, count, minimum, maximum), filtering "
                    + "(`count(pred, iter)`, `filter(pred, iter)`). For symbolic root finding "
                    + "use a separate call shape (the sidecar exposes /solve and /nsolve "
                    + "endpoints internally); for direct equation solving from this tool, frame "
                    + "as a numeric expression. REJECTED: I/O (open, read, write, download, "
                    + "include), shell exec (run, backticks), package management (Pkg.add, "
                    + "using Pkg), FFI (ccall, unsafe_), process control (exit, Sys., ENV[), "
                    + "direct evaluator access (eval, @eval, Meta.parse). Wall-clock cap 5s.";

    /**
     * Phase 22 §22.4 — Symja-back-end description for
     * {@code evaluate_symbolic}. Uses SandboxEngine's restricted
     * grammar with {@code **} for power (not {@code ^}, which the
     * sandbox parser doesn't accept — see Phase 21 §21.5).
     */
    private static final String EVALUATE_SYMBOLIC_DESC_SYMJA =
            "Evaluate a mathematical expression f(n) for each integer n in [range_start, "
                    + "range_end) and return mean/std/min/max. Use to verify whether a claimed "
                    + "formula is empirically consistent.";

    /**
     * Phase 22 §22.4 — Julia-back-end description for
     * {@code evaluate_symbolic}. Native Julia syntax — {@code ^} for
     * power (no rewrite needed), full library access.
     */
    private static final String EVALUATE_SYMBOLIC_DESC_JULIA =
            "Evaluate a mathematical expression f(n) over a numeric range and return "
                    + "mean/std/min/max via Julia's QuadGK/Statistics back-end. NATIVE JULIA "
                    + "SYNTAX. The variable is `n`. Examples: 'n^2' (squares over the range), "
                    + "'sin(n*π/180)' (sine of degrees), '1/n' (harmonic terms). "
                    + "Range size capped at 100,000 (much wider than the Symja path's 1000 — "
                    + "Julia evaluates the sweep in JIT-compiled native code, so a 100k-sample "
                    + "scan finishes in a few milliseconds).";

    /**
     * Pick the right description pair based on whether the Julia
     * sidecar will actually serve the call. When the bean is null,
     * disabled, or unreachable, the existing Symja / SandboxEngine
     * path takes the call and the description must match its syntax.
     */
    private boolean useJuliaDescriptions() {
        return juliaMathClient != null && juliaMathClient.isAvailable();
    }

    public String toolsJson(boolean includeDynamic) {
        return toolsJson(includeDynamic, false);
    }

    /**
     * Lever 2 — overload that additionally advertises the
     * {@code search_literature} citation-grounding tool when
     * {@code includeLiterature} is true AND a corpus is wired. The flag is
     * supplied by the caller (the field-injected {@code citationToolEnabled} in
     * {@link ResearchNarrationService}) so MathToolSuite stays free of Spring
     * config; absent a corpus the tool is omitted regardless.
     */
    public String toolsJson(boolean includeDynamic, boolean includeLiterature) {
        // Phase 22 §22.4 — pick syntax-flavour descriptions based on
        // whether the Julia sidecar will actually serve the call.
        // Computed once at the top of the method so the text block
        // can interpolate them all in a single `.formatted(...)`.
        boolean julia = useJuliaDescriptions();
        String evalSymDesc = jsonEncode(julia
                ? EVALUATE_SYMBOLIC_DESC_JULIA : EVALUATE_SYMBOLIC_DESC_SYMJA);
        String evalSymExprDesc = jsonEncode(julia
                ? "A native-Julia arithmetic expression in n. Allowed: +,-,*,/,^,sqrt,log,abs,floor,ceil,sin,cos,tan,exp,min,max"
                : "A pure arithmetic expression in n. Allowed: +,-,*,/,**,sqrt,log,abs,floor,ceil,pow,min,max");
        // Phase 22 §22.5 — the range cap differs by back-end (1000 for
        // Symja, 100_000 for Julia). Surface it in the JSON schema so
        // the LLM sees the actual ceiling it can ask for.
        String evalSymRangeEndDesc = jsonEncode(julia
                ? "Range end (range_end − range_start ≤ 100000)"
                : "Range end (range_end − range_start ≤ 1000)");
        String runSymDesc = jsonEncode(julia
                ? RUN_SYMBOLIC_DESC_JULIA : RUN_SYMBOLIC_DESC_SYMJA);
        String runSymExprDesc = jsonEncode(julia
                ? "A native-Julia expression. Parens () for function application; brackets [] for arrays. Use ^ for power. Examples in the description above."
                : "A Symja expression in full-form or operator-form syntax. Brackets [] for function application; {} for lists.");

        String dynamicTool = includeDynamic ? """
                ,{
                  "type":"function",
                  "function":{
                    "name":"run_predicate_scan",
                    "description":"Scan integers in [range_start, range_end) satisfying a number-theoretic predicate and measure a statistic. Use to test whether a specific property holds in a different integer range. The span per call is capped at 500: to cover a wider range, call this tool several times over ADJACENT sub-ranges (e.g. [800,1300) then [1300,1800)) and report each sub-range separately — never describe results over a range you did not scan.",
                    "parameters":{
                      "type":"object",
                      "properties":{
                        "predicate":{"type":"string","description":"Property filter: tokens from isPrime isHappy collatzTerminates isAbundant isPerfect joined with AND or OR; any token may be prefixed with NOT (e.g. 'isPrime AND NOT isAbundant') — use NOT to DECOMPOSE a union into its exclusive components (A-only, B-only) and test interaction against inclusion-exclusion"},
                        "range_start":{"type":"integer","description":"Range start (≥1)"},
                        "range_end":{"type":"integer","description":"Range end (range_end − range_start ≤ 500)"},
                        "metric":{"type":"string","description":"EXACTLY one of these four tokens, copied verbatim with no extra words: fraction_satisfying | mean_of:collatzSteps | max_of:collatzSteps | mean_of:sigmaSum. For ANY density / proportion / 'fraction of integers that are …' question, use fraction_satisfying. Only collatzSteps and sigmaSum statistics exist — there is NO divisor-count and NO omega (distinct-prime-factor) metric, so do not request those."},
                        "baseline":{"type":"number","description":"Hypothesized value for comparison"},
                        "hypothesis":{"type":"string","description":"What you predict this will show"}
                      },
                      "required":["predicate","range_start","range_end","metric","baseline","hypothesis"]
                    }
                  }
                },
                {
                  "type":"function",
                  "function":{
                    "name":"evaluate_symbolic",
                    "description":%s,
                    "parameters":{
                      "type":"object",
                      "properties":{
                        "expression":{"type":"string","description":%s},
                        "range_start":{"type":"integer","description":"Range start"},
                        "range_end":{"type":"integer","description":%s},
                        "rationale":{"type":"string","description":"Why this formula test addresses a weakness"}
                      },
                      "required":["expression","range_start","range_end","rationale"]
                    }
                  }
                }""".formatted(evalSymDesc, evalSymExprDesc, evalSymRangeEndDesc) : "";

        return """
                [{
                  "type":"function",
                  "function":{
                    "name":"run_param_sweep",
                    "description":"Re-run the paper's own computation at a different parameter region (param1/param2). Use to test whether the paper's findings hold at a different scale or starting point.",
                    "parameters":{
                      "type":"object",
                      "properties":{
                        "param1":{"type":"integer","description":"Search-space start integer (10–99)"},
                        "param2":{"type":"number","description":"Depth/scale factor (1.0–9.0)"},
                        "rationale":{"type":"string","description":"Why this parameter region tests a specific weakness"}
                      },
                      "required":["param1","param2","rationale"]
                    }
                  }
                }%s,
                {
                  "type":"function",
                  "function":{
                    "name":"run_sandboxed_code",
                    "description":"Run custom mathematical pseudocode in a safe sandbox (reviewed by a safety model before execution). Use for computations that don't fit the other tools. Pre-bound variables: start=range_start, end=range_end, param1, param2 (from the paper). Allowed: var, for, if/else, return, and built-ins: (a) number theory: isPrime, isHappy, collatzTerminates, isAbundant, isPerfect, collatzSteps, sigmaSum, gcd, mobius, sternBrocotDepthOf, isSquarefree, mertensPartialSum; (b) any java.lang.Math function — abs, sqrt, cbrt, pow, exp, expm1, log, log10, log1p, log2, floor, ceil, rint, signum, hypot, min, max, sin, cos, tan, asin, acos, atan, atan2, sinh, cosh, tanh, toRadians, toDegrees, etc.; (c) SYMBOLIC BRIDGES via Symja under the hood — integrate(expr, var, lo, hi) returns the definite integral (e.g. integrate(sin(x)**2, x, 0, PI) → 1.5708), differentiate(expr, var, at) returns d/dvar[expr] evaluated at the given point (e.g. differentiate(x**3 + 2*x, x, 5) → 77), simplify(expr) returns the simplified value but ONLY if it reduces to a finite number (e.g. simplify(sin(x)**2 + cos(x)**2) → 1). Bridges cannot nest, cannot use sandbox-only builtins (isPrime, gcd, etc.) inside their expressions, and reject unbound free variables. You may also DEFINE helper functions at the top of your code with 'function name(arg1, arg2) { ... return expr; }' and call them later — helpers see their params + globals only (no closures over caller locals), max 64 recursion depth. ARRAYS of numbers are supported: declare with 'var xs = [1, 2, 3];', index/assign with 'xs[i]' / 'xs[i] = v' / 'xs[i] += v', length via 'len(xs)' (or 'xs.length', auto-rewritten), and append with 'xs.push(v)' up to a preallocated element cap (default 16384, operator-configurable) — no resize/splice/pop, and no nested arrays. Pre-bound constants: PI, E, WIEN_B, STEFAN_BOLTZMANN_SIGMA, PLANCK_H, PLANCK_HBAR, BOLTZMANN_K, SPEED_OF_LIGHT, AVOGADRO, ELEMENTARY_CHARGE, GRAVITATIONAL_G, ELECTRON_MASS, PROTON_MASS, VACUUM_PERMITTIVITY, VACUUM_PERMEABILITY (SI units, Codata 2018). No I/O, no imports, no class creation. Max 5000 loop iterations. RETURN A SINGLE NUMBER. To report several quantities from one call, do NOT build a summary string (this grammar has no strings) — call recordSample(value) once per quantity (the harness returns count/mean/std/min/max plus each value), or return {label: value, …}, then return your primary number. Use var (not let/const); raise to a power with pow(x,y) or x**y (the ^ operator is not supported); // line comments are fine, but quoted string literals (double- or single-quoted text) and bare object/array values mid-expression are rejected.",
                    "parameters":{
                      "type":"object",
                      "properties":{
                        "code":{"type":"string","description":"JavaScript-like pseudocode. Example: var c=0; for(var n=start;n<end;n++){if(isPrime(n)&&isHappy(n)){c++;}} return c/(end-start);"},
                        "description":{"type":"string","description":"Plain-English description of what this code computes"},
                        "range_start":{"type":"integer","description":"Value for 'start' (1–9500)"},
                        "range_end":{"type":"integer","description":"Value for 'end' (range_end − range_start ≤ 5000)"}
                      },
                      "required":["code","description","range_start","range_end"]
                    }
                  }
                },
                {
                  "type":"function",
                  "function":{
                    "name":"run_symbolic",
                    "description":%s,
                    "parameters":{
                      "type":"object",
                      "properties":{
                        "expression":{"type":"string","description":%s},
                        "rationale":{"type":"string","description":"Why this symbolic check addresses a specific weakness in the paper"}
                      },
                      "required":["expression","rationale"]
                    }
                  }
                },
                {
                  "type":"function",
                  "function":{
                    "name":"run_gpu_eval",
                    "description":"Dispatch a scalar formula f(n) to the GPU for every integer n in [start, end), then aggregate mean/std/min/max/sum on the CPU. Use ONLY when the range is large enough that the sandbox (≤ 5000 iters) can't reach it — typical use: density / mean / tail-statistic checks across millions of values. Formula syntax is a STRICT SUBSET of run_sandboxed_code: arithmetic expression in the single bound variable n, with the constants pi and e and the functions sqrt, cbrt, log, log2, log10, exp, pow, hypot, sin, cos, tan, asin, acos, atan, atan2, sinh, cosh, tanh, abs, sign, floor, ceil, round, min, max. No control flow, no var, no user-defined helpers, no number-theory predicates (those are sandbox-only). Returns aggregate {mean, std_dev, min, max, sum, n, finite_count} — 'finite_count' is how many samples were finite (NaN/Inf are filtered out). Max range 10,000,000 samples; longer ranges are rejected.",
                    "parameters":{
                      "type":"object",
                      "properties":{
                        "formula":{"type":"string","description":"Scalar arithmetic expression in n. Example: 'sqrt(n) * log(n) + sin(n / pi)'."},
                        "start":{"type":"integer","description":"Inclusive start integer."},
                        "end":{"type":"integer","description":"Exclusive end integer. Must satisfy end > start and end - start ≤ 10000000."},
                        "rationale":{"type":"string","description":"Why this range-scale check addresses a paper claim that the sandbox can't reach."}
                      },
                      "required":["formula","start","end","rationale"]
                    }
                  }
                }%s%s%s%s%s%s%s%s%s]""".formatted(dynamicTool, runSymDesc, runSymExprDesc,
                conjectureVerifier == null ? "" : (verifyConjectureToolJson() + verifyConstantToolJson()
                        + verifyOscillationToolJson() + verifyGrowthToolJson()),
                (juliaMathClient != null && juliaMathClient.isPysrAvailable())
                        ? SYMBOLIC_REGRESS_TOOL_JSON : "",
                (sympyIdentityClient != null && sympyIdentityClient.isAvailable())
                        ? PROVE_IDENTITY_TOOL_JSON : "",
                ((includeLiterature && (literatureSearch != null || phase34SqliteLiterature != null))
                        ? SEARCH_LITERATURE_TOOL_JSON : "")
                        + (((referenceReader != null && referenceReader.available())
                                || phase34SqliteLiterature != null)
                                ? READ_REFERENCE_TOOL_JSON : "")
                        + (domainRefClient != null
                                ? LOOKUP_OEIS_TOOL_JSON + LOOKUP_DLMF_TOOL_JSON : ""),
                discoveryAvailable() ? DISCOVER_MERTENS_TOOL_JSON : "",
                divisorDiscoveryAvailable() ? DISCOVER_DIVISOR_TOOL_JSON : "",
                primeDiscoveryAvailable() ? DISCOVER_PRIME_TOOL_JSON : "",
                recallAvailable() ? RECALL_FRONTIER_TOOL_JSON : "",
                (mechanismProposalAvailable() ? PROPOSE_MECHANISM_TOOL_JSON : "")
                        + (scalarStatsAvailable() ? SCALAR_WINDOW_STATS_TOOL_JSON : "")
                        + DERIVE_VALUE_TOOL_JSON);
    }

    /**
     * Phase 24 §24.3 — symbolic_regress tool advertised when the Julia
     * sidecar is up AND app.julia.pysr.enabled=true. Omitting it
     * entirely from the schema when unavailable means the LLM never
     * sees a tool it can't call, which is cheaper (token-wise) than
     * advertising + rejecting.
     */
    /**
     * Lever 2 — citation-grounding tool. Advertised only when a
     * {@link com.emergentterrain.research.LiteratureSearchService} is wired AND
     * the caller passes {@code includeLiterature=true}. Returns curated
     * references from the corpus so the professor cites retrieved work (and the
     * citations land in the tool-call trace) rather than inventing author-year
     * references. No JSON-schema enum (xAI/grok rejects '/'-in-enum).
     */
    private static final String SEARCH_LITERATURE_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"search_literature",
                "description":"Look up citable prior work for this paper's domain/topic. Call this to GROUND any reference to prior work: cite ONLY the exact citation strings/keys this tool returns, and never invent author-year references. An empty/SILENT result means no prior work states the claim — in that case make no author-year citations (a SILENT over a large corpus is itself reportable as a novelty signal). When a corpus-grounded lookup is available, pass the SPECIFIC claim being grounded via 'claim' for the sharpest match.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "topic":{"type":"string","description":"Optional topic override; defaults to the paper's own topic. Use the paper's topic unless you have a specific reason to look up an adjacent one."},
                    "claim":{"type":"string","description":"Optional: the specific claim sentence to ground (e.g. 'the error term in the Dirichlet divisor problem has a bounded O(1) envelope at the x^(1/4) normalisation'). Sharper than a bare topic; preferred when checking whether a RESULT is already known."}
                  },
                  "required":[]
                }
              }
            }""";

    /**
     * Phase 33 L2 (2026-07-06) — full-text reading of a grounded reference. Advertised only when
     * the {@link com.emergentterrain.research.literature.ReferenceCoreReader} is wired AND a core
     * tier exists on disk. The description carries the caller policy (EVIDENCE-gated, one work
     * per paper) and the executor enforces a hard cap of 2 calls per paper.
     */
    private static final String READ_REFERENCE_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"read_reference",
                "description":"READ the full text of a work search_literature matched (open-access subset). Use when search_literature returned EVIDENCE and you need the prior result's actual content — its theorem statement, constant, or range of validity — to position your result against it (consistent-with-but-weaker-than / extends-the-range-of / contradicts). Pass the exact key printed by search_literature and a focused query (the claim or quantity you are checking). Returns the top matching passages. ONE work per paper (hard cap 2 calls); if the source warns of lossy extraction, PARAPHRASE prior formulas and cite the key, never quote them verbatim.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "key":{"type":"string","description":"The exact work key returned by search_literature."},
                    "query":{"type":"string","description":"What to look for in the text — the specific claim, constant, or exponent you are checking."}
                  },
                  "required":["key","query"]
                }
              }
            }""";

    /** Phase 32 §32.5d — OEIS integer-sequence identification. Advertised when domainRefClient set. */
    private static final String LOOKUP_OEIS_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"lookup_oeis",
                "description":"Identify an integer sequence in the OEIS (On-Line Encyclopedia of Integer Sequences). Call this when your paper computes a concrete integer sequence and you want to know whether it is a KNOWN, named one. Pass the leading terms exactly as computed. A match means the sequence is catalogued — cite the A-number and its name; no match is itself reportable (this sequence appears novel). Terms are strings so arbitrarily large integers are fine.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "terms":{"type":"array","items":{"type":"string"},"description":"The leading terms in order, e.g. [\\"0\\",\\"1\\",\\"1\\",\\"2\\",\\"3\\",\\"5\\",\\"8\\"]. Give at least 4-6 terms for a reliable match."}
                  },
                  "required":["terms"]
                }
              }
            }""";

    /** Phase 32 §32.5d — NIST DLMF special-function reference lookup. */
    private static final String LOOKUP_DLMF_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"lookup_dlmf",
                "description":"Retrieve the authoritative NIST Digital Library of Mathematical Functions (DLMF) section(s) for a special-function claim — zeta, gamma, Bernoulli, L-functions, etc. Use when positioning a claim against the standard reference for a special function (definitions, formulas, zeros, asymptotics). Returns the matching sections; cite as 'DLMF \\u00a7<id>' and quote formulas as LaTeX.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "query":{"type":"string","description":"The special-function claim or quantity to look up, e.g. 'Riemann zeta function nontrivial zeros critical line'."}
                  },
                  "required":["query"]
                }
              }
            }""";

    private static final String SYMBOLIC_REGRESS_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"symbolic_regress",
                "description":"Phase 24 — fit a closed-form expression to a small numeric table. Input is rows of [v1, v2, ..., vN, target] (max 256 rows, max 6 input variables). Returns a Pareto front of candidate expressions (mean-squared error vs complexity), sorted by complexity ascending — the first entry is the simplest law the search found, later entries fit better at the cost of complexity. Use when you suspect a clean functional law underlies a parameter sweep (e.g. gravity F=G·m1·m2/r², ideal-gas PV=nRT, Arrhenius k=A·exp(-Ea/RT), RC-decay v=v0·exp(-k·t)) and you want a machine to recover it rather than hand-fitting. Operators default to {+, -, *, /, ^, exp, log}; pass a custom subset from the allowlist {+, -, *, /, ^, exp, log, sqrt, abs, sin, cos} to constrain the search.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "samples":{"type":"array","items":{"type":"array","items":{"type":"number"}},"description":"Rows of [v1, ..., vN, target]. Length(row) must equal length(var_names) + 1."},
                    "var_names":{"type":"array","items":{"type":"string"},"description":"Variable names in order matching the first N entries of each row. Each must match [A-Za-z_][A-Za-z0-9_]*."},
                    "target_name":{"type":"string","description":"Cosmetic name for the target variable (the last column of each row). Defaults to 'f' if omitted."},
                    "operators":{"type":"array","items":{"type":"string"},"description":"Optional. Subset of operators the search is allowed to compose, drawn from the allowlist {+, -, *, /, ^, exp, log, sqrt, abs, sin, cos}. Operators outside the allowlist are rejected sidecar-side. Default: arithmetic + exp/log. (Phase 29: no JSON-schema enum here — xAI/grok rejects tool schemas whose enum values contain '/'; the allowlist is enforced by JuliaSafetyFilter, not the schema.)"},
                    "rationale":{"type":"string","description":"Why a closed-form fit addresses a specific weakness in the paper (e.g. 'paper observes k between 0.03 and 0.05 across a temperature sweep; check whether a clean Arrhenius form fits')."}
                  },
                  "required":["samples","var_names","rationale"]
                }
              }
            }""";

    /**
     * Phase 25 §25.3 — prove_identity tool advertised when the Julia
     * sidecar is up AND app.julia.sympy.enabled=true. Returns a
     * machine certificate (Gosper antidifference or WZ reduction
     * chain) alongside the closed form, which the reviewer panel
     * uses to grant {@code EvidenceLabel.THEOREM} legitimately
     * (phase 25 §25.4 wires that path).
     */
    private static final String PROVE_IDENTITY_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"prove_identity",
                "description":"Phase 25 — attempt a machine-certified proof of a hypergeometric-sum identity via SymPy's Gosper / Zeilberger-WZ machinery. Input is a summand F(k), a summation variable, lower and upper bounds, and optionally a target closed form. Returns 'proved' with a closed form + certificate string when the sum is hypergeometrically summable, 'no_closed_form' otherwise. Use ONLY on sums of hypergeometric terms (products of factorials, binomials, Pochhammer symbols, rational functions, powers); not appropriate for algebraic identities, geometric theorems, or general real-analysis claims. Examples that resolve: Σ binomial(n,k) for k from 0 to n → 2**n; Σ k·binomial(n,k) → n·2**(n-1); Σ 1/(k·(k+1)) for k from 1 to n → n/(n+1); Σ r**k for k from 0 to n → (r**(n+1)-1)/(r-1). Examples that return no_closed_form: Σ 1/k**2 (harmonic — no hypergeometric closed form), Σ k**k. A successful proof carries a certificate that the reviewer panel can stamp as EvidenceLabel.THEOREM in the published paper's audit trail.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "algorithm":{"type":"string","enum":["gosper","zeilberger_wz","sister_celine","auto"],"description":"Which algorithm to attempt. 'gosper' is cheapest, handles indefinite single-variable antidifferences (Σk, Σr^k). 'zeilberger_wz' handles multi-variable definite sums (Σbinomial(n,k), Σ k·binomial(n,k)). 'sister_celine' routes through the same SymPy summation in v1. 'auto' (recommended) tries Gosper first, falls back to WZ."},
                    "summand":{"type":"string","maxLength":512,"description":"The summand F(k) as a Python/SymPy expression. May reference the summation variable + free symbols (e.g. n, m). Example: 'binomial(n, k)', 'k**2', 'r**k', '1/(k*(k+1))'."},
                    "var":{"type":"string","pattern":"^[A-Za-z_][A-Za-z0-9_]*$","description":"Name of the summation variable. Must be a plain identifier."},
                    "lower":{"type":"string","maxLength":64,"description":"Lower bound expression. Usually an integer literal ('0', '1') but symbolic bounds like 'm' are supported."},
                    "upper":{"type":"string","maxLength":64,"description":"Upper bound expression. Usually 'n' for symbolic upper bounds; integer literals also OK."},
                    "target":{"type":"string","maxLength":256,"description":"Optional. Expected closed form for verification mode. Accepted but not yet used by the v1 dispatcher."},
                    "rationale":{"type":"string","description":"Why proving this identity addresses a specific weakness in the paper (e.g. 'paper claims Σ C(n,k) sums to 2^n but treats it as a conjecture; a machine certificate would let the panel grant THEOREM instead of CONJECTURE')."}
                  },
                  "required":["summand","var","lower","upper","rationale"]
                }
              }
            }""";

    /**
     * verify_conjecture tool schema. Built at call time so the two hard caps — the per-call
     * work cap ({@code window_size × num_windows ≤ maxWork}) and the trusted-oracle range cap
     * ({@code window_start + window_size × num_windows ≤ maxN}) — are surfaced with their
     * CONFIGURED values, so the model sizes its first call legally instead of burning calls
     * discovering the limits by trial (review Rec 4). Caps come from {@link ConjectureVerifier}.
     */
    private String verifyConjectureToolJson() {
        long maxWork = conjectureVerifier.maxWork();
        long maxN = conjectureVerifier.maxN();
        return ("""
            ,{
              "type":"function",
              "function":{
                "name":"verify_conjecture",
                "description":"Phase 23.5 — verify a math-domain conjecture of the form `lhs <= k*rhs + c` on a held-out window slice. Pick a window_start the conjecture miner did NOT see (anything outside the prototype's offsets {100, 1000, 5000, 10000, 50000, 100000, 500000, 1000000}). Evaluates num_windows disjoint windows of window_size starting at window_start, computes per-window margin (lhs - (k*rhs + c)), reports held overall (all margins within 2*TOL) or REFUTED with the worst sample as witness. TWO HARD CAPS, both rejected if exceeded: (1) WORK: window_size × num_windows ≤ %d (total integers scanned per call); (2) RANGE: window_start + window_size × num_windows ≤ %d (the trusted-oracle/sieve range — windows cannot extend past it). Size your FIRST call within both. Valid scalar names for lhs/rhs: abundant_density, deficient_density, prime_density, happy_density, mean_omega, mean_Omega, mean_divisor_count, max_divisor_count, mertens_delta, abundant_minus_deficient, omega_Omega_gap, log_window_size, sqrt_window_size, log_window_start, sqrt_window_start, log_sq_window_start, mertens_per_sqrt_W, cramer_gap_ratio, twin_vs_prime_sq, mean_divisor_excess (= mean_divisor_count − ln(center) → ≈ 2γ ≈ 1.1544, use verify_constant), omega_ek_ratio (Erdős–Kac Var(Ω)/ln ln x → 1, use verify_constant target 1), dirichlet_error (Dirichlet divisor error Δ(x)/x^{1/4}, already x^{1/4}-normalised, OSCILLATES around 0 and is bounded iff the Dirichlet conjecture holds — use verify_oscillation with NO scale_rhs, not a bound), squarefree_density (density of squarefree integers Ω(n)=ω(n) → 6/π²≈0.60793, a KNOWN constant — use verify_constant target 0.60793), richly_divisible_density (density of n with d(n)>ω(n)² — an UNCHARTED natural density, no standard result; use verify_constant to measure its limit), omega_excess_density (density of n with Ω(n)−ω(n)≥2, i.e. a cube or ≥2 squared primes — also UNCHARTED), wigert_bound (the Wigert maximal-order scale exp(ln x/ln ln x) — the GROWTH ANCHOR for max_divisor_count), log_log_window_start (the ln ln x scale — the GROWTH ANCHOR for mean_omega/mean_Omega, the Hardy–Ramanujan/Erdős–Kac normal order ω(n)~ln ln x). NOTE: when the lhs GROWS with x (max_prime_gap ~ ln²x by Cramér; max_divisor_count ~ exp(ln2·ln x/ln ln x) by Wigert; mean_divisor_count ~ log x; mean_omega/mean_Omega ~ ln ln x), a flat feature rhs makes k*rhs+c near-constant and the bound is always refuted — use a GROWTH ANCHOR as rhs whose own growth MATCHES the lhs: log_sq_window_start (the ln²x scale) for max_prime_gap, wigert_bound for max_divisor_count, log_log_window_start for mean_omega/mean_Omega, log_window_start for mean_divisor_count. A flat rhs (mean_divisor_excess, omega_ek_ratio, dirichlet_error, deficient_density) for a growing lhs is refuted by construction — anchor on the matching growth scale instead so the bound can track that growth and actually hold (the intercept c then recovers the additive constant: B₁≈0.261 for mean_omega, 2γ for the divisor law).",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "lhs":{"type":"string","description":"Left-hand-side scalar name (from the vocabulary above)."},
                    "rhs":{"type":"string","description":"Right-hand-side scalar name."},
                    "k":{"type":"number","description":"Multiplicative coefficient on rhs."},
                    "c":{"type":"number","description":"Additive constant."},
                    "rhs2":{"type":"string","description":"OPTIONAL second right-hand-side scalar for a two-term bound lhs <= k*rhs + k2*rhs2 + c (e.g. a residual-discovered companion term). Omit for a one-term bound."},
                    "k2":{"type":"number","description":"OPTIONAL coefficient on rhs2 (default 0; ignored unless rhs2 is set)."},
                    "window_start":{"type":"integer","description":"First integer in the first evaluated window. Pick a held-out offset. Must satisfy window_start + window_size × num_windows ≤ %d."},
                    "window_size":{"type":"integer","description":"Integers per window. window_size × num_windows ≤ %d (work cap)."},
                    "num_windows":{"type":"integer","description":"How many disjoint windows to evaluate (default 5)."}
                  },
                  "required":["lhs","rhs","k","c","window_start","window_size"]
                }
              }
            }""").formatted(maxWork, maxN, maxN, maxWork);
    }

    /** 2026-06-10 (lever D, frame 1) — the constant-convergence companion tool, advertised whenever
     *  verify_conjecture is (both need the conjecture verifier). */
    private String verifyConstantToolJson() {
        long maxWork = conjectureVerifier.maxWork();
        long maxN = conjectureVerifier.maxN();
        return ("""
            ,{
              "type":"function",
              "function":{
                "name":"verify_constant",
                "description":"2026-06-10 — verify that a scalar CONVERGES TO A CONSTANT, on a held-out window slice. Use this (NOT verify_conjecture) when the lhs's real content is a LIMIT, not a one-sided bound: a normalized ratio that plateaus — e.g. twin_vs_prime_sq → the Hardy–Littlewood twin constant 2C₂ ≈ 1.32032, or cramer_gap_ratio → a Cramér-type constant. Evaluates num_windows disjoint windows of window_size starting at window_start and computes the mean value and the worst |lhs − target| (two-sided). With 'target' supplied, reports CONVERGED (worst relative deviation ≤ tol) or NOT; without it, estimates the limit as the mean and reports the spread. A flat/oscillating/ratio quantity that REFUTES every `lhs ≤ k·rhs + c` bound is exactly the case for this tool — the result of such a paper is the measured constant + its convergence, not a refuted inequality. Same caps as verify_conjecture: WORK window_size × num_windows ≤ %d; RANGE window_start + window_size × num_windows ≤ %d. Valid lhs scalars: the same vocabulary as verify_conjecture (twin_vs_prime_sq, cramer_gap_ratio, mertens_per_sqrt_W, the densities, etc.).",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "lhs":{"type":"string","description":"Scalar whose convergence to a constant is tested."},
                    "target":{"type":"number","description":"The conjectured limit (e.g. 1.32032 for twin_vs_prime_sq → 2C₂). Omit to estimate it empirically from the windows."},
                    "tol":{"type":"number","description":"Relative tolerance for CONVERGED (worst |dev| / |target| ≤ tol). Default 0.05."},
                    "window_start":{"type":"integer","description":"First integer in the first window (single-slice mode). Ignored when scale_ladder is true."},
                    "window_size":{"type":"integer","description":"Integers per window. window_size × num_windows ≤ %d (work cap). Default 5000 in scale_ladder mode."},
                    "num_windows":{"type":"integer","description":"How many disjoint windows to evaluate (default 5)."},
                    "scale_ladder":{"type":"boolean","description":"SET TRUE for a MEASUREMENT across scales — the tool AUTO-EVALUATES the lhs at a GEOMETRIC ladder of window_starts (1e5, 1e6, 1e7, 1e8, up to near the trusted ceiling) and reports the per-scale trajectory + whether the gap to the limit is SHRINKING across scales. This is the load-bearing measurement (window_start is ignored, the tool picks the scales). REQUIRED for a slow converger: a single low-x window understates convergence and a clean limit only emerges at high x. Use this rather than guessing high window_start values yourself."}
                  },
                  "required":["lhs"]
                }
              }
            }""").formatted(maxWork, maxN, maxWork);
    }

    /** 2026-06-10 (lever D, frame 2) — oscillation/sign-change measurement tool. */
    private String verifyOscillationToolJson() {
        long maxWork = conjectureVerifier.maxWork();
        long maxN = conjectureVerifier.maxN();
        return ("""
            ,{
              "type":"function",
              "function":{
                "name":"verify_oscillation",
                "description":"2026-06-10 — measure the OSCILLATION of a scalar that swings around zero, on a held-out window slice. Use this (NOT verify_conjecture) when the lhs changes sign and has no one-sided bound — the canonical case is mertens_delta (the Mertens function M(x), which changes sign infinitely often; the RH-relevant statement is that the normalized envelope |M(x)|/√x stays bounded). Reports sign changes, amplitude, near-zero mean, and — when you pass scale_rhs (e.g. sqrt_window_start) — the bounded envelope max|lhs|/scale. A sign-changing quantity that REFUTES every `lhs ≤ k*rhs + c` bound but oscillates with a bounded envelope is a successful measurement, not a null. IMPORTANT — already-normalised scalars: dirichlet_error is ALREADY Δ(x)/x^{1/4}, so call verify_oscillation(dirichlet_error) with NO scale_rhs; passing scale_rhs=sqrt_window_start re-divides by √x (measures Δ(x)/x^{3/4}) and collapses the envelope toward 0, hiding the result — the raw envelope of dirichlet_error staying O(1) IS the Dirichlet-conjecture signal. Pass scale_rhs only for a RAW unnormalised lhs like mertens_delta (|M|/√x). Same caps as verify_conjecture: WORK window_size × num_windows ≤ %d; RANGE window_start + window_size × num_windows ≤ %d.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "lhs":{"type":"string","description":"The oscillating scalar (e.g. mertens_delta)."},
                    "scale_rhs":{"type":"string","description":"Optional scale scalar for the envelope max|lhs|/scale (e.g. sqrt_window_start for a RAW lhs like mertens_delta → |M|/√x). OMIT for an already-normalised lhs such as dirichlet_error (= Δ(x)/x^{1/4}) — passing one double-normalises and hides the result. Omit to skip the envelope."},
                    "window_start":{"type":"integer","description":"First integer in the first window (single-slice mode). Ignored when scale_ladder is true."},
                    "window_size":{"type":"integer","description":"Integers per window. window_size × num_windows ≤ %d (work cap). Default 5000 in scale_ladder mode."},
                    "num_windows":{"type":"integer","description":"How many disjoint windows to evaluate (default 5)."},
                    "scale_ladder":{"type":"boolean","description":"SET TRUE to MEASURE THE ENVELOPE ACROSS SCALES — the tool AUTO-EVALUATES the lhs at a GEOMETRIC ladder of window_starts (1e5, 1e6, 1e7, 1e8, up to near the trusted ceiling) and reports whether the oscillation amplitude/envelope is FLAT across decades (a BOUNDED O(1) envelope — the load-bearing signal) or GROWS with x (not bounded at reachable x, e.g. ~log log x). This is the load-bearing measurement (window_start is ignored, the tool picks the scales). REQUIRED to settle boundedness: a single low-x window CANNOT distinguish a bounded amplitude from a slowly growing one. Use this rather than hand-picking high window_start values. scale_rhs still applies (tracks max|lhs|/scale per rung for a RAW lhs like mertens_delta; OMIT it for an already-normalised lhs such as dirichlet_error and the raw amplitude is tracked)."}
                  },
                  "required":["lhs"]
                }
              }
            }""").formatted(maxWork, maxN, maxWork);
    }

    /** 2026-06-10 (lever D, frame 3) — super-log growth-scale measurement tool. */
    private String verifyGrowthToolJson() {
        long maxWork = conjectureVerifier.maxWork();
        long maxN = conjectureVerifier.maxN();
        return ("""
            ,{
              "type":"function",
              "function":{
                "name":"verify_growth",
                "description":"2026-06-10 — measure the GROWTH RATE of a super-logarithmic scalar, on a held-out window slice. Use this (NOT verify_conjecture) when the lhs grows faster than any power of log x so no log_window_start anchor can bound it — the canonical case is max_divisor_count, which by Wigert grows like exp(ln2·ln x/ln ln x), i.e. log(d_max)·ln ln x/ln x → ln2 ≈ 0.69315. Transforms each window's lhs into the growth coordinate (scale='wigert': log(lhs)·ln ln x/ln x; or 'loglog': lhs·ln ln x/ln x) and reports whether that converges to the growth constant (target defaults to ln2 for 'wigert'). A quantity that refutes every bound but whose Wigert ratio approaches ln2 is a successful growth-law measurement. Same caps as verify_conjecture: WORK window_size × num_windows ≤ %d; RANGE window_start + window_size × num_windows ≤ %d.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "lhs":{"type":"string","description":"The super-log-growing scalar (e.g. max_divisor_count)."},
                    "scale":{"type":"string","description":"Growth coordinate: 'wigert' (log(lhs)·lnln x/ln x, default) or 'loglog' (lhs·lnln x/ln x)."},
                    "target":{"type":"number","description":"The conjectured growth constant. Defaults to ln2≈0.69315 for 'wigert'. Omit (with non-wigert scale) to estimate empirically."},
                    "tol":{"type":"number","description":"Relative tolerance for CONVERGED. Default 0.10 (the Wigert limit is approached slowly)."},
                    "window_start":{"type":"integer","description":"First integer in the first window (held-out, high-x; single-slice mode). Ignored when scale_ladder is true. window_start + window_size × num_windows ≤ %d."},
                    "window_size":{"type":"integer","description":"Integers per window. window_size × num_windows ≤ %d (work cap). Default 5000 in scale_ladder mode."},
                    "num_windows":{"type":"integer","description":"How many disjoint windows to evaluate (default 5)."},
                    "scale_ladder":{"type":"boolean","description":"SET TRUE to MEASURE THE GROWTH LAW ACROSS SCALES — the tool AUTO-EVALUATES the growth coordinate at a GEOMETRIC ladder of window_starts (1e5, 1e6, 1e7, 1e8, up to near the trusted ceiling) and reports the per-scale trajectory + whether the gap to the growth constant is SHRINKING. This is the load-bearing measurement (window_start is ignored, the tool picks the scales) and it NEVER hits the WORK cap — use this instead of manual high-x windows, which exceed the cap. REQUIRED for a growth claim: the coordinate approaches its constant only logarithmically, so a single low-x slice cannot support (or refute) 'slow convergence'."}
                  },
                  "required":["lhs","window_start","window_size"]
                }
              }
            }""").formatted(maxWork, maxN, maxN, maxWork);
    }

    /**
     * Phase 30 fork 2b Phase 4 — runtime-discovery tool. Advertised only when
     * {@link #discoveryAvailable()} (discovery wired AND runtimeExecEnabled). It
     * computes the cumulative Mertens function M(x) at large x via a method that
     * is generated and then validated against ground truth before its result is
     * trusted — so it is mertens-class by construction.
     */
    private static final String DISCOVER_MERTENS_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"discover_mertens_value",
                "description":"Compute the cumulative Mertens function M(x) = sum of the Möbius function mu(n) for n=1..x at a LARGE x (beyond the 2,000,000 direct-sieve range) when a Mertens-related claim genuinely needs it. The value is produced by a numeric method that is generated and then VALIDATED against ground truth (exact agreement on checkpoints in [1, 2000000] plus the known M(10^7)=1037) before it is trusted; on failure it returns a note, never a guessed number. Use ONLY for M(x) at x > 2000000. The returned value is a COMPUTED result from a validated method, NOT an established mathematical fact — report it as such.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "x":{"type":"integer","description":"The target n: compute M(x). Must be > 2000000 (use a direct method below that)."},
                    "rationale":{"type":"string","description":"Why the paper's Mertens claim needs M(x) at this scale."}
                  },
                  "required":["x","rationale"]
                }
              }
            }""";

    /**
     * Phase 30 — runtime-discovery tool for the Dirichlet divisor sum, the divisor-domain
     * sibling of {@link #DISCOVER_MERTENS_TOOL_JSON}. Advertised only when {@link
     * #divisorDiscoveryAvailable()} (discovery wired AND runtimeExecEnabled AND the divisor
     * tool gate on). It computes D(x) at large x via a generated, moat-validated method —
     * divisor-class by construction. Anchored by D(10^7) beyond the sieve (parity with Mertens).
     */
    private static final String DISCOVER_DIVISOR_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"discover_divisor_value",
                "description":"Compute the Dirichlet divisor summatory function D(x) = sum of the number-of-divisors function d(n) for n=1..x at a LARGE x (beyond the 2,000,000 direct-sieve range) when a divisor-sum claim genuinely needs it. The value is produced by a numeric method that is generated and then VALIDATED against ground truth (exact agreement on checkpoints in [1, 2000000] plus the known D(10^7)=162725364) before it is trusted; on failure it returns a note, never a guessed number. Use ONLY for D(x) at x > 2000000. The returned value is a COMPUTED result from a validated method, NOT an established mathematical fact — report it as such.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "x":{"type":"integer","description":"The target n: compute D(x). Must be > 2000000 (use a direct method below that)."},
                    "rationale":{"type":"string","description":"Why the paper's divisor-sum claim needs D(x) at this scale."}
                  },
                  "required":["x","rationale"]
                }
              }
            }""";

    /* Path A — prime-counting discovery: validates a π(x) method against the sidecar's
     * prime oracle (quantity="prime") on the in-sieve checkpoints + a beyond-range anchor
     * π(10^7) (parity with the Mertens/divisor anchors), so a candidate that passes the
     * in-sieve checkpoints but diverges past it is caught. */
    private static final String DISCOVER_PRIME_SPEC =
            "P(x) = the number of primes p <= x (the prime-counting function pi(x))";
    private static final java.util.List<Long> DISCOVER_PRIME_CHECKPOINTS =
            java.util.List.of(1L, 2L, 10L, 1_000L, 100_000L, 999_983L, 1_000_000L, 2_000_000L);
    private static final java.util.Map<Long, Long> DISCOVER_PRIME_ANCHORS =
            java.util.Map.of(10_000_000L, 664_579L);   // π(10^7), OEIS A006880

    /**
     * Phase 30 (Path A) — runtime-discovery tool for the prime-counting function, the
     * prime-domain sibling of {@link #DISCOVER_MERTENS_TOOL_JSON} / {@link
     * #DISCOVER_DIVISOR_TOOL_JSON}. Advertised only when {@link #primeDiscoveryAvailable()}
     * (discovery wired AND runtimeExecEnabled AND the prime tool gate on). It computes π(x)
     * at large x via a generated, moat-validated method — prime-class by construction.
     * Anchored by π(10^7) beyond the sieve.
     */
    private static final String DISCOVER_PRIME_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"discover_prime_value",
                "description":"Compute the prime-counting function pi(x) = the number of primes p <= x at a LARGE x (beyond the 2,000,000 direct-sieve range) when a prime-density / prime-counting claim genuinely needs it. The value is produced by a numeric method that is generated and then VALIDATED against ground truth (exact agreement on checkpoints in [1, 2000000] plus the known pi(10^7)=664579) before it is trusted; on failure it returns a note, never a guessed number. Use ONLY for pi(x) at x > 2000000. The returned value is a COMPUTED result from a validated method, NOT an established mathematical fact — report it as such.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "x":{"type":"integer","description":"The target n: compute pi(x). Must be > 2000000 (use a direct method below that)."},
                    "rationale":{"type":"string","description":"Why the paper's prime-counting claim needs pi(x) at this scale."}
                  },
                  "required":["x","rationale"]
                }
              }
            }""";

    /**
     * Phase 30 fork 2b §8b — recall tool. Advertised only when {@link
     * #recallAvailable()}. READ-ONLY: it returns the persisted, independently
     * re-verified frontier corpus (records + best verified method) with each value's
     * trust ceiling stated, so a paper can cite computed frontier values without
     * re-running discovery and without mistaking a moat-validated value for a proof.
     */
    private static final String RECALL_FRONTIER_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"recall_frontier_knowledge",
                "description":"Look up the system's accumulated, independently re-verified frontier knowledge for a bound. Returns the validated (x, value, source, trust) records and the best verified method's reach/cost — all carried with each value's TRUST CEILING. A 'FULL'-trust value was validated by exact agreement against a trusted oracle on its overlap range: report it as a COMPUTED, verified value at that x, NOT as a proof of the bound for all x. Use this to ground a frontier claim in already-verified computation instead of re-deriving it. An empty result means nothing has been accumulated for that bound yet — make no frontier claim in that case.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "bound":{"type":"string","description":"Optional bound key. Defaults to the corpus matching this paper's topic ('divisor_D' = cumulative divisor sum D(x)=sum of d(n); otherwise 'mertens_M' = cumulative Mertens function M(x)). Pass a key explicitly only to read a different corpus than your topic's."}
                  },
                  "required":[]
                }
              }
            }""";

    /**
     * Phase 30 Wave 6 — 30.7 P3: propose a runnable mechanism for the bound being written about, and
     * have it gate-checked. Advertised only when {@link #mechanismProposalAvailable()}.
     */
    /**
     * 2026-06-05 — deterministic per-window scalar statistics. Exists because the
     * panel's follow-up computations (HCN divisor growth, cross-scalar correlation
     * over refuted windows — P-b72c-121's open questions) kept dying on the sandbox
     * loop cap across three sessions. Same trust class as verify_conjecture: pure
     * arithmetic over the trusted sieve substrate, no LLM/sandbox spend.
     */
    private static final String SCALAR_WINDOW_STATS_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"scalar_window_stats",
                "description":"Compute deterministic statistics of one or two window scalars across consecutive windows: min/max (with the window start achieving the max), mean, and — when two scalars are given — the Pearson and Spearman correlation between them across the windows. Use this instead of run_sandboxed_code for divisor-count growth probes, scalar trend checks, or correlation questions (it has no loop limit). Scalars are the same names verify_conjecture uses (e.g. max_divisor_count, mean_divisor_count, mertens_per_sqrt_W, prime_density).",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "lhs":{"type":"string","description":"Scalar to summarize (required)"},
                    "rhs":{"type":"string","description":"Optional second scalar; adds Pearson+Spearman correlation with lhs"},
                    "window_start":{"type":"integer","description":"First window start (>= 1)"},
                    "window_size":{"type":"integer","description":"Window size (100..50000)"},
                    "num_windows":{"type":"integer","description":"Consecutive windows (1..200; windows past the 2,000,000 sieve ceiling are skipped)"}
                  },
                  "required":["lhs","window_start","window_size","num_windows"]
                }
              }
            }""";

    private static final String PROPOSE_MECHANISM_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"propose_mechanism",
                "description":"Propose a RUNNABLE MECHANISM that explains the bound you are writing about, and have it checked. A mechanism is a few definitions in a tiny DSL, one per line: 'name := expr' for intermediates and 'predict(TARGET) := expr' for the predicted scalar, where TARGET is the bound's left-hand scalar and expr uses ONLY: existing scalar names, earlier-defined names, the operators * and /, and the functions log(), sqrt(), square(), reciprocal() — written in fully-parenthesised form, e.g. predict(mean_omega) := log(sqrt(mean_divisor_count)). Optionally add 'invariant: monotone_increasing(predict in X)' lines. The mechanism is ACCEPTED only if it (1) closes (every term is an observable or produced, nothing dangling/unused), (2) is dimensionally consistent, (3) respects its declared monotonicities, and (4) REGENERATES the bound on held-out windows (actual <= predict + tolerance). A pass means a verified runnable model, NOT a proof. Use it to turn a bound into a mechanism.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "dsl":{"type":"string","description":"The mechanism, newline-separated 'name := expr' / 'predict(TARGET) := expr' / 'invariant: ...' lines."},
                    "window_start":{"type":"integer","description":"First window start for the held-out check (1 <= start, start+window_size*num_windows <= 2000000)."},
                    "window_size":{"type":"integer","description":"Integers per window (e.g. 1000)."},
                    "num_windows":{"type":"integer","description":"How many windows to evaluate (e.g. 8)."}
                  },
                  "required":["dsl","window_start","window_size"]
                }
              }
            }""";

    // ── Executor factory ──────────────────────────────────────────────────────

    /**
     * Returns a function that maps a tool-call JSON string to a result string.
     * The function closes over the paper draft so run_param_sweep has access to
     * the paper's domain, topic, and original parameters.
     */
    public Function<String, String> buildExecutor(PaperDraft draft) {
        return buildExecutor(draft, new ToolMetrics());
    }

    /**
     * Phase 5 C3 — overload that lets the caller collect per-paper sandbox-reject
     * counts. The metrics object is mutated as tool calls fire; the narration
     * service reads it after the professor stage completes.
     */
    public Function<String, String> buildExecutor(PaperDraft draft, ToolMetrics metrics) {
        // Phase 33 L2 — per-paper read_reference budget (contract: one work per paper; hard cap 2
        // so a follow-up query on the same work stays possible without opening a token sink).
        final int[] readReferenceCalls = {0};
        return toolCallJson -> {
            try {
                JsonNode call = mapper.readTree(toolCallJson);
                String   name = call.path("name").asText("");
                JsonNode args = call.path("arguments");
                // arguments may arrive as a string (Groq sometimes wraps it)
                if (args.isTextual()) args = mapper.readTree(args.asText());
                String result = switch (name) {
                    case "run_param_sweep"    -> runParamSweep(draft, args);
                    case "run_predicate_scan" -> runPredicateScan(args);
                    case "evaluate_symbolic"  -> evaluateSymbolic(args);
                    case "run_sandboxed_code" -> runSandboxedCode(draft, args);
                    case "run_symbolic"       -> runSymbolic(args);
                    case "run_gpu_eval"       -> runGpuEval(args);
                    case "verify_conjecture"  -> appendScalarDefinitions(verifyConjecture(args), args);
                    case "verify_constant"    -> appendScalarDefinitions(verifyConstant(args), args);
                    case "verify_oscillation" -> appendScalarDefinitions(verifyOscillation(args), args);
                    case "verify_growth"      -> appendScalarDefinitions(verifyGrowth(args), args);
                    case "discover_mertens_value" -> discoverMertensValue(args);
                    case "discover_divisor_value" -> discoverDivisorValue(args);
                    case "discover_prime_value" -> discoverPrimeValue(args);
                    case "recall_frontier_knowledge" -> recallFrontierKnowledge(args, draft);
                    case "propose_mechanism" -> proposeMechanism(args);
                    case "scalar_window_stats" -> scalarWindowStats(args);
                    case "symbolic_regress"   -> symbolicRegress(args);
                    case "prove_identity"     -> proveIdentity(args);
                    case "search_literature"  -> searchLiterature(draft, args);
                    case "read_reference"     -> readReference(args, readReferenceCalls);
                    case "lookup_oeis"        -> lookupOeis(args);
                    case "lookup_dlmf"        -> lookupDlmf(args);
                    case "derive_value"       -> deriveValue(args);
                    default -> "Unknown tool: " + name;
                };
                // Phase 10 — count both rejections AND successes so the
                // professor stage can detect the "all tools failed but
                // synthesis prose was written anyway" pattern (P-352, P-353
                // each had 4 sandbox rejects, 0 successes, then fabricated
                // statistics into the published Findings).
                //
                // Phase 17 P8b — counter symmetry fix. The prior logic
                // classified a result as "success" whenever it wasn't
                // REJECTED-by-safety AND didn't start with one of the
                // five known runtime-failure prefixes. Holes:
                //   - "Missing expression./code./formula." (invalid args)
                //   - "Invalid range:", "Invalid params:", "Invalid metric:",
                //     "Invalid predicate scan:", "Invalid predicate token:"
                //   - "Param sweep failed:" (not in the failed list)
                //   - "Topic not computable via MathPhysicsEngine."
                //   - "Range too large:", "run_gpu_eval not configured:"
                //   - "Unknown tool:"
                // All of these would slip into the `else if (!failed)` branch
                // and increment the success counter. P-a328-613 + P-a328-623
                // both had all-`run_symbolic` traces with no actual successful
                // result yet were marked `prof:ok` (D3 didn't fire because
                // totalSuccesses ended up > 0 from one invalid-args call
                // somewhere).
                //
                // The fix: invert to a default-deny model. A call counts as
                // success ONLY when the result starts with a known
                // success-marker prefix. Anything else (rejected, runtime
                // failure, invalid args, unknown tool) does not increment
                // the success counter. The reject counter still tracks
                // safety-review rejections separately for the
                // sandbox_reject=N call-path stamp.
                boolean rejected = result.startsWith("REJECTED by safety review");
                boolean success  = isSuccessResult(result);
                if ("run_sandboxed_code".equals(name)) {
                    if (rejected) metrics.incrementSandboxRejects();
                    if (success)  metrics.incrementSandboxSuccesses();
                }
                if ("run_symbolic".equals(name) || "evaluate_symbolic".equals(name)) {
                    if (rejected) metrics.incrementSymbolicRejects();
                    if (success)  metrics.incrementSymbolicSuccesses();
                }
                if ("run_gpu_eval".equals(name)) {
                    if (rejected) metrics.incrementGpuEvalRejects();
                    if (success)  metrics.incrementGpuEvalSuccesses();
                }
                if ("run_predicate_scan".equals(name) && success) {
                    metrics.incrementPredicateScanSuccesses();
                }
                // 2026-05-29 — verify_conjecture success counter. The Phase 23.5
                // comment claimed this tool was "success-counted via isSuccessResult"
                // but NO counter was ever wired, so totalSuccesses() omitted it.
                // Effect: a CONJECTURE_INVESTIGATION paper whose professor uses
                // verify_conjecture (the purpose-built tool) — and nothing else that
                // happens to increment a counter — accrued totalSuccesses()==0 and
                // was killed by the D3 empty_trace gate even when every verify call
                // succeeded (observed room b72c4e10: P-b72c-3/4/5/6 all suppressed
                // once legacy-ai correctly used verify_conjecture only).
                // A verify result starting with "verify_conjecture:" (HELD or
                // REFUTED — both legitimate computations) counts; "Invalid …"/
                // "… not configured" (failed args / sidecar) does not, via isSuccessResult.
                if ("verify_conjecture".equals(name) && success) {
                    metrics.incrementVerifyConjectureSuccesses();
                }
                // run_param_sweep is intentionally not counted here — same
                // as pre-Phase-17. Param-sweep papers are rare enough on
                // INTERDISCIPLINARY topics that the D3 gate wasn't relying
                // on their contribution; adding a counter for them is a
                // feature change, not the bug-fix this commit scopes to.
                return result;
            } catch (Exception e) {
                log.warn("MathToolSuite: tool call failed: {}", e.getMessage());
                return "Tool execution error: " + e.getMessage();
            }
        };
    }

    // ── Tool implementations ──────────────────────────────────────────────────

    /**
     * Lever 2 — citation grounding. Returns curated references for the paper's
     * (domain, topic) so the professor cites retrieved work. Defaults the topic
     * to the draft's own; honours an optional {@code topic} override. The result
     * deliberately carries NO {@link #SUCCESS_PREFIXES} marker — a literature
     * lookup is not a computational success and must not feed the
     * zero-successes→suppressed gate.
     */
    private String searchLiterature(PaperDraft draft, JsonNode args) {
        if (phase34SqliteLiterature != null) {
            String query = args.path("claim").asText("");
            if (query.isBlank()) query = args.path("topic").asText("");
            if (query.isBlank() && draft != null) query = draft.topicName();
            return phase34SqliteLiterature.search(query);
        }
        // Phase 32 §32.5 step 4 — grounding mode. For qualifying papers (see groundingApplies),
        // route the lookup to the offline BM25 corpus on the py-sidecar. Any failure (sidecar
        // down/cold/corpus-less) returns null from the client and falls through to the curated
        // path — grounding never blocks a paper.
        if (groundingClient != null && groundingApplies(draft)) {
            String claim = args.path("claim").asText("");
            if (claim.isBlank()) claim = args.path("topic").asText("");
            if (claim.isBlank() && draft != null) claim = draft.topicName();
            if (!claim.isBlank()) {
                var grounding = groundingClient.ground(claim, 5);
                if (grounding != null) {
                    String grounded = formatGrounding(grounding, claim);
                    // Phase 33 L2 — when the reader is wired, an EVIDENCE match is readable in
                    // full: nudge exactly one read_reference call (per-paper cap enforced in the
                    // executor).
                    if (referenceReader != null && referenceReader.available()) {
                        // Run-38 (P-b72c-77627844) — the generic "you may read" nudge was skipped
                        // on four straight EVIDENCE matches even when the cores WERE on disk.
                        // Name the keys whose full text is guaranteed to return: a concrete
                        // zero-miss offer, not a suggestion.
                        // 2026-07-07 (closing-condition diagnosis) — the offer now fires on
                        // SILENT results too: SILENT is the CORRECT verdict for frontier claims
                        // (their novelty), and the near-miss keys are already rendered as
                        // citable context — the frontier papers that best satisfy every other
                        // closing-condition lane were the only ones never OFFERED a read.
                        java.util.List<String> onDisk = new java.util.ArrayList<>();
                        java.util.regex.Matcher km =
                                java.util.regex.Pattern.compile("\\bW\\d{7,}\\b").matcher(grounded);
                        while (km.find() && onDisk.size() < 3) {
                            String key = km.group();
                            if (!onDisk.contains(key) && referenceReader.hasCore(key)) {
                                onDisk.add(key);
                            }
                        }
                        if (!onDisk.isEmpty()) {
                            grounded += " FULL TEXT ON DISK for " + String.join(", ", onDisk)
                                    + " — a read_reference call on one of these RETURNS the actual "
                                    + "passages (guaranteed, no miss). Read it before citing it "
                                    + "(checklist rule): position your result against the work's "
                                    + "actual content."
                                    + (grounded.contains("SILENT")
                                            ? " No prior work states your claim — so read the "
                                                    + "NEAREST-CONTEXT work and position against "
                                                    + "it: adjacent-but-distinct (a different "
                                                    + "normalization, a weaker range, a related "
                                                    + "constant) is itself a reportable "
                                                    + "relationship."
                                            : "");
                        } else {
                            grounded += " NOTE: you may READ a matched work's full text — call "
                                    + "read_reference with its [W…] key and a focused query (ONE "
                                    + "work per paper; some works have no stored core — the tool "
                                    + "says so and you fall back to the snippet).";
                        }
                    }
                    return grounded;
                }
            }
        }
        if (literatureSearch == null) {
            return "search_literature not configured: no literature corpus wired.";
        }
        String domain = draft != null ? draft.domain() : null;
        String argTopic = args.path("topic").asText("");
        String topic = !argTopic.isBlank() ? argTopic
                : (draft != null ? draft.topicName() : null);
        if (domain == null || topic == null || topic.isBlank()) {
            return "search_literature: missing domain/topic — cannot search.";
        }
        java.util.List<com.emergentterrain.research.LiteratureReference> refs =
                literatureSearch.findFor(domain, topic);
        if (refs.isEmpty()) {
            return "search_literature: no references found for domain=" + domain
                    + " topic=" + topic + ". Make no author-year citations.";
        }
        StringBuilder sb = new StringBuilder("search_literature: ")
                .append(refs.size()).append(" reference(s) for ")
                .append(domain).append('/').append(topic).append(":\n");
        for (com.emergentterrain.research.LiteratureReference r : refs) {
            sb.append("- ").append(r.citation());
            if (r.closedForm() != null && !r.closedForm().isBlank())
                sb.append(" | closed-form: ").append(r.closedForm());
            if (r.validRegion() != null && !r.validRegion().isBlank())
                sb.append(" | valid: ").append(r.validRegion());
            if (r.magnitudeEstimate() != null && !r.magnitudeEstimate().isBlank())
                sb.append(" | magnitude: ").append(r.magnitudeEstimate());
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Phase 32 §32.5 step 4 — the widened caller policy (2026-07-04 reframe): grounding applies
     * to promotion-track papers (the original design) PLUS measurement-frame / priority-target
     * conjecture papers and anomaly-investigation topics — the classes that actually hit the
     * panel-novelty ceiling. Everything else keeps the flat-cost curated path.
     */
    static boolean groundingApplies(PaperDraft draft) {
        if (draft == null) {
            return false;
        }
        if (draft.kind() == com.emergentterrain.research.PaperKind.ANOMALY_PROMOTION) {
            return true;
        }
        String topic = draft.topicName();
        if (topic == null) {
            return false;
        }
        if (topic.startsWith("anomaly/")) {
            return true;
        }
        if (topic.startsWith("conjecture/")) {
            String lhs = topic.substring("conjecture/".length());
            return com.emergentterrain.research.conjecture.ScalarOpenness.selectionRank(lhs) == 0
                    || com.emergentterrain.research.conjecture.ScalarOpenness
                            .measurementFrame(lhs) != null;
        }
        return false;
    }

    /**
     * Render a grounding result for the tool-result channel. EVIDENCE = matching prior work
     * (cite ONLY the provided keys; assess support-vs-contradiction per snippet — the contracrow
     * step runs here in the professor's context, where the reasoning happens). SILENT = no
     * reference in the corpus states the claim (a positive novelty signal over a real corpus,
     * NOT a can't-assess) with near-miss references citable as context only.
     */
    static String formatGrounding(
            com.emergentterrain.research.literature.GroundingClient.GroundingResult grounding,
            String claim) {
        StringBuilder sb = new StringBuilder("search_literature (grounded, corpus of ")
                .append(grounding.corpusDocs()).append(" works): ");
        if (grounding.isEvidence()) {
            sb.append(String.format(
                    "EVIDENCE — prior work matches this claim (retrieval confidence %.2f). "
                    + "Cite ONLY the keys below, as [key]. For EACH snippet, assess whether it "
                    + "SUPPORTS or CONTRADICTS the specific claim \"%s\" — a contradiction with "
                    + "prior work must be reported, not smoothed over; a match means the claim "
                    + "is a REDISCOVERY and must be framed as one, with the citation.%n",
                    grounding.confidence(), clipClaim(claim)));
        } else {
            sb.append(String.format(
                    "SILENT — NO reference in the corpus states this claim (retrieval "
                    + "confidence %.2f below threshold). Over a corpus of this size that is a "
                    + "genuine-novelty signal: say explicitly that a corpus search found no "
                    + "prior statement of the claim. The references below are NEAR MISSES — "
                    + "related work citable as CONTEXT (as [key]), never as support for the "
                    + "claim itself. Do not invent author-year citations beyond these keys. "
                    + "SUBJECT CHECK before you cite any near miss: cite it ONLY if it shares "
                    + "the claim's specific subject — the same object, method, quantity, or "
                    + "constant. A work that overlaps only on a generic word (a shared term "
                    + "like \"flow\", \"density\", or \"scale\" used in an unrelated field) is "
                    + "NOT related work — drop it silently, do not list it as context. It is "
                    + "correct and expected for a SILENT result to yield NO cited context when "
                    + "no near miss shares the subject.%n",
                    grounding.confidence()));
        }
        for (var e : grounding.evidence()) {
            sb.append("- [").append(e.key()).append("] ");
            if (!e.authors().isBlank()) sb.append(e.authors()).append(" ");
            if (e.year() > 0) sb.append('(').append(e.year()).append(") ");
            sb.append(e.title());
            if (!e.venue().isBlank()) sb.append(". ").append(e.venue());
            if (!e.field().isBlank()) sb.append(" [subject: ").append(e.field())
                    .append(!e.subfield().isBlank() ? "/" + e.subfield() : "").append(']');
            if (!e.doi().isBlank()) sb.append(". ").append(e.doi());
            if (!e.snippet().isBlank()) sb.append("\n    \"").append(e.snippet()).append('"');
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String clipClaim(String claim) {
        if (claim == null) return "";
        return claim.length() > 200 ? claim.substring(0, 200) + "…" : claim;
    }

    private String runParamSweep(PaperDraft draft, JsonNode args) {
        // Read both as doubles so the transpose test sees the true scale value
        // (asInt would truncate 4.9→4 and hide that it belongs in param2).
        double raw1     = args.path("param1").asDouble(-1.0);
        double raw2     = args.path("param2").asDouble(-1.0);
        String rationale = args.path("rationale").asText("(no rationale)");
        SweepParams resolved = resolveSweepParams(raw1, raw2);
        if (!resolved.ok()) return resolved.error();
        int    param1 = resolved.param1();
        double param2 = resolved.param2();
        if (resolved.swapped()) {
            log.info("MathToolSuite: run_param_sweep auto-oriented transposed params "
                    + "param1={}→{} param2={}→{}", raw1, param1, raw2, param2);
        }
        int topicIdx = MathPhysicsEngine.topicIndex(draft.domain(), draft.topicName());
        if (topicIdx < 0) return "Topic not computable via MathPhysicsEngine.";
        try {
            long t = System.currentTimeMillis();
            com.emergentterrain.research.MathPhysicsEngineDelegate delegate = this.physicsEngineDelegate;
            if (delegate == null) {
                throw new IllegalStateException("physics-topic compute requires the sidecar-backed "
                        + "MathPhysicsEngineDelegate; none wired (in-process engine removed in §28.6).");
            }
            MathPhysicsEngine.ResearchResult result = delegate.compute(draft.domain(), topicIdx, param1, param2);
            log.info("MathToolSuite: run_param_sweep domain={} topic={} param1={} param2={} ms={}",
                    draft.domain(), draft.topicName(), param1, param2, System.currentTimeMillis() - t);
            // param1/param2 are abstract sweep knobs the engine maps to a physical
            // configuration internally; the model tends to pass its hypothesis's
            // physical values here and then cite the swept config as if it matched
            // (P-a328-931 cited a d=2.64µm sweep as support for a d=2.42µm claim).
            // Spell out that the resolved configuration in `findings` — not the
            // hypothesis — is what was computed. Keep the "Param sweep (" prefix
            // verbatim: isSuccessResult() classifies on it.
            return ("Param sweep (param1=%d, param2=%.1f — abstract knobs, NOT physical inputs): "
                    + "%s [NOTE: the configuration reported above is what was actually swept and "
                    + "may differ from the hypothesis's parameters; cite these resolved values, "
                    + "not the hypothesis. Rationale: %s]")
                    .formatted(param1, param2, result.findings(), rationale);
        } catch (Exception e) {
            return "Param sweep failed: " + e.getMessage();
        }
    }

    private String runPredicateScan(JsonNode args) {
        String predicate  = args.path("predicate").asText(null);
        int    rangeStart = args.path("range_start").asInt(-1);
        int    rangeEnd   = args.path("range_end").asInt(-1);
        String rawMetric  = args.path("metric").asText("fraction_satisfying");
        String metric     = normalizeMetric(rawMetric);
        double baseline   = args.path("baseline").asDouble(0.0);
        String hypothesis = args.path("hypothesis").asText("");
        if (predicate == null || rangeStart < 1 || rangeEnd <= rangeStart
                || rangeEnd - rangeStart > DynamicComputeEngine.MAX_RANGE) {
            // 2026-06-05 — inform, don't just reject: the recurring failure mode is a
            // wide-range request rejected here, followed by prose that claims the
            // never-scanned range anyway (P-b72c-131 [100,1900), P-b72c-124 [1000,1400)
            // — both NULL'd as scope overreach). Tell the model the repair path so the
            // wide claim becomes several real scans instead of an unexecuted assertion.
            return "Invalid predicate scan: range must be ≥1 and span ≤ "
                    + DynamicComputeEngine.MAX_RANGE
                    + ". To cover a wider range, issue several calls over ADJACENT sub-ranges"
                    + " (e.g. [a, a+" + DynamicComputeEngine.MAX_RANGE + ") then"
                    + " [a+" + DynamicComputeEngine.MAX_RANGE + ", a+" + 2 * DynamicComputeEngine.MAX_RANGE
                    + ")) and report each separately; do NOT describe results over a range you did not scan.";
        }
        for (String token : predicate.replace(" AND ", "|").replace(" OR ", "|").split("\\|")) {
            // 2026-07-04 (P-b72c-77627762) — a leading NOT negates a single predicate
            // (evaluated in DynamicComputeEngine.parseSinglePredicate), enabling
            // union decompositions like "isPrime AND NOT isAbundant".
            String bare = token.trim();
            if (bare.startsWith("NOT ")) bare = bare.substring(4).trim();
            if (!VALID_PREDICATES.contains(bare))
                return "Invalid predicate token: '" + token.trim() + "'. Use: " + VALID_PREDICATES
                        + " — each may be prefixed with NOT (e.g. \"isPrime AND NOT isAbundant\").";
        }
        if (!VALID_METRICS.contains(metric))
            return "Invalid metric '" + rawMetric + "'. Use: " + VALID_METRICS;
        if (!rawMetric.equals(metric))
            log.info("MathToolSuite: run_predicate_scan metric alias '{}' → '{}'", rawMetric, metric);
        // 2026-05-21 review §1 rec #4 — baseline shape vs metric type. Catches
        // the P-a328-871 family where the narrator picks a probability-shaped
        // baseline (≈ 0.3) against a magnitude metric (mean_of:sigmaSum). LLM
        // sees the rejection synchronously and can correct mid-loop.
        java.util.Optional<String> baselineErr =
                com.emergentterrain.research.BaselineMetricRangeValidator
                        .validate(metric, baseline);
        if (baselineErr.isPresent()) {
            return "Invalid baseline: " + baselineErr.get();
        }
        try {
            long t = System.currentTimeMillis();
            String id = "prof-scan-" + System.currentTimeMillis();
            DynamicProblem problem = new DynamicProblem(
                    id, "professor predicate scan", rangeStart, rangeEnd,
                    predicate, metric, baseline, hypothesis);
            MathPhysicsEngine.ResearchResult result = DynamicComputeEngine.compute(problem);
            log.info("MathToolSuite: run_predicate_scan predicate=\"{}\" range=[{},{}) ms={}",
                    predicate, rangeStart, rangeEnd, System.currentTimeMillis() - t);
            return "Predicate scan: " + result.findings();
        } catch (Exception e) {
            return "Predicate scan failed: " + e.getMessage();
        }
    }

    private String evaluateSymbolic(JsonNode args) {
        String expression = args.path("expression").asText(null);
        int    rangeStart = args.path("range_start").asInt(-1);
        int    rangeEnd   = args.path("range_end").asInt(-1);
        String rationale  = args.path("rationale").asText("");
        if (expression == null || expression.isBlank())
            return "Missing expression.";
        // Phase 22 §22.5 — swap the range cap based on which back-end
        // will serve the call. Julia gets the 100_000 ceiling
        // (JIT-compiled native code can chew through 100× the
        // sandbox's interpreter budget without breaking a sweat).
        // The SandboxEngine path keeps the historic 1000 cap.
        int maxRange = useJuliaDescriptions() ? MAX_SYMBOLIC_RANGE_JULIA : MAX_SYMBOLIC_RANGE;
        if (rangeStart < 0 || rangeEnd <= rangeStart || rangeEnd - rangeStart > maxRange)
            return "Invalid range: must be positive and span ≤ " + maxRange;

        // Phase 22 §22.2 — Julia sidecar delegation via /sweep. When
        // wired AND available, request a 100-sample sweep across the
        // requested range and surface mean/std/min/max in the same
        // text format the LLM downstream expects. On any failure,
        // fall through to the in-process SandboxEngine path.
        //
        // Sample count is fixed at 100 here — matches the granularity
        // of SandboxEngine's existing sweep and keeps the response
        // compact.
        //
        // Phase 22 §22.5 — adaptive sample count. When the LLM asks
        // for a small range (≤ 1000) we stick with 100 samples — same
        // resolution as before. For larger ranges (up to the 100_000
        // ceiling), use one sample per integer (range-size samples)
        // capped at the sidecar's own 100_000 limit. This gives the
        // LLM truthful "evaluated every n in [a,b)" semantics when it
        // asks for a tight numeric range, instead of silently
        // sub-sampling at 100 points across the whole interval.
        if (juliaMathClient != null && juliaMathClient.isAvailable()) {
            try {
                int rangeSize = rangeEnd - rangeStart;
                // Cap at 100_000 (sidecar limit). For small ranges,
                // 100 samples is plenty — matches SandboxEngine grain.
                int samples = Math.min(100_000, Math.max(100, rangeSize));
                long t = System.currentTimeMillis();
                final String expr = expression;
                com.emergentterrain.llm.JuliaMathClient.SweepResult sr =
                        withColdStartRetry(() -> juliaMathClient.sweep(expr, "n",
                                rangeStart, rangeEnd - 1, samples));
                log.info("MathToolSuite: evaluate_symbolic (julia) expr=\"{}\" range=[{},{}) samples={} ms={}",
                        expression, rangeStart, rangeEnd, samples,
                        System.currentTimeMillis() - t);
                return ("Symbolic evaluation of f(n)=%s over [%d,%d): " +
                        "n=%d mean=%.6g std=%.6g min=%.6g max=%.6g " +
                        "[Rationale: %s] [via=julia]")
                        .formatted(expression, rangeStart, rangeEnd,
                                sr.n(), sr.mean(), sr.std(), sr.min(), sr.max(), rationale);
            } catch (com.emergentterrain.llm.JuliaMathException e) {
                log.info("MathToolSuite: evaluate_symbolic julia-fallback ({}): {} — using SandboxEngine",
                        e.errorCode(), e.getMessage());
                // Fall through to SandboxEngine path below.
            }
        }

        // Phase 21 §21.5 — rewrite the textbook power operator `^` to the
        // sandbox parser's `**`. The SandboxEngine token table has STARSTAR
        // but no caret (caret would throw "Unexpected char '^'"), so the
        // LLM's reflex of writing `n^2` cost 1–3 wasted rounds per paper
        // (P-a328-685 signature) before this rewrite.
        expression = rewriteCaretToStarStar(expression, "evaluate_symbolic");
        try {
            long t = System.currentTimeMillis();
            SandboxEngine.SandboxResult result =
                    SandboxEngine.evalExpr(expression, rangeStart, rangeEnd);
            log.info("MathToolSuite: evaluate_symbolic expr=\"{}\" range=[{},{}) ms={}",
                    expression, rangeStart, rangeEnd, System.currentTimeMillis() - t);
            return "Symbolic evaluation of f(n)=%s over [%d,%d): %s [Rationale: %s]"
                    .formatted(expression, rangeStart, rangeEnd, result.summary(), rationale);
        } catch (Exception e) {
            return "Symbolic evaluation failed: " + e.getMessage();
        }
    }

    /**
     * Phase 21 §21.5 — rewrite the textbook power operator {@code ^} to the
     * sandbox parser's {@code **}. Targets {@code evaluate_symbolic} and
     * {@code run_gpu_eval}, both of which take a scalar arithmetic
     * expression in {@code n} only (no string literals are valid syntax),
     * so the rewrite can be unconditional. Emits an INFO log per hit so
     * adoption is measurable from the operational-logs section of
     * REVIEW_REPORT.
     *
     * <p>Run BEFORE the parser / static-checker. {@code Symja}'s
     * {@code run_symbolic} path is unaffected — {@code ^} is its native
     * power operator and lexes to {@code Power[…]} directly.
     */
    /**
     * Phase 22 §22.4 — JSON-encode a Java string as a JSON string
     * literal (including surrounding double quotes). Used by
     * {@link #toolsJson} so the long Mathematica / Julia tool
     * descriptions can be swapped in without hand-escaping every
     * backslash and quote. Conservative subset — escapes only the
     * characters that JSON requires.
     */
    static String jsonEncode(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder(s.length() + 16);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    static String rewriteCaretToStarStar(String expression, String toolName) {
        if (expression == null || expression.indexOf('^') < 0) return expression;
        String rewritten = expression.replace("^", "**");
        log.info("MathToolSuite: {} rewrote `^` → `**` (count={}) expr=\"{}\"",
                toolName, countChar(expression, '^'),
                expression.length() > 80 ? expression.substring(0, 80) + "…" : expression);
        return rewritten;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /**
     * Phase 10 — normalize single `=` to `==` (Set head → Equal head).
     *
     * <p>P-a328-364 emitted {@code Solve[Q = 1/2 / Sqrt[L/C], R]} which Symja
     * parses as a Set assignment, then the safety whitelist correctly blocks
     * the {@code Set} head. The LLM intended Equal — it just used the
     * JavaScript / textbook spelling instead of Mathematica's. The fix would
     * have been a one-character edit but the LLM saw only a generic
     * "non-whitelisted head" error and retried with wrong parameters.
     *
     * <p>The replacement is global and unconditional: any single {@code =}
     * not part of {@code ==}, {@code <=}, {@code >=}, {@code !=}, or
     * {@code :=}. Symja expressions never legitimately use {@code =} as an
     * operator (it's only the Set head, which is blocked anyway), so the
     * transformation is loss-free for valid inputs and corrective for the
     * common LLM mistake.
     */
    static String normalizeEqualHead(String expr) {
        if (expr == null) return null;
        // Negative lookbehind excludes <, >, !, =, : so we don't touch
        // <=, >=, !=, ==, := . Negative lookahead excludes = so we don't
        // double-substitute ==.
        return expr.replaceAll("(?<![<>!=:])=(?!=)", "==");
    }

    /**
     * Map a natural-language {@code run_predicate_scan} metric name to its
     * canonical {@link #VALID_METRICS} identifier. The LLM repeatedly emits the
     * spoken form and burns professor rounds discovering the exact token from
     * the rejection — P-a328-936 wasted 3 calls on {@code "density"} and
     * P-a328-934 wasted 3 on {@code "mean collatz steps"}, the same friction
     * class as the {@code range_start=0} clamp.
     *
     * <p>{@code density} ≡ {@code fraction_satisfying}: for a Boolean membership
     * predicate the natural density over {@code [start,end)} is exactly
     * count/total, which is what {@code computeFraction} returns — so the alias
     * is exact, not approximate. Already-canonical values (including any
     * {@code mean_of:}/{@code max_of:} form) pass through untouched; an unknown
     * name is returned verbatim so the caller's valid-set rejection still fires
     * with the full list.
     */
    static String normalizeMetric(String raw) {
        if (raw == null || raw.isBlank()) return "fraction_satisfying";
        String m = raw.trim();
        if (VALID_METRICS.contains(m)) return m;                 // already canonical
        // Collapse spaces / underscores / colons / parens / hyphens to single
        // spaces and lowercase, so "mean_of:collatzSteps", "mean of collatz
        // steps" and "Mean Collatz Steps" all reduce to one key.
        String key = m.toLowerCase().replaceAll("[\\s_:()\\-]+", " ").trim();
        // 2026-06-08 review rec 3 — any "fraction …" / "proportion …" /
        // "… satisfying …" phrasing collapses to the sole density metric.
        // The professor stage repeatedly emitted free-form variants the
        // exact-token switch missed ("fraction satisfying predicate",
        // "fraction of integers that are prime"), burning 1–3 tool rounds per
        // density paper (P-b72c-308/-309/-313). fraction_satisfying is the
        // ONLY fraction the tool computes, so the collapse is unambiguous.
        if (key.startsWith("fraction") || key.startsWith("proportion")
                || key.contains("satisfying")) {
            return "fraction_satisfying";
        }
        return switch (key) {
            case "density", "densities", "fraction", "proportion", "frequency",
                 "relative frequency", "fraction satisfying", "satisfying fraction" ->
                    "fraction_satisfying";
            case "mean collatz steps", "mean collatz depth", "average collatz steps",
                 "average collatz depth", "mean of collatz steps", "mean of collatzsteps",
                 "mean collatzsteps", "collatz steps", "collatz depth" ->
                    "mean_of:collatzSteps";
            case "max collatz steps", "max collatz depth", "maximum collatz steps",
                 "maximum collatz depth", "max of collatz steps", "max of collatzsteps",
                 "max collatzsteps" ->
                    "max_of:collatzSteps";
            case "mean sigma", "mean sigma sum", "mean of sigma sum", "mean of sigmasum",
                 "mean sigmasum", "sigma sum", "sigmasum" ->
                    "mean_of:sigmaSum";
            default -> m;                                        // unknown — let the valid-set reject fire
        };
    }

    private String runSymbolic(JsonNode args) {
        String expression = args.path("expression").asText(null);
        String rationale  = args.path("rationale").asText("(no rationale)");
        if (expression == null || expression.isBlank()) return "Missing expression.";

        // Phase 22 §22.2 — Julia sidecar delegation. When the
        // JuliaMathClient bean is wired AND reports itself available
        // (config opt-in + /healthz responded at startup), try the
        // sidecar first. On any failure (transport, parse, eval),
        // log at INFO and fall through to the existing Symja path so
        // a sidecar outage never blocks a paper. Transport failures get
        // one delayed retry first — see withColdStartRetry.
        //
        // The expression is passed through as-is. Until the LLM prompt
        // is re-trained to emit Julia syntax (Phase 22.4), most
        // Mathematica-shaped inputs (Solve[...], Floor[...], arcsin)
        // will fail on the sidecar and fall back to Symja, which is
        // the safe behaviour. Pure-arithmetic and Julia-compatible
        // inputs succeed via the sidecar.
        if (juliaMathClient != null && juliaMathClient.isAvailable()) {
            try {
                long t = System.currentTimeMillis();
                final String expr = expression;
                com.emergentterrain.llm.JuliaMathClient.EvalResult er =
                        withColdStartRetry(() -> juliaMathClient.eval(expr));
                log.info("MathToolSuite: run_symbolic (julia) expr=\"{}\" ms={} type={}",
                        expression, System.currentTimeMillis() - t, er.type());
                // Phase 21 §21.3 follow-on — Julia-path echo detection. Same
                // failure shape Symja showed on P-a328-693 (Solve[…] echoed
                // verbatim) is firing on the Julia path:
                //   * P-a328-839: `zeta(s) = sum(n^(-s) for n in 1:1000)` —
                //     Julia parsed the `=` as a function definition; result
                //     was the bare identifier "zeta", no evaluation.
                //   * P-a328-823: `(let theta = …; series = …; series)` —
                //     Symbolics didn't simplify; result was the type name
                //     "Series".
                // The LLM downstream treats `output=zeta` / `output=Series`
                // as a successful numeric result and fabricates a value to
                // fill the gap. Surface a tool-failure signal instead.
                if (isJuliaEcho(expression, er)) {
                    log.warn("MathToolSuite: run_symbolic (julia) echo detected expr=\"{}\" output=\"{}\" type={}",
                            expression, er.result(), er.type());
                    return "Symbolic solver did not evaluate the expression — Julia returned "
                            + "the unevaluated symbol \"" + er.result() + "\""
                            + (er.type() != null && !er.type().isBlank() ? " (type=" + er.type() + ")" : "")
                            + ". The expression may have been parsed as a function definition "
                            + "(top-level `=`) instead of an evaluation, or the symbolic result "
                            + "has no numeric form. Try: rewriting to call existing functions "
                            + "(e.g. `sum(1/n^2.5 for n in 1:100)` instead of `zeta(s) = …`), "
                            + "wrap in `float(…)` to force numeric coercion, or use numeric "
                            + "methods if no closed form is available. (Echoed expression: "
                            + expression + ")";
                }
                return "Symbolic result for %s: output=%s [Rationale: %s] [via=julia]"
                        .formatted(expression, er.result(), rationale);
            } catch (com.emergentterrain.llm.JuliaMathException e) {
                log.info("MathToolSuite: run_symbolic julia-fallback ({}): {} — using Symja",
                        e.errorCode(), e.getMessage());
                // Fall through to the Symja path below.
            }
        }

        // Phase 10 — rewrite `=` → `==` before the whitelist check so the
        // common LLM mistake of writing the Set head where Equal is meant
        // doesn't get blocked. See normalizeEqualHead() for the rationale.
        String normalized = normalizeEqualHead(expression);
        if (!normalized.equals(expression)) {
            log.info("MathToolSuite: run_symbolic normalized `=` → `==` expr=\"{}\"",
                    expression.length() > 80 ? expression.substring(0, 80) + "…" : expression);
            expression = normalized;
        }

        // Deterministic head-whitelist check before invoking Symja.
        SymbolicStaticChecker.Result staticResult = SymbolicStaticChecker.check(expression);
        if (staticResult instanceof SymbolicStaticChecker.Result.Rejected r) {
            log.warn("MathToolSuite: run_symbolic static-rejected: {} expr=\"{}\"",
                    r.reason(),
                    expression.length() > 80 ? expression.substring(0, 80) + "…" : expression);
            // Phase 29 §4a — a parse/syntax rejection (unbalanced parens, missing
            // operator) is malformed input, NOT a disallowed head. Echo the exact
            // error and a fix hint so the next attempt corrects the expression
            // rather than bouncing through budget_fallback retries against the
            // misleading "only calculus/algebra/trig heads are allowed" message.
            String reasonLower = r.reason() == null ? "" : r.reason().toLowerCase();
            if (reasonLower.startsWith("parse failed")
                    || reasonLower.contains("syntax error")
                    || reasonLower.contains("expected")) {
                return "REJECTED — the expression could not be parsed: " + r.reason()
                        + ". This is a SYNTAX error, not a disallowed function. Most common "
                        + "cause: unbalanced parentheses — count '(' vs ')' — or a missing "
                        + "operator between operands. Re-send run_symbolic with the "
                        + "parentheses balanced and an explicit operator between every "
                        + "operand (e.g. '2*(x+1)', not '2(x+1)' or '(x+1').";
            }
            // 2026-05-21 follow-on — when the rejected head is a known
            // sandbox primitive (isPrime, sigmaSum, collatzSteps, …), the
            // LLM got the right primitive but the wrong tool. Surface a
            // routing hint so the next attempt goes to run_sandboxed_code
            // instead of bouncing through 4–7 budget_fallback retries.
            String sandboxHint = sandboxPrimitiveHintFor(r.reason());
            if (sandboxHint != null) {
                return "REJECTED by safety review: " + r.reason()
                        + " — " + sandboxHint
                        + " run_sandboxed_code with a short JS loop "
                        + "(e.g. `var c=0; for(var n=lo;n<hi;n++){ if(isPrime(n)) c++; } return c;`). "
                        + "For the symbolic-math equivalent in run_symbolic, use the Mathematica-"
                        + "style capitalized name instead (PrimeQ, DivisorSigma, GCD).";
            }
            return "REJECTED by safety review: " + r.reason()
                    + " — only calculus/algebra/trig/special-function heads are allowed; "
                    + "I/O, process, network, state mutation, and JVM-escape heads are blocked.";
        }

        try {
            long t = System.currentTimeMillis();
            SymbolicEngine.SymbolicResult result = SymbolicEngine.evaluate(expression);
            log.info("MathToolSuite: run_symbolic expr=\"{}\" ms={} numeric={}",
                    expression, System.currentTimeMillis() - t, result.numericApprox());

            // Phase 21 §21.3 — solver-echo detection. When Symja can't
            // evaluate a Solve / Reduce / Integrate / DSolve and just echoes
            // the input back with no numericApprox, the LLM downstream tends
            // to fabricate a number to fill the gap (P-a328-693 → "61.04°"
            // and "1.49"). Retry once with N[...] for a numeric coercion;
            // if that still echoes, return an actionable error so the LLM
            // gets a tool-failure signal instead of an opaque pass-through.
            if (isSolverEcho(expression, result)) {
                log.warn("MathToolSuite: run_symbolic echo detected expr=\"{}\" output=\"{}\" — retrying with N[…]",
                        expression, result.output());
                String retryExpr = "N[" + expression + "]";
                try {
                    SymbolicEngine.SymbolicResult retry = SymbolicEngine.evaluate(retryExpr);
                    if (retry.numericApprox() != null || !isSolverEcho(expression, retry)) {
                        String numericNoteR = retry.numericApprox() == null
                                ? ""
                                : "  numeric=%.6g".formatted(retry.numericApprox());
                        return "Symbolic result for %s (retried as N[…]): output=%s%s [Rationale: %s]"
                                .formatted(expression, retry.output(), numericNoteR, rationale);
                    }
                } catch (Exception retryEx) {
                    log.warn("MathToolSuite: run_symbolic N[…] retry failed: {}", retryEx.getMessage());
                }
                return "Symbolic solver did not evaluate the expression — Symja echoed the input "
                        + "back without a numeric value. The equation may have no closed-form "
                        + "solution or may require a numeric method. Try: NSolve[…] for numeric "
                        + "root-finding, N[ArcSin[ratio]] for direct evaluation, or rewrite the "
                        + "problem to expose a closed form. (Echoed expression: " + expression + ")";
            }

            String numericNote = result.numericApprox() == null
                    ? ""
                    : "  numeric=%.6g".formatted(result.numericApprox());
            return "Symbolic result for %s: output=%s%s [Rationale: %s]"
                    .formatted(expression, result.output(), numericNote, rationale);
        } catch (Exception e) {
            return "Symbolic evaluation failed: " + e.getMessage();
        }
    }

    /**
     * Phase 21 §21.3 — detect the case where Symja returns the input
     * expression unchanged (the solver could not evaluate it). Returns true
     * when (a) {@code numericApprox} is null AND (b) the input head is one of
     * the known unevaluating-when-stuck heads (Solve, Reduce, Roots, FindRoot,
     * DSolve, NSolve, Integrate, Sum, Product) AND (c) Symja's full-form
     * output is structurally an application of the same head.
     *
     * <p>Detection is conservative — three independent signals must all
     * line up. The known-head list comes from Symja's documented behaviour:
     * these are the heads that, when given a sub-problem they can't crack,
     * return {@code Head(args)} verbatim instead of throwing or returning a
     * residual. Calls like {@code Sin[1.0]} do NOT echo (Sin produces a
     * numeric); only "solver-style" heads do.
     */
    static boolean isSolverEcho(String input, SymbolicEngine.SymbolicResult result) {
        if (input == null || result == null || result.output() == null) return false;
        if (result.numericApprox() != null) return false;
        String head = leadingHead(input);
        if (head == null) return false;
        if (!ECHO_PRONE_HEADS.contains(head)) return false;
        // Symja's full-form for an unevaluated Head[args] is "Head(args)" or
        // sometimes "Head[args]" (depends on Symja version). Accept either.
        String trimmed = result.output().stripLeading();
        return trimmed.startsWith(head + "(") || trimmed.startsWith(head + "[");
    }

    /** Heads that return their input unevaluated (echo) when Symja can't crack them. */
    private static final Set<String> ECHO_PRONE_HEADS = Set.of(
            "Solve", "Reduce", "Roots", "FindRoot",
            "DSolve", "NSolve", "Integrate", "Sum", "Product");

    /**
     * 2026-05-21 follow-on — when SymbolicStaticChecker rejects a head that
     * matches a known {@code SandboxEngine} primitive (case-insensitive),
     * return a hint string telling the LLM to route to {@code run_sandboxed_code}
     * instead. Without this hint, the LLM typically bounces through 4–7
     * budget_fallback retries trying to find a model that will accept the
     * symbolic call (paper P-a328-843 burned 5 rounds this way).
     *
     * <p>Match list is the {@code SandboxEngine} built-ins that have no
     * symbolic-equivalent name with the same casing: {@code isPrime},
     * {@code isHappy}, {@code sigmaSum}, {@code collatzSteps},
     * {@code collatzTerminates}, {@code isAbundant}, {@code isPerfect}.
     * Excluded: {@code gcd}, {@code log2} — those have Mathematica
     * equivalents (GCD, Log2) already in the symbolic whitelist, so a hint
     * pointing at the sandbox would be wrong.
     *
     * <p>Returns {@code null} when no sandbox primitive is detected in the
     * rejection reason; caller then falls back to the generic safety message.
     */
    static String sandboxPrimitiveHintFor(String rejectReason) {
        if (rejectReason == null) return null;
        String low = rejectReason.toLowerCase();
        Set<String> primitives = Set.of(
                "isprime", "ishappy", "sigmasum", "collatzsteps",
                "collatzterminates", "isabundant", "isperfect");
        java.util.List<String> matched = new java.util.ArrayList<>();
        for (String prim : primitives) {
            if (low.contains(prim) && !matched.contains(prim)) matched.add(prim);
        }
        if (matched.isEmpty()) return null;
        return String.join(", ", matched)
                + " look like SandboxEngine number-theoretic primitives, "
                + "not symbolic-math heads. Retry the same predicate via";
    }

    /**
     * Phase 21 §21.3 follow-on — detect Julia-path echo. Same failure shape
     * Symja showed (input echoed back without a number), but Julia surfaces
     * it differently: a function-definition mistake (top-level {@code =})
     * yields the bare identifier name (e.g. {@code "zeta"}); a Symbolics
     * expression that didn't simplify yields a type name (e.g.
     * {@code "Series"}). Both look successful to the narrator and downstream
     * the LLM fabricates a number to fill the gap.
     *
     * <p>Returns true when the Julia result is a bare identifier — no digits,
     * operators, parens, whitespace, or punctuation. Excludes the small set
     * of legitimate atom returns ({@code true}, {@code false}, {@code Inf},
     * {@code -Inf}, {@code NaN}) so they pass through unchanged.
     *
     * <p>{@code nothing} is intentionally treated as an echo — a Julia
     * computation that returns {@code nothing} typically means the LLM
     * routed the expression to a function that has no return value (e.g.,
     * an assignment or a side-effect-only call), not a real evaluation.
     */
    /**
     * 2026-07-05 (run-23 rec 1, P-b72c-77627786) — append the exact instrument definition of every
     * scalar named in a verify_* call to its result, so instrument hypotheses (P-786 conjectured
     * the excess baseline uses ln(window_start); it uses ln(center)) resolve inside the trace
     * instead of in post-hoc review. Documentation-only text from
     * {@link com.emergentterrain.research.conjecture.MathInvariants#definitionOf}; appended after
     * the verdict block so no downstream text-parser fragment changes.
     */
    /**
     * 2026-07-05 (run-24 rec 2, P-b72c-77627788) — prescriptive cap-fix hint. The professor made
     * the identical WORK-cap mistake in two consecutive rounds (10000 × 20 = 200k > 100k, twice)
     * because the old message diagnosed the overflow without naming the fix; professors act on
     * COPYABLE numbers (the LOOSE hint only worked once it said "re-verify at k=0.4280"). Those
     * two lost rounds were exactly the missing binding-refute call.
     */
    static String capFixHint(int windowSize, long maxWork) {
        long maxWindows = windowSize > 0 ? maxWork / windowSize : 0;
        return maxWindows >= 1
                ? String.format("FIX: keep window_size=%d and use num_windows ≤ %d.", windowSize, maxWindows)
                : String.format("FIX: window_size alone exceeds the cap — use window_size ≤ %d.", maxWork);
    }

    /**
     * Phase 33 lane R (R2, 2026-07-06) — derive_value: deterministic in-process arithmetic so
     * DERIVED quantities (differences, ratios, per-decade rates) carry tool provenance. Two 0.8+
     * papers (P-b72c-77627792 '0.025/decade', P-b72c-77627807 '0.0005') died at the manifest
     * gate on narrator-computed rates; the audit dimension (R1) informs, this tool closes.
     * Always advertised — pure Java, no sidecar, no flags.
     */
    private static final String DERIVE_VALUE_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"derive_value",
                "description":"Compute a DERIVED quantity (difference, ratio, per-decade rate, average) from numbers already in your trace, so the value carries tool provenance. Any number your synthesis will state that is NOT a direct tool output must come from this call — a hand-computed rate in prose is gated at publish. Pure arithmetic: + - * / ^ parentheses and ln() log10() sqrt() exp() abs(). Example expression: (1.02989 - 0.92890) / 4",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "expression":{"type":"string","description":"The arithmetic expression, using literal numbers from your trace."},
                    "label":{"type":"string","description":"Optional: what this quantity IS (e.g. mean per-decade decrement of k), echoed in the result."}
                  },
                  "required":["expression"]
                }
              }
            }""";

    private String deriveValue(JsonNode args) {
        String expr = args.path("expression").asText("");
        String label = args.path("label").asText("");
        try {
            double v = evalArithmetic(expr);
            if (!Double.isFinite(v)) {
                return "derive_value: " + expr + " is not finite (division by zero or domain "
                        + "error) — check the inputs.";
            }
            return "derive_value: " + expr + " = " + String.format("%.10g", v)
                    + (label.isBlank() ? "" : " (" + label + ")")
                    + ". This value carries provenance — cite it as this call's [call_N].";
        } catch (IllegalArgumentException e) {
            return "derive_value: could not parse '" + expr + "': " + e.getMessage()
                    + ". Allowed: numbers, + - * / ^, parentheses, ln() log10() sqrt() exp() abs().";
        }
    }

    /**
     * R2 — tiny recursive-descent evaluator: numbers (incl. scientific notation), + - * / ^
     * (right-assoc), unary minus, parentheses, ln/log10/sqrt/exp/abs. Deliberately NOT a general
     * expression language — no variables, no comma calls — so it cannot become a sandbox risk.
     * Static + pure for tests.
     */
    static double evalArithmetic(String expr) {
        return new ArithParser(expr == null ? "" : expr).parse();
    }

    private static final class ArithParser {
        private final String s;
        private int i;

        ArithParser(String s) { this.s = s; }

        double parse() {
            double v = sum();
            skipWs();
            if (i < s.length()) {
                throw new IllegalArgumentException("unexpected '" + s.charAt(i) + "' at " + i);
            }
            return v;
        }

        private double sum() {
            double v = term();
            while (true) {
                skipWs();
                if (eat('+')) { v += term(); }
                else if (eat('-')) { v -= term(); }
                else { return v; }
            }
        }

        private double term() {
            double v = power();
            while (true) {
                skipWs();
                if (eat('*')) { v *= power(); }
                else if (eat('/')) { v /= power(); }
                else { return v; }
            }
        }

        private double power() {
            double base = unary();
            skipWs();
            if (eat('^')) {
                return Math.pow(base, power());   // right-associative
            }
            return base;
        }

        private double unary() {
            skipWs();
            if (eat('-')) { return -unary(); }
            if (eat('+')) { return unary(); }
            return atom();
        }

        private double atom() {
            skipWs();
            if (eat('(')) {
                double v = sum();
                expect(')');
                return v;
            }
            if (i < s.length() && Character.isLetter(s.charAt(i))) {
                int start = i;
                while (i < s.length() && Character.isLetter(s.charAt(i))
                        || (i < s.length() && Character.isDigit(s.charAt(i)))) { i++; }
                String fn = s.substring(start, i).toLowerCase(java.util.Locale.ROOT);
                expect('(');
                double a = sum();
                expect(')');
                return switch (fn) {
                    case "ln" -> Math.log(a);
                    case "log10" -> Math.log10(a);
                    case "sqrt" -> Math.sqrt(a);
                    case "exp" -> Math.exp(a);
                    case "abs" -> Math.abs(a);
                    default -> throw new IllegalArgumentException("unknown function '" + fn + "'");
                };
            }
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) { i++; }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int save = i;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) { i++; }
                if (i < s.length() && Character.isDigit(s.charAt(i))) {
                    while (i < s.length() && Character.isDigit(s.charAt(i))) { i++; }
                } else {
                    i = save;   // 'e' wasn't an exponent
                }
            }
            if (start == i) {
                throw new IllegalArgumentException(i >= s.length()
                        ? "unexpected end of expression" : "unexpected '" + s.charAt(i) + "' at " + i);
            }
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number '" + s.substring(start, i) + "'");
            }
        }

        private boolean eat(char c) {
            skipWs();
            if (i < s.length() && s.charAt(i) == c) { i++; return true; }
            return false;
        }

        private void expect(char c) {
            if (!eat(c)) { throw new IllegalArgumentException("expected '" + c + "' at " + i); }
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) { i++; }
        }
    }

    /** Phase 33 L2 — read_reference dispatch: cap, then delegate to the reader. */
    private String readReference(JsonNode args, int[] calls) {
        if (referenceReader == null && phase34SqliteLiterature == null) {
            return "read_reference not configured.";
        }
        if (++calls[0] > 2) {
            return "read_reference budget exhausted for this paper (2 calls) — work from the "
                    + "passages you already retrieved.";
        }
        String key = args.path("key").asText("");
        String query = args.path("query").asText("");
        if (phase34SqliteLiterature != null) {
            return phase34SqliteLiterature.read(key, query);
        }
        return referenceReader.read(key, query);
    }

    private String lookupOeis(JsonNode args) {
        if (domainRefClient == null) {
            return "lookup_oeis not configured.";
        }
        java.util.List<String> terms = new java.util.ArrayList<>();
        for (JsonNode t : args.path("terms")) {
            String s = t.asText("").strip();
            if (!s.isEmpty()) {
                terms.add(s);
            }
        }
        if (terms.isEmpty()) {
            return "lookup_oeis: pass the sequence's leading terms in 'terms' (≥4 recommended).";
        }
        return domainRefClient.lookupOeis(terms, 5);
    }

    private String lookupDlmf(JsonNode args) {
        if (domainRefClient == null) {
            return "lookup_dlmf not configured.";
        }
        String query = args.path("query").asText("");
        if (query.isBlank()) {
            return "lookup_dlmf: pass the special-function claim/quantity in 'query'.";
        }
        return domainRefClient.lookupDlmf(query, 3);
    }

    static String appendScalarDefinitions(String result, JsonNode args) {
        if (result == null || args == null) {
            return result;
        }
        StringBuilder defs = new StringBuilder();
        for (String slot : new String[]{"lhs", "rhs", "rhs2"}) {
            String name = args.path(slot).asText("");
            if (name.isBlank()) {
                continue;
            }
            String def = com.emergentterrain.research.conjecture.MathInvariants.definitionOf(name);
            if (def != null && !defs.toString().contains(name + " = ")) {
                defs.append(defs.isEmpty() ? "" : "; ").append(name).append(" = ").append(def);
            }
        }
        return defs.isEmpty() ? result
                : result + "\ndefinitions (exact instrument formulas): " + defs;
    }

    /**
     * 2026-07-05 (P-b72c-77627777) — cold-start transport retry. The run started 3 minutes after
     * the sidecar; the first eval hit the client timeout while the sidecar was still JIT-warming,
     * fell back to Symja (which cannot parse Julia syntax), and every professor attempt failed →
     * the draft was suppressed with 0 successes. Same-window rollup: 1 transport_fail vs 1055 ok —
     * the sidecar recovered seconds later. One delayed retry on a TRANSPORT-coded failure bridges
     * the window; every other error code (parse, eval, safety) falls through unchanged.
     */
    static final long COLD_START_RETRY_MS = 10_000;

    private <T> T withColdStartRetry(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (com.emergentterrain.llm.JuliaMathException first) {
            if (!"transport".equals(first.errorCode())) {
                throw first;
            }
            log.info("MathToolSuite: julia transport failure — retrying once in {}ms "
                    + "(cold-start JIT window): {}", COLD_START_RETRY_MS, first.getMessage());
            try {
                Thread.sleep(COLD_START_RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw first;
            }
            return call.get();   // a second transport failure propagates to the normal fallback
        }
    }

    static boolean isJuliaEcho(String input,
                                com.emergentterrain.llm.JuliaMathClient.EvalResult er) {
        if (input == null || er == null || er.result() == null) return false;
        String result = er.result().strip();
        if (result.isEmpty()) return false;
        // Bare identifier only — no operators, no digits, no parens.
        if (!result.matches("[A-Za-z_][A-Za-z0-9_]*")) return false;
        // Legitimate single-token atoms.
        switch (result) {
            case "true":
            case "false":
            case "Inf":
            case "NaN":
                return false;
            default:
                return true;
        }
    }

    /**
     * Pull the leading head from a Mathematica-style expression. Returns
     * "Solve" from "Solve[…]" or "Solve(…)". Returns null when the input
     * doesn't start with an identifier followed by an opening bracket.
     */
    static String leadingHead(String expr) {
        if (expr == null) return null;
        String s = expr.stripLeading();
        int i = 0;
        while (i < s.length()
                && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        if (i == 0 || i == s.length()) return null;
        char next = s.charAt(i);
        if (next != '[' && next != '(') return null;
        return s.substring(0, i);
    }

    /**
     * Phase 30 fork 2b Phase 4 — {@code discover_mertens_value}: compute M(x) at
     * a large x via a runtime-discovered, moat-validated method. Builds a
     * {@link com.emergentterrain.research.discovery.DiscoveryTask} with the
     * standard checkpoints + OEIS anchor, runs the generate→validate→repair loop,
     * and returns the validated value tagged as a computed (not established)
     * result, or a failure note. Gated: returns a disabled line unless discovery
     * is wired AND {@code runtimeExecEnabled}.
     */
    String discoverMertensValue(JsonNode args) {
        if (!discoveryAvailable()) {
            return "discover_mertens_value: disabled (runtime discovery is off).";
        }
        long x = args.path("x").asLong(0);
        if (x <= com.emergentterrain.research.conjecture.ConjectureVerifier.MAX_N) {
            return "discover_mertens_value: 'x' must be > " +
                    com.emergentterrain.research.conjecture.ConjectureVerifier.MAX_N +
                    " (use a direct method at or below the sieve range).";
        }
        var task = new com.emergentterrain.research.discovery.DiscoveryTask(
                "M(x) = sum of the Möbius function mu(n) for n = 1..x (the cumulative Mertens function)",
                x, DISCOVER_CHECKPOINTS, DISCOVER_ANCHORS, 20, 28, discoveryService.maxAttempts());
        var outcome = discoveryService.discover(task);
        if (outcome.accepted()) {
            var p = outcome.provenance();
            return String.format(
                    "discover_mertens_value: M(%d) = %d. COMPUTED by a moat-validated method "
                    + "(method sha256=%s, reproduced %d checkpoints in [1, %d] + M(10^7)=1037). "
                    + "This is a computed value from a validated method, NOT an established result — "
                    + "report it as \"computed to x=%d\", with no claim about the Mertens conjecture.",
                    x, outcome.targetValue(), p.codeSha256().substring(0, 12), p.checkpointCount(),
                    com.emergentterrain.research.conjecture.ConjectureVerifier.MAX_N, x);
        }
        return "discover_mertens_value: no method validated within "
                + outcome.attempts() + " attempt(s) — " + outcome.failureReason()
                + ". Do NOT report a value for M(" + x + ").";
    }

    /**
     * Phase 30 — {@code discover_divisor_value}: compute the Dirichlet divisor sum D(x) at a
     * large x via a runtime-discovered, moat-validated method. The divisor-domain sibling of
     * {@link #discoverMertensValue(JsonNode)}: same generate→validate→repair loop (the
     * discovery service is generic over {@code task.quantity()}), with the divisor spec +
     * checkpoints and {@code quantity="divisor"} so the sidecar moat validates against the
     * real D(x) oracle. No beyond-range external anchor (overlap checkpoints carry the moat).
     * Gated: a disabled line unless discovery is wired, {@code runtimeExecEnabled}, AND the
     * {@code divisorDiscoverToolEnabled} gate is on.
     */
    String discoverDivisorValue(JsonNode args) {
        if (!divisorDiscoveryAvailable()) {
            return "discover_divisor_value: disabled (runtime discovery is off).";
        }
        long x = args.path("x").asLong(0);
        if (x <= com.emergentterrain.research.conjecture.ConjectureVerifier.MAX_N) {
            return "discover_divisor_value: 'x' must be > " +
                    com.emergentterrain.research.conjecture.ConjectureVerifier.MAX_N +
                    " (use a direct method at or below the sieve range).";
        }
        var task = new com.emergentterrain.research.discovery.DiscoveryTask(
                DISCOVER_DIVISOR_SPEC, x, DISCOVER_DIVISOR_CHECKPOINTS, DISCOVER_DIVISOR_ANCHORS,
                20, 28, discoveryService.maxAttempts(), "divisor");
        var outcome = discoveryService.discover(task);
        if (outcome.accepted()) {
            var p = outcome.provenance();
            return String.format(
                    "discover_divisor_value: D(%d) = %d. COMPUTED by a moat-validated method "
                    + "(method sha256=%s, reproduced %d checkpoints in [1, %d] + D(10^7)=162725364). "
                    + "This is a computed value from a validated method, NOT an established result — "
                    + "report it as \"computed to x=%d\", with no claim about the divisor error-term bound.",
                    x, outcome.targetValue(), p.codeSha256().substring(0, 12), p.checkpointCount(),
                    com.emergentterrain.research.conjecture.ConjectureVerifier.MAX_N, x);
        }
        return "discover_divisor_value: no method validated within "
                + outcome.attempts() + " attempt(s) — " + outcome.failureReason()
                + ". Do NOT report a value for D(" + x + ").";
    }

    /**
     * Phase 30 (Path A) — {@code discover_prime_value}: compute the prime-counting function
     * π(x) at a large x via a runtime-discovered, moat-validated method. The prime-domain
     * sibling of {@link #discoverDivisorValue(JsonNode)}: same generate→validate→repair loop
     * (the discovery service is generic over {@code task.quantity()}), with the prime spec +
     * checkpoints, {@code quantity="prime"} (so the sidecar moat validates against the real
     * π(x) oracle), and a beyond-range anchor π(10^7). Gated: a disabled line unless discovery
     * is wired, {@code runtimeExecEnabled}, AND the {@code primeDiscoverToolEnabled} gate is on.
     */
    String discoverPrimeValue(JsonNode args) {
        if (!primeDiscoveryAvailable()) {
            return "discover_prime_value: disabled (runtime discovery is off).";
        }
        long x = args.path("x").asLong(0);
        if (x <= com.emergentterrain.research.conjecture.ConjectureVerifier.MAX_N) {
            return "discover_prime_value: 'x' must be > " +
                    com.emergentterrain.research.conjecture.ConjectureVerifier.MAX_N +
                    " (use a direct method at or below the sieve range).";
        }
        var task = new com.emergentterrain.research.discovery.DiscoveryTask(
                DISCOVER_PRIME_SPEC, x, DISCOVER_PRIME_CHECKPOINTS, DISCOVER_PRIME_ANCHORS,
                20, 28, discoveryService.maxAttempts(), "prime");
        var outcome = discoveryService.discover(task);
        if (outcome.accepted()) {
            var p = outcome.provenance();
            return String.format(
                    "discover_prime_value: pi(%d) = %d. COMPUTED by a moat-validated method "
                    + "(method sha256=%s, reproduced %d checkpoints in [1, %d] + pi(10^7)=664579). "
                    + "This is a computed value from a validated method, NOT an established result — "
                    + "report it as \"computed to x=%d\", with no claim about the prime-counting bound.",
                    x, outcome.targetValue(), p.codeSha256().substring(0, 12), p.checkpointCount(),
                    com.emergentterrain.research.conjecture.ConjectureVerifier.MAX_N, x);
        }
        return "discover_prime_value: no method validated within "
                + outcome.attempts() + " attempt(s) — " + outcome.failureReason()
                + ". Do NOT report a value for pi(" + x + ").";
    }

    /**
     * Phase 30 fork 2b §8b — {@code recall_frontier_knowledge}: read the persisted,
     * independently re-verified frontier corpus for a bound (records + best method)
     * with trust carried verbatim. READ-ONLY — no LLM tokens, no discovery re-run; it
     * lets a paper reuse already-verified frontier values instead of regenerating them.
     * Gated: returns a disabled line unless knowledge is wired AND {@code frontierRecallEnabled}.
     */
    String recallFrontierKnowledge(JsonNode args, PaperDraft draft) {
        if (!recallAvailable()) {
            return "recall_frontier_knowledge: disabled (frontier recall is off).";
        }
        String bound = args.path("bound").asText("");
        if (bound == null || bound.isBlank()) {
            bound = defaultRecallBound(draft);
        }
        return "recall_frontier_knowledge [" + bound + "]:\n" + frontierKnowledge.recallText(bound);
    }

    /**
     * 2026-06-05 — scalar_window_stats: deterministic min/max/argmax/mean of one window
     * scalar (optionally Pearson+Spearman vs a second) over consecutive windows, via the
     * verifier's {@code sampleWindows} substrate. Replaces the sandbox-loop-capped
     * computations the panel keeps requesting (P-b72c-121's HCN-growth and correlation
     * open questions). The "Invalid scalar_window_stats call:" reject prefix keeps
     * rejected ranges out of the validator's executed-range union; the success prefix is
     * in {@link #SUCCESS_PREFIXES} (a computed stat is real evidence).
     */
    String scalarWindowStats(JsonNode args) {
        if (!scalarStatsAvailable()) {
            return "scalar_window_stats: disabled (verifier not wired or tool off).";
        }
        String lhs = args.path("lhs").asText(null);
        String rhsRaw = args.path("rhs").asText("");
        String rhs = rhsRaw == null || rhsRaw.isBlank() ? null : rhsRaw;
        long windowStart = args.path("window_start").asLong(0);
        int windowSize   = args.path("window_size").asInt(0);
        int numWindows   = args.path("num_windows").asInt(0);

        java.util.List<String> vocab = com.emergentterrain.research.conjecture.MathInvariants.vocabulary();
        if (lhs == null || !vocab.contains(lhs)) {
            return "Invalid scalar_window_stats call: unknown lhs scalar '" + lhs
                    + "'. Valid scalars: " + String.join(", ", vocab);
        }
        if (rhs != null && !vocab.contains(rhs)) {
            return "Invalid scalar_window_stats call: unknown rhs scalar '" + rhs
                    + "'. Valid scalars: " + String.join(", ", vocab);
        }
        if (windowStart < 1 || windowSize < 100 || windowSize > 50_000
                || numWindows < 1 || numWindows > 200) {
            return "Invalid scalar_window_stats call: require window_start >= 1, "
                    + "window_size in [100, 50000], num_windows in [1, 200].";
        }

        java.util.List<java.util.Map<String, Double>> samples =
                conjectureVerifier.sampleWindows(windowStart, windowSize, numWindows);
        com.emergentterrain.research.conjecture.ScalarWindowStats.Result r =
                com.emergentterrain.research.conjecture.ScalarWindowStats.compute(
                        samples, lhs, rhs, windowStart, windowSize);
        if (r == null) {
            return "scalar_window_stats: no usable windows — the requested range may extend past "
                    + "the 2,000,000 sieve ceiling, or the scalar was absent from every window.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("scalar_window_stats: %s%s over %d window(s) of size %d starting at %d.%n",
                lhs, rhs == null ? "" : " vs " + rhs, r.windows(), windowSize, windowStart));
        appendSeries(sb, r.lhs());
        if (r.rhs() != null) {
            appendSeries(sb, r.rhs());
            if (r.pearson() != null) {
                sb.append(String.format("  correlation over %d windows: Pearson %+.4f, Spearman %+.4f%n",
                        r.windows(), r.pearson(), r.spearman()));
            } else {
                sb.append("  correlation: undefined (a scalar is constant over these windows, or < 3 windows)\n");
            }
        }
        sb.append("  (deterministic computation over the trusted sieve substrate; windows past "
                + "x=2,000,000 were skipped)");
        return sb.toString();
    }

    private static void appendSeries(StringBuilder sb,
            com.emergentterrain.research.conjecture.ScalarWindowStats.Series s) {
        sb.append(String.format("  %s: n=%d min=%+.5f max=%+.5f (at window start %d) mean=%+.5f%n",
                s.name(), s.n(), s.min(), s.max(), s.argmaxWindowStart(), s.mean()));
    }

    /**
     * Phase 30 Wave 6 — 30.7 P3: parse a proposed mechanism, evaluate it over held-out windows via the
     * conjecture verifier's substrate, and return the {@link MechanismGate} verdict. Verified-not-proven
     * framing. Gated: disabled line unless the verifier is wired AND the proposal flag is on.
     */
    String proposeMechanism(JsonNode args) {
        if (!mechanismProposalAvailable()) {
            return "propose_mechanism: disabled (mechanism proposal is off).";
        }
        String dsl = args.path("dsl").asText("");
        if (dsl == null || dsl.isBlank()) {
            return "propose_mechanism: 'dsl' is required, e.g. predict(mean_omega) := log(sqrt(mean_divisor_count)).";
        }
        com.emergentterrain.research.conjecture.Mechanism m;
        try {
            m = com.emergentterrain.research.conjecture.Mechanism.parse(dsl);
        } catch (RuntimeException e) {
            return "propose_mechanism: DSL parse error — " + e.getMessage();
        }
        long start = args.path("window_start").asLong(0);
        int size = args.path("window_size").asInt(0);
        int num = args.path("num_windows").asInt(8);
        if (start < 1 || size < 1) {
            return "propose_mechanism: 'window_start' (>=1) and 'window_size' (>=1) are required, within [1, "
                    + com.emergentterrain.research.conjecture.ConjectureVerifier.MAX_N + "].";
        }
        java.util.List<java.util.Map<String, Double>> samples;
        try {
            samples = conjectureVerifier.sampleWindows(start, size, num);
        } catch (RuntimeException e) {
            return "propose_mechanism: could not evaluate windows — " + e.getMessage();
        }
        if (samples.size() < 6) {   // need >= 2 folds of MechanismGate.MIN_HELD (3) each
            return "propose_mechanism: only " + samples.size()
                    + " in-range window(s) evaluated; choose window_start/size so several windows fit in [1, "
                    + com.emergentterrain.research.conjecture.ConjectureVerifier.MAX_N + "].";
        }
        var verdict = com.emergentterrain.research.conjecture.MechanismGate.evaluate(
                m, samples, new java.util.HashSet<>(com.emergentterrain.research.conjecture.MathInvariants.vocabulary()),
                com.emergentterrain.research.conjecture.ConjectureEngine.TOL);
        if (verdict.admit()) {
            return String.format(
                    "propose_mechanism: ACCEPTED — the mechanism for %s closes, is dimensionally consistent, "
                    + "respects its declared invariants, and regenerates the bound on held-out windows. This is a "
                    + "VERIFIED RUNNABLE MODEL that reproduces the claim, NOT a proof — report it as such.", m.target());
        }
        return String.format(
                "propose_mechanism: REJECTED (%s) — closes=%s dimensionallyValid=%s invariantsHold=%s regenerates=%s. "
                + "Revise the mechanism and propose again.", verdict.reason(), verdict.closes(),
                verdict.dimensionallyValid(), verdict.invariantsHold(), verdict.regenerates());
    }

    /**
     * Phase 23.5 — verify a mined conjecture on a held-out window slice.
     * Delegates the heavy lifting to
     * {@link com.emergentterrain.research.conjecture.ConjectureVerifier};
     * this method just parses args, calls verify(), and formats the
     * result as a single LLM-readable line plus per-window detail.
     *
     * <p>Held-out semantics are at the prompt level — the LLM should
     * pick a {@code window_start} the miner didn't see (anything outside
     * the prototype's {100, 1_000, 5_000, 10_000, 50_000, 100_000,
     * 500_000, 1_000_000} offsets). The verifier itself doesn't
     * remember mining history.
     */
    private String verifyConjecture(JsonNode args) {
        if (conjectureVerifier == null) {
            return "verify_conjecture not configured: the conjecture miner package isn't wired into this MathToolSuite instance.";
        }
        String lhs = args.path("lhs").asText("");
        String rhs = args.path("rhs").asText("");
        if (lhs.isBlank() || rhs.isBlank()) {
            return "Missing 'lhs' or 'rhs' scalar name.";
        }
        double k = args.path("k").asDouble(Double.NaN);
        double c = args.path("c").asDouble(Double.NaN);
        if (Double.isNaN(k) || Double.isNaN(c)) {
            return "Missing 'k' or 'c' inequality coefficient.";
        }
        // G2 Stage 2 — optional SECOND affine term: lhs <= k*rhs + k2*rhs2 + c. Omit rhs2 for a
        // one-term bound (identical to before).
        String rhs2 = args.path("rhs2").asText("");
        double k2 = args.path("k2").asDouble(0.0);
        long windowStart = args.path("window_start").asLong(-1);
        int windowSize = args.path("window_size").asInt(-1);
        int numWindows = args.path("num_windows").asInt(
                com.emergentterrain.research.conjecture.ConjectureVerifier.DEFAULT_NUM_WINDOWS);
        if (windowStart < 0 || windowSize <= 0) {
            return "Invalid window: window_start >= 0 and window_size > 0 required.";
        }

        com.emergentterrain.research.conjecture.ConjectureVerifier.VerifyResult result;
        try {
            result = rhs2.isBlank()
                    ? conjectureVerifier.verify(lhs, rhs, k, c, windowStart, windowSize, numWindows)
                    : conjectureVerifier.verify(lhs, rhs, k, rhs2, k2, c, windowStart, windowSize, numWindows);
        } catch (IllegalArgumentException e) {
            return "Invalid verify_conjecture call: " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        String boundStr = rhs2.isBlank()
                ? String.format("%+.4f * %s + %+.4f", k, rhs, c)
                : String.format("%+.4f * %s + %+.4f * %s + %+.4f", k, rhs, k2, rhs2, c);
        sb.append(String.format(
                "verify_conjecture: %s <= %s %s across %d window(s) of size %d starting at %d. ",
                lhs, boundStr,
                result.held() ? "HELD" : "REFUTED",
                result.windowsEvaluated(), windowSize, windowStart));
        sb.append(String.format("worst margin: %+.5f at window starting %d.",
                result.worstMargin(), result.worstWindowStart()));
        // 2026-06-10 (tight-bound frontier) — relative slack at the binding (worst-margin)
        // window: |margin| / |bound|. The worst margin is the window where the lhs sits CLOSEST
        // to the ceiling, so this is the bound's tightest point; if even there the slack is large,
        // the bound is loose everywhere. Reported for HELD bounds both to nudge the professor to
        // iterate k/c toward a small slack and as the parseable tightness signal the discovery→pool
        // write-back gates on (ConjectureInvestigationWriteBackService). Smaller = tighter.
        // 2026-06-10 fix — find the binding window by MAX margin directly, not by matching
        // worstWindowStart(): for a log_window_* bound the binding-regime auto-probe appends windows
        // below the requested start, and worstWindowStart() didn't always equal a windows() entry, so
        // the lookup silently missed and no slack was emitted (B's gate went inert for growth anchors).
        // The max-margin window is the binding window by definition and is always present.
        if (result.held()) {
            var binding = result.windows().stream()
                    .max(java.util.Comparator.comparingDouble(w -> w.margin()));
            // |margin| / |bound|. WindowReport now stores the full evaluated bound (all terms), so this is
            // exact for BOTH one-term and two-term — no re-derivation from the primary term.
            double relSlack = binding
                    .map(w -> Math.abs(w.margin()) / Math.max(Math.abs(w.bound()), 1e-9))
                    .orElse(0.0);
            sb.append(String.format(
                    " relative slack: %.4f (|margin|/|bound|, smaller = tighter).", relSlack));
            // 2026-06-16 (REVIEW_REPORT b72c4e10 rec #1 follow-on) — the legend's "a small positive
            // margin still holds" prompted papers to (correctly) ask whether a tolerance-only hold is a
            // strict inequality. Report the split directly so the narrator doesn't reverse-engineer it:
            // strictly-held windows (margin <= 0) vs held-only-within-tolerance (0 < margin <= 2*TOL).
            double tolThreshold = result.holdThreshold();
            long strictHolds = result.windows().stream().filter(w -> w.margin() <= 0.0).count();
            long toleranceHolds = result.windows().stream()
                    .filter(w -> w.margin() > 0.0 && w.margin() <= tolThreshold).count();
            sb.append(String.format(
                    " %d/%d window(s) hold strictly (margin <= 0); %d hold only within tolerance "
                            + "(0 < margin <= %.5f).",
                    strictHolds, result.windows().size(), toleranceHolds, tolThreshold));
            if (relSlack > MAX_REL_SLACK_SUGGEST && !rhs2.isBlank()) {
                // G2 two-term tighten pass — the lever is the intercept c (keep k1, k2 from the residual
                // fit). Tightest c keeping every window holding = c + the binding (max) margin; re-verify
                // there AND confirm a slightly smaller c REFUTES, which demonstrates the binding regime
                // (clears binding_regime_uncovered + earns EMPIRICAL_RESULT for a held composite). Phrased
                // with "re-verify at c=" so it does NOT match the one-term SUGGEST_K gate (no mis-fire).
                double cTight = c + binding.map(w -> w.margin()).orElse(0.0);
                // run-26 rec 2 — name the LITERAL refuting intercept (copyable numbers rule):
                // lowering c by 2× the hold threshold pushes the binding window's margin past it.
                double cRefute = cTight - 2 * result.holdThreshold();
                sb.append(String.format(
                        " LOOSE (two-term): holds with this much slack — the tightest intercept keeping "
                                + "k and k2 is c ≈ %+.4f. Re-verify at c=%+.4f (keep k=%+.4f, rhs2=%s, "
                                + "k2=%+.4f) AND confirm c=%+.5f REFUTES (2× the hold threshold lower), to "
                                + "demonstrate the binding regime; a held composite that never binds stays "
                                + "loose and is not retained as an EMPIRICAL_RESULT.",
                        cTight, cTight, k, rhs2, k2, cRefute));
            } else if (relSlack > MAX_REL_SLACK_SUGGEST) {
                // 2026-06-11 (rec 2) — one-term: a HELD bound left at a loose textbook constant (e.g.
                // Cramér's k=1, ~2x loose at reachable x). Suggest the TIGHTEST k that still holds (max
                // over windows of (lhs − c)/rhs) so it tightens to the empirical fit. One-term only — for
                // two-term this k (rhs-only) is wrong, hence the rhs2 guard above.
                double[] lhsVals = result.windows().stream().mapToDouble(w -> w.lhsValue()).toArray();
                double[] rhsVals = result.windows().stream().mapToDouble(w -> w.rhsValue()).toArray();
                double kTight = tightestHoldingK(lhsVals, rhsVals, c);
                if (Double.isFinite(kTight) && kTight > 0 && kTight < k) {
                    double kSuggest = Math.ceil(kTight * 1000.0) / 1000.0;   // a hair above, so it still holds
                    // 2026-07-05 (P-b72c-77627783) — parity with the two-term hint: without the
                    // explicit refute clause the professor stopped at tight holds (rel-slack
                    // 0.0003!) and still drew binding_regime_uncovered. Name the full protocol.
                    sb.append(String.format(
                            " LOOSE: the tightest k that holds these windows is ~%.4f (your largest "
                                    + "(lhs−c)/rhs ratio) — re-verify at k=%.4f (keep c=%+.4f) for a TIGHT bound "
                                    + "the pool will retain, AND confirm a slightly smaller k REFUTES, to "
                                    + "demonstrate the binding regime; do NOT stop at a textbook asymptotic "
                                    + "constant that leaves this much slack, and do NOT stop at the tight hold "
                                    + "without its refute.",
                            kTight, kSuggest, c));
                }
            } else {
                // 2026-07-05 (run-26 rec 2, P-b72c-77627796) — the TIGHT-hold case had NO hint at
                // all: the LOOSE branches fire only on large slack, so a professor who reached
                // rel-slack 0.0026 got silence and spent its (D1-extended!) rounds RE-holding —
                // 6 HELD, 0 REFUTED, binding_regime demotion anyway. Name the literal refuting
                // intercept: lowering c by 2× the hold threshold pushes the binding window's
                // margin (≥ −threshold at a tight hold) past the threshold — a guaranteed REFUTED
                // there, which completes the demonstrated binding pair.
                double cRefute = c + binding.map(w -> w.margin()).orElse(0.0)
                        - 2 * result.holdThreshold();
                sb.append(String.format(
                        " TIGHT HOLD — one call from the demonstrated binding pair: re-verify with "
                                + "c=%+.5f (same k%s; that is the binding margin minus 2× the hold "
                                + "threshold %.5f) — it should REFUTE, and hold+refute together are the "
                                + "demonstration. Do NOT re-verify more holds.",
                        cRefute, rhs2.isBlank() ? "" : "/k2", result.holdThreshold()));
            }
            // 2026-06-18 (REVIEW_REPORT b72c4e10 §1 rec 3) — tolerance-dominated HOLD: when the lhs
            // magnitude is at or below the HOLD slack (2*TOL=0.04), "holds" is dominated by the tolerance,
            // not by the bound being tight (twin_prime_density ~0.002–0.016 < 0.04). Inform so a vacuous
            // small-magnitude HOLD is not narrated as meaningful evidence. Inform-don't-suppress; OFF→silent.
            if (verifyInformCaveatsEnabled && !result.relativeVerdict()) {
                double meanAbsLhs = result.windows().stream()
                        .mapToDouble(w -> Math.abs(w.lhsValue())).average().orElse(0.0);
                if (meanAbsLhs > 0 && meanAbsLhs <= tolThreshold) {
                    sb.append(String.format(
                            " NOTE (tolerance-dominated): the lhs magnitude (~%.4f) is at or below the HOLD "
                                    + "tolerance (%.2f = 2*TOL), so HOLD here is dominated by the tolerance and "
                                    + "is WEAK evidence — rely on a refutation at larger x or a relative-margin "
                                    + "view, not this HOLD.",
                            meanAbsLhs, tolThreshold));
                }
            }
        }
        // 2026-07-05 (run-21 Rec 1, option b) — relative-tolerance verdict tag. In the
        // tolerance-dominated regime the absolute 0.04 floor made every verdict vacuous (no
        // reachable REFUTED); the verifier re-scored holds against a scale-aware threshold, so a
        // HOLD is meaningful again and a REFUTED is reachable — say so, on BOTH verdicts, and
        // supersede the old weak-evidence note above.
        if (result.relativeVerdict()) {
            sb.append(String.format(
                    " RELATIVE-TOLERANCE VERDICT: the lhs magnitude is below the absolute HOLD "
                            + "tolerance (0.04), where the absolute verdict is vacuous, so this "
                            + "verdict used a scale-aware hold threshold of %.5f "
                            + "(max(4%% of mean |lhs|, 1/window_size)). A HOLD here is meaningful "
                            + "and a REFUTED is reachable — tighten the intercept until one "
                            + "appears to demonstrate the binding regime.",
                    result.holdThreshold()));
        }
        // 2026-06-18 (REVIEW_REPORT b72c4e10 §1 rec 2) — constant-framed LHS: a one-sided bound on a
        // quantity whose real content is a converged constant is typically non-informative (it bounds a
        // settling quantity by a possibly-growing RHS; P-b72c-77627725 bounded omega_Omega_gap → 0.77316
        // by a log-growing RHS). Steer to verify_constant. Verdict-independent; inform-don't-suppress (the
        // miner's seed + the call are intentional breadth — this attaches a hint, it does not suppress).
        if (verifyInformCaveatsEnabled
                && com.emergentterrain.research.ScoreCalibration.CONSTANT_FRAMED_LHS.containsKey(lhs)) {
            sb.append(String.format(
                    " NOTE (constant-framed): %s is a known constant-convergence quantity (→ %s); a one-sided "
                            + "bound on it is typically non-informative. Measure it with verify_constant — that "
                            + "converged value is the result, not this bound.",
                    lhs, com.emergentterrain.research.ScoreCalibration.CONSTANT_FRAMED_LHS.get(lhs)));
        }
        // Log-base label (room b72c4e10, REVIEW_REPORT §1 rec #2): the
        // log_window_* scalars are base-10 (MathInvariants uses Math.log10), but
        // "log" reads as natural log in the RH-conditional O(sqrt(x) log x)
        // motivation a narrator reaches for. Stating the base stops the fitted
        // constants from being silently tied to an ln bound — a base mismatch
        // P-b72c-17 had to call out by hand (the +2.0000 slope needs an
        // ln(10) rescale to read as an RH normalisation).
        //
        // §1 rec #2 refinement: the earlier "multiply by ln(10)" phrasing is true
        // for the SCALAR (log10(x)*ln(10)=ln(x)) but P-b72c-24 mis-applied it to
        // the COEFFICIENT (wrote 2*ln(10)=4.605 instead of 2/ln(10)=0.869). Spell
        // out BOTH conversions and print the concrete natural-log slope k/ln(10)
        // so the narrator copies the right number instead of re-deriving it.
        if (rhs.equals("log_window_start") || rhs.equals("log_window_size")) {
            String arg = rhs.substring(4); // "window_start" | "window_size"
            sb.append(String.format(
                    " (%s is base-10 log: the rhs_scalar times ln(10)=2.302585 equals"
                            + " ln(%s); the bound's natural-log slope is k/ln(10)=%+.5f"
                            + " — divide k by ln(10), do NOT multiply.)",
                    rhs, arg, k / Math.log(10.0)));
        }
        // Binding-regime auto-probe (room b72c4e10, REVIEW_REPORT §1 rec #3):
        // the verifier may append window(s) BELOW the requested start to test
        // where the bound binds. The headline verdict + worst margin above range
        // over ALL windows including that probe, so a call whose requested
        // windows all HELD can still read REFUTED purely because of the probe.
        // Spell out the requested-range result separately so the narrator does
        // not (a) present the probe as a window it chose, or (b) double-count the
        // same small-start refutation as independent evidence across calls.
        var probeWindows = result.windows().stream()
                .filter(w -> w.windowStart() < windowStart).toList();
        if (!probeWindows.isEmpty()) {
            var userWindows = result.windows().stream()
                    .filter(w -> w.windowStart() >= windowStart).toList();
            long userRefuted = userWindows.stream().filter(w -> !w.holds()).count();
            String userWorst = userWindows.stream()
                    .max(java.util.Comparator.comparingDouble(w -> w.margin()))
                    .map(w -> String.format(" worst margin %+.5f at start %d.",
                            w.margin(), w.windowStart()))
                    .orElse("");
            sb.append(String.format(
                    " [binding-regime auto-probe: %d extra window(s) below the requested"
                            + " start were appended at the smallest-ceiling start to test where"
                            + " the bound binds; they are NOT part of the requested range.",
                    probeWindows.size()));
            if (userWindows.isEmpty()) {
                sb.append(" The requested range contributed no windows.]");
            } else if (userRefuted == 0) {
                sb.append(String.format(
                        " Requested range (start>=%d): all %d window(s) HELD.%s"
                                + " The REFUTED verdict above is due solely to the appended probe.]",
                        windowStart, userWindows.size(), userWorst));
            } else {
                sb.append(String.format(
                        " Requested range (start>=%d): %d of %d window(s) REFUTED.%s]",
                        windowStart, userRefuted, userWindows.size(), userWorst));
            }
        }
        // 2026-06-16 (REVIEW_REPORT b72c4e10 batch 1, rec #1) — state the margin/holds convention
        // inline. Without it the narrator invents ambiguity about the sign of `margin` and hedges (or
        // the panel escalates HELD windows to "counterexamples"), draining Honesty/Rigor. Domain-neutral
        // legend; the per-window holds/REFUTED label is authoritative. Uses the live TOL so it never
        // drifts from ConjectureEngine.
        double holdThreshold = result.holdThreshold();
        sb.append(String.format(
                "%nverdict legend: margin = lhs - bound; a window HOLDS iff margin <= %.5f (%s) — "
                        + "a small positive margin still holds, only margin > %.5f is REFUTED. The "
                        + "per-window holds/REFUTED label is authoritative; do not re-derive or hedge "
                        + "the sign convention.",
                holdThreshold,
                result.relativeVerdict() ? "relative-tolerance verdict" : "2*TOL",
                holdThreshold));
        sb.append("\nPer-window:");
        for (var w : result.windows()) {
            // 2026-05-29 — label the rhs *scalar* explicitly and print the evaluated
            // bound (the inequality ceiling) alongside it. The bare "rhs=" column was the rhs scalar
            // (e.g. log_window_start), NOT the inequality ceiling, which a narrator
            // (P-b72c-9) misread as a tool bug ("constant-bound test returned a
            // non-constant rhs") and hedged its whole Findings on.
            // 2026-06-15 (G2 fix) — print the bound the verifier STORED (w.bound(), the full k·rhs +
            // k2·rhs2 + c), not a re-derived k·rhs+c that drops the second term. For a two-term bound also
            // surface rhs2_scalar so a downstream model can recompute every term. Identity now holds for
            // both: margin == lhs − bound.
            String rhs2Col = (k2 != 0.0)
                    ? String.format("  rhs2_scalar=%+.5f", w.rhs2Value())
                    : "";
            sb.append(String.format(
                    "\n  start=%d  lhs=%+.5f  rhs_scalar=%+.5f%s  bound=%+.5f  margin=%+.5f  %s",
                    w.windowStart(), w.lhsValue(), w.rhsValue(), rhs2Col, w.bound(),
                    w.margin(), w.holds() ? "holds" : "REFUTED"));
        }
        return sb.toString();
    }

    /**
     * 2026-06-10 (lever D, frame 1) — verify_constant: the two-sided constant-convergence companion to
     * verify_conjecture. For a scalar whose real content is a LIMIT (a normalized ratio that plateaus —
     * twin_vs_prime_sq → 2C₂, cramer_gap_ratio → a Cramér constant), this measures the value across
     * held-out failure-region windows and tests {@code |lhs − target|} (or the spread, if no target),
     * instead of forcing a one-sided {@code lhs ≤ k·rhs + c} bound the quantity doesn't fit. Same WORK
     * and RANGE caps as verify_conjecture; failures returned as plain text (no throw) for the tool loop.
     */
    private String verifyConstant(JsonNode args) {
        if (conjectureVerifier == null) {
            return "verify_constant not configured: the conjecture miner package isn't wired into this MathToolSuite instance.";
        }
        String lhs = args.path("lhs").asText("");
        if (lhs.isBlank()) {
            return "Missing 'lhs' scalar name.";
        }
        Double target = args.has("target") && !args.path("target").isNull()
                ? args.path("target").asDouble() : null;
        double tol = args.path("tol").asDouble(0.05);
        // measurement-frame-default.md — scale-ladder mode: the TOOL picks a geometric ladder of starts
        // (the range leaves the LLM's hands, which three runs showed it won't push high on its own), so a
        // slow converger is measured across scales out to the ceiling. window_start is ignored here.
        if (args.path("scale_ladder").asBoolean(false)) {
            int ladderSize = args.path("window_size").asInt(5000);
            int ladderNum = args.path("num_windows").asInt(5);
            if (ladderSize <= 0 || ladderNum <= 0) { ladderSize = 5000; ladderNum = 5; }
            return verifyConstantLadder(lhs, ladderSize, ladderNum, target, tol);
        }
        long windowStart = args.path("window_start").asLong(-1);
        int windowSize = args.path("window_size").asInt(-1);
        int numWindows = args.path("num_windows").asInt(
                com.emergentterrain.research.conjecture.ConjectureVerifier.DEFAULT_NUM_WINDOWS);
        if (windowStart < 0 || windowSize <= 0 || numWindows <= 0) {
            return "Invalid window: window_start >= 0, window_size > 0, num_windows > 0 required.";
        }
        long work = (long) windowSize * numWindows;
        if (work > conjectureVerifier.maxWork()) {
            return String.format("WORK cap exceeded: window_size × num_windows = %d > %d. %s",
                    work, conjectureVerifier.maxWork(),
                    capFixHint(windowSize, conjectureVerifier.maxWork()));
        }
        if (windowStart + work > conjectureVerifier.maxN()) {
            return String.format("RANGE cap exceeded: window_start + window_size × num_windows = %d > %d "
                            + "(oracle range). FIX: keep this window shape and use window_start ≤ %d.",
                    windowStart + work, conjectureVerifier.maxN(),
                    Math.max(0, conjectureVerifier.maxN() - work));
        }
        java.util.List<java.util.Map<String, Double>> windows;
        try {
            windows = conjectureVerifier.sampleWindows(windowStart, windowSize, numWindows);
        } catch (RuntimeException e) {
            return "verify_constant evaluation failed: " + e.getMessage();
        }
        if (windows.isEmpty()) {
            return "No windows evaluated for verify_constant (range past the oracle limit?).";
        }
        double[] values = new double[windows.size()];
        long[] starts = new long[windows.size()];
        for (int i = 0; i < windows.size(); i++) {
            java.util.Map<String, Double> w = windows.get(i);
            values[i] = w.getOrDefault(lhs, 0.0);
            starts[i] = w.getOrDefault("_window_start",
                    (double) (windowStart + (long) i * windowSize)).longValue();
        }
        com.emergentterrain.research.conjecture.ConstantConvergence.Assessment a =
                com.emergentterrain.research.conjecture.ConstantConvergence.assess(values, starts, target, tol);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "verify_constant: %s ~ %.5f over %d window(s) of size %d starting at %d. ",
                lhs, a.mean(), windows.size(), windowSize, windowStart));
        if (target != null) {
            // Convergence is judged by the MEAN's deviation from the target (the limit), with the
            // per-window spread reported as sampling noise — a sparse quantity (twin primes) is noisy
            // per window even once its average has converged.
            sb.append(String.format(
                    "%s target %.5f: mean rel-dev %.4f, per-window spread %.4f (worst |dev| %.5f at window "
                            + "starting %d) (tol %.4f).",
                    a.converged() ? "CONVERGED to" : "NOT converged to",
                    target, a.meanRelDeviation(), a.relativeSpread(), a.worstDeviation(),
                    a.worstWindowStart(), tol));
        } else {
            sb.append(String.format(
                    "no target — estimated limit %.5f (range [%.5f, %.5f]), per-window spread %.4f %s.",
                    a.estimate(), a.min(), a.max(), a.relativeSpread(),
                    a.converged() ? "(settled within tol)" : "(not yet settled)"));
        }
        return sb.toString();
    }

    /**
     * measurement-frame-default.md — auto scale-ladder for verify_constant. Evaluates {@code lhs} at a
     * GEOMETRIC ladder of window_starts (1e5, 1e6, … up to the largest decade that fits the trusted
     * range) and reports the per-scale mean trajectory + whether the gap to {@code target} is SHRINKING
     * across scales. The tool — not the LLM — picks the scales, so the reach is actually exercised; the
     * cross-scale trajectory is the strong measurement, and a slow converger shows a tightening trend
     * even when no single window is "converged".
     */
    private String verifyConstantLadder(String lhs, int windowSize, int numWindows, Double target, double tol) {
        long maxN = conjectureVerifier.maxN();
        // run-25 rec 1 — ladder rungs use their own sample budget (see ladderRungBudget): the
        // interactive WORK cap is a per-call guard, not a substrate limit, and second-order
        // deficit signals are unmeasurable at 100k integers/rung.
        int rungWindowSize = ladderRungWindowSize(windowSize, numWindows, ladderRungBudget);
        long work = (long) rungWindowSize * numWindows;
        java.util.List<Long> starts = new java.util.ArrayList<>();
        for (long s = 100_000L; s + work <= maxN; s *= 10) {
            starts.add(s);
        }
        if (starts.isEmpty()) {
            return "verify_constant scale-ladder: no rung fits (window_size × num_windows too large for the range).";
        }
        double[] means = new double[starts.size()];
        StringBuilder traj = new StringBuilder();
        int chunkedLogicalWindows = 0;
        int chunkCount = 0;
        long queuedMs = 0;
        long computeMs = 0;
        long transportMs = 0;
        for (int i = 0; i < starts.size(); i++) {
            long s = starts.get(i);
            java.util.List<java.util.Map<String, Double>> ws;
            try {
                if ("abundant_density".equals(lhs) && rangeChunkExecutor != null
                        && rangeChunkExecutor.isEnabled()
                        && rungWindowSize > rangeChunkExecutor.chunkSize()) {
                    ws = new java.util.ArrayList<>(numWindows);
                    for (int window = 0; window < numWindows; window++) {
                        long logicalStart = s + (long) window * rungWindowSize;
                        long logicalEnd = logicalStart + rungWindowSize;
                        com.emergentterrain.research.conjecture.RangePreservingChunkExecutor.DensityJobResult job =
                                rangeChunkExecutor.executeDensity("abundant_density", logicalStart, logicalEnd,
                                        (chunkStart, chunkSize) -> {
                                            com.emergentterrain.research.conjecture.ConjectureVerifier.TimedSampleWindow sample =
                                                    conjectureVerifier.sampleWindowTimed(chunkStart, chunkSize);
                                            return new com.emergentterrain.research.conjecture.RangePreservingChunkExecutor
                                                    .DensityMeasurement(sample.scalars().getOrDefault("abundant_density", 0.0),
                                                    sample.computeMs(), sample.transportMs());
                                        });
                        if (!job.complete()) {
                            long total = logicalEnd - logicalStart;
                            return String.format(
                                    "verify_constant scale-ladder PARTIAL: abundant_density logical range [%d, %d) "
                                            + "is %s after %,d / %,d integers; completed chunks are journaled as %s. "
                                            + "No full-range result is claimed; resume preserves this exact range.",
                                    logicalStart, logicalEnd, job.job().status(), job.completedLength(), total,
                                    job.job().jobId());
                        }
                        ws.add(java.util.Map.of("abundant_density", job.density(),
                                "_window_start", (double) logicalStart,
                                "_window_size", (double) rungWindowSize));
                        chunkedLogicalWindows++;
                        chunkCount += job.job().chunks().size();
                        queuedMs += job.queuedMs();
                        computeMs += job.computeMs();
                        transportMs += job.transportMs();
                    }
                } else {
                    ws = conjectureVerifier.sampleWindows(s, rungWindowSize, numWindows);
                }
            } catch (RuntimeException e) {
                return "verify_constant scale-ladder eval failed at window_start=" + s + ": " + e.getMessage();
            }
            double sum = 0.0;
            int n = 0;
            for (java.util.Map<String, Double> w : ws) {
                sum += w.getOrDefault(lhs, 0.0);
                n++;
            }
            means[i] = n > 0 ? sum / n : Double.NaN;
            traj.append(String.format("%s%d→%.5f", i == 0 ? "" : ", ", s, means[i]));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("verify_constant scale-ladder: %s measured across %d scales [%s]. ",
                lhs, starts.size(), traj));
        if (target != null) {
            double g0 = Math.abs(means[0] - target);
            double gN = Math.abs(means[means.length - 1] - target);
            boolean shrinking = gN < g0;
            sb.append(String.format(
                    "Target %.5f: |gap| %.5f at %d → %.5f at %d — %s. %s",
                    target, g0, starts.get(0), gN, starts.get(starts.size() - 1),
                    shrinking ? "SHRINKING toward the limit across scales" : "NOT shrinking",
                    gN <= Math.abs(target) * tol
                            ? "CONVERGED at the high end (within tol)."
                            : "The tightening TREND across scales is the result — a slow converger need "
                              + "not fully settle even at the ceiling; report the trajectory and rate, not a NULL."));
        } else {
            double hi = means[means.length - 1];
            double prev = means.length > 1 ? means[means.length - 2] : hi;
            sb.append(String.format(
                    "No target — highest-scale estimate %.5f (vs %.5f one decade lower; %s). Report the "
                            + "cross-scale trajectory as the measurement.",
                    hi, prev, Math.abs(hi - prev) <= Math.abs(hi) * tol ? "settling" : "still moving"));
        }
        if (chunkedLogicalWindows > 0) {
            sb.append(String.format(
                    " Phase 36 execution: %,d exact logical window(s) were preserved and completed as %,d "
                            + "resumable chunks (queue %,d ms; sidecar compute %,d ms; transport %,d ms).",
                    chunkedLogicalWindows, chunkCount, queuedMs, computeMs, transportMs));
        }
        // run-25 rec 1 — the per-rung sample size is load-bearing context for any second-order
        // claim: count noise scales ~1/sqrt(count) for sparse quantities, so a paper must be able
        // to compare its signal against the sample its rungs actually had.
        if (rungWindowSize != windowSize) {
            sb.append(String.format(
                    " rung sample: %d window(s) × %,d = %,d integers per rung (widened from "
                            + "window_size=%,d by the ladder rung budget — count noise ~1/sqrt(count) "
                            + "for sparse quantities).",
                    numWindows, rungWindowSize, (long) numWindows * rungWindowSize, windowSize));
        } else {
            sb.append(String.format(
                    " rung sample: %d window(s) × %,d = %,d integers per rung.",
                    numWindows, rungWindowSize, (long) numWindows * rungWindowSize));
        }
        return sb.toString();
    }

    /**
     * 2026-06-10 (lever D, frame 2) — verify_oscillation: the result-frame for a scalar that oscillates
     * around zero (mertens_delta = the Mertens function). Reports sign changes, amplitude, near-zero mean,
     * and — given a scale scalar (e.g. sqrt_window_start) — the bounded envelope max|lhs|/scale (the
     * RH-relevant |M(x)|/√x). A one-sided bound is the wrong object for such a quantity.
     */
    private String verifyOscillation(JsonNode args) {
        if (conjectureVerifier == null) {
            return "verify_oscillation not configured: the conjecture miner package isn't wired in.";
        }
        String lhs = args.path("lhs").asText("");
        if (lhs.isBlank()) {
            return "Missing 'lhs' scalar name.";
        }
        String scaleRhs = args.path("scale_rhs").asText("");
        // measurement-frame-default.md Stage 1e — auto scale-ladder for verify_oscillation. Run-10 showed
        // the dirichlet_error professor (flagship open object, score 0.677) clustered every window at
        // x∈[1.2M,1.8M] even with the ceiling raised and frontier data in context, and named the narrow
        // range as its #1 limitation ("cannot distinguish O(1) from log log x"). The manual ladder is not
        // followed for range (the same failure the CONSTANT auto-ladder fixed), so the TOOL picks the
        // scales here too. window_start/scale_rhs are honoured below; in ladder mode window_start is ignored.
        if (args.path("scale_ladder").asBoolean(false)) {
            int ladderSize = args.path("window_size").asInt(5000);
            int ladderNum = args.path("num_windows").asInt(5);
            if (ladderSize <= 0 || ladderNum <= 0) { ladderSize = 5000; ladderNum = 5; }
            return verifyOscillationLadder(lhs, scaleRhs, ladderSize, ladderNum);
        }
        Object[] sampled = sampleScalarWindows(args);
        if (sampled[0] instanceof String err) {
            return err;
        }
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Double>> windows =
                (java.util.List<java.util.Map<String, Double>>) sampled[0];
        long windowStart = (long) sampled[1];
        int windowSize = (int) sampled[2];
        double[] values = new double[windows.size()];
        long[] starts = new long[windows.size()];
        double[] scale = scaleRhs.isBlank() ? null : new double[windows.size()];
        for (int i = 0; i < windows.size(); i++) {
            java.util.Map<String, Double> w = windows.get(i);
            values[i] = w.getOrDefault(lhs, 0.0);
            starts[i] = w.getOrDefault("_window_start",
                    (double) (windowStart + (long) i * windowSize)).longValue();
            if (scale != null) scale[i] = w.getOrDefault(scaleRhs, 0.0);
        }
        com.emergentterrain.research.conjecture.OscillationAssessment.Assessment a =
                com.emergentterrain.research.conjecture.OscillationAssessment.assess(values, starts, scale);
        // 2026-06-14 — data-driven centering verdict. The old text hardcoded "centred near 0" off
        // oscillates() (which only checks mean-vs-amplitude), so a one-sign-biased series like
        // dirichlet_error (genuinely net-positive at reachable x; its sign-symmetry is an x→∞ property)
        // was mislabelled centred. Keep the "OSCILLATES" token in the biased case so the write-back
        // envelope-survivor parser still matches — the envelope is valid regardless of centering.
        String oscLabel;
        if (!a.oscillates()) {
            oscLabel = "one-sided (not a centred oscillation).";
        } else if (com.emergentterrain.research.conjecture.OscillationAssessment
                .centredNearZero(a.mean(), a.meanAbs())) {
            oscLabel = "OSCILLATES (centred near 0).";
        } else {
            oscLabel = String.format(
                    "OSCILLATES but biased one sign over the tested range (mean %+.5f vs mean|.| %.5f — "
                            + "NOT centred near 0 here; centring at 0 is an asymptotic property not yet "
                            + "visible at reachable x).", a.mean(), a.meanAbs());
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "verify_oscillation: %s over %d window(s) of size %d starting at %d: %d sign change(s), "
                        + "amplitude %.5f at window starting %d, mean %+.5f, mean|.| %.5f. %s",
                lhs, windows.size(), windowSize, windowStart, a.signChanges(), a.amplitude(),
                a.ampWindowStart(), a.mean(), a.meanAbs(), oscLabel));
        if (scale != null && !Double.isNaN(a.envelope())) {
            sb.append(String.format(" envelope max|%s|/%s = %.5f.", lhs, scaleRhs, a.envelope()));
        }
        return sb.toString();
    }

    /**
     * measurement-frame-default.md Stage 1e — auto scale-ladder for verify_oscillation. Mirrors
     * {@link #verifyConstantLadder}: evaluates {@code lhs} at a GEOMETRIC ladder of window_starts
     * (1e5, 1e6, … up to the largest decade that fits the trusted range) and reports whether the
     * oscillation's envelope is BOUNDED (flat across decades → an O(1) envelope, the load-bearing signal
     * for a normalised quantity like dirichlet_error) or GROWING with x (rising amplitude → not bounded at
     * reachable x, consistent with a slow growth such as log log x). The tracked metric is the bounded
     * envelope {@code max|lhs|/scale} when {@code scaleRhs} is given (e.g. |M|/√x for raw mertens_delta),
     * else the raw amplitude {@code max|lhs|} (the right object for an already-normalised lhs). The
     * cross-scale trend IS the measurement — it directly answers "is the amplitude bounded?", which a
     * single low-x window cannot. The tool — not the LLM — picks the scales, so the reach is exercised.
     */
    // 2026-06-30 — well-posedness of the oscillating-envelope ladder. An arithmetic error term such as
    // dirichlet_error (= Δ(x)/x^{1/4}) oscillates on the ~√x scale. Sampling a FIXED integer span at
    // every rung samples ~150 oscillation periods at x=1e5 but <2 at x=1e9, so max|lhs| spuriously
    // SHRINKS with x — a sampling artefact the professor read as "over-normalised". For an oscillating
    // target we instead scale the sampled span with √x (≈ OSC_SPAN_PERIODS periods) and hold the point
    // count fixed (OSC_LADDER_POINTS), so every rung samples a comparable slice of the oscillation and
    // the cross-scale amplitude trend is comparable. Verified offline: the fixed-span ladder reports
    // 2.92→1.23 (−58%, false "shrinking"); the √x-scaled ladder reports a flat 2.85→2.85 (bounded O(1)).
    private static final double OSC_SPAN_PERIODS = 3.0;   // sampled span ≈ this many √x oscillation periods
    private static final int OSC_LADDER_POINTS = 30;      // eval points per rung (held constant across scales)
    private static final int OSC_MIN_WINDOW = 8;          // floor on per-point window size at the smallest rung

    private String verifyOscillationLadder(String lhs, String scaleRhs, int windowSize, int numWindows) {
        long maxN = conjectureVerifier.maxN();
        long maxWork = conjectureVerifier.maxWork();
        long work = (long) windowSize * numWindows;
        boolean useEnvelope = scaleRhs != null && !scaleRhs.isBlank();
        // √x-scaled sampling for oscillating targets (today: dirichlet_error). The per-rung span can grow
        // to maxWork, so the rung must fit that worst case, not the small fixed `work`.
        boolean sqrtSpan = com.emergentterrain.research.conjecture.ScalarOpenness.isOscillatingTarget(lhs);
        long fitWork = sqrtSpan ? maxWork : work;
        java.util.List<Long> starts = new java.util.ArrayList<>();
        for (long s = 100_000L; s + fitWork <= maxN; s *= 10) {
            starts.add(s);
        }
        if (starts.isEmpty()) {
            return "verify_oscillation scale-ladder: no rung fits (window_size × num_windows too large for the range).";
        }
        double[] metric = new double[starts.size()];   // envelope (with scale) or raw amplitude per rung
        int totalSignChanges = 0;
        StringBuilder traj = new StringBuilder();
        for (int i = 0; i < starts.size(); i++) {
            long s = starts.get(i);
            int rungWindowSize = windowSize;
            int rungNumWindows = numWindows;
            if (sqrtSpan) {
                long span = Math.min(maxWork, Math.round(OSC_SPAN_PERIODS * Math.sqrt((double) s)));
                rungNumWindows = OSC_LADDER_POINTS;
                rungWindowSize = (int) Math.max(OSC_MIN_WINDOW, span / rungNumWindows);
                if ((long) rungWindowSize * rungNumWindows > maxWork) {
                    rungWindowSize = (int) (maxWork / rungNumWindows);
                }
            }
            java.util.List<java.util.Map<String, Double>> ws;
            try {
                ws = conjectureVerifier.sampleWindows(s, rungWindowSize, rungNumWindows);
            } catch (RuntimeException e) {
                return "verify_oscillation scale-ladder eval failed at window_start=" + s + ": " + e.getMessage();
            }
            if (ws.isEmpty()) {
                metric[i] = Double.NaN;
                traj.append(String.format("%s%d→NaN", i == 0 ? "" : ", ", s));
                continue;
            }
            double[] values = new double[ws.size()];
            long[] wStarts = new long[ws.size()];
            double[] scale = useEnvelope ? new double[ws.size()] : null;
            for (int j = 0; j < ws.size(); j++) {
                java.util.Map<String, Double> w = ws.get(j);
                values[j] = w.getOrDefault(lhs, 0.0);
                wStarts[j] = w.getOrDefault("_window_start",
                        (double) (s + (long) j * rungWindowSize)).longValue();
                if (scale != null) scale[j] = w.getOrDefault(scaleRhs, 0.0);
            }
            com.emergentterrain.research.conjecture.OscillationAssessment.Assessment a =
                    com.emergentterrain.research.conjecture.OscillationAssessment.assess(values, wStarts, scale);
            totalSignChanges += a.signChanges();
            metric[i] = useEnvelope ? a.envelope() : a.amplitude();
            traj.append(String.format("%s%d→%.5f", i == 0 ? "" : ", ", s, metric[i]));
        }
        String metricName = useEnvelope ? ("envelope max|" + lhs + "|/" + scaleRhs)
                                        : ("amplitude max|" + lhs + "|");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "verify_oscillation scale-ladder: %s measured across %d scales [%s]; %d total sign change(s). ",
                metricName, starts.size(), traj, totalSignChanges));
        if (sqrtSpan) {
            sb.append(String.format(
                    "(%s oscillates on the ~√x scale, so each rung samples a √x-proportional span "
                            + "(~%.0f periods, %d points) — the amplitude is comparable across rungs and a "
                            + "flat trend is the well-posed bounded-envelope signal, not an artefact of a "
                            + "fixed integer span.) ",
                    lhs, OSC_SPAN_PERIODS, OSC_LADDER_POINTS));
        }
        double m0 = metric[0];
        double mN = metric[metric.length - 1];
        if (!Double.isNaN(m0) && !Double.isNaN(mN) && Math.abs(m0) > 1e-12) {
            double rel = (mN - m0) / Math.abs(m0);
            boolean bounded = Math.abs(rel) <= 0.15;   // flat within 15% across the decades → O(1) envelope
            sb.append(String.format(
                    "%s %.5f at %d → %.5f at %d (%+.1f%% across the ladder) — %s",
                    useEnvelope ? "Envelope" : "Amplitude", m0, starts.get(0), mN,
                    starts.get(starts.size() - 1), rel * 100.0,
                    bounded
                        ? "FLAT across scales: consistent with a BOUNDED O(1) envelope — the load-bearing "
                          + "measurement (amplitude does not grow with x out to the ceiling)."
                        : (rel > 0
                            ? "GROWING with x: the envelope is NOT bounded at reachable x — consistent with a "
                              + "slow growth (e.g. log log x). Report the growth across scales, not a bounded "
                              + "verdict."
                            : "SHRINKING with x: amplitude decays across scales (likely over-normalised — the "
                              + "lhs may already be divided by too large a scale).")));
        } else {
            sb.append("Envelope trend unavailable (degenerate metric at an endpoint).");
        }
        return sb.toString();
    }

    /**
     * 2026-06-10 (lever D, frame 3) — verify_growth: the result-frame for a super-logarithmic quantity
     * (max_divisor_count ~ exp(ln2·ln x/ln ln x)). Transforms each window's lhs into the growth
     * coordinate (default 'wigert': log(lhs)·ln ln x/ln x → ln2) and tests convergence of that to a
     * constant via the same machinery as verify_constant.
     */
    private String verifyGrowth(JsonNode args) {
        if (conjectureVerifier == null) {
            return "verify_growth not configured: the conjecture miner package isn't wired in.";
        }
        String lhs = args.path("lhs").asText("");
        if (lhs.isBlank()) {
            return "Missing 'lhs' scalar name.";
        }
        String scale = args.path("scale").asText("wigert");
        Double target = args.has("target") && !args.path("target").isNull()
                ? args.path("target").asDouble()
                : ("wigert".equals(scale)
                        ? com.emergentterrain.research.conjecture.GrowthScale.WIGERT_LIMIT : null);
        double tol = args.path("tol").asDouble(0.10);
        // 2026-07-04 — auto scale-ladder for verify_growth, the GROWTH twin of the verify_constant /
        // verify_oscillation ladders. P-b72c-77627746 asked for the Wigert coordinate at 1e6 via manual
        // windows (window_size=50000 × 5) and died on the WORK cap, so "glacially slow convergence"
        // rested on x ∈ [1e5, 2e5) alone — while the same run's CONSTANT ladder cruised to 1e9. The
        // tool — not the LLM — picks the rungs, each rung a small fixed slice, so reach never depends
        // on manual window arithmetic under the cap.
        if (args.path("scale_ladder").asBoolean(false)) {
            int ladderSize = args.path("window_size").asInt(5000);
            int ladderNum = args.path("num_windows").asInt(5);
            if (ladderSize <= 0 || ladderNum <= 0) { ladderSize = 5000; ladderNum = 5; }
            return verifyGrowthLadder(lhs, scale, ladderSize, ladderNum, target, tol);
        }
        Object[] sampled = sampleScalarWindows(args);
        if (sampled[0] instanceof String err) {
            return err;
        }
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Double>> windows =
                (java.util.List<java.util.Map<String, Double>>) sampled[0];
        long windowStart = (long) sampled[1];
        int windowSize = (int) sampled[2];
        double[] values = new double[windows.size()];
        long[] starts = new long[windows.size()];
        try {
            for (int i = 0; i < windows.size(); i++) {
                java.util.Map<String, Double> w = windows.get(i);
                long start = w.getOrDefault("_window_start",
                        (double) (windowStart + (long) i * windowSize)).longValue();
                starts[i] = start;
                values[i] = com.emergentterrain.research.conjecture.GrowthScale.transform(
                        scale, w.getOrDefault(lhs, 0.0), start);
            }
        } catch (IllegalArgumentException e) {
            return "verify_growth: " + e.getMessage();
        }
        com.emergentterrain.research.conjecture.ConstantConvergence.Assessment a =
                com.emergentterrain.research.conjecture.ConstantConvergence.assess(values, starts, target, tol);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "verify_growth: %s in %s coords ~ %.5f over %d window(s) of size %d starting at %d. ",
                lhs, scale, a.mean(), windows.size(), windowSize, windowStart));
        if (target != null) {
            sb.append(String.format(
                    "%s growth constant %.5f: mean rel-dev %.4f, per-window spread %.4f (worst |dev| %.5f at "
                            + "window starting %d) (tol %.4f).",
                    a.converged() ? "CONVERGED to" : "approaching (not yet within tol)",
                    target, a.meanRelDeviation(), a.relativeSpread(), a.worstDeviation(),
                    a.worstWindowStart(), tol));
        } else {
            sb.append(String.format("estimated growth constant %.5f (range [%.5f, %.5f]), per-window spread %.4f.",
                    a.estimate(), a.min(), a.max(), a.relativeSpread()));
        }
        return sb.toString();
    }

    /**
     * 2026-07-04 — auto scale-ladder for verify_growth. Evaluates the {@code scale} growth coordinate
     * of {@code lhs} at a GEOMETRIC ladder of window_starts (1e5, 1e6, … up to the largest decade that
     * fits the trusted range) and reports the per-scale coordinate trajectory + whether the gap to the
     * growth constant is SHRINKING across scales. The growth coordinate approaches its limit only
     * logarithmically (the Wigert coordinate sits ~1.03 vs ln 2 ≈ 0.693 at reachable x), so the
     * cross-scale TREND is the measurement — a rung need not be "converged" for the ladder to be the
     * load-bearing result.
     */
    private String verifyGrowthLadder(String lhs, String scale, int windowSize, int numWindows,
                                      Double target, double tol) {
        long maxN = conjectureVerifier.maxN();
        long work = (long) windowSize * numWindows;
        java.util.List<Long> ladder = new java.util.ArrayList<>();
        for (long s = 100_000L; s + work <= maxN; s *= 10) {
            ladder.add(s);
        }
        if (ladder.isEmpty()) {
            return "verify_growth scale-ladder: no rung fits (window_size × num_windows too large for the range).";
        }
        double[] means = new double[ladder.size()];
        StringBuilder traj = new StringBuilder();
        for (int i = 0; i < ladder.size(); i++) {
            long s = ladder.get(i);
            java.util.List<java.util.Map<String, Double>> ws;
            try {
                ws = conjectureVerifier.sampleWindows(s, windowSize, numWindows);
            } catch (RuntimeException e) {
                return "verify_growth scale-ladder eval failed at window_start=" + s + ": " + e.getMessage();
            }
            double sum = 0.0;
            int n = 0;
            try {
                for (int j = 0; j < ws.size(); j++) {
                    java.util.Map<String, Double> w = ws.get(j);
                    long start = w.getOrDefault("_window_start",
                            (double) (s + (long) j * windowSize)).longValue();
                    sum += com.emergentterrain.research.conjecture.GrowthScale.transform(
                            scale, w.getOrDefault(lhs, 0.0), start);
                    n++;
                }
            } catch (IllegalArgumentException e) {
                return "verify_growth scale-ladder: " + e.getMessage();
            }
            means[i] = n > 0 ? sum / n : Double.NaN;
            traj.append(String.format("%s%d→%.5f", i == 0 ? "" : ", ", s, means[i]));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("verify_growth scale-ladder: %s in %s coords measured across %d scales [%s]. ",
                lhs, scale, ladder.size(), traj));
        if (target != null) {
            double g0 = Math.abs(means[0] - target);
            double gN = Math.abs(means[means.length - 1] - target);
            boolean shrinking = gN < g0;
            sb.append(String.format(
                    "Growth constant %.5f: |gap| %.5f at %d → %.5f at %d — %s. %s",
                    target, g0, ladder.get(0), gN, ladder.get(ladder.size() - 1),
                    shrinking ? "SHRINKING toward the constant across scales" : "NOT shrinking",
                    gN <= Math.abs(target) * tol
                            ? "CONVERGED at the high end (within tol)."
                            : "The tightening TREND across scales is the result — the growth coordinate "
                              + "approaches its constant only logarithmically, so it need not settle even "
                              + "at the ceiling; report the trajectory and rate, not a NULL."));
        } else {
            double hi = means[means.length - 1];
            double prev = means.length > 1 ? means[means.length - 2] : hi;
            sb.append(String.format(
                    "No target — highest-scale coordinate %.5f (vs %.5f one decade lower; %s). Report the "
                            + "cross-scale trajectory as the measurement.",
                    hi, prev, Math.abs(hi - prev) <= Math.abs(hi) * tol ? "settling" : "still moving"));
        }
        return sb.toString();
    }

    /** Shared cap-check + window sampling for the frame-2/3 measurement tools. Returns
     *  {@code [List<Map> windows, Long windowStart, Integer windowSize]} on success, or
     *  {@code [String error]} on a cap/argument failure (mirrors verify_conjecture's caps). */
    private Object[] sampleScalarWindows(JsonNode args) {
        long windowStart = args.path("window_start").asLong(-1);
        int windowSize = args.path("window_size").asInt(-1);
        int numWindows = args.path("num_windows").asInt(
                com.emergentterrain.research.conjecture.ConjectureVerifier.DEFAULT_NUM_WINDOWS);
        if (windowStart < 0 || windowSize <= 0 || numWindows <= 0) {
            return new Object[]{"Invalid window: window_start >= 0, window_size > 0, num_windows > 0 required."};
        }
        long work = (long) windowSize * numWindows;
        if (work > conjectureVerifier.maxWork()) {
            return new Object[]{String.format("WORK cap exceeded: window_size × num_windows = %d > %d. %s",
                    work, conjectureVerifier.maxWork(),
                    capFixHint(windowSize, conjectureVerifier.maxWork()))};
        }
        if (windowStart + work > conjectureVerifier.maxN()) {
            return new Object[]{String.format(
                    "RANGE cap exceeded: window_start + window_size × num_windows = %d > %d (oracle range).",
                    windowStart + work, conjectureVerifier.maxN())};
        }
        java.util.List<java.util.Map<String, Double>> windows;
        try {
            windows = conjectureVerifier.sampleWindows(windowStart, windowSize, numWindows);
        } catch (RuntimeException e) {
            return new Object[]{"window evaluation failed: " + e.getMessage()};
        }
        if (windows.isEmpty()) {
            return new Object[]{"No windows evaluated (range past the oracle limit?)."};
        }
        return new Object[]{windows, windowStart, windowSize};
    }

    /**
     * Phase 24 §24.3 — symbolic_regress tool handler. Validates the
     * sample shape on the JVM side (so a malformed request never
     * burns sidecar time), packages a {@link JuliaMathClient.PysrRequest},
     * and formats the Pareto-front result as a Markdown table for the
     * LLM's tool-result channel.
     *
     * <p>Failure modes are surfaced as plain-text result strings (no
     * exception thrown) so the professor stage's tool loop can read
     * the failure reason and either retry with adjusted args or move
     * on, matching the pattern from runSymbolic / runSandboxedCode.
     */
    private String symbolicRegress(JsonNode args) {
        if (juliaMathClient == null || !juliaMathClient.isPysrAvailable()) {
            return "symbolic_regress not configured: Julia sidecar PySR path is unavailable. "
                    + "Set app.julia.pysr.enabled=true and verify the sidecar is reachable.";
        }
        JsonNode samplesNode = args.path("samples");
        JsonNode varNamesNode = args.path("var_names");
        if (!samplesNode.isArray() || samplesNode.size() == 0) {
            return "Missing 'samples': must be a non-empty array of rows.";
        }
        if (!varNamesNode.isArray() || varNamesNode.size() == 0) {
            return "Missing 'var_names': must be a non-empty array of strings.";
        }
        if (samplesNode.size() > 256) {
            return "Too many samples: " + samplesNode.size() + " rows (max 256). "
                    + "Downsample stratified to ≤ 256 rows before retrying.";
        }
        if (varNamesNode.size() > 6) {
            return "Too many variables: " + varNamesNode.size() + " (max 6). "
                    + "PySR's recovery quality degrades beyond 6 inputs without per-call retuning.";
        }
        java.util.List<String> varNames = new java.util.ArrayList<>();
        for (JsonNode v : varNamesNode) {
            String s = v.asText("");
            if (!s.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return "Invalid var_names entry '" + s + "': must match [A-Za-z_][A-Za-z0-9_]*.";
            }
            varNames.add(s);
        }
        int expectedCols = varNames.size() + 1;
        double[][] samples = new double[samplesNode.size()][];
        for (int i = 0; i < samplesNode.size(); i++) {
            JsonNode row = samplesNode.get(i);
            if (!row.isArray() || row.size() != expectedCols) {
                return "Invalid samples[" + i + "]: must be a numeric row of length "
                        + expectedCols + " (= var_names.size() + 1 for target).";
            }
            double[] coerced = new double[expectedCols];
            for (int j = 0; j < expectedCols; j++) {
                JsonNode cell = row.get(j);
                if (!cell.isNumber()) {
                    return "Invalid samples[" + i + "][" + j + "]: not a number.";
                }
                coerced[j] = cell.asDouble();
            }
            samples[i] = coerced;
        }
        String targetName = args.path("target_name").asText("f");
        java.util.List<String> operators = null;
        JsonNode operatorsNode = args.path("operators");
        if (operatorsNode.isArray() && operatorsNode.size() > 0) {
            operators = new java.util.ArrayList<>();
            for (JsonNode op : operatorsNode) {
                operators.add(op.asText(""));
            }
        }
        com.emergentterrain.llm.JuliaMathClient.PysrRequest request =
                new com.emergentterrain.llm.JuliaMathClient.PysrRequest(
                        samples,
                        java.util.List.copyOf(varNames),
                        targetName,
                        operators == null ? null : java.util.List.copyOf(operators),
                        /*populations*/ 0,
                        /*iterations*/ 0,
                        /*complexityCeiling*/ 0,
                        /*timeoutMs*/ 0,
                        /*seed*/ null);

        com.emergentterrain.llm.JuliaMathClient.PysrResult result;
        try {
            result = juliaMathClient.pysr(request);
        } catch (com.emergentterrain.llm.JuliaMathException e) {
            // `busy` is retryable; surface as a hint. Other codes are
            // terminal — the LLM should adjust args or try another tool.
            if ("busy".equals(e.errorCode())) {
                return "symbolic_regress busy: another /pysr call is in progress. "
                        + "Retry after a short delay or batch this into a later iteration.";
            }
            log.warn("MathToolSuite: symbolic_regress failed code={} msg={}",
                    e.errorCode(), e.getMessage());
            return "symbolic_regress failed (" + e.errorCode() + "): " + e.getMessage();
        }

        if (result.pareto().isEmpty()) {
            return "symbolic_regress: search ran but produced no candidate expressions "
                    + "(evaluated " + result.evaluated() + " members in " + result.elapsedMs() + " ms). "
                    + "Try increasing the operator set or relaxing the complexity ceiling.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("symbolic_regress: Pareto front over ").append(samples.length).append(" samples, ")
          .append(varNames.size()).append(" vars (")
          .append(String.join(", ", varNames)).append(") → ")
          .append(targetName).append(". Evaluated ").append(result.evaluated())
          .append(" members in ").append(result.elapsedMs()).append(" ms.");
        if (result.retried()) {
            // Phase 24 §24.6 Lever 3 surface. The first pass produced a
            // stuck-looking fit; the sidecar re-ran with autoNormalize=true
            // and merged the better entries into the front below.
            sb.append(" autoRetry fired (rescaled-search rescue applied).");
        }
        sb.append("\n\n");
        sb.append("| Complexity | MSE | Expression |\n");
        sb.append("|---|---|---|\n");
        for (com.emergentterrain.llm.JuliaMathClient.ParetoEntry e : result.pareto()) {
            sb.append("| ").append(e.complexity()).append(" | ")
              .append(String.format("%.4g", e.mse())).append(" | `")
              .append(e.expression()).append("` |\n");
        }
        return sb.toString();
    }

    /**
     * Phase 25 §25.3 — prove_identity tool handler. Routes to
     * {@link com.emergentterrain.llm.SympyIdentityClient#prove}, formats
     * the proof outcome (or no-closed-form fallback) as a Markdown-friendly
     * string for the LLM's tool-result channel. A successful proof
     * includes the certificate verbatim so the panel can sanity-check
     * that it's a rational function (phase 25 §25.4's panel check).
     *
     * <p>Failure paths return plain-text result strings — no exception
     * thrown — so the professor stage can read the reason and retry
     * with adjusted args or fall back to surfacing the claim as a
     * CONJECTURE rather than a THEOREM.
     */
    private String proveIdentity(JsonNode args) {
        if (sympyIdentityClient == null || !sympyIdentityClient.isAvailable()) {
            return "prove_identity not configured: SymPy sidecar path is unavailable. "
                    + "Set app.julia.sympy.enabled=true and verify the sidecar is reachable.";
        }
        String algorithm = args.path("algorithm").asText("auto");
        String summand   = args.path("summand").asText("");
        String var       = args.path("var").asText("");
        String lower     = args.path("lower").asText("");
        String upper     = args.path("upper").asText("");
        String target    = args.has("target") ? args.path("target").asText("") : null;

        if (summand.isBlank()) return "Missing 'summand': must be a non-empty SymPy expression.";
        if (var.isBlank())     return "Missing 'var': must be a single identifier (e.g. 'k').";
        if (lower.isBlank())   return "Missing 'lower': must be an integer literal or symbolic expression.";
        if (upper.isBlank())   return "Missing 'upper': must be an integer literal or symbolic expression.";
        if (!var.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return "Invalid 'var' '" + var + "': must match [A-Za-z_][A-Za-z0-9_]*.";
        }
        if (!java.util.Set.of("gosper", "zeilberger_wz", "sister_celine", "auto")
                .contains(algorithm)) {
            return "Invalid 'algorithm' '" + algorithm
                    + "': must be one of gosper, zeilberger_wz, sister_celine, auto.";
        }

        com.emergentterrain.llm.SympyIdentityClient.IdentityProof proof;
        try {
            proof = sympyIdentityClient.prove(algorithm, summand, var, lower, upper,
                    target == null || target.isBlank()
                            ? java.util.Optional.empty()
                            : java.util.Optional.of(target));
        } catch (com.emergentterrain.llm.SympyIdentityException e) {
            // `busy` is retryable; surface as a hint. `safety_reject`
            // means the LLM tried an unsafe expression and should
            // rephrase. Other codes are terminal.
            if ("busy".equals(e.errorCode())) {
                return "prove_identity busy: another /prove_identity call is in progress. "
                        + "Retry after a short delay or move on.";
            }
            if ("safety_reject".equals(e.errorCode())) {
                return "prove_identity safety-rejected: " + e.getMessage()
                        + ". Rephrase the summand without Python-meta tokens.";
            }
            log.warn("MathToolSuite: prove_identity failed code={} msg={}",
                    e.errorCode(), e.getMessage());
            return "prove_identity failed (" + e.errorCode() + "): " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "prove_identity rejected: " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("prove_identity: Σ ").append(summand)
          .append(" for ").append(var).append(" from ").append(lower)
          .append(" to ").append(upper)
          .append(". Sidecar wall ").append(proof.wallMs()).append(" ms. ");
        switch (proof.status()) {
            case PROVED -> {
                sb.append("**PROVED** via ").append(proof.algorithmUsed())
                  .append(".\n\n")
                  .append("Closed form: `").append(proof.result()).append("`\n\n")
                  .append("Certificate: `").append(proof.certificate()).append("`\n\n")
                  .append("This identity is machine-proven and may be cited as ")
                  .append("EvidenceLabel.THEOREM in the audit trail.");
            }
            case RECURRENCE -> {
                sb.append("**RECURRENCE** via ").append(proof.algorithmUsed())
                  .append(". The sum satisfies a polynomial recurrence but ")
                  .append("isn't closed-form.\n\n")
                  .append("Recurrence: `").append(proof.result()).append("`\n\n")
                  .append("Certificate: `").append(proof.certificate()).append("`");
            }
            case NO_CLOSED_FORM -> {
                sb.append("**no_closed_form** via ").append(proof.algorithmUsed())
                  .append(". SymPy could not reduce this sum to a closed ")
                  .append("form via Gosper/WZ. The claim must remain ")
                  .append("CONJECTURE / EMPIRICAL_RESULT unless verified by ")
                  .append("another route.");
            }
            case UNKNOWN -> {
                sb.append("**unknown status** from sidecar (")
                  .append(proof.algorithmUsed()).append("). Treat as no_closed_form.");
            }
        }
        return sb.toString();
    }

    private String runGpuEval(JsonNode args) {
        String formula   = args.path("formula").asText(null);
        int    start     = args.path("start").asInt(Integer.MIN_VALUE);
        int    end       = args.path("end").asInt(Integer.MIN_VALUE);
        String rationale = args.path("rationale").asText("(no rationale)");

        if (formula == null || formula.isBlank()) return "Missing formula.";
        if (start == Integer.MIN_VALUE || end == Integer.MIN_VALUE) {
            return "Missing 'start' or 'end' integer.";
        }
        if (end <= start) {
            return "Invalid range: end must be > start (start=" + start + ", end=" + end + ").";
        }
        long span = (long) end - (long) start;
        if (span > GpuEvalStaticChecker.MAX_RANGE) {
            return "Range too large: " + span + " samples (max "
                    + GpuEvalStaticChecker.MAX_RANGE + ").";
        }

        // Phase 21 §21.5 — `^` → `**` pre-rewrite, same as evaluateSymbolic.
        formula = rewriteCaretToStarStar(formula, "run_gpu_eval");

        // Deterministic Java-side check that mirrors the Rust validate(). Fast
        // feedback to the LLM without paying a gRPC round-trip on a doomed call.
        GpuEvalStaticChecker.Result staticResult = GpuEvalStaticChecker.check(formula);
        if (staticResult instanceof GpuEvalStaticChecker.Result.Rejected r) {
            log.warn("MathToolSuite: run_gpu_eval static-rejected: {} formula=\"{}\"",
                    r.reason(),
                    formula.length() > 80 ? formula.substring(0, 80) + "…" : formula);
            return "REJECTED by safety review: " + r.reason()
                    + " — run_gpu_eval accepts a scalar arithmetic expression in n only "
                    + "(no var/for/if/return, no user functions, only sqrt/log/exp/trig/etc.).";
        }

        if (gpuEngineAdapter == null) {
            return "run_gpu_eval not configured: no GpuEngineAdapter wired into this "
                    + "MathToolSuite. Fall back to run_sandboxed_code for smaller ranges.";
        }

        try {
            long t = System.currentTimeMillis();
            EvalExprRequest req = EvalExprRequest.newBuilder()
                    .setRequestId("prof-gpu-" + System.currentTimeMillis())
                    .setFormula(formula)
                    .setStart(start)
                    .setEnd(end)
                    .build();
            EvalExprResponse resp = gpuEngineAdapter.evalExpr(req);
            log.info("MathToolSuite: run_gpu_eval formula=\"{}\" range=[{},{}) ms={} finite={}/{} mean={} std={}",
                    formula, start, end, System.currentTimeMillis() - t,
                    resp.getFiniteCount(), resp.getN(), resp.getMean(), resp.getStdDev());
            return "GPU eval of f(n)=%s over [%d,%d): mean=%.6g std=%.6g min=%.6g max=%.6g sum=%.6g n=%d finite=%d [Rationale: %s]"
                    .formatted(formula, start, end,
                            resp.getMean(), resp.getStdDev(),
                            resp.getMin(), resp.getMax(), resp.getSum(),
                            resp.getN(), resp.getFiniteCount(), rationale);
        } catch (Exception e) {
            return "GPU eval failed: " + e.getMessage();
        }
    }

    private String runSandboxedCode(PaperDraft draft, JsonNode args) {
        String code        = args.path("code").asText(null);
        String description = args.path("description").asText("(no description)");
        int    rangeStart  = args.path("range_start").asInt(-1);
        int    rangeEnd    = args.path("range_end").asInt(-1);
        if (code == null || code.isBlank()) return "Missing code.";
        // Auto-clamp range_start up to 1 rather than hard-rejecting a 0. The
        // model repeatedly emits range_start=0 (P-a328-930/931/933 lost 5 tool
        // calls to this in a single batch); these scans are 1-indexed so index 0
        // carries no meaning, and the result echoes the actual [start,end) used
        // (see the "over [%d,%d)" below), so the clamp stays visible to the
        // narrator. Only clamp when a sane end exists — a missing/zero end still
        // falls through to the rejection so genuinely empty ranges are caught.
        if (rangeStart < 1 && rangeEnd > 1) rangeStart = 1;
        // Genuinely-empty / inverted ranges still hard-reject.
        if (rangeStart < 1 || rangeEnd <= rangeStart)
            return "Invalid range: must be ≥1 and span ≤ " + MAX_SANDBOX_RANGE;
        // rec #6 (2026-05-28) — auto-clamp an over-cap span instead of hard-rejecting.
        // The model repeatedly emits ranges past the span cap (P-a328-997's call_2
        // requested T=3650–10427 K, span 6777 > 5000, and burned the call). The
        // success string below echoes the actual [start,end) used, so clamping here
        // keeps the computed sub-range visible; we also append an explicit note so
        // the narrator cites the computed range, not the requested one.
        String rangeClampNote = sandboxRangeClampNote(rangeStart, rangeEnd, MAX_SANDBOX_RANGE);
        if (!rangeClampNote.isEmpty()) {
            int requestedEnd = rangeEnd;
            rangeEnd = clampSandboxEnd(rangeStart, rangeEnd, MAX_SANDBOX_RANGE);
            log.info("MathToolSuite: sandbox range auto-clamped requested=[{},{}) → [{},{}) (cap {})",
                    rangeStart, requestedEnd, rangeStart, rangeEnd, MAX_SANDBOX_RANGE);
        }

        // Static check + primary-model intent review before execution.
        String reviewVerdict = reviewCodeIntent(code, description);
        if (!reviewVerdict.startsWith("APPROVED")) {
            log.warn("MathToolSuite: sandbox code rejected by intent review: {} code=\"{}\"",
                    reviewVerdict, code);
            // Phase 10 — append the grammar reference for static-stage
            // rejections so the LLM doesn't burn another 3 retries discovering
            // which JS construct the sandbox subset omits. Intent-stage
            // rejections (model said REJECTED with a sentence) get no hint —
            // those are semantic, not syntactic.
            // Phase 19 Layer 2 — prepend a specific one-line rewrite when
            // the rejection matches a known pattern, before the generic
            // grammar dump. The specific rewrite empirically cuts retry
            // counts more than the grammar dump alone.
            String hint = "";
            if (reviewVerdict.startsWith("REJECTED (static)")) {
                String rewrite = com.emergentterrain.research.SandboxStaticChecker
                        .rewriteHintFor(reviewVerdict);
                hint = (rewrite.isEmpty() ? "" : "\n\n" + rewrite)
                        + com.emergentterrain.research.SandboxStaticChecker.helpText();
            }
            return "REJECTED by safety review: " + reviewVerdict +
                    " — Please revise the pseudocode to address this issue and call run_sandboxed_code again."
                    + hint;
        }
        log.info("MathToolSuite: sandbox code approved, executing range=[{},{}) description=\"{}\" code=\"{}\"",
                rangeStart, rangeEnd, description, code);

        int param1 = draft.param1() != null ? draft.param1() : 42;
        double param2 = draft.param2() != null ? draft.param2() : 1.0;
        try {
            long t = System.currentTimeMillis();
            SandboxEngine.SandboxResult result =
                    SandboxEngine.run(code, rangeStart, rangeEnd, param1, param2);
            log.info("MathToolSuite: sandbox done description=\"{}\" ms={} {}",
                    description, System.currentTimeMillis() - t, result.summary());
            // Phase 10 — surface recordSample aggregates in the tool response.
            // The LLM previously fabricated multi-sample CIs from a single run
            // (P-a328-347, P-a328-348); recordSample lets it return real
            // n/mean/std/min/max alongside the primary numeric result.
            String stats = result.recordedN() > 0
                    ? "  stats: n=%d mean=%.6g std=%.6g min=%.6g max=%.6g".formatted(
                            result.recordedN(), result.recordedMean(),
                            result.recordedStd(), result.recordedMin(),
                            result.recordedMax())
                    : "";
            // Phase 19 Open Q1 — when the Layer-1c rewrite fired on a
            // multi-value return, surface the original key=value pairs so
            // the LLM's downstream prose can name each component instead
            // of having to back the values out of the aggregates.
            String named = "";
            if (!result.namedKeys().isEmpty()) {
                StringBuilder b = new StringBuilder("  named: ");
                for (int i = 0; i < result.namedKeys().size(); i++) {
                    if (i > 0) b.append(", ");
                    b.append(result.namedKeys().get(i)).append('=')
                     .append("%.6g".formatted(result.namedValues().get(i)));
                }
                named = b.toString();
            }
            return "Sandboxed computation — \"%s\" over [%d,%d): %s%s%s%s"
                    .formatted(description, rangeStart, rangeEnd, result.summary(), stats, named, rangeClampNote);
        } catch (Exception e) {
            return "Sandbox execution failed: " + e.getMessage();
        }
    }

    // ── Code review ───────────────────────────────────────────────────────────

    /**
     * Two-stage code review: a deterministic static check (whitelist + parse
     * validity) followed by a narrowed LLM intent check (does the code attempt
     * what the description claims).
     *
     * <p>The static stage replaces what the 4b reviewer was supposed to enforce
     * but could not — a May-2026 corpus run showed the model rejecting 100% of
     * valid sandbox attempts by hallucinating rules (claiming a variable named
     * {@code x} was "string manipulation", denying {@code sigmaSum} despite it
     * being on the whitelist, calling bisection non-convergent). Deterministic
     * checks remove that failure mode.
     *
     * <p>The LLM stage is intentionally advisory: a clear "REJECTED:" prefix
     * blocks execution, but any ambiguity defaults to approve. The runtime
     * sandbox enforces {@link com.emergentterrain.research.SandboxEngine#MAX_ITERATIONS}
     * and {@link com.emergentterrain.research.SandboxEngine#MAX_STMTS} as the
     * load-bearing safety boundary.
     *
     * <p>2026-05-25 — the intent check routes to the <b>primary</b> provider
     * ({@link LlmRouter#callPrimary}), not the fast tier. The small fast-tier
     * model kept false-rejecting valid code (e.g. flagging a fine-grained
     * threshold search as "not a series expansion") — the same hallucinated-
     * rejection failure mode the static stage was added to contain. A stronger
     * primary model judges intent far more reliably; the advisory + default-
     * approve contract still caps the blast radius if it errs.
     */
    private String reviewCodeIntent(String code, String description) {
        // Stage 1 — deterministic static check (parse + whitelist).
        com.emergentterrain.research.SandboxStaticChecker.Result staticResult =
                com.emergentterrain.research.SandboxStaticChecker.check(code);
        if (staticResult instanceof com.emergentterrain.research.SandboxStaticChecker.Result.Rejected r) {
            return "REJECTED (static): " + r.reason();
        }

        // Stage 2 — narrowed LLM intent check. Asks one question only: does the
        // code attempt what the description claims? No rule recitation that the
        // model can hallucinate against. Default-approve on any ambiguity.
        //
        // The verdict line is explicitly pinned to the LAST line: reasoning-
        // capable primary models (claude-sonnet-4-6) think out loud and emit a
        // tentative "REJECTED: …" preamble before self-correcting to "APPROVED"
        // on the final line (see finalIntentVerdict for the 2026-05-29 P-b72c-1
        // case). Pinning the verdict last + parsing the last verdict line makes
        // the conclusion, not the scratch-work, decide.
        String prompt = """
                Does the following pseudocode attempt to compute what the description claims?
                Ignore style, error handling, and convergence theory — judge intent only.
                A bug in the computation is NOT grounds for rejection; reject only if
                the code is unrelated to the described computation.

                Description: %s

                Code:
                %s

                You may reason briefly first, but the LAST line of your reply MUST be
                exactly one of:
                APPROVED
                REJECTED: <one-sentence reason explaining how the code is unrelated to the description>
                """.formatted(description, code);
        try {
            return finalIntentVerdict(router.callPrimary(prompt));
        } catch (Exception e) {
            log.warn("MathToolSuite: intent review failed ({}), approving conservatively", e.getMessage());
            return "APPROVED"; // fail-open: static check + runtime limits are the real safety boundary
        }
    }

    /**
     * Extract the model's FINAL intent verdict from a Stage-2 intent-review
     * reply. Reasoning-capable primary models think out loud — emitting a
     * tentative {@code REJECTED: …} then self-correcting to {@code APPROVED} on
     * the last line. The prior prefix-only parser ({@code startsWith("REJECTED")})
     * keyed off the scratch-work and false-rejected valid code (observed
     * 2026-05-29, P-b72c-1: a correct-intent mean_omega computation rejected
     * because the reply opened with "REJECTED: …" before concluding "APPROVED",
     * costing the paper its only computation → empty_trace suppression).
     *
     * <p>Scans non-blank lines from the bottom and returns at the first whose
     * first token is a verdict: {@code "APPROVED"} or the verbatim
     * {@code REJECTED…} line (its one-sentence reason rides along). Falls back to
     * {@code "APPROVED"} when no verdict line is present — matching the
     * default-approve / fail-open contract (the static check + runtime limits are
     * the load-bearing boundary). Package-private + static for unit testing.
     */
    static String finalIntentVerdict(String response) {
        if (response == null || response.isBlank()) return "APPROVED";
        String[] lines = response.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            String upper = line.toUpperCase(java.util.Locale.ROOT);
            if (upper.startsWith("APPROVED")) return "APPROVED";
            if (upper.startsWith("REJECTED")) return line;
        }
        return "APPROVED";
    }
}
