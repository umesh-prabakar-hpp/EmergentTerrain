package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;

/**
 * Actual mathematics and physics computations used as the research engine.
 * All results are deterministic given the same inputs and verifiable by peer reviewers
 * re-running the same computation.
 *
 * SearchParams (derived from a bot's BotGenome) controls how broadly each computation
 * explores its parameter space. candidateBreadth drives how many starting regions are
 * tried; the best (most extreme) result is kept. stepSize controls how far apart the
 * candidate regions are spaced. An efficiency factor in scoreResult penalises wide
 * searches so bots that find strong results cheaply score as well as brute-force
 * wide searchers — this maintains a multi-peaked fitness landscape.
 */
public class MathPhysicsEngine {

    public enum ClaimStatus {
        SUPPORTED,
        PARTIAL,
        REFUTED,
        OBSERVATIONAL
    }

    public record ResearchResult(String findings, double primaryValue,
                                 double secondaryValue, double tertiaryValue,
                                 double reviewValue, boolean theoryConsistent,
                                 String theoryCheck, ClaimStatus claimStatus,
                                 String claimAssessment, String hypothesis,
                                 /* Phase 7 F2 — closed-form equation governing this topic.
                                    Populated by the dispatcher in {@link MathPhysicsEngine#compute}.
                                    Surfaces in the per-computation H3 block's **Equation:** line. */
                                 String equation) {
        public ResearchResult(String findings, double primaryValue,
                              double secondaryValue, double tertiaryValue) {
            this(findings, primaryValue, secondaryValue, tertiaryValue, primaryValue,
                    allFinite(primaryValue, secondaryValue, tertiaryValue),
                    allFinite(primaryValue, secondaryValue, tertiaryValue)
                            ? "Computed values are finite and reproducible."
                            : "One or more computed values are non-finite.",
                    allFinite(primaryValue, secondaryValue, tertiaryValue)
                            ? ClaimStatus.OBSERVATIONAL : ClaimStatus.REFUTED,
                    // Phase 5 A5 — drop the canned "no prior theoretical bound" novelty
                    // claim for observational results. Many topics (RLC, FCC packing,
                    // VdW, diffraction) have well-known closed-form bounds; emitting
                    // this phrase invites the narrator to parrot it verbatim and produces
                    // the corpus's most visible "AI slop" tell. Stay silent about novelty.
                    allFinite(primaryValue, secondaryValue, tertiaryValue)
                            ? "The computed values are finite and reproduced under the search params declared above."
                            : "The computation produced non-finite values.",
                    null, null);
        }

        public ResearchResult(String findings, double primaryValue,
                              double secondaryValue, double tertiaryValue,
                              boolean theoryConsistent, String theoryCheck) {
            this(findings, primaryValue, secondaryValue, tertiaryValue, primaryValue,
                    theoryConsistent, theoryCheck,
                    theoryConsistent ? ClaimStatus.SUPPORTED : ClaimStatus.REFUTED,
                    theoryCheck, null, null);
        }

        public ResearchResult(String findings, double primaryValue,
                              double secondaryValue, double tertiaryValue,
                              double reviewValue, boolean theoryConsistent,
                              String theoryCheck) {
            this(findings, primaryValue, secondaryValue, tertiaryValue, reviewValue,
                    theoryConsistent, theoryCheck,
                    theoryConsistent ? ClaimStatus.SUPPORTED : ClaimStatus.REFUTED,
                    theoryCheck, null, null);
        }

        public ResearchResult(String findings, double primaryValue,
                              double secondaryValue, double tertiaryValue,
                              double reviewValue, ClaimStatus claimStatus,
                              String claimAssessment) {
            this(findings, primaryValue, secondaryValue, tertiaryValue, reviewValue,
                    allFinite(primaryValue, secondaryValue, tertiaryValue, reviewValue),
                    allFinite(primaryValue, secondaryValue, tertiaryValue, reviewValue)
                            ? "Computed values are finite and reproducible."
                            : "One or more computed values are non-finite.",
                    claimStatus, claimAssessment, null, null);
        }

        public ResearchResult(String findings, double primaryValue,
                              double secondaryValue, double tertiaryValue,
                              double reviewValue, ClaimStatus claimStatus,
                              String claimAssessment, String hypothesis) {
            this(findings, primaryValue, secondaryValue, tertiaryValue, reviewValue,
                    allFinite(primaryValue, secondaryValue, tertiaryValue, reviewValue),
                    allFinite(primaryValue, secondaryValue, tertiaryValue, reviewValue)
                            ? "Computed values are finite and reproducible."
                            : "One or more computed values are non-finite.",
                    claimStatus, claimAssessment, hypothesis, null);
        }

        public ResearchResult withClaim(ClaimStatus status, String assessment) {
            return new ResearchResult(findings, primaryValue, secondaryValue, tertiaryValue,
                    reviewValue, theoryConsistent, theoryCheck, status, assessment,
                    hypothesis, equation);
        }

        public ResearchResult withFindingsAndClaim(String updatedFindings,
                                                   ClaimStatus status,
                                                   String assessment) {
            return new ResearchResult(updatedFindings, primaryValue, secondaryValue,
                    tertiaryValue, reviewValue, theoryConsistent, theoryCheck,
                    status, assessment, hypothesis, equation);
        }

        public ResearchResult withHypothesis(String updatedHypothesis) {
            return new ResearchResult(findings, primaryValue, secondaryValue, tertiaryValue,
                    reviewValue, theoryConsistent, theoryCheck, claimStatus,
                    claimAssessment, updatedHypothesis, equation);
        }

        /** Phase 7 F2 — stamp the topic's closed-form equation. */
        public ResearchResult withEquation(String updatedEquation) {
            return new ResearchResult(findings, primaryValue, secondaryValue, tertiaryValue,
                    reviewValue, theoryConsistent, theoryCheck, claimStatus,
                    claimAssessment, hypothesis, updatedEquation);
        }

        private static boolean allFinite(double... values) {
            for (double v : values) if (!Double.isFinite(v)) return false;
            return true;
        }
    }

    /**
     * Genome-derived search strategy passed into every compute method.
     *
     * candidateBreadth — how many parameter regions are explored (20–80); the key
     *                    evolutionary driver — wider search finds more extreme results
     *                    but incurs an efficiency penalty in scoreResult.
     * stepSize         — spacing between candidate regions (0.05=nearby, 1.0=spread wide).
     * depthBias        — reserved; available to LLM mentoring for future refinement passes.
     * restartProb      — reserved; not used in deterministic compute paths.
     * acceptThreshold  — reserved for future publish-gating logic.
     */
    public record SearchParams(
            double stepSize,
            double restartProb,
            int    candidateBreadth,
            double depthBias,
            double acceptThreshold) {

        /** Derive search params from a bot's genome weights (w0–w4). */
        public static SearchParams from(BotGenome g) {
            double[] w = g.getTopicWeights();
            return new SearchParams(w[0], w[1], (int) (20 + w[2] * 60), w[3], w[4]);
        }

        /**
         * Genome-derived params with an education funding bonus.
         * educationFactor in [0,1] adds up to +20 extra candidate regions,
         * simulating how research funding expands the breadth of a search.
         */
        public static SearchParams from(BotGenome g, double educationFactor) {
            double[] w = g.getTopicWeights();
            int breadth = Math.min(80, (int)(20 + w[2] * 60) + (int)(educationFactor * 20));
            return new SearchParams(w[0], w[1], breadth, w[3], w[4]);
        }

        /** Neutral baseline used for peer-reviewer reproduction (same params as author). */
        public static SearchParams neutral() {
            return new SearchParams(0.5, 0.2, 40, 0.5, 0.0);
        }
    }

    // ---- Topic name tables ----

    private static final String[] MATH_TOPICS = {
            "Prime Constellation Search", "Collatz Stopping Time",
            "Goldbach Partition Survey", "Aliquot Sequence Behaviour",
            "Happy Number Distribution"
    };
    private static final String[] PHYSICS_TOPICS = {
            "Projectile Drag Optimisation", "Pendulum Breakdown Angle",
            "Van der Waals Crossover", "Diffraction Order Survey",
            "RLC Damping Transition",
            // Phase 7 F3.2 — 5 new closed-form physics topics.
            "Black-body Wien Peak",
            "Schrodinger Infinite Well Energy",
            "Kepler Third Law",
            "Snell Critical Angle Dispersion",
            "Stefan-Boltzmann Total Flux"
    };
    private static final String[] MATERIALS_TOPICS = {
            "Composite Failure Mode", "Binary Sphere Packing",
            "Stiffness Crossover Volume Fraction", "Arrhenius Rule Validity",
            "Thermal Stress Safety Window",
            // Phase 7 F3.3 — 5 new closed-form materials topics.
            "Hall-Petch Grain Size Strengthening",
            "Bragg X-ray Diffraction",
            "Carnot Efficiency",
            "Basquin Fatigue Law",
            "Young-Laplace Capillary Rise"
    };
    private static final String[] INTERDISCIPLINARY_TOPICS = {
            "Collatz-RLC Depth Resonance",        // Math→Physics: Collatz depth drives RLC inductance
            "Prime-Gap Defect Thermal Loading",    // Math→Materials: prime gap → crystal defect spacing
            "Resonant Thermal Fatigue Window",     // Physics→Materials: RLC frequency → thermal cycling
            "Happy-Number Packing Anomaly",        // Math→Materials: happy density → sphere packing seed
            "Van-der-Waals Composite Crossover"    // Physics→Materials: VdW pressure → composite stress
    };

    // ---- Title tables (2 options per topic for variety) ----

    private static final String[][] MATH_TITLES = {
            {"Twin Prime Clustering in Bounded Intervals", "Prime Constellation Density Near N"},
            {"Collatz Stopping Time Distribution Over Integer Ranges", "Trajectory Depth in 3n+1 Sequences"},
            {"Goldbach Partition Counts for Even Integers", "Partition-Poor Even Numbers: A Local Survey"},
            {"Aliquot Sequence Termination Rates in Integer Ranges", "Cycle Detection in Iterated Proper Divisor Sums"},
            {"Happy Number Density and Digit-Square Convergence Patterns", "Local Verification of the 14.3% Happy Number Conjecture"}
    };
    private static final String[][] PHYSICS_TITLES = {
            {"Optimal Launch Angle Under Quadratic Air Resistance", "Drag-Induced Departure from the 45° Range Maximum"},
            {"Amplitude Threshold for Pendulum Period Error Exceeding 1%", "Breakdown of the Small-Angle Approximation in Real Pendula"},
            {"Pressure Crossover for Van der Waals Deviation in CO₂", "Real Gas Departure from Ideal Behaviour at Elevated Pressure"},
            {"Visible Diffraction Order Count as a Function of d/λ", "Grating Resolution and Order Extinction Survey"},
            {"Critical Resistance for RLC Oscillation Collapse", "Q-Factor Sensitivity Near the Underdamped–Overdamped Boundary"},
            // Phase 7 F3.2 — titles for the 5 new physics topics.
            {"Wien's Displacement Law Verified Against Tabulated Black-Body Spectra", "Wavelength of Peak Emission as a Function of Temperature"},
            {"Particle-in-a-Box Ground State Energy vs Well Width", "Quantum Confinement Threshold Where E₁ Exceeds Thermal Energy"},
            {"Empirical Verification of Kepler's Third Law Over a Wide Orbit Range", "Period-Semimajor-Axis Scaling for a Synthetic Planet Sweep"},
            {"Critical Angle of Total Internal Reflection Across Refractive-Index Pairs", "Snell-Law Dispersion Survey for Common Glass–Air Interfaces"},
            {"Empirical Recovery of the Stefan-Boltzmann Constant from Black-Body Power", "Total Radiated Flux as a Quartic Function of Surface Temperature"}
    };
    private static final String[][] MATERIALS_TITLES = {
            {"Fibre Volume Fraction at Which Matrix Yields Before Composite", "Longitudinal Failure Mode Transition in Fibre Composites"},
            {"Size Ratio Optimisation for Binary Hard-Sphere Packing", "Void-Filling Efficiency in FCC Lattices with Mixed Sphere Sizes"},
            {"Volume Fraction Threshold for Transverse Stiffness Doubling", "Reuss Bound Crossover in Particle-Reinforced Composites"},
            {"Activation Energy Range for Van't Hoff Rule Validity", "When Does the 2×-per-10K Approximation Break Down?"},
            {"Maximum Safe CTE Before Thermal Stress Exceeds Yield", "Temperature-Rise Safety Window for Constrained Structural Materials"},
            // Phase 7 F3.3 — titles for the 5 new materials topics.
            {"Hall-Petch Yield Strength as a Function of Inverse Square-Root Grain Size", "Strengthening Coefficient k Recovered from a Grain-Size Sweep"},
            {"Bragg Diffraction Angles for First-Order n=1 X-ray Reflection", "Lattice Spacing Recovery from Tabulated Bragg Peaks"},
            {"Carnot Efficiency Across Realistic Hot/Cold Reservoir Pairs", "Maximum-Efficiency Ceiling for Reversible Heat Engines"},
            {"Basquin Stress-Life Fit for High-Cycle Fatigue Data", "Endurance-Limit Estimation from σ_a vs 2N_f Power Law"},
            {"Capillary Rise of Water in Glass Tubes Across Radii", "Young-Laplace Predictions for Microfluidic Channel Heights"}
    };
    private static final String[][] INTERDISCIPLINARY_TITLES = {
            {"Collatz Trajectory Depth as an RLC Inductance Driver", "Number-Theoretic Resonance: 3n+1 Depth Governs Circuit Frequency"},
            {"Prime-Gap Defect Spacing and Thermal Yield Onset", "Crystal Defect Periodicity Derived from Prime Constellation Gaps"},
            {"Resonant Frequency as a Thermal Cycling Rate in Composites", "From RLC Oscillation to Fibre Fatigue: A Cross-Domain Study"},
            {"Happy Number Density Modulating Binary Packing Efficiency", "Digit-Square Convergence as a Structural Disorder Proxy"},
            {"Van der Waals Pressure Threshold Governing Composite Stress Onset", "Real-Gas Behaviour as a Mechanical Loading Analogue"}
    };

    // ---- Public API ----

    /** Primary entry point: genome-driven search via SearchParams. */
    public static ResearchResult compute(String domain, int topicIndex,
                                         int param1, double param2, SearchParams sp) {
        int n = topicCount(domain);
        int t = Math.floorMod(topicIndex, n);
        return switch (domain) {
            case "PHYSICS"           -> computePhysics(t, param1, param2, sp);
            case "MATERIALS"         -> computeMaterials(t, param1, param2, sp);
            case "INTERDISCIPLINARY" -> computeInterdisciplinary(t, param1, param2, sp);
            default                  -> computeMath(t, param1, param2, sp);
        };
    }

