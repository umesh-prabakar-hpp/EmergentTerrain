package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 5 B2 — corpus-anchored magnitude-bound checks. */
class MagnitudeRangeValidatorTest {

    // -------- Composite failure: > 3 GPa is unphysical --------

    @Test
    void pA328_2_compositeStressTwelveGPaFlagged() {
        // P-a328-2/8/12/42/63/84/86: max stress ≈ 12 020 MPa from the linear σ = Vf·Ef·ε_y model.
        // The validator stops on the first violation, so the reported value is the
        // earliest above-bound entry in the series (Vf=80%→9620MPa here).
        String findings = "Composite failure (...): Vf=80%→9620MPa Vf=90%→12020MPa. Stress rises linearly.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", findings);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0))
                .contains("Composite Failure Mode")
                .contains("9620")
                .contains("3000");
    }

    @Test
    void compositeStressUnderThreeGPaPassesUnflagged() {
        String findings = "Composite failure (...): Vf=20%→820MPa Vf=40%→1620MPa Vf=60%→2420MPa.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", findings);
        assertThat(reasons).isEmpty();
    }

    // -------- VdW composite crossover: > 1.5 GPa is unphysical --------

    @Test
    void pA328_7_vdwSevenPointEightGPaFlagged() {
        // P-a328-7: 7.8 GPa stress.
        String findings = "VdW-induced composite stress reached 7800 MPa at P=80 atm.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "INTERDISCIPLINARY", "Van-der-Waals Composite Crossover", findings);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0)).contains("7800");
    }

    @Test
    void vdwUnderOnePointFiveGPaPassesUnflagged() {
        String findings = "VdW-induced composite stress reached 920 MPa at P=80 atm.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "INTERDISCIPLINARY", "Van-der-Waals Composite Crossover", findings);
        assertThat(reasons).isEmpty();
    }

    // -------- Arrhenius ratio > 12× is unphysical for the +10K rule --------

    @Test
    void arrheniusEighteenTimesFlagged() {
        // Synthetic — far above any realistic +10K ratio; would indicate a simulator bug.
        String findings = "Arrhenius (T=200 K): Ea=10kJ→1.04× Ea=50kJ→2.13× Ea=130kJ→18.5×.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Arrhenius Rule Validity", findings);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0)).contains("Arrhenius Rule Validity");
    }

    @Test
    void arrheniusUnderTwelveTimesPassesUnflagged() {
        // P-a328-31's correct 6.7× at T=342K, Ea=189kJ — well under the bound.
        String findings = "Arrhenius (T=342 K): Ea=10kJ→1.04× Ea=50kJ→1.65× Ea=189kJ→6.7×.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Arrhenius Rule Validity", findings);
        assertThat(reasons).isEmpty();
    }

    // -------- Tolerance / null safety / domain isolation --------

    @Test
    void nullArgumentsReturnEmptyReasonList() {
        assertThat(MagnitudeRangeValidator.validate(null, "T", "f")).isEmpty();
        assertThat(MagnitudeRangeValidator.validate("D", null, "f")).isEmpty();
        assertThat(MagnitudeRangeValidator.validate("D", "T", (String) null)).isEmpty();
    }

    @Test
    void unknownDomainTopicReturnsEmpty() {
        // No bound registered for this pair — pass through with no violation.
        String findings = "Some findings with 99999 MPa value that would otherwise trip a bound.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATHEMATICS", "Prime Constellation Search", findings);
        assertThat(reasons).isEmpty();
    }

    @Test
    void domainAndTopicMustBothMatch() {
        // Composite-failure bound is on MATERIALS:Composite Failure Mode. Other
        // topic, even with matching MPa stresses, must not trigger that bound.
        String findings = "Some materials topic emitting 12020 MPa.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Thermal Stress Safety Window", findings);
        assertThat(reasons).isEmpty();
    }

    // ---- Phase 7 F2: Map-shaped overload concatenates blocks ----

    @Test
    void mapOverloadConcatenatesBlocksBeforeChecking() {
        // The offending magnitude appears in computation 2; the Map overload
        // must concatenate before applying the regex so the bound fires.
        java.util.Map<Integer, String> blocks = new java.util.LinkedHashMap<>();
        blocks.put(1, "First block — modest 800 MPa stress, all fine.");
        blocks.put(2, "Second block: Vf=90%→12020MPa, an unphysical value.");
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", blocks);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0)).contains("12020");
    }

    @Test
    void mapOverloadOnSingleEntryMapBehavesIdenticallyToStringPath() {
        // Flag-off bit-identical: a single-entry map carrying the legacy prose
        // produces the same reason list as the legacy String overload.
        String prose = "Composite failure (...): Vf=80%→9620MPa Vf=90%→12020MPa.";
        java.util.Map<Integer, String> singleton = java.util.Map.of(1, prose);
        List<String> stringReasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", prose);
        List<String> mapReasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", singleton);
        assertThat(mapReasons).isEqualTo(stringReasons);
    }

    // ---- Phase 6 D1.5 (L2): corpus-fallback magnitude bound ----

    @Test
    void corpusFallbackFiresWhenRegistryHasNoBoundForTopic() {
        // Binary Sphere Packing is a registry stub — no bound. The corpus carries
        // structured magnitude_max=0.83 / unit=η / regex=peak η=([0-9.]+).
        String findings = "Binary peak η=0.95 at r=0.41 — well above the Hudson 1949 envelope.";
        LiteratureSearchService corpus = loadCorpus();
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Binary Sphere Packing", findings, corpus);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0))
                .contains("Binary Sphere Packing")
                .contains("corpus bound")
                .contains("Hudson");
    }

    @Test
    void corpusFallbackPassesWhenValueWithinBound() {
        String findings = "Binary peak η=0.79 at r=0.41 — matches Hudson 1949.";
        LiteratureSearchService corpus = loadCorpus();
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Binary Sphere Packing", findings, corpus);
        assertThat(reasons).isEmpty();
    }

    @Test
    void corpusFallbackSkippedWhenRegistryAlreadyHasBound() {
        // Composite Failure Mode has a registry bound (3000 MPa). The corpus-aware
        // overload must use the registry hit and ignore the corpus, even if the
        // corpus would have produced a different reason string.
        String prose = "Composite failure: Vf=90%→12020MPa.";
        LiteratureSearchService corpus = loadCorpus();
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", prose, corpus);
        assertThat(reasons).hasSize(1);
        assertThat(reasons.get(0))
                .contains("3000")
                .doesNotContain("corpus bound");
    }

    @Test
    void corpusFallbackNullSafe() {
        assertThat(MagnitudeRangeValidator.validate(
                null, "T", "f", loadCorpus())).isEmpty();
        assertThat(MagnitudeRangeValidator.validate(
                "MATHEMATICS", "Prime Constellation Search", "no bound configured", null))
                .isEmpty();
    }

    private static LiteratureSearchService loadCorpus() {
        LiteratureSearchService svc = new LiteratureSearchService(
                new org.springframework.core.io.ClassPathResource("literature-corpus.yml"));
        svc.loadCorpus();
        return svc;
    }

    // -------- Phase 18 §18.1 (P8c) — tool-call trace scan --------

    @Test
    void p593_hiddenInTrace_compositeStressFlaggedFromTrace() {
        // P-a328-593 pattern: call result reported "→5790MPa" (composite
        // longitudinal stress, 5.79 GPa, above the 3 GPa bound), but the
        // Findings prose dropped the number. validateTrace must catch it.
        // Real P-593 call_4 was a single-shot summary, no result= marker.
        // The validator must scan the full string in that case.
        var inv = new com.emergentterrain.llm.ToolInvocation(
                "run_param_sweep",
                "{\"param1\":\"785\",\"param2\":\"1.2\"}",
                "Best: T=785 K → P_c=52 atm → longitudinal σ=5790MPa",
                0,
                "call_4");
        List<String> reasons = MagnitudeRangeValidator.validateTrace(
                "MATERIALS", "Composite Failure Mode", List.of(inv));
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0))
                .startsWith("[trace]")
                .contains("Composite Failure Mode")
                .contains("5790")
                .contains("call_4");
    }

    @Test
    void traceScan_skipsRejectedTool() {
        // Sandbox-rejected calls contain arbitrary tokens (e.g. the rule
        // text) that can FP-match a numeric regex. validateTrace must skip
        // them entirely.
        var rejected = new com.emergentterrain.llm.ToolInvocation(
                "run_sandboxed_code",
                "{\"code\":\"…\"}",
                "REJECTED by safety review: parse failed line 17 (12345)",
                1,
                "call_2");
        assertThat(MagnitudeRangeValidator.validateTrace(
                "MATERIALS", "Composite Failure Mode", List.of(rejected)))
                .isEmpty();
    }

    @Test
    void traceScan_skipsToolError() {
        var err = new com.emergentterrain.llm.ToolInvocation(
                "run_param_sweep",
                "{}",
                "Tool error: connection refused (9999)",
                0,
                "call_1");
        assertThat(MagnitudeRangeValidator.validateTrace(
                "MATERIALS", "Composite Failure Mode", List.of(err)))
                .isEmpty();
    }

    @Test
    void traceScan_intermediateSweepValuesNotScannedWhenResultMarkerPresent() {
        // Parameter-sweep papers legitimately report intermediate values above
        // the bound (e.g. Vf=90% → 12020 MPa in the grid scan), with the
        // *terminal* result being a different scalar. When the tool result
        // includes "result=<scalar>", validateTrace only scans from result=
        // forward — the intermediate values stay invisible to this check.
        var inv = new com.emergentterrain.llm.ToolInvocation(
                "run_param_sweep",
                "{}",
                "Grid: Vf=90%→12020MPa Vf=10%→500MPa\nresult=1850MPa",
                0,
                "call_3");
        // 1850 MPa is below the 3000 MPa bound — must not flag.
        assertThat(MagnitudeRangeValidator.validateTrace(
                "MATERIALS", "Composite Failure Mode", List.of(inv)))
                .isEmpty();
    }

    @Test
    void traceScan_topicWithoutBoundReturnsEmpty() {
        var inv = new com.emergentterrain.llm.ToolInvocation(
                "run_predicate_scan", "{}", "result=99999", 0, "call_1");
        // MATHEMATICS / Prime Constellation Search has no magnitude bound
        // in the default registry — must return empty regardless of value.
        assertThat(MagnitudeRangeValidator.validateTrace(
                "MATHEMATICS", "Prime Constellation Search", List.of(inv)))
                .isEmpty();
    }

    @Test
    void traceScan_nullSafe() {
        assertThat(MagnitudeRangeValidator.validateTrace(
                null, "T", List.of())).isEmpty();
        assertThat(MagnitudeRangeValidator.validateTrace(
                "MATERIALS", "Composite Failure Mode", null)).isEmpty();
        assertThat(MagnitudeRangeValidator.validateTrace(
                "MATERIALS", "Composite Failure Mode", List.of())).isEmpty();
    }

    @Test
    void traceScan_proseValidatorStillWorksIndependently() {
        // Regression: the new trace overload must not change prose-validator
        // behaviour. The original P-a328-2 fixture still flags.
        String findings = "Composite failure (...): Vf=80%→9620MPa Vf=90%→12020MPa.";
        List<String> reasons = MagnitudeRangeValidator.validate(
                "MATERIALS", "Composite Failure Mode", findings);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons.get(0)).contains("9620");
    }
}
