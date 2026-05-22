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
 *   run_sandboxed_code   — run LLM pseudocode reviewed by 4b before execution
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
    static final java.util.List<String> SUCCESS_PREFIXES = java.util.List.of(
            "Param sweep (",
            "Predicate scan:",
            "Symbolic result for",
            "Symbolic evaluation of",
            "GPU eval of",
            "Sandboxed computation —",
            "verify_conjecture:",
            "symbolic_regress:");

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
        public int sandboxRejectCount()  { return sandboxRejectCount;  }
        public int symbolicRejectCount() { return symbolicRejectCount; }
        public int gpuEvalRejectCount()  { return gpuEvalRejectCount;  }
        public int sandboxSuccessCount() { return sandboxSuccessCount; }
        public int symbolicSuccessCount(){ return symbolicSuccessCount;}
        public int gpuEvalSuccessCount() { return gpuEvalSuccessCount; }
        public int predicateScanSuccessCount() { return predicateScanSuccessCount; }
        public int totalSuccesses() {
            return sandboxSuccessCount + symbolicSuccessCount
                    + gpuEvalSuccessCount + predicateScanSuccessCount;
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
        }
        void incrementGpuEvalSuccesses()    { gpuEvalSuccessCount++;    }
        void incrementPredicateScanSuccesses() { predicateScanSuccessCount++; }
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

    public MathToolSuite(LlmRouter router, ObjectMapper mapper) {
        this(router, mapper, null, null, null);
    }

    public MathToolSuite(LlmRouter router, ObjectMapper mapper, GpuEngineAdapter gpuEngineAdapter) {
        this(router, mapper, gpuEngineAdapter, null, null);
    }

    public MathToolSuite(LlmRouter router, ObjectMapper mapper,
                          GpuEngineAdapter gpuEngineAdapter,
                          com.emergentterrain.llm.JuliaMathClient juliaMathClient) {
        this(router, mapper, gpuEngineAdapter, juliaMathClient, null);
    }

    public MathToolSuite(LlmRouter router, ObjectMapper mapper,
                          GpuEngineAdapter gpuEngineAdapter,
                          com.emergentterrain.llm.JuliaMathClient juliaMathClient,
                          com.emergentterrain.research.conjecture.ConjectureVerifier conjectureVerifier) {
        this.router = router;
        this.mapper = mapper;
        this.gpuEngineAdapter = gpuEngineAdapter;
        this.juliaMathClient = juliaMathClient;
        this.conjectureVerifier = conjectureVerifier;
    }

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
                    "description":"Scan integers in [range_start, range_end) satisfying a number-theoretic predicate and measure a statistic. Use to test whether a specific property holds in a different integer range.",
                    "parameters":{
                      "type":"object",
                      "properties":{
                        "predicate":{"type":"string","description":"Property filter: tokens from isPrime isHappy collatzTerminates isAbundant isPerfect joined with AND or OR"},
                        "range_start":{"type":"integer","description":"Range start (≥1)"},
                        "range_end":{"type":"integer","description":"Range end (range_end − range_start ≤ 500)"},
                        "metric":{"type":"string","description":"One of: fraction_satisfying, mean_of:collatzSteps, max_of:collatzSteps, mean_of:sigmaSum"},
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
                    "description":"Run custom mathematical pseudocode in a safe sandbox (reviewed by a safety model before execution). Use for computations that don't fit the other tools. Pre-bound variables: start=range_start, end=range_end, param1, param2 (from the paper). Allowed: var, for, if/else, return, and built-ins: (a) number theory: isPrime, isHappy, collatzTerminates, isAbundant, isPerfect, collatzSteps, sigmaSum, gcd, mobius, sternBrocotDepthOf, isSquarefree, mertensPartialSum; (b) any java.lang.Math function — abs, sqrt, cbrt, pow, exp, expm1, log, log10, log1p, log2, floor, ceil, rint, signum, hypot, min, max, sin, cos, tan, asin, acos, atan, atan2, sinh, cosh, tanh, toRadians, toDegrees, etc.; (c) SYMBOLIC BRIDGES via Symja under the hood — integrate(expr, var, lo, hi) returns the definite integral (e.g. integrate(sin(x)**2, x, 0, PI) → 1.5708), differentiate(expr, var, at) returns d/dvar[expr] evaluated at the given point (e.g. differentiate(x**3 + 2*x, x, 5) → 77), simplify(expr) returns the simplified value but ONLY if it reduces to a finite number (e.g. simplify(sin(x)**2 + cos(x)**2) → 1). Bridges cannot nest, cannot use sandbox-only builtins (isPrime, gcd, etc.) inside their expressions, and reject unbound free variables. You may also DEFINE helper functions at the top of your code with 'function name(arg1, arg2) { ... return expr; }' and call them later — helpers see their params + globals only (no closures over caller locals), max 64 recursion depth. Pre-bound constants: PI, E, WIEN_B, STEFAN_BOLTZMANN_SIGMA, PLANCK_H, PLANCK_HBAR, BOLTZMANN_K, SPEED_OF_LIGHT, AVOGADRO, ELEMENTARY_CHARGE, GRAVITATIONAL_G, ELECTRON_MASS, PROTON_MASS, VACUUM_PERMITTIVITY, VACUUM_PERMEABILITY (SI units, Codata 2018). No I/O, no imports, no class creation. Max 5000 loop iterations.",
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
                }%s%s]""".formatted(dynamicTool, runSymDesc, runSymExprDesc,
                conjectureVerifier == null ? "" : VERIFY_CONJECTURE_TOOL_JSON,
                (juliaMathClient != null && juliaMathClient.isPysrAvailable())
                        ? SYMBOLIC_REGRESS_TOOL_JSON : "");
    }

    /**
     * Phase 24 §24.3 — symbolic_regress tool advertised when the Julia
     * sidecar is up AND app.julia.pysr.enabled=true. Omitting it
     * entirely from the schema when unavailable means the LLM never
     * sees a tool it can't call, which is cheaper (token-wise) than
     * advertising + rejecting.
     */
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
                    "operators":{"type":"array","items":{"type":"string","enum":["+","-","*","/","^","exp","log","sqrt","abs","sin","cos"]},"description":"Optional. Subset of operators the search is allowed to compose. Default: arithmetic + exp/log."},
                    "rationale":{"type":"string","description":"Why a closed-form fit addresses a specific weakness in the paper (e.g. 'paper observes k between 0.03 and 0.05 across a temperature sweep; check whether a clean Arrhenius form fits')."}
                  },
                  "required":["samples","var_names","rationale"]
                }
              }
            }""";

    private static final String VERIFY_CONJECTURE_TOOL_JSON = """
            ,{
              "type":"function",
              "function":{
                "name":"verify_conjecture",
                "description":"Phase 23.5 — verify a math-domain conjecture of the form `lhs <= k*rhs + c` on a held-out window slice. Pick a window_start the conjecture miner did NOT see (anything outside the prototype's offsets {100, 1000, 5000, 10000, 50000, 100000, 500000, 1000000}). Evaluates num_windows disjoint windows of window_size starting at window_start, computes per-window margin (lhs - (k*rhs + c)), reports held overall (all margins within 2*TOL) or REFUTED with the worst sample as witness. Valid scalar names for lhs/rhs: abundant_density, deficient_density, prime_density, happy_density, mean_omega, mean_Omega, mean_divisor_count, max_divisor_count, mertens_delta, abundant_minus_deficient, omega_Omega_gap, log_window_size, sqrt_window_size, log_window_start, mertens_per_sqrt_W.",
                "parameters":{
                  "type":"object",
                  "properties":{
                    "lhs":{"type":"string","description":"Left-hand-side scalar name (from the vocabulary above)."},
                    "rhs":{"type":"string","description":"Right-hand-side scalar name."},
                    "k":{"type":"number","description":"Multiplicative coefficient on rhs."},
                    "c":{"type":"number","description":"Additive constant."},
                    "window_start":{"type":"integer","description":"First integer in the first evaluated window. Pick a held-out offset."},
                    "window_size":{"type":"integer","description":"Integers per window (window_size * num_windows ≤ 100000)."},
                    "num_windows":{"type":"integer","description":"How many disjoint windows to evaluate (default 5)."}
                  },
                  "required":["lhs","rhs","k","c","window_start","window_size"]
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
                    case "verify_conjecture"  -> verifyConjecture(args);
                    case "symbolic_regress"   -> symbolicRegress(args);
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
                // Phase 23.5 — verify_conjecture is success-counted via the
                // same isSuccessResult path; rejected (LLM-side malformed
                // args) and held/refuted (legitimate outputs) both surface
                // their own descriptors so the D3 gate sees them.
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

    private String runParamSweep(PaperDraft draft, JsonNode args) {
        int    param1   = args.path("param1").asInt(-1);
        double param2   = args.path("param2").asDouble(-1.0);
        String rationale = args.path("rationale").asText("(no rationale)");
        if (param1 < 10 || param1 > 99 || param2 < 1.0 || param2 > 9.0) {
            return "Invalid params: param1 must be 10–99, param2 must be 1.0–9.0.";
        }
        int topicIdx = MathPhysicsEngine.topicIndex(draft.domain(), draft.topicName());
        if (topicIdx < 0) return "Topic not computable via MathPhysicsEngine.";
        try {
            long t = System.currentTimeMillis();
            MathPhysicsEngine.ResearchResult result =
                    MathPhysicsEngine.compute(draft.domain(), topicIdx, param1, param2);
            log.info("MathToolSuite: run_param_sweep domain={} topic={} param1={} param2={} ms={}",
                    draft.domain(), draft.topicName(), param1, param2, System.currentTimeMillis() - t);
            return "Param sweep (param1=%d, param2=%.1f): %s [Rationale: %s]"
                    .formatted(param1, param2, result.findings(), rationale);
        } catch (Exception e) {
            return "Param sweep failed: " + e.getMessage();
        }
    }

    private String runPredicateScan(JsonNode args) {
        String predicate  = args.path("predicate").asText(null);
        int    rangeStart = args.path("range_start").asInt(-1);
        int    rangeEnd   = args.path("range_end").asInt(-1);
        String metric     = args.path("metric").asText("fraction_satisfying");
        double baseline   = args.path("baseline").asDouble(0.0);
        String hypothesis = args.path("hypothesis").asText("");
        if (predicate == null || rangeStart < 1 || rangeEnd <= rangeStart
                || rangeEnd - rangeStart > DynamicComputeEngine.MAX_RANGE) {
            return "Invalid predicate scan: range must be ≥1 and span ≤ " + DynamicComputeEngine.MAX_RANGE;
        }
        for (String token : predicate.replace(" AND ", "|").replace(" OR ", "|").split("\\|")) {
            if (!VALID_PREDICATES.contains(token.trim()))
                return "Invalid predicate token: '" + token.trim() + "'. Use: " + VALID_PREDICATES;
        }
        if (!VALID_METRICS.contains(metric))
            return "Invalid metric '" + metric + "'. Use: " + VALID_METRICS;
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
                com.emergentterrain.llm.JuliaMathClient.SweepResult sr =
                        juliaMathClient.sweep(expression, "n",
                                rangeStart, rangeEnd - 1, samples);
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

    private String runSymbolic(JsonNode args) {
        String expression = args.path("expression").asText(null);
        String rationale  = args.path("rationale").asText("(no rationale)");
        if (expression == null || expression.isBlank()) return "Missing expression.";

        // Phase 22 §22.2 — Julia sidecar delegation. When the
        // JuliaMathClient bean is wired AND reports itself available
        // (config opt-in + /healthz responded at startup), try the
        // sidecar first. On any failure (transport, parse, eval),
        // log at INFO and fall through to the existing Symja path so
        // a sidecar outage never blocks a paper.
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
                com.emergentterrain.llm.JuliaMathClient.EvalResult er =
                        juliaMathClient.eval(expression);
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
        long windowStart = args.path("window_start").asLong(-1);
        int windowSize = args.path("window_size").asInt(-1);
        int numWindows = args.path("num_windows").asInt(
                com.emergentterrain.research.conjecture.ConjectureVerifier.DEFAULT_NUM_WINDOWS);
        if (windowStart < 0 || windowSize <= 0) {
            return "Invalid window: window_start >= 0 and window_size > 0 required.";
        }

        com.emergentterrain.research.conjecture.ConjectureVerifier.VerifyResult result;
        try {
            result = conjectureVerifier.verify(lhs, rhs, k, c, windowStart, windowSize, numWindows);
        } catch (IllegalArgumentException e) {
            return "Invalid verify_conjecture call: " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "verify_conjecture: %s <= %+.4f * %s + %+.4f %s across %d window(s) of size %d starting at %d. ",
                lhs, k, rhs, c,
                result.held() ? "HELD" : "REFUTED",
                result.windowsEvaluated(), windowSize, windowStart));
        sb.append(String.format("worst margin: %+.5f at window starting %d.",
                result.worstMargin(), result.worstWindowStart()));
        sb.append("\nPer-window:");
        for (var w : result.windows()) {
            sb.append(String.format("\n  start=%d  lhs=%+.5f  rhs=%+.5f  margin=%+.5f  %s",
                    w.windowStart(), w.lhsValue(), w.rhsValue(), w.margin(),
                    w.holds() ? "holds" : "REFUTED"));
        }
        return sb.toString();
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
          .append(" members in ").append(result.elapsedMs()).append(" ms.\n\n");
        sb.append("| Complexity | MSE | Expression |\n");
        sb.append("|---|---|---|\n");
        for (com.emergentterrain.llm.JuliaMathClient.ParetoEntry e : result.pareto()) {
            sb.append("| ").append(e.complexity()).append(" | ")
              .append(String.format("%.4g", e.mse())).append(" | `")
              .append(e.expression()).append("` |\n");
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
        if (rangeStart < 1 || rangeEnd <= rangeStart || rangeEnd - rangeStart > MAX_SANDBOX_RANGE)
            return "Invalid range: must be ≥1 and span ≤ " + MAX_SANDBOX_RANGE;

        // 4b safety review before execution
        String reviewVerdict = reviewCodeWith4b(code, description);
        if (!reviewVerdict.startsWith("APPROVED")) {
            log.warn("MathToolSuite: sandbox code rejected by 4b review: {} code=\"{}\"",
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
            return "Sandboxed computation — \"%s\" over [%d,%d): %s%s%s"
                    .formatted(description, rangeStart, rangeEnd, result.summary(), stats, named);
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
     */
    private String reviewCodeWith4b(String code, String description) {
        // Stage 1 — deterministic static check (parse + whitelist).
        com.emergentterrain.research.SandboxStaticChecker.Result staticResult =
                com.emergentterrain.research.SandboxStaticChecker.check(code);
        if (staticResult instanceof com.emergentterrain.research.SandboxStaticChecker.Result.Rejected r) {
            return "REJECTED (static): " + r.reason();
        }

        // Stage 2 — narrowed LLM intent check. Asks one question only: does the
        // code attempt what the description claims? No rule recitation that the
        // model can hallucinate against. Default-approve on any ambiguity.
        String prompt = """
                Does the following pseudocode attempt to compute what the description claims?
                Ignore style, error handling, and convergence theory — judge intent only.

                Description: %s

                Code:
                %s

                Reply with EXACTLY one of:
                APPROVED
                REJECTED: <one-sentence reason explaining how the code is unrelated to the description>
                """.formatted(description, code);
        try {
            String response = router.callFast(prompt).trim();
            if (response.toUpperCase().startsWith("REJECTED")) return response;
            return "APPROVED";
        } catch (Exception e) {
            log.warn("MathToolSuite: intent review failed ({}), approving conservatively", e.getMessage());
            return "APPROVED"; // fail-open: static check + runtime limits are the real safety boundary
        }
    }
}
