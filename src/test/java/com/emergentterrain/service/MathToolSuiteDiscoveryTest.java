package com.emergentterrain.service;

import com.emergentterrain.research.discovery.CandidateGenerator;
import com.emergentterrain.research.discovery.CandidateValidator;
import com.emergentterrain.research.discovery.CandidateDiscoveryService;
import com.emergentterrain.research.discovery.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 fork 2b Phase 4 — the {@code discover_mertens_value} tool: advertised
 * only when discovery is wired AND runtimeExecEnabled, and its handler formats
 * accept / disabled / out-of-range outcomes. No live model or sidecar (the
 * discovery service is backed by fakes).
 */
class MathToolSuiteDiscoveryTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static MathToolSuite suiteWith(CandidateDiscoveryService svc) {
        MathToolSuite suite = new MathToolSuite(null, OM);
        suite.setDiscoveryService(svc);
        return suite;
    }

    private static CandidateDiscoveryService svc(boolean enabled, ValidationResult result) {
        CandidateGenerator gen = req -> "candidate(x)=0";
        CandidateValidator val = (code, task) -> result;
        return new CandidateDiscoveryService(enabled, 3, gen, val);
    }

    private static ValidationResult accept(long value) {
        return new ValidationResult("ok", "accept", Map.of(), Map.of(), null, value, "");
    }

    @Test
    void toolAdvertisedOnlyWhenWiredAndEnabled() {
        assertThat(suiteWith(svc(true, accept(1L))).toolsJson(true, false))
                .contains("discover_mertens_value");
        // disabled flag → omitted
        assertThat(suiteWith(svc(false, accept(1L))).toolsJson(true, false))
                .doesNotContain("discover_mertens_value");
        // not wired at all → omitted
        assertThat(new MathToolSuite(null, OM).toolsJson(true, false))
                .doesNotContain("discover_mertens_value");
    }

    @Test
    void acceptOutputCarriesValueAndHonestFraming() throws Exception {
        MathToolSuite suite = suiteWith(svc(true, accept(62_366L)));
        String out = suite.discoverMertensValue(OM.readTree("{\"x\":1000000000000,\"rationale\":\"r\"}"));
        assertThat(out)
                .contains("M(1000000000000) = 62366")
                .contains("COMPUTED")
                .contains("NOT an established result");
    }

    @Test
    void rejectsTargetWithinSieveRange() throws Exception {
        MathToolSuite suite = suiteWith(svc(true, accept(1L)));
        String out = suite.discoverMertensValue(OM.readTree("{\"x\":1000000,\"rationale\":\"r\"}"));
        assertThat(out).contains("must be >");
    }

    @Test
    void disabledHandlerReturnsDisabledLine() throws Exception {
        MathToolSuite suite = suiteWith(svc(false, accept(1L)));
        String out = suite.discoverMertensValue(OM.readTree("{\"x\":1000000000,\"rationale\":\"r\"}"));
        assertThat(out).contains("disabled");
    }

    // ── discover_divisor_value — divisor-domain sibling, default ON ──────────────

    @Test
    void divisorToolAdvertisedWhenWiredEnabledAndGateOn() {
        // wired + runtimeExec on + divisor gate default ON → advertised
        assertThat(suiteWith(svc(true, accept(1L))).toolsJson(true, false))
                .contains("discover_divisor_value");
        // runtimeExec off → omitted (discoveryAvailable() short-circuits)
        assertThat(suiteWith(svc(false, accept(1L))).toolsJson(true, false))
                .doesNotContain("discover_divisor_value");
        // not wired at all → omitted
        assertThat(new MathToolSuite(null, OM).toolsJson(true, false))
                .doesNotContain("discover_divisor_value");
    }

    @Test
    void divisorToolOmittedWhenItsGateOff() {
        CandidateDiscoveryService svc = svc(true, accept(1L));
        svc.setDivisorToolEnabled(false);
        String tools = suiteWith(svc).toolsJson(true, false);
        // divisor tool held back, but Mertens (runtimeExec on) still advertised
        assertThat(tools).doesNotContain("discover_divisor_value");
        assertThat(tools).contains("discover_mertens_value");
    }

    @Test
    void divisorAcceptOutputCarriesValueAndHonestFraming() throws Exception {
        MathToolSuite suite = suiteWith(svc(true, accept(13218484L)));
        String out = suite.discoverDivisorValue(OM.readTree("{\"x\":1000000000,\"rationale\":\"r\"}"));
        assertThat(out)
                .contains("D(1000000000) = 13218484")
                .contains("COMPUTED")
                .contains("D(10^7)=162725364")
                .contains("NOT an established result");
    }

    @Test
    void divisorRejectsTargetWithinSieveRange() throws Exception {
        MathToolSuite suite = suiteWith(svc(true, accept(1L)));
        String out = suite.discoverDivisorValue(OM.readTree("{\"x\":1000000,\"rationale\":\"r\"}"));
        assertThat(out).contains("must be >");
    }

    @Test
    void divisorDisabledHandlerReturnsDisabledLine() throws Exception {
        MathToolSuite suite = suiteWith(svc(false, accept(1L)));
        String out = suite.discoverDivisorValue(OM.readTree("{\"x\":1000000000,\"rationale\":\"r\"}"));
        assertThat(out).contains("disabled");
    }

    // ── discover_prime_value — prime-counting sibling (Path A), default ON ──────────

    @Test
    void primeToolAdvertisedWhenWiredEnabledAndGateOn() {
        assertThat(suiteWith(svc(true, accept(1L))).toolsJson(true, false))
                .contains("discover_prime_value");
        assertThat(suiteWith(svc(false, accept(1L))).toolsJson(true, false))
                .doesNotContain("discover_prime_value");
        assertThat(new MathToolSuite(null, OM).toolsJson(true, false))
                .doesNotContain("discover_prime_value");
    }

    @Test
    void primeToolOmittedWhenItsGateOff() {
        CandidateDiscoveryService svc = svc(true, accept(1L));
        svc.setPrimeToolEnabled(false);
        String tools = suiteWith(svc).toolsJson(true, false);
        assertThat(tools).doesNotContain("discover_prime_value");
        assertThat(tools).contains("discover_mertens_value");   // others unaffected
    }

    @Test
    void primeAcceptOutputCarriesValueAndHonestFraming() throws Exception {
        MathToolSuite suite = suiteWith(svc(true, accept(37607912018L)));
        String out = suite.discoverPrimeValue(OM.readTree("{\"x\":1000000000000,\"rationale\":\"r\"}"));
        assertThat(out)
                .contains("pi(1000000000000) = 37607912018")
                .contains("COMPUTED")
                .contains("pi(10^7)=664579")
                .contains("NOT an established result");
    }

    @Test
    void primeRejectsTargetWithinSieveRange() throws Exception {
        MathToolSuite suite = suiteWith(svc(true, accept(1L)));
        String out = suite.discoverPrimeValue(OM.readTree("{\"x\":1000000,\"rationale\":\"r\"}"));
        assertThat(out).contains("must be >");
    }

    @Test
    void primeDisabledHandlerReturnsDisabledLine() throws Exception {
        MathToolSuite suite = suiteWith(svc(false, accept(1L)));
        String out = suite.discoverPrimeValue(OM.readTree("{\"x\":1000000000,\"rationale\":\"r\"}"));
        assertThat(out).contains("disabled");
    }

    // ── propose_mechanism (30.7 P3) — gating (no conjecture verifier wired here) ──────────

    @Test
    void mechanismToolNotAdvertisedWithoutVerifier() {
        MathToolSuite suite = suiteWith(svc(true, accept(1L)));
        suite.setMechanismProposalEnabled(true);   // flag on, but no conjecture verifier wired
        assertThat(suite.toolsJson(true, false)).doesNotContain("propose_mechanism");
    }

    @Test
    void mechanismHandlerDisabledWithoutVerifier() throws Exception {
        MathToolSuite suite = suiteWith(svc(true, accept(1L)));
        suite.setMechanismProposalEnabled(true);
        String out = suite.proposeMechanism(OM.readTree(
                "{\"dsl\":\"predict(y) := square(x)\",\"window_start\":1000,\"window_size\":1000}"));
        assertThat(out).contains("disabled");
    }
}
