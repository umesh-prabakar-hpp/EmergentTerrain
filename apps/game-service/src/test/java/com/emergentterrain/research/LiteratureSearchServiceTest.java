package com.emergentterrain.research;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 D1.2 — corpus + service. One assertion per engine topic that the
 * service returns at least one reference for it. Keeps the v1 corpus pinned
 * to the engine's per-topic methods.
 */
class LiteratureSearchServiceTest {

    private LiteratureSearchService service;

    @BeforeEach
    void setUp() {
        service = new LiteratureSearchService(new ClassPathResource("literature-corpus.yml"));
        service.loadCorpus();
    }

    // ── Corpus coverage: one entry per non-INTERDISCIPLINARY topic + sentinels ──

    @Test
    void corpusHasAtLeastTwentyReferences() {
        // 15 non-INTERDISCIPLINARY + 5 INTERDISCIPLINARY sentinels per memo §4.
        assertThat(service.size()).isGreaterThanOrEqualTo(20);
    }

    @Test
    void everyMathematicsTopicHasAReference() {
        for (String topic : List.of(
                "Prime Constellation Search", "Collatz Stopping Time",
                "Goldbach Partition Survey", "Aliquot Sequence Behaviour",
                "Happy Number Distribution")) {
            assertThat(service.findFor("MATHEMATICS", topic))
                    .as("MATHEMATICS topic %s has a curated reference", topic)
                    .isNotEmpty();
        }
    }

    @Test
    void everyPhysicsTopicHasAReference() {
        for (String topic : List.of(
                "Projectile Drag Optimisation", "Pendulum Breakdown Angle",
                "Van der Waals Crossover", "Diffraction Order Survey",
                "RLC Damping Transition")) {
            assertThat(service.findFor("PHYSICS", topic))
                    .as("PHYSICS topic %s has a curated reference", topic)
                    .isNotEmpty();
        }
    }

    @Test
    void everyMaterialsTopicHasAReference() {
        for (String topic : List.of(
                "Composite Failure Mode", "Binary Sphere Packing",
                "Stiffness Crossover Volume Fraction",
                "Arrhenius Rule Validity", "Thermal Stress Safety Window")) {
            assertThat(service.findFor("MATERIALS", topic))
                    .as("MATERIALS topic %s has a curated reference", topic)
                    .isNotEmpty();
        }
    }

    @Test
    void everyInterdisciplinaryTopicHasSentinelReference() {
        for (String topic : List.of(
                "Collatz-RLC Depth Resonance",
                "Prime-Gap Defect Thermal Loading",
                "Resonant Thermal Fatigue Window",
                "Happy-Number Packing Anomaly",
                "Van-der-Waals Composite Crossover")) {
            List<LiteratureReference> refs = service.findFor("INTERDISCIPLINARY", topic);
            assertThat(refs).as("INTERDISCIPLINARY topic %s has a sentinel", topic).isNotEmpty();
            assertThat(refs.get(0).isEngineConstructed())
                    .as("INTERDISCIPLINARY %s is marked engine-constructed", topic)
                    .isTrue();
        }
    }

    // ── Reference content ──

    @Test
    void primeConstellationReferenceQuotesHardyLittlewood() {
        LiteratureReference ref = service.findFor("MATHEMATICS", "Prime Constellation Search").get(0);
        assertThat(ref.citation()).contains("Hardy").contains("Littlewood");
        assertThat(ref.closedForm()).contains("C₂").contains("ln");
    }

    @Test
    void rlcReferenceCarriesCriticalDampingClosedForm() {
        LiteratureReference ref = service.findFor("PHYSICS", "RLC Damping Transition").get(0);
        assertThat(ref.closedForm()).contains("R_crit").contains("2·√(L/C)");
    }

    @Test
    void compositeFailureReferenceMatchesPhase5MagnitudeBound() {
        // The literature corpus's magnitude estimate should agree with the
        // Phase 5 B2 MagnitudeRangeValidator bound (3 GPa) — keeps the two
        // surfaces in sync.
        LiteratureReference ref = service.findFor("MATERIALS", "Composite Failure Mode").get(0);
        assertThat(ref.magnitudeEstimate()).contains("3 GPa");
    }

    // ── Lookup semantics ──

    @Test
    void lookupIsCaseInsensitive() {
        assertThat(service.findFor("mathematics", "Prime Constellation Search")).isNotEmpty();
        assertThat(service.findFor("MATHEMATICS", "prime constellation search")).isNotEmpty();
        assertThat(service.findFor("Mathematics", "Prime Constellation Search")).isNotEmpty();
    }

    @Test
    void lookupReturnsEmptyForUnknownDomainOrTopic() {
        assertThat(service.findFor("BIOLOGY", "Prime Constellation Search")).isEmpty();
        assertThat(service.findFor("MATHEMATICS", "Riemann Zeta Zeros")).isEmpty();
    }

    @Test
    void nullArgumentsReturnEmpty() {
        assertThat(service.findFor(null, "Prime Constellation Search")).isEmpty();
        assertThat(service.findFor("MATHEMATICS", null)).isEmpty();
    }

    @Test
    void zeroOrNegativeMaxResultsReturnsEmpty() {
        assertThat(service.findFor("MATHEMATICS", "Prime Constellation Search", 0)).isEmpty();
        assertThat(service.findFor("MATHEMATICS", "Prime Constellation Search", -1)).isEmpty();
    }

    @Test
    void defaultMaxResultsCapIsThree() {
        // Whatever the corpus carries for a topic, the default-arity lookup never
        // returns more than the memo's DEFAULT_MAX_RESULTS (3).
        for (LiteratureReference r : service.allReferences()) {
            int count = service.findFor(r.domain(), r.topic()).size();
            assertThat(count).isLessThanOrEqualTo(LiteratureSearchService.DEFAULT_MAX_RESULTS);
        }
    }

    // ── Operator-only `notes` is loaded ──

    @Test
    void notesFieldIsLoadedAndPreserved() {
        LiteratureReference ref = service.findFor("MATERIALS", "Arrhenius Rule Validity").get(0);
        // Notes documents the P-a328-19 vs P-a328-31 corpus story; verify it loaded.
        assertThat(ref.notes()).isNotBlank().contains("P-a328");
    }
}
