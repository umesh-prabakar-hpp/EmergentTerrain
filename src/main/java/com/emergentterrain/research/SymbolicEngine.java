package com.emergentterrain.research;

import org.matheclipse.core.basic.Config;
import org.matheclipse.core.eval.ExprEvaluator;
import org.matheclipse.core.form.tex.TeXFormFactory;
import org.matheclipse.core.interfaces.IExpr;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Phase 8 Tier 3 — symbolic-math entry point. Wraps Symja's
 * {@link ExprEvaluator} behind a deterministic safety boundary parallel to
 * {@link SandboxEngine} for run_sandboxed_code.
 *
 * <p>The engine itself does no syntax filtering — head-level rejection is the
 * job of {@link SymbolicStaticChecker}, which runs before any LLM input
 * reaches {@code evaluate(...)}. The engine's job is purely
 * (a) bound execution time, (b) bound iteration count, (c) capture the
 * Symja result and turn it into a {@link SymbolicResult} the LLM tool layer
 * can serialise.
 *
 * <h2>Safety boundary</h2>
 * <ul>
 *   <li>{@link Config#FILESYSTEM_ENABLED} is forced {@code false} at class
 *       load so any {@code Import[]}, {@code Export[]}, {@code Get[]},
 *       {@code Put[]}, {@code OpenRead[]}, {@code OpenWrite[]} call from
 *       Symja's evaluator fails before touching the filesystem — defence in
 *       depth on top of {@link SymbolicStaticChecker}.</li>
 *   <li>Each call runs on a single-thread {@link ExecutorService} with a
 *       wall-clock timeout. {@code Future.cancel(true)} interrupts a runaway
 *       evaluation (e.g. {@code Sum[1, {k, 1, Infinity}]}).</li>
 *   <li>{@code EvalEngine.setIterationLimit(...)} caps the symbolic rewrite
 *       depth, catching divergent rewrites before the wall-clock timer fires.</li>
 * </ul>
 */
public final class SymbolicEngine {

    public static final long DEFAULT_TIMEOUT_MS    = 3_000;
    public static final int  DEFAULT_ITERATION_CAP = 100_000;

    static {
        // Global engine-wide kill-switch for file/system primitives. Affects
        // every ExprEvaluator instantiated in this JVM — intentional.
        Config.FILESYSTEM_ENABLED = false;
        Config.SERVER_MODE        = true;
    }

    private SymbolicEngine() {}

    /**
     * Symbolic evaluation result. {@code output} is Symja's full-form string,
     * {@code tex} is the LaTeX form, {@code numericApprox} is non-null only
     * when the result is a finite number.
     */
    public record SymbolicResult(String input,
                                 String output,
                                 String tex,
                                 Double numericApprox,
                                 String summary) {}

    public static SymbolicResult evaluate(String expression) {
        return evaluate(expression, DEFAULT_TIMEOUT_MS);
    }

    public static SymbolicResult evaluate(String expression, long timeoutMs) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("empty expression");
        }
        try (ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "symja-eval");
            t.setDaemon(true);
            return t;
        })) {
            Future<SymbolicResult> future = executor.submit(() -> {
                ExprEvaluator eval = new ExprEvaluator(true, (short) 0);
                eval.getEvalEngine().setIterationLimit(DEFAULT_ITERATION_CAP);
                IExpr result = eval.evaluate(expression);
                return toResult(expression, result);
            });
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                throw new RuntimeException(
                        "Symbolic evaluation timed out after " + timeoutMs + " ms");
            } catch (Exception ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                throw new RuntimeException(
                        "Symbolic evaluation failed: " + cause.getMessage(), cause);
            }
        }
    }

    /**
     * Parse without evaluation. Used by {@link SymbolicStaticChecker} so head
     * inspection happens on a syntactically valid expression and Symja's own
     * parser is the source of truth for what's a legal expression.
     */
    public static IExpr parse(String expression) {
        ExprEvaluator eval = new ExprEvaluator(true, (short) 0);
        return eval.parse(expression);
    }

    private static SymbolicResult toResult(String input, IExpr result) {
        String output = result.toString();
        String tex    = toTex(result);
        Double numeric = tryNumericApprox(result);
        return new SymbolicResult(input, output, tex, numeric, summarize(output, numeric));
    }

    /**
     * Attempt to coerce the symbolic result to a {@code double}. Returns
     * {@code null} when the expression has no meaningful numeric value (it's
     * a symbolic form like {@code 2*x + 3}, a list, a rule, etc.).
     *
     * <p>Symja's {@code IExpr.isNumber()} is too narrow — it returns false for
     * exact symbolic constants like {@code Pi/2} or {@code Sqrt[2]} which DO
     * have a numeric value the LLM cares about. We therefore call
     * {@code evalf()} optimistically and only keep the result if it's finite.
     */
    private static Double tryNumericApprox(IExpr result) {
        if (result == null || result.isList() || result.isRule()) return null;
        try {
            double d = result.evalf();
            if (Double.isFinite(d)) return d;
        } catch (Exception ignore) { /* not coercible to a finite double */ }
        return null;
    }

    private static String toTex(IExpr expr) {
        try {
            StringBuilder sb = new StringBuilder();
            new TeXFormFactory().convert(sb, expr);
            return sb.toString();
        } catch (Exception e) {
            return ""; // TeX is a nice-to-have; never block the result on it
        }
    }

    private static String summarize(String output, Double numeric) {
        if (numeric != null) {
            return "output=%s  numeric=%.6g".formatted(output, numeric);
        }
        return "output=" + output;
    }
}
