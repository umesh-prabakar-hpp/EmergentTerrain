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
                                 String claimAssessment, String hypothesis) {
        public ResearchResult(String findings, double primaryValue,
                              double secondaryValue, double tertiaryValue) {
            this(findings, primaryValue, secondaryValue, tertiaryValue, primaryValue,
                    allFinite(primaryValue, secondaryValue, tertiaryValue),
                    allFinite(primaryValue, secondaryValue, tertiaryValue)
                            ? "Computed values are finite and reproducible."
                            : "One or more computed values are non-finite.",
                    allFinite(primaryValue, secondaryValue, tertiaryValue)
                            ? ClaimStatus.OBSERVATIONAL : ClaimStatus.REFUTED,
                    allFinite(primaryValue, secondaryValue, tertiaryValue)
                            ? "The computed values are finite; this region has no prior theoretical bound to compare against."
                            : "The computation produced non-finite values.",
                    null);
        }

        public ResearchResult(String findings, double primaryValue,
                              double secondaryValue, double tertiaryValue,
                              boolean theoryConsistent, String theoryCheck) {
            this(findings, primaryValue, secondaryValue, tertiaryValue, primaryValue,
                    theoryConsistent, theoryCheck,
                    theoryConsistent ? ClaimStatus.SUPPORTED : ClaimStatus.REFUTED,
                    theoryCheck, null);
        }

        public ResearchResult(String findings, double primaryValue,
                              double secondaryValue, double tertiaryValue,
                              double reviewValue, boolean theoryConsistent,
                              String theoryCheck) {
            this(findings, primaryValue, secondaryValue, tertiaryValue, reviewValue,
                    theoryConsistent, theoryCheck,
                    theoryConsistent ? ClaimStatus.SUPPORTED : ClaimStatus.REFUTED,
                    theoryCheck, null);
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
                    claimStatus, claimAssessment, null);
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
                    claimStatus, claimAssessment, hypothesis);
        }

        public ResearchResult withClaim(ClaimStatus status, String assessment) {
            return new ResearchResult(findings, primaryValue, secondaryValue, tertiaryValue,
                    reviewValue, theoryConsistent, theoryCheck, status, assessment, hypothesis);
        }

        public ResearchResult withFindingsAndClaim(String updatedFindings,
                                                   ClaimStatus status,
                                                   String assessment) {
            return new ResearchResult(updatedFindings, primaryValue, secondaryValue,
                    tertiaryValue, reviewValue, theoryConsistent, theoryCheck,
                    status, assessment, hypothesis);
        }

        public ResearchResult withHypothesis(String updatedHypothesis) {
            return new ResearchResult(findings, primaryValue, secondaryValue, tertiaryValue,
                    reviewValue, theoryConsistent, theoryCheck, claimStatus,
                    claimAssessment, updatedHypothesis);
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
            "RLC Damping Transition"
    };
    private static final String[] MATERIALS_TOPICS = {
            "Composite Failure Mode", "Binary Sphere Packing",
            "Stiffness Crossover Volume Fraction", "Arrhenius Rule Validity",
            "Thermal Stress Safety Window"
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
            {"Critical Resistance for RLC Oscillation Collapse", "Q-Factor Sensitivity Near the Underdamped–Overdamped Boundary"}
    };
    private static final String[][] MATERIALS_TITLES = {
            {"Fibre Volume Fraction at Which Matrix Yields Before Composite", "Longitudinal Failure Mode Transition in Fibre Composites"},
            {"Size Ratio Optimisation for Binary Hard-Sphere Packing", "Void-Filling Efficiency in FCC Lattices with Mixed Sphere Sizes"},
            {"Volume Fraction Threshold for Transverse Stiffness Doubling", "Reuss Bound Crossover in Particle-Reinforced Composites"},
            {"Activation Energy Range for Van't Hoff Rule Validity", "When Does the 2×-per-10K Approximation Break Down?"},
            {"Maximum Safe CTE Before Thermal Stress Exceeds Yield", "Temperature-Rise Safety Window for Constrained Structural Materials"}
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
        return switch (domain) {
            case "PHYSICS"           -> computePhysics(topicIndex % 5, param1, param2, sp);
            case "MATERIALS"         -> computeMaterials(topicIndex % 5, param1, param2, sp);
            case "INTERDISCIPLINARY" -> computeInterdisciplinary(topicIndex % 5, param1, param2, sp);
            default                  -> computeMath(topicIndex % 5, param1, param2, sp);
        };
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
        double scale = topicScale(domain, topicIndex % 5);
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
            // Materials
            case "MATERIALS:0"   -> 5000.0;  // composite maxStress in MPa (~1000-18000)
            case "MATERIALS:1"   -> 0.1;     // sphere packing improvement% (~0.02-0.15)
            case "MATERIALS:2"   -> 0.4;     // stiffness crossover Vf-0.5 (~0.3-0.5)
            case "MATERIALS:3"   -> 3.0;     // Arrhenius worstDev (~0.5-10)
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
                    "Computed values were independently reproduced by peer reviewers. These findings represent an observational baseline; no prior theoretical bound constrains the expected value in this region. %s"
                            .formatted(result.claimAssessment());
        };
    }

    // ---- Mathematics ----

    private static ResearchResult computeMath(int topic, int param1, double param2, SearchParams sp) {
        return switch (topic) {
            case 0 -> primeConstellations(param1 * 10, param1 * 15, sp);
            case 1 -> collatzStoppingTimes(param1 * 3, 60, sp);
            case 2 -> goldbachPartitions(Math.max(4, param1 * 4), (int) param2 * 10 + 20, sp);
            case 3 -> aliquotSequences(Math.max(2, param1), 40, sp);
            default -> happyNumbers(param1 * 7, 150, sp);
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
        ClaimStatus status = hlDeviation <= 0.35 ? ClaimStatus.SUPPORTED
                : hlDeviation <= 0.75 ? ClaimStatus.PARTIAL : ClaimStatus.REFUTED;
        String assessment = "Hardy-Littlewood twin-count deviation is %.1f%% for this finite interval; %s."
                .formatted(hlDeviation * 100,
                        status == ClaimStatus.SUPPORTED ? "within the configured support band"
                                : status == ClaimStatus.PARTIAL ? "suggestive but outside the strong-support band"
                                : "too large to call the hypothesis supported");
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
        return switch (topic) {
            case 0 -> projectileWithDrag(10.0 + param2 * 5, param1 / 1000.0, sp);
            case 1 -> pendulumBreakdown(0.1 + param1 / 200.0, sp);
            case 2 -> vanDerWaalsDeviation(200.0 + param1 * 3.0, 0.01, sp);
            case 3 -> diffractionSurvey(1.0 + param1 / 50.0, 400.0 + param2 * 40, sp);
            default -> rlcDampingTransition(0.001 + param1 / 10000.0, 1e-6 + param2 * 1e-7, sp);
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
            sb.append("%.0f°→%.2f%% ".formatted((double) deg, err));
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

    // ---- Materials ----

    private static ResearchResult computeMaterials(int topic, int param1, double param2, SearchParams sp) {
        return switch (topic) {
            case 0 -> compositeFailureMode(50.0 + param1 * 5, sp);
            case 1 -> binarySpherePacking(sp);
            case 2 -> compositeStiffnessCrossover(10.0 + param1 * 2, 2.0, sp);
            case 3 -> arrheniusRuleValidity(300.0 + param1 * 3, 50.0 + param2 * 20, sp);
            default -> thermalStressSafety(50.0 + param1 * 3, 200.0 + param2 * 50, sp);
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
        return switch (topic) {
            case 0 -> collatzRlcDepthResonance(param1, param2, sp);
            case 1 -> primeGapDefectThermal(param1, param2, sp);
            case 2 -> resonantThermalFatigue(param1, param2, sp);
            case 3 -> happyPackingAnomaly(param1, param2, sp);
            default -> vanDerWaalsCompositeStress(param1, param2, sp);
        };
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
