package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 30 Wave 6 — 30.7 P2: the three mechanism gates, composed over already-built pieces.
 * A {@link Mechanism} is admitted only if it passes all three (decisions D3/D4, 2026-06-03):
 *
 * <ul>
 *   <li><b>Regeneration (sim #3, D3 bound form)</b> — on a held-out window fold, the explained bound
 *       {@code target <= predict + 2*tol} holds on every window. (The mechanism is parameter-free, so
 *       the fit fold has nothing to tune; testing on the held fold checks it generalises to windows the
 *       proposer didn't lean on. Mean and ensemble coincide for a deterministic predict.)</li>
 *   <li><b>Closure (sim #4)</b> — over the mechanism's quantity graph (each definition produces its name
 *       from its expression's operands), every operand is food or produced (no dangling term, via
 *       {@link DerivationClosure#producible}), every non-target produced name is consumed, and the target
 *       is produced.</li>
 *   <li><b>Conservation (sim #7, D4)</b> — dimensional balance (no definition mixes a bounded-NT scalar
 *       with a window-magnitude, reusing {@link OperatorGrammar} 's dimension guard) + declared
 *       monotonicities ({@code monotone_increasing/decreasing(predict in X)} checked by the sign of
 *       Spearman(predict, X)).</li>
 * </ul>
 *
 * <p>Pure, stateless, deterministic. No persistence/authoring (P3).
 */
public final class MechanismGate {

    static final int MIN_HELD = 3;

    private MechanismGate() {
    }

    public record Verdict(boolean regenerates, boolean closes, boolean dimensionallyValid,
                          boolean invariantsHold, boolean admit, String reason) {
    }

    public static Verdict evaluate(Mechanism m, List<Map<String, Double>> samples, Set<String> food, double tol) {
        if (m == null || samples == null) {
            return new Verdict(false, false, false, false, false, "insufficient_input");
        }
        boolean closes = closes(m, food);
        boolean dim = dimensionallyValid(m);
        boolean regen = regenerates(m, samples, tol);
        boolean inv = monotonicitiesHold(m, samples);

        String reason;
        if (!closes) {
            reason = "not_closed";
        } else if (!dim) {
            reason = "dimensional_violation";
        } else if (!inv) {
            reason = "invariant_violation";
        } else if (!regen) {
            reason = "fails_regeneration";
        } else {
            reason = "admit";
        }
        return new Verdict(regen, closes, dim, inv, closes && dim && regen && inv, reason);
    }

    // ---- closure (#4) ----
    static boolean closes(Mechanism m, Set<String> food) {
        Set<String> produced = new LinkedHashSet<>(m.producedNames());
        if (!produced.contains(m.target())) {
            return false;
        }
        DerivationGraph g = new DerivationGraph();
        Set<String> consumed = new LinkedHashSet<>();
        for (Mechanism.Definition d : m.definitions()) {
            List<String> inputs = new ArrayList<>(d.expr().operands());
            consumed.addAll(inputs);
            g.add(new DerivationNode(d.name(), d.name(), inputs));
        }
        // No dangling term: every produced name is grounded (its inputs producible from food).
        Set<String> producible = DerivationClosure.producible(g, food == null ? Set.of() : food, null);
        if (!producible.containsAll(produced)) {
            return false;
        }
        // No un-consumed product: every produced name except the target is read by some definition.
        for (String p : produced) {
            if (!p.equals(m.target()) && !consumed.contains(p)) {
                return false;
            }
        }
        return true;
    }

    // ---- dimensional balance (#7) ----
    static boolean dimensionallyValid(Mechanism m) {
        for (Mechanism.Definition d : m.definitions()) {
            if (OperatorGrammar.mixesIncompatibleDimensions(d.expr())) {
                return false;
            }
        }
        return true;
    }

    // ---- regeneration (#3, bound form on a held-out fold) ----
    static boolean regenerates(Mechanism m, List<Map<String, Double>> samples, double tol) {
        int held = 0;
        for (int i = 0; i < samples.size(); i++) {
            if (i % 2 == 0) {
                continue;   // even = fit fold (unused: parameter-free); odd = held fold
            }
            Map<String, Double> w = samples.get(i);
            Double actual = w.get(m.target());
            double predict = m.predict(w);
            if (actual == null || !Double.isFinite(actual) || !Double.isFinite(predict)) {
                continue;
            }
            held++;
            if (actual > predict + 2 * tol) {
                return false;   // bound violated out-of-sample
            }
        }
        return held >= MIN_HELD;
    }

    // ---- declared monotonicities (#7) ----
    static boolean monotonicitiesHold(Mechanism m, List<Map<String, Double>> samples) {
        for (String inv : m.declaredInvariants()) {
            String t = inv.trim();
            boolean inc = t.startsWith("monotone_increasing(");
            boolean dec = t.startsWith("monotone_decreasing(");
            if (!inc && !dec) {
                continue;   // non-monotonicity invariant (e.g. dimension(...)) handled elsewhere/ignored
            }
            int in = t.indexOf(" in ");
            int close = t.lastIndexOf(')');
            if (in < 0 || close < 0 || close <= in) {
                continue;
            }
            String var = t.substring(in + 4, close).trim();
            List<Double> ps = new ArrayList<>();
            List<Double> xs = new ArrayList<>();
            for (Map<String, Double> w : samples) {
                double p = m.predict(w);
                Double x = w.get(var);
                if (Double.isFinite(p) && x != null && Double.isFinite(x)) {
                    ps.add(p);
                    xs.add(x);
                }
            }
            if (ps.size() < MIN_HELD) {
                continue;   // not enough evidence to refute
            }
            double rho = ConjectureStochasticNull.pearson(
                    ConjectureStochasticNull.ranks(ps), ConjectureStochasticNull.ranks(xs));
            if (inc && rho < 0) {
                return false;
            }
            if (dec && rho > 0) {
                return false;
            }
        }
        return true;
    }
}
