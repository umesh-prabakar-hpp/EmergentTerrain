package com.emergentterrain.research.conjecture;

/**
 * 2026-06-10 (lever D, frame 3 — super-logarithmic growth scale). The result-frame for a scalar that
 * grows FASTER than any power of log x, so neither a flat feature nor a {@code log_window_start} growth
 * anchor can bound it. The canonical case is {@code max_divisor_count} — the maximal divisor count, which
 * by Wigert/Ramanujan grows like {@code exp(ln2 · ln x / ln ln x)}; equivalently
 * {@code log(d_max)·ln ln x / ln x → ln2 ≈ 0.69315}. The right "result" is the measured GROWTH CONSTANT
 * in the correct coordinates, which is a {@link ConstantConvergence} once the value is transformed.
 *
 * <p>Pure + static (no substrate scalar needed — the transform is a function of the window's lhs value
 * and its start x), so frame 3 reuses the {@code verify_constant} machinery on transformed values rather
 * than adding a Java↔Julia substrate scalar.
 */
public final class GrowthScale {

    private GrowthScale() {}

    /** ln 2 — the Wigert limit of {@code log(max d(n))·ln ln n / ln n}. */
    public static final double WIGERT_LIMIT = Math.log(2.0);

    /**
     * Transform a per-window value into the coordinate whose finite-x value approaches a growth constant.
     * <ul>
     *   <li>{@code "wigert"}: {@code log(value)·ln ln x / ln x} — for a super-log quantity like
     *       {@code max_divisor_count} (limit {@link #WIGERT_LIMIT}).</li>
     *   <li>{@code "loglog"}: {@code value·ln ln x / ln x} — when {@code value} is already a log-scale
     *       quantity.</li>
     * </ul>
     */
    public static double transform(String scale, double value, long x) {
        double lnx = Math.log(Math.max(x, 3.0));
        double lnlnx = Math.log(Math.max(lnx, 1.0 + 1e-9));
        if (lnx <= 0) return 0.0;
        return switch (scale) {
            case "wigert" -> Math.log(Math.max(value, 1.0)) * lnlnx / lnx;
            case "loglog" -> value * lnlnx / lnx;
            default -> throw new IllegalArgumentException("unknown growth scale: " + scale
                    + " (expected 'wigert' or 'loglog')");
        };
    }
}
