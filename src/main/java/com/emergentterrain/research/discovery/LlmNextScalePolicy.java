package com.emergentterrain.research.discovery;

import com.emergentterrain.service.LlmRouter;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 30 frontier-escalation Phase 6 — an LLM-decided step. Given the (x, ratio)
 * history, the headroom margin, and the compute frontier, a frontier model picks
 * the next scale to probe. The prompt is tuned **aggressive** (leap when there's
 * room, never crawl) and explicitly permits a *backward* step to densify/localize
 * when the ratio is climbing. Robust: a garbage/out-of-range reply falls back to
 * the aggressive {@link AdaptiveNextScalePolicy}, so the loop never stalls on a bad
 * generation.
 *
 * <p>Selected by {@code app.research.discovery.frontierScalePolicy=llm}.
 */
@Component
public class LlmNextScalePolicy implements NextScalePolicy {

    private static final long MAX_SCALE = 1_000_000_000_000_000_000L;   // 1e18 sanity cap
    private final LlmRouter router;

    public LlmNextScalePolicy(LlmRouter router) {
        this.router = router;
    }

    @Override
    public long nextScale(NextScaleContext ctx) {
        String reply;
        try {
            reply = router.callPrimary(buildPrompt(ctx));
        } catch (RuntimeException e) {
            reply = null;
        }
        return nextScaleFromReply(reply, ctx);
    }

    /** Parse + clamp the model reply; fall back to the aggressive adaptive policy on anything bad. */
    static long nextScaleFromReply(String reply, NextScaleContext ctx) {
        Long parsed = parseScale(reply);
        if (parsed == null || parsed < 1 || parsed > MAX_SCALE) {
            return new AdaptiveNextScalePolicy().nextScale(ctx);
        }
        return parsed;
    }

    /** Extract the next-scale integer from a reply: prefer the last {@code NeM} form, else the last digit run. */
    static Long parseScale(String reply) {
        if (reply == null) {
            return null;
        }
        String s = reply.replace(",", "").replace("_", "");
        Long sci = null;
        Matcher m = Pattern.compile("(\\d+)\\s*[eE]\\s*\\+?(\\d+)").matcher(s);
        while (m.find()) {
            try {
                long base = Long.parseLong(m.group(1));
                int exp = Integer.parseInt(m.group(2));
                if (exp <= 18) {
                    double v = base * Math.pow(10, exp);
                    if (v >= 1 && v <= 9e18) {
                        sci = (long) v;
                    }
                }
            } catch (RuntimeException ignore) {
                // skip a malformed match
            }
        }
        if (sci != null) {
            return sci;
        }
        Long last = null;
        Matcher d = Pattern.compile("\\d{2,}").matcher(s);   // >=2 digits to skip prose "1"
        while (d.find()) {
            try {
                last = Long.parseLong(d.group());
            } catch (RuntimeException ignore) {
                // overflow on an absurd run — skip
            }
        }
        return last;
    }

    static String buildPrompt(NextScaleContext ctx) {
        StringBuilder h = new StringBuilder();
        for (EscalationCheckpoint c : ctx.history()) {
            if (c.value() != null) {
                h.append(String.format("  x=%d -> value=%d, ratio=%.4f, %s%n",
                        c.x(), c.value(), Math.abs((double) c.value()) / Math.sqrt((double) c.x()),
                        c.verdict().name().toLowerCase()));
            }
        }
        // §10.2 — surface the last failure so the model can pick a REACHABLE x. We inform; we
        // do not clamp — it stays free to leap, or to retry the same x with a better method.
        String failureNote = ctx.lastFailedX() > 0
                ? String.format("IMPORTANT — your previous target x=%d could NOT be computed (%s). The method must "
                        + "run inside a hard memory+time-capped sandbox, so a footprint that grows with x can't reach "
                        + "very large x. Pick a target you can actually reach now (a smaller x with a memory-bounded/"
                        + "segmented method validates and lets the frontier ratchet upward from there). You MAY still "
                        + "leap once you have a method that scales.%n",
                        ctx.lastFailedX(), ctx.lastFailureReason() == null ? "no value" : ctx.lastFailureReason())
                : "";
        return ("""
                You are choosing the NEXT scale x to probe a bound that has held so far. Goal: advance the validated frontier AGGRESSIVELY.
                History (x -> value, ratio = |value|/sqrt(x), verdict):
                %s
                %sCurrent x = %d. Headroom margin = %.3f (1 = far below the bound, 0 = at it). Compute frontier x_c (cost runs out beyond this) = %s.
                Rules:
                - When the bound holds with room (high margin), LEAP: 10x, 100x, or 1000x. Do NOT take timid small steps.
                - You MAY pick a SMALLER x than the current one to densify/localize if the ratio is climbing toward the bound and you want to study the approach — but default to large FORWARD leaps.
                - If the previous target failed to compute, prefer a scale you can reach NOW; do not just re-leap to the same unreachable size.
                - Stay a positive integer; do not exceed ~10x the compute frontier (the loop pivots past it anyway).
                Reply with ONLY the next x as plain digits (no commas, no exponent, no prose). Example: 5000000000
                """).formatted(
                h.length() == 0 ? "  (none yet)" : h.toString(),
                failureNote,
                ctx.currentX(), ctx.margin(),
                ctx.computeFrontierX() > 0 ? String.format("%.3g", ctx.computeFrontierX()) : "unknown");
    }
}
