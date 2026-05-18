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
            "Sandboxed computation —");

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
        void incrementGpuEvalSuccesses()    { gpuEvalSuccessCount++;    }
        void incrementPredicateScanSuccesses() { predicateScanSuccessCount++; }
    }

    private static final Set<String> VALID_PREDICATES = Set.of(
            "isPrime", "isHappy", "collatzTerminates", "isAbundant", "isPerfect");
    private static final Set<String> VALID_METRICS = Set.of(
            "fraction_satisfying", "mean_of:collatzSteps", "max_of:collatzSteps", "mean_of:sigmaSum");

    private static final int MAX_SANDBOX_RANGE = 5_000;
    private static final int MAX_SYMBOLIC_RANGE = 1_000;

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

    public MathToolSuite(LlmRouter router, ObjectMapper mapper) {
        this(router, mapper, null);
    }

    public MathToolSuite(LlmRouter router, ObjectMapper mapper, GpuEngineAdapter gpuEngineAdapter) {
        this.router = router;
        this.mapper = mapper;
        this.gpuEngineAdapter = gpuEngineAdapter;
    }

    // ── Tool definitions JSON ─────────────────────────────────────────────────

    public String toolsJson(boolean includeDynamic) {
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
                    "description":"Evaluate a mathematical expression f(n) for each integer n in [range_start, range_end) and return mean/std/min/max. Use to verify whether a claimed formula is empirically consistent.",
                    "parameters":{
                      "type":"object",
                      "properties":{
                        "expression":{"type":"string","description":"A pure arithmetic expression in n. Allowed: +,-,*,/,**,sqrt,log,abs,floor,ceil,pow,min,max"},
                        "range_start":{"type":"integer","description":"Range start"},
                        "range_end":{"type":"integer","description":"Range end (range_end − range_start ≤ 1000)"},
                        "rationale":{"type":"string","description":"Why this formula test addresses a weakness"}
                      },
                      "required":["expression","range_start","range_end","rationale"]
                    }
                  }
                }""" : "";

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
                    "description":"Evaluate a SYMBOLIC math expression using Symja (Mathematica-like syntax). Use to (a) verify a claimed derivative or integral has the closed form the paper states, (b) check that two expressions are equivalent after simplification, (c) compute a Series expansion at a point, (d) Solve an equation for its symbolic roots, (e) Simplify a long algebraic form. Returns the simplified output AND a numeric approximation if the result is a finite number. Examples: 'Integrate[Sin[x]^2, {x, 0, Pi}]' (→ Pi/2, numeric 1.5708), 'D[x^3 + 2*x, x]' (→ 2 + 3*x^2), 'Solve[x^2 - 5*x + 6 == 0, x]' (→ {{x->2}, {x->3}}), 'Simplify[Sin[x]^2 + Cos[x]^2]' (→ 1), 'Series[Exp[x], {x, 0, 4}]'. Allowed heads include arithmetic, calculus (Integrate, D, Limit, Series, Sum, Product), algebra (Solve, Simplify, Expand, Factor), trig, exp/log, special functions (Gamma, Erf, BesselJ, Zeta), number theory (PrimeQ, MoebiusMu, EulerPhi). REJECTED: I/O (Import, Export, Get, Put), network (URLFetch), process (Run), state mutation (Set, Clear), JVM escape (Java, JavaClass), ToExpression, Compile. Wall-clock cap 3s; iteration cap 100k.",
                    "parameters":{
                      "type":"object",
                      "properties":{
                        "expression":{"type":"string","description":"A Symja expression in full-form or operator-form syntax. Brackets [] for function application; {} for lists."},
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
                }]""".formatted(dynamicTool);
    }

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
        if (rangeStart < 0 || rangeEnd <= rangeStart || rangeEnd - rangeStart > MAX_SYMBOLIC_RANGE)
            return "Invalid range: must be positive and span ≤ " + MAX_SYMBOLIC_RANGE;
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
            return "REJECTED by safety review: " + r.reason()
                    + " — only calculus/algebra/trig/special-function heads are allowed; "
                    + "I/O, process, network, state mutation, and JVM-escape heads are blocked.";
        }

        try {
            long t = System.currentTimeMillis();
            SymbolicEngine.SymbolicResult result = SymbolicEngine.evaluate(expression);
            log.info("MathToolSuite: run_symbolic expr=\"{}\" ms={} numeric={}",
                    expression, System.currentTimeMillis() - t, result.numericApprox());
            String numericNote = result.numericApprox() == null
                    ? ""
                    : "  numeric=%.6g".formatted(result.numericApprox());
            return "Symbolic result for %s: output=%s%s [Rationale: %s]"
                    .formatted(expression, result.output(), numericNote, rationale);
        } catch (Exception e) {
            return "Symbolic evaluation failed: " + e.getMessage();
        }
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
                    reviewVerdict, code.length() > 80 ? code.substring(0, 80) + "…" : code);
            // Phase 10 — append the grammar reference for static-stage
            // rejections so the LLM doesn't burn another 3 retries discovering
            // which JS construct the sandbox subset omits. Intent-stage
            // rejections (model said REJECTED with a sentence) get no hint —
            // those are semantic, not syntactic.
            String hint = reviewVerdict.startsWith("REJECTED (static)")
                    ? com.emergentterrain.research.SandboxStaticChecker.helpText()
                    : "";
            return "REJECTED by safety review: " + reviewVerdict +
                    " — Please revise the pseudocode to address this issue and call run_sandboxed_code again."
                    + hint;
        }
        log.info("MathToolSuite: sandbox code approved, executing range=[{},{}) description=\"{}\"",
                rangeStart, rangeEnd, description);

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
            return "Sandboxed computation — \"%s\" over [%d,%d): %s%s"
                    .formatted(description, rangeStart, rangeEnd, result.summary(), stats);
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
