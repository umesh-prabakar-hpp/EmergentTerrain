package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the PreRegistration record — the hypothesis locked before computation runs.
 * Also exercises FormulationResult to confirm it correctly carries the pre-registration.
 */
class PreRegistrationTest {

    @Test
    void preRegistrationRecordHoldsAllFields() {
        var pr = new PreRegistration(
                "Collatz stopping times peak near n=871",
                "max_steps > 150 in [800, 900)",
                "max_steps < 100 across the entire range",
                "");

        assertThat(pr.hypothesis()).isEqualTo("Collatz stopping times peak near n=871");
        assertThat(pr.prediction()).isEqualTo("max_steps > 150 in [800, 900)");
        assertThat(pr.falsificationCondition()).isEqualTo("max_steps < 100 across the entire range");
        assertThat(pr.mappingNotes()).isEmpty();
    }

    @Test
    void preRegistrationWithMappingNotes() {
        var pr = new PreRegistration(
                "RLC resonance depth maps to Collatz stopping time via L = depth × alpha",
                "resonance depth peaks at same param1 as Collatz max_steps",
                "no correlation between depth and stopping time",
                "L = depth × alpha where alpha = 0.02 Hz/step");

        assertThat(pr.mappingNotes()).contains("alpha");
    }

    @Test
    void preRegistrationEquality() {
        var pr1 = new PreRegistration("h", "p", "f", "notes");
        var pr2 = new PreRegistration("h", "p", "f", "notes");
        assertThat(pr1).isEqualTo(pr2);
    }

    @Test
    void preRegistrationNotEqualWhenHypothesisDiffers() {
        var pr1 = new PreRegistration("hypothesis A", "p", "f", "");
        var pr2 = new PreRegistration("hypothesis B", "p", "f", "");
        assertThat(pr1).isNotEqualTo(pr2);
    }

    @Test
    void preRegistrationWithNullMappingNotes() {
        var pr = new PreRegistration("h", "p", "f", null);
        assertThat(pr.mappingNotes()).isNull();
        // null mapping notes = same-domain paper, no cross-domain mapping required
    }

    @Test
    void resultClassificationEnumCoversAllFourValues() {
        // Confirms all four classifications exist and are distinct
        assertThat(ResultClassification.values()).containsExactlyInAnyOrder(
                ResultClassification.SUPPORTED,
                ResultClassification.PARTIALLY_SUPPORTED,
                ResultClassification.NULL_RESULT,
                ResultClassification.DEGENERATE);
    }

    @Test
    void paperDraftCarriesPreRegistrationAndClassification() {
        var preReg = new PreRegistration("hypothesis", "prediction", "falsification", "");
        var draft = new com.emergentterrain.research.PaperDraft(
                java.util.UUID.randomUUID(), "bot-id", "MATHEMATICS", "Collatz Stopping Time",
                "title", "raw", "hyp", "conc", "THEORETICAL",
                java.util.List.of(), 0.5, 0.5, 0.0, 1L,
                java.time.Instant.now(), 1, "P-test-1", 42, 3.0, false,
                null, null, java.util.List.of(), null,
                preReg, ResultClassification.SUPPORTED);

        assertThat(draft.preRegistration()).isEqualTo(preReg);
        assertThat(draft.resultClassification()).isEqualTo(ResultClassification.SUPPORTED);
    }

    @Test
    void paperDraftAllowsNullPreRegistrationForDynamicPapers() {
        // Dynamic research papers use null pre-registration
        var draft = new com.emergentterrain.research.PaperDraft(
                java.util.UUID.randomUUID(), "bot-id", "PHYSICS", "RLC Damping Transition",
                "title", "raw", "hyp", "conc", "EMPIRICAL",
                java.util.List.of(), 0.3, 0.3, 0.0, 1L,
                java.time.Instant.now(), 1, "P-test-2", null, null, false,
                null, null, java.util.List.of(), null,
                null, ResultClassification.PARTIALLY_SUPPORTED);

        assertThat(draft.preRegistration()).isNull();
        assertThat(draft.param1()).isNull();
    }
}
