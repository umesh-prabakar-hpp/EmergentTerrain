package com.emergentterrain.research.recognise;

import java.util.List;
import java.util.Optional;

/**
 * 2026-06-05 review rec #1 — tolerance-based near-match against a curated catalog
 * of named mathematical constants. Exists because the Phase 26 engines (PSLQ/LLL)
 * find EXACT integer relations and therefore — correctly — never match an
 * empirically MINED constant: P-b72c-177's fitted ceiling 1.3128 sat 0.6% from the
 * Hardy–Littlewood twin-prime constant 2C₂ = 1.32032… and no engine could say so.
 *
 * <p>A near-match is NOT an identification — the result text says "numerically
 * close to" with the relative error stated, and the narration hook instructs the
 * model to judge plausibility against its evidence. Ambiguity guard: if two
 * catalog entries fall inside the tolerance, the closer wins but the result names
 * the runner-up so the prose can hedge.
 *
 * <p>Catalog is deliberately small and number-theory-weighted (this fires on
 * conjecture constants from the NT substrate). Pure, stateless, deterministic.
 */
public final class NamedConstantNearMatch {

    private NamedConstantNearMatch() {
    }

    /** Default relative tolerance — mined constants carry fit noise ~1%. */
    public static final double DEFAULT_REL_TOL = 0.015;

    public record Named(String name, String symbol, double value) {
    }

    /**
     * @param relErr    |c − value| / value
     * @param runnerUp  second catalog entry also inside tolerance, or null
     */
    public record Match(Named constant, double relErr, Named runnerUp) {

        /** One-line, honestly-hedged description for prompt/prose use. */
        public String describe(double c) {
            StringBuilder sb = new StringBuilder(String.format(
                    "%.6g is numerically close to %s (%s = %.10g, relative error %.2f%%)",
                    c, constant.name(), constant.symbol(), constant.value(),
                    relErr * 100));
            if (runnerUp != null) {
                sb.append(String.format(" — note %s (%.10g) is also within tolerance",
                        runnerUp.name(), runnerUp.value()));
            }
            return sb.toString();
        }
    }

    /** Number-theory-weighted catalog; values to 10 significant digits. */
    static final List<Named> CATALOG = List.of(
            new Named("the Hardy–Littlewood twin prime constant", "2C_2", 1.320323632),
            new Named("the twin prime constant", "C_2", 0.6601618158),
            new Named("Brun's constant", "B_2", 1.902160583),
            new Named("the Mertens constant", "M", 0.2614972128),
            new Named("the Euler–Mascheroni constant", "gamma", 0.5772156649),
            new Named("Artin's constant", "C_Artin", 0.3739558136),
            new Named("exp(gamma) (Mertens' third theorem)", "e^gamma", 1.781072418),
            new Named("the squarefree density", "6/pi^2", 0.6079271019),
            new Named("zeta(2)", "pi^2/6", 1.644934067),
            new Named("Apery's constant", "zeta(3)", 1.202056903),
            new Named("Catalan's constant", "G", 0.9159655942),
            new Named("the golden ratio", "phi", 1.618033989),
            new Named("pi", "pi", 3.141592654),
            new Named("e", "e", 2.718281828),
            new Named("ln 2", "ln(2)", 0.6931471806));

    /** Nearest catalog entry within {@link #DEFAULT_REL_TOL}, or empty. */
    public static Optional<Match> match(double c) {
        return match(c, DEFAULT_REL_TOL);
    }

    public static Optional<Match> match(double c, double relTol) {
        if (!Double.isFinite(c) || c == 0.0 || relTol <= 0) {
            return Optional.empty();
        }
        Named best = null;
        Named second = null;
        double bestErr = Double.MAX_VALUE;
        double secondErr = Double.MAX_VALUE;
        for (Named n : CATALOG) {
            double err = Math.abs(c - n.value()) / Math.abs(n.value());
            if (err < bestErr) {
                second = best;
                secondErr = bestErr;
                best = n;
                bestErr = err;
            } else if (err < secondErr) {
                second = n;
                secondErr = err;
            }
        }
        if (best == null || bestErr > relTol) {
            return Optional.empty();
        }
        return Optional.of(new Match(best, bestErr, secondErr <= relTol ? second : null));
    }
}
