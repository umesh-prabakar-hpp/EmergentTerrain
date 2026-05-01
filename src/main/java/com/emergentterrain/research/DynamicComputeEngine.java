package com.emergentterrain.research;

import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;

/**
 * Interprets LLM-generated DynamicProblem specs and produces a ResearchResult.
 * Uses the primitive helpers exposed by MathPhysicsEngine (isPrime, isHappy,
 * collatzSteps, sigmaSum) so the computation is deterministic and peer-reviewable.
 */
public class DynamicComputeEngine {

    public static final int MAX_RANGE = 500;

    public static MathPhysicsEngine.ResearchResult compute(DynamicProblem problem) {
        int start = Math.max(1, problem.rangeStart());
        int end   = Math.min(problem.rangeEnd(), start + MAX_RANGE);
        int n     = end - start;
        if (n <= 0) return emptyResult(problem);

        IntPredicate pred = parsePredicate(problem.predicate());
        if (pred == null) return emptyResult(problem);

        String metric = problem.metric() != null ? problem.metric() : "fraction_satisfying";

        if (metric.equals("fraction_satisfying")) {
            return computeFraction(problem, start, end, n, pred);
        }
        if (metric.startsWith("mean_of:") || metric.startsWith("max_of:")) {
            return computeValueMetric(problem, start, end, pred, metric);
        }
        return emptyResult(problem);
    }

    private static MathPhysicsEngine.ResearchResult computeFraction(
            DynamicProblem problem, int start, int end, int n, IntPredicate pred) {
        int count = 0;
        for (int i = start; i < end; i++) if (pred.test(i)) count++;
        double fraction = (double) count / n;
        double deviation = Math.abs(fraction - problem.baseline());

        double se = Math.sqrt(Math.max(0, fraction * (1 - fraction)) / n);
        double ci = 1.96 * se;
        int dp = (se > 1e-9) ? Math.max(0, -(int) Math.floor(Math.log10(se))) : 4;
        String fmt = "%." + dp + "f";
        String propStr = (fmt + " ± " + fmt + " (95%% CI, n=%d)").formatted(fraction, ci, n);

        MathPhysicsEngine.ClaimStatus status =
                deviation <= Math.max(0.01, problem.baseline() * 0.15) ? MathPhysicsEngine.ClaimStatus.SUPPORTED
                : deviation <= Math.max(0.03, problem.baseline() * 0.40) ? MathPhysicsEngine.ClaimStatus.PARTIAL
                : MathPhysicsEngine.ClaimStatus.REFUTED;

        String findings = "Dynamic [%d,%d) \"%s\": fraction satisfying predicate = %s (baseline %.3f, deviation %+.3f)."
                .formatted(start, end, problem.description(), propStr, problem.baseline(), fraction - problem.baseline());
        String assessment = "Fraction %.3f vs predicted %.3f — deviation %.3f."
                .formatted(fraction, problem.baseline(), deviation);

        return new MathPhysicsEngine.ResearchResult(findings, deviation, fraction, n)
                .withClaim(status, assessment)
                .withHypothesis(problem.hypothesis() != null ? problem.hypothesis() : findings);
    }

