package com.emergentterrain.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 11 C1 — verify the {@code callId} field is preserved through the
 * backward-compat constructor and through Jackson serialization (the trace is
 * persisted as a JSON blob on the paper record; pre-Phase-11 papers
 * deserialize without the field, post-Phase-11 papers with it).
 */
class ToolInvocationTest {

    @Test
    void backwardCompatConstructor_setsCallIdToNull() {
        ToolInvocation inv = new ToolInvocation("run_sandboxed_code", "{}", "ok", 0);
        assertNull(inv.callId(),
                "Pre-Phase-11 callers without callId should round-trip as null");
        assertEquals("run_sandboxed_code", inv.functionName());
        assertEquals(0, inv.round());
    }

    @Test
    void fiveArgConstructor_preservesCallId() {
        ToolInvocation inv = new ToolInvocation(
                "run_sandboxed_code", "{}", "ok", 0, "call_1");
        assertEquals("call_1", inv.callId());
    }

    @Test
    void jacksonRoundTrip_preservesCallId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ToolInvocation original = new ToolInvocation(
                "run_param_sweep", "{\"param1\":42}", "result", 1, "call_3");
        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"callId\":\"call_3\""),
                "Serialized JSON must include callId field, got: " + json);

        ToolInvocation parsed = mapper.readValue(json, ToolInvocation.class);
        assertEquals(original, parsed);
        assertEquals("call_3", parsed.callId());
    }

    @Test
    void jacksonDeserialize_legacyJsonWithoutCallId_yieldsNullCallId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Pre-Phase-11 persisted shape (no callId field).
        String legacyJson = "{\"functionName\":\"run_param_sweep\","
                + "\"argsJson\":\"{}\",\"resultJson\":\"r\",\"round\":0}";
        ToolInvocation parsed = mapper.readValue(legacyJson, ToolInvocation.class);
        assertNull(parsed.callId(),
                "Legacy persisted papers without callId must deserialize cleanly");
        assertEquals("run_param_sweep", parsed.functionName());
    }

    @Test
    void monotonicCallIdFormat_matchesPoolLoopContract() {
        // The pool loops stamp call IDs as "call_" + (trace.size() + 1).
        // Format contract assertion — if this ever changes, the citation
        // validator's lookup logic must change in lockstep.
        for (int i = 0; i < 5; i++) {
            String expected = "call_" + (i + 1);
            ToolInvocation inv = new ToolInvocation("fn", "{}", "r", 0, expected);
            assertEquals(expected, inv.callId());
            assertTrue(inv.callId().matches("call_\\d+"),
                    "callId must match the pool-stamped format: " + inv.callId());
        }
    }
}
