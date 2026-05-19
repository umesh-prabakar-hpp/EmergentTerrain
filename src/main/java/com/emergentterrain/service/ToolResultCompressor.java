package com.emergentterrain.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 20.1 — condense older {@code role=tool} messages in the in-flight
 * conversation history so the next round's payload doesn't pay full
 * input-token cost for sweep tables and intermediate values the model
 * already consumed.
 *
 * <h2>Scope</h2>
 * Only the conversation history sent to the LLM next round is touched.
 * The {@link com.emergentterrain.llm.LlmTraceWriter} captures every tool
 * call's full raw output independently of this compressor, so the
 * persisted trace and downstream validators (Phase 18.1's trace-scan
 * MagnitudeRangeValidator in particular) see the uncompressed values.
 *
 * <h2>Strategy</h2>
 * On each call to {@link #condenseOlderToolMessages}, walk the messages
 * ArrayNode in order. For every {@code role=tool} message that is NOT
 * the last tool message, if its {@code content} exceeds
 * {@link #thresholdChars}, replace with first {@link #headChars} + a
 * truncation marker + last {@link #tailChars}. The most-recent tool
 * message stays raw — the LLM just finished reading it and needs full
 * fidelity to plan the next move.
 *
 * <h2>Why this matters</h2>
 * Five-round tool-heavy papers accumulate 50-80 KB of tool prose by
 * round 5. With this compressor on, older rounds shrink to ~1 KB each,
 * cutting per-round input by 40-60% on the worst cases. Stacks with
 * Phase 20.3 (cached tool-schema send) and Phase 20.2 (tag-level
 * history compression) — three angles on the same redundant-input
 * problem.
 *
 * <h2>Risk</h2>
 * Validator coupling: if Phase 18.1's MagnitudeRangeValidator scans the
 * model's in-flight messages instead of the persisted trace, compression
 * would hide values it needed. The validator scans the persisted trace
 * (via {@code traceWriter.record}), not {@code messages}, so this risk
 * is mitigated by construction — but verify with the next refresh after
 * flipping the flag on.
 *
 * <h2>Configuration</h2>
 * <pre>
 * app.ai.tool-result-compression:
 *   enabled: false              # default; flip after Phase 18.1 refresh confirms no FP
 *   threshold-chars: 1024       # below this, leave the message alone
 *   head-chars: 500             # keep this much of the start
 *   tail-chars: 200             # keep this much of the end
 * </pre>
 */
@Component
public class ToolResultCompressor {

    private static final Logger log = LoggerFactory.getLogger(ToolResultCompressor.class);

    private final boolean enabled;
    private final int thresholdChars;
    private final int headChars;
    private final int tailChars;

    public ToolResultCompressor(
            @Value("${app.ai.tool-result-compression.enabled:false}") boolean enabled,
            @Value("${app.ai.tool-result-compression.threshold-chars:1024}") int thresholdChars,
            @Value("${app.ai.tool-result-compression.head-chars:500}") int headChars,
            @Value("${app.ai.tool-result-compression.tail-chars:200}") int tailChars) {
        this.enabled = enabled;
        // Defensive clamps — operator typos shouldn't break the LLM call path.
        this.thresholdChars = Math.max(64, thresholdChars);
        this.headChars      = Math.max(32, headChars);
        this.tailChars      = Math.max(0,  tailChars);
        if (enabled) {
            log.info("ToolResultCompressor: enabled threshold={} head={} tail={}",
                    this.thresholdChars, this.headChars, this.tailChars);
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Walk {@code messages} and condense the {@code content} of every
     * {@code role=tool} message EXCEPT the last one whose length exceeds
     * {@link #thresholdChars}. Mutates the input ArrayNode in place
     * (cheaper than rebuilding); returns the same node for chaining.
     * No-op when {@link #enabled} is false.
     */
    public ArrayNode condenseOlderToolMessages(ArrayNode messages) {
        if (!enabled || messages == null) return messages;
        // First pass: locate the index of the last role=tool message.
        int lastToolIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (isToolMessage(messages.get(i))) lastToolIdx = i;
        }
        if (lastToolIdx < 0) return messages;  // nothing to do
        // Second pass: condense older tool messages.
        long bytesSaved = 0;
        int condensed = 0;
        for (int i = 0; i < lastToolIdx; i++) {
            if (!isToolMessage(messages.get(i))) continue;
            ObjectNode node = (ObjectNode) messages.get(i);
            String content = node.path("content").asText("");
            if (content.length() < thresholdChars) continue;
            String shrunk = shrink(content);
            bytesSaved += (content.length() - shrunk.length());
            node.put("content", shrunk);
            condensed++;
        }
        if (condensed > 0) {
            log.debug("ToolResultCompressor: condensed {} older tool messages, bytesSaved={}",
                    condensed, bytesSaved);
        }
        return messages;
    }

    private boolean isToolMessage(com.fasterxml.jackson.databind.JsonNode n) {
        return n != null && n.isObject() && "tool".equals(n.path("role").asText(""));
    }

    private String shrink(String content) {
        int total = content.length();
        if (total <= headChars + tailChars + 64) return content;
        int omitted = total - headChars - tailChars;
        return content.substring(0, headChars)
                + " …<truncated " + omitted + " chars by ToolResultCompressor>… "
                + content.substring(total - tailChars);
    }
}
