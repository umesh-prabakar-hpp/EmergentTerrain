package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 5 B3 — fingerprint and findClones unit tests. */
class ContentHashDeduplicatorTest {

    // -------- fingerprint --------

    @Test
    void identicalFindingsProduceIdenticalFingerprints() {
        String findings = "RLC circuit (L=0.0080 H, C=1.1e-06 F): f₀=5344.81 Hz, R_crit=31.3 Ω.";
        String a = ContentHashDeduplicator.fingerprint("PHYSICS", "RLC Damping Transition", findings);
        String b = ContentHashDeduplicator.fingerprint("PHYSICS", "RLC Damping Transition", findings);
        assertThat(a).isNotEmpty().isEqualTo(b);
    }

    @Test
    void differentNumericContentProducesDifferentFingerprints() {
        String findings1 = "Composite failure: Vf=80%→9620MPa Vf=90%→12020MPa.";
        String findings2 = "Composite failure: Vf=80%→3010MPa Vf=90%→3450MPa.";
        String a = ContentHashDeduplicator.fingerprint("MATERIALS", "Composite Failure Mode", findings1);
        String b = ContentHashDeduplicator.fingerprint("MATERIALS", "Composite Failure Mode", findings2);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void wordOrderInFindingsDoesNotAffectFingerprint() {
        // Sorted multiset of numeric tokens — surrounding words can vary.
        String f1 = "Sweep: 0.789 max at r=0.405, baseline 0.745.";
        String f2 = "Baseline 0.745. Max value 0.789 at r=0.405 in sweep.";
        String a = ContentHashDeduplicator.fingerprint("MATERIALS", "Binary Sphere Packing", f1);
        String b = ContentHashDeduplicator.fingerprint("MATERIALS", "Binary Sphere Packing", f2);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void fewerThanThreeNumericTokensReturnsEmptyFingerprint() {
        // Insufficient signal — refuse to fingerprint short findings.
        assertThat(ContentHashDeduplicator.fingerprint("X", "Y", "the value is 42")).isEmpty();
        assertThat(ContentHashDeduplicator.fingerprint("X", "Y", "two values 1.0 and 2.0")).isEmpty();
        assertThat(ContentHashDeduplicator.fingerprint("X", "Y", "three: 1, 2, 3"))
                .isNotEmpty();
    }

    @Test
    void domainAndTopicAreIncludedInFingerprint() {
        // Same numbers, different (domain, topic) → distinct fingerprints.
        String findings = "Values: 1.0 2.0 3.0";
        String a = ContentHashDeduplicator.fingerprint("PHYSICS", "RLC Damping Transition", findings);
        String b = ContentHashDeduplicator.fingerprint("MATHEMATICS", "Collatz Stopping Time", findings);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void nullArgumentsReturnEmptyFingerprint() {
        assertThat(ContentHashDeduplicator.fingerprint(null, "T", "f")).isEmpty();
        assertThat(ContentHashDeduplicator.fingerprint("D", null, "f")).isEmpty();
        assertThat(ContentHashDeduplicator.fingerprint("D", "T", null)).isEmpty();
    }

    // -------- findClones --------

    @Test
    void findClonesReturnsPriorPaperWithMatchingFingerprint() {
        // Two papers with the same findings → second one detects the first as a clone.
        String findings = "RLC: f₀=5344.81 Hz R_crit=31.3 Ω, Q=0.5 at crit.";
        ResearchPaper prior = paper("P-prior-1", "PHYSICS", "RLC Damping Transition", findings, "PUBLISHED");

        List<String> clones = ContentHashDeduplicator.findClones(
                "PHYSICS", "RLC Damping Transition", findings, List.of(prior));
        assertThat(clones).containsExactly("P-prior-1");
    }

    @Test
    void findClonesSkipsNeedsRevisionPapers() {
        String findings = "RLC: f₀=5344.81 Hz R_crit=31.3 Ω, Q=0.5 at crit.";
        ResearchPaper prior = paper("P-prior-1", "PHYSICS", "RLC Damping Transition", findings, "NEEDS_REVISION");
        List<String> clones = ContentHashDeduplicator.findClones(
                "PHYSICS", "RLC Damping Transition", findings, List.of(prior));
        assertThat(clones).isEmpty();
    }

    @Test
    void findClonesSkipsNullResultPapers() {
        String findings = "Sweep: 0.789 at 0.405 baseline 0.745.";
        ResearchPaper prior = paper("P-prior-1", "MATERIALS", "Binary Sphere Packing", findings, "NULL_RESULT");
        List<String> clones = ContentHashDeduplicator.findClones(
                "MATERIALS", "Binary Sphere Packing", findings, List.of(prior));
        assertThat(clones).isEmpty();
    }

    @Test
    void findClonesEmptyWhenNoPriorMatches() {
        String findings1 = "RLC: f₀=5344.81 Hz R_crit=31.3 Ω.";
        String findings2 = "RLC: f₀=9000.00 Hz R_crit=50.0 Ω.";
        ResearchPaper prior = paper("P-prior-1", "PHYSICS", "RLC Damping Transition", findings1, "PUBLISHED");
        List<String> clones = ContentHashDeduplicator.findClones(
                "PHYSICS", "RLC Damping Transition", findings2, List.of(prior));
        assertThat(clones).isEmpty();
    }

    @Test
    void findClonesReturnsAllMatchingPriors() {
        // Cluster: 3 prior papers with the same fingerprint (the P-a328-69/73/85 pattern).
        String findings = "Happy density 34/150 = 0.23 ± 0.07 across 32 windows, max deviation 0.0837.";
        ResearchPaper p1 = paper("P-prior-1", "MATHEMATICS", "Happy Number Distribution", findings, "PUBLISHED");
        ResearchPaper p2 = paper("P-prior-2", "MATHEMATICS", "Happy Number Distribution", findings, "PUBLISHED");
        List<String> clones = ContentHashDeduplicator.findClones(
                "MATHEMATICS", "Happy Number Distribution", findings, List.of(p1, p2));
        assertThat(clones).containsExactlyInAnyOrder("P-prior-1", "P-prior-2");
    }

    // -------- helper --------

    private static ResearchPaper paper(String id, String domain, String topic, String findings, String status) {
        return new ResearchPaper(id, UUID.randomUUID(), UUID.randomUUID().toString(),
                domain, topic, "Title",
                "hyp", "EMPIRICAL", findings, "conc",
                List.of(), 0.5, status, 100L, Instant.now(),
                1, 0.0, "test-llm", "path");
    }
}
