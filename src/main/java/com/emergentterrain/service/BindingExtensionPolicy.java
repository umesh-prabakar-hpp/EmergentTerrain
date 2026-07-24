package com.emergentterrain.service;

import com.emergentterrain.llm.ToolInvocation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 33 lane D (D1, 2026-07-05) — adaptive round extension on a live result. The tool loop's
 * flat round cap is calibrated for memos; P-b72c-77627788 reached a near-binding hold (margin
 * +0.00002 against a 0.00026 threshold) with no budget left for the single refute call that
 * completes the binding demonstration, and P-774/783 posed follow-ups their own trace had no
 * rounds to run. When the loop is about to end and the accumulated trace shows an INCOMPLETE
 * high-value verification state — a tightly-held bound (small relative slack) with no refuting
 * counterpart anywhere in the trace — grant {@link #extraRounds()} more rounds, once, with an
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

    /** A verification call selected by the instrument itself to complete a binding pair. */
    public record ForcedToolCall(String functionName, String argumentsJson, String intercept) {}

    /** Relative slack below which a held bound counts as tight enough to be worth completing. */
    static final double NEAR_BINDING_REL_SLACK = 0.01;

    @Value("${app.research.professor.extra-rounds-on-binding.extra-rounds:2}")
    private int extraRounds = 2;

    public int extraRounds() {
        return Math.max(1, extraRounds);
    }

    /**
     * True when the trace holds a tightly-held verification (relative slack <
     * {@link #NEAR_BINDING_REL_SLACK}) and NO refuted verdict anywhere — the half-finished
     * binding pair. A trace with a REFUTED already has its demonstration half; a trace with only
     * loose holds has nothing worth extending for.
     */
    public boolean needsBindingCompletion(List<ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        boolean nearBindingHold = false;
        for (ToolInvocation call : trace) {
            String result = call == null ? null : call.resultJson();
            if (result == null) {
                continue;
            }
            if (result.contains(" REFUTED across")) {
                return false;   // the pair's refute half exists — nothing to complete
            }
            if (result.contains(" HELD across") && hasRelSlackBelow(result, NEAR_BINDING_REL_SLACK)) {
                nearBindingHold = true;
            }
        }
        return nearBindingHold;
    }

    /** The hint injected as a user message when the extension is granted. */
    public String extensionHint() {
        // run-26 rec 2 — P-796 spent its extension RE-holding; point at the tool's LITERAL
        // refuting intercept ("TIGHT HOLD — … re-verify with c=…") instead of a vague "tighter".
        return "EXTENSION GRANTED (" + extraRounds() + " extra tool round(s)): your trace contains "
                + "a tightly-held bound (relative slack < " + NEAR_BINDING_REL_SLACK + ") without "
                + "its refuting counterpart — you are ONE call from a DEMONSTRATED binding regime. "
                + "Your tight-hold tool result names the exact refuting intercept (\"TIGHT HOLD — "
                + "… re-verify with c=…\"): make THAT call verbatim. Do NOT verify more holds. "
                + "Then write your synthesis reporting the hold+refute pair.";
    }

    /**
     * Return the exact same-grid refutation call named by a {@code TIGHT HOLD} result, but only
     * while the trace has no actual refutation. The verifier supplies the intercept, so this
     * policy never estimates or invents a counterexample parameter itself.
     */
    public Optional<ForcedToolCall> forcedBindingRefutation(List<ToolInvocation> trace) {
        if (trace == null || trace.isEmpty()) {
            return Optional.empty();
        }
        for (ToolInvocation call : trace) {
            String result = call == null ? null : call.resultJson();
            if (result != null && result.contains(" REFUTED across")) {
                return Optional.empty();
            }
        }
        for (ToolInvocation call : trace) {
            if (call == null || !"verify_conjecture".equals(call.functionName())
                    || call.argsJson() == null || call.resultJson() == null
                    || !call.resultJson().contains(" HELD across")) {
                continue;
            }
            Matcher m = TOOL_NAMED_REFUTATION.matcher(call.resultJson());
            if (m.find()) {
                return Optional.of(new ForcedToolCall(call.functionName(), call.argsJson(), m.group(1)));
            }
        }
        return Optional.empty();
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
}
