package com.emergentterrain.research.conjecture;

import java.util.Random;

/**
 * Phase 30 Wave 6 / 30.7 — mechanism-as-runnable-model (simulation-patterns #3).
 * A declared {@link MechanismSpec} must compile to a runnable predictor that
 * <b>regenerates the claim</b>; only then can the mechanism justify a label above
 * {@code CONJECTURE}. This is the verdict side that replaces "trust the prose."
 *
 * <p>Implemented hooks (self-contained, deterministic):
 * <ul>
 *   <li><b>HEURISTIC_DERIVATION</b> — self-consistency: the heuristic's predicted
 *       constants must match the mined {@code k}/{@code c} within tolerance.</li>
 *   <li><b>PROBABILISTIC_MODEL</b> — seeded Monte-Carlo: simulate the declared
 *       model of the LHS statistic and check the claimed bound holds at the
 *       claimed coverage. (On the mertens statistic this reproduces reality —
 *       the bound's model-coverage is ~99.8% at large window_start and ~12% at
 *       small start, matching "holds at large start, fails at small start".)</li>
 * </ul>
 *
 * <p>The heavier kinds return {@link Status#UNVERIFIABLE} until their hooks ship
 * (a CAS for asymptotic growth-rate comparison, an optimizer for the extremal
 * saturating case, the identity prover for analogy-to-known-theory). This is the
 * honest staged position of Phase 30.7: a self-declared kind is auditable but
 * cannot promote the label until a hook verifies it.
 *
 * <p>Pure, deterministic; not yet wired into the narration/label pipeline.
 */
public final class MechanismVerifier {

    /** Tolerance/coverage slack constants kept explicit for the deterministic hooks. */
    private static final double DEFAULT_REL_TOL = 1e-3;
    private static final int DEFAULT_SAMPLES = 100_000;
    private static final long DEFAULT_SEED = 1729L;
    private static final double COVERAGE_SLACK = 1e-3;

    public enum Status { VERIFIED, REFUTED, UNVERIFIABLE, NOT_APPLICABLE }

    public record Verdict(Status status, String detail) {}

    private MechanismVerifier() {}

    /** Run the declared mechanism against the claim it is meant to explain. */
    public static Verdict verify(MechanismSpec spec, Inequality claim) {
        if (spec == null) {
            return new Verdict(Status.UNVERIFIABLE, "no mechanism spec");
        }
        if (claim == null) {
            return new Verdict(Status.UNVERIFIABLE, "no claim to regenerate");
        }
        return switch (spec.kind()) {
            case HEURISTIC_DERIVATION -> verifyHeuristic(spec, claim);
            case PROBABILISTIC_MODEL -> verifyProbabilistic(spec, claim);
            case NONE -> new Verdict(Status.NOT_APPLICABLE, "no mechanism claimed");
            case ASYMPTOTIC_INTUITION, EXTREMAL_CONSTRUCTION, ANALOGY_TO_KNOWN_THEORY ->
                    new Verdict(Status.UNVERIFIABLE, spec.kind()
                            + " has no runnable hook yet (CAS / optimizer / identity-prover deferred)");
        };
    }

    /** A declared mechanism may justify a label above CONJECTURE only if VERIFIED. */
    public static boolean justifiesPromotion(Verdict verdict) {
        return verdict != null && verdict.status() == Status.VERIFIED;
    }

    private static Verdict verifyHeuristic(MechanismSpec spec, Inequality claim) {
        Double predictedK = spec.param("predictedK");
        Double predictedC = spec.param("predictedC");
        if (predictedK == null || predictedC == null) {
            return new Verdict(Status.UNVERIFIABLE,
                    "heuristic_derivation must declare predictedK and predictedC");
        }
        double tol = spec.param("tolerance", DEFAULT_REL_TOL);
        boolean kOk = within(predictedK, claim.k(), tol);
        boolean cOk = within(predictedC, claim.c(), tol);
        if (kOk && cOk) {
            return new Verdict(Status.VERIFIED, String.format(
                    "heuristic constants match the mined fit (k=%.4f, c=%.4f)", claim.k(), claim.c()));
        }
        return new Verdict(Status.REFUTED, String.format(
                "heuristic predicts k=%.4f,c=%.4f but the mined fit is k=%.4f,c=%.4f",
                predictedK, predictedC, claim.k(), claim.c()));
    }

    private static Verdict verifyProbabilistic(MechanismSpec spec, Inequality claim) {
        Double mu = spec.param("mu");
        Double sigma = spec.param("sigma");
        Double rhsValue = spec.param("rhsValue");
        Double coverage = spec.param("coverage");
        if (mu == null || sigma == null || rhsValue == null || coverage == null) {
            return new Verdict(Status.UNVERIFIABLE,
                    "probabilistic_model must declare mu, sigma, rhsValue and coverage");
        }
        if (sigma < 0) {
            return new Verdict(Status.UNVERIFIABLE, "sigma must be >= 0");
        }
        long seed = (long) spec.param("seed", (double) DEFAULT_SEED);
        int samples = Math.max(1, (int) spec.param("samples", DEFAULT_SAMPLES));
        double bound = claim.k() * rhsValue + claim.c();
        Random rng = new Random(seed);
        int satisfied = 0;
        for (int i = 0; i < samples; i++) {
            double x = mu + sigma * rng.nextGaussian();
            if (x <= bound) {
                satisfied++;
            }
        }
        double simCoverage = (double) satisfied / samples;
        if (simCoverage + COVERAGE_SLACK >= coverage) {
            return new Verdict(Status.VERIFIED, String.format(
                    "model coverage %.4f >= claimed %.4f (bound %.4f regenerated at rhs=%.4f)",
                    simCoverage, coverage, bound, rhsValue));
        }
        return new Verdict(Status.REFUTED, String.format(
                "model coverage %.4f < claimed %.4f — the bound is violated more often than claimed",
                simCoverage, coverage));
    }

    /** |predicted - actual| within a relative tolerance (absolute floor of 1). */
    private static boolean within(double predicted, double actual, double relTol) {
        return Math.abs(predicted - actual) <= relTol * Math.max(1.0, Math.abs(actual));
    }
}
