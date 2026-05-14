package com.emergentterrain.research;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phase 7 F1 — central registry of per-(domain, topic) validator specs.
 *
 * <p>Promotes the 4 hand-curated bounds previously hard-coded in
 * {@link MagnitudeRangeValidator#validate(String, String, String)} into a Map
 * keyed by {@link TopicKey}, alongside stub entries for the remaining ~16
 * topics MathPhysicsEngine knows about today. F3 fills in the stubs with real
 * bounds, hypothesis templates, and closed-form formulae.
 *
 * <p>TODO Phase 7 F1+: operators may want a {@code topic-validators.yml}
 * override loaded at startup to expand bounds without recompiling. The
 * registration API (register / findFor) is shaped to accept that without
 * call-site churn — the loader just calls {@link #register(TopicKey, ValidatorSpec)}
 * after the bundled defaults.
 */
@Component
public class TopicValidatorRegistry {

    /**
     * Composite (domain, topic) key. Equality is case-insensitive so that the
     * registry tolerates "PHYSICS" vs "physics" / "RLC Damping Transition" vs
     * "rlc damping transition" from caller-side stringification drift.
     */
    public record TopicKey(String domain, String topic) {
        public TopicKey {
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(topic, "topic");
            domain = domain.toLowerCase(Locale.ROOT);
            topic = topic.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Per-topic validator metadata. Only {@code magnitudeRegex} + {@code upperLimit}
     * + {@code unit} drive Phase 5 B2 magnitude bounding today; the other fields
     * are populated for the 4 originally-bounded topics and left empty for the
     * F3 stubs.
     *
     * <p>A null {@code magnitudeRegex} signals "no magnitude check" — the
     * {@link MagnitudeRangeValidator} delegate treats those topics as no-ops.
     */
    public record ValidatorSpec(
            String  hypothesisTemplate,
            Pattern magnitudeRegex,
            double  upperLimit,
            String  unit,
            double  nullResultDeviationThreshold,
            String  closedFormFormula,
            boolean requiresPhysicalMechanism,
            String  reasonForBound
    ) {
        /** True iff the spec has a real magnitude bound the validator can apply. */
        public boolean hasMagnitudeBound() {
            return magnitudeRegex != null;
        }
    }

    private final Map<TopicKey, ValidatorSpec> specs = new HashMap<>();

    public TopicValidatorRegistry() {
        registerDefaults();
    }

    /**
     * Registers a spec. Idempotent: re-registering an identical key overwrites
     * the prior entry — tests rely on this so they can swap a registry in
     * isolation without throwing.
     *
     * <p>Throws if an INTERDISCIPLINARY topic is registered without either a
     * magnitude bound or {@code requiresPhysicalMechanism=true}. INTERDISCIPLINARY
     * findings without a physical-mechanism check are exactly the anti-pattern
     * Phase 5 B2 was meant to catch (P-a328-7's 7.8 GPa VdW stress was an
     * INTERDISCIPLINARY paper); failing fast at startup beats catching it at
     * publish time.
     */
    public void register(TopicKey key, ValidatorSpec spec) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(spec, "spec");
        if ("interdisciplinary".equals(key.domain())
                && !spec.hasMagnitudeBound()
                && !spec.requiresPhysicalMechanism()) {
            throw new IllegalStateException(
                    "INTERDISCIPLINARY topic '" + key.topic() + "' registered without a "
                            + "magnitude bound and without requiresPhysicalMechanism=true; "
                            + "cross-domain claims need a physical-reality check");
        }
        specs.put(key, spec);
    }

    /** Returns the spec for the given (domain, topic), case-insensitively. */
    public Optional<ValidatorSpec> findFor(String domain, String topic) {
        if (domain == null || topic == null) return Optional.empty();
        return Optional.ofNullable(specs.get(new TopicKey(domain, topic)));
    }

    /** Visible-for-testing — current registration count. */
    int size() {
        return specs.size();
    }

    // ---- Seed: 20 known topics from MathPhysicsEngine ----

    private void registerDefaults() {
        // The 4 bounds that existed in MagnitudeRangeValidator.BOUNDS pre-F1.
        register(new TopicKey("MATERIALS", "Composite Failure Mode"),
                bound(Pattern.compile("→([0-9]+(?:\\.[0-9]+)?)MPa"), 3000.0, "MPa",
                        "composite longitudinal stress > 3 GPa is unphysical for any "
                                + "real polymer-matrix composite"));
        register(new TopicKey("INTERDISCIPLINARY", "Van-der-Waals Composite Crossover"),
                bound(Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*MPa"), 1500.0, "MPa",
                        "VdW-induced composite stress > 1.5 GPa exceeds any realistic "
                                + "real-gas loading regime"));
        register(new TopicKey("INTERDISCIPLINARY", "Prime-Gap Defect Thermal Loading"),
                bound(Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*MPa"), 2000.0, "MPa",
                        "thermal-stress-from-prime-gap > 2 GPa is unphysical for any "
                                + "real defect periodicity"));
        // Arrhenius high-Ea low-T sanity: ratios > 12× indicate the simulator
        // returned a wildly non-physical Van't Hoff multiplier.
        register(new TopicKey("MATERIALS", "Arrhenius Rule Validity"),
                bound(Pattern.compile("Ea=[0-9]+kJ→([0-9]+(?:\\.[0-9]+)?)×"), 12.0, "×",
                        "Van't Hoff +10K multiplier > 12× would require a non-physical "
                                + "Ea/T² regime; check the simulator before publishing"));

        // F3 stubs — register a known-empty spec so findFor returns "present
        // but no bound" (delegate then no-ops). Topic names mirror
        // MathPhysicsEngine's *_TOPICS arrays so the registry is the single
        // source of truth for which topics exist.
        stub("MATHEMATICS", "Prime Constellation Search");
        stub("MATHEMATICS", "Collatz Stopping Time");
        stub("MATHEMATICS", "Goldbach Partition Survey");
        stub("MATHEMATICS", "Aliquot Sequence Behaviour");
        stub("MATHEMATICS", "Happy Number Distribution");
        // Phase 7 F3.4 — bounds for the new mathematics topics.
        register(new TopicKey("MATHEMATICS", "Mertens Partial Sums"),
                bound(Pattern.compile("\\|M\\|/√x\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)"), 2.0, "ratio",
                        "Empirical |M(x)|/√x > 2 in the tested range exceeds the "
                                + "standard 2·√x heuristic envelope; flag as a Mertens-"
                                + "conjecture stress event for follow-up investigation"));
        register(new TopicKey("MATHEMATICS", "Divisor Sum Average"),
                bound(Pattern.compile("dev=([0-9]+(?:\\.[0-9]+)?)"), 0.20, "ratio",
                        "Divisor-sum average deviation > 20% from π²/12 indicates the "
                                + "swept N is far below the asymptotic regime"));
        register(new TopicKey("MATHEMATICS", "Riemann Euler-Product Convergence"),
                bound(Pattern.compile("dev\\s+([0-9]+(?:\\.[0-9]+)?)\\s+at"), 0.15, "ratio",
                        "Euler-product / ζ-sum ratio deviation > 15% indicates s too "
                                + "close to 1 or insufficient prime truncation"));
        register(new TopicKey("MATHEMATICS", "Stern-Brocot Tree Depth Survey"),
                bound(Pattern.compile("max depth ([0-9]+)"), 50.0, "levels",
                        "Stern-Brocot depth ≥ 50 in q < 10⁶ regime is empirically "
                                + "unexpected; flag for sampling-strategy review"));
        register(new TopicKey("MATHEMATICS", "Squarefree Density"),
                bound(Pattern.compile("dev=([0-9]+(?:\\.[0-9]+)?)"), 0.10, "ratio",
                        "Squarefree density deviation > 10% from 6/π² indicates "
                                + "window-width too narrow for the asymptotic to apply"));

        stub("PHYSICS", "Projectile Drag Optimisation");
        stub("PHYSICS", "Pendulum Breakdown Angle");
        stub("PHYSICS", "Van der Waals Crossover");
        stub("PHYSICS", "Diffraction Order Survey");
        stub("PHYSICS", "RLC Damping Transition");
        // Phase 7 F3.2 — bounds for the new physics topics. Each bound captures
        // the "this topic's findings should never see a value above X" guard;
        // findings exceeding it indicates the simulator returned a wildly
        // non-physical magnitude that the publish path must route to REJECTED.
        register(new TopicKey("PHYSICS", "Black-body Wien Peak"),
                bound(Pattern.compile("dev=([0-9]+(?:\\.[0-9]+)?)%"), 25.0, "%",
                        "Wien λ_max·T deviation > 25% is unphysical: the displacement law "
                                + "is one of the highest-precision tabulated constants"));
        register(new TopicKey("PHYSICS", "Schrodinger Infinite Well Energy"),
                bound(Pattern.compile("ratio=([0-9]+(?:\\.[0-9]+)?)"), 200.0, "×kT",
                        "E₁/kT > 200 would require sub-Ångstrom well widths or "
                                + "ultracold thermal references; flag as unphysical"));
        register(new TopicKey("PHYSICS", "Kepler Third Law"),
                bound(Pattern.compile("dev\\s+([0-9]+(?:\\.[0-9]+)?)\\s+at"), 0.5, "ratio",
                        "Kepler T²/a³ deviation > 0.5 (50%) indicates the simulator "
                                + "violated the inverse-square gravitation assumption"));
        register(new TopicKey("PHYSICS", "Snell Critical Angle Dispersion"),
                bound(Pattern.compile("error\\s+([0-9]+(?:\\.[0-9]+)?)°"), 15.0, "°",
                        "Snell critical-angle error > 15° suggests an inverted n₁/n₂ "
                                + "or non-physical refractive index"));
        register(new TopicKey("PHYSICS", "Stefan-Boltzmann Total Flux"),
                bound(Pattern.compile("dev=([0-9]+(?:\\.[0-9]+)?)%"), 30.0, "%",
                        "Stefan-Boltzmann σ-deviation > 30% indicates a regime where "
                                + "the simple P=σAT⁴ closure fails (greybody / non-LTE) "
                                + "and the paper should not present it as universal"));

        stub("MATERIALS", "Binary Sphere Packing");
        stub("MATERIALS", "Stiffness Crossover Volume Fraction");
        stub("MATERIALS", "Thermal Stress Safety Window");
        // Phase 7 F3.3 — bounds for the new materials topics.
        register(new TopicKey("MATERIALS", "Hall-Petch Grain Size Strengthening"),
                bound(Pattern.compile("σ_y=([0-9]+(?:\\.[0-9]+)?)MPa"), 5000.0, "MPa",
                        "Hall-Petch yield strength > 5 GPa is unphysical for any "
                                + "polycrystalline steel; flag the sub-50 nm grain regime"));
        register(new TopicKey("MATERIALS", "Bragg X-ray Diffraction"),
                bound(Pattern.compile("θ[₁_1]?=([0-9]+(?:\\.[0-9]+)?)°"), 90.0, "°",
                        "Bragg angle > 90° violates the n·λ = 2·d·sin θ closure "
                                + "(sin θ ≤ 1 by definition)"));
        register(new TopicKey("MATERIALS", "Carnot Efficiency"),
                bound(Pattern.compile("η=([0-9]+(?:\\.[0-9]+)?)"), 1.0, "ratio",
                        "Carnot efficiency η ≥ 1 violates the second law of thermodynamics"));
        register(new TopicKey("MATERIALS", "Basquin Fatigue Law"),
                bound(Pattern.compile("σ_a=([0-9]+(?:\\.[0-9]+)?)MPa"), 3000.0, "MPa",
                        "Basquin stress amplitude > 3 GPa exceeds any common metal's "
                                + "ultimate tensile strength; extrapolation has broken down"));
        register(new TopicKey("MATERIALS", "Young-Laplace Capillary Rise"),
                bound(Pattern.compile("h=([0-9]+(?:\\.[0-9]+)?)cm"), 50.0, "cm",
                        "water capillary rise > 50 cm enters the sub-micron-radius "
                                + "regime where additional surface physics (curvature-"
                                + "dependent γ, disjoining pressure) dominate"));

        // Unbounded INTERDISCIPLINARY topics — they're cross-domain by construction
        // (Math→Physics, Physics→Materials, Math→Materials), so they pass the
        // invariant via requiresPhysicalMechanism=true even without a numeric
        // bound. F3 will replace these with proper bounds.
        stubInterdisciplinary("Collatz-RLC Depth Resonance");
        stubInterdisciplinary("Resonant Thermal Fatigue Window");
        stubInterdisciplinary("Happy-Number Packing Anomaly");
    }

    private static ValidatorSpec bound(Pattern regex, double upperLimit, String unit, String reason) {
        return new ValidatorSpec("", regex, upperLimit, unit, 0.0, "", false, reason);
    }

    private void stub(String domain, String topic) {
        register(new TopicKey(domain, topic),
                new ValidatorSpec("", null, 0.0, "", 0.0, "", false, ""));
    }

    private void stubInterdisciplinary(String topic) {
        register(new TopicKey("INTERDISCIPLINARY", topic),
                new ValidatorSpec("", null, 0.0, "", 0.0, "", true, ""));
    }

    // ---- Static singleton accessor for legacy static call sites ----

    private static final class Holder {
        static final TopicValidatorRegistry INSTANCE = new TopicValidatorRegistry();
    }

    /**
     * Default singleton used by static call sites (the static
     * {@link MagnitudeRangeValidator#validate} keeps its pre-F1 signature, so it
     * needs a registry it can reach without DI). Spring still constructs the
     * @Component bean independently; both share the same registration logic.
     */
    public static TopicValidatorRegistry defaultRegistry() {
        return Holder.INSTANCE;
    }
}
