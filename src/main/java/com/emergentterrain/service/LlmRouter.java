package com.emergentterrain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Routes LLM calls to the appropriate pool based on task quality requirements.
 *
 * Primary pool (Groq when available, else Ollama): high-quality tasks visible to users —
 *   paper narration and genome mentoring.
 *
 * Fast pool (Ollama when available, else primary): low-stakes frequent tasks —
 *   research formulation, refinement decisions, pattern explanation, government digest.
 *
 * Routing rationale: Groq's free tier is rate-limited. The two tasks routed to primary
 * produce text users read; the rest parse a short JSON response where model quality is
 * indistinguishable. Local Gemma3:4b handles the frequent calls at zero quota cost.
 */
@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true", matchIfMissing = false)
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    final LlmPool primaryPool;
    final LlmPool fastPool;

    public LlmRouter(ApplicationContext ctx) {
        Map<String, LlmPool> pools = ctx.getBeansOfType(LlmPool.class);
        if (pools.isEmpty()) throw new IllegalStateException("LlmRouter: no LlmPool beans found");

        LlmPool groq   = pools.get("groqPool");
        LlmPool ollama = pools.get("ollamaPool");

        // Primary: prefer Groq for high-quality narration and mentoring
        this.primaryPool = (groq != null) ? groq : ollama;
        // Fast: prefer Ollama for frequent low-stakes calls (preserves Groq quota)
        this.fastPool = (ollama != null) ? ollama : primaryPool;

        log.info("LlmRouter: primary={} {}  fast={} [{}]",
                primaryPool.getClass().getSimpleName(), primaryPool.selectedModels(),
                fastPool.getClass().getSimpleName(), fastPool.selectedModel());
    }

    /** Synchronous call routed to the primary (high-quality) pool. */
    public String callPrimary(String prompt) {
        return primaryPool.callSync(prompt);
    }

    /** Streaming call routed to the primary pool. */
    public void streamPrimary(String prompt, Consumer<String> tokenConsumer) {
        primaryPool.callStream(prompt, tokenConsumer);
    }

    /** Synchronous call routed to the fast (local) pool. */
    public String callFast(String prompt) {
        return fastPool.callSync(prompt);
    }

    /** Streaming call routed to the fast pool. */
    public void streamFast(String prompt, Consumer<String> tokenConsumer) {
        fastPool.callStream(prompt, tokenConsumer);
    }

    public String primaryModel()  { return primaryPool.selectedModel(); }
    public String fastModel()     { return fastPool.selectedModel(); }
    public int    primaryPoolSize() { return Math.max(1, primaryPool.selectedModels().size()); }

    /** True when the primary pool is Groq and supports multi-turn tool-calling. */
    public boolean supportsProfessorTools() {
        return primaryPool instanceof GroqPool;
    }

    /**
     * Tool-enabled primary call. The professor LLM may invoke tools to gather evidence,
     * then writes a synthesis. Falls back to a plain primary call if Groq is not configured.
     */
    public String callPrimaryWithTools(String systemPrompt, String userPrompt,
                                        String toolsJson,
                                        java.util.function.Function<String, String> executor) {
        if (primaryPool instanceof GroqPool groq) {
            return groq.callWithTools(systemPrompt, userPrompt, toolsJson, executor);
        }
        return primaryPool.callSync(systemPrompt + "\n\n" + userPrompt);
    }
}