    /**
     * Phase 7 F3 — number of topics registered for a domain. Driven by the
     * domain's TOPICS array length so adding a topic in one place
     * automatically updates {@link #compute(String, int, int, double, SearchParams)}
     * and {@link #scoreResult(String, int, double, SearchParams)} indexing.
     */
    public static int topicCount(String domain) {
        return switch (domain == null ? "" : domain) {
            case "PHYSICS"           -> PHYSICS_TOPICS.length;
            case "MATERIALS"         -> MATERIALS_TOPICS.length;
            case "INTERDISCIPLINARY" -> INTERDISCIPLINARY_TOPICS.length;
            case "MATHEMATICS"       -> MATH_TOPICS.length;
            default                  -> MATH_TOPICS.length;
        };
    }

    /**
     * Phase 7 F3 — return a topic index in the range [0, topicCount(domain)).
     * When {@code topicsV2Enabled} is true, samples uniformly across every
     * registered topic in the domain (current 5 + the F3 additions). When
     * false, falls back to {@code rawWeightedSelection} which is the bot's
     * weighted genome-driven choice over the original 5 topics — bit-identical
     * to pre-F3 behaviour.
     */
    public static int selectTopicIndex(String domain, int rawWeightedSelection,
                                        boolean topicsV2Enabled, java.util.Random rng) {
        if (!topicsV2Enabled) {
            return Math.floorMod(rawWeightedSelection, Math.min(5, topicCount(domain)));
        }
        return rng.nextInt(topicCount(domain));
    }

    /** Backward-compatible overload — uses neutral SearchParams. */
    public static ResearchResult compute(String domain, int topicIndex,
                                         int param1, double param2) {
        return compute(domain, topicIndex, param1, param2, SearchParams.neutral());
    }

    /**
     * Normalised quality score in (0, 1).
     * Primary value is divided by a per-topic scale before the sigmoid so every topic
     * uses the full (0,1) range instead of saturating near 1.0 or languishing near 0.
     * An efficiency factor then penalises wide searches so narrow-precise strategies
     * remain competitive against broad-shallow ones.
     */
    public static double scoreResult(String domain, int topicIndex,
                                     double value, SearchParams sp) {
        if (!Double.isFinite(value)) return 0.0;
        double scale = topicScale(domain, Math.floorMod(topicIndex, topicCount(domain)));
        double abs = Math.abs(value) / scale;
        double raw = abs / (1.0 + abs);
        double efficiencyFactor = 1.0 - (sp.candidateBreadth() / 80.0) * 0.25;
        return raw * efficiencyFactor;
    }

    /** Backward-compatible overload — uses neutral SearchParams. */
    public static double scoreResult(String domain, int topicIndex, double value) {
        return scoreResult(domain, topicIndex, value, SearchParams.neutral());
    }

    /**
     * Per-topic normalization scale: chosen so that a "typical interesting result"
     * lands near sigmoid input 1.0 (score ≈ 0.5 × efficiencyFactor).
     * Fixes sigmoid saturation for large-primary topics (Collatz, RLC, Composite) and
     * near-zero scores for small-primary topics (Aliquot, Happy, Sphere packing).
     */
    private static double topicScale(String domain, int topic) {
        return switch (domain + ":" + topic) {
            // Mathematics
            case "MATHEMATICS:1" -> 100.0;   // Collatz maxSteps (~100-350 typical)
            case "MATHEMATICS:2" -> 3.0;     // Goldbach contrast ratio (~1.5-5)
            case "MATHEMATICS:3" -> 0.05;    // Aliquot anomalyFrac (~0.02-0.10)
            case "MATHEMATICS:4" -> 0.02;    // Happy number deviation (~0.005-0.05)
            // Physics
            case "PHYSICS:0"     -> 10.0;    // projectile angle shift in degrees (~5-25°)
            case "PHYSICS:1"     -> 50.0;    // pendulum T0*(80-angle) (~13-185)
            case "PHYSICS:2"     -> 30.0;    // VdW maxDev% (~10-90%)
            case "PHYSICS:3"     -> 1.5;     // diffraction order ratio (~1.5-2.5)
            case "PHYSICS:4"     -> 2000.0;  // RLC f0 in Hz (~500-5000)
            // Phase 7 F3.2 — new physics topics.
            case "PHYSICS:5"     -> 0.05;    // Wien deviation fraction (~0.001-0.10)
            case "PHYSICS:6"     -> 2.0;     // Schrödinger E₁/kT ratio (~0.5-5)
            case "PHYSICS:7"     -> 0.05;    // Kepler T²/a³ deviation fraction (~0.001-0.10)
            case "PHYSICS:8"     -> 5.0;     // Snell |θ_predicted − θ_observed| in degrees
            case "PHYSICS:9"     -> 0.05;    // Stefan-Boltzmann σ-deviation fraction (~0.005-0.10)
            // Materials
            case "MATERIALS:0"   -> 5000.0;  // composite maxStress in MPa (~1000-18000)
            case "MATERIALS:1"   -> 0.1;     // sphere packing improvement% (~0.02-0.15)
            case "MATERIALS:2"   -> 0.4;     // stiffness crossover Vf-0.5 (~0.3-0.5)
            case "MATERIALS:3"   -> 3.0;     // Arrhenius worstDev (~0.5-10)
            // Phase 7 F3.3 — new materials topics.
            case "MATERIALS:5"   -> 1000.0;  // Hall-Petch σ_y in MPa (~200-2000)
            case "MATERIALS:6"   -> 30.0;    // Bragg θ in degrees (~5-50)
            case "MATERIALS:7"   -> 0.5;     // Carnot efficiency (~0.1-0.7)
            case "MATERIALS:8"   -> 400.0;   // Basquin σ_a in MPa (~100-800)
            case "MATERIALS:9"   -> 5.0;     // Young-Laplace h in cm (~1-30)
            case "DYNAMIC:0"             -> 0.1;
            case "INTERDISCIPLINARY:0"   -> 2000.0;  // RLC f₀ driven by Collatz depth (~200-8000 Hz)
            case "INTERDISCIPLINARY:1"   -> 500.0;   // composite stress from prime-gap alpha (~100-2000 MPa)
            case "INTERDISCIPLINARY:2"   -> 0.6;     // fatigue fraction driven by RLC frequency (~0.2-0.9)
            case "INTERDISCIPLINARY:3"   -> 0.05;    // packing improvement modulated by happy density (~0.01-0.15)
            case "INTERDISCIPLINARY:4"   -> 8000.0;  // composite stress triggered by VdW pressure onset (~1000-15000 MPa)
            default              -> 1.0;     // MATHEMATICS:0 hlDeviation, MATERIALS:4 failFrac — already well-ranged
        };
    }

    /** Reverse-lookup: returns the 0-based topic index for a given topic name, or -1 if not found. */
    public static int topicIndex(String domain, String topicName) {
        String[] names = switch (domain) {
            case "PHYSICS"           -> PHYSICS_TOPICS;
            case "MATERIALS"         -> MATERIALS_TOPICS;
            case "INTERDISCIPLINARY" -> INTERDISCIPLINARY_TOPICS;
            default                  -> MATH_TOPICS;
        };
        for (int i = 0; i < names.length; i++) {
            if (names[i].equalsIgnoreCase(topicName)) return i;
        }
        return -1;
    }

    public static String topicName(String domain, int topicIndex) {
        String[] names = switch (domain) {
            case "PHYSICS"           -> PHYSICS_TOPICS;
            case "MATERIALS"         -> MATERIALS_TOPICS;
            case "INTERDISCIPLINARY" -> INTERDISCIPLINARY_TOPICS;
            default                  -> MATH_TOPICS;
        };
        return names[topicIndex % names.length];
    }

    public static String generateTitle(String domain, int topicIndex, java.util.Random rng) {
        String[][] table = switch (domain) {
            case "PHYSICS"           -> PHYSICS_TITLES;
            case "MATERIALS"         -> MATERIALS_TITLES;
            case "INTERDISCIPLINARY" -> INTERDISCIPLINARY_TITLES;
            default                  -> MATH_TITLES;
        };
        String[] row = table[topicIndex % table.length];
        return row[rng.nextInt(row.length)];
    }

    public static String generateHypothesis(String domain, int topicIndex, int param1, double param2) {
        return switch (domain + ":" + (topicIndex % 5)) {
            case "MATHEMATICS:0" -> {
                int n = param1 * 10;
                int w = param1 * 15;
                yield "Consecutive prime pairs in [%d, %d) will exhibit twin prime density consistent with the Hardy-Littlewood conjecture: ~2C₂/ln(N)² per unit interval."
                        .formatted(n, n + w);
            }
            case "MATHEMATICS:1" -> {
                int start = param1 * 3;
                yield "Collatz stopping times in [%d, %d) will show no counterexample to the 3n+1 conjecture; maximum depth is expected to scale sub-linearly with N."
                        .formatted(start, start + 60);
            }
            case "MATHEMATICS:2" -> {
                int even = param1 * 4;
                int span = (int) param2 * 10;
                yield "Every even number in [%d, %d) will have at least one Goldbach partition; average partition count will grow roughly as N/ln²(N)."
                        .formatted(even, even + span);
            }
            case "MATHEMATICS:3" ->
                "Aliquot sequences in [%d, %d) will predominantly terminate at 1; the fraction that escape into cycles or grow unboundedly will increase with N."
                        .formatted(param1, param1 + 40);
            case "MATHEMATICS:4" -> {
                int rangeStart = param1 * 7;
                yield "Happy number density near %d will remain close to the conjectured asymptotic 14.3%%, regardless of where in ℤ⁺ the window is placed."
                        .formatted(rangeStart);
            }
            case "PHYSICS:0" -> {
                double k = param1 / 1000.0;
                double v0 = 10.0 + param2 * 5;
                yield "With quadratic drag k=%.3f s/m and v₀=%.1f ms⁻¹, the range-optimal launch angle will fall below 45°; the shift grows with k and shrinks with v₀."
                        .formatted(k, v0);
            }
            case "PHYSICS:1" -> {
                double L = 0.1 + param1 / 200.0;
                yield "For a pendulum of length L=%.2f m, the small-angle period T₀=2π√(L/g) will diverge from the exact period by more than 1%% above some amplitude θ_c; we expect θ_c < 20°."
                        .formatted(L);
            }
            case "PHYSICS:2" -> {
                double T = 200 + param1 * 3.0;
                yield "At T=%.0f K, CO₂ (Van der Waals a=3.64, b=0.04267) will depart from ideal gas behaviour by more than 1%% above a crossover pressure P_c; P_c decreases as temperature falls."
                        .formatted(T);
            }
            case "PHYSICS:3" -> {
                double d = 1.0 + param1 / 50.0;
                yield "For a grating with d=%.2f µm, the number of visible diffraction orders will vary across the optical spectrum (400–700 nm); shorter wavelengths will support more orders."
                        .formatted(d);
            }
            case "PHYSICS:4" -> {
                double L = 0.001 + param1 / 10000.0;
                double C = 1e-6 + param2 * 1e-7;
                yield "In an RLC circuit with L=%.4f H and C=%.2e F, the critical damping resistance R_c=2√(L/C) marks a sharp transition; Q-factor collapses from oscillatory to exponential decay above R_c."
                        .formatted(L, C);
            }
            case "MATERIALS:0" -> {
                yield "In a steel-fibre/polymer composite (Ef=200 GPa, Em=3 GPa), the matrix will yield before the composite fractures across all fibre volume fractions; longitudinal stress at matrix yield will scale linearly with Vf."
                        .formatted();
            }
            case "MATERIALS:1" ->
                "In an FCC lattice, filling octahedral voids (r<0.414) with small spheres will improve packing beyond the 74.05% FCC baseline; the improvement will peak near r≈0.41 and plateau above r=0.5.";
            case "MATERIALS:2" -> {
                double ratio = 10 + param1 * 2.0;
                yield "For a composite with Ef/Em=%.0f, transverse stiffness (Reuss bound) doubles the matrix value at a Vf well above 0.5; the crossover Vf decreases as Ef/Em grows."
                        .formatted(ratio);
            }
            case "MATERIALS:3" -> {
                double T = 300 + param1 * 3.0;
                yield "At T=%.0f K, the Van't Hoff rule (D doubles per 10 K) will hold within 10%% only over a limited activation energy range; at high Ea the actual multiplier will far exceed 2×."
                        .formatted(T);
            }
            case "MATERIALS:4" -> {
                double dT = 50 + param1 * 3.0;
                yield "For a fully constrained steel member (E=200 GPa) subject to ΔT=%.0f K, there exists a critical CTE α_c below which thermal stress stays under yield; common engineering materials cluster near this boundary."
                        .formatted(dT);
            }
            case "INTERDISCIPLINARY:0" ->
                "Collatz stopping depth at n=%d will map to an RLC inductance that produces a measurable resonant frequency; deeper trajectories will yield lower-frequency circuits."
                        .formatted(param1 * 3);
            case "INTERDISCIPLINARY:1" ->
                "Prime constellation gaps near %d will act as crystal defect spacings; wider gaps (rarer primes) will produce lower effective CTE and safer thermal stress."
                        .formatted(param1 * 10);
            case "INTERDISCIPLINARY:2" ->
                "The RLC resonant frequency for an inductance derived from param1=%d will drive thermal cycling; high-frequency circuits will push a majority of structural materials past their yield threshold."
                        .formatted(param1);
            case "INTERDISCIPLINARY:3" ->
                "Happy number density near %d will deviate from the 14.3%% asymptotic in a way that measurably modulates small-sphere packing efficiency in binary FCC lattices."
                        .formatted(param1 * 7);
            case "INTERDISCIPLINARY:4" ->
                "Van der Waals crossover pressure for CO₂ at temperature derived from param1=%d, treated as a mechanical pressure, will drive composite longitudinal stress above 3 GPa."
                        .formatted(param1);
            default ->
                "The observed values are consistent with theoretical predictions under standard boundary conditions.";
        };
    }

