package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-05-21 review §1 rec #5 — unit tests for {@link
 * CitationCorpusValidator}. Three fabricated citations from the review
 * batch drive the test cases.
 */
class CitationCorpusValidatorTest {

    private static LiteratureReference ref(String citation) {
        return new LiteratureReference(
                "MATHEMATICS", "Prime Constellation Search", citation,
                "closed form", "valid region", "magnitude estimate",
                "notes", 10);
    }

    private static final List<LiteratureReference> CORPUS = List.of(
            ref("Hardy & Littlewood (1923), 'Some Problems of Partitio Numerorum III'"),
            ref("Lagarias (1985), 'The 3x+1 Problem and Its Generalizations'"),
            ref("Reuss (1929), 'Berechnung der Fließgrenze von Mischkristallen'"),
            ref("Hudson (1992), 'Elastic Properties of Composites'"));

    @Test
    void corpusMatchPassesForHardyLittlewood() {
        // "Hardy & Littlewood (1923)" appears verbatim in the corpus citation.
        assertThat(CitationCorpusValidator.validate(
                "Density follows Hardy & Littlewood (1923) for large N.", CORPUS))
                .isEmpty();
    }

    @Test
    void corpusMatchPassesForLagarias() {
        // "Lagarias 1985" (no parens) — extracts the first capitalised token
        // + year, matches corpus citation containing "Lagarias" + "1985".
        assertThat(CitationCorpusValidator.validate(
                "Broader investigations are documented in Lagarias 1985.", CORPUS))
                .isEmpty();
    }

    @Test
    void fabricatedAuthorYearFlags() {
        // Hypothetical fabricated cite — "Smith (2020)" is not in our test corpus.
        List<String> flagged = CitationCorpusValidator.validate(
                "As shown in Smith (2020), the density varies.", CORPUS);
        assertThat(flagged).containsExactly("Smith (2020)");
    }

    @Test
    void textbookWithoutYearReturnsEmpty_v1Limitation() {
        // "Hecht Optics 5th ed." has no year → not extracted at all in v1.
        // Documented limitation; future iteration could add textbook detection.
        assertThat(CitationCorpusValidator.validate(
                "See Hecht Optics 5th ed. for grating theory.", CORPUS))
                .isEmpty();
    }

    @Test
    void multipleCitesMixOfPassAndFlag() {
        List<String> flagged = CitationCorpusValidator.validate(
                "Hardy & Littlewood (1923) for primes; Brown (1999) for the bound.",
                CORPUS);
        assertThat(flagged).containsExactly("Brown (1999)");
    }

    @Test
    void duplicateCitesDeduplicated() {
        List<String> flagged = CitationCorpusValidator.validate(
                "Smith (2020) showed this. Later, Smith (2020) confirmed.", CORPUS);
        assertThat(flagged).containsExactly("Smith (2020)");
    }

    @Test
    void yearOutsideRangeNotExtracted() {
        // "Smith 1700" — year before 1800 isn't extracted. Avoids false
        // positives on stray 4-digit numbers like "...in 1721 different ways".
        assertThat(CitationCorpusValidator.validate(
                "Background in 1721 different cases applies here.", CORPUS))
                .isEmpty();
    }

    @Test
    void lowercaseFollowedByYearNotExtracted() {
        // Lastname must start with an uppercase letter. "tested in 2020"
        // isn't an author-year cite.
        assertThat(CitationCorpusValidator.validate(
                "We tested in 2020 across all ranges.", CORPUS))
                .isEmpty();
    }

    @Test
    void emptyCorpusReturnsEmpty() {
        // Defensive: nothing to validate against, return empty rather than
        // flag every cite as unmatched. The publish-side call should treat
        // empty corpus as "skip the check".
        assertThat(CitationCorpusValidator.validate("Smith (2020) showed.", List.of()))
                .isEmpty();
    }

    @Test
    void nullInputsReturnEmpty() {
        assertThat(CitationCorpusValidator.validate(null, CORPUS)).isEmpty();
        assertThat(CitationCorpusValidator.validate("Smith (2020).", null)).isEmpty();
        assertThat(CitationCorpusValidator.validate("", CORPUS)).isEmpty();
    }

    @Test
    void hyphenatedSurnameMatches() {
        // "Hardy-Littlewood 1923" — corpus has "Hardy & Littlewood (1923)".
        // First token is "Hardy", year is 1923, both appear in corpus citation.
        assertThat(CitationCorpusValidator.validate(
                "Following Hardy-Littlewood 1923 the bound holds.", CORPUS))
                .isEmpty();
    }
}
