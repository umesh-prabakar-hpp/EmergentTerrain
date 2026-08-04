package com.emergentterrain.research;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 12 D6 — registry of {@code (domain, topic)} keys that carry a known
 * textbook bound. Used by {@link ClosingProseValidator} to detect the
 * "no prior theoretical bound" boilerplate when it fires on a topic whose
 * bound is well-known.
 *
 * <p>REVIEW_REPORT refresh #3 §1 rec #5 named five Reject papers (P-a328-416
 * Mertens, P-422 Beer-Lambert, P-423 Stefan-Boltzmann, P-427 Doppler, P-432
 * Hall) that used the canned line "*…no prior theoretical bound constrains
 * the expected value in this region*" on textbook problems. The closing-
 * prose template defaults to that string when it cannot identify a textbook
 * reference; the topic→bound mapping is missing for problems with very
 * well-known bounds.
 *
 * <p>This class is the missing mapping. It is intentionally a static
 * hardcoded set rather than a YAML lookup so D6's scope stays narrow
 * (REVIEW_REPORT recommendation #5 + adjacent textbook topics) without
 * requiring new citations / closed-form text / valid-region commentary for
 * each entry. A YAML hookup can come in a follow-on phase if the registry's
 * coverage needs to expand fast.
 *
 * <p><b>Inclusion criterion:</b> the topic has a closed-form solution with
 * known parameter relationships that a textbook would cite (Stefan-Boltzmann
 * σT⁴, Beer-Lambert A=εcl, Hall-Petch σ_y = σ₀ + k·d^{-1/2}, etc.). Active
 * conjectures (Twin Prime distribution, Goldbach, Aliquot, Collatz, Happy
 * Number) are excluded — for those topics the boilerplate sentence is
 * actually correct.
 *
 * <p><b>Lookup:</b> case-insensitive equality on both fields, matching the
 * style of {@link LiteratureSearchService} for consistency.
 */
public final class TextbookBoundRegistry {

    /**
     * Bounded (domain, topic) keys. Order doesn't matter; lookup is by Set
     * membership. The five entries the May 2026 corpus needs to catch the
     * REVIEW_REPORT refresh #3 examples are at the top of each domain group
     * with an inline marker comment.
     */
    private static final Set<TopicKey> BOUNDED = Set.of(
            // ── MATHEMATICS — bounded subset ──
            new TopicKey("MATHEMATICS", "Mertens Partial Sums"),                  // P-a328-416
            new TopicKey("MATHEMATICS", "Divisor Sum Average"),                   // ln N + 2γ − 1
            new TopicKey("MATHEMATICS", "Squarefree Density"),                    // 6/π²
            new TopicKey("MATHEMATICS", "Stern-Brocot Tree Depth Survey"),        // O(log N)
            new TopicKey("MATHEMATICS", "Riemann Euler-Product Convergence"),     // ζ(s) = Π 1/(1-p^{-s})
            new TopicKey("MATHEMATICS", "Abundant Density"),                      // Deléglise 1998 asymptotic 0.2476

            // ── PHYSICS — all closed-form ──
            new TopicKey("PHYSICS", "Stefan-Boltzmann Total Flux"),               // P-a328-423
            new TopicKey("PHYSICS", "Black-body Wien Peak"),
            new TopicKey("PHYSICS", "Projectile Drag Optimisation"),
            new TopicKey("PHYSICS", "Pendulum Breakdown Angle"),
            new TopicKey("PHYSICS", "Van der Waals Crossover"),
            new TopicKey("PHYSICS", "Diffraction Order Survey"),
            new TopicKey("PHYSICS", "RLC Damping Transition"),
            new TopicKey("PHYSICS", "Schrodinger Infinite Well Energy"),
            new TopicKey("PHYSICS", "Schrödinger Infinite Well"),  // alt spelling
            new TopicKey("PHYSICS", "Kepler Third Law"),
            new TopicKey("PHYSICS", "Snell Critical Angle Dispersion"),

            // ── MATERIALS — all closed-form ──
            new TopicKey("MATERIALS", "Composite Failure Mode"),
            new TopicKey("MATERIALS", "Binary Sphere Packing"),                   // √2 - 1 octahedral void
            new TopicKey("MATERIALS", "Stiffness Crossover Volume Fraction"),
            new TopicKey("MATERIALS", "Arrhenius Rule Validity"),
            new TopicKey("MATERIALS", "Thermal Stress Safety Window"),
            new TopicKey("MATERIALS", "Hall-Petch Grain Size Strengthening"),
            new TopicKey("MATERIALS", "Bragg X-ray Diffraction"),
            new TopicKey("MATERIALS", "Carnot Efficiency"),
            new TopicKey("MATERIALS", "Basquin Fatigue Law"),
            new TopicKey("MATERIALS", "Young-Laplace Capillary Rise"),

            // ── CHEMISTRY — all closed-form ──
            new TopicKey("CHEMISTRY", "Beer-Lambert Absorbance"),                 // P-a328-422
            new TopicKey("CHEMISTRY", "Henderson-Hasselbalch Buffer pH"),
            new TopicKey("CHEMISTRY", "Nernst Electrochemical Potential"),
            new TopicKey("CHEMISTRY", "Equilibrium Constant Kc"),
            new TopicKey("CHEMISTRY", "Real-Gas Z-Factor"),

            // ── BIOLOGY — all closed-form ──
            new TopicKey("BIOLOGY", "Hardy-Weinberg Equilibrium"),
            new TopicKey("BIOLOGY", "Verhulst Logistic Growth"),
            new TopicKey("BIOLOGY", "Lotka-Volterra Predator-Prey"),
            new TopicKey("BIOLOGY", "Michaelis-Menten Enzyme Kinetics"),
            new TopicKey("BIOLOGY", "Kleiber Allometric Scaling"),

            // ── INTERDISCIPLINARY — closed-form subset ──
            new TopicKey("INTERDISCIPLINARY", "Acoustic Doppler Frequency Shift"), // P-a328-427
            new TopicKey("INTERDISCIPLINARY", "Hall-Effect Carrier Sign"),         // P-a328-432
            new TopicKey("INTERDISCIPLINARY", "Schottky-Barrier Thermionic Emission")
            // Engine-constructed bridges (Collatz-RLC, Prime-Gap Defect, Resonant Thermal
            // Fatigue, Happy-Number Packing, VdW Composite) are deliberately NOT in the
            // registry: their coupling equations are parameterised inventions of the
            // engine, not textbook results. The "no prior theoretical bound" boilerplate
            // is actually correct prose on those topics.
    );

    /** Suffix tokens the registry tolerates on its topic keys. */
    private static final Map<String, String> SCHRODINGER_ALIASES = Map.of(
            "schrodinger infinite well", "schrodinger infinite well energy",
            "schrödinger infinite well", "schrodinger infinite well energy");

    private TextbookBoundRegistry() {}

    /**
     * Returns {@code true} when the {@code (domain, topic)} pair has a
     * textbook closed-form bound that makes the "no prior theoretical bound"
     * boilerplate factually wrong. Case-insensitive on both fields. Null
     * inputs return {@code false} so callers can pass raw narration fields
     * without null-checking first.
     */
    public static boolean hasKnownBound(String domain, String topic) {
        if (domain == null || topic == null) return false;
        String d = domain.trim();
        String t = canonicaliseTopic(topic.trim());
        // Exact match (case-insensitive) against the registry.
        for (TopicKey key : BOUNDED) {
            if (key.domain.equalsIgnoreCase(d) && key.topic.equalsIgnoreCase(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalise common spelling variants so a topic written as "Schrödinger
     * Infinite Well" (no "Energy") matches the engine's "Schrodinger Infinite
     * Well Energy" canonical key. Lower-cased lookup against {@link
     * #SCHRODINGER_ALIASES}; unrecognised inputs are returned unchanged.
     */
    private static String canonicaliseTopic(String t) {
        String alias = SCHRODINGER_ALIASES.get(t.toLowerCase(Locale.ROOT));
        return alias != null ? alias : t;
    }

    /**
     * Composite key. Equality is case-insensitive on both fields so the
     * registry holds a stable representation regardless of how a caller
     * spells the domain ("MATHEMATICS" vs "mathematics") or topic case.
     */
    private record TopicKey(String domain, String topic) {
        @Override
        public int hashCode() {
            return domain.toLowerCase(Locale.ROOT).hashCode() * 31
                    + topic.toLowerCase(Locale.ROOT).hashCode();
        }
        @Override
        public boolean equals(Object o) {
            return o instanceof TopicKey k
                    && k.domain.equalsIgnoreCase(domain)
                    && k.topic.equalsIgnoreCase(topic);
        }
    }
}
