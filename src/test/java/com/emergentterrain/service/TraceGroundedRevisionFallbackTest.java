package com.emergentterrain.service;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.NarrationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceGroundedRevisionFallbackTest {

    @Test
    void malformedRevisionWithRefutedTraceUsesExecutedEvidenceInsteadOfFirstPassProse() {
        NarrationResult malformed = ResearchNarrationService.parseNarrationResult(new ObjectMapper(),
                "{\"hypothesis\":\"broken\",\"findings\":[}");
        assertThat(malformed.hypothesis()).isNull();

        NarrationResult firstPass = new NarrationResult(
                "The proposed relationship holds.",
                "No adversarial check has run; the relationship remains intact.",
                "The relationship remains intact.", null, "test-model", 0.8, List.of(),
                null, List.of(), List.of(), null, null, null, null, List.of());
        List<ToolInvocation> trace = List.of(new ToolInvocation(
                "verify_conjecture", "{}",
                "verify_conjecture: x <= y REFUTED across 20 windows; worst margin: +23.95030 "
                        + "at window starting 1565123.", 0, "call_1"));

        NarrationResult fallback = ResearchNarrationService.traceGroundedRevisionFallback(firstPass, trace);

        assertThat(fallback.hypothesis()).isEqualTo("The proposed relationship holds.");
        assertThat(fallback.findings()).contains("[call_1]", "REFUTED", "1565123");
        assertThat(fallback.findings()).doesNotContain("No adversarial check has run");
        assertThat(fallback.conclusion()).contains("REFUTED", "no positive result is claimed");
        assertThat(fallback.toolCallTrace()).containsExactlyElementsOf(trace);
    }

    @Test
    void heldTraceMentioningAReachableRefutationDoesNotBecomeARefutation() {
        NarrationResult firstPass = new NarrationResult(
                "h", "unverified first-pass prose", "unverified conclusion", null, "test-model", 0.8,
                List.of(), null, List.of(), List.of(), null, null, null, null, List.of());
        List<ToolInvocation> trace = List.of(new ToolInvocation(
                "verify_conjecture", "{}",
                "verify_conjecture: x <= y HELD across 20 windows. A HOLD is meaningful and a REFUTED "
                        + "verdict is reachable with the scale-aware rule.", 0, "call_1"));

        NarrationResult fallback = ResearchNarrationService.traceGroundedRevisionFallback(firstPass, trace);

        assertThat(fallback.conclusion()).contains("no supported conclusion or extrapolation is claimed");
        assertThat(fallback.conclusion()).doesNotContain("is REFUTED");
    }
}
