package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Inequality conjecture engine: seed, update (refute/tighten), novelty
 * filter, knob-restatement filter. Stateless; the conjecture store is the
 * caller's {@code List<Inequality>}.
 *
 * <p>Java port of {@code scripts/graffiti/conjectures.py}. Identifier
 * names track the Python verbatim so diffs are useful when debugging
 * parity. Numerical constants below match the Python defaults so the two
 * implementations produce the same survivor set on the same input.
 */
public final class ConjectureEngine {

    /** Slack tolerance. A tick violating {@code lhs <= k*rhs + c} by more than 2*TOL retires the conjecture. */
    public static final double TOL = 0.02;
    /** Confirmed-runs threshold for promotion to "law-like". */
    public static final int PROMOTE_RUNS = 50;
    /** Distinct-configs threshold for promotion. */
    public static final int PROMOTE_CONFIGS = 5;
    /** Pair correlations above this are dropped as near-identities (not conjectures). */
    public static final double NEAR_IDENTITY_CORR = 0.98;

    private static final double[] K_GRID = {
            -2.0, -1.0, -0.5, 0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0
    };

    private ConjectureEngine() {}

    // ---------- seeding ----------

    /**
     * Seed candidate inequalities from one run's samples. For each ordered
     * pair of vocabulary names, fits the tightest (k, c) such that
     * {@code lhs <= k*rhs + c} holds across every sample. Pairs with
     * |pearson| ≥ {@link #NEAR_IDENTITY_CORR} are dropped as near-identities.
     *
     * @param samples list of named-scalar maps (one per sample/window/tick)
     * @param vocabulary the conjecture-eligible scalar names
     * @return seeded inequalities (one per surviving ordered pair)
     */
    public static List<Inequality> seed(List<Map<String, Double>> samples, List<String> vocabulary) {
        List<Inequality> out = new ArrayList<>();
        if (samples.isEmpty()) {
            return out;
        }
        Map<String, double[]> series = new HashMap<>();
        for (String name : vocabulary) {
            double[] arr = new double[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                arr[i] = samples.get(i).getOrDefault(name, 0.0);
            }
            series.put(name, arr);
        }
        for (String lhs : vocabulary) {
            for (String rhs : vocabulary) {
                if (lhs.equals(rhs)) {
                    continue;
                }
                double[] xs = series.get(lhs);
                double[] ys = series.get(rhs);
                if (Math.abs(pearson(xs, ys)) >= NEAR_IDENTITY_CORR) {
                    continue;  // near-identity, not a conjecture
                }
                double[] fit = tightestKC(xs, ys);
                if (fit == null) {
                    continue;
                }
                out.add(new Inequality(lhs, rhs, fit[0], fit[1]));
            }
        }
        return out;
    }

