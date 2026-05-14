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
 * Phase 9 U2 — aggregated registry of every 70B+ tools-capable model across
 * every pool. The substrate {@code LlmRouter.callPrimary*} and
 * {@code ReviewPanelService} migrate onto in Phase 9 U3.
 *
 * <p>At construction we collect every {@link LlmPool} bean where
 * {@link LlmPool#supportsTools()} is true, enumerate each pool's
 * {@link LlmPool#modelIds()}, and build a flat list of {@link ModelHandle}s.
 * The list order matches the {@code app.ai.primary-order} bean iteration —
 * which in turn matches operator config — so reviewer slots and primary
 * fallbacks remain deterministic.
 *
 * <p>The registry does <b>not</b> apply the Phase 7 E2
 * {@code app.ai.tool-calling.providers} filter at construction. That flag is
 * specifically about which providers may receive tool-calling traffic, so it
 * applies only to {@link #callWithTools} consumers and is enforced at the
 * caller in Phase 9 U3 — the panel and the router each filter
 * {@link #availableModels()} according to their own configuration. This keeps
 * the registry's invariant simple: every supportsTools model the JVM knows
 * about is included.
 *
 * <p>Sequential walk semantics — operator direction 2026-05-14. {@link #callSync},
 * {@link #callStream}, {@link #callWithTools} all iterate
 * {@link #availableModels()} in order; on RuntimeException they continue to
 * the next handle; only when every handle has been tried do they re-throw.
 */
@Service
public class PrimaryModelRegistry implements ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(PrimaryModelRegistry.class);

    private final List<ModelHandle> handles;
    /**
     * Phase 9 follow-on (2026-05-14) — round-robin cursor advanced once per
     * {@link #availableModels()} call. Without it, every consumer's
     * first-success-wins dispatch concentrates load on the first registered
     * handle; concurrent narration threads then race past the per-model block
     * check and all hit that one model with simultaneous 429s. Rotating the
     * starting position distributes load across all available handles —
     * 6 concurrent calls on 6 healthy handles end up on 6 different models.
     */
    private final java.util.concurrent.atomic.AtomicInteger rrCursor =
            new java.util.concurrent.atomic.AtomicInteger();

    public PrimaryModelRegistry(ApplicationContext ctx) {
        List<ModelHandle> collected = new ArrayList<>();
        for (LlmPool pool : ctx.getBeansOfType(LlmPool.class).values()) {
            if (!pool.supportsTools()) continue;
            for (String modelId : pool.modelIds()) {
                collected.add(new ModelHandle(pool, modelId));
            }
        }
        this.handles = Collections.unmodifiableList(collected);

        log.info("PrimaryModelRegistry: resolved={} handles=[{}]",
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
        if (out.size() <= 1) return List.copyOf(out);
        // Phase 9 follow-on — rotate the starting position by the cursor so each
        // call sees a different first handle. Sequential walk semantics preserve
        // (every available handle still gets visited in order); the rotation only
        // changes WHICH one is tried first, distributing the first-success-wins
        // load across the pool.
        int rot = Math.abs(rrCursor.getAndIncrement() % out.size());
        if (rot == 0) return List.copyOf(out);
        List<ModelHandle> rotated = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            rotated.add(out.get((i + rot) % out.size()));
        }
        return List.copyOf(rotated);
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
                    return null;  // streaming has no return; dispatch returns void via Void
                });
    }

    @Override
    public ToolCallResponse callWithTools(String systemPrompt, String userPrompt,
                                           String toolsJson,
                                           Function<String, String> executor) {
        return dispatch(
                "callWithTools",
                h -> h.pool().callWithToolsOnModel(
                        h.modelId(), systemPrompt, userPrompt, toolsJson, executor));
    }

    /**
     * Walk {@link #availableModels()} in order, applying {@code action} to each
     * handle. Returns the first non-throwing result. Each RuntimeException is
     * caught, logged, and treated as a signal to continue. When every handle
     * is exhausted, re-throws the last exception (or a clean "no available
     * models" exception when none were eligible at all).
     */
    private <T> T dispatch(String op, Function<ModelHandle, T> action) {
        List<ModelHandle> available = availableModels();
        if (available.isEmpty()) {
            throw new RuntimeException(
                    "PrimaryModelRegistry." + op + ": no available models (all "
                            + handles.size() + " handles blocked)");
        }
        RuntimeException last = null;
        for (ModelHandle h : available) {
            try {
                return action.apply(h);
            } catch (RuntimeException e) {
                last = e;
                log.warn("PrimaryModelRegistry.{}: handle={} failed, trying next: {}",
                        op, h.reviewerId(), e.getMessage());
            }
        }
        throw last;
    }
}
