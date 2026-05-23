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
            "Happy Number Distribution",
            // Phase 7 F3.4 — 5 new mathematics topics.
            "Mertens Partial Sums",
            "Divisor Sum Average",
            "Riemann Euler-Product Convergence",
            "Stern-Brocot Tree Depth Survey",
            "Squarefree Density"
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
            "Van-der-Waals Composite Crossover",   // Physics→Materials: VdW pressure → composite stress
            // Phase 7 F3.5 — 3 new closed-form INTERDISCIPLINARY topics.
            // Each carries a physical mechanism so the cross-domain bridge is legitimate.
            "Hall-Effect Carrier Sign",           // Physics→Materials: Lorentz force → V_H sign
            "Schottky-Barrier Thermionic Emission", // Math→Physics: Boltzmann exp(-φ/kT) → J(T)
            "Acoustic Doppler Frequency Shift"     // Physics↔Physics: relative motion → f shift
    };

    // ---- Phase 7 FX: 5 new domains beyond the original 4 ----
    // Each domain ships with 5 closed-form topics. ARCHITECTURE_TERRESTRIAL and
    // ARCHITECTURE_UNDERWATER are deliberately parameterised — never hard-code
    // Earth's gravity or water's properties; both are inputs.

    private static final String[] BIOLOGY_TOPICS = {
            "Verhulst Logistic Growth",
            "Lotka-Volterra Predator-Prey",
            "Michaelis-Menten Enzyme Kinetics",
            "Kleiber Allometric Scaling",
            "Hardy-Weinberg Equilibrium"
    };

    private static final String[] CHEMISTRY_TOPICS = {
            "Henderson-Hasselbalch Buffer pH",
            "Nernst Electrochemical Potential",
            "Beer-Lambert Absorbance",
            "Equilibrium Constant Kc",
            "Real-Gas Z-Factor"
    };

    /** FX.3 — ARCHITECTURE_TERRESTRIAL: structures on any planetary surface. Generic
     *  over gravity g and atmospheric density ρ_atm; NO Earth-specific constants in
     *  the compute methods. */
    private static final String[] ARCHITECTURE_TERRESTRIAL_TOPICS = {
            "Beam Deflection Under Self Weight",
            "Atmospheric Wind Load Pressure",
            "Foundation Bearing Capacity",
            "Thermal Mass Time Lag",
            "Daylight Factor at Window Aperture"
    };

    /** FX.4 — ARCHITECTURE_LIQUID: structures submerged in any liquid. Generic over
     *  liquid density ρ_liq, viscosity μ, surface tension γ, and local gravity g.
     *  Findings apply to water, methane lakes (Titan), ammonia oceans, sub-surface
     *  hydrocarbons — NOT pinned to Earth-water properties. */
    private static final String[] ARCHITECTURE_LIQUID_TOPICS = {
            "Hydrostatic Pressure With Depth",
            "Buoyant Lift Capacity",
            "Hull Buckling Under External Pressure",
            "Hydrodynamic Drag on Submerged Body",
            "Dissolved-Gas Solubility"
    };

    /** FX.5 — ARCHITECTURE_SPACE: structures in vacuum / micro-gravity. Inputs include
     *  radiation flux, micrometeoroid density, orbit altitude, station radius. Already
     *  generic by construction (no atmospheric or liquid medium). */
    private static final String[] ARCHITECTURE_SPACE_TOPICS = {
            "Radiation Shielding Mass",
            "Vacuum Thermal Radiative Balance",
            "Micrometeoroid Impact Flux",
            "Centrifugal Artificial Gravity Radius",
            "Orbital Decay From Atmospheric Drag"
    };

    // ---- Title tables (2 options per topic for variety) ----

    private static final String[][] MATH_TITLES = {
            {"Twin Prime Clustering in Bounded Intervals", "Prime Constellation Density Near N"},
            {"Collatz Stopping Time Distribution Over Integer Ranges", "Trajectory Depth in 3n+1 Sequences"},
            {"Goldbach Partition Counts for Even Integers", "Partition-Poor Even Numbers: A Local Survey"},
            {"Aliquot Sequence Termination Rates in Integer Ranges", "Cycle Detection in Iterated Proper Divisor Sums"},
            {"Happy Number Density and Digit-Square Convergence Patterns", "Local Verification of the 14.3% Happy Number Conjecture"},
            // Phase 7 F3.4 — titles for the 5 new mathematics topics.
            {"Empirical Growth of the Mertens Function in Bounded Ranges", "Möbius Partial-Sum Oscillation vs the √x Heuristic"},
            {"Average Order of σ(n) Compared to the (π²/12)·N Asymptotic", "Divisor-Sum Mean Drift Over Integer Windows"},
            {"Empirical Closeness of Σ 1/nˢ and Π 1/(1-p⁻ˢ) for s>1", "Riemann ζ-Function Truncation vs the Euler-Product Estimate"},
            {"Depth of Rationals in the Stern-Brocot Tree as a Function of Denominator", "Continued-Fraction Depth Survey Over Sampled p/q Pairs"},
            {"Local Density of Squarefree Integers Compared to the 6/π² Asymptotic", "Squarefree Counting in Bounded Intervals"}
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
            {"Van der Waals Pressure Threshold Governing Composite Stress Onset", "Real-Gas Behaviour as a Mechanical Loading Analogue"},
            // Phase 7 F3.5 — titles for the 3 new interdisciplinary topics.
            {"Hall Voltage Sign as a Diagnostic for Charge-Carrier Type", "Lorentz-Force Recovery of Carrier Density from Cross-Bar Probes"},
            {"Schottky Diode Saturation Current vs Barrier Height and Temperature", "Thermionic-Emission Threshold Across Realistic Barrier Configurations"},
            {"Acoustic Doppler Frequency Shift for Mixed Source-Observer Velocities", "Wave-Speed-Bounded Doppler Limits in a Single Medium"}
    };

    // ---- Phase 7 FX.1 BIOLOGY titles ----
    private static final String[][] BIOLOGY_TITLES = {
            {"Population Growth Under the Verhulst Logistic Model",                  "Carrying-Capacity Saturation in a Bounded Habitat"},
            {"Lotka-Volterra Predator-Prey Oscillation Stability",                   "Coupled-ODE Cycle Amplitudes Across α/β/γ/δ Parameter Sweeps"},
            {"Michaelis-Menten Enzyme Saturation and K_m Recovery",                  "Initial-Rate vs Substrate Concentration in Steady-State Catalysis"},
            {"Kleiber's Three-Quarters Allometric Scaling Across Body Masses",       "Metabolic-Rate Power Law Tested Against Synthetic Organism Data"},
            {"Hardy-Weinberg Equilibrium Persistence Under Random Mating",           "Allele-Frequency Drift Across Generations With No Selection Pressure"}
    };

    // ---- Phase 7 FX.2 CHEMISTRY titles ----
    private static final String[][] CHEMISTRY_TITLES = {
            {"Buffer pH Across Acid-Base Concentration Ratios",                       "Henderson-Hasselbalch Recovery of pKₐ from Titration Curves"},
            {"Nernst Cell Potential as a Function of Concentration",                  "Electrochemical Activity Coefficient Survey at Reduced Concentrations"},
            {"Beer-Lambert Absorbance Linearity Across Path Length and Concentration","Molar Absorptivity ε Recovery from Tabulated Spectra"},
            {"Equilibrium Constant Kc Stability Across Temperature Perturbations",    "Le-Chatelier Sensitivity Measured Through Kc Ratios"},
            {"Compressibility Factor Z for Real Gases vs Ideal-Gas Baseline",         "Pitzer-Curl Z-Factor Recovery Across Reduced-State Coordinates"}
    };

    // ---- Phase 7 FX.3 ARCHITECTURE_TERRESTRIAL titles (generic across planets) ----
    private static final String[][] ARCHITECTURE_TERRESTRIAL_TITLES = {
            {"Cantilever Beam Deflection Under Self-Weight Across Gravity Regimes",   "Maximum Span Limit Where Self-Weight Deflection Stays Within Tolerance"},
            {"Wind Load on Vertical Facades Across Atmospheric Densities",            "Drag-Pressure Sensitivity to Local Atmospheric Composition"},
            {"Terzaghi Bearing Capacity Under Variable Surface Gravity",              "Foundation Sizing for Structures on Low-g Terrestrial Bodies"},
            {"Thermal Mass Time-Lag for Building Envelopes Across Climates",          "Convective-Coefficient Sensitivity of Wall Equilibration Times"},
            {"Daylight-Factor Tradeoff Between Aperture Area and Sky Visibility",     "Internal Illuminance as a Fraction of External Sky Conditions"}
    };

    // ---- Phase 7 FX.4 ARCHITECTURE_LIQUID titles (generic across any liquid) ----
    private static final String[][] ARCHITECTURE_LIQUID_TITLES = {
            {"Hydrostatic Pressure Profile in an Arbitrary Liquid Column",            "Depth-Pressure Scaling Across Liquids of Varied Density"},
            {"Buoyant Lift Capacity for a Submerged Structure",                       "Archimedes-Principle Survey Across Liquid-Density Regimes"},
            {"Critical Buckling Pressure for a Thin-Walled Submerged Hull",          "Hull-Wall Thickness Threshold for Deep-Liquid Habitats"},
            {"Hydrodynamic Drag on a Submerged Body Across Viscosity Regimes",        "Reynolds-Number Sensitivity of Drag Coefficient Across Liquids"},
            {"Henry's-Law Dissolved-Gas Solubility Across Liquid Hosts",              "Gas-Equilibrium Pressure for Habitat Atmospheres in Liquid Volumes"}
    };

    // ---- Phase 7 FX.5 ARCHITECTURE_SPACE titles ----
    private static final String[][] ARCHITECTURE_SPACE_TITLES = {
            {"Areal-Density Requirements for Radiation Shielding of Crewed Modules",  "Mass-Per-Area Sweep Across Solar-Particle Event Regimes"},
            {"Radiative Equilibrium Temperature of an Insulated Module in Vacuum",    "Stefan-Boltzmann Heat Rejection vs Cabin Internal Generation"},
            {"Annual Cumulative Micrometeoroid Impact Flux on a Habitat Module",      "Sub-mm Particle Penetration Probability for a Year-Long Mission"},
            {"Centrifugal Spin-Habitat Radius for Comfortable Artificial Gravity",    "Radius vs Rotation-Rate Threshold for Coriolis-Tolerable Gait"},
            {"Orbital Decay Rate From Residual Atmospheric Drag at LEO",              "Time-to-Reentry as a Function of Ballistic Coefficient and Altitude"}
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
            // Phase 7 FX.1 — BIOLOGY.
            case "BIOLOGY"           -> computeBiology(t, param1, param2, sp);
            // Phase 7 FX.2 — CHEMISTRY.
            case "CHEMISTRY"         -> computeChemistry(t, param1, param2, sp);
            // Phase 7 FX.3 — ARCHITECTURE_TERRESTRIAL (generic across planetary g + atmosphere).
            case "ARCHITECTURE_TERRESTRIAL" -> computeArchTerrestrial(t, param1, param2, sp);
            // Phase 7 FX.4 — ARCHITECTURE_LIQUID (generic across any liquid).
            case "ARCHITECTURE_LIQUID"      -> computeArchLiquid(t, param1, param2, sp);
            // Phase 7 FX.5 — ARCHITECTURE_SPACE.
            case "ARCHITECTURE_SPACE"       -> computeArchSpace(t, param1, param2, sp);
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
            // Phase 7 FX — new domains beyond the original 4.
            case "BIOLOGY"           -> BIOLOGY_TOPICS.length;
            case "CHEMISTRY"         -> CHEMISTRY_TOPICS.length;
            case "ARCHITECTURE_TERRESTRIAL" -> ARCHITECTURE_TERRESTRIAL_TOPICS.length;
            case "ARCHITECTURE_LIQUID"      -> ARCHITECTURE_LIQUID_TOPICS.length;
            case "ARCHITECTURE_SPACE"       -> ARCHITECTURE_SPACE_TOPICS.length;
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
            // Phase 7 F3.4 — new mathematics topics.
            case "MATHEMATICS:5" -> 10.0;    // Mertens |M(x)|/√x ratio (~0.1-3.0; ratio capped at ~10)
            case "MATHEMATICS:6" -> 0.05;    // Divisor-sum deviation from (π²/12)·N (~0.01-0.10)
            case "MATHEMATICS:7" -> 0.05;    // Riemann ratio deviation from 1.0 (~0.005-0.10)
            case "MATHEMATICS:8" -> 30.0;    // Stern-Brocot max depth (~5-50)
            case "MATHEMATICS:9" -> 0.05;    // Squarefree density deviation (~0.005-0.10)
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
            // Phase 7 F3.5 — new interdisciplinary topics.
            case "INTERDISCIPLINARY:5"   -> 0.05;    // Hall voltage in V (~1e-4 to 1e-1 V)
            case "INTERDISCIPLINARY:6"   -> 1.0e3;   // Schottky J in A/cm² (~1e-3 to 1e4)
            case "INTERDISCIPLINARY:7"   -> 100.0;   // Doppler |f' − f| in Hz (~5-300)
            // Phase 7 FX.1 — BIOLOGY topics.
            case "BIOLOGY:0"         -> 0.5;     // Verhulst N(t)/K steady-state error (~0-1)
            case "BIOLOGY:1"         -> 0.6;     // Lotka-Volterra cycle-amplitude ratio (~0.2-1.0)
            case "BIOLOGY:2"         -> 0.5;     // Michaelis-Menten K_m recovery error (~0.05-0.5)
            case "BIOLOGY:3"         -> 0.10;    // Kleiber exponent deviation from 0.75 (~0.01-0.10)
            case "BIOLOGY:4"         -> 0.05;    // Hardy-Weinberg allele-frequency drift (~0.005-0.10)
            // Phase 7 FX.2 — CHEMISTRY topics.
            case "CHEMISTRY:0"       -> 0.5;     // Henderson-Hasselbalch pKₐ recovery error (~0.05-1.0)
            case "CHEMISTRY:1"       -> 0.2;     // Nernst E_cell deviation in V (~0.01-0.3)
            case "CHEMISTRY:2"       -> 0.05;    // Beer-Lambert ε recovery deviation (~0.005-0.10)
            case "CHEMISTRY:3"       -> 0.3;     // Equilibrium Kc drift across T (~0.05-0.5)
            case "CHEMISTRY:4"       -> 0.1;     // Z-factor |1 − Z| (~0.01-0.20)
            // Phase 7 FX.3 — ARCHITECTURE_TERRESTRIAL topics (generic over g, ρ_atm).
            case "ARCHITECTURE_TERRESTRIAL:0" -> 0.1;    // beam deflection ratio δ/L (~0.001-0.10)
            case "ARCHITECTURE_TERRESTRIAL:1" -> 5000.0; // wind pressure in Pa (~10-10000)
            case "ARCHITECTURE_TERRESTRIAL:2" -> 500.0;  // bearing capacity kPa (~10-2000)
            case "ARCHITECTURE_TERRESTRIAL:3" -> 12.0;   // thermal time-lag hours (~1-24)
            case "ARCHITECTURE_TERRESTRIAL:4" -> 5.0;    // daylight factor % (~0.5-20)
            // Phase 7 FX.4 — ARCHITECTURE_LIQUID topics (generic over ρ_liq, μ, γ).
            case "ARCHITECTURE_LIQUID:0"      -> 1.0e6;  // hydrostatic P in Pa (~1e3-1e7)
            case "ARCHITECTURE_LIQUID:1"      -> 1.0e5;  // buoyant force in N (~1e2-1e7)
            case "ARCHITECTURE_LIQUID:2"      -> 5.0e6;  // hull buckling P_crit in Pa (~1e4-1e8)
            case "ARCHITECTURE_LIQUID:3"      -> 1.0e4;  // drag force in N (~1-1e6)
            case "ARCHITECTURE_LIQUID:4"      -> 0.5;    // dissolved-gas concentration mol/L (~0.01-1.0)
            // Phase 7 FX.5 — ARCHITECTURE_SPACE topics.
            case "ARCHITECTURE_SPACE:0"       -> 100.0;  // shielding areal density g/cm² (~5-300)
            case "ARCHITECTURE_SPACE:1"       -> 300.0;  // equilibrium T in K (~80-500)
            case "ARCHITECTURE_SPACE:2"       -> 100.0;  // annual impact count (~1-1000)
            case "ARCHITECTURE_SPACE:3"       -> 50.0;   // habitat radius m (~5-500)
            case "ARCHITECTURE_SPACE:4"       -> 1000.0; // orbital decay days (~10-10000)
            default              -> 1.0;     // MATHEMATICS:0 hlDeviation, MATERIALS:4 failFrac — already well-ranged
        };
    }

    /** Reverse-lookup: returns the 0-based topic index for a given topic name, or -1 if not found. */
    public static int topicIndex(String domain, String topicName) {
        String[] names = topicArrayFor(domain);
        for (int i = 0; i < names.length; i++) {
            if (names[i].equalsIgnoreCase(topicName)) return i;
        }
        return -1;
    }

    public static String topicName(String domain, int topicIndex) {
        String[] names = topicArrayFor(domain);
        return names[Math.floorMod(topicIndex, names.length)];
    }

    /**
     * Phase 7 FX — centralised topic-array lookup. The switch grows to one case
     * per registered domain; an unknown domain falls back to MATHEMATICS so
     * legacy callers that pass uppercase/non-canonical strings continue to
     * receive a sensible default.
     */
    private static String[] topicArrayFor(String domain) {
        return switch (domain == null ? "" : domain) {
            case "PHYSICS"           -> PHYSICS_TOPICS;
            case "MATERIALS"         -> MATERIALS_TOPICS;
            case "INTERDISCIPLINARY" -> INTERDISCIPLINARY_TOPICS;
            case "MATHEMATICS"       -> MATH_TOPICS;
            // Phase 7 FX — new domains.
            case "BIOLOGY"           -> BIOLOGY_TOPICS;
            case "CHEMISTRY"         -> CHEMISTRY_TOPICS;
            case "ARCHITECTURE_TERRESTRIAL" -> ARCHITECTURE_TERRESTRIAL_TOPICS;
            case "ARCHITECTURE_LIQUID"      -> ARCHITECTURE_LIQUID_TOPICS;
            case "ARCHITECTURE_SPACE"       -> ARCHITECTURE_SPACE_TOPICS;
            default                  -> MATH_TOPICS;
        };
    }

    public static String generateTitle(String domain, int topicIndex, java.util.Random rng) {
        String[][] table = switch (domain) {
            case "PHYSICS"           -> PHYSICS_TITLES;
            case "MATERIALS"         -> MATERIALS_TITLES;
            case "INTERDISCIPLINARY" -> INTERDISCIPLINARY_TITLES;
            // Phase 7 FX — new domains.
            case "BIOLOGY"           -> BIOLOGY_TITLES;
            case "CHEMISTRY"         -> CHEMISTRY_TITLES;
            case "ARCHITECTURE_TERRESTRIAL" -> ARCHITECTURE_TERRESTRIAL_TITLES;
            case "ARCHITECTURE_LIQUID"      -> ARCHITECTURE_LIQUID_TITLES;
            case "ARCHITECTURE_SPACE"       -> ARCHITECTURE_SPACE_TITLES;
            default                  -> MATH_TITLES;
        };
        String[] row = table[Math.floorMod(topicIndex, table.length)];
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
            case 4 -> happyNumbers(param1 * 7, 150, sp).withEquation(eq);
            // Phase 7 F3.4 — new mathematics topics.
            case 5 -> mertensPartialSums(Math.max(10, param1 * 5), 200, sp).withEquation(eq);
            case 6 -> divisorSumAverage(Math.max(10, param1 * 4), 150, sp).withEquation(eq);
            case 7 -> riemannEulerProduct(1.5 + param2 / 9.0, sp).withEquation(eq);
            case 8 -> sternBrocotDepth(Math.max(2, param1), sp).withEquation(eq);
            default -> squarefreeDensity(Math.max(10, param1 * 6), 200, sp).withEquation(eq);
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
        double twinDensity  = (double) twins / Math.max(1, primes.size() - 1);
        double primeDensity = (double) primes.size() / (end - start);
        double lnN = Math.log(Math.max(2, start));
        double hlPrediction = 1.320323631693739 / (lnN * lnN) * width;
        double hlDeviation  = Math.abs(twins - hlPrediction) / Math.max(1.0, hlPrediction);
        return composePrimeConstellations(start, end, primes.size(), twins, cousins, sexy,
                maxGap, hlPrediction, hlDeviation, primeDensity, twinDensity);
    }

    /**
     * Phase 28 §28.6 — shared prose composer for MATHEMATICS topic 0
     * (primeConstellations). Both the in-process path
     * ({@link #primeConstellationsAt}) and the sidecar-cutover path
     * ({@link SidecarResultComposer#composeFromDetail}) call this with the
     * same numeric inputs, so the {@code findings/assessment/hypothesis}
     * prose is byte-identical by construction — there is no second copy of
     * the {@code String.format} templates to drift. The numeric arguments
     * are exactly the fields the sidecar emits in its {@code detail} map
     * (windowStart, windowEnd, primeCount, twinCount, cousinCount,
     * sexyCount, maxGap, hlPrediction, hlDeviation, primeDensity,
     * twinDensity); the claim-status band is recomputed here from
     * {@code hlDeviation} so this method is fully self-contained.
     */
    static ResearchResult composePrimeConstellations(int start, int end, int primeCount,
            int twins, int cousins, int sexy, int maxGap,
            double hlPrediction, double hlDeviation, double primeDensity, double twinDensity) {
        int pairs = Math.max(1, primeCount - 1);
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
                        .formatted(start, end, primeCount, fmtProp(primeDensity, end - start),
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
        double longFraction = (double) longTraj / count;
        int end = start + count;
        return composeCollatzStoppingTimes(start, end, maxSteps, maxStepsAt,
                wMean, wM2, count, longFraction, allTerminated);
    }

    /**
     * Phase 28 §28.6b — shared prose composer for MATHEMATICS:1 (collatz).
     * {@code wMean}/{@code wM2} are the Welford running mean + M2 of the
     * stopping-step counts (the sidecar emits both so {@code fmtMean} can be
     * reproduced JVM-side).
     */
    static ResearchResult composeCollatzStoppingTimes(int start, int end, int maxSteps,
            int maxStepsAt, double wMean, double wM2, int count,
            double longFraction, boolean allTerminated) {
        double avgSteps = wMean;
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
        return composeGoldbachPartitions(evenStart, end, wMean, wM2, tested,
                minParts, minPartsAt, singleCount, contrast, avg);
    }

    /**
     * Phase 28 §28.6b — shared prose composer for MATHEMATICS:2 (goldbach).
     * Uses the 4-arg ResearchResult constructor (status defaults to
     * OBSERVATIONAL when finite), matching the in-process path.
     */
    static ResearchResult composeGoldbachPartitions(int evenStart, int end,
            double wMean, double wM2, int tested, int minParts, int minPartsAt,
            int singleCount, double contrast, double avg) {
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
        return composeAliquotSequencesOuter(best, firstAnomaly, lastAnomaly, sampled);
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
        return composeAliquotSequencesInner(start, end, terminate, perfect, cycle, escape,
                terminateFrac, anomalyFrac, count);
    }

    /**
     * Phase 28 §28.6b — shared prose composer for MATHEMATICS:3 (aliquot),
     * inner-window half. The outer search's trend overlay is applied by
     * {@link #composeAliquotSequencesOuter}; splitting them keeps both the
     * in-process path and the detail path single-sourced.
     */
    static ResearchResult composeAliquotSequencesInner(int start, int end, int terminate,
            int perfect, int cycle, int escape, double terminateFrac,
            double anomalyFrac, int count) {
        return new ResearchResult(
                "Aliquot sequences [%d,%d): terminate=%d (%s), perfect=%d, cycle=%d, escape=%d. Anomaly fraction %s; range-start trend requires the multi-window validator."
                        .formatted(start, end, terminate, fmtProp(terminateFrac, count),
                                   perfect, cycle, escape, fmtProp(anomalyFrac, count)),
                anomalyFrac, terminateFrac, cycle)
                .withHypothesis("Aliquot sequences in [%d, %d) will predominantly terminate at 1; searched windows will test whether cycle-or-escape anomaly fractions increase with larger starts."
                        .formatted(start, end));
    }

    /**
     * Phase 28 §28.6b — outer trend overlay for MATHEMATICS:3 (aliquot).
     * Recomputes the predominant-termination / trend bands from the same
     * inputs the in-process search uses, then overwrites findings + claim.
     */
    static ResearchResult composeAliquotSequencesOuter(ResearchResult best,
            double firstAnomaly, double lastAnomaly, int sampled) {
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
        return composeHappyNumbersOuter(best, maxDeviation, sampled);
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
        double deviation = Math.abs(density - 0.1430);
        int end = start + count;
        return composeHappyNumbersInner(start, end, happy, count, happyMean, happyM2,
                density, deviation);
    }

    /**
     * Phase 28 §28.6b — shared prose composer for MATHEMATICS:4 (happy),
     * inner-window half. {@code happyM2} is the Welford M2 of steps-to-1 over
     * the happy numbers only (emitted by the sidecar for {@code fmtMean}); the
     * outer multi-window overlay is applied by {@link #composeHappyNumbersOuter}.
     */
    static ResearchResult composeHappyNumbersInner(int start, int end, int happy,
            int count, double happyMean, double happyM2, double density, double deviation) {
        double avgSteps = happy > 0 ? happyMean : 0;
        return new ResearchResult(
                "Happy numbers [%d,%d): happy=%d/%d, density=%s (theoretical≈0.143, deviation=%+.3f), avg_steps_to_1=%s. Local density is compared with the conjectured asymptotic."
                        .formatted(start, end, happy, count, fmtProp(density, count), density - 0.1430,
                                   fmtMean(happyMean, happyM2, happy)),
                deviation, avgSteps, density)
                .withHypothesis("Happy number density in [%d, %d) will remain close to the conjectured asymptotic 14.3%%; multi-window validation tests the broader regardless-of-window claim."
                        .formatted(start, end));
    }

    /**
     * Phase 28 §28.6b — outer multi-window overlay for MATHEMATICS:4 (happy).
     * The claim bands key off {@code maxDeviation} (the worst window), not the
     * winning window's deviation — recomputed here from the same input.
     */
    static ResearchResult composeHappyNumbersOuter(ResearchResult best,
            double maxDeviation, int sampled) {
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

    // ---- Phase 7 F3.4: new mathematics topics ----

    /** Mertens function: M(x) = Σ_{n ≤ x} μ(n). Returns max |M|/√x ratio across the sweep. */
    private static ResearchResult mertensPartialSums(int seedStart, int width, SearchParams sp) {
        double bestRatio = 0;
        int bestX = seedStart + width;
        long bestM = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int start = Math.max(1, seedStart + (int) (i * sp.stepSize() * 50));
            int end = start + width;
            long M = mertensPartialSum(end);
            double ratio = Math.abs(M) / Math.sqrt(Math.max(1.0, end));
            samples++;
            if (ratio > bestRatio) { bestRatio = ratio; bestX = end; bestM = M; }
            if (i < 4) sb.append(mertensSweepSegment(end, M, ratio));
        }
        return composeMertensPartialSums(seedStart, width, bestRatio, bestX, bestM,
                samples, sb.toString().trim());
    }

    /** Phase 28 §28.6b — one Mertens sample-sweep segment (incl. trailing space). */
    static String mertensSweepSegment(int x, long M, double ratio) {
        return "x=%d→M=%d(|M|/√x=%.3f) ".formatted(x, M, ratio);
    }

    /**
     * Phase 28 §28.6b — shared prose composer for MATHEMATICS:5 (mertens).
     * {@code sweep} is the already-trimmed sample-sweep string (first ≤4
     * windows), built from {@link #mertensSweepSegment} by both the in-process
     * loop and the detail path.
     */
    static ResearchResult composeMertensPartialSums(int seedStart, int width,
            double bestRatio, int bestX, long bestM, int samples, String sweep) {
        // Spec bound: |M(x)| ≤ 2·√x in the tested range.
        boolean withinBound = bestRatio <= 2.0;
        ClaimStatus status = withinBound ? ClaimStatus.SUPPORTED
                : bestRatio <= 4.0 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Mertens max |M(x)|/√x = %.3f at x=%d (M=%d) across %d windows. %s")
                .formatted(bestRatio, bestX, bestM, samples,
                        withinBound ? "|M(x)| stays within the 2·√x heuristic envelope"
                                : "|M(x)| exceeds 2·√x — empirical Mertens conjecture stress test");
        return new ResearchResult(
                "Mertens function (windows %d, width=%d): worst |M|/√x = %.3f at x=%d (M=%d). Sample sweep: %s"
                        .formatted(samples, width, bestRatio, bestX, bestM, sweep),
                bestRatio, bestM, bestX, bestRatio, status, assessment)
                .withHypothesis(("Across Mertens partial sums starting near x=%d with windows of width %d, the "
                        + "ratio |M(x)|/√x will stay within the 2× heuristic envelope for the unconditional "
                        + "bound |M(x)| = O(√x·log log log x).")
                        .formatted(seedStart, width));
    }

    /** (1/N)·Σ_{n ≤ N} σ(n) ≈ (π²/12)·N for large N. (Note: σ here is the FULL divisor sum.) */
    private static ResearchResult divisorSumAverage(int seedN, int width, SearchParams sp) {
        double asymptotic = Math.PI * Math.PI / 12.0;
        double bestDev = 0;
        int bestN = seedN;
        double bestAvg = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        double sumDev = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int N = Math.max(20, seedN + (int) (i * sp.stepSize() * 40));
            long total = 0;
            // σ(n) is the full divisor sum (includes 1 and n). sigmaSum returns the
            // aliquot sum (σ(n) − n), so add n back.
            for (int n = 1; n <= N; n++) total += sigmaSum(n) + n;
            double avgOverN = ((double) total) / ((double) N * N);
            double dev = Math.abs(avgOverN - asymptotic) / asymptotic;
            sumDev += dev;
            samples++;
            if (dev > bestDev) { bestDev = dev; bestN = N; bestAvg = avgOverN; }
            if (i < 4) sb.append("N=%d→avg/N=%.3f(dev=%.2f%%) ".formatted(N, avgOverN, dev * 100));
        }
        double meanDev = samples > 0 ? sumDev / samples : 0;
        return composeDivisorSumAverage(seedN, width, bestDev, bestN, bestAvg, meanDev, samples);
    }

    /**
     * Phase 28 §28.6b — shared prose composer for MATHEMATICS:6 (divisor-sum
     * average). Like squarefree, the in-process method builds a sample-sweep
     * string it never embeds in findings, so the detail is fully numeric; the
     * π²/12 asymptotic is recomputed rather than carried.
     */
    static ResearchResult composeDivisorSumAverage(int seedN, int width, double bestDev,
            int bestN, double bestAvg, double meanDev, int samples) {
        double asymptotic = Math.PI * Math.PI / 12.0;
        ClaimStatus status = bestDev <= 0.10 ? ClaimStatus.SUPPORTED
                : bestDev <= 0.20 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Divisor-sum average max deviation %.3f from π²/12 ≈ %.4f at N=%d; mean dev %.3f. %s")
                .formatted(bestDev, asymptotic, bestN, meanDev,
                        bestDev <= 0.10 ? "Dirichlet asymptotic holds within 10% in this regime"
                                : "deviation exceeds 10% — N may be too small for the asymptotic to dominate");
        return new ResearchResult(
                "Divisor-sum average (windows %d, base width=%d): worst dev=%.3f at N=%d (avg/N=%.3f, asymptotic=%.3f); mean dev=%.3f."
                        .formatted(samples, width, bestDev, bestN, bestAvg, asymptotic, meanDev),
                bestDev, meanDev, bestAvg, bestDev, status, assessment)
                .withHypothesis(("Across upper-limits N swept upward from %d, the running average (1/N)·Σσ(n) "
                        + "approaches Dirichlet's (π²/12)·N within 10%% as N grows.")
                        .formatted(seedN));
    }

    /** Σ 1/nˢ vs Π 1/(1−p⁻ˢ) for s>1. Empirically truncated. */
    private static ResearchResult riemannEulerProduct(double seedS, SearchParams sp) {
        double bestDev = 0;
        double bestS = seedS;
        double bestRatio = 1.0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        double sumDev = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double s = Math.max(1.1, seedS + i * sp.stepSize() * 0.2);
            int truncation = 5000;
            double zetaSum = 0;
            for (int n = 1; n <= truncation; n++) zetaSum += Math.pow(n, -s);
            double eulerProd = 1.0;
            for (int p = 2; p <= 200; p++) {
                if (!isPrime(p)) continue;
                eulerProd *= 1.0 / (1.0 - Math.pow(p, -s));
            }
            double ratio = eulerProd / zetaSum;
            double dev = Math.abs(ratio - 1.0);
            sumDev += dev;
            samples++;
            if (dev > bestDev) { bestDev = dev; bestS = s; bestRatio = ratio; }
            if (i < 4) sb.append(riemannSweepSegment(s, ratio));
        }
        double meanDev = samples > 0 ? sumDev / samples : 0;
        return composeRiemannEulerProduct(seedS, bestDev, bestS, bestRatio, meanDev,
                samples, sb.toString().trim());
    }

    /** Phase 28 §28.6b — one Riemann sample-sweep segment (incl. trailing space). */
    static String riemannSweepSegment(double s, double ratio) {
        return "s=%.3f→ratio=%.4f ".formatted(s, ratio);
    }

    /** Phase 28 §28.6b — shared prose composer for MATHEMATICS:7 (Riemann ζ). */
    static ResearchResult composeRiemannEulerProduct(double seedS, double bestDev,
            double bestS, double bestRatio, double meanDev, int samples, String sweep) {
        boolean withinBand = bestDev <= 0.05;
        ClaimStatus status = withinBand ? ClaimStatus.SUPPORTED
                : bestDev <= 0.15 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Riemann ζ truncated-sum vs Euler-product ratio: worst dev %.4f at s=%.3f (ratio=%.4f); mean dev %.4f. %s")
                .formatted(bestDev, bestS, bestRatio, meanDev,
                        withinBand ? "ratio stays within 5% band — Euler product converges"
                                : "ratio band exceeded — truncation effect dominant or s too close to 1");
        return new ResearchResult(
                "Riemann ζ Euler-product survey (%d s-values starting at %.3f): worst dev %.4f at s=%.3f. Sample sweep: %s"
                        .formatted(samples, seedS, bestDev, bestS, sweep),
                bestDev, bestRatio, bestS, bestDev, status, assessment)
                .withHypothesis(("Across s-values sampled near %.3f, the ratio of the truncated ζ-sum Σ 1/nˢ to the "
                        + "Euler product Π 1/(1 − p⁻ˢ) (over primes ≤ 200) will remain within 5%% of unity.")
                        .formatted(seedS));
    }

    /** Stern-Brocot tree depth via continued-fraction expansion. */
    private static ResearchResult sternBrocotDepth(int seedQ, SearchParams sp) {
        int maxDepth = 0;
        int bestP = 0, bestQ = seedQ;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        long sumDepth = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int q = Math.max(2, seedQ + (int) (i * sp.stepSize() * 50));
            int p = 1 + (i * 7) % Math.max(2, q - 1);
            int depth = sternBrocotDepthOf(p, q);
            sumDepth += depth;
            samples++;
            if (depth > maxDepth) { maxDepth = depth; bestP = p; bestQ = q; }
            if (i < 4) sb.append(sternBrocotSweepSegment(p, q, depth));
        }
        double meanDepth = samples > 0 ? (double) sumDepth / samples : 0;
        return composeSternBrocotDepth(seedQ, maxDepth, bestP, bestQ, meanDepth,
                samples, sb.toString().trim());
    }

    /** Phase 28 §28.6b — one Stern-Brocot sample-sweep segment (incl. trailing space). */
    static String sternBrocotSweepSegment(int p, int q, int depth) {
        return "%d/%d→depth=%d ".formatted(p, q, depth);
    }

    /** Phase 28 §28.6b — shared prose composer for MATHEMATICS:8 (Stern-Brocot depth). */
    static ResearchResult composeSternBrocotDepth(int seedQ, int maxDepth, int bestP,
            int bestQ, double meanDepth, int samples, String sweep) {
        // Spec: max depth < 50 for q < 10⁶.
        boolean withinBound = maxDepth < 50 && bestQ < 1_000_000;
        ClaimStatus status = withinBound
                ? (maxDepth >= 5 ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL)
                : ClaimStatus.REFUTED;
        String assessment = ("Stern-Brocot worst depth %d at p/q=%d/%d (mean depth %.2f across %d samples). %s")
                .formatted(maxDepth, bestP, bestQ, meanDepth, samples,
                        !withinBound ? "depth ≥ 50 in q < 10⁶ regime — unexpected; reject"
                                : maxDepth >= 5 ? "depth distribution consistent with the Fibonacci envelope"
                                        : "shallow rationals only — sweep did not hit deep continued fractions");
        return new ResearchResult(
                "Stern-Brocot depth (%d samples starting q=%d): max depth %d at %d/%d; mean depth %.2f. Sample: %s"
                        .formatted(samples, seedQ, maxDepth, bestP, bestQ, meanDepth, sweep),
                maxDepth, meanDepth, bestQ, maxDepth, status, assessment)
                .withHypothesis(("Across rationals p/q sampled with denominators near %d, the Stern-Brocot tree "
                        + "depth (equivalently the continued-fraction length) will stay below Fib⁻¹(max(p,q)) "
                        + "and below the empirical 50-level cap for q < 10⁶.")
                        .formatted(seedQ));
    }

    /** Squarefree density: count of n ≤ N with no repeated prime factor / N → 6/π². */
    private static ResearchResult squarefreeDensity(int seedStart, int width, SearchParams sp) {
        double asymptotic = 6.0 / (Math.PI * Math.PI);
        double bestDev = 0;
        int bestStart = seedStart;
        double bestDensity = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        double sumDev = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int start = Math.max(1, seedStart + (int) (i * sp.stepSize() * 200));
            int end = start + width;
            int count = 0;
            for (int n = start; n < end; n++) if (isSquarefree(n)) count++;
            double density = (double) count / Math.max(1, end - start);
            double dev = Math.abs(density - asymptotic);
            sumDev += dev;
            samples++;
            if (dev > bestDev) { bestDev = dev; bestStart = start; bestDensity = density; }
            if (i < 4) sb.append("[%d,%d)→density=%.4f(dev=%.4f) ".formatted(start, end, density, dev));
        }
        double meanDev = samples > 0 ? sumDev / samples : 0;
        return composeSquarefreeDensity(seedStart, width, samples, bestDev, bestStart,
                bestDensity, meanDev);
    }

    /**
     * Phase 28 §28.6b — shared prose composer for MATHEMATICS:9 (squarefree
     * density). No sample-sweep string here (the in-process method builds one
     * but never embeds it in findings), so the detail is fully numeric. The
     * asymptotic 6/π² is recomputed rather than carried.
     */
    static ResearchResult composeSquarefreeDensity(int seedStart, int width, int samples,
            double bestDev, int bestStart, double bestDensity, double meanDev) {
        double asymptotic = 6.0 / (Math.PI * Math.PI);
        ClaimStatus status = bestDev <= 0.05 ? ClaimStatus.SUPPORTED
                : bestDev <= 0.10 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Squarefree density max dev %.4f from 6/π²≈%.4f at start=%d (density=%.4f); mean dev %.4f. %s")
                .formatted(bestDev, asymptotic, bestStart, bestDensity, meanDev,
                        bestDev <= 0.05 ? "density holds within 5% of the asymptotic"
                                : "deviation exceeds 5% — narrow window effect or non-equidistributed start");
        return new ResearchResult(
                "Squarefree density (%d windows, width=%d): worst dev=%.4f at [%d), density=%.4f vs asymptotic %.4f."
                        .formatted(samples, width, bestDev, bestStart, bestDensity, asymptotic),
                bestDev, meanDev, bestDensity, bestDev, status, assessment)
                .withHypothesis(("Across windows of width %d starting near %d, the empirical squarefree density "
                        + "remains within 5%% of the asymptotic 6/π² ≈ 0.6079.")
                        .formatted(width, seedStart));
    }

    // ---- F3.4 helpers (small, deterministic) ----

    static long mertensPartialSum(int x) {
        long m = 0;
        for (int n = 1; n <= x; n++) m += mobius(n);
        return m;
    }

    /** Möbius function μ(n): squarefree-aware. */
    static int mobius(int n) {
        if (n == 1) return 1;
        int primeFactors = 0;
        long m = n;
        for (long p = 2; p * p <= m; p++) {
            if (m % p == 0) {
                m /= p;
                primeFactors++;
                if (m % p == 0) return 0;  // square factor
            }
        }
        if (m > 1) primeFactors++;
        return (primeFactors & 1) == 0 ? 1 : -1;
    }

    /**
     * Stern-Brocot depth of p/q as the LENGTH of the continued-fraction expansion
     * (number of Euclidean steps), not the sum of partial quotients. The F3 spec's
     * bound Depth(p/q) ≤ Fib⁻¹(max(p, q)) only makes sense in this length-of-CF
     * semantic; the Fibonacci-pair worst case has q = F_k and depth k.
     */
    static int sternBrocotDepthOf(int p, int q) {
        if (p <= 0 || q <= 0) return 0;
        int depth = 0;
        while (p > 0 && q > 0) {
            depth++;
            int r = p % q;
            p = q;
            q = r;
        }
        return depth;
    }

    /** True iff n has no repeated prime factor. */
    static boolean isSquarefree(int n) {
        if (n < 1) return false;
        long m = n;
        for (long p = 2; p * p <= m; p++) {
            if (m % p == 0) {
                m /= p;
                if (m % p == 0) return false;
            }
        }
        return true;
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
        return composeProjectileWithDragAt(v0, k, bestAngle, bestRange);
    }

    /**
     * Phase 28 §28.6b — shared prose composer for PHYSICS:0 (projectile drag).
     * The derived scalars (vacuumRange, shift, loss) are recomputed from
     * {@code v0/k/bestAngle/bestRange} so the composer is self-contained;
     * {@code g} matches the in-process constant.
     */
    static ResearchResult composeProjectileWithDragAt(double v0, double k,
            int bestAngle, double bestRange) {
        double g = 9.81;
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
            sb.append(pendulumSweepSegment(deg, err));
            if (err > 1.0 && nearlyEqual(breakdownAngle, 80, 0.5)) breakdownAngle = deg;
        }
        return composePendulumBreakdownAt(L, T0, breakdownAngle, sb.toString().trim());
    }

    /** Phase 28 §28.6b — one pendulum period-error sweep segment (incl. trailing space). */
    static String pendulumSweepSegment(double deg, double err) {
        return "θ=%.0f°→%.2f%% ".formatted(deg, err);
    }

    /**
     * Phase 28 §28.6b — shared prose composer for PHYSICS:1 (pendulum
     * breakdown). {@code breakdownAngle} is double-valued (matches the
     * in-process local) and {@code sweep} is the trimmed 16-step amplitude
     * sweep rebuilt from the sidecar's {@code sampleSweep} array.
     */
    static ResearchResult composePendulumBreakdownAt(double L, double T0,
            double breakdownAngle, String sweep) {
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
                        .formatted(L, T0, sweep, breakdownAngle),
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
                sb.append(vdwSweepSegment(p, dev));
        }
        return composeVanDerWaalsDeviationAt(T, tolerance, maxDev, crossoverP, sb.toString().trim());
    }

    /** Phase 28 §28.6b — one Van der Waals deviation sweep segment (incl. trailing space). */
    static String vdwSweepSegment(int p, double dev) {
        return "%datm→%.2f%% ".formatted(p, dev * 100);
    }

    /**
     * Phase 28 §28.6b — shared prose composer for PHYSICS:2 (Van der Waals).
     * {@code crossoverP} is the raw first-crossover pressure (−1 if none); the
     * "observed" flag and the 101.0 sentinel secondary are derived here.
     */
    static ResearchResult composeVanDerWaalsDeviationAt(double T, double tolerance,
            double maxDev, double crossoverP, String sweep) {
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
                        .formatted(T, sweep, crossoverText, maxDev * 100),
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
            sb.append(diffractionSweepSegment(lam, orders));
        }
        double orderRatio = (double) maxOrders / Math.max(1, minOrders);
        return composeDiffractionSurveyAt(dMicrons, lambdaNm, maxOrder, theta1, orderRatio,
                sb.toString().trim());
    }

    /** Phase 28 §28.6b — one diffraction order-count sweep segment (incl. trailing space). */
    static String diffractionSweepSegment(double lam, int orders) {
        return "%.0fnm→%d ".formatted(lam, orders);
    }

    /** Phase 28 §28.6b — shared prose composer for PHYSICS:3 (diffraction survey). */
    static ResearchResult composeDiffractionSurveyAt(double dMicrons, double lambdaNm,
            int maxOrder, double theta1, double orderRatio, String sweep) {
        return new ResearchResult(
                "Diffraction grating (d=%.2f µm, λ_ref=%.0f nm): max_orders=%d, 1st_order_angle=%.1f°. Orders by wavelength: %s. Order ratio (max/min)=%.2f — blue light supports %.1f× more orders than red."
                        .formatted(dMicrons, lambdaNm, maxOrder, theta1, sweep,
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
            sb.append(rlcSweepSegment(mult, Q));
        }
        return composeRlcDampingTransitionAt(L, C, f0, critR, minQ, maxQ, sb.toString().trim());
    }

    /**
     * Phase 28 §28.6b — one RLC Q-factor sweep segment (incl. trailing space).
     * The damping regime label is derived from the R/R_crit multiplier, so it
     * need not be carried in the sidecar detail.
     */
    static String rlcSweepSegment(double mult, double Q) {
        String regime = mult < 1 ? "under" : mult > 1 ? "over" : "crit";
        return "%.2f×R_c→Q=%.2f(%s) ".formatted(mult, Q, regime);
    }

    /** Phase 28 §28.6b — shared prose composer for PHYSICS:4 (RLC damping transition). */
    static ResearchResult composeRlcDampingTransitionAt(double L, double C, double f0,
            double critR, double minQ, double maxQ, String sweep) {
        return new ResearchResult(
                "RLC circuit (L=%.4f H, C=%.2e F): f₀=%.2f Hz, R_crit=%.3f Ω. Q-factor sweep: %s. Q range: [%.2f, %.2f] — transition from oscillatory to exponential at R_crit."
                        .formatted(L, C, f0, critR, sweep, minQ, maxQ),
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
        return composeWienPeak(seedT, bestT, samples, bestLambda, bestDev, meanDev);
    }

    /** Phase 28 §28.6b — shared prose composer for PHYSICS:5 (Wien peak). */
    static ResearchResult composeWienPeak(double seedT, double bestT, int samples,
            double bestLambda, double bestDev, double meanDev) {
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
            if (i < 4) sb.append(schrodingerSweepSegment(Lnm, E1, ratio));
        }
        return composeSchrodingerInfiniteWell(seedLnm, T, samples, bestRatio, bestL, bestE1,
                sb.toString().trim());
    }

    /** Phase 28 §28.6b — one infinite-well ground-state sweep segment (incl. trailing space). */
    static String schrodingerSweepSegment(double Lnm, double E1, double ratio) {
        return "L=%.2fnm→E₁=%.2eJ(ratio=%.2f) ".formatted(Lnm, E1, ratio);
    }

    /** Phase 28 §28.6b — shared prose composer for PHYSICS:6 (Schrödinger infinite well). */
    static ResearchResult composeSchrodingerInfiniteWell(double seedLnm, double T, int samples,
            double bestRatio, double bestL, double bestE1, String sweep) {
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
                        .formatted(seedLnm, bestL, T, samples, bestRatio, bestL, bestE1, sweep),
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
        return composeKeplerThirdLaw(seedAuAu, bestA, samples, bestDev, bestT, meanDev);
    }

    /** Phase 28 §28.6b — shared prose composer for PHYSICS:7 (Kepler third law). */
    static ResearchResult composeKeplerThirdLaw(double seedAuAu, double bestA, int samples,
            double bestDev, double bestT, double meanDev) {
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
        return composeSnellCriticalAngle(seedN2, bestN2, samples, bestErr, bestTheta, meanErr);
    }

    /** Phase 28 §28.6b — shared prose composer for PHYSICS:8 (Snell critical angle). */
    static ResearchResult composeSnellCriticalAngle(double seedN2, double bestN2, int samples,
            double bestErr, double bestTheta, double meanErr) {
        double n1 = 1.5;  // glass
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
        return composeStefanBoltzmann(seedT, bestT, samples, bestSigma, bestDev, meanDev);
    }

    /** Phase 28 §28.6b — shared prose composer for PHYSICS:9 (Stefan-Boltzmann). */
    static ResearchResult composeStefanBoltzmann(double seedT, double bestT, int samples,
            double bestSigma, double bestDev, double meanDev) {
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
            if (i < 4) sb.append(hallPetchSweepSegment(d_um, sigmaY));
        }
        return composeHallPetchStrengthening(sigma0, k, maxYield, bestD, samples,
                sb.toString().trim());
    }

    /** Phase 28 §28.6b — one Hall-Petch grain-size sweep segment (incl. trailing space). */
    static String hallPetchSweepSegment(double dUm, double sigmaY) {
        return "d=%.2fµm→σ_y=%.0fMPa ".formatted(dUm, sigmaY);
    }

    /** Phase 28 §28.6b — shared prose composer for MATERIALS:5 (Hall-Petch). */
    static ResearchResult composeHallPetchStrengthening(double sigma0, double k,
            double maxYield, double bestD, int samples, String sweep) {
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
                        .formatted(sigma0, k, samples, maxYield, bestD, sweep),
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
                sb.append(braggSweepSegment(d, t1));
            }
        }
        double meanOrders = samples > 0 ? (double) validOrders / samples : 0;
        return composeBraggDiffraction(lambdaNm, seedDnm, samples, bestAngle, bestD, bestN,
                meanOrders, sb.toString().trim());
    }

    /** Phase 28 §28.6b — one Bragg first-order-angle sweep segment (incl. trailing space). */
    static String braggSweepSegment(double d, double t1) {
        return "d=%.3fnm→θ₁=%.1f° ".formatted(d, t1);
    }

    /** Phase 28 §28.6b — shared prose composer for MATERIALS:6 (Bragg diffraction). */
    static ResearchResult composeBraggDiffraction(double lambdaNm, double seedDnm, int samples,
            double bestAngle, double bestD, int bestN, double meanOrders, String sweep) {
        ClaimStatus status = bestAngle > 0.5 && bestAngle < 89.5 ? ClaimStatus.SUPPORTED
                : bestAngle > 0 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Bragg first-order θ=%.1f° at d=%.3f nm (λ=%.3f nm); mean visible orders = %.1f across %d samples. %s")
                .formatted(bestAngle, bestD, lambdaNm, meanOrders, samples,
                        status == ClaimStatus.SUPPORTED ? "Bragg condition produces a physically valid diffraction angle"
                                : "first-order angle outside the [0.5°, 89.5°] band — extreme lattice/λ ratio");
        return new ResearchResult(
                "Bragg diffraction (λ=%.3f nm, %d lattice samples): peak first-order θ=%.1f° at d=%.3f nm (n=%d). Sample sweep: %s"
                        .formatted(lambdaNm, samples, bestAngle, bestD, bestN, sweep),
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
            if (i < 4) sb.append(carnotSweepSegment(Tc, Th, eta));
        }
        double meanEta = samples > 0 ? sumEta / samples : 0;
        return composeCarnotEfficiency(seedTc, seedTh, samples, bestEta, bestTc, bestTh,
                meanEta, sb.toString().trim());
    }

    /** Phase 28 §28.6b — one Carnot efficiency sweep segment (incl. trailing space). */
    static String carnotSweepSegment(double Tc, double Th, double eta) {
        return "T_c=%.0fK/T_h=%.0fK→η=%.3f ".formatted(Tc, Th, eta);
    }

    /** Phase 28 §28.6b — shared prose composer for MATERIALS:7 (Carnot efficiency). */
    static ResearchResult composeCarnotEfficiency(double seedTc, double seedTh, int samples,
            double bestEta, double bestTc, double bestTh, double meanEta, String sweep) {
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
            if (i < 4) sb.append(basquinSweepSegment(Nf, sigmaA));
        }
        return composeBasquinFatigue(sigmaPrimeF, b, bestAmp, bestNf, countAboveUts, samples,
                sb.toString().trim());
    }

    /** Phase 28 §28.6b — one Basquin S-N sweep segment (incl. trailing space). */
    static String basquinSweepSegment(long Nf, double sigmaA) {
        return "N_f=%dk→σ_a=%.0fMPa ".formatted(Nf / 1000, sigmaA);
    }

    /** Phase 28 §28.6b — shared prose composer for MATERIALS:8 (Basquin fatigue). */
    static ResearchResult composeBasquinFatigue(double sigmaPrimeF, double b, double bestAmp,
            long bestNf, int countAboveUts, int samples, String sweep) {
        double UTS = sigmaPrimeF * 0.9;   // assume σ_f is ~90% of UTS
        boolean physical = countAboveUts == 0;
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : bestAmp > sigmaPrimeF * 0.5 ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = ("Basquin: σ'_f=%.0f MPa, b=%.3f, UTS≈%.0f MPa; max σ_a=%.0f MPa at N_f=%d; %d/%d samples above UTS. %s")
                .formatted(sigmaPrimeF, b, UTS, bestAmp, bestNf, countAboveUts, samples,
                        !physical ? "σ_a exceeds UTS in some samples — Basquin extrapolation breaks above static strength"
                                : "fatigue-stress range stays below ultimate tensile strength");
        return new ResearchResult(
                "Basquin S-N curve (σ'_f=%.0f MPa, b=%.3f, %d cycle counts): max σ_a=%.0fMPa at N_f=%d. Sample sweep: %s"
                        .formatted(sigmaPrimeF, b, samples, bestAmp, bestNf, sweep),
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
            if (i < 4) sb.append(youngLaplaceSweepSegment(r_mm, h_cm));
        }
        return composeYoungLaplaceCapillary(seedRadiusMm, maxH, bestR, samples, countAboveLimit,
                sb.toString().trim());
    }

    /** Phase 28 §28.6b — one Young-Laplace capillary-rise sweep segment (incl. trailing space). */
    static String youngLaplaceSweepSegment(double rMm, double hCm) {
        return "r=%.3fmm→h=%.2fcm ".formatted(rMm, hCm);
    }

    /**
     * Phase 28 §28.6b — shared prose composer for MATERIALS:9 (Young-Laplace
     * capillary rise). The fixed water/glass constants (γ, ρ, contact angle)
     * are recomputed to match the in-process method.
     */
    static ResearchResult composeYoungLaplaceCapillary(double seedRadiusMm, double maxH,
            double bestR, int samples, int countAboveLimit, String sweep) {
        double gamma = 0.0728;   // N/m, water surface tension at 20°C
        double rho = 1000.0;     // kg/m³
        double contactAngle = Math.toRadians(20.0);  // wet glass
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
                        .formatted(gamma, Math.toDegrees(contactAngle), samples, maxH, bestR, sweep),
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

    // Phase 28 §28.6b — package-private so SidecarResultComposer can recompute
    // the winning window's prose from the sidecar's emitted sigmaYm (this inner
    // method is a cheap pure function of sigmaYm; the heavy part is the outer
    // search, which the sidecar runs). Same for the other MATERIALS inner-window
    // methods below.
    static ResearchResult compositeFailureModeAt(double sigmaYm) {
        // Phase 10 — add the fibre-fracture criterion the original simulator
        // explicitly omitted. P-a328-343 reported 7.6 GPa at Vf=50%, P-a328-346
        // extrapolated to 9.4 GPa at Vf=70% — both impossible for a steel
        // fibre composite (steel UTS ≤ 3 GPa; the matrix would fail by fibre
        // fracture long before reaching 9 GPa). The original "matrix-yields-
        // first" assumption only holds when ε_matrix-yield < ε_fibre-fracture.
        // For the standard params it does NOT: ε_F = 3000/200000 = 0.015 vs
        // ε_Y = sigmaYm/3000 ≈ 0.067, so the fibre fractures first.
        double Ef = 200e3, Em = 3e3;
        // Steel-fibre tensile strength. Generous bound on the high end of the
        // commercial range (piano wire ≈ 2.5 GPa; ultra-high-strength steels
        // ≈ 3 GPa). Below this, the fibre is intact and carries Vf-weighted
        // load up to its fracture strain.
        double sigmaFFracture = 3_000.0;  // MPa
        double epsY = sigmaYm / Em;                       // matrix-yield strain
        double epsF = sigmaFFracture / Ef;                // fibre-fracture strain
        // The composite fails at whichever strain is reached first; the
        // limiting stress is computed via the Voigt rule of mixtures at that
        // strain.
        double failureStrain = Math.min(epsY, epsF);
        boolean fibreFirst = epsF < epsY;
        StringBuilder sb = new StringBuilder();
        double maxStress = 0, maxVf = 0;
        for (int vfPct = 10; vfPct <= 90; vfPct += 10) {
            double Vf = vfPct / 100.0;
            // Voigt-rule composite stress at the limiting strain.
            //   sigma_C = Vf * sigma_F + (1-Vf) * sigma_M
            // where sigma_F = Ef*ε and sigma_M = Em*ε. At ε = failureStrain
            // this is the actual composite stress when failure begins.
            double sigmaC = (Vf * Ef + (1 - Vf) * Em) * failureStrain;
            if (sigmaC > maxStress) { maxStress = sigmaC; maxVf = Vf; }
            sb.append("Vf=%.0f%%→%.0fMPa ".formatted(Vf * 100, sigmaC));
        }
        String mode = fibreFirst
                ? "fibre fractures first (ε_F=%.4f < ε_Y=%.4f); matrix never reaches yield"
                        .formatted(epsF, epsY)
                : "matrix yields first (ε_Y=%.4f < ε_F=%.4f); fibre carries elastic load up to that point"
                        .formatted(epsY, epsF);
        String assessment = ("Composite stress at failure is bounded by the FIRST event reached. %s. "
                + "Linear Vf-scaling holds within this regime; max at Vf=%.0f%% → %.0f MPa.")
                .formatted(mode, maxVf * 100, maxStress);
        String hypothesis = ("For a steel-fibre/polymer composite (Ef=200 GPa, Em=3 GPa, "
                + "σ_F-fracture=3 GPa, σ_ym=%.0f MPa), composite failure occurs at the smaller "
                + "of ε_Y=σ_ym/Em and ε_F=σ_F-fracture/Ef; the limiting stress scales linearly "
                + "with Vf under the Voigt rule.")
                .formatted(sigmaYm);
        return new ResearchResult(
                ("Composite failure (Ef=200GPa, Em=3GPa, σ_ym=%.0f MPa, σ_F-frac=3GPa, ε_failure=%.4f, mode=%s): "
                        + "longitudinal stress at failure: %s. Stress rises linearly with Vf; max at Vf=%.0f%% → %.0f MPa.")
                        .formatted(sigmaYm, failureStrain, fibreFirst ? "fibre-fracture" : "matrix-yield",
                                sb.toString().trim(), maxVf * 100, maxStress),
                maxStress, failureStrain, maxVf, maxStress, ClaimStatus.SUPPORTED, assessment, hypothesis);
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
                sb.append(spherePackingSweepSegment(r, etaTotal));
        }
        double improvement = (bestEta - etaFCC) * 100;
        return composeBinarySpherePacking(etaFCC, bestEta, bestR, improvement, samples,
                sb.toString().trim());
    }

    /** Phase 28 §28.6b — one binary-sphere-packing sweep segment (incl. trailing space). */
    static String spherePackingSweepSegment(double r, double eta) {
        return "r=%.2f→η=%.4f ".formatted(r, eta);
    }

    /** Phase 28 §28.6b — shared prose composer for MATERIALS:1 (binary sphere packing). */
    static ResearchResult composeBinarySpherePacking(double etaFCC, double bestEta,
            double bestR, double improvement, int samples, String sweep) {
        return new ResearchResult(
                "Binary sphere packing (FCC base η=%.4f, %d r-samples): packing survey: %s. Peak packing η=%.4f at r≈%.3f (+%.2f%% over FCC). Octahedral void limit r<0.414; tetrahedral r<0.225."
                        .formatted(etaFCC, samples, sweep, bestEta, bestR, improvement),
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

    static ResearchResult compositeStiffnessCrossoverAt(double EfOverEm, double targetMultiple) {
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

    static ResearchResult arrheniusRuleValidityAt(double T, double maxEaKJ) {
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

    static ResearchResult thermalStressSafetyAt(double deltaT, double sigmaYMPa) {
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
            case 4 -> vanDerWaalsCompositeStress(param1, param2, sp).withEquation(eq);
            // Phase 7 F3.5 — new interdisciplinary topics.
            case 5 -> hallEffectCarrierSign(param1, param2, sp).withEquation(eq);
            case 6 -> schottkyThermionicEmission(param1, param2, sp).withEquation(eq);
            default -> acousticDopplerShift(param1, param2, sp).withEquation(eq);
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
                case 4 -> "x_{n+1} = Σ_i d_i(x_n)²   (digit-square iteration; happy iff terminates at 1)";
                case 5 -> "M(x) = Σ_{n ≤ x} μ(n);   |M(x)| = O(√x·log log log x)   (Mertens function)";
                case 6 -> "(1/N)·Σ_{n ≤ N} σ(n) ~ (π²/12)·N   (Dirichlet average of the divisor function)";
                case 7 -> "ζ(s) = Σ_n 1/n^s = Π_p 1/(1 − p^(−s))   (Riemann ζ Euler product, s>1)";
                case 8 -> "depth(p/q) ≤ Fib⁻¹(max(p, q))   (Stern–Brocot tree continued-fraction depth)";
                default -> "ρ_squarefree = 6/π² ≈ 0.6079   (asymptotic density of squarefree integers)";
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
                case 4 -> "(P_c + a/V²)·(V − b) = R·T,   σ_long = P_c · [V_f·E_f + (1 − V_f)·E_m]/10³   (VdW crossover pressure → composite longitudinal stress)";
                case 5 -> "V_H = (I·B)/(n·e·t);   sign(V_H) = sign(carrier charge)   (Hall effect: Lorentz force on charge carriers)";
                case 6 -> "J = A*·T²·exp(−φ/(k·T))   (Richardson-Dushman thermionic emission across a Schottky barrier)";
                default -> "f' = f·(c + v_o)/(c − v_s)   (Acoustic Doppler shift for source v_s, observer v_o, sound speed c)";
            };
            // Phase 7 FX.1 — BIOLOGY.
            case "BIOLOGY" -> switch (t) {
                case 0 -> "dN/dt = r·N·(1 − N/K)   (Verhulst logistic growth; r = intrinsic rate, K = carrying capacity)";
                case 1 -> "dx/dt = α·x − β·x·y,   dy/dt = δ·x·y − γ·y   (Lotka-Volterra predator-prey)";
                case 2 -> "v = V_max · [S] / (K_m + [S])   (Michaelis-Menten enzyme kinetics)";
                case 3 -> "R_metabolic ∝ M^(3/4)   (Kleiber's allometric scaling law)";
                default -> "p² + 2·p·q + q² = 1,  q = 1 − p   (Hardy-Weinberg allele equilibrium for two alleles)";
            };
            // Phase 7 FX.2 — CHEMISTRY.
            case "CHEMISTRY" -> switch (t) {
                case 0 -> "pH = pKₐ + log₁₀([A⁻]/[HA])   (Henderson-Hasselbalch buffer equation)";
                case 1 -> "E = E° − (R·T)/(n·F) · ln Q   (Nernst electrochemical potential)";
                case 2 -> "A = ε · c · l   (Beer-Lambert absorbance; ε molar absorptivity, c concentration, l path length)";
                case 3 -> "Kc = Π [products]^ν / Π [reactants]^ν   (equilibrium constant)";
                default -> "Z = (P · V_m) / (R · T)   (compressibility factor; Z=1 for ideal gas)";
            };
            // Phase 7 FX.3 — ARCHITECTURE_TERRESTRIAL (generic over planetary g + atmosphere).
            case "ARCHITECTURE_TERRESTRIAL" -> switch (t) {
                case 0 -> "δ = (ρ·g·A·L⁴)/(8·E·I)   (cantilever self-weight deflection; ρ density, g surface gravity)";
                case 1 -> "q = ½·ρ_atm·v²   (dynamic wind pressure; ρ_atm planet-specific atmospheric density)";
                case 2 -> "q_u = c·N_c + γ_soil·D·N_q + 0.5·γ_soil·B·N_γ   (Terzaghi bearing capacity; γ_soil = ρ_soil·g)";
                case 3 -> "τ = (ρ·c_p·V)/(h·A)   (thermal mass time-lag; h surface heat-transfer coefficient)";
                default -> "DF = (E_internal / E_external)·100%   (daylight factor; window-area fraction of sky-visible aperture)";
            };
            // Phase 7 FX.4 — ARCHITECTURE_LIQUID (generic over any liquid).
            case "ARCHITECTURE_LIQUID" -> switch (t) {
                case 0 -> "P(z) = ρ_liq · g · z   (hydrostatic pressure with depth; ρ_liq = liquid density, g = local gravity)";
                case 1 -> "F_b = ρ_liq · g · V_displaced   (Archimedes buoyant force on a submerged volume)";
                case 2 -> "P_crit = 2·E·(t/R)³ / (1 − ν²)   (thin-walled cylindrical hull external-pressure buckling)";
                case 3 -> "F_d = ½ · ρ_liq · v² · A · C_d(Re)   (hydrodynamic drag; C_d depends on Reynolds number Re = ρ_liq·v·L/μ)";
                default -> "c_dissolved = k_H(liquid) · P_gas   (Henry's-law dissolved-gas solubility; k_H is liquid-specific)";
            };
            // Phase 7 FX.5 — ARCHITECTURE_SPACE.
            case "ARCHITECTURE_SPACE" -> switch (t) {
                case 0 -> "σ_areal = Φ·E_dep / D_max   (radiation shielding areal density vs flux Φ and dose budget D_max)";
                case 1 -> "T_eq⁴ = (Q_internal + α·S) / (ε·σ·A)   (radiative thermal balance in vacuum; α absorptivity, ε emissivity)";
                case 2 -> "N_impacts = Φ_meteoroid · A · t   (cumulative micrometeoroid impacts over mission lifetime)";
                case 3 -> "a_centrifugal = ω²·R = v_tangential²/R   (artificial-gravity radius vs spin rate ω)";
                default -> "dh/dt ≈ −2π·ρ_atm(h)·B_inv·a   (orbital decay rate; B_inv = inverse ballistic coefficient)";
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

    // ---- Phase 7 F3.5: new INTERDISCIPLINARY topics ----

    /**
     * Hall-effect (Physics → Materials): V_H = (I·B)/(n·e·t). The sign of V_H
     * recovers the carrier type — positive for holes, negative for electrons.
     * Mechanism: Lorentz force F = qv×B deflects carriers to one face of the bar.
     */
    private static ResearchResult hallEffectCarrierSign(int param1, double param2, SearchParams sp) {
        double e = 1.602176634e-19;   // C
        // Geometry & current — chosen so V_H stays sub-1 V even at the lowest
        // sampled n. F3 spec bound: |V_H| ≤ 1 V at reasonable I, B.
        double I = 0.1;               // A
        double t = 1e-4;              // m, 100 µm bar thickness
        // Choose a "true" carrier charge sign from param2: +1 for holes, -1 for electrons.
        double trueSign = (((int)(param2 * 10)) % 2 == 0) ? +1.0 : -1.0;
        double bestAbsV = 0;
        double bestB = 0;
        double bestN = 0;
        int correctSign = 0;
        int totalSamples = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double B = 0.05 + (param1 + i * sp.stepSize() * 5) * 0.002;  // T
            double n = 1e24 + i * sp.stepSize() * 1e23;                    // carriers/m³ (doped semi → metal range)
            double V_H = trueSign * (I * B) / (n * e * t);
            totalSamples++;
            if (Math.signum(V_H) == trueSign) correctSign++;
            if (Math.abs(V_H) > bestAbsV) { bestAbsV = Math.abs(V_H); bestB = B; bestN = n; }
            if (i < 4) sb.append("B=%.2fT,n=%.1e→V_H=%.4eV ".formatted(B, n, V_H));
        }
        double signRecovery = (double) correctSign / Math.max(1, totalSamples);
        boolean physical = bestAbsV <= 1.0;  // F3 spec bound
        ClaimStatus status = physical && signRecovery >= 0.95 ? ClaimStatus.SUPPORTED
                : physical ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Hall: max |V_H|=%.4f V at B=%.2f T, n=%.1e /m³; sign-recovery rate = %.0f%% over %d samples. %s")
                .formatted(bestAbsV, bestB, bestN, signRecovery * 100, totalSamples,
                        !physical ? "|V_H| > 1 V is unphysical for any reasonable cross-bar geometry"
                                : signRecovery >= 0.95 ? "Lorentz-force sign convention recovers the carrier type"
                                        : "sign-recovery degraded — check B-field direction convention");
        return new ResearchResult(
                "Hall effect (carrier sign=%s, %d samples): max |V_H|=%.4f V at B=%.2f T, n=%.1e /m³; sign-recovery %.0f%%. Sample: %s"
                        .formatted(trueSign > 0 ? "+" : "−", totalSamples, bestAbsV, bestB, bestN, signRecovery * 100, sb.toString().trim()),
                bestAbsV, signRecovery, bestN, bestAbsV, status, assessment)
                .withHypothesis(("For a current-carrying bar with parameters chosen near param1=%d, param2=%.1f, "
                        + "the sign of the Hall voltage V_H = (I·B)/(n·e·t) recovers the carrier charge type "
                        + "across the swept B and n; magnitudes stay below 1 V at every sampled point.")
                        .formatted(param1, param2));
    }

    /**
     * Schottky-barrier thermionic emission (Math → Physics): J = A*·T²·exp(−φ/(k·T)).
     * The exponential structure is the mathematical content; the underlying mechanism
     * is Boltzmann-statistics-mediated carrier injection over a metal/semiconductor
     * barrier.
     */
    private static ResearchResult schottkyThermionicEmission(int param1, double param2, SearchParams sp) {
        double k = 8.617333262e-5;   // eV/K
        double Astar = 120.0;        // A/cm²/K², canonical Richardson constant
        // param1 picks T near room-temp; param2 picks barrier height φ in eV.
        double bestJ = 0;
        double bestT = 0;
        double bestPhi = 0;
        int aboveLimit = 0;
        int samples = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double T = 200.0 + (param1 + i * sp.stepSize() * 5) * 5.0;     // K
            double phi = 0.4 + param2 / 10.0 + i * sp.stepSize() * 0.02;   // eV
            double exponent = -phi / (k * T);
            // Cap exponent to avoid overflow underflow noise; physically the result
            // would just be effectively zero.
            double expTerm = exponent > -60 ? Math.exp(exponent) : 0;
            double J = Astar * T * T * expTerm;
            samples++;
            if (J > bestJ) { bestJ = J; bestT = T; bestPhi = phi; }
            if (J > 1e6) aboveLimit++;
            if (i < 4) sb.append("T=%.0fK,φ=%.2feV→J=%.2eA/cm² ".formatted(T, phi, J));
        }
        boolean physical = aboveLimit == 0;  // F3 spec: J ≤ 10⁶ A/cm²
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : bestJ > 1e-3 ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = ("Schottky: max J=%.2e A/cm² at T=%.0f K, φ=%.2f eV; %d/%d samples above 10⁶ A/cm² limit. %s")
                .formatted(bestJ, bestT, bestPhi, aboveLimit, samples,
                        !physical ? "J > 10⁶ A/cm² is unphysical for any realistic Schottky barrier"
                                : bestJ > 1e-3 ? "thermionic regime active across sampled (T, φ)"
                                        : "deep barrier or low T — emission negligible");
        return new ResearchResult(
                "Schottky thermionic emission (A*=%.1f A/cm²/K², %d samples): max J=%.2e A/cm² at T=%.0f K, φ=%.2f eV. Sample: %s"
                        .formatted(Astar, samples, bestJ, bestT, bestPhi, sb.toString().trim()),
                bestJ, bestT, bestPhi, bestJ, status, assessment)
                .withHypothesis(("For a Schottky barrier with barrier height swept near φ=%.2f eV at temperatures "
                        + "starting near %d K, the Richardson-Dushman current density J = A*·T²·exp(−φ/kT) "
                        + "remains below 10⁶ A/cm² across the swept regime — the exponential mechanism is "
                        + "Boltzmann-controlled thermionic emission, not a numerical artefact.")
                        .formatted(0.4 + param2 / 10.0, 200 + param1 * 5));
    }

    /**
     * Acoustic Doppler shift (Physics ↔ Physics): f' = f·(c + v_o)/(c − v_s).
     * Mechanism: relative motion within a single medium modifies the apparent
     * frequency. Bound: |f' − f| ≤ f when both v_s and v_o stay below c.
     */
    private static ResearchResult acousticDopplerShift(int param1, double param2, SearchParams sp) {
        double c = 343.0;  // m/s, sound speed in dry air at 20°C
        double f = 1000.0; // Hz, baseline source frequency
        double bestShift = 0;
        double bestVs = 0;
        double bestVo = 0;
        double bestFp = f;
        int violatesBound = 0;
        int samples = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            // Bound v_s, v_o well below c. Param1 sets v_s scale, param2 sets v_o scale.
            double v_s = Math.min(c - 5.0, (param1 + i * sp.stepSize() * 3) * 0.5);   // m/s
            double v_o = Math.min(c - 5.0, (i * sp.stepSize() * 1.5 + param2 * 5));    // m/s
            double fp = f * (c + v_o) / (c - v_s);
            double shift = Math.abs(fp - f);
            samples++;
            if (shift > bestShift) { bestShift = shift; bestVs = v_s; bestVo = v_o; bestFp = fp; }
            if (shift > f) violatesBound++;  // F3 spec: |f' − f| ≤ f
            if (i < 4) sb.append("v_s=%.1f,v_o=%.1f→f'=%.1fHz(Δ=%.1f) ".formatted(v_s, v_o, fp, shift));
        }
        boolean physical = violatesBound == 0;
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : bestShift > 5.0 ? ClaimStatus.SUPPORTED : ClaimStatus.OBSERVATIONAL;
        String assessment = ("Doppler: max |f'−f|=%.1f Hz at v_s=%.1f m/s, v_o=%.1f m/s; %d/%d samples above f bound. %s")
                .formatted(bestShift, bestVs, bestVo, violatesBound, samples,
                        !physical ? "|f'−f| > f indicates v_s approached c — formula breaks down at sonic boom"
                                : bestShift > 5.0 ? "measurable Doppler shift across the sweep"
                                        : "low relative velocities — minimal shift");
        return new ResearchResult(
                "Acoustic Doppler (c=%.0f m/s, f=%.0f Hz, %d samples): max |f'−f|=%.1f Hz at v_s=%.1f, v_o=%.1f m/s → f'=%.1f Hz. Sample: %s"
                        .formatted(c, f, samples, bestShift, bestVs, bestVo, bestFp, sb.toString().trim()),
                bestShift, bestFp, bestVs, bestShift, status, assessment)
                .withHypothesis(("For a 1 kHz source in dry air with source velocity swept around %.1f m/s and "
                        + "observer velocity around %.1f m/s, the relative-motion Doppler shift f' = f·(c+v_o)/(c−v_s) "
                        + "produces a measurable but bounded |f'−f|; the mechanism is purely classical wave-front "
                        + "compression / dilation in a single medium.")
                        .formatted(param1 * 0.5, param2 * 5));
    }

    // ---- Phase 7 FX.1: BIOLOGY ----

    private static ResearchResult computeBiology(int topic, int param1, double param2, SearchParams sp) {
        String eq = equationFor("BIOLOGY", topic);
        return switch (topic) {
            case 0 -> verhulstLogistic(0.05 + param2 / 90.0, 100.0 + param1 * 5.0, sp).withEquation(eq);
            case 1 -> lotkaVolterra(0.4 + param2 / 30.0, 0.02 + param1 / 5000.0, sp).withEquation(eq);
            case 2 -> michaelisMenten(0.5 + param2 / 10.0, 1.0 + param1 / 25.0, sp).withEquation(eq);
            case 3 -> kleiberAllometric(0.5 + param1 * 0.5, sp).withEquation(eq);
            default -> hardyWeinberg(0.3 + param2 / 30.0, sp).withEquation(eq);
        };
    }

    /** Verhulst dN/dt = r·N·(1 − N/K). Returns max |N(t)/K − N_eq/K| across the sweep. */
    private static ResearchResult verhulstLogistic(double r, double K, SearchParams sp) {
        // Closed-form: N(t) = K / (1 + ((K-N0)/N0)·exp(-r·t))
        double N0 = K / 20.0;  // start at 5% of carrying capacity
        double bestDeviation = 0;
        double bestT = 0;
        double bestN = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double t = Math.max(0.1, i * sp.stepSize() * 2.0);
            double Nt = K / (1 + ((K - N0) / N0) * Math.exp(-r * t));
            double deviation = Math.abs(Nt - K) / K;
            samples++;
            if (deviation > bestDeviation) { bestDeviation = deviation; bestT = t; bestN = Nt; }
            if (i < 4) sb.append("t=%.2f→N=%.1f(%.0f%%K) ".formatted(t, Nt, Nt / K * 100));
        }
        ClaimStatus status = bestDeviation < 0.99 ? ClaimStatus.SUPPORTED : ClaimStatus.OBSERVATIONAL;
        String assessment = ("Verhulst logistic: r=%.3f, K=%.1f, N₀=%.1f. Max distance from carrying capacity %.3f at t=%.2f (N=%.1f). %s")
                .formatted(r, K, N0, bestDeviation, bestT, bestN,
                        bestDeviation < 0.5 ? "population approaches K within the sweep"
                                : "population still well below K — early growth phase");
        return new ResearchResult(
                "Verhulst logistic (r=%.3f, K=%.1f, %d samples): max distance-from-K=%.3f at t=%.2f, N=%.1f. Sample sweep: %s"
                        .formatted(r, K, samples, bestDeviation, bestT, bestN, sb.toString().trim()),
                bestDeviation, bestN, K, bestDeviation, status, assessment)
                .withHypothesis(("For a population with intrinsic growth rate r=%.3f starting at N₀=%.1f in an "
                        + "environment with carrying capacity K=%.1f, the Verhulst dN/dt = r·N·(1−N/K) drives "
                        + "N(t) → K monotonically; the time required to reach 95%% of K characterises r.")
                        .formatted(r, N0, K));
    }

    /** Lotka-Volterra coupled ODE. Returns max-cycle-amplitude ratio across param sweeps. */
    private static ResearchResult lotkaVolterra(double alpha, double beta, SearchParams sp) {
        // α = prey intrinsic growth, β = predation rate, γ = predator death, δ = predator gain.
        double gamma = 0.3;
        double delta = 0.01;
        double bestRatio = 0;
        double bestAlpha = alpha;
        double bestPeakX = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double a = Math.max(0.1, alpha + i * sp.stepSize() * 0.05);
            // Discrete-time approximation over a few cycle periods.
            double x = 40.0, y = 9.0;
            double dt = 0.05;
            double maxX = x, minX = x;
            for (int step = 0; step < 400; step++) {
                double dx = (a * x - beta * x * y) * dt;
                double dy = (delta * x * y - gamma * y) * dt;
                x = Math.max(0.0, x + dx);
                y = Math.max(0.0, y + dy);
                if (x > maxX) maxX = x;
                if (x < minX) minX = x;
            }
            double ratio = maxX / Math.max(0.001, minX);
            samples++;
            if (ratio > bestRatio) { bestRatio = ratio; bestAlpha = a; bestPeakX = maxX; }
            if (i < 4) sb.append("α=%.3f→x_max/x_min=%.2f ".formatted(a, ratio));
        }
        // Cycle ratio >= 1.5 indicates stable oscillation; explosion >> 1e6 is unphysical.
        boolean physical = bestRatio < 1e6 && Double.isFinite(bestPeakX);
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : bestRatio >= 1.5 ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = ("Lotka-Volterra: β=%.4f, γ=%.2f, δ=%.3f; max amplitude ratio %.2f at α=%.3f (peak x=%.1f). %s")
                .formatted(beta, gamma, delta, bestRatio, bestAlpha, bestPeakX,
                        !physical ? "trajectory diverged — unphysical predation parameters"
                                : bestRatio >= 1.5 ? "predator-prey cycles are clearly oscillatory"
                                        : "weak oscillation — system tracks equilibrium");
        return new ResearchResult(
                "Lotka-Volterra (β=%.4f, γ=%.2f, δ=%.3f, %d α-samples): max x_max/x_min=%.2f at α=%.3f. Sample: %s"
                        .formatted(beta, gamma, delta, samples, bestRatio, bestAlpha, sb.toString().trim()),
                bestRatio, bestPeakX, bestAlpha, bestRatio, status, assessment)
                .withHypothesis(("With predation β=%.4f, predator death γ=%.2f, predator gain δ=%.3f, and prey "
                        + "intrinsic growth swept around α=%.3f, the Lotka-Volterra ODEs sustain bounded "
                        + "oscillations; the amplitude ratio x_max/x_min quantifies cycle vigour.")
                        .formatted(beta, gamma, delta, alpha));
    }

    /** Michaelis-Menten v = V_max·[S]/(K_m + [S]). Estimates K_m via Lineweaver-Burk style. */
    private static ResearchResult michaelisMenten(double Km, double Vmax, SearchParams sp) {
        double bestRecoveryError = Double.MAX_VALUE;
        double bestKmRecovered = Km;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            // Use a clean two-point estimate: v at [S]=K_m gives v=V_max/2.
            // Pretend we measure v at [S]_1 and [S]_2 with a noise band ε.
            double S1 = Km * 0.5;
            double S2 = Km * 4.0;
            double noise = Math.sin(i * 0.7) * 0.02;
            double v1 = Vmax * S1 / (Km + S1) * (1.0 + noise);
            double v2 = Vmax * S2 / (Km + S2) * (1.0 + noise);
            // Solve for K_m from two (S, v) pairs:
            //   K_m = (v1·v2·(S2 − S1)) / (v1·S1·v2/V_max − v2·S2·v1/V_max) — algebra-heavy.
            // Easier: K_m = S·(V_max/v − 1). Average from both pairs.
            double Km1 = S1 * (Vmax / v1 - 1.0);
            double Km2 = S2 * (Vmax / v2 - 1.0);
            double KmEst = (Km1 + Km2) / 2.0;
            double err = Math.abs(KmEst - Km) / Km;
            samples++;
            if (err < bestRecoveryError) { bestRecoveryError = err; bestKmRecovered = KmEst; }
            if (i < 4) sb.append("K_m_est=%.3f(err=%.3f) ".formatted(KmEst, err));
        }
        ClaimStatus status = bestRecoveryError < 0.10 ? ClaimStatus.SUPPORTED
                : bestRecoveryError < 0.30 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Michaelis-Menten: V_max=%.3f, true K_m=%.3f; best recovered K_m=%.3f (err=%.3f). %s")
                .formatted(Vmax, Km, bestKmRecovered, bestRecoveryError,
                        bestRecoveryError < 0.10 ? "two-point K_m recovery within 10% — saturation kinetics validated"
                                : "recovery error exceeds 10% — noise band or non-Michaelian behaviour");
        return new ResearchResult(
                "Michaelis-Menten (V_max=%.3f, K_m=%.3f, %d samples): best K_m recovery err=%.3f. Sample: %s"
                        .formatted(Vmax, Km, samples, bestRecoveryError, sb.toString().trim()),
                bestRecoveryError, bestKmRecovered, Vmax, bestRecoveryError, status, assessment)
                .withHypothesis(("For an enzyme with V_max=%.3f and K_m=%.3f, the initial-rate Michaelis-Menten "
                        + "law v = V_max·[S]/(K_m+[S]) permits K_m recovery from two-point substrate measurements "
                        + "within 10%% under modest noise.")
                        .formatted(Vmax, Km));
    }

    /** Kleiber's law R ∝ M^(3/4). Fits exponent across body masses. */
    private static ResearchResult kleiberAllometric(double seedM, SearchParams sp) {
        // Generate (M, R) pairs from R = R0·M^0.75 with noise, then fit exponent.
        double R0 = 3.5;
        double trueExp = 0.75;
        java.util.List<double[]> samplesData = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double M = Math.max(0.01, seedM * Math.pow(2.0, i * sp.stepSize() - 4));
            double R = R0 * Math.pow(M, trueExp) * (1.0 + Math.sin(i * 0.41) * 0.03);
            samplesData.add(new double[]{M, R});
            if (i < 4) sb.append("M=%.2f→R=%.2f ".formatted(M, R));
        }
        // Linear regression on log(R) vs log(M).
        double sumLogM = 0, sumLogR = 0, sumLogMLogR = 0, sumLogMSq = 0;
        int n = samplesData.size();
        for (double[] p : samplesData) {
            double logM = Math.log(p[0]);
            double logR = Math.log(p[1]);
            sumLogM += logM;
            sumLogR += logR;
            sumLogMLogR += logM * logR;
            sumLogMSq += logM * logM;
        }
        double slope = (n * sumLogMLogR - sumLogM * sumLogR)
                / Math.max(1e-12, n * sumLogMSq - sumLogM * sumLogM);
        double deviation = Math.abs(slope - trueExp);
        ClaimStatus status = deviation < 0.05 ? ClaimStatus.SUPPORTED
                : deviation < 0.10 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Kleiber: fitted exponent %.3f vs Kleiber's 0.75 (deviation %.3f) across %d masses. %s")
                .formatted(slope, deviation, n,
                        deviation < 0.05 ? "Kleiber's three-quarters law holds within 5%"
                                : "exponent drifts > 5% from 0.75 — additional metabolic terms suspected");
        return new ResearchResult(
                "Kleiber allometric (R₀=%.2f, true exp=0.75, %d masses): fitted exp=%.3f (dev %.3f). Sample: %s"
                        .formatted(R0, n, slope, deviation, sb.toString().trim()),
                deviation, slope, R0, deviation, status, assessment)
                .withHypothesis(("Across a sweep of body masses centred near M=%.2f, the log-log slope of "
                        + "metabolic rate vs mass will recover Kleiber's 3/4 exponent within 5%% under noise.")
                        .formatted(seedM));
    }

    /** Hardy-Weinberg p² + 2pq + q² = 1. Tracks allele-frequency drift across generations. */
    private static ResearchResult hardyWeinberg(double seedP, SearchParams sp) {
        double p = Math.max(0.05, Math.min(0.95, seedP));
        double q = 1.0 - p;
        double maxDrift = 0;
        double finalP = p;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            int generations = (int) (10 + i * sp.stepSize() * 4);
            // Idealised: no selection, no migration, no mutation → p stays fixed exactly.
            // Add small noise to simulate finite-population drift.
            double pn = p;
            double localMaxDrift = 0;
            for (int gen = 0; gen < generations; gen++) {
                double driftStep = Math.sin(gen * 0.13 + i * 0.5) * 0.002;
                pn = Math.max(0.0, Math.min(1.0, pn + driftStep));
                double drift = Math.abs(pn - p);
                if (drift > localMaxDrift) localMaxDrift = drift;
            }
            samples++;
            if (localMaxDrift > maxDrift) { maxDrift = localMaxDrift; finalP = pn; }
            if (i < 4) sb.append("gen=%d→p=%.4f(drift=%.4f) ".formatted(generations, pn, localMaxDrift));
        }
        ClaimStatus status = maxDrift < 0.05 ? ClaimStatus.SUPPORTED
                : maxDrift < 0.10 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Hardy-Weinberg: starting p=%.3f (q=%.3f); max drift %.4f after %d simulations. %s")
                .formatted(p, q, maxDrift, samples,
                        maxDrift < 0.05 ? "allele frequencies stable within 5% — HWE holds"
                                : "drift exceeds 5% — small-population deviation");
        return new ResearchResult(
                "Hardy-Weinberg (p₀=%.3f, %d generation sweeps): max drift %.4f, ending p=%.4f. Sample: %s"
                        .formatted(p, samples, maxDrift, finalP, sb.toString().trim()),
                maxDrift, finalP, p, maxDrift, status, assessment)
                .withHypothesis(("In an infinite randomly mating population starting from allele frequencies "
                        + "p=%.3f, q=%.3f, the Hardy-Weinberg equilibrium p² + 2pq + q² = 1 predicts no "
                        + "generational drift; sampled finite-population trajectories stay within 5%% of p₀.")
                        .formatted(p, q));
    }

    // ---- Phase 7 FX.2: CHEMISTRY ----

    private static ResearchResult computeChemistry(int topic, int param1, double param2, SearchParams sp) {
        String eq = equationFor("CHEMISTRY", topic);
        return switch (topic) {
            case 0 -> hendersonHasselbalch(4.0 + param1 / 30.0, sp).withEquation(eq);
            case 1 -> nernstPotential(0.5 + param1 / 100.0, 298.0 + param2 * 5.0, sp).withEquation(eq);
            case 2 -> beerLambert(0.1 + param2 / 90.0, 1.0 + param1 / 20.0, sp).withEquation(eq);
            case 3 -> equilibriumKc(1.0 + param2 / 2.0, 298.0 + param1 * 3.0, sp).withEquation(eq);
            default -> compressibilityZ(0.7 + param1 / 200.0, 1.0 + param2 / 4.0, sp).withEquation(eq);
        };
    }

    /** Henderson-Hasselbalch: pH = pKₐ + log₁₀([A⁻]/[HA]). Recovers pKₐ from titration curves. */
    private static ResearchResult hendersonHasselbalch(double truePKa, SearchParams sp) {
        double bestErr = Double.MAX_VALUE;
        double bestRecovered = truePKa;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            // Vary the ratio [A⁻]/[HA] across a buffer titration sweep.
            double ratio = Math.pow(10.0, -1.0 + i * sp.stepSize() * 0.2);
            double pH = truePKa + Math.log10(ratio) + Math.sin(i * 0.3) * 0.01;
            // Recover pKₐ assuming we know the ratio exactly.
            double pKaEst = pH - Math.log10(ratio);
            double err = Math.abs(pKaEst - truePKa);
            samples++;
            if (err < bestErr) { bestErr = err; bestRecovered = pKaEst; }
            if (i < 4) sb.append("ratio=%.3f→pH=%.2f(pKₐ_est=%.3f) ".formatted(ratio, pH, pKaEst));
        }
        ClaimStatus status = bestErr < 0.05 ? ClaimStatus.SUPPORTED
                : bestErr < 0.20 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Henderson-Hasselbalch: true pKₐ=%.3f, best recovered=%.3f (err=%.3f) across %d titration points. %s")
                .formatted(truePKa, bestRecovered, bestErr, samples,
                        bestErr < 0.05 ? "pKₐ recovered within 0.05 — buffer model holds"
                                : "recovery error > 0.05 — titration outside the buffer's effective region");
        return new ResearchResult(
                "Henderson-Hasselbalch (true pKₐ=%.3f, %d titration points): best recovery err=%.3f. Sample: %s"
                        .formatted(truePKa, samples, bestErr, sb.toString().trim()),
                bestErr, bestRecovered, truePKa, bestErr, status, assessment)
                .withHypothesis(("For a weak acid with pKₐ=%.3f, the Henderson-Hasselbalch equation "
                        + "permits pKₐ recovery from titration ratios within ±0.05 across a half-pH-unit sweep.")
                        .formatted(truePKa));
    }

    /** Nernst E = E° − (RT/nF)·ln Q. Tests cell-potential drift across concentration. */
    private static ResearchResult nernstPotential(double E0, double T, SearchParams sp) {
        double R = 8.314;
        double F = 96485.0;
        int n = 1;
        double bestDeviation = 0;
        double bestE = E0;
        double bestQ = 1.0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double Q = Math.pow(10.0, -2.0 + i * sp.stepSize() * 0.4);
            double E = E0 - (R * T / (n * F)) * Math.log(Q);
            double dev = Math.abs(E - E0);
            samples++;
            if (dev > bestDeviation) { bestDeviation = dev; bestE = E; bestQ = Q; }
            if (i < 4) sb.append("Q=%.3f→E=%.4fV ".formatted(Q, E));
        }
        // F3-style bound: |E − E°| ≤ 0.3 V indicates a sensible electrochemical range.
        boolean physical = bestDeviation <= 0.3;
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : bestDeviation > 0.05 ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = ("Nernst: E°=%.3f V, T=%.0f K; max |E−E°|=%.4f V at Q=%.3f. %s")
                .formatted(E0, T, bestDeviation, bestQ,
                        !physical ? "|E−E°| > 0.3 V indicates Q outside any physical electrochemical regime"
                                : bestDeviation > 0.05 ? "measurable concentration-driven shift"
                                        : "near-standard conditions across the sweep");
        return new ResearchResult(
                "Nernst (E°=%.3f V, T=%.0f K, %d Q-samples): max |E−E°|=%.4f V at Q=%.3f, E=%.4f V. Sample: %s"
                        .formatted(E0, T, samples, bestDeviation, bestQ, bestE, sb.toString().trim()),
                bestDeviation, bestE, bestQ, bestDeviation, status, assessment)
                .withHypothesis(("For an n=1 redox couple with E°=%.3f V at T=%.0f K, the Nernst equation "
                        + "produces measurable cell-potential shifts of order RT/F ≈ 25 mV per decade of Q.")
                        .formatted(E0, T));
    }

    /** Beer-Lambert: A = ε·c·l. Recovers molar absorptivity ε. */
    private static ResearchResult beerLambert(double trueEps, double trueL, SearchParams sp) {
        double bestErr = Double.MAX_VALUE;
        double bestRecoveredEps = trueEps;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double c = 0.001 + i * sp.stepSize() * 0.0005;
            double A = trueEps * c * trueL * (1.0 + Math.sin(i * 0.27) * 0.01);
            // Recover ε = A / (c·l).
            double epsRecovered = A / (c * trueL);
            double err = Math.abs(epsRecovered - trueEps) / trueEps;
            samples++;
            if (err < bestErr) { bestErr = err; bestRecoveredEps = epsRecovered; }
            if (i < 4) sb.append("c=%.4f,A=%.3f→ε=%.3f ".formatted(c, A, epsRecovered));
        }
        ClaimStatus status = bestErr < 0.05 ? ClaimStatus.SUPPORTED
                : bestErr < 0.15 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Beer-Lambert: true ε=%.3f L·mol⁻¹·cm⁻¹, l=%.2f cm; best ε recovery err=%.3f. %s")
                .formatted(trueEps, trueL, bestErr,
                        bestErr < 0.05 ? "linearity holds within 5% — Beer-Lambert valid in this concentration range"
                                : "absorbance / concentration deviates > 5% — non-linear regime");
        return new ResearchResult(
                "Beer-Lambert (ε=%.3f, l=%.2f cm, %d c-samples): best ε recovery err=%.3f, est ε=%.3f. Sample: %s"
                        .formatted(trueEps, trueL, samples, bestErr, bestRecoveredEps, sb.toString().trim()),
                bestErr, bestRecoveredEps, trueEps, bestErr, status, assessment)
                .withHypothesis(("For a dilute solution of molar absorptivity ε=%.3f L·mol⁻¹·cm⁻¹ in a "
                        + "%.2f cm cell, the Beer-Lambert law A = ε·c·l holds within 5%% across the swept "
                        + "concentration range.")
                        .formatted(trueEps, trueL));
    }

    /** Equilibrium constant Kc drift across temperature (Van't Hoff). */
    private static ResearchResult equilibriumKc(double Kc298, double seedT, SearchParams sp) {
        double R = 8.314;
        double deltaH = 30_000.0;  // J/mol, modest endothermic
        double bestDrift = 0;
        double bestT = seedT;
        double bestKc = Kc298;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double T = Math.max(250.0, seedT + i * sp.stepSize() * 10.0);
            // Van't Hoff integrated: ln(K2/K1) = -ΔH/R · (1/T2 − 1/T1)
            double lnRatio = -deltaH / R * (1.0 / T - 1.0 / 298.15);
            double Kc = Kc298 * Math.exp(lnRatio);
            double drift = Math.abs(Math.log(Kc / Kc298));
            samples++;
            if (drift > bestDrift) { bestDrift = drift; bestT = T; bestKc = Kc; }
            if (i < 4) sb.append("T=%.0fK→Kc=%.3f(|ln drift|=%.3f) ".formatted(T, Kc, drift));
        }
        boolean physical = Double.isFinite(bestKc) && bestKc > 0;
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : bestDrift > 0.3 ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = ("Equilibrium Kc (Van't Hoff, ΔH=%.0f J/mol): max |ln(Kc/Kc°)|=%.3f at T=%.0f K (Kc=%.3f). %s")
                .formatted(deltaH, bestDrift, bestT, bestKc,
                        !physical ? "Kc went non-finite — runaway exponential at extreme T"
                                : bestDrift > 0.3 ? "Le-Chatelier shift clearly visible across the sweep"
                                        : "minimal temperature-driven shift");
        return new ResearchResult(
                "Equilibrium Kc (Kc°=%.3f at 298K, ΔH=%.0f J/mol, %d T-samples): max |ln drift|=%.3f at T=%.0f K (Kc=%.3f). Sample: %s"
                        .formatted(Kc298, deltaH, samples, bestDrift, bestT, bestKc, sb.toString().trim()),
                bestDrift, bestKc, bestT, bestDrift, status, assessment)
                .withHypothesis(("For a reaction with Kc°=%.3f at 298 K and ΔH=%.0f J/mol, the Van't Hoff "
                        + "integrated equation predicts measurable Kc drift across the swept temperature "
                        + "range, with magnitude bounded by exp(|ΔH/R · (1/T − 1/T°)|).")
                        .formatted(Kc298, deltaH));
    }

    /** Compressibility factor Z = PV/RT. Tracks real-gas departure from ideal. */
    private static ResearchResult compressibilityZ(double seedTr, double seedPr, SearchParams sp) {
        double bestDev = 0;
        double bestTr = seedTr;
        double bestPr = seedPr;
        double bestZ = 1.0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        int clampedSamples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            // Sweep reduced state (Tr, Pr). Use a small Pitzer-Curl style approximation.
            double Tr = Math.max(0.5, seedTr + i * sp.stepSize() * 0.05);
            double PrRaw = Math.max(0.1, seedPr + i * sp.stepSize() * 0.2);
            // Empirical Pitzer-Curl-ish: Z ≈ 1 + B0·Pr/Tr where B0 = 0.083 − 0.422·Tr^(-1.6).
            double B0 = 0.083 - 0.422 * Math.pow(Tr, -1.6);
            // Phase 10 — clamp Pr to the validity envelope of the truncated
            // virial equation. The P-a328-359 paper reported Z = -0.142 at
            // (Tr=0.79, Pr=1.73): the bare formula gives Z < 0 there, which
            // is physically impossible (Z = PV/nRT must be positive). Without
            // a fuller EOS (Peng-Robinson) we cap Pr so the linearised model
            // stays in its valid envelope:
            //   Z ≥ 0.05  ⇒  Pr ≤ 0.95·Tr / |B0|  when B0 < 0.
            // Higher pressures need a cubic EOS; this simulator's value is to
            // demonstrate real-gas departure, not to model the dense regime.
            double Pr = PrRaw;
            if (B0 < 0) {
                double maxValidPr = 0.95 * Tr / Math.abs(B0);
                if (PrRaw > maxValidPr) {
                    Pr = maxValidPr;
                    clampedSamples++;
                }
            }
            double Z = 1.0 + B0 * Pr / Tr;
            double dev = Math.abs(1.0 - Z);
            samples++;
            if (dev > bestDev) { bestDev = dev; bestTr = Tr; bestPr = Pr; bestZ = Z; }
            if (i < 4) sb.append("(Tr=%.2f,Pr=%.2f)→Z=%.3f ".formatted(Tr, Pr, Z));
        }
        // Bound: |1−Z| ≤ 0.5 in the explored range; beyond that physics simplifies.
        boolean physical = bestDev <= 0.5;
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : bestDev > 0.1 ? ClaimStatus.SUPPORTED : ClaimStatus.OBSERVATIONAL;
        String envelopeNote = clampedSamples > 0
                ? " (%d of %d samples clamped to Pitzer-Curl validity envelope; dense regime needs cubic EOS)"
                        .formatted(clampedSamples, samples)
                : "";
        String assessment = ("Z-factor: best (Tr=%.2f, Pr=%.2f, Z=%.3f) shows %.3f departure from ideal across %d samples.%s %s")
                .formatted(bestTr, bestPr, bestZ, bestDev, samples, envelopeNote,
                        !physical ? "|1−Z| > 0.5 indicates the explored state is far from the corresponding-states regime"
                                : bestDev > 0.1 ? "real-gas departure measurable — Pitzer correction important"
                                        : "near-ideal regime — Z ≈ 1");
        return new ResearchResult(
                "Compressibility Z (%d (Tr, Pr) samples%s): max |1−Z|=%.3f at Tr=%.2f, Pr=%.2f (Z=%.3f). Sample: %s"
                        .formatted(samples,
                                clampedSamples > 0 ? ", " + clampedSamples + " clamped" : "",
                                bestDev, bestTr, bestPr, bestZ, sb.toString().trim()),
                bestDev, bestZ, bestPr, bestDev, status, assessment)
                .withHypothesis(("Across reduced states sampled near (Tr=%.2f, Pr=%.2f), the Pitzer-Curl "
                        + "compressibility factor Z = 1 + B₀·Pr/Tr captures real-gas departure with |1−Z| ≤ 0.5.")
                        .formatted(seedTr, seedPr));
    }

    // ---- Phase 7 FX.3: ARCHITECTURE_TERRESTRIAL ----
    // ALL methods generic over surface gravity g and atmospheric density ρ_atm.
    // No Earth-anchored constants in the compute logic. Param mapping:
    //   param1 → primary structural parameter (length, mass, etc.)
    //   param2 → environment scaling (gravity multiplier, atmospheric density multiplier)
    // The seed environment is Earth-like only because it's a familiar reference; the
    // sweep covers regimes from Mars-low (~0.38·g_E, ~0.02·ρ_E) to Venus-high (~0.91·g_E,
    // ~65·ρ_E) so a single bot can publish findings about structures on any planet.

    private static ResearchResult computeArchTerrestrial(int topic, int param1, double param2, SearchParams sp) {
        String eq = equationFor("ARCHITECTURE_TERRESTRIAL", topic);
        // Environment seed: g in [1, 25] m/s², ρ_atm in [0.01, 70] kg/m³.
        double gSeed   = 0.5 + (param1 / 99.0) * 25.0;
        double rhoSeed = 0.01 + (param2 / 9.0) * 70.0;
        return switch (topic) {
            case 0 -> beamDeflectionSelfWeight(gSeed, sp).withEquation(eq);
            case 1 -> windLoadPressure(rhoSeed, sp).withEquation(eq);
            case 2 -> bearingCapacityTerzaghi(gSeed, sp).withEquation(eq);
            case 3 -> thermalMassTimeLag(rhoSeed, sp).withEquation(eq);
            default -> daylightFactor(param1, sp).withEquation(eq);
        };
    }

    /** Cantilever self-weight deflection: δ = (ρ·g·A·L⁴)/(8·E·I). */
    private static ResearchResult beamDeflectionSelfWeight(double gSeed, SearchParams sp) {
        // Steel reference: ρ=7850 kg/m³, E=200 GPa, I/A·L² ≈ 1/12 for a rectangular cross-section.
        double rho = 7850.0;
        double E = 200e9;
        double width = 0.2;       // m
        double height = 0.4;      // m
        double I = (width * Math.pow(height, 3)) / 12.0;
        double A = width * height;
        double bestRatio = 0;
        double bestL = 0;
        double bestG = gSeed;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double g = Math.max(0.2, gSeed + Math.sin(i * 0.31) * 5.0);
            double L = 2.0 + i * sp.stepSize() * 0.5;
            double delta = (rho * g * A * Math.pow(L, 4)) / (8.0 * E * I);
            double ratio = delta / L;
            samples++;
            if (ratio > bestRatio) { bestRatio = ratio; bestL = L; bestG = g; }
            if (i < 4) sb.append("L=%.1fm,g=%.2fm/s²→δ/L=%.4f ".formatted(L, g, ratio));
        }
        ClaimStatus status = bestRatio < 0.005 ? ClaimStatus.SUPPORTED
                : bestRatio < 0.01 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Self-weight beam deflection: max δ/L=%.4f at L=%.1f m under g=%.2f m/s². %s")
                .formatted(bestRatio, bestL, bestG,
                        bestRatio < 0.005 ? "deflection within standard L/200 building tolerance"
                                : bestRatio < 0.01 ? "borderline tolerance (L/100)"
                                        : "deflection exceeds L/100 — beam too long for this gravity regime");
        return new ResearchResult(
                "Cantilever self-weight (ρ=%.0f kg/m³, E=%.0e Pa, A=%.3f m², I=%.3e m⁴, %d samples): max δ/L=%.4f at L=%.1f m, g=%.2f m/s². Sample: %s"
                        .formatted(rho, E, A, I, samples, bestRatio, bestL, bestG, sb.toString().trim()),
                bestRatio, bestL, bestG, bestRatio, status, assessment)
                .withHypothesis(("For a steel cantilever swept over lengths starting at 2 m on a planetary surface "
                        + "with g≈%.2f m/s², the self-weight deflection δ/L = (ρ·g·A·L³)/(8·E·I) stays below the "
                        + "standard L/200 serviceability limit while length remains under a critical L_c that "
                        + "scales as g^(−1/3).")
                        .formatted(gSeed));
    }

    /** Atmospheric wind load: q = ½·ρ_atm·v². */
    private static ResearchResult windLoadPressure(double rhoAtmSeed, SearchParams sp) {
        double bestPressure = 0;
        double bestV = 0;
        double bestRho = rhoAtmSeed;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double rhoAtm = Math.max(0.001, rhoAtmSeed + Math.sin(i * 0.27) * 5.0);
            double v = 5.0 + i * sp.stepSize() * 3.0;  // m/s, light breeze to hurricane
            double q = 0.5 * rhoAtm * v * v;
            samples++;
            if (q > bestPressure) { bestPressure = q; bestV = v; bestRho = rhoAtm; }
            if (i < 4) sb.append("ρ_atm=%.2fkg/m³,v=%.1fm/s→q=%.1fPa ".formatted(rhoAtm, v, q));
        }
        // Spec bound: q ≤ 10 kPa even at hurricane wind on Venus-like atmosphere is the
        // limit of conventional facade design.
        boolean physical = bestPressure <= 10_000.0;
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : bestPressure > 500.0 ? ClaimStatus.SUPPORTED : ClaimStatus.OBSERVATIONAL;
        String assessment = ("Wind load: max q=%.1f Pa at v=%.1f m/s, ρ_atm=%.2f kg/m³. %s")
                .formatted(bestPressure, bestV, bestRho,
                        !physical ? "q > 10 kPa exceeds conventional facade design loads"
                                : bestPressure > 500.0 ? "design-relevant facade loading"
                                        : "low-pressure regime — minor facade design driver");
        return new ResearchResult(
                "Wind load (%d samples): max q=%.1f Pa at v=%.1f m/s, ρ_atm=%.2f kg/m³. Sample: %s"
                        .formatted(samples, bestPressure, bestV, bestRho, sb.toString().trim()),
                bestPressure, bestV, bestRho, bestPressure, status, assessment)
                .withHypothesis(("For a vertical facade exposed to local atmospheric density ρ_atm≈%.2f kg/m³ "
                        + "with wind velocities swept from 5 m/s upward, the design pressure q=½·ρ_atm·v² scales "
                        + "as v² and remains below conventional 10 kPa facade limits across the sampled regime.")
                        .formatted(rhoAtmSeed));
    }

    /** Terzaghi bearing capacity: q_u = c·N_c + γ_soil·D·N_q + 0.5·γ_soil·B·N_γ. */
    private static ResearchResult bearingCapacityTerzaghi(double gSeed, SearchParams sp) {
        double cohesion = 20_000.0;  // Pa, cohesive soil
        double rhoSoil = 1900.0;      // kg/m³
        double Nc = 17.7, Nq = 7.4, Ngamma = 5.0;  // friction angle 20° coefficients
        double maxQ = 0;
        double bestB = 1.0;
        double bestG = gSeed;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double g = Math.max(0.5, gSeed + Math.sin(i * 0.23) * 3.0);
            double gammaSoil = rhoSoil * g;
            double D = 1.0 + i * sp.stepSize() * 0.5;
            double B = 1.0 + i * sp.stepSize() * 0.5;
            double q_u = cohesion * Nc + gammaSoil * D * Nq + 0.5 * gammaSoil * B * Ngamma;
            double q_kPa = q_u / 1000.0;
            samples++;
            if (q_kPa > maxQ) { maxQ = q_kPa; bestB = B; bestG = g; }
            if (i < 4) sb.append("g=%.2f,B=%.1fm→q_u=%.0fkPa ".formatted(g, B, q_kPa));
        }
        ClaimStatus status = maxQ < 2000 ? ClaimStatus.SUPPORTED
                : maxQ < 5000 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Terzaghi bearing: max q_u=%.0f kPa at B=%.1f m, g=%.2f m/s². %s")
                .formatted(maxQ, bestB, bestG,
                        maxQ < 2000 ? "bearing within standard cohesive-soil design envelope"
                                : maxQ < 5000 ? "high-capacity foundation regime"
                                        : "capacity > 5 MPa — bedrock or unusual soil");
        return new ResearchResult(
                "Bearing capacity (c=%.0f Pa, ρ_soil=%.0f kg/m³, %d samples): max q_u=%.0f kPa at B=%.1f m, g=%.2f m/s². Sample: %s"
                        .formatted(cohesion, rhoSoil, samples, maxQ, bestB, bestG, sb.toString().trim()),
                maxQ, bestB, bestG, maxQ, status, assessment)
                .withHypothesis(("For a strip foundation in cohesive soil (c=%.0f Pa, ρ_soil=%.0f kg/m³) on a "
                        + "planetary surface with g≈%.2f m/s², the Terzaghi bearing capacity q_u "
                        + "scales linearly with g via γ_soil = ρ_soil·g, giving lower-g worlds proportionally "
                        + "weaker shallow foundations.")
                        .formatted(cohesion, rhoSoil, gSeed));
    }

    /** Thermal mass time-lag: τ = (ρ·c_p·V)/(h·A). */
    private static ResearchResult thermalMassTimeLag(double rhoAtmSeed, SearchParams sp) {
        double rho = 2400.0;       // kg/m³, concrete
        double cp = 880.0;         // J/(kg·K), concrete
        // h scales with atmospheric density via natural convection:
        // h ≈ k·(ρ_atm)^0.5 across realistic regimes.
        double thickness = 0.2;    // m
        double V_per_A = thickness;   // V/A = thickness for a slab
        double maxTau = 0;
        double bestThickness = thickness;
        double bestRho = rhoAtmSeed;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double t_wall = Math.max(0.05, thickness + i * sp.stepSize() * 0.05);
            double rhoAtm = Math.max(0.001, rhoAtmSeed + Math.sin(i * 0.29) * 5.0);
            double h = 5.0 * Math.sqrt(rhoAtm);  // W/(m²·K), heuristic
            double tau = (rho * cp * t_wall) / Math.max(0.1, h);
            double tauHours = tau / 3600.0;
            samples++;
            if (tauHours > maxTau) { maxTau = tauHours; bestThickness = t_wall; bestRho = rhoAtm; }
            if (i < 4) sb.append("t=%.2fm,ρ_atm=%.2f→τ=%.1fh ".formatted(t_wall, rhoAtm, tauHours));
        }
        ClaimStatus status = maxTau < 24 ? ClaimStatus.SUPPORTED
                : maxTau < 48 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Thermal mass τ: max τ=%.1f h at t=%.2f m, ρ_atm=%.2f kg/m³. %s")
                .formatted(maxTau, bestThickness, bestRho,
                        maxTau < 24 ? "diurnal time-lag fits a daily cycle"
                                : maxTau < 48 ? "multi-day lag — thick wall in thin atmosphere"
                                        : "unrealistic τ > 48 h — recheck h-coefficient model");
        return new ResearchResult(
                "Thermal mass (ρ=%.0f kg/m³, c_p=%.0f J/kg·K, %d samples): max τ=%.1f h at t=%.2f m, ρ_atm=%.2f kg/m³. Sample: %s"
                        .formatted(rho, cp, samples, maxTau, bestThickness, bestRho, sb.toString().trim()),
                maxTau, bestThickness, bestRho, maxTau, status, assessment)
                .withHypothesis(("For a concrete wall with thickness swept from 0.2 m exposed to atmospheric "
                        + "density ρ_atm≈%.2f kg/m³, the thermal time-lag τ=(ρ·c_p·V)/(h·A) scales as 1/√ρ_atm "
                        + "through the convective coefficient, so thinner atmospheres produce longer lags.")
                        .formatted(rhoAtmSeed));
    }

    /** Daylight factor: DF = (E_internal / E_external)·100. */
    private static ResearchResult daylightFactor(int param1, SearchParams sp) {
        // Internal illuminance proportional to (window area · sky visibility · transmittance).
        double tau = 0.7;            // glazing transmittance
        double maxDF = 0;
        double bestRatio = 0;
        double bestSky = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double windowToFloor = 0.05 + (param1 / 99.0) * 0.3 + i * sp.stepSize() * 0.02;
            double skyVisibility = 0.3 + i * sp.stepSize() * 0.1;
            double DF = windowToFloor * skyVisibility * tau * 100.0;
            samples++;
            if (DF > maxDF) { maxDF = DF; bestRatio = windowToFloor; bestSky = skyVisibility; }
            if (i < 4) sb.append("WFR=%.2f,sky=%.2f→DF=%.2f%% ".formatted(windowToFloor, skyVisibility, DF));
        }
        ClaimStatus status = maxDF >= 2 && maxDF <= 20 ? ClaimStatus.SUPPORTED
                : maxDF < 2 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Daylight factor: max DF=%.2f%% at WFR=%.2f, sky=%.2f. %s")
                .formatted(maxDF, bestRatio, bestSky,
                        maxDF >= 2 && maxDF <= 20 ? "DF in the 2–20% useful-daylight band"
                                : maxDF < 2 ? "DF < 2% — under-lit"
                                        : "DF > 20% — overheating risk dominant");
        return new ResearchResult(
                "Daylight factor (transmittance=%.2f, %d samples): max DF=%.2f%% at WFR=%.2f, sky-vis=%.2f. Sample: %s"
                        .formatted(tau, samples, maxDF, bestRatio, bestSky, sb.toString().trim()),
                maxDF, bestRatio, bestSky, maxDF, status, assessment)
                .withHypothesis(("For window-to-floor ratios swept around %.2f with sky visibility ranging from "
                        + "0.3 to 1.0, the daylight factor DF=(E_internal/E_external)·100 lands in the standard "
                        + "2–20%% useful-daylight band; this holds independent of planetary latitude or "
                        + "atmospheric scattering regime as long as the external illuminance is non-zero.")
                        .formatted(0.05 + (param1 / 99.0) * 0.3));
    }

    // ---- Phase 7 FX.4: ARCHITECTURE_LIQUID ----
    // Generic over ANY liquid. Liquid property regimes covered by the param sweep:
    //   • Water (ρ ≈ 1000 kg/m³, γ ≈ 0.072 N/m, μ ≈ 1.0 mPa·s)
    //   • Methane (Titan lakes; ρ ≈ 420, γ ≈ 0.013, μ ≈ 0.18 mPa·s)
    //   • Ammonia (cryovolcano; ρ ≈ 680, γ ≈ 0.022, μ ≈ 0.13 mPa·s)
    //   • Mercury (ρ ≈ 13534, γ ≈ 0.486, μ ≈ 1.55 mPa·s)
    //   • Liquid magma (ρ ≈ 2700, γ ≈ 0.37, μ ≈ 1e3 mPa·s)
    // Param mapping:
    //   param1 → primary geometric scale (depth, hull radius, body volume)
    //   param2 → liquid-density seed (1.0 ≈ low-density liquid, 9.0 ≈ very dense)

    private static ResearchResult computeArchLiquid(int topic, int param1, double param2, SearchParams sp) {
        String eq = equationFor("ARCHITECTURE_LIQUID", topic);
        // Liquid density seed in [200, 14000] kg/m³ across the param2 range.
        double rhoLiqSeed = 200.0 + (param2 / 9.0) * 13800.0;
        // Surface tension scales roughly with density^(2/3) for similar polar liquids.
        double gammaSeed = 0.005 + 0.5 * Math.pow(rhoLiqSeed / 14000.0, 0.6);
        // Viscosity seed in mPa·s; defaults to water-like.
        double muSeed = 0.1 + (param1 / 99.0) * 9.0;
        return switch (topic) {
            case 0 -> hydrostaticPressureDepth(rhoLiqSeed, sp).withEquation(eq);
            case 1 -> buoyantLiftCapacity(rhoLiqSeed, sp).withEquation(eq);
            case 2 -> hullBucklingPressure(rhoLiqSeed, sp).withEquation(eq);
            case 3 -> hydrodynamicDrag(rhoLiqSeed, muSeed, sp).withEquation(eq);
            default -> dissolvedGasSolubility(gammaSeed, rhoLiqSeed, sp).withEquation(eq);
        };
    }

    /** Hydrostatic: P(z) = ρ_liq·g·z. Generic over liquid density and local gravity. */
    private static ResearchResult hydrostaticPressureDepth(double rhoLiq, SearchParams sp) {
        double g = 9.81;  // can be swept too; here we vary depth and ρ_liq instead
        double maxP = 0;
        double bestDepth = 0;
        double bestRho = rhoLiq;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double rho = Math.max(100.0, rhoLiq + Math.sin(i * 0.27) * 500.0);
            double depth = 1.0 + i * sp.stepSize() * 5.0;
            double P = rho * g * depth;
            samples++;
            if (P > maxP) { maxP = P; bestDepth = depth; bestRho = rho; }
            if (i < 4) sb.append("z=%.1fm,ρ=%.0fkg/m³→P=%.0fPa ".formatted(depth, rho, P));
        }
        boolean physical = maxP < 1.0e8;  // 100 MPa bound — deep ocean limit
        ClaimStatus status = !physical ? ClaimStatus.REFUTED
                : maxP > 1.0e4 ? ClaimStatus.SUPPORTED : ClaimStatus.OBSERVATIONAL;
        String assessment = ("Hydrostatic: max P=%.0f Pa at z=%.1f m, ρ_liq=%.0f kg/m³. %s")
                .formatted(maxP, bestDepth, bestRho,
                        !physical ? "P > 100 MPa lies beyond conventional submerged-habitat designs"
                                : maxP > 1.0e4 ? "design-relevant pressure regime"
                                        : "shallow-depth regime — low hydrostatic loading");
        return new ResearchResult(
                "Hydrostatic pressure (%d samples): max P=%.0f Pa at z=%.1f m, ρ_liq=%.0f kg/m³. Sample: %s"
                        .formatted(samples, maxP, bestDepth, bestRho, sb.toString().trim()),
                maxP, bestDepth, bestRho, maxP, status, assessment)
                .withHypothesis(("For a column of a liquid with density ρ_liq≈%.0f kg/m³ under gravity g≈%.2f m/s², "
                        + "the hydrostatic pressure P(z) = ρ·g·z scales linearly with depth z; the maximum "
                        + "pressure at the swept depth bound stays under 100 MPa for design-relevant regimes.")
                        .formatted(rhoLiq, g));
    }

    /** Buoyant lift: F_b = ρ_liq·g·V_displaced. */
    private static ResearchResult buoyantLiftCapacity(double rhoLiq, SearchParams sp) {
        double g = 9.81;
        double maxF = 0;
        double bestV = 0;
        double bestRho = rhoLiq;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double rho = Math.max(100.0, rhoLiq + Math.sin(i * 0.27) * 500.0);
            double V = 1.0 + i * sp.stepSize() * 0.5;  // m³ displaced
            double F = rho * g * V;
            samples++;
            if (F > maxF) { maxF = F; bestV = V; bestRho = rho; }
            if (i < 4) sb.append("V=%.1fm³,ρ=%.0fkg/m³→F_b=%.0fN ".formatted(V, rho, F));
        }
        ClaimStatus status = maxF > 1.0e3 ? ClaimStatus.SUPPORTED : ClaimStatus.OBSERVATIONAL;
        String assessment = ("Buoyancy: max F_b=%.0f N at V=%.1f m³, ρ_liq=%.0f kg/m³. %s")
                .formatted(maxF, bestV, bestRho,
                        maxF > 1.0e3 ? "buoyant lift sufficient for habitat-scale displaced volumes"
                                : "low-displacement regime — small lift");
        return new ResearchResult(
                "Buoyant lift (%d samples): max F_b=%.0f N at V=%.1f m³, ρ_liq=%.0f kg/m³. Sample: %s"
                        .formatted(samples, maxF, bestV, bestRho, sb.toString().trim()),
                maxF, bestV, bestRho, maxF, status, assessment)
                .withHypothesis(("For a structure displacing V≈%.1f m³ of a liquid with density ρ_liq≈%.0f kg/m³ "
                        + "under gravity g≈%.2f m/s², Archimedes-principle buoyant lift F_b = ρ_liq·g·V scales "
                        + "linearly with both the liquid density and the displaced volume.")
                        .formatted(1.0 + sp.candidateBreadth() * sp.stepSize() * 0.5, rhoLiq, g));
    }

    /** Thin-walled cylindrical hull buckling: P_crit = 2·E·(t/R)³ / (1 − ν²). */
    private static ResearchResult hullBucklingPressure(double rhoLiq, SearchParams sp) {
        double E = 200e9;       // Pa, steel
        double nu = 0.3;        // Poisson ratio
        double R = 2.0;         // m, habitat radius
        double minPcrit = Double.MAX_VALUE;
        double bestT = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double t = 0.005 + i * sp.stepSize() * 0.002;
            double Pcrit = 2.0 * E * Math.pow(t / R, 3) / (1.0 - nu * nu);
            samples++;
            if (Pcrit < minPcrit) { minPcrit = Pcrit; bestT = t; }
            if (i < 4) sb.append("t=%.3fm→P_crit=%.0fPa ".formatted(t, Pcrit));
        }
        // Express minPcrit as P_crit ÷ a "design hydrostatic" at depth depth_max for the seed liquid.
        double designDepth = 100.0;
        double designP = rhoLiq * 9.81 * designDepth;
        double safetyMargin = minPcrit / Math.max(1.0, designP);
        ClaimStatus status = safetyMargin > 2.0 ? ClaimStatus.SUPPORTED
                : safetyMargin > 1.0 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Hull buckling: min P_crit=%.0f Pa at t=%.3f m (R=%.1f m); safety margin %.2f× design hydrostatic at %.0f m in liquid of density %.0f kg/m³. %s")
                .formatted(minPcrit, bestT, R, safetyMargin, designDepth, rhoLiq,
                        safetyMargin > 2.0 ? "hull design has > 2× margin over design hydrostatic"
                                : safetyMargin > 1.0 ? "marginal — < 2× safety factor"
                                        : "thinnest wall fails before reaching design depth");
        return new ResearchResult(
                "Hull buckling (E=%.0e Pa, R=%.1f m, %d t-samples): min P_crit=%.0f Pa at t=%.3f m. Sample: %s"
                        .formatted(E, R, samples, minPcrit, bestT, sb.toString().trim()),
                minPcrit, bestT, safetyMargin, minPcrit, status, assessment)
                .withHypothesis(("For a thin-walled cylindrical hull of radius R=%.1f m submerged in a liquid of "
                        + "density ρ_liq≈%.0f kg/m³, the critical buckling pressure P_crit = 2·E·(t/R)³/(1−ν²) "
                        + "rises cubically with wall thickness t; the design hydrostatic load at 100 m depth "
                        + "sets the minimum viable t.")
                        .formatted(R, rhoLiq));
    }

    /** Hydrodynamic drag: F_d = ½·ρ_liq·v²·A·C_d. C_d depends on Reynolds Re = ρ_liq·v·L/μ. */
    private static ResearchResult hydrodynamicDrag(double rhoLiq, double muMpas, SearchParams sp) {
        double mu = muMpas * 1e-3;  // Pa·s
        double L = 2.0, A = L * L;
        double maxF = 0;
        double bestV = 0;
        double bestCd = 0;
        double bestRe = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double v = 0.1 + i * sp.stepSize() * 0.5;
            double Re = rhoLiq * v * L / Math.max(1e-6, mu);
            // Sphere/blunt body C_d sketch: ~24/Re below Re=1, ~0.5 at Re>1e3, transition between.
            double Cd = Re < 1.0 ? 24.0 / Math.max(0.01, Re)
                    : Re < 1e3 ? 24.0 / Re + 0.5
                    : 0.5;
            double F = 0.5 * rhoLiq * v * v * A * Cd;
            samples++;
            if (F > maxF) { maxF = F; bestV = v; bestCd = Cd; bestRe = Re; }
            if (i < 4) sb.append("v=%.2fm/s→Re=%.0f,C_d=%.2f,F_d=%.0fN ".formatted(v, Re, Cd, F));
        }
        ClaimStatus status = maxF > 1.0 ? ClaimStatus.SUPPORTED : ClaimStatus.OBSERVATIONAL;
        String assessment = ("Drag: max F_d=%.0f N at v=%.2f m/s (Re=%.0f, C_d=%.2f). %s")
                .formatted(maxF, bestV, bestRe, bestCd,
                        bestRe > 1000 ? "turbulent regime — C_d ≈ 0.5"
                                : bestRe > 1 ? "transitional regime"
                                        : "creeping flow — Stokes drag dominates");
        return new ResearchResult(
                "Hydrodynamic drag (ρ_liq=%.0f kg/m³, μ=%.2f mPa·s, %d v-samples): max F_d=%.0f N at v=%.2f m/s, Re=%.0f. Sample: %s"
                        .formatted(rhoLiq, muMpas, samples, maxF, bestV, bestRe, sb.toString().trim()),
                maxF, bestV, bestCd, maxF, status, assessment)
                .withHypothesis(("For a body of characteristic length L≈2 m submerged in a liquid with density "
                        + "ρ_liq≈%.0f kg/m³ and viscosity μ≈%.2f mPa·s, the hydrodynamic drag F_d = ½·ρ·v²·A·C_d "
                        + "transitions from a 24/Re creeping-flow regime to a flat C_d≈0.5 turbulent regime "
                        + "across the swept velocity range.")
                        .formatted(rhoLiq, muMpas));
    }

    /** Henry's law dissolved gas: c = k_H · P_gas. k_H is liquid-specific. */
    private static ResearchResult dissolvedGasSolubility(double gamma, double rhoLiq, SearchParams sp) {
        // Toy model: k_H scales with surface tension γ (loose proxy for solvent strength).
        double kH = 0.0008 + gamma * 0.05;   // mol/(L·atm) — water ~0.0013 for O₂
        double maxC = 0;
        double bestP = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double P = 0.5 + i * sp.stepSize() * 0.5;
            double c = kH * P;
            samples++;
            if (c > maxC) { maxC = c; bestP = P; }
            if (i < 4) sb.append("P=%.1fatm→c=%.4fmol/L ".formatted(P, c));
        }
        ClaimStatus status = maxC < 1.0 ? ClaimStatus.SUPPORTED : ClaimStatus.PARTIAL;
        String assessment = ("Dissolved gas: max c=%.4f mol/L at P=%.1f atm (k_H≈%.4f mol/L·atm). %s")
                .formatted(maxC, bestP, kH,
                        maxC < 1.0 ? "Henry's-law linear regime holds across the swept pressure"
                                : "c approaches saturation — Henry's law breaks down");
        return new ResearchResult(
                "Henry's-law dissolution (k_H=%.4f mol/L·atm, ρ_liq=%.0f kg/m³, γ=%.3f N/m, %d P-samples): max c=%.4f mol/L. Sample: %s"
                        .formatted(kH, rhoLiq, gamma, samples, maxC, sb.toString().trim()),
                maxC, bestP, kH, maxC, status, assessment)
                .withHypothesis(("For a liquid with density ρ_liq≈%.0f kg/m³ and surface tension γ≈%.3f N/m "
                        + "(determining the host-specific Henry's coefficient k_H), the dissolved-gas "
                        + "concentration c = k_H·P_gas scales linearly with overpressure until saturation.")
                        .formatted(rhoLiq, gamma));
    }

    // ---- Phase 7 FX.5: ARCHITECTURE_SPACE ----

    private static ResearchResult computeArchSpace(int topic, int param1, double param2, SearchParams sp) {
        String eq = equationFor("ARCHITECTURE_SPACE", topic);
        return switch (topic) {
            case 0 -> radiationShieldingMass(0.1 + param2 / 10.0, sp).withEquation(eq);
            case 1 -> vacuumThermalBalance(50.0 + param1 * 20.0, sp).withEquation(eq);
            case 2 -> micrometeoroidFlux(50.0 + param1, sp).withEquation(eq);
            case 3 -> centrifugalGravityRadius(0.5 + param2 / 9.0 * 1.5, sp).withEquation(eq);
            default -> orbitalDecayRate(200.0 + param1 * 5.0, sp).withEquation(eq);
        };
    }

    /** Radiation shielding: σ_areal = Φ·E / D_max. Mass per area needed to attenuate dose. */
    private static ResearchResult radiationShieldingMass(double dailyFluxScale, SearchParams sp) {
        double E_dep = 5.0;       // MeV per particle, average dose deposit
        double Dmax = 500.0;      // mSv/year, crew exposure budget
        double maxSigma = 0;
        double bestPhi = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            // Φ in particles / cm² / day; vary by event regime.
            double Phi = Math.pow(10, 2 + dailyFluxScale + i * sp.stepSize() * 0.2);
            // Synthetic: required σ_areal scales as log10(Φ·E_dep / Dmax) g/cm².
            double sigma = 5.0 + 5.0 * Math.log10(Phi * E_dep / Math.max(1.0, Dmax));
            samples++;
            if (sigma > maxSigma) { maxSigma = sigma; bestPhi = Phi; }
            if (i < 4) sb.append("Φ=%.1e/cm²/d→σ=%.1fg/cm² ".formatted(Phi, sigma));
        }
        ClaimStatus status = maxSigma < 100.0 ? ClaimStatus.SUPPORTED
                : maxSigma < 300.0 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Shielding: max σ_areal=%.1f g/cm² at Φ=%.1e particles/cm²/day. %s")
                .formatted(maxSigma, bestPhi,
                        maxSigma < 100.0 ? "shield mass within feasible cislunar habitat budget"
                                : maxSigma < 300.0 ? "high-flux regime — heavy shielding needed"
                                        : "extreme solar-event flux — shielding mass impractical");
        return new ResearchResult(
                "Radiation shielding (%d Φ-samples, E_dep=%.1f MeV, D_max=%.0f mSv/yr): max σ_areal=%.1f g/cm² at Φ=%.1e/cm²/d. Sample: %s"
                        .formatted(samples, E_dep, Dmax, maxSigma, bestPhi, sb.toString().trim()),
                maxSigma, bestPhi, E_dep, maxSigma, status, assessment)
                .withHypothesis(("For a crewed module exposed to particle flux scaled near 10^%.1f /cm²/day with "
                        + "an exposure budget of D_max=%.0f mSv/yr, the areal shielding requirement σ_areal "
                        + "grows logarithmically with flux.")
                        .formatted(2 + dailyFluxScale, Dmax));
    }

    /** Vacuum thermal balance: T_eq⁴ = (Q_internal + α·S)/(ε·σ·A). */
    private static ResearchResult vacuumThermalBalance(double qInternalSeed, SearchParams sp) {
        double sigma = 5.670374419e-8;
        double A = 50.0;     // m² radiative area
        double S = 1361.0;   // W/m², solar constant at 1 AU (sweep can vary effective S)
        double alpha = 0.3;
        double epsilon = 0.85;
        double maxT = 0;
        double minT = Double.MAX_VALUE;
        double bestQ = qInternalSeed;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double Qint = Math.max(0.0, qInternalSeed + i * sp.stepSize() * 100.0);
            // Sweep effective insolation as well (orbit altitude / occultation fraction).
            double Seff = S * (0.5 + Math.sin(i * 0.31) * 0.4);
            double Teq = Math.pow((Qint + alpha * Seff * A) / (epsilon * sigma * A), 0.25);
            samples++;
            if (Teq > maxT) { maxT = Teq; bestQ = Qint; }
            if (Teq < minT) minT = Teq;
            if (i < 4) sb.append("Q=%.0fW,Sₑff=%.0fW/m²→T=%.0fK ".formatted(Qint, Seff, Teq));
        }
        ClaimStatus status = maxT >= 273 && maxT <= 320 ? ClaimStatus.SUPPORTED
                : maxT >= 200 && maxT <= 350 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Vacuum thermal: T range [%.0f, %.0f] K at α=%.2f, ε=%.2f, A=%.0f m². %s")
                .formatted(minT, maxT, alpha, epsilon, A,
                        maxT >= 273 && maxT <= 320 ? "habitat interior achievable around 273–320 K"
                                : maxT < 273 ? "module trends cold — extra Q_internal or insulation needed"
                                        : "module trends hot — additional radiators required");
        return new ResearchResult(
                "Vacuum thermal (α=%.2f, ε=%.2f, A=%.0f m², %d Q+S samples): max T=%.0f K at Q=%.0f W. Sample: %s"
                        .formatted(alpha, epsilon, A, samples, maxT, bestQ, sb.toString().trim()),
                maxT, minT, bestQ, maxT, status, assessment)
                .withHypothesis(("For a habitat with A=%.0f m² radiating surface, α=%.2f absorptivity, ε=%.2f "
                        + "emissivity, and Q_internal swept near %.0f W, the equilibrium temperature "
                        + "T_eq = [(Q_int+α·S·A)/(ε·σ·A)]^¼ falls within crew-habitable bands across the "
                        + "sampled solar-flux regimes.")
                        .formatted(A, alpha, epsilon, qInternalSeed));
    }

    /** Cumulative micrometeoroid impact count: N = Φ·A·t. */
    private static ResearchResult micrometeoroidFlux(double areaSeed, SearchParams sp) {
        double Phi = 1.0e-8;  // impacts/m²/s for sub-mm meteoroids
        double tYear = 3.156e7;  // seconds
        double maxImpacts = 0;
        double bestArea = areaSeed;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double A = Math.max(1.0, areaSeed + i * sp.stepSize() * 20.0);
            double mission = (1.0 + i * sp.stepSize() * 0.5) * tYear;
            double N = Phi * A * mission;
            samples++;
            if (N > maxImpacts) { maxImpacts = N; bestArea = A; }
            if (i < 4) sb.append("A=%.0fm²→N≈%.1f ".formatted(A, N));
        }
        ClaimStatus status = maxImpacts < 100 ? ClaimStatus.SUPPORTED
                : maxImpacts < 1000 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Impact flux: max %.1f sub-mm impacts on A=%.0f m² over the sweep. %s")
                .formatted(maxImpacts, bestArea,
                        maxImpacts < 100 ? "Whipple-shield design adequate"
                                : maxImpacts < 1000 ? "elevated impact regime — additional shielding needed"
                                        : "impact density requires re-evaluation of mission orbit");
        return new ResearchResult(
                "Micrometeoroid flux (Φ=%.0e /m²/s, %d (A, mission) samples): max impacts=%.1f at A=%.0f m². Sample: %s"
                        .formatted(Phi, samples, maxImpacts, bestArea, sb.toString().trim()),
                maxImpacts, bestArea, Phi, maxImpacts, status, assessment)
                .withHypothesis(("For habitat surfaces A swept near %.0f m² over mission durations spanning one "
                        + "to several years, the expected sub-mm meteoroid impact count scales linearly with "
                        + "A·t at the LEO micrometeoroid flux Φ≈10⁻⁸ /m²/s.")
                        .formatted(areaSeed));
    }

    /** Centrifugal artificial gravity: a = ω²·R. Find R for a given a_target at habitable rate. */
    private static ResearchResult centrifugalGravityRadius(double aTarget, SearchParams sp) {
        // a_target in fractions of g_E.
        double aMps = aTarget * 9.81;
        double bestR = 0;
        double bestOmega = 0;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            // ω ranges 0.2-4 rad/s (3-40 rpm).
            double omega = 0.2 + i * sp.stepSize() * 0.1;
            // Coriolis-tolerable: ω ≤ 0.3 rad/s (≈3 rpm) preferred. Above that, gait is uncomfortable.
            double R = aMps / (omega * omega);
            samples++;
            if (omega <= 0.3 && R > bestR) { bestR = R; bestOmega = omega; }
            if (i < 4) sb.append("ω=%.2frad/s→R=%.1fm ".formatted(omega, R));
        }
        // If no comfortable ω found, take the smallest R from any ω.
        if (bestR == 0 && samples > 0) {
            bestR = aMps / (0.3 * 0.3);
            bestOmega = 0.3;
        }
        ClaimStatus status = bestR < 100 ? ClaimStatus.SUPPORTED
                : bestR < 500 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Spin habitat: comfortable R=%.1f m at ω=%.2f rad/s for a_target=%.2f g. %s")
                .formatted(bestR, bestOmega, aTarget,
                        bestR < 100 ? "radius within feasible construction scale"
                                : bestR < 500 ? "large-radius habitat — high construction cost"
                                        : "kilometer-scale radius — beyond near-term feasibility");
        return new ResearchResult(
                "Centrifugal (a_target=%.2f g, %d ω-samples): comfort-R=%.1f m at ω=%.2f rad/s. Sample: %s"
                        .formatted(aTarget, samples, bestR, bestOmega, sb.toString().trim()),
                bestR, bestOmega, aTarget, bestR, status, assessment)
                .withHypothesis(("For a target artificial gravity a_target≈%.2f g, comfortable Coriolis "
                        + "conditions (ω ≤ 0.3 rad/s) require a habitat radius R = a/ω² that grows quadratically "
                        + "with decreasing rotation rate.")
                        .formatted(aTarget));
    }

    /** Orbital decay: dh/dt ≈ −2π·ρ_atm(h)·B_inv·a. */
    private static ResearchResult orbitalDecayRate(double altKmSeed, SearchParams sp) {
        // Toy: ρ_atm(h) ≈ 1.225·exp(−h/8) kg/m³ ≪ atmospheric scale height.
        double bestDays = 0;
        double bestH = altKmSeed;
        StringBuilder sb = new StringBuilder();
        int samples = 0;
        for (int i = 0; i < sp.candidateBreadth(); i++) {
            double h = Math.max(200, altKmSeed + i * sp.stepSize() * 10.0);
            double rho = 1.225 * Math.exp(-h / 60.0);  // exaggerated scale height
            // Decay rate (m/s descent): rough heuristic.
            double Binv = 0.001;
            double a = 7.7e6;  // orbital semimajor in m
            double dhdt = 2 * Math.PI * rho * Binv * a;  // m/s vertical
            double daysToDeorbit = (h * 1000.0) / Math.max(1e-6, dhdt) / 86400.0;
            samples++;
            if (daysToDeorbit > bestDays) { bestDays = daysToDeorbit; bestH = h; }
            if (i < 4) sb.append("h=%.0fkm→ρ=%.2eρ_atm→t_deorbit=%.1fd ".formatted(h, rho, daysToDeorbit));
        }
        ClaimStatus status = bestDays > 30 ? ClaimStatus.SUPPORTED
                : bestDays > 5 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = ("Orbital decay: max time-to-reentry %.1f days at h=%.0f km. %s")
                .formatted(bestDays, bestH,
                        bestDays > 30 ? "satellite lifetime > 1 month at the sampled altitudes"
                                : bestDays > 5 ? "weeks-scale lifetime — frequent reboosts needed"
                                        : "rapid decay — orbit too low for sustained mission");
        return new ResearchResult(
                "Orbital decay (%d h-samples starting %.0f km): max t_deorbit=%.1f days at h=%.0f km. Sample: %s"
                        .formatted(samples, altKmSeed, bestDays, bestH, sb.toString().trim()),
                bestDays, bestH, 0.001, bestDays, status, assessment)
                .withHypothesis(("For a satellite swept from altitude h≈%.0f km with ballistic inverse-coefficient "
                        + "B⁻¹≈0.001, the orbital decay rate dh/dt = 2π·ρ_atm(h)·B⁻¹·a falls exponentially "
                        + "with altitude through the atmospheric scale height.")
                        .formatted(altKmSeed));
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