    private static MathPhysicsEngine.ResearchResult computeValueMetric(
            DynamicProblem problem, int start, int end, IntPredicate pred, String metric) {
        String fnName = metric.substring(metric.indexOf(':') + 1);
        IntToLongFunction valFn = parseValueFn(fnName);
        if (valFn == null) return emptyResult(problem);

        boolean isMax = metric.startsWith("max_of:");
        double wMean = 0, wM2 = 0;
        long maxVal = Long.MIN_VALUE;
        int wCount = 0;
        for (int i = start; i < end; i++) {
            if (!pred.test(i)) continue;
            long val = valFn.applyAsLong(i);
            wCount++;
            double delta = val - wMean;
            wMean += delta / wCount;
            wM2 += delta * (val - wMean);
            if (val > maxVal) maxVal = val;
        }
        if (wCount == 0) return emptyResult(problem);

        double se = (wCount > 1) ? Math.sqrt(wM2 / (wCount - 1) / wCount) : 0;
        int dp = (se > 1e-9) ? Math.max(0, -(int) Math.floor(Math.log10(se))) : 2;
        String fmt = "%." + dp + "f";
        String meanStr = (fmt + " ± " + fmt + " (SE, n=%d)").formatted(wMean, se, wCount);

        double primary = isMax ? maxVal : Math.abs(wMean - problem.baseline());
        double deviation = Math.abs(wMean - problem.baseline());
        MathPhysicsEngine.ClaimStatus status =
                deviation <= problem.baseline() * 0.1 ? MathPhysicsEngine.ClaimStatus.SUPPORTED
                : deviation <= problem.baseline() * 0.3 ? MathPhysicsEngine.ClaimStatus.PARTIAL
                : MathPhysicsEngine.ClaimStatus.REFUTED;

        String label = isMax ? "Max" : "Mean";
        long displayMax = maxVal == Long.MIN_VALUE ? 0 : maxVal;
        String findings = "Dynamic [%d,%d) \"%s\": %s of %s over %d matching = %s (max=%d, baseline %.3f, deviation %+.3f)."
                .formatted(start, end, problem.description(), label, fnName, wCount,
                           meanStr, displayMax, problem.baseline(), wMean - problem.baseline());
        String assessment = "%s %.2f vs predicted %.3f.".formatted(label, wMean, problem.baseline());

        return new MathPhysicsEngine.ResearchResult(findings, primary, wMean, se)
                .withClaim(status, assessment)
                .withHypothesis(problem.hypothesis() != null ? problem.hypothesis() : findings);
    }

    // --- Predicate / value-function parsing ---

    static IntPredicate parsePredicate(String spec) {
        if (spec == null || spec.isBlank()) return null;
        if (spec.contains(" AND ")) {
            String[] parts = spec.split(" AND ");
            IntPredicate p = parseSinglePredicate(parts[0].trim());
            for (int i = 1; i < parts.length; i++) {
                IntPredicate q = parseSinglePredicate(parts[i].trim());
                if (p == null || q == null) return null;
                p = p.and(q);
            }
            return p;
        }
        if (spec.contains(" OR ")) {
            String[] parts = spec.split(" OR ");
            IntPredicate p = parseSinglePredicate(parts[0].trim());
            for (int i = 1; i < parts.length; i++) {
                IntPredicate q = parseSinglePredicate(parts[i].trim());
                if (p == null || q == null) return null;
                p = p.or(q);
            }
            return p;
        }
        return parseSinglePredicate(spec.trim());
    }

    private static IntPredicate parseSinglePredicate(String name) {
        return switch (name) {
            case "isPrime"           -> MathPhysicsEngine::isPrime;
            case "isHappy"           -> MathPhysicsEngine::isHappy;
            case "collatzTerminates" -> n -> MathPhysicsEngine.collatzSteps(n) < 10_000;
            case "isAbundant"        -> n -> MathPhysicsEngine.sigmaSum(n) > n;
            case "isPerfect"         -> n -> MathPhysicsEngine.sigmaSum(n) == n;
            default -> null;
        };
    }

    private static IntToLongFunction parseValueFn(String name) {
        return switch (name) {
            case "collatzSteps" -> n -> MathPhysicsEngine.collatzSteps(n);
            case "sigmaSum"     -> MathPhysicsEngine::sigmaSum;
            default -> null;
        };
    }

    private static MathPhysicsEngine.ResearchResult emptyResult(DynamicProblem problem) {
        return new MathPhysicsEngine.ResearchResult(
                "Dynamic problem \"%s\": no results (invalid spec or empty range)."
                        .formatted(problem != null ? problem.description() : "?"),
                0, 0, 0);
    }
}
