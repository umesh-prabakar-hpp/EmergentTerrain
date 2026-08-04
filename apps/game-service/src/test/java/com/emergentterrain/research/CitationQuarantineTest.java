package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sentence-level citation quarantine (room b72c4e10 fix, Rec 5). Pure — exercises
 * {@link CitationValidator#quarantineSentences} and {@link
 * CitationValidator#containsNumberToken} with no Spring.
 */
class CitationQuarantineTest {

    @Test
    void excisesOnlyTheSentenceCarryingTheRejectedNumber() {
        // P-b72c-12 shape: one fabricated constant (0.143) in an otherwise-fine block.
        String block = "The happy-prime fraction is 0.036 [call_1]. "
                + "The asymptotic happy density is 0.143. "
                + "This is consistent with the prime number theorem.";
        String kept = CitationValidator.quarantineSentences(block, List.of("0.143"));
        assertTrue(kept.contains("0.036"), "traced sentence survives");
        assertTrue(kept.contains("prime number theorem"), "unrelated sentence survives");
        assertFalse(kept.contains("0.143"), "fabricated-constant sentence is removed");
    }

    @Test
    void removesEverySentenceWithAnyRejectedToken() {
        String block = "Value A is 0.143. Value B is 0.025. Value C is 0.036 [call_1].";
        String kept = CitationValidator.quarantineSentences(block, List.of("0.143", "0.025"));
        assertEquals("Value C is 0.036 [call_1].", kept);
    }

    @Test
    void returnsEmptyWhenAllSentencesRejected() {
        String block = "Density is 0.143. Ratio is 0.025.";
        String kept = CitationValidator.quarantineSentences(block, List.of("0.143", "0.025"));
        assertTrue(kept.isBlank(), "all sentences carried a rejected number → empty");
    }

    @Test
    void noRejectedTokensLeavesBlockUnchanged() {
        String block = "All good here, 0.036 [call_1] and 0.052 [call_2].";
        assertEquals(block, CitationValidator.quarantineSentences(block, List.of()));
    }

    @Test
    void containsNumberTokenRespectsDigitBoundaries() {
        // 0.143 must not strike a sentence that only contains 0.1437.
        assertTrue(CitationValidator.containsNumberToken("the value 0.143 here", "0.143"));
        assertFalse(CitationValidator.containsNumberToken("the value 0.1437 here", "0.143"));
        assertFalse(CitationValidator.containsNumberToken("the value 10.143 here", "0.143"));
        assertTrue(CitationValidator.containsNumberToken("ends at 0.143", "0.143"));
        assertTrue(CitationValidator.containsNumberToken("0.143 leads", "0.143"));
    }

    @Test
    void allFailuresAreProseSideGatesOnCheck3() {
        // Prose-side only (uncited/unmatched) → quarantine-eligible.
        var proseSide = new CitationValidator.CitationValidationResult(
                2, 0, 0, 0, 0, List.of(), List.of());
        assertTrue(proseSide.allFailuresAreProseSide());
        // Any out-of-tolerance (Check-3 claim-side fabrication) → NOT eligible.
        var withCheck3 = new CitationValidator.CitationValidationResult(
                1, 0, 1, 0, 0, List.of(), List.of());
        assertFalse(withCheck3.allFailuresAreProseSide());
        // No failures at all → not "prose-side failures" either.
        assertFalse(CitationValidator.CitationValidationResult.clean().allFailuresAreProseSide());
    }
}
