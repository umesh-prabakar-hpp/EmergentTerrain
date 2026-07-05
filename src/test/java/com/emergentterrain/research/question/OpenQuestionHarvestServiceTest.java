package com.emergentterrain.research.question;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 33 T1 — pure parse/derivation tests for the open-question harvester. */
class OpenQuestionHarvestServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void parsesProseWrappedJson_capsAndCoercesKinds() {
        String raw = """
                Here are the questions:
                [{"question":"Does k(x) continue to rise toward the Cramér value 1.0 beyond x=1.5e6?","kind":"RATE"},
                 {"question":"Is the inferred k stable under changes in window size?","kind":"SENSITIVITY"},
                 {"question":"Something with a bogus kind","kind":"WILD"},
                 {"question":"A fourth question past the cap","kind":"SCOPE"}]
                Hope that helps!""";
        List<PaperOpenQuestion> qs = OpenQuestionHarvestService.parse(raw, "P-1", "max_prime_gap", OM);
        assertThat(qs).hasSize(3);   // capped at MAX_QUESTIONS_PER_PAPER
        assertThat(qs.get(0).kind()).isEqualTo("RATE");
        assertThat(qs.get(0).question()).contains("Cramér value 1.0");
        assertThat(qs.get(2).kind()).isEqualTo("OTHER");   // WILD coerced
        assertThat(qs).allMatch(q -> q.family().equals("max_prime_gap"));
    }

    @Test
    void degenerateInputs_harvestNothing() {
        assertThat(OpenQuestionHarvestService.parse(null, "P-1", "f", OM)).isEmpty();
        assertThat(OpenQuestionHarvestService.parse("no json at all", "P-1", "f", OM)).isEmpty();
        assertThat(OpenQuestionHarvestService.parse("[]", "P-1", "f", OM)).isEmpty();
        assertThat(OpenQuestionHarvestService.parse("[{\"question\":\"\"}]", "P-1", "f", OM)).isEmpty();
        assertThat(OpenQuestionHarvestService.parse("[not valid json]", "P-1", "f", OM)).isEmpty();
    }

    @Test
    void familyDerivation_conjectureTopicsUseTheLhs() {
        assertThat(OpenQuestionHarvestService.familyOf("conjecture/max_prime_gap"))
                .isEqualTo("max_prime_gap");
        assertThat(OpenQuestionHarvestService.familyOf("Pendulum Breakdown"))
                .isEqualTo("Pendulum Breakdown");
        assertThat(OpenQuestionHarvestService.familyOf(null)).isEqualTo("unknown");
    }
}
