package com.emergentterrain.service;

import com.emergentterrain.llm.ToolInvocation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 33 lane D (D1, 2026-07-05) — adaptive round extension on a live result. The tool loop's
 * flat round cap is calibrated for memos; P-b72c-77627788 reached a near-binding hold (margin
 * +0.00002 against a 0.00026 threshold) with no budget left for the single refute call that
 * completes the binding demonstration, and P-774/783 posed follow-ups their own trace had no
 * rounds to run. When the loop is about to end and the accumulated trace shows an INCOMPLETE
 * high-value verification state — a tightly-held bound (small relative slack) with no refuting
 * counterpart for the same relation and grid — grant {@link #extraRounds()} more rounds, once, with an
 * explicit hint injected so the extension is spent on completing the pair.
 *
 * <p>Detection reads the tool RESULT text the verification tools own (the same stable fragments
 * the sharpness gate keys on: {@code " HELD across"} / {@code " REFUTED across"} /
 * {@code "relative slack: "}), so on tool loops that never ran those tools the policy is inert.
 * Bean exists only under {@code app.research.professor.extra-rounds-on-binding.enabled=true};
 * absent bean = byte-identical loop behavior.
 */
@Component
@ConditionalOnProperty(value = "app.research.professor.extra-rounds-on-binding.enabled",
        havingValue = "true")
public class BindingExtensionPolicy {

    private static final Pattern TOOL_NAMED_REFUTATION = Pattern.compile(
            "TIGHT HOLD[^\\n]*?re-verify with c=([+-]?[0-9]+(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOOL_NAMED_TIGHT_K = Pattern.compile(
            "LOOSE:[^\\n]*?re-verify at k=([+-]?[0-9]+(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A verification call selected by the instrument itself to complete a binding pair. */
    public record ForcedToolCall(String functionName, String argumentsJson, String intercept) {}

    /** A verifier-selected tight-k call that must precede any binding-pair completion. */
    public record ForcedTighteningCall(String functionName, String argumentsJson, String coefficient) {}

    /**
     * Largest relative slack that counts as a tight affine result everywhere in the pipeline.
     * The verifier, binding extension, and publication validators share this inclusive boundary.
     */
    public static final double TIGHT_RELATIVE_SLACK = 0.05;

    @Value("${app.research.professor.extra-rounds-on-binding.extra-rounds:2}")
    private int extraRounds = 2;

    public int extraRounds() {
        return Math.max(1, extraRounds);
    }

    /**
     * True when the trace holds a tightly-held verification (relative slack ≤
     * {@link #TIGHT_RELATIVE_SLACK}) and no refuted verdict for that same relation and grid —
     * the half-finished binding pair. A completed same-grid pair or a trace with only loose holds
     * has nothing worth extending for.
     */
    public boolean needsBindingCompletion(List<ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        for (ToolInvocation call : trace) {
            String result = call == null ? null : call.resultJson();
            if (result == null) {
                continue;
            }
            if (result.contains(" HELD across") && hasRelSlackAtMost(result, TIGHT_RELATIVE_SLACK)) {
                if (!hasSameRelationGridRefutation(call, trace)) return true;
            }
        }
        return false;
    }

    /** The hint injected as a user message when the extension is granted. */
    public String extensionHint() {
        // run-26 rec 2 — P-796 spent its extension RE-holding; point at the tool's LITERAL
        // refuting intercept ("TIGHT HOLD — … re-verify with c=…") instead of a vague "tighter".
        return "EXTENSION GRANTED (" + extraRounds() + " extra tool round(s)): your trace contains "
                + "a tightly-held bound (relative slack ≤ " + TIGHT_RELATIVE_SLACK + ") without "
                + "its refuting counterpart — you are ONE call from a DEMONSTRATED binding regime. "
                + "Your tight-hold tool result names the exact refuting intercept (\"TIGHT HOLD — "
                + "… re-verify with c=…\"): make THAT call verbatim. Do NOT verify more holds. "
                + "Then write your synthesis reporting the hold+refute pair.";
    }

    /**
     * Return the exact same-grid refutation call named by a {@code TIGHT HOLD} result, but only
     * while the trace has no matching same-grid refutation. The verifier supplies the intercept, so this
     * policy never estimates or invents a counterexample parameter itself.
     */
    public Optional<ForcedToolCall> forcedBindingRefutation(List<ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) {
            return Optional.empty();
        }
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_conjecture".equals(call.functionName())
                    || call.argsJson() == null || call.resultJson() == null
                    || !call.resultJson().contains(" HELD across")) {
                continue;
            }
            Matcher m = TOOL_NAMED_REFUTATION.matcher(call.resultJson());
            if (m.find() && !hasSameRelationGridRefutation(call, trace)) {
                return Optional.of(new ForcedToolCall(call.functionName(), call.argsJson(), m.group(1)));
            }
        }
        return Optional.empty();
    }

    /**
     * Return the exact one-term tightening call named by a loose verifier result, unless that
     * exact call is already in the trace.  The tool calculated the coefficient; this policy only
     * carries it into execution.  It deliberately runs before {@link #forcedBindingRefutation}:
     * a loose hold cannot support a binding pair or an independent replication.
     */
    public Optional<ForcedTighteningCall> forcedBindingTightening(List<ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) return Optional.empty();
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_conjecture".equals(call.functionName())
                    || call.argsJson() == null || call.resultJson() == null
                    || !call.resultJson().contains(" HELD across")) continue;
            Matcher match = TOOL_NAMED_TIGHT_K.matcher(call.resultJson());
            if (!match.find()) continue;
            String tightened = withCoefficient(call.argsJson(), match.group(1));
            if (tightened == null || containsExactArguments(trace, tightened)) continue;
            return Optional.of(new ForcedTighteningCall(call.functionName(), tightened, match.group(1)));
        }
        return Optional.empty();
    }

    /**
     * Deterministically lock a second verifier grid before executing it.  The policy keeps the
     * relation, coefficients, intercept, width, and number of windows fixed; only the start moves
     * by a substantial, reproducible separation.  This makes the follow-up independent of the
     * model's post-result choice while retaining an auditable request in the selected trace.
     */
    public static Optional<String> independentReplicationArguments(String heldArgs) {
        if (heldArgs == null || heldArgs.isBlank()) return Optional.empty();
        try {
            JsonNode parsed = JSON.readTree(heldArgs);
            if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode args)) {
                return Optional.empty();
            }
            JsonNode startNode = args.path("window_start");
            JsonNode sizeNode = args.path("window_size");
            JsonNode windowsNode = args.path("num_windows");
            if (!startNode.canConvertToLong() || !sizeNode.canConvertToLong()
                    || !windowsNode.canConvertToInt()) return Optional.empty();
            OptionalLong start = independentWindowStart(startNode.longValue(), sizeNode.longValue(),
                    windowsNode.intValue());
            if (start.isEmpty()) return Optional.empty();
            args.put("window_start", start.getAsLong());
            return Optional.of(JSON.writeValueAsString(args));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** The deterministic start used by both the execution policy and publication gate. */
    public static OptionalLong independentWindowStart(long windowStart, long windowSize, int numWindows) {
        if (windowStart < 0 || windowSize <= 0 || numWindows <= 0) return OptionalLong.empty();
        try {
            long span = Math.multiplyExact(windowSize, (long) numWindows);
            long separatedSpan = Math.multiplyExact(span, 20L);
            long separation = Math.max(separatedSpan, Math.max(1L, windowStart / 2L));
            return OptionalLong.of(Math.addExact(windowStart, separation));
        } catch (ArithmeticException ignored) {
            return OptionalLong.empty();
        }
    }

    private static String withCoefficient(String argsJson, String coefficient) {
        try {
            JsonNode parsed = JSON.readTree(argsJson);
            if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode args)
                    || (!args.path("rhs2").isMissingNode() && !args.path("rhs2").isNull())) {
                return null;
            }
            args.put("k", Double.parseDouble(coefficient));
            return JSON.writeValueAsString(args);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean containsExactArguments(List<ToolInvocation> trace, String argumentsJson) {
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_conjecture".equals(call.functionName())
                    || call.argsJson() == null) continue;
            try {
                if (JSON.readTree(argumentsJson).equals(JSON.readTree(call.argsJson()))) return true;
            } catch (Exception ignored) {
                // A malformed historical invocation cannot satisfy the exact policy call.
            }
        }
        return false;
    }

    private static boolean hasSameRelationGridRefutation(ToolInvocation held, List<ToolInvocation> trace) {
        for (ToolInvocation candidate : trace) {
            if (candidate != null && "verify_conjecture".equals(candidate.functionName())
                    && candidate.resultJson() != null && candidate.resultJson().contains(" REFUTED across")
                    && sameRelationAndGrid(held.argsJson(), candidate.argsJson())) return true;
        }
        return false;
    }

    /** Same bound terms and tested grid; c intentionally may differ. */
    private static boolean sameRelationAndGrid(String heldArgs, String refutedArgs) {
        if (heldArgs == null || refutedArgs == null) return false;
        try {
            JsonNode held = JSON.readTree(heldArgs);
            JsonNode refuted = JSON.readTree(refutedArgs);
            return sameText(held, refuted, "lhs") && sameText(held, refuted, "rhs")
                    && sameOptionalText(held, refuted, "rhs2")
                    && sameNumber(held, refuted, "k") && sameOptionalNumber(held, refuted, "k2")
                    && sameNumber(held, refuted, "window_start")
                    && sameNumber(held, refuted, "window_size")
                    && sameNumber(held, refuted, "num_windows");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean sameText(JsonNode a, JsonNode b, String key) {
        return a.path(key).isTextual() && a.path(key).textValue().equals(b.path(key).textValue());
    }

    private static boolean sameOptionalText(JsonNode a, JsonNode b, String key) {
        return a.path(key).isMissingNode() || a.path(key).isNull()
                ? b.path(key).isMissingNode() || b.path(key).isNull() : sameText(a, b, key);
    }

    private static boolean sameNumber(JsonNode a, JsonNode b, String key) {
        return a.path(key).isNumber() && b.path(key).isNumber()
                && Double.compare(a.path(key).doubleValue(), b.path(key).doubleValue()) == 0;
    }

    private static boolean sameOptionalNumber(JsonNode a, JsonNode b, String key) {
        return a.path(key).isMissingNode() || a.path(key).isNull()
                ? b.path(key).isMissingNode() || b.path(key).isNull() : sameNumber(a, b, key);
    }

    /** Parse "relative slack: X" from a verification result; true iff present and X < threshold. */
    static boolean hasRelSlackBelow(String result, double threshold) {
        int i = result.indexOf("relative slack: ");
        if (i < 0) {
            return false;
        }
        int start = i + "relative slack: ".length();
        int end = start;
        while (end < result.length()
                && (Character.isDigit(result.charAt(end)) || result.charAt(end) == '.')) {
            end++;
        }
        try {
            return end > start && Double.parseDouble(result.substring(start, end)) < threshold;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Parse "relative slack: X" from a verification result; true iff present and X ≤ threshold. */
    static boolean hasRelSlackAtMost(String result, double threshold) {
        int i = result == null ? -1 : result.indexOf("relative slack: ");
        if (i < 0) return false;
        int start = i + "relative slack: ".length();
        int end = start;
        while (end < result.length()
                && (Character.isDigit(result.charAt(end)) || result.charAt(end) == '.')) {
            end++;
        }
        try {
            return end > start && Double.parseDouble(result.substring(start, end)) <= threshold;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
