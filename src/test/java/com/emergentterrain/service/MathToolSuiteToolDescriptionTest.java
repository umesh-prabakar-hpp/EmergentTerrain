package com.emergentterrain.service;

import com.emergentterrain.llm.JuliaMathClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 22 §22.4 — pins the dual-syntax tool description switching
 * in {@link MathToolSuite#toolsJson(boolean)}. When the
 * {@link JuliaMathClient} is null / disabled / unreachable, the
 * Symja-syntax description (Mathematica-flavoured) is served so the
 * LLM emits inputs the Symja path can handle. When the client is
 * available, the Julia-syntax description is served so the LLM
 * emits inputs the sidecar can evaluate.
 *
 * <p>The toolsJson output is asserted as parsed JSON (not raw
 * string match) so cosmetic whitespace doesn't make tests brittle.
 */
class MathToolSuiteToolDescriptionTest {

    private static final ObjectMapper M = new ObjectMapper();

    private MathToolSuite suiteWithJulia(boolean available) {
        JuliaMathClient client = mock(JuliaMathClient.class);
        when(client.isAvailable()).thenReturn(available);
        return new MathToolSuite(
                mock(LlmRouter.class), M, /*gpu*/ null, client);
    }

    private MathToolSuite suiteWithoutJulia() {
        return new MathToolSuite(mock(LlmRouter.class), M, /*gpu*/ null, /*julia*/ null);
    }

    /** Locate the function description by tool name in toolsJson. */
    private static String findToolDescription(String json, String toolName) {
        try {
            JsonNode root = M.readTree(json);
            for (JsonNode tool : root) {
                JsonNode fn = tool.path("function");
                if (toolName.equals(fn.path("name").asText())) {
                    return fn.path("description").asText();
                }
            }
            throw new AssertionError("tool not found: " + toolName);
        } catch (Exception e) {
            throw new AssertionError("toolsJson is not valid JSON: " + json.substring(0, 200), e);
        }
    }

    // ── Symja path (default) ─────────────────────────────────────────

    @Test
    void nullJuliaClient_servesSymjaSyntaxDescriptions() {
        String json = suiteWithoutJulia().toolsJson(true);
        String runSym = findToolDescription(json, "run_symbolic");
        // Sanity — Mathematica-syntax markers should be present.
        assertThat(runSym).contains("Symja");
        assertThat(runSym).contains("Mathematica");
        assertThat(runSym).contains("Solve[");
        assertThat(runSym).contains("Integrate[Sin[x]^2");
        // And the Julia markers should be absent.
        assertThat(runSym).doesNotContain("Symbolics.jl");
    }

    @Test
    void disabledJuliaClient_servesSymjaSyntaxDescriptions() {
        // available=false → Symja path is what the call will hit
        // regardless of why (disabled, unreachable, etc.).
        String json = suiteWithJulia(/*available*/ false).toolsJson(true);
        String runSym = findToolDescription(json, "run_symbolic");
        assertThat(runSym).contains("Mathematica");
        assertThat(runSym).doesNotContain("Symbolics.jl");
    }

    // ── Julia path ───────────────────────────────────────────────────

    @Test
    void availableJuliaClient_servesJuliaSyntaxDescriptions() {
        String json = suiteWithJulia(/*available*/ true).toolsJson(true);
        String runSym = findToolDescription(json, "run_symbolic");
        // Julia markers should be present.
        assertThat(runSym).contains("Symbolics.jl");
        assertThat(runSym).contains("NATIVE JULIA SYNTAX");
        // And the verbose Symja examples gone — no "Solve[" or
        // "Integrate[Sin[x]^2".
        assertThat(runSym).doesNotContain("Integrate[Sin[x]^2");
        assertThat(runSym).doesNotContain("Solve[");
    }

    @Test
    void availableJuliaClient_evaluateSymbolicSwitchesToo() {
        String json = suiteWithJulia(/*available*/ true).toolsJson(true);
        String evalSym = findToolDescription(json, "evaluate_symbolic");
        assertThat(evalSym).contains("Julia");
        assertThat(evalSym).contains("QuadGK");
        // Expression description must say `^` (Julia native) not `**`.
        try {
            JsonNode root = M.readTree(json);
            for (JsonNode tool : root) {
                JsonNode fn = tool.path("function");
                if ("evaluate_symbolic".equals(fn.path("name").asText())) {
                    String exprDesc = fn.path("parameters").path("properties")
                            .path("expression").path("description").asText();
                    assertThat(exprDesc).contains("native-Julia");
                    assertThat(exprDesc).contains("^");
                    assertThat(exprDesc).doesNotContain("**");
                    break;
                }
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── JSON well-formedness ─────────────────────────────────────────

    @Test
    void toolsJson_isAlwaysValidJson_acrossAllJuliaStates() {
        // Smoke check — every combination of julia-available × dynamic
        // tool must produce parse-clean JSON.
        for (boolean dynamic : new boolean[]{true, false}) {
            for (var suite : new MathToolSuite[]{
                    suiteWithoutJulia(),
                    suiteWithJulia(false),
                    suiteWithJulia(true)}) {
                String json = suite.toolsJson(dynamic);
                // Throws if the JSON is malformed.
                try {
                    JsonNode root = M.readTree(json);
                    assertThat(root.isArray()).isTrue();
                    assertThat(root.size()).isGreaterThan(0);
                } catch (Exception e) {
                    throw new AssertionError(
                            "toolsJson(dynamic=" + dynamic + ") produced invalid JSON: " + e.getMessage(), e);
                }
            }
        }
    }

    // ── jsonEncode helper ────────────────────────────────────────────

    @Test
    void jsonEncode_escapesQuotesAndBackslashes() {
        assertThat(MathToolSuite.jsonEncode("hello"))
                .isEqualTo("\"hello\"");
        assertThat(MathToolSuite.jsonEncode("say \"hi\""))
                .isEqualTo("\"say \\\"hi\\\"\"");
        assertThat(MathToolSuite.jsonEncode("path\\to\\file"))
                .isEqualTo("\"path\\\\to\\\\file\"");
        assertThat(MathToolSuite.jsonEncode("line1\nline2"))
                .isEqualTo("\"line1\\nline2\"");
    }

    @Test
    void jsonEncode_nullInput_returnsLiteralNull() {
        // Distinguish "null" the JSON literal from "\"null\"" the
        // string value — caller may want either; we pick null literal
        // because that's the conventional JSON-encode-of-null.
        assertThat(MathToolSuite.jsonEncode(null)).isEqualTo("null");
    }
}
