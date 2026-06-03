package com.emergentterrain.research.conjecture;

/**
 * Phase 30 Wave 3 — sublinear generation budget (ext #4).
 *
 * <p>Open-ended generation must not outpace validation, or the pool explodes with unvetted
 * conjectures. The budget on newly generated (composed) conjectures per pass is
 * {@code floor(n_validations^α)} with {@code α < 1}: as the cumulative validation work grows, the
 * allowance grows too, but <em>sublinearly</em> — early on (few validations) generation is throttled
 * hard, and it only approaches the configured hard cap once enough validation has accrued.
 *
 * <p>Pure, stateless, deterministic. {@code n_validations} is supplied by the caller (the miner uses
 * the cumulative {@code confirmedRuns} across the live pool — validations that held). The result is
 * clamped to {@code [0, hardCap]} so the existing fixed cap stays an upper ceiling.
 */
public final class SublinearGenerationBudget {

    private SublinearGenerationBudget() {
    }

    /**
     * @param nValidations cumulative validations performed (≥0)
     * @param alpha        exponent in (0,1]; clamped to that range. {@code <1} ⇒ sublinear growth
     * @param hardCap      configured upper ceiling on new conjectures per pass
     * @return {@code min(hardCap, floor(nValidations^alpha))}, never negative
     */
    public static int budget(long nValidations, double alpha, int hardCap) {
        if (nValidations <= 0 || hardCap <= 0) {
            return 0;
        }
        double a = Math.max(0.0, Math.min(1.0, alpha));
        double raw = Math.pow((double) nValidations, a);
        if (!Double.isFinite(raw) || raw < 0) {
            return 0;
        }
        long b = (long) Math.floor(raw);
        return (int) Math.min((long) hardCap, b);
    }
}
