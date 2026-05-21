package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Candidate conjecture of the form {@code lhs <= k * rhs + c}, with the
 * machinery to track confirmation/refutation across runs and configs.
 *
 * <p>Mutable on purpose: {@link ConjectureEngine#update} tightens
 * {@code c}, bumps {@code confirmedRuns}, and appends witnesses in place.
 * Mirrors {@code scripts/graffiti/conjectures.py:Inequality} field-for-
 * field; the Java port keeps the same names so the two implementations
 * can be diffed when debugging parity.
 *
 * <p>Fields are package-private and accessed directly by
 * {@link ConjectureEngine}. JPA persistence is a separate concern handled
 * by the {@code Conjecture} entity in a later commit; this class stays a
 * pure value carrier for the engine.
 */
public class Inequality {

    final String lhs;
    final String rhs;
    double k;
    double c;
    int confirmedRuns = 0;
    final List<String> confirmedConfigs = new ArrayList<>();
    final List<Witness> refutedBy = new ArrayList<>();
    double p99Slack = 0.0;
    /** Worst observed margin per config_hash (negative = headroom). */
    final Map<String, Double> slackByConfig = new LinkedHashMap<>();
    /** Set by {@link ConjectureEngine#filterKnobRestatements} when dropped. */
    String droppedByKnob;

    public Inequality(String lhs, String rhs, double k, double c) {
        this.lhs = lhs;
        this.rhs = rhs;
        this.k = k;
        this.c = c;
    }

    public String lhs() { return lhs; }
    public String rhs() { return rhs; }
    public double k() { return k; }
    public double c() { return c; }
    public int confirmedRuns() { return confirmedRuns; }
    public List<String> confirmedConfigs() { return confirmedConfigs; }
    public List<Witness> refutedBy() { return refutedBy; }
    public double p99Slack() { return p99Slack; }
    public Map<String, Double> slackByConfig() { return slackByConfig; }
    public String droppedByKnob() { return droppedByKnob; }

    @Override
    public String toString() {
        return String.format("%s <= %+.3f * %s %+.4f (runs=%d cfgs=%d slack=%+.4f)",
                lhs, k, rhs, c, confirmedRuns, confirmedConfigs.size(), p99Slack);
    }
}
