package com.emergentterrain.service;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.DirectionOfFindingValidator;
import com.emergentterrain.research.SinglePointSweepValidator;
import com.emergentterrain.research.UncertaintyDisclosureValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Lever 2 — read-only "self-check" tools for the prof→student revision stage.
 *
 * <p>Each tool wraps one of the publish-stage quality validators so the revising
 * LLM can verify its candidate prose <em>before</em> emitting the final JSON,
 * turning a post-publish NEEDS_REVISION demotion into in-loop self-correction:
 * <ul>
 *   <li>{@code check_direction_of_finding} → {@link DirectionOfFindingValidator}
 *       (conclusion-polarity inversion + null-result-vs-trace contradiction)</li>
 *   <li>{@code check_uncertainty_disclosure} → {@link UncertaintyDisclosureValidator}
 *       (unit-anchored numerics with no ± / exact / to-within disclosure)</li>
 *   <li>{@code check_single_point_sweep} → {@link SinglePointSweepValidator}
 *       (sample counts inflated beyond what the tool-call trace supports)</li>
 * </ul>
 *
 * <p>The tools are pure: each takes the prose to check as arguments and returns
 * {@code "<tool>: PASS"} or {@code "<tool>: FAIL — <reasons>"}. They never mutate
 * state and never run computation, so their invocations carry no
 * {@link MathToolSuite#SUCCESS_PREFIXES} marker and must not be folded into the
 * professor trace (whose size feeds {@code SinglePointSweepValidator} at publish).
 *
 * <p>Not a Spring bean — constructed manually by {@link ResearchNarrationService},
 * mirroring {@link MathToolSuite}. No JSON-schema enums (xAI/grok rejects
 * '/'-in-enum; see {@code ReviewPanelService}).
 */
public final class RevisionSelfCheckTools {

    static final String DIRECTION_TOOL  = "check_direction_of_finding";
    static final String UNCERTAINTY_TOOL = "check_uncertainty_disclosure";
    static final String SWEEP_TOOL       = "check_single_point_sweep";

    /** Short system-prompt preamble the revision call prepends when self-check is on. */
    public static final String SELF_CHECK_SYSTEM_PROMPT = """
            You are revising a research paper draft. Before you emit your final JSON, \
            you MUST validate it with the provided self-check tools:
              1. Call check_direction_of_finding with your hypothesis, findings, and conclusion.
              2. Call check_uncertainty_disclosure with your findings.
              3. Call check_single_point_sweep with your findings.
            If any tool returns FAIL, fix the specific issue it names and re-run that \
            check until it returns PASS. Do not invent new numbers or tool calls to pass \
            a check — adjust the prose to honestly reflect the evidence. When every check \
            passes, emit ONLY the final revised JSON object (no prose, no tool calls).""";

    private static final String TOOLS_JSON = """
            [
              {
                "type":"function",
                "function":{
                  "name":"check_direction_of_finding",
                  "description":"Verify that the conclusion's polarity matches the numbers (e.g. does not say 'hypothesis confirmed' when the observed value exceeds the stated bound), and that a null-result conclusion is not contradicted by findings that cite concrete trace numerics. Returns PASS or FAIL with the reason.",
                  "parameters":{
                    "type":"object",
                    "properties":{
                      "hypothesis":{"type":"string","description":"The paper's hypothesis prose."},
                      "findings":{"type":"string","description":"The paper's findings prose."},
                      "conclusion":{"type":"string","description":"The paper's conclusion prose."}
                    },
                    "required":["hypothesis","findings","conclusion"]
                  }
                }
              },
              {
                "type":"function",
                "function":{
                  "name":"check_uncertainty_disclosure",
                  "description":"Verify that findings citing unit-anchored numerics (e.g. 12.3 MPa, 450 K, 0.05 eV) also disclose uncertainty. Accepted forms: '± N' for a quantitative bound, 'exact' / '[exact closed form]' for closed-form recoveries, or 'to within X%'. Returns PASS or FAIL with the reason.",
                  "parameters":{
                    "type":"object",
                    "properties":{
                      "findings":{"type":"string","description":"The paper's findings prose."}
                    },
                    "required":["findings"]
                  }
                }
              },
              {
                "type":"function",
                "function":{
                  "name":"check_single_point_sweep",
                  "description":"Verify that any sample-count claim in the findings (e.g. 'n=40', 'sixteen sampled cases') is supported by the number of successful tool calls actually made. Flags inflated sweep sizes. Returns PASS or FAIL with the reason.",
                  "parameters":{
                    "type":"object",
                    "properties":{
                      "findings":{"type":"string","description":"The paper's findings prose."}
                    },
                    "required":["findings"]
                  }
                }
              }
            ]""";

    private final ObjectMapper mapper;

    public RevisionSelfCheckTools(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String toolsJson() {
        return TOOLS_JSON;
    }

    /**
     * Build the executor. {@code profTrace} is the professor stage's tool-call
     * trace (may be null/empty) — its size drives the direction null-result
     * check and the single-point-sweep allowance. The revision's own self-check
     * calls are NOT part of this trace and must not be appended to it.
     */
    public Function<String, String> buildExecutor(List<ToolInvocation> profTrace) {
        int traceSize = profTrace == null ? 0 : profTrace.size();
        return toolCallJson -> {
            try {
                JsonNode call = mapper.readTree(toolCallJson);
                String name = call.path("name").asText("");
                JsonNode args = call.path("arguments");
                if (args.isTextual()) args = mapper.readTree(args.asText());
                List<String> reasons = switch (name) {
                    case DIRECTION_TOOL -> {
                        String hyp = args.path("hypothesis").asText("");
                        String fnd = args.path("findings").asText("");
                        String con = args.path("conclusion").asText("");
                        Map<Integer, String> fmap = fnd.isBlank() ? Map.of() : Map.of(1, fnd);
                        yield DirectionOfFindingValidator.validate(hyp, fmap, con, traceSize);
                    }
                    case UNCERTAINTY_TOOL ->
                            UncertaintyDisclosureValidator.validate(args.path("findings").asText(""));
                    case SWEEP_TOOL ->
                            SinglePointSweepValidator.validate(args.path("findings").asText(""), profTrace);
                    default -> null;
                };
                if (reasons == null) return "Unknown self-check tool: " + name;
                if (reasons.isEmpty()) return name + ": PASS";
                return name + ": FAIL — " + String.join("; ", reasons);
            } catch (Exception e) {
                return "Self-check tool error: " + e.getMessage();
            }
        };
    }
}
