package com.emergentterrain.service;

import com.emergentterrain.llm.ToolCallResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Phase 9 U6 — aggregated registry of every sub-70B / fast-tier model across
 * every pool. The substrate {@code LlmRouter.callFast} / {@code streamFast}
 * migrate onto.
 *
 * <p>Eligibility mirrors {@link PrimaryModelRegistry} but inverted: pools
 * where {@link LlmPool#supportsTools()} is {@code false}. Currently that's
 * just {@link OllamaPool}, but the design accepts any future fast-tier
 * provider (e.g. a Together-AI small-model pool or a NVIDIA sub-70B pool)
 * without further interface changes.
 *
 * <p>Sequential walk semantics — operator direction 2026-05-14, same as the
 * primary registry. {@link #callSync} and {@link #callStream} iterate
 * {@link #availableModels()} in order; on RuntimeException they continue to
 * the next handle; only when every handle has been tried do they re-throw.
 *
 * <p>{@link #callWithTools} throws {@link UnsupportedOperationException} —
 * tool-calling is a primary-tier capability by definition. Callers that need
 * tools must route through {@link PrimaryModelRegistry}.
 */
@Service
public class FastModelRegistry implements ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(FastModelRegistry.class);

    private final List<ModelHandle> handles;

    public FastModelRegistry(ApplicationContext ctx) {
        List<ModelHandle> collected = new ArrayList<>();
        for (LlmPool pool : ctx.getBeansOfType(LlmPool.class).values()) {
            if (pool.supportsTools()) continue;  // primary-tier; skip
            for (String modelId : pool.modelIds()) {
                collected.add(new ModelHandle(pool, modelId));
            }
        }
        this.handles = Collections.unmodifiableList(collected);

        log.info("FastModelRegistry: resolved={} handles=[{}]",
                handles.size(),
                handles.stream().map(ModelHandle::reviewerId).toList());
    }

    @Override
    public List<ModelHandle> allModels() {
        return handles;
    }

    @Override
    public List<ModelHandle> availableModels() {
        List<ModelHandle> out = new ArrayList<>(handles.size());
        for (ModelHandle h : handles) {
            if (!h.isBlocked()) out.add(h);
        }
        return List.copyOf(out);
    }

    @Override
    public String callSync(String prompt) {
        return dispatch(
                "callSync",
                h -> h.pool().callSyncOnModel(h.modelId(), prompt));
    }

    @Override
    public void callStream(String prompt, Consumer<String> tokenConsumer) {
        dispatch(
                "callStream",
                h -> {
                    h.pool().callStreamOnModel(h.modelId(), prompt, tokenConsumer);
                    return null;
                });
    }

    /**
     * Tool-calling is a primary-tier capability by definition; the fast tier's
     * models are explicitly the ones that don't support tools. Callers that
     * need tools must use {@link PrimaryModelRegistry#callWithTools}.
     */
    @Override
    public ToolCallResponse callWithTools(String systemPrompt, String userPrompt,
                                           String toolsJson,
                                           Function<String, String> executor) {
        throw new UnsupportedOperationException(
                "FastModelRegistry: tool-calling is a primary-tier capability — "
                        + "use PrimaryModelRegistry.callWithTools instead");
    }

    private <T> T dispatch(String op, Function<ModelHandle, T> action) {
        List<ModelHandle> available = availableModels();
        if (available.isEmpty()) {
            throw new RuntimeException(
                    "FastModelRegistry." + op + ": no available models (all "
                            + handles.size() + " handles blocked)");
        }
        RuntimeException last = null;
        for (ModelHandle h : available) {
            try {
                return action.apply(h);
            } catch (RuntimeException e) {
                last = e;
                log.warn("FastModelRegistry.{}: handle={} failed, trying next: {}",
                        op, h.reviewerId(), e.getMessage());
            }
        }
        throw last;
    }
}
