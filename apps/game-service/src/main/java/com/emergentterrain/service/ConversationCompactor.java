package com.emergentterrain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 20.2 — tag-level history compaction. Treats the conversation as
 * a tagged stream and degrades gracefully when its total size exceeds the
 * per-pool context-window budget — instead of failing the LLM call
 * outright with a context-window error.
 *
 * <h2>Two-stage compaction</h2>
 * <ol>
 *   <li><b>Stage 1 — content truncation.</b> Walk messages from newest
 *       to oldest. Keep the most recent {@code keepRecent} messages
 *       intact. For older messages whose {@code content} exceeds 256
 *       chars, truncate to first 200 + marker + last 50 chars. Preserves
 *       structural memory (the model still sees there was a turn N
 *       rounds ago, just not the full content) before sacrificing whole
 *       turns.</li>
 *   <li><b>Stage 2 — whole-turn drop.</b> If the conversation is STILL
 *       over budget after stage 1, drop oldest non-system messages one
 *       at a time (preserving system + the most recent {@code keepRecent}).
 *       Last resort — sacrifices structural memory.</li>
 * </ol>
 *
 * <h2>Distinction from Phase 20.1</h2>
 * 20.1 ({@link ToolResultCompressor}) condenses individual tool-result
 * <i>contents</i> (a sweep table inside one tool message). 20.2 condenses
 * the <i>envelope</i> — the per-turn framing of all message kinds
 * (system / user / assistant / tool) for older turns. The two stack:
 * 20.1 keeps each individual result tight; 20.2 shrinks the older
 * envelope when total budget is still over.
 *
 * <h2>When to invoke</h2>
 * Pool's runToolLoop calls this after 20.1's compressor, when the
 * accumulated payload would otherwise exceed the pool's context-window
 * budget. The {@code targetChars} parameter sets the budget (typically
 * ~70% of the pool's context window so the model has headroom for the
 * next round's output).
 *
 * <h2>Configuration</h2>
 * <pre>
 * app.ai.conversation-compaction:
 *   enabled: false              # default; pull when a real paper fails for context-window
 *   target-chars: 24000         # global default; per-pool budget recommended in code
 *   keep-recent: 2              # untouched newest message count
 * </pre>
 */
@Component
public class ConversationCompactor {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompactor.class);

    private static final int  STAGE1_THRESHOLD_CHARS = 256;
    private static final int  STAGE1_HEAD_CHARS      = 200;
    private static final int  STAGE1_TAIL_CHARS      = 50;

    private final boolean enabled;
    private final int defaultTargetChars;
    private final int defaultKeepRecent;

    public ConversationCompactor(
            @Value("${app.ai.conversation-compaction.enabled:false}") boolean enabled,
            @Value("${app.ai.conversation-compaction.target-chars:24000}") int defaultTargetChars,
            @Value("${app.ai.conversation-compaction.keep-recent:2}") int defaultKeepRecent) {
        this.enabled = enabled;
        this.defaultTargetChars = Math.max(2048, defaultTargetChars);
        this.defaultKeepRecent  = Math.max(1, defaultKeepRecent);
        if (enabled) {
            log.info("ConversationCompactor: enabled defaultTargetChars={} defaultKeepRecent={}",
                    this.defaultTargetChars, this.defaultKeepRecent);
        }
    }

    public boolean isEnabled() { return enabled; }
    public int defaultTargetChars() { return defaultTargetChars; }
    public int defaultKeepRecent() { return defaultKeepRecent; }

    /**
     * Compact {@code messages} in place if its total content size exceeds
     * {@code targetChars}. No-op when {@link #enabled} is false. Returns
     * the same node for chaining. Caller picks {@code targetChars} per
     * pool (typically ~70% of context window) and {@code keepRecent}
     * (typically 2).
     */
    public ArrayNode compact(ArrayNode messages, int targetChars, int keepRecent) {
        if (!enabled || messages == null || messages.size() == 0) return messages;
        int targetClamped = Math.max(2048, targetChars);
        int keepClamped   = Math.max(1, keepRecent);

        int totalChars = totalContentChars(messages);
        if (totalChars <= targetClamped) return messages;

        long beforeChars = totalChars;
        int beforeMessages = messages.size();

        // Stage 1 — content truncation.
        int cutoff = Math.max(0, messages.size() - keepClamped);
        for (int i = 0; i < cutoff; i++) {
            JsonNode m = messages.get(i);
            if (!m.isObject()) continue;
            ObjectNode node = (ObjectNode) m;
            String content = node.path("content").asText("");
            if (content.length() < STAGE1_THRESHOLD_CHARS) continue;
            int omitted = content.length() - STAGE1_HEAD_CHARS - STAGE1_TAIL_CHARS;
            String shrunk = content.substring(0, STAGE1_HEAD_CHARS)
                    + " …<truncated " + omitted + " chars by ConversationCompactor stage 1>… "
                    + content.substring(content.length() - STAGE1_TAIL_CHARS);
            node.put("content", shrunk);
        }
        int afterStage1 = totalContentChars(messages);

        // Stage 2 — whole-turn drop. Drop the oldest non-system message
        // until we're under budget or only system + keep-recent remain.
        while (totalContentChars(messages) > targetClamped) {
            int dropIdx = firstNonSystemDroppable(messages, keepClamped);
            if (dropIdx < 0) break;
            messages.remove(dropIdx);
        }
        int afterStage2 = totalContentChars(messages);

        log.debug("ConversationCompactor: compacted from {} chars ({} msgs) → stage1 {} → stage2 {} chars ({} msgs)",
                beforeChars, beforeMessages, afterStage1, afterStage2, messages.size());
        return messages;
    }

    /** Convenience: use the configured defaults. */
    public ArrayNode compact(ArrayNode messages) {
        return compact(messages, defaultTargetChars, defaultKeepRecent);
    }

    private int totalContentChars(ArrayNode messages) {
        int total = 0;
        for (int i = 0; i < messages.size(); i++) {
            JsonNode m = messages.get(i);
            if (m.isObject()) total += m.path("content").asText("").length();
        }
        return total;
    }

    private int firstNonSystemDroppable(ArrayNode messages, int keepRecent) {
        int max = messages.size() - keepRecent;
        for (int i = 0; i < max; i++) {
            JsonNode m = messages.get(i);
            if (!m.isObject()) continue;
            if (!"system".equals(m.path("role").asText(""))) return i;
        }
        return -1;
    }
}