    public static String generateConclusion(ResearchResult result, boolean passed) {
        if (!result.theoryConsistent()) {
            return "Theory check failed: %s Findings require correction before acceptance."
                    .formatted(result.theoryCheck());
        }
        if (!passed) {
            return "Computational inconsistencies detected during peer review. Findings require independent verification before acceptance.";
        }
        return switch (result.claimStatus()) {
            case SUPPORTED ->
                    "Computed values were independently reproduced by peer reviewers, and the topic-specific validator supports the hypothesis. %s"
                            .formatted(result.claimAssessment());
            case PARTIAL ->
                    "Computed values were independently reproduced by peer reviewers. The topic-specific validator found partial support only: %s"
                            .formatted(result.claimAssessment());
            case REFUTED ->
                    "Computed values were independently reproduced by peer reviewers, but the topic-specific validator does not support the hypothesis: %s"
                            .formatted(result.claimAssessment());
            case OBSERVATIONAL ->
                    // Phase 5 A5 — observational results no longer assert "no prior
                    // theoretical bound constrains this region." Many topics emitted under
                    // OBSERVATIONAL do have closed-form bounds (R_crit=2√(L/C),
                    // floor(d/λ), Hudson FCC peak); the canned novelty claim was the
                    // most repeated AI-slop tell in the corpus.
                    "Computed values were independently reproduced by peer reviewers. These findings represent an observational baseline. %s"
                            .formatted(result.claimAssessment());
        };
    }

    // ---- Mathematics ----

    private static ResearchResult computeMath(int topic, int param1, double param2, SearchParams sp) {
        String eq = equationFor("MATHEMATICS", topic);
        return switch (topic) {
            case 0 -> primeConstellations(param1 * 10, param1 * 15, sp).withEquation(eq);
            case 1 -> collatzStoppingTimes(param1 * 3, 60, sp).withEquation(eq);
            case 2 -> goldbachPartitions(Math.max(4, param1 * 4), (int) param2 * 10 + 20, sp).withEquation(eq);
            case 3 -> aliquotSequences(Math.max(2, param1), 40, sp).withEquation(eq);
            default -> happyNumbers(param1 * 7, 150, sp).withEquation(eq);
        };
    }

    // Outer search: try candidateBreadth windows spaced by stepSize*200.
    // Primary = H-L deviation ratio — larger when actual twin count diverges more from prediction.
    private static ResearchResult primeConstellations(int seedStart, int width, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int start = Math.max(2, seedStart + (int) (i * sp.stepSize() * 200));
            ResearchResult r = primeConstellationsAt(start, width);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : primeConstellationsAt(seedStart, width);
    }

    private static ResearchResult primeConstellationsAt(int start, int width) {
        int end = start + Math.max(50, width);
        List<Integer> primes = new ArrayList<>();
        for (int n = Math.max(2, start); n < end; n++) if (isPrime(n)) primes.add(n);
        int twins = 0, cousins = 0, sexy = 0, maxGap = 0;
        for (int i = 1; i < primes.size(); i++) {
            int gap = primes.get(i) - primes.get(i - 1);
            if (gap > maxGap) maxGap = gap;
            if (gap == 2) twins++;
            if (gap == 4) cousins++;
            if (gap == 6) sexy++;
        }
        int pairs = Math.max(1, primes.size() - 1);
        double twinDensity  = (double) twins / pairs;
        double primeDensity = (double) primes.size() / (end - start);
        double lnN = Math.log(Math.max(2, start));
        double hlPrediction = 1.320323631693739 / (lnN * lnN) * width;
        double hlDeviation  = Math.abs(twins - hlPrediction) / Math.max(1.0, hlPrediction);
        // Phase 5 B5 — tightened bands for asymptotic predictions. The May 2026
        // corpus had four papers (P-a328-70, 71, 74, 78) report H-L deviations
        // of 29–35% under the old 35%-supports-the-hypothesis band; the report
        // (Rec #19) flagged the wider band as systematically overclaiming.
        // New asymptotic bands: ≤15% strong support, 15–30% suggestive, ≥30% refuted.
        ClaimStatus status = hlDeviation <= 0.15 ? ClaimStatus.SUPPORTED
                : hlDeviation <= 0.30 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = "Hardy-Littlewood twin-count deviation is %.1f%% for this finite interval; %s."
                .formatted(hlDeviation * 100,
                        status == ClaimStatus.SUPPORTED ? "within the ≤15%% strong-support band"
                                : status == ClaimStatus.PARTIAL ? "suggestive but outside the strong-support band (15–30%%)"
                                : "too large to call the hypothesis supported (≥30%%)");
        String hypothesis = "Consecutive prime pairs in [%d, %d) will exhibit twin prime density consistent with the Hardy-Littlewood finite-interval estimate ~2C₂/ln(N)²."
                .formatted(start, end);
        return new ResearchResult(
                "Prime constellations [%d,%d): %d primes (density=%s), twin=%d, cousin=%d, sexy=%d, max_gap=%d. H-L predicted twins≈%.1f, deviation=%.1f%%. Twin density among pairs: %s."
                        .formatted(start, end, primes.size(), fmtProp(primeDensity, end - start),
                                   twins, cousins, sexy, maxGap, hlPrediction, hlDeviation * 100,
                                   fmtProp(twinDensity, pairs)),
                hlDeviation, primeDensity, maxGap, hlDeviation, status, assessment, hypothesis);
    }

    // Outer search: try candidateBreadth ranges spaced by stepSize*20.
    // Primary = maxSteps — deeper trajectories signal more complex behaviour near that region.
    private static ResearchResult collatzStoppingTimes(int seedStart, int count, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int start = Math.max(1, seedStart + (int) (i * sp.stepSize() * 20));
            ResearchResult r = collatzStoppingTimesAt(start, count);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : collatzStoppingTimesAt(seedStart, count);
    }

    private static ResearchResult collatzStoppingTimesAt(int start, int count) {
        int maxSteps = 0, maxStepsAt = start;
        int longTraj = 0, wCount = 0;
        boolean allTerminated = true;
        double wMean = 0, wM2 = 0;
        for (int n = Math.max(1, start); n < start + count; n++) {
            long x = n;
            int steps = 0;
            while (x != 1 && steps < 10_000) { x = (x % 2 == 0) ? x / 2 : 3 * x + 1; steps++; }
            if (x != 1) allTerminated = false;
            wCount++;
            double delta = steps - wMean;
            wMean += delta / wCount;
            wM2 += delta * (steps - wMean);
            if (steps > maxSteps) { maxSteps = steps; maxStepsAt = n; }
            if (steps > 100) longTraj++;
        }
        double avgSteps = wMean;
        double longFraction = (double) longTraj / count;
        int end = start + count;
        ClaimStatus status = allTerminated ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = allTerminated
                ? "All %d starting values in [%d, %d) terminated at 1; the 3n+1 conjecture holds locally. Maximum stopping depth %d at n=%d."
                        .formatted(count, start, end, maxSteps, maxStepsAt)
                : "Not all starting values in [%d, %d) reached 1 within 10,000 steps; the conjecture could not be fully verified."
                        .formatted(start, end);
        String terminated = allTerminated
                ? "All %d starting values terminated at 1 (conjecture holds locally).".formatted(count)
                : "Some values did not terminate within 10,000 steps.";
        return new ResearchResult(
                "Collatz [%d,%d): max_steps=%d (at n=%d), avg_steps=%s, fraction>100_steps=%s. %s"
                        .formatted(start, end, maxSteps, maxStepsAt,
                                   fmtMean(wMean, wM2, count), fmtProp(longFraction, count), terminated),
                maxSteps, avgSteps, longFraction, maxSteps, status, assessment)
                .withHypothesis("Collatz stopping times in [%d, %d) will show no counterexample to the 3n+1 conjecture; maximum depth characterizes local trajectory complexity."
                        .formatted(start, end));
    }

    // Outer search: try candidateBreadth even ranges spaced by stepSize*40.
    // Primary = avg/max(1,minParts) — contrast ratio flags thin-partition neighbourhoods.
    private static ResearchResult goldbachPartitions(int seedEven, int count, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int even = Math.max(4, seedEven + (int) (i * sp.stepSize() * 40));
            if (even % 2 != 0) even++;
            ResearchResult r = goldbachPartitionsAt(even, count);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : goldbachPartitionsAt(seedEven, count);
    }

    private static ResearchResult goldbachPartitionsAt(int evenStart, int count) {
        if (evenStart % 2 != 0) evenStart++;
        if (evenStart < 4) evenStart = 4;
        int minParts = Integer.MAX_VALUE, minPartsAt = evenStart;
        int singleCount = 0, tested = 0;
        double wMean = 0, wM2 = 0;
        for (int n = evenStart; n < evenStart + count * 2; n += 2) {
            int parts = 0;
            for (int p = 2; p <= n / 2; p++) if (isPrime(p) && isPrime(n - p)) parts++;
            tested++;
            double delta = parts - wMean;
            wMean += delta / tested;
            wM2 += delta * (parts - wMean);
            if (parts < minParts) { minParts = parts; minPartsAt = n; }
            if (parts == 1) singleCount++;
        }
        double avg = wMean;
        double contrast = avg / Math.max(1.0, minParts);
        int end = evenStart + count * 2;
        return new ResearchResult(
                "Goldbach partitions, even [%d,%d): avg=%s partitions/number, min=%d (at n=%d), single-partition=%s. All even numbers satisfy Goldbach conjecture locally."
                        .formatted(evenStart, end, fmtMean(wMean, wM2, tested),
                                   minParts, minPartsAt, fmtProp((double) singleCount / Math.max(1, tested), tested)),
                contrast, avg, singleCount)
                .withHypothesis("Every even number in [%d, %d) will have at least one Goldbach partition; partition counts will expose local thin-partition neighbourhoods."
                        .formatted(evenStart, end));
    }

    // Outer search: try candidateBreadth ranges spaced by stepSize*15.
    // Primary = anomaly fraction (cycle+escape) — higher means more sociable/unbounded sequences.
    private static ResearchResult aliquotSequences(int seedStart, int count, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        double firstAnomaly = Double.NaN;
        double lastAnomaly = Double.NaN;
        int sampled = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int start = Math.max(2, seedStart + (int) (i * sp.stepSize() * 15));
            ResearchResult r = aliquotSequencesAt(start, count);
            if (Double.isNaN(firstAnomaly)) firstAnomaly = r.primaryValue();
            lastAnomaly = r.primaryValue();
            sampled++;
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        if (best == null) best = aliquotSequencesAt(seedStart, count);
        boolean predominantlyTerminates = best.secondaryValue() >= 0.5;
        boolean trendIncreases = !Double.isNaN(firstAnomaly) && lastAnomaly >= firstAnomaly + 0.05;
        ClaimStatus status = !predominantlyTerminates ? ClaimStatus.REFUTED
                : trendIncreases ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = "Predominant local termination is %s; anomaly fraction across %d searched windows changed from %.3f to %.3f, so the range-start trend claim is %s."
                .formatted(predominantlyTerminates ? "observed" : "not observed",
                        sampled, firstAnomaly, lastAnomaly,
                        trendIncreases ? "supported by the sampled windows" : "not established");
        String findings = "%s Search trend: anomaly fraction first_window=%.3f, last_window=%.3f across %d windows."
                .formatted(best.findings(), firstAnomaly, lastAnomaly, sampled);
        return best.withFindingsAndClaim(findings, status, assessment);
    }

    // Aliquot sequences can grow without bound — cap x to avoid O(√x) factoring of huge numbers.
    private static final long ALIQUOT_X_CAP = 1_000_000L;

    private static ResearchResult aliquotSequencesAt(int start, int count) {
        int terminate = 0, perfect = 0, cycle = 0, escape = 0;
        for (int n = start; n < start + count; n++) {
            long x = n;
            java.util.Set<Long> seen = new java.util.HashSet<>();
            int steps = 0;
            boolean done = false;
            while (steps < 200 && !done) {
                if (x <= 1) { terminate++; done = true; break; }
                if (x > ALIQUOT_X_CAP) { escape++; done = true; break; }
                long sigma = 1;
                for (long d = 2; d * d <= x; d++) {
                    if (x % d == 0) { sigma += d; if (d != x / d) sigma += x / d; }
                }
                if (sigma == x) { perfect++; done = true; break; }
                if (seen.contains(sigma)) { cycle++; done = true; break; }
                seen.add(x);
                x = sigma;
                steps++;
            }
            if (!done) escape++;
        }
        double terminateFrac = (double) terminate / count;
        double anomalyFrac   = (double) (cycle + escape) / count;
        int end = start + count;
        return new ResearchResult(
                "Aliquot sequences [%d,%d): terminate=%d (%s), perfect=%d, cycle=%d, escape=%d. Anomaly fraction %s; range-start trend requires the multi-window validator."
                        .formatted(start, end, terminate, fmtProp(terminateFrac, count),
                                   perfect, cycle, escape, fmtProp(anomalyFrac, count)),
                anomalyFrac, terminateFrac, cycle)
                .withHypothesis("Aliquot sequences in [%d, %d) will predominantly terminate at 1; searched windows will test whether cycle-or-escape anomaly fractions increase with larger starts."
                        .formatted(start, end));
    }

    // Outer search: try candidateBreadth windows spaced by stepSize*200.
    // Primary = |density - 0.143| — larger when local density deviates from the asymptotic conjecture.
    private static ResearchResult happyNumbers(int seedStart, int count, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        double maxDeviation = 0;
        int sampled = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int start = Math.max(1, seedStart + (int) (i * sp.stepSize() * 200));
            ResearchResult r = happyNumbersAt(start, count);
            if (r.primaryValue() > maxDeviation) maxDeviation = r.primaryValue();
            sampled++;
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        if (best == null) best = happyNumbersAt(seedStart, count);
        ClaimStatus status = maxDeviation <= 0.03 ? ClaimStatus.SUPPORTED
                : maxDeviation <= 0.06 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = "Across %d searched windows, the largest absolute density deviation from 14.3%% was %.4f; %s."
                .formatted(sampled, maxDeviation,
                        status == ClaimStatus.SUPPORTED ? "all sampled windows stayed close to the conjectured density"
                                : status == ClaimStatus.PARTIAL ? "the global claim is only partially supported"
                                : "the searched windows contradict the regardless-of-window claim");
        String findings = "%s Multi-window validation: max_abs_deviation=%.4f across %d windows."
                .formatted(best.findings(), maxDeviation, sampled);
        return best.withFindingsAndClaim(findings, status, assessment);
    }

    private static ResearchResult happyNumbersAt(int start, int count) {
        int happy = 0;
        double happyMean = 0, happyM2 = 0;
        for (int n = Math.max(1, start); n < start + count; n++) {
            long x = n;
            int steps = 0;
            while (x != 1 && x != 4 && steps < 1000) {
                long sum = 0;
                while (x > 0) { long d = x % 10; sum += d * d; x /= 10; }
                x = sum;
                steps++;
            }
            if (x == 1) {
                happy++;
                double delta = steps - happyMean;
                happyMean += delta / happy;
                happyM2 += delta * (steps - happyMean);
            }
        }
        double density  = (double) happy / count;
        double avgSteps = happy > 0 ? happyMean : 0;
        double deviation = Math.abs(density - 0.1430);
        int end = start + count;
        return new ResearchResult(
                "Happy numbers [%d,%d): happy=%d/%d, density=%s (theoretical≈0.143, deviation=%+.3f), avg_steps_to_1=%s. Local density is compared with the conjectured asymptotic."
                        .formatted(start, end, happy, count, fmtProp(density, count), density - 0.1430,
                                   fmtMean(happyMean, happyM2, happy)),
                deviation, avgSteps, density)
                .withHypothesis("Happy number density in [%d, %d) will remain close to the conjectured asymptotic 14.3%%; multi-window validation tests the broader regardless-of-window claim."
                        .formatted(start, end));
    }

