package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Phase 5 B2 — flag papers whose Findings contain magnitudes outside the
 * physically-reasonable range for their (domain, topic).
 *
 * <p>Phase 7 F1: the bounds table moved into {@link TopicValidatorRegistry}.
 * This class is now a thin static delegate that preserves the pre-F1
 * call-site signature so {@link UniversityService} and the existing test
 * suite keep working unchanged.
 */
public final class MagnitudeRangeValidator {

    private MagnitudeRangeValidator() {}

    /**
     * Returns a list of human-readable violation reasons for downstream
     * telemetry. Empty list = no violation (including the no-bound-registered
     * case, matching pre-F1 behaviour for unbounded topics).
     */
    public static List<String> validate(String domain, String topic, String findings) {
        return validate(TopicValidatorRegistry.defaultRegistry(), domain, topic, findings);
    }

    /**
     * Phase 6 D1.5 (L2) — corpus-aware overload. After the registry pass, if no
     * registry bound applied, consult the literature corpus for a structured
     * (magnitudeMax, magnitudeUnit, magnitudeRegex) entry and apply that as a
     * fallback bound. Lets operators add bounds via YAML without recompiling
     * the registry.
     */
    public static List<String> validate(String domain, String topic, String findings,
                                         LiteratureSearchService literature) {
        List<String> reasons = validate(TopicValidatorRegistry.defaultRegistry(), domain, topic, findings);
        if (!reasons.isEmpty() || literature == null || findings == null
                || domain == null || topic == null) {
            return reasons;
        }
        // Registry produced no violations — only fall back to corpus when the
        // registry has *no* bound for this (domain, topic). Otherwise the
        // registry's silence means "checked and clean", not "no opinion".
        Optional<TopicValidatorRegistry.ValidatorSpec> regSpec =
                TopicValidatorRegistry.defaultRegistry().findFor(domain, topic);
        if (regSpec.isPresent() && regSpec.get().hasMagnitudeBound()) {
            return reasons;
        }
        for (LiteratureReference r : literature.findFor(domain, topic)) {
            if (!r.hasStructuredMagnitudeBound()) continue;
            Pattern regex;
            try {
                regex = Pattern.compile(r.magnitudeRegex());
            } catch (PatternSyntaxException e) {
                continue;
            }
            Matcher m = regex.matcher(findings);
            while (m.find()) {
                if (m.groupCount() < 1) break;
                double value;
                try {
                    value = Double.parseDouble(m.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (value > r.magnitudeMax()) {
                    reasons.add(String.format(
                            "%s (observed %.0f %s, corpus bound %.0f %s): %s.",
                            topic, value, r.magnitudeUnit(),
                            r.magnitudeMax(), r.magnitudeUnit(),
                            r.citation()));
                    return reasons;
                }
            }
        }
        return reasons;
    }

    /**
     * Phase 6 D1.5 — corpus-aware Map overload. Mirrors
     * {@link #validate(String, String, java.util.Map)} but routes through the
     * literature-corpus fallback when the registry has no bound.
     */
    public static List<String> validate(String domain, String topic,
                                         java.util.Map<Integer, String> findingsMap,
                                         LiteratureSearchService literature) {
        return validate(domain, topic, joinBlocks(findingsMap), literature);
    }

    /**
     * Phase 7 F2 — structured-findings overload. Operates on the concatenation of
     * every block's prose — semantically equivalent to {@link #validate(String,
     * String, String)} on the joined String. Null / empty map degrades to a null
     * findings, which the String overload short-circuits.
     */
    public static List<String> validate(String domain, String topic,
                                         java.util.Map<Integer, String> findingsMap) {
        return validate(domain, topic, joinBlocks(findingsMap));
    }

    /** Phase 7 F2 — join helper that mirrors the publish-path join semantics. */
    static String joinBlocks(java.util.Map<Integer, String> findingsMap) {
        if (findingsMap == null || findingsMap.isEmpty()) return null;
        if (findingsMap.size() == 1) {
            return findingsMap.values().iterator().next();
        }
        StringBuilder sb = new StringBuilder();
        new java.util.TreeMap<>(findingsMap).forEach((k, v) -> {
            if (v == null || v.isBlank()) return;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(v);
        });
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Phase 18 §18.1 (P8c) — scan tool-call trace results for magnitude
     * violations the Findings prose may have dropped. Targets P-a328-593's
     * "5790 MPa hidden in call_4 result, not surfaced in prose" pattern.
     *
     * <p>Scans the final {@code result} field of every successful (non-error,
     * non-rejected) tool call against the same (domain, topic) bound table as
     * the prose overload. Intermediate sweep grid values are not extracted —
     * only the terminal scalar / aggregate the tool returned. This keeps
     * legitimate parameter-sweep papers (whose grid values legitimately
     * straddle the ceiling) from false-positive flagging.
     *
     * <p>Returns a list of reasons tagged with {@code "[trace]"} so the
     * UniversityService stamp can distinguish prose violations from trace
     * violations on the call_path.
     */
    public static List<String> validateTrace(
            String domain, String topic,
            List<com.emergentterrain.llm.ToolInvocation> trace) {
        return validateTrace(TopicValidatorRegistry.defaultRegistry(),
                domain, topic, trace, null);
    }

    /** Phase 18 §18.1 (P8c) — corpus-aware overload, same fallback as
     *  {@link #validate(String, String, String, LiteratureSearchService)}. */
    public static List<String> validateTrace(
            String domain, String topic,
            List<com.emergentterrain.llm.ToolInvocation> trace,
            LiteratureSearchService literature) {
        return validateTrace(TopicValidatorRegistry.defaultRegistry(),
                domain, topic, trace, literature);
    }

    /** Phase 18 §18.1 (P8c) — registry-injectable seam for tests. */
    public static List<String> validateTrace(
            TopicValidatorRegistry registry,
            String domain, String topic,
            List<com.emergentterrain.llm.ToolInvocation> trace,
            LiteratureSearchService literature) {
        List<String> reasons = new ArrayList<>();
        if (domain == null || topic == null || trace == null || trace.isEmpty()) {
            return reasons;
        }
        Optional<TopicValidatorRegistry.ValidatorSpec> maybeSpec = registry.findFor(domain, topic);
        Pattern regex = null;
        double upper = 0.0;
        String unit = "";
        String reasonForBound = "";
        boolean fromCorpus = false;
        if (maybeSpec.isPresent() && maybeSpec.get().hasMagnitudeBound()) {
            TopicValidatorRegistry.ValidatorSpec spec = maybeSpec.get();
            regex = spec.magnitudeRegex();
            upper = spec.upperLimit();
            unit = spec.unit();
            reasonForBound = spec.reasonForBound();
        } else if (literature != null) {
            for (LiteratureReference r : literature.findFor(domain, topic)) {
                if (!r.hasStructuredMagnitudeBound()) continue;
                try {
                    regex = Pattern.compile(r.magnitudeRegex());
                } catch (PatternSyntaxException e) {
                    continue;
                }
                upper = r.magnitudeMax();
                unit = r.magnitudeUnit();
                reasonForBound = r.citation();
                fromCorpus = true;
                break;
            }
        }
        if (regex == null) return reasons;

        // The registry/corpus regex is tuned to the prose-validator's
        // `Vf=80%→9620MPa` notation — too tight for trace summaries that
        // intersperse prose (`→ longitudinal σ=5790MPa`). Build a looser
        // unit-only fallback from `unit` so trace summaries also match.
        Pattern unitFallback = null;
        if (unit != null && !unit.isBlank()) {
            try {
                unitFallback = Pattern.compile(
                        "([0-9]+(?:\\.[0-9]+)?)\\s*\\Q" + unit + "\\E\\b");
            } catch (PatternSyntaxException e) {
                unitFallback = null;
            }
        }

        for (com.emergentterrain.llm.ToolInvocation inv : trace) {
            String result = inv.resultJson();
            if (result == null || result.isBlank()) continue;
            // Skip calls the harness reported as failed — the rejection text
            // contains arbitrary tokens that can FP-match a numeric regex.
            if (result.startsWith("REJECTED") || result.startsWith("Tool error")
                    || result.contains("not computable")) continue;
            // Only inspect the final scalar result when the tool used the
            // `result=<scalar>` convention; the sandbox/sweep response format
            // puts the terminal scalar after `result=`, so intermediate sweep
            // grid values that legitimately straddle the bound stay invisible.
            // For tool shapes without that marker (one-line summaries like
            // P-593's `Best: T=... → σ=5790MPa`), scan the full result.
            String scanTarget = result;
            int resultEq = result.indexOf("result=");
            if (resultEq >= 0) {
                int eol = result.indexOf('\n', resultEq);
                scanTarget = eol > 0 ? result.substring(resultEq, eol) : result.substring(resultEq);
            }
            // Try registry regex first; fall back to the unit-only pattern.
            Double hit = firstViolation(regex, scanTarget, upper);
            if (hit == null && unitFallback != null) {
                hit = firstViolation(unitFallback, scanTarget, upper);
            }
            if (hit != null) {
                reasons.add(String.format(
                        "[trace] %s (%s observed %.0f %s in %s result, bound %.0f %s): %s.",
                        topic, fromCorpus ? "corpus" : "registry",
                        hit, unit, inv.callId() != null ? inv.callId() : "call",
                        upper, unit, reasonForBound));
                return reasons;  // one trace violation is enough
            }
        }
        return reasons;
    }

    private static Double firstViolation(Pattern regex, String text, double upper) {
        Matcher m = regex.matcher(text);
        while (m.find()) {
            double value;
            try {
                value = Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (value > upper) return value;
        }
        return null;
    }

    /** Test/DI seam — same behaviour, registry passed in explicitly. */
    public static List<String> validate(TopicValidatorRegistry registry,
                                         String domain, String topic, String findings) {
        List<String> reasons = new ArrayList<>();
        if (domain == null || topic == null || findings == null) return reasons;
        Optional<TopicValidatorRegistry.ValidatorSpec> maybeSpec = registry.findFor(domain, topic);
        if (maybeSpec.isEmpty()) return reasons;
        TopicValidatorRegistry.ValidatorSpec spec = maybeSpec.get();
        if (!spec.hasMagnitudeBound()) return reasons;
        Matcher m = spec.magnitudeRegex().matcher(findings);
        while (m.find()) {
            double value;
            try {
                value = Double.parseDouble(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (value > spec.upperLimit()) {
                reasons.add(String.format(
                        "%s (observed %.0f %s, bound %.0f %s): %s.",
                        topic, value, spec.unit(), spec.upperLimit(), spec.unit(),
                        spec.reasonForBound()));
                break;  // one violation per bound is enough
            }
        }
        return reasons;
    }
}
