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
