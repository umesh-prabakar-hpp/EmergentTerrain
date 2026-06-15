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
    // Phase 30 G2 — optional SECOND affine term: lhs ≤ k*rhs + k2*rhs2 + c. rhs2==null (k2==0) is the
    // backward-compat one-term bound — every legacy seed/fit/verify/filter behaves identically until a
    // row actually sets rhs2. Package-private mutation like k/c (set inside the engine / mapper).
    String rhs2;
    double k2 = 0.0;
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

    /**
     * Phase 28 §28.3 — full-field factory for wire-roundtrip deserialization.
     * Used by {@code JuliaMathClient.conjectureSeed} / {@code conjectureUpdate}
     * to reconstruct Inequality instances from the sidecar's JSON response.
     * Keeps the per-field setters package-private (so post-construction
     * mutation stays inside {@link ConjectureEngine}) while still letting
     * cross-package callers materialise a fully-populated instance.
     */
    public static Inequality fromFields(String lhs, String rhs, double k, double c,
                                         int confirmedRuns,
                                         List<String> confirmedConfigs,
                                         List<Witness> refutedBy,
                                         double p99Slack,
                                         Map<String, Double> slackByConfig,
                                         String droppedByKnob) {
        Inequality ineq = new Inequality(lhs, rhs, k, c);
        ineq.confirmedRuns = confirmedRuns;
        if (confirmedConfigs != null) ineq.confirmedConfigs.addAll(confirmedConfigs);
        if (refutedBy != null)        ineq.refutedBy.addAll(refutedBy);
        ineq.p99Slack = p99Slack;
        if (slackByConfig != null)    ineq.slackByConfig.putAll(slackByConfig);
        ineq.droppedByKnob = droppedByKnob;
        return ineq;
    }

    public String lhs() { return lhs; }
    public String rhs() { return rhs; }
    public double k() { return k; }
    public double c() { return c; }
    /** G2 — the optional second-term scalar name; null for a one-term bound. */
    public String rhs2() { return rhs2; }
    /** G2 — the optional second-term coefficient; 0 for a one-term bound. */
    public double k2() { return k2; }
    /** G2 — true iff this is a two-term bound (rhs2 set). */
    public boolean isTwoTerm() { return rhs2 != null; }
    /** G2 — set the optional second affine term (package-private mutation, like k/c). */
    void setSecondTerm(String rhs2, double k2) { this.rhs2 = rhs2; this.k2 = k2; }
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