    // ---- Physics ----

    private static ResearchResult computePhysics(int topic, int param1, double param2, SearchParams sp) {
        String eq = equationFor("PHYSICS", topic);
        return switch (topic) {
            case 0 -> projectileWithDrag(10.0 + param2 * 5, param1 / 1000.0, sp).withEquation(eq);
            case 1 -> pendulumBreakdown(0.1 + param1 / 200.0, sp).withEquation(eq);
            case 2 -> vanDerWaalsDeviation(200.0 + param1 * 3.0, 0.01, sp).withEquation(eq);
            case 3 -> diffractionSurvey(1.0 + param1 / 50.0, 400.0 + param2 * 40, sp).withEquation(eq);
            case 4 -> rlcDampingTransition(0.001 + param1 / 10000.0, 1e-6 + param2 * 1e-7, sp).withEquation(eq);
            // Phase 7 F3.2 — new physics topics.
            case 5 -> wienPeak(2000.0 + param1 * 30.0, sp).withEquation(eq);
            case 6 -> schrodingerInfiniteWell(1.0 + param1 / 10.0, param2, sp).withEquation(eq);
            case 7 -> keplerThirdLaw(0.5 + param1 / 20.0, sp).withEquation(eq);
            case 8 -> snellCriticalAngle(1.0 + param2 / 9.0, sp).withEquation(eq);
            default -> stefanBoltzmann(300.0 + param1 * 50.0, sp).withEquation(eq);
        };
    }

    // Outer search: try candidateBreadth (v0, k) pairs — v0 increases by stepSize*5 per step.
    // Primary = angle shift (45 - bestAngle); larger shift means drag is more distorting.
    private static ResearchResult projectileWithDrag(double seedV0, double k, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double v0 = Math.max(2.0, seedV0 + i * sp.stepSize() * 5);
            ResearchResult r = projectileWithDragAt(v0, k);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : projectileWithDragAt(seedV0, k);
    }

    private static ResearchResult projectileWithDragAt(double v0, double k) {
        double g = 9.81, dt = 0.03;
        int bestAngle = 45;
        double bestRange = 0;
        for (int a = 5; a <= 85; a++) {
            double vx = v0 * Math.cos(Math.toRadians(a));
            double vy = v0 * Math.sin(Math.toRadians(a));
            double x = 0, y = 0;
            for (int i = 0; i < 5000 && y >= -0.01; i++) {
                double spd = Math.sqrt(vx * vx + vy * vy);
                vx -= k * spd * vx * dt;
                vy -= (g + k * spd * vy) * dt;
                x += vx * dt;
                y += vy * dt;
            }
            if (x > bestRange) { bestRange = x; bestAngle = a; }
        }
        double vacuumRange = v0 * v0 / g;
        double shift = 45 - bestAngle;
        double loss  = (vacuumRange - bestRange) / vacuumRange;
        return new ResearchResult(
                "Projectile drag (v₀=%.1f ms⁻¹, k=%.4f s/m): optimal_angle=%d° (%.0f° below vacuum 45°), range=%.2f m vs vacuum=%.2f m (%.1f%% loss). Drag breaks angle symmetry."
                        .formatted(v0, k, bestAngle, (double) shift, bestRange, vacuumRange, loss * 100),
                shift, bestRange, loss)
                .withHypothesis("With quadratic drag k=%.4f s/m and v₀=%.1f ms⁻¹, the range-optimal launch angle will fall below the vacuum 45° optimum."
                        .formatted(k, v0));
    }

    // Outer search: try candidateBreadth pendulum lengths spaced by stepSize*0.05 m.
    // Primary = T0*(80-breakdownAngle): T0 scales with √L so longer pendula score higher,
    // making the genome's stepSize/candidateBreadth actually influence which L is found.
    private static ResearchResult pendulumBreakdown(double seedL, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double L = Math.max(0.01, seedL + i * sp.stepSize() * 0.05);
            ResearchResult r = pendulumBreakdownAt(L);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : pendulumBreakdownAt(seedL);
    }

    private static ResearchResult pendulumBreakdownAt(double L) {
        double g  = 9.81;
        double T0 = 2 * Math.PI * Math.sqrt(L / g);
        double breakdownAngle = 80;
        StringBuilder sb = new StringBuilder();
        for (int deg = 5; deg <= 80; deg += 5) {
            double k  = Math.sin(Math.toRadians(deg) / 2);
            double k2 = k * k;
            double T  = T0 * (1 + k2 / 4 + 9 * k2 * k2 / 64 + 25 * k2 * k2 * k2 / 256);
            double err = (T - T0) / T0 * 100;
            // Self-contained tuple `θ=Nº→V%`: the angle is bound to its value within each
            // pair so the narration LLM can't shift row labels by ignoring the small leading
            // entry. P-a328-60 / P-a328-87 misread the prior unprefixed format and produced
            // a 5°-shifted table (0.77% at 25° rather than 1.19%). Phase 5 C5.
            sb.append("θ=%.0f°→%.2f%% ".formatted((double) deg, err));
            if (err > 1.0 && nearlyEqual(breakdownAngle, 80, 0.5)) breakdownAngle = deg;
        }
        ClaimStatus status = breakdownAngle < 20 ? ClaimStatus.SUPPORTED
                : nearlyEqual(breakdownAngle, 20, 0.5) ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = "The 1%% error threshold occurs at about %.0f°, so the θ_c < 20° expectation is %s."
                .formatted(breakdownAngle,
                        status == ClaimStatus.SUPPORTED ? "supported"
                                : status == ClaimStatus.PARTIAL ? "borderline rather than strongly supported"
                                : "not supported");
        String hypothesis = "For a pendulum of length L=%.2f m, the small-angle period T₀=2π√(L/g) will diverge from the finite-amplitude period by more than 1%% above θ_c; the configured claim expects θ_c < 20°."
                .formatted(L);
        double primary = T0 * (80 - breakdownAngle);
        return new ResearchResult(
                "Pendulum (L=%.2f m, T₀=%.3f s): period error by amplitude: %s. Small-angle error exceeds 1%% at θ≈%.0f°."
                        .formatted(L, T0, sb.toString().trim(), breakdownAngle),
                primary, T0, L, primary, status, assessment, hypothesis);
    }

    // Outer search: try candidateBreadth temperatures spaced by stepSize*20 K.
    // Primary = maxDev*100 (max percentage deviation from ideal gas).
    private static ResearchResult vanDerWaalsDeviation(double seedT, double tolerance, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double T = Math.max(150, seedT + i * sp.stepSize() * 20);
            ResearchResult r = vanDerWaalsDeviationAt(T, tolerance);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : vanDerWaalsDeviationAt(seedT, tolerance);
    }

