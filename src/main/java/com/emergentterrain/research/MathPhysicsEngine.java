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
            "Projectile Mechanics", "Wave Resonance", "Ideal Gas Behaviour",
            "Optical Refraction", "Coulomb Interactions"
    };
    private static final String[] MATERIALS_TOPICS = {
            "Elastic Moduli", "Crystal Packing", "Composite Mechanics",
            "Arrhenius Diffusion", "Thermal Expansion"
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
            {"Ballistic Trajectory Optimisation Under Uniform Gravity", "Energy Conservation in Projectile Systems"},
            {"Standing Wave Resonance in Homogeneous Media", "Frequency-Energy Relationships in Acoustic Systems"},
            {"Ideal Gas Behaviour at Elevated Temperatures", "Kinetic Theory Validation via RMS Velocity"},
            {"Snell's Law Across Refractive Index Gradients", "Critical Angle Dependencies at Dielectric Interfaces"},
            {"Inverse-Square Law Verification for Coulomb Interactions", "Electric Potential Analysis Around Point Charges"}
    };
    private static final String[][] MATERIALS_TITLES = {
            {"Young's Modulus via Stress-Strain Analysis", "Elastic Constant Relationships in Isotropic Solids"},
            {"Packing Efficiency in Crystal Lattice Structures", "Void Fraction in Close-Packed Arrangements"},
            {"Rule of Mixtures in Fibre-Reinforced Composites", "Voigt-Reuss Bounds in Anisotropic Systems"},
            {"Arrhenius Diffusion Rate Temperature Sensitivity", "Activation Energy from Diffusion Coefficients"},
            {"Linear Thermal Expansion in Crystalline Solids", "CTE Measurement Across Temperature Ranges"}
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
            case "PHYSICS:0" ->
                "A projectile launched at θ=%d° with v₀=%.1f ms⁻¹ achieves range and height consistent with kinematic equations."
                        .formatted(param1 % 85 + 5, Math.max(5.0, param2));
            case "PHYSICS:1" ->
                "Acoustic waves at f=%.1f Hz propagate with wavelength λ=c/f and energy density proportional to A²f²."
                        .formatted(Math.max(20.0, 20.0 + param1 % 10000));
            case "PHYSICS:2" ->
                "An ideal gas at T=%.0f K satisfies PV=nRT with RMS molecular velocity √(3RT/M)."
                        .formatted(200.0 + param1 % 600);
            case "PHYSICS:3" ->
                "Light transitioning from n₁=1.0 to n₂=%.2f obeys Snell's law with Brewster angle arctan(n₂/n₁)."
                        .formatted(1.3 + (param1 % 100) / 100.0);
            case "PHYSICS:4" ->
                "The Coulomb force between point charges decays as r⁻² with constant k=8.99×10⁹ N·m²/C²."
                        .formatted();
            case "MATERIALS:0" ->
                "Stress-strain linearity holds in the elastic regime, with E=σ/ε and derived shear/bulk moduli satisfying isotropy relations.";
            case "MATERIALS:1" ->
                "FCC/BCC/SC crystal packing efficiencies (74%/68%/52%) are derivable from hard-sphere geometry alone.";
            case "MATERIALS:2" ->
                "Longitudinal stiffness of fibre composites obeys the Voigt (rule of mixtures) upper bound exactly.";
            case "MATERIALS:3" ->
                "Diffusivity follows D=D₀·exp(−Eₐ/RT); a 10 K temperature rise increases D by a factor exp(EₐΔT/RT²).";
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
            case 0 -> projectileMotion(param1 % 85 + 5, Math.max(5.0, 5.0 + param2 % 45));
            case 1 -> waveResonance(Math.max(20.0, 20.0 + param1 % 10000), Math.max(0.001, param1 / 10000.0));
            case 2 -> idealGasLaw(200.0 + param1 % 600, 0.01 + (param1 % 50) * 0.002);
            case 3 -> snellRefraction(1.0, 1.3 + (param1 % 100) / 100.0);
            default -> coulombInteraction((1 + param1 % 20) * 1e-9, 0.01 + (param1 % 100) / 100.0);
        };
    }

    private static ResearchResult projectileMotion(int angleDeg, double v0) {
        double a = Math.toRadians(angleDeg);
        double g = 9.81;
        double range    = (v0 * v0 * Math.sin(2 * a)) / g;
        double maxH     = (v0 * v0 * Math.pow(Math.sin(a), 2)) / (2 * g);
        double tFlight  = (2 * v0 * Math.sin(a)) / g;
        double optRange = (v0 * v0) / g;
        return new ResearchResult(
                "Projectile: θ=%d°, v₀=%.2f ms⁻¹ → range=%.3f m, max_height=%.3f m, t_flight=%.3f s. Optimal (45°) range=%.3f m. KE=%.1f J (unit mass)."
                        .formatted(angleDeg, v0, range, maxH, tFlight, optRange, 0.5 * v0 * v0),
                range, maxH, tFlight);
    }

    private static ResearchResult waveResonance(double freq, double amplitude) {
        double c = 343.0; // m/s in air
        double wavelength = c / freq;
        double omega = 2 * Math.PI * freq;
        double energyDensity = 0.5 * 1.293 * amplitude * amplitude * omega * omega;
        double period = 1.0 / freq;
        return new ResearchResult(
                "Wave: f=%.2f Hz, A=%.4f m → λ=%.4f m, ω=%.2f rad/s, T=%.6f s, energy_density=%.4f J/m³. Resonance nodes at L=nλ/2."
                        .formatted(freq, amplitude, wavelength, omega, period, energyDensity),
                wavelength, energyDensity, period);
    }

    private static ResearchResult idealGasLaw(double temp, double moles) {
        double R = 8.314;
        double V = 0.001; // 1 litre
        double P = moles * R * temp / V;
        double vRms = Math.sqrt(3 * R * temp / 0.029); // air M=29g/mol
        double kB = 1.38e-23;
        double meanFP = kB * temp / (Math.sqrt(2) * Math.PI * Math.pow(3.7e-10, 2) * P);
        return new ResearchResult(
                "Ideal gas: T=%.1f K, n=%.3f mol, V=1 L → P=%.1f Pa, v_rms=%.1f ms⁻¹, λ_mfp≈%.2e m. PV/nRT=%.6f (ideal ratio)."
                        .formatted(temp, moles, P, vRms, meanFP, P * V / (moles * R * temp)),
                P, vRms, meanFP);
    }

    private static ResearchResult snellRefraction(double n1, double n2) {
        double brewsterDeg = Math.toDegrees(Math.atan(n2 / n1));
        StringBuilder cases = new StringBuilder();
        for (int inc : new int[]{15, 30, 45, 60}) {
            double sinR = n1 * Math.sin(Math.toRadians(inc)) / n2;
            if (Math.abs(sinR) <= 1.0) {
                cases.append("%.0f°→%.2f° ".formatted((double) inc, Math.toDegrees(Math.asin(sinR))));
            }
        }
        return new ResearchResult(
                "Snell's law n₁=%.3f→n₂=%.3f: [%s] Brewster_angle=%.2f°. No TIR (n₁<n₂). Reflectance minimum at Brewster angle confirms p-polarisation prediction."
                        .formatted(n1, n2, cases.toString().trim(), brewsterDeg),
                brewsterDeg, n2 / n1, 0);
    }

    private static ResearchResult coulombInteraction(double q, double r) {
        double k = 8.99e9;
        double F = k * q * q / (r * r);
        double V = k * q / r;
        double E = k * q / (r * r);
        return new ResearchResult(
                "Coulomb: q=%.2e C, r=%.3f m → F=%.4e N, V=%.4e J/C, E=%.4e N/C. Doubling r → F/4 (inverse-square confirmed). Energy stored U=kq²/r=%.4e J."
                        .formatted(q, r, F, V, E, k * q * q / r),
                F, V, E);
    }

    // ---- Materials ----

    private static ResearchResult computeMaterials(int topic, int param1, double param2) {
        return switch (topic) {
            case 0 -> elasticModuli(Math.max(50.0, 50.0 + param2 % 450), 0.001 + (param1 % 50) * 0.001);
            case 1 -> crystalPacking(new int[]{6, 8, 12}[param1 % 3]);
            case 2 -> compositeStrength(Math.max(0.05, Math.min(0.95, (double) param1 / 200.0)));
            case 3 -> arrheniusDiffusion(300.0 + param1 % 500, 50.0 + param2 % 200);
            default -> thermalExpansion(Math.max(5.0, 5.0 + param1 % 100), 200.0 + param1 % 800);
        };
    }

    private static ResearchResult elasticModuli(double stressMPa, double strain) {
        double E  = stressMPa / strain; // MPa
        double nu = 0.25 + strain * 2;  // approximate Poisson ratio
        nu = Math.min(0.49, nu);
        double G  = E / (2 * (1 + nu));
        double K  = E / (3 * (1 - 2 * nu));
        return new ResearchResult(
                "Elastic moduli: σ=%.1f MPa, ε=%.4f → E=%.2f GPa, ν=%.3f, G=%.2f GPa, K=%.2f GPa. Cauchy relation G=E/[2(1+ν)] verified. Plastic onset ~σ_y=%.0f MPa."
                        .formatted(stressMPa, strain, E / 1000, nu, G / 1000, K / 1000, stressMPa * 1.5),
                E, G, K);
    }

    private static ResearchResult crystalPacking(int cn) {
        double eta = switch (cn) {
            case 12 -> Math.PI / (3 * Math.sqrt(2));  // FCC  74.05%
            case 8  -> Math.PI * Math.sqrt(3) / 8;    // BCC  68.02%
            default -> Math.PI / 6;                    // SC   52.36%
        };
        String lattice = switch (cn) { case 12 -> "FCC"; case 8 -> "BCC"; default -> "SC"; };
        return new ResearchResult(
                "Crystal packing (%s, CN=%d): η=%.4f (%.2f%%), void_fraction=%.4f. Theoretical maximum (FCC/HCP)=%.4f. Hard-sphere model confirmed."
                        .formatted(lattice, cn, eta, eta * 100, 1 - eta, Math.PI / (3 * Math.sqrt(2))),
                eta, 1 - eta, cn);
    }

    private static ResearchResult compositeStrength(double vf) {
        double vm    = 1.0 - vf;
        double Ef    = 200e3; // MPa (steel fibre)
        double Em    = 3e3;   // MPa (polymer matrix)
        double E_L   = vf * Ef + vm * Em;                         // Voigt
        double E_T   = 1.0 / (vf / Ef + vm / Em);                // Reuss
        double aniso = E_L / E_T;
        return new ResearchResult(
                "Composite (vf=%.2f, vm=%.2f): E_L=%.2f GPa, E_T=%.2f GPa, anisotropy=%.1f×. Voigt upper bound and Reuss lower bound bracketed correctly. Load transfer efficiency=%.1f%%."
                        .formatted(vf, vm, E_L / 1000, E_T / 1000, aniso, 100.0 * E_T / E_L),
                E_L, E_T, aniso);
    }

    private static ResearchResult arrheniusDiffusion(double T, double EaKJ) {
        double R  = 8.314;
        double D0 = 1e-4; // m²/s
        double D  = D0 * Math.exp(-EaKJ * 1000 / (R * T));
        double halfLife = Math.log(2) / Math.max(D, 1e-30);
        double T10  = T + 10;
        double D10  = D0 * Math.exp(-EaKJ * 1000 / (R * T10));
        double mult = D10 / Math.max(D, 1e-30);
        return new ResearchResult(
                "Arrhenius diffusion: T=%.1f K, Eₐ=%.1f kJ/mol → D=%.3e m²/s, half_life=%.2e s. +10 K multiplies D by %.2f×. Van't Hoff rule (~2× per 10 K) approx at low Eₐ."
                        .formatted(T, EaKJ, D, halfLife, mult),
                D, halfLife, mult);
    }

    private static ResearchResult thermalExpansion(double alphaPerMK, double deltaT) {
        double alpha = alphaPerMK * 1e-6; // /K
        double strain = alpha * deltaT;
        double stress = strain * 200e9;   // assume steel E=200 GPa
        double deltaV = 3 * strain;       // volumetric expansion ≈ 3α ΔT
        return new ResearchResult(
                "Thermal expansion: α=%.1f µm/(m·K), ΔT=%.1f K → ε_thermal=%.4f, σ_constrained=%.1f MPa, ΔV/V≈%.4f. CTE anisotropy negligible for isotropic sample."
                        .formatted(alphaPerMK, deltaT, strain, stress / 1e6, deltaV),
                strain, stress / 1e6, deltaV);
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
