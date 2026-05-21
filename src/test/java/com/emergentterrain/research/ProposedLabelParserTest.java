package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 23.6 — {@link ProposedLabelParser} contract. Pure-logic tests
 * over LLM-output text; no LLM call required.
 */
class ProposedLabelParserTest {

    @Test
    void inlineEvidenceLabelClaim() {
        assertEquals(Optional.of(EvidenceLabel.THEOREM),
                ProposedLabelParser.parse("EvidenceLabel: THEOREM"));
        assertEquals(Optional.of(EvidenceLabel.EMPIRICAL_RESULT),
                ProposedLabelParser.parse("the proper label here is EvidenceLabel: empirical_result, finally"));
    }

    @Test
    void shortLabelKeywordAlsoWorks() {
        assertEquals(Optional.of(EvidenceLabel.CONJECTURE),
                ProposedLabelParser.parse("Label: CONJECTURE"));
        assertEquals(Optional.of(EvidenceLabel.UNEXPLAINED_OBSERVATION),
                ProposedLabelParser.parse("label = unexplained_observation"));
    }

    @Test
    void jsonStyleValueWithQuotes() {
        assertEquals(Optional.of(EvidenceLabel.EMPIRICAL_RESULT),
                ProposedLabelParser.parse("\"label\": \"EMPIRICAL_RESULT\""));
        assertEquals(Optional.of(EvidenceLabel.CONJECTURE),
                ProposedLabelParser.parse("evidence_label = 'CONJECTURE'"));
    }

    @Test
    void sectionHeaderForm() {
        String response = """
                ## Evidence Label

                EMPIRICAL_RESULT

                ## Mechanism
                The relation is bounded by ...
                """;
        assertEquals(Optional.of(EvidenceLabel.EMPIRICAL_RESULT),
                ProposedLabelParser.parse(response));
    }

    @Test
    void caseAndPunctuationTolerant() {
        // Hyphen alias for underscore.
        assertEquals(Optional.of(EvidenceLabel.EMPIRICAL_RESULT),
                ProposedLabelParser.parse("Label: empirical-result"));
        assertEquals(Optional.of(EvidenceLabel.THEOREM),
                ProposedLabelParser.parse("EVIDENCE_LABEL: theorem"));
    }

    @Test
    void noRecognizableLabelReturnsEmpty() {
        assertTrue(ProposedLabelParser.parse("Nothing here about labels.").isEmpty());
        assertTrue(ProposedLabelParser.parse("").isEmpty());
        assertTrue(ProposedLabelParser.parse(null).isEmpty());
    }

    @Test
    void unknownLabelValueReturnsEmpty() {
        assertTrue(ProposedLabelParser.parse("Label: AXIOM").isEmpty(),
                "AXIOM is not in our EvidenceLabel enum — parser must not invent");
        assertTrue(ProposedLabelParser.parse("Label: yes").isEmpty());
    }

    @Test
    void ambiguousMultipleDistinctLabelsReturnsEmpty() {
        // LLM hedged. Refuse to pick.
        String response = """
                Maybe Label: CONJECTURE
                Or perhaps EvidenceLabel: EMPIRICAL_RESULT
                """;
        assertTrue(ProposedLabelParser.parse(response).isEmpty(),
                "two distinct claims = ambiguous; downstream falls back to floor default");
    }

    @Test
    void sameLabelMentionedMultipleTimesParsesCleanly() {
        // Repeating the same label is not ambiguous.
        String response = """
                Label: CONJECTURE
                ... lots of prose ...
                As mentioned above, Label: CONJECTURE.
                """;
        assertEquals(Optional.of(EvidenceLabel.CONJECTURE),
                ProposedLabelParser.parse(response));
    }
}
