package com.emergentterrain.research;

import com.emergentterrain.service.LlmRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 32 — well-posedness gate (paper-pipeline-quality-brief Change 1). A single cheap LLM check on a
 * candidate topic/conjecture <i>before</i> a paper run is spent on it, catching the framing failures the
 * downstream verification machinery cannot — e.g. a quantity that is representation-dependent (happy
 * numbers are base-10 specific), has no limiting value (a "density" of something that oscillates), or is a
 * tooling artifact (a confidence interval on an exhaustive census).
 *
 * <p><b>Caveat/reframe-first, not reject.</b> Per this project's <i>inform-don't-suppress</i> design and
 * the no-reseed-skip rule, the gate does NOT hard-drop a topic. A non-{@code admit} verdict yields a
 * caveat (via {@link #caveatFor}) that the caller injects into the writer seed so the paper opens by
 * stating the issue, rather than silently presenting an artifact as a result. The miner's churn is
 * untouched; this only annotates what gets written.
 *
 * <p><b>Domain-neutral.</b> The prompt names base/units/window/encoding as <i>examples</i>; the gate runs
 * on every domain (math/physics/materials), so the logic carries no domain-specific hardcoding.
 *
 * <p>Best-effort: disabled by flag, a null router, or any parse/LLM failure all return {@link Verdict#ADMIT}
 * — the gate can only add a caveat, never block a paper on its own malfunction.
 */
@Component
public class WellPosednessGate {

    /** The gate's decision. {@code issue}/{@code verdict} use the prompt's controlled vocabularies. */
    public record Verdict(boolean wellDefined, String issue, String explanation,
                          String verdict, String caveat, String reframedQuestion) {
        public static final Verdict ADMIT = new Verdict(true, "none", "", "admit", "", "");
    }

    /** Issue code for a quantity that depends on an arbitrary choice (number base, units). */
    public static final String ISSUE_REPRESENTATION_DEPENDENT = "representation_dependent";

    /**
     * 2026-06-15 — true iff the verdict's issue is {@code representation_dependent} (the base-10
     * happy-number / unit-dependent class). The caller may PRUNE such a topic (skip + log) rather than
     * caveat it, under its own flag — a deliberate, flag-gated exception to caveat-first for the one
     * class that is a non-research-grade artifact (a base-10 density has no representation-independent
     * limit). Pure; null-safe.
     */
    public static boolean isRepresentationDependent(Verdict v) {
        return v != null && ISSUE_REPRESENTATION_DEPENDENT.equals(v.issue());
    }

    /**
     * Base-dependent predicates whose density has no representation-independent meaning. {@code isHappy}
     * (sum-of-squared-decimal-digits iteration) is base-10; every other predicate in the math
     * vocabulary (isPrime/isAbundant/isPerfect/collatzTerminates) is base-independent. Lowercased for
     * substring matching against a composite predicate like {@code "isPrime AND isHappy"}.
     */
    public static final java.util.Set<String> BASE_DEPENDENT_PREDICATES = java.util.Set.of("ishappy");

    /**
     * 2026-06-15 (review-2026-06-15f Rec 2) — DETERMINISTIC base-dependent-density detector. The
     * LLM well-posedness verdict is non-deterministic (the same isPrime∧isHappy density topic was
     * caveated on one proposal and rejected on another in the same run, so the verdict-keyed prune
     * leaked and the topic published). This deterministic predicate-name check does not depend on the
     * LLM: true iff the predicate references a base-dependent predicate ({@link #BASE_DEPENDENT_PREDICATES})
     * AND the metric is a density / fraction. A non-density use of a base-dependent predicate (e.g. mean
     * Collatz depth of happy primes) is NOT flagged — only its representation-dependent DENSITY. Null-safe.
     */
    public static boolean isBaseDependentDensityTopic(String predicate, String metric) {
        if (predicate == null || metric == null) {
            return false;
        }
        String p = predicate.toLowerCase(java.util.Locale.ROOT);
        boolean baseDependent = BASE_DEPENDENT_PREDICATES.stream().anyMatch(p::contains);
        String m = metric.toLowerCase(java.util.Locale.ROOT);
        boolean density = m.contains("fraction") || m.contains("density");
        return baseDependent && density;
    }

    /**
     * Provably-disjoint predicate pairs (σ(p) = p+1 &lt; 2p bars every prime from abundance and
     * perfection). Over such a pair, a conjunction density is vacuously 0 — a theorem, not a
     * measurement — and a disjunction density is EXACTLY the sum of the component densities, so
     * neither composite carries content beyond its parts. Lowercased tokens.
     */
    public static final java.util.Set<java.util.Set<String>> DISJOINT_PREDICATE_PAIRS =
            java.util.Set.of(
                    java.util.Set.of("isprime", "isabundant"),
                    java.util.Set.of("isprime", "isperfect"));

    /**
     * 2026-07-04 (run-19 rec 2; the family's 15th paper across 11 batches) — DETERMINISTIC
     * disjoint-composite-density detector: true iff the metric is a density/fraction AND the
     * predicate is an AND/OR composite over a provably-disjoint pair
     * ({@link #DISJOINT_PREDICATE_PAIRS}). The AND density is a definitional impossibility
     * (papers keep re-proving σ(p) &lt; 2p from scratch); the OR density is inclusion–exclusion
     * with zero overlap — a census of two independently-understood sets. Component predicates
     * used ALONE (or with a non-density metric) are untouched. Null-safe.
     */
    public static boolean isDisjointCompositeDensityTopic(String predicate, String metric) {
        if (predicate == null || metric == null) {
            return false;
        }
        String p = predicate.toLowerCase(java.util.Locale.ROOT);
        if (!p.contains(" and ") && !p.contains(" or ")) {
            return false;
        }
        String m = metric.toLowerCase(java.util.Locale.ROOT);
        if (!m.contains("fraction") && !m.contains("density")) {
            return false;
        }
        for (java.util.Set<String> pair : DISJOINT_PREDICATE_PAIRS) {
            if (pair.stream().allMatch(p::contains)) {
                return true;
            }
        }
        return false;
    }

    static final String GATE_PROMPT = """
            You are a well-posedness gate for an automated research pipeline. You will be given a candidate
            conjecture or measured quantity. Decide whether it is worth admitting as a research question.
            Reply with ONLY this JSON:

            {
              "well_defined": true|false,
              "issue": "none | representation_dependent | no_limiting_value | tautological | tooling_artifact | settled_known_result | recreational",
              "explanation": "<=40 words, plain",
              "verdict": "admit | admit_with_caveat | reframe | reject",
              "caveat": "<=30 words; required if verdict is admit_with_caveat; else empty",
              "reframed_question": "<the question an expert would actually ask; required if verdict is reframe; else empty"
            }

            Rules:
            - representation_dependent: the quantity depends on an arbitrary choice (number base, units,
              window, encoding). Example: 'happy numbers' are base-10 specific.
            - no_limiting_value: the quantity is treated as if it has an asymptotic density/limit when it
              does not (it oscillates or diverges).
            - tooling_artifact: an artifact of how a tool reports (e.g. a confidence interval on an
              exhaustive census, which has no sampling uncertainty).
            - settled_known_result: the answer is already established in the literature.
            - recreational: well-defined but not a research-grade question; label honestly.
            - Be decisive. "admit" is the verdict only when none of the issues apply.""";

    @Value("${app.research.wellposedness-gate.enabled:false}")
    private boolean enabled;

    private final LlmRouter router;

    public WellPosednessGate(LlmRouter router) {
        this.router = router;
    }

    /**
     * Assess a candidate topic (a plain-language description of the quantity/conjecture under study).
     * Returns {@link Verdict#ADMIT} when disabled, without a router, or on any failure — never throws.
     */
    public Verdict assess(String topicDescription) {
        if (!enabled || router == null || topicDescription == null || topicDescription.isBlank()) {
            return Verdict.ADMIT;
        }
        try {
            // callForRole tags the role context itself and falls back to the fast model for an
            // unregistered role, so no role config / no extra wrapping is needed here.
            String raw = router.callForRole("wellposedness-gate",
                    GATE_PROMPT + "\n\nCANDIDATE:\n" + topicDescription.strip());
            Verdict v = parse(raw);
            return v != null ? v : Verdict.ADMIT;
        } catch (Exception e) {
            return Verdict.ADMIT;   // best-effort context; never block a paper on the gate's failure
        }
    }

    // ── pure parsing + caveat construction (unit-tested without Spring/LLM) ────────────────────────

    private static final Pattern P_WELL = Pattern.compile("\"well_defined\"\\s*:\\s*(true|false)");
    private static final Pattern P_ISSUE = strField("issue");
    private static final Pattern P_EXPL = strField("explanation");
    private static final Pattern P_VERDICT = strField("verdict");
    private static final Pattern P_CAVEAT = strField("caveat");
    private static final Pattern P_REFRAME = strField("reframed_question");

    private static Pattern strField(String key) {
        return Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    }

    /** Lenient parse of the gate JSON out of an LLM response (which may wrap it in prose). null if no
     *  verdict field is found. */
    public static Verdict parse(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher vm = P_VERDICT.matcher(raw);
        if (!vm.find()) {
            return null;   // no recognizable verdict → caller falls back to ADMIT
        }
        String verdict = vm.group(1).trim();
        Matcher wm = P_WELL.matcher(raw);
        boolean wellDefined = !wm.find() || Boolean.parseBoolean(wm.group(1));
        return new Verdict(wellDefined,
                grp(P_ISSUE, raw, "none"),
                grp(P_EXPL, raw, ""),
                verdict,
                grp(P_CAVEAT, raw, ""),
                grp(P_REFRAME, raw, ""));
    }

    private static String grp(Pattern p, String raw, String dflt) {
        Matcher m = p.matcher(raw);
        return m.find() ? m.group(1).trim() : dflt;
    }

    /**
     * The caveat/reframe steer to inject into the writer seed for a non-{@code admit} verdict (empty for
     * admit). Caveat-first: even a {@code reject} verdict becomes a strong caveat rather than a silent
     * drop, so the paper at least opens by naming the ill-posedness instead of presenting an artifact as
     * a result.
     */
    public static String caveatFor(Verdict v) {
        if (v == null || v.verdict() == null || "admit".equals(v.verdict())) {
            return "";
        }
        String issue = (v.issue() == null || v.issue().isBlank()) ? "ill-posed" : v.issue();
        if ("reframe".equals(v.verdict())
                && v.reframedQuestion() != null && !v.reframedQuestion().isBlank()) {
            return "WELL-POSEDNESS NOTE — this question as posed is " + issue + " ("
                    + nonBlank(v.explanation(), "see below")
                    + "). State that in your first paragraph; the better-posed question an expert would "
                    + "ask is: " + v.reframedQuestion().strip();
        }
        String body = nonBlank(v.caveat(), nonBlank(v.explanation(), "the quantity is not well-posed"));
        return "WELL-POSEDNESS NOTE — this question is " + issue + ": " + body
                + ". State this caveat explicitly in your first paragraph; do not present an artifact of "
                + "an arbitrary choice (base, units, window, encoding) or a non-existent limit as a "
                + "fundamental result.";
    }

    private static String nonBlank(String s, String dflt) {
        return (s == null || s.isBlank()) ? dflt : s.strip();
    }
}