    private static ResearchResult vanDerWaalsDeviationAt(double T, double tolerance) {
        double a = 3.640, b = 0.04267, R = 0.08206;
        double crossoverP = -1, maxDev = 0;
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= 100; p++) {
            double P = p, Videal = R * T / P, V = Videal;
            for (int iter = 0; iter < 30; iter++) {
                double f  = (P + a / (V * V)) * (V - b) - R * T;
                double df = (P + a / (V * V)) - 2 * a * (V - b) / (V * V * V);
                if (Math.abs(df) < 1e-15) break;
                double step = f / df;
                V -= step;
                if (V <= b) { V = b * 1.01; break; }
            }
            double dev = Math.abs(V - Videal) / Videal;
            if (dev > maxDev) maxDev = dev;
            if (dev > tolerance && crossoverP < 0) crossoverP = p;
            if (p == 1 || p == 5 || p == 10 || p == 20 || p == 50 || p == 100)
                sb.append("%datm→%.2f%% ".formatted(p, dev * 100));
        }
        boolean crossoverObserved = crossoverP > 0;
        String crossoverText = crossoverObserved
                ? "First crossover >%.0f%% occurs at P=%.0f atm."
                        .formatted(tolerance * 100, crossoverP)
                : "No >%.0f%% crossover was observed up to 100 atm."
                        .formatted(tolerance * 100);
        String hypothesis = "At T=%.0f K, CO₂ under the Van der Waals model will be tested for more than %.0f%% departure from ideal-gas volume within 1-100 atm."
                .formatted(T, tolerance * 100);
        return new ResearchResult(
                "Van der Waals CO₂ (T=%.0f K): deviation from ideal: %s. %s Max deviation in [1,100] atm: %.2f%%."
                        .formatted(T, sb.toString().trim(), crossoverText, maxDev * 100),
                maxDev * 100, crossoverObserved ? crossoverP : 101.0, T)
                .withHypothesis(hypothesis);
    }

    // Outer search: try candidateBreadth grating spacings spaced by stepSize*0.5 µm.
    // Primary = maxOrders/minOrders ratio — oscillates due to integer floor effects rather than
    // growing monotonically, so the genome's strategy actually influences which d is found.
    private static ResearchResult diffractionSurvey(double seedD, double lambdaNm, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double d = Math.max(0.4, seedD + i * sp.stepSize() * 0.5);
            ResearchResult r = diffractionSurveyAt(d, lambdaNm);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : diffractionSurveyAt(seedD, lambdaNm);
    }

    private static ResearchResult diffractionSurveyAt(double dMicrons, double lambdaNm) {
        int maxOrder = (int) (dMicrons * 1000 / lambdaNm);
        double theta1 = maxOrder >= 1
                ? Math.toDegrees(Math.asin(Math.min(1.0, lambdaNm / (dMicrons * 1000)))) : 0;
        StringBuilder sb = new StringBuilder();
        int minOrders = Integer.MAX_VALUE, maxOrders = 0;
        for (double lam : new double[]{400, 450, 500, 550, 600, 650, 700}) {
            int orders = (int) (dMicrons * 1000 / lam);
            if (orders < minOrders) minOrders = orders;
            if (orders > maxOrders) maxOrders = orders;
            sb.append("%.0fnm→%d ".formatted(lam, orders));
        }
        double orderRatio = (double) maxOrders / Math.max(1, minOrders);
        return new ResearchResult(
                "Diffraction grating (d=%.2f µm, λ_ref=%.0f nm): max_orders=%d, 1st_order_angle=%.1f°. Orders by wavelength: %s. Order ratio (max/min)=%.2f — blue light supports %.1f× more orders than red."
                        .formatted(dMicrons, lambdaNm, maxOrder, theta1, sb.toString().trim(),
                                orderRatio, orderRatio),
                orderRatio, maxOrder, theta1)
                .withHypothesis("For a grating with d=%.2f µm, the number of visible diffraction orders will vary across 400-700 nm wavelengths; shorter wavelengths will support more orders."
                        .formatted(dMicrons));
    }

    // Outer search: try candidateBreadth inductances; lower L (via stepSize) gives higher f₀.
    // Primary = f₀ (resonant frequency) — bots that search toward smaller L find higher-frequency circuits.
    private static ResearchResult rlcDampingTransition(double seedL, double C, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double L = Math.max(1e-5, seedL / (1.0 + i * sp.stepSize() * 0.2));
            ResearchResult r = rlcDampingTransitionAt(L, C);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : rlcDampingTransitionAt(seedL, C);
    }

    private static ResearchResult rlcDampingTransitionAt(double L, double C) {
        double critR  = 2 * Math.sqrt(L / C);
        double omega0 = 1 / Math.sqrt(L * C);
        double f0     = omega0 / (2 * Math.PI);
        StringBuilder sb = new StringBuilder();
        double minQ = Double.MAX_VALUE, maxQ = 0;
        for (double mult : new double[]{0.1, 0.25, 0.5, 1.0, 2.0, 4.0}) {
            double R = critR * mult;
            double Q = omega0 * L / R;
            if (Q < minQ) minQ = Q;
            if (Q > maxQ) maxQ = Q;
            String regime = mult < 1 ? "under" : mult > 1 ? "over" : "crit";
            sb.append("%.2f×R_c→Q=%.2f(%s) ".formatted(mult, Q, regime));
        }
        return new ResearchResult(
                "RLC circuit (L=%.4f H, C=%.2e F): f₀=%.2f Hz, R_crit=%.3f Ω. Q-factor sweep: %s. Q range: [%.2f, %.2f] — transition from oscillatory to exponential at R_crit."
                        .formatted(L, C, f0, critR, sb.toString().trim(), minQ, maxQ),
                f0, critR, maxQ / Math.max(minQ, 1e-10))
                .withHypothesis("In an RLC circuit with L=%.4f H and C=%.2e F, R_c=2√(L/C) marks the transition between underdamped oscillation and overdamped decay."
                        .formatted(L, C));
    }

    // ---- Phase 7 F3.2: new physics topics ----

    /** Wien displacement law: peak-emission wavelength scales as b/T (b=2.898e-3 m·K). */
    private static ResearchResult wienPeak(double seedT, SearchParams sp) {
        double wienB = 2.8977719e-3;
        double bestDev = 0;
        double bestT = seedT;
        double bestLambda = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        double sumDev = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double T = Math.max(100.0, seedT + i * sp.stepSize() * 200);
            double lambdaMax = wienB / T;
            // Synthetic "observed" λ_max: perturb the closed-form by a 0.5% noise band so
            // the deviation sweep is non-trivial. The hypothesis test is whether ALL
            // sampled points stay within Wien's 5% acceptance band.
            double observed = lambdaMax * (1.0 + Math.sin(i * 0.37) * 0.005);
            double dev = Math.abs(observed * T - wienB) / wienB;
            sumDev += dev;
            samples++;
            if (dev > bestDev) { bestDev = dev; bestT = T; bestLambda = observed; }
            if (i < 4) sb.append("T=%.0fK→λ_max=%.2fµm(dev=%.3f%%) ".formatted(T, observed * 1e6, dev * 100));
        }
        double meanDev = samples > 0 ? sumDev / samples : 0;
        ClaimStatus status = bestDev <= 0.05 ? ClaimStatus.SUPPORTED
                : bestDev <= 0.10 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Worst-case λ_max·T deviation from Wien's constant (b=2.898×10⁻³ m·K) is %.3f%% "
                + "over %d sampled temperatures; %s.").formatted(bestDev * 100, samples,
                status == ClaimStatus.SUPPORTED ? "Wien's displacement law holds within the 5% band"
                        : status == ClaimStatus.PARTIAL ? "5%–10% band — borderline"
                                : "deviation exceeds 10% — law not supported in this regime");
        return new ResearchResult(
                "Wien peak (T=%.0f–%.0f K, %d samples): worst λ_max=%.2fµm at T=%.0f K, dev=%.3f%%; mean dev=%.3f%%."
                        .formatted(seedT, bestT, samples, bestLambda * 1e6, bestT, bestDev * 100, meanDev * 100),
                bestDev, meanDev, bestLambda, bestDev, status, assessment)
                .withHypothesis(("Across the sampled temperature range starting near %.0f K, the empirical "
                        + "λ_max·T product will remain within 5%% of Wien's constant 2.898×10⁻³ m·K.")
                        .formatted(seedT));
    }

    /** Particle-in-a-1D-box ground-state energy E₁ = h²/(8mL²). */
    private static ResearchResult schrodingerInfiniteWell(double seedLnm, double param2, SearchParams sp) {
        double h = 6.62607015e-34;       // J·s
        double me = 9.1093837015e-31;    // kg (electron mass)
        double kB = 1.380649e-23;        // J/K
        double T = 300.0 + param2 * 30.0;  // K — thermal reference
        double bestRatio = 0;
        double bestL = seedLnm;
        double bestE1 = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double Lnm = Math.max(0.1, seedLnm - i * sp.stepSize() * 0.05);
            double Lm = Lnm * 1e-9;
            double E1 = (h * h) / (8.0 * me * Lm * Lm);
            double ratio = E1 / (kB * T);
            samples++;
            if (ratio > bestRatio) { bestRatio = ratio; bestL = Lnm; bestE1 = E1; }
            if (i < 4) sb.append("L=%.2fnm→E₁=%.2eJ(ratio=%.2f) ".formatted(Lnm, E1, ratio));
        }
        boolean confinementDominant = bestRatio > 1.0;
        ClaimStatus status = bestRatio > 5.0 ? ClaimStatus.SUPPORTED
                : confinementDominant ? ClaimStatus.PARTIAL : ClaimStatus.OBSERVATIONAL;
        String assessment = ("Maximum E₁/kT ratio is %.2f at L=%.3f nm (T=%.0f K); %s.")
                .formatted(bestRatio, bestL, T,
                        bestRatio > 5.0 ? "quantum confinement dominates thermal energy by ≥5×"
                                : confinementDominant ? "quantum confinement exceeds thermal energy"
                                        : "thermal energy still dominates the well's ground state");
        return new ResearchResult(
                "Infinite-well ground-state survey (L=%.2f–%.2f nm, T=%.0f K, %d samples): best ratio %.2f at L=%.2f nm, E₁=%.2e J. Sample sweep: %s"
                        .formatted(seedLnm, bestL, T, samples, bestRatio, bestL, bestE1, sb.toString().trim()),
                bestRatio, bestE1, bestL, bestRatio, status, assessment)
                .withHypothesis(("For an electron in a 1-D infinite well swept around L=%.2f nm at T=%.0f K, "
                        + "the ground-state energy E₁=h²/(8mL²) will exceed thermal energy kT for "
                        + "sufficiently narrow wells; the maximum E₁/kT ratio characterises the confinement scale.")
                        .formatted(seedLnm, T));
    }

    /** Kepler's third law: T² ∝ a³ with constant 4π²/(GM). Solar M assumed. */
    private static ResearchResult keplerThirdLaw(double seedAuAu, SearchParams sp) {
        double GMsun = 1.32712440018e20;  // m³/s²
        double AU = 1.495978707e11;        // m
        double secPerYear = 3.15576e7;
        double bestDev = 0;
        double bestA = seedAuAu;
        double bestT = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        double sumDev = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double aAU = Math.max(0.05, seedAuAu + i * sp.stepSize() * 0.5);
            double aM = aAU * AU;
            double Tsec = 2 * Math.PI * Math.sqrt(aM * aM * aM / GMsun);
            double Tyear = Tsec / secPerYear;
            // Empirical synthetic observation with a small noise band.
            double observedT = Tyear * (1.0 + Math.sin(i * 0.51) * 0.02);
            // Kepler check: observed T² / a³ should equal 1 (yr² / AU³) for the sun.
            double ratio = (observedT * observedT) / (aAU * aAU * aAU);
            double dev = Math.abs(ratio - 1.0);
            sumDev += dev;
            samples++;
            if (dev > bestDev) { bestDev = dev; bestA = aAU; bestT = observedT; }
            if (i < 4) sb.append("a=%.2fAU→T=%.2fyr(T²/a³=%.3f) ".formatted(aAU, observedT, ratio));
        }
        double meanDev = samples > 0 ? sumDev / samples : 0;
        ClaimStatus status = bestDev <= 0.10 ? ClaimStatus.SUPPORTED
                : bestDev <= 0.20 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Worst-case T²/a³ deviation from Kepler's constant (1 yr²/AU³ around Sol) is %.3f "
                + "at a=%.2f AU; %s.").formatted(bestDev, bestA,
                status == ClaimStatus.SUPPORTED ? "Kepler's third law holds within 10%"
                        : status == ClaimStatus.PARTIAL ? "10%–20% band — borderline"
                                : "deviation exceeds 20% — law not supported");
        return new ResearchResult(
                "Kepler third-law survey (a=%.2f–%.2f AU, %d samples): worst dev %.3f at a=%.2f AU, T=%.2f yr; mean dev %.3f."
                        .formatted(seedAuAu, bestA, samples, bestDev, bestA, bestT, meanDev),
                bestDev, meanDev, bestT, bestDev, status, assessment)
                .withHypothesis(("Across orbits sampled near a=%.2f AU under a Sol-mass primary, the period-"
                        + "semimajor-axis ratio T²/a³ will remain within 10%% of Kepler's constant (1 yr²/AU³).")
                        .formatted(seedAuAu));
    }

    /** Snell's law critical angle: θ_c = arcsin(n₂/n₁) for n₁>n₂. */
    private static ResearchResult snellCriticalAngle(double seedN2, SearchParams sp) {
        double n1 = 1.5;  // glass
        double bestErr = 0;
        double bestN2 = seedN2;
        double bestTheta = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        double sumErr = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double n2 = Math.max(1.0, Math.min(n1 - 0.01, seedN2 + i * sp.stepSize() * 0.05));
            double thetaC = Math.toDegrees(Math.asin(n2 / n1));
            // Synthetic observed angle drifts by up to 1.5° (within the 2° bound).
            double observed = thetaC + Math.sin(i * 0.43) * 1.5;
            double err = Math.abs(thetaC - observed);
            sumErr += err;
            samples++;
            if (err > bestErr) { bestErr = err; bestN2 = n2; bestTheta = thetaC; }
            if (i < 4) sb.append("n₂=%.3f→θ_c=%.1f°(err=%.2f°) ".formatted(n2, thetaC, err));
        }
        double meanErr = samples > 0 ? sumErr / samples : 0;
        ClaimStatus status = bestErr <= 2.0 ? ClaimStatus.SUPPORTED
                : bestErr <= 4.0 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Worst-case |θ_predicted − θ_observed| is %.2f° at n₂=%.3f; %s.")
                .formatted(bestErr, bestN2,
                        status == ClaimStatus.SUPPORTED ? "Snell critical-angle prediction holds within the 2° band"
                                : status == ClaimStatus.PARTIAL ? "2°–4° band — borderline"
                                        : "deviation exceeds 4° — Snell prediction not supported");
        return new ResearchResult(
                "Snell critical-angle (n₁=%.2f, n₂=%.3f–%.3f, %d samples): max error %.2f° at n₂=%.3f, θ_c=%.1f°; mean %.2f°."
                        .formatted(n1, seedN2, bestN2, samples, bestErr, bestN2, bestTheta, meanErr),
                bestErr, meanErr, bestTheta, bestErr, status, assessment)
                .withHypothesis(("For a n₁=%.2f → n₂≈%.3f interface, the critical angle θ_c=arcsin(n₂/n₁) "
                        + "will match the observed total-internal-reflection onset within 2°.")
                        .formatted(n1, seedN2));
    }

    /** Stefan-Boltzmann: P = σ·A·T⁴ with σ = 5.67×10⁻⁸ W/m²/K⁴. */
    private static ResearchResult stefanBoltzmann(double seedT, SearchParams sp) {
        double sigmaTrue = 5.670374419e-8;
        double A = 1.0;  // m², unit-area emitter
        double bestDev = 0;
        double bestT = seedT;
        double bestSigma = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        double sumDev = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double T = Math.max(100.0, seedT + i * sp.stepSize() * 100.0);
            double P = sigmaTrue * A * Math.pow(T, 4);
            // Empirical "observed" power with small noise; recover σ_observed = P/(A·T⁴).
            double observedP = P * (1.0 + Math.sin(i * 0.29) * 0.03);
            double sigmaObs = observedP / (A * Math.pow(T, 4));
            double dev = Math.abs(sigmaObs - sigmaTrue) / sigmaTrue;
            sumDev += dev;
            samples++;
            if (dev > bestDev) { bestDev = dev; bestT = T; bestSigma = sigmaObs; }
            if (i < 4) sb.append("T=%.0fK→P=%.2eW(σ_obs=%.3e, dev=%.2f%%) ".formatted(T, observedP, sigmaObs, dev * 100));
        }
        double meanDev = samples > 0 ? sumDev / samples : 0;
        ClaimStatus status = bestDev <= 0.10 ? ClaimStatus.SUPPORTED
                : bestDev <= 0.20 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Worst-case σ-deviation from Stefan-Boltzmann's constant (5.670×10⁻⁸ W·m⁻²·K⁻⁴) "
                + "is %.2f%% at T=%.0f K; %s.").formatted(bestDev * 100, bestT,
                status == ClaimStatus.SUPPORTED ? "law holds within 10%"
                        : status == ClaimStatus.PARTIAL ? "10%-20% band — borderline"
                                : "deviation exceeds 20% — law not supported");
        return new ResearchResult(
                "Stefan-Boltzmann survey (T=%.0f–%.0f K, %d samples): worst σ_obs=%.3e at T=%.0f K, dev=%.2f%%; mean dev=%.2f%%."
                        .formatted(seedT, bestT, samples, bestSigma, bestT, bestDev * 100, meanDev * 100),
                bestDev, meanDev, bestSigma, bestDev, status, assessment)
                .withHypothesis(("For a unit-area emitter swept from T=%.0f K, the empirical σ recovered from "
                        + "P/(A·T⁴) will remain within 10%% of Stefan-Boltzmann's tabulated 5.670×10⁻⁸ W·m⁻²·K⁻⁴.")
                        .formatted(seedT));
    }

    // ---- Phase 7 F3.3: new materials topics ----

    /** Hall-Petch grain-size strengthening: σ_y = σ_0 + k·d^(−1/2). */
    private static ResearchResult hallPetchStrengthening(double seedSigma0, double param2, SearchParams sp) {
        // k is the Hall-Petch coefficient (MPa·µm^½). Steels typically 300-700.
        double k = 300.0 + param2 * 50.0;
        double sigma0 = Math.max(50.0, seedSigma0);
        double maxYield = 0;
        double bestD = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double d_um = Math.max(0.05, 50.0 - i * sp.stepSize() * 1.5);
            double sigmaY = sigma0 + k / Math.sqrt(d_um);
            samples++;
            if (sigmaY > maxYield) { maxYield = sigmaY; bestD = d_um; }
            if (i < 4) sb.append("d=%.2fµm→σ_y=%.0fMPa ".formatted(d_um, sigmaY));
        }
        boolean physicalCeiling = maxYield < 5000;  // No steel exceeds 5 GPa
        ClaimStatus status = physicalCeiling
                ? (maxYield > sigma0 * 2 ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL)
                : ClaimStatus.REFUTED;
        String assessment = ("Hall-Petch: σ_0=%.0f MPa, k=%.0f MPa·µm^½; max σ_y=%.0f MPa at d=%.3f µm. %s")
                .formatted(sigma0, k, maxYield, bestD,
                        !physicalCeiling ? "σ_y > 5 GPa is unphysical for any steel — strengthening model fails at sub-50 nm grains"
                                : maxYield > sigma0 * 2 ? "grain refinement doubles yield strength — Hall-Petch holds in this range"
                                        : "modest strengthening only — Hall-Petch effect is weak");
        return new ResearchResult(
                "Hall-Petch sweep (σ_0=%.0f MPa, k=%.0f MPa·µm^½, %d grain samples): max σ_y=%.0fMPa at d=%.3f µm. Sample sweep: %s"
                        .formatted(sigma0, k, samples, maxYield, bestD, sb.toString().trim()),
                maxYield, sigma0, k, maxYield, status, assessment)
                .withHypothesis(("Yield strength of a polycrystalline steel with friction stress σ_0=%.0f MPa and "
                        + "Hall-Petch coefficient k=%.0f MPa·µm^½ scales as σ_0 + k·d^(−1/2); fine grain regimes "
                        + "produce the strongest material.")
                        .formatted(sigma0, k));
    }

    /** Bragg X-ray diffraction: n·λ = 2·d·sin θ. Recovers θ for given (d, λ, n). */
    private static ResearchResult braggDiffraction(double seedDnm, double lambdaNm, SearchParams sp) {
        double bestAngle = 0;
        double bestD = seedDnm;
        int bestN = 1;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        int validOrders = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double d = Math.max(0.05, seedDnm + i * sp.stepSize() * 0.02);
            // Sweep diffraction orders n = 1..3, count those within [0°, 90°].
            int orders = 0;
            for (int n = 1; n <= 3; n++) {
                double sinTheta = (n * lambdaNm) / (2 * d);
                if (sinTheta <= 1.0) {
                    double theta = Math.toDegrees(Math.asin(sinTheta));
                    if (n == 1 && theta > bestAngle) { bestAngle = theta; bestD = d; bestN = n; }
                    orders++;
                }
            }
            validOrders += orders;
            samples++;
            if (i < 4) {
                double sinT1 = lambdaNm / (2 * d);
                double t1 = sinT1 <= 1.0 ? Math.toDegrees(Math.asin(sinT1)) : 90.0;
                sb.append("d=%.3fnm→θ₁=%.1f° ".formatted(d, t1));
            }
        }
        double meanOrders = samples > 0 ? (double) validOrders / samples : 0;
        ClaimStatus status = bestAngle > 0.5 && bestAngle < 89.5 ? ClaimStatus.SUPPORTED
                : bestAngle > 0 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Bragg first-order θ=%.1f° at d=%.3f nm (λ=%.3f nm); mean visible orders = %.1f across %d samples. %s")
                .formatted(bestAngle, bestD, lambdaNm, meanOrders, samples,
                        status == ClaimStatus.SUPPORTED ? "Bragg condition produces a physically valid diffraction angle"
                                : "first-order angle outside the [0.5°, 89.5°] band — extreme lattice/λ ratio");
        return new ResearchResult(
                "Bragg diffraction (λ=%.3f nm, %d lattice samples): peak first-order θ=%.1f° at d=%.3f nm (n=%d). Sample sweep: %s"
                        .formatted(lambdaNm, samples, bestAngle, bestD, bestN, sb.toString().trim()),
                bestAngle, bestD, meanOrders, bestAngle, status, assessment)
                .withHypothesis(("For λ=%.3f nm X-rays and lattice spacings swept near d=%.3f nm, the first-order "
                        + "Bragg condition n·λ = 2·d·sin θ yields a recoverable diffraction angle in [0°, 90°].")
                        .formatted(lambdaNm, seedDnm));
    }

    /** Carnot efficiency: η = 1 − T_c/T_h. */
    private static ResearchResult carnotEfficiency(double seedTc, double seedTh, SearchParams sp) {
        double bestEta = 0;
        double bestTc = seedTc;
        double bestTh = seedTh;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        double sumEta = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double Tc = Math.max(50.0, seedTc + Math.sin(i * 0.31) * 30.0);
            double Th = Math.max(Tc + 10.0, seedTh + i * sp.stepSize() * 25.0);
            double eta = 1.0 - Tc / Th;
            sumEta += eta;
            samples++;
            if (eta > bestEta) { bestEta = eta; bestTc = Tc; bestTh = Th; }
            if (i < 4) sb.append("T_c=%.0fK/T_h=%.0fK→η=%.3f ".formatted(Tc, Th, eta));
        }
        double meanEta = samples > 0 ? sumEta / samples : 0;
        // Carnot bound: η must be in [0, 1) strict. Above 1 is unphysical.
        boolean physical = bestEta >= 0 && bestEta < 1.0;
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : bestEta > 0.5 ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = ("Carnot: best η=%.3f at T_c=%.0f K, T_h=%.0f K; mean η=%.3f across %d samples. %s")
                .formatted(bestEta, bestTc, bestTh, meanEta, samples,
                        !physical ? "η outside [0, 1) — second law violated; reject"
                                : bestEta > 0.5 ? "efficiency exceeds 50% — high-temperature regime"
                                        : "low-temperature regime — bounded efficiency expected");
        return new ResearchResult(
                "Carnot efficiency sweep (T_c≈%.0f K, T_h=%.0f–%.0f K, %d samples): max η=%.3f at T_c=%.0f K, T_h=%.0f K; mean η=%.3f."
                        .formatted(seedTc, seedTh, bestTh, samples, bestEta, bestTc, bestTh, meanEta),
                bestEta, meanEta, bestTh, bestEta, status, assessment)
                .withHypothesis(("For reversible Carnot cycles with T_c≈%.0f K and T_h swept upward from %.0f K, "
                        + "the achievable efficiency η = 1 − T_c/T_h will remain strictly less than 1 and rise "
                        + "monotonically with T_h.")
                        .formatted(seedTc, seedTh));
    }

    /** Basquin high-cycle fatigue: σ_a = σ'_f · (2·N_f)^b. */
    private static ResearchResult basquinFatigue(double seedSigmaF, double param2, SearchParams sp) {
        double sigmaPrimeF = seedSigmaF;  // MPa, fatigue strength coefficient
        double b = -0.08 - param2 * 0.01;  // typical -0.05 to -0.15
        double UTS = sigmaPrimeF * 0.9;   // assume σ_f is ~90% of UTS
        double bestAmp = 0;
        long bestNf = 0;
        int countAboveUts = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            long Nf = (long) Math.max(1_000, 10_000 + i * sp.stepSize() * 50_000);
            double sigmaA = sigmaPrimeF * Math.pow(2.0 * Nf, b);
            samples++;
            if (sigmaA > bestAmp) { bestAmp = sigmaA; bestNf = Nf; }
            if (sigmaA > UTS) countAboveUts++;
            if (i < 4) sb.append("N_f=%dk→σ_a=%.0fMPa ".formatted(Nf / 1000, sigmaA));
        }
        boolean physical = countAboveUts == 0;
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : bestAmp > sigmaPrimeF * 0.5 ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = ("Basquin: σ'_f=%.0f MPa, b=%.3f, UTS≈%.0f MPa; max σ_a=%.0f MPa at N_f=%d; %d/%d samples above UTS. %s")
                .formatted(sigmaPrimeF, b, UTS, bestAmp, bestNf, countAboveUts, samples,
                        !physical ? "σ_a exceeds UTS in some samples — Basquin extrapolation breaks above static strength"
                                : "fatigue-stress range stays below ultimate tensile strength");
        return new ResearchResult(
                "Basquin S-N curve (σ'_f=%.0f MPa, b=%.3f, %d cycle counts): max σ_a=%.0fMPa at N_f=%d. Sample sweep: %s"
                        .formatted(sigmaPrimeF, b, samples, bestAmp, bestNf, sb.toString().trim()),
                bestAmp, sigmaPrimeF, Math.abs(b), bestAmp, status, assessment)
                .withHypothesis(("For a metal with fatigue strength coefficient σ'_f=%.0f MPa and exponent b=%.3f, "
                        + "the Basquin power law σ_a = σ'_f·(2·N_f)^b will produce stress amplitudes that stay below "
                        + "the material's ultimate tensile strength across the swept cycle-count range.")
                        .formatted(sigmaPrimeF, b));
    }

    /** Young-Laplace capillary rise: h = 2γ·cos θ / (ρ·g·r). */
    private static ResearchResult youngLaplaceCapillary(double seedRadiusMm, SearchParams sp) {
        double gamma = 0.0728;   // N/m, water surface tension at 20°C
        double rho = 1000.0;     // kg/m³
        double g = 9.81;
        double contactAngle = Math.toRadians(20.0);  // wet glass
        double maxH = 0;
        double bestR = seedRadiusMm;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        int countAboveLimit = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double r_mm = Math.max(0.005, seedRadiusMm - i * sp.stepSize() * 0.005);
            double r_m = r_mm * 1e-3;
            double h_m = (2 * gamma * Math.cos(contactAngle)) / (rho * g * r_m);
            double h_cm = h_m * 100.0;
            samples++;
            if (h_cm > maxH) { maxH = h_cm; bestR = r_mm; }
            if (h_cm > 50.0) countAboveLimit++;  // F3 spec: water rise must be ≤ 50 cm
            if (i < 4) sb.append("r=%.3fmm→h=%.2fcm ".formatted(r_mm, h_cm));
        }
        boolean physical = countAboveLimit < samples / 2;  // most samples must stay sane
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : maxH > 5.0 ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = ("Young-Laplace: γ=%.4f N/m, ρ=%.0f kg/m³, contact angle %.0f°; max h=%.2f cm at r=%.3f mm; %d/%d samples above 50 cm. %s")
                .formatted(gamma, rho, Math.toDegrees(contactAngle), maxH, bestR, countAboveLimit, samples,
                        !physical ? "rise exceeds 50 cm — sub-micron radius regime where additional physics dominates"
                                : maxH > 5.0 ? "measurable capillary rise across the swept radius range"
                                        : "small capillary effect at this radius scale");
        return new ResearchResult(
                "Young-Laplace capillary survey (γ=%.4f N/m, contact=%.0f°, %d radius samples): max rise %.2f cm at r=%.3f mm. Sample sweep: %s"
                        .formatted(gamma, Math.toDegrees(contactAngle), samples, maxH, bestR, sb.toString().trim()),
                maxH, bestR, gamma, maxH, status, assessment)
                .withHypothesis(("Water in a glass capillary with radii swept down from %.3f mm will rise per "
                        + "Young-Laplace h = 2γ·cos θ / (ρ·g·r); the maximum measurable rise across the sweep "
                        + "scales inversely with tube radius.")
                        .formatted(seedRadiusMm));
    }

    // ---- Materials ----

    private static ResearchResult computeMaterials(int topic, int param1, double param2, SearchParams sp) {
        String eq = equationFor("MATERIALS", topic);
        return switch (topic) {
            case 0 -> compositeFailureMode(50.0 + param1 * 5, sp).withEquation(eq);
            case 1 -> binarySpherePacking(sp).withEquation(eq);
            case 2 -> compositeStiffnessCrossover(10.0 + param1 * 2, 2.0, sp).withEquation(eq);
            case 3 -> arrheniusRuleValidity(300.0 + param1 * 3, 50.0 + param2 * 20, sp).withEquation(eq);
            case 4 -> thermalStressSafety(50.0 + param1 * 3, 200.0 + param2 * 50, sp).withEquation(eq);
            // Phase 7 F3.3 — new materials topics.
            case 5 -> hallPetchStrengthening(50.0 + param1, param2, sp).withEquation(eq);
            case 6 -> braggDiffraction(0.05 + param1 / 500.0, 0.154, sp).withEquation(eq);
            case 7 -> carnotEfficiency(280.0 + param2 * 10, 400.0 + param1 * 5, sp).withEquation(eq);
            case 8 -> basquinFatigue(800.0 + param1 * 5, param2, sp).withEquation(eq);
            default -> youngLaplaceCapillary(0.1 + param1 / 200.0, sp).withEquation(eq);
        };
    }

    // Outer search: try candidateBreadth sigmaYm values spaced by stepSize*50 MPa.
    // Primary = maxStress — higher matrix yield strength produces more extreme composite stress.
    private static ResearchResult compositeFailureMode(double seedSigmaYm, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double sigmaYm = clamp(seedSigmaYm + i * sp.stepSize() * 50, 10.0, 200.0);
            ResearchResult r = compositeFailureModeAt(sigmaYm);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : compositeFailureModeAt(clamp(seedSigmaYm, 10.0, 200.0));
    }

    private static ResearchResult compositeFailureModeAt(double sigmaYm) {
        double Ef = 200e3, Em = 3e3;
        double epsY = sigmaYm / Em;
        StringBuilder sb = new StringBuilder();
        double maxStress = 0, maxVf = 0;
        for (int vfPct = 10; vfPct <= 90; vfPct += 10) {
            double Vf = vfPct / 100.0;
            double EL = Vf * Ef + (1 - Vf) * Em;
            double sigmaC = EL * epsY;
            if (sigmaC > maxStress) { maxStress = sigmaC; maxVf = Vf; }
            sb.append("Vf=%.0f%%→%.0fMPa ".formatted(Vf * 100, sigmaC));
        }
        String assessment = "The linear stress-at-matrix-yield trend is validated within the bounded 10-200 MPa matrix-yield search range, but no fibre-strength or composite-fracture criterion is modeled; the matrix-yields-before-fracture claim is only partially supported.";
        String hypothesis = "For a steel-fibre/polymer composite with σ_ym=%.0f MPa in the bounded matrix-yield search range, longitudinal stress at matrix yield will scale linearly with fibre volume fraction; any matrix-yields-before-fracture claim remains conditional without a fibre-strength criterion."
                .formatted(sigmaYm);
        return new ResearchResult(
                "Composite failure (Ef=200GPa, Em=3GPa, σ_ym=%.0f MPa, ε_y=%.4f): longitudinal stress at matrix yield: %s. Stress rises linearly with Vf; max at Vf=%.0f%% → %.0f MPa. Fibre fracture strength is not included in this model."
                        .formatted(sigmaYm, epsY, sb.toString().trim(), maxVf * 100, maxStress),
                maxStress, epsY, maxVf, maxStress, ClaimStatus.PARTIAL, assessment, hypothesis);
    }

    // Inner precision: candidateBreadth controls r sample density across [0.001, 0.65].
    // Primary = (bestEta - etaFCC)*100 — finer sampling finds the peak r more precisely.
    private static ResearchResult binarySpherePacking(SearchParams sp) {
        double etaFCC = Math.PI / (3 * Math.sqrt(2));
        double bestR = 0, bestEta = etaFCC;
        StringBuilder sb = new StringBuilder();
        int samples = sp.candidateBreadth();
        for (int i = 0; i < samples; i++) {
            double r = 0.001 + (i / (double) Math.max(1, samples - 1)) * 0.649;
            double voidVolOcta  = r <= 0.4142 ? (4.0 / 3) * Math.PI * Math.pow(r, 3) : 0;
            double voidVolTetra = r <= 0.2247 ? 2 * (4.0 / 3) * Math.PI * Math.pow(r, 3) : 0;
            double addedPacking = (voidVolOcta + voidVolTetra) * etaFCC / ((4.0 / 3) * Math.PI);
            double etaTotal = Math.min(0.99, etaFCC + addedPacking);
            if (etaTotal > bestEta) { bestEta = etaTotal; bestR = r; }
            if (i % (samples / Math.min(6, samples)) == 0)
                sb.append("r=%.2f→η=%.4f ".formatted(r, etaTotal));
        }
        double improvement = (bestEta - etaFCC) * 100;
        return new ResearchResult(
                "Binary sphere packing (FCC base η=%.4f, %d r-samples): packing survey: %s. Peak packing η=%.4f at r≈%.3f (+%.2f%% over FCC). Octahedral void limit r<0.414; tetrahedral r<0.225."
                        .formatted(etaFCC, samples, sb.toString().trim(), bestEta, bestR, improvement),
                improvement, bestR, etaFCC)
                .withHypothesis("In an FCC lattice, filling octahedral and tetrahedral voids with smaller hard spheres will improve packing beyond the 74.05% FCC baseline; sampled radius ratios will identify the peak improvement.");
    }

    // Outer search: try candidateBreadth Ef/Em ratios spaced by stepSize*5.
    // Searches toward lower ratios (more surprising crossover behaviour further from 50%).
    // Primary = crossoverVf - 0.5 (departure from the intuitive 50% guess).
    private static ResearchResult compositeStiffnessCrossover(double seedRatio, double targetMultiple,
                                                               SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double ratio = Math.max(2.0, seedRatio - i * sp.stepSize() * 5);
            ResearchResult r = compositeStiffnessCrossoverAt(ratio, targetMultiple);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : compositeStiffnessCrossoverAt(seedRatio, targetMultiple);
    }

    private static ResearchResult compositeStiffnessCrossoverAt(double EfOverEm, double targetMultiple) {
        double Em = 3e3, Ef = EfOverEm * Em;
        double crossoverVf = -1;
        StringBuilder sb = new StringBuilder();
        for (int vfPct = 5; vfPct <= 95; vfPct += 5) {
            double Vf = vfPct / 100.0;
            double ET = 1.0 / (Vf / Ef + (1 - Vf) / Em);
            double multiple = ET / Em;
            if (vfPct % 20 == 0) sb.append("Vf=%.0f%%→%.2f× ".formatted(Vf * 100, multiple));
            if (multiple >= targetMultiple && crossoverVf < 0) crossoverVf = Vf;
        }
        if (crossoverVf < 0) crossoverVf = 1.0;
        double surprise = crossoverVf - 0.5;
        return new ResearchResult(
                "Composite stiffness (Ef/Em=%.0f, target=%.1f×Em): Reuss bound survey: %s. E_T reaches %.1f×Em at Vf=%.2f — well above the intuitive 50%% guess."
                        .formatted(EfOverEm, targetMultiple, sb.toString().trim(), targetMultiple, crossoverVf),
                surprise, crossoverVf, EfOverEm)
                .withHypothesis("For a composite with Ef/Em=%.0f, transverse stiffness under the Reuss bound will reach %.1f×Em at a fibre volume fraction well above 0.5."
                        .formatted(EfOverEm, targetMultiple));
    }

    // Outer search: try candidateBreadth temperatures spaced by stepSize*20 K.
    // Primary = worst-case |ratio - 2.0| — larger when the Van't Hoff rule breaks down most severely.
    private static ResearchResult arrheniusRuleValidity(double seedT, double maxEaKJ, SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double T = Math.max(200.0, seedT + i * sp.stepSize() * 20);
            ResearchResult r = arrheniusRuleValidityAt(T, maxEaKJ);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : arrheniusRuleValidityAt(seedT, maxEaKJ);
    }

    private static ResearchResult arrheniusRuleValidityAt(double T, double maxEaKJ) {
        double R = 8.314;
        int valid = 0, total = 0;
        double firstValid = -1, lastValid = -1;
        double worstDev = 0;
        double maxRatio = 0;
        StringBuilder sb = new StringBuilder();
        for (int ea = 10; ea <= (int) maxEaKJ; ea += 10) {
            double Ea = ea * 1000.0;
            double D1 = Math.exp(-Ea / (R * T));
            double D2 = Math.exp(-Ea / (R * (T + 10)));
            double ratio = D2 / D1;
            if (ratio > maxRatio) maxRatio = ratio;
            double dev = Math.abs(ratio - 2.0);
            if (dev > worstDev) worstDev = dev;
            boolean holds = dev / 2.0 < 0.10;
            total++;
            if (holds) { valid++; if (firstValid < 0) firstValid = ea; lastValid = ea; }
            if (ea <= 50 || ea % 40 == 10) sb.append("Ea=%dkJ→%.2f× ".formatted(ea, ratio));
        }
        double fraction = (double) valid / Math.max(1, total);
        boolean limitedRange = valid > 0 && valid < total;
        boolean highEaFarExceeds = maxRatio >= 2.2;
        ClaimStatus status = limitedRange && highEaFarExceeds ? ClaimStatus.SUPPORTED
                : limitedRange || highEaFarExceeds ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = "Limited 2x-valid activation-energy range: %s; maximum +10K multiplier=%.2f×, so the high-Ea far-exceeds-2x claim is %s."
                .formatted(limitedRange ? "observed" : "not observed", maxRatio,
                        highEaFarExceeds ? "supported" : "not supported in the scanned range");
        String hypothesis = "At T=%.0f K, the Van't Hoff rule (diffusion doubles per 10 K) will hold within 10%% only over a limited activation-energy range up to %.0f kJ/mol; high Ea values may exceed 2×."
                .formatted(T, maxEaKJ);
        return new ResearchResult(
                "Arrhenius Van't Hoff (T=%.0f K, Ea 10–%.0f kJ/mol): sample ratios: %s. Rule holds within 10%% for %d/%d cases (%.0f%%), Ea=[%.0f,%.0f] kJ/mol; max_multiplier=%.2f×."
                        .formatted(T, maxEaKJ, sb.toString().trim(), valid, total, fraction * 100,
                                Math.max(firstValid, 10), Math.max(lastValid, 10), maxRatio),
                worstDev, firstValid > 0 ? firstValid : maxEaKJ,
                lastValid > 0 ? lastValid : maxEaKJ, worstDev, status, assessment, hypothesis);
    }

    // Outer search: try candidateBreadth deltaT values spaced by stepSize*20 K.
    // Primary = failure fraction (materials exceeding yield) — rises with deltaT.
    private static ResearchResult thermalStressSafety(double seedDeltaT, double sigmaYMPa,
                                                       SearchParams sp) {
        ResearchResult best = null;
        double bestExtreme = -1;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double deltaT = Math.max(10.0, seedDeltaT + i * sp.stepSize() * 20);
            ResearchResult r = thermalStressSafetyAt(deltaT, sigmaYMPa);
            if (r.primaryValue() > bestExtreme) { bestExtreme = r.primaryValue(); best = r; }
        }
        return best != null ? best : thermalStressSafetyAt(seedDeltaT, sigmaYMPa);
    }

    private static ResearchResult thermalStressSafetyAt(double deltaT, double sigmaYMPa) {
        double E = 200e3;
        double critAlpha = sigmaYMPa / (E * deltaT) * 1e6;
        double[] alphas = {1, 5, 10, 12, 17, 23, 25, 30, 50};
        String[] labels = {"Invar", "Steel", "Cu", "Al", "Al(soft)", "Brass", "Al-alloy", "Zn", "PVC"};
        int safe = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < alphas.length; i++) {
            double stress = alphas[i] * 1e-6 * E * deltaT;
            boolean ok = stress < sigmaYMPa;
            if (ok) safe++;
            sb.append("%s(%.0f)→%.0fMPa(%s) ".formatted(labels[i], alphas[i], stress, ok ? "✓" : "✗"));
        }
        double failFraction = (double) (alphas.length - safe) / alphas.length;
        return new ResearchResult(
                "Thermal stress safety (ΔT=%.0f K, σ_Y=%.0f MPa, E=200GPa): critical_α=%.2f µm/(m·K). Material survey: %s. %d/%d materials remain below yield."
                        .formatted(deltaT, sigmaYMPa, critAlpha, sb.toString().trim(), safe, alphas.length),
                failFraction, critAlpha, safe)
                .withHypothesis("For a fully constrained steel member with ΔT=%.0f K and σ_Y=%.0f MPa, there is a critical CTE α_c below which thermal stress remains below yield."
                        .formatted(deltaT, sigmaYMPa));
    }

    // ---- Interdisciplinary (chained cross-domain computation) ----

    private static ResearchResult computeInterdisciplinary(int topic, int param1, double param2,
                                                            SearchParams sp) {
        String eq = equationFor("INTERDISCIPLINARY", topic);
        return switch (topic) {
            case 0 -> collatzRlcDepthResonance(param1, param2, sp).withEquation(eq);
            case 1 -> primeGapDefectThermal(param1, param2, sp).withEquation(eq);
            case 2 -> resonantThermalFatigue(param1, param2, sp).withEquation(eq);
            case 3 -> happyPackingAnomaly(param1, param2, sp).withEquation(eq);
            default -> vanDerWaalsCompositeStress(param1, param2, sp).withEquation(eq);
        };
    }

    /**
     * Phase 7 F2 — closed-form equation governing a (domain, topic) pair, indexed
     * by domain string and 0-based topic index. Surfaces in the per-computation
     * H3 block's **Equation:** line in the markdown renderer. Returns null when
     * the pair is not registered (treated as "no equation line").
     */
    public static String equationFor(String domain, int topicIndex) {
        int t = Math.floorMod(topicIndex, Math.max(1, topicCount(domain)));
        return switch (domain == null ? "" : domain) {
            case "MATHEMATICS" -> switch (t) {
                case 0 -> "π₂(N) ~ 2·C₂·N/ln²(N)   (Hardy–Littlewood twin-prime conjecture)";
                case 1 -> "x_{n+1} = x_n/2 if even, else 3·x_n + 1   (Collatz 3n+1 map)";
                case 2 -> "r(2n) = #{ (p, q) : p + q = 2n, p,q prime }   (Goldbach partition count)";
                case 3 -> "s(n) = σ(n) − n   (aliquot sum; σ(n) = sum of all divisors)";
                default -> "x_{n+1} = Σ_i d_i(x_n)²   (digit-square iteration; happy iff terminates at 1)";
            };
            case "PHYSICS" -> switch (t) {
                case 0 -> "m·dv/dt = −m·g·ĵ − k·|v|·v   (quadratic-drag projectile)";
                case 1 -> "T = 2π·√(L/g)·(1 + θ²/16 + 11·θ⁴/3072 + …)   (finite-amplitude pendulum)";
                case 2 -> "(P + a/V²)·(V − b) = R·T   (Van der Waals equation of state)";
                case 3 -> "d·sin θ = m·λ   (Fraunhofer diffraction grating)";
                case 4 -> "f₀ = 1/(2π·√(L·C)),   R_c = 2·√(L/C)   (RLC critical damping)";
                case 5 -> "λ_max · T = b = 2.898×10⁻³ m·K   (Wien displacement law)";
                case 6 -> "E_n = n²·h²/(8·m·L²)   (1-D infinite-square-well energy levels)";
                case 7 -> "T² = (4π²/(G·M))·a³   (Kepler's third law)";
                case 8 -> "n₁·sin θ₁ = n₂·sin θ₂;   θ_c = arcsin(n₂/n₁) when n₁>n₂   (Snell + critical angle)";
                default -> "P = σ·A·T⁴,   σ ≈ 5.670×10⁻⁸ W·m⁻²·K⁻⁴   (Stefan-Boltzmann total flux)";
            };
            case "MATERIALS" -> switch (t) {
                case 0 -> "σ_c(V_f) = V_f·E_f·ε_y + (1 − V_f)·σ_ym   (longitudinal rule-of-mixtures at matrix yield)";
                case 1 -> "η_FCC = π/(3·√2) ≈ 0.7405;   void radii r_octa ≤ 0.4142, r_tetra ≤ 0.2247   (close-packed lattice)";
                case 2 -> "1/E_T = V_f/E_f + (1 − V_f)/E_m   (Reuss / transverse-stiffness lower bound)";
                case 3 -> "k(T) = A·exp(−Eₐ/(R·T))   (Arrhenius rate law; Van't Hoff 2×-per-10 K is a special case)";
                case 4 -> "σ_th = E·α·ΔT   (constrained-member thermal stress)";
                case 5 -> "σ_y(d) = σ_0 + k·d^(−1/2)   (Hall-Petch grain-size strengthening)";
                case 6 -> "n·λ = 2·d·sin θ   (Bragg X-ray diffraction; n = reflection order)";
                case 7 -> "η_Carnot = 1 − T_c/T_h   (Carnot reversible-engine efficiency)";
                case 8 -> "σ_a = σ'_f · (2·N_f)^b   (Basquin high-cycle fatigue power law)";
                default -> "h = 2·γ·cos θ / (ρ·g·r)   (Young-Laplace capillary rise)";
            };
            case "INTERDISCIPLINARY" -> switch (t) {
                case 0 -> "depth_C(n) → L = depth/10⁴ H,   f₀ = 1/(2π·√(L·C))   (Collatz-driven RLC resonance)";
                case 1 -> "g(n) = p_{k+1} − p_k → α = 5 + 20/g µm·K⁻¹,   σ_th = E·α·ΔT   (prime-gap defect spacing → CTE → thermal stress)";
                case 2 -> "f₀ = 1/(2π·√(L·C)),   ΔT_cycle = 10 + f₀/10³,   σ_th = E·α·ΔT_cycle   (RLC frequency → thermal-cycling rate → fatigue exposure)";
                case 3 -> "ρ_happy(N) ≈ 0.143,   r = 0.2 + 0.3·(ρ_happy/0.143),   Δη = ρ_voids·(4πr³/3)/(V_cell)   (happy density → binary-FCC void radius → packing improvement)";
                default -> "(P_c + a/V²)·(V − b) = R·T,   σ_long = P_c · [V_f·E_f + (1 − V_f)·E_m]/10³   (VdW crossover pressure → composite longitudinal stress)";
            };
            default -> null;
        };
    }

    /**
     * Phase 7 F2 — equation lookup by topic name. Resolves the topic index via
     * {@link #topicIndex(String, String)} then dispatches to {@link
     * #equationFor(String, int)}. Returns null when the topic isn't registered
     * — markdown renderer treats null as "no Equation: line."
     */
    public static String equationFor(String domain, String topicName) {
        int idx = topicIndex(domain, topicName);
        if (idx < 0) return null;
        return equationFor(domain, idx);
    }

    /**
     * Math→Physics chain: Collatz stopping time at n=param1*3 determines inductance
     * L = depth/10000 H; that L drives an RLC resonance survey.
     * Cross-domain claim: number-theoretic trajectory depth has a measurable analogue
     * in circuit resonance — deeper Collatz sequences produce lower-frequency RLC circuits.
     */
    private static ResearchResult collatzRlcDepthResonance(int param1, double param2,
                                                            SearchParams sp) {
        int best = 0;
        double bestF0 = 0;
        int bestDepth = 0, bestN = param1 * 3;
        StringBuilder mathSb = new StringBuilder();
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int n = Math.max(2, param1 * 3 + (int)(i * sp.stepSize() * 10));
            int depth = collatzSteps(n);
            double L = Math.max(1e-5, depth / 10000.0);
            double C = 1e-6 + param2 * 1e-7;
            double f0 = 1.0 / (2 * Math.PI * Math.sqrt(L * C));
            if (f0 > bestF0) { bestF0 = f0; bestDepth = depth; bestN = n; }
            if (i < 4) mathSb.append("n=%d→depth=%d→f₀=%.0fHz ".formatted(n, depth, f0));
        }
        double L_best = Math.max(1e-5, bestDepth / 10000.0);
        double C = 1e-6 + param2 * 1e-7;
        double critR = 2 * Math.sqrt(L_best / C);
        boolean deepMeansLow = bestDepth > 50 && bestF0 < 5000;
        ClaimStatus status = deepMeansLow ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = "Collatz depth %d at n=%d maps to L=%.4f H; resonant frequency f₀=%.0f Hz, R_crit=%.2f Ω. %s"
                .formatted(bestDepth, bestN, L_best, bestF0, critR,
                        deepMeansLow ? "Deep trajectories yield sub-5 kHz circuits — cross-domain relationship confirmed."
                                     : "Cross-domain relationship is suggestive but not monotone in the searched range.");
        return new ResearchResult(
                "Collatz→RLC chain (param1=%d, C=%.2e F): best_n=%d depth=%d → L=%.4f H → f₀=%.0f Hz, R_crit=%.2f Ω. Sample chain: %s"
                        .formatted(param1, C, bestN, bestDepth, L_best, bestF0, critR, mathSb.toString().trim()),
                bestF0, bestDepth, critR, bestF0, status, assessment)
                .withHypothesis("Collatz stopping depth at n=%d (depth=%d) maps to circuit inductance L=%.4f H; the resulting RLC resonance f₀=%.0f Hz quantifies how number-theoretic complexity translates to physical frequency."
                        .formatted(bestN, bestDepth, L_best, bestF0));
    }

    /**
     * Math→Materials chain: prime constellation max gap in a window seeded by param1
     * is interpreted as a crystal defect spacing (in µm), driving a thermal stress model.
     */
    private static ResearchResult primeGapDefectThermal(int param1, double param2,
                                                         SearchParams sp) {
        double bestStress = 0;
        int bestGap = 0, bestStart = param1 * 10;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int start = Math.max(2, param1 * 10 + (int)(i * sp.stepSize() * 50));
            int end = start + 60;
            int maxGap = 2;
            int prev = -1;
            for (int n = start; n < end; n++) {
                if (isPrime(n)) { if (prev > 0) maxGap = Math.max(maxGap, n - prev); prev = n; }
            }
            // Defect spacing in µm = maxGap; CTE increases with defect density (1/spacing)
            double alpha = 5.0 + 20.0 / maxGap;
            double deltaT = 200 + param2 * 30;
            double E = 200e3;
            double stress = alpha * 1e-6 * E * deltaT;
            if (stress > bestStress) { bestStress = stress; bestGap = maxGap; bestStart = start; }
            if (i < 4) sb.append("[%d,%d)gap=%d→σ=%.0fMPa ".formatted(start, end, maxGap, stress));
        }
        double threshold = 200.0 + param2 * 30;
        boolean yieldExceeded = bestStress > threshold;
        ClaimStatus status = yieldExceeded ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = "Best prime gap %d in range starting %d → effective CTE α=%.2f µm/(m·K) → stress=%.0f MPa (yield threshold ~%.0f MPa). %s"
                .formatted(bestGap, bestStart, 5.0 + 20.0 / bestGap, bestStress, threshold,
                        yieldExceeded ? "Yield exceeded — prime-gap-derived defect spacing produces critical thermal loading."
                                      : "Stress below yield — this prime-gap spacing is a safe defect density.");
        return new ResearchResult(
                "Prime-gap→Thermal chain: sample windows: %s Best: gap=%d → α=%.2f µm/(m·K) → σ=%.0f MPa at ΔT=%.0f K."
                        .formatted(sb.toString().trim(), bestGap, 5.0 + 20.0 / bestGap, bestStress,
                                200 + param2 * 30),
                bestStress, bestGap, 5.0 + 20.0 / bestGap, bestStress, status, assessment)
                .withHypothesis("Prime constellation gaps near %d map to crystal defect spacings; wider gaps (rarer primes) produce lower effective CTE and safer thermal stress."
                        .formatted(param1 * 10));
    }

    /**
     * Physics→Materials chain: RLC resonant frequency for given L (param1-derived) and C
     * becomes the thermal cycling frequency; materials are tested against fatigue failure.
     * High-frequency cycling exhausts thermal fatigue allowance faster.
     */
    private static ResearchResult resonantThermalFatigue(int param1, double param2,
                                                          SearchParams sp) {
        // Use NEGATIVE_INFINITY so the very first candidate always wins, avoiding zero-sentinel defaults
        // when no material fails (fatigueFrac=0 for every candidate is a valid null result, not a bug).
        double bestFatigueFrac = Double.NEGATIVE_INFINITY;
        double bestF0 = 0, bestL = 0;
        StringBuilder sb = new StringBuilder();
        double[] alphas = {1, 5, 10, 12, 17, 23, 25, 30, 50};
        String[] labels = {"Invar", "Steel", "Cu", "Al", "Al(soft)", "Brass", "Al-alloy", "Zn", "PVC"};
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double L = Math.max(1e-5, (0.001 + param1 / 10000.0) / (1.0 + i * sp.stepSize() * 0.2));
            double C = 1e-6 + param2 * 1e-7;
            double f0 = 1.0 / (2 * Math.PI * Math.sqrt(L * C));
            // Thermal cycling: each oscillation is one load cycle; material fatigue limit ~10^6 cycles
            // Higher f0 → fatigue limit reached sooner; fatigue fraction = f0 / 1e6 (scaled)
            double deltaT = 10.0 + f0 / 1000.0;
            double E = 200e3;
            int failed = 0;
            for (double alpha : alphas) {
                double stress = alpha * 1e-6 * E * deltaT;
                if (stress > 200) failed++;
            }
            double fatigueFrac = (double) failed / alphas.length;
            if (fatigueFrac > bestFatigueFrac) {
                bestFatigueFrac = fatigueFrac; bestF0 = f0; bestL = L;
            }
            if (i < 4) sb.append("L=%.4fH→f₀=%.0fHz→fail=%d/%d ".formatted(L, f0, failed, alphas.length));
        }
        if (bestFatigueFrac < 0) bestFatigueFrac = 0; // empty candidateBreadth guard
        boolean criticalFatigue = bestFatigueFrac >= 0.5;
        ClaimStatus status = criticalFatigue ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = "At f₀=%.0f Hz (L=%.4f H), %.0f%% of materials exceed 200 MPa yield under resonance-driven thermal loading. %s"
                .formatted(bestF0, bestL, bestFatigueFrac * 100,
                        criticalFatigue ? "Majority of materials reach critical fatigue — resonant coupling is structurally significant."
                                        : "Minority of materials fail — resonance-driven fatigue is material-selective.");
        return new ResearchResult(
                "RLC→Fatigue chain: sample circuits: %s Best: f₀=%.0f Hz (L=%.4f H) → thermal ΔT=%.1f K/cycle → %.0f%% materials exceed yield."
                        .formatted(sb.toString().trim(), bestF0, bestL, 10 + bestF0 / 1000.0,
                                bestFatigueFrac * 100),
                bestFatigueFrac, bestF0, bestL, bestFatigueFrac, status, assessment)
                .withHypothesis("RLC resonant frequency for L derived from param1=%d governs the thermal cycling rate; higher frequencies push more materials past their 200 MPa yield threshold."
                        .formatted(param1));
    }

    /**
     * Math→Materials chain: happy number density in a window seeded by param1 modulates
     * the small-sphere radius in binary FCC sphere packing.
     * Happy density above the 14.3% asymptotic is mapped to a denser packing improvement.
     */
    private static ResearchResult happyPackingAnomaly(int param1, double param2, SearchParams sp) {
        double bestImprovement = 0;
        double bestDensity = 0.143;
        int bestStart = param1 * 7;
        StringBuilder sb = new StringBuilder();
        double etaFCC = Math.PI / (3 * Math.sqrt(2));
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int start = Math.max(1, param1 * 7 + (int)(i * sp.stepSize() * 200));
            int count = 80 + (int)(param2 * 20);
            int happy = 0;
            for (int n = start; n < start + count; n++) if (isHappy(n)) happy++;
            double density = (double) happy / count;
            // Density deviation drives the small-sphere radius: r = 0.2 + 0.3*(density/0.143)
            double r = Math.min(0.45, 0.2 + 0.3 * (density / 0.143));
            double voidVol = r <= 0.4142 ? (4.0 / 3) * Math.PI * Math.pow(r, 3) : 0;
            double improvement = (voidVol * etaFCC / ((4.0 / 3) * Math.PI)) * 100;
            if (improvement > bestImprovement) {
                bestImprovement = improvement; bestDensity = density; bestStart = start;
            }
            if (i < 4) sb.append("[%d]happy=%.3f→r=%.3f→+%.2f%% ".formatted(start, density, r, improvement));
        }
        double r_best = Math.min(0.45, 0.2 + 0.3 * (bestDensity / 0.143));
        boolean anomalous = bestDensity > 0.155 || bestDensity < 0.130;
        ClaimStatus status = anomalous ? ClaimStatus.SUPPORTED : ClaimStatus.OBSERVATIONAL;
        String assessment = "Best window [%d]: happy density=%.3f → small-sphere r=%.3f → packing +%.3f%% over FCC. %s"
                .formatted(bestStart, bestDensity, r_best, bestImprovement,
                        anomalous ? "Density anomaly detected — above/below 14.3% asymptotic; modulated packing improvement is statistically notable."
                                  : "Density near asymptotic — packing improvement follows expected baseline; no strong anomaly.");
        return new ResearchResult(
                "Happy→Packing chain: sample windows: %s Best: density=%.3f at [%d] → r=%.3f → FCC improvement=%.3f%%."
                        .formatted(sb.toString().trim(), bestDensity, bestStart, r_best, bestImprovement),
                bestImprovement / 100.0, bestDensity, r_best, bestImprovement / 100.0, status, assessment)
                .withHypothesis("Happy number density near %d modulates small-sphere radius in binary FCC packing; windows with density anomalies above the 14.3%% asymptotic produce measurably different packing efficiency."
                        .formatted(param1 * 7));
    }

    /**
     * Physics→Materials chain: Van der Waals crossover pressure (where CO₂ departs >1% from ideal)
     * at temperature T derived from param1 is used as a proxy mechanical pressure loading a fibre composite.
     */
    private static ResearchResult vanDerWaalsCompositeStress(int param1, double param2,
                                                              SearchParams sp) {
        double bestStress = 0;
        double bestCrossoverP = -1, bestT = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double T = Math.max(150, 200 + param1 * 3.0 + i * sp.stepSize() * 20);
            double a = 3.640, b = 0.04267, R = 0.08206;
            double crossoverP = -1;
            for (int p = 1; p <= 100; p++) {
                double P = p, Videal = R * T / P, V = Videal;
                for (int iter = 0; iter < 20; iter++) {
                    double f  = (P + a / (V * V)) * (V - b) - R * T;
                    double df = (P + a / (V * V)) - 2 * a * (V - b) / (V * V * V);
                    if (Math.abs(df) < 1e-15) break;
                    V -= f / df;
                    if (V <= b) { V = b * 1.01; break; }
                }
                double dev = Math.abs(V - Videal) / Videal;
                if (dev > 0.01 && crossoverP < 0) { crossoverP = p; break; }
            }
            // Treat crossover pressure (atm) as MPa loading on a composite
            double Ef = 200e3, Em = 3e3, Vf = 0.3 + param2 * 0.05;
            double EL = Vf * Ef + (1 - Vf) * Em;
            double stress = crossoverP > 0 ? crossoverP * EL / 1000.0 : 0;
            if (stress > bestStress) { bestStress = stress; bestCrossoverP = crossoverP; bestT = T; }
            if (i < 4 && crossoverP > 0) sb.append("T=%.0fK→P_c=%.0fatm→σ=%.0fMPa ".formatted(T, crossoverP, stress));
        }
        boolean highStress = bestStress > 3000;
        ClaimStatus status = highStress ? ClaimStatus.SUPPORTED : ClaimStatus.OBSERVATIONAL;
        String assessment = "At T=%.0f K, VdW crossover at P_c=%.0f atm → composite longitudinal stress=%.0f MPa (Vf=%.2f). %s"
                .formatted(bestT, bestCrossoverP > 0 ? bestCrossoverP : 0, bestStress,
                        0.3 + param2 * 0.05,
                        highStress ? "Crossover-driven stress exceeds 3 GPa — Van der Waals pressure onset is mechanically critical for this composite geometry."
                                   : "Stress below 3 GPa threshold — VdW crossover at this temperature does not critically load the composite.");
        return new ResearchResult(
                "VdW→Composite chain: sample results: %s Best: T=%.0f K → P_c=%.0f atm → longitudinal σ=%.0f MPa."
                        .formatted(sb.toString().trim(), bestT, bestCrossoverP > 0 ? bestCrossoverP : 0, bestStress),
                bestStress, bestCrossoverP > 0 ? bestCrossoverP : 0, bestT, bestStress, status, assessment)
                .withHypothesis("Van der Waals crossover pressure for CO₂ at T=%.0f K, treated as a mechanical loading analogue, drives longitudinal stress in a %.0f%% fibre-volume composite."
                        .formatted(bestT, (0.3 + param2 * 0.05) * 100));
    }

    // ---- Helpers (package-private so DynamicComputeEngine can use them) ----

    static boolean isPrime(int n) {
        if (n < 2) return false;
        if (n < 4) return true;
        if (n % 2 == 0 || n % 3 == 0) return false;
        for (int i = 5; (long) i * i <= n; i += 6) {
            if (n % i == 0 || n % (i + 2) == 0) return false;
        }
        return true;
    }

    static boolean isHappy(int n) {
        long x = n;
        int steps = 0;
        while (x != 1 && x != 4 && steps < 1000) {
            long sum = 0;
            while (x > 0) { long d = x % 10; sum += d * d; x /= 10; }
            x = sum;
            steps++;
        }
        return x == 1;
    }

    static int collatzSteps(int n) {
        long x = Math.max(1, n);
        int steps = 0;
        while (x != 1 && steps < 10_000) { x = (x % 2 == 0) ? x / 2 : 3 * x + 1; steps++; }
        return steps;
    }

    static long sigmaSum(int n) {
        if (n <= 1) return 0;
        long sigma = 1;
        for (long d = 2; d * d <= n; d++) {
            if (n % d == 0) { sigma += d; if (d != n / d) sigma += n / d; }
        }
        return sigma;
    }

    private static boolean nearlyEqual(double a, double b, double tolerance) {
        return Math.abs(a - b) <= tolerance;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Format a proportion estimated from n samples with 95% CI.
     * Decimal places chosen so the CI has ~2 significant figures.
     * e.g., p=0.0467, n=150 → "0.047 ± 0.033 (95% CI, n=150)"
     */
    private static String fmtProp(double p, int n) {
        if (n <= 0) return String.format("%.4f", p);
        double se = Math.sqrt(Math.max(0, p * (1 - p)) / n);
        double ci = 1.96 * se;
        int dp = (se > 1e-9) ? Math.max(0, -(int) Math.floor(Math.log10(se))) : 4;
        String fmt = "%." + dp + "f";
        return (fmt + " ± " + fmt + " (95%% CI, n=%d)").formatted(p, ci, n);
    }

    /**
     * Format a mean ± SE from a Welford M2 accumulator and sample count.
     * e.g., mean=70.3, m2=54000, n=60 → "70 ± 4 (SE, n=60)"
     */
    private static String fmtMean(double mean, double m2, int n) {
        double se = (n > 1) ? Math.sqrt(m2 / (n - 1) / n) : 0;
        int dp = (se > 1e-9) ? Math.max(0, -(int) Math.floor(Math.log10(se))) : 2;
        String fmt = "%." + dp + "f";
        return (fmt + " ± " + fmt + " (SE, n=%d)").formatted(mean, se, n);
    }
}
