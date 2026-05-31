package com.emergentterrain.research.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 fork 2b Phase 3 — unit tests for the thin adapters' pure parts:
 * LLM code-fence extraction and the /discover/validate JSON mapping. No live
 * model or sidecar.
 */
class DiscoveryAdaptersTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void extractsFencedJulia() {
        String reply = "Here is the method:\n```julia\nfunction candidate(x)\n    0\nend\n```\nDone.";
        assertThat(LlmCandidateGenerator.extractJulia(reply))
                .isEqualTo("function candidate(x)\n    0\nend");
    }

    @Test
    void extractsUnlabeledFenceAndBareCode() {
        assertThat(LlmCandidateGenerator.extractJulia("```\ncandidate(x)=0\n```")).isEqualTo("candidate(x)=0");
        assertThat(LlmCandidateGenerator.extractJulia("candidate(x)=0")).isEqualTo("candidate(x)=0");
        assertThat(LlmCandidateGenerator.extractJulia(null)).isEmpty();
    }

    @Test
    void parsesAcceptEnvelope() throws Exception {
        String json = """
            {"ok":true,"status":"ok","verdict":"accept",
             "values":{"2":0,"100000000":1928},"oracle":{"2":0},
             "target":100000000,"targetValue":1928}""";
        ValidationResult r = SidecarCandidateValidator.parse(OM.readTree(json));
        assertThat(r.accepted()).isTrue();
        assertThat(r.targetValue()).isEqualTo(1928L);
        assertThat(r.values()).containsEntry(100_000_000L, 1928L);
        assertThat(r.firstFail()).isNull();
    }

    @Test
    void parsesRejectEnvelopeWithFirstFail() throws Exception {
        String json = """
            {"ok":true,"status":"ok","verdict":"reject",
             "values":{"2":2},"oracle":{"2":0},"firstFail":[2,2,0]}""";
        ValidationResult r = SidecarCandidateValidator.parse(OM.readTree(json));
        assertThat(r.accepted()).isFalse();
        assertThat(r.firstFail()).isNotNull();
        assertThat(r.firstFail().k()).isEqualTo(2L);
        assertThat(r.firstFail().got()).isEqualTo(2L);
        assertThat(r.firstFail().want()).isEqualTo(0L);
    }

    @Test
    void parsesNaAndDisabledEnvelopes() throws Exception {
        // candidate ran but failed the sandbox → na with a status
        ValidationResult na = SidecarCandidateValidator.parse(OM.readTree(
                "{\"ok\":true,\"status\":\"ast_reject\",\"verdict\":\"na\",\"detail\":\"open\"}"));
        assertThat(na.verdict()).isEqualTo("na");
        assertThat(na.status()).isEqualTo("ast_reject");

        // endpoint disabled (403): ok=false, no verdict → na, status carries the error code
        ValidationResult disabled = SidecarCandidateValidator.parse(OM.readTree(
                "{\"ok\":false,\"error\":\"disabled\",\"explanation\":\"set ET_DISCOVER_EXEC=1\"}"));
        assertThat(disabled.verdict()).isEqualTo("na");
        assertThat(disabled.status()).isEqualTo("disabled");
    }
}
