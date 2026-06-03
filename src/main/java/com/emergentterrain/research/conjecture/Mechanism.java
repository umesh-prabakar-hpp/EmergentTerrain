package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 30 Wave 6 — 30.7 P1: a mechanism as a runnable model (decisions D1–D4 settled 2026-06-03).
 *
 * <p>A mechanism is an ordered list of quantity <b>definitions</b> over {@link OperatorGrammar.Expr}
 * (D1: in-process, no sandbox) culminating in a <b>target</b> scalar, plus declared invariants (stored
 * here; checked in P2). It lowers to {@link #predict}: evaluate the definitions in order into a working
 * copy of the window, then return the target's value — the model's prediction of the target quantity.
 *
 * <p>The DSL (sim #3) is parsed by {@link #parse}: {@code name := <expr>} lines (expr in the canonical
 * {@link OperatorGrammar} render syntax, parsed by {@link OperatorGrammar#parse}), with
 * {@code predict(TARGET) := <expr>} naming the predicted scalar, {@code invariant: …} lines collected,
 * and {@code #} comments / blanks ignored. Definitions are evaluated in listed order, so a later
 * expression may reference an earlier definition's name as a leaf (intermediates).
 *
 * <p>Pure, stateless, deterministic. No gates here (out-of-sample regeneration, closure, conservation
 * are P2); no authoring/persistence (P3).
 */
public final class Mechanism {

    /** One quantity definition: {@code name := expr}. */
    public record Definition(String name, OperatorGrammar.Expr expr) {
    }

    private final List<Definition> definitions;
    private final String target;
    private final List<String> declaredInvariants;

    public Mechanism(List<Definition> definitions, String target, List<String> declaredInvariants) {
        this.definitions = List.copyOf(definitions);
        this.target = target;
        this.declaredInvariants = declaredInvariants == null ? List.of() : List.copyOf(declaredInvariants);
    }

    public List<Definition> definitions() { return definitions; }
    public String target() { return target; }
    public List<String> declaredInvariants() { return declaredInvariants; }

    /** Names this mechanism produces (its definition outputs) — used by the P2 closure check. */
    public List<String> producedNames() {
        List<String> out = new ArrayList<>(definitions.size());
        for (Definition d : definitions) {
            out.add(d.name());
        }
        return out;
    }

    /**
     * Lower to a prediction: evaluate the definitions in order over a copy of {@code window}, then
     * return the target's value (NaN if the target is undefined or any input is non-finite).
     */
    public double predict(Map<String, Double> window) {
        Map<String, Double> env = new LinkedHashMap<>(window == null ? Map.of() : window);
        for (Definition d : definitions) {
            env.put(d.name(), d.expr().eval(env));
        }
        Double v = env.get(target);
        return v == null ? Double.NaN : v;
    }

    /**
     * Parse the mechanism DSL. Lines: {@code predict(TARGET) := expr} (sets the target),
     * {@code name := expr} (an intermediate), {@code invariant: text} (collected), {@code # comment}
     * and blanks ignored. If no {@code predict(...)} line appears, the last definition is the target.
     */
    public static Mechanism parse(String dsl) {
        List<Definition> defs = new ArrayList<>();
        List<String> invariants = new ArrayList<>();
        String target = null;
        if (dsl != null) {
            for (String raw : dsl.split("\\R")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("invariant:")) {
                    invariants.add(line.substring("invariant:".length()).trim());
                    continue;
                }
                int eq = line.indexOf(":=");
                if (eq < 0) {
                    throw new IllegalArgumentException("not a definition: " + line);
                }
                String lhs = line.substring(0, eq).trim();
                OperatorGrammar.Expr expr = OperatorGrammar.parse(line.substring(eq + 2).trim());
                String name;
                if (lhs.startsWith("predict(") && lhs.endsWith(")")) {
                    name = lhs.substring("predict(".length(), lhs.length() - 1).trim();
                    target = name;
                } else {
                    name = lhs;
                }
                defs.add(new Definition(name, expr));
            }
        }
        if (defs.isEmpty()) {
            throw new IllegalArgumentException("mechanism has no definitions");
        }
        if (target == null) {
            target = defs.get(defs.size() - 1).name();
        }
        return new Mechanism(defs, target, invariants);
    }
}
