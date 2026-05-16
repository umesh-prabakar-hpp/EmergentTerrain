package com.emergentterrain.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 E2 — pins each of the five repair rules in
 * {@link LenientJsonParser}, plus the round-trip property that Jackson
 * actually accepts the repaired output.
 */
class LenientJsonParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Rule 1: code fences ────────────────────────────────────────────

    @Test
    void rule1_stripsOpeningAndClosingFences() {
        String raw = "```json\n{\"a\": 1}\n```";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isTrue();
        assertThat(r.text()).isEqualTo("{\"a\": 1}");
        assertParseable(r.text());
    }

    @Test
    void rule1_stripsBareTripleBacktickFence() {
        String raw = "```\n{\"a\": 1}\n```";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.text().trim()).isEqualTo("{\"a\": 1}");
        assertParseable(r.text());
    }

    // ── Rule 2: prose preamble / postamble ─────────────────────────────

    @Test
    void rule2_lifts_jsonOutOfProsePreambleAndPostamble() {
        String raw = "Sure, here's the JSON:\n{\"a\": 1}\nLet me know if you need more.";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isTrue();
        assertThat(r.text()).isEqualTo("{\"a\": 1}");
        assertParseable(r.text());
    }

    @Test
    void rule2_truncatedResponseNoClosingBraceKeepsContentFromFirstBrace() {
        // Prose preamble + truncated body — rule 5 will append closers.
        String raw = "Here's the result: {\"a\": 1, \"b\": 2";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isTrue();
        assertThat(r.text()).startsWith("{");
        assertParseable(r.text());
    }

    // ── Rule 3: trailing commas ────────────────────────────────────────

    @Test
    void rule3_dropsTrailingCommaBeforeClosingBrace() {
        String raw = "{\"a\": 1, \"b\": 2,}";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isTrue();
        assertThat(r.text()).isEqualTo("{\"a\": 1, \"b\": 2}");
        assertParseable(r.text());
    }

    @Test
    void rule3_dropsTrailingCommaBeforeClosingBracket() {
        String raw = "[1, 2, 3,]";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.text()).isEqualTo("[1, 2, 3]");
        assertParseable(r.text());
    }

    // ── Rule 4: unclosed string ───────────────────────────────────────

    @Test
    void rule4_closesDanglingStringAtEof() {
        // Mid-string truncation: the value's open quote has no closer.
        String raw = "{\"hypothesis\": \"The pendulum period error";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isTrue();
        assertThat(r.text()).contains("\"The pendulum period error\"");
        assertParseable(r.text());
    }

    @Test
    void rule4_escapedQuotesDoNotMiscountAsOpener() {
        // The \" inside the string must not toggle the open/close count.
        String raw = "{\"k\": \"value with \\\"escaped\\\" quotes\"}";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isFalse();
        assertThat(r.text()).isEqualTo(raw);
        assertParseable(r.text());
    }

    // ── Rule 5: unbalanced braces / brackets ──────────────────────────

    @Test
    void rule5_appendsMissingClosingBrace() {
        String raw = "{\"a\": 1";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isTrue();
        assertThat(r.text()).isEqualTo("{\"a\": 1}");
        assertParseable(r.text());
    }

    @Test
    void rule5_balancesNestedObjectsInReverseOrder() {
        // `{[` needs `]}` not `}]`.
        String raw = "{\"a\": [1, 2";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isTrue();
        assertThat(r.text()).isEqualTo("{\"a\": [1, 2]}");
        assertParseable(r.text());
    }

    @Test
    void rule5_ignoresBracesInsideStrings() {
        // The `{` inside the string must NOT increase brace depth.
        String raw = "{\"k\": \"value with { inside\"}";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isFalse();
        assertThat(r.text()).isEqualTo(raw);
        assertParseable(r.text());
    }

    // ── Composition — multiple repairs in one input ───────────────────

    @Test
    void composition_codeFenceWithPreambleAndTrailingComma() {
        String raw = "Here's the JSON:\n```json\n{\"a\": 1, \"b\": 2,}\n```\nThanks!";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isTrue();
        assertThat(r.text()).isEqualTo("{\"a\": 1, \"b\": 2}");
        assertParseable(r.text());
    }

    @Test
    void composition_realisticTruncatedRevisionResponse() {
        // Approximates the rev:parse_failed signature in REVIEW_REPORT refresh #1:
        // the LLM's revision response cuts off mid-findings.
        String raw = "```json\n{\"hypothesis\": \"Carnot efficiency rises with T_h.\","
                + "\"findings\": {\"1\": \"η rose from 0.37 to 0.70 across the sweep";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isTrue();
        assertParseable(r.text());
    }

    // ── Pass-through: clean input ─────────────────────────────────────

    @Test
    void cleanJsonPassesThroughUnchanged() {
        String raw = "{\"hypothesis\": \"complete\", \"findings\": {\"1\": \"done.\"}}";
        var r = LenientJsonParser.repair(raw);
        assertThat(r.repaired()).isFalse();
        assertThat(r.text()).isEqualTo(raw);
    }

    @Test
    void nullInputReturnsNullAndNotRepaired() {
        var r = LenientJsonParser.repair(null);
        assertThat(r.text()).isNull();
        assertThat(r.repaired()).isFalse();
    }

    @Test
    void blankInputReturnsBlankAndNotRepaired() {
        var r = LenientJsonParser.repair("");
        assertThat(r.text()).isEmpty();
        assertThat(r.repaired()).isFalse();
        var r2 = LenientJsonParser.repair("   ");
        assertThat(r2.text()).isEqualTo("   ");
        assertThat(r2.repaired()).isFalse();
    }

    // ── Helper-method direct tests ─────────────────────────────────────

    @Test
    void hasUnclosedStringDetectsOddQuoteCount() {
        assertThat(LenientJsonParser.hasUnclosedString("{\"a\": \"open")).isTrue();
        assertThat(LenientJsonParser.hasUnclosedString("{\"a\": \"closed\"}")).isFalse();
        // Escaped quotes don't toggle.
        assertThat(LenientJsonParser.hasUnclosedString("{\"a\": \"x\\\"y\"}")).isFalse();
    }

    @Test
    void balanceClosersHandlesMixedBraceBracketStacks() {
        assertThat(LenientJsonParser.balanceClosers("{[{")).isEqualTo("{[{}]}");
        assertThat(LenientJsonParser.balanceClosers("{")).isEqualTo("{}");
        assertThat(LenientJsonParser.balanceClosers("{}")).isEqualTo("{}");
    }

    // ── Helper ─────────────────────────────────────────────────────────

    private void assertParseable(String json) {
        try {
            mapper.readTree(json);
        } catch (Exception e) {
            org.junit.jupiter.api.Assertions.fail(
                    "Expected Jackson to accept repaired output but it threw: " + e.getMessage()
                            + "\nInput: " + json);
        }
    }
}