    /**
     * Refute or tighten every conjecture against one run's samples.
     * Mutates {@code store} in place (drops refuted entries, tightens
     * confirmed ones, appends witnesses).
     *
     * @return [confirmed, retired] counts for this run
     */
    public static int[] update(
            List<Inequality> store,
            String runKey,
            String configHash,
            List<Map<String, Double>> samples) {
        int confirmed = 0;
        int retired = 0;
        Iterator<Inequality> it = store.iterator();
        while (it.hasNext()) {
            Inequality ineq = it.next();
            int worstIdx = 0;
            double worst = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < samples.size(); i++) {
                Map<String, Double> s = samples.get(i);
                double lhs = s.getOrDefault(ineq.lhs, 0.0);
                double rhs = s.getOrDefault(ineq.rhs, 0.0);
                double margin = lhs - (ineq.k * rhs + ineq.c);
                if (margin > worst) {
                    worst = margin;
                    worstIdx = i;
                }
            }
            if (worst > 2 * TOL) {
                long tickAtWorst = samples.get(worstIdx).getOrDefault("_tick", (double) worstIdx).longValue();
                ineq.refutedBy.add(new Witness(runKey, tickAtWorst, worst));
                it.remove();
                retired++;
                continue;
            }
            // Confirmed: tighten c toward observed slack (half the headroom).
            if (worst < 0) {
                ineq.c = ineq.c + worst * 0.5;
            }
            ineq.confirmedRuns++;
            if (!ineq.confirmedConfigs.contains(configHash)) {
                ineq.confirmedConfigs.add(configHash);
            }
            ineq.p99Slack = Math.max(ineq.p99Slack, worst);
            ineq.slackByConfig.merge(configHash, worst, Math::max);
            confirmed++;
        }
        return new int[]{confirmed, retired};
    }

    /**
     * Drop conjectures whose per-config slack moves monotonically with a
     * swept knob. Such conjectures are restating the knob, not describing
     * the simulation's invariants.
     *
     * @param store mutated in place
     * @param axes knob axes (axis name + map of configHash → knob value)
     * @param threshold |Spearman| above which a conjecture is treated as a knob-restatement
     * @param minConfigs minimum number of distinct configs needed to evaluate
     * @return number dropped
     */
    public static int filterKnobRestatements(
            List<Inequality> store,
            List<KnobAxis> axes,
            double threshold,
            int minConfigs) {
        if (axes.isEmpty()) {
            return 0;
        }
        int dropped = 0;
        Iterator<Inequality> it = store.iterator();
        while (it.hasNext()) {
            Inequality ineq = it.next();
            String culprit = null;
            for (KnobAxis axis : axes) {
                List<Double> xs = new ArrayList<>();
                List<Double> ys = new ArrayList<>();
                for (Map.Entry<String, Double> e : axis.configValues().entrySet()) {
                    Double slack = ineq.slackByConfig.get(e.getKey());
                    if (slack != null) {
                        xs.add(e.getValue());
                        ys.add(slack);
                    }
                }
                if (xs.size() >= minConfigs && Math.abs(spearman(xs, ys)) >= threshold) {
                    culprit = axis.knob();
                    break;
                }
            }
            if (culprit != null) {
                ineq.droppedByKnob = culprit;
                it.remove();
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * Promoted = ≥ {@link #PROMOTE_RUNS} confirmed runs across ≥
     * {@link #PROMOTE_CONFIGS} distinct config hashes.
     */
    public static List<Inequality> promoted(List<Inequality> store) {
        List<Inequality> out = new ArrayList<>();
        for (Inequality c : store) {
            if (c.confirmedRuns >= PROMOTE_RUNS
                    && c.confirmedConfigs.size() >= PROMOTE_CONFIGS) {
                out.add(c);
            }
        }
        return out;
    }

    // ---------- helpers ----------

    /**
     * Returns the (k, c) minimizing {@code c} such that
     * {@code lhs[i] <= k*rhs[i] + c} for all i. Sweeps k over {@link #K_GRID}.
     * Cheap and grid-coarse; good enough for v1.
     */
    static double[] tightestKC(double[] lhs, double[] rhs) {
        if (lhs.length == 0 || lhs.length != rhs.length) {
            return null;
        }
        double[] best = null;
        for (double k : K_GRID) {
            double c = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < lhs.length; i++) {
                double v = lhs[i] - k * rhs[i];
                if (v > c) {
                    c = v;
                }
            }
            if (best == null || c < best[1]) {
                best = new double[]{k, c};
            }
        }
        return best;
    }

    static double pearson(double[] xs, double[] ys) {
        int n = xs.length;
        if (n < 3) {
            return 0.0;
        }
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += xs[i];
            my += ys[i];
        }
        mx /= n;
        my /= n;
        double num = 0;
        double dx = 0;
        double dy = 0;
        for (int i = 0; i < n; i++) {
            double xd = xs[i] - mx;
            double yd = ys[i] - my;
            num += xd * yd;
            dx += xd * xd;
            dy += yd * yd;
        }
        if (dx == 0 || dy == 0) {
            return 0.0;
        }
        return num / Math.sqrt(dx * dy);
    }

    static double spearman(List<Double> xs, List<Double> ys) {
        int n = xs.size();
        if (n < 3) {
            return 0.0;
        }
        double[] rx = ranks(xs);
        double[] ry = ranks(ys);
        return pearson(rx, ry);
    }

    /**
     * Average-rank assignment for ties. Mirrors the Python {@code rank}
     * helper in {@code _spearman}.
     */
    static double[] ranks(List<Double> arr) {
        int n = arr.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(arr::get));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && arr.get(order[j + 1]).equals(arr.get(order[i]))) {
                j++;
            }
            double avg = (i + j) / 2.0;
            for (int m = i; m <= j; m++) {
                r[order[m]] = avg;
            }
            i = j + 1;
        }
        return r;
    }

    /** A swept knob (e.g. {@code window_size}) and its values per config hash. */
    public record KnobAxis(String knob, Map<String, Double> configValues) {}

    /**
     * Sort survivors for human inspection: prefer more-confirmed first,
     * then tighter slack. Returns a new list; doesn't mutate input.
     */
    public static List<Inequality> sortedForDisplay(List<Inequality> store) {
        List<Inequality> out = new ArrayList<>(store);
        out.sort(Comparator
                .<Inequality>comparingInt(c -> -c.confirmedRuns)
                .thenComparingDouble(c -> c.p99Slack));
        return out;
    }
}
