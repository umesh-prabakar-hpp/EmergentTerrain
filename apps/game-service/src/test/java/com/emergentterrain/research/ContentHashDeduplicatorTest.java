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
    void findClonesMatchesNeedsRevisionPriors() {
        // NEEDS_REVISION priors ARE matched: when the critique:skip funnel dominates and
        // every prior cluster member is NEEDS_REVISION, skipping that bucket would leave
        // the match-set empty even though the new paper is a clear duplicate. The
        // a3289005 May-2026 corpus had 47+ within-run clones all status=NEEDS_REVISION;
        // the prior filter (which skipped both NULL_RESULT and NEEDS_REVISION) caught
        // 0 of them and only ever fired on archived cross-run papers (status=PUBLISHED).
        String findings = "RLC: f₀=5344.81 Hz R_crit=31.3 Ω, Q=0.5 at crit.";
        ResearchPaper prior = paper("P-prior-1", "PHYSICS", "RLC Damping Transition", findings, "NEEDS_REVISION");
        List<String> clones = ContentHashDeduplicator.findClones(
                "PHYSICS", "RLC Damping Transition", findings, List.of(prior));
        assertThat(clones).containsExactly("P-prior-1");
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

    // ---- Phase 7 G2 — Jaccard fallback for near-clones ----

    @Test
    void phase7_G2_jaccardCatchesTruncatedClone() {
        // P-284 vs P-244 from REVIEW_REPORT.md refresh #9 — same composite-failure-mode
        // template, but the new paper was truncated at Vf=60% (missing the last 3
        // Vf-stress data points). Exact fingerprint diverges; Jaccard catches it.
        String priorFull =
                "Composite failure (Ef=200 GPa, Em=3 GPa, σym=200 MPa, εy=0.0667): "
                + "Vf=10%→1513 MPa, Vf=20%→2827 MPa, Vf=30%→4140 MPa, Vf=40%→5453 MPa, "
                + "Vf=50%→6767 MPa, Vf=60%→8080 MPa, Vf=70%→9393 MPa, Vf=80%→10707 MPa, "
                + "Vf=90%→12020 MPa.";
        String newTruncated =
                "Composite failure (Ef=200 GPa, Em=3 GPa, σym=200 MPa, εy=0.0667): "
                + "Vf=10%→1513 MPa, Vf=20%→2827 MPa, Vf=30%→4140 MPa, Vf=40%→5453 MPa, "
                + "Vf=50%→6767 MPa, Vf=60%→8080 MPa.";

        ResearchPaper prior = paper("P-prior-244", "MATERIALS", "Composite Failure Mode",
                priorFull, "REJECTED");

        List<String> clones = ContentHashDeduplicator.findClones(
                "MATERIALS", "Composite Failure Mode", newTruncated, List.of(prior));

        assertThat(clones).containsExactly("P-prior-244");
    }

    @Test
    void phase7_G2_jaccardCatchesComputation2ExtendedClone() {
        // P-276 vs REJECTED P-255 from REVIEW_REPORT.md refresh #8 — Collatz template,
        // new paper added a Computation-2 stub with extra numeric tokens that the
        // prior didn't have. Exact fingerprint diverges; Jaccard catches it.
        String priorShort =
                "Collatz (270,330): max 143 steps at n=327, avg 55 ± 5 (n=60), "
                + "fraction > 100 steps: 20% ± 10%. All 60 terminated at 1.";
        String newWithExtras =
                "Collatz (270,330): max 143 steps at n=327, avg 55 ± 5 (n=60), "
                + "fraction > 100 steps: 20% ± 10%. All 60 terminated at 1. "
                + "Computation 2 — exploring around n=85 with scale 3.1.";

        ResearchPaper prior = paper("P-prior-255", "MATHEMATICS", "Collatz Stopping Time",
                priorShort, "REJECTED");

        List<String> clones = ContentHashDeduplicator.findClones(
                "MATHEMATICS", "Collatz Stopping Time", newWithExtras, List.of(prior));

        assertThat(clones).containsExactly("P-prior-255");
    }

    @Test
    void phase7_G2_jaccardDoesNotFireAcrossDifferentTopics() {
        // Even with matching numeric tokens, papers on DIFFERENT topics aren't
        // clones. The (domain, topic) gate prevents Jaccard false-positives.
        String findings = "Values 1513, 2827, 4140, 5453, 6767, 8080, 9393, 10707, 12020.";
        ResearchPaper prior = paper("P-other-topic", "MATERIALS", "Thermal Stress Safety Window",
                findings, "PUBLISHED");

        List<String> clones = ContentHashDeduplicator.findClones(
                "MATERIALS", "Composite Failure Mode", findings, List.of(prior));

        assertThat(clones).isEmpty();
    }

    @Test
    void phase7_G2_jaccardDoesNotFireForUnrelatedTokenSets() {
        // Same topic but completely different numbers — Jaccard correctly returns 0.
        String priorFindings = "Values 1, 2, 3, 4, 5, 6, 7.";
        String newFindings   = "Values 100, 200, 300, 400, 500, 600, 700.";
        ResearchPaper prior = paper("P-unrelated", "MATERIALS", "Composite Failure Mode",
                priorFindings, "PUBLISHED");

        List<String> clones = ContentHashDeduplicator.findClones(
                "MATERIALS", "Composite Failure Mode", newFindings, List.of(prior));

        assertThat(clones).isEmpty();
    }

    @Test
    void phase7_G2_jaccardSimilarityIsZeroForDisjointMultisets() {
        double sim = ContentHashDeduplicator.jaccardSimilarity(
                List.of("1", "2", "3"), List.of("4", "5", "6"));
        assertThat(sim).isEqualTo(0.0);
    }

    @Test
    void phase7_G2_jaccardSimilarityIsOneForSubsetMatch() {
        double sim = ContentHashDeduplicator.jaccardSimilarity(
                List.of("1", "2", "3"), List.of("1", "2", "3", "4", "5"));
        assertThat(sim).isEqualTo(1.0);  // |∩| / min(3, 5) = 3/3
    }

    @Test
    void phase7_G2_jaccardRespectsMultiplicity() {
        // [a, a] ∩ [a] should have size 1, not 2.
        double sim = ContentHashDeduplicator.jaccardSimilarity(
                List.of("a", "a"), List.of("a"));
        assertThat(sim).isEqualTo(1.0);  // |∩| / min(2, 1) = 1/1
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
