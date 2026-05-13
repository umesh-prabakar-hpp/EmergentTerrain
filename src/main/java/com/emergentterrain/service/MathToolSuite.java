package com.emergentterrain.service;

import com.emergentterrain.research.DynamicComputeEngine;
import com.emergentterrain.research.DynamicProblem;
import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.research.SandboxEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.function.Function;

/**
 * Professor tool suite — four mathematical tools the Groq professor can call
 * during critique to gather experimental evidence before writing its synthesis.
 *
 * Tools:
 *   run_param_sweep      — MathPhysicsEngine at professor-chosen param1/param2
 *   run_predicate_scan   — DynamicComputeEngine number-theoretic predicate scan
 *   evaluate_symbolic    — evaluate f(n) over a range, return statistics
 *   run_sandboxed_code   — run LLM pseudocode reviewed by 4b before execution
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
    public static final class ToolMetrics {
        private int sandboxRejectCount = 0;
        public int sandboxRejectCount() { return sandboxRejectCount; }
        void incrementSandboxRejects()  { sandboxRejectCount++; }
    }

    private static final Set<String> VALID_PREDICATES = Set.of(
            "isPrime", "isHappy", "collatzTerminates", "isAbundant", "isPerfect");
    private static final Set<String> VALID_METRICS = Set.of(
            "fraction_satisfying", "mean_of:collatzSteps", "max_of:collatzSteps", "mean_of:sigmaSum");

    private static final int MAX_SANDBOX_RANGE = 5_000;
    private static final int MAX_SYMBOLIC_RANGE = 1_000;

    private final LlmRouter router;
    private final ObjectMapper mapper;

    public MathToolSuite(LlmRouter router, ObjectMapper mapper) {
        this.router = router;
        this.mapper = mapper;
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
                    "description":"Run custom mathematical pseudocode in a safe sandbox (reviewed by a safety model before execution). Use for computations that don't fit the other tools. Pre-bound variables: start=range_start, end=range_end, param1, param2 (from the paper). Allowed: var, for, if/else, return, and built-ins: isPrime isHappy collatzTerminates isAbundant isPerfect collatzSteps sigmaSum abs sqrt pow floor ceil log min max gcd. No I/O, no imports, no class creation. Max 5000 loop iterations.",
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
                    default -> "Unknown tool: " + name;
                };
                if ("run_sandboxed_code".equals(name) && result.startsWith("REJECTED by safety review")) {
                    metrics.incrementSandboxRejects();
                }
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
            return "REJECTED by safety review: " + reviewVerdict +
                    " — Please revise the pseudocode to address this issue and call run_sandboxed_code again.";
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
            return "Sandboxed computation — \"%s\" over [%d,%d): %s"
                    .formatted(description, rangeStart, rangeEnd, result.summary());
        } catch (Exception e) {
            return "Sandbox execution failed: " + e.getMessage();
        }
    }

    // ── 4b code review ────────────────────────────────────────────────────────

    private String reviewCodeWith4b(String code, String description) {
        String prompt = """
                Review the following mathematical pseudocode for safety and correctness.
                Check:
                1. No file I/O, network calls, class instantiation, imports, or reflection.
                2. No strings, arrays, or object manipulation — only numbers and arithmetic.
                3. Only whitelisted functions: isPrime, isHappy, collatzTerminates, isAbundant,
                   isPerfect, collatzSteps, sigmaSum, abs, sqrt, pow, floor, ceil, log, log2,
                   min, max, gcd, sin, cos, tan, asin, acos, atan, atan2, exp.
                   Math constants PI and E are also available as bare identifiers.
                4. The code logically computes what the description claims.
                5. No infinite loops (loops must have a finite upper bound from 'end').

                Description: %s

                Code:
                %s

                Reply with EXACTLY one of:
                APPROVED
                REJECTED: <one-sentence reason>
                """.formatted(description, code);
        try {
            String response = router.callFast(prompt).trim();
            // Be permissive: only reject on explicit REJECTED
            if (response.toUpperCase().startsWith("REJECTED")) return response;
            return "APPROVED";
        } catch (Exception e) {
            log.warn("MathToolSuite: 4b code review failed ({}), approving conservatively", e.getMessage());
            return "APPROVED"; // fail-open: interpreter itself enforces the hard limits
        }
    }
}
