package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

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
