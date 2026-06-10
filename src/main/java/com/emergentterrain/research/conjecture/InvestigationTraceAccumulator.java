package com.emergentterrain.research.conjecture;

import com.emergentterrain.llm.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-06-10 (lever F) — the union of professor-stage tool traces across ALL best-of-N candidates of a
 * paper, keyed by paperId.
 *
 * <p>Why: best-of-N narration ({@code app.research.narration.best-of-n}, candidates=3) produces several
 * candidate narrations per draft and {@code selectBest} keeps only the winner; {@code publishPaper}'s
 * discovery→pool write-back then sees only the WINNER's {@code NarrationResult.toolCallTrace()}. A bound
 * a LOSING candidate's professor proved HELD at the failure region — observed 2026-06-10:
 * {@code max_divisor_count ≤ 40·log_window_start + 22}, relative slack ≈0.005, verified at x≈2M — is
 * discarded with that candidate, so the discovery never reaches the pool. The bound's validity is
 * independent of which candidate wins (verify_conjecture is deterministic), so the write-back should see
 * every candidate's verified bounds.
 *
 * <p>Each candidate's professor stage calls {@link #add} as it captures its trace; {@code publishPaper}
 * calls {@link #drain} once and unions the result with the winner's trace before running the write-back.
 * This leaves {@code toolCallTrace()} (which the tool-output-attribution validator checks against the
 * WINNER's prose) untouched — the union is a write-back-only channel.
 *
 * <p>Bounded (insertion-order eviction at {@link #MAX_PAPERS}) so a paper that never reaches
 * {@code publishPaper} (suppressed/abandoned) ages out instead of leaking.
 */
@Component
public class InvestigationTraceAccumulator {

    /** Cap on retained papers; suppressed/never-drained entries age out FIFO. */
    static final int MAX_PAPERS = 256;

    private final Map<String, List<ToolInvocation>> byPaper =
            Collections.synchronizedMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<ToolInvocation>> eldest) {
                    return size() > MAX_PAPERS;
                }
            });

    /** Append one candidate's professor trace to this paper's accumulated union. No-op on empty. */
    public void add(String paperId, List<ToolInvocation> trace) {
        if (paperId == null || paperId.isBlank() || trace == null || trace.isEmpty()) {
            return;
        }
        synchronized (byPaper) {
            byPaper.computeIfAbsent(paperId, k -> new ArrayList<>()).addAll(trace);
        }
    }

    /** Remove and return this paper's accumulated union (never null). */
    public List<ToolInvocation> drain(String paperId) {
        if (paperId == null) {
            return List.of();
        }
        synchronized (byPaper) {
            List<ToolInvocation> t = byPaper.remove(paperId);
            return t == null ? List.of() : t;
        }
    }
}
