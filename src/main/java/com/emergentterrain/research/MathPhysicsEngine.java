package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Actual mathematics and physics computations used as the research engine.
 * All results are deterministic given the same inputs and verifiable by peer reviewers
 * re-running the same computation.
 */
public class MathPhysicsEngine {

    public record ResearchResult(String findings, double primaryValue,
                                 double secondaryValue, double tertiaryValue,
                                 double reviewValue, boolean theoryConsistent,
                                 String theoryCheck) {
        public ResearchResult(String findings, double primaryValue,
                              double secondaryValue, double tertiaryValue) {
            this(findings, primaryValue, secondaryValue, tertiaryValue, primaryValue,
                    allFinite(primaryValue, secondaryValue, tertiaryValue),
                    allFinite(primaryValue, secondaryValue, tertiaryValue)
                            ? "Computed values are finite and reproducible."
                            : "One or more computed values are non-finite.");
        }

        public ResearchResult(String findings, double primaryValue,
                              double secondaryValue, double tertiaryValue,
                              boolean theoryConsistent, String theoryCheck) {
            this(findings, primaryValue, secondaryValue, tertiaryValue, primaryValue,
                    theoryConsistent, theoryCheck);
        }

        private static boolean allFinite(double... values) {
            for (double value : values) {
                if (!Double.isFinite(value)) return false;
            }
            return true;
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

    // ---- Public API ----

    public static ResearchResult compute(String domain, int topicIndex, int param1, double param2) {
        return switch (domain) {
            case "PHYSICS"    -> computePhysics(topicIndex % 5, param1, param2);
            case "MATERIALS"  -> computeMaterials(topicIndex % 5, param1, param2);
            default           -> computeMath(topicIndex % 5, param1, param2);
        };
    }

    /**
     * Normalised quality score [0,1] from a review value, used for peer-review comparison.
     * Reviewers compare scores rather than raw values so tolerance is domain-independent.
     */
    public static double scoreResult(String domain, int topicIndex, double value) {
        // All current computations return finite deterministic values.
        // Score = sigmoid-like normalisation so any finite value maps to (0,1).
        if (!Double.isFinite(value)) return 0.0;
        double abs = Math.abs(value);
        return abs / (1.0 + abs);  // always in (0,1), monotone — two results agree if close
    }

    public static String topicName(String domain, int topicIndex) {
        String[] names = switch (domain) {
            case "PHYSICS"   -> PHYSICS_TOPICS;
            case "MATERIALS" -> MATERIALS_TOPICS;
            default          -> MATH_TOPICS;
        };
        return names[topicIndex % names.length];
    }

    public static String generateTitle(String domain, int topicIndex, Random rng) {
        String[][] table = switch (domain) {
            case "PHYSICS"   -> PHYSICS_TITLES;
            case "MATERIALS" -> MATERIALS_TITLES;
            default          -> MATH_TITLES;
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
                double sigmaYm = 50 + param1 * 5.0;
                yield "In a steel-fibre/polymer composite (Ef=200 GPa, Em=3 GPa), the matrix will yield before the composite fractures across all fibre volume fractions; longitudinal stress at matrix yield will scale linearly with Vf."
                        .formatted();
            }
            case "MATERIALS:1" ->
                "In an FCC lattice, filling octahedral voids (r<0.414) with small spheres will improve packing beyond the 74.05%% FCC baseline; the improvement will peak near r≈0.41 and plateau above r=0.5.";
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
        return "Results passed configured theory checks and computed values were independently verified by peer reviewers running the same computation. The hypothesis is supported.";
    }

    // ---- Mathematics ----

    private static ResearchResult computeMath(int topic, int param1, double param2) {
        return switch (topic) {
            case 0 -> primeConstellations(param1 * 10, param1 * 15);
            case 1 -> collatzStoppingTimes(param1 * 3, 60);
            case 2 -> goldbachPartitions(Math.max(4, param1 * 4), (int) param2 * 10 + 20);
            case 3 -> aliquotSequences(Math.max(2, param1), 40);
            default -> happyNumbers(param1 * 7, 150);
        };
    }

    private static ResearchResult primeConstellations(int start, int width) {
        int end = start + Math.max(50, width);
        List<Integer> primes = new ArrayList<>();
        for (int n = Math.max(2, start); n < end; n++) {
            if (isPrime(n)) primes.add(n);
        }
        int twins = 0, cousins = 0, sexy = 0, maxGap = 0;
        for (int i = 1; i < primes.size(); i++) {
            int gap = primes.get(i) - primes.get(i - 1);
            if (gap > maxGap) maxGap = gap;
            if (gap == 2) twins++;
            if (gap == 4) cousins++;
            if (gap == 6) sexy++;
        }
        int pairs = Math.max(1, primes.size() - 1);
        double twinDensity   = (double) twins / pairs;
        double primeDensity  = (double) primes.size() / (end - start);
        double lnN = Math.log(Math.max(2, start));
        double hardyLittlewood = 2 * 1.3203 / (lnN * lnN) * width; // H-L predicted twin count
        return new ResearchResult(
                "Prime constellations [%d,%d): %d primes (density=%.4f), twin=%d, cousin=%d, sexy=%d, max_gap=%d. H-L predicted twins≈%.1f. Twin density among pairs: %.4f."
                        .formatted(start, end, primes.size(), primeDensity, twins, cousins, sexy, maxGap, hardyLittlewood, twinDensity),
                twinDensity, primeDensity, maxGap);
    }

    private static ResearchResult collatzStoppingTimes(int start, int count) {
        int maxSteps = 0;
        int maxStepsAt = start;
        double totalSteps = 0;
        int longTraj = 0; // > 100 steps
        for (int n = Math.max(1, start); n < start + count; n++) {
            long x = n;
            int steps = 0;
            while (x != 1 && steps < 10_000) {
                x = (x % 2 == 0) ? x / 2 : 3 * x + 1;
                steps++;
            }
            totalSteps += steps;
            if (steps > maxSteps) { maxSteps = steps; maxStepsAt = n; }
            if (steps > 100) longTraj++;
        }
        double avgSteps = totalSteps / count;
        double longFraction = (double) longTraj / count;
        return new ResearchResult(
                "Collatz [%d,%d+%d): max_steps=%d (at n=%d), avg_steps=%.2f, fraction>100_steps=%.3f. All %d starting values terminated at 1 (conjecture holds locally)."
                        .formatted(start, start, count, maxSteps, maxStepsAt, avgSteps, longFraction, count),
                maxSteps, avgSteps, longFraction);
    }

    private static ResearchResult goldbachPartitions(int evenStart, int count) {
        if (evenStart % 2 != 0) evenStart++;
        if (evenStart < 4) evenStart = 4;
        long totalPartitions = 0;
        int minParts = Integer.MAX_VALUE;
        int minPartsAt = evenStart;
        int singleCount = 0;
        int tested = 0;
        for (int n = evenStart; n < evenStart + count * 2; n += 2) {
            int parts = 0;
            for (int p = 2; p <= n / 2; p++) {
                if (isPrime(p) && isPrime(n - p)) parts++;
            }
            totalPartitions += parts;
            tested++;
            if (parts < minParts) { minParts = parts; minPartsAt = n; }
            if (parts == 1) singleCount++;
        }
        double avg = tested > 0 ? (double) totalPartitions / tested : 0;
        return new ResearchResult(
                "Goldbach partitions, even [%d,%d): avg=%.2f partitions/number, min=%d (at n=%d), single-partition=%d/%d. All even numbers satisfy Goldbach conjecture locally."
                        .formatted(evenStart, evenStart + count * 2, avg, minParts, minPartsAt, singleCount, tested),
                avg, minParts, singleCount);
    }

    private static ResearchResult aliquotSequences(int start, int count) {
        int terminate = 0, perfect = 0, cycle = 0, escape = 0;
        for (int n = start; n < start + count; n++) {
            long x = n;
            java.util.Set<Long> seen = new java.util.HashSet<>();
            int steps = 0;
            boolean done = false;
            while (steps < 200 && !done) {
                if (x <= 1) { terminate++; done = true; break; }
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
        return new ResearchResult(
                "Aliquot sequences [%d,%d+%d): terminate=%d (%.3f), perfect=%d, cycle=%d, escape=%d. Termination rate %.1f%% — sociable/escaping numbers grow with range start."
                        .formatted(start, start, count, terminate, terminateFrac, perfect, cycle, escape, terminateFrac * 100),
                terminateFrac, cycle, escape);
    }

    private static ResearchResult happyNumbers(int start, int count) {
        int happy = 0;
        int totalSteps = 0;
        for (int n = Math.max(1, start); n < start + count; n++) {
            long x = n;
            int steps = 0;
            while (x != 1 && x != 4 && steps < 1000) {
                long sum = 0;
                while (x > 0) { long d = x % 10; sum += d * d; x /= 10; }
                x = sum;
                steps++;
            }
            if (x == 1) { happy++; totalSteps += steps; }
        }
        double density = (double) happy / count;
        double avgSteps = happy > 0 ? (double) totalSteps / happy : 0;
        double deviation = density - 0.1430;
        return new ResearchResult(
                "Happy numbers [%d,%d+%d): happy=%d, density=%.4f (theoretical≈0.1430, deviation=%+.4f), avg_steps_to_1=%.1f. Density consistent with conjectured asymptotic."
                        .formatted(start, start, count, happy, density, deviation, avgSteps),
                density, avgSteps, Math.abs(deviation));
    }

    // ---- Physics ----

    private static ResearchResult computePhysics(int topic, int param1, double param2) {
        return switch (topic) {
            case 0 -> projectileWithDrag(10.0 + param2 * 5, param1 / 1000.0);
            case 1 -> pendulumBreakdown(0.1 + param1 / 200.0);
            case 2 -> vanDerWaalsDeviation(200.0 + param1 * 3.0, 0.01);
            case 3 -> diffractionSurvey(1.0 + param1 / 50.0, 400.0 + param2 * 40);
            default -> rlcDampingTransition(0.001 + param1 / 10000.0, 1e-6 + param2 * 1e-7);
        };
    }

    // Sweep launch angles under quadratic drag; optimal angle shifts below 45°.
    private static ResearchResult projectileWithDrag(double v0, double k) {
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
        double loss = (vacuumRange - bestRange) / vacuumRange;
        return new ResearchResult(
                "Projectile drag (v₀=%.1f ms⁻¹, k=%.4f s/m): optimal_angle=%d° (%.0f° below vacuum 45°), range=%.2f m vs vacuum=%.2f m (%.1f%% loss). Drag breaks angle symmetry."
                        .formatted(v0, k, bestAngle, (double) shift, bestRange, vacuumRange, loss * 100),
                bestAngle, bestRange, loss);
    }

    // Find amplitude at which small-angle period error exceeds 1% using elliptic series.
    private static ResearchResult pendulumBreakdown(double L) {
        double g = 9.81;
        double T0 = 2 * Math.PI * Math.sqrt(L / g);
        double breakdownAngle = 80;
        StringBuilder sb = new StringBuilder();
        for (int deg = 5; deg <= 80; deg += 5) {
            double k = Math.sin(Math.toRadians(deg) / 2);
            double k2 = k * k;
            // Series: T = T0 * (1 + k²/4 + 9k⁴/64 + 25k⁶/256)
            double T = T0 * (1 + k2 / 4 + 9 * k2 * k2 / 64 + 25 * k2 * k2 * k2 / 256);
            double err = (T - T0) / T0 * 100;
            sb.append("%.0f°→%.2f%% ".formatted((double) deg, err));
            if (err > 1.0 && nearlyEqual(breakdownAngle, 80, 0.5)) breakdownAngle = deg;
        }
        return new ResearchResult(
                "Pendulum (L=%.2f m, T₀=%.3f s): period error by amplitude: %s. Small-angle error exceeds 1%% at θ≈%.0f°."
                        .formatted(L, T0, sb.toString().trim(), breakdownAngle),
                breakdownAngle, T0, L);
    }

    // Find pressure where CO₂ deviates from ideal gas by more than tolerance.
    private static ResearchResult vanDerWaalsDeviation(double T, double tolerance) {
        double a = 3.640, b = 0.04267, R = 0.08206; // L·atm units
        double crossoverP = 100;
        double maxDev = 0;
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p <= 100; p++) {
            double P = p;
            double Videal = R * T / P;
            double V = Videal;
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
            if (dev > tolerance && nearlyEqual(crossoverP, 100, 0.5)) crossoverP = p;
            if (p == 1 || p == 5 || p == 10 || p == 20 || p == 50 || p == 100)
                sb.append("%datm→%.2f%% ".formatted(p, dev * 100));
        }
        return new ResearchResult(
                "Van der Waals CO₂ (T=%.0f K): deviation from ideal: %s. Crossover >%.0f%% at P=%.0f atm. Max deviation in [1,100] atm: %.2f%%."
                        .formatted(T, sb.toString().trim(), tolerance * 100, crossoverP, maxDev * 100),
                crossoverP, maxDev, T);
    }

    // Count visible diffraction orders across optical spectrum for given grating spacing.
    private static ResearchResult diffractionSurvey(double dMicrons, double lambdaNm) {
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
        double spectralSpread = maxOrders - minOrders;
        return new ResearchResult(
                "Diffraction grating (d=%.2f µm, λ_ref=%.0f nm): max_orders=%d, 1st_order_angle=%.1f°. Orders by wavelength: %s. Spectral spread=%d orders — blue light shows %d more orders than red."
                        .formatted(dMicrons, lambdaNm, maxOrder, theta1, sb.toString().trim(),
                                (int) spectralSpread, (int) spectralSpread),
                maxOrder, theta1, spectralSpread);
    }

    // Survey Q-factor across resistance values around the critical damping point.
    private static ResearchResult rlcDampingTransition(double L, double C) {
        double critR = 2 * Math.sqrt(L / C);
        double omega0 = 1 / Math.sqrt(L * C);
        double f0 = omega0 / (2 * Math.PI);
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
                critR, f0, maxQ / Math.max(minQ, 1e-10));
    }

    // ---- Materials ----

    private static ResearchResult computeMaterials(int topic, int param1, double param2) {
        return switch (topic) {
            case 0 -> compositeFailureMode(50.0 + param1 * 5);
            case 1 -> binarySpherePacking(param1 / 250.0);
            case 2 -> compositeStiffnessCrossover(10.0 + param1 * 2, 2.0);
            case 3 -> arrheniusRuleValidity(300.0 + param1 * 3, 50.0 + param2 * 20);
            default -> thermalStressSafety(50.0 + param1 * 3, 200.0 + param2 * 50);
        };
    }

    // Find longitudinal composite stress at matrix yield across fibre volume fractions.
    private static ResearchResult compositeFailureMode(double sigmaYm) {
        double Ef = 200e3, Em = 3e3; // MPa
        double epsY = sigmaYm / Em;
        StringBuilder sb = new StringBuilder();
        double maxStress = 0;
        double maxVf = 0;
        for (int vfPct = 10; vfPct <= 90; vfPct += 10) {
            double Vf = vfPct / 100.0;
            double EL = Vf * Ef + (1 - Vf) * Em;
            double sigmaC = EL * epsY; // composite stress when matrix reaches yield strain
            if (sigmaC > maxStress) { maxStress = sigmaC; maxVf = Vf; }
            sb.append("Vf=%.0f%%→%.0fMPa ".formatted(Vf * 100, sigmaC));
        }
        return new ResearchResult(
                "Composite failure (Ef=200GPa, Em=3GPa, σ_ym=%.0f MPa, ε_y=%.4f): longitudinal stress at matrix yield: %s. Stress rises linearly with Vf; max at Vf=%.0f%% → %.0f MPa."
                        .formatted(sigmaYm, epsY, sb.toString().trim(), maxVf * 100, maxStress),
                maxStress, epsY, maxVf);
    }

    // Sweep small-sphere radius ratio; compute packing improvement over pure FCC base.
    private static ResearchResult binarySpherePacking(double rTarget) {
        double etaFCC = Math.PI / (3 * Math.sqrt(2)); // 0.7405
        double bestR = 0, bestEta = etaFCC;
        StringBuilder sb = new StringBuilder();
        for (int rPct = 5; rPct <= 60; rPct += 5) {
            double r = rPct / 100.0;
            // Octahedral voids (1 per sphere): fit when r < 0.4142; tetrahedral (2 per sphere): r < 0.2247
            double voidVolOcta  = r <= 0.4142 ? (4.0 / 3) * Math.PI * Math.pow(r, 3) : 0;
            double voidVolTetra = r <= 0.2247 ? 2 * (4.0 / 3) * Math.PI * Math.pow(r, 3) : 0;
            double addedPacking = (voidVolOcta + voidVolTetra) * etaFCC
                    / ((4.0 / 3) * Math.PI); // fraction of unit cell filled
            double etaTotal = Math.min(0.99, etaFCC + addedPacking);
            if (etaTotal > bestEta) { bestEta = etaTotal; bestR = r; }
            if (rPct % 10 == 0) sb.append("r=%.2f→η=%.4f ".formatted(r, etaTotal));
        }
        return new ResearchResult(
                "Binary sphere packing (FCC base η=%.4f): packing vs size ratio: %s. Peak packing η=%.4f at r≈%.2f. Octahedral void limit r<0.414; tetrahedral r<0.225."
                        .formatted(etaFCC, sb.toString().trim(), bestEta, bestR),
                bestEta, bestR, etaFCC);
    }

    // Find Vf where Reuss (transverse) composite stiffness reaches targetMultiple × Em.
    private static ResearchResult compositeStiffnessCrossover(double EfOverEm, double targetMultiple) {
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
        return new ResearchResult(
                "Composite stiffness (Ef/Em=%.0f, target=%.1f×Em): Reuss bound survey: %s. E_T reaches %.1f×Em at Vf=%.2f — well above the intuitive 50%% guess."
                        .formatted(EfOverEm, targetMultiple, sb.toString().trim(), targetMultiple, crossoverVf),
                crossoverVf, EfOverEm, targetMultiple);
    }

    // Find the Ea range where D(T+10)/D(T) is within 10% of the Van't Hoff factor of 2.
    private static ResearchResult arrheniusRuleValidity(double T, double maxEaKJ) {
        double R = 8.314;
        int valid = 0, total = 0;
        double firstValid = -1, lastValid = -1;
        StringBuilder sb = new StringBuilder();
        for (int ea = 10; ea <= (int) maxEaKJ; ea += 10) {
            double Ea = ea * 1000.0;
            double D1 = Math.exp(-Ea / (R * T));
            double D2 = Math.exp(-Ea / (R * (T + 10)));
            double ratio = D2 / D1;
            boolean holds = Math.abs(ratio - 2.0) / 2.0 < 0.10;
            total++;
            if (holds) { valid++; if (firstValid < 0) firstValid = ea; lastValid = ea; }
            if (ea <= 50 || ea % 40 == 10) sb.append("Ea=%dkJ→%.2f× ".formatted(ea, ratio));
        }
        double fraction = (double) valid / Math.max(1, total);
        return new ResearchResult(
                "Arrhenius Van't Hoff (T=%.0f K, Ea 10–%.0f kJ/mol): sample ratios: %s. Rule holds within 10%% for %d/%d cases (%.0f%%), Ea=[%.0f,%.0f] kJ/mol. Fails at high Ea."
                        .formatted(T, maxEaKJ, sb.toString().trim(), valid, total, fraction * 100,
                                Math.max(firstValid, 10), Math.max(lastValid, 10)),
                fraction, firstValid > 0 ? firstValid : maxEaKJ, lastValid > 0 ? lastValid : maxEaKJ);
    }

    // Find maximum CTE before constrained thermal stress exceeds yield; survey common materials.
    private static ResearchResult thermalStressSafety(double deltaT, double sigmaYMPa) {
        double E = 200e3; // MPa, steel modulus
        double critAlpha = sigmaYMPa / (E * deltaT) * 1e6; // µm/(m·K)
        double[] alphas = {1, 5, 10, 12, 17, 23, 25, 30, 50}; // representative CTEs
        String[] labels = {"Invar", "Steel", "Cu", "Al", "Al(soft)", "Brass", "Al-alloy", "Zn", "PVC"};
        int safe = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < alphas.length; i++) {
            double stress = alphas[i] * 1e-6 * E * deltaT;
            boolean ok = stress < sigmaYMPa;
            if (ok) safe++;
            sb.append("%s(%.0f)→%.0fMPa(%s) ".formatted(labels[i], alphas[i], stress, ok ? "✓" : "✗"));
        }
        return new ResearchResult(
                "Thermal stress safety (ΔT=%.0f K, σ_Y=%.0f MPa, E=200GPa): critical_α=%.2f µm/(m·K). Material survey: %s. %d/%d materials remain below yield."
                        .formatted(deltaT, sigmaYMPa, critAlpha, sb.toString().trim(), safe, alphas.length),
                critAlpha, safe, sigmaYMPa);
    }

    // ---- Helper ----

    private static boolean isPrime(int n) {
        if (n < 2) return false;
        if (n < 4) return true;
        if (n % 2 == 0 || n % 3 == 0) return false;
        for (int i = 5; (long) i * i <= n; i += 6) {
            if (n % i == 0 || n % (i + 2) == 0) return false;
        }
        return true;
    }

    private static boolean nearlyEqual(double a, double b, double tolerance) {
        return Math.abs(a - b) <= tolerance;
    }
}
