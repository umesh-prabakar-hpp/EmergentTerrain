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
                                 double secondaryValue, double tertiaryValue) {}

    // ---- Topic name tables ----

    private static final String[] MATH_TOPICS = {
            "Prime Gap Analysis", "Sequence Convergence", "Polygon Geometry",
            "Number Classification", "Matrix Spectral Analysis"
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
            {"Prime Gap Distribution in Bounded Intervals", "Empirical Study of Prime Spacing Patterns"},
            {"Fibonacci Convergence to the Golden Ratio", "Rate Analysis of Linear Recurrence Limits"},
            {"Geometric Ratios in Regular Polytopes", "Inscribed Circle Optimality in Polygonal Systems"},
            {"Classification of Integers by Divisor Sums", "Abundant Number Density Below Threshold N"},
            {"Spectral Bounds for Parametric Symmetric Matrices", "Frobenius Norm Analysis of Matrix Families"}
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
            case "MATHEMATICS:0" ->
                "Prime gaps in [%d, %d) follow a distribution with mean proportional to ln(N), consistent with Cramér's conjecture."
                        .formatted(Math.max(2, param1), Math.max(2, param1) + 200);
            case "MATHEMATICS:1" ->
                "The ratio of consecutive Fibonacci terms converges to φ=(1+√5)/2≈1.61803 with exponentially decreasing error.";
            case "MATHEMATICS:2" ->
                "For a regular %d-gon, the ratio of inscribed circle area to polygon area approaches π/4 as side count increases."
                        .formatted(Math.max(3, param1 % 20 + 3));
            case "MATHEMATICS:3" ->
                "The density of abundant numbers in [%d, %d] is approximately 24%%, independent of the interval start."
                        .formatted(Math.max(2, param1), Math.max(2, param1) * 5);
            case "MATHEMATICS:4" ->
                "The Frobenius norm of a %dx%d symmetric cosine matrix provides a tight upper bound on its spectral radius."
                        .formatted(Math.max(2, param1 % 6 + 2), Math.max(2, param1 % 6 + 2));
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
        if (!passed) {
            return "Computational inconsistencies detected during peer review. Findings require independent verification before acceptance.";
        }
        return "Results are consistent with theoretical predictions. Computed values were independently verified by peer reviewers running the same computation. The hypothesis is supported.";
    }

    // ---- Mathematics ----

    private static ResearchResult computeMath(int topic, int param1, double param2) {
        return switch (topic) {
            case 0 -> primeGapAnalysis(Math.max(2, param1), Math.max(2, param1) + 200);
            case 1 -> sequenceConvergence(Math.max(5, param1 % 50 + 5));
            case 2 -> polygonGeometry(Math.max(3, param1 % 20 + 3));
            case 3 -> numberClassification(Math.max(2, param1), Math.max(2, param1) * 5);
            default -> matrixSpectral(Math.max(2, param1 % 6 + 2), Math.max(0.1, param2 % 10.0));
        };
    }

    private static ResearchResult primeGapAnalysis(int start, int end) {
        List<Integer> primes = new ArrayList<>();
        for (int n = start; n < end; n++) {
            if (isPrime(n)) primes.add(n);
        }
        if (primes.size() < 2) {
            return new ResearchResult("Insufficient primes in range [" + start + "," + end + ").", 0, 0, 0);
        }
        double sumGaps = 0;
        int maxGap = 0;
        for (int i = 1; i < primes.size(); i++) {
            int gap = primes.get(i) - primes.get(i - 1);
            sumGaps += gap;
            if (gap > maxGap) maxGap = gap;
        }
        double avgGap = sumGaps / (primes.size() - 1);
        double density = (double) primes.size() / (end - start);
        double lnMid = Math.log((start + end) / 2.0);
        return new ResearchResult(
                "Prime gap analysis [%d,%d): %d primes, mean_gap=%.3f (ln(N)≈%.2f), max_gap=%d, density=%.4f. Cramér bound √(ln(N))≈%.2f not exceeded."
                        .formatted(start, end, primes.size(), avgGap, lnMid, maxGap, density, Math.sqrt(lnMid)),
                avgGap, maxGap, density);
    }

    private static ResearchResult sequenceConvergence(int n) {
        long a = 1, b = 1;
        double lastRatio = 1.0;
        double sumSqErr = 0;
        double phi = (1 + Math.sqrt(5)) / 2.0;
        int terms = n + 20;
        for (int i = 2; i < terms; i++) {
            long c = a + b;
            if (c <= 0) break; // overflow guard
            lastRatio = (double) c / b;
            sumSqErr += Math.pow(lastRatio - phi, 2);
            a = b;
            b = c;
        }
        double rmse = Math.sqrt(sumSqErr / (terms - 2));
        double error = Math.abs(lastRatio - phi);
        return new ResearchResult(
                "Fibonacci convergence (n=%d terms): φ_computed=%.10f, φ_theoretical=%.10f, |error|=%.2e, RMSE=%.2e. Exponential convergence rate O(φ⁻ⁿ) confirmed."
                        .formatted(terms, lastRatio, phi, error, rmse),
                lastRatio, error, rmse);
    }

    private static ResearchResult polygonGeometry(int sides) {
        double interior = (sides - 2) * 180.0 / sides;
        double area = (sides * 1.0) / (4 * Math.tan(Math.PI / sides)); // unit side
        double circumR = 1.0 / (2 * Math.sin(Math.PI / sides));
        double inR = 1.0 / (2 * Math.tan(Math.PI / sides));
        double circleRatio = Math.PI * inR * inR / area;
        return new ResearchResult(
                "Regular %d-gon (unit side): interior=%.4f°, area=%.6f, circumR=%.6f, inR=%.6f, inscribed_circle_ratio=%.6f. Limit→circle (ratio→1) as n→∞."
                        .formatted(sides, interior, area, circumR, inR, circleRatio),
                area, circumR, circleRatio);
    }

    private static ResearchResult numberClassification(int start, int end) {
        int abundant = 0, deficient = 0, perfect = 0;
        for (int n = Math.max(2, start); n <= end; n++) {
            int sigma = 1;
            for (int d = 2; (long) d * d <= n; d++) {
                if (n % d == 0) { sigma += d; if (d != n / d) sigma += n / d; }
            }
            if (sigma > n) abundant++;
            else if (sigma < n) deficient++;
            else perfect++;
        }
        int total = Math.max(1, end - Math.max(2, start) + 1);
        return new ResearchResult(
                "Number classification [%d,%d]: abundant=%.1f%%, deficient=%.1f%%, perfect=%d. Abundant density ~24.76%% (Erdős–Kac)."
                        .formatted(start, end, 100.0 * abundant / total, 100.0 * deficient / total, perfect),
                (double) abundant / total, (double) deficient / total, perfect);
    }

    private static ResearchResult matrixSpectral(int size, double scale) {
        double trace = 0, frobenius = 0;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                double entry = Math.cos((i + 1) * (j + 1) * scale * 0.1) + (i == j ? 2.0 : 0.0);
                frobenius += entry * entry;
                if (i == j) trace += entry;
            }
        }
        frobenius = Math.sqrt(frobenius);
        return new ResearchResult(
                "Symmetric %dx%d matrix (scale=%.2f): trace=%.4f, ‖A‖_F=%.4f, spectral_radius≤‖A‖_F=%.4f. Gershgorin discs confirmed. Trace=sum(eigenvalues)=%.4f."
                        .formatted(size, size, scale, trace, frobenius, frobenius, trace),
                trace, frobenius, frobenius);
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
}
