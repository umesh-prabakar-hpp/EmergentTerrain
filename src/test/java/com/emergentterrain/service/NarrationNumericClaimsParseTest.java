package com.emergentterrain.service;

import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.PromptSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 11 C2 — parser tests for the optional {@code numeric_claims} JSON
 * array. Confirms the parser handles: well-formed array (full round-trip),
 * absent (null), empty, malformed entries (silently skipped), and that
 * PromptSanitizer leaves {@code [call_N]} markers untouched.
 */
class NarrationNumericClaimsParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesWellFormedArray() {
        String json = """
                {"hypothesis":"h","findings":"At T=792 K [call_2], ln=7.553 [call_2].",
                 "conclusion":"c","confidenceScore":0.7,
                 "numeric_claims":[
                   {"value":792,"units":"K","tool_call_id":"call_2",
                    "context":"swept temperature","source_location":"findings:1"},
                   {"value":7.553,"units":"","tool_call_id":"call_2",
                    "context":"ln(Kc/Kc0) at T=792 K","source_location":"findings:1"}
                 ]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.numericClaims()).hasSize(2);
        NarrationResult.NumericClaim first = r.numericClaims().get(0);
        assertThat(first.value()).isEqualTo(792.0);
        assertThat(first.units()).isEqualTo("K");
        assertThat(first.toolCallId()).isEqualTo("call_2");
        assertThat(first.sourceLocation()).isEqualTo("findings:1");
        NarrationResult.NumericClaim second = r.numericClaims().get(1);
        assertThat(second.value()).isEqualTo(7.553);
        assertThat(second.units()).isEmpty();
    }

    @Test
    void returnsNullWhenArrayAbsent() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.5}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.numericClaims()).isNull();
    }

    @Test
    void returnsEmptyListWhenArrayEmpty() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.5,
                 "numeric_claims":[]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.numericClaims()).isEmpty();
    }

    @Test
    void silentlySkipsMalformedEntries() {
        // First entry missing value (skipped); second missing tool_call_id (skipped);
        // third is well-formed and survives.
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.5,
                 "numeric_claims":[
                   {"units":"K","tool_call_id":"call_1"},
                   {"value":3.14,"units":"","context":"missing id"},
                   {"value":42.0,"units":"MPa","tool_call_id":"call_3",
                    "context":"yield strength","source_location":"findings:2"}
                 ]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        assertThat(r.numericClaims()).hasSize(1);
        assertThat(r.numericClaims().get(0).toolCallId()).isEqualTo("call_3");
        assertThat(r.numericClaims().get(0).value()).isEqualTo(42.0);
    }

    @Test
    void tolerates_nonArrayShape() {
        // numeric_claims is an object rather than an array — parser logs and
        // returns an empty list (paper still parses cleanly).
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.5,
                 "numeric_claims":{"oops":"not an array"}}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);
        // Either null or empty is acceptable here — the contract is "do not crash".
        assertThat(r.numericClaims()).isNotNull();
        assertThat(r.numericClaims()).isEmpty();
    }

    @Test
    void promptSanitizer_preservesCallMarkers() {
        // C2 contract: PromptSanitizer must not strip [call_N] markers because
        // the CitationValidator (C3) depends on them surviving to the validator
        // input. None of the existing leak patterns target this shape, but we
        // assert it explicitly so a future regex addition doesn't silently
        // break Phase 11.
        String prose = "At T=792 K [call_2], ln(Kc/Kc0) = 7.553 [call_2], matching "
                + "Van't Hoff [call_3] to 4 sig figs.";
        String sanitized = PromptSanitizer.sanitize(prose);
        assertThat(sanitized).contains("[call_2]");
        assertThat(sanitized).contains("[call_3]");
        assertThat(sanitized).contains("7.553");
    }

    @Test
    void promptSanitizer_callMarkers_surviveAlongsideLeakStrip() {
        // Mixed input: legitimate [call_N] markers AND a known leak pattern
        // ("starting position 61 and scale 5.4"). The leak strips; the markers
        // survive.
        String prose = "The result at 7.553 [call_2] is robust starting position 61 "
                + "and scale 5.4. Confirmed [call_3].";
        String sanitized = PromptSanitizer.sanitize(prose);
        assertThat(sanitized).contains("[call_2]");
        assertThat(sanitized).contains("[call_3]");
        assertThat(sanitized).doesNotContain("starting position 61");
    }
}
