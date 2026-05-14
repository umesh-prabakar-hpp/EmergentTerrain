package com.emergentterrain.service;

import com.emergentterrain.llm.ToolCallResponse;
import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9 U5 — unit tests for the universal selection-test runner. Each test
 * mocks the dispatch lambdas so the probes hit a deterministic stub instead
 * of a real LLM.
 */
class ModelSelectionTesterTest {

    @Test
    void test_passingChatJsonAndToolModelIsApproved() {
        Function<String, String> sync = prompt -> {
            if (prompt.contains("OK")) return "OK";
            // JSON probe
            return "{\"name\":\"Euler\",\"value\":2.71828,\"good\":true}";
        };
        ModelSelectionTester.ToolDispatch tool = (sys, user, toolsJson, exec) ->
                new ToolCallResponse("ok",
                        List.of(new ToolInvocation("get_value", "{\"key\":\"pi\"}", "3.14", 0)));

        ModelSelectionTester.SelectionResult r =
                ModelSelectionTester.test("test", "test-model", sync, tool);

        assertThat(r.passed()).isTrue();
        assertThat(r.chatMs()).isGreaterThanOrEqualTo(0);
        assertThat(r.jsonMs()).isGreaterThanOrEqualTo(0);
        assertThat(r.toolMs()).isNotNull();
    }

    @Test
    void test_chatProbeMustContainOK() {
        Function<String, String> sync = prompt -> "Hello, I'm a language model.";
        ModelSelectionTester.SelectionResult r =
                ModelSelectionTester.test("test", "test-model", sync, null);
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("did not contain 'OK'");
    }

