package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rejects a narrow but serious contradiction: prose saying that a study definition or locked
 * instrument is unavailable even though the selected trace or pre-registration contains it.
 * This is deliberately an availability check, not a general natural-language fact checker.
 */
public final class TraceDefinitionAvailabilityValidator {
    private static final Pattern UNAVAILABLE_INSTRUMENT = Pattern.compile(
            "(?is)\\b(?:definition|formula|normaliz(?:ation|er)|log(?:arithm)?\\s+base|"
                    + "window(?:\\s+(?:construction|definition|size|allocation))?|"
                    + "(?:pre-?registered|registered)\\s+(?:scan|window|ladder|holdout)|"
                    + "(?:scale\\s+)?ladder)\\b[^.]{0,100}\\b(?:unavailable|absent|missing|"
                    + "not\\s+(?:available|recorded|specified|defined|given|provided|known|stated|present))\\b");
    private static final Pattern NO_INSTRUMENT = Pattern.compile(
            "(?is)\\b(?:no|without)\\s+(?:available\\s+)?(?:definition|formula|normaliz(?:ation|er)|"
                    + "log(?:arithm)?\\s+base|window(?:\\s+(?:construction|definition|size|allocation))?|"
                    + "(?:pre-?registered|registered)\\s+(?:scan|window|ladder|holdout)|"
                    + "(?:scale\\s+)?ladder)\\b");

    private TraceDefinitionAvailabilityValidator() {}

    public static List<String> validate(String prose, PreRegistration registration,
                                        List<ToolInvocation> trace) {
        if (prose == null || prose.isBlank()) return List.of();
        String normalized = prose.toLowerCase(Locale.ROOT);
        if (!UNAVAILABLE_INSTRUMENT.matcher(normalized).find() && !NO_INSTRUMENT.matcher(normalized).find()) {
            return List.of();
        }
        if (!hasRecordedInstrument(registration, trace)) return List.of();
        return List.of("Prose says a definition or registered instrument is unavailable, but the selected "
                + "trace or locked pre-registration records it; state the recorded definition in Methods "
                + "or remove the availability claim.");
    }

    private static boolean hasRecordedInstrument(PreRegistration registration, List<ToolInvocation> trace) {
        if (registration != null && registration.mappingNotes() != null && !registration.mappingNotes().isBlank()) {
            return true;
        }
        if (trace == null) return false;
        for (ToolInvocation call : trace) {
            if (call == null) continue;
            String args = call.argsJson() == null ? "" : call.argsJson();
            String result = call.resultJson() == null ? "" : call.resultJson();
            if (args.contains("window_start") || args.contains("window_size") || args.contains("range_start")
                    || args.contains("scale_ladder") || result.toLowerCase(Locale.ROOT).contains("definition")) {
                return true;
            }
        }
        return false;
    }
}
