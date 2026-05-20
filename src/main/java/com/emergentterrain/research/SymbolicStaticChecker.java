package com.emergentterrain.research;

import org.matheclipse.core.interfaces.IAST;
import org.matheclipse.core.interfaces.IExpr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 8 Tier 3 — deterministic safety check for {@code run_symbolic}
 * pseudocode (Symja syntax). Parallel to {@link SandboxStaticChecker} but
 * for Symja's IExpr AST instead of {@link SandboxEngine}'s.
 *
 * <p>Reuses Symja's own parser ({@link SymbolicEngine#parse(String)}) so
 * the parser is the source of truth for what is syntactically valid Symja.
 * The only additional rule enforced here is that every {@code IAST} head in
 * the parsed tree must appear in {@link #ALLOWED_HEADS}. The whitelist is
 * curated for symbolic verification of LLM-produced math claims (calculus,
 * algebra, trig, series) and deliberately excludes:
 *
 * <ul>
 *   <li>I/O — {@code Import, Export, Get, Put, Save, OpenRead, OpenWrite,
 *       OpenAppend, Read, Write, BinaryRead, BinaryWrite, ReadList}.</li>
 *   <li>Network — {@code URLFetch, URLRead, URLDownload, URLExecute, URLSubmit,
 *       SendMail}.</li>
 *   <li>Process — {@code Run, RunProcess, StartProcess, SystemDialogInput}.</li>
 *   <li>State-mutating assignment — {@code Set, SetDelayed, TagSet, UpSet,
 *       UpSetDelayed, Clear, ClearAll, Remove, Unset}.</li>
 *   <li>JVM escape hatches — {@code Java, JavaClass, JavaNew, LoadJavaClass}.</li>
 *   <li>String→symbol bypass of the parser — {@code ToExpression,
 *       MakeExpression, Symbol}.</li>
 *   <li>Concurrency — {@code ParallelTry, Parallelize, ParallelMap,
 *       ParallelSum, ParallelTable}.</li>
 *   <li>Compile / native — {@code Compile, CompilePrint}.</li>
 * </ul>
 *
 * <p>{@link SymbolicEngine}'s {@code Config.FILESYSTEM_ENABLED = false} adds
 * defence in depth — even if an unexpected head slips through, the underlying
 * filesystem-touching primitives are disarmed at the engine level.
 */
public final class SymbolicStaticChecker {

    private SymbolicStaticChecker() {}

    public sealed interface Result {
        record Approved() implements Result {}
        record Rejected(String reason) implements Result {}
    }

    static final Set<String> ALLOWED_HEADS = Set.of(
            // ── Arithmetic ──────────────────────────────────────────────────
            "Plus", "Times", "Power", "Subtract", "Divide", "Minus",
            "Rational", "Sqrt", "Surd", "CubeRoot", "Mod", "Quotient",
            "GCD", "LCM", "Factorial", "Factorial2", "Binomial", "Multinomial",
            // ── Comparison & logic ──────────────────────────────────────────
            "Equal", "Unequal", "Less", "Greater", "LessEqual", "GreaterEqual",
            "And", "Or", "Not", "Xor", "Nand", "Nor", "Implies", "Equivalent",
            "If", "Which", "Piecewise", "Boole",
            // ── Calculus ────────────────────────────────────────────────────
            "Integrate", "NIntegrate", "D", "Derivative",
            "Limit", "Series", "SeriesCoefficient", "Normal", "O",
            "Sum", "NSum", "Product", "NProduct",
            // ── Algebra / polynomials ───────────────────────────────────────
            "Solve", "NSolve", "Reduce", "FindRoot",
            "Simplify", "FullSimplify", "Expand", "ExpandAll", "Factor",
            "Apart", "Together", "Collect", "Cancel", "Distribute",
            "PolynomialGCD", "PolynomialLCM", "PolynomialQuotient",
            "PolynomialRemainder", "Coefficient", "CoefficientList",
            "Exponent", "Variables", "PolynomialDegree",
            // ── Trig / inverse trig ─────────────────────────────────────────
            "Sin", "Cos", "Tan", "Cot", "Sec", "Csc",
            "ArcSin", "ArcCos", "ArcTan", "ArcCot", "ArcSec", "ArcCsc",
            "Sinh", "Cosh", "Tanh", "Coth", "Sech", "Csch",
            "ArcSinh", "ArcCosh", "ArcTanh", "ArcCoth", "ArcSech", "ArcCsch",
            "Sinc",
            // ── Exp / log ───────────────────────────────────────────────────
            "Exp", "Log", "Log2", "Log10", "LogGamma",
            // ── Numerical / rounding ────────────────────────────────────────
            "N", "NumericQ", "IntegerPart", "FractionalPart", "Round",
            "Floor", "Ceiling", "Abs", "Sign", "Re", "Im", "Conjugate",
            "Arg", "Chop", "Rationalize",
            // ── Special functions ───────────────────────────────────────────
            "Gamma", "Beta", "Erf", "Erfc", "Erfi", "InverseErf",
            "BesselJ", "BesselY", "BesselI", "BesselK",
            "LegendreP", "LegendreQ", "ChebyshevT", "ChebyshevU",
            "HermiteH", "LaguerreL", "JacobiP", "GegenbauerC",
            "EllipticE", "EllipticK", "EllipticF", "EllipticPi",
            "Zeta", "PolyLog", "PolyGamma", "HarmonicNumber",
            "FresnelC", "FresnelS",
            // ── Number-theoretic ────────────────────────────────────────────
            "PrimeQ", "Prime", "PrimePi", "NextPrime", "PreviousPrime",
            "FactorInteger", "Divisors", "DivisorSigma", "MoebiusMu",
            "EulerPhi", "JacobiSymbol",
            // ── List manipulation (read-only) ───────────────────────────────
            // Symja parses {a, b, c} as List[a, b, c]; Integrate's bounds
            // {x, 0, Pi} is a List literal, so List MUST be allowed for any
            // calculus operation to round-trip the parse.
            "List", "Range", "Table", "Length", "First", "Last", "Rest",
            "Most", "Take", "Drop", "Part", "Select", "Cases",
            "Total", "Mean", "Variance", "StandardDeviation", "Median",
            "Min", "Max", "Sort", "Reverse", "Tally", "Union", "Intersection",
            "Complement", "Position", "Count", "MemberQ", "FreeQ",
            "Map", "Apply", "MapThread", "Thread", "Fold", "FoldList",
            // ── Constants ───────────────────────────────────────────────────
            "Pi", "E", "EulerGamma", "GoldenRatio", "Catalan",
            "Degree", "Infinity", "ComplexInfinity", "Indeterminate", "Null",
            "True", "False",
            // ── Rules (only the data shape, not state mutation) ─────────────
            // Symja parses 'x -> 0' as Rule[x, 0] and 'x :> 0' as RuleDelayed.
            // Solve / Replace / Limit / Series return AssRule[...] lists. These
            // are data, not state-mutating — Set/SetDelayed are the dangerous
            // pair and remain blacklisted.
            "Rule", "RuleDelayed", "Replace", "ReplaceAll", "ReplaceRepeated",
            // ── Local scope (Phase 21 §21.5) ────────────────────────────────
            // Module / With / Block create LOCAL bindings that don't escape
            // the call. Symja allocates a fresh `$module$NNN` symbol per
            // Module entry, evaluates the body, then discards the scope.
            // Combined with FILESYSTEM_ENABLED=false and the Set/SetDelayed
            // blacklist (the actual state-mutation primitives), local-scope
            // wrappers are pure and safe. Pre-21.5 P-a328-690's round-0
            // wrote `Module[{...}, ...]` which the LLM reflexively emits for
            // any "let me define intermediates" thought; rejecting it forced
            // 1–3 wasted rounds. CompoundExpression (`a; b; c`) is admitted
            // for the same reason — Module bodies use it heavily.
            "Module", "With", "Block", "CompoundExpression"
    );

    public static Result check(String expression) {
        if (expression == null || expression.isBlank()) {
            return new Result.Rejected("empty expression");
        }
        IExpr ast;
        try {
            ast = SymbolicEngine.parse(expression);
        } catch (RuntimeException e) {
            return new Result.Rejected("parse failed: " + e.getMessage());
        }
        List<String> bad = new ArrayList<>();
        collectBadHeads(ast, bad);
        if (!bad.isEmpty()) {
            return new Result.Rejected("non-whitelisted head(s): "
                    + String.join(", ", new LinkedHashSet<>(bad)));
        }
        return new Result.Approved();
    }

    private static void collectBadHeads(IExpr expr, List<String> bad) {
        if (expr == null) return;
        if (expr.isAST()) {
            IAST ast = (IAST) expr;
            String headName = ast.head().toString();
            if (!ALLOWED_HEADS.contains(headName)) bad.add(headName);
            // Recurse into the head (rare — e.g. f[x][y]) and into the args.
            collectBadHeads(ast.head(), bad);
            for (int i = 1; i < ast.size(); i++) {
                collectBadHeads(ast.get(i), bad);
            }
        }
        // Leaf atoms (Integer, Rational, Real, String, Symbol) are always
        // safe — bare symbols are free variables; numeric atoms can't trigger
        // anything. No checking needed.
    }
}