    @Test
    void test_chatProbeAcceptsCaseInsensitiveOK() {
        Function<String, String> sync = prompt -> {
            if (prompt.contains("Reply")) return "ok!";
            return "{\"name\":\"Euler\",\"value\":2.71828,\"good\":true}";
        };
        ModelSelectionTester.SelectionResult r =
                ModelSelectionTester.test("test", "test-model", sync, null);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void test_jsonProbeAcceptsMarkdownFencedJson() {
        Function<String, String> sync = prompt -> {
            if (prompt.contains("Reply")) return "OK";
            return "Here you go:\n```json\n"
                    + "{\"name\":\"Euler\",\"value\":2.71828,\"good\":true}\n"
                    + "```";
        };
        ModelSelectionTester.SelectionResult r =
                ModelSelectionTester.test("test", "test-model", sync, null);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void test_jsonProbeRejectsMissingFields() {
        Function<String, String> sync = prompt -> {
            if (prompt.contains("Reply")) return "OK";
            return "{\"name\":\"Euler\"}";  // missing value + good
        };
        ModelSelectionTester.SelectionResult r =
                ModelSelectionTester.test("test", "test-model", sync, null);
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("json-probe");
    }

    @Test
    void test_jsonProbeRejectsWrongValue() {
        Function<String, String> sync = prompt -> {
            if (prompt.contains("Reply")) return "OK";
            return "{\"name\":\"Euler\",\"value\":3.14,\"good\":true}";  // wrong value
        };
        ModelSelectionTester.SelectionResult r =
                ModelSelectionTester.test("test", "test-model", sync, null);
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("wrong 'value'");
    }

    @Test
    void test_jsonProbeRejectsFalseGoodField() {
        Function<String, String> sync = prompt -> {
            if (prompt.contains("Reply")) return "OK";
            return "{\"name\":\"Euler\",\"value\":2.71828,\"good\":false}";
        };
        ModelSelectionTester.SelectionResult r =
                ModelSelectionTester.test("test", "test-model", sync, null);
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("'good'");
    }

    @Test
    void test_chatProbeThrownExceptionDropsModel() {
        Function<String, String> sync = prompt -> {
            throw new RuntimeException("429 quota exceeded");
        };
        ModelSelectionTester.SelectionResult r =
                ModelSelectionTester.test("test", "test-model", sync, null);
        assertThat(r.passed()).isFalse();
        assertThat(r.reason()).contains("chat-probe threw").contains("429 quota");
    }

    @Test
    void test_toolProbeRequiresGetValueCallWithKeyPi() {
        Function<String, String> sync = prompt -> {
            if (prompt.contains("Reply")) return "OK";
            return "{\"name\":\"Euler\",\"value\":2.71828,\"good\":true}";
        };
        // Model called the wrong function.
        ModelSelectionTester.ToolDispatch wrongFn = (sys, user, toolsJson, exec) ->
                new ToolCallResponse("ok",
                        List.of(new ToolInvocation("something_else", "{}", "x", 0)));
        ModelSelectionTester.SelectionResult r1 =
                ModelSelectionTester.test("test", "test-model", sync, wrongFn);
        assertThat(r1.passed()).isFalse();
        assertThat(r1.reason()).contains("not 'get_value'");

        // Model called get_value but with wrong key.
        ModelSelectionTester.ToolDispatch wrongKey = (sys, user, toolsJson, exec) ->
                new ToolCallResponse("ok",
                        List.of(new ToolInvocation("get_value", "{\"key\":\"e\"}", "x", 0)));
        ModelSelectionTester.SelectionResult r2 =
                ModelSelectionTester.test("test", "test-model", sync, wrongKey);
        assertThat(r2.passed()).isFalse();
        assertThat(r2.reason()).contains("expected pi");

        // Model didn't call any tool.
        ModelSelectionTester.ToolDispatch noTool = (sys, user, toolsJson, exec) ->
                new ToolCallResponse("I won't use the tool, sorry.", List.of());
        ModelSelectionTester.SelectionResult r3 =
                ModelSelectionTester.test("test", "test-model", sync, noTool);
        assertThat(r3.passed()).isFalse();
        assertThat(r3.reason()).contains("trace was empty");
    }

    @Test
    void test_nonToolPoolSkipsToolProbe() {
        Function<String, String> sync = prompt -> {
            if (prompt.contains("Reply")) return "OK";
            return "{\"name\":\"Euler\",\"value\":2.71828,\"good\":true}";
        };
        ModelSelectionTester.SelectionResult r =
                ModelSelectionTester.test("test", "ollama-model", sync, null);
        assertThat(r.passed()).isTrue();
        assertThat(r.toolMs()).isNull();   // tool probe skipped
    }

    @Test
    void filterPassing_dropsFailedModelsPreservesPassingOrder() {
        List<String> candidates = List.of("good-1", "bad", "good-2");
        java.util.function.BiFunction<String, String, String> sync = (modelId, prompt) -> {
            if (modelId.equals("bad") && prompt.contains("Reply")) return "I don't know";
            if (prompt.contains("Reply")) return "OK";
            return "{\"name\":\"Euler\",\"value\":2.71828,\"good\":true}";
        };
        List<String> passing = ModelSelectionTester.filterPassing("test", candidates, sync, null);
        assertThat(passing).containsExactly("good-1", "good-2");
    }

    @Test
    void filterPassing_emptyCandidatesReturnsEmpty() {
        assertThat(ModelSelectionTester.filterPassing("test", List.of(),
                (m, p) -> "OK", null)).isEmpty();
        assertThat(ModelSelectionTester.filterPassing("test", null,
                (m, p) -> "OK", null)).isEmpty();
    }

    @Test
    void extractJsonObject_handlesProseEmbeddedJson() {
        String resp = "Sure thing — here it is: {\"name\":\"X\"} hope that helps!";
        String extracted = ModelSelectionTester.extractJsonObject(resp);
        assertThat(extracted).isEqualTo("{\"name\":\"X\"}");
    }

    @Test
    void extractJsonObject_handlesNestedBraces() {
        String resp = "Here: {\"outer\":{\"inner\":1}, \"other\":2}";
        String extracted = ModelSelectionTester.extractJsonObject(resp);
        assertThat(extracted).isEqualTo("{\"outer\":{\"inner\":1}, \"other\":2}");
    }

    @Test
    void extractJsonObject_returnsNullWhenNoBraces() {
        assertThat(ModelSelectionTester.extractJsonObject("just prose")).isNull();
    }
}
